package app.pwhs.blockads.ui.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.appearance.component.AppearanceScaffold
import app.pwhs.blockads.ui.appearance.component.NavigationCard
import app.pwhs.blockads.ui.component.rememberIsTabletOrFold
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppearanceNavigationScreen(
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    onNavigateBack: () -> Unit = {},
    viewModel: AppearanceViewModel = koinViewModel()
) {
    val showBottomNavLabels by viewModel.showBottomNavLabels.collectAsStateWithLifecycle()
    val bottomNavSecondaryTab by viewModel.bottomNavSecondaryTab.collectAsStateWithLifecycle()
    val navRailPosition by viewModel.navRailPosition.collectAsStateWithLifecycle()
    val floatingCapsuleNav by viewModel.floatingCapsuleNav.collectAsStateWithLifecycle()
    val isTabletOrFold = rememberIsTabletOrFold()

    AppearanceScaffold(
        title = stringResource(R.string.settings_navigation),
        showBackButton = showBackButton,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        NavigationCard(
            showBottomNavLabels = showBottomNavLabels,
            onToggleShowBottomNavLabels = { viewModel.setShowBottomNavLabels(it) },
            bottomNavSecondaryTab = bottomNavSecondaryTab,
            onSelectBottomNavSecondaryTab = { viewModel.setBottomNavSecondaryTab(it) },
            floatingCapsuleNav = floatingCapsuleNav,
            onToggleFloatingCapsuleNav = { viewModel.setFloatingCapsuleNav(it) },
            isTabletOrFold = isTabletOrFold,
            navRailPosition = navRailPosition,
            onSelectNavRailPosition = { viewModel.setNavRailPosition(it) }
        )
    }
}
