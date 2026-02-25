package app.pwhs.blockads.ui.firewall.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.FirewallRule
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.ui.whitelist.data.AppInfoData
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun FirewallAppItem(
    app: AppInfoData,
    rule: FirewallRule?,
    onToggle: () -> Unit,
    onConfigure: () -> Unit
) {
    val isBlocked = rule?.isEnabled == true
    val backgroundColor by animateColorAsState(
        targetValue = if (isBlocked)
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        else
            Color.Transparent,
        animationSpec = tween(300),
        label = "firewall_bg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { if (isBlocked) onConfigure() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            Image(
                painter = rememberDrawablePainter(drawable = app.icon),
                contentDescription = app.label,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // App info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (isBlocked && rule != null) {
                    val statusParts = mutableListOf<String>()
                    if (rule.blockWifi) statusParts.add(stringResource(R.string.firewall_network_wifi))
                    if (rule.blockMobileData) statusParts.add(stringResource(R.string.firewall_network_mobile))
                    val networkText = if (statusParts.size == 2) {
                        stringResource(R.string.firewall_network_all)
                    } else {
                        statusParts.joinToString()
                    }
                    val scheduleText = if (rule.scheduleEnabled) {
                        stringResource(
                            R.string.firewall_status_schedule,
                            rule.scheduleStartHour,
                            rule.scheduleStartMinute,
                            rule.scheduleEndHour,
                            rule.scheduleEndMinute
                        )
                    } else {
                        ""
                    }
                    Text(
                        text = stringResource(R.string.firewall_app_blocked) + " ($networkText$scheduleText)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Toggle
            Switch(
                checked = isBlocked,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.error
                )
            )
        }
    }
}
