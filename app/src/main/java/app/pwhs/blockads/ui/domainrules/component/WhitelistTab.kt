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
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.ui.theme.TextSecondary

import androidx.compose.runtime.remember
import app.pwhs.blockads.utils.ParsedFilterRule

@Composable
fun WhitelistTab(
    domains: List<WhitelistDomain>,
    profileFilterRules: List<ParsedFilterRule> = emptyList(),
    onToggle: (WhitelistDomain) -> Unit = {},
    onRemove: (WhitelistDomain) -> Unit,
    onEdit: (WhitelistDomain) -> Unit = {}
) {
    if (domains.isEmpty()) {
        EmptyState(stringResource(R.string.whitelist_domains_empty))
    } else {
        // Domain count
        Column {
            Text(
                text = "${domains.size} ${stringResource(R.string.settings_whitelist_domains).lowercase()}",
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
                    items = domains,
                    key = { it.id }
                ) { domain ->
                    val matchedRule = remember(domain.domain, profileFilterRules) {
                        profileFilterRules.find { it.param.trim().equals(domain.domain.trim(), ignoreCase = true) }
                    }
                    SwipeToDismissItem(
                        onDismiss = { onRemove(domain) }
                    ) {
                        DomainItem(
                            domain = domain.domain,
                            addedTimestamp = domain.addedTimestamp,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            icon = Icons.Default.CheckCircle,
                            isEnabled = domain.isEnabled,
                            ruleType = matchedRule?.type,
                            onToggle = { onToggle(domain) },
                            onDelete = { onRemove(domain) },
                            onEdit = { onEdit(domain) }
                        )
                    }
                }
            }
        }
    }
}