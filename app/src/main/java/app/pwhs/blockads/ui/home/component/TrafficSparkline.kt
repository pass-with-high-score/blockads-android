package app.pwhs.blockads.ui.home.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

private const val SEGMENTS = 48
private const val IDLE_AMPLITUDE = 0.22f
private const val GAUSSIAN_SIGMA = 1.5f
private const val GAUSSIAN_RADIUS = 4
private const val MIN_VERTICAL_SCALE = 0.25f

private val GAUSSIAN_WEIGHTS: FloatArray = FloatArray(GAUSSIAN_RADIUS * 2 + 1) { index ->
    val d = (index - GAUSSIAN_RADIUS).toDouble()
    exp(-0.5 * (d / GAUSSIAN_SIGMA) * (d / GAUSSIAN_SIGMA)).toFloat()
}.let { weights ->
    val sum = weights.sum()
    FloatArray(weights.size) { i -> weights[i] / sum }
}

private class SmoothedSpline(
    val values: FloatArray,
    val slopes: FloatArray,
    val maxVal: Float
)

private class PointsHolder(var points: List<Float> = emptyList())

private fun buildSmoothedSpline(points: List<Float>): SmoothedSpline {
    val n = points.size
    val smoothed = FloatArray(n) { i ->
        var acc = 0f
        for (d in -GAUSSIAN_RADIUS..GAUSSIAN_RADIUS) {
            val sample = points[(i + d).coerceIn(0, n - 1)]
            val safe = if (sample.isFinite()) sample.coerceAtLeast(0f) else 0f
            acc += GAUSSIAN_WEIGHTS[d + GAUSSIAN_RADIUS] * safe
        }
        acc
    }

    // Clamp slopes to +/-3*v so cubic Bezier control points never dip below zero baseline.
    val slopes = FloatArray(n) { i ->
        val raw = when (i) {
            0 -> smoothed[1] - smoothed[0]
            n - 1 -> smoothed[n - 1] - smoothed[n - 2]
            else -> (smoothed[i + 1] - smoothed[i - 1]) * 0.5f
        }
        val limit = 3f * smoothed[i]
        raw.coerceIn(-limit, limit)
    }

    val maxVal = max(MIN_VERTICAL_SCALE, smoothed.maxOrNull() ?: MIN_VERTICAL_SCALE)
    return SmoothedSpline(values = smoothed, slopes = slopes, maxVal = maxVal)
}

private fun evaluateSplineY(
    spline: SmoothedSpline,
    t: Float,
    baselineY: Float,
    usableHeight: Float
): Float {
    val segments = spline.values.size - 1
    val scaled = (t * segments).coerceIn(0f, segments.toFloat())
    val idx = scaled.toInt().coerceAtMost(segments - 1)
    val u = scaled - idx
    val inv = 1f - u
    val v0 = spline.values[idx]
    val v1 = spline.values[idx + 1]
    val cp1v = v0 + spline.slopes[idx] / 3f
    val cp2v = v1 - spline.slopes[idx + 1] / 3f
    val value = inv * inv * inv * v0 +
        3f * inv * inv * u * cp1v +
        3f * inv * u * u * cp2v +
        u * u * u * v1
    return baselineY - (value / spline.maxVal) * usableHeight
}

@Composable
fun TrafficSparkline(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val hasLiveTraffic = points.size >= 2 && points.any { it > 0.01f }

    // Retain last live samples during fade-out so cross-blend to idle wave stays smooth.
    val cachedLivePoints = remember { PointsHolder() }
    if (hasLiveTraffic) {
        cachedLivePoints.points = points
    }
    val splinePoints = if (hasLiveTraffic) points else cachedLivePoints.points
    val spline = remember(splinePoints) {
        if (splinePoints.size >= 2 && splinePoints.any { it > 0.01f }) {
            buildSmoothedSpline(splinePoints)
        } else {
            null
        }
    }

    val liveBlendState = animateFloatAsState(
        targetValue = if (hasLiveTraffic) 1f else 0f,
        animationSpec = tween(500),
        label = "sparkline_live_blend"
    )
    val phaseState = rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "wave_phase"
    )

    val strokePath = remember { Path() }
    val fillPath = remember { Path() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val strokePx = 2.2.dp.toPx()
        val pad = strokePx * 0.5f
        val baselineY = height - pad
        val usableHeight = (baselineY - pad).coerceAtLeast(1f)
        val liveBlend = if (spline != null) liveBlendState.value else 0f

        strokePath.reset()
        fillPath.reset()

        if (liveBlend >= 0.999f && spline != null) {
            val n = spline.values.size
            val step = width / (n - 1)
            val y0 = baselineY - (spline.values[0] / spline.maxVal) * usableHeight
            strokePath.moveTo(0f, y0)
            fillPath.moveTo(0f, height)
            fillPath.lineTo(0f, y0)

            for (i in 0 until n - 1) {
                val x0 = i * step
                val x1 = (i + 1) * step
                val cp1x = x0 + step / 3f
                val cp2x = x1 - step / 3f
                val cp1y = baselineY - ((spline.values[i] + spline.slopes[i] / 3f) / spline.maxVal) * usableHeight
                val cp2y = baselineY - ((spline.values[i + 1] - spline.slopes[i + 1] / 3f) / spline.maxVal) * usableHeight
                val y1 = baselineY - (spline.values[i + 1] / spline.maxVal) * usableHeight
                strokePath.cubicTo(cp1x, cp1y, cp2x, cp2y, x1, y1)
                fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, x1, y1)
            }
        } else {
            val mid = height / 2f
            val phase = phaseState.value
            for (i in 0..SEGMENTS) {
                val t = i / SEGMENTS.toFloat()
                val taper = sin(PI * t).toFloat()
                val idleY = mid - sin(t * 2 * PI * 1.5 - phase).toFloat() * IDLE_AMPLITUDE * height * taper
                val y = if (liveBlend > 0.001f && spline != null) {
                    val liveY = evaluateSplineY(spline, t, baselineY, usableHeight)
                    idleY + (liveY - idleY) * liveBlend
                } else {
                    idleY
                }
                val x = t * width
                if (i == 0) {
                    strokePath.moveTo(x, y)
                    fillPath.moveTo(x, height)
                    fillPath.lineTo(x, y)
                } else {
                    strokePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
        }

        fillPath.lineTo(width, height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    color.copy(alpha = if (isDark) 0.22f else 0.14f),
                    color.copy(alpha = 0f)
                ),
                startY = 0f,
                endY = height
            )
        )
        drawPath(
            path = strokePath,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
