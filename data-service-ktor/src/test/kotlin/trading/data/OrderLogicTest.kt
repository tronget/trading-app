package trading.data

import kotlinx.serialization.json.Json
import trading.data.model.OrderType
import trading.data.model.PlaceOrderRequest
import trading.data.model.Side
import trading.data.redis.TickMessage
import trading.data.service.limitCrossed
import trading.data.service.newAveragePrice
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderLogicTest {

    @Test
    fun `average price after buying more`() {
        // 10 шт по 100 + 10 шт по 200 → средняя 150
        val avg = newAveragePrice(
            oldQty = BigDecimal("10"), oldAvg = BigDecimal("100"),
            addQty = BigDecimal("10"), addPrice = BigDecimal("200"),
        )
        assertEquals(0, BigDecimal("150").compareTo(avg))
    }

    @Test
    fun `average price keeps precision`() {
        val avg = newAveragePrice(
            oldQty = BigDecimal("3"), oldAvg = BigDecimal("100"),
            addQty = BigDecimal("1"), addPrice = BigDecimal("200"),
        )
        assertEquals(0, BigDecimal("125").compareTo(avg))
    }

    @Test
    fun `buy limit crosses when price drops to limit`() {
        val limit = BigDecimal("100")
        assertTrue(limitCrossed(Side.BUY, limit, BigDecimal("99.5")))
        assertTrue(limitCrossed(Side.BUY, limit, BigDecimal("100")))
        assertFalse(limitCrossed(Side.BUY, limit, BigDecimal("100.01")))
    }

    @Test
    fun `sell limit crosses when price rises to limit`() {
        val limit = BigDecimal("100")
        assertTrue(limitCrossed(Side.SELL, limit, BigDecimal("100.5")))
        assertTrue(limitCrossed(Side.SELL, limit, BigDecimal("100")))
        assertFalse(limitCrossed(Side.SELL, limit, BigDecimal("99.99")))
    }

    @Test
    fun `place order request round-trips with string money`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """{"userId":1,"symbol":"SBER","side":"BUY","type":"LIMIT","qty":"2.5","price":"305.123456"}"""
        val request = json.decodeFromString<PlaceOrderRequest>(raw)
        assertEquals(OrderType.LIMIT, request.type)
        assertEquals(0, BigDecimal("2.5").compareTo(request.qty))
        assertEquals(0, BigDecimal("305.123456").compareTo(request.price))

        val encoded = json.encodeToString(PlaceOrderRequest.serializer(), request)
        assertTrue("\"2.5\"" in encoded)
    }

    @Test
    fun `tick message parses quotes-go payload`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """{"symbol":"SBER","price":305.5,"ts":1781297920237,"volume":158,"seq":25}"""
        val tick = json.decodeFromString<TickMessage>(raw)
        assertEquals("SBER", tick.symbol)
        assertEquals(305.5, tick.price)
    }
}
