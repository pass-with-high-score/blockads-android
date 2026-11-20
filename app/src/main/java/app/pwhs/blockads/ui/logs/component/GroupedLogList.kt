package app.pwhs.blockads.ui.logs.component

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.ui.logs.data.BlockSource
import app.pwhs.blockads.ui.logs.data.LogGroup
import app.pwhs.blockads.ui.logs.data.LogGrouping
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.utils.formatTimestamp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun LogGroupCard(
    group: LogGroup,
    grouping: LogGrouping,
    isExpanded: Boolean,
    whitelistedDomains: Set<String>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    filterNames: Map<String, String>,
    onToggleExpand: () -> Unit,
    onSelectEntry: (DnsLogEntry) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onQuickBlock: (String) -> Unit,
    onQuickWhitelist: (String) -> Unit,
    onFilterApp: (String) -> Unit,
    onFilterQueryType: (String) -> Unit,
    onFilterBlockSource: (BlockSource) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appIcon: Drawable? = remember(group.packageName, grouping) {
        if (grouping == LogGrouping.BY_APP && group.packageName.contains(".")) {
            try {
                val icon = context.packageManager.getApplicationIcon(group.packageName)
                if (icon.intrinsicWidth > 0 && icon.intrinsicHeight > 0) icon else null
            } catch (_: Exception) {
                null
            }
        } else null
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "groupChevron"
    )
    val hasBlocked = group.blockedCount > 0
    val indicatorColor = if (hasBlocked) DangerRed else MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (appIcon != null) {
                    Image(
                        painter = rememberDrawablePainter(drawable = appIcon),
                        contentDescription = group.title,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(indicatorColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hasBlocked) Icons.Default.Block else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = indicatorColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimestamp(group.latestTimestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        if (group.subtitle.isNotEmpty()) {
                            Text(text = "·", color = TextSecondary)
                            Text(
                                text = group.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (group.blockedCount > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DangerRed.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.log_group_blocked_badge, group.blockedCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DangerRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${group.totalCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    group.entries.forEach { entry ->
                        LogEntryItem(
                            entry = entry,
                            isWhitelisted = whitelistedDomains.contains(entry.domain.lowercase()),
                            isSelectionMode = selectionMode,
                            isSelected = selectedIds.contains(entry.id),
                            filterNames = filterNames,
                            onTap = { onSelectEntry(entry) },
                            onLongPress = { onToggleSelection(entry.id) },
                            onToggleSelection = { onToggleSelection(entry.id) },
                            onQuickBlock = { onQuickBlock(entry.domain) },
                            onQuickWhitelist = { onQuickWhitelist(entry.domain) },
                            onFilterApp = onFilterApp,
                            onFilterQueryType = onFilterQueryType,
                            onFilterBlockSource = onFilterBlockSource
                        )
                    }
                }
            }
        }
    }
}
