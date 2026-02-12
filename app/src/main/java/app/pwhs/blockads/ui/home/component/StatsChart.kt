package app.pwhs.blockads.ui.home.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary

@Composable
fun StatsChart(
    stats: List<app.pwhs.blockads.data.HourlyStat>,
    modifier: Modifier = Modifier
) {
    val totalColor = AccentBlue.copy(alpha = 0.4f)
    val blockedColor = DangerRed.copy(alpha = 0.7f)
    val labelColor = TextSecondary

    Canvas(modifier = modifier) {
        if (stats.isEmpty()) return@Canvas

        val barCount = stats.size
        val maxVal = (stats.maxOfOrNull { it.total } ?: 1).coerceAtLeast(1)
        val barWidth = size.width / barCount.toFloat()
        val chartHeight = size.height - 16f // leave room for bottom labels

        stats.forEachIndexed { index, stat ->
            val x = index * barWidth

            // Total bar
            val totalBarHeight = (stat.total.toFloat() / maxVal) * chartHeight
            drawRoundRect(
                color = totalColor,
                topLeft = androidx.compose.ui.geometry.Offset(x + 1f, chartHeight - totalBarHeight),
                size = androidx.compose.ui.geometry.Size(barWidth - 2f, totalBarHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )

            // Blocked bar (overlaid)
            val blockedBarHeight = (stat.blocked.toFloat() / maxVal) * chartHeight
            drawRoundRect(
                color = blockedColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x + 1f,
                    chartHeight - blockedBarHeight
                ),
                size = androidx.compose.ui.geometry.Size(barWidth - 2f, blockedBarHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
        }
    }
}