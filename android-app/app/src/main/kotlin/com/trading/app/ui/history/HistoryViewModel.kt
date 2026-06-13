package com.trading.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trading.app.data.Order
import com.trading.app.data.Trade
import com.trading.app.data.TradingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val orders: List<Order> = emptyList(),
    val trades: List<Trade> = emptyList(),
)

class HistoryViewModel(private val trading: TradingRepository) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val orders = trading.orders()
                val trades = trading.trades()
                _state.update { it.copy(loading = false, orders = orders, trades = trades) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    fun cancelOrder(id: Long) {
        viewModelScope.launch {
            try {
                trading.cancelOrder(id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Не удалось отменить") }
            }
        }
    }
}
