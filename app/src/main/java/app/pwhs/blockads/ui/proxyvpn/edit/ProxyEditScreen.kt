package app.pwhs.blockads.ui.proxyvpn.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import android.content.ClipData
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.data.entities.ProxyProtocol
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyEditScreen(
    profileId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProxyEditViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val copiedMsg = stringResource(R.string.proxy_link_copied)
    val savedMsgFormat = stringResource(R.string.proxy_save_success)

    LaunchedEffect(profileId) {
        viewModel.onIntent(ProxyEditIntent.Load(profileId))
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProxyEditEffect.Saved -> {
                    snackbarHostState.showSnackbar(String.format(savedMsgFormat, effect.name))
                    onNavigateBack()
                }
                is ProxyEditEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.proxy_add_custom_title
                            else R.string.proxy_edit_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
                        )
                    }
                },
                actions = {
                    if (state.shareLink.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                clipboard.nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText("proxy_link", state.shareLink)
                                )
                                viewModel.onIntent(ProxyEditIntent.Load(state.profileId))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.proxy_copy_link)
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.onIntent(ProxyEditIntent.Save) },
                        enabled = !state.isLoading
                    ) {
                        Text(
                            text = stringResource(R.string.wireguard_action_save),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }

            val hasTransport = state.protocol == ProxyProtocol.VLESS ||
                state.protocol == ProxyProtocol.VMESS ||
                state.protocol == ProxyProtocol.TROJAN
            val hasTls = state.security != "none" &&
                state.protocol != ProxyProtocol.SHADOWSOCKS &&
                state.protocol != ProxyProtocol.SOCKS5 &&
                state.protocol != ProxyProtocol.SOCKS4 &&
                state.protocol != ProxyProtocol.HTTP &&
                state.protocol != ProxyProtocol.SSH

            if (rememberIsExpandedLayout(maxWidth)) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SectionHeader(stringResource(R.string.proxy_field_protocol))
                        ProtocolSelector(
                            selectedProtocol = state.protocol,
                            onProtocolSelect = { viewModel.onIntent(ProxyEditIntent.SetProtocol(it)) },
                            useFlowLayout = true
                        )

                        SectionHeader(stringResource(R.string.proxy_section_basic))
                        BasicConfigCard(
                            state = state,
                            onIntent = viewModel::onIntent
                        )

                        SectionHeader(stringResource(R.string.proxy_section_credentials))
                        CredentialsCard(
                            state = state,
                            onIntent = viewModel::onIntent
                        )

                        Spacer(modifier = Modifier.height(32.dp))
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (hasTransport) {
                            SectionHeader(stringResource(R.string.proxy_section_transport))
                            TransportCard(
                                state = state,
                                onIntent = viewModel::onIntent
                            )
                        }

                        if (hasTls) {
                            SectionHeader(stringResource(R.string.proxy_section_tls))
                            TlsRealityCard(
                                state = state,
                                onIntent = viewModel::onIntent
                            )
                        }

                        SectionHeader(stringResource(R.string.proxy_section_advanced))
                        RawJsonCard(
                            isRawMode = state.isRawJsonMode,
                            rawJson = state.rawJson,
                            error = state.errors[ProxyEditViewModel.KEY_JSON],
                            onToggle = { viewModel.onIntent(ProxyEditIntent.ToggleRawJsonMode(it)) },
                            onChangeJson = { viewModel.onIntent(ProxyEditIntent.SetRawJson(it)) }
                        )

                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        SectionHeader(stringResource(R.string.proxy_field_protocol))
                        ProtocolSelector(
                            selectedProtocol = state.protocol,
                            onProtocolSelect = { viewModel.onIntent(ProxyEditIntent.SetProtocol(it)) }
                        )
                    }

                    item {
                        SectionHeader(stringResource(R.string.proxy_section_basic))
                        BasicConfigCard(
                            state = state,
                            onIntent = viewModel::onIntent
                        )
                    }

                    item {
                        SectionHeader(stringResource(R.string.proxy_section_credentials))
                        CredentialsCard(
                            state = state,
                            onIntent = viewModel::onIntent
                        )
                    }

                    if (hasTransport) {
                        item {
                            SectionHeader(stringResource(R.string.proxy_section_transport))
                            TransportCard(
                                state = state,
                                onIntent = viewModel::onIntent
                            )
                        }
                    }

                    if (hasTls) {
                        item {
                            SectionHeader(stringResource(R.string.proxy_section_tls))
                            TlsRealityCard(
                                state = state,
                                onIntent = viewModel::onIntent
                            )
                        }
                    }

                    item {
                        SectionHeader(stringResource(R.string.proxy_section_advanced))
                        RawJsonCard(
                            isRawMode = state.isRawJsonMode,
                            rawJson = state.rawJson,
                            error = state.errors[ProxyEditViewModel.KEY_JSON],
                            onToggle = { viewModel.onIntent(ProxyEditIntent.ToggleRawJsonMode(it)) },
                            onChangeJson = { viewModel.onIntent(ProxyEditIntent.SetRawJson(it)) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}
