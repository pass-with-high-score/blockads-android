package app.pwhs.blockads.ui.settings.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.VpnKey

@Composable
fun ProtectionSection(
    autoReconnect: Boolean,
    routingMode: String,
    networkSwitchDelayEnabled: Boolean,
    networkSwitchDelaySec: Int,
    safeSearchEnabled: Boolean,
    youtubeRestrictedMode: Boolean,
    dnsResponseType: String,
    upstreamDNS: String,
    onSetAutoReconnect: (Boolean) -> Unit,
    onSetRoutingMode: (Boolean) -> Unit,
    onSetNetworkSwitchDelayEnabled: (Boolean) -> Unit,
    onSetNetworkSwitchDelaySec: (Int) -> Unit,
    onSetSafeSearchEnabled: (Boolean) -> Unit,
    onSetYoutubeRestrictedMode: (Boolean) -> Unit,
    onShowDnsResponseTypeDialog: () -> Unit,
    onNavigateToDNSProvider: () -> Unit,
    onNavigateToWireGuardImport: () -> Unit,
    onNavigateToHttpsFiltering: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_protection),
            icon = Icons.Default.Shield,
            description = stringResource(R.string.settings_category_protection_desc)
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Auto-reconnect + Root proxy
        SettingsCard {
            Column {
                SettingsToggleItem(
                    icon = Icons.Default.Replay,
                    title = stringResource(R.string.settings_auto_reconnect),
                    subtitle = stringResource(R.string.settings_auto_reconnect_desc),
                    isChecked = autoReconnect,
                    onCheckedChange = onSetAutoReconnect
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingsToggleItem(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.settings_root_proxy),
                    subtitle = stringResource(R.string.settings_root_proxy_desc),
                    isChecked = routingMode == AppPreferences.ROUTING_MODE_ROOT,
                    onCheckedChange = onSetRoutingMode
                )
                // Network Switch Delay
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingsToggleItem(
                    icon = Icons.Default.PhoneAndroid,
                    title = stringResource(R.string.settings_network_switch_delay),
                    subtitle = stringResource(R.string.settings_network_switch_delay_desc),
                    isChecked = networkSwitchDelayEnabled,
                    onCheckedChange = onSetNetworkSwitchDelayEnabled
                )
                AnimatedVisibility(
                    visible = networkSwitchDelayEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.settings_network_switch_delay_value,
                                networkSwitchDelaySec
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            listOf(5, 10, 30, 60, 120).forEach { sec ->
                                FilterChip(
                                    selected = networkSwitchDelaySec == sec,
                                    onClick = { onSetNetworkSwitchDelaySec(sec) },
                                    label = { Text("${sec}s") }
                                )
                            }
                        }
                    }
                }
                // Safe Search
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingsToggleItem(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.settings_safe_search),
                    subtitle = stringResource(R.string.settings_safe_search_desc),
                    isChecked = safeSearchEnabled,
                    onCheckedChange = onSetSafeSearchEnabled
                )
                // YouTube Restricted
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingsToggleItem(
                    icon = Icons.Default.OndemandVideo,
                    title = stringResource(R.string.settings_youtube_restricted),
                    subtitle = stringResource(R.string.settings_youtube_restricted_desc),
                    isChecked = youtubeRestrictedMode,
                    onCheckedChange = onSetYoutubeRestrictedMode
                )
                // DNS Response Type
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingItem(
                    icon = Icons.Default.Block,
                    title = stringResource(R.string.settings_dns_response_type),
                    desc = when (dnsResponseType) {
                        AppPreferences.DNS_RESPONSE_NXDOMAIN -> stringResource(R.string.dns_response_nxdomain)
                        AppPreferences.DNS_RESPONSE_REFUSED -> stringResource(R.string.dns_response_refused)
                        else -> stringResource(R.string.dns_response_custom_ip)
                    },
                    onClick = onShowDnsResponseTypeDialog
                )
                // DNS Provider
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingItem(
                    icon = Icons.Default.Dns,
                    title = stringResource(R.string.dns_provider_title),
                    desc = upstreamDNS,
                    onClick = onNavigateToDNSProvider
                )
                // WireGuard Import
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingItem(
                    icon = Icons.Default.VpnKey,
                    title = stringResource(R.string.wireguard_import_title) + " (BETA)",
                    desc = stringResource(R.string.wireguard_empty_desc),
                    onClick = onNavigateToWireGuardImport
                )
                // HTTPS Filtering
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingItem(
                    icon = Icons.Default.Shield,
                    title = stringResource(R.string.https_filtering_title) + " (BETA)",
                    desc = stringResource(R.string.https_filtering_settings_desc),
                    onClick = onNavigateToHttpsFiltering
                )
            }
        }
    }
}
