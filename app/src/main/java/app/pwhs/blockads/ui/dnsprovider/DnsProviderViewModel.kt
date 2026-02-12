package app.pwhs.blockads.ui.dnsprovider

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsProvider
import app.pwhs.blockads.data.DnsProviders
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DnsProviderViewModel(
    private val appPrefs: AppPreferences,
    application: Application
) : AndroidViewModel(application) {

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

    val selectedProviderId: StateFlow<String?> = appPrefs.dnsProviderId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val customDnsEnabled: StateFlow<Boolean> = appPrefs.dnsProviderId
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun selectProvider(provider: DnsProvider) {
        viewModelScope.launch {
            appPrefs.setDnsProviderId(provider.id)
            appPrefs.setUpstreamDns(provider.ipAddress)
            // Set fallback DNS to a different provider for redundancy
            val fallbackProvider = when (provider.id) {
                "google" -> DnsProviders.CLOUDFLARE
                "cloudflare" -> DnsProviders.GOOGLE
                else -> DnsProviders.CLOUDFLARE
            }
            appPrefs.setFallbackDns(fallbackProvider.ipAddress)
        }
    }

    fun setCustomDns(upstream: String, fallback: String) {
        viewModelScope.launch {
            appPrefs.setDnsProviderId(null)
            appPrefs.setUpstreamDns(upstream)
            appPrefs.setFallbackDns(fallback)
        }
    }
}
