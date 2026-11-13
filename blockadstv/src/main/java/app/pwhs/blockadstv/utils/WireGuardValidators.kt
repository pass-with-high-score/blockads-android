package app.pwhs.blockadstv.utils

object WireGuardValidators {

    private val base64Regex = Regex("^[A-Za-z0-9+/]{43}=$")

    fun key(value: String, fieldLabel: String, optional: Boolean = false): String? {
        if (value.isEmpty()) return if (optional) null else "$fieldLabel is required"
        return if (base64Regex.matches(value)) null else "$fieldLabel must be a 44-char base64 key"
    }

    fun cidr(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return "Empty"
        val slash = v.indexOf('/')
        if (slash < 0) return "Missing /prefix"
        val ip = v.substring(0, slash)
        val prefix = v.substring(slash + 1).toIntOrNull() ?: return "Invalid prefix"
        val isIPv6 = ip.contains(':')
        val maxPrefix = if (isIPv6) 128 else 32
        if (prefix < 0 || prefix > maxPrefix) return "Prefix out of range"
        return if (isValidIp(ip, isIPv6)) null else "Invalid IP"
    }

    fun allowedIp(value: String): String? {
        if (parseCidrToRoute(value) != null) return null
        val v = value.trim()
        if ('%' in v) return "Zone ids are not supported"
        return (if ('/' in v) cidr(v) else ip(v)) ?: "Not supported by WireGuard"
    }

    fun ip(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return "Empty"
        val isIPv6 = v.contains(':')
        return if (isValidIp(v, isIPv6)) null else "Invalid IP"
    }

    fun endpoint(value: String, optional: Boolean = false): String? {
        val v = value.trim()
        if (v.isEmpty()) return if (optional) null else "Endpoint is required"
        val hostEnd: Int
        val portStart: Int
        if (v.startsWith("[")) {
            val close = v.indexOf(']')
            if (close < 0) return "Missing ']' for IPv6 literal"
            if (v.getOrNull(close + 1) != ':') return "Missing port after ']'"
            hostEnd = close + 1
            portStart = close + 2
        } else {
            val lastColon = v.lastIndexOf(':')
            if (lastColon < 0) return "Missing :port"
            hostEnd = lastColon
            portStart = lastColon + 1
        }
        val host = v.substring(0, hostEnd).removePrefix("[").removeSuffix("]")
        val port = v.substring(portStart).toIntOrNull()
        if (host.isEmpty()) return "Empty host"
        if (port == null || port !in 1..65_535) return "Invalid port"
        return null
    }

    internal fun isValidIp(ip: String, isIPv6: Boolean): Boolean = try {
        if (isIPv6) {
            java.net.Inet6Address.getByName(ip) != null
        } else {
            val parts = ip.split('.')
            parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
    } catch (_: Exception) {
        false
    }
}
