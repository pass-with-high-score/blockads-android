package app.pwhs.blockads.ui.proxyvpn.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SKELETON_PULSE_PERIOD_MS = 1600L
private const val PLAY_PAUSE_CORNER_RADIUS = 1.2f
private const val PLAY_PAUSE_STROKE_WIDTH = 1.8f
private const val CLOVER_SPIN_DURATION_MS = 1500
private const val CLOVER_QUARTER_DURATION_MS = 375

private val CLOVER_4_SHAPE = Clover4LeafShape()
private val PLAY_CORNERS = listOf(
    Offset(8.3f, 5.6f),
    Offset(18.8f, 12f),
    Offset(8.3f, 18.4f)
)

// Centerline coordinates match FlClash AppGlyphs.playPause so the 1.8 stroke forms exact Material bar proportions.
private val PLAY_HALVES = listOf(
    listOf(Offset(8.3f, 5.6f), Offset(18.8f, 12f), Offset(18.8f, 12f), Offset(8.3f, 12f)) to
        listOf(Offset(5.9f, 6.9f), Offset(18.1f, 6.9f), Offset(18.1f, 9.1f), Offset(5.9f, 9.1f)),
    listOf(Offset(8.3f, 12f), Offset(18.8f, 12f), Offset(18.8f, 12f), Offset(8.3f, 18.4f)) to
        listOf(Offset(5.9f, 14.9f), Offset(18.1f, 14.9f), Offset(18.1f, 17.1f), Offset(5.9f, 17.1f))
)

/**
 * Morphs smoothly from a circle at [progress] = 0f into an N-lobed rounded Material 3 cookie/flower
 * at [progress] = 1f, keeping outer lobe tips bounded within the layout box during spring overshoot.
 */
