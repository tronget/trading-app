package trading.gateway.quotes

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import trading.gateway.model.Tick
import java.util.concurrent.ConcurrentHashMap

/**
 * Одна подписка на Redis quotes:ticks на весь Gateway; тики раздаются
 * WebSocket-клиентам через SharedFlow (fan-out в памяти, медленные клиенты
 * теряют старые тики, а не тормозят остальных).
 */
class QuoteHub(redisHost: String, redisPort: Int) {
    private val log = LoggerFactory.getLogger(QuoteHub::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = RedisClient.create(RedisURI.create(redisHost, redisPort))
    private val commands = client.connect().sync()

    private val lastTicks = ConcurrentHashMap<String, Tick>()

    private val tickFlow = MutableSharedFlow<Tick>(
        extraBufferCapacity = 8192,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ticks: SharedFlow<Tick> = tickFlow

    fun start() {
        val pubSub = client.connectPubSub()
        pubSub.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                val tick = runCatching { json.decodeFromString<Tick>(message) }.getOrNull() ?: return
                lastTicks[tick.symbol] = tick
                tickFlow.tryEmit(tick)
            }
        })
        pubSub.sync().subscribe("quotes:ticks")
        log.info("subscribed to quotes:ticks")
    }

    /** Последний тик символа: из памяти, при промахе — из quote:last:<symbol>. */
    fun lastTick(symbol: String): Tick? {
        lastTicks[symbol]?.let { return it }
        val raw = runCatching { commands.get("quote:last:$symbol") }.getOrNull() ?: return null
        val tick = runCatching { json.decodeFromString<Tick>(raw) }.getOrNull() ?: return null
        lastTicks[symbol] = tick
        return tick
    }

    fun close() {
        client.shutdown()
    }
}
