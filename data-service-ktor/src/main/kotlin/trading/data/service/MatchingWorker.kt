package trading.data.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import trading.data.db.Database
import trading.data.db.OrderRepo
import trading.data.redis.PriceCache
import java.math.BigDecimal

/**
 * Фоновый матчинг лимитных заявок: слушает поток тиков из Redis и исполняет
 * пересечённые лимитки по цене тика. Работает в отдельной корутине,
 * не блокируя HTTP-обработчики.
 */
class MatchingWorker(
    private val db: Database,
    private val prices: PriceCache,
    private val orders: OrderService,
) {
    private val log = LoggerFactory.getLogger(MatchingWorker::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        prices.ticks.collect { tick ->
            try {
                matchSymbol(tick.symbol, BigDecimal(tick.price.toString()))
            } catch (e: Exception) {
                log.error("matching failed for {}: {}", tick.symbol, e.message)
            }
        }
    }

    private suspend fun matchSymbol(symbol: String, tickPrice: BigDecimal) {
        val executed = db.tx("match_limit_orders") { c ->
            val crossed = OrderRepo.lockCrossedLimitOrders(c, symbol, tickPrice)
            for (order in crossed) {
                orders.executeFill(c, order, tickPrice)
            }
            crossed.size
        }
        if (executed > 0) {
            log.info("matched {} limit order(s) for {} at {}", executed, symbol, tickPrice)
        }
    }
}
