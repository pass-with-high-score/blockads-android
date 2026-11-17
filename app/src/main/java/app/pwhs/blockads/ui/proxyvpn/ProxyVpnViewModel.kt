package app.pwhs.blockads.ui.proxyvpn

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ShadowsocksProfile
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.utils.ProxyLinkParser
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.service.vpn.VpnNotificationManager
import app.pwhs.blockads.utils.WgConfigIssue
import app.pwhs.blockads.utils.WireGuardConfigParser
import app.pwhs.blockads.utils.configIssue
import app.pwhs.blockads.utils.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import app.pwhs.blockads.utils.NodePingResult
import app.pwhs.blockads.utils.ProxyPingTester
import app.pwhs.blockads.data.entities.ProxySubscription
import app.pwhs.blockads.ui.proxyvpn.delegate.ProxySubscriptionDelegate
import app.pwhs.blockads.utils.WireGuardPingTester
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class ProxyVpnViewModel(
    application: Application,
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()
    private val httpClient: HttpClient by inject()

    private val subscriptionDelegate = ProxySubscriptionDelegate(
        appPrefs = appPrefs,
        httpClient = httpClient,
        scope = viewModelScope,
        emitEvent = { _events.emit(it) },
        onError = { _error.value = it },
        onRequestRestart = { ServiceController.requestRestart(getApplication()) }
    )

    val preferredIPType: StateFlow<String> = appPrefs.preferredIPType.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppPreferences.PREFERRED_IP_AUTO
    )

    private val networkInfoDelegate = app.pwhs.blockads.ui.proxyvpn.delegate.ProxyNetworkInfoDelegate(
        context = application,
        httpClient = httpClient,
        scope = viewModelScope
    )
    val networkName: StateFlow<String> = networkInfoDelegate.networkName
    val networkType: StateFlow<app.pwhs.blockads.utils.CurrentNetworkType> = networkInfoDelegate.networkType
    val currentIp: StateFlow<String?> = networkInfoDelegate.currentIp
    val isRefreshingIp: StateFlow<Boolean> = networkInfoDelegate.isRefreshingIp

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _isProxyActive = MutableStateFlow(false)
    val isProxyActive: StateFlow<Boolean> = _isProxyActive.asStateFlow()

    private val _activeProxyType = MutableStateFlow(AppPreferences.PROXY_TYPE_WIREGUARD)
    val activeProxyType: StateFlow<String> = _activeProxyType.asStateFlow()

    // WireGuard state
    private val _wgProfiles = MutableStateFlow<List<WireGuardProfile>>(emptyList())
    val wgProfiles: StateFlow<List<WireGuardProfile>> = _wgProfiles.asStateFlow()

    private val _activeWgProfileId = MutableStateFlow<String?>(null)
    val activeWgProfileId: StateFlow<String?> = _activeWgProfileId.asStateFlow()

    private val _isLoadingWg = MutableStateFlow(false)
    val isLoadingWg: StateFlow<Boolean> = _isLoadingWg.asStateFlow()

    private val _splitDnsZones = MutableStateFlow("")
    val splitDnsZones: StateFlow<String> = _splitDnsZones.asStateFlow()

    private val _excludeLan = MutableStateFlow(false)
    val excludeLan: StateFlow<Boolean> = _excludeLan.asStateFlow()

    private val _allowAppBypass = MutableStateFlow(false)
    val allowAppBypass: StateFlow<Boolean> = _allowAppBypass.asStateFlow()

    // Proxy Nodes state (VLESS, Trojan, Shadowsocks)
    private val _proxyProfiles = MutableStateFlow<List<ProxyProfile>>(emptyList())
    val proxyProfiles: StateFlow<List<ProxyProfile>> = _proxyProfiles.asStateFlow()

    val proxySubscriptions: StateFlow<List<ProxySubscription>> = subscriptionDelegate.proxySubscriptions
    val lastSubscriptionUrl: StateFlow<String?> = subscriptionDelegate.lastSubscriptionUrl
    val isUpdatingSubscription: StateFlow<Boolean> = subscriptionDelegate.isUpdatingSubscription

    fun updateSubscription(subscription: ProxySubscription? = null) = subscriptionDelegate.updateSubscription(subscription)
    fun updateAllSubscriptions() = subscriptionDelegate.updateAllSubscriptions()
    fun deleteSubscription(id: String, deleteNodes: Boolean = true) = subscriptionDelegate.deleteSubscription(id, deleteNodes)
    fun removeDuplicates() = subscriptionDelegate.removeDuplicates()

    // WireGuard ping state
    private val _wgPingResults = MutableStateFlow<Map<String, NodePingResult>>(emptyMap())
    val wgPingResults: StateFlow<Map<String, NodePingResult>> = _wgPingResults.asStateFlow()

    private val _isPingingAllWg = MutableStateFlow(false)
    val isPingingAllWg: StateFlow<Boolean> = _isPingingAllWg.asStateFlow()

    fun pingWireGuardProfile(profileId: String) {
        val target = _wgProfiles.value.firstOrNull { it.id == profileId } ?: return
        viewModelScope.launch {
            _wgPingResults.update { it + (profileId to NodePingResult.Testing) }
            val res = WireGuardPingTester.pingProfile(target)
            _wgPingResults.update { it + (profileId to res) }
        }
    }

    fun pingAllWireGuardProfiles() {
        val profiles = _wgProfiles.value
        if (profiles.isEmpty() || _isPingingAllWg.value) return
        viewModelScope.launch {
            _isPingingAllWg.value = true
            _wgPingResults.update { current ->
                current + profiles.associate { it.id to NodePingResult.Testing }
            }
            WireGuardPingTester.pingAll(profiles) { id, res ->
                _wgPingResults.update { it + (id to res) }
            }
            _isPingingAllWg.value = false
        }
    }

    fun pingAll() {
        pingAllWireGuardProfiles()
        pingAllProxyNodes()
    }

    private val _activeProxyProfileId = MutableStateFlow<String?>(null)
    val activeProxyProfileId: StateFlow<String?> = _activeProxyProfileId.asStateFlow()

    // Shadowsocks state (legacy compatibility)
    private val _ssProfiles = MutableStateFlow<List<ShadowsocksProfile>>(emptyList())
    val ssProfiles: StateFlow<List<ShadowsocksProfile>> = _ssProfiles.asStateFlow()

    private val _activeSsProfileId = MutableStateFlow<String?>(null)
    val activeSsProfileId: StateFlow<String?> = _activeSsProfileId.asStateFlow()

    private val _pingResults = MutableStateFlow<Map<String, NodePingResult>>(emptyMap())
    val pingResults: StateFlow<Map<String, NodePingResult>> = _pingResults.asStateFlow()

    private val _isPingingAll = MutableStateFlow(false)
    val isPingingAll: StateFlow<Boolean> = _isPingingAll.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _events = MutableSharedFlow<ProxyVpnUiEvent>()
    val events: SharedFlow<ProxyVpnUiEvent> = _events.asSharedFlow()

    init {
        combine(appPrefs.wgProfiles, appPrefs.wgActiveProfileId) { l, a -> l to a }
            .onEach { (list, active) ->
                _wgProfiles.value = list
                _activeWgProfileId.value = active ?: list.firstOrNull { it.configIssue == null }?.id
            }.launchIn(viewModelScope)

        combine(appPrefs.proxyProfiles, appPrefs.activeProxyProfileId) { l, a -> l to a }
            .onEach { (list, active) ->
                _proxyProfiles.value = list
                _activeProxyProfileId.value = active ?: list.firstOrNull()?.id
            }.launchIn(viewModelScope)

        combine(appPrefs.ssProfiles, appPrefs.activeSsProfileId) { l, a -> l to a }
            .onEach { (list, active) ->
                _ssProfiles.value = list
                _activeSsProfileId.value = active ?: list.firstOrNull()?.id
            }.launchIn(viewModelScope)

        combine(appPrefs.routingMode, appPrefs.activeProxyType) { m, t -> m to t }
            .onEach { (mode, type) ->
                _isProxyActive.value = mode == AppPreferences.ROUTING_MODE_PROXY_VPN ||
                    mode == AppPreferences.ROUTING_MODE_WIREGUARD ||
                    mode == AppPreferences.ROUTING_MODE_SHADOWSOCKS
                _activeProxyType.value = type
                networkInfoDelegate.refreshAfterVpnToggle()
            }.launchIn(viewModelScope)

        viewModelScope.launch {
            _splitDnsZones.value = appPrefs.splitDnsZones.first()
            _excludeLan.value = appPrefs.excludeLan.first()
            _allowAppBypass.value = appPrefs.allowAppBypass.first()
        }
    }

    fun refreshNetworkAndIp() = networkInfoDelegate.refreshPublicIp()
    fun selectTab(index: Int) { _selectedTab.value = index }
    fun clearError() { _error.value = null }

    private suspend fun disableHttpsFilteringIfNeeded() {
        if (appPrefs.getHttpsFilteringEnabledSnapshot()) {
            appPrefs.setHttpsFilteringEnabled(false)
            _events.emit(ProxyVpnUiEvent.HttpsFilteringDisabled)
        }
    }

    fun toggleProxyVpn() {
        viewModelScope.launch {
            val currentlyActive = _isProxyActive.value
            if (currentlyActive) {
                appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                _events.emit(ProxyVpnUiEvent.ProxyVpnToggled(false))
            } else {
                disableHttpsFilteringIfNeeded()
                val targetType = if (_selectedTab.value == 1) AppPreferences.PROXY_TYPE_SHADOWSOCKS else AppPreferences.PROXY_TYPE_WIREGUARD
                appPrefs.setActiveProxyType(targetType)
                appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_PROXY_VPN)
                _events.emit(ProxyVpnUiEvent.ProxyVpnToggled(true))
            }
            ServiceController.requestRestart(getApplication())
        }
    }

    fun setPreferredIPType(type: String) {
        viewModelScope.launch {
            appPrefs.setPreferredIPType(type)
        }
    }

    // ── WireGuard Operations ─────────────────────────────────────────────

    fun setActiveWireGuardProfile(id: String) {
        viewModelScope.launch {
            val profile = _wgProfiles.value.firstOrNull { it.id == id } ?: return@launch
            profile.configIssue?.let { issue ->
                _error.value = fixFirstMessage(profile, issue)
                return@launch
            }
            disableHttpsFilteringIfNeeded()
            appPrefs.setActiveWgProfile(id)
            appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_WIREGUARD)
            appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_PROXY_VPN)
            _events.emit(ProxyVpnUiEvent.ProfileActivated(profile.name))
            ServiceController.requestRestart(getApplication())
        }
    }

    fun importWireGuardFromUri(uri: Uri) {
        viewModelScope.launch {
            _isLoadingWg.value = true
            _error.value = null
            try {
                val content = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: throw IllegalArgumentException("Could not read file")
                }
                val displayName = deriveNameFromUri(uri) ?: defaultUniqueName()
                val parsed = WireGuardConfigParser.parse(content)
                val profile = WireGuardProfile(
                    id = WireGuardProfile.newId(),
                    name = displayName,
                    config = parsed,
                )
                val makeActive = appPrefs.getActiveWgProfileSnapshot() == null && profile.configIssue == null
                appPrefs.addOrUpdateWgProfile(profile, makeActive = makeActive)
                _events.emit(ProxyVpnUiEvent.ProfileImported(displayName))
                if (makeActive && _isProxyActive.value) {
                    ServiceController.requestRestart(getApplication())
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to import configuration"
            } finally {
                _isLoadingWg.value = false
            }
        }
    }

    fun renameWireGuardProfile(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            appPrefs.renameWgProfile(id, name)
            _events.emit(ProxyVpnUiEvent.ProfileRenamed)
        }
    }

    fun deleteWireGuardProfile(id: String) {
        viewModelScope.launch {
            val target = _wgProfiles.value.firstOrNull { it.id == id } ?: return@launch
            val wasActive = _activeWgProfileId.value == id
            appPrefs.removeWgProfile(id)
            _events.emit(ProxyVpnUiEvent.ProfileDeleted(target.name))
            if (wasActive) VpnNotificationManager.cancelWireGuardConfigIssue(getApplication())
            if (wasActive && _isProxyActive.value && _activeProxyType.value == AppPreferences.PROXY_TYPE_WIREGUARD) {
                if (appPrefs.getActiveWgProfileSnapshot() == null) {
                    appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                    _events.emit(ProxyVpnUiEvent.ProxyVpnToggled(false))
                }
                ServiceController.requestRestart(getApplication())
            }
        }
    }

    fun setSplitDnsZones(zones: String) {
        _splitDnsZones.value = zones
        viewModelScope.launch {
            appPrefs.setSplitDnsZones(zones)
            if (_isProxyActive.value) ServiceController.requestRestart(getApplication())
        }
    }

    fun setExcludeLan(enabled: Boolean) {
        _excludeLan.value = enabled
        viewModelScope.launch {
            appPrefs.setExcludeLan(enabled)
            if (_isProxyActive.value) ServiceController.requestRestart(getApplication())
        }
    }

    fun setAllowAppBypass(enabled: Boolean) {
        _allowAppBypass.value = enabled
        viewModelScope.launch {
            appPrefs.setAllowAppBypass(enabled)
            if (_isProxyActive.value) ServiceController.requestRestart(getApplication())
        }
    }

    // ── Proxy Nodes (VLESS, Trojan, Shadowsocks) Operations ──────────────

    fun setActiveProxyProfile(id: String) {
        viewModelScope.launch {
            val profile = _proxyProfiles.value.firstOrNull { it.id == id } ?: return@launch
            disableHttpsFilteringIfNeeded()
            appPrefs.setActiveProxyProfileId(id)
            appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_SHADOWSOCKS)
            appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_PROXY_VPN)
            _events.emit(ProxyVpnUiEvent.ProfileActivated(profile.displayName))
            ServiceController.requestRestart(getApplication())
        }
    }

    suspend fun saveProxyNodeLink(rawUri: String): Boolean {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return false

        // 1. Subscription URL (http/https) — unless it's a single HTTP/HTTPS proxy URI (user:pass@host:port or host:port with no path)
        if ((trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) &&
            !isLikelyHttpProxyUri(trimmed)
        ) {
            val importResult = subscriptionDelegate.subscriptionManager.importOrUpdateSubscription(trimmed)
            return importResult.fold(
                onSuccess = { (sub, res) ->
                    disableHttpsFilteringIfNeeded()
                    _events.emit(ProxyVpnUiEvent.ProfileImported("${res.totalParsed} profiles (${sub.name})"))
                    ServiceController.requestRestart(getApplication())
                    true
                },
                onFailure = { err ->
                    Timber.e(err, "Failed to import subscription: %s", trimmed)
                    false
                }
            )
        }

        // 2. WireGuard / AmneziaWG URI (wireguard://, vpn://, awg://)
        if (trimmed.startsWith("wireguard://", ignoreCase = true) ||
            trimmed.startsWith("vpn://", ignoreCase = true) ||
            trimmed.startsWith("awg://", ignoreCase = true)) {
            return try {
                val wgProfile = if (trimmed.startsWith("wireguard://", ignoreCase = true)) {
                    WireGuardConfigParser.parseUri(trimmed)
                } else {
                    WireGuardConfigParser.parseVpnUri(trimmed)
                }
                val makeActive = appPrefs.getActiveWgProfileSnapshot() == null && wgProfile.configIssue == null
                appPrefs.addOrUpdateWgProfile(wgProfile, makeActive = makeActive)
                appPrefs.removeDuplicateWgProfiles()
                _events.emit(ProxyVpnUiEvent.ProfileImported(wgProfile.name))
                if (makeActive && _isProxyActive.value) {
                    ServiceController.requestRestart(getApplication())
                }
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse wireguard/vpn link: %s", trimmed)
                false
            }
        }

        // 3. Single proxy link (vless://, vmess://, trojan://, ss://, hysteria2://, hy2://)
        val parseResult = ProxyLinkParser.parse(trimmed)
        return parseResult.fold(
            onSuccess = { profile ->
                disableHttpsFilteringIfNeeded()
                appPrefs.addProxyProfile(profile)
                appPrefs.removeDuplicateProfiles()
                appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_SHADOWSOCKS)
                appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_PROXY_VPN)
                _events.emit(ProxyVpnUiEvent.ProfileImported(profile.displayName))
                ServiceController.requestRestart(getApplication())
                true
            },
            onFailure = { err ->
                Timber.e(err, "Failed to parse proxy link: %s", trimmed)
                false
            }
        )
    }

    private fun isLikelyHttpProxyUri(raw: String): Boolean = try {
        val uri = java.net.URI(raw)
        !uri.rawUserInfo.isNullOrEmpty() ||
            ((uri.path.isNullOrEmpty() || uri.path == "/") && uri.port != -1)
    } catch (_: Exception) {
        false
    }

    fun pingProxyNode(profileId: String) {
        val target = _proxyProfiles.value.firstOrNull { it.id == profileId } ?: return
        viewModelScope.launch {
            _pingResults.update { it + (profileId to NodePingResult.Testing) }
            val res = ProxyPingTester.pingNode(target)
            _pingResults.update { it + (profileId to res) }
        }
    }

    fun pingAllProxyNodes() {
        val profiles = _proxyProfiles.value
        if (profiles.isEmpty() || _isPingingAll.value) return
        viewModelScope.launch {
            _isPingingAll.value = true
            _pingResults.update { current ->
                current + profiles.associate { it.id to NodePingResult.Testing }
            }
            ProxyPingTester.pingAll(profiles) { id, res ->
                _pingResults.update { it + (id to res) }
            }
            _isPingingAll.value = false
        }
    }

    fun renameProxyProfile(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            appPrefs.renameProxyProfile(id, name)
            _events.emit(ProxyVpnUiEvent.ProfileRenamed)
        }
    }

    fun deleteProxyProfile(id: String) {
        viewModelScope.launch {
            val target = _proxyProfiles.value.firstOrNull { it.id == id } ?: return@launch
            val wasActive = _activeProxyProfileId.value == id
            appPrefs.deleteProxyProfile(id)
            _events.emit(ProxyVpnUiEvent.ProfileDeleted(target.displayName))
            if (wasActive && _isProxyActive.value && (_activeProxyType.value == AppPreferences.PROXY_TYPE_SHADOWSOCKS || _activeProxyType.value == AppPreferences.PROXY_TYPE_NODE)) {
                if (appPrefs.getActiveProxyProfileSnapshot() == null) {
                    appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                    _events.emit(ProxyVpnUiEvent.ProxyVpnToggled(false))
                }
                ServiceController.requestRestart(getApplication())
            }
        }
    }

    private fun fixFirstMessage(profile: WireGuardProfile, issue: WgConfigIssue): String {
        val res = getApplication<Application>().resources
        return res.getString(R.string.wireguard_issue_fix_first, profile.name, issue.describe(res))
    }

    private fun deriveNameFromUri(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.removeSuffix(".conf")?.takeIf { it.isNotBlank() }
            } else null
        }
    }.getOrNull()

    private fun defaultUniqueName(): String {
        val existing = _wgProfiles.value.map { it.name }.toSet()
        var n = 1
        while ("Tunnel $n" in existing) n++
        return "Tunnel $n"
    }

    override fun onCleared() {
        super.onCleared()
        networkInfoDelegate.cleanUp()
    }
}
