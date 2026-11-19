package app.pwhs.blockads.ui.home.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.data.entities.HourlyStat

object MiniBarChartDefaults {
    val TotalQueriesSample = listOf(0.22f, 0.35f, 0.28f, 0.45f, 0.65f, 0.90f, 0.55f, 0.35f, 0.50f, 0.30f)
    val BlockedQueriesSample = listOf(0.18f, 0.26f, 0.40f, 0.60f, 0.48f, 0.80f, 0.38f, 0.30f, 0.52f, 0.32f)

    fun createSparklineData(
        hourlyStats: List<HourlyStat>,
        isBlocked: Boolean,
        barCount: Int = 10
    ): List<Float> {
        val sample = if (isBlocked) BlockedQueriesSample else TotalQueriesSample
        if (hourlyStats.isEmpty()) {
            return sample
        }
        val recentStats = hourlyStats.takeLast(barCount)
        val counts = recentStats.map { if (isBlocked) it.blocked else it.total }
        val max = counts.maxOrNull() ?: 0
        if (max <= 0) {
            return sample
        }

        val nonZeroCount = counts.count { it > 0 }
        val paddedCounts = if (counts.size < barCount) {
            List(barCount - counts.size) { 0 } + counts
        } else {
            counts
        }

        return paddedCounts.mapIndexed { index, count ->
            if (nonZeroCount <= 2 && count == 0) {
                sample[index % sample.size] * 0.55f
            } else if (count == 0) {
                0.18f
            } else {
                (count.toFloat() / max).coerceIn(0.25f, 1f)
            }
        }
    }
}

@Composable
fun MiniBarChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.5.dp,
    barSpacing: Dp = 2.dp,
    barAlpha: Float = 0.35f
) {
    if (data.isEmpty()) return

    val animatedFractions = data.mapIndexed { index, fraction ->
        key(index) {
            animateFloatAsState(
                targetValue = fraction.coerceIn(0.15f, 1f),
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "mini_bar_$index"
            )
        }
    }

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val count = animatedFractions.size
        val widthPx = barWidth.toPx()
        val spacingPx = barSpacing.toPx()
        val totalWidth = count * widthPx + (count - 1) * spacingPx
        val startX = (size.width - totalWidth).coerceAtLeast(0f)
        val cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)

        animatedFractions.forEachIndexed { index, fractionState ->
            val barHeight = size.height * fractionState.value.coerceIn(0.15f, 1f)
            val left = startX + index * (widthPx + spacingPx)
            val top = size.height - barHeight
            drawRoundRect(
                color = color.copy(alpha = barAlpha),
                topLeft = Offset(left, top),
                size = Size(widthPx, barHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}
