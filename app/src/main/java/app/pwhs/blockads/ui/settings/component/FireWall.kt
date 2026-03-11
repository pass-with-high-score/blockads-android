package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.ui.theme.TextSecondary

@Composable
fun FireWall(
    modifier: Modifier = Modifier,
    filterLists: List<FilterList>,
    autoUpdateNotification: String,
    autoUpdateFrequency: String,
    autoUpdateWifiOnly: Boolean,
    autoUpdateEnabled: Boolean,
    whitelistCount: Int = 0,
    onNavigateToFirewall: () -> Unit = {},
    onNavigateToFilterSetup: () -> Unit = {},
    onNavigateToWhitelistDomains: () -> Unit = {},
    blocklistCount: Int = 0,
    onNavigateToBlocklistDomains: () -> Unit = {},
    onSetAutoUpdateWifiOnly: (Boolean) -> Unit = {},
    onSetAutoUpdateFrequency: (String) -> Unit = {},
    onSetAutoUpdateNotification: (String) -> Unit = {},
    onSetAutoUpdateEnable: (Boolean) -> Unit = {},
) {
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }


    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        SettingItem(
            icon = Icons.Default.Security,
            desc = stringResource(R.string.settings_firewall_desc),
            title = stringResource(R.string.settings_firewall),
            onClick = onNavigateToFirewall
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        )
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToWhitelistDomains() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_whitelist_domains),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(R.string.settings_whitelist_domains_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (whitelistCount > 0) {
                    Text(
                        text = "$whitelistCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToBlocklistDomains() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_blocklist_domains),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(R.string.settings_blocklist_domains_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (blocklistCount > 0) {
                    Text(
                        text = "$blocklistCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        )
        SettingItem(
            icon = Icons.Default.FilterList,
            desc = stringResource(
                R.string.settings_filter_lists,
                filterLists.count { it.isEnabled }
            ),
            title = stringResource(R.string.filter_setup_title),
            onClick = onNavigateToFilterSetup
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsToggleItem(
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.settings_auto_update_enabled),
                    subtitle = stringResource(R.string.settings_auto_update_enabled_desc),
                    isChecked = autoUpdateEnabled,
                    onCheckedChange = { onSetAutoUpdateEnable(it) }
                )

                if (autoUpdateEnabled) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )

                    // Update frequency
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFrequencyDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_auto_update_frequency),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                when (autoUpdateFrequency) {
                                    AppPreferences.UPDATE_FREQUENCY_6H -> stringResource(
                                        R.string.settings_auto_update_frequency_6h
                                    )

                                    AppPreferences.UPDATE_FREQUENCY_12H -> stringResource(
                                        R.string.settings_auto_update_frequency_12h
                                    )

                                    AppPreferences.UPDATE_FREQUENCY_24H -> stringResource(
                                        R.string.settings_auto_update_frequency_24h
                                    )

                                    AppPreferences.UPDATE_FREQUENCY_48H -> stringResource(
                                        R.string.settings_auto_update_frequency_48h
                                    )

                                    AppPreferences.UPDATE_FREQUENCY_MANUAL -> stringResource(
                                        R.string.settings_auto_update_frequency_manual
                                    )

                                    else -> stringResource(R.string.settings_auto_update_frequency_24h)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )

                    // Wi-Fi only
                    SettingsToggleItem(
                        icon = Icons.Default.Wifi,
                        title = stringResource(R.string.settings_auto_update_wifi_only),
                        subtitle = stringResource(R.string.settings_auto_update_wifi_only_desc),
                        isChecked = autoUpdateWifiOnly,
                        onCheckedChange = { onSetAutoUpdateWifiOnly(it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )

                    // Notification preference
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showNotificationDialog = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_auto_update_notification),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                when (autoUpdateNotification) {
                                    AppPreferences.NOTIFICATION_NORMAL -> stringResource(
                                        R.string.settings_auto_update_notification_normal
                                    )

                                    AppPreferences.NOTIFICATION_SILENT -> stringResource(
                                        R.string.settings_auto_update_notification_silent
                                    )

                                    AppPreferences.NOTIFICATION_NONE -> stringResource(R.string.settings_auto_update_notification_none)
                                    else -> stringResource(R.string.settings_auto_update_notification_normal)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showFrequencyDialog) {
        FrequencyDialog(
            autoUpdateFrequency = autoUpdateFrequency,
            onUpdateFrequencyChange = { freq ->
                onSetAutoUpdateFrequency(freq)
                showFrequencyDialog = false
            },
            onDismiss = { showFrequencyDialog = false }
        )
    }

    // Notification dialog
    if (showNotificationDialog) {
        NotificationDialog(
            autoUpdateNotification = autoUpdateNotification,
            onUpdateNotification = { type ->
                onSetAutoUpdateNotification(type)
                showNotificationDialog = false
            },
            onDismiss = { showNotificationDialog = false }
        )
    }
}