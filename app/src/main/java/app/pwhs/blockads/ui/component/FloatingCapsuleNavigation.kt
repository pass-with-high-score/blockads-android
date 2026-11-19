package app.pwhs.blockads.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.ui.data.BottomBarScreen
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

val LocalFloatingBottomBarPadding = compositionLocalOf { 0.dp }

private val CapsuleSpringSpec = spring<Dp>(
    dampingRatio = 0.78f,
    stiffness = Spring.StiffnessMediumLow
)

private class CapsuleTouchState {
    var touchPos by mutableStateOf(Offset.Unspecified)
    var isPressed by mutableStateOf(false)
}

@Composable
private fun rememberCapsuleTouchGlow(state: CapsuleTouchState): Float {
    val touchGlow by animateFloatAsState(
        targetValue = if (state.isPressed) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (state.isPressed) 120 else 360,
            easing = FastOutSlowInEasing
        ),
        label = "capsuleTouchGlow"
    )
    return touchGlow
}

private fun Modifier.trackCapsuleTouch(state: CapsuleTouchState): Modifier =
    pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                // Observe on Initial pass without consuming so child tab clickables continue working.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val activePointer = event.changes.firstOrNull { it.pressed }
                if (activePointer != null) {
                    state.touchPos = activePointer.position
                    state.isPressed = true
                } else {
                    state.isPressed = false
                }
            }
        }
    }

private data class JellyDeformation(val scaleX: Float, val scaleY: Float)

private fun computeJellyDeformation(
    targetOffset: Dp,
    indicatorOffset: Dp,
    stepSize: Dp,
    touchGlow: Float,
    horizontal: Boolean,
): JellyDeformation {
    val velocityFactor = if (stepSize > 0.dp) {
        (abs((targetOffset - indicatorOffset).value) / stepSize.value).coerceIn(0f, 1f)
    } else {
        0f
    }
    val jellyWave = sin(PI * velocityFactor).toFloat()
    val liftScale = 1f + 0.04f * jellyWave + 0.025f * touchGlow
    val stretch = (1f + 0.16f * jellyWave) * liftScale
    val squash = (1f - 0.08f * jellyWave) * liftScale
    return if (horizontal) {
        JellyDeformation(scaleX = stretch, scaleY = squash)
    } else {
        JellyDeformation(scaleX = squash, scaleY = stretch)
    }
}

@Composable
private fun rememberLensTouchPos(
    touchPos: Offset,
    innerPadding: Dp,
    indicatorOffset: Dp,
    horizontal: Boolean,
): Offset {
    if (!touchPos.isSpecified) return Offset.Unspecified
    val density = LocalDensity.current
    val paddingPx = with(density) { innerPadding.toPx() }
    val offsetPx = with(density) { indicatorOffset.toPx() }
    return if (horizontal) {
        touchPos - Offset(paddingPx + offsetPx, paddingPx)
    } else {
        touchPos - Offset(paddingPx, paddingPx + offsetPx)
    }
}

