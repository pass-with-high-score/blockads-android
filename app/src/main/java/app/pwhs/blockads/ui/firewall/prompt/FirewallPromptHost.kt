package app.pwhs.blockads.ui.firewall.prompt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.ui.firewall.component.FirewallPermissionDialog
import org.koin.androidx.compose.koinViewModel

/** App-wide host that surfaces pending firewall prompts as a dialog on any screen. */
@Composable
fun FirewallPromptHost(viewModel: FirewallPromptViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FirewallPromptContent(uiState = uiState, onIntent = viewModel::processIntent)
}

@Composable
fun FirewallPromptContent(
    uiState: FirewallPromptUiState,
    onIntent: (FirewallPromptUiIntent) -> Unit
) {
    if (uiState.pendingItems.isEmpty()) return
    FirewallPermissionDialog(
        pendingItems = uiState.pendingItems,
        onAlwaysAllow = { onIntent(FirewallPromptUiIntent.AlwaysAllow(it)) },
        onAllowOnce = { onIntent(FirewallPromptUiIntent.AllowOnce(it)) },
        onBlock = { onIntent(FirewallPromptUiIntent.Block(it)) },
        onDismiss = { onIntent(FirewallPromptUiIntent.LaterAll) }
    )
}
