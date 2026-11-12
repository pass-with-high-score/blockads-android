package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FirewallRule
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

enum class FirewallDecision { BLOCKED, ALLOWED, UNDECIDED }

/**
 * Manages firewall rules and determines whether a given app's traffic
 * should be blocked based on network type (Wi-Fi/Mobile), schedule, and
 * (optionally) an interactive per-app permission prompt.
 */
class FirewallManager(
    private val context: Context,
    private val firewallRuleDao: FirewallRuleDao,
    private val promptEnabled: Boolean = false,
    private val promptManager: FirewallPromptManager? = null
) {

    // isEnabled = true -> blocked, isEnabled = false -> explicitly allowed by the user
    private val rulesCache = ConcurrentHashMap<String, FirewallRule>()

    init {
        if (promptEnabled) promptManager?.attach(this)
    }

    /** Load all firewall rules (blocked and explicitly allowed) into memory. */
    suspend fun loadRules() {
        rulesCache.clear()
        for (rule in firewallRuleDao.getAllSnapshot()) {
            rulesCache[rule.packageName] = rule
        }
        Timber.d("Loaded ${rulesCache.size} firewall rules (prompt=$promptEnabled)")
    }

    /** True when the engine must consult [shouldBlock]: a blocking rule exists or prompt mode is on. */
    fun hasActiveRules(): Boolean = promptEnabled || rulesCache.values.any { it.isEnabled }

    fun decisionFor(packageName: String): FirewallDecision {
        val rule = rulesCache[packageName] ?: return FirewallDecision.UNDECIDED
        return if (rule.isEnabled) FirewallDecision.BLOCKED else FirewallDecision.ALLOWED
    }

    /** Applies a user decision in memory so it takes effect without a VPN restart. */
    fun applyRule(rule: FirewallRule) {
        rulesCache[rule.packageName] = rule
    }

    /**
     * Check if a given app (by package name) should be blocked right now.
     * @return true if the app should be blocked
     */
    fun shouldBlock(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val rule = rulesCache[packageName] ?: return shouldBlockUndecided(packageName)
        if (!rule.isEnabled) return false
        if (!isBlockedOnCurrentNetwork(rule)) return false
        if (rule.scheduleEnabled && !isWithinSchedule(rule)) return false
        return true
    }

    // Undecided apps are held back until the user answers the prompt.
    private fun shouldBlockUndecided(packageName: String): Boolean {
        if (!promptEnabled) return false
        return promptManager?.onUndecidedRequest(packageName) ?: false
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
        return isWithinSchedule(rule, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
    }

    companion object {
        /** Builds a manager wired to the interactive prompt preference and loads its rules. */
        suspend fun create(
            context: Context,
            firewallRuleDao: FirewallRuleDao,
            appPrefs: AppPreferences,
            promptManager: FirewallPromptManager? = defaultPromptManager()
        ): FirewallManager {
            val promptEnabled = runCatching { appPrefs.firewallPromptEnabled.first() }.getOrDefault(false)
            return FirewallManager(context, firewallRuleDao, promptEnabled, promptManager).also { it.loadRules() }
        }

        // Null when Koin is not started (e.g. unit tests); prompt mode then degrades to allow.
        private fun defaultPromptManager(): FirewallPromptManager? =
            runCatching { GlobalContext.getOrNull()?.getOrNull<FirewallPromptManager>() }.getOrNull()

        internal fun isWithinSchedule(rule: FirewallRule, currentTime: Int): Boolean {
            val startTime = rule.scheduleStartHour * 60 + rule.scheduleStartMinute
            val endTime = rule.scheduleEndHour * 60 + rule.scheduleEndMinute

            return if (startTime <= endTime) {
                // Same day schedule (e.g., 08:00 - 17:00)
                currentTime in startTime..endTime
            } else {
                // Overnight schedule (e.g., 22:00 - 06:00)
                currentTime !in (endTime + 1)..<startTime
            }
        }
    }
}
