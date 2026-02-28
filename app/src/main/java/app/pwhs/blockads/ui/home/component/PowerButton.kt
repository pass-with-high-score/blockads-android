package app.pwhs.blockads.ui.home.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed

@Composable
fun PowerButton(
    isActive: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val vpnStateDescription = when {
        isConnecting -> stringResource(R.string.accessibility_vpn_connecting)
        isActive -> stringResource(R.string.accessibility_vpn_active)
        else -> stringResource(R.string.accessibility_vpn_inactive)
    }
    val toggleDescription = stringResource(R.string.accessibility_toggle_vpn)

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnecting -> AccentBlue
            isActive -> MaterialTheme.colorScheme.primary
            else -> DangerRed
        },
        animationSpec = tween(500),
        label = "buttonColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive || isConnecting) 1f else 0.95f,
        animationSpec = tween(300),
        label = "scale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            isConnecting -> 0.3f
            isActive -> 0.4f
            else -> 0.2f
        },
        animationSpec = tween(500),
        label = "glow"
    )

    // Pulsing animation when active
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive && !isConnecting) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(180.dp)
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    )
                )
        )

        // Main button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .scale(scale)
                .shadow(
                    elevation = if (isActive || isConnecting) 20.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = buttonColor.copy(alpha = 0.3f),
                    spotColor = buttonColor.copy(alpha = 0.3f)
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = 0.2f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .border(
                    width = 3.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            buttonColor,
                            buttonColor.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isConnecting
                ) { onClick() }
                .semantics {
                    contentDescription = toggleDescription
                    stateDescription = vpnStateDescription
                    role = Role.Button
                }
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    color = buttonColor,
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = buttonColor,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}