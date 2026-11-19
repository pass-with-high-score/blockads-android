package app.pwhs.blockads.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches theme state in a single frame and fades a captured snapshot of the previous frame
 * out over the new live tree, avoiding costly per-frame ThemeData/ColorScheme interpolation.
 */
@Composable
fun ThemeCrossFade(
    themeKey: Any,
    durationMillis: Int = 220,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val graphicsLayer = rememberGraphicsLayer()
    var previousKey by remember { mutableStateOf(themeKey) }
    var snapshotBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val fadeAlpha = remember { Animatable(0f) }

    LaunchedEffect(themeKey) {
        if (themeKey == previousKey) return@LaunchedEffect
        if (durationMillis <= 0 || graphicsLayer.size.width <= 0 || graphicsLayer.size.height <= 0) {
            snapshotBitmap = null
            fadeAlpha.snapTo(0f)
            previousKey = themeKey
            return@LaunchedEffect
        }

        val captured = runCatching { graphicsLayer.toImageBitmap() }.getOrNull()
        // Commit the new key immediately after capture so the live tree underneath renders in 1 frame.
        previousKey = themeKey
        if (captured == null) {
            snapshotBitmap = null
            fadeAlpha.snapTo(0f)
            return@LaunchedEffect
        }

        snapshotBitmap = captured
        fadeAlpha.snapTo(1f)
        try {
            fadeAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing)
            )
        } finally {
            if (snapshotBitmap === captured) {
                snapshotBitmap = null
            }
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier.drawWithContent {
                // Hold the prior frame inside graphicsLayer if draw runs before LaunchedEffect captures it.
                if (themeKey == previousKey || graphicsLayer.size == IntSize.Zero) {
                    graphicsLayer.record {
                        this@drawWithContent.drawContent()
                    }
                }
                drawLayer(graphicsLayer)
            }
        ) {
            content()
        }

        val snapshot = snapshotBitmap
        val alpha = fadeAlpha.value.coerceIn(0f, 1f)
        if (snapshot != null && alpha > 0f) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val width = size.width.roundToInt()
                val height = size.height.roundToInt()
                if (width > 0 && height > 0 &&
                    abs(snapshot.width - width) <= 2 &&
                    abs(snapshot.height - height) <= 2
                ) {
                    drawImage(
                        image = snapshot,
                        dstSize = IntSize(width, height),
                        alpha = alpha
                    )
                }
            }
        }
    }
}
