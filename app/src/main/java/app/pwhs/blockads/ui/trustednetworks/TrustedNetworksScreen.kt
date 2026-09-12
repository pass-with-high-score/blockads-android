package app.pwhs.blockads.ui.trustednetworks

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val currentSsid by viewModel.currentSsid.collectAsStateWithLifecycle()

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

            // Master toggle
            SettingsCard {
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Add current network
            SettingsCard(onClick = { addCurrent() }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        trustedList.forEachIndexed { index, ssid ->
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
                                Text(
                                    text = ssid,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.removeSsid(ssid) }) {
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

            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

