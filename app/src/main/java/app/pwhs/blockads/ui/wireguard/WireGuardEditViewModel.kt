package app.pwhs.blockads.ui.wireguard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.utils.WireGuardValidators
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/**
 * Editable state for one peer. List-typed fields are kept as comma-joined
 * strings while the user types; we only split + trim on validate/save.
 */
data class PeerFormState(
    val rowId: String = UUID.randomUUID().toString(),
    val publicKey: String = "",
    val presharedKey: String = "",
    val endpoint: String = "",
    val allowedIPs: String = "",
    val persistentKeepalive: String = "",
)

/**
 * Editable state for the whole profile. Mirrors [WireGuardConfig] +
 * [WireGuardProfile.name] but with all fields as user-typed strings so
 * the form can accept partial / invalid input without losing data.
 */
data class WireGuardEditState(
    val profileId: String = "",
    val name: String = "",
    val privateKey: String = "",
    val addresses: String = "",
    val listenPort: String = "",
    val dns: String = "",
    val peers: List<PeerFormState> = listOf(PeerFormState()),
)

/**
 * Per-field error messages. Keys are stable identifiers used by the
 * screen to attach `supportingText`. Peer errors are keyed by
 * "peer.<rowId>.<field>".
 */
data class WireGuardEditErrors(val map: Map<String, String> = emptyMap()) {
    val isValid: Boolean get() = map.isEmpty()
    operator fun get(key: String): String? = map[key]
}