@Composable
fun FloatingCapsuleBottomBar(
    screens: List<BottomBarScreen>,
    currentTab: BottomBarScreen,
    firewallEnabled: Boolean,
    showLabels: Boolean,
    onSelectTab: (BottomBarScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = screens.indexOf(currentTab).coerceAtLeast(0)
    val barHeight = if (showLabels) 64.dp else 56.dp
    val innerPadding = 4.dp
    val touchState = remember { CapsuleTouchState() }
    val touchGlow = rememberCapsuleTouchGlow(touchState)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        CapsuleContainer(
            touchState = touchState,
            touchGlow = touchGlow,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(barHeight)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val tabWidth = maxWidth / screens.size.coerceAtLeast(1)
                val targetOffset = tabWidth * selectedIndex
                val indicatorOffset by animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = CapsuleSpringSpec,
                    label = "bottomCapsuleIndicatorOffset"
                )
                val jelly = computeJellyDeformation(
                    targetOffset = targetOffset,
                    indicatorOffset = indicatorOffset,
                    stepSize = tabWidth,
                    touchGlow = touchGlow,
                    horizontal = true,
                )
                val lensTouchPos = rememberLensTouchPos(
                    touchPos = touchState.touchPos,
                    innerPadding = innerPadding,
                    indicatorOffset = indicatorOffset,
                    horizontal = true,
                )

                CapsuleIndicator(
                    jellyScaleX = jelly.scaleX,
                    jellyScaleY = jelly.scaleY,
                    touchPos = lensTouchPos,
                    touchGlow = touchGlow,
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    screens.forEach { screen ->
                        CapsuleTabItem(
                            screen = screen,
                            selected = currentTab == screen,
                            firewallEnabled = firewallEnabled,
                            showLabels = showLabels,
                            onClick = { onSelectTab(screen) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingCapsuleNavigationRail(
    screens: List<BottomBarScreen>,
    currentTab: BottomBarScreen,
    firewallEnabled: Boolean,
    showLabels: Boolean,
    onSelectTab: (BottomBarScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = screens.indexOf(currentTab).coerceAtLeast(0)
    val railWidth = if (showLabels) 84.dp else 64.dp
    val itemHeight = if (showLabels) 64.dp else 56.dp
    val innerPadding = 4.dp
    val innerHeight = itemHeight * screens.size.coerceAtLeast(1)
    // Match outer corner radius to (indicatorRadius + innerPadding) so top/bottom indicators never clip.
    val cornerRadius = itemHeight / 2 + innerPadding
    val containerShape = RoundedCornerShape(cornerRadius)
    val touchState = remember { CapsuleTouchState() }
    val touchGlow = rememberCapsuleTouchGlow(touchState)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        CapsuleContainer(
            touchState = touchState,
            touchGlow = touchGlow,
            shape = containerShape,
            cornerRadius = cornerRadius,
            modifier = Modifier.width(railWidth)
        ) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
                    .height(innerHeight)
            ) {
                val targetOffset = itemHeight * selectedIndex
                val indicatorOffset by animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = CapsuleSpringSpec,
                    label = "railCapsuleIndicatorOffset"
                )
                val jelly = computeJellyDeformation(
                    targetOffset = targetOffset,
                    indicatorOffset = indicatorOffset,
                    stepSize = itemHeight,
                    touchGlow = touchGlow,
                    horizontal = false,
                )
                val lensTouchPos = rememberLensTouchPos(
                    touchPos = touchState.touchPos,
                    innerPadding = innerPadding,
                    indicatorOffset = indicatorOffset,
                    horizontal = false,
                )

                CapsuleIndicator(
                    jellyScaleX = jelly.scaleX,
                    jellyScaleY = jelly.scaleY,
                    touchPos = lensTouchPos,
                    touchGlow = touchGlow,
                    modifier = Modifier
                        .offset(y = indicatorOffset)
                        .fillMaxWidth()
                        .height(itemHeight)
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    screens.forEach { screen ->
                        CapsuleTabItem(
                            screen = screen,
                            selected = currentTab == screen,
                            firewallEnabled = firewallEnabled,
                            showLabels = showLabels,
                            onClick = { onSelectTab(screen) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleContainer(
    touchState: CapsuleTouchState,
    touchGlow: Float,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    cornerRadius: Dp? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.trackCapsuleTouch(touchState),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Box(
            modifier = Modifier.liquidGlassBar(
                touchPos = touchState.touchPos,
                touchGlow = touchGlow,
                cornerRadius = cornerRadius
            )
        ) {
            content()
        }
    }
}

@Composable
private fun CapsuleIndicator(
    jellyScaleX: Float,
    jellyScaleY: Float,
    touchPos: Offset,
    touchGlow: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = jellyScaleX
                scaleY = jellyScaleY
            }
            .clip(CircleShape)
            .liquidGlassLens(
                touchPos = touchPos,
                touchGlow = touchGlow
            )
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = CircleShape
            )
    )
}

@Composable
private fun CapsuleTabItem(
    screen: BottomBarScreen,
    selected: Boolean,
    firewallEnabled: Boolean,
    showLabels: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            selected -> 1.04f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "capsuleTabScale"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "capsuleTabColor"
    )

    Column(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            NavTabIcon(screen = screen, firewallEnabled = firewallEnabled)
            if (showLabels) {
                Spacer(modifier = Modifier.height(2.dp))
                NavTabLabel(screen = screen)
            }
        }
    }
}
