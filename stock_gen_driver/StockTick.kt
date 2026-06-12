// StockTick.kt — Kotlin helper to read records from /dev/stockN
//
// Data class mirrors the kernel struct stock_tick (32 bytes, little-endian).
// Usage:
//   val reader = StockDevReader("/dev/stock0")
//   reader.use { r ->
//       repeat(100) { println(r.readTick()) }
//   }

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class StockTick(
    val seq: Long,
    val timestampNs: Long,
    val priceUDollar: Long,   // price * 1_000_000
    val volume: Int,
    val devIdx: Int,
) {
    val priceDouble: Double get() = priceUDollar / 1_000_000.0

    override fun toString() =
        "StockTick(dev=$devIdx seq=$seq price=\$${String.format("%.6f", priceDouble)} " +
        "vol=$volume ts=${timestampNs}ns)"

    companion object {
        const val SIZE = 32

        fun parse(bytes: ByteArray, offset: Int = 0): StockTick {
            require(bytes.size - offset >= SIZE) { "Buffer too small" }
            val buf = ByteBuffer.wrap(bytes, offset, SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            return StockTick(
                seq           = buf.getLong(),
                timestampNs   = buf.getLong(),
                priceUDollar  = buf.getLong(),
                volume        = buf.getInt(),
                devIdx        = buf.getInt(),
            )
        }
    }
}

class StockDevReader(private val path: String) : AutoCloseable {
    private val fis = FileInputStream(path)
    private val buf = ByteArray(StockTick.SIZE * 16)  // read up to 16 ticks at once

    /** Blocks until the next tick arrives (or throws on IO error / interrupt). */
    fun readTick(): StockTick {
        val n = fis.read(buf, 0, StockTick.SIZE)
        check(n == StockTick.SIZE) { "Short read: $n bytes" }
        return StockTick.parse(buf)
    }

    /** Read up to [max] ticks without blocking after the first one. */
    fun readBatch(max: Int = 16): List<StockTick> {
        val n = fis.read(buf, 0, StockTick.SIZE * max.coerceAtMost(16))
        if (n <= 0) return emptyList()
        return (0 until n / StockTick.SIZE).map { i ->
            StockTick.parse(buf, i * StockTick.SIZE)
        }
    }

    override fun close() = fis.close()
}
