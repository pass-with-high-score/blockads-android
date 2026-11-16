package app.pwhs.blockads.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.ui.data.BottomBarScreen

val LocalFloatingBottomBarPadding = compositionLocalOf { 0.dp }

private val CapsuleSpringSpec = spring<androidx.compose.ui.unit.Dp>(
    dampingRatio = 0.78f,
    stiffness = Spring.StiffnessMediumLow
)

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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        CapsuleContainer(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(barHeight)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                val tabWidth = maxWidth / screens.size.coerceAtLeast(1)
                val indicatorOffset by animateDpAsState(
                    targetValue = tabWidth * selectedIndex,
                    animationSpec = CapsuleSpringSpec,
                    label = "bottomCapsuleIndicatorOffset"
                )

                CapsuleIndicator(
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
    val itemHeight = if (showLabels) 60.dp else 52.dp
    val innerHeight = itemHeight * screens.size.coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        CapsuleContainer(
            modifier = Modifier.width(84.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxWidth()
                    .height(innerHeight)
            ) {
                val indicatorOffset by animateDpAsState(
                    targetValue = itemHeight * selectedIndex,
                    animationSpec = CapsuleSpringSpec,
                    label = "railCapsuleIndicatorOffset"
                )

                CapsuleIndicator(
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
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        content = content
    )
}

@Composable
private fun CapsuleIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
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
