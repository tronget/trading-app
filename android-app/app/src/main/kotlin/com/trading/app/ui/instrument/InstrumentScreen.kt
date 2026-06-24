package com.trading.app.ui.instrument

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trading.app.ui.components.LineChart
import com.trading.app.ui.theme.LossRed
import com.trading.app.ui.theme.ProfitGreen
import java.math.BigDecimal
import java.util.Locale

// Серверная колонка — NUMERIC(18, 6): значение меньше 1e12, до 6 знаков после точки.
private val MAX_AMOUNT = BigDecimal("999999999999.999999")
private val MAX_QTY = BigDecimal("999999999999")

/** Ошибка для поля «Количество»: только целое положительное число акций. */
private fun qtyError(input: String): String? {
    if (input.isBlank()) return null // пустое поле — блокируем кнопки, без красного текста
    val value = input.toBigDecimalOrNull() ?: return "Введите число"
    return when {
        value.signum() <= 0 -> "Должно быть больше нуля"
        value.stripTrailingZeros().scale() > 0 -> "Только целое число акций"
        value > MAX_QTY -> "Слишком большое количество"
        else -> null
    }
}

/** Ошибка для поля «Цена»: положительное число, до 6 знаков после точки. */
private fun priceError(input: String): String? {
    if (input.isBlank()) return null
    val value = input.toBigDecimalOrNull() ?: return "Введите число"
    return when {
        value.signum() <= 0 -> "Должно быть больше нуля"
        value.scale() > 6 -> "Не более 6 знаков после точки"
        value > MAX_AMOUNT -> "Слишком большое значение"
        else -> null
    }
}

@Composable
fun InstrumentScreen(viewModel: InstrumentViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var qty by rememberSaveable { mutableStateOf("1") }
    var limitPrice by rememberSaveable { mutableStateOf("") }
    var orderType by rememberSaveable { mutableStateOf("MARKET") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                state.lastPrice?.let { String.format(Locale.US, "%.2f $", it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        } else if (state.candles.isNotEmpty()) {
            LineChart(values = state.candles.map { it.close })
        } else {
            Text(state.error ?: "Нет данных за период")
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1m", "5m", "15m", "1h").forEach { interval ->
                FilterChip(
                    selected = state.interval == interval,
                    onClick = { viewModel.load(interval) },
                    label = { Text(interval) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Новый ордер", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = orderType == "MARKET",
                onClick = { orderType = "MARKET" },
                label = { Text("Рыночный") },
            )
            FilterChip(
                selected = orderType == "LIMIT",
                onClick = { orderType = "LIMIT" },
                label = { Text("Лимитный") },
            )
        }
        Spacer(Modifier.height(8.dp))

        val qtyErr = qtyError(qty)
        val qtyValue = qty.toLongOrNull() ?: 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { qty = (qtyValue - 1).coerceAtLeast(1).toString() },
                enabled = qtyValue > 1,
            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            OutlinedTextField(
                value = qty,
                onValueChange = { new -> qty = new.filter { it.isDigit() } },
                label = { Text("Количество (шт)") },
                singleLine = true,
                isError = qtyErr != null,
                supportingText = qtyErr?.let { { Text(it, color = LossRed) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            FilledTonalIconButton(
                onClick = { qty = (qtyValue + 1).toString() },
                enabled = qtyValue < MAX_QTY.toLong(),
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
        val priceErr = if (orderType == "LIMIT") priceError(limitPrice) else null
        if (orderType == "LIMIT") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = limitPrice,
                onValueChange = { limitPrice = it },
                label = { Text("Цена") },
                singleLine = true,
                isError = priceErr != null,
                supportingText = priceErr?.let { { Text(it, color = LossRed) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        val qtyValid = qtyErr == null && qty.isNotBlank()
        val priceValid = orderType == "MARKET" || (priceErr == null && limitPrice.isNotBlank())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.placeOrder("BUY", orderType, qty, limitPrice) },
                enabled = !state.placing && qtyValid && priceValid,
                colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen),
                modifier = Modifier.weight(1f),
            ) { Text("Купить") }
            Button(
                onClick = { viewModel.placeOrder("SELL", orderType, qty, limitPrice) },
                enabled = !state.placing && qtyValid && priceValid,
                colors = ButtonDefaults.buttonColors(containerColor = LossRed),
                modifier = Modifier.weight(1f),
            ) { Text("Продать") }
        }

        state.orderResult?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = if (state.orderFailed) LossRed else ProfitGreen,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
