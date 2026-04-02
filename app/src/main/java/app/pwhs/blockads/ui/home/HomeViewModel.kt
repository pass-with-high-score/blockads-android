package app.pwhs.blockads.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.entities.DailyStat
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.HourlyStat
import app.pwhs.blockads.data.entities.ProtectionProfile
import app.pwhs.blockads.data.entities.TopBlockedDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.VpnState
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class HomeViewModel(
    appPrefs: AppPreferences,
    dnsLogDao: DnsLogDao,
    private val filterRepo: FilterListRepository,
    profileDao: ProtectionProfileDao,
    filterListDao: FilterListDao,
) : ViewModel() {

    val routingMode: StateFlow<String> = appPrefs.routingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "local")

    // ── Reactive VPN state (derived from the single source of truth) ──
    val vpnEnabled: StateFlow<Boolean> = combine(
        AdBlockVpnService.state,
        RootProxyService.state
    ) { state1, state2 ->
        state1 == VpnState.RUNNING || state2 == VpnState.RUNNING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AdBlockVpnService.isRunning || RootProxyService.isRunning)

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

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalCount: StateFlow<Int> = dnsLogDao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val securityThreatsBlocked: StateFlow<Int> = dnsLogDao.getBlockedCountByReason(
        FilterListRepository.BLOCK_REASON_SECURITY
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentBlocked: StateFlow<List<DnsLogEntry>> =
        dnsLogDao.getRecentBlocked()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hourlyStats: StateFlow<List<HourlyStat>> = dnsLogDao.getHourlyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyStats: StateFlow<List<DailyStat>> = dnsLogDao.getDailyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topBlockedDomains: StateFlow<List<TopBlockedDomain>> = dnsLogDao.getTopBlockedDomains()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfile: StateFlow<ProtectionProfile?> = profileDao.getActiveFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

}
