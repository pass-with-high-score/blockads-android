package app.pwhs.blockads.ui.firewall.prompt

import android.graphics.drawable.Drawable

data class FirewallPromptItem(
    val packageName: String,
    val appLabel: String,
    val icon: Drawable?
)

data class FirewallPromptUiState(
    val current: FirewallPromptItem? = null,
    val pendingCount: Int = 0
)

sealed interface FirewallPromptUiIntent {
    data class AlwaysAllow(val packageName: String) : FirewallPromptUiIntent
    data class AllowOnce(val packageName: String) : FirewallPromptUiIntent
    data class Block(val packageName: String) : FirewallPromptUiIntent
    data class Later(val packageName: String) : FirewallPromptUiIntent
}
