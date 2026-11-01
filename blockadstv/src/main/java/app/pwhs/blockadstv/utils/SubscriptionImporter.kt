package app.pwhs.blockadstv.utils

import android.util.Base64
import app.pwhs.blockadstv.data.entities.ProxyProfile
import timber.log.Timber

data class SubscriptionImportResult(
    val proxyProfiles: List<ProxyProfile> = emptyList(),
    val failedCount: Int = 0
) {
    val totalParsed: Int get() = proxyProfiles.size
}

object SubscriptionImporter {

    fun parseSubscriptionContent(content: String): SubscriptionImportResult {
        val trimmed = content.trim()
        val decodedContent = tryDecodeBase64(trimmed) ?: trimmed
        val lines = decodedContent.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val profiles = mutableListOf<ProxyProfile>()
        var failed = 0

        for (line in lines) {
            if (line.startsWith("#") || line.startsWith("//")) continue
            val result = ProxyLinkParser.parse(line)
            result.onSuccess { profile ->
                profiles.add(profile)
            }.onFailure {
                failed++
                Timber.w("Failed to parse node link in subscription: $line")
            }
        }

        return SubscriptionImportResult(
            proxyProfiles = profiles,
            failedCount = failed
        )
    }

    private fun tryDecodeBase64(input: String): String? {
        val cleaned = input.replace("\r", "").replace("\n", "").trim()
        val flags = listOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE)
        for (flag in flags) {
            try {
                val bytes = Base64.decode(cleaned, flag)
                val str = String(bytes, Charsets.UTF_8)
                if (str.any { it in "\u0000\u0001\u0002\u0003" }) continue
                if (str.contains("://")) return str
            } catch (_: Exception) {}
        }
        return null
    }
}
