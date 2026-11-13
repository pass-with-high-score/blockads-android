package app.pwhs.blockadstv.utils

import app.pwhs.blockadstv.data.entities.WireGuardConfig
import app.pwhs.blockadstv.data.entities.WireGuardInterface
import app.pwhs.blockadstv.data.entities.WireGuardPeer
import app.pwhs.blockadstv.data.entities.WireGuardProfile
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object WireGuardConfigParser {

    private enum class Section { NONE, INTERFACE, PEER }

    private val sectionRegex = Regex("""^\[(\w+)]$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): WireGuardConfig {
        var currentSection = Section.NONE

        var privateKey: String? = null
        val addresses = mutableListOf<String>()
        var listenPort: Int? = null
        val dns = mutableListOf<String>()
        var jc: Int? = null
        var jmin: Int? = null
        var jmax: Int? = null
        var s1: Int? = null
        var s2: Int? = null
        var s3: Int? = null
        var s4: Int? = null
        var h1: Long? = null
        var h2: Long? = null
        var h3: Long? = null
        var h4: Long? = null
        var headerProtectionKey: String? = null
        var randomTrailers: Boolean? = null
        var disableCookies: Boolean? = null
        var contentPaddingAddition: String? = null
        var rekeyAfterTime: String? = null
        var rekeyTimeout: String? = null
        var rejectAfterTime: String? = null
        var keepaliveTimeout: String? = null
        var maxHandshakeAttempts: String? = null
        var i1: String? = null
        var i2: String? = null
        var i3: String? = null
        var i4: String? = null
        var i5: String? = null

        data class PeerBuilder(
            var publicKey: String? = null,
            var presharedKey: String? = null,
            var endpoint: String? = null,
            val allowedIPs: MutableList<String> = mutableListOf(),
            var persistentKeepalive: Int? = null,
        )

        val peers = mutableListOf<PeerBuilder>()
        var currentPeer: PeerBuilder? = null

        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val sectionMatch = sectionRegex.find(trimmed)
            if (sectionMatch != null) {
                when (sectionMatch.groupValues[1].lowercase()) {
                    "interface" -> currentSection = Section.INTERFACE
                    "peer" -> {
                        currentPeer?.let { peers.add(it) }
                        currentPeer = PeerBuilder()
                        currentSection = Section.PEER
                    }
                    else -> currentSection = Section.NONE
                }
                continue
            }

            val eqIndex = trimmed.indexOf('=')
            if (eqIndex == -1) continue

            val key = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()

            when (currentSection) {
                Section.INTERFACE -> when (key.lowercase()) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses.addAll(
                        value.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    )
                    "listenport" -> listenPort = value.toIntOrNull()
                    "dns" -> dns.addAll(
                        value.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    )
                    "jc" -> jc = value.toIntOrNull()
                    "jmin" -> jmin = value.toIntOrNull()
                    "jmax" -> jmax = value.toIntOrNull()
                    "s1" -> s1 = value.toIntOrNull()
                    "s2" -> s2 = value.toIntOrNull()
                    "s3" -> s3 = value.toIntOrNull()
                    "s4" -> s4 = value.toIntOrNull()
                    "h1" -> h1 = value.toLongOrNull()
                    "h2" -> h2 = value.toLongOrNull()
                    "h3" -> h3 = value.toLongOrNull()
                    "h4" -> h4 = value.toLongOrNull()
                    "headerprotectionkey", "header_protection_key" -> headerProtectionKey = value
                    "randomtrailers", "random_trailers" -> randomTrailers = parseAmneziaBool(value)
                    "disablecookies", "disable_cookies" -> disableCookies = parseAmneziaBool(value)
                    "contentpaddingaddition", "content_padding_addition" -> contentPaddingAddition = value
                    "rekeyaftertime", "rekey_after_time" -> rekeyAfterTime = value
                    "rekeytimeout", "rekey_timeout" -> rekeyTimeout = value
                    "rejectaftertime", "reject_after_time" -> rejectAfterTime = value
                    "keepalivetimeout", "keepalive_timeout" -> keepaliveTimeout = value
                    "maxhandshakeattempts", "max_handshake_attempts" -> maxHandshakeAttempts = value
                    "i1" -> i1 = value
                    "i2" -> i2 = value
                    "i3" -> i3 = value
                    "i4" -> i4 = value
                    "i5" -> i5 = value
                }
                Section.PEER -> {
                    val peer = currentPeer ?: continue
                    when (key.lowercase()) {
                        "publickey" -> peer.publicKey = value
                        "presharedkey" -> peer.presharedKey = value
                        "endpoint" -> peer.endpoint = value
                        "allowedips" -> peer.allowedIPs.addAll(
                            value.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        )
                        "persistentkeepalive" -> peer.persistentKeepalive = value.toIntOrNull()
                    }
                }
                Section.NONE -> Unit
            }
        }

        currentPeer?.let { peers.add(it) }

        requireNotNull(privateKey) { "Missing required field: PrivateKey in [Interface]" }
        require(addresses.isNotEmpty()) { "Missing required field: Address in [Interface]" }

        val parsedPeers = peers.map { pb ->
            requireNotNull(pb.publicKey) { "Missing required field: PublicKey in [Peer]" }
            WireGuardPeer(
                publicKey = pb.publicKey!!,
                presharedKey = pb.presharedKey,
                endpoint = pb.endpoint,
                allowedIPs = pb.allowedIPs.toList(),
                persistentKeepalive = pb.persistentKeepalive,
            )
        }

        return WireGuardConfig(
            interfaceConfig = WireGuardInterface(
                privateKey = privateKey,
                address = addresses.toList(),
                listenPort = listenPort,
                dns = dns.toList(),
                jc = jc,
                jmin = jmin,
                jmax = jmax,
                s1 = s1,
                s2 = s2,
                s3 = s3,
                s4 = s4,
                h1 = h1,
                h2 = h2,
                h3 = h3,
                h4 = h4,
                headerProtectionKey = headerProtectionKey,
                randomTrailers = randomTrailers,
                disableCookies = disableCookies,
                contentPaddingAddition = contentPaddingAddition,
                rekeyAfterTime = rekeyAfterTime,
                rekeyTimeout = rekeyTimeout,
                rejectAfterTime = rejectAfterTime,
                keepaliveTimeout = keepaliveTimeout,
                maxHandshakeAttempts = maxHandshakeAttempts,
                i1 = i1,
                i2 = i2,
                i3 = i3,
                i4 = i4,
                i5 = i5,
            ),
            peers = parsedPeers,
        )
    }

    fun parseUri(rawUri: String): WireGuardProfile {
        val trimmed = rawUri.trim()
        val uri = URI(trimmed)
        require(uri.scheme.equals("wireguard", ignoreCase = true)) { "Invalid scheme: ${uri.scheme}" }

        val rawUserInfo = uri.rawUserInfo ?: throw IllegalArgumentException("Missing private key in userInfo")
        val privateKey = URLDecoder.decode(rawUserInfo, StandardCharsets.UTF_8.name())

        val host = uri.host ?: throw IllegalArgumentException("Missing host in wireguard URI")
        val port = if (uri.port != -1) uri.port else 51820
        val endpoint = "$host:$port"

        val queryParams = mutableMapOf<String, String>()
        uri.rawQuery?.split("&")?.forEach { part ->
            val eq = part.indexOf('=')
            if (eq != -1) {
                val k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8.name()).lowercase()
                val v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8.name())
                queryParams[k] = v
            }
        }

        val addresses = queryParams["address"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("10.0.0.2/32")
        val publicKey = queryParams["publickey"] ?: throw IllegalArgumentException("Missing publickey query param")
        val presharedKey = queryParams["presharedkey"]
        val allowedIps = queryParams["allowedips"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("0.0.0.0/0", "::/0")
        val dns = queryParams["dns"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val keepalive = queryParams["persistentkeepalive"]?.toIntOrNull()

        val rawFragment = uri.rawFragment
        val name = if (!rawFragment.isNullOrEmpty()) {
            URLDecoder.decode(rawFragment, StandardCharsets.UTF_8.name())
        } else {
            "WireGuard-$host"
        }

        val config = WireGuardConfig(
            interfaceConfig = WireGuardInterface(
                privateKey = privateKey,
                address = addresses,
                dns = dns,
            ),
            peers = listOf(
                WireGuardPeer(
                    publicKey = publicKey,
                    presharedKey = presharedKey,
                    endpoint = endpoint,
                    allowedIPs = allowedIps,
                    persistentKeepalive = keepalive,
                ),
            ),
        )

        return WireGuardProfile(
            id = WireGuardProfile.newId(),
            name = name,
            config = config,
        )
    }

    fun parseVpnUri(rawUri: String): WireGuardProfile {
        val trimmed = rawUri.trim()
        val base64Content = when {
            trimmed.startsWith("vpn://", ignoreCase = true) -> trimmed.substring(6)
            trimmed.startsWith("awg://", ignoreCase = true) -> trimmed.substring(6)
            else -> trimmed
        }.substringBefore("#")

        val decoded = decodeBase64Safe(base64Content)
        val fragment = trimmed.substringAfter("#", "")
        val profileName = if (fragment.isNotEmpty()) {
            URLDecoder.decode(fragment, StandardCharsets.UTF_8.name())
        } else {
            val commentName = decoded.lines()
                .firstOrNull { it.trim().startsWith("#") && !it.trim().startsWith("#[") }
                ?.removePrefix("#")?.trim()
            commentName?.ifEmpty { "AmneziaWG" } ?: "AmneziaWG"
        }

        val config = parse(decoded)
        return WireGuardProfile(
            id = WireGuardProfile.newId(),
            name = profileName,
            config = config,
        )
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
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun parseAmneziaBool(v: String): Boolean =
        v.equals("on", ignoreCase = true) ||
            v.equals("true", ignoreCase = true) ||
            v.equals("yes", ignoreCase = true) ||
            v.equals("1") ||
            v.equals("enabled", ignoreCase = true)
}
