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
    val prompt = uiState.current ?: return
    val pkg = prompt.packageName
    FirewallPermissionDialog(
        appLabel = prompt.appLabel,
        packageName = pkg,
        appIcon = prompt.icon,
        pendingCount = uiState.pendingCount,
        onAlwaysAllow = { onIntent(FirewallPromptUiIntent.AlwaysAllow(pkg)) },
        onAllowOnce = { onIntent(FirewallPromptUiIntent.AllowOnce(pkg)) },
        onBlock = { onIntent(FirewallPromptUiIntent.Block(pkg)) },
        onDismiss = { onIntent(FirewallPromptUiIntent.Later(pkg)) }
    )
}
