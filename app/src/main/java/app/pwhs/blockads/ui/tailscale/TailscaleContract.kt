package app.pwhs.blockads.ui.tailscale

data class TailscalePeer(
    val id: Long,
    val name: String,
    val ips: List<String>,
    val os: String,
    val online: Boolean,
    val isExitNode: Boolean,
)

data class TailscaleExitNodeItem(
    val id: Long,
    val name: String,
    val ip: String,
)

data class TailscaleUiState(
    val isEnabled: Boolean = false,
    val isLoggedIn: Boolean = false,
    val connectionState: String = "DISCONNECTED", // DISCONNECTED, CONNECTING, AUTHENTICATING, NEEDS_AUTH, CONNECTED, ERROR
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
)

sealed interface TailscaleUiIntent {
    data class ToggleEnabled(val enabled: Boolean) : TailscaleUiIntent
    data object SignInWithBrowser : TailscaleUiIntent
    data class SignInWithAuthKey(val key: String) : TailscaleUiIntent
    data class SetControlUrl(val url: String) : TailscaleUiIntent
    data class SelectExitNode(val nodeId: Long) : TailscaleUiIntent
    data object RefreshStatus : TailscaleUiIntent
    data object Logout : TailscaleUiIntent
}

sealed interface TailscaleUiEffect {
    data class OpenBrowser(val url: String) : TailscaleUiEffect
    data class ShowSnackbar(val message: String) : TailscaleUiEffect
}
