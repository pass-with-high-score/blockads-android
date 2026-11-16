package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences

@Composable
fun FilterSetupSection(
    modifier: Modifier = Modifier,
    autoUpdateNotification: String,
    autoUpdateFrequency: String,
    autoUpdateWifiOnly: Boolean,
    autoUpdateEnabled: Boolean,
    filterBuildMode: String = AppPreferences.BUILD_MODE_ONLINE,
    showFilterListSetting: Boolean = true,
    onNavigateToFilterSetup: () -> Unit = {},
    onSetFilterBuildMode: (String) -> Unit = {},
    onSetAutoUpdateWifiOnly: (Boolean) -> Unit = {},
    onSetAutoUpdateFrequency: (String) -> Unit = {},
    onSetAutoUpdateNotification: (String) -> Unit = {},
    onSetAutoUpdateEnable: (Boolean) -> Unit = {},
    onNavigateToGeoIp: () -> Unit = {},
    onOpenFrequencyPane: (() -> Unit)? = null,
    onOpenNotificationPane: (() -> Unit)? = null,
    onOpenBuildModePane: (() -> Unit)? = null,
) {
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    var showBuildModeDialog by remember { mutableStateOf(false) }
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_filters),
            description = stringResource(R.string.settings_category_filters_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingsCard {
            Column {
                // 0. Filter Setup item (shown when Filter tab is not on bottom navigation)
                if (showFilterListSetting) {
                    SettingItem(
                        iconPainter = painterResource(R.drawable.ic_shield),
                        iconTint = Color(0xFF7C3AED),
                        title = if (app.pwhs.blockads.BuildConfig.IS_STORE) stringResource(R.string.settings_category_filters) else stringResource(R.string.filter_setup_title),
                        desc = stringResource(R.string.settings_category_filters_desc),
                        onClick = onNavigateToFilterSetup
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)
                }

                // 1. Auto-update toggle
                SettingsToggleItem(
                    iconPainter = painterResource(R.drawable.ic_settings_auto_update),
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
                        iconPainter = painterResource(R.drawable.ic_settings_network_delay),
                        iconTint = Color(0xFF059669),
                        title = stringResource(R.string.settings_auto_update_frequency),
                        desc = frequencyDesc,
                        onClick = { onOpenFrequencyPane?.invoke() ?: run { showFrequencyDialog = true } }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                    // 4. Wi-Fi only
                    SettingsToggleItem(
                        iconPainter = painterResource(R.drawable.ic_settings_trusted_wifi),
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
                        iconPainter = painterResource(R.drawable.ic_settings_notification_bell),
                        iconTint = Color(0xFF059669),
                        title = stringResource(R.string.settings_auto_update_notification),
                        desc = notificationDesc,
                        onClick = { onOpenNotificationPane?.invoke() ?: run { showNotificationDialog = true } }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // Filter Build Mode (Server vs Local)
                val buildModeDesc = when (filterBuildMode) {
                    AppPreferences.BUILD_MODE_LOCAL -> stringResource(R.string.filter_build_mode_local)
                    else -> stringResource(R.string.filter_build_mode_server)
                }
                SettingItem(
                    iconPainter = painterResource(R.drawable.ic_settings_filter_lists),
                    iconTint = Color(0xFFD97706),
                    title = stringResource(R.string.filter_build_mode),
                    desc = buildModeDesc,
                    onClick = { onOpenBuildModePane?.invoke() ?: run { showBuildModeDialog = true } }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // GeoIP Database Management
                SettingItem(
                    iconPainter = painterResource(R.drawable.ic_settings_network_delay),
                    iconTint = Color(0xFF2563EB),
                    title = stringResource(R.string.settings_geoip_title),
                    desc = stringResource(R.string.settings_geoip_subtitle),
                    onClick = onNavigateToGeoIp
                )
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

    if (showBuildModeDialog) {
        BuildModeDialog(
            currentMode = filterBuildMode,
            onModeSelected = { mode ->
                onSetFilterBuildMode(mode)
                showBuildModeDialog = false
            },
            onDismiss = { showBuildModeDialog = false }
        )
    }
}