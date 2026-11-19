package app.pwhs.blockads.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DailyStat
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.HourlyStat
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ShadowsocksProfile
import app.pwhs.blockads.data.entities.TopBlockedDomain
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.data.network.NetworkSpeed
import app.pwhs.blockads.data.network.NetworkSpeedMonitor
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.IptablesManager
import app.pwhs.blockads.service.NotificationHelper
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.VpnState
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.home.component.ActiveFeatureBadge
import app.pwhs.blockads.ui.home.data.HomeWidgetId
import app.pwhs.blockads.ui.home.data.RecentLogFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class HomeViewModel(
    context: Context,
    private val appPrefs: AppPreferences,
    dnsLogDao: DnsLogDao,
    private val filterRepo: FilterListRepository,
    filterListDao: FilterListDao,
    whitelistDomainDao: WhitelistDomainDao,
    customDnsRuleDao: CustomDnsRuleDao,
) : ViewModel() {

    private fun <T> Flow<T>.homeState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val whitelistedDomains: StateFlow<Set<String>> = whitelistDomainDao.getAll()
        .map { list -> list.filter { it.isEnabled }.map { it.domain.lowercase() }.toSet() }
        .homeState(emptySet())

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val routingDelegate = HomeRoutingDelegate(
        context = context,
        scope = viewModelScope,
        appPrefs = appPrefs,
        filterRepo = filterRepo,
        whitelistDomainDao = whitelistDomainDao,
        customDnsRuleDao = customDnsRuleDao,
        events = _events,
    )

    private val _isEditingHome = MutableStateFlow(false)
    val isEditingHome: StateFlow<Boolean> = _isEditingHome.asStateFlow()

    // Keep drag swaps instantaneous in-memory while DataStore persists asynchronously.
    private val _optimisticHomeWidgets = MutableStateFlow<List<HomeWidgetId>?>(null)
    private val activeWidgetWrites = AtomicInteger(0)
    private var lastPersistedWidgets: List<HomeWidgetId>? = null

    val activeHomeWidgets: StateFlow<List<HomeWidgetId>> = combine(
        appPrefs.homeWidgets
            .map { HomeWidgetId.fromSerialized(it) }
            .onEach { persisted ->
                lastPersistedWidgets = persisted
                if (activeWidgetWrites.get() == 0 && persisted == _optimisticHomeWidgets.value) {
                    _optimisticHomeWidgets.value = null
                }
            }
            .onStart { emit(HomeWidgetId.DEFAULT_ORDER) },
        _optimisticHomeWidgets,
    ) { persisted, optimistic ->
        optimistic ?: persisted
    }.homeState(HomeWidgetId.DEFAULT_ORDER)

    val routingMode: StateFlow<String> = appPrefs.routingMode.homeState("local")

    val isRootAvailable: StateFlow<Boolean> = flow {
        emit(IptablesManager.isRootAvailable())
    }.homeState(false)

    val activeWgProfile: StateFlow<WireGuardProfile?> = appPrefs.wgProfiles
        .combine(appPrefs.wgActiveProfileId) { profiles, activeId ->
            profiles.firstOrNull { it.id == activeId }
        }.homeState(null)

    val activeSsProfile: StateFlow<ShadowsocksProfile?> = appPrefs.ssProfile.homeState(null)
    val activeProxyProfile: StateFlow<ProxyProfile?> = appPrefs.activeProxyProfile.homeState(null)
    val activeProxyType: StateFlow<String> = appPrefs.activeProxyType.homeState(AppPreferences.PROXY_TYPE_WIREGUARD)

    // Trusted networks (#197): true when BlockAds was auto-paused on a
    // trusted Wi-Fi, so Home can show a distinct state instead of "Unprotected".
    val pausedByTrusted: StateFlow<Boolean> = appPrefs.pausedByTrusted.homeState(false)
    val pausedTrustedSsid: StateFlow<String> = appPrefs.pausedTrustedSsid.homeState("")
    val vpnRevokedByAnotherApp: StateFlow<Boolean> = appPrefs.vpnRevokedByAnotherApp.homeState(false)
    val stabilityTipDismissed: StateFlow<Boolean> = appPrefs.stabilityTipDismissed.homeState(false)
    val stabilityTipDismissedAt: StateFlow<Long> = appPrefs.stabilityTipDismissedAt.homeState(0L)
    val stabilityTipLastBatteryIgnored: StateFlow<Boolean> = appPrefs.stabilityTipLastBatteryIgnored.homeState(false)
    val stabilityTipLastAlwaysOn: StateFlow<Boolean> = appPrefs.stabilityTipLastAlwaysOn.homeState(false)

    fun dismissVpnRevokedWarning() {
        viewModelScope.launch { appPrefs.setVpnRevokedByAnotherApp(false) }
    }

    fun dismissStabilityTip(batteryIgnored: Boolean = false, alwaysOn: Boolean = false) {
        markTipDismissedThisSession()
        viewModelScope.launch { appPrefs.recordStabilityTipDismissed(batteryIgnored, alwaysOn) }
    }

    fun updateStabilityTipHardwareState(batteryIgnored: Boolean, alwaysOn: Boolean) {
        viewModelScope.launch { appPrefs.updateStabilityTipHardwareState(batteryIgnored, alwaysOn) }
    }

    val dismissedFeatureTips: StateFlow<Set<String>> = appPrefs.dismissedFeatureTips.homeState(emptySet())
    val homeTipsEnabled: StateFlow<Boolean> = appPrefs.homeTipsEnabled.homeState(true)

    private val _hasInteractedWithTipThisSession = MutableStateFlow(false)
    val hasInteractedWithTipThisSession: StateFlow<Boolean> = _hasInteractedWithTipThisSession.asStateFlow()

    fun markTipDismissedThisSession() {
        _hasInteractedWithTipThisSession.value = true
    }

    fun dismissFeatureTip(tipId: String) {
        markTipDismissedThisSession()
        viewModelScope.launch { appPrefs.dismissFeatureTip(tipId) }
    }

    // ── Reactive VPN state (derived from the single source of truth) ──
    val vpnEnabled: StateFlow<Boolean> = combine(
        AdBlockVpnService.state,
        RootProxyService.state
    ) { state1, state2 ->
        state1 == VpnState.RUNNING || state1 == VpnState.STOPPING ||
            state2 == VpnState.RUNNING || state2 == VpnState.STOPPING
    }.homeState(AdBlockVpnService.isRunning || AdBlockVpnService.isStopping || RootProxyService.isRunning)

    val vpnConnecting: StateFlow<Boolean> = combine(
        AdBlockVpnService.state,
        RootProxyService.state
    ) { state1, state2 ->
        state1 == VpnState.STARTING || state1 == VpnState.RESTARTING ||
            state2 == VpnState.STARTING || state2 == VpnState.RESTARTING
    }.homeState(AdBlockVpnService.isConnecting)

    val vpnStopping: StateFlow<Boolean> = combine(
        AdBlockVpnService.state,
        RootProxyService.state
    ) { state1, state2 ->
        state1 == VpnState.STOPPING || state2 == VpnState.STOPPING
    }.homeState(false)

    val vpnPhase: StateFlow<String> = AdBlockVpnService.status.phase

    val activeFeatureBadges: StateFlow<List<ActiveFeatureBadge>> = combine(
        combine(
            appPrefs.dnsProtocol,
            appPrefs.httpsFilteringEnabled,
            appPrefs.firewallEnabled,
            appPrefs.tailscale.enabled
        ) { protocol, httpsEnabled, firewallEnabled, tailscaleEnabled ->
            listOf(protocol, httpsEnabled, firewallEnabled, tailscaleEnabled)
        },
        appPrefs.routingMode,
        filterRepo.domainCountFlow
    ) { core, mode, rulesCount ->
        buildActiveFeatureBadges(
            protocol = core[0] as DnsProtocol,
            httpsEnabled = core[1] as Boolean,
            firewallEnabled = core[2] as Boolean,
            tailscaleEnabled = core[3] as Boolean,
            mode = mode,
            rulesCount = rulesCount,
        )
    }.homeState(emptyList())

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCount().homeState(0)
    val totalCount: StateFlow<Int> = dnsLogDao.getTotalCount().homeState(0)

    private val _recentFilter = MutableStateFlow(RecentLogFilter.BLOCKED)
    val recentFilter: StateFlow<RecentLogFilter> = _recentFilter.asStateFlow()

    val recentLogs: StateFlow<List<DnsLogEntry>> = combine(
        _recentFilter,
        dnsLogDao.getRecentBlocked(5),
        dnsLogDao.getRecentLogs(5)
    ) { filter, blocked, all ->
        if (filter == RecentLogFilter.BLOCKED) blocked else all
    }.homeState(emptyList())

    val recentBlocked: StateFlow<List<DnsLogEntry>> = recentLogs

    fun setRecentFilter(filter: RecentLogFilter) {
        _recentFilter.value = filter
    }

    val networkSpeed: StateFlow<NetworkSpeed> = NetworkSpeedMonitor.observeNetworkSpeed().homeState(NetworkSpeed())
    val hourlyStats: StateFlow<List<HourlyStat>> = dnsLogDao.getHourlyStats().homeState(emptyList())
    val dailyStats: StateFlow<List<DailyStat>> = dnsLogDao.getDailyStats().homeState(emptyList())
    val topBlockedDomains: StateFlow<List<TopBlockedDomain>> = dnsLogDao.getTopBlockedDomains().homeState(emptyList())

    val milestoneReached: StateFlow<Long?> = combine(
        blockedCount,
        appPrefs.lastSeenMilestoneDialog,
        appPrefs.milestoneNotificationsEnabled
    ) { blocked, lastSeen, enabled ->
        NotificationHelper.unseenMilestone(blocked.toLong(), lastSeen, enabled)
    }.homeState(null)

    fun dismissMilestoneDialog(milestone: Long) {
        viewModelScope.launch { appPrefs.setLastSeenMilestoneDialog(milestone) }
    }

    val securityFilterIds: StateFlow<Set<String>> = filterListDao.getAll()
        .map { list -> list.filter { it.category == FilterList.CATEGORY_SECURITY }.map { it.id.toString() }.toSet() }
        .homeState(emptySet())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filterLoadFailed = MutableStateFlow(false)
    val filterLoadFailed: StateFlow<Boolean> = _filterLoadFailed.asStateFlow()

    private val _protectionUptimeMs = MutableStateFlow(0L)
    val protectionUptimeMs: StateFlow<Long> = _protectionUptimeMs.asStateFlow()

    val domainCount: StateFlow<Int> = filterRepo.domainCountFlow.homeState(filterRepo.domainCount)

    // Warn when protection is on in VPN mode but Android Private DNS (Strict
    // DoT) is active — it bypasses BlockAds' DNS interception, so filtering
    // silently doesn't apply. Root Proxy mode disables Private DNS itself, so
    // the warning is VPN-mode only (#145).
    val privateDnsWarning: StateFlow<Boolean> = combine(
        vpnEnabled,
        routingMode,
        AdBlockVpnService.privateDnsStrict
    ) { enabled, mode, strict ->
        enabled && mode != AppPreferences.ROUTING_MODE_ROOT && strict
    }.homeState(false)

    init {
        // Uptime ticker — only ticks while VPN or Root Proxy is RUNNING
        viewModelScope.launch {
            while (isActive) {
                var uptime = 0L
                if (AdBlockVpnService.isRunning && AdBlockVpnService.startTimestamp > 0) {
                    uptime = System.currentTimeMillis() - AdBlockVpnService.startTimestamp
                } else if (RootProxyService.isRunning && RootProxyService.startTimestamp > 0) {
                    uptime = System.currentTimeMillis() - RootProxyService.startTimestamp
                }
                _protectionUptimeMs.value = uptime
                delay(1000)
            }
        }
    }

    fun toggleEditHome() {
        _isEditingHome.value = !_isEditingHome.value
    }

    fun setEditingHome(editing: Boolean) {
        _isEditingHome.value = editing
    }

    fun moveHomeWidget(fromIndex: Int, toIndex: Int) {
        val current = (_optimisticHomeWidgets.value ?: activeHomeWidgets.value).toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val moved = current.removeAt(fromIndex)
        current.add(toIndex, moved)
        persistHomeWidgets(current)
    }

    fun removeHomeWidget(widget: HomeWidgetId) {
        val current = _optimisticHomeWidgets.value ?: activeHomeWidgets.value
        if (current.size <= 1 || widget !in current) return
        persistHomeWidgets(current - widget)
    }

    fun addHomeWidget(widget: HomeWidgetId) {
        val current = _optimisticHomeWidgets.value ?: activeHomeWidgets.value
        if (widget in current) return
        persistHomeWidgets(current + widget)
    }

    fun resetHomeWidgets() {
        persistHomeWidgets(HomeWidgetId.DEFAULT_ORDER)
    }

    private fun persistHomeWidgets(updated: List<HomeWidgetId>) {
        _optimisticHomeWidgets.value = updated
        activeWidgetWrites.incrementAndGet()
        viewModelScope.launch {
            try {
                appPrefs.setHomeWidgets(HomeWidgetId.serialize(updated))
            } finally {
                if (activeWidgetWrites.decrementAndGet() == 0 && lastPersistedWidgets == _optimisticHomeWidgets.value) {
                    _optimisticHomeWidgets.value = null
                }
            }
        }
    }

    fun stopVpn(context: Context) {
        if (RootProxyService.isRunning) RootProxyService.stop(context)
        if (AdBlockVpnService.isRunning) {
            val intent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    fun preloadFilter() {
        if (_isLoading.value || filterRepo.domainCount > 0) return
        loadFilters()
    }

    fun retryLoadFilter() = loadFilters()

    private fun loadFilters() {
        viewModelScope.launch {
            _isLoading.value = true
            _filterLoadFailed.value = false
            try {
                filterRepo.seedDefaultsIfNeeded()
                filterRepo.loadAllEnabledFilters()
                _filterLoadFailed.value = false
            } catch (e: Exception) {
                Timber.e(e)
                _filterLoadFailed.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addToWhitelist(domain: String) = routingDelegate.addToWhitelist(domain)
    fun removeFromWhitelist(domain: String) = routingDelegate.removeFromWhitelist(domain)
    fun addWildcardWhitelist(domain: String) = routingDelegate.addWildcardWhitelist(domain)
    fun addToCustomBlockRules(domain: String) = routingDelegate.addToCustomBlockRules(domain)
    fun setRoutingMode(mode: String) = routingDelegate.setRoutingMode(mode)
    fun saveShadowsocksUri(uri: String): Result<ShadowsocksProfile> = routingDelegate.saveShadowsocksUri(uri)
}
