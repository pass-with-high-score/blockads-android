package app.pwhs.blockads.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DailyStat
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.data.entities.HourlyStat
import app.pwhs.blockads.data.entities.ProtectionProfile
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.entities.TopBlockedDomain
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.ui.home.data.AvailableUpdate
import app.pwhs.blockads.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeViewModel(
    dnsLogDao: DnsLogDao,
    private val filterRepo: FilterListRepository,
    profileDao: ProtectionProfileDao,
    private val appPreferences: AppPreferences,
    private val updateChecker: UpdateChecker,
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filterLoadFailed = MutableStateFlow(false)
    val filterLoadFailed: StateFlow<Boolean> = _filterLoadFailed.asStateFlow()

    private val _protectionUptimeMs = MutableStateFlow(0L)
    val protectionUptimeMs: StateFlow<Long> = _protectionUptimeMs.asStateFlow()

    val domainCount: Int get() = filterRepo.domainCount

    // Update notification — combines stored update info with dismissed version
    val availableUpdate: StateFlow<AvailableUpdate?> = combine(
        appPreferences.availableUpdateVersion,
        appPreferences.availableUpdateChangelog,
        appPreferences.availableUpdateUrl,
        appPreferences.dismissedUpdateVersion
    ) { version, changelog, url, dismissed ->
        if (version.isNotBlank() && version != BuildConfig.VERSION_NAME && version != dismissed) {
            AvailableUpdate(version, changelog, url)
        } else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    fun dismissUpdate() {
        val update = availableUpdate.value ?: return
        viewModelScope.launch {
            appPreferences.setDismissedUpdateVersion(update.version)
        }
    }

    fun checkForUpdate(context: Context) {
        viewModelScope.launch {
            val update = updateChecker.checkForUpdate(context)
            if (update != null) {
                appPreferences.setAvailableUpdateVersion(update.latestVersion)
                appPreferences.setAvailableUpdateChangelog(update.changelog)
                appPreferences.setAvailableUpdateUrl(update.webUrl)
            }
        }
    }
}
