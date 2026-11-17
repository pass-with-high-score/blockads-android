package app.pwhs.blockads.data.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class ProxyProtocol(val value: String, val displayName: String) {
    SHADOWSOCKS("shadowsocks", "SS"),
    TROJAN("trojan", "Trojan"),
    VLESS("vless", "VLESS"),
    VMESS("vmess", "VMess"),
    HYSTERIA2("hysteria2", "Hysteria 2"),
    TUIC("tuic", "TUIC"),
    MASQUE("masque", "MASQUE"),
    SOCKS5("socks5", "SOCKS5"),
    SOCKS4("socks4", "SOCKS4"),
    HTTP("http", "HTTP"),
    HTTPS("https", "HTTPS"),
    SSH("ssh", "SSH");

    companion object {
        fun fromValue(value: String): ProxyProtocol = when (value.lowercase()) {
            "trojan" -> TROJAN
            "vless" -> VLESS
            "vmess" -> VMESS
            "hysteria2", "hy2" -> HYSTERIA2
            "tuic" -> TUIC
            "masque" -> MASQUE
            "socks5", "socks" -> SOCKS5
            "socks4", "socks4a" -> SOCKS4
            "http" -> HTTP
            "https" -> HTTPS
            "ssh" -> SSH
            else -> SHADOWSOCKS
        }
    }
}

