package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun NotificationsSection(
    dailySummaryEnabled: Boolean,
    milestoneNotificationsEnabled: Boolean,
    onSetDailySummaryEnabled: (Boolean) -> Unit,
    onSetMilestoneNotificationsEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_notifications),
            icon = Icons.Default.Notifications,
            description = stringResource(R.string.settings_category_notifications_desc)
        )
        SettingsCard {
            Column {
                SettingsToggleItem(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_daily_summary),
                    subtitle = stringResource(R.string.settings_daily_summary_desc),
                    isChecked = dailySummaryEnabled,
                    onCheckedChange = onSetDailySummaryEnabled
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingsToggleItem(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_milestone_notifications),
                    subtitle = stringResource(R.string.settings_milestone_notifications_desc),
                    isChecked = milestoneNotificationsEnabled,
                    onCheckedChange = onSetMilestoneNotificationsEnabled
                )
            }
        }
    }
}
