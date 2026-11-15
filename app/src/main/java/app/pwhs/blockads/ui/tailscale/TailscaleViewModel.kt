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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

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
    private var authJob: Job? = null
    private var authEngine: tunnel.Engine? = null
    private val tailscalePeerNotifier by lazy { TailscalePeerNotifier(getApplication()) }
    private var syncJob: Job? = null
    private val transitionMutex = Mutex()
    private val activeTeardowns = AtomicInteger(0)
    private val authGeneration = AtomicInteger(0)
    @Volatile private var isCleared = false

    private fun launchSync(stateJson: String? = null) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            syncActiveSession(stateJson)
            if (isActive) pollStatusOnce()
        }
    }

    init {
        serviceClient.onConnected = { if (_uiState.value.isEnabled && _uiState.value.isLoggedIn) launchSync() }
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
                    isEnabled = enabled, isLoggedIn = hasKeys, isVpnRunning = isVpnRunning,
                    controlUrl = controlUrl, authKey = authKey, activeExitNodeId = exitNodeId,
                    advertiseExitNode = advertiseExit, adblockEnabled = adblockEnabled,
                    pinnedPeerIds = pinned, taildropDirUri = tdUri, taildropDirName = tdName,
                    connectionState = if (!enabled) "DISCONNECTED" else if (!isVpnRunning) "VPN_DISCONNECTED" else "CONNECTING"
                )
            }
            if (isVpnRunning) serviceClient.bind()
            if (enabled && hasKeys && isVpnRunning) {
                launchSync(stateJson)
                startStatusPolling()
            } else if (hasKeys && isVpnRunning) startStatusPolling()

            viewModelScope.launch {
                AdBlockVpnService.state.collect { vpnState ->
                    val running = vpnState == VpnState.RUNNING
                    if (running) serviceClient.bind() else {
                        serviceClient.unbind()
                        if (authEngine == null) pollJob?.cancel()
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
                        state.copy(
                            pinnedPeerIds = pinnedIds,
                            peers = TailscaleStatusParser.updatePinnedPeers(state.peers, pinnedIds),
                            selectedPeerForDetail = state.selectedPeerForDetail?.copy(isPinned = pinnedIds.contains(state.selectedPeerForDetail.id))
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
        if (!enabled) {
            activeTeardowns.incrementAndGet()
            authGeneration.incrementAndGet()
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (enabled) {
                    transitionMutex.withLock {
                        appPrefs.tailscale.setEnabled(true)
                        val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
                        val hasKeys = stateJson.contains("node_key") && stateJson.contains("machine_key")
                        _uiState.update { it.copy(isEnabled = true, connectionState = "CONNECTING") }
                        if (!hasKeys) {
                            signInWithBrowser()
                        } else {
                            launchSync(stateJson)
                            startStatusPolling()
                        }
                    }
                } else {
                    withContext(NonCancellable) {
                        transitionMutex.withLock {
                            appPrefs.tailscale.setEnabled(false)
                            _uiState.update { it.copy(isEnabled = false, connectionState = "DISCONNECTED") }
                            pollJob?.cancel()
                            val authToCancel = authJob.also { authJob = null }
                            val jobToCancel = syncJob.also { syncJob = null }
                            authToCancel?.cancelAndJoin()
                            authEngine?.stopTailscale()
                            authEngine = null
                            jobToCancel?.cancelAndJoin()
                            serviceClient.stopTailscale()
                            _uiState.update { it.copy(connectionState = "DISCONNECTED") }
                        }
                    }
                }
            } finally {
                if (!enabled && activeTeardowns.decrementAndGet() == 0 && isCleared) serviceClient.unbind()
            }
        }
    }

    private fun signInWithBrowser() = startAuthFlow(authKeyOverride = null)
    private fun signInWithAuthKey(key: String) = startAuthFlow(authKeyOverride = key)

    private fun startAuthFlow(authKeyOverride: String?) {
        authJob?.cancel()
        val gen = authGeneration.incrementAndGet()
        authJob = viewModelScope.launch(Dispatchers.IO) {
            if (!isActive || authGeneration.get() != gen) return@launch
            _uiState.update { it.copy(isLoading = true, errorMessage = "", authKey = authKeyOverride ?: it.authKey) }
            try {
                authEngine?.stopTailscale()
                authEngine = null
                if (authKeyOverride != null && isActive && authGeneration.get() == gen) {
                    appPrefs.tailscale.setAuthKey(authKeyOverride)
                }
                val stateJson = appPrefs.tailscale.getStateJsonSnapshot()
                val controlUrl = appPrefs.tailscale.getControlUrlSnapshot()
                val authKey = authKeyOverride ?: appPrefs.tailscale.getAuthKeySnapshot()
                if (!isActive || authGeneration.get() != gen) return@launch

                val engine = tunnel.Tunnel.newEngine()
                authEngine = engine
                val updatedState = engine.configureTailscale(stateJson, controlUrl, authKey)
                if (!isActive || authGeneration.get() != gen) {
                    engine.stopTailscale()
                    if (authEngine === engine) authEngine = null
                    return@launch
                }
                if (updatedState.isNotEmpty() && updatedState != "{}") appPrefs.tailscale.setStateJson(updatedState)

                val authUrl = engine.startTailscaleAuth("blockads-android")
                if (!isActive || authGeneration.get() != gen) {
                    engine.stopTailscale()
                    if (authEngine === engine) authEngine = null
                    return@launch
                }
                _uiState.update {
                    it.copy(isLoading = false, authUrl = authUrl, connectionState = if (authUrl.isNotEmpty()) "NEEDS_AUTH" else "CONNECTED")
                }

                if (authUrl.isNotEmpty() && authKeyOverride == null) {
                    _uiEffect.emit(TailscaleUiEffect.OpenBrowser(authUrl))
                } else if (authUrl.isEmpty()) {
                    val activeState = engine.tailscaleState.takeIf { it.isNotEmpty() && it != "{}" }
                    engine.stopTailscale()
                    authEngine = null
                    applyActiveTailscaleSession(activeState, gen)
                }
                if (isActive && authGeneration.get() == gen) startStatusPolling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (authGeneration.get() == gen) {
                    Timber.e(e, "Tailscale sign-in failed")
                    val fallback = if (authKeyOverride == null) "Sign-in error" else "Auth error"
                    _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: fallback) }
                }
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

    private fun toggleAdvertiseExitNode(enabled: Boolean) = viewModelScope.launch {
        appPrefs.tailscale.setAdvertiseExitNode(enabled)
        _uiState.update { it.copy(advertiseExitNode = enabled) }
        if (!serviceClient.setAdvertiseExitNode(enabled)) authEngine?.setTailscaleAdvertiseExitNode(enabled)
    }

    private fun toggleAdBlock(enabled: Boolean) = viewModelScope.launch {
        appPrefs.tailscale.setAdblockEnabled(enabled)
        _uiState.update { it.copy(adblockEnabled = enabled) }
        if (!serviceClient.setAdBlockEnabled(enabled)) authEngine?.setTailscaleAdBlockEnabled(enabled)
    }

    private fun logout() {
        activeTeardowns.incrementAndGet()
        authGeneration.incrementAndGet()
        pollJob?.cancel()
        val initialAuthJob = authJob.also { authJob = null }
        val initialSyncJob = syncJob.also { syncJob = null }
        _uiState.update { state ->
            TailscaleUiState(
                isEnabled = false, isLoggedIn = false, isVpnRunning = AdBlockVpnService.isRunning,
                connectionState = "DISCONNECTED", controlUrl = state.controlUrl,
                taildropDirUri = state.taildropDirUri, taildropDirName = state.taildropDirName
            )
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    transitionMutex.withLock {
                        pollJob?.cancel()
                        val latestAuthJob = authJob.also { authJob = null }
                        initialAuthJob?.cancelAndJoin()
                        latestAuthJob?.cancelAndJoin()
                        authEngine?.stopTailscale()
                        authEngine = null
                        val latestSyncJob = syncJob.also { syncJob = null }
                        initialSyncJob?.cancelAndJoin()
                        latestSyncJob?.cancelAndJoin()
                        serviceClient.stopTailscale()
                        appPrefs.tailscale.setEnabled(false)
                        appPrefs.tailscale.setStateJson("")
                        appPrefs.tailscale.setAuthKey("")
                        appPrefs.tailscale.setExitNodeId(0L)
                        appPrefs.tailscale.setAdvertiseExitNode(false)
                        _uiState.update { it.copy(isLoading = false, isEnabled = false, isLoggedIn = false, authUrl = "", connectionState = "DISCONNECTED") }
                    }
                }
            } finally {
                if (activeTeardowns.decrementAndGet() == 0 && isCleared) serviceClient.unbind()
            }
        }
    }

    private fun togglePinPeer(peerId: Long) = viewModelScope.launch { appPrefs.tailscale.togglePinPeer(peerId) }

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

    private fun sendFileToPeer(peer: TailscalePeer, uri: android.net.Uri) {
        if (!serviceClient.isConnected && !AdBlockVpnService.isRunning) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            var fileName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file"
            _uiState.update { it.copy(isSendingFile = true, sendingFileName = fileName) }
            try {
                fileName = serviceClient.resolveFileName(uri)
                _uiState.update { it.copy(sendingFileName = fileName) }
                serviceClient.sendFileUri(peer.id.toString(), fileName, uri)
                    .onSuccess {
                        _uiEffect.emit(TailscaleUiEffect.ShowSnackbar(app.getString(R.string.tailscale_send_file_success, fileName, peer.name)))
                    }
                    .onFailure { err ->
                        _uiEffect.emit(TailscaleUiEffect.ShowSnackbar(app.getString(R.string.tailscale_send_file_failed, fileName, err.message ?: "Unknown error")))
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to send Taildrop file")
                _uiEffect.emit(TailscaleUiEffect.ShowSnackbar(app.getString(R.string.tailscale_send_file_failed, fileName, e.message ?: "Unknown error")))
            } finally {
                _uiState.update { it.copy(isSendingFile = false, sendingFileName = null) }
            }
        }
    }

    private fun pingPeer(peerId: Long, target: String) = viewModelScope.launch {
        _uiState.update { state ->
            state.copy(
                peers = state.peers.map { if (it.id == peerId) it.copy(isPingLoading = true, pingError = null) else it },
                selectedPeerForDetail = state.selectedPeerForDetail?.takeIf { it.id == peerId }?.copy(isPingLoading = true, pingError = null) ?: state.selectedPeerForDetail
            )
        }
        val (rtt, errorStr) = TailscaleStatusParser.parsePingResponse(serviceClient.pingPeer(target), AdBlockVpnService.isRunning)
        _uiState.update { state ->
            state.copy(
                peers = state.peers.map { if (it.id == peerId) it.copy(isPingLoading = false, lastPingMs = rtt, pingError = errorStr) else it },
                selectedPeerForDetail = state.selectedPeerForDetail?.takeIf { it.id == peerId }?.copy(isPingLoading = false, lastPingMs = rtt, pingError = errorStr) ?: state.selectedPeerForDetail
            )
        }
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                pollStatusOnce()
                delay(if (authEngine != null) 1000L else 3000L)
            }
        }
    }

    private suspend fun pollStatusOnce() = withContext(Dispatchers.IO) {
        try {
            val polledGen = authGeneration.get()
            val polledEngine = authEngine
            val engineStatus = polledEngine?.tailscaleStatus ?: ""
            val fromAuthEngine = engineStatus.isNotEmpty() && engineStatus != "{}"
            val statusJson = if (fromAuthEngine) engineStatus else serviceClient.getStatus()
            if (statusJson.isNotEmpty() && statusJson != "{}") {
                parseAndApplyStatus(statusJson, if (fromAuthEngine) polledEngine else null, polledGen)
            } else {
                val hasKeys = appPrefs.tailscale.getStateJsonSnapshot().let { it.contains("node_key") && it.contains("machine_key") }
                if (hasKeys && !_uiState.value.isLoggedIn) _uiState.update { it.copy(isLoggedIn = true) }
                if (!AdBlockVpnService.isRunning) {
                    _uiState.update { it.copy(connectionState = if (it.isEnabled) "VPN_DISCONNECTED" else "DISCONNECTED") }
                } else if (_uiState.value.connectionState == "CONNECTING" && hasKeys) {
                    _uiState.update { it.copy(connectionState = "READY") }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error polling status")
        }
    }

    private fun parseAndApplyStatus(json: String, polledEngine: tunnel.Engine?, polledGen: Int) {
        try {
            tailscalePeerNotifier.checkAndNotifyJson(json)
            val parsed = TailscaleStatusParser.parse(json, _uiState.value.pinnedPeerIds, _uiState.value.peers.associateBy { it.id })
            val isNowConnected = parsed.state == "CONNECTED"
            val staleExitCleared = isNowConnected && parsed.peers.isNotEmpty() &&
                _uiState.value.activeExitNodeId != 0L && parsed.exitNodes.none { it.id == _uiState.value.activeExitNodeId }

            _uiState.update {
                it.copy(
                    connectionState = parsed.state, isLoggedIn = it.isLoggedIn || isNowConnected,
                    selfIp = parsed.selfIp ?: it.selfIp, magicDnsName = parsed.magicDnsName.ifEmpty { it.magicDnsName },
                    peers = parsed.peers, exitNodes = parsed.exitNodes,
                    activeExitNodeId = if (staleExitCleared) 0L else it.activeExitNodeId,
                    selectedPeerForDetail = it.selectedPeerForDetail?.let { sel -> parsed.peers.find { p -> p.id == sel.id } ?: sel }
                )
            }
            if (staleExitCleared) {
                viewModelScope.launch {
                    appPrefs.tailscale.setExitNodeId(0L)
                    serviceClient.setExitNode(0L)
                }
            }
            if (isNowConnected && polledEngine != null && authEngine === polledEngine && authGeneration.get() == polledGen) {
                authJob = viewModelScope.launch {
                    if (authEngine !== polledEngine || authGeneration.get() != polledGen) return@launch
                    authEngine = null
                    val activeState = polledEngine.tailscaleState.takeIf { it.isNotEmpty() && it != "{}" }
                    polledEngine.stopTailscale()
                    applyActiveTailscaleSession(activeState, polledGen)
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun applyActiveTailscaleSession(activeState: String?, expectedGen: Int = authGeneration.get()) {
        if (authGeneration.get() != expectedGen) return
        if (activeState != null) appPrefs.tailscale.setStateJson(activeState)
        if (authGeneration.get() != expectedGen) return
        appPrefs.tailscale.setEnabled(true)
        if (authGeneration.get() != expectedGen) return
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
            val advertiseExit = appPrefs.tailscale.getAdvertiseExitNodeSnapshot()
            val adblockEnabled = appPrefs.tailscale.getAdblockEnabledSnapshot()
            val updatedState = serviceClient.syncSession(
                stateJson = stateToUse, controlUrl = controlUrl, authKey = authKey,
                exitNodeId = exitNodeId, advertiseExitNode = advertiseExit, adblockEnabled = adblockEnabled
            )
            if (!updatedState.isNullOrEmpty() && updatedState != "{}") appPrefs.tailscale.setStateJson(updatedState)
            val latestState = _uiState.value
            if (latestState.activeExitNodeId != exitNodeId) serviceClient.setExitNode(latestState.activeExitNodeId)
            if (latestState.advertiseExitNode != advertiseExit) serviceClient.setAdvertiseExitNode(latestState.advertiseExitNode)
            if (latestState.adblockEnabled != adblockEnabled) serviceClient.setAdBlockEnabled(latestState.adblockEnabled)
        } else if (!AdBlockVpnService.isRunning) {
            ServiceController.requestStart(getApplication<Application>().applicationContext)
        }
    }

    override fun onCleared() {
        super.onCleared()
        isCleared = true
        authGeneration.incrementAndGet()
        authJob?.cancel().also { authJob = null }
        syncJob?.cancel().also { syncJob = null }
        pollJob?.cancel()
        authEngine?.stopTailscale().also { authEngine = null }
        if (activeTeardowns.get() == 0) serviceClient.unbind()
    }
}
