package app.pwhs.blockads.ui.settings

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.data.FilterList
import app.pwhs.blockads.data.FilterListBackup
import app.pwhs.blockads.data.FilterListDao
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.data.LocaleHelper
import app.pwhs.blockads.data.SettingsBackup
import app.pwhs.blockads.data.WhitelistDomain
import app.pwhs.blockads.data.WhitelistDomainDao
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
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
    application: Application,
) : AndroidViewModel(application) {

    val autoReconnect: StateFlow<Boolean> = appPrefs.autoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val upstreamDns: StateFlow<String> = appPrefs.upstreamDns
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_UPSTREAM_DNS
        )

    val fallbackDns: StateFlow<String> = appPrefs.fallbackDns
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_FALLBACK_DNS
        )

    val dnsProtocol: StateFlow<app.pwhs.blockads.data.DnsProtocol> = appPrefs.dnsProtocol
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            app.pwhs.blockads.data.DnsProtocol.PLAIN
        )

    val dohUrl: StateFlow<String> = appPrefs.dohUrl
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_DOH_URL
        )

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whitelistDomains: StateFlow<List<WhitelistDomain>> = whitelistDomainDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val themeMode: StateFlow<String> = appPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.THEME_SYSTEM)

    val appLanguage: StateFlow<String> = appPrefs.appLanguage
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.LANGUAGE_SYSTEM
        )

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

    fun setUpstreamDns(dns: String) {
        viewModelScope.launch { appPrefs.setUpstreamDns(dns) }
    }

    fun setFallbackDns(dns: String) {
        viewModelScope.launch { appPrefs.setFallbackDns(dns) }
    }

    fun setDnsProtocol(protocol: app.pwhs.blockads.data.DnsProtocol) {
        viewModelScope.launch { appPrefs.setDnsProtocol(protocol) }
    }

    fun setDohUrl(url: String) {
        viewModelScope.launch { appPrefs.setDohUrl(url) }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { appPrefs.setThemeMode(mode) }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            appPrefs.setAppLanguage(language)
            LocaleHelper.setLocale(getApplication<Application>().applicationContext, language)
            // On pre-API 33, we need to recreate the activity
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                (getApplication<Application>().applicationContext as? Activity)?.recreate()
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

    fun clearLogs() {
        viewModelScope.launch {
            dnsLogDao.clearAll()
            _events.toast(R.string.filter_log_cleared)
        }
    }

    fun addWhitelistDomain(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            if (cleanDomain.isNotBlank()) {
                val exists = whitelistDomainDao.exists(cleanDomain)
                if (exists == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                    _events.toast(R.string.filter_domain_whitelisted, listOf(cleanDomain))
                } else {
                    _events.toast(R.string.filter_domain_already_whitelisted)
                }
            }
        }
    }

    fun removeWhitelistDomain(domain: WhitelistDomain) {
        viewModelScope.launch {
            whitelistDomainDao.delete(domain)
        }
    }

    // ── Export Settings ──────────────────────────────────────────────
    fun exportSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                val backup = SettingsBackup(
                    upstreamDns = appPrefs.upstreamDns.first(),
                    fallbackDns = appPrefs.fallbackDns.first(),
                    autoReconnect = appPrefs.autoReconnect.first(),
                    themeMode = appPrefs.themeMode.first(),
                    appLanguage = appPrefs.appLanguage.first(),
                    filterLists = filterLists.value.map { f ->
                        FilterListBackup(name = f.name, url = f.url, isEnabled = f.isEnabled)
                    },
                    whitelistDomains = whitelistDomains.value.map { it.domain },
                    whitelistedApps = appPrefs.getWhitelistedAppsSnapshot().toList()
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
                _events.toast(R.string.filter_settings_imported)
            } catch (e: Exception) {
                _events.toast(R.string.filter_import_failed, listOf("${e.message}"))

            }
        }
    }
}
