package app.pwhs.blockads.ui.proxyvpn.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ProxyProtocol
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ProxyEditViewModel(
    application: Application,
    private val appPrefs: AppPreferences
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProxyEditUiState())
    val uiState: StateFlow<ProxyEditUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ProxyEditEffect>()
    val effects: SharedFlow<ProxyEditEffect> = _effects.asSharedFlow()

    fun onIntent(intent: ProxyEditIntent) {
        when (intent) {
            is ProxyEditIntent.Load -> loadProfile(intent.profileId)
            is ProxyEditIntent.SetName -> _uiState.update { it.copy(name = intent.name, errors = it.errors - KEY_NAME) }
            is ProxyEditIntent.SetProtocol -> onProtocolChanged(intent.protocol)
            is ProxyEditIntent.SetServer -> _uiState.update { it.copy(server = intent.server, errors = it.errors - KEY_SERVER) }
            is ProxyEditIntent.SetPort -> _uiState.update { it.copy(port = intent.port, errors = it.errors - KEY_PORT) }
            is ProxyEditIntent.SetMethod -> _uiState.update { it.copy(method = intent.method) }
            is ProxyEditIntent.SetPassword -> _uiState.update { it.copy(password = intent.password, errors = it.errors - KEY_PASSWORD) }
            is ProxyEditIntent.SetUuid -> _uiState.update { it.copy(uuid = intent.uuid, errors = it.errors - KEY_UUID) }
            is ProxyEditIntent.GenerateUuid -> _uiState.update { it.copy(uuid = UUID.randomUUID().toString(), errors = it.errors - KEY_UUID) }
            is ProxyEditIntent.SetAlterId -> _uiState.update { it.copy(alterId = intent.alterId) }
            is ProxyEditIntent.SetSecurity -> _uiState.update { it.copy(security = intent.security) }
            is ProxyEditIntent.SetFlow -> _uiState.update { it.copy(flow = intent.flow) }
            is ProxyEditIntent.SetNetwork -> _uiState.update { it.copy(network = intent.network) }
            is ProxyEditIntent.SetWsPath -> _uiState.update { it.copy(wsPath = intent.path) }
            is ProxyEditIntent.SetWsHost -> _uiState.update { it.copy(wsHost = intent.host) }
            is ProxyEditIntent.SetSni -> _uiState.update { it.copy(sni = intent.sni) }
            is ProxyEditIntent.SetAllowInsecure -> _uiState.update { it.copy(allowInsecure = intent.allow) }
            is ProxyEditIntent.SetFingerprint -> _uiState.update { it.copy(fingerprint = intent.fp) }
            is ProxyEditIntent.SetPublicKey -> _uiState.update { it.copy(publicKey = intent.key) }
            is ProxyEditIntent.SetShortId -> _uiState.update { it.copy(shortId = intent.id) }
            is ProxyEditIntent.SetSpiderX -> _uiState.update { it.copy(spiderX = intent.spx) }
            is ProxyEditIntent.SetObfs -> _uiState.update { it.copy(obfs = intent.obfs) }
            is ProxyEditIntent.SetObfsPassword -> _uiState.update { it.copy(obfsPassword = intent.pass) }
            is ProxyEditIntent.SetCongestionControl -> _uiState.update { it.copy(congestionControl = intent.cc) }
            is ProxyEditIntent.SetUdpRelayMode -> _uiState.update { it.copy(udpRelayMode = intent.mode) }
            is ProxyEditIntent.SetAlpn -> _uiState.update { it.copy(alpn = intent.alpn) }
            is ProxyEditIntent.SetMasqueUser -> _uiState.update { it.copy(masqueUser = intent.user) }
            is ProxyEditIntent.SetMasquePass -> _uiState.update { it.copy(masquePass = intent.pass) }
            is ProxyEditIntent.SetMasquePath -> _uiState.update { it.copy(masquePath = intent.path) }
            is ProxyEditIntent.SetUsername -> _uiState.update { it.copy(username = intent.username, errors = it.errors - KEY_USERNAME) }
            is ProxyEditIntent.SetPrivateKey -> _uiState.update { it.copy(privateKey = intent.privateKey, errors = it.errors - KEY_PASSWORD) }
            is ProxyEditIntent.SetPassphrase -> _uiState.update { it.copy(passphrase = intent.passphrase) }
            is ProxyEditIntent.ToggleRawJsonMode -> toggleRawJsonMode(intent.isRaw)
            is ProxyEditIntent.SetRawJson -> _uiState.update { it.copy(rawJson = intent.json, errors = it.errors - KEY_JSON) }
            is ProxyEditIntent.Save -> save()
        }
    }

    private fun loadProfile(profileId: String?) {
        if (profileId.isNullOrEmpty() || profileId == "new") {
            _uiState.update {
                ProxyEditUiState(
                    isNew = true,
                    profileId = UUID.randomUUID().toString(),
                    uuid = UUID.randomUUID().toString()
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val profile = appPrefs.getProxyProfile(profileId)
            if (profile != null) {
                _uiState.value = ProxyEditUiState(
                    isNew = false,
                    profileId = profile.id,
                    name = profile.name,
                    protocol = profile.protocol,
                    server = profile.server,
                    port = profile.port.toString(),
                    method = profile.method.ifEmpty { "aes-256-gcm" },
                    password = profile.password,
                    uuid = profile.uuid,
                    alterId = profile.alterId.toString(),
                    security = profile.security.ifEmpty { "tls" },
                    flow = profile.flow,
                    network = profile.network.ifEmpty { "tcp" },
                    wsPath = profile.wsPath.ifEmpty { "/" },
                    wsHost = profile.wsHost,
                    sni = profile.sni,
                    allowInsecure = profile.allowInsecure,
                    fingerprint = profile.fingerprint.ifEmpty { "chrome" },
                    publicKey = profile.publicKey,
                    shortId = profile.shortId,
                    spiderX = profile.spiderX,
                    obfs = profile.obfs,
                    obfsPassword = profile.obfsPassword,
                    congestionControl = profile.congestionControl.ifEmpty { "bbr" },
                    udpRelayMode = profile.udpRelayMode.ifEmpty { "native" },
                    alpn = profile.alpn.ifEmpty { "h3" },
                    masqueUser = profile.masqueUser,
                    masquePass = profile.masquePass,
                    masquePath = profile.masquePath.ifEmpty { "/.well-known/masque/ip/*/*/" },
                    username = profile.username,
                    privateKey = profile.privateKey,
                    passphrase = profile.passphrase,
                    rawJson = profile.toEngineJson(),
                    shareLink = profile.toShareLink(),
                    isLoading = false
                )
            } else {
                _uiState.update { it.copy(isLoading = false) }
                _effects.emit(ProxyEditEffect.ShowMessage("Profile not found"))
            }
        }
    }

    private fun onProtocolChanged(protocol: ProxyProtocol) {
        _uiState.update { current ->
            val defaultPort = when (protocol) {
                ProxyProtocol.SHADOWSOCKS -> "8388"
                ProxyProtocol.SOCKS5, ProxyProtocol.SOCKS4 -> "1080"
                ProxyProtocol.HTTP -> "8080"
                ProxyProtocol.SSH -> "22"
                else -> "443"
            }
            val defaultSecurity = when (protocol) {
                ProxyProtocol.VLESS -> "reality"
                ProxyProtocol.VMESS -> "auto"
                ProxyProtocol.SOCKS5, ProxyProtocol.SOCKS4, ProxyProtocol.HTTP, ProxyProtocol.SSH -> "none"
                else -> "tls"
            }
            val defaultUuid = if (current.uuid.isEmpty() && (protocol == ProxyProtocol.VLESS || protocol == ProxyProtocol.VMESS || protocol == ProxyProtocol.TUIC)) {
                UUID.randomUUID().toString()
            } else current.uuid

            current.copy(
                protocol = protocol,
                port = defaultPort,
                security = defaultSecurity,
                uuid = defaultUuid,
                errors = emptyMap()
            )
        }
    }

    private fun toggleRawJsonMode(isRaw: Boolean) {
        if (isRaw) {
            val currentProfile = buildProfileFromState(_uiState.value)
            _uiState.update { it.copy(isRawJsonMode = true, rawJson = currentProfile.toEngineJson()) }
        } else {
            // Attempt to sync from raw JSON back to fields if valid
            try {
                val parsed = ProxyProfile.fromJson(_uiState.value.rawJson)
                _uiState.update { state ->
                    state.copy(
                        isRawJsonMode = false,
                        name = parsed.name.ifEmpty { state.name },
                        server = parsed.server,
                        port = parsed.port.toString(),
                        method = parsed.method,
                        password = parsed.password,
                        uuid = parsed.uuid,
                        alterId = parsed.alterId.toString(),
                        security = parsed.security,
                        flow = parsed.flow,
                        network = parsed.network,
                        wsPath = parsed.wsPath,
                        wsHost = parsed.wsHost,
                        sni = parsed.sni,
                        allowInsecure = parsed.allowInsecure,
                        publicKey = parsed.publicKey,
                        shortId = parsed.shortId,
                        spiderX = parsed.spiderX,
                        fingerprint = parsed.fingerprint,
                        obfs = parsed.obfs,
                        obfsPassword = parsed.obfsPassword,
                        congestionControl = parsed.congestionControl,
                        udpRelayMode = parsed.udpRelayMode,
                        alpn = parsed.alpn,
                        username = parsed.username,
                        privateKey = parsed.privateKey,
                        passphrase = parsed.passphrase
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isRawJsonMode = false) }
            }
        }
    }

    private fun buildProfileFromState(state: ProxyEditUiState): ProxyProfile {
        val portInt = state.port.trim().toIntOrNull() ?: 443
        return ProxyProfile(
            id = state.profileId.ifEmpty { UUID.randomUUID().toString() },
            name = state.name.trim(),
            protocol = state.protocol,
            server = state.server.trim(),
            port = portInt,
            method = state.method.trim(),
            password = state.password.trim(),
            uuid = state.uuid.trim(),
            alterId = state.alterId.trim().toIntOrNull() ?: 0,
            security = state.security.trim(),
            flow = state.flow.trim(),
            network = state.network.trim(),
            wsPath = state.wsPath.trim(),
            wsHost = state.wsHost.trim(),
            sni = state.sni.trim(),
            allowInsecure = state.allowInsecure,
            fingerprint = state.fingerprint.trim(),
            publicKey = state.publicKey.trim(),
            shortId = state.shortId.trim(),
            spiderX = state.spiderX.trim(),
            obfs = state.obfs.trim(),
            obfsPassword = state.obfsPassword.trim(),
            congestionControl = state.congestionControl.trim(),
            udpRelayMode = state.udpRelayMode.trim(),
            alpn = state.alpn.trim(),
            masqueUser = state.masqueUser.trim(),
            masquePass = state.masquePass.trim(),
            masquePath = state.masquePath.trim(),
            username = state.username.trim(),
            privateKey = state.privateKey.trim(),
            passphrase = state.passphrase.trim()
        )
    }

    private fun validate(state: ProxyEditUiState): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        val context = getApplication<Application>()

        if (state.isRawJsonMode) {
            try {
                ProxyProfile.fromJson(state.rawJson)
            } catch (_: Exception) {
                errors[KEY_JSON] = context.getString(R.string.proxy_err_json_invalid)
            }
            return errors
        }

        if (state.server.isBlank()) {
            errors[KEY_SERVER] = context.getString(R.string.proxy_err_server_required)
        }
        val port = state.port.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            errors[KEY_PORT] = context.getString(R.string.proxy_err_port_invalid)
        }

        when (state.protocol) {
            ProxyProtocol.SHADOWSOCKS -> {
                if (state.password.isBlank()) errors[KEY_PASSWORD] = context.getString(R.string.proxy_err_password_required)
            }
            ProxyProtocol.TROJAN -> {
                if (state.password.isBlank()) errors[KEY_PASSWORD] = context.getString(R.string.proxy_err_password_required)
            }
            ProxyProtocol.VLESS, ProxyProtocol.VMESS -> {
                if (state.uuid.isBlank()) errors[KEY_UUID] = context.getString(R.string.proxy_err_uuid_required)
            }
            ProxyProtocol.HYSTERIA2 -> {
                if (state.password.isBlank()) errors[KEY_PASSWORD] = context.getString(R.string.proxy_err_password_required)
            }
            ProxyProtocol.TUIC -> {
                if (state.uuid.isBlank()) errors[KEY_UUID] = context.getString(R.string.proxy_err_uuid_required)
            }
            ProxyProtocol.MASQUE -> {
                if (state.masqueUser.isBlank() && state.masquePass.isBlank()) {
                    errors[KEY_PASSWORD] = context.getString(R.string.proxy_err_password_required)
                }
            }
            ProxyProtocol.SOCKS5, ProxyProtocol.SOCKS4, ProxyProtocol.HTTP, ProxyProtocol.HTTPS -> {
                // Username and password are optional for SOCKS/HTTP(S) proxies.
            }
            ProxyProtocol.SSH -> {
                if (state.username.isBlank()) {
                    errors[KEY_USERNAME] = "Username is required"
                }
                if (state.password.isBlank() && state.privateKey.isBlank()) {
                    errors[KEY_PASSWORD] = context.getString(R.string.proxy_err_password_required)
                }
            }
        }

        return errors
    }

    private fun save() {
        val state = _uiState.value
        val errors = validate(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val profileToSave = if (state.isRawJsonMode) {
                    val parsed = ProxyProfile.fromJson(state.rawJson)
                    parsed.copy(id = state.profileId.ifEmpty { UUID.randomUUID().toString() })
                } else {
                    buildProfileFromState(state)
                }

                if (state.isNew) {
                    appPrefs.addProxyProfile(profileToSave)
                } else {
                    appPrefs.updateProxyProfile(profileToSave)
                }

                _effects.emit(ProxyEditEffect.Saved(profileToSave.displayName))
            } catch (e: Exception) {
                _effects.emit(ProxyEditEffect.ShowMessage(e.message ?: "Failed to save profile"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    companion object {
        const val KEY_NAME = "name"
        const val KEY_SERVER = "server"
        const val KEY_PORT = "port"
        const val KEY_PASSWORD = "password"
        const val KEY_USERNAME = "username"
        const val KEY_UUID = "uuid"
        const val KEY_JSON = "json"
    }
}
