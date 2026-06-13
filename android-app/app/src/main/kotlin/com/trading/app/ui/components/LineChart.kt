package com.trading.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Линейный график цен закрытия на Canvas (без сторонних библиотек). */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        if (values.size < 2) return@Canvas
        val minValue = values.min()
        val maxValue = values.max()
        val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0

        val stepX = size.width / (values.size - 1)
        fun pointAt(index: Int): Offset {
            val normalized = (values[index] - minValue) / range
            return Offset(
                x = index * stepX,
                y = size.height * (1f - normalized.toFloat()) * 0.92f + size.height * 0.04f,
            )
        }

        val linePath = Path()
        val fillPath = Path()
        for (i in values.indices) {
            val point = pointAt(i)
            if (i == 0) {
                linePath.moveTo(point.x, point.y)
                fillPath.moveTo(point.x, size.height)
                fillPath.lineTo(point.x, point.y)
            } else {
                linePath.lineTo(point.x, point.y)
                fillPath.lineTo(point.x, point.y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent),
            ),
        )
        drawPath(linePath, color = lineColor, style = Stroke(width = 4f))
    }
}
