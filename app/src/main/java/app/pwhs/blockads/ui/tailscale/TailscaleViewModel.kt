package app.pwhs.blockads.ui.tailscale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.service.VpnState
import app.pwhs.blockads.service.tailscale.TailscalePeerNotifier
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
    application: Application,
    private val serviceClient: TailscaleServiceClient = TailscaleServiceClient(application.applicationContext),
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()

    private val _uiState = MutableStateFlow(TailscaleUiState())
    val uiState: StateFlow<TailscaleUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<TailscaleUiEffect>(extraBufferCapacity = 64)
    val uiEffect: SharedFlow<TailscaleUiEffect> = _uiEffect.asSharedFlow()

    private var pollJob: Job? = null
    private var authEngine: tunnel.Engine? = null
    private val tailscalePeerNotifier by lazy { TailscalePeerNotifier(getApplication()) }
    private var syncJob: Job? = null

    private fun launchSync(stateJson: String? = null) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            syncActiveSession(stateJson)
            if (isActive) {
                pollStatusOnce()
            }
        }
    }

    init {
        serviceClient.onConnected = {
            if (_uiState.value.isEnabled && _uiState.value.isLoggedIn) {
                launchSync()
            }
        }
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

            val pinned = appPrefs.tailscale.getPinnedPeerIdsSnapshot()
            val tdUri = appPrefs.tailscale.getTaildropDirUriSnapshot()
            val tdName = appPrefs.tailscale.getTaildropDirNameSnapshot()
            val isVpnRunning = AdBlockVpnService.isRunning

            _uiState.update {
                it.copy(
                    isEnabled = enabled, isLoggedIn = hasKeys, isVpnRunning = isVpnRunning,
                    controlUrl = controlUrl, authKey = authKey, activeExitNodeId = exitNodeId,
                    pinnedPeerIds = pinned, taildropDirUri = tdUri, taildropDirName = tdName,
                    connectionState = when {
                        !enabled -> "DISCONNECTED"
                        !isVpnRunning -> "VPN_DISCONNECTED"
                        else -> "CONNECTING"
                    }
                )
            }
            if (isVpnRunning) {
                serviceClient.bind()
            }
            if (enabled && hasKeys && isVpnRunning) {
                launchSync(stateJson)
                startStatusPolling()
            } else if (hasKeys && isVpnRunning) {
                startStatusPolling()
            }

            viewModelScope.launch {
                AdBlockVpnService.state.collect { vpnState ->
                    val running = vpnState == VpnState.RUNNING
                    if (running) {
                        serviceClient.bind()
                    } else {
                        serviceClient.unbind()
                        if (authEngine == null) {
                            pollJob?.cancel()
                        }
                    }
                    _uiState.update { state ->
                        val connState = when {
                            !state.isEnabled -> "DISCONNECTED"
                            !running -> "VPN_DISCONNECTED"
                            state.connectionState == "VPN_DISCONNECTED" -> "CONNECTING"
                            else -> state.connectionState
                        }
                        state.copy(isVpnRunning = running, connectionState = connState)
                    }
                    if (running && _uiState.value.isEnabled && _uiState.value.isLoggedIn) {
                        launchSync()
                        startStatusPolling()
                    }
                }
            }

            viewModelScope.launch {
                appPrefs.tailscale.pinnedPeerIds.collect { pinnedIds ->
                    _uiState.update { state ->
                        val updated = state.peers.map { p -> p.copy(isPinned = pinnedIds.contains(p.id)) }
                        val sorted = updated.sortedWith(
                            compareByDescending<TailscalePeer> { it.isPinned }
                                .thenByDescending { it.isSelf }
                                .thenByDescending { it.online }
                                .thenBy { it.name.lowercase() }
                        )
                        state.copy(
                            pinnedPeerIds = pinnedIds,
                            peers = sorted,
                            selectedPeerForDetail = state.selectedPeerForDetail?.let { cur ->
                                cur.copy(isPinned = pinnedIds.contains(cur.id))
                            }
                        )
                    }
                }
            }
        }
    }

    fun processIntent(intent: TailscaleUiIntent) {
        when (intent) {
            is TailscaleUiIntent.ToggleEnabled -> toggleTailscale(intent.enabled)
            is TailscaleUiIntent.StartVpn -> ServiceController.requestStart(getApplication<Application>().applicationContext)
            is TailscaleUiIntent.SignInWithBrowser -> signInWithBrowser()
            is TailscaleUiIntent.SignInWithAuthKey -> signInWithAuthKey(intent.key)
            is TailscaleUiIntent.SetControlUrl -> setControlUrl(intent.url)
            is TailscaleUiIntent.SelectExitNode -> selectExitNode(intent.nodeId)
            is TailscaleUiIntent.RefreshStatus -> viewModelScope.launch { pollStatusOnce() }
            is TailscaleUiIntent.Logout -> logout()
            is TailscaleUiIntent.SelectPeerForDetail -> _uiState.update { it.copy(selectedPeerForDetail = intent.peer) }
            is TailscaleUiIntent.TogglePinPeer -> togglePinPeer(intent.peerId)
            is TailscaleUiIntent.PingPeer -> pingPeer(intent.peerId, intent.target)
            is TailscaleUiIntent.SetTaildropDirectory -> setTaildropDirectory(intent.uri, intent.displayName)
            is TailscaleUiIntent.ResetTaildropDirectory -> resetTaildropDirectory()
            is TailscaleUiIntent.CopyToClipboard -> copyToClipboard(intent.text, intent.label)
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
                    launchSync(stateJson)
                    startStatusPolling()
                }
            } else {
                syncJob?.cancel()
                syncJob = null
                pollJob?.cancel()
                serviceClient.stopTailscale()
                _uiState.update { it.copy(connectionState = "DISCONNECTED") }
            }
        }
    }

    private fun signInWithBrowser() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = "") }
            try {
                authEngine?.stopTailscale()
                authEngine = null

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
                } else {
                    val activeState = engine.tailscaleState.takeIf { it.isNotEmpty() && it != "{}" }
                    engine.stopTailscale()
                    authEngine = null
                    applyActiveTailscaleSession(activeState)
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
                authEngine?.stopTailscale()
                authEngine = null

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
                    applyActiveTailscaleSession(activeState)
                }
                startStatusPolling()
            } catch (e: Exception) {
                Timber.e(e, "Tailscale auth-key sign-in failed")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Auth error") }
            }
        }
    }

    private fun setControlUrl(url: String) = viewModelScope.launch {
        appPrefs.tailscale.setControlUrl(url)
        _uiState.update { it.copy(controlUrl = url) }
    }

    private fun selectExitNode(nodeId: Long) = viewModelScope.launch {
        appPrefs.tailscale.setExitNodeId(nodeId)
        _uiState.update { it.copy(activeExitNodeId = nodeId) }
        serviceClient.setExitNode(nodeId)
    }

    private fun logout() {
        viewModelScope.launch {
            syncJob?.cancel()
            syncJob = null
            pollJob?.cancel()
            authEngine?.stopTailscale()
            authEngine = null
            serviceClient.stopTailscale()
            appPrefs.tailscale.setEnabled(false)
            appPrefs.tailscale.setStateJson("")
            appPrefs.tailscale.setAuthKey("")
            appPrefs.tailscale.setExitNodeId(0L)

            _uiState.update { state ->
                TailscaleUiState(
                    isEnabled = false, isLoggedIn = false,
                    isVpnRunning = AdBlockVpnService.isRunning,
                    connectionState = "DISCONNECTED", controlUrl = state.controlUrl,
                    taildropDirUri = state.taildropDirUri, taildropDirName = state.taildropDirName
                )
            }
        }
    }

    private fun togglePinPeer(peerId: Long) = viewModelScope.launch {
        appPrefs.tailscale.togglePinPeer(peerId)
    }

    private fun setTaildropDirectory(uri: String, name: String) = viewModelScope.launch {
        appPrefs.tailscale.setTaildropDirectory(uri, name)
        _uiState.update { it.copy(taildropDirUri = uri, taildropDirName = name) }
    }

    private fun resetTaildropDirectory() = viewModelScope.launch {
        appPrefs.tailscale.resetTaildropDirectory()
        _uiState.update { it.copy(taildropDirUri = "", taildropDirName = "Downloads/Taildrop") }
    }

    private fun copyToClipboard(text: String, label: String) = viewModelScope.launch {
        _uiEffect.emit(TailscaleUiEffect.CopyToClipboard(text, label))
    }

    private fun pingPeer(peerId: Long, target: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                val updated = state.peers.map {
                    if (it.id == peerId) it.copy(isPingLoading = true, pingError = null) else it
                }
                state.copy(
                    peers = updated,
                    selectedPeerForDetail = state.selectedPeerForDetail?.takeIf { it.id == peerId }
                        ?.copy(isPingLoading = true, pingError = null) ?: state.selectedPeerForDetail
                )
            }

            val rawJson = serviceClient.pingPeer(target)
            var rtt: Long? = null
            var errorStr: String? = null
            if (rawJson.isNotEmpty()) {
                val obj = JSONObject(rawJson)
                if (obj.has("latency_ms")) {
                    rtt = obj.optLong("latency_ms")
                } else if (obj.has("error")) {
                    errorStr = obj.optString("error")
                }
            } else {
                errorStr = if (AdBlockVpnService.isRunning) "Peer unreachable" else "Tunnel not active"
            }

            _uiState.update { state ->
                val updated = state.peers.map {
                    if (it.id == peerId) it.copy(isPingLoading = false, lastPingMs = rtt, pingError = errorStr) else it
                }
                state.copy(
                    peers = updated,
                    selectedPeerForDetail = state.selectedPeerForDetail?.takeIf { it.id == peerId }
                        ?.copy(isPingLoading = false, lastPingMs = rtt, pingError = errorStr) ?: state.selectedPeerForDetail
                )
            }
        }
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                pollStatusOnce()
                val interval = if (authEngine != null) 1000L else 3000L
                delay(interval)
            }
        }
    }

    private suspend fun pollStatusOnce() = withContext(Dispatchers.IO) {
        try {
            // When actively authenticating via authEngine, poll authEngine first.
            // Otherwise, query active VPN tunnel adapter via bound AIDL service.
            val statusJson = if (authEngine != null) {
                val s = authEngine?.tailscaleStatus ?: ""
                if (s.isNotEmpty() && s != "{}") s else serviceClient.getStatus()
            } else {
                serviceClient.getStatus()
            }

            if (statusJson.isNotEmpty() && statusJson != "{}") {
                parseAndApplyStatus(statusJson)
            } else {
                val hasKeys = appPrefs.tailscale.getStateJsonSnapshot().let {
                    it.contains("node_key") && it.contains("machine_key")
                }
                if (hasKeys && !_uiState.value.isLoggedIn) {
                    _uiState.update { it.copy(isLoggedIn = true) }
                }
                // When running in multi-process (:vpn), Tailscale status is queried via AIDL.
                // If VPN is not running, ensure connection state reflects VPN_DISCONNECTED when enabled, or DISCONNECTED.
                // If VPN is running and keys exist, mark connection state as READY instead of hanging in CONNECTING.
                if (!AdBlockVpnService.isRunning) {
                    _uiState.update { it.copy(connectionState = if (it.isEnabled) "VPN_DISCONNECTED" else "DISCONNECTED") }
                } else if (_uiState.value.connectionState == "CONNECTING" && hasKeys) {
                    _uiState.update { it.copy(connectionState = "READY") }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error polling status")
        }
    }

    private fun parseAndApplyStatus(json: String) {
        try {
            tailscalePeerNotifier.checkAndNotifyJson(json)
            val currentPinned = _uiState.value.pinnedPeerIds
            val currentPeersMap = _uiState.value.peers.associateBy { it.id }
            val parsed = TailscaleStatusParser.parse(json, currentPinned, currentPeersMap)

            val isNowConnected = parsed.state == "CONNECTED"
            _uiState.update {
                val updatedSelected = it.selectedPeerForDetail?.let { sel ->
                    parsed.peers.find { p -> p.id == sel.id } ?: sel
                }
                it.copy(
                    connectionState = parsed.state,
                    isLoggedIn = it.isLoggedIn || isNowConnected,
                    selfIp = parsed.selfIp ?: it.selfIp,
                    magicDnsName = parsed.magicDnsName.ifEmpty { it.magicDnsName },
                    peers = parsed.peers,
                    exitNodes = parsed.exitNodes,
                    selectedPeerForDetail = updatedSelected
                )
            }

            if (isNowConnected && authEngine != null) {
                viewModelScope.launch {
                    val engine = authEngine
                    authEngine = null
                    val activeState = engine?.tailscaleState?.takeIf { it.isNotEmpty() && it != "{}" }
                    engine?.stopTailscale()
                    applyActiveTailscaleSession(activeState)
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun applyActiveTailscaleSession(activeState: String?) {
        if (activeState != null) {
            appPrefs.tailscale.setStateJson(activeState)
        }
        appPrefs.tailscale.setEnabled(true)
        _uiState.update { it.copy(isEnabled = true, isLoggedIn = true, authUrl = "") }
        _uiEffect.emit(TailscaleUiEffect.AuthSuccess)
        launchSync(activeState)
    }

    private suspend fun syncActiveSession(stateJson: String? = null) {
        if (serviceClient.isConnected) {
            val stateToUse = stateJson ?: appPrefs.tailscale.getStateJsonSnapshot()
            val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
            val authKey = appPrefs.tailscale.getAuthKeySnapshot()
            val exitNodeId = appPrefs.tailscale.getExitNodeIdSnapshot()
            val updatedState = serviceClient.syncSession(stateToUse, controlUrl, authKey, exitNodeId)
            if (!updatedState.isNullOrEmpty() && updatedState != "{}") {
                appPrefs.tailscale.setStateJson(updatedState)
            }
        } else if (!AdBlockVpnService.isRunning) {
            ServiceController.requestStart(getApplication<Application>().applicationContext)
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
        syncJob = null
        pollJob?.cancel()
        authEngine?.stopTailscale()
        authEngine = null
        serviceClient.unbind()
    }
}
