package app.pwhs.blockads.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProvider
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.ui.onboarding.data.ProtectionLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OnboardingViewModel(
    private val appPrefs: AppPreferences,
    application: Application
) : AndroidViewModel(application) {

    private val _selectedProtectionLevel = MutableStateFlow(ProtectionLevel.STANDARD)
    val selectedProtectionLevel: StateFlow<ProtectionLevel> = _selectedProtectionLevel.asStateFlow()

    private val _selectedDnsProvider = MutableStateFlow(DnsProviders.CLOUDFLARE)
    val selectedDnsProvider: StateFlow<DnsProvider> = _selectedDnsProvider.asStateFlow()

    fun selectProtectionLevel(level: ProtectionLevel) {
        _selectedProtectionLevel.value = level
    }

    fun selectDnsProvider(provider: DnsProvider) {
        _selectedDnsProvider.value = provider
    }

    suspend fun completeOnboarding() {
        // Save protection level
        appPrefs.setProtectionLevel(_selectedProtectionLevel.value.name)

        // Save DNS provider
        val provider = _selectedDnsProvider.value
        appPrefs.setDnsProviderId(provider.id)
        appPrefs.setUpstreamDns(provider.ipAddress)
        appPrefs.setFallbackDns(selectFallbackDns(provider).ipAddress)

        // Mark onboarding as completed
        appPrefs.setOnboardingCompleted(true)
    }

    /**
     * Select a fallback DNS provider different from the primary one.
     * Uses Google ↔ Cloudflare pairing for standard fallbacks.
     */
    private fun selectFallbackDns(primary: DnsProvider): DnsProvider {
        return when (primary.id) {
            DnsProviders.GOOGLE.id -> DnsProviders.CLOUDFLARE
            DnsProviders.CLOUDFLARE.id -> DnsProviders.GOOGLE
            else -> DnsProviders.ALL_PROVIDERS.firstOrNull {
                it.id != primary.id
            } ?: DnsProviders.CLOUDFLARE
        }
    }
}
