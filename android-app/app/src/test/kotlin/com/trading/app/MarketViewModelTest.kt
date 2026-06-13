package com.trading.app

import com.trading.app.data.Candle
import com.trading.app.data.MarketRepository
import com.trading.app.data.QuoteItem
import com.trading.app.data.Tick
import com.trading.app.ui.market.MarketViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private class FakeMarketRepository : MarketRepository {
    val tickFlow = MutableSharedFlow<Tick>(extraBufferCapacity = 16)
    var failQuotes = false

    override suspend fun quotes(): List<QuoteItem> {
        if (failQuotes) throw RuntimeException("network down")
        return listOf(
            QuoteItem("SBER", "Sberbank", last = 100.0, changePct = 0.5),
            QuoteItem("GAZP", "Gazprom", last = 200.0, changePct = -0.2),
        )
    }

    override suspend fun history(symbol: String, interval: String, limit: Int): List<Candle> = emptyList()

    override fun ticker(symbols: List<String>): Flow<Tick> = tickFlow
}

class MarketViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads quotes on start`() = runTest(dispatcher) {
        val viewModel = MarketViewModel(FakeMarketRepository())
        dispatcher.scheduler.runCurrent()

        val state = viewModel.state.value
        assertEquals(false, state.loading)
        assertEquals(listOf("SBER", "GAZP"), state.quotes.map { it.symbol })
    }

    @Test
    fun `tick updates matching quote price`() = runTest(dispatcher) {
        val repo = FakeMarketRepository()
        val viewModel = MarketViewModel(repo)
        dispatcher.scheduler.runCurrent()

        repo.tickFlow.tryEmit(Tick(symbol = "SBER", price = 105.5, ts = 1, volume = 7))
        dispatcher.scheduler.runCurrent()

        val sber = viewModel.state.value.quotes.first { it.symbol == "SBER" }
        assertEquals(105.5, sber.last)
        val gazp = viewModel.state.value.quotes.first { it.symbol == "GAZP" }
        assertEquals(200.0, gazp.last) // другой символ не тронут
    }

    @Test
    fun `error from repository lands in state`() = runTest(dispatcher) {
        val repo = FakeMarketRepository().apply { failQuotes = true }
        val viewModel = MarketViewModel(repo)
        dispatcher.scheduler.runCurrent()

        val state = viewModel.state.value
        assertEquals(false, state.loading)
        assertNotNull(state.error)
    }
}
