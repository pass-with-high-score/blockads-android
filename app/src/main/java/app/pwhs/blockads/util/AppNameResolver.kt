package app.pwhs.blockads.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the source app name for a DNS query by mapping the connection
 * to the owning UID, then looking up the app label via PackageManager.
 *
 * On Android 10+ (API 29+): Uses the official ConnectivityManager.getConnectionOwnerUid() API.
 * On older versions: Falls back to parsing /proc/net/udp (and /proc/net/udp6).
 */
class AppNameResolver(private val context: Context) {

    companion object {
        private const val TAG = "AppNameResolver"
    }

    // Cache UID -> app name to avoid repeated PackageManager lookups
    private val uidToAppNameCache = ConcurrentHashMap<Int, String>()

    /**
     * Resolved app identity containing both display name and package name.
     */
    data class AppIdentity(val appName: String, val packageName: String)

    /**
     * Resolve the app name that owns the given DNS query connection.
     * Returns the app label (e.g. "Chrome") or empty string if not found.
     *
     * @param sourcePort  Local UDP source port of the DNS query
     * @param sourceIp    Source IP address bytes from the DNS packet
     * @param destIp      Destination IP address bytes from the DNS packet
     * @param destPort    Destination port (typically 53)
     */
    fun resolve(sourcePort: Int, sourceIp: ByteArray, destIp: ByteArray, destPort: Int): String {
        val uid = findUidForConnection(sourcePort, sourceIp, destIp, destPort)
        if (uid < 0) return ""
        return getAppNameForUid(uid)
    }

    /**
     * Resolve both app name and package name in a single UID lookup.
     * Avoids duplicate UID resolution on the hot path.
     */
    fun resolveIdentity(sourcePort: Int, sourceIp: ByteArray, destIp: ByteArray, destPort: Int): AppIdentity {
        val uid = findUidForConnection(sourcePort, sourceIp, destIp, destPort)
        if (uid < 0) return AppIdentity("", "")
        return AppIdentity(
            appName = getAppNameForUid(uid),
            packageName = getPackageNameForUid(uid)
        )
    }

    /**
     * Resolve the package name that owns the given DNS query connection.
     * Returns the package name (e.g. "com.android.chrome") or empty string if not found.
     */
    fun resolvePackageName(sourcePort: Int, sourceIp: ByteArray, destIp: ByteArray, destPort: Int): String {
        val uid = findUidForConnection(sourcePort, sourceIp, destIp, destPort)
        if (uid < 0) return ""
        return getPackageNameForUid(uid)
    }

    /**
     * Find the UID owning the connection.
     * Uses official API on Android 10+, falls back to /proc/net/udp on older versions.
     */
    private fun findUidForConnection(
        sourcePort: Int,
        sourceIp: ByteArray,
        destIp: ByteArray,
        destPort: Int
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val local = InetSocketAddress(InetAddress.getByAddress(sourceIp), sourcePort)
                val remote = InetSocketAddress(InetAddress.getByAddress(destIp), destPort)
                val uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
                if (uid >= 0) return uid
            } catch (e: Exception) {
                Log.d(TAG, "getConnectionOwnerUid failed, trying fallback: ${e.message}")
            }
        }

        // Fallback for API < 29 or if official API fails
        return findUidFromProcNet(sourcePort)
    }

    /**
     * Fallback: Look up /proc/net/udp and /proc/net/udp6 to find the UID owning the given port.
     * Used on Android versions before 10 (API < 29) where getConnectionOwnerUid() is unavailable.
     */
    private fun findUidFromProcNet(port: Int): Int {
        val hexPort = String.format("%04X", port)

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

    // Cache UID -> package name
    private val uidToPackageNameCache = ConcurrentHashMap<Int, String>()

    private fun getPackageNameForUid(uid: Int): String {
        uidToPackageNameCache[uid]?.let { return it }

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) return ""

        val packageName = packages[0]
        uidToPackageNameCache[uid] = packageName
        return packageName
    }
}
