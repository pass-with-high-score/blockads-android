package app.pwhs.blockadstv.data.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

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
    // Metadata
    val tag: String = "",
    val rawUri: String = "",
    val subscriptionUrl: String = "",
    val subscriptionId: String = ""
) {
    val displayName: String
        get() = name.ifEmpty { tag.ifEmpty { "$server:$port" } }

    val protocolBadgeText: String
        get() = when {
            security.equals("reality", ignoreCase = true) -> "REALITY"
            protocol == ProxyProtocol.TROJAN -> "Trojan"
            network.equals("ws", ignoreCase = true) -> "${protocol.displayName}+WS"
            protocol == ProxyProtocol.HYSTERIA2 -> "Hysteria 2"
            protocol == ProxyProtocol.MASQUE -> "MASQUE"
            else -> protocol.displayName
        }

    fun sanitizeOrRepair(): ProxyProfile {
        var profile = this
        if (profile.security.isEmpty() || profile.security == "none") {
            if (profile.sni.isNotEmpty() || profile.rawUri.contains("security=tls", ignoreCase = true) || profile.rawUri.contains("\"tls\":\"tls\"", ignoreCase = true)) {
                profile = profile.copy(security = "tls")
            }
        }
        return profile
    }

    fun toEngineJson(): String = jsonInstance.encodeToString(serializer(), sanitizeOrRepair())

    companion object {
        private val jsonInstance = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(jsonStr: String): ProxyProfile =
            jsonInstance.decodeFromString(serializer(), jsonStr)
    }
}

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
