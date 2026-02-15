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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.DnsCategory
import app.pwhs.blockads.data.DnsProviders
import app.pwhs.blockads.ui.dnsprovider.component.CategoryHeader
import app.pwhs.blockads.ui.dnsprovider.component.CustomDnsCard
import app.pwhs.blockads.ui.dnsprovider.component.CustomDnsDialog
import app.pwhs.blockads.ui.dnsprovider.component.DnsProviderCard
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsProviderScreen(
    navigator: DestinationsNavigator,
    viewModel: DnsProviderViewModel = koinViewModel()
) {
    val selectedProviderId by viewModel.selectedProviderId.collectAsState()
    val customDnsEnabled by viewModel.customDnsEnabled.collectAsState()
    val upstreamDns by viewModel.upstreamDns.collectAsState()
    val fallbackDns by viewModel.fallbackDns.collectAsState()

    var showCustomDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_provider_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back))
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Standard DNS Providers
            item {
                CategoryHeader(stringResource(R.string.dns_category_standard))
            }
            items(DnsProviders.ALL_PROVIDERS.filter { it.category == DnsCategory.STANDARD }) { provider ->
                DnsProviderCard(
                    provider = provider,
                    isSelected = provider.id == selectedProviderId,
                    onClick = { viewModel.selectProvider(provider) }
                )
            }

            // Privacy-focused DNS Providers
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoryHeader(stringResource(R.string.dns_category_privacy))
            }
            items(DnsProviders.ALL_PROVIDERS.filter { it.category == DnsCategory.PRIVACY }) { provider ->
                DnsProviderCard(
                    provider = provider,
                    isSelected = provider.id == selectedProviderId,
                    onClick = { viewModel.selectProvider(provider) }
                )
            }

            // Family-safe DNS Providers
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoryHeader(stringResource(R.string.dns_category_family))
            }
            items(DnsProviders.ALL_PROVIDERS.filter { it.category == DnsCategory.FAMILY }) { provider ->
                DnsProviderCard(
                    provider = provider,
                    isSelected = provider.id == selectedProviderId,
                    onClick = { viewModel.selectProvider(provider) }
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
                    upstreamDns = upstreamDns,
                    fallbackDns = fallbackDns,
                    onClick = { showCustomDialog = true }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showCustomDialog) {
        CustomDnsDialog(
            upstreamDns = upstreamDns,
            fallbackDns = fallbackDns,
            onDismiss = { showCustomDialog = false },
            onSave = { upstream, fallback ->
                viewModel.setCustomDns(upstream, fallback)
                showCustomDialog = false
            }
        )
    }
}
