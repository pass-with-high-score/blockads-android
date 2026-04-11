package app.pwhs.blockadstv.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import timber.log.Timber
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class AppNameResolver(private val context: Context) {

    private val uidToAppNameCache = ConcurrentHashMap<Int, String>()
    private val uidToPackageNameCache = ConcurrentHashMap<Int, String>()

    data class AppIdentity(val appName: String, val packageName: String)

    fun resolveIdentity(sourcePort: Int, sourceIp: ByteArray, destIp: ByteArray, destPort: Int): AppIdentity {
        val uid = findUidForConnection(sourcePort, sourceIp, destIp, destPort)
        if (uid < 0) return AppIdentity("", "")
        return AppIdentity(
            appName = getAppNameForUid(uid),
            packageName = getPackageNameForUid(uid),
        )
    }

    private fun findUidForConnection(sourcePort: Int, sourceIp: ByteArray, destIp: ByteArray, destPort: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val local = InetSocketAddress(InetAddress.getByAddress(sourceIp), sourcePort)
                val remote = InetSocketAddress(InetAddress.getByAddress(destIp), destPort)
                cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
            } catch (e: Exception) {
                -1
            }
        }
        return -1
    }

    private fun getAppNameForUid(uid: Int): String {
        uidToAppNameCache[uid]?.let { return it }

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) {
            val name = when {
                uid == 0 -> "System (root)"
                uid == 1000 -> "Android System"
                uid < 10000 -> "System ($uid)"
                else -> ""
            }
            uidToAppNameCache[uid] = name
            return name
        }

        val appName = try {
            val appInfo = pm.getApplicationInfo(packages[0], 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packages[0]
        }

        uidToAppNameCache[uid] = appName
        return appName
    }

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
