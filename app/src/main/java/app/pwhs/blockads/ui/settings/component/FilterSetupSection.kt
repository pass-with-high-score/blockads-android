package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FilterList

@Composable
fun FilterSetupSection(
    modifier: Modifier = Modifier,
    filterLists: List<FilterList>,
    autoUpdateNotification: String,
    autoUpdateFrequency: String,
    autoUpdateWifiOnly: Boolean,
    autoUpdateEnabled: Boolean,
    onNavigateToFilterSetup: () -> Unit = {},
    onSetAutoUpdateWifiOnly: (Boolean) -> Unit = {},
    onSetAutoUpdateFrequency: (String) -> Unit = {},
    onSetAutoUpdateNotification: (String) -> Unit = {},
    onSetAutoUpdateEnable: (Boolean) -> Unit = {},
) {
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_filters),
            description = stringResource(R.string.settings_category_filters_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingsCard {
            Column {
                // 1. Filter list navigation
                val enabledFilterCount = filterLists.count { it.isEnabled }
                SettingItem(
                    icon = Icons.Default.FilterList,
                    iconTint = Color(0xFF059669),
                    title = stringResource(R.string.filter_setup_title),
                    desc = stringResource(R.string.settings_category_filters_desc),
                    statusValue = stringResource(R.string.settings_filter_lists, enabledFilterCount),
                    onClick = onNavigateToFilterSetup
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 2. Auto-update toggle
                SettingsToggleItem(
                    icon = Icons.Default.Download,
                    iconTint = Color(0xFF059669),
                    title = stringResource(R.string.settings_auto_update_enabled),
                    subtitle = stringResource(R.string.settings_auto_update_enabled_desc),
                    isChecked = autoUpdateEnabled,
                    onCheckedChange = onSetAutoUpdateEnable
                )

                if (autoUpdateEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                    // 3. Update frequency
                    val frequencyDesc = when (autoUpdateFrequency) {
                        AppPreferences.UPDATE_FREQUENCY_6H -> stringResource(R.string.settings_auto_update_frequency_6h)
                        AppPreferences.UPDATE_FREQUENCY_12H -> stringResource(R.string.settings_auto_update_frequency_12h)
                        AppPreferences.UPDATE_FREQUENCY_24H -> stringResource(R.string.settings_auto_update_frequency_24h)
                        AppPreferences.UPDATE_FREQUENCY_48H -> stringResource(R.string.settings_auto_update_frequency_48h)
                        AppPreferences.UPDATE_FREQUENCY_MANUAL -> stringResource(R.string.settings_auto_update_frequency_manual)
                        else -> stringResource(R.string.settings_auto_update_frequency_24h)
                    }
                    SettingItem(
                        icon = Icons.Default.Schedule,
                        iconTint = Color(0xFF059669),
                        title = stringResource(R.string.settings_auto_update_frequency),
                        desc = frequencyDesc,
                        onClick = { showFrequencyDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                    // 4. Wi-Fi only
                    SettingsToggleItem(
                        icon = Icons.Default.Wifi,
                        iconTint = Color(0xFF059669),
                        title = stringResource(R.string.settings_auto_update_wifi_only),
                        subtitle = stringResource(R.string.settings_auto_update_wifi_only_desc),
                        isChecked = autoUpdateWifiOnly,
                        onCheckedChange = onSetAutoUpdateWifiOnly
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                    // 5. Notification preference
                    val notificationDesc = when (autoUpdateNotification) {
                        AppPreferences.NOTIFICATION_NORMAL -> stringResource(R.string.settings_auto_update_notification_normal)
                        AppPreferences.NOTIFICATION_SILENT -> stringResource(R.string.settings_auto_update_notification_silent)
                        AppPreferences.NOTIFICATION_NONE -> stringResource(R.string.settings_auto_update_notification_none)
                        else -> stringResource(R.string.settings_auto_update_notification_normal)
                    }
                    SettingItem(
                        icon = Icons.Default.Notifications,
                        iconTint = Color(0xFF059669),
                        title = stringResource(R.string.settings_auto_update_notification),
                        desc = notificationDesc,
                        onClick = { showNotificationDialog = true }
                    )
                }
            }
        }
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