package app.pwhs.blockads.ui.dnsprovider

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsCategory
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.DnsProvider
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.service.ServiceController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import app.pwhs.blockads.data.dao.ConfigDao
import app.pwhs.blockads.utils.ConfigRuleHelper
import timber.log.Timber

class DnsProviderViewModel(
    private val appPrefs: AppPreferences,
    application: Application,
    private val configDao: ConfigDao? = null
) : AndroidViewModel(application) {

    private val _effects = MutableSharedFlow<DnsProviderUiEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<DnsProviderUiEffect> = _effects.asSharedFlow()

    private val _selectedTab = MutableStateFlow(0)
    private val _selectedCategory = MutableStateFlow<DnsCategory?>(null)
    private val _showCustomSheet = MutableStateFlow(false)
    private val _showFallbackSheet = MutableStateFlow(false)

    val state: StateFlow<DnsProviderUiState> = combine(
        appPrefs.dnsProviderId,
        appPrefs.upstreamDns,
        appPrefs.dnsProtocol,
        appPrefs.dohUrl,
        appPrefs.odohRelayUrl,
        appPrefs.fallbackDns,
        appPrefs.blockDohBypass,
        _selectedTab,
        _selectedCategory,
        _showCustomSheet,
        _showFallbackSheet
    ) { args: Array<Any?> ->
        val providerId = args[0] as String?
        val upstream = args[1] as String
        val protocol = args[2] as DnsProtocol
        val doh = args[3] as String
        val odohRelay = args[4] as String
        val fallback = args[5] as String
        val dohBypass = args[6] as Boolean
        val tab = args[7] as Int
        val category = args[8] as DnsCategory?
        val showCustom = args[9] as Boolean
        val showFallback = args[10] as Boolean

        val isCustom = providerId == AppPreferences.CUSTOM_DNS_PROVIDER_ID ||
                (providerId == null && DnsProviders.getByIp(upstream) == null)

        val activeProvider = if (isCustom) null else DnsProviders.getById(providerId ?: "")
        val providerName = activeProvider?.name ?: if (isCustom) "Custom DNS" else "System Default"

        val endpoint = when (protocol) {
            DnsProtocol.DOH, DnsProtocol.ODOH -> doh
            DnsProtocol.DOT -> "tls://$upstream"
            DnsProtocol.DOQ -> if (doh.startsWith("quic://", ignoreCase = true)) doh else "quic://${doh.removePrefix("https://")}"
            DnsProtocol.PLAIN -> upstream
        }

        val customDisplay = when (protocol) {
            DnsProtocol.DOH, DnsProtocol.ODOH -> doh
            DnsProtocol.DOT -> "tls://$upstream"
            DnsProtocol.DOQ -> if (doh.startsWith("quic://", ignoreCase = true)) doh else "quic://${doh.removePrefix("https://")}"
            DnsProtocol.PLAIN -> upstream
        }

        DnsProviderUiState(
            selectedTab = tab,
            selectedCategory = category,
            selectedProviderId = if (isCustom) null else providerId,
            activeProviderName = providerName,
            activeProtocol = protocol,
            activeEndpoint = endpoint,
            upstreamDns = upstream,
            dohUrl = doh,
            odohRelayUrl = odohRelay,
            fallbackDns = fallback,
            customDnsDisplay = customDisplay,
            isCustomDns = isCustom,
            blockDohBypass = dohBypass,
            showCustomSheet = showCustom,
            showFallbackSheet = showFallback
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DnsProviderUiState()
    )

    fun onIntent(intent: DnsProviderUiIntent) {
        when (intent) {
            is DnsProviderUiIntent.SelectTab -> _selectedTab.value = intent.tabIndex
            is DnsProviderUiIntent.SelectCategory -> _selectedCategory.value = intent.category
            is DnsProviderUiIntent.SelectProvider -> selectProvider(intent.provider)
            is DnsProviderUiIntent.OpenCustomSheet -> _showCustomSheet.value = true
            is DnsProviderUiIntent.CloseCustomSheet -> _showCustomSheet.value = false
            is DnsProviderUiIntent.SaveCustomDns -> saveCustomDns(intent.protocol, intent.endpoint, intent.relayUrl)
            is DnsProviderUiIntent.OpenFallbackSheet -> _showFallbackSheet.value = true
            is DnsProviderUiIntent.CloseFallbackSheet -> _showFallbackSheet.value = false
            is DnsProviderUiIntent.SaveFallbackDns -> saveFallbackDns(intent.fallbackIp)
            is DnsProviderUiIntent.ToggleBlockDohBypass -> toggleBlockDohBypass(intent.enabled)
        }
    }

    private fun selectProvider(provider: DnsProvider) {
        viewModelScope.launch {
            appPrefs.setDnsProviderId(provider.id)
            appPrefs.setUpstreamDns(provider.ipAddress)

            if (provider.odohRelayUrl != null && provider.dohUrl != null) {
                appPrefs.setDnsProtocol(DnsProtocol.ODOH)
                appPrefs.setDohUrl(provider.dohUrl)
                appPrefs.setOdohRelayUrl(provider.odohRelayUrl)
            } else if (provider.dohUrl != null) {
                if (provider.dohUrl.startsWith("quic://", ignoreCase = true)) {
                    appPrefs.setDnsProtocol(DnsProtocol.DOQ)
                } else {
                    appPrefs.setDnsProtocol(DnsProtocol.DOH)
                }
                appPrefs.setDohUrl(provider.dohUrl)
            } else {
                appPrefs.setDnsProtocol(DnsProtocol.PLAIN)
            }

            val currentFallback = appPrefs.fallbackDns.first()
            if (currentFallback.isNotBlank() && currentFallback == provider.ipAddress) {
                val fallbackIp = DnsProviders.getSecondaryIp(provider)
                    ?: when (provider.id) {
                        DnsProviders.QUAD9.id, DnsProviders.QUAD9_DOQ.id -> DnsProviders.ADGUARD.ipAddress
                        DnsProviders.ADGUARD.id -> DnsProviders.QUAD9.ipAddress
                        DnsProviders.SYSTEM.id -> DnsProviders.QUAD9.ipAddress
                        else -> DnsProviders.ALL_PROVIDERS.firstOrNull {
                            it.id != provider.id && it.category == DnsCategory.PRIVACY
                        }?.ipAddress ?: DnsProviders.QUAD9.ipAddress
                    }
                appPrefs.setFallbackDns(fallbackIp)
            }
            val servers = listOfNotNull(provider.ipAddress.takeIf { it.isNotBlank() }, DnsProviders.getSecondaryIp(provider)?.takeIf { it.isNotBlank() })
            syncToActiveProfile(servers)
            restartService()
        }
    }

    fun getParsedHost(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> {
                try { java.net.URL(trimmed).host } catch (_: Exception) { trimmed }
            }
            trimmed.startsWith("quic://", ignoreCase = true) -> {
                try {
                    java.net.URI(trimmed).host ?: trimmed.removePrefix("quic://").removePrefix("QUIC://")
                } catch (_: Exception) {
                    trimmed.removePrefix("quic://").removePrefix("QUIC://")
                }
            }
            trimmed.startsWith("tls://", ignoreCase = true) -> {
                trimmed.removePrefix("tls://").removePrefix("TLS://")
            }
            else -> trimmed
        }
    }

    private fun saveCustomDns(protocol: DnsProtocol, endpoint: String, relayUrl: String) {
        val trimmedEndpoint = endpoint.trim()
        if (trimmedEndpoint.isBlank()) return

        viewModelScope.launch {
            val parsedHost = getParsedHost(trimmedEndpoint)
            val currentFallback = appPrefs.fallbackDns.first().trim()

            if (protocol == DnsProtocol.PLAIN && currentFallback.equals(parsedHost, ignoreCase = true)) {
                _effects.emit(DnsProviderUiEffect.ShowToast(R.string.dns_error_duplicate))
                return@launch
            }

            appPrefs.setDnsProviderId(AppPreferences.CUSTOM_DNS_PROVIDER_ID)
            appPrefs.setDnsProtocol(protocol)

            when (protocol) {
                DnsProtocol.DOH -> {
                    appPrefs.setDohUrl(trimmedEndpoint)
                    appPrefs.setUpstreamDns(parsedHost)
                }
                DnsProtocol.ODOH -> {
                    appPrefs.setDohUrl(trimmedEndpoint)
                    appPrefs.setOdohRelayUrl(relayUrl.trim())
                    appPrefs.setUpstreamDns(parsedHost)
                }
                DnsProtocol.DOQ -> {
                    val fullDoq = if (trimmedEndpoint.startsWith("quic://", ignoreCase = true)) trimmedEndpoint else "quic://$trimmedEndpoint"
                    appPrefs.setDohUrl(fullDoq)
                    appPrefs.setUpstreamDns(parsedHost)
                }
                DnsProtocol.DOT -> {
                    appPrefs.setUpstreamDns(parsedHost)
                }
                DnsProtocol.PLAIN -> {
                    appPrefs.setUpstreamDns(parsedHost)
                }
            }
            val customServers = listOfNotNull(parsedHost.takeIf { it.isNotBlank() }, currentFallback.takeIf { it.isNotBlank() })
            syncToActiveProfile(customServers)
            _showCustomSheet.value = false
            restartService()
        }
    }

    private suspend fun syncToActiveProfile(servers: List<String>) {
        if (configDao == null || servers.isEmpty()) return
        try {
            val active = configDao.getActive() ?: return
            val updated = ConfigRuleHelper.updateDnsSection(active.content, servers)
            if (updated != active.content) {
                configDao.update(active.copy(content = updated))
                Timber.d("Active profile [dns] synced from DNS screen: $servers")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync DNS server to active profile")
        }
    }

    private fun saveFallbackDns(dns: String) {
        val trimmed = dns.trim()
        viewModelScope.launch {
            if (trimmed.isNotBlank()) {
                val currentUpstream = appPrefs.upstreamDns.first().trim()
                val currentProtocol = appPrefs.dnsProtocol.first()

                if (currentProtocol == DnsProtocol.PLAIN && currentUpstream.equals(trimmed, ignoreCase = true)) {
                    _effects.emit(DnsProviderUiEffect.ShowToast(R.string.dns_error_duplicate))
                    return@launch
                }
            }

            appPrefs.setFallbackDns(trimmed)
            _showFallbackSheet.value = false
            restartService()
        }
    }

    private fun toggleBlockDohBypass(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setBlockDohBypass(enabled)
        }
    }

    private fun restartService() {
        ServiceController.requestRestart(getApplication<Application>().applicationContext)
    }
}
