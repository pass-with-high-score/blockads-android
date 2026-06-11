package app.pwhs.blockads.utils

/**
 * Stateless field validators for WireGuard config inputs. Each function
 * returns null when valid, or a short error message suitable for display
 * under a text field.
 */
object WireGuardValidators {

    private val base64Regex = Regex("^[A-Za-z0-9+/]{43}=$")

    /** WireGuard keys are 32 raw bytes encoded as 44-char base64 ending with '='. */
    fun key(value: String, fieldLabel: String, optional: Boolean = false): String? {
        if (value.isEmpty()) return if (optional) null else "$fieldLabel is required"
        return if (base64Regex.matches(value)) null else "$fieldLabel must be a 44-char base64 key"
    }

    /** "ip/prefix" — IPv4 or IPv6, prefix in valid range. */
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

    /** Bare IP address (no CIDR), used for DNS entries. */
    fun ip(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return "Empty"
        val isIPv6 = v.contains(':')
        return if (isValidIp(v, isIPv6)) null else "Invalid IP"
    }

    /** "host:port" — host can be hostname or IP (IPv6 in brackets), port 1-65535. */
    fun endpoint(value: String, optional: Boolean = false): String? {
        val v = value.trim()
        if (v.isEmpty()) return if (optional) null else "Endpoint is required"
        // IPv6 literal in brackets: [::1]:51820
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

    fun port(value: String, optional: Boolean = true): String? {
        val v = value.trim()
        if (v.isEmpty()) return if (optional) null else "Port is required"
        val p = v.toIntOrNull() ?: return "Invalid port"
        return if (p in 1..65_535) null else "Port out of range"
    }

    /** Persistent keepalive: 0 (disabled) up to 65535. */
    fun keepalive(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val n = v.toIntOrNull() ?: return "Invalid number"
        return if (n in 0..65_535) null else "Out of range (0-65535)"
    }

    private fun isValidIp(ip: String, isIPv6: Boolean): Boolean = try {
        if (isIPv6) java.net.Inet6Address.getByName(ip) != null
        else {
            // InetAddress.getByName resolves hostnames too; require dotted-quad shape first.
            val parts = ip.split('.')
            parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
    } catch (_: Exception) {
        false
    }
}
