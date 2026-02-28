package app.pwhs.blockads.ui.profile.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ProfileSchedule
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.util.formatDays
import app.pwhs.blockads.util.formatTime

@Composable
fun ScheduleItem(
    schedule: ProfileSchedule,
    profileName: String,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${formatTime(schedule.startHour, schedule.startMinute)} – ${
                    formatTime(
                        schedule.endHour,
                        schedule.endMinute
                    )
                }",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = formatDays(schedule.daysOfWeek),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Switch(
            checked = schedule.isEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}