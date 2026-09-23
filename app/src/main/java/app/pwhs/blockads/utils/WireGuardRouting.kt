package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.data.entities.WireGuardProfile

/** A single VPN route derived from a WireGuard peer's AllowedIPs. */
data class WgRoute(val address: String, val prefixLength: Int, val isIpv6: Boolean)

/** Routes to install into the TUN, plus whether this config is a full tunnel. */
data class WgRouting(
    val routes: List<WgRoute>,
    val isFullTunnel: Boolean,
    /** The peers with allowedIPs rewritten to the canonical entries behind [routes], for the Go engine. */
    val peers: List<WireGuardPeer> = emptyList(),
    /** Non-blank AllowedIPs entries that failed to parse and were dropped. */
    val skippedEntries: List<String> = emptyList(),
) {
    /** Why this config must not connect, or null if it is usable. */
    val issue: WgConfigIssue?
        get() = when {
            skippedEntries.isNotEmpty() -> WgConfigIssue.MalformedAllowedIps(skippedEntries)
            routes.isEmpty() -> WgConfigIssue.NoAllowedIps
            else -> null
        }

    /** [routes] plus `::/0` for a v4-only full tunnel, so IPv6 enters the TUN and is dropped instead of bypassing it. */
    val tunRoutes: List<WgRoute>
        get() = if (isFullTunnel && routes.none { it.isIpv6 }) routes + WgRoute("::", 0, true) else routes
}

/** Why this profile must not connect, or null if it can. */
val WireGuardProfile.configIssue: WgConfigIssue? get() = wgRoutingFromPeers(config.peers).issue

sealed interface WgConfigIssue {
    data object NoAllowedIps : WgConfigIssue
    data class MalformedAllowedIps(val entries: List<String>) : WgConfigIssue
}

/** TUN routes from the union of the peers' AllowedIPs, as wg-quick does; deduplicated, order preserved. */
fun wgRoutingFromPeers(peers: List<WireGuardPeer>): WgRouting {
    val routes = LinkedHashSet<WgRoute>()
    val skipped = mutableListOf<String>()
    val normalizedPeers = peers.map { peer ->
        val canonical = LinkedHashSet<String>()
        for (raw in peer.allowedIPs) {
            val route = parseCidrToRoute(raw)
            if (route == null) {
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) skipped.add(trimmed) // blank != malformed
                continue
            }
            routes.add(route)
            canonical.add("${route.address}/${route.prefixLength}")
        }
        peer.copy(allowedIPs = canonical.toList())
    }
    val isFull = routes.any { !it.isIpv6 && it.address == "0.0.0.0" && it.prefixLength == 0 }
    return WgRouting(routes.toList(), isFullTunnel = isFull, peers = normalizedPeers, skippedEntries = skipped)
}

/**
 * Parses "addr" or "addr/prefix" into a canonical [WgRoute]; null if malformed.
 * Host bits are masked because Android's addRoute rejects them, though wg-quick accepts `10.13.13.1/24`.
 */
internal fun parseCidrToRoute(raw: String): WgRoute? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val slash = trimmed.indexOf('/')
    val addr = if (slash >= 0) trimmed.substring(0, slash) else trimmed
    if (addr.isEmpty()) return null

    val isIpv6 = addr.contains(':')
    // wireguard-go rejects zone ids and never matches v4 against a 4-in-6 prefix.
    if (isIpv6 && (addr.contains('%') || addr.contains('.'))) return null
    if (!WireGuardValidators.isValidIp(addr, isIpv6)) return null

    val max = if (isIpv6) 128 else 32
    val prefix = if (slash >= 0) {
        val p = trimmed.substring(slash + 1).toIntOrNull() ?: return null
        if (p < 0 || p > max) return null
        p
    } else {
        max
    }

    val bytes = addressToBytes(addr, isIpv6) ?: return null
    maskInPlace(bytes, prefix)
    val network = if (isIpv6) formatIpv6(bytes) else formatIpv4(bytes)
    return WgRoute(network, prefix, isIpv6)
}

/** Raw address bytes, or null if the parsed family doesn't match [isIpv6]. */
private fun addressToBytes(addr: String, isIpv6: Boolean): ByteArray? {
    val bytes = if (isIpv6) {
        // getByName returns 4 bytes for a dotless 4-in-6 form like "::ffff:c0a8:101"; the size check drops it.
        try {
            java.net.InetAddress.getByName(addr).address
        } catch (_: Exception) {
            return null
        }
    } else {
        val parts = addr.split('.')
        if (parts.size != 4) return null
        ByteArray(4) { (parts[it].toIntOrNull() ?: return null).toByte() }
    }
    return if (bytes.size == (if (isIpv6) 16 else 4)) bytes else null
}

/** Zeroes every bit past [prefix]. */
private fun maskInPlace(bytes: ByteArray, prefix: Int) {
    for (i in bytes.indices) {
        val keep = (prefix - i * 8).coerceIn(0, 8)
        val mask = if (keep == 0) 0 else (0xff shl (8 - keep)) and 0xff
        bytes[i] = (bytes[i].toInt() and mask).toByte()
    }
}

private fun formatIpv4(bytes: ByteArray): String =
    bytes.joinToString(".") { (it.toInt() and 0xff).toString() }

/** RFC 5952 form: lowercase hex, longest run of >=2 zero groups collapsed to "::". */
private fun formatIpv6(bytes: ByteArray): String {
    val groups = IntArray(8) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
    var runStart = -1
    var runLen = 0
    var i = 0
    while (i < groups.size) {
        if (groups[i] != 0) {
            i++
            continue
        }
        var j = i
        while (j < groups.size && groups[j] == 0) j++
        if (j - i > runLen) {
            runLen = j - i
            runStart = i
        }
        i = j
    }
    if (runLen < 2) runStart = -1

    val sb = StringBuilder()
    i = 0
    while (i < groups.size) {
        if (i == runStart) {
            sb.append("::")
            i += runLen
            continue
        }
        if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
        sb.append(groups[i].toString(16))
        i++
    }
    return if (sb.isEmpty()) "::" else sb.toString()
}
