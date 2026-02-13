package app.pwhs.blockads.ui.logs.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.pwhs.blockads.R
import app.pwhs.blockads.data.DnsLogEntry
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.NeonGreen
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.ui.theme.WhitelistAmber
import app.pwhs.blockads.util.formatTimestamp

@Composable
fun LogEntryItem(
    entry: DnsLogEntry,
    isWhitelisted: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onToggleSelection: () -> Unit = {},
    onQuickBlock: () -> Unit = {},
    onQuickWhitelist: () -> Unit = {}
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            isWhitelisted -> WhitelistAmber
            entry.isBlocked -> DangerRed
            else -> NeonGreen
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    val statusText = when {
        isWhitelisted -> stringResource(R.string.log_status_whitelisted)
        entry.isBlocked -> stringResource(R.string.log_status_blocked)
        else -> stringResource(R.string.log_status_allowed)
    }

    val statusIcon = when {
        isWhitelisted -> Icons.Default.Shield
        entry.isBlocked -> Icons.Default.Block
        else -> Icons.Default.CheckCircle
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) onToggleSelection() else onTap()
                    },
                    onLongClick = {
                        if (!isSelectionMode) onLongPress()
                    }
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = TextSecondary
                    ),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Status indicator
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.domain,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (entry.appName.isNotEmpty()) {
                    Text(
                        text = entry.appName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "·",
                        color = TextSecondary
                    )
                    Text(
                        text = entry.queryType,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    if (entry.responseTimeMs > 0) {
                        Text(
                            text = "·",
                            color = TextSecondary
                        )
                        Text(
                            text = "${entry.responseTimeMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Quick action button + status label
            if (!isSelectionMode) {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Quick action: Block (for allowed) or Whitelist (for blocked)
                    if (entry.isBlocked && !isWhitelisted) {
                        IconButton(
                            onClick = onQuickWhitelist,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.log_action_unblock),
                                tint = NeonGreen.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else if (!entry.isBlocked && !isWhitelisted) {
                        IconButton(
                            onClick = onQuickBlock,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = stringResource(R.string.log_action_block),
                                tint = DangerRed.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


