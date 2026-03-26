package app.pwhs.blockads.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.FilterListBackup
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.data.entities.FirewallRuleBackup
import app.pwhs.blockads.data.entities.ProfileManager
import app.pwhs.blockads.data.entities.SettingsBackup
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.utils.CustomRuleParser
import app.pwhs.blockads.worker.DailySummaryScheduler
import app.pwhs.blockads.worker.FilterUpdateScheduler
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
    private val profileDao: ProtectionProfileDao,
    private val profileManager: ProfileManager,
    private val firewallRuleDao: FirewallRuleDao,
    application: Application,
) : AndroidViewModel(application) {

    val autoReconnect: StateFlow<Boolean> = appPrefs.autoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whitelistDomains: StateFlow<List<WhitelistDomain>> = whitelistDomainDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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

    val upstreamDns: StateFlow<String> = appPrefs.upstreamDns
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_UPSTREAM_DNS
        )

    val networkSwitchDelayEnabled: StateFlow<Boolean> = appPrefs.networkSwitchDelayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val networkSwitchDelaySec: StateFlow<Int> = appPrefs.networkSwitchDelaySec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    val routingMode: StateFlow<String> = appPrefs.routingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.ROUTING_MODE_DIRECT)

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

    fun setNetworkSwitchDelayEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setNetworkSwitchDelayEnabled(enabled) }
    }

    fun setNetworkSwitchDelaySec(seconds: Int) {
        viewModelScope.launch { appPrefs.setNetworkSwitchDelaySec(seconds) }
    }

    fun setRoutingMode(mode: String) {
        viewModelScope.launch { 
            val oldMode = appPrefs.routingMode.first()
            if (oldMode == mode) return@launch

            appPrefs.setRoutingMode(mode) 
            val context = getApplication<Application>().applicationContext
            
            val wasRoot = oldMode == AppPreferences.ROUTING_MODE_ROOT
            val isRoot = mode == AppPreferences.ROUTING_MODE_ROOT

            if (AdBlockVpnService.isRunning || app.pwhs.blockads.service.RootProxyService.isRunning) {
                if (wasRoot && !isRoot) {
                    app.pwhs.blockads.service.RootProxyService.stop(context)
                    kotlinx.coroutines.delay(800)
                    val startIntent = android.content.Intent(context, AdBlockVpnService::class.java).apply {
                        action = AdBlockVpnService.ACTION_START
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                } else if (!wasRoot && isRoot) {
                    val stopIntent = android.content.Intent(context, AdBlockVpnService::class.java).apply {
                        action = AdBlockVpnService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                    kotlinx.coroutines.delay(800)
                    app.pwhs.blockads.service.RootProxyService.start(context)
                } else {
                    requestVpnRestart()
                }
            }
        }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateEnabled(enabled)
            FilterUpdateScheduler.scheduleFilterUpdate(
                getApplication<Application>().applicationContext,
                appPrefs
            )
        }
    }

    fun setAutoUpdateFrequency(frequency: String) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateFrequency(frequency)
            FilterUpdateScheduler.scheduleFilterUpdate(
                getApplication<Application>().applicationContext,
                appPrefs
            )
        }
    }

    fun setAutoUpdateWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateWifiOnly(wifiOnly)
            FilterUpdateScheduler.scheduleFilterUpdate(
                getApplication<Application>().applicationContext,
                appPrefs
            )
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
                val activeProfile = profileDao.getActive()
                val backup = SettingsBackup(
                    upstreamDns = appPrefs.upstreamDns.first(),
                    fallbackDns = appPrefs.fallbackDns.first(),
                    autoReconnect = appPrefs.autoReconnect.first(),
                    themeMode = appPrefs.themeMode.first(),
                    appLanguage = appPrefs.appLanguage.first(),
                    safeSearchEnabled = appPrefs.safeSearchEnabled.first(),
                    youtubeRestrictedMode = appPrefs.youtubeRestrictedMode.first(),
                    dailySummaryEnabled = appPrefs.dailySummaryEnabled.first(),
                    milestoneNotificationsEnabled = appPrefs.milestoneNotificationsEnabled.first(),
                    activeProfileType = activeProfile?.profileType ?: "",
                    firewallEnabled = appPrefs.firewallEnabled.first(),
                    filterLists = filterLists.value.map { f ->
                        FilterListBackup(name = f.name, url = f.url, isEnabled = f.isEnabled)
                    },
                    whitelistDomains = whitelistDomains.value.map { it.domain },
                    whitelistedApps = appPrefs.getWhitelistedAppsSnapshot().toList(),
                    customRules = customDnsRuleDao.getAll().map { it.rule },
                    firewallRules = firewallRuleDao.getEnabledRules().map { r ->
                        FirewallRuleBackup(
                            packageName = r.packageName,
                            blockWifi = r.blockWifi,
                            blockMobileData = r.blockMobileData,
                            scheduleEnabled = r.scheduleEnabled,
                            scheduleStartHour = r.scheduleStartHour,
                            scheduleStartMinute = r.scheduleStartMinute,
                            scheduleEndHour = r.scheduleEndHour,
                            scheduleEndMinute = r.scheduleEndMinute,
                            isEnabled = r.isEnabled
                        )
                    }
                )

                val jsonFormat = kotlinx.serialization.json.Json { prettyPrint = true }
                getApplication<Application>().applicationContext.contentResolver.openOutputStream(
                    uri
                )?.use { out ->
                    out.write(
                        jsonFormat.encodeToString(SettingsBackup.serializer(), backup).toByteArray()
                    )
                }
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
                val jsonStr =
                    getApplication<Application>().applicationContext.contentResolver.openInputStream(
                        uri
                    )?.use { input ->
                        input.bufferedReader().readText()
                    } ?: throw Exception("Cannot read file")

                val jsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val backup = jsonFormat.decodeFromString(SettingsBackup.serializer(), jsonStr)

                // Preferences
                appPrefs.setUpstreamDns(backup.upstreamDns)
                appPrefs.setFallbackDns(backup.fallbackDns)
                appPrefs.setAutoReconnect(backup.autoReconnect)
                appPrefs.setThemeMode(backup.themeMode)
                appPrefs.setAppLanguage(backup.appLanguage)
                appPrefs.setSafeSearchEnabled(backup.safeSearchEnabled)
                appPrefs.setYoutubeRestrictedMode(backup.youtubeRestrictedMode)
                appPrefs.setDailySummaryEnabled(backup.dailySummaryEnabled)
                if (backup.dailySummaryEnabled) {
                    DailySummaryScheduler.scheduleDailySummary(getApplication())
                } else {
                    DailySummaryScheduler.cancelDailySummary(getApplication())
                }
                appPrefs.setMilestoneNotificationsEnabled(backup.milestoneNotificationsEnabled)
                appPrefs.setFirewallEnabled(backup.firewallEnabled)

                // Restore active profile by type (applies filter config + prefs atomically)
                if (backup.activeProfileType.isNotBlank()) {
                    val profile = profileDao.getByType(backup.activeProfileType)
                    if (profile != null) {
                        profileManager.switchToProfile(profile.id)
                    }
                }

                // Filter lists — only add new
                backup.filterLists.forEach { f ->
                    if (filterLists.value.none { it.url == f.url }) {
                        filterListDao.insert(
                            FilterList(
                                name = f.name,
                                url = f.url,
                                isEnabled = f.isEnabled
                            )
                        )
                    }
                }

                // Whitelist domains — only add new
                backup.whitelistDomains.forEach { domain ->
                    if (whitelistDomainDao.exists(domain) == 0) {
                        whitelistDomainDao.insert(WhitelistDomain(domain = domain))
                    }
                }

                // Whitelisted apps — merge
                val current = appPrefs.getWhitelistedAppsSnapshot()
                appPrefs.setWhitelistedApps(current + backup.whitelistedApps.toSet())

                // Custom rules — parse and add (avoid duplicates)
                val existingRules = customDnsRuleDao.getAll().map { it.rule }.toSet()
                backup.customRules.forEach { ruleText ->
                    if (ruleText !in existingRules) {
                        val rule = CustomRuleParser.parseRule(ruleText)
                        if (rule != null) {
                            customDnsRuleDao.insert(rule)
                        }
                    }
                }

                // Firewall rules — only add new
                backup.firewallRules.forEach { r ->
                    if (firewallRuleDao.getByPackageName(r.packageName) == null) {
                        firewallRuleDao.insert(
                            FirewallRule(
                                packageName = r.packageName,
                                blockWifi = r.blockWifi,
                                blockMobileData = r.blockMobileData,
                                scheduleEnabled = r.scheduleEnabled,
                                scheduleStartHour = r.scheduleStartHour,
                                scheduleStartMinute = r.scheduleStartMinute,
                                scheduleEndHour = r.scheduleEndHour,
                                scheduleEndMinute = r.scheduleEndMinute,
                                isEnabled = r.isEnabled
                            )
                        )
                    }
                }

                _events.toast(R.string.filter_settings_imported)
                requestVpnRestart()
            } catch (e: Exception) {
                _events.toast(R.string.filter_import_failed, listOf("${e.message}"))
            }
        }
    }

    private fun requestVpnRestart() {
        AdBlockVpnService.requestRestart(getApplication<Application>().applicationContext)
    }
}
