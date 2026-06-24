package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

import app.pwhs.blockads.ui.settings.component.SettingsToggleItem

import androidx.compose.runtime.Composable

@Composable
fun DeviceOwnerSection(
    lockdownEnabled: Boolean,
    restrictionsEnforced: Boolean,
    onSetRestrictionsEnforced: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.settings_device_owner_section_title),
            icon = Icons.Default.AdminPanelSettings,
            description = stringResource(R.string.settings_device_owner_section_desc)
        )
        
        SettingsCard {
            SettingsToggleItem(
                icon = Icons.Default.AdminPanelSettings,
                title = stringResource(R.string.settings_device_owner_enforce_restrictions),
                subtitle = stringResource(R.string.settings_device_owner_enforce_restrictions_desc),
                isChecked = restrictionsEnforced,
                modifier = Modifier.alpha(if (lockdownEnabled) 0.5f else 1f),
                onCheckedChange = {
                    if (!lockdownEnabled) onSetRestrictionsEnforced(it)
                }
            )
        }
    }
}
