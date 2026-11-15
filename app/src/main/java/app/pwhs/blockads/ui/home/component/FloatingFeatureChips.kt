package app.pwhs.blockads.ui.home.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class FeatureBadgeKind {
    DNS_PROTOCOL,
    HTTPS_FILTER,
    FIREWALL,
    TAILSCALE,
    TUNNEL_MODE,
    FILTER_RULES
}

data class ActiveFeatureBadge(
    val kind: FeatureBadgeKind,
    val label: String
)

private data class BadgePalette(
    val icon: ImageVector,
    val startColor: Color,
    val endColor: Color
)

@Composable
fun BoxScope.FloatingFeatureChips(
    visible: Boolean,
    badges: List<ActiveFeatureBadge>,
    onBadgeClick: (FeatureBadgeKind) -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "floating_feature_chips")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "float_phase"
    )
    val orbitRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_rotation"
    )
    val glowPulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    val visibleBadges = badges.take(4)
    val alignments = when (visibleBadges.size) {
        1 -> listOf(Alignment.CenterEnd)
        2 -> listOf(Alignment.TopStart, Alignment.BottomEnd)
        3 -> listOf(Alignment.CenterStart, Alignment.TopEnd, Alignment.BottomEnd)
        else -> listOf(
            Alignment.TopStart,
            Alignment.TopEnd,
            Alignment.BottomStart,
            Alignment.BottomEnd
        )
    }

    val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.85f),
            exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Canvas(modifier = Modifier.size(188.dp)) {
                rotate(orbitRotation) {
                    drawCircle(
                        color = ringColor,
                        radius = size.minDimension / 2f,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 14f), 0f)
                        )
                    )
                }
            }
        }

        visibleBadges.forEachIndexed { index, badge ->
            val alignment = alignments[index]
            val stagger = index * 1.55f
            val offsetYDp = (sin((phase + stagger).toDouble()) * 5.0).toFloat()
            val offsetXDp = (cos((phase + stagger * 0.8f).toDouble()) * 3.0).toFloat()
            val tiltDeg = (sin((phase + stagger + 0.6f).toDouble()) * 2.0).toFloat()
            val iconScale = 0.94f + 0.12f * ((sin((phase * 2f + stagger).toDouble()).toFloat() + 1f) * 0.5f)

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(260, delayMillis = index * 70)) +
                    scaleIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        initialScale = 0.55f
                    ),
                exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.7f),
                modifier = Modifier
                    .align(alignment)
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .offset {
                        IntOffset(
                            x = (offsetXDp * density).roundToInt(),
                            y = (offsetYDp * density).roundToInt()
                        )
                    }
                    .graphicsLayer { rotationZ = tiltDeg }
            ) {
                FeatureChipPill(
                    badge = badge,
                    glowAlpha = glowPulse,
                    iconScale = iconScale,
                    onClick = { onBadgeClick(badge.kind) }
                )
            }
        }
    }
}

@Composable
private fun FeatureChipPill(
    badge: ActiveFeatureBadge,
    glowAlpha: Float,
    iconScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = badgePalette(badge.kind)
    val shape = RoundedCornerShape(22.dp)
    val bgBrush = Brush.horizontalGradient(
        colors = listOf(
            palette.startColor.copy(alpha = 0.08f + 0.04f * glowAlpha),
            palette.endColor.copy(alpha = 0.08f + 0.04f * glowAlpha)
        )
    )
    val iconHaloBrush = Brush.linearGradient(
        colors = listOf(palette.startColor, palette.endColor)
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .background(bgBrush)
                .padding(start = 6.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
                    .clip(CircleShape)
                    .background(iconHaloBrush),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = palette.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = badge.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun badgePalette(kind: FeatureBadgeKind): BadgePalette = when (kind) {
    FeatureBadgeKind.DNS_PROTOCOL -> BadgePalette(
        icon = Icons.Default.Dns,
        startColor = Color(0xFF00B4D8),
        endColor = Color(0xFF4361EE)
    )
    FeatureBadgeKind.HTTPS_FILTER -> BadgePalette(
        icon = Icons.Default.Https,
        startColor = Color(0xFF10B981),
        endColor = Color(0xFF06B6D4)
    )
    FeatureBadgeKind.FIREWALL -> BadgePalette(
        icon = Icons.Default.Security,
        startColor = Color(0xFFF97316),
        endColor = Color(0xFFEF4444)
    )
    FeatureBadgeKind.TAILSCALE -> BadgePalette(
        icon = Icons.Default.Hub,
        startColor = Color(0xFF7C3AED),
        endColor = Color(0xFF38BDF8)
    )
    FeatureBadgeKind.TUNNEL_MODE -> BadgePalette(
        icon = Icons.Default.VpnKey,
        startColor = Color(0xFFEC4899),
        endColor = Color(0xFF8B5CF6)
    )
    FeatureBadgeKind.FILTER_RULES -> BadgePalette(
        icon = Icons.Default.Shield,
        startColor = Color(0xFF16A34A),
        endColor = Color(0xFF22C55E)
    )
}
