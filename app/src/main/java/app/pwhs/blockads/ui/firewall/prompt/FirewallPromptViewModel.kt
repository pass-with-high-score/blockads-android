package app.pwhs.blockads.ui.firewall.prompt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.service.FirewallPromptDecision
import app.pwhs.blockads.service.FirewallPromptManager
import app.pwhs.blockads.service.PendingFirewallPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class FirewallPromptViewModel(
    private val promptManager: FirewallPromptManager,
    application: Application
) : AndroidViewModel(application) {

    // Prompts the user postponed by dismissing the dialog; they stay blocked and in the notification shade.
    private val snoozed = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<FirewallPromptUiState> = combine(
        promptManager.pendingPrompts,
        snoozed
    ) { pending, later ->
        val visible = pending.filterNot { it.packageName in later }
        FirewallPromptUiState(
            current = visible.firstOrNull()?.toItem(),
            pendingCount = visible.size
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
            is FirewallPromptUiIntent.Later -> snoozed.update { it + intent.packageName }
        }
    }

    private fun PendingFirewallPrompt.toItem(): FirewallPromptItem {
        val pm = getApplication<Application>().packageManager
        val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
        return FirewallPromptItem(packageName = packageName, appLabel = appLabel, icon = icon)
    }
}
