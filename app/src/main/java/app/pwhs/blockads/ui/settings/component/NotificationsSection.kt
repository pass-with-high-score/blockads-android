package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_notifications),
            description = stringResource(R.string.settings_category_notifications_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingsCard {
            Column {
                SettingsToggleItem(
                    icon = Icons.Default.Notifications,
                    iconTint = Color(0xFF3B82F6),
                    title = stringResource(R.string.settings_daily_summary),
                    subtitle = stringResource(R.string.settings_daily_summary_desc),
                    isChecked = dailySummaryEnabled,
                    onCheckedChange = onSetDailySummaryEnabled
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = dividerColor
                )
                SettingsToggleItem(
                    icon = Icons.Default.Celebration,
                    iconTint = Color(0xFFF59E0B),
                    title = stringResource(R.string.settings_milestone_notifications),
                    subtitle = stringResource(R.string.settings_milestone_notifications_desc),
                    isChecked = milestoneNotificationsEnabled,
                    onCheckedChange = onSetMilestoneNotificationsEnabled
                )
            }
        }
    }
}
