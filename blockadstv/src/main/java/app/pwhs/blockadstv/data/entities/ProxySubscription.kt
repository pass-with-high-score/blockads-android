package app.pwhs.blockadstv.data.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

@Serializable
data class ProxySubscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val nodeCount: Int = 0,
    val autoUpdate: Boolean = false
) {
    val displayName: String
        get() = name.ifEmpty {
            runCatching { URI(url).host }.getOrNull()?.ifEmpty { url } ?: url
        }
}

@Serializable
data class ProxySubscriptionList(
    val subscriptions: List<ProxySubscription> = emptyList()
) {
    fun toJson(): String = jsonInstance.encodeToString(this)

    companion object {
        private val jsonInstance = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonStr: String): ProxySubscriptionList =
            try {
                jsonInstance.decodeFromString<ProxySubscriptionList>(jsonStr)
            } catch (_: Exception) {
                ProxySubscriptionList()
            }
    }
}
