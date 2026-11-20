package app.pwhs.blockads.ui.logs.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.ui.logs.data.BlockSource
import app.pwhs.blockads.ui.logs.data.LogGrouping
import app.pwhs.blockads.ui.logs.data.groupLogs
import app.pwhs.blockads.utils.formatTimestamp
import kotlinx.coroutines.launch

@Composable
fun LogsFeedList(
    logs: List<DnsLogEntry>,
    grouping: LogGrouping,
    filterSignature: Any,
    whitelistedDomains: Set<String>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    filterNames: Map<String, String>,
    onSelectEntry: (DnsLogEntry) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onQuickBlock: (String) -> Unit,
    onQuickWhitelist: (String) -> Unit,
    onFilterApp: (String) -> Unit,
    onFilterQueryType: (String) -> Unit,
    onFilterBlockSource: (BlockSource) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val unknownAppLabel = stringResource(R.string.log_unknown_app)

    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24
        }
    }

    // Freeze live list updates while the user scrolls through older logs to prevent jumpiness.
    var frozenLogs by remember { mutableStateOf<List<DnsLogEntry>?>(null) }
    var expandedGroups by remember(grouping) { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(filterSignature, grouping) {
        frozenLogs = null
        listState.scrollToItem(0)
    }

    LaunchedEffect(isAtTop, logs) {
        if (isAtTop || logs.isEmpty()) {
            frozenLogs = null
        } else if (frozenLogs == null) {
            frozenLogs = logs
        }
    }

    val displayedLogs = frozenLogs ?: logs
    val pendingNewCount = remember(logs, frozenLogs) {
        val snapshot = frozenLogs ?: return@remember 0
        val topId = snapshot.firstOrNull()?.id ?: return@remember logs.size
        logs.count { it.id > topId }
    }

    val groupedLogs = remember(displayedLogs, grouping, unknownAppLabel) {
        if (grouping == LogGrouping.NONE) emptyList()
        else groupLogs(displayedLogs, grouping, unknownAppLabel)
    }

    val scrollTimeHint by remember(displayedLogs, groupedLogs, grouping) {
        derivedStateOf {
            if (!listState.isScrollInProgress) return@derivedStateOf null
            val idx = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
            val ts = if (grouping == LogGrouping.NONE) {
                displayedLogs.getOrNull(idx)?.timestamp
            } else {
                groupedLogs.getOrNull(idx)?.latestTimestamp
            }
            ts?.let { formatTimestamp(it) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "header_spacer") { Spacer(modifier = Modifier.height(4.dp)) }

            if (grouping == LogGrouping.NONE) {
                items(displayedLogs, key = { it.id }) { entry ->
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
            } else {
                items(groupedLogs, key = { "${grouping.name}:${it.key}" }) { group ->
                    val isExpanded = group.key in expandedGroups
                    LogGroupCard(
                        group = group,
                        grouping = grouping,
                        isExpanded = isExpanded,
                        whitelistedDomains = whitelistedDomains,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        filterNames = filterNames,
                        onToggleExpand = {
                            expandedGroups = if (isExpanded) {
                                expandedGroups - group.key
                            } else {
                                expandedGroups + group.key
                            }
                        },
                        onSelectEntry = onSelectEntry,
                        onToggleSelection = onToggleSelection,
                        onQuickBlock = onQuickBlock,
                        onQuickWhitelist = onQuickWhitelist,
                        onFilterApp = onFilterApp,
                        onFilterQueryType = onFilterQueryType,
                        onFilterBlockSource = onFilterBlockSource
                    )
                }
            }

            item(key = "footer_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // Jump to latest pill when scrolled away from top
        AnimatedVisibility(
            visible = !isAtTop,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        ) {
            Surface(
                onClick = {
                    frozenLogs = null
                    coroutineScope.launch { listState.animateScrollToItem(0) }
                },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (pendingNewCount > 0) {
                            stringResource(R.string.log_jump_to_latest_count, pendingNewCount)
                        } else {
                            stringResource(R.string.log_jump_to_latest)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Floating timestamp hint while actively scrolling
        AnimatedVisibility(
            visible = scrollTimeHint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 22.dp)
        ) {
            scrollTimeHint?.let { hint ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 2.dp
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
