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

    /** Current connected SSID, or null if not on Wi-Fi / no location permission. */
    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    /** Refresh the current SSID (call when the screen resumes or permission granted). */
    fun refreshCurrentSsid() {
        _currentSsid.value = TrustedNetworkManager.currentSsid(getApplication())
    }

    fun setPauseOnTrustedEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setPauseOnTrustedEnabled(enabled) }
    }

    fun addCurrentNetwork() {
        val ssid = TrustedNetworkManager.currentSsid(getApplication()) ?: return
        viewModelScope.launch { appPrefs.toggleTrustedSsid(ssid) }
    }

    fun removeSsid(ssid: String) {
        viewModelScope.launch {
            val current = appPrefs.getTrustedSsidsSnapshot()
            appPrefs.setTrustedSsids(current - ssid)
        }
    }
}
