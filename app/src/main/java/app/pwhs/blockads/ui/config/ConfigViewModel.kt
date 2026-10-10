package app.pwhs.blockads.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.ConfigDao
import app.pwhs.blockads.data.entities.ConfigProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.utils.ConfigRuleHelper
import app.pwhs.blockads.utils.ProfileMigrationHelper
import kotlinx.coroutines.flow.first

class ConfigViewModel(
    private val configDao: ConfigDao,
    private val client: HttpClient,
    application: Application,
    private val appPreferences: AppPreferences? = null,
    private val customDnsRuleDao: CustomDnsRuleDao? = null,
    private val whitelistDomainDao: WhitelistDomainDao? = null,
    private val filterListDao: FilterListDao? = null,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ConfigUiState(isLoading = true))
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ConfigUiEffect>()
    val effects: SharedFlow<ConfigUiEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            seedDefaultConfigIfNeeded()
            combine(
                configDao.getAllFlow(),
                configDao.getActiveFlow()
            ) { configs, active ->
                _uiState.update { current ->
                    current.copy(
                        configs = configs,
                        activeConfig = active ?: configs.firstOrNull(),
                        isLoading = false
                    )
                }
            }.collect {}
        }
    }

    fun onIntent(intent: ConfigUiIntent) {
        when (intent) {
            is ConfigUiIntent.SelectActive -> selectActive(intent.configId)
            is ConfigUiIntent.AddRemoteConfig -> addRemoteConfig(intent.name, intent.url)
            is ConfigUiIntent.AddLocalConfig -> addLocalConfig(intent.name, intent.content)
            is ConfigUiIntent.UpdateConfig -> updateConfig(intent.configId, intent.name, intent.content)
            is ConfigUiIntent.DeleteConfig -> deleteConfig(intent.config)
            is ConfigUiIntent.ShowAddDialog -> _uiState.update { it.copy(showImportDialog = true) }
            is ConfigUiIntent.DismissAddDialog -> _uiState.update { it.copy(showImportDialog = false) }
            is ConfigUiIntent.EditConfig -> _uiState.update { it.copy(editingConfig = intent.config, isEditorOpen = true) }
            is ConfigUiIntent.EditActiveConfig -> {
                _uiState.value.activeConfig?.let { active ->
                    _uiState.update { it.copy(editingConfig = active, isEditorOpen = true) }
                }
            }
            is ConfigUiIntent.CloseEditor -> _uiState.update { it.copy(editingConfig = null, isEditorOpen = false) }
            is ConfigUiIntent.RefreshRemote -> refreshRemoteConfig(intent.configId)
            is ConfigUiIntent.LoadSample -> loadSampleConfig()
            is ConfigUiIntent.ResetActiveConfig -> resetActiveConfig()
            is ConfigUiIntent.ShowImportDialog -> _uiState.update { it.copy(showImportDialog = true) }
            is ConfigUiIntent.DismissImportDialog -> _uiState.update { it.copy(showImportDialog = false) }
            is ConfigUiIntent.ShowProfilesSheet -> _uiState.update { it.copy(showProfilesSheet = true) }
            is ConfigUiIntent.DismissProfilesSheet -> _uiState.update { it.copy(showProfilesSheet = false) }
            is ConfigUiIntent.ShowMiscSettingsDialog -> _uiState.update { it.copy(showMiscSettingsDialog = true) }
            is ConfigUiIntent.DismissMiscSettingsDialog -> _uiState.update { it.copy(showMiscSettingsDialog = false) }
            is ConfigUiIntent.ShowSnippetsSheet -> _uiState.update { it.copy(showSnippetsSheet = true) }
            is ConfigUiIntent.DismissSnippetsSheet -> _uiState.update { it.copy(showSnippetsSheet = false) }
            is ConfigUiIntent.ShowResetConfirmDialog -> _uiState.update { it.copy(showResetConfirmDialog = true) }
            is ConfigUiIntent.DismissResetConfirmDialog -> _uiState.update { it.copy(showResetConfirmDialog = false) }
            is ConfigUiIntent.ToggleAutoUpdate -> toggleAutoUpdate(intent.configId, intent.enabled)
            is ConfigUiIntent.MigrateFromAppSettings -> migrateFromAppSettings()
        }
    }

    private suspend fun seedDefaultConfigIfNeeded() = withContext(Dispatchers.IO) {
        if (configDao.getCount() == 0) {
            val defaultConfig = ConfigProfile(
                name = ConfigProfile.DEFAULT_NAME,
                content = ConfigProfile.SAMPLE_CONFIG,
                isActive = true,
                isBuiltIn = true
            )
            configDao.insert(defaultConfig)
        } else {
            val default = configDao.getAll().firstOrNull { it.isBuiltIn && it.content.contains("Quantumult") }
            if (default != null) {
                configDao.update(
                    default.copy(
                        content = default.content.replace("Quantumult X default configuration", "Configuration Profile")
                            .replace("Quantumult", "BlockAds")
                    )
                )
            }
        }
    }

    private fun loadSampleConfig() {
        val active = _uiState.value.activeConfig ?: return
        updateConfig(active.id, active.name, ConfigProfile.SAMPLE_CONFIG)
        viewModelScope.launch {
            _effects.emit(ConfigUiEffect.ShowToast(R.string.profile_sample_loaded))
        }
    }

    private fun resetActiveConfig() {
        val active = _uiState.value.activeConfig ?: return
        updateConfig(active.id, active.name, ConfigProfile.SAMPLE_CONFIG)
        _uiState.update { it.copy(showResetConfirmDialog = false) }
        viewModelScope.launch {
            _effects.emit(ConfigUiEffect.ShowToast(R.string.config_updated))
        }
    }

    private fun toggleAutoUpdate(configId: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configDao.getById(configId) ?: return@launch
            configDao.update(existing.copy(autoUpdate = enabled))
        }
    }

    private fun selectActive(configId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            configDao.setActive(configId)
            configDao.getById(configId)?.let { syncProfileDnsToAppPrefs(it.content) }
            _effects.emit(ConfigUiEffect.ShowToast(R.string.config_activated))
        }
    }

    private fun addRemoteConfig(name: String, url: String) {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isEmpty() || trimmedUrl.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true, showImportDialog = false) }
            try {
                val content = withContext(Dispatchers.IO) {
                    client.get(trimmedUrl).bodyAsText()
                }
                val newConfig = ConfigProfile(
                    name = trimmedName,
                    content = content,
                    remoteUrl = trimmedUrl,
                    lastUpdated = System.currentTimeMillis(),
                    isActive = false
                )
                withContext(Dispatchers.IO) {
                    configDao.insert(newConfig)
                }
                _effects.emit(ConfigUiEffect.ShowToast(R.string.config_added))
            } catch (e: Exception) {
                Timber.e(e, "Failed to download remote config")
                _effects.emit(ConfigUiEffect.ShowToast(R.string.config_fetch_failed))
            } finally {
                _uiState.update { it.copy(isUpdating = false) }
            }
        }
    }

    private fun addLocalConfig(name: String, content: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val newConfig = ConfigProfile(
                name = trimmedName,
                content = content,
                isActive = false
            )
            configDao.insert(newConfig)
            _uiState.update { it.copy(showImportDialog = false) }
            _effects.emit(ConfigUiEffect.ShowToast(R.string.config_added))
        }
    }

    private fun updateConfig(configId: Long, name: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = configDao.getById(configId) ?: return@launch
            configDao.update(existing.copy(name = name.trim(), content = content))
            if (existing.isActive) {
                syncProfileDnsToAppPrefs(content)
            }
            _uiState.update { it.copy(editingConfig = null) }
            _effects.emit(ConfigUiEffect.ShowToast(R.string.config_updated))
        }
    }

    private fun deleteConfig(config: ConfigProfile) {
        if (config.isBuiltIn) return
        viewModelScope.launch(Dispatchers.IO) {
            configDao.delete(config)
            if (config.isActive) {
                configDao.getAll().firstOrNull()?.let { configDao.setActive(it.id) }
            }
            _effects.emit(ConfigUiEffect.ShowToast(R.string.config_deleted))
        }
    }

    private fun refreshRemoteConfig(configId: Long) {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { configDao.getById(configId) } ?: return@launch
            val url = config.remoteUrl ?: return@launch

            _uiState.update { it.copy(isUpdating = true) }
            try {
                val content = withContext(Dispatchers.IO) {
                    client.get(url).bodyAsText()
                }
                withContext(Dispatchers.IO) {
                    configDao.update(
                        config.copy(
                            content = content,
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                }
                _effects.emit(ConfigUiEffect.ShowToast(R.string.config_updated))
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh remote config")
                _effects.emit(ConfigUiEffect.ShowToast(R.string.config_fetch_failed))
            } finally {
                _uiState.update { it.copy(isUpdating = false) }
            }
        }
    }

    private fun migrateFromAppSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true) }
            try {
                withContext(Dispatchers.IO) {
                    val dnsId = appPreferences?.dnsProviderId?.first()
                    val customRules = customDnsRuleDao?.getAll() ?: emptyList()
                    val whitelist = whitelistDomainDao?.getAll()?.first() ?: emptyList()
                    val filterLists = filterListDao?.getEnabled() ?: emptyList()

                    val content = ProfileMigrationHelper.generateProfileFromCurrentSettings(
                        selectedDnsProviderId = dnsId,
                        customRules = customRules,
                        whitelistDomains = whitelist,
                        enabledFilterLists = filterLists
                    )

                    val profile = ConfigProfile(
                        name = "Migrated Profile",
                        content = content,
                        isActive = false,
                        isBuiltIn = false
                    )
                    val id = configDao.insert(profile)
                    configDao.setActive(id)
                }
                _effects.emit(ConfigUiEffect.ShowToast(R.string.profile_migrated_success))
            } catch (e: Exception) {
                Timber.e(e, "Failed to migrate profile from app settings")
            } finally {
                _uiState.update { it.copy(isUpdating = false) }
            }
        }
    }

    private suspend fun syncProfileDnsToAppPrefs(content: String) {
        if (appPreferences == null) return
        val servers = ConfigRuleHelper.parseDnsServers(content)
        if (servers.isEmpty()) return
        val primary = servers[0]
        val secondary = servers.getOrNull(1)

        val matched = DnsProviders.getByIp(primary)
        if (matched != null) {
            appPreferences.setDnsProviderId(matched.id)
            appPreferences.setUpstreamDns(matched.ipAddress)
            if (matched.odohRelayUrl != null && matched.dohUrl != null) {
                appPreferences.setDnsProtocol(DnsProtocol.ODOH)
                appPreferences.setDohUrl(matched.dohUrl)
                appPreferences.setOdohRelayUrl(matched.odohRelayUrl)
            } else if (matched.dohUrl != null) {
                val protocol = if (matched.dohUrl.startsWith("quic://", ignoreCase = true)) DnsProtocol.DOQ else DnsProtocol.DOH
                appPreferences.setDnsProtocol(protocol)
                appPreferences.setDohUrl(matched.dohUrl)
            } else {
                appPreferences.setDnsProtocol(DnsProtocol.PLAIN)
            }
        } else {
            appPreferences.setDnsProviderId(AppPreferences.CUSTOM_DNS_PROVIDER_ID)
            appPreferences.setUpstreamDns(primary)
            appPreferences.setDnsProtocol(DnsProtocol.PLAIN)
        }
        if (secondary != null) {
            appPreferences.setFallbackDns(secondary)
        }
        Timber.d("AppPreferences DNS synced from active profile [dns]: primary=$primary, secondary=$secondary")
    }
}
