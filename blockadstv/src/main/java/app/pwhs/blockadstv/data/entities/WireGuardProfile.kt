package app.pwhs.blockadstv.data.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class WireGuardProfile(
    val id: String,
    val name: String,
    val config: WireGuardConfig,
) {
    val isAmnezia: Boolean
        get() = config.isAmnezia

    fun isSameProfile(other: WireGuardProfile): Boolean {
        if (id == other.id) return true
        val thisPeer = config.peers.firstOrNull()
        val otherPeer = other.config.peers.firstOrNull()
        if (thisPeer != null && otherPeer != null) {
            val end1 = thisPeer.endpoint.orEmpty()
            val end2 = otherPeer.endpoint.orEmpty()
            if (end1.isNotEmpty() && end1 == end2 &&
                thisPeer.publicKey.isNotEmpty() && thisPeer.publicKey == otherPeer.publicKey
            ) {
                return true
            }
        }
        return name.isNotEmpty() &&
            name.equals(other.name, ignoreCase = true) &&
            thisPeer?.endpoint == otherPeer?.endpoint
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

@Serializable
data class WireGuardProfileList(
    val profiles: List<WireGuardProfile> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonStr: String): WireGuardProfileList =
            try {
                json.decodeFromString<WireGuardProfileList>(jsonStr)
            } catch (_: Exception) {
                WireGuardProfileList()
            }
    }
}
