package app.pwhs.blockads.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.DnsProvider
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.data.entities.ProfileManager
import app.pwhs.blockads.ui.onboarding.data.ProtectionLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class OnboardingViewModel(
    private val appPrefs: AppPreferences,
    private val profileManager: ProfileManager,
    application: Application
) : AndroidViewModel(application) {

    private val _selectedProtectionLevel = MutableStateFlow(ProtectionLevel.STANDARD)
    val selectedProtectionLevel: StateFlow<ProtectionLevel> = _selectedProtectionLevel.asStateFlow()

    private val _selectedDnsProvider = MutableStateFlow(DnsProviders.SYSTEM)
    val selectedDnsProvider: StateFlow<DnsProvider> = _selectedDnsProvider.asStateFlow()

    fun selectProtectionLevel(level: ProtectionLevel) {
        _selectedProtectionLevel.value = level
    }

    fun selectDnsProvider(provider: DnsProvider) {
        _selectedDnsProvider.value = provider
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setCrashReportingEnabled(enabled)
            // also toggle it immediately so it starts or stops
            app.pwhs.blockads.utils.CrashReportingManager.toggleSentry(getApplication(), enabled)
        }
    }

    suspend fun completeOnboarding() {
        // Save protection level
        val protectionLevel = _selectedProtectionLevel.value.name
        appPrefs.setProtectionLevel(protectionLevel)

        // Save DNS provider
        val provider = _selectedDnsProvider.value
        appPrefs.setDnsProviderId(provider.id)
        appPrefs.setUpstreamDns(provider.ipAddress)

        // Set protocol based on provider capabilities
        if (provider.dohUrl != null) {
            appPrefs.setDnsProtocol(DnsProtocol.DOH)
            appPrefs.setDohUrl(provider.dohUrl)
        } else {
            appPrefs.setDnsProtocol(DnsProtocol.PLAIN)
        }

        appPrefs.setFallbackDns(selectFallbackDns(provider).ipAddress)

        // Activate the matching preset profile so the real filter state
        // matches the protection level shown during onboarding.
        profileManager.switchToProtectionLevel(protectionLevel)

        // Mark onboarding as completed
        appPrefs.setOnboardingCompleted(true)
    }

    /**
     * Select a fallback DNS provider different from the primary one.
     * Uses privacy-friendly Quad9 ↔ AdGuard pairing for standard fallbacks.
     */
    private fun selectFallbackDns(primary: DnsProvider): DnsProvider {
        return when (primary.id) {
            DnsProviders.QUAD9.id -> DnsProviders.ADGUARD
            DnsProviders.ADGUARD.id -> DnsProviders.QUAD9
            DnsProviders.SYSTEM.id -> DnsProviders.QUAD9
            else -> DnsProviders.ALL_PROVIDERS.firstOrNull {
                it.id != primary.id
            } ?: DnsProviders.QUAD9
        }
    }
}
