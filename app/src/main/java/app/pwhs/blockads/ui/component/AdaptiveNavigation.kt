package app.pwhs.blockads.ui.component

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.ui.data.BottomBarScreen

@Composable
fun rememberIsExpandedLayout(maxWidth: Dp): Boolean {
    val configuration = LocalConfiguration.current
    return maxWidth >= 600.dp && configuration.smallestScreenWidthDp >= 600
}

@Composable
fun rememberIsTabletOrFold(): Boolean {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    return remember(configuration.smallestScreenWidthDp, context) {
        val isTablet = configuration.smallestScreenWidthDp >= 600
        val hasHingeSensor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        isTablet || hasHingeSensor
    }
}

@Composable
fun BlockAdsNavigationBar(
    screens: List<BottomBarScreen>,
    currentTab: BottomBarScreen,
    firewallEnabled: Boolean,
    showLabels: Boolean,
    floatingCapsule: Boolean = false,
    onSelectTab: (BottomBarScreen) -> Unit
) {
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = LocalDensity.current.density,
            fontScale = 1f
        )
    ) {
        if (floatingCapsule) {
            FloatingCapsuleBottomBar(
                screens = screens,
                currentTab = currentTab,
                firewallEnabled = firewallEnabled,
                showLabels = showLabels,
                onSelectTab = onSelectTab
            )
        } else {
            NavigationBar(
                windowInsets = WindowInsets.navigationBars,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                screens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentTab == screen,
                        onClick = { onSelectTab(screen) },
                        icon = { NavTabIcon(screen = screen, firewallEnabled = firewallEnabled) },
                        label = if (showLabels) {
                            { NavTabLabel(screen = screen) }
                        } else null,
                        alwaysShowLabel = showLabels,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun BlockAdsNavigationRail(
    screens: List<BottomBarScreen>,
    currentTab: BottomBarScreen,
    firewallEnabled: Boolean,
    showLabels: Boolean,
    floatingCapsule: Boolean = false,
    onSelectTab: (BottomBarScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = LocalDensity.current.density,
            fontScale = 1f
        )
    ) {
        if (floatingCapsule) {
            FloatingCapsuleNavigationRail(
                screens = screens,
                currentTab = currentTab,
                firewallEnabled = firewallEnabled,
                showLabels = showLabels,
                onSelectTab = onSelectTab,
                modifier = modifier
            )
        } else {
            NavigationRail(
                modifier = modifier.fillMaxHeight(),
                windowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    screens.forEach { screen ->
                        NavigationRailItem(
                            selected = currentTab == screen,
                            onClick = { onSelectTab(screen) },
                            icon = { NavTabIcon(screen = screen, firewallEnabled = firewallEnabled) },
                            label = if (showLabels) {
                                { NavTabLabel(screen = screen) }
                            } else null,
                            alwaysShowLabel = showLabels,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NavTabIcon(
    screen: BottomBarScreen,
    firewallEnabled: Boolean
) {
    if (screen == BottomBarScreen.Firewall && firewallEnabled) {
        BurningFireIcon(contentDescription = stringResource(screen.labelRes))
    } else {
        Icon(
            painter = painterResource(screen.icon),
            contentDescription = stringResource(screen.labelRes),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
internal fun NavTabLabel(screen: BottomBarScreen) {
    Text(
        text = stringResource(screen.labelRes),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = LocalTextStyle.current.copy(
            fontSize = 10.5.sp,
            letterSpacing = (-0.2).sp
        )
    )
}
