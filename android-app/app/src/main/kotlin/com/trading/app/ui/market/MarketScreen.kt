package com.trading.app.ui.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.trading.app.data.QuoteItem
import com.trading.app.ui.theme.LossRed
import com.trading.app.ui.theme.ProfitGreen
import java.util.Locale

@Composable
fun MarketScreen(viewModel: MarketViewModel, onOpenInstrument: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.loading && state.quotes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error != null && state.quotes.isEmpty() -> ErrorRetry(state.error!!) { viewModel.refresh() }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.quotes, key = { it.symbol }) { quote ->
                QuoteRow(quote) { onOpenInstrument(quote.symbol) }
            }
        }
    }
}

@Composable
private fun QuoteRow(quote: QuoteItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(quote.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(quote.name, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    quote.last?.let { String.format(Locale.US, "%.2f $", it) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                quote.changePct?.let { pct ->
                    Text(
                        String.format(Locale.US, "%+.2f%%", pct),
                        color = if (pct >= 0) ProfitGreen else LossRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Повторить") }
    }
}
