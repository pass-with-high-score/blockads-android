package app.pwhs.blockads.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
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
    // Cache UID -> app name to avoid repeated PackageManager lookups
    private val uidToAppNameCache = ConcurrentHashMap<Int, String>()

    // Our own app's UID. Excluded from background snapshots: in Root
    // Proxy mode the engine forwards upstream DNS via UDP sockets that
    // belong to this UID, and on Android 10+ SELinux limits the
    // unprivileged /proc/net read to those sockets only — including
    // them would mask real foreign sockets via first-writer-wins.
    private val ownUid: Int = context.applicationInfo.uid

    // Background snapshot of /proc/net/udp{,6} maintained by [snapshotJob].
    // Populated only when [startSnapshotter] is active (Root Proxy mode).
    // DNS query lookups read this map without blocking — no inline shell.
    @Volatile private var procNetSnapshot: Map<Int, Int> = emptyMap()
    private var snapshotJob: Job? = null

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
     * Find the UID owning the connection.
     * Uses official API on Android 10+, falls back to /proc/net/udp on older versions
     * or when the official API returns UID_UNKNOWN (Root Proxy mode: iptables REDIRECT
     * rewrites the 5-tuple so getConnectionOwnerUid can't see the original socket pair).
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
                Timber.d("getConnectionOwnerUid failed, trying fallback: ${e.message}")
            }
        }

        return findUidFromProcNet(sourcePort)
    }

    /**
     * Start a background coroutine that snapshots /proc/net/udp{,6} via
     * the persistent root shell every [SNAPSHOT_INTERVAL_MS]. Should be
     * called when Root Proxy mode starts. The snapshot is consulted
     * first by [findUidFromProcNet], so DNS query lookups never block on
     * a shell call — they just read an in-memory map. This avoids issue
     * #130 (per-query shell spawns serialising DNS threads) while still
     * catching short-lived DNS sockets that close before an on-demand
     * read could see them.
     */
    fun startSnapshotter(scope: CoroutineScope) {
        snapshotJob?.cancel()
        snapshotJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val map = readProcNetViaRoot()
                    if (map.isNotEmpty()) {
                        procNetSnapshot = map
                    }
                } catch (e: Exception) {
                    Timber.w(e, "/proc/net snapshot read failed")
                }
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
        Timber.d("AppNameResolver snapshotter started (interval=${SNAPSHOT_INTERVAL_MS}ms)")
    }

    /**
     * Stop the background snapshotter and clear the snapshot. Call when
     * Root Proxy mode stops so we don't keep spawning root shell calls.
     */
    fun stopSnapshotter() {
        snapshotJob?.cancel()
        snapshotJob = null
        procNetSnapshot = emptyMap()
        Timber.d("AppNameResolver snapshotter stopped")
    }

    /**
     * Look up /proc/net/udp and /proc/net/udp6 to find the UID owning the given port.
     * Used on API < 29 and on Android 10+ in Root Proxy mode.
     */
    private fun findUidFromProcNet(port: Int): Int {
        // Hot path (Root Proxy mode): read from the background snapshot.
        procNetSnapshot[port]?.let { return it }

        val hexPort = String.format("%04X", port)

        // Unprivileged read fallback (works for own app's sockets and on older Android)
        findUidInProcFile("/proc/net/udp", hexPort)?.let { return it }
        findUidInProcFile("/proc/net/udp6", hexPort)?.let { return it }

        // Root-privileged read: on Android 10+, SELinux restricts /proc/net/udp
        // to only show the app's own sockets. In Root Proxy mode the snapshotter
        // already covers this path; this branch is the slow-path safety net.
        findUidFromProcNetRoot(hexPort)?.let { return it }

        return -1
    }

    /**
     * Build a port → UID map from the full /proc/net/udp{,6} table via
     * the libsu persistent root shell. Sockets owned by our own app
     * are filtered out (they'd otherwise mask foreign sockets via
     * first-writer-wins in the map and would never be the originator
     * of a redirected query).
     */
    private fun readProcNetViaRoot(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        val result = com.topjohnwu.superuser.Shell.cmd(
            "cat /proc/net/udp /proc/net/udp6 2>/dev/null"
        ).exec()
        if (!result.isSuccess) return map
        for (line in result.out) {
            try {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 8) continue
                val localAddress = parts[1]
                val colonIndex = localAddress.lastIndexOf(':')
                if (colonIndex < 0) continue
                val port = localAddress.substring(colonIndex + 1).toInt(16)
                val uid = parts[7].toIntOrNull() ?: continue
                if (uid == ownUid) continue
                map.putIfAbsent(port, uid)
            } catch (_: Exception) {
                // Skip header / malformed lines
            }
        }
        return map
    }

    /**
     * Read /proc/net/udp and /proc/net/udp6 via root shell (libsu).
     * This bypasses SELinux restrictions on Android 10+ that prevent
     * normal apps from seeing other apps' socket entries.
     */
    private fun findUidFromProcNetRoot(hexPort: String): Int? {
        try {
            val result = com.topjohnwu.superuser.Shell.cmd(
                "cat /proc/net/udp /proc/net/udp6 2>/dev/null"
            ).exec()
            if (!result.isSuccess) return null

            for (line in result.out) {
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
            }
        } catch (e: Exception) {
            Timber.d("Root /proc/net lookup failed: ${e.message}")
        }
        return null
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
            Timber.d("Cannot read $path: ${e.message}")
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
            Timber.e("Package not found for UID $uid: ${e.message}")
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

    private companion object {
        // 100ms catches most DNS sockets (typically 100–500ms lifetime)
        // while keeping the persistent-shell call rate at ~10/sec, which
        // is well under the per-query rate that caused issue #130.
        const val SNAPSHOT_INTERVAL_MS = 100L
    }
}
