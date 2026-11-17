package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.WireGuardProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import timber.log.Timber

data class SubscriptionImportResult(
    val proxyProfiles: List<ProxyProfile> = emptyList(),
    val wireGuardProfiles: List<WireGuardProfile> = emptyList(),
    val totalParsed: Int = 0,
    val failedCount: Int = 0,
    val subscriptionUrl: String = ""
)

object SubscriptionImporter {

    suspend fun importFromUrl(url: String, httpClient: HttpClient): Result<SubscriptionImportResult> =
        runCatching {
            val trimmedUrl = url.trim()
            require(trimmedUrl.startsWith("http://", ignoreCase = true) || trimmedUrl.startsWith("https://", ignoreCase = true)) {
                "URL must start with http:// or https://"
            }

            val responseText = httpClient.get(trimmedUrl).bodyAsText().trim()
            val parsed = parseSubscriptionContent(responseText)
            parsed.copy(
                proxyProfiles = parsed.proxyProfiles.map { it.copy(subscriptionUrl = trimmedUrl) },
                subscriptionUrl = trimmedUrl
            )
        }

    fun parseSubscriptionContent(content: String): SubscriptionImportResult {
        val trimmedRaw = content.trim()
        if ((trimmedRaw.startsWith("[") && trimmedRaw.endsWith("]")) || (trimmedRaw.startsWith("{") && trimmedRaw.endsWith("}"))) {
            val jsonResult = JsonSubscriptionParser.parse(trimmedRaw)
            if (jsonResult.totalParsed > 0) {
                return jsonResult
            }
        }

        val decodedText = decodeBase64IfPossible(trimmedRaw)
        val trimmedDecoded = decodedText.trim()
        if ((trimmedDecoded.startsWith("[") && trimmedDecoded.endsWith("]")) || (trimmedDecoded.startsWith("{") && trimmedDecoded.endsWith("}"))) {
            val jsonResult = JsonSubscriptionParser.parse(trimmedDecoded)
            if (jsonResult.totalParsed > 0) {
                return jsonResult
            }
        }

        val lines = decodedText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val proxyList = mutableListOf<ProxyProfile>()
        val wgList = mutableListOf<WireGuardProfile>()
        var failed = 0

        for (line in lines) {
            when {
                line.startsWith("wireguard://", ignoreCase = true) -> {
                    try {
                        val wgProfile = WireGuardConfigParser.parseUri(line)
                        wgList.add(wgProfile)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to parse wireguard link: %s", line)
                        failed++
                    }
                }
                line.startsWith("vpn://", ignoreCase = true) ||
                line.startsWith("awg://", ignoreCase = true) -> {
                    try {
                        val wgProfile = WireGuardConfigParser.parseVpnUri(line)
                        wgList.add(wgProfile)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to parse amnezia vpn link: %s", line)
                        failed++
                    }
                }
                line.startsWith("trojan://", ignoreCase = true) ||
                line.startsWith("vless://", ignoreCase = true) ||
                line.startsWith("vmess://", ignoreCase = true) ||
                line.startsWith("ss://", ignoreCase = true) ||
                line.startsWith("hysteria2://", ignoreCase = true) ||
                line.startsWith("hy2://", ignoreCase = true) ||
                line.startsWith("tuic://", ignoreCase = true) ||
                line.startsWith("masque://", ignoreCase = true) ||
                line.startsWith("socks5://", ignoreCase = true) ||
                line.startsWith("socks://", ignoreCase = true) ||
                line.startsWith("socks4://", ignoreCase = true) ||
                line.startsWith("socks4a://", ignoreCase = true) ||
                line.startsWith("ssh://", ignoreCase = true) -> {
                    val result = ProxyLinkParser.parse(line)
                    result.onSuccess { proxyList.add(it) }
                        .onFailure {
                            Timber.w(it, "Failed to parse proxy link: %s", line)
                            failed++
                        }
                }
                else -> {
                    // Ignore non-VPN lines (comments or metadata headers)
                }
            }
        }

        return SubscriptionImportResult(
            proxyProfiles = proxyList,
            wireGuardProfiles = wgList,
            totalParsed = proxyList.size + wgList.size,
            failedCount = failed
        )
    }

    private fun decodeBase64IfPossible(text: String): String {
        // If content has multiple lines and starts with a known protocol, or is JSON, it's already plain text
        if (text.contains("://") || (text.startsWith("[") && text.endsWith("]")) || (text.startsWith("{") && text.endsWith("}"))) {
            return text
        }
        return try {
            val bytes = try {
                java.util.Base64.getDecoder().decode(text)
            } catch (_: Exception) {
                try {
                    java.util.Base64.getUrlDecoder().decode(text)
                } catch (_: Exception) {
                    android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                }
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            text
        }
    }
}
