package app.pwhs.blockads.ui.filter.overlap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.utils.FilterOverlapAnalyzer
import app.pwhs.blockads.utils.OverlapRuleCategory

@Composable
fun OverlapDuplicatesTab(
    uiState: FilterOverlapUiState,
    onIntent: (FilterOverlapIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedName = uiState.perFilterStats
        .firstOrNull { it.filterId == uiState.selectedFilterId }
        ?.filterName

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "search_bar") {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { onIntent(FilterOverlapIntent.UpdateSearchQuery(it)) },
                placeholder = {
                    Text(
                        if (uiState.selectedCategory == OverlapRuleCategory.DOMAINS) {
                            stringResource(R.string.filter_overlap_search_hint)
                        } else {
                            stringResource(R.string.filter_overlap_search_hint_rules)
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onIntent(FilterOverlapIntent.UpdateSearchQuery("")) }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (selectedName != null) {
            item(key = "active_filter") {
                FilterChip(
                    selected = true,
                    onClick = { onIntent(FilterOverlapIntent.SelectFilter(uiState.selectedFilterId)) },
                    label = { Text(stringResource(R.string.filter_overlap_filtering_by, selectedName)) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        if (uiState.duplicateEntries.isEmpty()) {
            item(key = "no_duplicates") {
                Text(
                    text = if (uiState.selectedCategory == OverlapRuleCategory.DOMAINS) {
                        stringResource(R.string.filter_overlap_no_duplicates)
                    } else {
                        stringResource(R.string.filter_overlap_no_duplicates_rules)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(
                items = uiState.duplicateEntries,
                key = { "dup_${it.kind.name}_${it.rule}" }
            ) { entry ->
                DuplicateDomainCard(
                    entry = entry,
                    selectedFilterId = uiState.selectedFilterId,
                    onSelectFilter = { onIntent(FilterOverlapIntent.SelectFilter(it)) }
                )
            }
            if (uiState.hasMoreDuplicates) {
                item(key = "capped_notice") {
                    Text(
                        text = stringResource(
                            R.string.filter_overlap_showing_capped,
                            FilterOverlapAnalyzer.DEFAULT_MAX_DISPLAY_ENTRIES
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}
