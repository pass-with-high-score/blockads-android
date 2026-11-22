package app.pwhs.blockads.ui.home.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.AppStat
import app.pwhs.blockads.data.entities.BlockReasonStat
import app.pwhs.blockads.data.entities.CountryStat
import app.pwhs.blockads.data.entities.DailyStat
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.HourlyStat
import app.pwhs.blockads.data.entities.TopBlockedDomain
import app.pwhs.blockads.data.network.NetworkSpeed
import app.pwhs.blockads.ui.home.data.ActiveDnsInfo
import app.pwhs.blockads.ui.home.data.HomeWidgetId
import app.pwhs.blockads.ui.home.data.ProxyTunnelInfo
import app.pwhs.blockads.ui.home.data.RecentLogFilter
import app.pwhs.blockads.ui.home.data.SecurityShieldInfo
import app.pwhs.blockads.ui.logs.data.LogFilterStatus
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.utils.AppConstants.AVG_AD_SIZE_KB
import app.pwhs.blockads.utils.formatCount
import app.pwhs.blockads.utils.formatDataSize
import app.pwhs.blockads.utils.formatUptimeShort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val SWAY_SWINGS = intArrayOf(6, 7, 5)

@Composable
fun HomeEditableStatsList(
    activeWidgets: List<HomeWidgetId>,
    isEditing: Boolean,
    totalCount: Int,
    blockedCount: Int,
    domainCount: Int,
    networkSpeed: NetworkSpeed,
    protectionUptimeMs: Long,
    hourlyStats: List<HourlyStat>,
    dailyStats: List<DailyStat>,
    topBlockedDomains: List<TopBlockedDomain>,
    recentBlocked: List<DnsLogEntry>,
    securityFilterIds: Set<String>,
    recentFilter: RecentLogFilter,
    scrollState: ScrollState,
    onRecentFilterChange: (RecentLogFilter) -> Unit,
    onNavigateToLogScreen: (LogFilterStatus) -> Unit,
    onSelectBlockedDomain: (SelectedBlockedDomain) -> Unit,
    onMoveWidget: (Int, Int) -> Unit,
    onRemoveWidget: (HomeWidgetId) -> Unit,
    onAddWidget: (HomeWidgetId) -> Unit,
    vpnEnabled: Boolean = false,
    activeDnsInfo: ActiveDnsInfo = ActiveDnsInfo(),
    topApps: List<AppStat> = emptyList(),
    securityShieldInfo: SecurityShieldInfo = SecurityShieldInfo(),
    proxyTunnelInfo: ProxyTunnelInfo = ProxyTunnelInfo(),
    blockReasons24h: List<BlockReasonStat> = emptyList(),
    topCountries24h: List<CountryStat> = emptyList(),
    onTestAdBlock: () -> Unit = {},
    onDnsLeakTest: () -> Unit = {},
    onNavigateToDomainRules: () -> Unit = {},
    onTogglePauseProtection: () -> Unit = {},
    onNavigateToDnsProvider: () -> Unit = {},
    onNavigateToHttpsFiltering: () -> Unit = {},
    onNavigateToFirewall: () -> Unit = {},
    onNavigateToFilters: () -> Unit = {},
    onNavigateToProxyVpn: () -> Unit = {},
    onOpenRoutingModeSheet: () -> Unit = {},
    onNavigateToAppLogs: (String) -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val view = LocalView.current
    val totalChartData = remember(hourlyStats) { MiniBarChartDefaults.createSparklineData(hourlyStats, isBlocked = false) }
    val blockedChartData = remember(hourlyStats) { MiniBarChartDefaults.createSparklineData(hourlyStats, isBlocked = true) }
    val dataSavedKb = blockedCount * AVG_AD_SIZE_KB

    // Single shared sway loop across all cards while Edit Mode is active.
    val swayProgress = if (isEditing) {
        rememberInfiniteTransition(label = "edit_sway").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
            label = "sway_progress"
        ).value
    } else 0f

    var draggedWidget by remember { mutableStateOf<HomeWidgetId?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var pointerWindowY by remember { mutableFloatStateOf(Float.NaN) }
    val itemHeights = remember { mutableStateMapOf<HomeWidgetId, Int>() }
    val latestActiveWidgets by rememberUpdatedState(activeWidgets)
    val latestOnMove by rememberUpdatedState(onMoveWidget)

    LaunchedEffect(isEditing) {
        if (!isEditing) {
            draggedWidget = null
            dragOffsetY = 0f
            pointerWindowY = Float.NaN
        }
    }

    // Keep removed cards mounted until their shrink exit finishes.
    val visibilityStates = remember { HomeWidgetId.entries.associateWith { MutableTransitionState(it in activeWidgets) } }
    var displayOrder by remember { mutableStateOf(activeWidgets) }

    LaunchedEffect(activeWidgets) {
        HomeWidgetId.entries.forEach { visibilityStates.getValue(it).targetState = it in activeWidgets }
        val activeSet = activeWidgets.toSet()
        val exiting = displayOrder.filter { it !in activeSet && visibilityStates.getValue(it).currentState }
        if (exiting.isEmpty()) {
            displayOrder = activeWidgets
        } else {
            val merged = activeWidgets.toMutableList()
            exiting.forEach { merged.add(displayOrder.indexOf(it).coerceIn(0, merged.size), it) }
            displayOrder = merged
            snapshotFlow { exiting.all { val s = visibilityStates.getValue(it); !s.currentState && s.isIdle } }.first { it }
            displayOrder = activeWidgets
        }
    }

    var previousWidgetCount by remember { mutableIntStateOf(activeWidgets.size) }
    LaunchedEffect(activeWidgets.size) {
        if (isEditing && activeWidgets.size > previousWidgetCount) {
            delay(80)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
        previousWidgetCount = activeWidgets.size
    }

    val handleDragDelta: (HomeWidgetId, Float) -> Unit = { widgetId, deltaY ->
        dragOffsetY += deltaY
        val widgets = latestActiveWidgets
        val idx = widgets.indexOf(widgetId)
        if (idx != -1) {
            val nextH = if (idx < widgets.lastIndex) (itemHeights[widgets[idx + 1]] ?: 0).toFloat() else 0f
            val prevH = if (idx > 0) (itemHeights[widgets[idx - 1]] ?: 0).toFloat() else 0f
            if (nextH > 0f && dragOffsetY > nextH * 0.55f) {
                dragOffsetY -= nextH
                latestOnMove(idx, idx + 1)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } else if (prevH > 0f && dragOffsetY < -prevH * 0.55f) {
                dragOffsetY += prevH
                latestOnMove(idx, idx - 1)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    // Auto-scroll the parent container when dragging near the top or bottom viewport edge.
    LaunchedEffect(draggedWidget) {
        val activeId = draggedWidget ?: return@LaunchedEffect
        val edgeThresholdPx = with(density) { 148.dp.toPx() }
        val edgeRampPx = with(density) { 96.dp.toPx() }
        val minSpeedPxPerSec = with(density) { 260.dp.toPx() }
        val maxSpeedPxPerSec = with(density) { 920.dp.toPx() }
        var lastFrameMs = 0L

        while (draggedWidget == activeId) {
            val frameMs = withFrameMillis { it }
            val deltaSec = if (lastFrameMs == 0L) 0f else ((frameMs - lastFrameMs).coerceIn(0L, 50L)) / 1000f
            lastFrameMs = frameMs

            val y = pointerWindowY
            val viewportHeight = view.height.toFloat()
            if (y.isNaN() || deltaSec <= 0f || viewportHeight <= 0f) continue

            val widgets = latestActiveWidgets
            val idx = widgets.indexOf(activeId)
            if (idx == -1) continue

            val scrollStep = when {
                y > viewportHeight - edgeThresholdPx && idx < widgets.lastIndex && scrollState.value < scrollState.maxValue -> {
                    val fraction = ((y - (viewportHeight - edgeThresholdPx)) / edgeRampPx).coerceIn(0f, 1f)
                    (minSpeedPxPerSec + (maxSpeedPxPerSec - minSpeedPxPerSec) * fraction) * deltaSec
                }
                y < edgeThresholdPx && idx > 0 && scrollState.value > 0 -> {
                    val fraction = ((edgeThresholdPx - y) / edgeRampPx).coerceIn(0f, 1f)
                    -(minSpeedPxPerSec + (maxSpeedPxPerSec - minSpeedPxPerSec) * fraction) * deltaSec
                }
                else -> 0f
            }

            if (scrollStep != 0f) {
                val scrolled = scrollState.scrollBy(scrollStep)
                if (scrolled != 0f) {
                    handleDragDelta(activeId, scrolled)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        for (widgetId in displayOrder) {
            key(widgetId) {
                val isDragging = draggedWidget == widgetId
                val hasVisibleBody = isEditing || when (widgetId) {
                    HomeWidgetId.ACTIVITY_CHART -> hourlyStats.isNotEmpty() || dailyStats.isNotEmpty()
                    HomeWidgetId.TOP_BLOCKED -> topBlockedDomains.isNotEmpty()
                    HomeWidgetId.TOP_APPS -> topApps.isNotEmpty()
                    HomeWidgetId.BLOCK_REASONS -> blockReasons24h.isNotEmpty()
                    HomeWidgetId.TOP_COUNTRIES -> topCountries24h.isNotEmpty()
                    else -> true
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 10f else 0f)
                        .onSizeChanged { itemHeights[widgetId] = it.height }
                        .graphicsLayer {
                            if (isDragging) {
                                scaleX = 1.03f; scaleY = 1.03f
                                translationY = dragOffsetY
                            } else if (isEditing) {
                                val seed = widgetId.ordinal
                                val turn = swayProgress * 2f * PI.toFloat() * SWAY_SWINGS[seed % 3] + seed * 1.7f
                                translationY = cos(turn) * 1.25.dp.toPx()
                                rotationZ = sin(turn) * 0.7f
                            }
                        }
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = visibilityStates.getValue(widgetId),
                        enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) +
                            fadeIn(tween(200, delayMillis = 80, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                animationSpec = tween(200, delayMillis = 80, easing = FastOutSlowInEasing),
                                initialScale = 0.92f
                            ),
                        exit = scaleOut(
                            animationSpec = tween(160, easing = FastOutSlowInEasing),
                            targetScale = 0.92f
                        ) +
                            fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                            shrinkVertically(tween(240, delayMillis = 80, easing = FastOutSlowInEasing))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (hasVisibleBody) 12.dp else 0.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        if (isDragging) {
                                            shadowElevation = 12.dp.toPx()
                                            shape = RoundedCornerShape(16.dp)
                                            clip = false
                                        }
                                    }
                            ) {
                            when (widgetId) {
                                HomeWidgetId.QUERY_STATS -> Row(
                                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    Arrangement.spacedBy(12.dp)
                                ) {
                                    StatCard(
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        icon = Icons.Default.QueryStats,
                                        label = stringResource(R.string.total_queries),
                                        value = formatCount(totalCount),
                                        color = MaterialTheme.colorScheme.secondary,
                                        chartData = totalChartData,
                                        showChevron = true,
                                        onClick = { onNavigateToLogScreen(LogFilterStatus.ALL) }
                                    )
                                    StatCard(
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        icon = Icons.Default.Block,
                                        label = stringResource(R.string.blocked_queries),
                                        value = formatCount(blockedCount),
                                        color = DangerRed,
                                        chartData = blockedChartData,
                                        showChevron = true,
                                        onClick = { onNavigateToLogScreen(LogFilterStatus.BLOCKED) }
                                    )
                                }

                                HomeWidgetId.BLOCK_RATE -> HomeBlockRateCard(totalCount, blockedCount, domainCount)
                                HomeWidgetId.NETWORK_SPEED -> NetworkSpeedSection(networkSpeed)
                                HomeWidgetId.DATA_AND_UPTIME -> Row(
                                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    Arrangement.spacedBy(12.dp)
                                ) {
                                    StatCard(
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        icon = Icons.Default.DataSaverOn,
                                        label = stringResource(R.string.home_data_saved),
                                        value = formatDataSize(dataSavedKb),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    StatCard(
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        icon = Icons.Default.Timer,
                                        label = stringResource(R.string.home_protection_uptime),
                                        value = formatUptimeShort(protectionUptimeMs),
                                        color = AccentBlue
                                    )
                                }

                                HomeWidgetId.ACTIVITY_CHART -> {
                                    if (isEditing && hourlyStats.isEmpty() && dailyStats.isEmpty()) EmptyWidgetEditPlaceholder(widgetId)
                                    else HomeActivityChart(hourlyStats = hourlyStats, dailyStats = dailyStats)
                                }

                                HomeWidgetId.TOP_BLOCKED -> {
                                    if (isEditing && topBlockedDomains.isEmpty()) EmptyWidgetEditPlaceholder(widgetId)
                                    else TopBlockedSection(
                                        topBlockedDomains = topBlockedDomains,
                                        onDomainClick = { onSelectBlockedDomain(SelectedBlockedDomain(domain = it.domain, count = it.count)) }
                                    )
                                }

                                HomeWidgetId.RECENT_ACTIVITY -> RecentBlockedSection(
                                    recentBlocked = recentBlocked,
                                    securityFilterIds = securityFilterIds,
                                    currentFilter = recentFilter,
                                    onFilterChange = onRecentFilterChange,
                                    onViewAllClick = { onNavigateToLogScreen(LogFilterStatus.ALL) },
                                    onEntryClick = { entry ->
                                        onSelectBlockedDomain(
                                            SelectedBlockedDomain(
                                                domain = entry.domain, appName = entry.appName,
                                                packageName = entry.packageName, blockedBy = entry.blockedBy, isBlocked = entry.isBlocked
                                            )
                                        )
                                    }
                                )

                                HomeWidgetId.QUICK_ACTIONS -> HomeQuickActionsCard(
                                    vpnEnabled = vpnEnabled,
                                    onTestAdBlock = onTestAdBlock,
                                    onDnsLeakTest = onDnsLeakTest,
                                    onDomainRules = onNavigateToDomainRules,
                                    onTogglePauseProtection = onTogglePauseProtection,
                                )

                                HomeWidgetId.ACTIVE_DNS -> HomeActiveDnsCard(
                                    info = activeDnsInfo,
                                    onClick = onNavigateToDnsProvider,
                                )

                                HomeWidgetId.TOP_APPS -> {
                                    if (isEditing && topApps.isEmpty()) EmptyWidgetEditPlaceholder(widgetId)
                                    else HomeTopAppsCard(
                                        topApps = topApps,
                                        onAppClick = { onNavigateToAppLogs(it.appName) },
                                        onViewAllClick = onNavigateToStatistics,
                                    )
                                }

                                HomeWidgetId.SECURITY_SHIELD -> HomeSecurityShieldCard(
                                    info = securityShieldInfo,
                                    onFiltersClick = onNavigateToFilters,
                                    onHttpsClick = onNavigateToHttpsFiltering,
                                    onFirewallClick = onNavigateToFirewall,
                                )

                                HomeWidgetId.PROXY_VPN_STATUS -> HomeProxyVpnCard(
                                    info = proxyTunnelInfo,
                                    onConfigureClick = onNavigateToProxyVpn,
                                    onSwitchModeClick = onOpenRoutingModeSheet,
                                )

                                HomeWidgetId.BLOCK_REASONS -> {
                                    if (isEditing && blockReasons24h.isEmpty()) EmptyWidgetEditPlaceholder(widgetId)
                                    else HomeBlockReasonsCard(
                                        reasons = blockReasons24h,
                                        onViewStatsClick = onNavigateToStatistics,
                                    )
                                }

                                HomeWidgetId.TOP_COUNTRIES -> {
                                    if (isEditing && topCountries24h.isEmpty()) EmptyWidgetEditPlaceholder(widgetId)
                                    else HomeTopCountriesCard(
                                        countries = topCountries24h,
                                        onViewStatsClick = onNavigateToStatistics,
                                    )
                                }
                            }

                            if (isEditing) {
                                var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                                // Sibling input blocker disables inner card clicks and enables long-press drag.
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .onGloballyPositioned { overlayCoords = it }
                                        .clip(RoundedCornerShape(16.dp))
                                        .then(
                                            if (isDragging) {
                                                Modifier.border(
                                                    width = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(16.dp)
                                                )
                                            } else Modifier
                                        )
                                        .pointerInput(widgetId) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { startOffset ->
                                                    draggedWidget = widgetId
                                                    dragOffsetY = 0f
                                                    pointerWindowY = overlayCoords
                                                        ?.takeIf { it.isAttached }
                                                        ?.localToWindow(startOffset)
                                                        ?.y ?: Float.NaN
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    overlayCoords
                                                        ?.takeIf { it.isAttached }
                                                        ?.localToWindow(change.position)
                                                        ?.y
                                                        ?.let { pointerWindowY = it }
                                                    handleDragDelta(widgetId, dragAmount.y)
                                                },
                                                onDragEnd = {
                                                    draggedWidget = null
                                                    dragOffsetY = 0f
                                                    pointerWindowY = Float.NaN
                                                },
                                                onDragCancel = {
                                                    draggedWidget = null
                                                    dragOffsetY = 0f
                                                    pointerWindowY = Float.NaN
                                                }
                                            )
                                        }
                                )

                                if (activeWidgets.size > 1) {
                                    RemoveWidgetBadge(
                                        onRemove = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onRemoveWidget(widgetId)
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .offset(x = (-6).dp, y = (-6).dp)
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        }

        val availableWidgets = remember(activeWidgets) { HomeWidgetId.entries.filterNot { it in activeWidgets } }
        AvailableWidgetsSection(
            visible = isEditing,
            availableWidgets = availableWidgets,
            onAddWidget = onAddWidget,
        )
    }
}
