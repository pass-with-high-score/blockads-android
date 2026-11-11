package app.pwhs.blockads.ui.tailscale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TailscaleViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.processIntent(TailscaleUiIntent.RefreshStatus)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is TailscaleUiEffect.OpenBrowser -> {
                    try {
                        val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
                            .setShowTitle(true)
                            .build()
                        customTabs.launchUrl(context, Uri.parse(effect.url))
                    } catch (e: Exception) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
                is TailscaleUiEffect.AuthSuccess -> {
                    try {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to bring MainActivity to foreground")
                    }
                    snackbarHostState.showSnackbar(context.getString(R.string.tailscale_auth_success))
                }
                is TailscaleUiEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is TailscaleUiEffect.CopyToClipboard -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                    snackbarHostState.showSnackbar(context.getString(R.string.tailscale_copied, effect.label))
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tailscale_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tailscale),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color(0xFF4F46E5)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.tailscale_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.isLoggedIn) {
                item {
                    val selfPeer = uiState.peers.find { it.isSelf }
                    TailscaleStatusCard(
                        uiState = uiState,
                        onToggleEnabled = { viewModel.processIntent(TailscaleUiIntent.ToggleEnabled(it)) },
                        onDisconnect = { viewModel.processIntent(TailscaleUiIntent.Logout) },
                        onStartVpn = { viewModel.processIntent(TailscaleUiIntent.StartVpn) },
                        onSelectSelfPeer = selfPeer?.let { sp ->
                            { viewModel.processIntent(TailscaleUiIntent.SelectPeerForDetail(sp)) }
                        }
                    )
                }

                item {
                    TailscaleTaildropCard(
                        dirName = uiState.taildropDirName,
                        hasCustomDir = uiState.hasCustomTaildropDir,
                        onSetDirectory = { uri, name ->
                            viewModel.processIntent(TailscaleUiIntent.SetTaildropDirectory(uri, name))
                        },
                        onResetDirectory = {
                            viewModel.processIntent(TailscaleUiIntent.ResetTaildropDirectory)
                        }
                    )
                }

                if (uiState.isEnabled && uiState.peers.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.tailscale_peers),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column {
                                uiState.peers.forEachIndexed { index, peer ->
                                    TailscalePeerItem(
                                        peer = peer,
                                        onClick = { viewModel.processIntent(TailscaleUiIntent.SelectPeerForDetail(peer)) },
                                        showDivider = index < uiState.peers.size - 1
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (uiState.connectionState == "NEEDS_AUTH") {
                item {
                    TailscaleWaitingAuthCard(
                        authUrl = uiState.authUrl,
                        onOpenUrl = {
                            if (uiState.authUrl.isNotEmpty()) {
                                try {
                                    val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
                                        .setShowTitle(true)
                                        .build()
                                    customTabs.launchUrl(context, Uri.parse(uiState.authUrl))
                                } catch (e: Exception) {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.authUrl))
                                    context.startActivity(intent)
                                }
                            }
                        }
                    )
                }
            } else {
                item {
                    TailscaleSignInCard(
                        uiState = uiState,
                        onSignInBrowser = { viewModel.processIntent(TailscaleUiIntent.SignInWithBrowser) },
                        onSignInAuthKey = { key -> viewModel.processIntent(TailscaleUiIntent.SignInWithAuthKey(key)) },
                        onSetControlUrl = { url -> viewModel.processIntent(TailscaleUiIntent.SetControlUrl(url)) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }

        if (uiState.selectedPeerForDetail != null) {
            TailscalePeerDetailSheet(
                peer = uiState.selectedPeerForDetail!!,
                onDismiss = { viewModel.processIntent(TailscaleUiIntent.SelectPeerForDetail(null)) },
                onTogglePin = { viewModel.processIntent(TailscaleUiIntent.TogglePinPeer(it)) },
                onPing = { id, target -> viewModel.processIntent(TailscaleUiIntent.PingPeer(id, target)) },
                onCopy = { text, label -> viewModel.processIntent(TailscaleUiIntent.CopyToClipboard(text, label)) }
            )
        }
    }
}

@Preview(name = "Light")
@Composable
private fun TailscaleScreenPreview() {
    MaterialTheme {
        Surface {
            TailscaleScreen(onNavigateBack = {})
        }
    }
}