@Immutable
data class MorphingCookieShape(
    val progress: Float,
    val lobes: Int = 9,
    val depth: Float = 0.10f,
    val rotationDeg: Float = 0f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val radius = size.minDimension * 0.5f
        if (radius <= 0f) return Outline.Rectangle(Rect.Zero)

        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val safeLobes = lobes.coerceAtLeast(3)
        val effectiveDepth = (depth * progress.coerceAtLeast(0f)).coerceIn(0f, 0.45f)
        val path = Path()

        if (effectiveDepth <= 0.0001f) {
            path.addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
            return Outline.Generic(path)
        }

        // Sampling an integer multiple of lobes keeps every lobe tip and valley symmetrical.
        val steps = maxOf(120, safeLobes * 16)
        val rotationRad = Math.toRadians(rotationDeg.toDouble() - 90.0)
        val twoPi = 2.0 * PI

        for (i in 0 until steps) {
            val theta = twoPi * i / steps
            val phi = safeLobes * theta
            // Second harmonic broadens lobe crowns and pinches valleys like MaterialShapes cookies/clovers.
            val dip = ((0.84 - (cos(phi) - 0.16 * cos(2.0 * phi))) * 0.5).toFloat()
            val r = radius * (1f - 2f * effectiveDepth * dip)
            val angle = theta + rotationRad
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * Four-leaf clover shape aligned with its leaves on the 45-degree diagonals so every quarter-turn
 * settles in the same upright orientation.
 */
@Immutable
data class Clover4LeafShape(val rotationDeg: Float = 0f) : Shape {
    private val delegate = MorphingCookieShape(
        progress = 1f,
        lobes = 4,
        depth = 0.14f,
        rotationDeg = 45f + rotationDeg
    )

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = delegate.createOutline(size, layoutDirection, density)
}

/**
 * Draws a procedural 24x24 Play-to-Pause glyph whose two rounded halves interpolate from a triangle
 * into parallel bars while rotating 90 degrees, letting spring overshoot bounce cleanly past 90 deg.
 */
@Composable
fun ProceduralPlayPauseIcon(
    progress: Float,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.graphicsLayer { alpha = tint.alpha }) {
        val minSide = size.minDimension
        if (minSide <= 0f || tint.alpha <= 0f) return@Canvas

        val scale = minSide / 24f
        val offsetX = (size.width - minSide) * 0.5f
        val offsetY = (size.height - minSide) * 0.5f
        val fold = progress.coerceIn(0f, 1f)
        val rotationDeg = 90f * progress
        val opaqueTint = tint.copy(alpha = 1f)
        val strokeStyle = Stroke(
            width = PLAY_PAUSE_STROKE_WIDTH,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        withTransform({
            translate(left = offsetX, top = offsetY)
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            rotate(degrees = rotationDeg, pivot = Offset(12f, 12f))
        }) {
            if (fold <= 0.001f) {
                val path = buildRoundedPolygonPath(PLAY_CORNERS, PLAY_PAUSE_CORNER_RADIUS)
                drawPath(path = path, color = opaqueTint, style = Fill)
                drawPath(path = path, color = opaqueTint, style = strokeStyle)
            } else {
                for ((half, bar) in PLAY_HALVES) {
                    val vertices = List(bar.size) { index ->
                        lerp(half[index], bar[index], fold)
                    }
                    val path = buildRoundedPolygonPath(vertices, PLAY_PAUSE_CORNER_RADIUS)
                    drawPath(path = path, color = opaqueTint, style = Fill)
                    drawPath(path = path, color = opaqueTint, style = strokeStyle)
                }
            }
        }
    }
}

/**
 * Placeholder pill synchronized to the system wall clock so separate skeletons pulse in unison.
 */
@Composable
fun WallClockSkeletonPill(
    width: Dp,
    height: Dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    modifier: Modifier = Modifier
) {
    val pulseAlpha = remember { mutableFloatStateOf(wallClockPulseAlpha()) }
    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameMillis {
                pulseAlpha.floatValue = wallClockPulseAlpha()
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .graphicsLayer { alpha = pulseAlpha.floatValue }
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 44dp 4-leaf clover badge matching FlClash's _CountryBadge: displays the 2-letter country code
 * when idle, or spins continuously while refreshing and settles upright on the next 90-degree turn.
 */
@Composable
fun Clover4LeafRefreshBadge(
    isConnected: Boolean,
    isRefreshingIp: Boolean,
    countryCode: String?,
    iconTint: Color,
    onRefreshIp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cloverRotation = remember { Animatable(0f) }
    LaunchedEffect(isRefreshingIp) {
        if (isRefreshingIp) {
            while (true) {
                cloverRotation.animateTo(
                    targetValue = cloverRotation.value + 360f,
                    animationSpec = tween(durationMillis = CLOVER_SPIN_DURATION_MS, easing = LinearEasing)
                )
            }
        } else {
            // Clover4Leaf repeats every 90 deg, so settling on the next quarter-turn finishes upright without reversing.
            val currentAngle = cloverRotation.value
            val targetAngle = ceil(currentAngle / 90f) * 90f
            if (targetAngle - currentAngle > 0.5f) {
                val fraction = ((targetAngle - currentAngle) / 90f).coerceIn(0.1f, 1f)
                cloverRotation.animateTo(
                    targetValue = targetAngle,
                    animationSpec = tween(
                        durationMillis = (CLOVER_QUARTER_DURATION_MS * fraction).roundToInt().coerceAtLeast(150),
                        easing = FastOutSlowInEasing
                    )
                )
            }
            cloverRotation.snapTo(cloverRotation.value % 360f)
        }
    }

    val colors = MaterialTheme.colorScheme
    val badgeColor = if (isConnected) {
        colors.onPrimaryContainer.copy(alpha = 0.14f)
    } else {
        colors.surfaceContainerHighest.copy(alpha = 0.85f)
    }
    val codeLabel = countryCode?.trim()?.uppercase()?.take(2).orEmpty()
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CLOVER_4_SHAPE)
            .clickable(enabled = !isRefreshingIp, onClick = onRefreshIp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = cloverRotation.value }
                .clip(CLOVER_4_SHAPE)
                .background(badgeColor)
        )
        if (!isRefreshingIp && codeLabel.isNotEmpty()) {
            Text(
                text = codeLabel,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 14.sp,
                    letterSpacing = 0.5.sp
                ),
                fontWeight = FontWeight.Bold,
                color = if (isConnected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.proxy_refresh_ip),
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * FlClash-inspired M3 Expressive start button morphing between a 68dp Play circle and a 9-lobed Pause cookie.
 */
@Composable
fun MorphingStartToggle(
    isConnected: Boolean,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val morphProgress by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = if (isConnected) {
            spring(dampingRatio = 0.62f, stiffness = 320f)
        } else {
            tween(durationMillis = 380, easing = FastOutSlowInEasing)
        },
        label = "start_toggle_morph"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "start_toggle_press_scale"
    )

    val colors = MaterialTheme.colorScheme
    val clampedMorph = morphProgress.coerceIn(0f, 1f)
    val morphShape = MorphingCookieShape(
        progress = morphProgress.coerceIn(0f, 1.15f),
        lobes = 9,
        depth = 0.07f,
        rotationDeg = 40f * morphProgress
    )
    val buttonColor = lerp(colors.surface, colors.primary, clampedMorph)
    val iconTint = lerp(colors.onSurfaceVariant, colors.onPrimary, clampedMorph)
    val toggleDescription = stringResource(R.string.accessibility_toggle_vpn)
    val stateDesc = stringResource(if (isConnected) R.string.accessibility_vpn_active else R.string.accessibility_vpn_inactive)

    Surface(
        modifier = modifier
            .size(68.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics {
                contentDescription = toggleDescription
                stateDescription = stateDesc
                role = Role.Button
            },
        shape = morphShape,
        color = buttonColor,
        border = if (clampedMorph < 0.99f) BorderStroke(1.5.dp, colors.outlineVariant.copy(alpha = 1f - clampedMorph)) else null,
        shadowElevation = (1f + 3f * clampedMorph).dp,
        interactionSource = interactionSource,
        onClick = onToggleConnection
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProceduralPlayPauseIcon(progress = morphProgress, tint = iconTint, modifier = Modifier.size(28.dp))
        }
    }
}

private fun wallClockPulseAlpha(): Float {
    val phase = (System.currentTimeMillis() % SKELETON_PULSE_PERIOD_MS) / SKELETON_PULSE_PERIOD_MS.toDouble()
    return 0.75f + 0.25f * cos(2.0 * PI * phase).toFloat()
}

private fun buildRoundedPolygonPath(vertices: List<Offset>, cornerRadius: Float): Path {
    val count = vertices.size
    val path = Path()
    if (count < 3) return path

    fun cornerPoints(index: Int): Pair<Offset, Offset> {
        val current = vertices[index]
        val previous = vertices[(index - 1 + count) % count]
        val next = vertices[(index + 1) % count]
        val toPrev = previous - current
        val toNext = next - current
        val dPrev = hypot(toPrev.x, toPrev.y)
        val dNext = hypot(toNext.x, toNext.y)
        val enter = if (dPrev > 1e-4f) {
            current + toPrev * (min(cornerRadius, dPrev * 0.5f) / dPrev)
        } else {
            current
        }
        val exit = if (dNext > 1e-4f) {
            current + toNext * (min(cornerRadius, dNext * 0.5f) / dNext)
        } else {
            current
        }
        return enter to exit
    }

    val (_, firstExit) = cornerPoints(0)
    path.moveTo(firstExit.x, firstExit.y)
    for (index in 1..count) {
        val wrapped = index % count
        val vertex = vertices[wrapped]
        val (enter, exit) = cornerPoints(wrapped)
        path.lineTo(enter.x, enter.y)
        if (enter != exit) {
            path.quadraticTo(vertex.x, vertex.y, exit.x, exit.y)
        }
    }
    path.close()
    return path
}
