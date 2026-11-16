package app.pwhs.blockads.ui.onboarding.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.AccentBlue

/**
 * Shared radial-glow circular icon used across onboarding steps and the foldable two-pane hero.
 */
@Composable
fun OnboardingGlowIcon(
    modifier: Modifier = Modifier,
    imageVector: ImageVector? = null,
    painter: Painter? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    outerSize: Dp = 140.dp,
    innerSize: Dp = 100.dp,
    iconSize: Dp = 56.dp
) {
    Box(
        modifier = modifier
            .size(outerSize)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.2f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            when {
                painter != null -> Icon(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = accentColor
                )

                imageVector != null -> Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = accentColor
                )
            }
        }
    }
}

/**
 * Left-hand visual pane shown on unfolded foldables and large screens (`width >= 600.dp`).
 */
@Composable
fun OnboardingHeroPane(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "onboarding_hero_icon"
            ) { page ->
                when (page) {
                    0 -> OnboardingGlowIcon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        outerSize = 180.dp,
                        innerSize = 128.dp,
                        iconSize = 72.dp
                    )

                    1 -> OnboardingGlowIcon(
                        imageVector = Icons.Filled.Dns,
                        outerSize = 180.dp,
                        innerSize = 128.dp,
                        iconSize = 68.dp
                    )

                    2 -> OnboardingGlowIcon(
                        imageVector = Icons.Filled.VpnKey,
                        outerSize = 180.dp,
                        innerSize = 128.dp,
                        iconSize = 68.dp
                    )

                    3 -> OnboardingGlowIcon(
                        imageVector = Icons.Filled.Notifications,
                        accentColor = AccentBlue,
                        outerSize = 180.dp,
                        innerSize = 128.dp,
                        iconSize = 68.dp
                    )

                    4 -> OnboardingGlowIcon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        outerSize = 180.dp,
                        innerSize = 128.dp,
                        iconSize = 68.dp
                    )

                    5 -> OnboardingGlowIcon(
                        imageVector = Icons.Filled.BugReport,
                        outerSize = 180.dp,
                        innerSize = 128.dp,
                        iconSize = 68.dp
                    )

                    else -> {
                        val scale = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            scale.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = 500,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        }
                        OnboardingGlowIcon(
                            imageVector = Icons.Filled.CheckCircle,
                            outerSize = 180.dp,
                            innerSize = 128.dp,
                            iconSize = 76.dp,
                            modifier = Modifier.scale(scale.value)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = "${currentPage + 1} / $totalPages",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}
