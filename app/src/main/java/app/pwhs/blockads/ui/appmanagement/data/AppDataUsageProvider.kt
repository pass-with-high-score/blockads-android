package app.pwhs.blockads.ui.appmanagement.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import java.util.Calendar

data class UidTrafficStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val wifiRxBytes: Long = 0L,
    val wifiTxBytes: Long = 0L,
    val mobileRxBytes: Long = 0L,
    val mobileTxBytes: Long = 0L
)

object AppDataUsageProvider {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun loadUsageByUid(
        context: Context,
        uids: List<Int>,
        timeRange: AppUsageTimeRange
    ): Pair<Boolean, Map<Int, UidTrafficStats>> {
        if (hasUsageStatsPermission(context)) {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            if (nsm != null) {
                val endTime = System.currentTimeMillis()
                val startTime = computeStartTime(endTime, timeRange)
                val combined = HashMap<Int, UidTrafficStats>()
                queryNetworkType(nsm, ConnectivityManager.TYPE_WIFI, startTime, endTime, isWifi = true, combined)
                queryNetworkType(nsm, ConnectivityManager.TYPE_MOBILE, startTime, endTime, isWifi = false, combined)
                return true to combined
            }
        }

        // Fallback: kernel per-UID counters since device boot (requires zero special permissions).
        val fallback = HashMap<Int, UidTrafficStats>(uids.size)
        for (uid in uids) {
            if (uid < 0) continue
            val rx = TrafficStats.getUidRxBytes(uid).let { if (it > 0L) it else 0L }
            val tx = TrafficStats.getUidTxBytes(uid).let { if (it > 0L) it else 0L }
            if (rx > 0L || tx > 0L) {
                fallback[uid] = UidTrafficStats(rxBytes = rx, txBytes = tx)
            }
        }
        return false to fallback
    }

    private fun computeStartTime(endTime: Long, range: AppUsageTimeRange): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = endTime
            if (range == AppUsageTimeRange.TODAY) {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            } else {
                add(Calendar.DAY_OF_YEAR, -range.days)
            }
        }
        return cal.timeInMillis
    }

    @Suppress("DEPRECATION")
    private fun queryNetworkType(
        nsm: NetworkStatsManager,
        networkType: Int,
        startTime: Long,
        endTime: Long,
        isWifi: Boolean,
        out: MutableMap<Int, UidTrafficStats>
    ) {
        runCatching {
            val stats = nsm.querySummary(networkType, null, startTime, endTime)
            val bucket = NetworkStats.Bucket()
            stats.use {
                while (it.hasNextBucket()) {
                    it.getNextBucket(bucket)
                    val uid = bucket.uid
                    val rx = bucket.rxBytes.coerceAtLeast(0L)
                    val tx = bucket.txBytes.coerceAtLeast(0L)
                    if (rx == 0L && tx == 0L) continue
                    val prev = out[uid] ?: UidTrafficStats()
                    out[uid] = if (isWifi) {
                        prev.copy(
                            rxBytes = prev.rxBytes + rx,
                            txBytes = prev.txBytes + tx,
                            wifiRxBytes = prev.wifiRxBytes + rx,
                            wifiTxBytes = prev.wifiTxBytes + tx
                        )
                    } else {
                        prev.copy(
                            rxBytes = prev.rxBytes + rx,
                            txBytes = prev.txBytes + tx,
                            mobileRxBytes = prev.mobileRxBytes + rx,
                            mobileTxBytes = prev.mobileTxBytes + tx
                        )
                    }
                }
            }
        }
    }
}
