package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R

enum class RuleCategory {
    ALL, DOMAIN, IP_GEO, OTHER, POLICY
}

data class RuleDoc(
    val name: String,
    val category: RuleCategory,
    val description: String,
    val syntax: String,
    val matches: List<String>,
    val notMatches: List<String> = emptyList(),
    val usage: String
)

private val RULE_DOCS = listOf(
    RuleDoc(
        name = "HOST-SUFFIX",
        category = RuleCategory.DOMAIN,
        description = "Matches the specified domain and all its subdomains. Most commonly used rule type.",
        syntax = "HOST-SUFFIX, doubleclick.net, REJECT",
        matches = listOf("doubleclick.net", "ad.doubleclick.net", "stats.doubleclick.net"),
        notMatches = listOf("notdoubleclick.net"),
        usage = "Blocking an entire ad/tracker service or whitelisting a whole website domain."
    ),
    RuleDoc(
        name = "HOST",
        category = RuleCategory.DOMAIN,
        description = "Matches only the exact domain name specified. Does not match subdomains or parent domains.",
        syntax = "HOST, tracking.example.com, REJECT",
        matches = listOf("tracking.example.com"),
        notMatches = listOf("example.com", "sub.tracking.example.com"),
        usage = "Targeting a specific sub-host without affecting the root domain or other subdomains."
    ),
    RuleDoc(
        name = "HOST-KEYWORD",
        category = RuleCategory.DOMAIN,
        description = "Matches any host containing the specified keyword anywhere in its name.",
        syntax = "HOST-KEYWORD, analytics, REJECT",
        matches = listOf("google-analytics.com", "myanalytics.net", "app.analytics.io"),
        notMatches = listOf("google.com"),
        usage = "Broadly catching domains containing obvious ad or telemetry keywords."
    ),
    RuleDoc(
        name = "IP-CIDR",
        category = RuleCategory.IP_GEO,
        description = "Matches IPv4 addresses belonging to the specified CIDR subnet block.",
        syntax = "IP-CIDR, 192.168.1.0/24, DIRECT",
        matches = listOf("192.168.1.1", "192.168.1.100"),
        notMatches = listOf("192.168.2.1", "10.0.0.1"),
        usage = "Whitelisting local area networks (LAN) or routing specific IP subnets."
    ),
    RuleDoc(
        name = "IP-CIDR6",
        category = RuleCategory.IP_GEO,
        description = "Matches IPv6 addresses belonging to the specified CIDR subnet block.",
        syntax = "IP-CIDR6, 2001:db8::/32, REJECT",
        matches = listOf("2001:db8::1", "2001:db8:ffff::1"),
        notMatches = listOf("2001:db9::1"),
        usage = "Filtering or routing specific IPv6 network ranges."
    ),
    RuleDoc(
        name = "GEOIP",
        category = RuleCategory.IP_GEO,
        description = "Matches the geographic country code (ISO 3166-1 alpha-2) of the destination server.",
        syntax = "GEOIP, VN, DIRECT",
        matches = listOf("IP addresses located in Vietnam (VN)"),
        notMatches = listOf("IP addresses located outside VN"),
        usage = "Bypassing local domestic traffic directly while tunneling international traffic."
    ),
    RuleDoc(
        name = "USER-AGENT",
        category = RuleCategory.OTHER,
        description = "Matches the User-Agent header of HTTP requests with wildcard support.",
        syntax = "USER-AGENT, *Spotify*, DIRECT",
        matches = listOf("Spotify/8.8.0 Android/34", "Spotify Music Client"),
        notMatches = listOf("Mozilla/5.0 Chrome"),
        usage = "Routing or allowing traffic based on the specific client application sending it."
    ),
    RuleDoc(
        name = "FINAL",
        category = RuleCategory.OTHER,
        description = "Fallback rule evaluated when no preceding rules match. Does not take any parameter.",
        syntax = "FINAL, DIRECT",
        matches = listOf("All remaining unmatched traffic"),
        notMatches = emptyList(),
        usage = "Defining the default catch-all action for all unhandled requests."
    ),
    RuleDoc(
        name = "REJECT (Policy)",
        category = RuleCategory.POLICY,
        description = "Blocks the request immediately. DNS requests return 0.0.0.0 or connection is dropped.",
        syntax = "HOST-SUFFIX, adservice.google.com, REJECT",
        matches = listOf("Ads, telemetry, trackers, scam & malware domains"),
        notMatches = emptyList(),
        usage = "Blocklisting unwanted domains and advertisements."
    ),
    RuleDoc(
        name = "DIRECT (Policy)",
        category = RuleCategory.POLICY,
        description = "Bypasses filtering and routes directly without VPN intervention.",
        syntax = "HOST-SUFFIX, banking.example.com, DIRECT",
        matches = listOf("Whitelisted trusted sites, local network traffic, critical apps"),
        notMatches = emptyList(),
        usage = "Fixing broken websites or apps that reject VPN/ad-blocking connections."
    ),
    RuleDoc(
        name = "PROXY (Policy)",
        category = RuleCategory.POLICY,
        description = "Forwards matching traffic through a configured proxy node if proxy routing is active.",
        syntax = "HOST-SUFFIX, overseas-service.com, PROXY",
        matches = listOf("Foreign services, geo-restricted content"),
        notMatches = emptyList(),
        usage = "Selective proxying for cross-border traffic."
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleTypesDocSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(RuleCategory.ALL) }

    val filteredRules = remember(searchQuery, selectedCategory) {
        RULE_DOCS.filter { rule ->
            val matchesCategory = when (selectedCategory) {
                RuleCategory.ALL -> true
                else -> rule.category == selectedCategory
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                rule.name.contains(searchQuery, ignoreCase = true) ||
                        rule.description.contains(searchQuery, ignoreCase = true) ||
                        rule.usage.contains(searchQuery, ignoreCase = true) ||
                        rule.syntax.contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rule_docs_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.rule_docs_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.filter_search_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )

            // Category Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryChip(
                    label = stringResource(R.string.rule_docs_category_all),
                    selected = selectedCategory == RuleCategory.ALL,
                    onClick = { selectedCategory = RuleCategory.ALL }
                )
                CategoryChip(
                    label = stringResource(R.string.rule_docs_category_domain),
                    selected = selectedCategory == RuleCategory.DOMAIN,
                    onClick = { selectedCategory = RuleCategory.DOMAIN }
                )
                CategoryChip(
                    label = stringResource(R.string.rule_docs_category_ip),
                    selected = selectedCategory == RuleCategory.IP_GEO,
                    onClick = { selectedCategory = RuleCategory.IP_GEO }
                )
                CategoryChip(
                    label = stringResource(R.string.rule_docs_category_other),
                    selected = selectedCategory == RuleCategory.OTHER,
                    onClick = { selectedCategory = RuleCategory.OTHER }
                )
                CategoryChip(
                    label = stringResource(R.string.rule_docs_category_policy),
                    selected = selectedCategory == RuleCategory.POLICY,
                    onClick = { selectedCategory = RuleCategory.POLICY }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // Rules List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredRules, key = { it.name }) { rule ->
                    RuleDocCard(rule = rule)
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun RuleDocCard(
    rule: RuleDoc,
    modifier: Modifier = Modifier
) {
    val tagColor = when (rule.category) {
        RuleCategory.DOMAIN -> MaterialTheme.colorScheme.primary
        RuleCategory.IP_GEO -> MaterialTheme.colorScheme.secondary
        RuleCategory.OTHER -> MaterialTheme.colorScheme.tertiary
        RuleCategory.POLICY -> when {
            rule.name.startsWith("REJECT") -> MaterialTheme.colorScheme.error
            rule.name.startsWith("DIRECT") -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondary
        }
        RuleCategory.ALL -> MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Rule Name + Category Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = tagColor
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tagColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = when (rule.category) {
                            RuleCategory.DOMAIN -> "Domain"
                            RuleCategory.IP_GEO -> "IP / CIDR"
                            RuleCategory.OTHER -> "Special"
                            RuleCategory.POLICY -> "Policy"
                            RuleCategory.ALL -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = tagColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = rule.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Syntax Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.rule_docs_example_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = rule.syntax,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Matches
            if (rule.matches.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.rule_docs_matches_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        rule.matches.forEach { match ->
                            Text(
                                text = "• $match",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Not Matches
            if (rule.notMatches.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.HighlightOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.rule_docs_not_matches_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        rule.notMatches.forEach { notMatch ->
                            Text(
                                text = "• $notMatch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Usage recommendation / tip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${stringResource(R.string.rule_docs_usage_label)} ${rule.usage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
