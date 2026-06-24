package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockdownSection(
    lockdownEnabled: Boolean,
    lockdownDuration: Long,
    onSetLockdownEnabled: (Boolean) -> Unit,
    onSetLockdownDuration: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val durations = listOf(
        1 * 60 * 1000L to stringResource(R.string.lockdown_duration_1m),
        5 * 60 * 1000L to stringResource(R.string.lockdown_duration_5m),
        10 * 60 * 1000L to stringResource(R.string.lockdown_duration_10m),
        30 * 60 * 1000L to stringResource(R.string.lockdown_duration_30m),
        60 * 60 * 1000L to stringResource(R.string.lockdown_duration_1h),
        6 * 60 * 60 * 1000L to stringResource(R.string.lockdown_duration_6h),
        12 * 60 * 60 * 1000L to stringResource(R.string.lockdown_duration_12h),
        24 * 60 * 60 * 1000L to stringResource(R.string.lockdown_duration_24h)
    )

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.settings_lockdown_section_title),
            icon = Icons.Default.Lock,
            description = stringResource(R.string.settings_lockdown_section_desc)
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Column {
                SettingsToggleItem(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.settings_lockdown_mode_title),
                    subtitle = stringResource(R.string.settings_lockdown_mode_desc),
                    isChecked = lockdownEnabled,
                    onCheckedChange = { onSetLockdownEnabled(it) }
                )

                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )
                    
                    val selectedLabel = durations.find { it.first == lockdownDuration }?.second ?: stringResource(R.string.lockdown_duration_5m)
                    
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_lockdown_duration_title)) },
                        supportingContent = { Text(stringResource(R.string.settings_lockdown_duration_desc)) },
                        trailingContent = {
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier.menuAnchor().width(150.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    durations.forEach { (durationMs, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                onSetLockdownDuration(durationMs)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
