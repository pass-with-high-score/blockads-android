package app.pwhs.blockads.ui.trustednetworks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.TrustedNetworkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrustedNetworksViewModel(
    private val appPrefs: AppPreferences,
    application: Application,
) : AndroidViewModel(application) {

    val trustedSsids: StateFlow<Set<String>> = appPrefs.trustedSsids
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val pauseOnTrustedEnabled: StateFlow<Boolean> = appPrefs.pauseOnTrustedEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val useSystemDnsOnTrustedEnabled: StateFlow<Boolean> = appPrefs.useSystemDnsOnTrustedEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Current connected SSID and optional BSSID, or null if not on Wi-Fi / no location permission. */
    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _currentBssid = MutableStateFlow<String?>(null)
    val currentBssid: StateFlow<String?> = _currentBssid.asStateFlow()

    /** Refresh the current SSID/BSSID (call when the screen resumes or permission granted). */
    fun refreshCurrentSsid() {
        val info = TrustedNetworkManager.currentWifiInfo(getApplication())
        _currentSsid.value = info?.first
        _currentBssid.value = info?.second
    }

    fun setPauseOnTrustedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setPauseOnTrustedEnabled(enabled)
            if (enabled) {
                appPrefs.setUseSystemDnsOnTrustedEnabled(false)
            }
        }
    }

    fun setUseSystemDnsOnTrustedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setUseSystemDnsOnTrustedEnabled(enabled)
            if (enabled) {
                appPrefs.setPauseOnTrustedEnabled(false)
            }
        }
    }

    fun addCurrentNetwork() {
        val ssid = TrustedNetworkManager.currentSsid(getApplication()) ?: return
        addSsid(ssid)
    }

    fun addSsid(rawSsid: String) {
        val ssid = rawSsid.trim()
        if (ssid.isEmpty()) return
        viewModelScope.launch {
            val current = appPrefs.getTrustedSsidsSnapshot()
            if (ssid !in current) {
                appPrefs.setTrustedSsids(current + ssid)
            }
        }
    }

    fun removeSsid(rawEntry: String) {
        viewModelScope.launch {
            val current = appPrefs.getTrustedSsidsSnapshot()
            appPrefs.setTrustedSsids(current - rawEntry)
        }
    }

    fun togglePinBssid(rawEntry: String) {
        val parsed = TrustedNetworkManager.parseEntry(rawEntry)
        val nextEntry = if (parsed.bssid != null) {
            parsed.ssid
        } else {
            val bssid = _currentBssid.value?.takeIf { _currentSsid.value == parsed.ssid } ?: return
            TrustedNetworkManager.TrustedNetworkEntry(parsed.ssid, bssid).encode()
        }
        viewModelScope.launch {
            val current = appPrefs.getTrustedSsidsSnapshot()
            appPrefs.setTrustedSsids((current - rawEntry) + nextEntry)
        }
    }
}
