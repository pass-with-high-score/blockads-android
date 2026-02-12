package app.pwhs.blockads.ui.dnsprovider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.DnsCategory
import app.pwhs.blockads.data.DnsProvider
import app.pwhs.blockads.data.DnsProviders
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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

@Composable
fun CategoryHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun DnsProviderCard(
    provider: DnsProvider,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (provider.dohUrl != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.dns_doh_badge),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = provider.ipAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun CustomDnsCard(
    isSelected: Boolean,
    upstreamDns: String,
    fallbackDns: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dns_custom_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dns_custom_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$upstreamDns / $fallbackDns",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun CustomDnsDialog(
    upstreamDns: String,
    fallbackDns: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var editUpstream by remember { mutableStateOf(upstreamDns) }
    var editFallback by remember { mutableStateOf(fallbackDns) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dns_custom_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_upstream_dns),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editUpstream,
                    onValueChange = { editUpstream = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("8.8.8.8") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.settings_fallback_dns),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editFallback,
                    onValueChange = { editFallback = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("1.1.1.1") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(editUpstream, editFallback) }) {
                Text(stringResource(R.string.dns_custom_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dns_custom_cancel))
            }
        }
    )
}
