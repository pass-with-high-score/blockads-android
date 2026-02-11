package app.pwhs.blockads.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.data.FilterList
import app.pwhs.blockads.data.FilterListBackup
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.data.LocaleHelper
import app.pwhs.blockads.data.SettingsBackup
import app.pwhs.blockads.data.WhitelistDomain
import app.pwhs.blockads.data.WhitelistDomainDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appPrefs: AppPreferences,
    private val filterRepo: FilterListRepository,
    private val dnsLogDao: DnsLogDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val context: Context,
) : ViewModel() {

    private val filterListDao = app.pwhs.blockads.data.AppDatabase.getInstance(context).filterListDao()

    val autoReconnect: StateFlow<Boolean> = appPrefs.autoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val upstreamDns: StateFlow<String> = appPrefs.upstreamDns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_UPSTREAM_DNS)

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whitelistDomains: StateFlow<List<WhitelistDomain>> = whitelistDomainDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    val themeMode: StateFlow<String> = appPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.THEME_SYSTEM)

    val appLanguage: StateFlow<String> = appPrefs.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.LANGUAGE_SYSTEM)

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

    fun setThemeMode(mode: String) {
        viewModelScope.launch { appPrefs.setThemeMode(mode) }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            appPrefs.setAppLanguage(language)
            LocaleHelper.setLocale(context, language)
            // On pre-API 33, we need to recreate the activity
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                (context as? Activity)?.recreate()
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            dnsLogDao.clearAll()
            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
    }

    fun addWhitelistDomain(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            if (cleanDomain.isNotBlank()) {
                val exists = whitelistDomainDao.exists(cleanDomain)
                if (exists == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                    Toast.makeText(context, "Domain whitelisted: $cleanDomain", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Domain already whitelisted", Toast.LENGTH_SHORT).show()
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
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonFormat.encodeToString(SettingsBackup.serializer(), backup).toByteArray())
                }
                Toast.makeText(context, "Settings exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Import Settings ──────────────────────────────────────────────
    fun importSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: throw Exception("Cannot read file")

                val jsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val backup = jsonFormat.decodeFromString(SettingsBackup.serializer(), jsonStr)

                // Preferences
                appPrefs.setUpstreamDns(backup.upstreamDns)
                appPrefs.setAutoReconnect(backup.autoReconnect)
                appPrefs.setThemeMode(backup.themeMode)
                appPrefs.setAppLanguage(backup.appLanguage)

                // Filter lists — only add new
                backup.filterLists.forEach { f ->
                    if (filterLists.value.none { it.url == f.url }) {
                        filterListDao.insert(FilterList(name = f.name, url = f.url, isEnabled = f.isEnabled))
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

                Toast.makeText(context, "Settings imported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
