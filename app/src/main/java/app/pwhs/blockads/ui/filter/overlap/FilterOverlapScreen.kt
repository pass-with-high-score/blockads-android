package app.pwhs.blockads.ui.filter.overlap

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.component.UndoSnackbarHost
import app.pwhs.blockads.ui.theme.BlockadsTheme
import app.pwhs.blockads.utils.FilterOverlapAnalyzer
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber

@Composable
fun FilterOverlapScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FilterOverlapViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.filter_overlap_share_title)
    val exportFailedMessage = stringResource(R.string.filter_overlap_export_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            viewModel.onIntent(FilterOverlapIntent.ExportCombinedList(uri))
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.onIntent(FilterOverlapIntent.ExportCombinedList(treeUri))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FilterOverlapEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                is FilterOverlapEffect.NavigateBack -> onNavigateBack()
                is FilterOverlapEffect.ShareFile -> {
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = effect.mimeType
                            putExtra(Intent.EXTRA_STREAM, effect.uri)
                            putExtra(Intent.EXTRA_SUBJECT, "blockads-combined-filters.txt")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(
                            shareIntent,
                            shareTitle
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to share combined filter list, falling back to folder picker")
                        try {
                            folderLauncher.launch(null)
                        } catch (e2: Exception) {
                            Timber.e(e2, "Folder picker also failed, saving directly to Downloads")
                            viewModel.onIntent(FilterOverlapIntent.ExportCombinedList())
                        }
                    }
                }
            }
        }
    }

    FilterOverlapContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onIntent = viewModel::onIntent,
        onExportClick = {
            try {
                exportLauncher.launch("blockads-combined-filters.txt")
            } catch (e: ActivityNotFoundException) {
                Timber.w(e, "ActivityNotFoundException on CreateDocument, falling back to folder picker")
                try {
                    folderLauncher.launch(null)
                } catch (e2: ActivityNotFoundException) {
                    Timber.w(e2, "ActivityNotFoundException on OpenDocumentTree, falling back to Share")
                    viewModel.onIntent(FilterOverlapIntent.ShareCombinedList)
                }
            }
        },
        onShareClick = { viewModel.onIntent(FilterOverlapIntent.ShareCombinedList) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterOverlapContent(
    uiState: FilterOverlapUiState,
    snackbarHostState: SnackbarHostState,
    onIntent: (FilterOverlapIntent) -> Unit,
    onExportClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { UndoSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onIntent(FilterOverlapIntent.NavigateBack) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.filter_overlap_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(
                        onClick = { onIntent(FilterOverlapIntent.Analyze) },
                        enabled = !uiState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.filter_detail_update)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.perFilterStats.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.filterCount < 2 -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.filter_overlap_need_two_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.filter_overlap_need_two),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    PrimaryTabRow(
                        selectedTabIndex = uiState.selectedTab,
                        containerColor = MaterialTheme.colorScheme.background
                    ) {
                        Tab(
                            selected = uiState.selectedTab == 0,
                            onClick = { onIntent(FilterOverlapIntent.SelectTab(0)) },
                            text = { Text(stringResource(R.string.filter_overlap_tab_overview)) }
                        )
                        Tab(
                            selected = uiState.selectedTab == 1,
                            onClick = { onIntent(FilterOverlapIntent.SelectTab(1)) },
                            text = {
                                Text(
                                    "${stringResource(R.string.filter_overlap_tab_duplicates)} (${
                                        java.text.NumberFormat.getIntegerInstance().format(uiState.overlappingDomainCount)
                                    })"
                                )
                            }
                        )
                    }

                    OverlapCategoryChips(
                        selectedCategory = uiState.selectedCategory,
                        onSelectCategory = { onIntent(FilterOverlapIntent.SelectCategory(it)) }
                    )

                    if (uiState.selectedTab == 0) {
                        OverlapOverviewTab(
                            uiState = uiState,
                            onIntent = onIntent,
                            onExportClick = onExportClick,
                            onShareClick = onShareClick
                        )
                    } else {
                        OverlapDuplicatesTab(
                            uiState = uiState,
                            onIntent = onIntent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlapOverviewTab(
    uiState: FilterOverlapUiState,
    onIntent: (FilterOverlapIntent) -> Unit,
    onExportClick: () -> Unit,
    onShareClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "summary") {
            OverlapSummaryCard(
                uiState = uiState,
                onExportClick = onExportClick,
                onShareClick = onShareClick
            )
        }

        item(key = "per_filter_header") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.filter_overlap_per_filter_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.filter_overlap_per_filter_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(
            items = uiState.perFilterStats,
            key = { "stat_${it.filterId}" }
        ) { stat ->
            FilterOverlapStatCard(
                stat = stat,
                selected = uiState.selectedFilterId == stat.filterId,
                onClick = {
                    onIntent(FilterOverlapIntent.SelectFilter(stat.filterId))
                    onIntent(FilterOverlapIntent.SelectTab(1))
                },
                onDisable = {
                    onIntent(FilterOverlapIntent.DisableFilter(stat.filterId, stat.filterName))
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FilterOverlapLightPreview() {
    BlockadsTheme(themeMode = AppPreferences.THEME_LIGHT) {
        FilterOverlapContent(
            uiState = FilterOverlapUiState(isLoading = false, filterCount = 1),
            snackbarHostState = SnackbarHostState(),
            onIntent = {},
            onExportClick = {},
            onShareClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FilterOverlapDarkPreview() {
    BlockadsTheme(themeMode = AppPreferences.THEME_DARK) {
        FilterOverlapContent(
            uiState = FilterOverlapUiState(isLoading = false, filterCount = 1),
            snackbarHostState = SnackbarHostState(),
            onIntent = {},
            onExportClick = {},
            onShareClick = {}
        )
    }
}
