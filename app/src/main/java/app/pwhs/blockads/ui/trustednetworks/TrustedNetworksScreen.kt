package app.pwhs.blockads.ui.trustednetworks

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.settings.component.SectionHeader
import app.pwhs.blockads.ui.settings.component.SettingIconBadge
import app.pwhs.blockads.ui.settings.component.SettingsCard
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedNetworksScreen(
    modifier: Modifier = Modifier,
    viewModel: TrustedNetworksViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val trusted by viewModel.trustedSsids.collectAsStateWithLifecycle()
    val enabled by viewModel.pauseOnTrustedEnabled.collectAsStateWithLifecycle()
    val useSystemDns by viewModel.useSystemDnsOnTrustedEnabled.collectAsStateWithLifecycle()
    val currentSsid by viewModel.currentSsid.collectAsStateWithLifecycle()
    val currentBssid by viewModel.currentBssid.collectAsStateWithLifecycle()
    var showManualDialog by remember { mutableStateOf(false) }
    var manualSsidInput by remember { mutableStateOf("") }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.refreshCurrentSsid()
            viewModel.addCurrentNetwork()
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshCurrentSsid() }

    fun addCurrent() {
        val hasLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            viewModel.refreshCurrentSsid()
            viewModel.addCurrentNetwork()
        } else {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trusted_networks_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Action toggles on trusted networks
            SettingsCard {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingIconBadge(
                            painter = painterResource(R.drawable.ic_settings_trusted_wifi),
                            tint = Color(0xFF059669)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.trusted_networks_toggle),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.trusted_networks_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(checked = enabled, onCheckedChange = { viewModel.setPauseOnTrustedEnabled(it) })
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingIconBadge(
                            painter = painterResource(R.drawable.ic_settings_dns),
                            tint = Color(0xFF2563EB)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.trusted_networks_use_system_dns_toggle),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.trusted_networks_use_system_dns_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(checked = useSystemDns, onCheckedChange = { viewModel.setUseSystemDnsOnTrustedEnabled(it) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val isCurrentAlreadyAdded = currentSsid != null &&
                app.pwhs.blockads.service.TrustedNetworkManager.isTrusted(trusted, currentSsid, currentBssid)
            SettingsCard {
                Column {
                    if (!isCurrentAlreadyAdded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { addCurrent() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingIconBadge(
                                icon = Icons.Default.Add,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.trusted_networks_add_current),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    currentSsid ?: stringResource(R.string.trusted_networks_no_wifi),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (currentSsid != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = dividerColor
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                manualSsidInput = ""
                                showManualDialog = true
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingIconBadge(
                            icon = Icons.Default.Edit,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.trusted_networks_add_manual),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.trusted_networks_add_manual_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showManualDialog) {
                AlertDialog(
                    onDismissRequest = { showManualDialog = false },
                    title = { Text(stringResource(R.string.trusted_networks_add_manual)) },
                    text = {
                        OutlinedTextField(
                            value = manualSsidInput,
                            onValueChange = { manualSsidInput = it },
                            label = { Text(stringResource(R.string.trusted_networks_ssid_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.addSsid(manualSsidInput)
                                showManualDialog = false
                            },
                            enabled = manualSsidInput.isNotBlank()
                        ) {
                            Text(stringResource(R.string.trusted_networks_add_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showManualDialog = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(
                title = stringResource(R.string.trusted_networks_list_header)
            )

            if (trusted.isEmpty()) {
                SettingsCard {
                    Text(
                        stringResource(R.string.trusted_networks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                SettingsCard {
                    Column {
                        val trustedList = trusted.toList()
                        trustedList.forEachIndexed { index, rawEntry ->
                            val entry = app.pwhs.blockads.service.TrustedNetworkManager.parseEntry(rawEntry)
                            val canPinCurrent = entry.bssid == null &&
                                currentSsid == entry.ssid &&
                                currentBssid != null
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingIconBadge(
                                    painter = painterResource(R.drawable.ic_settings_trusted_wifi),
                                    tint = Color(0xFF059669)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.ssid,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (entry.bssid != null) {
                                            stringResource(R.string.trusted_networks_bssid_pinned, entry.bssid)
                                        } else {
                                            stringResource(R.string.trusted_networks_any_router)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.bssid != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                if (entry.bssid != null || canPinCurrent) {
                                    IconButton(onClick = { viewModel.togglePinBssid(rawEntry) }) {
                                        Icon(
                                            imageVector = if (entry.bssid != null) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = stringResource(
                                                if (entry.bssid != null) R.string.trusted_networks_unpin_bssid
                                                else R.string.trusted_networks_pin_bssid
                                            ),
                                            tint = if (entry.bssid != null) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.removeSsid(rawEntry) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_settings_trash),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (index < trustedList.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = dividerColor
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

