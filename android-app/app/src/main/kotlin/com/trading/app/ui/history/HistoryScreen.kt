package com.trading.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trading.app.data.Order
import com.trading.app.data.Trade
import com.trading.app.ui.orderStatusLabel
import com.trading.app.ui.orderTypeLabel
import com.trading.app.ui.sideLabel
import com.trading.app.ui.theme.LossRed
import com.trading.app.ui.theme.ProfitGreen

@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0; viewModel.refresh() }, text = { Text("Ордера") })
            Tab(selected = tab == 1, onClick = { tab = 1; viewModel.refresh() }, text = { Text("Сделки") })
        }

        state.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }

        if (tab == 0) {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.orders, key = { it.id }) { order ->
                    OrderRow(order, onCancel = { viewModel.cancelOrder(order.id) })
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.trades, key = { it.id }) { TradeRow(it) }
            }
        }
    }
}

@Composable
private fun OrderRow(order: Order, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "${sideLabel(order.side)} ${order.qty} ${order.symbol}",
                    fontWeight = FontWeight.Bold,
                    color = if (order.side == "BUY") ProfitGreen else LossRed,
                )
                Text(
                    "${orderTypeLabel(order.type)}${order.price?.let { " @ $it" } ?: ""} · ${orderStatusLabel(order.status)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (order.status == "NEW") {
                TextButton(onClick = onCancel) { Text("Отменить") }
            }
        }
    }
}

@Composable
private fun TradeRow(trade: Trade) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "${sideLabel(trade.side)} ${trade.qty} ${trade.symbol}",
                    fontWeight = FontWeight.Bold,
                    color = if (trade.side == "BUY") ProfitGreen else LossRed,
                )
                Text(trade.executedAt.take(19), style = MaterialTheme.typography.bodySmall)
            }
            Text("@ ${trade.price}", style = MaterialTheme.typography.titleMedium)
        }
    }
}
