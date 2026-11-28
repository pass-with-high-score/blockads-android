package app.pwhs.blockads.ui.tailscale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
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
    application: Application
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()

    private val _uiState = MutableStateFlow(TailscaleUiState())
    val uiState: StateFlow<TailscaleUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<TailscaleUiEffect>(extraBufferCapacity = 64)
    val uiEffect: SharedFlow<TailscaleUiEffect> = _uiEffect.asSharedFlow()

    private var pollJob: Job? = null
    @Volatile
    private var authEngine: tunnel.Engine? = null
    @Volatile
    private var isHandingOffSession: Boolean = false
    private val tailscalePeerNotifier by lazy { TailscalePeerNotifier(getApplication()) }

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            val enabled = appPrefs.tailscale.isEnabledSnapshot()
            val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
            val authKey = appPrefs.tailscale.getAuthKeySnapshot()
            val exitNodeId = appPrefs.tailscale.getExitNodeIdSnapshot()
            val advertiseExit = appPrefs.tailscale.getAdvertiseExitNodeSnapshot()
            val adblockEnabled = appPrefs.tailscale.getAdblockEnabledSnapshot()
            val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
            val hasKeys = stateJson.contains("node_key") && stateJson.contains("machine_key")

            val pinned = appPrefs.tailscale.getPinnedPeerIdsSnapshot()
            val tdUri = appPrefs.tailscale.getTaildropDirUriSnapshot()
            val tdName = appPrefs.tailscale.getTaildropDirNameSnapshot()
            val isVpnRunning = AdBlockVpnService.isRunning

            _uiState.update {
                it.copy(
                    isEnabled = enabled,
                    isLoggedIn = hasKeys,
                    isVpnRunning = isVpnRunning,
                    controlUrl = controlUrl,
                    authKey = authKey,
                    activeExitNodeId = exitNodeId,
                    advertiseExitNode = advertiseExit,
                    adblockEnabled = adblockEnabled,
                    pinnedPeerIds = pinned,
                    taildropDirUri = tdUri,
                    taildropDirName = tdName,
                    connectionState = when {
                        !enabled -> "DISCONNECTED"
                        !isVpnRunning -> "VPN_DISCONNECTED"
                        else -> "CONNECTING"
                    }
                )
            }
            if (enabled && hasKeys && isVpnRunning) {
                val adapter = AdBlockVpnService.activeTunnelAdapter
                if (adapter != null && !adapter.isTailscaleActive()) {
                    syncActiveSession(stateJson)
                }
                startStatusPolling()
            } else if (hasKeys && isVpnRunning) {
                startStatusPolling()
            }

            viewModelScope.launch {
                AdBlockVpnService.state.collect { vpnState ->
                    val running = vpnState == VpnState.RUNNING
                    val restarting = vpnState == VpnState.RESTARTING || vpnState == VpnState.STARTING
                    _uiState.update { state ->
                        val connState = when {
                            !state.isEnabled -> "DISCONNECTED"
                            restarting -> "CONNECTING"
                            !running -> "VPN_DISCONNECTED"
                            state.connectionState == "VPN_DISCONNECTED" || state.connectionState == "DISCONNECTED" -> "CONNECTING"
                            else -> state.connectionState
                        }
                        state.copy(isVpnRunning = running || restarting, connectionState = connState)
                    }
                    if (running && _uiState.value.isEnabled && _uiState.value.isLoggedIn) {
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
            is TailscaleUiIntent.SignInWithBrowser -> signInWithBrowser(intent.controlUrl)
            is TailscaleUiIntent.SignInWithAuthKey -> signInWithAuthKey(intent.key, intent.controlUrl)
            is TailscaleUiIntent.CancelAuth -> cancelAuth()
            is TailscaleUiIntent.SetControlUrl -> setControlUrl(intent.url)
            is TailscaleUiIntent.SelectExitNode -> selectExitNode(intent.nodeId)
            is TailscaleUiIntent.ToggleAdvertiseExitNode -> toggleAdvertiseExitNode(intent.enabled)
            is TailscaleUiIntent.ToggleAdBlock -> toggleAdBlock(intent.enabled)
            is TailscaleUiIntent.RefreshStatus -> viewModelScope.launch { pollStatusOnce() }
            is TailscaleUiIntent.Logout -> logout()
            is TailscaleUiIntent.SelectPeerForDetail -> _uiState.update { it.copy(selectedPeerForDetail = intent.peer) }
            is TailscaleUiIntent.TogglePinPeer -> togglePinPeer(intent.peerId)
            is TailscaleUiIntent.PingPeer -> pingPeer(intent.peerId, intent.target)
            is TailscaleUiIntent.SetTaildropDirectory -> setTaildropDirectory(intent.uri, intent.displayName)
            is TailscaleUiIntent.ResetTaildropDirectory -> resetTaildropDirectory()
            is TailscaleUiIntent.SendFileToPeer -> sendFileToPeer(intent.peer, intent.uri)
            is TailscaleUiIntent.CopyToClipboard -> copyToClipboard(intent.text, intent.label)
        }
    }

    private fun toggleTailscale(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.tailscale.setEnabled(enabled)
            val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
            val hasKeys = stateJson.contains("node_key") && stateJson.contains("machine_key")

            val isVpnRunning = AdBlockVpnService.activeTunnelAdapter != null
            _uiState.update {
                it.copy(
                    isEnabled = enabled,
                    connectionState = if (enabled && isVpnRunning) "CONNECTING" else "DISCONNECTED"
                )
            }
            if (enabled) {
                if (!hasKeys) {
                    signInWithBrowser()
                } else if (isVpnRunning) {
                    val adapter = AdBlockVpnService.activeTunnelAdapter
                    if (adapter != null && appPrefs.excludeLan.first()) {
                        AdBlockVpnService.requestRestart(getApplication<Application>().applicationContext)
                    } else {
                        syncActiveSession(stateJson)
                    }
                    startStatusPolling()
                }
            } else {
                pollJob?.cancel()
                val adapter = AdBlockVpnService.activeTunnelAdapter
                adapter?.stopTailscale()
                _uiState.update { it.copy(connectionState = "DISCONNECTED") }
                if (adapter != null && appPrefs.excludeLan.first()) {
                    AdBlockVpnService.requestRestart(getApplication<Application>().applicationContext)
                }
            }
        }
    }

    private fun cancelAuth() {
        pollJob?.cancel()
        pollJob = null
        val engine = authEngine
        authEngine = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { engine?.stopTailscale() }
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                authUrl = "",
                connectionState = "DISCONNECTED",
                errorMessage = "",
            )
        }
    }

    private fun normalizeControlUrl(raw: String?): Result<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.success(DEFAULT_CONTROL_URL)
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = runCatching { java.net.URI(withScheme) }.getOrNull()
        val host = parsed?.host.orEmpty()
        if (host.isBlank() || host.contains(" ")) {
            return Result.failure(IllegalArgumentException("Invalid control server URL: $trimmed"))
        }
        return Result.success(withScheme.trimEnd('/'))
    }

    private fun signInWithBrowser(controlUrlOverride: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedUrl = normalizeControlUrl(controlUrlOverride ?: appPrefs.tailscale.getControlUrlSnapshot())
                .getOrElse { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionState = "DISCONNECTED",
                            errorMessage = err.message ?: "Invalid control server URL",
                        )
                    }
                    return@launch
                }

            appPrefs.tailscale.setControlUrl(resolvedUrl)
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = "",
                    authUrl = "",
                    controlUrl = resolvedUrl,
                )
            }
            try {
                authEngine?.stopTailscale()
                authEngine = null

                val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
                val authKey = appPrefs.tailscale.getAuthKeySnapshot()

                val engine = tunnel.Tunnel.newEngine()
                authEngine = engine
                val updatedState = engine.configureTailscale(stateJson, resolvedUrl, authKey)
                if (updatedState.isNotEmpty() && updatedState != "{}") {
                    appPrefs.tailscale.setStateJson(updatedState)
                }

                val authUrl = engine.startTailscaleAuth("blockads-android")
                if (authUrl.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            authUrl = authUrl,
                            connectionState = "NEEDS_AUTH",
                            errorMessage = "",
                        )
                    }
                    _uiEffect.emit(TailscaleUiEffect.OpenBrowser(authUrl))
                    startStatusPolling()
                } else {
                    handleEmptyAuthUrl(engine)
                }
            } catch (e: Exception) {
                Timber.e(e, "Tailscale browser sign-in failed")
                authEngine?.stopTailscale()
                authEngine = null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authUrl = "",
                        connectionState = "DISCONNECTED",
                        errorMessage = e.message ?: "Sign-in error",
                    )
                }
            }
        }
    }

    private fun signInWithAuthKey(key: String, controlUrlOverride: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedKey = key.trim()
            val resolvedUrl = normalizeControlUrl(controlUrlOverride ?: appPrefs.tailscale.getControlUrlSnapshot())
                .getOrElse { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionState = "DISCONNECTED",
                            errorMessage = err.message ?: "Invalid control server URL",
                        )
                    }
                    return@launch
                }

            appPrefs.tailscale.setControlUrl(resolvedUrl)
            appPrefs.tailscale.setAuthKey(trimmedKey)
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = "",
                    authUrl = "",
                    authKey = trimmedKey,
                    controlUrl = resolvedUrl,
                )
            }
            try {
                authEngine?.stopTailscale()
                authEngine = null

                val stateJson = appPrefs.tailscale.getStateJsonSnapshot()

                val engine = tunnel.Tunnel.newEngine()
                authEngine = engine
                val updatedState = engine.configureTailscale(stateJson, resolvedUrl, trimmedKey)
                if (updatedState.isNotEmpty() && updatedState != "{}") {
                    appPrefs.tailscale.setStateJson(updatedState)
                }

                val authUrl = engine.startTailscaleAuth("blockads-android")
                if (authUrl.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            authUrl = authUrl,
                            connectionState = "NEEDS_AUTH",
                            errorMessage = "",
                        )
                    }
                    _uiEffect.emit(TailscaleUiEffect.OpenBrowser(authUrl))
                    startStatusPolling()
                } else {
                    handleEmptyAuthUrl(engine)
                }
            } catch (e: Exception) {
                Timber.e(e, "Tailscale auth-key sign-in failed")
                authEngine?.stopTailscale()
                authEngine = null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authUrl = "",
                        connectionState = "DISCONNECTED",
                        errorMessage = e.message ?: "Auth error",
                    )
                }
            }
        }
    }

    private suspend fun handleEmptyAuthUrl(engine: tunnel.Engine) {
        val statusJson = runCatching { engine.tailscaleStatus }.getOrDefault("")
        val parsed = runCatching {
            TailscaleStatusParser.parse(statusJson, emptySet(), emptyMap())
        }.getOrNull()

        val failed = parsed == null ||
            parsed.state == "ERROR" ||
            parsed.error.isNotBlank() ||
            (parsed.state != "CONNECTED" && parsed.selfIp.isNullOrEmpty())

        if (failed) {
            engine.stopTailscale()
            authEngine = null
            val errMsg = parsed?.error?.takeIf { it.isNotBlank() }
                ?: "Unable to connect to control server. Please check the URL and try again."
            _uiState.update {
                it.copy(
                    isLoading = false,
                    authUrl = "",
                    connectionState = "DISCONNECTED",
                    errorMessage = errMsg,
                )
            }
            return
        }

        val activeState = engine.tailscaleState.takeIf { it.isNotEmpty() && it != "{}" }
        engine.stopTailscale()
        authEngine = null
        _uiState.update { it.copy(isLoading = false, authUrl = "", errorMessage = "") }
        applyActiveTailscaleSession(activeState)
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

    private fun toggleAdvertiseExitNode(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.tailscale.setAdvertiseExitNode(enabled)
            _uiState.update { it.copy(advertiseExitNode = enabled) }
            val adapter = AdBlockVpnService.activeTunnelAdapter
            if (adapter != null) {
                adapter.setTailscaleAdvertiseExitNode(enabled)
            } else {
                authEngine?.setTailscaleAdvertiseExitNode(enabled)
            }
        }
    }

    private fun toggleAdBlock(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.tailscale.setAdblockEnabled(enabled)
            _uiState.update { it.copy(adblockEnabled = enabled) }
            val adapter = AdBlockVpnService.activeTunnelAdapter
            if (adapter != null) {
                adapter.setTailscaleAdBlockEnabled(enabled)
            } else {
                authEngine?.setTailscaleAdBlockEnabled(enabled)
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
            appPrefs.tailscale.setAdvertiseExitNode(false)

            _uiState.update {
                TailscaleUiState(
                    isEnabled = false,
                    isLoggedIn = false,
                    connectionState = "DISCONNECTED"
                )
            }
        }
    }

    private fun togglePinPeer(peerId: Long) {
        viewModelScope.launch {
            appPrefs.tailscale.togglePinPeer(peerId)
        }
    }

    private fun setTaildropDirectory(uri: String, name: String) {
        viewModelScope.launch {
            appPrefs.tailscale.setTaildropDirectory(uri, name)
            _uiState.update { it.copy(taildropDirUri = uri, taildropDirName = name) }
        }
    }

    private fun resetTaildropDirectory() {
        viewModelScope.launch {
            appPrefs.tailscale.resetTaildropDirectory()
            _uiState.update { it.copy(taildropDirUri = "", taildropDirName = "Downloads/Taildrop") }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        viewModelScope.launch {
            _uiEffect.emit(TailscaleUiEffect.CopyToClipboard(text, label))
        }
    }

    private fun sendFileToPeer(peer: TailscalePeer, uri: android.net.Uri) {
        val adapter = AdBlockVpnService.activeTunnelAdapter ?: return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = resolveFileName(app, uri)
            _uiState.update { it.copy(isSendingFile = true, sendingFileName = fileName) }
            val tempDir = java.io.File(app.cacheDir, "taildrop_out").apply { mkdirs() }
            val tempFile = java.io.File(tempDir, "send_${System.currentTimeMillis()}_$fileName")
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Cannot open selected file")

                val target = peer.id.toString()
                adapter.sendTailscaleFile(target, fileName, tempFile.absolutePath)
                    .onSuccess {
                        _uiEffect.emit(
                            TailscaleUiEffect.ShowSnackbar(
                                app.getString(R.string.tailscale_send_file_success, fileName, peer.name)
                            )
                        )
                    }
                    .onFailure { err ->
                        _uiEffect.emit(
                            TailscaleUiEffect.ShowSnackbar(
                                app.getString(
                                    R.string.tailscale_send_file_failed,
                                    fileName,
                                    err.message ?: "Unknown error"
                                )
                            )
                        )
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send Taildrop file")
                _uiEffect.emit(
                    TailscaleUiEffect.ShowSnackbar(
                        app.getString(
                            R.string.tailscale_send_file_failed,
                            fileName,
                            e.message ?: "Unknown error"
                        )
                    )
                )
            } finally {
                tempFile.delete()
                _uiState.update { it.copy(isSendingFile = false, sendingFileName = null) }
            }
        }
    }

    private fun resolveFileName(app: Application, uri: android.net.Uri): String {
        app.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file"
    }

    private fun pingPeer(peerId: Long, target: String) {
        viewModelScope.launch(Dispatchers.IO) {
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

            val adapter = AdBlockVpnService.activeTunnelAdapter
            val rawJson = adapter?.pingTailscalePeer(target) ?: ""
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
                errorStr = "Tunnel not active"
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
                val curState = _uiState.value.connectionState
                val isTransitioning = authEngine != null ||
                    isHandingOffSession ||
                    curState == "CONNECTING" ||
                    curState == "AUTHENTICATING"
                delay(if (isTransitioning) 1000L else 3000L)
            }
        }
    }

    private suspend fun pollStatusOnce() = withContext(Dispatchers.IO) {
        if (isHandingOffSession) return@withContext
        val vpnState = AdBlockVpnService.state.value
        if (authEngine == null && (vpnState == VpnState.RESTARTING || vpnState == VpnState.STARTING)) {
            return@withContext
        }
        try {
            // When actively authenticating via authEngine, poll authEngine first.
            // Otherwise, query active VPN tunnel adapter.
            val adapter = AdBlockVpnService.activeTunnelAdapter
            if (authEngine == null &&
                vpnState == VpnState.RUNNING &&
                _uiState.value.isEnabled &&
                _uiState.value.isLoggedIn &&
                adapter != null &&
                adapter.isEngineRunning() &&
                !adapter.isTailscaleActive()
            ) {
                Timber.d("Tailscale enabled while VPN engine running without active manager; syncing session")
                syncActiveSession()
            }

            val statusJson = if (authEngine != null) {
                val s = authEngine?.tailscaleStatus ?: ""
                if (s.isNotEmpty() && s != "{}") s else adapter?.getTailscaleStatus() ?: ""
            } else {
                adapter?.getTailscaleStatus() ?: ""
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

            if (parsed.state == "ERROR" && authEngine != null) {
                val engine = authEngine
                authEngine = null
                pollJob?.cancel()
                pollJob = null
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { engine?.stopTailscale() }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authUrl = "",
                        connectionState = "DISCONNECTED",
                        errorMessage = parsed.error.ifBlank { "Authentication failed" },
                    )
                }
                return
            }

            val isNowConnected = parsed.state == "CONNECTED"
            val adapter = AdBlockVpnService.activeTunnelAdapter
            val engineBooting = authEngine == null &&
                _uiState.value.isEnabled &&
                AdBlockVpnService.state.value == VpnState.RUNNING &&
                (adapter == null || !adapter.isEngineRunning())
            val effectiveState = if (parsed.state == "DISCONNECTED" && engineBooting) {
                "CONNECTING"
            } else {
                parsed.state
            }
            if (effectiveState != _uiState.value.connectionState) {
                Timber.d("Tailscale UI state: ${_uiState.value.connectionState} -> $effectiveState (raw=${parsed.state}, selfIp=${parsed.selfIp}, err=${parsed.error})")
            }
            val staleExitCleared = isNowConnected &&
                parsed.peers.isNotEmpty() &&
                _uiState.value.activeExitNodeId != 0L &&
                parsed.exitNodes.none { it.id == _uiState.value.activeExitNodeId }

            _uiState.update {
                val updatedSelected = it.selectedPeerForDetail?.let { sel ->
                    parsed.peers.find { p -> p.id == sel.id } ?: sel
                }
                it.copy(
                    connectionState = effectiveState,
                    isLoggedIn = it.isLoggedIn || isNowConnected,
                    selfIp = parsed.selfIp ?: it.selfIp,
                    magicDnsName = parsed.magicDnsName.ifEmpty { it.magicDnsName },
                    peers = parsed.peers,
                    exitNodes = parsed.exitNodes,
                    activeExitNodeId = if (staleExitCleared) 0L else it.activeExitNodeId,
                    selectedPeerForDetail = updatedSelected
                )
            }

            if (staleExitCleared) {
                viewModelScope.launch {
                    appPrefs.tailscale.setExitNodeId(0L)
                    AdBlockVpnService.activeTunnelAdapter?.setTailscaleExitNode(0L)
                }
            }

            if (isNowConnected && authEngine != null && !isHandingOffSession) {
                isHandingOffSession = true
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val engine = authEngine
                        authEngine = null
                        val activeState = engine?.tailscaleState?.takeIf { it.isNotEmpty() && it != "{}" }
                        engine?.stopTailscale()
                        applyActiveTailscaleSession(activeState)
                    } finally {
                        isHandingOffSession = false
                    }
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
        val adapter = AdBlockVpnService.activeTunnelAdapter
        val vpnRunning = adapter != null || AdBlockVpnService.isRunning
        _uiState.update {
            it.copy(
                isEnabled = true,
                isLoggedIn = true,
                isVpnRunning = vpnRunning,
                authUrl = "",
                connectionState = if (vpnRunning) "CONNECTING" else "VPN_DISCONNECTED",
            )
        }
        _uiEffect.emit(TailscaleUiEffect.AuthSuccess)
        if (adapter != null && appPrefs.excludeLan.first()) {
            AdBlockVpnService.requestRestart(getApplication<Application>().applicationContext)
        } else if (adapter != null) {
            syncActiveSession(activeState)
        }
        isHandingOffSession = false
        if (vpnRunning) {
            startStatusPolling()
        }
    }

    private suspend fun syncActiveSession(stateJson: String? = null) {
        val adapter = AdBlockVpnService.activeTunnelAdapter ?: return
        val stateToUse = stateJson ?: appPrefs.tailscale.getStateJsonSnapshot()
        val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
        val authKey = appPrefs.tailscale.getAuthKeySnapshot()
        adapter.configureTailscale(stateToUse, controlUrl, authKey)
        val exitNodeId = appPrefs.tailscale.getExitNodeIdSnapshot()
        if (exitNodeId != 0L) {
            adapter.setTailscaleExitNode(exitNodeId)
        }
        adapter.startTailscaleSession("blockads-android")
    }

    override fun onCleared() {
        super.onCleared()
        authEngine?.stopTailscale()
        authEngine = null
    }

    companion object {
        const val DEFAULT_CONTROL_URL = "https://controlplane.tailscale.com"
    }
}