class WireGuardEditViewModel(
    application: Application,
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()

    private val _state = MutableStateFlow(WireGuardEditState())
    val state: StateFlow<WireGuardEditState> = _state.asStateFlow()

    private val _errors = MutableStateFlow(WireGuardEditErrors())
    val errors: StateFlow<WireGuardEditErrors> = _errors.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events = MutableSharedFlow<EditEvent>()
    val events: SharedFlow<EditEvent> = _events.asSharedFlow()

    sealed class EditEvent {
        data class Saved(val name: String) : EditEvent()
        data class Failed(val message: String) : EditEvent()
    }

    /** Load a profile into the form. Call once when the screen opens. */
    fun load(profileId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val profile = appPrefs.getWgProfilesSnapshot().firstOrNull { it.id == profileId }
            if (profile == null) {
                _events.emit(EditEvent.Failed("Profile not found"))
                _isLoading.value = false
                return@launch
            }
            _state.value = profile.toFormState()
            _isLoading.value = false
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setPrivateKey(value: String) = _state.update { it.copy(privateKey = value) }
    fun setAddresses(value: String) = _state.update { it.copy(addresses = value) }
    fun setListenPort(value: String) = _state.update { it.copy(listenPort = value) }
    fun setDns(value: String) = _state.update { it.copy(dns = value) }

    fun updatePeer(rowId: String, transform: (PeerFormState) -> PeerFormState) {
        _state.update { s ->
            s.copy(peers = s.peers.map { if (it.rowId == rowId) transform(it) else it })
        }
    }

    fun addPeer() = _state.update { it.copy(peers = it.peers + PeerFormState()) }

    fun removePeer(rowId: String) = _state.update { s ->
        if (s.peers.size <= 1) s
        else s.copy(peers = s.peers.filterNot { it.rowId == rowId })
    }

    /**
     * Validate every field and persist if valid. Restarts the VPN if the
     * edited profile is the active one and WireGuard routing is on.
     */
    fun save() {
        viewModelScope.launch {
            val s = _state.value
            val errors = validate(s)
            _errors.value = WireGuardEditErrors(errors)
            if (errors.isNotEmpty()) {
                _events.emit(EditEvent.Failed("Fix the highlighted fields"))
                return@launch
            }

            val updated = s.toProfile()
            val active = appPrefs.getActiveWgProfileSnapshot()
            appPrefs.addOrUpdateWgProfile(updated)
            _events.emit(EditEvent.Saved(updated.name))

            // Restart VPN only if this profile is the active one and WG is on.
            val isWgOn =
                appPrefs.getRoutingModeSnapshot() == AppPreferences.ROUTING_MODE_WIREGUARD
            if (isWgOn && active?.id == updated.id) {
                ServiceController.requestRestart(getApplication())
            }
        }
    }

    private fun validate(s: WireGuardEditState): Map<String, String> {
        val errs = mutableMapOf<String, String>()
        if (s.name.isBlank()) errs[FIELD_NAME] = "Name is required"
        WireGuardValidators.key(s.privateKey, "Private key")?.let { errs[FIELD_PRIVATE_KEY] = it }

        val addresses = s.addresses.splitTrim()
        if (addresses.isEmpty()) {
            errs[FIELD_ADDRESSES] = "At least one address is required"
        } else {
            addresses.firstNotNullOfOrNull { WireGuardValidators.cidr(it) }
                ?.let { errs[FIELD_ADDRESSES] = it }
        }

        WireGuardValidators.port(s.listenPort)?.let { errs[FIELD_LISTEN_PORT] = it }

        s.dns.splitTrim().firstNotNullOfOrNull { WireGuardValidators.ip(it) }
            ?.let { errs[FIELD_DNS] = it }

        if (s.peers.isEmpty()) {
            errs[FIELD_PEERS] = "At least one peer is required"
        }
        for (peer in s.peers) {
            WireGuardValidators.key(peer.publicKey, "Public key")
                ?.let { errs["peer.${peer.rowId}.publicKey"] = it }
            WireGuardValidators.key(peer.presharedKey, "Preshared key", optional = true)
                ?.let { errs["peer.${peer.rowId}.presharedKey"] = it }
            WireGuardValidators.endpoint(peer.endpoint, optional = true)
                ?.let { errs["peer.${peer.rowId}.endpoint"] = it }
            peer.allowedIPs.splitTrim().firstNotNullOfOrNull { WireGuardValidators.cidr(it) }
                ?.let { errs["peer.${peer.rowId}.allowedIPs"] = it }
            WireGuardValidators.keepalive(peer.persistentKeepalive)
                ?.let { errs["peer.${peer.rowId}.persistentKeepalive"] = it }
        }
        return errs
    }

    private fun WireGuardEditState.toProfile(): WireGuardProfile = WireGuardProfile(
        id = profileId,
        name = name.trim(),
        config = WireGuardConfig(
            interfaceConfig = WireGuardInterface(
                privateKey = privateKey.trim(),
                address = addresses.splitTrim(),
                listenPort = listenPort.trim().toIntOrNull(),
                dns = dns.splitTrim(),
            ),
            peers = peers.map { p ->
                WireGuardPeer(
                    publicKey = p.publicKey.trim(),
                    presharedKey = p.presharedKey.trim().takeIf { it.isNotEmpty() },
                    endpoint = p.endpoint.trim().takeIf { it.isNotEmpty() },
                    allowedIPs = p.allowedIPs.splitTrim(),
                    persistentKeepalive = p.persistentKeepalive.trim().toIntOrNull(),
                )
            },
        ),
    )

    private fun WireGuardProfile.toFormState() = WireGuardEditState(
        profileId = id,
        name = name,
        privateKey = config.interfaceConfig.privateKey,
        addresses = config.interfaceConfig.address.joinToString(", "),
        listenPort = config.interfaceConfig.listenPort?.toString().orEmpty(),
        dns = config.interfaceConfig.dns.joinToString(", "),
        peers = config.peers.map { p ->
            PeerFormState(
                publicKey = p.publicKey,
                presharedKey = p.presharedKey.orEmpty(),
                endpoint = p.endpoint.orEmpty(),
                allowedIPs = p.allowedIPs.joinToString(", "),
                persistentKeepalive = p.persistentKeepalive?.toString().orEmpty(),
            )
        }.ifEmpty { listOf(PeerFormState()) },
    )

    private fun String.splitTrim(): List<String> =
        split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun MutableStateFlow<WireGuardEditState>.update(
        transform: (WireGuardEditState) -> WireGuardEditState,
    ) {
        value = transform(value)
    }

    companion object {
        const val FIELD_NAME = "name"
        const val FIELD_PRIVATE_KEY = "privateKey"
        const val FIELD_ADDRESSES = "addresses"
        const val FIELD_LISTEN_PORT = "listenPort"
        const val FIELD_DNS = "dns"
        const val FIELD_PEERS = "peers"
    }
}
