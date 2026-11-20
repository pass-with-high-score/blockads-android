package app.pwhs.blockads.ui.domainrules.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.domainrules.dialog.formatPackageNamesLabel
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.utils.ParsedFilterRule

@Composable
fun BlocklistTab(
    rules: List<ParsedFilterRule>,
    appLabelMap: Map<String, String> = emptyMap(),
    selectedIds: Set<String> = emptySet(),
    isReorderMode: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
    onSwapPriority: (ParsedFilterRule, ParsedFilterRule) -> Unit = { _, _ -> },
    onToggle: (ParsedFilterRule) -> Unit = {},
    onRemove: (ParsedFilterRule) -> Unit,
    onEdit: (ParsedFilterRule) -> Unit = {}
) {
    val isSelectionMode = selectedIds.isNotEmpty()
    if (rules.isEmpty()) {
        EmptyState(stringResource(R.string.blocklist_domains_empty))
    } else {
        Column {
            Text(
                text = "${rules.size} ${stringResource(R.string.add_blocklist_domains_title).lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp
                )
            ) {
                itemsIndexed(
                    items = rules,
                    key = { _, it -> it.id.ifEmpty { it.rawLine } }
                ) { index, rule ->
                    val ruleKey = rule.id.ifEmpty { rule.rawLine }
                    val canMoveUp = isReorderMode &&
                        index > 0 &&
                        rule.rawLine.startsWith("custom:") &&
                        rules[index - 1].rawLine.startsWith("custom:")
                    val canMoveDown = isReorderMode &&
                        index < rules.lastIndex &&
                        rule.rawLine.startsWith("custom:") &&
                        rules[index + 1].rawLine.startsWith("custom:")

                    val itemContent: @Composable () -> Unit = {
                        DomainItem(
                            domain = rule.param.ifEmpty { rule.type },
                            addedTimestamp = 0L,
                            iconTint = DangerRed.copy(alpha = 0.7f),
                            icon = Icons.Default.Block,
                            isEnabled = rule.isEnabled,
                            ruleType = rule.type,
                            appLabel = formatPackageNamesLabel(rule.packageName, appLabelMap),
                            isSelectionMode = isSelectionMode,
                            isSelected = ruleKey in selectedIds,
                            isReorderMode = isReorderMode,
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            onToggleSelect = { onToggleSelect(ruleKey) },
                            onMoveUp = { if (canMoveUp) onSwapPriority(rule, rules[index - 1]) },
                            onMoveDown = { if (canMoveDown) onSwapPriority(rule, rules[index + 1]) },
                            onToggle = { onToggle(rule) },
                            onDelete = { onRemove(rule) },
                            onEdit = { onEdit(rule) }
                        )
                    }

                    if (isSelectionMode || isReorderMode) {
                        itemContent()
                    } else {
                        SwipeToDismissItem(onDismiss = { onRemove(rule) }) {
                            itemContent()
                        }
                    }
                }
            }
        }
    }
}
