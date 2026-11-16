package app.pwhs.blockads.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.home.data.RecentLogFilter
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.SecurityOrange
import app.pwhs.blockads.ui.logs.data.LogFilterStatus
import app.pwhs.blockads.ui.theme.TextSecondary
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.home.component.HomeActiveTipCard
import app.pwhs.blockads.ui.home.component.HomeActivityChart
import app.pwhs.blockads.ui.home.component.HomeAmbientBackground
import app.pwhs.blockads.ui.home.component.HomeAppBar
import app.pwhs.blockads.ui.home.component.HomeBlockRateCard
import app.pwhs.blockads.ui.home.component.HomeBlockedDomainHandler
import app.pwhs.blockads.ui.home.component.HomeFeatureTipType
import app.pwhs.blockads.ui.home.component.HomeStatusHeader
import app.pwhs.blockads.ui.home.component.MilestoneBottomSheet
import app.pwhs.blockads.ui.home.component.MiniBarChartDefaults
import app.pwhs.blockads.ui.home.component.NetworkSpeedSection
import app.pwhs.blockads.ui.home.component.FeatureBadgeKind
import app.pwhs.blockads.ui.home.component.FloatingFeatureChips
import app.pwhs.blockads.ui.home.component.PowerButton
import app.pwhs.blockads.ui.home.component.VpnPhaseActivityBanner
import app.pwhs.blockads.ui.home.component.PrivateDnsWarningCard
import app.pwhs.blockads.ui.home.component.RecentBlockedSection
import app.pwhs.blockads.ui.home.component.RoutingModeBottomSheet
import app.pwhs.blockads.ui.home.component.SelectedBlockedDomain
import app.pwhs.blockads.ui.home.component.StatCard
import app.pwhs.blockads.ui.home.component.TopBlockedSection
import app.pwhs.blockads.ui.home.component.VpnRevokedWarningCard
import app.pwhs.blockads.ui.home.component.isVisible
import app.pwhs.blockads.ui.home.component.rememberHomeTipState
import app.pwhs.blockads.utils.AppConstants.AVG_AD_SIZE_KB
import app.pwhs.blockads.utils.VpnUtils
import app.pwhs.blockads.utils.formatCount
import app.pwhs.blockads.utils.formatDataSize
import app.pwhs.blockads.utils.formatTimeSince
import app.pwhs.blockads.utils.formatUptimeShort
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

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
    // Show the trusted-network paused state only while actually off.
    val showTrustedPause = pausedByTrusted && !vpnEnabled && !vpnConnecting && !vpnStopping
    val showRevokedWarning = vpnRevokedByAnotherApp && !vpnEnabled && !vpnConnecting && !vpnStopping
    val context = LocalContext.current
    var selectedBlockedDomain by remember { mutableStateOf<SelectedBlockedDomain?>(null) }
    var showRoutingModeSheet by rememberSaveable { mutableStateOf(false) }

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
                    onNavigateToBrowser = onNavigateToBrowser
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

            val totalChartData = remember(hourlyStats) {
                MiniBarChartDefaults.createSparklineData(hourlyStats, isBlocked = false)
            }
            val blockedChartData = remember(hourlyStats) {
                MiniBarChartDefaults.createSparklineData(hourlyStats, isBlocked = true)
            }
            val dataSavedKb = blockedCount * AVG_AD_SIZE_KB

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

            val statsContent: @Composable () -> Unit = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Default.QueryStats,
                        label = stringResource(R.string.total_queries),
                        value = formatCount(totalCount),
                        color = MaterialTheme.colorScheme.secondary,
                        chartData = totalChartData,
                        showChevron = true,
                        onClick = { onNavigateToLogScreen(LogFilterStatus.ALL) }
                    )
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Default.Block,
                        label = stringResource(R.string.blocked_queries),
                        value = formatCount(blockedCount),
                        color = DangerRed,
                        chartData = blockedChartData,
                        showChevron = true,
                        onClick = { onNavigateToLogScreen(LogFilterStatus.BLOCKED) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                HomeBlockRateCard(
                    totalCount = totalCount,
                    blockedCount = blockedCount,
                    domainCount = domainCount
                )

                Spacer(modifier = Modifier.height(12.dp))

                NetworkSpeedSection(networkSpeed = networkSpeed)

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Default.DataSaverOn,
                        label = stringResource(R.string.home_data_saved),
                        value = formatDataSize(dataSavedKb),
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Default.Timer,
                        label = stringResource(R.string.home_protection_uptime),
                        value = formatUptimeShort(protectionUptimeMs),
                        color = AccentBlue
                    )
                }

                HomeActivityChart(
                    hourlyStats = hourlyStats,
                    dailyStats = dailyStats
                )

                TopBlockedSection(
                    topBlockedDomains = topBlockedDomains,
                    onDomainClick = { entry ->
                        selectedBlockedDomain = SelectedBlockedDomain(
                            domain = entry.domain,
                            count = entry.count
                        )
                    }
                )

                RecentBlockedSection(
                    recentBlocked = recentBlocked,
                    securityFilterIds = securityFilterIds,
                    currentFilter = recentFilter,
                    onFilterChange = { viewModel.setRecentFilter(it) },
                    onViewAllClick = { onNavigateToLogScreen(LogFilterStatus.ALL) },
                    onEntryClick = { entry ->
                        selectedBlockedDomain = SelectedBlockedDomain(
                            domain = entry.domain,
                            appName = entry.appName,
                            packageName = entry.packageName,
                            blockedBy = entry.blockedBy,
                            isBlocked = entry.isBlocked
                        )
                    }
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
                                .verticalScroll(rememberScrollState())
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
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = 16.dp,
                                    bottom = (if (tipState.isVisible) 100.dp else 24.dp) + floatingBottomPadding
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            statsContent()
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp)
                            .padding(bottom = (if (tipState.isVisible) 100.dp else 24.dp) + floatingBottomPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        heroContent()
                        Spacer(modifier = Modifier.height(18.dp))
                        statsContent()
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

        app.pwhs.blockads.ui.home.component.HomeRateAppPrompt(blockedCount = blockedCount)
    }
}
}