package trading.gateway.quotes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import trading.gateway.auth.TokenService
import trading.gateway.model.ErrorResponse
import trading.gateway.model.Instrument
import trading.gateway.model.QuoteItem
import trading.gateway.model.SubscribeCommand
import trading.gateway.trading.DataServiceClient
import trading.gateway.trading.passthrough
import java.util.concurrent.ConcurrentHashMap

/**
 * Котировки: снапшот списка инструментов, свечи из ClickHouse и
 * WebSocket-стрим тиков с подпиской по символам.
 */
fun Route.quoteRoutes(
    hub: QuoteHub,
    clickhouse: ClickhouseClient,
    dataService: DataServiceClient,
    tokens: TokenService,
) {
    val json = Json { ignoreUnknownKeys = true }

    get("/quotes") {
        val (status, body) = dataService.get("/internal/instruments").passthrough()
        if (status != HttpStatusCode.OK.value) {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("data service unavailable"))
            return@get
        }
        val instruments = json.decodeFromString<List<Instrument>>(body)
        val dayOpen = clickhouse.dayOpenPrices()

        call.respond(instruments.map { instrument ->
            val tick = hub.lastTick(instrument.symbol)
            val open = dayOpen[instrument.symbol]
            val changePct = if (tick != null && open != null && open > 0) {
                Math.round((tick.price - open) / open * 100_00) / 100.0
            } else null
            QuoteItem(
                symbol = instrument.symbol,
                name = instrument.name,
                last = tick?.price,
                changePct = changePct,
                volume = tick?.volume?.toLong(),
            )
        })
    }

    get("/quotes/{symbol}/history") {
        val symbol = call.parameters["symbol"]!!.uppercase()
        val interval = call.request.queryParameters["interval"] ?: "1m"
        if (interval !in SUPPORTED_INTERVALS) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse("interval must be one of ${SUPPORTED_INTERVALS.keys}"),
            )
            return@get
        }
        val now = System.currentTimeMillis()
        val to = call.request.queryParameters["to"]?.toLongOrNull() ?: now
        val from = call.request.queryParameters["from"]?.toLongOrNull() ?: (to - 24 * 3600 * 1000)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 500

        call.respond(clickhouse.candles(symbol, interval, from, to, limit))
    }

    webSocket("/ws") {
        val token = call.request.queryParameters["token"]
            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token == null || tokens.verifyAccess(token) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }

        val subscribed = ConcurrentHashMap.newKeySet<String>()

        val sender = launch {
            hub.ticks.collect { tick ->
                if (tick.symbol in subscribed) {
                    outgoing.send(Frame.Text(json.encodeToString(trading.gateway.model.Tick.serializer(), tick)))
                }
            }
        }

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val command = runCatching { json.decodeFromString<SubscribeCommand>(frame.readText()) }
                    .getOrNull() ?: continue
                val symbols = command.symbols.map { it.uppercase() }
                when (command.action) {
                    "subscribe" -> {
                        subscribed.addAll(symbols)
                        // сразу отправляем снапшот последних цен по новым символам
                        for (symbol in symbols) {
                            hub.lastTick(symbol)?.let {
                                outgoing.send(Frame.Text(json.encodeToString(trading.gateway.model.Tick.serializer(), it)))
                            }
                        }
                    }
                    "unsubscribe" -> subscribed.removeAll(symbols.toSet())
                }
            }
        } finally {
            sender.cancel()
        }
    }
}
