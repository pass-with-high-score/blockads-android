package app.pwhs.blockads.ui.dnsprovider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsCategory
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.ui.dnsprovider.component.CategoryHeader
import app.pwhs.blockads.ui.dnsprovider.component.CustomDnsCard
import app.pwhs.blockads.ui.dnsprovider.component.CustomDnsDialog
import app.pwhs.blockads.ui.dnsprovider.component.DnsProviderCard
import app.pwhs.blockads.ui.dnsprovider.component.DnsProviderGroupCard
import app.pwhs.blockads.ui.dnsprovider.component.DohBypassCard
import app.pwhs.blockads.ui.dnsprovider.component.FallbackDnsCard
import app.pwhs.blockads.ui.dnsprovider.component.FallbackDnsDialog
import app.pwhs.blockads.ui.event.UiEventEffect
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsProviderScreen(
    modifier: Modifier = Modifier,
    viewModel: DnsProviderViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val selectedProviderId by viewModel.selectedProviderId.collectAsStateWithLifecycle()
    val customDnsEnabled by viewModel.customDnsEnabled.collectAsStateWithLifecycle()
    val customDnsDisplay by viewModel.customDnsDisplay.collectAsStateWithLifecycle()
    val upstreamDns by viewModel.upstreamDns.collectAsStateWithLifecycle()
    val fallbackDns by viewModel.fallbackDns.collectAsStateWithLifecycle()
    val blockDohBypass by viewModel.blockDohBypass.collectAsStateWithLifecycle()

    var showCustomDialog by remember { mutableStateOf(false) }
    var showFallbackDialog by remember { mutableStateOf(false) }

    var customDnsError by remember { mutableStateOf<String?>(null) }
    var fallbackDnsError by remember { mutableStateOf<String?>(null) }
    val duplicateErrorMsg = stringResource(R.string.dns_error_duplicate)

    UiEventEffect(viewModel.events)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_provider_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Standard DNS Providers
            item {
                CategoryHeader(stringResource(R.string.dns_category_standard))
            }
            item {
                DnsProviderGroupCard(
                    providers = DnsProviders.ALL_PROVIDERS.filter { it.category == DnsCategory.STANDARD },
                    selectedProviderId = selectedProviderId,
                    onSelectProvider = { viewModel.selectProvider(it) }
                )
            }

            // Privacy-focused DNS Providers
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoryHeader(stringResource(R.string.dns_category_privacy))
            }
            item {
                DnsProviderGroupCard(
                    providers = DnsProviders.ALL_PROVIDERS.filter { it.category == DnsCategory.PRIVACY },
                    selectedProviderId = selectedProviderId,
                    onSelectProvider = { viewModel.selectProvider(it) }
                )
            }

            // Family-safe DNS Providers
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoryHeader(stringResource(R.string.dns_category_family))
            }
            item {
                DnsProviderGroupCard(
                    providers = DnsProviders.ALL_PROVIDERS.filter { it.category == DnsCategory.FAMILY },
                    selectedProviderId = selectedProviderId,
                    onSelectProvider = { viewModel.selectProvider(it) }
                )
            }

            // Custom DNS Option
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoryHeader(stringResource(R.string.dns_category_custom))
            }
            item {
                CustomDnsCard(
                    isSelected = customDnsEnabled,
                    upstreamDns = customDnsDisplay,
                    onClick = { showCustomDialog = true }
                )
            }

            // Standalone Fallback DNS (always visible for all configurations)
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoryHeader(stringResource(R.string.dns_category_fallback))
            }
            item {
                FallbackDnsCard(
                    fallbackDns = fallbackDns,
                    onClick = { showFallbackDialog = true }
                )
            }

            // Advanced DNS Security (DoH Leak Protection)
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoryHeader(stringResource(R.string.dns_category_advanced_security))
            }
            item {
                DohBypassCard(
                    enabled = blockDohBypass,
                    onCheckedChange = { viewModel.setBlockDohBypass(it) }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                Spacer(modifier = Modifier.height(200.dp))
            }
        }
    }

    if (showCustomDialog) {
        CustomDnsDialog(
            upstreamDns = customDnsDisplay,
            errorText = customDnsError,
            onDismiss = {
                showCustomDialog = false
                customDnsError = null
            },
            onSave = { upstream ->
                val trimmed = upstream.trim()
                val parsed = viewModel.getParsedHost(trimmed)
                val isPlain = !trimmed.startsWith("https://", ignoreCase = true) &&
                        !trimmed.startsWith("tls://", ignoreCase = true) &&
                        !trimmed.startsWith("quic://", ignoreCase = true)
                if (isPlain && parsed.equals(fallbackDns.trim(), ignoreCase = true)) {
                    customDnsError = duplicateErrorMsg
                } else {
                    viewModel.setCustomDns(upstream)
                    showCustomDialog = false
                    customDnsError = null
                }
            }
        )
    }

    if (showFallbackDialog) {
        FallbackDnsDialog(
            fallbackDns = fallbackDns,
            errorText = fallbackDnsError,
            onDismiss = {
                showFallbackDialog = false
                fallbackDnsError = null
            },
            onSave = { dns ->
                val trimmed = dns.trim()
                val isUpstreamPlain = customDnsDisplay.isBlank() ||
                        (!customDnsDisplay.startsWith("https://", ignoreCase = true) &&
                         !customDnsDisplay.startsWith("tls://", ignoreCase = true) &&
                         !customDnsDisplay.startsWith("quic://", ignoreCase = true))
                if (isUpstreamPlain && trimmed.equals(upstreamDns.trim(), ignoreCase = true)) {
                    fallbackDnsError = duplicateErrorMsg
                } else {
                    viewModel.setFallbackDns(dns)
                    showFallbackDialog = false
                    fallbackDnsError = null
                }
            }
        )
    }
}

