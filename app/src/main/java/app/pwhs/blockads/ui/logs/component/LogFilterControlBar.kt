package app.pwhs.blockads.ui.logs.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.logs.data.BlockSource
import app.pwhs.blockads.ui.logs.data.LogFilterStatus
import app.pwhs.blockads.ui.logs.data.TimeRange
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.NeonGreen
import app.pwhs.blockads.ui.theme.WhitelistAmber

@Composable
fun LogFilterControlBar(
    filterStatus: LogFilterStatus,
    onFilterStatusChange: (LogFilterStatus) -> Unit,
    timeRange: TimeRange,
    onTimeRangeChange: (TimeRange) -> Unit,
    appFilter: String,
    onOpenAppFilter: () -> Unit,
    onClearAppFilter: () -> Unit,
    selectedBlockSources: Set<BlockSource> = BlockSource.entries.toSet(),
    onToggleBlockSource: (BlockSource) -> Unit = {},
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isTimeDropdownExpanded by remember { mutableStateOf(false) }

    val statusTabs = listOf(
        Triple(LogFilterStatus.ALL, R.string.logs_filter_all, Icons.Default.Dns),
        Triple(LogFilterStatus.BLOCKED, R.string.logs_filter_blocked, Icons.Default.Block),
        Triple(LogFilterStatus.ALLOWED, R.string.log_status_allowed, Icons.Default.CheckCircle)
    )
    val selectedTabIndex = when (filterStatus) {
        LogFilterStatus.ALL -> 0
        LogFilterStatus.BLOCKED -> 1
        LogFilterStatus.ALLOWED -> 2
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Status secondary tab row (Zero horizontal scroll needed!)
        SecondaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = {
                val indicatorColor = when (selectedTabIndex) {
                    1 -> DangerRed
                    2 -> NeonGreen
                    else -> MaterialTheme.colorScheme.primary
                }
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                    color = indicatorColor,
                    height = 2.5.dp
                )
            },
            divider = {}
        ) {
            statusTabs.forEachIndexed { index, (status, labelRes, icon) ->
                val isSelected = selectedTabIndex == index
                val itemColor = when (status) {
                    LogFilterStatus.BLOCKED -> DangerRed
                    LogFilterStatus.ALLOWED -> NeonGreen
                    LogFilterStatus.ALL -> MaterialTheme.colorScheme.primary
                }
                Tab(
                    selected = isSelected,
                    onClick = { onFilterStatusChange(status) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) itemColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(labelRes),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                                color = if (isSelected) itemColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.height(44.dp)
                )
            }
        }

        // Quick Filters Row: Time Dropdown + App Filter Sheet Trigger + Reset
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time Dropdown Chip
            Box {
                val timeLabelRes = when (timeRange) {
                    TimeRange.ALL -> R.string.log_time_range_all
                    TimeRange.HOUR_1 -> R.string.log_time_range_1h
                    TimeRange.HOUR_6 -> R.string.log_time_range_6h
                    TimeRange.HOUR_24 -> R.string.log_time_range_24h
                    TimeRange.DAY_7 -> R.string.log_time_range_7d
                }
                FilterChip(
                    selected = timeRange != TimeRange.ALL,
                    onClick = { isTimeDropdownExpanded = true },
                    label = {
                        Text(
                            text = stringResource(timeLabelRes),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )

                DropdownMenu(
                    expanded = isTimeDropdownExpanded,
                    onDismissRequest = { isTimeDropdownExpanded = false }
                ) {
                    val ranges = listOf(
                        TimeRange.ALL to R.string.log_time_range_all,
                        TimeRange.HOUR_1 to R.string.log_time_range_1h,
                        TimeRange.HOUR_6 to R.string.log_time_range_6h,
                        TimeRange.HOUR_24 to R.string.log_time_range_24h,
                        TimeRange.DAY_7 to R.string.log_time_range_7d
                    )
                    ranges.forEach { (range, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(label),
                                    fontWeight = if (timeRange == range) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onTimeRangeChange(range)
                                isTimeDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // App Filter Chip
            val isAppFiltered = appFilter.isNotEmpty()
            FilterChip(
                selected = isAppFiltered,
                onClick = onOpenAppFilter,
                label = {
                    Text(
                        text = if (isAppFiltered) appFilter else stringResource(R.string.log_filter_all_apps),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                trailingIcon = {
                    if (isAppFiltered) {
                        IconButton(
                            onClick = onClearAppFilter,
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = WhitelistAmber.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f, fill = false)
            )

            // Reset Filter Button (visible if any custom filter is active)
            val isBlockSourceFiltered = selectedBlockSources.size < BlockSource.entries.size
            val hasActiveFilter = isAppFiltered || timeRange != TimeRange.ALL || filterStatus != LogFilterStatus.ALL || isBlockSourceFiltered
            AnimatedVisibility(
                visible = hasActiveFilter,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(
                    onClick = onResetFilters,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAltOff,
                        contentDescription = stringResource(R.string.log_reset_filters),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Block Source Filter Row (visible when filterStatus == LogFilterStatus.BLOCKED)
        BlockSourceFilterRow(
            visible = filterStatus == LogFilterStatus.BLOCKED,
            selectedSources = selectedBlockSources,
            onToggleSource = onToggleBlockSource,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}
