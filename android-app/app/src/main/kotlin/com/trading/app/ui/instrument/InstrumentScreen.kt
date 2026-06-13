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
import java.util.Locale

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
                label = { Text("Market") },
            )
            FilterChip(
                selected = orderType == "LIMIT",
                onClick = { orderType = "LIMIT" },
                label = { Text("Limit") },
            )
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = qty,
            onValueChange = { qty = it },
            label = { Text("Количество") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        if (orderType == "LIMIT") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = limitPrice,
                onValueChange = { limitPrice = it },
                label = { Text("Цена") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        val qtyValid = qty.toDoubleOrNull()?.let { it > 0 } == true
        val priceValid = orderType == "MARKET" || limitPrice.toDoubleOrNull()?.let { it > 0 } == true
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
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
