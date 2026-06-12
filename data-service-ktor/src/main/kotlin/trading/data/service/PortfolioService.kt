package trading.data.service

import trading.data.db.AccountRepo
import trading.data.db.Database
import trading.data.db.PositionRepo
import trading.data.model.PortfolioDto
import trading.data.redis.PriceCache
import java.math.BigDecimal
import java.math.RoundingMode

/** Портфель: позиции с текущей ценой и P&L, свободный кэш, общая стоимость. */
class PortfolioService(private val db: Database, private val prices: PriceCache) {

    suspend fun portfolio(userId: Long): PortfolioDto = db.tx("portfolio") { c ->
        val cash = AccountRepo.cash(c, userId)
            ?: throw NotFoundException("user $userId has no account")

        val positions = PositionRepo.listByUser(c, userId).map { position ->
            val last = prices.lastPrice(position.symbol)
            val pnl = last?.let {
                ((it - position.avgPrice) * position.qty).setScale(2, RoundingMode.HALF_UP)
            }
            position.copy(last = last, pnl = pnl)
        }

        val positionsValue = positions.fold(BigDecimal.ZERO) { acc, position ->
            acc + (position.last ?: position.avgPrice) * position.qty
        }

        PortfolioDto(
            userId = userId,
            currency = "USD",
            cash = cash,
            positions = positions,
            totalValue = (cash + positionsValue).setScale(2, RoundingMode.HALF_UP),
        )
    }
}
