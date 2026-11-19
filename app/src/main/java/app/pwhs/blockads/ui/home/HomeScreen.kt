package app.pwhs.blockads.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.home.component.FeatureBadgeKind
import app.pwhs.blockads.ui.home.component.FloatingFeatureChips
import app.pwhs.blockads.ui.home.component.HomeActiveTipCard
import app.pwhs.blockads.ui.home.component.HomeAmbientBackground
import app.pwhs.blockads.ui.home.component.HomeAppBar
import app.pwhs.blockads.ui.home.component.HomeBlockedDomainHandler
import app.pwhs.blockads.ui.home.component.HomeEditableStatsList
import app.pwhs.blockads.ui.home.component.HomeFeatureTipType
import app.pwhs.blockads.ui.home.component.HomeRateAppPrompt
import app.pwhs.blockads.ui.home.component.HomeStatusHeader
import app.pwhs.blockads.ui.home.component.MilestoneBottomSheet
import app.pwhs.blockads.ui.home.component.PowerButton
import app.pwhs.blockads.ui.home.component.PrivateDnsWarningCard
import app.pwhs.blockads.ui.home.component.RoutingModeBottomSheet
import app.pwhs.blockads.ui.home.component.SelectedBlockedDomain
import app.pwhs.blockads.ui.home.component.VpnPhaseActivityBanner
import app.pwhs.blockads.ui.home.component.VpnRevokedWarningCard
import app.pwhs.blockads.ui.home.component.isVisible
import app.pwhs.blockads.ui.home.component.rememberHomeTipState
import app.pwhs.blockads.ui.logs.data.LogFilterStatus
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onShowVpnConflictDialog: () -> Unit = {},
    onRequestVpnPermission: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
    onNavigateToStatisticsScreen: () -> Unit = {},
    onNavigateToLogScreen: (LogFilterStatus) -> Unit = {},
    onNavigateToLogsWithQuery: (String) -> Unit = {},
    onNavigateToBrowser: (String) -> Unit = {},
    onNavigateToFeature: (HomeFeatureTipType) -> Unit = {},
    onFeatureBadgeClick: (FeatureBadgeKind) -> Unit = {},
) {
    val vpnEnabled by viewModel.vpnEnabled.collectAsStateWithLifecycle()
    val vpnConnecting by viewModel.vpnConnecting.collectAsStateWithLifecycle()
    val vpnStopping by viewModel.vpnStopping.collectAsStateWithLifecycle()
    val vpnPhase by viewModel.vpnPhase.collectAsStateWithLifecycle()
    val activeFeatureBadges by viewModel.activeFeatureBadges.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val domainCount by viewModel.domainCount.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val filterLoadFailed by viewModel.filterLoadFailed.collectAsStateWithLifecycle()
    val recentBlocked by viewModel.recentBlocked.collectAsStateWithLifecycle()
    val recentFilter by viewModel.recentFilter.collectAsStateWithLifecycle()
    val hourlyStats by viewModel.hourlyStats.collectAsStateWithLifecycle()
    val dailyStats by viewModel.dailyStats.collectAsStateWithLifecycle()
    val networkSpeed by viewModel.networkSpeed.collectAsStateWithLifecycle()
    val milestoneReached by viewModel.milestoneReached.collectAsStateWithLifecycle()
    val topBlockedDomains by viewModel.topBlockedDomains.collectAsStateWithLifecycle()
    val protectionUptimeMs by viewModel.protectionUptimeMs.collectAsStateWithLifecycle()
    val securityFilterIds by viewModel.securityFilterIds.collectAsStateWithLifecycle()
    val whitelistedDomains by viewModel.whitelistedDomains.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val privateDnsWarning by viewModel.privateDnsWarning.collectAsStateWithLifecycle()
    val pausedByTrusted by viewModel.pausedByTrusted.collectAsStateWithLifecycle()
    val pausedTrustedSsid by viewModel.pausedTrustedSsid.collectAsStateWithLifecycle()
    val vpnRevokedByAnotherApp by viewModel.vpnRevokedByAnotherApp.collectAsStateWithLifecycle()
    val isEditingHome by viewModel.isEditingHome.collectAsStateWithLifecycle()
    val activeHomeWidgets by viewModel.activeHomeWidgets.collectAsStateWithLifecycle()

    // Show the trusted-network paused state only while actually off.
    val showTrustedPause = pausedByTrusted && !vpnEnabled && !vpnConnecting && !vpnStopping
    val showRevokedWarning = vpnRevokedByAnotherApp && !vpnEnabled && !vpnConnecting && !vpnStopping
    val context = LocalContext.current
    var selectedBlockedDomain by remember { mutableStateOf<SelectedBlockedDomain?>(null) }
    var showRoutingModeSheet by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isEditingHome) {
        viewModel.setEditingHome(false)
    }

    UiEventEffect(viewModel.events)

    LaunchedEffect(Unit) {
        viewModel.preloadFilter()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HomeAmbientBackground(
            vpnEnabled = vpnEnabled,
            vpnConnecting = vpnConnecting,
            vpnStopping = vpnStopping,
            modifier = Modifier.fillMaxSize()
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                HomeAppBar(
                    isLoading = isLoading,
                    filterLoadFailed = filterLoadFailed,
                    viewModel = viewModel,
                    onNavigateToStatisticsScreen = onNavigateToStatisticsScreen,
                    onNavigateToLogScreen = { onNavigateToLogScreen(LogFilterStatus.ALL) },
                    onNavigateToBrowser = onNavigateToBrowser,
                    isEditing = isEditingHome,
                    onToggleEdit = { viewModel.toggleEditHome() },
                    onResetWidgets = { viewModel.resetHomeWidgets() }
                )
            }
        ) { innerPadding ->
            val isRootMode = routingMode == AppPreferences.ROUTING_MODE_ROOT
            val tipState = rememberHomeTipState(viewModel, routingMode, onNavigateToFeature)
            val haptic = LocalHapticFeedback.current
            val isFirstVpnChange = remember { mutableStateOf(true) }
            LaunchedEffect(vpnEnabled) {
                if (isFirstVpnChange.value) {
                    isFirstVpnChange.value = false
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }

            val heroContent: @Composable () -> Unit = {
                if (privateDnsWarning) {
                    PrivateDnsWarningCard()
                }

                if (showRevokedWarning) {
                    VpnRevokedWarningCard(
                        onReconnect = {
                            viewModel.dismissVpnRevokedWarning()
                            if (!vpnConnecting && !vpnStopping) {
                                onRequestVpnPermission()
                            }
                        },
                        onDismiss = {
                            viewModel.dismissVpnRevokedWarning()
                        }
                    )
                }

                HomeStatusHeader(
                    vpnStopping = vpnStopping,
                    vpnConnecting = vpnConnecting,
                    vpnEnabled = vpnEnabled,
                    showTrustedPause = showTrustedPause,
                    isRootMode = isRootMode,
                    pausedTrustedSsid = pausedTrustedSsid,
                    routingMode = routingMode,
                    onRoutingModeClick = { showRoutingModeSheet = true }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Power button with floating active-feature chips around it
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(198.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FloatingFeatureChips(
                        visible = vpnEnabled && !vpnStopping,
                        badges = activeFeatureBadges,
                        onBadgeClick = { kind ->
                            if (kind == FeatureBadgeKind.TUNNEL_MODE) {
                                showRoutingModeSheet = true
                            } else {
                                onFeatureBadgeClick(kind)
                            }
                        }
                    )
                    PowerButton(
                        isActive = vpnEnabled,
                        isConnecting = vpnConnecting,
                        isStopping = vpnStopping,
                        onClick = {
                            if (!vpnConnecting && !vpnStopping) {
                                if (vpnEnabled) {
                                    viewModel.stopVpn(context)
                                } else {
                                    viewModel.dismissVpnRevokedWarning()
                                    onRequestVpnPermission()
                                }
                            }
                        }
                    )
                }

                VpnPhaseActivityBanner(
                    isConnecting = vpnConnecting,
                    isStopping = vpnStopping,
                    isActive = vpnEnabled,
                    phase = vpnPhase
                )
            }

            val statsContent: @Composable (ScrollState) -> Unit = { targetScrollState ->
                HomeEditableStatsList(
                    activeWidgets = activeHomeWidgets,
                    isEditing = isEditingHome,
                    totalCount = totalCount,
                    blockedCount = blockedCount,
                    domainCount = domainCount,
                    networkSpeed = networkSpeed,
                    protectionUptimeMs = protectionUptimeMs,
                    hourlyStats = hourlyStats,
                    dailyStats = dailyStats,
                    topBlockedDomains = topBlockedDomains,
                    recentBlocked = recentBlocked,
                    securityFilterIds = securityFilterIds,
                    recentFilter = recentFilter,
                    scrollState = targetScrollState,
                    onRecentFilterChange = { viewModel.setRecentFilter(it) },
                    onNavigateToLogScreen = onNavigateToLogScreen,
                    onSelectBlockedDomain = { selectedBlockedDomain = it },
                    onMoveWidget = { from, to -> viewModel.moveHomeWidget(from, to) },
                    onRemoveWidget = { viewModel.removeHomeWidget(it) },
                    onAddWidget = { viewModel.addHomeWidget(it) }
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val isTwoPane = rememberIsExpandedLayout(maxWidth)
                val floatingBottomPadding = LocalFloatingBottomBarPadding.current

                if (isTwoPane) {
                    val heroScrollState = rememberScrollState()
                    val statsScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.44f)
                                .fillMaxHeight()
                                .verticalScroll(heroScrollState)
                                .padding(top = 24.dp, bottom = 24.dp + floatingBottomPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top
                        ) {
                            heroContent()
                        }

                        Column(
                            modifier = Modifier
                                .weight(0.56f)
                                .fillMaxHeight()
                                .verticalScroll(statsScrollState)
                                .padding(
                                    top = 16.dp,
                                    bottom = (if (tipState.isVisible) 100.dp else 24.dp) + floatingBottomPadding
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            statsContent(statsScrollState)
                        }
                    }
                } else {
                    val mainScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(mainScrollState)
                            .padding(horizontal = 24.dp)
                            .padding(bottom = (if (tipState.isVisible) 100.dp else 24.dp) + floatingBottomPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        heroContent()
                        Spacer(modifier = Modifier.height(18.dp))
                        statsContent(mainScrollState)
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                AnimatedVisibility(
                    visible = tipState.isVisible,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(if (isTwoPane) Alignment.BottomEnd else Alignment.BottomCenter)
                        .widthIn(max = 520.dp)
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 8.dp + floatingBottomPadding
                        )
                ) {
                    HomeActiveTipCard(tipState = tipState)
                }
            }

            milestoneReached?.let { milestone ->
                MilestoneBottomSheet(
                    milestone = milestone,
                    onDismiss = { viewModel.dismissMilestoneDialog(milestone) }
                )
            }

            selectedBlockedDomain?.let { target ->
                val isWhitelisted = whitelistedDomains.contains(target.domain.lowercase())
                HomeBlockedDomainHandler(
                    target = target,
                    isWhitelisted = isWhitelisted,
                    onDismiss = { selectedBlockedDomain = null },
                    onToggleWhitelist = {
                        if (isWhitelisted) viewModel.removeFromWhitelist(target.domain)
                        else viewModel.addToWhitelist(target.domain)
                        selectedBlockedDomain = null
                    },
                    onAddWildcardWhitelist = {
                        viewModel.addWildcardWhitelist(target.domain)
                        selectedBlockedDomain = null
                    },
                    onAddToCustomBlockRules = {
                        viewModel.addToCustomBlockRules(target.domain)
                        selectedBlockedDomain = null
                    },
                    onViewInLogs = {
                        selectedBlockedDomain = null
                        onNavigateToLogsWithQuery(target.domain)
                    }
                )
            }

            if (showRoutingModeSheet) {
                val isRootAvailable by viewModel.isRootAvailable.collectAsStateWithLifecycle()
                val activeWgProfile by viewModel.activeWgProfile.collectAsStateWithLifecycle()
                val activeProxyProfile by viewModel.activeProxyProfile.collectAsStateWithLifecycle()
                val activeSsProfile by viewModel.activeSsProfile.collectAsStateWithLifecycle()
                val activeProxyType by viewModel.activeProxyType.collectAsStateWithLifecycle()

                RoutingModeBottomSheet(
                    currentMode = routingMode,
                    isRootAvailable = isRootAvailable,
                    activeWgProfile = activeWgProfile,
                    activeProxyProfile = activeProxyProfile,
                    activeSsProfile = activeSsProfile,
                    activeProxyType = activeProxyType,
                    onSelectMode = { mode ->
                        viewModel.setRoutingMode(mode)
                        showRoutingModeSheet = false
                    },
                    onConfigureProxyVpn = {
                        showRoutingModeSheet = false
                        onNavigateToFeature(HomeFeatureTipType.WIREGUARD)
                    },
                    onDismiss = { showRoutingModeSheet = false }
                )
            }

            HomeRateAppPrompt(blockedCount = blockedCount)
        }
    }
}
