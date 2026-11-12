package app.pwhs.blockadstv.ui.screens.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.entities.FilterList
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FiltersScreen(
    modifier: Modifier = Modifier,
    filterListDao: FilterListDao = koinInject(),
    filterRepo: FilterListRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val filters by filterListDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var customUrl by remember { mutableStateOf("") }
    var selectedFilterForAction by remember { mutableStateOf<FilterList?>(null) }

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
                painter = painterResource(R.drawable.ic_settings_filter_lists),
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "rules_engine_section") { RulesEngineSection() }
                item(key = "empty_filters_hint") {
                    Text(
                        text = "No filter lists available. Start the VPN to download filters.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "rules_engine_section") { RulesEngineSection() }
                items(filters, key = { it.id }) { filter ->
                    FilterListItem(
                        filter = filter,
                        onClick = {
                            if (!filter.isBuiltIn) {
                                selectedFilterForAction = filter
                            } else {
                                scope.launch {
                                    filterListDao.setEnabled(filter.id, !filter.isEnabled)
                                    filterRepo.loadAllEnabledFilters()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    selectedFilterForAction?.let { filter ->
        CustomFilterDialog(
            filter = filter,
            onDismiss = { selectedFilterForAction = null },
            onToggle = {
                scope.launch {
                    filterListDao.setEnabled(filter.id, !filter.isEnabled)
                    filterRepo.loadAllEnabledFilters()
                    selectedFilterForAction = null
                }
            },
            onDelete = {
                scope.launch {
                    filterListDao.delete(filter)
                    filterRepo.loadAllEnabledFilters()
                    selectedFilterForAction = null
                }
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CustomFilterDialog(
    filter: FilterList,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, NeonGreen, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_filter_lists),
                        contentDescription = null,
                        tint = NeonGreen,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Custom Filter",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = filter.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NeonGreen,
                )

                Text(
                    text = filter.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterDialogButton(
                        label = if (filter.isEnabled) "Disable" else "Enable",
                        iconRes = if (filter.isEnabled) R.drawable.ic_shield_off else R.drawable.ic_shield_on,
                        color = if (filter.isEnabled) TextSecondary else NeonGreen,
                        onClick = onToggle,
                        modifier = Modifier.weight(1f),
                    )
                    FilterDialogButton(
                        label = "Delete",
                        iconRes = R.drawable.ic_settings_trash,
                        color = DangerRed,
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                    )
                    FilterDialogButton(
                        label = "Cancel",
                        iconRes = R.drawable.ic_error,
                        color = TextSecondary,
                        onClick = onDismiss,
                        modifier = Modifier.weight(0.8f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterDialogButton(
    label: String,
    @androidx.annotation.DrawableRes iconRes: Int,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) color else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (isFocused) color else TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) color else TextSecondary,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterListItem(
    filter: FilterList,
    onClick: () -> Unit,
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
                    onClick()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (filter.isEnabled) R.drawable.ic_shield_on else R.drawable.ic_shield_off),
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
