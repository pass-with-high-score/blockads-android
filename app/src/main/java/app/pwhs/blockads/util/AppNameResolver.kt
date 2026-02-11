package app.pwhs.blockads.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the source app name for a DNS query by looking up the source port
 * in /proc/net/udp (and /proc/net/udp6) to find the owning UID,
 * then mapping that UID to an app label via PackageManager.
 */
class AppNameResolver(private val context: Context) {

    companion object {
        private const val TAG = "AppNameResolver"
    }

    // Cache UID -> app name to avoid repeated PackageManager lookups
    private val uidToAppNameCache = ConcurrentHashMap<Int, String>()

    /**
     * Resolve the app name that owns the given local UDP source port.
     * Returns the app label (e.g. "Chrome") or empty string if not found.
     */
    fun resolve(sourcePort: Int): String {
        val uid = findUidForPort(sourcePort)
        if (uid < 0) return ""
        return getAppNameForUid(uid)
    }

    /**
     * Look up /proc/net/udp and /proc/net/udp6 to find the UID owning the given local port.
     * Returns -1 if not found.
     *
     * Format of /proc/net/udp:
     *   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
     *    0: 0100007F:0035 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 12345
     *
     * local_address is hex IP:port. We match the port portion.
     */
    private fun findUidForPort(port: Int): Int {
        val hexPort = String.format("%04X", port)

        // Try IPv4 first, then IPv6
        findUidInProcFile("/proc/net/udp", hexPort)?.let { return it }
        findUidInProcFile("/proc/net/udp6", hexPort)?.let { return it }

        return -1
    }

    private fun findUidInProcFile(path: String, hexPort: String): Int? {
        try {
            File(path).bufferedReader(Charsets.UTF_8).use { reader ->
                // Skip header line
                reader.readLine() ?: return null

                var line = reader.readLine()
                while (line != null) {
                    try {
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 8) {
                            val localAddress = parts[1]
                            val colonIndex = localAddress.lastIndexOf(':')
                            if (colonIndex >= 0) {
                                val localPort = localAddress.substring(colonIndex + 1)
                                if (localPort.equals(hexPort, ignoreCase = true)) {
                                    return parts[7].toIntOrNull()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Skip malformed lines
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read $path: ${e.message}")
        }
        return null
    }

    private fun getAppNameForUid(uid: Int): String {
        uidToAppNameCache[uid]?.let { return it }

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) {
            // System UIDs
            val name = when {
                uid == 0 -> "System (root)"
                uid == 1000 -> "Android System"
                uid < 10000 -> "System ($uid)"
                else -> ""
            }
            uidToAppNameCache[uid] = name
            return name
        }

        // Use the first package's app label
        val appName = try {
            val appInfo = pm.getApplicationInfo(packages[0], 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packages[0]
        }

        uidToAppNameCache[uid] = appName
        return appName
    }
}
