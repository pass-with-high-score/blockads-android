package app.pwhs.blockadstv.ui.screens.proxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.entities.ProxyProfile
import app.pwhs.blockadstv.service.IptablesManager
import app.pwhs.blockadstv.utils.NodePingResult
import app.pwhs.blockadstv.utils.ProxyLinkParser
import app.pwhs.blockadstv.utils.ProxyPingTester
import app.pwhs.blockadstv.utils.SubscriptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyViewModel(
    private val tvPrefs: TvPreferences,
    private val subscriptionManager: SubscriptionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<ProxyUiEffect>()
    val effect: SharedFlow<ProxyUiEffect> = _effect.asSharedFlow()

    init {
        viewModelScope.launch {
            tvPrefs.routingMode.collect { mode ->
                _uiState.update { it.copy(routingMode = mode) }
            }
        }
        viewModelScope.launch {
            tvPrefs.proxyProfiles.collect { profiles ->
                _uiState.update { it.copy(profiles = profiles) }
            }
        }
        viewModelScope.launch {
            tvPrefs.activeProxyProfileId.collect { id ->
                _uiState.update { it.copy(activeProfileId = id) }
            }
        }
        viewModelScope.launch {
            tvPrefs.proxySubscriptions.collect { subs ->
                _uiState.update { it.copy(subscriptions = subs) }
            }
        }
        checkRootAvailability()
    }

    private fun checkRootAvailability() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = IptablesManager.isRootAvailable()
            _uiState.update { it.copy(isRootAvailable = root) }
        }
    }

    fun onIntent(intent: ProxyUiIntent) {
        when (intent) {
            is ProxyUiIntent.SelectRoutingMode -> handleSelectRoutingMode(intent.mode)
            is ProxyUiIntent.SelectActiveProfile -> handleSelectProfile(intent.id)
            is ProxyUiIntent.DeleteProfile -> handleDeleteProfile(intent.id)
            is ProxyUiIntent.PingProfile -> handlePingProfile(intent.profile)
            is ProxyUiIntent.PingAll -> handlePingAll()
            is ProxyUiIntent.ImportLink -> handleImportLink(intent.link)
            is ProxyUiIntent.ImportSubscription -> handleImportSubscription(intent.url, intent.name)
            is ProxyUiIntent.DeleteSubscription -> handleDeleteSubscription(intent.id)
            is ProxyUiIntent.ShowAddNodeDialog -> _uiState.update { it.copy(showAddNodeDialog = intent.show, dialogError = null) }
            is ProxyUiIntent.ShowImportDialog -> _uiState.update { it.copy(showImportDialog = intent.show, dialogError = null) }
            is ProxyUiIntent.DismissRootWarning -> _uiState.update { it.copy(showRootWarningDialog = false) }
        }
    }

    private fun handleSelectRoutingMode(mode: String) {
        viewModelScope.launch {
            if (mode == TvPreferences.ROUTING_MODE_ROOT && !_uiState.value.isRootAvailable) {
                // Re-check root just in case
                val hasRoot = withContext(Dispatchers.IO) { IptablesManager.isRootAvailable() }
                if (!hasRoot) {
                    _uiState.update { it.copy(showRootWarningDialog = true) }
                    return@launch
                } else {
                    _uiState.update { it.copy(isRootAvailable = true) }
                }
            }
            tvPrefs.setRoutingMode(mode)
            _effect.emit(ProxyUiEffect.RequestRestart)
        }
    }

    private fun handleSelectProfile(id: String) {
        viewModelScope.launch {
            tvPrefs.setActiveProxyProfileId(id)
            if (_uiState.value.routingMode == TvPreferences.ROUTING_MODE_PROXY_VPN) {
                _effect.emit(ProxyUiEffect.RequestRestart)
            }
        }
    }

    private fun handleDeleteProfile(id: String) {
        viewModelScope.launch {
            tvPrefs.deleteProxyProfile(id)
        }
    }

    private fun handlePingProfile(profile: ProxyProfile) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(pingResults = it.pingResults + (profile.id to NodePingResult.Testing))
            }
            val res = ProxyPingTester.pingNode(profile)
            _uiState.update {
                it.copy(pingResults = it.pingResults + (profile.id to res))
            }
        }
    }

    private fun handlePingAll() {
        viewModelScope.launch {
            val profiles = _uiState.value.profiles
            ProxyPingTester.pingAll(profiles) { id, result ->
                _uiState.update {
                    it.copy(pingResults = it.pingResults + (id to result))
                }
            }
        }
    }

    private fun handleImportLink(link: String) {
        viewModelScope.launch {
            val res = ProxyLinkParser.parse(link)
            res.fold(
                onSuccess = { profile ->
                    tvPrefs.addProxyProfile(profile)
                    _uiState.update { it.copy(showAddNodeDialog = false, dialogError = null) }
                    _effect.emit(ProxyUiEffect.ShowToast("Added ${profile.displayName}"))
                },
                onFailure = { err ->
                    _uiState.update { it.copy(dialogError = err.message ?: "Invalid link") }
                }
            )
        }
    }

    private fun handleImportSubscription(url: String, name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingSubscription = true, dialogError = null) }
            val res = subscriptionManager.importOrUpdateSubscription(url, name)
            _uiState.update { it.copy(isUpdatingSubscription = false) }
            res.fold(
                onSuccess = { (sub, parsed) ->
                    _uiState.update { it.copy(showImportDialog = false) }
                    _effect.emit(ProxyUiEffect.ShowToast("Imported ${parsed.totalParsed} nodes"))
                    _effect.emit(ProxyUiEffect.RequestRestart)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(dialogError = err.message ?: "Failed to import subscription") }
                }
            )
        }
    }

    private fun handleDeleteSubscription(id: String) {
        viewModelScope.launch {
            subscriptionManager.deleteSubscription(id)
        }
    }
}
