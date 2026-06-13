package com.trading.app.ui.instrument

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trading.app.data.Candle
import com.trading.app.data.MarketRepository
import com.trading.app.data.PlaceOrderRequest
import com.trading.app.data.TradingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class InstrumentUiState(
    val symbol: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val candles: List<Candle> = emptyList(),
    val interval: String = "1m",
    val lastPrice: Double? = null,
    val orderResult: String? = null,
    val placing: Boolean = false,
)

/** Карточка инструмента: график + живая цена + размещение ордера. */
class InstrumentViewModel(
    private val market: MarketRepository,
    private val trading: TradingRepository,
    private val symbol: String,
) : ViewModel() {
    private val _state = MutableStateFlow(InstrumentUiState(symbol = symbol))
    val state: StateFlow<InstrumentUiState> = _state.asStateFlow()

    init {
        load("1m")
        viewModelScope.launch {
            market.ticker(listOf(symbol)).collect { tick ->
                _state.update { it.copy(lastPrice = tick.price) }
            }
        }
    }

    fun load(interval: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, interval = interval) }
            try {
                val candles = market.history(symbol, interval, 120)
                _state.update { it.copy(loading = false, candles = candles) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    fun placeOrder(side: String, type: String, qty: String, price: String?) {
        if (_state.value.placing) return
        viewModelScope.launch {
            _state.update { it.copy(placing = true, orderResult = null) }
            try {
                val order = trading.placeOrder(
                    PlaceOrderRequest(
                        symbol = symbol,
                        side = side,
                        type = type,
                        qty = qty,
                        price = price?.takeIf { type == "LIMIT" },
                        clientOrderId = UUID.randomUUID().toString(),
                    )
                )
                _state.update { it.copy(placing = false, orderResult = "Ордер #${order.id}: ${order.status}") }
            } catch (e: Exception) {
                _state.update { it.copy(placing = false, orderResult = "Ошибка: ${e.message}") }
            }
        }
    }

    fun clearOrderResult() {
        _state.update { it.copy(orderResult = null) }
    }
}
