package app.pwhs.blockadstv.ui.screens.wireguard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.datastore.WireGuardPreferences
import app.pwhs.blockadstv.data.entities.WireGuardProfile
import app.pwhs.blockadstv.data.geoip.GeoIpManager
import app.pwhs.blockadstv.utils.NodePingResult
import app.pwhs.blockadstv.utils.WireGuardConfigParser
import app.pwhs.blockadstv.utils.WireGuardGeoIpHelper
import app.pwhs.blockadstv.utils.WireGuardPingTester
import app.pwhs.blockadstv.utils.configIssue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WireGuardViewModel(
    private val tvPrefs: TvPreferences,
    private val wireGuardPrefs: WireGuardPreferences,
    private val geoIpManager: GeoIpManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WireGuardUiState())
    val uiState: StateFlow<WireGuardUiState> = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<WireGuardUiEffect>()
    val effect: SharedFlow<WireGuardUiEffect> = _effect.asSharedFlow()

    init {
        refreshGeoIpInfo()
        viewModelScope.launch {
            tvPrefs.routingMode.collect { mode ->
                _uiState.update {
                    it.copy(isWireGuardModeActive = mode == WireGuardPreferences.ROUTING_MODE_WIREGUARD)
                }
            }
        }
        viewModelScope.launch {
            wireGuardPrefs.wgProfiles.collect { profiles ->
                _uiState.update { it.copy(profiles = profiles) }
                resolveCountryCodes(profiles)
            }
        }
        viewModelScope.launch {
            wireGuardPrefs.wgActiveProfileId.collect { activeId ->
                _uiState.update { it.copy(activeProfileId = activeId) }
            }
        }
        viewModelScope.launch {
            wireGuardPrefs.geoIpAutoUpdate.collect { autoUpdate ->
                _uiState.update { it.copy(geoIpAutoUpdate = autoUpdate) }
            }
        }
        viewModelScope.launch {
            geoIpManager.updateEvents.collect {
                refreshGeoIpInfo()
                resolveCountryCodes(_uiState.value.profiles)
            }
        }
    }

    fun onIntent(intent: WireGuardUiIntent) {
        when (intent) {
            is WireGuardUiIntent.SetWireGuardMode -> handleSetWireGuardMode(intent.enabled)
            is WireGuardUiIntent.SelectProfile -> handleSelectProfile(intent.id)
            is WireGuardUiIntent.DeleteProfile -> handleDeleteProfile(intent.id)
            is WireGuardUiIntent.RenameProfile -> handleRenameProfile(intent.id, intent.name)
            is WireGuardUiIntent.PingProfile -> handlePingProfile(intent.profile)
            is WireGuardUiIntent.PingAll -> handlePingAll()
            is WireGuardUiIntent.ImportConfigOrUri -> handleImport(intent.input, intent.name)
            is WireGuardUiIntent.ShowImportDialog -> _uiState.update {
                it.copy(showImportDialog = intent.show, dialogError = null)
            }
            is WireGuardUiIntent.ShowRenameDialog -> _uiState.update {
                it.copy(showRenameDialogFor = intent.profile, dialogError = null)
            }
            is WireGuardUiIntent.UpdateGeoIpDatabase -> handleUpdateGeoIp()
            is WireGuardUiIntent.SetGeoIpAutoUpdate -> viewModelScope.launch {
                wireGuardPrefs.setGeoIpAutoUpdate(intent.enabled)
            }
        }
    }

    private fun refreshGeoIpInfo() {
        _uiState.update { it.copy(geoIpInfo = geoIpManager.getDatabaseInfo()) }
    }

    private fun resolveCountryCodes(profiles: List<WireGuardProfile>) {
        viewModelScope.launch {
            val resolved = mutableMapOf<String, String>()
            for (profile in profiles) {
                val endpoint = profile.config.peers.firstOrNull()?.endpoint.orEmpty()
                val cc = WireGuardGeoIpHelper.resolveCountryCode(endpoint, profile.name)
                if (!cc.isNullOrEmpty()) {
                    resolved[profile.id] = cc
                }
            }
            if (resolved.isNotEmpty()) {
                _uiState.update { it.copy(countryCodes = it.countryCodes + resolved) }
            }
        }
    }

    private fun handleSetWireGuardMode(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val active = wireGuardPrefs.getActiveWgProfileSnapshot()
                if (active == null) {
                    _effect.emit(WireGuardUiEffect.ShowToast("Import a valid WireGuard profile first"))
                    return@launch
                }
                tvPrefs.setRoutingMode(WireGuardPreferences.ROUTING_MODE_WIREGUARD)
            } else {
                tvPrefs.setRoutingMode(TvPreferences.ROUTING_MODE_DIRECT)
            }
            _effect.emit(WireGuardUiEffect.RequestRestart)
        }
    }

    private fun handleSelectProfile(id: String) {
        viewModelScope.launch {
            val profile = _uiState.value.profiles.firstOrNull { it.id == id } ?: return@launch
            profile.configIssue?.let { issue ->
                _effect.emit(WireGuardUiEffect.ShowToast(issue.toDisplayMessage()))
                return@launch
            }
            wireGuardPrefs.setActiveWgProfile(id)
            if (_uiState.value.isWireGuardModeActive) {
                _effect.emit(WireGuardUiEffect.RequestRestart)
            }
        }
    }

    private fun handleDeleteProfile(id: String) {
        viewModelScope.launch {
            wireGuardPrefs.removeWgProfile(id)
            if (_uiState.value.activeProfileId == id && _uiState.value.isWireGuardModeActive) {
                if (wireGuardPrefs.getActiveWgProfileSnapshot() == null) {
                    tvPrefs.setRoutingMode(TvPreferences.ROUTING_MODE_DIRECT)
                }
                _effect.emit(WireGuardUiEffect.RequestRestart)
            }
        }
    }

    private fun handleRenameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = "Name cannot be empty") }
            return
        }
        viewModelScope.launch {
            wireGuardPrefs.renameWgProfile(id, trimmed)
            _uiState.update { it.copy(showRenameDialogFor = null, dialogError = null) }
        }
    }

    private fun handlePingProfile(profile: WireGuardProfile) {
        viewModelScope.launch {
            _uiState.update { it.copy(pingResults = it.pingResults + (profile.id to NodePingResult.Testing)) }
            val res = WireGuardPingTester.pingProfile(profile)
            _uiState.update { it.copy(pingResults = it.pingResults + (profile.id to res)) }
        }
    }

    private fun handlePingAll() {
        val profiles = _uiState.value.profiles
        if (profiles.isEmpty()) return
        viewModelScope.launch {
            WireGuardPingTester.pingAll(profiles) { id, res ->
                _uiState.update { it.copy(pingResults = it.pingResults + (id to res)) }
            }
        }
    }

    private fun handleImport(rawInput: String, customName: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = "Paste a WireGuard config or URI") }
            return
        }
        viewModelScope.launch {
            try {
                val parsedProfile = when {
                    trimmed.startsWith("wireguard://", ignoreCase = true) ->
                        WireGuardConfigParser.parseUri(trimmed)
                    trimmed.startsWith("vpn://", ignoreCase = true) ||
                        trimmed.startsWith("awg://", ignoreCase = true) ->
                        WireGuardConfigParser.parseVpnUri(trimmed)
                    else -> {
                        val cfg = WireGuardConfigParser.parse(trimmed)
                        val endpointHost = cfg.peers.firstOrNull()?.endpoint?.substringBefore(':').orEmpty()
                        val defaultName = customName.trim().ifEmpty {
                            endpointHost.ifEmpty { "WireGuard" }
                        }
                        WireGuardProfile(
                            id = WireGuardProfile.newId(),
                            name = defaultName,
                            config = cfg,
                        )
                    }
                }
                val finalProfile = if (customName.trim().isNotEmpty()) {
                    parsedProfile.copy(name = customName.trim())
                } else {
                    parsedProfile
                }
                finalProfile.configIssue?.let { issue ->
                    _uiState.update { it.copy(dialogError = issue.toDisplayMessage()) }
                    return@launch
                }
                wireGuardPrefs.addOrUpdateWgProfile(finalProfile, makeActive = true)
                _uiState.update { it.copy(showImportDialog = false, dialogError = null) }
                _effect.emit(WireGuardUiEffect.ShowToast("Imported ${finalProfile.name}"))
                if (_uiState.value.isWireGuardModeActive) {
                    _effect.emit(WireGuardUiEffect.RequestRestart)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(dialogError = e.message ?: "Invalid WireGuard config") }
            }
        }
    }

    private fun handleUpdateGeoIp() {
        if (_uiState.value.isUpdatingGeoIp) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingGeoIp = true) }
            val result = geoIpManager.checkAndUpdate(force = true)
            _uiState.update {
                it.copy(
                    isUpdatingGeoIp = false,
                    geoIpInfo = geoIpManager.getDatabaseInfo(),
                )
            }
            val msg = if (result.isSuccess) "GeoIP database updated" else "GeoIP update failed"
            _effect.emit(WireGuardUiEffect.ShowToast(msg))
        }
    }
}
