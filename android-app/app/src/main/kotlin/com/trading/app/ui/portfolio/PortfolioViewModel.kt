package com.trading.app.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trading.app.data.Portfolio
import com.trading.app.data.TradingRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PortfolioUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val portfolio: Portfolio? = null,
)

class PortfolioViewModel(private val trading: TradingRepository) : ViewModel() {
    private val _state = MutableStateFlow(PortfolioUiState())
    val state: StateFlow<PortfolioUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refreshOnce()
                delay(5000) // P&L пересчитывается на сервере по последним ценам
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        try {
            val portfolio = trading.portfolio()
            _state.update { it.copy(loading = false, error = null, portfolio = portfolio) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Ошибка сети") }
        }
    }
}
