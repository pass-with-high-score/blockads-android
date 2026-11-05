package app.pwhs.blockads.ui.domainrules.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary

@Composable
fun BlocklistTab(
    domains: List<CustomDnsRule>,
    onToggle: (CustomDnsRule) -> Unit = {},
    onRemove: (CustomDnsRule) -> Unit,
    onEdit: (CustomDnsRule) -> Unit = {}
) {
    if (domains.isEmpty()) {
        EmptyState(stringResource(R.string.blocklist_domains_empty))
    } else {
        Column {
            Text(
                text = "${domains.size} ${stringResource(R.string.add_blocklist_domains_title).lowercase()}",
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
                items(
                    items = domains,
                    key = { it.id }
                ) { rule ->
                    SwipeToDismissItem(
                        onDismiss = { onRemove(rule) }
                    ) {
                        DomainItem(
                            domain = rule.domain,
                            addedTimestamp = rule.addedTimestamp,
                            iconTint = DangerRed.copy(alpha = 0.7f),
                            icon = Icons.Default.Block,
                            isEnabled = rule.isEnabled,
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
