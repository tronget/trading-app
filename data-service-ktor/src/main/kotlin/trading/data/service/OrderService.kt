package trading.data.service

import trading.data.db.AccountRepo
import trading.data.db.Database
import trading.data.db.InstrumentRepo
import trading.data.db.OrderRepo
import trading.data.db.PositionRepo
import trading.data.db.TradeRepo
import trading.data.model.OrderDto
import trading.data.model.OrderStatus
import trading.data.model.OrderType
import trading.data.model.PlaceOrderRequest
import trading.data.model.Side
import trading.data.redis.PriceCache
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.sql.Connection

class ValidationException(message: String) : Exception(message)
class NotFoundException(message: String) : Exception(message)

/** Средняя цена позиции после докупки. Чистая функция — покрыта юнит-тестами. */
fun newAveragePrice(
    oldQty: BigDecimal,
    oldAvg: BigDecimal,
    addQty: BigDecimal,
    addPrice: BigDecimal,
): BigDecimal {
    val totalQty = oldQty + addQty
    return (oldQty * oldAvg + addQty * addPrice)
        .divide(totalQty, MathContext.DECIMAL64)
        .setScale(6, RoundingMode.HALF_UP)
}

/** Пересечена ли лимитная заявка текущей ценой (BUY ждёт цену ниже, SELL — выше). */
fun limitCrossed(side: Side, limitPrice: BigDecimal, tickPrice: BigDecimal): Boolean = when (side) {
    Side.BUY -> tickPrice <= limitPrice
    Side.SELL -> tickPrice >= limitPrice
}

/**
 * Размещение и исполнение ордеров. Market исполняется сразу по последней цене
 * из Redis-кэша; limit сохраняется и матчится воркером по потоку тиков.
 * Все изменения баланса/позиции/сделки — в одной транзакции.
 */
class OrderService(private val db: Database, private val prices: PriceCache) {

    suspend fun place(request: PlaceOrderRequest): OrderDto {
        validate(request)
        return db.tx("place_order") { c ->
            request.clientOrderId?.let { clientOrderId ->
                OrderRepo.findByClientOrderId(c, clientOrderId)?.let { return@tx it } // идемпотентность
            }

            if (!InstrumentRepo.exists(c, request.symbol)) {
                throw ValidationException("unknown symbol ${request.symbol}")
            }

            val order = OrderRepo.insert(
                c, request.userId, request.symbol, request.side, request.type,
                request.qty, request.price, request.clientOrderId,
            )

            when (order.type) {
                OrderType.MARKET -> {
                    val price = prices.lastPrice(order.symbol)
                        ?: run {
                            OrderRepo.updateStatus(c, order.id, OrderStatus.REJECTED)
                            return@tx order.copy(status = OrderStatus.REJECTED)
                        }
                    executeFill(c, order, price)
                    OrderRepo.findById(c, order.id)!!
                }
                OrderType.LIMIT -> order
            }
        }
    }

    suspend fun cancel(orderId: Long, userId: Long): OrderDto = db.tx("cancel_order") { c ->
        val order = OrderRepo.findById(c, orderId) ?: throw NotFoundException("order $orderId not found")
        if (order.userId != userId) throw NotFoundException("order $orderId not found")

        val active = OrderRepo.lockActive(c, orderId)
            ?: throw ValidationException("order $orderId is not active (status ${order.status})")
        OrderRepo.updateStatus(c, active.id, OrderStatus.CANCELLED)
        active.copy(status = OrderStatus.CANCELLED)
    }

    suspend fun list(userId: Long, status: OrderStatus?, limit: Int): List<OrderDto> =
        db.tx("list_orders") { c -> OrderRepo.listByUser(c, userId, status, limit) }

    suspend fun get(orderId: Long): OrderDto =
        db.tx("get_order") { c -> OrderRepo.findById(c, orderId) }
            ?: throw NotFoundException("order $orderId not found")

    /**
     * Исполняет ордер целиком по цене [price] внутри текущей транзакции:
     * проверка средств/позиции → сделка → пересчёт позиции и баланса.
     * При нехватке средств ордер помечается REJECTED.
     */
    fun executeFill(c: Connection, order: OrderDto, price: BigDecimal) {
        val cost = (order.qty * price).setScale(2, RoundingMode.HALF_UP)
        val cash = AccountRepo.lockCash(c, order.userId)
            ?: throw ValidationException("user ${order.userId} has no account")

        when (order.side) {
            Side.BUY -> {
                if (cash < cost) {
                    OrderRepo.updateStatus(c, order.id, OrderStatus.REJECTED)
                    return
                }
                val position = PositionRepo.lock(c, order.userId, order.symbol)
                val newQty = (position?.qty ?: BigDecimal.ZERO) + order.qty
                val newAvg = if (position == null || position.qty.signum() == 0) {
                    price
                } else {
                    newAveragePrice(position.qty, position.avgPrice, order.qty, price)
                }
                PositionRepo.upsert(c, order.userId, order.symbol, newQty, newAvg)
                AccountRepo.addCash(c, order.userId, cost.negate())
            }
            Side.SELL -> {
                val position = PositionRepo.lock(c, order.userId, order.symbol)
                if (position == null || position.qty < order.qty) {
                    OrderRepo.updateStatus(c, order.id, OrderStatus.REJECTED)
                    return
                }
                PositionRepo.upsert(c, order.userId, order.symbol, position.qty - order.qty, position.avgPrice)
                AccountRepo.addCash(c, order.userId, cost)
            }
        }

        TradeRepo.insert(c, order.id, order.userId, order.symbol, order.side, order.qty, price)
        OrderRepo.updateStatus(c, order.id, OrderStatus.FILLED, filledQty = order.qty)
    }

    private fun validate(request: PlaceOrderRequest) {
        if (request.qty.signum() <= 0) throw ValidationException("qty must be positive")
        when (request.type) {
            OrderType.LIMIT -> {
                val price = request.price ?: throw ValidationException("limit order requires price")
                if (price.signum() <= 0) throw ValidationException("price must be positive")
            }
            OrderType.MARKET -> {
                if (request.price != null) throw ValidationException("market order must not have price")
            }
        }
    }
}
