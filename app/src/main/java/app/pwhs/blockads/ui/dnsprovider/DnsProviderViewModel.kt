package app.pwhs.blockads.ui.dnsprovider

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsProvider
import app.pwhs.blockads.data.DnsProviders
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    val selectedProviderId: StateFlow<String?> = combine(
        appPrefs.dnsProviderId,
        appPrefs.upstreamDns
    ) { providerId, upstreamDns ->
        // If provider ID is set, use it
        if (providerId != null) {
            providerId
        } else {
            // Otherwise, try to detect provider from current upstream DNS
            DnsProviders.getByIp(upstreamDns)?.id
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val customDnsEnabled: StateFlow<Boolean> = combine(
        appPrefs.dnsProviderId,
        appPrefs.upstreamDns
    ) { providerId, upstreamDns ->
        // Custom DNS is enabled if no provider ID is set and IP doesn't match any preset
        providerId == null && DnsProviders.getByIp(upstreamDns) == null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
