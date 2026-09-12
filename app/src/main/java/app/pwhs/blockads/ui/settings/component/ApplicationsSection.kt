package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun ApplicationsSection(
    onNavigateToWhitelistApps: () -> Unit,
    onNavigateToAppManagement: () -> Unit,
    onNavigateToTrustedNetworks: () -> Unit = {},
    excludeLan: Boolean = true,
    onSetExcludeLan: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_apps),
            description = stringResource(R.string.settings_category_apps_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingsCard {
            Column {
                // 1. App Whitelist
                SettingItem(
                    icon = Icons.Default.AppBlocking,
                    iconTint = Color(0xFFEA580C),
                    title = stringResource(R.string.settings_whitelist_apps),
                    desc = stringResource(R.string.settings_whitelist_apps_desc),
                    onClick = onNavigateToWhitelistApps
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 2. App Management
                SettingItem(
                    icon = Icons.Default.Apps,
                    iconTint = Color(0xFFEA580C),
                    title = stringResource(R.string.app_management_title),
                    desc = stringResource(R.string.app_management_desc),
                    onClick = onNavigateToAppManagement
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 3. Trusted Wi-Fi Networks
                SettingItem(
                    icon = Icons.Default.Wifi,
                    iconTint = Color(0xFF059669),
                    title = stringResource(R.string.trusted_networks_title),
                    desc = stringResource(R.string.trusted_networks_settings_desc),
                    onClick = onNavigateToTrustedNetworks
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 4. Exclude LAN Traffic (Split Kill Switch)
                SettingsToggleItem(
                    icon = Icons.Default.Lan,
                    iconTint = Color(0xFF059669),
                    title = stringResource(R.string.exclude_lan_title),
                    subtitle = stringResource(R.string.exclude_lan_description),
                    isChecked = excludeLan,
                    onCheckedChange = onSetExcludeLan
                )
            }
        }
    }
}
