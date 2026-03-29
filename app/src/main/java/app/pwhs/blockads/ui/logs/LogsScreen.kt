package app.pwhs.blockads.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.logs.component.DomainDetailBottomSheet
import app.pwhs.blockads.ui.logs.component.LogEntryItem
import app.pwhs.blockads.ui.logs.data.TimeRange
import app.pwhs.blockads.ui.logs.dialog.ConfirmClearLogDialog
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.ui.theme.WhitelistAmber
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    modifier: Modifier = Modifier,
    viewModel: LogViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = { }
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val showBlockedOnly by viewModel.showBlockedOnly.collectAsStateWithLifecycle()
    val filterNames by viewModel.filterNames.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val timeRange by viewModel.timeRange.collectAsStateWithLifecycle()
    val appFilter by viewModel.appFilter.collectAsStateWithLifecycle()
    val appNames by viewModel.appNames.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val whitelistedDomains by viewModel.whitelistedDomains.collectAsStateWithLifecycle()
    var isSearchVisible by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<DnsLogEntry?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resource = LocalResources.current

    UiEventEffect(viewModel.events)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    if (selectionMode) {
                        Text(
                            stringResource(R.string.log_bulk_selected, selectedIds.size),
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            stringResource(R.string.nav_logs),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isSearchVisible = !isSearchVisible }) {
                        Icon(
                            if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = { viewModel.exportLogs() }) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = "Export logs",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear logs",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // Search bar
            AnimatedVisibility(
                visible = isSearchVisible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = {
                        Text(
                            stringResource(R.string.log_search_hint),
                            color = TextSecondary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = TextSecondary
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )
            }

            // Filter chips row: status + time range
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showBlockedOnly,
                    onClick = { if (showBlockedOnly) viewModel.toggleFilter() },
                    label = { Text(stringResource(R.string.logs_filter_all)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
                FilterChip(
                    selected = showBlockedOnly,
                    onClick = { if (!showBlockedOnly) viewModel.toggleFilter() },
                    label = { Text(stringResource(R.string.logs_filter_blocked)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DangerRed.copy(alpha = 0.15f),
                        selectedLabelColor = DangerRed
                    )
                )
            }

            // Time range filter chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val timeRanges = listOf(
                    TimeRange.ALL to R.string.log_time_range_all,
                    TimeRange.HOUR_1 to R.string.log_time_range_1h,
                    TimeRange.HOUR_6 to R.string.log_time_range_6h,
                    TimeRange.HOUR_24 to R.string.log_time_range_24h,
                    TimeRange.DAY_7 to R.string.log_time_range_7d
                )
                items(timeRanges) { (range, labelRes) ->
                    FilterChip(
                        selected = timeRange == range,
                        onClick = { viewModel.setTimeRange(range) },
                        label = {
                            Text(
                                stringResource(labelRes),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                // App filter chips
                if (appNames.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    item {
                        FilterChip(
                            selected = appFilter.isEmpty(),
                            onClick = { viewModel.setAppFilter("") },
                            label = {
                                Text(
                                    stringResource(R.string.log_filter_all_apps),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = WhitelistAmber.copy(alpha = 0.15f),
                                selectedLabelColor = WhitelistAmber
                            )
                        )
                    }
                    items(appNames) { name ->
                        FilterChip(
                            selected = appFilter == name,
                            onClick = { viewModel.setAppFilter(name) },
                            label = {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = WhitelistAmber.copy(alpha = 0.15f),
                                selectedLabelColor = WhitelistAmber
                            )
                        )
                    }
                }
            }

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
                            onLongPress = { selectedEntry = entry },
                            onToggleSelection = { viewModel.toggleSelection(entry.id) },
                            onQuickBlock = { viewModel.addToCustomBlockRules(entry.domain) },
                            onQuickWhitelist = { viewModel.addToWhitelist(entry.domain) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(200.dp)) }
                }
            }
        }

        // Domain detail bottom sheet
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

    if (showClearConfirm) {
        ConfirmClearLogDialog(
            onClear = {
                viewModel.clearLogs()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

}
