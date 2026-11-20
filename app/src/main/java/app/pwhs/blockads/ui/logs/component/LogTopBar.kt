package app.pwhs.blockads.ui.logs.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.logs.data.LogGrouping
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    isSearchVisible: Boolean,
    recordDnsLogs: Boolean,
    onNavigateBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleRecordDnsLogs: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
    logRetentionDays: Int = 0,
    onOpenRetentionSettings: () -> Unit = {},
    grouping: LogGrouping = LogGrouping.NONE,
    onGroupingChange: (LogGrouping) -> Unit = {},
) {
    var showGroupingMenu by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.accessibility_navigate_back)
                )
            }
        },
        title = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "top_bar_title"
            ) { isSelecting ->
                if (isSelecting) {
                    Text(
                        text = stringResource(R.string.log_bulk_selected, selectedCount),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                } else {
                    Box {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showGroupingMenu = true }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.nav_logs),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            if (grouping != LogGrouping.NONE) {
                                val groupLabelRes = when (grouping) {
                                    LogGrouping.BY_DOMAIN -> R.string.log_group_by_domain
                                    LogGrouping.BY_APP -> R.string.log_group_by_app
                                    LogGrouping.NONE -> R.string.log_group_none
                                }
                                Text(
                                    text = stringResource(groupLabelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showGroupingMenu,
                            onDismissRequest = { showGroupingMenu = false }
                        ) {
                            val options = listOf(
                                LogGrouping.NONE to R.string.log_group_none,
                                LogGrouping.BY_DOMAIN to R.string.log_group_by_domain,
                                LogGrouping.BY_APP to R.string.log_group_by_app
                            )
                            options.forEach { (mode, labelRes) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(labelRes),
                                            fontWeight = if (grouping == mode) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    trailingIcon = if (grouping == mode) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        onGroupingChange(mode)
                                        showGroupingMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        actions = {
            // Search toggle
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = stringResource(R.string.log_search_hint),
                    tint = if (isSearchVisible) MaterialTheme.colorScheme.primary else TextSecondary
                )
            }

            // Pause/Resume recording logs toggle
            IconButton(onClick = onToggleRecordDnsLogs) {
                Icon(
                    imageVector = if (recordDnsLogs) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = stringResource(
                        if (recordDnsLogs) R.string.log_pause_recording else R.string.log_resume_recording
                    ),
                    tint = if (recordDnsLogs) MaterialTheme.colorScheme.onSurfaceVariant else DangerRed
                )
            }

            // Export logs
            IconButton(onClick = onExportLogs) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = "Export logs",
                    tint = TextSecondary
                )
            }

            // Auto-delete old logs setting
            IconButton(onClick = onOpenRetentionSettings) {
                Icon(
                    imageVector = Icons.Default.AutoDelete,
                    contentDescription = stringResource(R.string.log_retention_title),
                    tint = if (logRetentionDays > 0) MaterialTheme.colorScheme.primary else TextSecondary
                )
            }

            // Clear logs
            IconButton(onClick = onClearLogs) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Clear logs",
                    tint = TextSecondary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
