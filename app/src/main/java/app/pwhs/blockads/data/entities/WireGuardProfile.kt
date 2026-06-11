package app.pwhs.blockads.data.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A named WireGuard configuration. Multiple profiles can be saved; one is
 * marked active in [app.pwhs.blockads.data.datastore.AppPreferences] and is
 * the one the VPN service uses on start.
 */
@Serializable
data class WireGuardProfile(
    val id: String,
    val name: String,
    val config: WireGuardConfig,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Wrapper used to persist the profile list as a single JSON string. */
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