@Serializable
data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: ProxyProtocol = ProxyProtocol.SHADOWSOCKS,
    val server: String = "",
    val port: Int = 443,
    // Shadowsocks specific
    val method: String = "",
    // Shadowsocks & Trojan & Hysteria2 & TUIC specific
    val password: String = "",
    // VLESS & VMess & TUIC specific
    val uuid: String = "",
    val alterId: Int = 0,
    // TLS / Transport
    val sni: String = "",
    val allowInsecure: Boolean = false,
    val security: String = "tls", // "tls", "reality", or "none"
    val network: String = "tcp",  // "tcp" or "ws"
    val wsPath: String = "",
    val wsHost: String = "",
    // VLESS Reality specific
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val fingerprint: String = "",
    val flow: String = "",
    // Hysteria2 specific
    val obfs: String = "",
    val obfsPassword: String = "",
    // TUIC specific
    val congestionControl: String = "bbr",
    val udpRelayMode: String = "native",
    val alpn: String = "",
    // MASQUE specific
    val masquePath: String = "",
    val masqueUser: String = "",
    val masquePass: String = "",
    // SOCKS / HTTP(S) / SSH specific
    val username: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    // Metadata
    val tag: String = "",
    val rawUri: String = "",
    val subscriptionUrl: String = "",
    val subscriptionId: String = ""
) {
    val displayName: String
        get() = name.ifEmpty { tag.ifEmpty { "$server:$port" } }

    /** Indicates whether this profile uses DPI-resistant stealth obfuscation */
    val isObfuscated: Boolean
        get() = security.equals("reality", ignoreCase = true) ||
                protocol == ProxyProtocol.TROJAN ||
                network.equals("ws", ignoreCase = true) ||
                (protocol == ProxyProtocol.HYSTERIA2 && obfs.isNotEmpty()) ||
                protocol == ProxyProtocol.MASQUE

    val protocolBadgeText: String
        get() = when {
            security.equals("reality", ignoreCase = true) -> "REALITY"
            protocol == ProxyProtocol.TROJAN -> "Trojan"
            network.equals("ws", ignoreCase = true) -> "${protocol.displayName}+WS"
            protocol == ProxyProtocol.HYSTERIA2 -> "Hysteria 2"
            protocol == ProxyProtocol.MASQUE -> "MASQUE (H3)"
            else -> protocol.displayName
        }

    /** Determines if two profiles point to the same remote proxy node */
    fun isSameNode(other: ProxyProfile): Boolean {
        if (id == other.id) return true
        if (rawUri.isNotEmpty() && other.rawUri.isNotEmpty() && rawUri == other.rawUri) return true
        if (protocol != other.protocol) return false
        if (!server.equals(other.server, ignoreCase = true) || port != other.port) return false
        val credMatch = when (protocol) {
            ProxyProtocol.TROJAN, ProxyProtocol.HYSTERIA2 -> password.isNotEmpty() && password == other.password
            ProxyProtocol.VLESS, ProxyProtocol.VMESS -> uuid.isNotEmpty() && uuid == other.uuid
            ProxyProtocol.SHADOWSOCKS -> (method.isEmpty() || method == other.method) && password == other.password
            ProxyProtocol.TUIC -> (uuid.isNotEmpty() && uuid == other.uuid) || (password.isNotEmpty() && password == other.password)
            ProxyProtocol.MASQUE -> (masqueUser.isNotEmpty() && masqueUser == other.masqueUser) || (masquePass.isNotEmpty() && masquePass == other.masquePass)
            ProxyProtocol.SOCKS5, ProxyProtocol.SOCKS4, ProxyProtocol.HTTP, ProxyProtocol.HTTPS, ProxyProtocol.SSH ->
                username == other.username && password == other.password
        }
        if (credMatch) return true
        if (name.isNotEmpty() && name.equals(other.name, ignoreCase = true)) return true
        if (tag.isNotEmpty() && tag.equals(other.tag, ignoreCase = true)) return true
        // If same protocol, server, and port with no conflicting credentials
        return true
    }

    suspend fun resolveServer(): ProxyProfile {
        val resolved = app.pwhs.blockads.utils.DnsResolverUtils.resolveHost(server)
        return if (resolved != null) {
            copy(
                server = resolved,
                sni = sni.ifEmpty { server },
                wsHost = if (network == "ws" && wsHost.isEmpty()) server else wsHost
            )
        } else {
            this
        }
    }

    fun sanitizeOrRepair(): ProxyProfile {
        var profile = this
        if (protocol == ProxyProtocol.SHADOWSOCKS && rawUri.isNotEmpty()) {
            val isCorrupted = method.any { it.code !in 32..126 }
            if (isCorrupted) {
                val parsed = app.pwhs.blockads.utils.ProxyLinkParser.parse(rawUri).getOrNull()
                if (parsed != null) {
                    profile = parsed.copy(
                        id = this.id,
                        subscriptionId = this.subscriptionId,
                        subscriptionUrl = this.subscriptionUrl
                    )
                }
            }
        }
        if (profile.protocol == ProxyProtocol.HTTPS && profile.security != "tls") {
            profile = profile.copy(security = "tls")
        } else if (profile.protocol !in setOf(ProxyProtocol.SOCKS5, ProxyProtocol.SOCKS4, ProxyProtocol.HTTP, ProxyProtocol.SSH) &&
            (profile.security.isEmpty() || profile.security == "none")
        ) {
            if (profile.sni.isNotEmpty() || profile.rawUri.contains("security=tls", ignoreCase = true) || profile.rawUri.contains("\"tls\":\"tls\"", ignoreCase = true)) {
                profile = profile.copy(security = "tls")
            }
        }
        return profile
    }

    fun toEngineJson(): String = jsonInstance.encodeToString(serializer(), sanitizeOrRepair())

    fun toShareLink(): String = rawUri.ifEmpty { app.pwhs.blockads.utils.ProxyLinkBuilder.buildLink(this) }

    companion object {
        private val jsonInstance = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(jsonStr: String): ProxyProfile =
            jsonInstance.decodeFromString(serializer(), jsonStr)
    }
}

fun ShadowsocksProfile.toProxyProfile(): ProxyProfile = ProxyProfile(
    id = id,
    name = name,
    protocol = ProxyProtocol.SHADOWSOCKS,
    server = server,
    port = port,
    method = method,
    password = password,
    tag = tag,
    rawUri = rawUri
)

@Serializable
data class ProxyProfileList(
    val profiles: List<ProxyProfile> = emptyList(),
) {
    fun toJson(): String = jsonInstance.encodeToString(serializer(), this)

    companion object {
        private val jsonInstance = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonStr: String): ProxyProfileList =
            try {
                jsonInstance.decodeFromString(serializer(), jsonStr)
            } catch (_: Exception) {
                ProxyProfileList()
            }
    }
}
