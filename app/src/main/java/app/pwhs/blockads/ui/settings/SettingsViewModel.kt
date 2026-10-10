package app.pwhs.blockads.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.FilterListBackup
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.data.entities.FirewallRuleBackup
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.SettingsBackup
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.utils.CustomRuleParser
import app.pwhs.blockads.worker.DailySummaryScheduler
import app.pwhs.blockads.worker.FilterUpdateScheduler
import app.pwhs.blockads.service.IptablesManager
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.utils.CrashReportingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appPrefs: AppPreferences,
    private val filterRepo: FilterListRepository,
    private val dnsLogDao: DnsLogDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val filterListDao: FilterListDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val firewallRuleDao: FirewallRuleDao,
    private val backupManager: app.pwhs.blockads.utils.SettingsBackupManager? = null,
    application: Application,
) : AndroidViewModel(application) {

    val autoReconnect: StateFlow<Boolean> = appPrefs.autoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val crashReportingEnabled: StateFlow<Boolean> = appPrefs.crashReportingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hideFromRecents: StateFlow<Boolean> = appPrefs.hideFromRecents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoUpdateEnabled: StateFlow<Boolean> = appPrefs.autoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoUpdateFrequency: StateFlow<String> = appPrefs.autoUpdateFrequency
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.UPDATE_FREQUENCY_24H
        )

    val autoUpdateWifiOnly: StateFlow<Boolean> = appPrefs.autoUpdateWifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoUpdateNotification: StateFlow<String> = appPrefs.autoUpdateNotification
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.NOTIFICATION_NORMAL
        )

    val dnsResponseType: StateFlow<String> = appPrefs.dnsResponseType
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DNS_RESPONSE_CUSTOM_IP
        )

    val safeSearchEnabled: StateFlow<Boolean> = appPrefs.safeSearchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    val youtubeRestrictedMode: StateFlow<Boolean> = appPrefs.youtubeRestrictedMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dailySummaryEnabled: StateFlow<Boolean> = appPrefs.dailySummaryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val milestoneNotificationsEnabled: StateFlow<Boolean> = appPrefs.milestoneNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val upstreamDns: StateFlow<String> = kotlinx.coroutines.flow.combine(
        appPrefs.dnsProviderId,
        appPrefs.upstreamDns
    ) { id, upstream ->
        if (id == AppPreferences.CUSTOM_DNS_PROVIDER_ID) {
            upstream
        } else {
            app.pwhs.blockads.data.entities.DnsProviders.getById(id ?: "")?.name ?: upstream
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_UPSTREAM_DNS)


    val routingMode: StateFlow<String> = appPrefs.routingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.ROUTING_MODE_DIRECT)

    val excludeLan: StateFlow<Boolean> = appPrefs.excludeLan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            filterRepo.seedDefaultsIfNeeded()
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setAutoReconnect(enabled) }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setCrashReportingEnabled(enabled)
            CrashReportingManager.toggleSentry(getApplication(), enabled)
        }
    }

    fun setHideFromRecents(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setHideFromRecents(enabled) }
    }


    fun setExcludeLan(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setExcludeLan(enabled)
            requestVpnRestart()
        }
    }

    fun setRoutingModeEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                if (IptablesManager.isRootAvailable()) {
                    applyRoutingMode(AppPreferences.ROUTING_MODE_ROOT)
                } else {
                    _events.toast(R.string.root_not_available)
                }
            } else {
                applyRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
            }
        }
    }

    private suspend fun applyRoutingMode(mode: String) {
        val oldMode = appPrefs.routingMode.first()
        if (oldMode == mode) return

        appPrefs.setRoutingMode(mode)
        val context = getApplication<Application>().applicationContext

        val isRoot = mode == AppPreferences.ROUTING_MODE_ROOT

        if (AdBlockVpnService.isRunning || RootProxyService.isRunning) {
            if (isRoot) {
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
        }
    }

    private suspend fun scheduleUpdates() {
        val context = getApplication<Application>().applicationContext
        FilterUpdateScheduler.scheduleFilterUpdate(context, appPrefs)
        app.pwhs.blockads.worker.ConfigUpdateScheduler.scheduleConfigUpdate(context, appPrefs)
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateEnabled(enabled)
            scheduleUpdates()
        }
    }

    fun setAutoUpdateFrequency(frequency: String) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateFrequency(frequency)
            scheduleUpdates()
        }
    }

    fun setAutoUpdateWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateWifiOnly(wifiOnly)
            scheduleUpdates()
        }
    }

    fun setAutoUpdateNotification(notificationType: String) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateNotification(notificationType)
        }
    }

    fun setDnsResponseType(responseType: String) {
        viewModelScope.launch {
            appPrefs.setDnsResponseType(responseType)
            requestVpnRestart()
        }
    }

    fun setSafeSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setSafeSearchEnabled(enabled)
            requestVpnRestart()
        }
    }


    fun setYoutubeRestrictedMode(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setYoutubeRestrictedMode(enabled)
            requestVpnRestart()
        }
    }

    fun setDailySummaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setDailySummaryEnabled(enabled)
            if (enabled) {
                DailySummaryScheduler.scheduleDailySummary(
                    getApplication<Application>().applicationContext
                )
            } else {
                DailySummaryScheduler.cancelDailySummary(
                    getApplication<Application>().applicationContext
                )
            }
        }
    }

    fun setMilestoneNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setMilestoneNotificationsEnabled(enabled)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            dnsLogDao.clearAll()
            _events.toast(R.string.filter_log_cleared)
        }
    }

    // ── Export Settings ──────────────────────────────────────────────
    fun exportSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                val mgr = backupManager ?: createFallbackBackupManager()
                mgr.exportBackup(uri)
                _events.toast(R.string.filter_settings_export)
            } catch (e: Exception) {
                _events.toast(R.string.filter_export_failed, listOf("${e.message}"))
            }
        }
    }

    // ── Import Settings ──────────────────────────────────────────────
    fun importSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                val mgr = backupManager ?: createFallbackBackupManager()
                mgr.importBackup(uri)
                _events.toast(R.string.filter_settings_imported)
                requestVpnRestart()
            } catch (e: Exception) {
                _events.toast(R.string.filter_import_failed, listOf("${e.message}"))
            }
        }
    }

    private fun createFallbackBackupManager(): app.pwhs.blockads.utils.SettingsBackupManager {
        val configDao = try {
            org.koin.java.KoinJavaComponent.get<app.pwhs.blockads.data.dao.ConfigDao>(app.pwhs.blockads.data.dao.ConfigDao::class.java)
        } catch (_: Exception) {
            app.pwhs.blockads.data.AppDatabase.getInstance(getApplication()).configDao()
        }
        return app.pwhs.blockads.utils.SettingsBackupManager(
            context = getApplication(),
            appPrefs = appPrefs,
            filterListDao = filterListDao,
            whitelistDomainDao = whitelistDomainDao,
            customDnsRuleDao = customDnsRuleDao,
            firewallRuleDao = firewallRuleDao,
            configDao = configDao,
            filterRepo = filterRepo
        )
    }

    private fun requestVpnRestart() {
        ServiceController.requestRestart(getApplication<Application>().applicationContext)
    }
}
