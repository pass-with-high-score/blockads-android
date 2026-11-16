package app.pwhs.blockads.ui.vpnsettings

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
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.proxyvpn.component.PreferredIpTypeCard
import app.pwhs.blockads.ui.proxyvpn.component.SplitDnsCard
import app.pwhs.blockads.ui.settings.component.SectionHeader
import app.pwhs.blockads.ui.wireguard.component.SettingToggleCard
import org.koin.androidx.compose.koinViewModel

@Composable
fun VpnSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VpnSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    VpnSettingsContent(
        uiState = uiState,
        onIntent = viewModel::sendIntent,
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSettingsContent(
    uiState: VpnSettingsUiState,
    onIntent: (VpnSettingsUiIntent) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vpn_settings_title)) },
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
        }
    ) { padding ->
        val generalSection: @Composable () -> Unit = {
            SectionHeader(title = stringResource(R.string.vpn_settings_section_general))
            PreferredIpTypeCard(
                currentType = uiState.preferredIPType,
                onTypeSelected = { onIntent(VpnSettingsUiIntent.SetPreferredIPType(it)) },
                modifier = Modifier.padding(horizontal = 0.dp)
            )
        }

        val routingSection: @Composable () -> Unit = {
            SectionHeader(title = stringResource(R.string.vpn_settings_section_routing))
            SettingToggleCard(
                icon = Icons.Filled.Lan,
                title = stringResource(R.string.exclude_lan_title),
                description = stringResource(R.string.exclude_lan_description),
                checked = uiState.excludeLan,
                onCheckedChange = { onIntent(VpnSettingsUiIntent.SetExcludeLan(it)) }
            )
            SettingToggleCard(
                icon = Icons.AutoMirrored.Filled.AltRoute,
                title = stringResource(R.string.allow_app_bypass_title),
                description = stringResource(R.string.allow_app_bypass_description),
                checked = uiState.allowAppBypass,
                onCheckedChange = { onIntent(VpnSettingsUiIntent.SetAllowAppBypass(it)) }
            )
            SplitDnsCard(
                value = uiState.splitDnsZones,
                onValueChange = { onIntent(VpnSettingsUiIntent.SetSplitDnsZones(it)) }
            )
        }

        val securitySection: @Composable () -> Unit = {
            SectionHeader(title = stringResource(R.string.vpn_settings_section_security))
            SettingToggleCard(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.dns_block_webrtc_leak_title),
                description = stringResource(R.string.dns_block_webrtc_leak_desc),
                checked = uiState.blockWebRtcLeak,
                onCheckedChange = { onIntent(VpnSettingsUiIntent.SetBlockWebRtcLeak(it)) }
            )
            SettingToggleCard(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.dns_block_doh_bypass_title),
                description = stringResource(R.string.dns_block_doh_bypass_desc),
                checked = uiState.blockDohBypass,
                onCheckedChange = { onIntent(VpnSettingsUiIntent.SetBlockDohBypass(it)) }
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
                        generalSection()
                        Spacer(modifier = Modifier.height(6.dp))
                        securitySection()
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
                        routingSection()
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { generalSection() }
                    item { Spacer(modifier = Modifier.height(6.dp)) }
                    item { routingSection() }
                    item { Spacer(modifier = Modifier.height(6.dp)) }
                    item { securitySection() }
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}
