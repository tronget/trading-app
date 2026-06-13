package com.trading.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ProfitGreen = Color(0xFF1E8E3E)
val LossRed = Color(0xFFD93025)

private val lightScheme = lightColorScheme(
    primary = Color(0xFF0B5345),
    secondary = Color(0xFF148F77),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    secondary = Color(0xFF80CBC4),
)

@Composable
fun TradingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme,
        content = content,
    )
}
