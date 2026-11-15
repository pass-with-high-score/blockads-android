package app.pwhs.blockads.service

import android.content.Context
import android.content.pm.PackageManager
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.entities.FirewallRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

data class PendingFirewallPrompt(
    val packageName: String,
    val appLabel: String,
    val timestamp: Long
)

enum class FirewallPromptDecision { ALWAYS_ALLOW, ALLOW_ONCE, BLOCK }

/**
 * Tracks apps that requested network access without a remembered decision,
 * notifies the user, and applies their Allow/Block answer.
 */
class FirewallPromptManager(
    private val context: Context,
    private val firewallRuleDao: FirewallRuleDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifier = FirewallPromptNotifier(context)

    private val _pendingPrompts = MutableStateFlow<List<PendingFirewallPrompt>>(emptyList())
    val pendingPrompts: StateFlow<List<PendingFirewallPrompt>> = _pendingPrompts.asStateFlow()

    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()
    private val sessionAllowed = ConcurrentHashMap.newKeySet<String>()
    private val promptableCache = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var activeFirewall: FirewallManager? = null

    fun attach(manager: FirewallManager) {
        activeFirewall = manager
    }

    /**
     * Called on the engine's hot path for an app with no stored rule.
     * @return true if the request must be held back until the user decides
     */
    fun onUndecidedRequest(packageName: String): Boolean {
        if (packageName in sessionAllowed) return false
        val promptable = promptableCache.getOrPut(packageName) { isPromptable(packageName) }
        if (!promptable) return false
        if (pendingKeys.add(packageName)) enqueue(packageName)
        return true
    }

    fun decide(packageName: String, decision: FirewallPromptDecision) {
        scope.launch {
            try {
                when (decision) {
                    FirewallPromptDecision.ALWAYS_ALLOW -> persist(packageName, blocked = false)
                    FirewallPromptDecision.BLOCK -> persist(packageName, blocked = true)
                    FirewallPromptDecision.ALLOW_ONCE -> sessionAllowed.add(packageName)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply firewall decision for $packageName")
            }
            resolve(packageName)
        }
    }

    /** Drops all pending prompts and one-time allowances (e.g. when prompt mode is turned off). */
    fun clearAll() {
        _pendingPrompts.value.forEach { notifier.cancel(it.packageName) }
        pendingKeys.clear()
        sessionAllowed.clear()
        _pendingPrompts.value = emptyList()
    }

    private fun enqueue(packageName: String) {
        scope.launch {
            val prompt = PendingFirewallPrompt(
                packageName = packageName,
                appLabel = resolveLabel(packageName),
                timestamp = System.currentTimeMillis()
            )
            _pendingPrompts.update { current -> current + prompt }
            notifier.show(prompt)
        }
    }

    private suspend fun persist(packageName: String, blocked: Boolean) {
        val existing = firewallRuleDao.getByPackageName(packageName)
        val rule = existing?.copy(isEnabled = blocked)
            ?: FirewallRule(packageName = packageName, isEnabled = blocked)
        firewallRuleDao.insert(rule)
        activeFirewall?.applyRule(rule)
    }

    private fun resolve(packageName: String) {
        pendingKeys.remove(packageName)
        _pendingPrompts.update { current -> current.filterNot { it.packageName == packageName } }
        notifier.cancel(packageName)
    }

    private fun isPromptable(packageName: String): Boolean {
        if (packageName == context.packageName) return false
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (_: Exception) {
        packageName
    }
}
