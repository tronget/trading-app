package com.trading.app.ui.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trading.app.data.Position
import com.trading.app.ui.market.ErrorRetry
import com.trading.app.ui.theme.LossRed
import com.trading.app.ui.theme.ProfitGreen
import java.math.BigDecimal

@Composable
fun PortfolioScreen(viewModel: PortfolioViewModel, onOpenInstrument: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val portfolio = state.portfolio

    when {
        state.loading && portfolio == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error != null && portfolio == null -> ErrorRetry(state.error!!) { viewModel.refresh() }
        portfolio != null -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Стоимость портфеля", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${portfolio.totalValue} ${portfolio.currency}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val totalPnl = portfolio.positions
                            .mapNotNull { it.pnl?.toBigDecimalOrNull() }
                            .fold(BigDecimal.ZERO) { acc, p -> acc + p }
                        if (portfolio.positions.any { it.pnl != null }) {
                            val negative = totalPnl.signum() < 0
                            val sign = if (negative) "" else "+"
                            Text(
                                "Прибыль/убыток: $sign${totalPnl.toPlainString()} ${portfolio.currency}",
                                color = if (negative) LossRed else ProfitGreen,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Свободно: ${portfolio.cash} ${portfolio.currency}")
                    }
                }
            }
            if (portfolio.positions.isEmpty()) {
                item { Text("Позиций пока нет", Modifier.padding(16.dp)) }
            } else {
                items(portfolio.positions, key = { it.symbol }) { position ->
                    PositionRow(position) { onOpenInstrument(position.symbol) }
                }
            }
        }
    }
}

@Composable
private fun PositionRow(position: Position, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(position.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${position.qty} шт × ${position.avgPrice}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(position.last ?: "—", style = MaterialTheme.typography.titleMedium)
                position.pnl?.let { pnl ->
                    val negative = pnl.startsWith("-")
                    Text(
                        if (negative) pnl else "+$pnl",
                        color = if (negative) LossRed else ProfitGreen,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
