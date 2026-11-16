package app.pwhs.blockads.ui.dnsprovider

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.ui.dnsprovider.component.ActiveDnsHeroCard
import app.pwhs.blockads.ui.dnsprovider.component.CategoryHeader
import app.pwhs.blockads.ui.dnsprovider.component.CustomDnsBottomSheet
import app.pwhs.blockads.ui.dnsprovider.component.CustomDnsTabContent
import app.pwhs.blockads.ui.dnsprovider.component.DnsCategoryChips
import app.pwhs.blockads.ui.dnsprovider.component.DnsProviderGroupCard
import app.pwhs.blockads.ui.dnsprovider.component.DnsProviderTabs
import app.pwhs.blockads.ui.dnsprovider.component.DohBypassCard
import app.pwhs.blockads.ui.dnsprovider.component.FallbackDnsBottomSheet
import app.pwhs.blockads.ui.dnsprovider.component.FallbackDnsCard
import app.pwhs.blockads.ui.dnsprovider.component.WebRtcLeakCard
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsProviderScreen(
    modifier: Modifier = Modifier,
    viewModel: DnsProviderViewModel = koinViewModel(),
    showBackButton: Boolean = true,
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLeakTestSheet by remember { androidx.compose.runtime.mutableStateOf(false) }
    val leakViewModel: app.pwhs.blockads.ui.dnsprovider.leak.DnsLeakTestViewModel = koinViewModel()
    val leakState by leakViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel.effects) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DnsProviderUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.messageRes, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val filteredProviders = remember(state.selectedCategory) {
        if (state.selectedCategory == null) {
            DnsProviders.ALL_PROVIDERS
        } else {
            DnsProviders.ALL_PROVIDERS.filter { it.category == state.selectedCategory }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = if (showBackButton) androidx.compose.material3.ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_provider_title)) },
                windowInsets = if (showBackButton) TopAppBarDefaults.windowInsets else WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        leakViewModel.onIntent(app.pwhs.blockads.ui.dnsprovider.leak.DnsLeakTestUiIntent.StartTest)
                        showLeakTestSheet = true
                    }) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = stringResource(R.string.dns_leak_test_title),
                            tint = MaterialTheme.colorScheme.primary
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
            contentPadding = PaddingValues(bottom = 16.dp + LocalFloatingBottomBarPadding.current),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Active DNS Hero Card
            item {
                ActiveDnsHeroCard(
                    providerName = state.activeProviderName,
                    protocol = state.activeProtocol,
                    endpoint = state.activeEndpoint,
                    fallbackDns = state.fallbackDns,
                    onEditFallback = { viewModel.onIntent(DnsProviderUiIntent.OpenFallbackSheet) },
                    onTestDnsLeak = {
                        leakViewModel.onIntent(app.pwhs.blockads.ui.dnsprovider.leak.DnsLeakTestUiIntent.StartTest)
                        showLeakTestSheet = true
                    }
                )
            }

            // Tabs: Built-in vs Custom
            item {
                DnsProviderTabs(
                    selectedTab = state.selectedTab,
                    onTabSelected = { viewModel.onIntent(DnsProviderUiIntent.SelectTab(it)) }
                )
            }

            // Content according to selected tab
            if (state.selectedTab == 0) {
                // Built-in Tab
                item {
                    DnsCategoryChips(
                        selectedCategory = state.selectedCategory,
                        onSelectCategory = { viewModel.onIntent(DnsProviderUiIntent.SelectCategory(it)) }
                    )
                }

                item {
                    DnsProviderGroupCard(
                        providers = filteredProviders,
                        selectedProviderId = state.selectedProviderId,
                        onSelectProvider = { viewModel.onIntent(DnsProviderUiIntent.SelectProvider(it)) }
                    )
                }
            } else {
                // Custom Tab
                item {
                    CustomDnsTabContent(
                        isCustomActive = state.isCustomDns,
                        currentProtocol = state.activeProtocol,
                        currentEndpoint = state.customDnsDisplay,
                        currentRelayUrl = state.odohRelayUrl,
                        onConfigure = { viewModel.onIntent(DnsProviderUiIntent.OpenCustomSheet) }
                    )
                }
            }

            // Fallback DNS Section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                CategoryHeader(stringResource(R.string.dns_category_fallback))
            }
            item {
                FallbackDnsCard(
                    fallbackDns = state.fallbackDns,
                    onClick = { viewModel.onIntent(DnsProviderUiIntent.OpenFallbackSheet) }
                )
            }

            // Advanced DNS Security Section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                CategoryHeader(stringResource(R.string.dns_category_advanced_security))
            }
            item {
                DohBypassCard(
                    enabled = state.blockDohBypass,
                    onCheckedChange = { viewModel.onIntent(DnsProviderUiIntent.ToggleBlockDohBypass(it)) }
                )
            }
            item {
                WebRtcLeakCard(
                    enabled = state.blockWebRtcLeak,
                    onCheckedChange = { viewModel.onIntent(DnsProviderUiIntent.ToggleBlockWebRtcLeak(it)) }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // Custom DNS BottomSheet
    if (state.showCustomSheet) {
        CustomDnsBottomSheet(
            initialProtocol = state.activeProtocol,
            initialEndpoint = state.customDnsDisplay,
            initialRelayUrl = state.odohRelayUrl,
            onDismiss = { viewModel.onIntent(DnsProviderUiIntent.CloseCustomSheet) },
            onSave = { protocol, endpoint, relayUrl ->
                viewModel.onIntent(DnsProviderUiIntent.SaveCustomDns(protocol, endpoint, relayUrl))
            }
        )
    }

    // Fallback DNS BottomSheet
    if (state.showFallbackSheet) {
        FallbackDnsBottomSheet(
            initialFallbackDns = state.fallbackDns,
            onDismiss = { viewModel.onIntent(DnsProviderUiIntent.CloseFallbackSheet) },
            onSave = { fallbackIp ->
                viewModel.onIntent(DnsProviderUiIntent.SaveFallbackDns(fallbackIp))
            }
        )
    }

    // DNS Leak Test BottomSheet
    if (showLeakTestSheet) {
        app.pwhs.blockads.ui.dnsprovider.leak.DnsLeakTestBottomSheet(
            uiState = leakState,
            onStartTest = { leakViewModel.onIntent(app.pwhs.blockads.ui.dnsprovider.leak.DnsLeakTestUiIntent.StartTest) },
            onDismiss = { showLeakTestSheet = false }
        )
    }
}
