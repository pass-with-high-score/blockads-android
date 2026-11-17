package app.pwhs.blockads.ui.firewall.prompt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.service.FirewallPromptDecision
import app.pwhs.blockads.service.FirewallPromptManager
import app.pwhs.blockads.service.PendingFirewallPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class FirewallPromptViewModel(
    private val promptManager: FirewallPromptManager,
    application: Application
) : AndroidViewModel(application) {

    val uiState: StateFlow<FirewallPromptUiState> = combine(
        promptManager.pendingPrompts,
        promptManager.snoozedPackages
    ) { pending, later ->
        val visible = pending
            .filterNot { it.packageName in later }
            .sortedByDescending { it.timestamp }
        FirewallPromptUiState(
            pendingItems = visible.map { it.toItem() }
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FirewallPromptUiState())

    fun processIntent(intent: FirewallPromptUiIntent) {
        when (intent) {
            is FirewallPromptUiIntent.AlwaysAllow ->
                promptManager.decide(intent.packageName, FirewallPromptDecision.ALWAYS_ALLOW)
            is FirewallPromptUiIntent.AllowOnce ->
                promptManager.decide(intent.packageName, FirewallPromptDecision.ALLOW_ONCE)
            is FirewallPromptUiIntent.Block ->
                promptManager.decide(intent.packageName, FirewallPromptDecision.BLOCK)
            is FirewallPromptUiIntent.Later -> promptManager.snooze(listOf(intent.packageName))
            FirewallPromptUiIntent.LaterAll ->
                promptManager.snooze(uiState.value.pendingItems.map { it.packageName })
        }
    }

    private fun PendingFirewallPrompt.toItem(): FirewallPromptItem {
        val pm = getApplication<Application>().packageManager
        val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
        return FirewallPromptItem(
            packageName = packageName,
            appLabel = appLabel,
            icon = icon,
            timestamp = timestamp
        )
    }
}
