package app.pwhs.blockads.ui.profile.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.TextSecondary

@Composable
fun AddScheduleDialog(
    onDismiss: () -> Unit,
    onAdd: (Int, Int, Int, Int, String) -> Unit
) {
    var startHour by rememberSaveable { mutableIntStateOf(18) }
    var startMinute by rememberSaveable { mutableIntStateOf(0) }
    var endHour by rememberSaveable { mutableIntStateOf(8) }
    var endMinute by rememberSaveable { mutableIntStateOf(0) }
    var selectedDays by rememberSaveable { mutableStateOf(setOf(1, 2, 3, 4, 5, 6, 7)) }

    val dayLabels = listOf(
        1 to stringResource(R.string.profile_day_mon),
        2 to stringResource(R.string.profile_day_tue),
        3 to stringResource(R.string.profile_day_wed),
        4 to stringResource(R.string.profile_day_thu),
        5 to stringResource(R.string.profile_day_fri),
        6 to stringResource(R.string.profile_day_sat),
        7 to stringResource(R.string.profile_day_sun)
    )

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_add_schedule)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.profile_schedule_start),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = startHour.toString(),
                        onValueChange = { startHour = it.toIntOrNull()?.coerceIn(0, 23) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                    Text(":")
                    OutlinedTextField(
                        value = startMinute.toString().padStart(2, '0'),
                        onValueChange = { startMinute = it.toIntOrNull()?.coerceIn(0, 59) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.profile_schedule_end),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = endHour.toString(),
                        onValueChange = { endHour = it.toIntOrNull()?.coerceIn(0, 23) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                    Text(":")
                    OutlinedTextField(
                        value = endMinute.toString().padStart(2, '0'),
                        onValueChange = { endMinute = it.toIntOrNull()?.coerceIn(0, 59) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.profile_schedule_days),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    dayLabels.forEach { (dayNum, label) ->
                        val isSelected = dayNum in selectedDays
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    selectedDays = if (isSelected) selectedDays - dayNum
                                    else selectedDays + dayNum
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val days = selectedDays.sorted().joinToString(",")
                    onAdd(startHour, startMinute, endHour, endMinute, days)
                },
                enabled = selectedDays.isNotEmpty()
            ) {
                Text(stringResource(R.string.settings_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}