package com.trading.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.trading.app.data.Candle
import com.trading.app.data.MarketRepository
import com.trading.app.data.Order
import com.trading.app.data.Portfolio
import com.trading.app.data.PlaceOrderRequest
import com.trading.app.data.Position
import com.trading.app.data.QuoteItem
import com.trading.app.data.Tick
import com.trading.app.data.Trade
import com.trading.app.data.TradingRepository
import com.trading.app.ui.history.HistoryScreen
import com.trading.app.ui.history.HistoryViewModel
import com.trading.app.ui.instrument.InstrumentScreen
import com.trading.app.ui.instrument.InstrumentViewModel
import com.trading.app.ui.market.MarketScreen
import com.trading.app.ui.market.MarketViewModel
import com.trading.app.ui.portfolio.PortfolioScreen
import com.trading.app.ui.portfolio.PortfolioViewModel
import com.trading.app.ui.theme.TradingTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/**
 * Скриншот-тесты Compose-экранов: рендер без эмулятора (Robolectric Native
 * Graphics + Roborazzi). PNG пишутся в build/outputs/roborazzi и идут
 * в отчёт по проекту.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val fakeMarket = object : MarketRepository {
        override suspend fun quotes() = listOf(
            QuoteItem("SBER", "Sberbank", last = 99.84, changePct = 0.42, volume = 158),
            QuoteItem("GAZP", "Gazprom", last = 200.12, changePct = -0.31, volume = 991),
            QuoteItem("AAPL", "Apple Inc.", last = 299.95, changePct = 0.08, volume = 580),
            QuoteItem("BTC", "Bitcoin", last = 400.51, changePct = 1.25, volume = 585),
        )

        override suspend fun history(symbol: String, interval: String, limit: Int): List<Candle> =
            (0 until 60).map { i ->
                val base = 100 + 3 * sin(i / 6.0) + i * 0.05
                Candle("2026-06-12 12:${"%02d".format(i)}:00", base, base + 0.4, base - 0.4, base + 0.1, 1000L + i)
            }

        override fun ticker(symbols: List<String>): Flow<Tick> = emptyFlow()
    }

    private val fakeTrading = object : TradingRepository {
        override suspend fun portfolio() = Portfolio(
            userId = 1, currency = "USD", cash = "999001.49",
            positions = listOf(
                Position("SBER", "10", "99.825", last = "99.84", pnl = "0.15"),
                Position("BTC", "2", "401.20", last = "400.51", pnl = "-1.38"),
            ),
            totalValue = "1000800.91",
        )

        override suspend fun orders() = listOf(
            Order(3, 1, "SBER", "BUY", "LIMIT", "1", "95", "0", "NEW", null,
                "2026-06-12 12:00:00", "2026-06-12 12:00:00"),
            Order(2, 1, "SBER", "SELL", "LIMIT", "5", "99", "5", "FILLED", null,
                "2026-06-12 11:59:00", "2026-06-12 11:59:30"),
            Order(1, 1, "SBER", "BUY", "MARKET", "10", null, "10", "FILLED", "co-1",
                "2026-06-12 11:58:00", "2026-06-12 11:58:00"),
        )

        override suspend fun trades() = listOf(
            Trade(2, 2, 1, "SBER", "SELL", "5", "99.73", "0", "2026-06-12 11:59:30"),
            Trade(1, 1, 1, "SBER", "BUY", "10", "99.83", "0", "2026-06-12 11:58:00"),
        )

        override suspend fun placeOrder(request: PlaceOrderRequest) = orders().first()
        override suspend fun cancelOrder(id: Long) = orders().first()
    }

    @Test
    fun market() {
        compose.setContent {
            TradingTheme { MarketScreen(MarketViewModel(fakeMarket)) {} }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/market.png")
    }

    @Test
    fun instrument() {
        compose.setContent {
            TradingTheme {
                InstrumentScreen(InstrumentViewModel(fakeMarket, fakeTrading, "SBER"))
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/instrument.png")
    }

    @Test
    fun portfolio() {
        compose.setContent {
            TradingTheme { PortfolioScreen(PortfolioViewModel(fakeTrading)) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/portfolio.png")
    }

    @Test
    fun history() {
        compose.setContent {
            TradingTheme { HistoryScreen(HistoryViewModel(fakeTrading)) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/history.png")
    }
}
