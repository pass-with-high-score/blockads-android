package app.pwhs.blockads.ui.proxyvpn.edit

import app.pwhs.blockads.data.entities.ProxyProtocol

data class ProxyEditUiState(
    val isNew: Boolean = true,
    val profileId: String = "",
    val name: String = "",
    val protocol: ProxyProtocol = ProxyProtocol.VLESS,
    val server: String = "",
    val port: String = "443",
    // Shadowsocks specific
    val method: String = "aes-256-gcm",
    // Shadowsocks, Trojan, Hysteria2, TUIC specific
    val password: String = "",
    // VLESS, VMess, TUIC specific
    val uuid: String = "",
    val alterId: String = "0",
    // Security / TLS / Transport
    val security: String = "tls", // "tls", "reality", "none" (or "auto" for VMess)
    val flow: String = "",        // "xtls-rprx-vision", ""
    val network: String = "tcp",  // "tcp" or "ws"
    val wsPath: String = "/",
    val wsHost: String = "",
    val sni: String = "",
    val allowInsecure: Boolean = false,
    val fingerprint: String = "chrome",
    // VLESS Reality specific
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    // Hysteria 2 specific
    val obfs: String = "",
    val obfsPassword: String = "",
    // TUIC specific
    val congestionControl: String = "bbr",
    val udpRelayMode: String = "native",
    val alpn: String = "h3",
    // MASQUE specific
    val masqueUser: String = "",
    val masquePass: String = "",
    val masquePath: String = "/.well-known/masque/ip/*/*/",
    // SOCKS / HTTP(S) / SSH specific
    val username: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    // Custom JSON mode
    val isRawJsonMode: Boolean = false,
    val rawJson: String = "",
    // Status
    val isLoading: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val shareLink: String = ""
)

sealed interface ProxyEditIntent {
    data class Load(val profileId: String?) : ProxyEditIntent
    data class SetName(val name: String) : ProxyEditIntent
    data class SetProtocol(val protocol: ProxyProtocol) : ProxyEditIntent
    data class SetServer(val server: String) : ProxyEditIntent
    data class SetPort(val port: String) : ProxyEditIntent
    data class SetMethod(val method: String) : ProxyEditIntent
    data class SetPassword(val password: String) : ProxyEditIntent
    data class SetUuid(val uuid: String) : ProxyEditIntent
    data object GenerateUuid : ProxyEditIntent
    data class SetAlterId(val alterId: String) : ProxyEditIntent
    data class SetSecurity(val security: String) : ProxyEditIntent
    data class SetFlow(val flow: String) : ProxyEditIntent
    data class SetNetwork(val network: String) : ProxyEditIntent
    data class SetWsPath(val path: String) : ProxyEditIntent
    data class SetWsHost(val host: String) : ProxyEditIntent
    data class SetSni(val sni: String) : ProxyEditIntent
    data class SetAllowInsecure(val allow: Boolean) : ProxyEditIntent
    data class SetFingerprint(val fp: String) : ProxyEditIntent
    data class SetPublicKey(val key: String) : ProxyEditIntent
    data class SetShortId(val id: String) : ProxyEditIntent
    data class SetSpiderX(val spx: String) : ProxyEditIntent
    data class SetObfs(val obfs: String) : ProxyEditIntent
    data class SetObfsPassword(val pass: String) : ProxyEditIntent
    data class SetCongestionControl(val cc: String) : ProxyEditIntent
    data class SetUdpRelayMode(val mode: String) : ProxyEditIntent
    data class SetAlpn(val alpn: String) : ProxyEditIntent
    data class SetMasqueUser(val user: String) : ProxyEditIntent
    data class SetMasquePass(val pass: String) : ProxyEditIntent
    data class SetMasquePath(val path: String) : ProxyEditIntent
    data class SetUsername(val username: String) : ProxyEditIntent
    data class SetPrivateKey(val privateKey: String) : ProxyEditIntent
    data class SetPassphrase(val passphrase: String) : ProxyEditIntent
    data class ToggleRawJsonMode(val isRaw: Boolean) : ProxyEditIntent
    data class SetRawJson(val json: String) : ProxyEditIntent
    data object Save : ProxyEditIntent
}

sealed interface ProxyEditEffect {
    data class Saved(val name: String) : ProxyEditEffect
    data class ShowMessage(val message: String) : ProxyEditEffect
}
