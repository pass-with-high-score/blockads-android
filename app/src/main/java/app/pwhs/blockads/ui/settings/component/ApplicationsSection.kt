package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun ApplicationsSection(
    onNavigateToWhitelistApps: () -> Unit,
    onNavigateToAppManagement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_apps),
            icon = Icons.Default.PhoneAndroid,
            description = stringResource(R.string.settings_category_apps_desc)
        )
        SettingsCard {
            SettingItem(
                icon = Icons.Default.AppBlocking,
                title = stringResource(R.string.settings_whitelist_apps),
                desc = stringResource(R.string.settings_whitelist_apps_desc),
                onClick = onNavigateToWhitelistApps
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
            )
            SettingItem(
                icon = Icons.Default.Apps,
                title = stringResource(R.string.app_management_title),
                desc = stringResource(R.string.app_management_desc),
                onClick = onNavigateToAppManagement
            )
        }
    }
}
