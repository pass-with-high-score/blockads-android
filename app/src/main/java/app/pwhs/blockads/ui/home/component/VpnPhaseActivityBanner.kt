package app.pwhs.blockads.ui.home.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.SecurityOrange
import app.pwhs.blockads.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private const val MIN_STEP_DISPLAY_MS = 420L
private const val COMPLETION_HOLD_MS = 750L

@Composable
fun VpnPhaseActivityBanner(
    isConnecting: Boolean,
    isStopping: Boolean,
    isActive: Boolean,
    phase: String,
    modifier: Modifier = Modifier
) {
    val inProgress = isConnecting || isStopping
    val connectingTitle = stringResource(R.string.status_connecting)
    val disconnectingTitle = stringResource(R.string.status_disconnecting)
    val protectedTitle = stringResource(R.string.status_protected)
    val disconnectedTitle = stringResource(R.string.status_unprotected)

    // Stacked timeline of steps; UI-only delay keeps the real VPN engine instant.
    val visibleSteps = remember { mutableStateListOf<String>() }
    val pendingSteps = remember { mutableStateListOf<String>() }
    var activeIndex by remember { mutableIntStateOf(-1) }
    var bannerVisible by remember { mutableStateOf(false) }
    var wasStopping by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(inProgress, isStopping, phase) {
        if (inProgress) {
            if (!bannerVisible || (wasStopping != isStopping && showCompleted)) {
                visibleSteps.clear()
                pendingSteps.clear()
                activeIndex = -1
            }
            wasStopping = isStopping
            showCompleted = false
            bannerVisible = true

            val next = phase.trim()
            if (next.isNotBlank() && !visibleSteps.contains(next) && !pendingSteps.contains(next)) {
                pendingSteps.add(next)
            }
        }
    }

    val currentInProgress by rememberUpdatedState(inProgress)
    val currentIsActive by rememberUpdatedState(isActive)

    LaunchedEffect(bannerVisible) {
        if (!bannerVisible) return@LaunchedEffect

        while (currentInProgress || pendingSteps.isNotEmpty()) {
            if (pendingSteps.isNotEmpty()) {
                val nextStep = pendingSteps.removeAt(0)
                visibleSteps.add(nextStep)
                activeIndex = visibleSteps.lastIndex
                delay(MIN_STEP_DISPLAY_MS)
            } else {
                delay(70L)
            }
        }

        // Mark all steps complete and hold briefly so the stacked steps are easy to read.
        showCompleted = true
        activeIndex = visibleSteps.size
        delay(COMPLETION_HOLD_MS)
        bannerVisible = false
        showCompleted = false
        visibleSteps.clear()
        pendingSteps.clear()
        activeIndex = -1
    }

    val accentColor by animateColorAsState(
        targetValue = when {
            showCompleted && currentIsActive && !wasStopping -> MaterialTheme.colorScheme.primary
            wasStopping || isStopping -> SecurityOrange
            else -> AccentBlue
        },
        animationSpec = tween(250),
        label = "phase_accent"
    )

    val headerTitle = when {
        showCompleted && currentIsActive && !wasStopping -> protectedTitle
        showCompleted -> disconnectedTitle
        wasStopping || isStopping -> disconnectingTitle
        else -> connectingTitle
    }

    AnimatedVisibility(
        visible = bannerVisible,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(260)) + shrinkVertically(tween(260)),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = accentColor.copy(alpha = 0.08f),
            modifier = Modifier
                .padding(top = 16.dp)
                .widthIn(min = 240.dp, max = 310.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .padding(top = 12.dp)
            ) {
                // Header row: "Đang kết nối..." / "Đang ngắt kết nối..."
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showCompleted) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        PulsingDotsIndicator(color = accentColor)
                    }
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Stacked steps below the header
                if (visibleSteps.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 16.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        visibleSteps.forEachIndexed { index, stepText ->
                            val isDone = index < activeIndex || showCompleted
                            val isCurrent = index == activeIndex && !showCompleted
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 }
                            ) {
                                StepRow(
                                    text = stepText,
                                    isDone = isDone,
                                    isCurrent = isCurrent,
                                    accentColor = accentColor
                                )
                            }
                        }
                    }
                }

                if (showCompleted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(3.dp)
                            .background(accentColor)
                    )
                } else {
                    LinearProgressIndicator(
                        color = accentColor,
                        trackColor = accentColor.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    text: String,
    isDone: Boolean,
    isCurrent: Boolean,
    accentColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isDone) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent) accentColor else TextSecondary.copy(alpha = 0.4f))
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.onSurface else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PulsingDotsIndicator(
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "phase_dots")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_wave"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val distance = kotlin.math.abs(progress - index).coerceAtMost(1f)
            val factor = 1f - FastOutSlowInEasing.transform(distance)
            val scale = 0.7f + 0.45f * factor
            val alpha = 0.4f + 0.6f * factor
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(scale)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
