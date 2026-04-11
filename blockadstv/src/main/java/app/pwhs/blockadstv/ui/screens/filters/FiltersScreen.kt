package app.pwhs.blockadstv.ui.screens.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.entities.FilterList
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FiltersScreen(
    modifier: Modifier = Modifier,
    filterListDao: FilterListDao = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val filters by filterListDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var customUrl by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Filter Lists",
                style = MaterialTheme.typography.headlineLarge,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enable filter lists to block ads, trackers, and malware",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Enabled count
        val enabledCount = filters.count { it.isEnabled }
        Text(
            text = "$enabledCount of ${filters.size} lists enabled",
            style = MaterialTheme.typography.labelLarge,
            color = NeonGreen,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add custom filter
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvTextInput(
                value = customUrl,
                onValueChange = { customUrl = it },
                placeholder = "Add custom filter URL (hosts file)",
                modifier = Modifier.weight(1f),
                onDone = {},
            )

            var isFocused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isFocused) NeonGreen.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surface
                    )
                    .border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                            val url = customUrl.trim()
                            if (url.isNotEmpty()) {
                                scope.launch {
                                    filterListDao.insert(
                                        FilterList(
                                            name = url.substringAfterLast("/").take(30).ifEmpty { "Custom filter" },
                                            url = url,
                                            description = "Custom filter",
                                            isEnabled = true,
                                            isBuiltIn = false,
                                            originalUrl = url,
                                        )
                                    )
                                    customUrl = ""
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add",
                    tint = if (isFocused) NeonGreen else TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) NeonGreen else TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filters.isEmpty()) {
            Text(
                text = "No filter lists available. Start the VPN to download filters.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filters, key = { it.id }) { filter ->
                    FilterListItem(
                        filter = filter,
                        onToggle = {
                            CoroutineScope(Dispatchers.IO).launch {
                                filterListDao.setEnabled(filter.id, !filter.isEnabled)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterListItem(
    filter: FilterList,
    onToggle: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    onToggle()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = if (filter.isEnabled) NeonGreen else TextSecondary,
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = filter.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = buildString {
                    append(filter.description)
                    if (filter.ruleCount > 0) {
                        if (isNotEmpty()) append(" - ")
                        append("~${filter.ruleCount / 1000}k rules")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        TvSwitch(checked = filter.isEnabled)
    }
}
