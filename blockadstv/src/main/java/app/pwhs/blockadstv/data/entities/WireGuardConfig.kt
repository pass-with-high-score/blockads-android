package app.pwhs.blockadstv.data.entities

import app.pwhs.blockadstv.utils.DnsResolverUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WireGuardConfig(
    val interfaceConfig: WireGuardInterface,
    val peers: List<WireGuardPeer>,
) {
    val isAmnezia: Boolean
        get() = interfaceConfig.isAmnezia

    fun toJson(): String = json.encodeToString(this)

    suspend fun resolveEndpoints(): WireGuardConfig {
        val resolvedPeers = peers.map { peer ->
            val endpoint = peer.endpoint ?: return@map peer
            val lastColon = endpoint.lastIndexOf(':')
            if (lastColon <= 0) return@map peer

            val host = endpoint.substring(0, lastColon).trim().removeSurrounding("[", "]")
            val port = endpoint.substring(lastColon + 1).trim()

            val resolved = DnsResolverUtils.resolveHost(host)
            if (resolved != null) {
                peer.copy(endpoint = "$resolved:$port")
            } else {
                peer
            }
        }
        return copy(peers = resolvedPeers)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonStr: String): WireGuardConfig =
            json.decodeFromString<WireGuardConfig>(jsonStr)
    }
}

@Serializable
data class WireGuardInterface(
    val privateKey: String,
    val address: List<String>,
    val listenPort: Int? = null,
    val dns: List<String> = emptyList(),
    val jc: Int? = null,
    val jmin: Int? = null,
    val jmax: Int? = null,
    val s1: Int? = null,
    val s2: Int? = null,
    val s3: Int? = null,
    val s4: Int? = null,
    val h1: Long? = null,
    val h2: Long? = null,
    val h3: Long? = null,
    val h4: Long? = null,
    val headerProtectionKey: String? = null,
    val randomTrailers: Boolean? = null,
    val disableCookies: Boolean? = null,
    val contentPaddingAddition: String? = null,
    val rekeyAfterTime: String? = null,
    val rekeyTimeout: String? = null,
    val rejectAfterTime: String? = null,
    val keepaliveTimeout: String? = null,
    val maxHandshakeAttempts: String? = null,
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null,
) {
    val isAmnezia: Boolean
        get() = jc != null || s1 != null || h1 != null || headerProtectionKey != null || i1 != null
}

@Serializable
data class WireGuardPeer(
    val publicKey: String,
    val presharedKey: String? = null,
    val endpoint: String? = null,
    @SerialName("allowedIPs")
    val allowedIPs: List<String> = emptyList(),
    val persistentKeepalive: Int? = null,
)
