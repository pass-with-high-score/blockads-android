package app.pwhs.blockads.ui.filter

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.FilterList
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
    val filterLists by viewModel.filterLists.collectAsState()
    val isUpdatingFilter by viewModel.isUpdatingFilter.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    UiEventEffect(viewModel.events)

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                // Built-in filters section
                val builtInFilters = filterLists.filter { it.isBuiltIn }
                val adFilters = builtInFilters.filter { it.category != FilterList.CATEGORY_SECURITY }
                val securityFilters = builtInFilters.filter { it.category == FilterList.CATEGORY_SECURITY }
                val customFilters = filterLists.filter { !it.isBuiltIn }

                if (adFilters.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.filter_category_ad), activeCount = adFilters.count { it.isEnabled })
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
                                    onClick = { navigator.navigate(FilterDetailScreenDestination(filterId = filter.id)) }
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

                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (securityFilters.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.filter_category_security), activeCount = securityFilters.count { it.isEnabled })
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
                                    onClick = { navigator.navigate(FilterDetailScreenDestination(filterId = filter.id)) }
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

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Custom filters section
                SectionHeader(stringResource(R.string.filter_custom), activeCount = customFilters.count { it.isEnabled })
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
                                    onClick = { navigator.navigate(FilterDetailScreenDestination(filterId = filter.id)) }
                                )
                                if (index < customFilters.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
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

                Spacer(modifier = Modifier.height(16.dp))

                // Custom Rules button
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

                Spacer(modifier = Modifier.height(200.dp))
            }
        }

        // Add filter dialog
        if (showAddDialog) {
            AddFilterDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, url ->
                    viewModel.addFilterList(name, url)
                    showAddDialog = false
                }
            )
        }
    }

}
