package trading.data.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** Сообщение тика из Redis-канала quotes:ticks (контракт сервиса котировок #6). */
@Serializable
data class TickMessage(
    val symbol: String,
    val price: Double,
    val ts: Long,
    val volume: Int = 0,
    val seq: Long = 0,
)

/**
 * Кэш последних цен: подписан на quotes:ticks, при промахе читает quote:last:<symbol>.
 * Поток тиков ретранслируется в [ticks] — его слушает матчинг-воркер.
 */
class PriceCache(redisHost: String, redisPort: Int) {
    private val log = LoggerFactory.getLogger(PriceCache::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = RedisClient.create(RedisURI.create(redisHost, redisPort))
    private val commands = client.connect().sync()

    private val lastPrices = ConcurrentHashMap<String, BigDecimal>()

    private val tickFlow = MutableSharedFlow<TickMessage>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ticks: SharedFlow<TickMessage> = tickFlow

    fun start() {
        val pubSub = client.connectPubSub()
        pubSub.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                val tick = runCatching { json.decodeFromString<TickMessage>(message) }
                    .getOrElse {
                        log.warn("bad tick message: {}", message)
                        return
                    }
                lastPrices[tick.symbol] = BigDecimal(tick.price.toString())
                tickFlow.tryEmit(tick)
            }
        })
        pubSub.sync().subscribe("quotes:ticks")
        log.info("subscribed to quotes:ticks at redis")
    }

    /** Последняя цена символа: из памяти, при промахе — из quote:last:<symbol>. */
    fun lastPrice(symbol: String): BigDecimal? {
        lastPrices[symbol]?.let { return it }
        val raw = runCatching { commands.get("quote:last:$symbol") }.getOrNull() ?: return null
        val tick = runCatching { json.decodeFromString<TickMessage>(raw) }.getOrNull() ?: return null
        val price = BigDecimal(tick.price.toString())
        lastPrices[symbol] = price
        return price
    }

    fun close() {
        client.shutdown()
    }
}
