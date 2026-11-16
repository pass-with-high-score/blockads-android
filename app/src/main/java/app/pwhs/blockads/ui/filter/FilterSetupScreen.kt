package app.pwhs.blockads.ui.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FilterList as FilterListEntity
import app.pwhs.blockads.ui.component.UndoSnackbarHost
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.filter.component.AddFilterBottomSheet
import app.pwhs.blockads.ui.filter.component.CustomTabContent
import app.pwhs.blockads.ui.filter.component.filterListItems
import app.pwhs.blockads.ui.filter.detail.FilterDetailScreen
import app.pwhs.blockads.ui.filter.component.FilterSearchBar
import app.pwhs.blockads.ui.filter.component.FilterTabRow
import app.pwhs.blockads.ui.filter.component.FilterTagChipRow
import app.pwhs.blockads.ui.filter.component.FilterTopBar
import app.pwhs.blockads.ui.filter.component.TAG_STARRED
import app.pwhs.blockads.ui.filter.dialog.CreateFilterTagDialog
import app.pwhs.blockads.ui.filter.data.FilterCategoryTab
import app.pwhs.blockads.ui.filter.data.matchesCategory
import app.pwhs.blockads.ui.filter.data.resolvedCategory
import app.pwhs.blockads.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSetupScreen(
    modifier: Modifier = Modifier,
    canNavigateBack: Boolean = false,
    onNavigateBack: () -> Unit = {},
    viewModel: FilterSetupViewModel = koinViewModel(),
    onNavigateToFilterDetail: (filterId: Long) -> Unit = {},
    onNavigateToOverlap: () -> Unit = {},
    externalFilterId: Long? = null,
    onExternalFilterIdConsumed: () -> Unit = {},
) {
    val allLists by viewModel.filterLists.collectAsStateWithLifecycle()
    val filterLists by viewModel.filteredFilterLists.collectAsStateWithLifecycle()
    val isUpdatingFilter by viewModel.isUpdatingFilter.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val starredUrls by viewModel.starredFilterUrls.collectAsStateWithLifecycle()
    val filterBuildMode by viewModel.filterBuildMode.collectAsStateWithLifecycle()
    val supportErrorInfo by viewModel.supportErrorInfo.collectAsStateWithLifecycle()
    val customFilterTags by viewModel.customFilterTags.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var filterToEdit by remember { mutableStateOf<FilterListEntity?>(null) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var tagToDelete by remember { mutableStateOf<String?>(null) }
    var isSearchVisible by remember { mutableStateOf(false) }

    val isStore = BuildConfig.IS_STORE
    val categories = remember(isStore) {
        if (isStore) {
            listOf(FilterCategoryTab.CUSTOM)
        } else {
            FilterCategoryTab.entries
        }
    }

    val pagerState = rememberPagerState(initialPage = 0) { categories.size }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources

    UiEventEffect(viewModel.events)

    LaunchedEffect(viewModel) {
        viewModel.undoDeleteEvent.collect { filter ->
            snackbarHostState.currentSnackbarData?.dismiss()
            var handled = false
            try {
                val result = snackbarHostState.showSnackbar(
                    message = resources.getString(R.string.filter_list_removed_named, filter.name),
                    actionLabel = resources.getString(R.string.action_undo),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                handled = true
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.cancelDeleteFilterList(filter)
                } else {
                    viewModel.commitDeleteFilterList(filter)
                }
            } finally {
                if (!handled) {
                    viewModel.commitDeleteFilterList(filter)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.filterAddedEvent.collect {
            showAddDialog = false
            filterToEdit = null
        }
    }

    val activeCounts = remember(allLists, starredUrls) {
        val counts = mutableMapOf<FilterCategoryTab, Int>()
        for (tab in categories) {
            counts[tab] = 0
        }
        for (filter in allLists) {
            if (!filter.isEnabled) continue
            counts[FilterCategoryTab.ALL] = (counts[FilterCategoryTab.ALL] ?: 0) + 1
            if (starredUrls.contains(filter.url)) {
                counts[FilterCategoryTab.STARRED] = (counts[FilterCategoryTab.STARRED] ?: 0) + 1
            }
            if (!filter.isBuiltIn) {
                counts[FilterCategoryTab.CUSTOM] = (counts[FilterCategoryTab.CUSTOM] ?: 0) + 1
            } else {
                val cat = filter.resolvedCategory()
                counts[cat] = (counts[cat] ?: 0) + 1
            }
        }
        counts
    }

    val tagActiveCounts = remember(allLists, starredUrls, customFilterTags) {
        val counts = mutableMapOf<String, Int>()
        counts["__all__"] = allLists.count { it.isEnabled }
        counts[TAG_STARRED] = allLists.count { it.isEnabled && starredUrls.contains(it.url) }
        for (tag in customFilterTags) {
            counts[tag] = allLists.count { it.isEnabled && it.category.equals(tag, ignoreCase = true) }
        }
        counts
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { UndoSnackbarHost(snackbarHostState) },
        topBar = {
            FilterTopBar(
                title = if (isStore) stringResource(R.string.settings_category_filters) else stringResource(R.string.filter_setup_title),
                isSearchVisible = isSearchVisible,
                onToggleSearch = {
                    isSearchVisible = !isSearchVisible
                    if (!isSearchVisible) viewModel.setSearchQuery("")
                },
                canNavigateBack = canNavigateBack,
                onNavigateBack = onNavigateBack,
                onOpenOverlap = onNavigateToOverlap,
                isUpdatingFilter = isUpdatingFilter,
                updateProgress = updateProgress,
                onUpdateAllFilters = { viewModel.updateAllFilters() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FilterSearchBar(
                visible = isSearchVisible,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) }
            )

            if (isStore) {
                FilterTagChipRow(
                    tags = customFilterTags,
                    selectedTag = selectedTag,
                    onSelectTag = { viewModel.selectTag(it) },
                    onCreateTagClick = { showCreateTagDialog = true },
                    activeCounts = tagActiveCounts,
                    onDeleteTag = { tagToDelete = it }
                )
            } else {
                FilterTabRow(
                    selectedTab = categories[pagerState.currentPage],
                    onTabSelected = { tab ->
                        coroutineScope.launch {
                            val targetIndex = categories.indexOf(tab)
                            if (targetIndex >= 0) {
                                pagerState.animateScrollToPage(targetIndex)
                            }
                        }
                    },
                    activeCounts = activeCounts
                )
            }

            val isSearching = searchQuery.isNotBlank()

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val currentTab = categories[page]
                val displayFilters = remember(filterLists, currentTab, starredUrls, isStore, selectedTag) {
                    if (isStore) {
                        val storeFilters = when (selectedTag) {
                            null -> filterLists
                            TAG_STARRED -> filterLists.filter { starredUrls.contains(it.url) }
                            else -> filterLists.filter { it.category.equals(selectedTag, ignoreCase = true) }
                        }
                        storeFilters.sortedWith(compareByDescending<FilterListEntity> { it.isEnabled }.thenBy { it.name })
                    } else {
                        val tabFilters = filterLists.filter { it.matchesCategory(currentTab, starredUrls) }
                        if (currentTab == FilterCategoryTab.ALL) {
                            tabFilters.sortedWith(compareByDescending<FilterListEntity> { it.isEnabled }.thenBy { it.name })
                        } else {
                            tabFilters
                        }
                    }
                }

                if (currentTab == FilterCategoryTab.CUSTOM) {
                    if (isSearching && displayFilters.isEmpty()) {
                        EmptyCategorySearch(searchQuery)
                    } else if (isStore && selectedTag == TAG_STARRED && displayFilters.isEmpty()) {
                        EmptyStarredFilter()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 16.dp,
                                bottom = 24.dp + LocalFloatingBottomBarPadding.current
                            )
                        ) {
                            item {
                                CustomTabContent(
                                    customFilters = displayFilters,
                                    onToggle = { viewModel.toggleFilterList(it) },
                                    onDelete = { viewModel.requestDeleteFilterList(it) },
                                    onEdit = { filterToEdit = it },
                                    onFilterClick = onNavigateToFilterDetail,
                                    onShowAddDialog = { showAddDialog = true },
                                    starredUrls = starredUrls,
                                    onToggleStar = { viewModel.toggleStarredFilter(it) },
                                    emptyText = if (selectedTag != null) stringResource(R.string.filter_tag_empty) else null
                                )
                            }
                        }
                    }
                } else if (currentTab == FilterCategoryTab.STARRED && displayFilters.isEmpty() && !isSearching) {
                    EmptyStarredFilter()
                } else {
                    if (isSearching && displayFilters.isEmpty()) {
                        EmptyCategorySearch(searchQuery)
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            if (rememberIsExpandedLayout(maxWidth)) {
                                var selectedFilterId by rememberSaveable { mutableStateOf<Long?>(null) }
                                LaunchedEffect(externalFilterId) {
                                    if (externalFilterId != null) {
                                        selectedFilterId = externalFilterId
                                        onExternalFilterIdConsumed()
                                    }
                                }
                                val activeFilterId = selectedFilterId
                                    ?.takeIf { id -> displayFilters.any { it.id == id } }
                                    ?: displayFilters.firstOrNull()?.id

                                Row(modifier = Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        modifier = Modifier
                                            .weight(0.46f)
                                            .fillMaxHeight(),
                                        contentPadding = PaddingValues(
                                            start = 16.dp,
                                            end = 12.dp,
                                            top = 16.dp,
                                            bottom = 24.dp + LocalFloatingBottomBarPadding.current
                                        )
                                    ) {
                                        filterListItems(
                                            filters = displayFilters,
                                            onToggle = { viewModel.toggleFilterList(it) },
                                            onFilterClick = { id -> selectedFilterId = id },
                                            starredUrls = starredUrls,
                                            onToggleStar = { viewModel.toggleStarredFilter(it) }
                                        )
                                    }

                                    VerticalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )

                                    Box(
                                        modifier = Modifier
                                            .weight(0.54f)
                                            .fillMaxHeight()
                                    ) {
                                        if (activeFilterId != null) {
                                            FilterDetailScreen(
                                                filterId = activeFilterId,
                                                showBackButton = false,
                                                onNavigateBack = { selectedFilterId = null }
                                            )
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 16.dp,
                                        bottom = 24.dp + LocalFloatingBottomBarPadding.current
                                    )
                                ) {
                                    filterListItems(
                                        filters = displayFilters,
                                        onToggle = { viewModel.toggleFilterList(it) },
                                        onFilterClick = onNavigateToFilterDetail,
                                        starredUrls = starredUrls,
                                        onToggleStar = { viewModel.toggleStarredFilter(it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val isAddingCustomFilter by viewModel.isAddingCustomFilter.collectAsStateWithLifecycle()
        val addFilterProgress by viewModel.addFilterProgress.collectAsStateWithLifecycle()
        if (showAddDialog || filterToEdit != null) {
            AddFilterBottomSheet(
                onDismiss = {
                    if (!isAddingCustomFilter) {
                        showAddDialog = false
                        filterToEdit = null
                    }
                },
                onAdd = { name, url, category, buildLocally ->
                    if (!isAddingCustomFilter) {
                        viewModel.addFilterList(name, url, category, buildLocally)
                    }
                },
                filterToEdit = filterToEdit,
                onEdit = { filter, name, url, category, buildLocally ->
                    if (!isAddingCustomFilter) {
                        viewModel.editFilterList(filter, name, url, category, buildLocally)
                    }
                },
                availableTags = customFilterTags,
                defaultCategory = if (selectedTag != TAG_STARRED) (selectedTag ?: "") else "",
                onCreateTag = { viewModel.createTag(it) },
                existingUrls = filterLists.map { it.url },
                isValidating = isAddingCustomFilter,
                progress = addFilterProgress,
                defaultBuildLocally = filterBuildMode == AppPreferences.BUILD_MODE_LOCAL
            )
        }

        if (showCreateTagDialog) {
            CreateFilterTagDialog(
                onDismiss = { showCreateTagDialog = false },
                onConfirm = { newTag ->
                    viewModel.createTag(newTag)
                    showCreateTagDialog = false
                },
                existingTags = customFilterTags
            )
        }

        tagToDelete?.let { tag ->
            AlertDialog(
                onDismissRequest = { tagToDelete = null },
                title = { Text(stringResource(R.string.filter_tag_delete)) },
                text = { Text(stringResource(R.string.filter_tag_delete_confirm, tag)) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteTag(tag)
                            tagToDelete = null
                        }
                    ) {
                        Text(stringResource(R.string.filter_tag_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { tagToDelete = null }) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                }
            )
        }

        supportErrorInfo?.let { errorInfo ->
            app.pwhs.blockads.ui.component.ErrorSupportBottomSheet(
                errorInfo = errorInfo,
                onDismiss = viewModel::dismissSupportError
            )
        }
    }
}

@Composable
private fun EmptyCategorySearch(
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = TextSecondary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.filter_search_no_results, searchQuery),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyStarredFilter(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = TextSecondary.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.filter_starred_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
