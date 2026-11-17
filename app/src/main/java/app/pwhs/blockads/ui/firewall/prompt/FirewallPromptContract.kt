package app.pwhs.blockads.ui.firewall.prompt

import android.graphics.drawable.Drawable

data class FirewallPromptItem(
    val packageName: String,
    val appLabel: String,
    val icon: Drawable?,
    val timestamp: Long = 0L
)

data class FirewallPromptUiState(
    val pendingItems: List<FirewallPromptItem> = emptyList()
)

sealed interface FirewallPromptUiIntent {
    data class AlwaysAllow(val packageName: String) : FirewallPromptUiIntent
    data class AllowOnce(val packageName: String) : FirewallPromptUiIntent
    data class Block(val packageName: String) : FirewallPromptUiIntent
    data class Later(val packageName: String) : FirewallPromptUiIntent
    data object LaterAll : FirewallPromptUiIntent
}
