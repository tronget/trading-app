package trading.gateway.quotes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import trading.gateway.Config
import trading.gateway.model.Candle
import java.util.concurrent.atomic.AtomicReference

/** Интервалы свечей: имя в API → выражение группировки ClickHouse. */
val SUPPORTED_INTERVALS: Map<String, String> = mapOf(
    "1m" to "toStartOfMinute(ts)",
    "5m" to "toStartOfInterval(ts, INTERVAL 5 MINUTE)",
    "15m" to "toStartOfInterval(ts, INTERVAL 15 MINUTE)",
    "1h" to "toStartOfHour(ts)",
    "1d" to "toStartOfDay(ts)",
)

@Serializable
private data class CandleRow(
    val t: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

@Serializable
private data class DayOpenRow(val symbol: String, val open: Double)

/**
 * Чтение истории котировок из ClickHouse по HTTP-интерфейсу.
 * Значения подставляются только через param_* — без конкатенации SQL.
 */
class ClickhouseClient(private val config: Config) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) { expectSuccess = false }

    private suspend fun query(sql: String, params: Map<String, String>): List<String> {
        val response = http.get(config.clickhouseUrl) {
            parameter("database", config.clickhouseDb)
            parameter("query", "$sql FORMAT JSONEachRow")
            params.forEach { (name, value) -> parameter("param_$name", value) }
            headers {
                append("X-ClickHouse-User", config.clickhouseUser)
                append("X-ClickHouse-Key", config.clickhousePassword)
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw RuntimeException("clickhouse query failed (${response.status.value}): ${body.take(200)}")
        }
        return body.lineSequence().filter { it.isNotBlank() }.toList()
    }

    /** Свечи по символу из сырых тиков с группировкой на лету. */
    suspend fun candles(symbol: String, interval: String, fromTs: Long, toTs: Long, limit: Int): List<Candle> {
        val bucket = SUPPORTED_INTERVALS[interval]
            ?: throw IllegalArgumentException("interval must be one of ${SUPPORTED_INTERVALS.keys}")
        val sql = """
            SELECT toString($bucket) AS t,
                   toFloat64(argMin(price, ts)) AS open,
                   toFloat64(max(price)) AS high,
                   toFloat64(min(price)) AS low,
                   toFloat64(argMax(price, ts)) AS close,
                   sum(toUInt64(volume)) AS volume
            FROM ticks
            WHERE symbol = {sym:String}
              AND ts >= fromUnixTimestamp64Milli({fromMs:Int64})
              AND ts < fromUnixTimestamp64Milli({toMs:Int64})
            GROUP BY t ORDER BY t DESC LIMIT {lim:UInt32}
        """.trimIndent()
        val rows = query(sql, mapOf(
            "sym" to symbol,
            "fromMs" to fromTs.toString(),
            "toMs" to toTs.toString(),
            "lim" to limit.toString(),
        ))
        return rows.map { json.decodeFromString<CandleRow>(it) }
            .map { Candle(it.t, it.open, it.high, it.low, it.close, it.volume) }
            .asReversed()
    }

    // Цена открытия дня по всем символам — для changePct в /quotes (кэш 30 секунд).
    private val dayOpenCache = AtomicReference<Pair<Long, Map<String, Double>>>(0L to emptyMap())

    suspend fun dayOpenPrices(): Map<String, Double> {
        val (cachedAt, cached) = dayOpenCache.get()
        val now = System.currentTimeMillis()
        if (now - cachedAt < 30_000) return cached

        val sql = """
            SELECT symbol, toFloat64(argMin(price, ts)) AS open
            FROM ticks
            WHERE ts >= toStartOfDay(now())
            GROUP BY symbol
        """.trimIndent()
        val result = runCatching {
            query(sql, emptyMap()).associate {
                val row = json.decodeFromString<DayOpenRow>(it)
                row.symbol to row.open
            }
        }.getOrElse { return cached }

        dayOpenCache.set(now to result)
        return result
    }
}
