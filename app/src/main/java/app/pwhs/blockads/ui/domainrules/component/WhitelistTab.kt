package app.pwhs.blockads.ui.domainrules.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.utils.ParsedFilterRule

@Composable
fun WhitelistTab(
    rules: List<ParsedFilterRule>,
    onToggle: (ParsedFilterRule) -> Unit = {},
    onRemove: (ParsedFilterRule) -> Unit,
    onEdit: (ParsedFilterRule) -> Unit = {}
) {
    if (rules.isEmpty()) {
        EmptyState(stringResource(R.string.whitelist_domains_empty))
    } else {
        Column {
            Text(
                text = "${rules.size} ${stringResource(R.string.settings_whitelist_domains).lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp
                )
            ) {
                items(
                    items = rules,
                    key = { it.id.ifEmpty { it.rawLine } }
                ) { rule ->
                    SwipeToDismissItem(
                        onDismiss = { onRemove(rule) }
                    ) {
                        DomainItem(
                            domain = rule.param.ifEmpty { rule.type },
                            addedTimestamp = 0L,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            icon = Icons.Default.CheckCircle,
                            isEnabled = rule.isEnabled,
                            ruleType = rule.type,
                            onToggle = { onToggle(rule) },
                            onDelete = { onRemove(rule) },
                            onEdit = { onEdit(rule) }
                        )
                    }
                }
            }
        }
    }
}