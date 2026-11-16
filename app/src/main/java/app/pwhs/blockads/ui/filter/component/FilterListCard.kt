package app.pwhs.blockads.ui.filter.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.data.entities.FilterList

fun LazyListScope.filterListItems(
    filters: List<FilterList>,
    onToggle: (FilterList) -> Unit,
    onFilterClick: (Long) -> Unit,
    starredUrls: Set<String> = emptySet(),
    onToggleStar: ((FilterList) -> Unit)? = null,
    onDelete: ((FilterList) -> Unit)? = null
) {
    itemsIndexed(
        items = filters,
        key = { _, filter -> filter.id },
        contentType = { _, _ -> "filter_item" }
    ) { index, filter ->
        val shape = when {
            filters.size == 1 -> RoundedCornerShape(16.dp)
            index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            index == filters.lastIndex -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            else -> RoundedCornerShape(0.dp)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                FilterItem(
                    filter = filter,
                    onToggle = { onToggle(filter) },
                    onDelete = onDelete?.let { { it(filter) } },
                    isStarred = starredUrls.contains(filter.url),
                    onToggleStar = onToggleStar?.let { { it(filter) } },
                    onClick = { onFilterClick(filter.id) }
                )
                if (index < filters.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}

fun LazyGridScope.filterGridItems(
    filters: List<FilterList>,
    onToggle: (FilterList) -> Unit,
    onFilterClick: (Long) -> Unit,
    starredUrls: Set<String> = emptySet(),
    onToggleStar: ((FilterList) -> Unit)? = null,
    onDelete: ((FilterList) -> Unit)? = null
) {
    items(
        items = filters,
        key = { it.id },
        contentType = { "filter_item" }
    ) { filter ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            FilterItem(
                filter = filter,
                onToggle = { onToggle(filter) },
                onDelete = onDelete?.let { { it(filter) } },
                isStarred = starredUrls.contains(filter.url),
                onToggleStar = onToggleStar?.let { { it(filter) } },
                onClick = { onFilterClick(filter.id) }
            )
        }
    }
}

@Composable
fun FilterListCard(
    filters: List<FilterList>,
    onToggle: (FilterList) -> Unit,
    onFilterClick: (Long) -> Unit,
    onDelete: ((FilterList) -> Unit)? = null,
    starredUrls: Set<String> = emptySet(),
    onToggleStar: ((FilterList) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (filters.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            filters.forEachIndexed { index, filter ->
                FilterItem(
                    filter = filter,
                    onToggle = { onToggle(filter) },
                    onDelete = onDelete?.let { { it(filter) } },
                    isStarred = starredUrls.contains(filter.url),
                    onToggleStar = onToggleStar?.let { { it(filter) } },
                    onClick = { onFilterClick(filter.id) }
                )
                if (index < filters.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}
