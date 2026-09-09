package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun DeviceOwnerSection(
    lockdownEnabled: Boolean,
    restrictionsEnforced: Boolean,
    onSetRestrictionsEnforced: (Boolean) -> Unit,
    onClearDeviceOwner: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.settings_device_owner_section_title),
            icon = Icons.Default.AdminPanelSettings,
            description = stringResource(R.string.settings_device_owner_section_desc)
        )
        
        SettingsCard {
            Column {
                SettingsToggleItem(
                    icon = Icons.Default.AdminPanelSettings,
                    title = stringResource(R.string.settings_device_owner_enforce_restrictions),
                    subtitle = stringResource(R.string.settings_device_owner_enforce_restrictions_desc),
                    isChecked = restrictionsEnforced,
                    enabled = !lockdownEnabled,
                    modifier = Modifier.alpha(if (lockdownEnabled) 0.5f else 1f),
                    onCheckedChange = {
                        if (!lockdownEnabled) onSetRestrictionsEnforced(it)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )

                SettingsClickItem(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.settings_device_owner_clear_owner),
                    subtitle = stringResource(R.string.settings_device_owner_clear_owner_desc),
                    iconTint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.alpha(if (lockdownEnabled) 0.5f else 1f),
                    onClick = {
                        if (!lockdownEnabled) {
                            showConfirmDialog = true
                        }
                    }
                )
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(stringResource(R.string.clear_device_owner_confirm_title))
            },
            text = {
                Text(stringResource(R.string.clear_device_owner_confirm_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        onClearDeviceOwner()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

