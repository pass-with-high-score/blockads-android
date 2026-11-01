package app.pwhs.blockadstv.utils

import app.pwhs.blockadstv.data.entities.ProxyProfile
import app.pwhs.blockadstv.data.entities.ProxyProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ProxyLinkParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawUri: String): Result<ProxyProfile> = runCatching {
        val trimmed = rawUri.trim()
        when {
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojanUri(trimmed)
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVlessUri(trimmed)
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmessUri(trimmed)
            trimmed.startsWith("hysteria2://", ignoreCase = true) || trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2Uri(trimmed)
            trimmed.startsWith("tuic://", ignoreCase = true) -> parseTuicUri(trimmed)
            trimmed.startsWith("masque://", ignoreCase = true) -> parseMasqueUri(trimmed)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocksUri(trimmed)
            else -> throw IllegalArgumentException("Unsupported proxy URI. Expected trojan://, vless://, vmess://, hysteria2://, tuic://, masque://, or ss://")
        }
    }

    private fun parseMasqueUri(trimmed: String): ProxyProfile {
        val uri = URI(trimmed)
        val rawUserInfo = uri.rawUserInfo.orEmpty()
        val decodedUserInfo = URLDecoder.decode(rawUserInfo, StandardCharsets.UTF_8.name())
        val colonIdx = decodedUserInfo.indexOf(':')
        val user = if (colonIdx != -1) decodedUserInfo.substring(0, colonIdx) else decodedUserInfo
        val pass = if (colonIdx != -1) decodedUserInfo.substring(colonIdx + 1) else ""

        val server = uri.host.orEmpty()
        val port = if (uri.port != -1) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val sni = query["sni"].orEmpty().ifEmpty { server }
        val alpn = query["alpn"].orEmpty().ifEmpty { "h3" }
        val path = query["path"].orEmpty().ifEmpty { "/.well-known/masque/ip/*/*/" }
        val allowInsecure = query["allow_insecure"] == "1" || query["allow_insecure"].equals("true", ignoreCase = true)
        val tag = decodeFragment(uri.rawFragment)

        return ProxyProfile(
            name = tag.ifEmpty { "$server:$port" },
            protocol = ProxyProtocol.MASQUE,
            server = server,
            port = port,
            masqueUser = user,
            masquePass = pass,
            masquePath = path,
            sni = sni,
            alpn = alpn,
            allowInsecure = allowInsecure,
            tag = tag,
            rawUri = trimmed
        )
    }

    private fun parseTuicUri(trimmed: String): ProxyProfile {
        val uri = URI(trimmed)
        val rawUserInfo = uri.rawUserInfo.orEmpty()
        val decodedUserInfo = URLDecoder.decode(rawUserInfo, StandardCharsets.UTF_8.name())
        val colonIdx = decodedUserInfo.indexOf(':')
        val uuid = if (colonIdx != -1) decodedUserInfo.substring(0, colonIdx) else decodedUserInfo
        val password = if (colonIdx != -1) decodedUserInfo.substring(colonIdx + 1) else ""
        require(uuid.isNotEmpty()) { "Missing UUID in tuic URI" }

        val server = uri.host.orEmpty()
        val port = if (uri.port != -1) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val sni = query["sni"].orEmpty().ifEmpty { server }
        val alpn = query["alpn"].orEmpty()
        val congestionControl = query["congestion_control"].orEmpty().ifEmpty { "bbr" }
        val udpRelayMode = query["udp_relay_mode"].orEmpty().ifEmpty { "native" }
        val allowInsecure = query["allow_insecure"] == "1" || query["allow_insecure"].equals("true", ignoreCase = true)
        val tag = decodeFragment(uri.rawFragment)

        return ProxyProfile(
            name = tag.ifEmpty { "$server:$port" },
            protocol = ProxyProtocol.TUIC,
            server = server,
            port = port,
            uuid = uuid,
            password = password,
            sni = sni,
            alpn = alpn,
            congestionControl = congestionControl,
            udpRelayMode = udpRelayMode,
            allowInsecure = allowInsecure,
            tag = tag,
            rawUri = trimmed
        )
    }

    private fun parseTrojanUri(trimmed: String): ProxyProfile {
        val uri = URI(trimmed)
        val password = URLDecoder.decode(uri.rawUserInfo.orEmpty(), StandardCharsets.UTF_8.name())
        require(password.isNotEmpty()) { "Missing password in trojan URI" }
        val server = uri.host.orEmpty()
        val port = if (uri.port != -1) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val sni = query["sni"].orEmpty().ifEmpty { server }
        val allowInsecure = query["allowinsecure"] == "1" || query["allowinsecure"].equals("true", ignoreCase = true)
        val network = query["type"]?.lowercase() ?: "tcp"
        val wsPath = query["path"].orEmpty()
        val wsHost = query["host"].orEmpty()
        val tag = decodeFragment(uri.rawFragment)

        return ProxyProfile(
            name = tag.ifEmpty { "$server:$port" },
            protocol = ProxyProtocol.TROJAN,
            server = server,
            port = port,
            password = password,
            sni = sni,
            allowInsecure = allowInsecure,
            network = network,
            wsPath = wsPath,
            wsHost = wsHost,
            tag = tag,
            rawUri = trimmed
        )
    }

    private fun parseVlessUri(trimmed: String): ProxyProfile {
        val uri = URI(trimmed)
        val uuid = URLDecoder.decode(uri.rawUserInfo.orEmpty(), StandardCharsets.UTF_8.name())
        require(uuid.isNotEmpty()) { "Missing UUID in vless URI" }
        val server = uri.host.orEmpty()
        val port = if (uri.port != -1) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val security = query["security"]?.lowercase() ?: if (port == 443) "tls" else "none"
        val sni = query["sni"].orEmpty().ifEmpty { server }
        val allowInsecure = query["allowinsecure"] == "1" || query["allowinsecure"].equals("true", ignoreCase = true)
        val network = query["type"]?.lowercase() ?: "tcp"
        val wsPath = query["path"].orEmpty()
        val wsHost = query["host"].orEmpty()
        val publicKey = query["pbk"].orEmpty()
        val shortId = query["sid"].orEmpty()
        val spiderX = query["spx"].orEmpty()
        val fingerprint = query["fp"].orEmpty()
        val flow = query["flow"].orEmpty()
        val tag = decodeFragment(uri.rawFragment)

        return ProxyProfile(
            name = tag.ifEmpty { "$server:$port" },
            protocol = ProxyProtocol.VLESS,
            server = server,
            port = port,
            uuid = uuid,
            sni = sni,
            security = security,
            allowInsecure = allowInsecure,
            network = network,
            wsPath = wsPath,
            wsHost = wsHost,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
            fingerprint = fingerprint,
            flow = flow,
            tag = tag,
            rawUri = trimmed
        )
    }

    private fun parseVmessUri(trimmed: String): ProxyProfile {
        val base64Part = trimmed.substring(8).trim()
        val decodedJson = decodeBase64Safe(base64Part)
        val obj = json.parseToJsonElement(decodedJson).jsonObject
        val server = obj["add"]?.jsonPrimitive?.content.orEmpty()
        val port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 443
        val uuid = obj["id"]?.jsonPrimitive?.content.orEmpty()
        val alterId = obj["aid"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val net = obj["net"]?.jsonPrimitive?.content ?: "tcp"
        val tls = obj["tls"]?.jsonPrimitive?.content.orEmpty()
        val sni = obj["sni"]?.jsonPrimitive?.content.orEmpty().ifEmpty { obj["host"]?.jsonPrimitive?.content.orEmpty() }
        val wsPath = obj["path"]?.jsonPrimitive?.content.orEmpty()
        val wsHost = obj["host"]?.jsonPrimitive?.content.orEmpty()
        val tag = obj["ps"]?.jsonPrimitive?.content.orEmpty()

        return ProxyProfile(
            name = tag.ifEmpty { "$server:$port" },
            protocol = ProxyProtocol.VMESS,
            server = server,
            port = port,
            uuid = uuid,
            alterId = alterId,
            sni = sni,
            security = if (tls.equals("tls", ignoreCase = true)) "tls" else "none",
            network = net,
            wsPath = wsPath,
            wsHost = wsHost,
            tag = tag,
            rawUri = trimmed
        )
    }

    private fun parseHysteria2Uri(trimmed: String): ProxyProfile {
        val uri = URI(trimmed)
        val rawUserInfo = uri.rawUserInfo.orEmpty()
        val password = URLDecoder.decode(rawUserInfo, StandardCharsets.UTF_8.name())
        val server = uri.host.orEmpty()
        val port = if (uri.port != -1) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val sni = query["sni"].orEmpty()
        val obfs = query["obfs"].orEmpty()
        val obfsPassword = query["obfs-password"].orEmpty()
        val allowInsecure = query["insecure"] == "1" || query["insecure"].equals("true", ignoreCase = true)
        val tag = decodeFragment(uri.rawFragment)

        return ProxyProfile(
            name = tag.ifEmpty { "$server:$port" },
            protocol = ProxyProtocol.HYSTERIA2,
            server = server,
            port = port,
            password = password,
            sni = sni,
            allowInsecure = allowInsecure,
            obfs = obfs,
            obfsPassword = obfsPassword,
            tag = tag,
            rawUri = trimmed
        )
    }

    private fun parseShadowsocksUri(trimmed: String): ProxyProfile {
        val uri = URI(trimmed)
        val tag = decodeFragment(uri.rawFragment)
        var server = uri.host.orEmpty()
        var port = if (uri.port != -1) uri.port else 8388
        var method = ""
        var password = ""

        val rawUserInfo = uri.rawUserInfo
        if (!rawUserInfo.isNullOrEmpty()) {
            val safeUserInfo = rawUserInfo.replace("+", "%2B")
            val urlDecoded = runCatching { URLDecoder.decode(safeUserInfo, StandardCharsets.UTF_8.name()) }.getOrDefault(rawUserInfo)
            val parts = if (urlDecoded.contains(':')) {
                urlDecoded
            } else {
                decodeBase64Safe(rawUserInfo)
            }
            val colon = parts.indexOf(':')
            if (colon != -1) {
                method = parts.substring(0, colon)
                password = parts.substring(colon + 1)
            } else {
                password = parts
            }
        } else if (uri.host.isNullOrEmpty()) {
            val noScheme = trimmed.substringAfter("://").substringBefore("#").substringBefore("?")
            val decoded = decodeBase64Safe(noScheme)
            val at = decoded.lastIndexOf('@')
            if (at != -1) {
                val creds = decoded.substring(0, at)
                val hostPort = decoded.substring(at + 1)
                val cColon = creds.indexOf(':')
                if (cColon != -1) {
                    method = creds.substring(0, cColon)
                    password = creds.substring(cColon + 1)
                }
                val hpColon = hostPort.lastIndexOf(':')
                if (hpColon != -1) {
                    server = hostPort.substring(0, hpColon)
                    port = hostPort.substring(hpColon + 1).toIntOrNull() ?: 8388
                }
            }
        }

        val query = parseQuery(uri.rawQuery)
        val security = query["security"].orEmpty()
        val sni = query["sni"].orEmpty()
        val allowInsecure = query["insecure"] == "1" || query["insecure"].equals("true", ignoreCase = true)
        val network = query["type"].orEmpty().ifEmpty { query["network"].orEmpty() }.ifEmpty { "tcp" }

        return ProxyProfile(
            name = tag.ifEmpty { "$server:$port" },
            protocol = ProxyProtocol.SHADOWSOCKS,
            server = server,
            port = port,
            method = method,
            password = password,
            sni = sni,
            allowInsecure = allowInsecure,
            security = if (security.isNotEmpty()) security else "none",
            network = network,
            tag = tag,
            rawUri = trimmed
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        rawQuery.split("&").forEach { part ->
            val eq = part.indexOf('=')
            if (eq != -1) {
                val k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8.name()).lowercase()
                val v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8.name())
                map[k] = v
            }
        }
        return map
    }

    private fun decodeFragment(rawFragment: String?): String {
        if (rawFragment.isNullOrEmpty()) return ""
        return try {
            URLDecoder.decode(rawFragment, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            rawFragment
        }
    }

    private fun decodeBase64Safe(input: String): String {
        val clean = input.trim()
        val bytes = try {
            java.util.Base64.getDecoder().decode(clean)
        } catch (_: Exception) {
            try {
                java.util.Base64.getUrlDecoder().decode(clean)
            } catch (_: Exception) {
                try {
                    java.util.Base64.getMimeDecoder().decode(clean)
                } catch (_: Exception) {
                    android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                }
            }
        }
        return String(bytes, Charsets.UTF_8)
    }
}
