package app.pwhs.blockads.ui.home

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.DailyStat
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.data.DnsLogEntry
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.data.HourlyStat
import app.pwhs.blockads.data.ProtectionProfile
import app.pwhs.blockads.data.ProtectionProfileDao
import app.pwhs.blockads.data.TopBlockedDomain
import app.pwhs.blockads.service.AdBlockVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeViewModel(
    dnsLogDao: DnsLogDao,
    private val filterRepo: FilterListRepository,
    profileDao: ProtectionProfileDao,
) : ViewModel() {

    // Poll AdBlockVpnService.isRunning for immediate state updates
    private val _vpnEnabled = MutableStateFlow(AdBlockVpnService.isRunning)
    val vpnEnabled: StateFlow<Boolean> = _vpnEnabled.asStateFlow()

    private val _vpnConnecting = MutableStateFlow(AdBlockVpnService.isConnecting)
    val vpnConnecting: StateFlow<Boolean> = _vpnConnecting.asStateFlow()

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalCount: StateFlow<Int> = dnsLogDao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filterLoadFailed = MutableStateFlow(false)
    val filterLoadFailed: StateFlow<Boolean> = _filterLoadFailed.asStateFlow()

    private val _protectionUptimeMs = MutableStateFlow(0L)
    val protectionUptimeMs: StateFlow<Long> = _protectionUptimeMs.asStateFlow()

    val domainCount: Int get() = filterRepo.domainCount

    init {
        // Start polling VPN state
        viewModelScope.launch {
            while (isActive) {
                _vpnEnabled.value = AdBlockVpnService.isRunning
                _vpnConnecting.value = AdBlockVpnService.isConnecting
                val startTime = AdBlockVpnService.startTimestamp
                _protectionUptimeMs.value = if (AdBlockVpnService.isRunning && startTime > 0) {
                    System.currentTimeMillis() - startTime
                } else {
                    0L
                }
                delay(500)
            }
        }
    }

    fun startVpn(context: Context) {
        val intent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        }
        context.startService(intent)
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

