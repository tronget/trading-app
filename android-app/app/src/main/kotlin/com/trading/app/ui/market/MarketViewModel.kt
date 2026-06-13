package com.trading.app.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trading.app.data.MarketRepository
import com.trading.app.data.QuoteItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MarketUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val quotes: List<QuoteItem> = emptyList(),
)

/** Watchlist: снапшот по REST + живые обновления цен по WebSocket. */
class MarketViewModel(private val market: MarketRepository) : ViewModel() {
    private val _state = MutableStateFlow(MarketUiState())
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val quotes = market.quotes()
                _state.update { it.copy(loading = false, quotes = quotes) }
                startStream(quotes.map { it.symbol })
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    private fun startStream(symbols: List<String>) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            market.ticker(symbols).collect { tick ->
                _state.update { current ->
                    current.copy(quotes = current.quotes.map { quote ->
                        if (quote.symbol == tick.symbol) {
                            quote.copy(last = tick.price, volume = tick.volume.toLong())
                        } else quote
                    })
                }
            }
        }
    }
}
