package com.trading.app.ui.instrument

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trading.app.data.ApiException
import com.trading.app.data.Candle
import com.trading.app.data.MarketRepository
import com.trading.app.data.Order
import com.trading.app.data.PlaceOrderRequest
import com.trading.app.data.TradingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

data class InstrumentUiState(
    val symbol: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val candles: List<Candle> = emptyList(),
    val interval: String = "1m",
    val lastPrice: Double? = null,
    val orderResult: String? = null,
    /** true — сообщение об ошибке (красное), false — успех (зелёное). */
    val orderFailed: Boolean = false,
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
            _state.update { it.copy(placing = true, orderResult = null, orderFailed = false) }
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
                val (message, failed) = describeOrder(order, side)
                _state.update { it.copy(placing = false, orderResult = message, orderFailed = failed) }
            } catch (e: ApiException) {
                _state.update { it.copy(placing = false, orderResult = friendlyApiError(e), orderFailed = true) }
            } catch (e: IOException) {
                _state.update {
                    it.copy(
                        placing = false,
                        orderResult = "Нет связи с сервером. Проверьте подключение и попробуйте снова.",
                        orderFailed = true,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(placing = false, orderResult = "Не удалось разместить ордер. Попробуйте ещё раз.", orderFailed = true)
                }
            }
        }
    }

    fun clearOrderResult() {
        _state.update { it.copy(orderResult = null, orderFailed = false) }
    }

    /** Человеческое описание ответа сервера. Возвращает (текст, это_ошибка). */
    private fun describeOrder(order: Order, side: String): Pair<String, Boolean> {
        val buy = side == "BUY"
        val amount = trimQty(order.qty)
        return when (order.status.uppercase()) {
            "FILLED" -> (if (buy) "✓ Куплено $amount шт $symbol" else "✓ Продано $amount шт $symbol") to false
            "REJECTED" ->
                (if (buy) "Недостаточно средств для покупки $amount шт"
                else "Недостаточно акций $symbol для продажи") to true
            "NEW", "PARTIALLY_FILLED" ->
                "Лимитный ордер на ${if (buy) "покупку" else "продажу"} $amount шт размещён" to false
            "CANCELLED" -> "Ордер отменён" to true
            else -> "Ордер #${order.id}: ${order.status}" to false
        }
    }

    /** Переводит серверные сообщения об ошибке на понятный русский. */
    private fun friendlyApiError(e: ApiException): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("qty must be positive") -> "Количество должно быть больше нуля"
            raw.contains("limit order requires price") -> "Для лимитного ордера укажите цену"
            raw.contains("price must be positive") -> "Цена должна быть больше нуля"
            raw.contains("unknown symbol") -> "Неизвестный инструмент"
            raw.contains("no account") -> "Торговый счёт не найден"
            e.status == 401 -> "Сессия истекла. Войдите снова."
            e.status >= 500 -> "Сервер временно недоступен. Попробуйте позже."
            else -> "Не удалось разместить ордер: $raw"
        }
    }

    private fun trimQty(s: String): String =
        s.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: s
}
