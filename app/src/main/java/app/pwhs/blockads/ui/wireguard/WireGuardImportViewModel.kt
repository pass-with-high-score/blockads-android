package app.pwhs.blockads.ui.wireguard

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.utils.WireGuardConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Manages the list of WireGuard profiles and which one is active. The
 * VPN service reads the active profile via [AppPreferences.getWgConfigJsonSnapshot]
 * on each (re)start, so changes here only take effect after [ServiceController.requestRestart].
 */
class WireGuardImportViewModel(
    application: Application,
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()

    private val _profiles = MutableStateFlow<List<WireGuardProfile>>(emptyList())
    val profiles: StateFlow<List<WireGuardProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Whether WireGuard routing mode is currently active in preferences. */
    private val _isWgActive = MutableStateFlow(false)
    val isWgActive: StateFlow<Boolean> = _isWgActive.asStateFlow()

    /** Split-DNS zones for routing internal domains via WireGuard DNS. */
    private val _splitDnsZones = MutableStateFlow("")
    val splitDnsZones: StateFlow<String> = _splitDnsZones.asStateFlow()

    /** Exclude LAN/private IPs from WireGuard tunnel. */
    private val _excludeLan = MutableStateFlow(false)
    val excludeLan: StateFlow<Boolean> = _excludeLan.asStateFlow()

    /** One-shot UI events. */
    private val _events = MutableSharedFlow<WireGuardUiEvent>()
    val events: SharedFlow<WireGuardUiEvent> = _events.asSharedFlow()

    init {
        // Stream profiles + active id so the list reacts to imports/deletes.
        combine(appPrefs.wgProfiles, appPrefs.wgActiveProfileId) { list, active ->
            list to active
        }.onEach { (list, active) ->
            _profiles.value = list
            _activeProfileId.value = active ?: list.firstOrNull()?.id
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            _isWgActive.value =
                appPrefs.getRoutingModeSnapshot() == AppPreferences.ROUTING_MODE_WIREGUARD
            _splitDnsZones.value = appPrefs.splitDnsZones.first()
            _excludeLan.value = appPrefs.excludeLan.first()
        }
    }

    /**
     * Read a .conf file via SAF URI, parse it, and save it as a new profile.
     * If no profile is currently active, the new one becomes active.
     */
    fun importFromUri(uri: Uri, fallbackName: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val rawText = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
                        ?: throw Exception("Cannot open input stream")
                }

                if (rawText.isBlank()) {
                    _error.value = "File is empty"
                    return@launch
                }

                val parsed = WireGuardConfigParser.parse(rawText)
                val displayName = fallbackName?.takeIf { it.isNotBlank() }
                    ?: deriveNameFromUri(uri)
                    ?: defaultUniqueName()
                val profile = WireGuardProfile(
                    id = WireGuardProfile.newId(),
                    name = displayName,
                    config = parsed,
                )
                val makeActive = appPrefs.getActiveWgProfileSnapshot() == null
                appPrefs.addOrUpdateWgProfile(profile, makeActive = makeActive)
                _events.emit(WireGuardUiEvent.ProfileImported(displayName))
                if (makeActive && _isWgActive.value) {
                    ServiceController.requestRestart(getApplication())
                }
            } catch (e: IllegalArgumentException) {
                _error.value = e.message ?: "Invalid WireGuard configuration"
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to import configuration"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Switch which profile the VPN uses. Restarts the tunnel if WG is on. */
    fun setActiveProfile(id: String) {
        viewModelScope.launch {
            val profile = _profiles.value.firstOrNull { it.id == id } ?: return@launch
            appPrefs.setActiveWgProfile(id)
            _events.emit(WireGuardUiEvent.ProfileActivated(profile.name))
            if (_isWgActive.value) {
                ServiceController.requestRestart(getApplication())
            }
        }
    }

    fun renameProfile(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            appPrefs.renameWgProfile(id, name)
            _events.emit(WireGuardUiEvent.ProfileRenamed)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val target = _profiles.value.firstOrNull { it.id == id } ?: return@launch
            val wasActive = _activeProfileId.value == id
            appPrefs.removeWgProfile(id)
            _events.emit(WireGuardUiEvent.ProfileDeleted(target.name))

            // If the active profile was deleted and WG was on, either:
            //  - restart with the new active (if any other profile exists), or
            //  - turn WG off (no profiles left).
            if (wasActive && _isWgActive.value) {
                val remaining = appPrefs.getWgProfilesSnapshot()
                if (remaining.isEmpty()) {
                    appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                    _isWgActive.value = false
                    _events.emit(WireGuardUiEvent.WireGuardToggled(false))
                }
                ServiceController.requestRestart(getApplication())
            }
        }
    }

    /**
     * Toggle WireGuard routing mode. Requires at least one profile to enable.
     */
    fun toggleWireGuard() {
        viewModelScope.launch {
            val newActive = !_isWgActive.value
            if (newActive) {
                if (appPrefs.getActiveWgProfileSnapshot() == null) {
                    _error.value = "Import a config first"
                    return@launch
                }
                // HTTPS filtering and WireGuard routing are mutually
                // exclusive: TcpIpStack terminates flows and dials the
                // destination directly, bypassing the WG tunnel. Turn
                // HTTPS filtering off so WG actually carries traffic.
                if (appPrefs.getHttpsFilteringEnabledSnapshot()) {
                    appPrefs.setHttpsFilteringEnabled(false)
                    _events.emit(WireGuardUiEvent.HttpsFilteringDisabledForWg)
                }
                appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_WIREGUARD)
            } else {
                appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
            }
            _isWgActive.value = newActive
            ServiceController.requestRestart(getApplication())
            _events.emit(WireGuardUiEvent.WireGuardToggled(newActive))
        }
    }

    fun setExcludeLan(enabled: Boolean) {
        _excludeLan.value = enabled
        viewModelScope.launch { appPrefs.setExcludeLan(enabled) }
    }

    fun setSplitDnsZones(zones: String) {
        _splitDnsZones.value = zones
        viewModelScope.launch { appPrefs.setSplitDnsZones(zones) }
    }

    fun clearError() {
        _error.value = null
    }

    private fun deriveNameFromUri(uri: Uri): String? {
        val ctx = getApplication<Application>()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIdx)?.removeSuffix(".conf")?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultUniqueName(): String {
        val existing = _profiles.value.map { it.name }.toSet()
        var n = 1
        while ("Tunnel $n" in existing) n++
        return "Tunnel $n"
    }
}
