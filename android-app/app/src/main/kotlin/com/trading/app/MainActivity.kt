package com.trading.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trading.app.di.AppContainer
import com.trading.app.ui.auth.AuthScreen
import com.trading.app.ui.auth.AuthViewModel
import com.trading.app.ui.history.HistoryScreen
import com.trading.app.ui.history.HistoryViewModel
import com.trading.app.ui.instrument.InstrumentScreen
import com.trading.app.ui.instrument.InstrumentViewModel
import com.trading.app.ui.market.MarketScreen
import com.trading.app.ui.market.MarketViewModel
import com.trading.app.ui.portfolio.PortfolioScreen
import com.trading.app.ui.portfolio.PortfolioViewModel
import com.trading.app.ui.theme.TradingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TradingApp).container
        setContent {
            TradingTheme {
                AppRoot(container)
            }
        }
    }
}

/** Фабрика для ViewModel с зависимостями из контейнера. */
private class Factory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

private data class BottomTab(val route: String, val title: String, val icon: @Composable () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(container: AppContainer) {
    val authViewModel: AuthViewModel = viewModel(factory = Factory { AuthViewModel(container.authRepository) })
    val authState by authViewModel.state.collectAsStateWithLifecycle()

    if (!authState.loggedIn) {
        AuthScreen(authViewModel)
        return
    }

    val navController = rememberNavController()
    val tabs = listOf(
        BottomTab("market", "Рынок") { Icon(Icons.Filled.ShowChart, null) },
        BottomTab("portfolio", "Портфель") { Icon(Icons.Filled.PieChart, null) },
        BottomTab("history", "История") { Icon(Icons.AutoMirrored.Filled.List, null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trading") },
                actions = {
                    IconButton(onClick = { authViewModel.logout() }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Выйти")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo("market")
                                launchSingleTop = true
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "market",
            modifier = Modifier.padding(padding),
        ) {
            composable("market") {
                val vm: MarketViewModel = viewModel(factory = Factory { MarketViewModel(container.marketRepository) })
                MarketScreen(vm) { symbol -> navController.navigate("instrument/$symbol") }
            }
            composable("portfolio") {
                val vm: PortfolioViewModel = viewModel(factory = Factory { PortfolioViewModel(container.tradingRepository) })
                PortfolioScreen(vm)
            }
            composable("history") {
                val vm: HistoryViewModel = viewModel(factory = Factory { HistoryViewModel(container.tradingRepository) })
                HistoryScreen(vm)
            }
            composable("instrument/{symbol}") { entry ->
                val symbol = entry.arguments?.getString("symbol") ?: return@composable
                val vm: InstrumentViewModel = viewModel(
                    key = "instrument-$symbol",
                    factory = Factory {
                        InstrumentViewModel(container.marketRepository, container.tradingRepository, symbol)
                    },
                )
                InstrumentScreen(vm)
            }
        }
    }
}
