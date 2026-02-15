package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import app.pwhs.blockads.data.FirewallRule
import app.pwhs.blockads.data.FirewallRuleDao
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages firewall rules and determines whether a given app's DNS queries
 * should be blocked based on network type (Wi-Fi/Mobile) and schedule.
 */
class FirewallManager(
    private val context: Context,
    private val firewallRuleDao: FirewallRuleDao
) {
    companion object {
        private const val TAG = "FirewallManager"
    }

    // In-memory cache of enabled firewall rules, keyed by package name
    private val rulesCache = ConcurrentHashMap<String, FirewallRule>()

    /**
     * Load all enabled firewall rules into memory cache.
     * Should be called when VPN starts.
     */
    suspend fun loadRules() {
        rulesCache.clear()
        val rules = firewallRuleDao.getEnabledRules()
        for (rule in rules) {
            rulesCache[rule.packageName] = rule
        }
        Log.d(TAG, "Loaded ${rulesCache.size} firewall rules")
    }

    /**
     * Check if a given app (by package name) should be blocked right now.
     * Considers: rule enabled, network type (Wi-Fi/Mobile), and schedule.
     *
     * @return true if the app should be blocked
     */
    fun shouldBlock(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val rule = rulesCache[packageName] ?: return false
        if (!rule.isEnabled) return false

        // Check network type
        if (!isBlockedOnCurrentNetwork(rule)) return false

        // Check schedule
        if (rule.scheduleEnabled && !isWithinSchedule(rule)) return false

        return true
    }

    private fun isBlockedOnCurrentNetwork(rule: FirewallRule): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return true // No network → block
        val caps = cm.getNetworkCapabilities(network) ?: return true

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return when {
            isWifi -> rule.blockWifi
            isCellular -> rule.blockMobileData
            else -> rule.blockWifi || rule.blockMobileData // Other transports: block if either is set
        }
    }

    private fun isWithinSchedule(rule: FirewallRule): Boolean {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)
        val currentTime = currentHour * 60 + currentMinute

        val startTime = rule.scheduleStartHour * 60 + rule.scheduleStartMinute
        val endTime = rule.scheduleEndHour * 60 + rule.scheduleEndMinute

        return if (startTime <= endTime) {
            // Same day schedule (e.g., 08:00 - 17:00)
            currentTime in startTime..endTime
        } else {
            // Overnight schedule (e.g., 22:00 - 06:00)
            currentTime >= startTime || currentTime <= endTime
        }
    }
}
