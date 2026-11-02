package app.pwhs.blockads.ui.tailscale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class TailscaleViewModel(
    application: Application
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()

    private val _uiState = MutableStateFlow(TailscaleUiState())
    val uiState: StateFlow<TailscaleUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<TailscaleUiEffect>()
    val uiEffect: SharedFlow<TailscaleUiEffect> = _uiEffect.asSharedFlow()

    private var pollJob: Job? = null
    private var authEngine: tunnel.Engine? = null

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            val enabled = appPrefs.tailscale.isEnabledSnapshot()
            val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
            val authKey = appPrefs.tailscale.getAuthKeySnapshot()
            val exitNodeId = appPrefs.tailscale.getExitNodeIdSnapshot()
            val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
            val hasKeys = stateJson.contains("node_key") && stateJson.contains("machine_key")

            _uiState.update {
                it.copy(
                    isEnabled = enabled,
                    isLoggedIn = hasKeys,
                    controlUrl = controlUrl,
                    authKey = authKey,
                    activeExitNodeId = exitNodeId,
                    connectionState = if (enabled) "CONNECTING" else "DISCONNECTED"
                )
            }
            if (enabled && hasKeys) {
                val adapter = AdBlockVpnService.activeTunnelAdapter
                if (adapter != null && !adapter.isTailscaleActive()) {
                    adapter.configureTailscale(stateJson, controlUrl, authKey)
                    if (exitNodeId != 0L) {
                        adapter.setTailscaleExitNode(exitNodeId)
                    }
                    adapter.startTailscaleSession("blockads-android")
                }
                startStatusPolling()
            } else if (hasKeys) {
                startStatusPolling()
            }
        }
    }

    fun processIntent(intent: TailscaleUiIntent) {
        when (intent) {
            is TailscaleUiIntent.ToggleEnabled -> toggleTailscale(intent.enabled)
            is TailscaleUiIntent.SignInWithBrowser -> signInWithBrowser()
            is TailscaleUiIntent.SignInWithAuthKey -> signInWithAuthKey(intent.key)
            is TailscaleUiIntent.SetControlUrl -> setControlUrl(intent.url)
            is TailscaleUiIntent.SelectExitNode -> selectExitNode(intent.nodeId)
            is TailscaleUiIntent.RefreshStatus -> viewModelScope.launch { pollStatusOnce() }
            is TailscaleUiIntent.Logout -> logout()
        }
    }

    private fun toggleTailscale(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.tailscale.setEnabled(enabled)
            val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
            val hasKeys = stateJson.contains("node_key") && stateJson.contains("machine_key")

            _uiState.update {
                it.copy(
                    isEnabled = enabled,
                    connectionState = if (enabled) "CONNECTING" else "DISCONNECTED"
                )
            }
            if (enabled) {
                if (!hasKeys) {
                    signInWithBrowser()
                } else {
                    val adapter = AdBlockVpnService.activeTunnelAdapter
                    if (adapter != null) {
                        val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
                        val authKey = appPrefs.tailscale.getAuthKeySnapshot()
                        adapter.configureTailscale(stateJson, controlUrl, authKey)
                        val exitNodeId = appPrefs.tailscale.getExitNodeIdSnapshot()
                        if (exitNodeId != 0L) {
                            adapter.setTailscaleExitNode(exitNodeId)
                        }
                        adapter.startTailscaleSession("blockads-android")
                    } else {
                        ServiceController.requestStart(getApplication<Application>().applicationContext)
                    }
                    startStatusPolling()
                }
            } else {
                pollJob?.cancel()
                val adapter = AdBlockVpnService.activeTunnelAdapter
                adapter?.stopTailscale()
                _uiState.update { it.copy(connectionState = "DISCONNECTED") }
            }
        }
    }

    private fun signInWithBrowser() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = "") }
            try {
                val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
                val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
                val authKey = appPrefs.tailscale.getAuthKeySnapshot()

                val engine = tunnel.Tunnel.newEngine()
                authEngine = engine
                val updatedState = engine.configureTailscale(stateJson, controlUrl, authKey)
                if (updatedState.isNotEmpty() && updatedState != "{}") {
                    appPrefs.tailscale.setStateJson(updatedState)
                }

                val authUrl = engine.startTailscaleAuth("blockads-android")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authUrl = authUrl,
                        connectionState = if (authUrl.isNotEmpty()) "NEEDS_AUTH" else "CONNECTED"
                    )
                }

                if (authUrl.isNotEmpty()) {
                    _uiEffect.emit(TailscaleUiEffect.OpenBrowser(authUrl))
                }
                startStatusPolling()
            } catch (e: Exception) {
                Timber.e(e, "Tailscale browser sign-in failed")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Sign-in error") }
            }
        }
    }

    private fun signInWithAuthKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = "", authKey = key) }
            try {
                appPrefs.tailscale.setAuthKey(key)
                val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
                val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()

                val engine = tunnel.Tunnel.newEngine()
                authEngine = engine
                val updatedState = engine.configureTailscale(stateJson, controlUrl, key)
                if (updatedState.isNotEmpty() && updatedState != "{}") {
                    appPrefs.tailscale.setStateJson(updatedState)
                }

                val authUrl = engine.startTailscaleAuth("blockads-android")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authUrl = authUrl,
                        connectionState = if (authUrl.isNotEmpty()) "NEEDS_AUTH" else "CONNECTED"
                    )
                }

                if (authUrl.isEmpty()) {
                    val activeState = engine.tailscaleState.takeIf { it.isNotEmpty() && it != "{}" }
                    engine.stopTailscale()
                    authEngine = null
                    if (activeState != null) {
                        appPrefs.tailscale.setStateJson(activeState)
                    }
                    appPrefs.tailscale.setEnabled(true)
                    _uiState.update { it.copy(isEnabled = true, isLoggedIn = true) }
                    _uiEffect.emit(TailscaleUiEffect.ShowSnackbar("Tailscale authorized!"))
                    val adapter = AdBlockVpnService.activeTunnelAdapter
                    if (adapter != null && activeState != null) {
                        adapter.configureTailscale(activeState, controlUrl, key)
                        adapter.startTailscaleSession("blockads-android")
                    } else {
                        ServiceController.requestStart(getApplication<Application>().applicationContext)
                    }
                }
                startStatusPolling()
            } catch (e: Exception) {
                Timber.e(e, "Tailscale auth-key sign-in failed")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Auth error") }
            }
        }
    }

    private fun setControlUrl(url: String) {
        viewModelScope.launch {
            appPrefs.tailscale.setControlUrl(url)
            _uiState.update { it.copy(controlUrl = url) }
        }
    }

    private fun selectExitNode(nodeId: Long) {
        viewModelScope.launch {
            appPrefs.tailscale.setExitNodeId(nodeId)
            _uiState.update { it.copy(activeExitNodeId = nodeId) }
            val adapter = AdBlockVpnService.activeTunnelAdapter
            if (adapter != null) {
                adapter.setTailscaleExitNode(nodeId)
            } else {
                ServiceController.requestRestart(getApplication())
            }
        }
    }

    private fun logout() {
        viewModelScope.launch {
            pollJob?.cancel()
            authEngine?.stopTailscale()
            authEngine = null
            AdBlockVpnService.activeTunnelAdapter?.stopTailscale()
            appPrefs.tailscale.setEnabled(false)
            appPrefs.tailscale.setStateJson("")
            appPrefs.tailscale.setAuthKey("")
            appPrefs.tailscale.setExitNodeId(0L)

            _uiState.update {
                TailscaleUiState(
                    isEnabled = false,
                    isLoggedIn = false,
                    connectionState = "DISCONNECTED"
                )
            }
        }
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                pollStatusOnce()
                delay(3000)
            }
        }
    }

    private suspend fun pollStatusOnce() = withContext(Dispatchers.IO) {
        try {
            // When actively authenticating via authEngine, poll authEngine first.
            // Otherwise, query active VPN tunnel adapter.
            val statusJson = if (authEngine != null) {
                val s = authEngine?.tailscaleStatus ?: ""
                if (s.isNotEmpty() && s != "{}") s else AdBlockVpnService.activeTunnelAdapter?.getTailscaleStatus() ?: ""
            } else {
                AdBlockVpnService.activeTunnelAdapter?.getTailscaleStatus() ?: ""
            }

            if (statusJson.isNotEmpty() && statusJson != "{}") {
                parseAndApplyStatus(statusJson)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error polling status")
        }
    }

    private fun parseAndApplyStatus(json: String) {
        try {
            val obj = JSONObject(json)
            val state = obj.optString("state", "DISCONNECTED")
            val ipsList = mutableListOf<String>()
            val ipsArr = obj.optJSONArray("tailscale_ips")
            if (ipsArr != null) {
                for (i in 0 until ipsArr.length()) {
                    ipsList.add(ipsArr.getString(i))
                }
            }
            val dnsName = obj.optString("magic_dns_name", "")

            val peersList = mutableListOf<TailscalePeer>()
            val peersArr = obj.optJSONArray("peers")
            if (peersArr != null) {
                for (i in 0 until peersArr.length()) {
                    val p = peersArr.getJSONObject(i)
                    val pIps = mutableListOf<String>()
                    val pIpsArr = p.optJSONArray("ips")
                    if (pIpsArr != null) {
                        for (j in 0 until pIpsArr.length()) {
                            pIps.add(pIpsArr.getString(j))
                        }
                    }
                    peersList.add(
                        TailscalePeer(
                            id = p.optLong("id"),
                            name = p.optString("name"),
                            ips = pIps,
                            os = p.optString("os"),
                            online = p.optBoolean("online"),
                            isExitNode = p.optBoolean("is_exit_node")
                        )
                    )
                }
            }

            val exitList = mutableListOf<TailscaleExitNodeItem>()
            val exitsArr = obj.optJSONArray("exit_nodes")
            if (exitsArr != null) {
                for (i in 0 until exitsArr.length()) {
                    val ex = exitsArr.getJSONObject(i)
                    exitList.add(
                        TailscaleExitNodeItem(
                            id = ex.optLong("id"),
                            name = ex.optString("name"),
                            ip = ex.optString("ip")
                        )
                    )
                }
            }

            val isNowConnected = state == "CONNECTED"
            _uiState.update {
                it.copy(
                    connectionState = state,
                    isLoggedIn = it.isLoggedIn || isNowConnected,
                    selfIp = ipsList.firstOrNull() ?: it.selfIp,
                    magicDnsName = dnsName.ifEmpty { it.magicDnsName },
                    peers = peersList,
                    exitNodes = exitList
                )
            }

            if (isNowConnected && authEngine != null) {
                viewModelScope.launch {
                    val engine = authEngine
                    authEngine = null
                    val activeState = engine?.tailscaleState?.takeIf { it.isNotEmpty() && it != "{}" }
                    engine?.stopTailscale()
                    if (activeState != null) {
                        appPrefs.tailscale.setStateJson(activeState)
                    }
                    appPrefs.tailscale.setEnabled(true)
                    _uiState.update { it.copy(isEnabled = true, isLoggedIn = true) }
                    val adapter = AdBlockVpnService.activeTunnelAdapter
                    if (adapter != null) {
                        val stateToUse = activeState ?: appPrefs.tailscale.getStateJsonSnapshot()
                        val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
                        val authKey = appPrefs.tailscale.getAuthKeySnapshot()
                        adapter.configureTailscale(stateToUse, controlUrl, authKey)
                        val exitNodeId = appPrefs.tailscale.getExitNodeIdSnapshot()
                        if (exitNodeId != 0L) {
                            adapter.setTailscaleExitNode(exitNodeId)
                        }
                        adapter.startTailscaleSession("blockads-android")
                    } else {
                        ServiceController.requestStart(getApplication<Application>().applicationContext)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        authEngine?.stopTailscale()
        authEngine = null
    }
}
