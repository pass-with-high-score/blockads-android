package app.pwhs.blockadstv.data.model

import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SniProxyRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val targetIp: String,
    val targetPort: Int = 443,
    val packageNames: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    val targetAddress: String
        get() = if (targetIp.contains(":")) "[$targetIp]:$targetPort" else "$targetIp:$targetPort"
}

object SniProxyRuleCodec {
    const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"
    val YOUTUBE_PRESET_DOMAINS = listOf(
        "youtubei.googleapis.com",
        "googlevideo.com",
        "youtube.com",
    )

    fun fromJsonList(raw: String): List<SniProxyRule> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        SniProxyRule(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = obj.optString("name", ""),
                            targetIp = obj.optString("targetIp", ""),
                            targetPort = obj.optInt("targetPort", 443).coerceIn(1, 65535),
                            packageNames = obj.optJSONArray("packageNames").toStringList(),
                            domains = obj.optJSONArray("domains").toStringList(),
                            enabled = obj.optBoolean("enabled", true),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun toJsonList(rules: List<SniProxyRule>): String {
        val arr = JSONArray()
        rules.forEach { rule ->
            arr.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("targetIp", rule.targetIp)
                    put("targetPort", rule.targetPort)
                    put("packageNames", JSONArray(rule.packageNames))
                    put("domains", JSONArray(rule.domains))
                    put("enabled", rule.enabled)
                }
            )
        }
        return arr.toString()
    }

    /**
     * Resolves package names to Android UIDs for the Go engine so packet-level
     * routing avoids JNI calls on the hot path.
     */
    fun toEngineJson(rules: List<SniProxyRule>, packageManager: PackageManager): String {
        if (rules.isEmpty()) return "[]"
        val arr = JSONArray()
        rules.forEach { rule ->
            if (!rule.enabled || rule.targetIp.isBlank()) return@forEach
            val uids = if (rule.packageNames.isEmpty()) {
                emptyList()
            } else {
                rule.packageNames.mapNotNull { pkg ->
                    runCatching { packageManager.getPackageUid(pkg, 0) }.getOrNull()
                }
            }
            if (rule.packageNames.isNotEmpty() && uids.isEmpty()) return@forEach

            arr.put(
                JSONObject().apply {
                    put("targetAddr", rule.targetAddress)
                    put("uids", JSONArray(uids))
                    put("domains", JSONArray(rule.domains))
                    put("enabled", rule.enabled)
                }
            )
        }
        return arr.toString()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                val item = optString(i).trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }
}
