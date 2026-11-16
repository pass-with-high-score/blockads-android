package app.pwhs.blockads.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.ui.component.UndoSnackbarHost
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.logs.component.AppFilterBottomSheet
import app.pwhs.blockads.ui.logs.component.DomainDetailBottomSheet
import app.pwhs.blockads.ui.logs.component.DomainDetailContent
import app.pwhs.blockads.ui.logs.component.LogEntryItem
import app.pwhs.blockads.ui.logs.component.LogFilterControlBar
import app.pwhs.blockads.ui.logs.component.LogSearchBar
import app.pwhs.blockads.ui.logs.component.LogTopBar
import app.pwhs.blockads.ui.logs.data.LogFilterStatus
import app.pwhs.blockads.ui.logs.data.TimeRange
import app.pwhs.blockads.ui.logs.dialog.ConfirmClearLogDialog
import app.pwhs.blockads.ui.theme.SecurityOrange
import app.pwhs.blockads.ui.theme.TextSecondary
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    modifier: Modifier = Modifier,
    initialFilterStatus: LogFilterStatus = LogFilterStatus.ALL,
    initialSearchQuery: String = "",
    viewModel: LogViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = { }
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val filterStatus by viewModel.filterStatus.collectAsStateWithLifecycle()
    val filterNames by viewModel.filterNames.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val timeRange by viewModel.timeRange.collectAsStateWithLifecycle()
    val appFilter by viewModel.appFilter.collectAsStateWithLifecycle()
    val appNames by viewModel.appNames.collectAsStateWithLifecycle()
    val appPackages by viewModel.appPackages.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val whitelistedDomains by viewModel.whitelistedDomains.collectAsStateWithLifecycle()
    val recordDnsLogs by viewModel.recordDnsLogs.collectAsStateWithLifecycle()
    val logRetentionDays by viewModel.logRetentionDays.collectAsStateWithLifecycle()

    var isSearchVisible by remember { mutableStateOf(initialSearchQuery.isNotEmpty()) }
    var showAppFilterSheet by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<DnsLogEntry?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }

    val appSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resource = LocalResources.current

    UiEventEffect(viewModel.events)

    LaunchedEffect(viewModel) {
        viewModel.undoEvents.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val undoLabel = resource.getString(R.string.action_undo)
            val message = when (event) {
                is LogUndoEvent.Whitelisted ->
                    resource.getString(R.string.log_whitelisted, event.domain)
                is LogUndoEvent.Blocked ->
                    resource.getString(R.string.log_blocked_named, event.domain)
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (event) {
                    is LogUndoEvent.Whitelisted -> viewModel.undoWhitelist(event.domain)
                    is LogUndoEvent.Blocked -> viewModel.undoBlock(event.domain)
                }
            }
        }
    }

    LaunchedEffect(initialFilterStatus) {
        if (initialFilterStatus != LogFilterStatus.ALL) {
            viewModel.setFilterStatus(initialFilterStatus)
        }
    }

    LaunchedEffect(initialSearchQuery) {
        if (initialSearchQuery.isNotEmpty()) {
            viewModel.setSearchQuery(initialSearchQuery)
            isSearchVisible = true
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { UndoSnackbarHost(snackbarHostState) },
        topBar = {
            LogTopBar(
                selectionMode = selectionMode,
                selectedCount = selectedIds.size,
                isSearchVisible = isSearchVisible,
                recordDnsLogs = recordDnsLogs,
                onNavigateBack = {
                    if (selectionMode) {
                        viewModel.clearSelection()
                    } else {
                        onNavigateBack()
                    }
                },
                onToggleSearch = { isSearchVisible = !isSearchVisible },
                onToggleRecordDnsLogs = { viewModel.setRecordDnsLogs(!recordDnsLogs) },
                onExportLogs = { viewModel.exportLogs() },
                onClearLogs = { showClearConfirm = true },
                logRetentionDays = logRetentionDays,
                onOpenRetentionSettings = { showRetentionDialog = true }
            )
        }
    ) { innerPadding ->
        val logsListPane: @Composable (Modifier) -> Unit = { listModifier ->
            Column(modifier = listModifier) {
            // Search bar
            LogSearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) },
                visible = isSearchVisible
            )

            // Paused notice banner if recording is turned off
            AnimatedVisibility(
                visible = !recordDnsLogs,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SecurityOrange.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PauseCircle,
                            contentDescription = null,
                            tint = SecurityOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.log_recording_paused_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(onClick = { viewModel.setRecordDnsLogs(true) }) {
                        Text(
                            text = stringResource(R.string.log_resume_recording),
                            style = MaterialTheme.typography.labelMedium,
                            color = SecurityOrange
                        )
                    }
                }
            }

            // Compact Filter Bar (Status TabRow + Time Dropdown + App Sheet trigger)
            LogFilterControlBar(
                filterStatus = filterStatus,
                onFilterStatusChange = { viewModel.setFilterStatus(it) },
                timeRange = timeRange,
                onTimeRangeChange = { viewModel.setTimeRange(it) },
                appFilter = appFilter,
                onOpenAppFilter = { showAppFilterSheet = true },
                onClearAppFilter = { viewModel.setAppFilter("") },
                onResetFilters = {
                    viewModel.setAppFilter("")
                    viewModel.setTimeRange(TimeRange.ALL)
                    viewModel.setSearchQuery("")
                    viewModel.setFilterStatus(LogFilterStatus.ALL)
                }
            )

            // Main log list or empty state
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No results for \"$searchQuery\""
                            else stringResource(R.string.logs_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    items(logs, key = { it.id }) { entry ->
                        val isDomainWhitelisted = whitelistedDomains.contains(
                            entry.domain.lowercase()
                        )
                        LogEntryItem(
                            entry = entry,
                            isWhitelisted = isDomainWhitelisted,
                            isSelectionMode = selectionMode,
                            isSelected = selectedIds.contains(entry.id),
                            filterNames = filterNames,
                            onTap = { selectedEntry = entry },
                            onLongPress = { viewModel.toggleSelection(entry.id) },
                            onToggleSelection = { viewModel.toggleSelection(entry.id) },
                            onQuickBlock = { viewModel.addToCustomBlockRules(entry.domain) },
                            onQuickWhitelist = { viewModel.addToWhitelist(entry.domain) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isListDetail = rememberIsExpandedLayout(maxWidth)

            if (isListDetail) {
                val activeEntry = selectedEntry
                    ?.takeIf { e -> logs.any { it.id == e.id } }
                    ?: logs.firstOrNull()

                Row(modifier = Modifier.fillMaxSize()) {
                    logsListPane(
                        Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                    )

                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Box(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                    ) {
                        if (activeEntry != null) {
                            val isDomainWhitelisted = whitelistedDomains.contains(activeEntry.domain.lowercase())
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 12.dp)
                            ) {
                                DomainDetailContent(
                                    entry = activeEntry,
                                    isWhitelisted = isDomainWhitelisted,
                                    filterNames = filterNames,
                                    onAddToWhiteList = { viewModel.addToWhitelist(activeEntry.domain) },
                                    onCopyDomain = {
                                        val clipboard =
                                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("domain", activeEntry.domain))
                                        Toast.makeText(
                                            context,
                                            resource.getString(R.string.domain_copied),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onAddToCustomBlockRules = { viewModel.addToCustomBlockRules(activeEntry.domain) },
                                    onAddWildcardWhitelist = { viewModel.addWildcardWhitelist(activeEntry.domain) },
                                    viewModel = viewModel
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.logs_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            } else {
                logsListPane(Modifier.fillMaxSize())

                // Domain detail bottom sheet on compact screens
                selectedEntry?.let { entry ->
                    val isDomainWhitelisted = whitelistedDomains.contains(entry.domain.lowercase())
                    DomainDetailBottomSheet(
                        entry = entry,
                        isWhitelisted = isDomainWhitelisted,
                        filterNames = filterNames,
                        onDismiss = { selectedEntry = null },
                        onAddToWhiteList = {
                            viewModel.addToWhitelist(entry.domain)
                            selectedEntry = null
                        },
                        onCopyDomain = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("domain", entry.domain))
                            Toast.makeText(
                                context,
                                resource.getString(R.string.domain_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                            selectedEntry = null
                        },
                        onAddToCustomBlockRules = {
                            viewModel.addToCustomBlockRules(entry.domain)
                            selectedEntry = null
                        },
                        onAddWildcardWhitelist = {
                            viewModel.addWildcardWhitelist(entry.domain)
                            selectedEntry = null
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    // App Filter BottomSheet
    if (showAppFilterSheet) {
        AppFilterBottomSheet(
            sheetState = appSheetState,
            appNames = appNames,
            selectedApp = appFilter,
            logs = logs,
            allAppPackages = appPackages,
            onSelectApp = { viewModel.setAppFilter(it) },
            onDismiss = { showAppFilterSheet = false }
        )
    }

    // Clear confirmation dialog
    if (showClearConfirm) {
        ConfirmClearLogDialog(
            onClear = {
                viewModel.clearLogs()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    if (showRetentionDialog) {
        app.pwhs.blockads.ui.logs.dialog.LogRetentionDialog(
            selectedDays = logRetentionDays,
            onSelectDays = { viewModel.setLogRetentionDays(it) },
            onDismiss = { showRetentionDialog = false }
        )
    }
}
