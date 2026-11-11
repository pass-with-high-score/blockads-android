package app.pwhs.blockads.ui.tailscale

data class TailscalePeer(
    val id: Long,
    val name: String,
    val ips: List<String>,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val os: String,
    val online: Boolean,
    val isExitNode: Boolean,
    val isPinned: Boolean = false,
    val isSelf: Boolean = false,
    val keyExpiry: Long = 0L,
    val keyExpired: Boolean = false,
    val curAddr: String = "",
    val relay: String = "",
    val lastPingMs: Long? = null,
    val isPingLoading: Boolean = false,
    val pingError: String? = null,
)

data class TailscaleExitNodeItem(
    val id: Long,
    val name: String,
    val ip: String,
)

data class TailscaleUiState(
    val isEnabled: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isVpnRunning: Boolean = false,
    val connectionState: String = "DISCONNECTED", // DISCONNECTED, CONNECTING, AUTHENTICATING, NEEDS_AUTH, CONNECTED, VPN_DISCONNECTED, ERROR
    val authUrl: String = "",
    val errorMessage: String = "",
    val selfIp: String = "",
    val magicDnsName: String = "",
    val controlUrl: String = "https://controlplane.tailscale.com",
    val authKey: String = "",
    val peers: List<TailscalePeer> = emptyList(),
    val exitNodes: List<TailscaleExitNodeItem> = emptyList(),
    val activeExitNodeId: Long = 0L,
    val isLoading: Boolean = false,
    val selectedPeerForDetail: TailscalePeer? = null,
    val pinnedPeerIds: Set<Long> = emptySet(),
    val taildropDirUri: String = "",
    val taildropDirName: String = "Downloads/Taildrop",
) {
    val hasCustomTaildropDir: Boolean
        get() = taildropDirUri.isNotEmpty()
}

sealed interface TailscaleUiIntent {
    data class ToggleEnabled(val enabled: Boolean) : TailscaleUiIntent
    data object SignInWithBrowser : TailscaleUiIntent
    data class SignInWithAuthKey(val key: String) : TailscaleUiIntent
    data class SetControlUrl(val url: String) : TailscaleUiIntent
    data class SelectExitNode(val nodeId: Long) : TailscaleUiIntent
    data object RefreshStatus : TailscaleUiIntent
    data object StartVpn : TailscaleUiIntent
    data object Logout : TailscaleUiIntent
    data class SelectPeerForDetail(val peer: TailscalePeer?) : TailscaleUiIntent
    data class TogglePinPeer(val peerId: Long) : TailscaleUiIntent
    data class PingPeer(val peerId: Long, val target: String) : TailscaleUiIntent
    data class SetTaildropDirectory(val uri: String, val displayName: String) : TailscaleUiIntent
    data object ResetTaildropDirectory : TailscaleUiIntent
    data class CopyToClipboard(val text: String, val label: String) : TailscaleUiIntent
}

sealed interface TailscaleUiEffect {
    data class OpenBrowser(val url: String) : TailscaleUiEffect
    data class ShowSnackbar(val message: String) : TailscaleUiEffect
    data class CopyToClipboard(val text: String, val label: String) : TailscaleUiEffect
    data object AuthSuccess : TailscaleUiEffect
}
