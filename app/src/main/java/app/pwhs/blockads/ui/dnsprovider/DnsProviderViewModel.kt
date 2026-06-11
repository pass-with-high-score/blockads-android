package app.pwhs.blockads.ui.dnsprovider

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsCategory
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.DnsProvider
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DnsProviderViewModel(
    private val appPrefs: AppPreferences,
    application: Application
) : AndroidViewModel(application) {

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val upstreamDns: StateFlow<String> = appPrefs.upstreamDns
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AppPreferences.DEFAULT_UPSTREAM_DNS
        )

    val fallbackDns: StateFlow<String> = appPrefs.fallbackDns
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AppPreferences.DEFAULT_FALLBACK_DNS
        )

    val selectedProviderId: StateFlow<String?> = combine(
        appPrefs.dnsProviderId,
        appPrefs.upstreamDns
    ) { providerId, upstreamDns ->
        // If provider ID is set, use it
        providerId ?: // Otherwise, try to detect provider from current upstream DNS
        DnsProviders.getByIp(upstreamDns)?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val customDnsEnabled: StateFlow<Boolean> = combine(
        appPrefs.dnsProviderId,
        appPrefs.upstreamDns
    ) { providerId, upstreamDns ->
        // Custom DNS is enabled if no provider ID is set and IP doesn't match any preset
        providerId == null && DnsProviders.getByIp(upstreamDns) == null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Unified display value for the custom DNS input.
     * Shows the current DNS server in the format the user originally entered:
     * - Plain DNS: IP address (e.g., "8.8.8.8")
     * - DoH: full URL (e.g., "https://dns.google/dns-query")
     * - DoT: tls:// prefix + server (e.g., "tls://dns.google")
     */
    val customDnsDisplay: StateFlow<String> = combine(
        appPrefs.dnsProtocol,
        appPrefs.upstreamDns,
        appPrefs.dohUrl
    ) { protocol, upstream, doh ->
        when (protocol) {
            DnsProtocol.DOH -> doh
            DnsProtocol.DOT -> "tls://$upstream"
            DnsProtocol.DOQ -> if (doh.startsWith("quic://", ignoreCase = true)) doh else "quic://${doh.removePrefix("https://")}"
            DnsProtocol.PLAIN -> upstream
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_UPSTREAM_DNS
    )

    fun selectProvider(provider: DnsProvider) {
        viewModelScope.launch {
            appPrefs.setDnsProviderId(provider.id)
            appPrefs.setUpstreamDns(provider.ipAddress)

            // Set protocol based on provider capabilities
            if (provider.dohUrl != null) {
                appPrefs.setDnsProtocol(DnsProtocol.DOH)
                appPrefs.setDohUrl(provider.dohUrl)
            } else {
                appPrefs.setDnsProtocol(DnsProtocol.PLAIN)
            }

            // Only auto-set fallback DNS if it would conflict with the new primary
            val currentFallback = appPrefs.fallbackDns.first()
            if (currentFallback == provider.ipAddress) {
                val fallbackProvider = when (provider.id) {
                    DnsProviders.QUAD9.id -> DnsProviders.ADGUARD
                    DnsProviders.ADGUARD.id -> DnsProviders.QUAD9
                    DnsProviders.SYSTEM.id -> DnsProviders.QUAD9
                    else -> {
                        DnsProviders.ALL_PROVIDERS.firstOrNull {
                            it.id != provider.id && it.category == DnsCategory.PRIVACY
                        } ?: DnsProviders.QUAD9
                    }
                }
                appPrefs.setFallbackDns(fallbackProvider.ipAddress)
            }
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun setFallbackDns(dns: String) {
        val trimmed = dns.trim()
        viewModelScope.launch {
            val currentUpstream = appPrefs.upstreamDns.first()
            if (currentUpstream.equals(trimmed, ignoreCase = true)) {
                _events.toast(R.string.dns_error_duplicate)
                return@launch
            }
            appPrefs.setFallbackDns(trimmed)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun getParsedHost(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> {
                try { java.net.URL(trimmed).host } catch (_: Exception) { trimmed }
            }
            trimmed.startsWith("quic://", ignoreCase = true) -> {
                try { java.net.URI(trimmed).host ?: trimmed.removePrefix("quic://").removePrefix("QUIC://") } catch (_: Exception) { trimmed.removePrefix("quic://").removePrefix("QUIC://") }
            }
            trimmed.startsWith("tls://", ignoreCase = true) -> {
                trimmed.removePrefix("tls://").removePrefix("TLS://")
            }
            else -> trimmed
        }
    }

    fun setCustomDns(upstream: String) {
        val trimmed = upstream.trim()
        if (trimmed.isBlank()) return // Guard against empty input

        viewModelScope.launch {
            val parsedHost = getParsedHost(trimmed)

            val currentFallback = appPrefs.fallbackDns.first()
            if (currentFallback.equals(parsedHost, ignoreCase = true)) {
                _events.toast(R.string.dns_error_duplicate)
                return@launch
            }

            appPrefs.setDnsProviderId(null)
            when {
                trimmed.startsWith("https://", ignoreCase = true) -> {
                    appPrefs.setDnsProtocol(DnsProtocol.DOH)
                    appPrefs.setDohUrl(trimmed)
                    appPrefs.setUpstreamDns(parsedHost)
                }
                trimmed.startsWith("quic://", ignoreCase = true) -> {
                    appPrefs.setDnsProtocol(DnsProtocol.DOQ)
                    appPrefs.setDohUrl(trimmed)
                    appPrefs.setUpstreamDns(parsedHost)
                }
                trimmed.startsWith("tls://", ignoreCase = true) -> {
                    appPrefs.setDnsProtocol(DnsProtocol.DOT)
                    appPrefs.setUpstreamDns(parsedHost)
                }
                else -> {
                    appPrefs.setDnsProtocol(DnsProtocol.PLAIN)
                    appPrefs.setUpstreamDns(parsedHost)
                }
            }
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }
}
