package app.pwhs.blockads.ui.home

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DailyStat
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.HourlyStat
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ShadowsocksProfile
import app.pwhs.blockads.data.entities.TopBlockedDomain
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.data.network.NetworkSpeed
import app.pwhs.blockads.data.network.NetworkSpeedMonitor
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.IptablesManager
import app.pwhs.blockads.service.NotificationHelper
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.service.VpnState
import app.pwhs.blockads.ui.home.component.ActiveFeatureBadge
import app.pwhs.blockads.ui.home.component.FeatureBadgeKind
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.ui.home.data.RecentLogFilter
import app.pwhs.blockads.utils.CustomRuleParser
import app.pwhs.blockads.utils.configIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class HomeViewModel(
    private val context: Context,
    private val appPrefs: AppPreferences,
    dnsLogDao: DnsLogDao,
    private val filterRepo: FilterListRepository,
    filterListDao: FilterListDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
) : ViewModel() {

    val whitelistedDomains: StateFlow<Set<String>> = whitelistDomainDao.getAll()
        .map { list -> list.filter { it.isEnabled }.map { it.domain.lowercase() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val routingMode: StateFlow<String> = appPrefs.routingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "local")

    val isRootAvailable: StateFlow<Boolean> = flow {
        emit(IptablesManager.isRootAvailable())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val activeWgProfile: StateFlow<WireGuardProfile?> = appPrefs.wgProfiles
        .combine(appPrefs.wgActiveProfileId) { profiles, activeId ->
            profiles.firstOrNull { it.id == activeId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeSsProfile: StateFlow<ShadowsocksProfile?> = appPrefs.ssProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeProxyProfile: StateFlow<ProxyProfile?> = appPrefs.activeProxyProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeProxyType: StateFlow<String> = appPrefs.activeProxyType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.PROXY_TYPE_WIREGUARD)

    // Trusted networks (#197): true when BlockAds was auto-paused on a
    // trusted Wi-Fi, so Home can show a distinct state instead of "Unprotected".
    val pausedByTrusted: StateFlow<Boolean> = appPrefs.pausedByTrusted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val pausedTrustedSsid: StateFlow<String> = appPrefs.pausedTrustedSsid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val vpnRevokedByAnotherApp: StateFlow<Boolean> = appPrefs.vpnRevokedByAnotherApp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val stabilityTipDismissed: StateFlow<Boolean> = appPrefs.stabilityTipDismissed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val stabilityTipDismissedAt: StateFlow<Long> = appPrefs.stabilityTipDismissedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val stabilityTipLastBatteryIgnored: StateFlow<Boolean> = appPrefs.stabilityTipLastBatteryIgnored
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val stabilityTipLastAlwaysOn: StateFlow<Boolean> = appPrefs.stabilityTipLastAlwaysOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun dismissVpnRevokedWarning() {
        viewModelScope.launch {
            appPrefs.setVpnRevokedByAnotherApp(false)
        }
    }

    fun dismissStabilityTip(batteryIgnored: Boolean = false, alwaysOn: Boolean = false) {
        markTipDismissedThisSession()
        viewModelScope.launch {
            appPrefs.recordStabilityTipDismissed(batteryIgnored, alwaysOn)
        }
    }

    fun updateStabilityTipHardwareState(batteryIgnored: Boolean, alwaysOn: Boolean) {
        viewModelScope.launch {
            appPrefs.updateStabilityTipHardwareState(batteryIgnored, alwaysOn)
        }
    }

    val dismissedFeatureTips: StateFlow<Set<String>> = appPrefs.dismissedFeatureTips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val homeTipsEnabled: StateFlow<Boolean> = appPrefs.homeTipsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _hasInteractedWithTipThisSession = MutableStateFlow(false)
    val hasInteractedWithTipThisSession: StateFlow<Boolean> = _hasInteractedWithTipThisSession.asStateFlow()

    fun markTipDismissedThisSession() {
        _hasInteractedWithTipThisSession.value = true
    }

    fun dismissFeatureTip(tipId: String) {
        markTipDismissedThisSession()
        viewModelScope.launch {
            appPrefs.dismissFeatureTip(tipId)
        }
    }

    // ── Reactive VPN state (derived from the single source of truth) ──
    val vpnEnabled: StateFlow<Boolean> = combine(
        AdBlockVpnService.state,
        RootProxyService.state
    ) { state1, state2 ->
        state1 == VpnState.RUNNING || state1 == VpnState.STOPPING ||
        state2 == VpnState.RUNNING || state2 == VpnState.STOPPING
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AdBlockVpnService.isRunning || AdBlockVpnService.isStopping || RootProxyService.isRunning
    )

    val vpnConnecting: StateFlow<Boolean> = combine(
        AdBlockVpnService.state,
        RootProxyService.state
    ) { state1, state2 ->
        state1 == VpnState.STARTING || state1 == VpnState.RESTARTING ||
        state2 == VpnState.STARTING || state2 == VpnState.RESTARTING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AdBlockVpnService.isConnecting)

    val vpnStopping: StateFlow<Boolean> = combine(
        AdBlockVpnService.state,
        RootProxyService.state
    ) { state1, state2 ->
        state1 == VpnState.STOPPING || state2 == VpnState.STOPPING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
        val protocol = core[0] as app.pwhs.blockads.data.entities.DnsProtocol
        val httpsEnabled = core[1] as Boolean
        val firewallEnabled = core[2] as Boolean
        val tailscaleEnabled = core[3] as Boolean
        buildList {
            add(
                ActiveFeatureBadge(
                    kind = FeatureBadgeKind.DNS_PROTOCOL,
                    label = when (protocol.name) {
                        "DOH" -> "DoH"
                        "DOT" -> "DoT"
                        "DOQ" -> "DoQ"
                        else -> "DNS"
                    }
                )
            )
            if (httpsEnabled) {
                add(ActiveFeatureBadge(FeatureBadgeKind.HTTPS_FILTER, "HTTPS"))
            }
            if (firewallEnabled) {
                add(ActiveFeatureBadge(FeatureBadgeKind.FIREWALL, "Firewall"))
            }
            if (tailscaleEnabled) {
                add(ActiveFeatureBadge(FeatureBadgeKind.TAILSCALE, "Tailscale"))
            }
            when (mode) {
                AppPreferences.ROUTING_MODE_WIREGUARD ->
                    add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "WireGuard"))
                AppPreferences.ROUTING_MODE_SHADOWSOCKS ->
                    add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "Shadowsocks"))
                AppPreferences.ROUTING_MODE_PROXY_VPN ->
                    add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "Proxy"))
                AppPreferences.ROUTING_MODE_ROOT ->
                    add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "Root"))
            }
            if (rulesCount > 0) {
                val formatted = if (rulesCount >= 1000) "${rulesCount / 1000}K" else "$rulesCount"
                add(ActiveFeatureBadge(FeatureBadgeKind.FILTER_RULES, formatted))
            }
        }.take(4)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalCount: StateFlow<Int> = dnsLogDao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _recentFilter = MutableStateFlow(RecentLogFilter.BLOCKED)
    val recentFilter: StateFlow<RecentLogFilter> = _recentFilter.asStateFlow()

    val recentLogs: StateFlow<List<DnsLogEntry>> = combine(
        _recentFilter,
        dnsLogDao.getRecentBlocked(5),
        dnsLogDao.getRecentLogs(5)
    ) { filter, blocked, all ->
        if (filter == RecentLogFilter.BLOCKED) blocked else all
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentBlocked: StateFlow<List<DnsLogEntry>> = recentLogs

    fun setRecentFilter(filter: RecentLogFilter) {
        _recentFilter.value = filter
    }

    val networkSpeed: StateFlow<NetworkSpeed> = NetworkSpeedMonitor.observeNetworkSpeed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkSpeed())

    val hourlyStats: StateFlow<List<HourlyStat>> = dnsLogDao.getHourlyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyStats: StateFlow<List<DailyStat>> = dnsLogDao.getDailyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topBlockedDomains: StateFlow<List<TopBlockedDomain>> = dnsLogDao.getTopBlockedDomains()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val milestoneReached: StateFlow<Long?> = combine(
        blockedCount,
        appPrefs.lastSeenMilestoneDialog,
        appPrefs.milestoneNotificationsEnabled
    ) { blocked, lastSeen, enabled ->
        NotificationHelper.unseenMilestone(blocked.toLong(), lastSeen, enabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun dismissMilestoneDialog(milestone: Long) {
        viewModelScope.launch {
            appPrefs.setLastSeenMilestoneDialog(milestone)
        }
    }

    val securityFilterIds: StateFlow<Set<String>> = filterListDao.getAll()
        .map { list -> list.filter { it.category == FilterList.CATEGORY_SECURITY }.map { it.id.toString() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filterLoadFailed = MutableStateFlow(false)
    val filterLoadFailed: StateFlow<Boolean> = _filterLoadFailed.asStateFlow()

    private val _protectionUptimeMs = MutableStateFlow(0L)
    val protectionUptimeMs: StateFlow<Long> = _protectionUptimeMs.asStateFlow()

    val domainCount: StateFlow<Int> = filterRepo.domainCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), filterRepo.domainCount)

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    fun stopVpn(context: Context) {
        if (RootProxyService.isRunning) {
            RootProxyService.stop(context)
        }
        if (AdBlockVpnService.isRunning) {
            val intent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    fun preloadFilter() {
        if (_isLoading.value || filterRepo.domainCount > 0) return // Already loaded or loading
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

    fun retryLoadFilter() {
        viewModelScope.launch {
            _isLoading.value = true
            _filterLoadFailed.value = false
            try {
                filterRepo.seedDefaultsIfNeeded()
                filterRepo.loadAllEnabledFilters()
                _filterLoadFailed.value = false
            } catch (e: Exception) {
                _filterLoadFailed.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addToWhitelist(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            val exists = whitelistDomainDao.exists(cleanDomain)
            if (exists == 0) {
                whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                filterRepo.loadWhitelist()
                _events.toast(R.string.log_whitelisted, listOf(": $cleanDomain"))
            } else {
                _events.toast(R.string.log_already_whitelisted)
            }
        }
    }

    fun removeFromWhitelist(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            whitelistDomainDao.deleteByDomain(cleanDomain)
            filterRepo.loadWhitelist()
            _events.toast(R.string.whitelist_domain_removed)
        }
    }

    fun addWildcardWhitelist(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            val domainRuleText = "@@||$cleanDomain^"
            val wildcardRuleText = "@@||*.$cleanDomain^"

            var addedAny = false
            if (customDnsRuleDao.exists(domainRuleText) == 0) {
                val domainRule = CustomRuleParser.parseRule(domainRuleText)
                if (domainRule != null) {
                    customDnsRuleDao.insert(domainRule)
                    addedAny = true
                }
            }
            if (customDnsRuleDao.exists(wildcardRuleText) == 0) {
                val wildcardRule = CustomRuleParser.parseRule(wildcardRuleText)
                if (wildcardRule != null) {
                    customDnsRuleDao.insert(wildcardRule)
                    addedAny = true
                }
            }
            if (addedAny) {
                filterRepo.loadCustomRules()
            }
            _events.toast(R.string.log_wildcard_whitelisted, listOf(cleanDomain))
        }
    }

    fun addToCustomBlockRules(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            val ruleText = CustomRuleParser.formatBlockRule(cleanDomain)
            val rule = CustomRuleParser.parseRule(ruleText)
            if (rule != null) {
                if (customDnsRuleDao.exists(rule.rule) == 0) {
                    customDnsRuleDao.insert(rule)
                    filterRepo.loadCustomRules()
                }
                _events.toast(R.string.rule_added)
            }
        }
    }

    fun setRoutingMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldMode = appPrefs.routingMode.first()
            if (oldMode == mode) return@launch

            if (mode == AppPreferences.ROUTING_MODE_ROOT) {
                if (!IptablesManager.isRootAvailable()) {
                    _events.toast(R.string.root_not_available)
                    return@launch
                }
            }

            if (mode == AppPreferences.ROUTING_MODE_PROXY_VPN || mode == AppPreferences.ROUTING_MODE_WIREGUARD) {
                var activeType = appPrefs.getActiveProxyTypeSnapshot()
                val activeWg = appPrefs.getActiveWgProfileSnapshot()
                val activeProxy = appPrefs.getActiveProxyProfileSnapshot() ?: appPrefs.getSsProfileSnapshot()

                if (activeType == AppPreferences.PROXY_TYPE_WIREGUARD && activeWg == null && activeProxy != null) {
                    activeType = AppPreferences.PROXY_TYPE_SHADOWSOCKS
                    appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_SHADOWSOCKS)
                } else if (activeType != AppPreferences.PROXY_TYPE_WIREGUARD && activeProxy == null && activeWg != null) {
                    activeType = AppPreferences.PROXY_TYPE_WIREGUARD
                    appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_WIREGUARD)
                }

                if (activeType == AppPreferences.PROXY_TYPE_WIREGUARD || mode == AppPreferences.ROUTING_MODE_WIREGUARD) {
                    val active = activeWg
                    if (active == null) {
                        _events.toast(R.string.routing_mode_no_proxy_profile)
                        return@launch
                    }
                    active.configIssue?.let { issue ->
                        _events.toast(R.string.wireguard_issue_fix_first, listOf(active.name, issue))
                        return@launch
                    }
                } else {
                    if (activeProxy == null) {
                        _events.toast(R.string.routing_mode_no_proxy_profile)
                        return@launch
                    }
                }
                if (appPrefs.getHttpsFilteringEnabledSnapshot()) {
                    appPrefs.setHttpsFilteringEnabled(false)
                    _events.toast(R.string.wireguard_https_filtering_disabled)
                }
            }

            if (mode == AppPreferences.ROUTING_MODE_SHADOWSOCKS) {
                val active = appPrefs.getSsProfileSnapshot()
                if (active == null) {
                    _events.toast(R.string.routing_mode_no_ss_profile)
                    return@launch
                }
                if (appPrefs.getHttpsFilteringEnabledSnapshot()) {
                    appPrefs.setHttpsFilteringEnabled(false)
                    _events.toast(R.string.wireguard_https_filtering_disabled)
                }
            }

            appPrefs.setRoutingMode(mode)

            val isRunning = AdBlockVpnService.isRunning || RootProxyService.isRunning
            if (!isRunning) return@launch

            val oldWasRoot = oldMode == AppPreferences.ROUTING_MODE_ROOT
            val newIsRoot = mode == AppPreferences.ROUTING_MODE_ROOT

            if (oldWasRoot != newIsRoot) {
                if (newIsRoot) {
                    val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
                        action = AdBlockVpnService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                    delay(800)
                    RootProxyService.start(context)
                } else {
                    RootProxyService.stop(context)
                    delay(800)
                    val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
                        action = AdBlockVpnService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                }
            } else {
                ServiceController.requestRestart(context)
            }
        }
    }

    fun saveShadowsocksUri(uri: String): Result<ShadowsocksProfile> {
        return try {
            val json = tunnel.Tunnel.parseShadowsocksURI(uri)
            val profile = ShadowsocksProfile.fromJson(json).copy(rawUri = uri)
            viewModelScope.launch(Dispatchers.IO) {
                if (appPrefs.getHttpsFilteringEnabledSnapshot()) {
                    appPrefs.setHttpsFilteringEnabled(false)
                    _events.toast(R.string.wireguard_https_filtering_disabled)
                }
                appPrefs.setSsProfile(profile)
                appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_SHADOWSOCKS)
                ServiceController.requestRestart(context)
            }
            Result.success(profile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse and save Shadowsocks URI")
            Result.failure(e)
        }
    }
}
