package app.pwhs.blockadstv.ui.screens.proxy

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.service.ServiceController
import app.pwhs.blockadstv.ui.screens.proxy.component.AddProxyDialog
import app.pwhs.blockadstv.ui.screens.proxy.component.AddSubscriptionDialog
import app.pwhs.blockadstv.ui.screens.proxy.component.DialogButton
import app.pwhs.blockadstv.ui.screens.proxy.component.ProxyModeSelector
import app.pwhs.blockadstv.ui.screens.proxy.component.ProxyNodeItem
import app.pwhs.blockadstv.ui.screens.proxy.component.RootWarningDialog
import app.pwhs.blockadstv.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    viewModel: ProxyViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effect.collectLatest { effect ->
            when (effect) {
                is ProxyUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is ProxyUiEffect.RequestRestart -> {
                    ServiceController.requestRestart(context)
                }
            }
        }
    }

    ProxyScreenContent(
        uiState = uiState,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyScreenContent(
    uiState: ProxyUiState,
    onIntent: (ProxyUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.showAddNodeDialog) {
        AddProxyDialog(
            errorMessage = uiState.dialogError,
            onSave = { onIntent(ProxyUiIntent.ImportLink(it)) },
            onDismiss = { onIntent(ProxyUiIntent.ShowAddNodeDialog(false)) },
        )
    }

    if (uiState.showImportDialog) {
        AddSubscriptionDialog(
            isLoading = uiState.isUpdatingSubscription,
            errorMessage = uiState.dialogError,
            onSave = { url, name -> onIntent(ProxyUiIntent.ImportSubscription(url, name)) },
            onDismiss = { onIntent(ProxyUiIntent.ShowImportDialog(false)) },
        )
    }

    if (uiState.showRootWarningDialog) {
        RootWarningDialog(
            onDismiss = { onIntent(ProxyUiIntent.DismissRootWarning) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_nav_proxy),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.proxy_title),
                style = MaterialTheme.typography.headlineLarge,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.proxy_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Protection Mode Selector (DNS Only, Proxy VPN, Root Proxy)
        ProxyModeSelector(
            currentMode = uiState.routingMode,
            onSelectMode = { onIntent(ProxyUiIntent.SelectRoutingMode(it)) },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Action Toolbar & Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.proxy_nodes_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.profiles.isNotEmpty()) {
                    DialogButton(
                        text = stringResource(R.string.proxy_ping_all),
                        isPrimary = false,
                        onClick = { onIntent(ProxyUiIntent.PingAll) },
                    )
                }
                DialogButton(
                    text = stringResource(R.string.proxy_add_node),
                    isPrimary = false,
                    onClick = { onIntent(ProxyUiIntent.ShowAddNodeDialog(true)) },
                )
                DialogButton(
                    text = stringResource(R.string.proxy_add_subscription),
                    isPrimary = true,
                    onClick = { onIntent(ProxyUiIntent.ShowImportDialog(true)) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Node List or Empty state
        if (uiState.profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.proxy_no_nodes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(uiState.profiles, key = { it.id }) { profile ->
                    ProxyNodeItem(
                        profile = profile,
                        isActive = profile.id == uiState.activeProfileId,
                        pingResult = uiState.pingResults[profile.id],
                        onSelect = { onIntent(ProxyUiIntent.SelectActiveProfile(profile.id)) },
                        onPing = { onIntent(ProxyUiIntent.PingProfile(profile)) },
                        onDelete = { onIntent(ProxyUiIntent.DeleteProfile(profile.id)) },
                    )
                }
            }
        }
    }
}
