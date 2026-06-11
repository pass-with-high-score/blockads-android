package app.pwhs.blockads.ui.httpsfiltering

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.httpsfiltering.component.BrowserRow
import app.pwhs.blockads.ui.httpsfiltering.component.ExplanationCard
import app.pwhs.blockads.ui.httpsfiltering.component.MasterToggleCard
import app.pwhs.blockads.ui.httpsfiltering.component.SetupGuideCard
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpsFilteringScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HttpsFilteringViewModel = koinViewModel()
) {
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isProxyRunning by viewModel.isProxyRunning.collectAsStateWithLifecycle()
    val browsers by viewModel.browsers.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val certExported by viewModel.certExported.collectAsStateWithLifecycle()
    val certStatus by viewModel.certStatus.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    val wgDisabledMsg = stringResource(R.string.https_filtering_wireguard_disabled)
    val certSavedLegacyMsg = stringResource(R.string.https_filtering_cert_saved_legacy)
    val proxyStartedMsg = stringResource(R.string.https_filtering_started)
    val proxyStoppedMsg = stringResource(R.string.https_filtering_stopped)

    // Re-verify when the user returns from Android's Security Settings.
    // They likely just installed (or removed) the certificate.
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.verifyCert()
    }

    // Auto-verify on first composition when filtering is on, so users
    // see the install status without having to tap the button.
    LaunchedEffect(isEnabled) {
        if (isEnabled) viewModel.verifyCert()
    }

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HttpsFilteringEvent.CaCertSavedToDownloads -> {
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.https_filtering_cert_saved_downloads, event.fileName)
                    )
                }

                is HttpsFilteringEvent.CaCertExportedLegacy -> {
                    // On old Android, try the legacy install intent
                    try {
                        val intent = viewModel.createSecuritySettingsIntent()
                        settingsLauncher.launch(intent)
                    } catch (_: Exception) {
                        snackbarHostState.showSnackbar(certSavedLegacyMsg)
                    }
                }

                is HttpsFilteringEvent.Error -> {
                    snackbarHostState.showSnackbar(event.message)
                }

                is HttpsFilteringEvent.ProxyStarted -> {
                    snackbarHostState.showSnackbar(proxyStartedMsg)
                }

                is HttpsFilteringEvent.ProxyStopped -> {
                    snackbarHostState.showSnackbar(proxyStoppedMsg)
                }

                is HttpsFilteringEvent.WireGuardDisabledForHttps -> {
                    snackbarHostState.showSnackbar(wgDisabledMsg)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.https_filtering_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Master Toggle + Status ─────────────────────────────
                item {
                    MasterToggleCard(
                        isEnabled = isEnabled,
                        isProxyRunning = isProxyRunning,
                        onToggle = { viewModel.toggleEnabled(it) }
                    )
                }

                // ── Explanation Card ───────────────────────────────────
                item {
                    ExplanationCard()
                }

                // ── Setup Guide (only when enabled) ──────────────────
                item {
                    AnimatedVisibility(
                        visible = isEnabled,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        SetupGuideCard(
                            certExported = certExported,
                            certStatus = certStatus,
                            onExport = { viewModel.exportCaCert() },
                            onOpenSettings = {
                                try {
                                    val intent = viewModel.createSecuritySettingsIntent()
                                    settingsLauncher.launch(intent)
                                } catch (_: Exception) {
                                }
                            },
                            onVerifyCert = { viewModel.verifyCert() }
                        )
                    }
                }

                // ── Browser Selection Header ──────────────────────────
                if (isEnabled && browsers.isNotEmpty()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.https_filtering_browser_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.https_filtering_browser_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // ── Browser List ──────────────────────────────────
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column {
                                browsers.forEachIndexed { index, browser ->
                                    BrowserRow(
                                        browser = browser,
                                        onToggle = { viewModel.toggleBrowser(browser.packageName) }
                                    )
                                    if (index < browsers.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(
                                                alpha = 0.4f
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom spacer
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}
