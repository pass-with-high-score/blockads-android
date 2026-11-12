package app.pwhs.blockads.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Handles the Allow / Block action buttons of the firewall prompt notification. */
class FirewallPromptReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        const val ACTION_ALLOW = "app.pwhs.blockads.FIREWALL_PROMPT_ALLOW"
        const val ACTION_BLOCK = "app.pwhs.blockads.FIREWALL_PROMPT_BLOCK"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private val promptManager: FirewallPromptManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val decision = when (intent.action) {
            ACTION_ALLOW -> FirewallPromptDecision.ALWAYS_ALLOW
            ACTION_BLOCK -> FirewallPromptDecision.BLOCK
            else -> return
        }
        promptManager.decide(packageName, decision)
    }
}
