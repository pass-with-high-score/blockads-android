package app.pwhs.blockads.ui.firewall.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun FirewallRuleDialog(
    appName: String,
    existingRule: FirewallRule?,
    packageName: String,
    onSave: (FirewallRule) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var blockWifi by remember { mutableStateOf(existingRule?.blockWifi ?: true) }
    var blockMobileData by remember { mutableStateOf(existingRule?.blockMobileData ?: true) }
    var scheduleEnabled by remember { mutableStateOf(existingRule?.scheduleEnabled ?: false) }
    var startHour by remember { mutableIntStateOf(existingRule?.scheduleStartHour ?: 22) }
    var startMinute by remember { mutableIntStateOf(existingRule?.scheduleStartMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(existingRule?.scheduleEndHour ?: 6) }
    var endMinute by remember { mutableIntStateOf(existingRule?.scheduleEndMinute ?: 0) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.firewall_configure),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Block Wi-Fi toggle
                SwitchRow(
                    label = stringResource(R.string.firewall_block_wifi),
                    checked = blockWifi,
                    onCheckedChange = { blockWifi = it }
                )

                // Block Mobile Data toggle
                SwitchRow(
                    label = stringResource(R.string.firewall_block_mobile),
                    checked = blockMobileData,
                    onCheckedChange = { blockMobileData = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Schedule toggle
                SwitchRow(
                    label = stringResource(R.string.firewall_schedule),
                    description = stringResource(R.string.firewall_schedule_desc),
                    checked = scheduleEnabled,
                    onCheckedChange = { scheduleEnabled = it }
                )

                if (scheduleEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.firewall_schedule_from),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%02d:%02d",
                                    startHour,
                                    startMinute
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row {
                                TextButton(onClick = {
                                    startHour = (startHour + 1) % 24
                                }) { Text(stringResource(R.string.firewall_schedule_hour_plus)) }
                                TextButton(onClick = {
                                    startMinute = (startMinute + 30) % 60
                                }) { Text(stringResource(R.string.firewall_schedule_minute_plus)) }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.firewall_schedule_to),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%02d:%02d",
                                    endHour,
                                    endMinute
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row {
                                TextButton(onClick = {
                                    endHour = (endHour + 1) % 24
                                }) { Text(stringResource(R.string.firewall_schedule_hour_plus)) }
                                TextButton(onClick = {
                                    endMinute = (endMinute + 30) % 60
                                }) { Text(stringResource(R.string.firewall_schedule_minute_plus)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    FirewallRule(
                        id = existingRule?.id ?: 0,
                        packageName = packageName,
                        blockWifi = blockWifi,
                        blockMobileData = blockMobileData,
                        scheduleEnabled = scheduleEnabled,
                        scheduleStartHour = startHour,
                        scheduleStartMinute = startMinute,
                        scheduleEndHour = endHour,
                        scheduleEndMinute = endMinute,
                        isEnabled = true
                    )
                )
            }) {
                Text(
                    stringResource(android.R.string.ok),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            Row {
                if (existingRule != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
