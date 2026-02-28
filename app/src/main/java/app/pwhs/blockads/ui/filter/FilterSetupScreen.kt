package app.pwhs.blockads.ui.filter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.filter.component.AddFilterDialog
import app.pwhs.blockads.ui.filter.component.FilterItem
import app.pwhs.blockads.ui.filter.component.SectionHeader
import app.pwhs.blockads.ui.theme.TextSecondary
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.CustomRulesScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FilterDetailScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSetupScreen(
    navigator: DestinationsNavigator,
    viewModel: FilterSetupViewModel = koinViewModel()
) {
    val filterLists by viewModel.filteredFilterLists.collectAsState()
    val isUpdatingFilter by viewModel.isUpdatingFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }

    UiEventEffect(viewModel.events)

    LaunchedEffect(Unit) {
        viewModel.filterAddedEvent.collect {
            showAddDialog = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.filter_setup_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = {
                        isSearchVisible = !isSearchVisible
                        if (!isSearchVisible) viewModel.setSearchQuery("")
                    }) {
                        Icon(
                            if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextSecondary
                        )
                    }
                    TextButton(
                        onClick = { viewModel.updateAllFilters() },
                        enabled = !isUpdatingFilter
                    ) {
                        if (isUpdatingFilter) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_updating))
                        } else {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_update_all))
                        }
                    }
                }
            )

        }
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
                            stringResource(R.string.filter_search_hint),
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

            // Filter content
            val builtInFilters = filterLists.filter { it.isBuiltIn }
            val adFilters = builtInFilters.filter { it.category != FilterList.CATEGORY_SECURITY }
            val securityFilters =
                builtInFilters.filter { it.category == FilterList.CATEGORY_SECURITY }
            val customFilters = filterLists.filter { !it.isBuiltIn }
            val isSearching = searchQuery.isNotEmpty()
            val hasResults = filterLists.isNotEmpty()

            if (isSearching && !hasResults) {
                // Empty search results
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
                            text = stringResource(R.string.filter_search_no_results, searchQuery),
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
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Ad filters section
                    if (adFilters.isNotEmpty()) {
                        item {
                            SectionHeader(
                                stringResource(R.string.filter_category_ad),
                                activeCount = adFilters.count { it.isEnabled }
                            )
                        }
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.animateContentSize()
                            ) {
                                Column {
                                    adFilters.forEachIndexed { index, filter ->
                                        FilterItem(
                                            filter = filter,
                                            onToggle = { viewModel.toggleFilterList(filter) },
                                            onDelete = null,
                                            onClick = {
                                                navigator.navigate(
                                                    FilterDetailScreenDestination(filterId = filter.id)
                                                )
                                            }
                                        )
                                        if (index < adFilters.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    // Security filters section
                    if (securityFilters.isNotEmpty()) {
                        item {
                            SectionHeader(
                                stringResource(R.string.filter_category_security),
                                activeCount = securityFilters.count { it.isEnabled }
                            )
                        }
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.animateContentSize()
                            ) {
                                Column {
                                    securityFilters.forEachIndexed { index, filter ->
                                        FilterItem(
                                            filter = filter,
                                            onToggle = { viewModel.toggleFilterList(filter) },
                                            onDelete = null,
                                            onClick = {
                                                navigator.navigate(
                                                    FilterDetailScreenDestination(filterId = filter.id)
                                                )
                                            }
                                        )
                                        if (index < securityFilters.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    // Custom filters section (hide when searching with no custom results)
                    if (!isSearching || customFilters.isNotEmpty()) {
                        item {
                            SectionHeader(
                                stringResource(R.string.filter_custom),
                                activeCount = customFilters.count { it.isEnabled }
                            )
                        }
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.animateContentSize()
                            ) {
                                Column {
                                    if (customFilters.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.filter_custom_empty),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    } else {
                                        customFilters.forEachIndexed { index, filter ->
                                            FilterItem(
                                                filter = filter,
                                                onToggle = { viewModel.toggleFilterList(filter) },
                                                onDelete = { viewModel.deleteFilterList(filter) },
                                                onClick = {
                                                    navigator.navigate(
                                                        FilterDetailScreenDestination(filterId = filter.id)
                                                    )
                                                }
                                            )
                                            if (index < customFilters.lastIndex) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(horizontal = 16.dp),
                                                    color = MaterialTheme.colorScheme.outline.copy(
                                                        alpha = 0.1f
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    // Add button
                                    TextButton(
                                        onClick = { showAddDialog = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.settings_add_custom_filter))
                                    }
                                }
                            }
                        }
                    }

                    // Custom Rules button
                    if (!isSearching) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = { navigator.navigate(CustomRulesScreenDestination()) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.custom_rules))
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(200.dp)) }
                }
            }
        }

        // Add filter dialog
        val isValidatingUrl by viewModel.isValidatingUrl.collectAsState()
        if (showAddDialog) {
            AddFilterDialog(
                onDismiss = { if (!isValidatingUrl) showAddDialog = false },
                onAdd = { name, url ->
                    viewModel.addFilterList(name, url)
                },
                existingUrls = filterLists.map { it.url },
                isValidating = isValidatingUrl
            )
        }
    }

}
