package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.TextSecondary

enum class RuleTypeCategory(val label: String) {
    ALL("All"),
    POPULAR("Popular"),
    DOMAIN("Domain"),
    NETWORK("IP & Network"),
    APP("App & Process"),
    ADVANCED("Advanced")
}

data class RuleTypeMeta(
    val type: String,
    val category: RuleTypeCategory,
    val summary: String,
    val example: String,
    val isPopular: Boolean = false,
    val isAdvanced: Boolean = false
)

object RuleTypeCatalog {
    val entries: List<RuleTypeMeta> = listOf(
        RuleTypeMeta("HOST-SUFFIX", RuleTypeCategory.DOMAIN, "Match domain and all subdomains", "doubleclick.net", isPopular = true),
        RuleTypeMeta("HOST", RuleTypeCategory.DOMAIN, "Match exact domain only", "ads.example.com", isPopular = true),
        RuleTypeMeta("HOST-KEYWORD", RuleTypeCategory.DOMAIN, "Match domains containing keyword", "analytics", isPopular = true),
        RuleTypeMeta("DOMAIN-WILDCARD", RuleTypeCategory.DOMAIN, "Match domain with * and ? wildcards", "*.ads.*.example.com"),
        RuleTypeMeta("DOMAIN-REGEX", RuleTypeCategory.DOMAIN, "Match domain with regular expression", "^ad[0-9]+\\..+\\.com$", isAdvanced = true),
        RuleTypeMeta("GEOSITE", RuleTypeCategory.DOMAIN, "Match curated site category", "category-ads, apple"),
        RuleTypeMeta("USER-AGENT", RuleTypeCategory.DOMAIN, "Match HTTP User-Agent header", "*TrackingSDK*", isAdvanced = true),

        RuleTypeMeta("IP-CIDR", RuleTypeCategory.NETWORK, "Match destination IPv4 CIDR subnet", "192.168.0.0/16", isPopular = true),
        RuleTypeMeta("IP-CIDR6", RuleTypeCategory.NETWORK, "Match destination IPv6 CIDR subnet", "2001:db8::/32"),
        RuleTypeMeta("IP-SUFFIX", RuleTypeCategory.NETWORK, "Match destination IP suffix mask", "0.0.0.1/8", isAdvanced = true),
        RuleTypeMeta("IP-ASN", RuleTypeCategory.NETWORK, "Match destination Autonomous System Number", "15169", isAdvanced = true),
        RuleTypeMeta("GEOIP", RuleTypeCategory.NETWORK, "Match destination server country code", "VN, US, CN", isPopular = true),
        RuleTypeMeta("SRC-IP-CIDR", RuleTypeCategory.NETWORK, "Match source IPv4 CIDR subnet", "10.0.0.0/8", isAdvanced = true),
        RuleTypeMeta("SRC-IP-SUFFIX", RuleTypeCategory.NETWORK, "Match source IP suffix mask", "0.0.0.1/8", isAdvanced = true),
        RuleTypeMeta("SRC-IP-ASN", RuleTypeCategory.NETWORK, "Match source ASN", "13335", isAdvanced = true),
        RuleTypeMeta("SRC-GEOIP", RuleTypeCategory.NETWORK, "Match source country code", "VN", isAdvanced = true),
        RuleTypeMeta("DST-PORT", RuleTypeCategory.NETWORK, "Match destination port or range", "443, 853, 8000-9000", isPopular = true),
        RuleTypeMeta("SRC-PORT", RuleTypeCategory.NETWORK, "Match source port or range", "5353", isAdvanced = true),
        RuleTypeMeta("IN-PORT", RuleTypeCategory.NETWORK, "Match inbound listener port", "7890", isAdvanced = true),
        RuleTypeMeta("NETWORK", RuleTypeCategory.NETWORK, "Match transport protocol (TCP/UDP)", "UDP"),
        RuleTypeMeta("DSCP", RuleTypeCategory.NETWORK, "Match IP DSCP QoS value", "46", isAdvanced = true),

        RuleTypeMeta("PROCESS-NAME", RuleTypeCategory.APP, "Match exact app process/package name", "com.zhiliaoapp.musically", isPopular = true),
        RuleTypeMeta("PROCESS-NAME-WILDCARD", RuleTypeCategory.APP, "Match process name with wildcards", "com.google.*"),
        RuleTypeMeta("PROCESS-NAME-REGEX", RuleTypeCategory.APP, "Match process name with regex", "^com\\.facebook\\..*", isAdvanced = true),
        RuleTypeMeta("PROCESS-PATH", RuleTypeCategory.APP, "Match exact executable path", "/data/app/...", isAdvanced = true),
        RuleTypeMeta("PROCESS-PATH-WILDCARD", RuleTypeCategory.APP, "Match executable path with wildcards", "*/chrome/*", isAdvanced = true),
        RuleTypeMeta("PROCESS-PATH-REGEX", RuleTypeCategory.APP, "Match executable path with regex", ".*\\/ads\\/.*", isAdvanced = true),
        RuleTypeMeta("UID", RuleTypeCategory.APP, "Match Linux/Android app UID", "10123", isAdvanced = true),

        RuleTypeMeta("IN-TYPE", RuleTypeCategory.ADVANCED, "Match inbound type", "TUN, DNS, HTTP", isAdvanced = true),
        RuleTypeMeta("IN-USER", RuleTypeCategory.ADVANCED, "Match inbound proxy username", "alice", isAdvanced = true),
        RuleTypeMeta("IN-NAME", RuleTypeCategory.ADVANCED, "Match inbound listener name", "socks-in", isAdvanced = true),
        RuleTypeMeta("RULE-SET", RuleTypeCategory.ADVANCED, "Reference external or named rule set", "ads-list", isAdvanced = true),
        RuleTypeMeta("AND", RuleTypeCategory.ADVANCED, "All sub-conditions must match", "((NETWORK,UDP),(DST-PORT,443))", isAdvanced = true),
        RuleTypeMeta("OR", RuleTypeCategory.ADVANCED, "Any sub-condition matches", "((DST-PORT,80),(DST-PORT,443))", isAdvanced = true),
        RuleTypeMeta("NOT", RuleTypeCategory.ADVANCED, "Negate a sub-condition", "((GEOIP,VN))", isAdvanced = true),
        RuleTypeMeta("SUB-RULE", RuleTypeCategory.ADVANCED, "Jump to a named sub-rule group", "(GEOIP,CN),my-subrule", isAdvanced = true),
        RuleTypeMeta("FINAL", RuleTypeCategory.ADVANCED, "Fallback rule when no earlier rules match", "(no parameter needed)", isAdvanced = true)
    )

    private val byType = entries.associateBy { it.type }

    fun find(type: String): RuleTypeMeta? = byType[type.uppercase()]
}

internal val FILTER_POLICIES = listOf("REJECT", "REJECT-DROP", "DIRECT", "PASS", "PASS-RULE", "PROXY")
internal val QUICK_COUNTRIES = listOf("VN", "US", "SG", "JP", "CN", "DE")
internal val QUICK_NETWORKS = listOf("TCP", "UDP")

private val IPV4_CIDR_REGEX = Regex(
    "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(/(3[0-2]|[12]?\\d))?$"
)

internal fun validateRuleParam(type: String, param: String): String? {
    val trimmed = param.trim()
    if (trimmed.isEmpty()) return null
    return when (type.uppercase()) {
        "HOST-REGEX", "DOMAIN-REGEX", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX" ->
            if (runCatching { Regex(trimmed) }.isFailure) "Invalid regular expression syntax" else null
        "IP-CIDR", "SRC-IP-CIDR" ->
            if (!IPV4_CIDR_REGEX.matches(trimmed)) "Expected IPv4 or IPv4/CIDR (e.g. 192.168.1.0/24)" else null
        "DST-PORT", "SRC-PORT", "IN-PORT" -> {
            val parts = trimmed.split("/")
            val valid = parts.all { part ->
                val range = part.trim().split("-")
                when (range.size) {
                    1 -> range[0].toIntOrNull()?.let { it in 1..65535 } == true
                    2 -> {
                        val a = range[0].toIntOrNull()
                        val b = range[1].toIntOrNull()
                        a != null && b != null && a in 1..65535 && b in 1..65535 && a <= b
                    }
                    else -> false
                }
            }
            if (!valid) "Invalid port or range (1-65535, e.g. 443 or 8000-9000)" else null
        }
        "AND", "OR", "NOT" -> {
            var depth = 0
            for (ch in trimmed) {
                if (ch == '(') depth++
                else if (ch == ')') depth--
                if (depth < 0) return "Unbalanced parentheses in logic rule"
            }
            if (depth != 0 || !trimmed.startsWith("(")) {
                "Logic rule must be wrapped in balanced parentheses, e.g. ((NETWORK,UDP),(DST-PORT,443))"
            } else null
        }
        else -> null
    }
}

internal fun policyDescription(policy: String): String = when (policy.uppercase()) {
    "REJECT" -> "Block immediately (returns NXDOMAIN / connection refused)."
    "REJECT-DROP" -> "Silently drop packets without sending a response."
    "DIRECT" -> "Bypass ad-blocking and allow connection directly (Whitelist)."
    "PASS" -> "Stop evaluating remaining rules and allow."
    "PASS-RULE" -> "Skip this rule group and continue evaluating."
    "PROXY" -> "Route matching traffic through the configured proxy."
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleTypePickerSheet(
    selectedType: String,
    onSelectType: (String) -> Unit,
    onOpenDocs: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(RuleTypeCategory.POPULAR) }

    val filteredEntries = remember(searchQuery, selectedCategory) {
        val q = searchQuery.trim()
        RuleTypeCatalog.entries.filter { meta ->
            val matchesCategory = when {
                q.isNotEmpty() -> true // Search across all categories when typing
                selectedCategory == RuleTypeCategory.ALL -> true
                selectedCategory == RuleTypeCategory.POPULAR -> meta.isPopular
                else -> meta.category == selectedCategory
            }
            val matchesQuery = q.isEmpty() ||
                meta.type.contains(q, ignoreCase = true) ||
                meta.summary.contains(q, ignoreCase = true) ||
                meta.example.contains(q, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.rule_field_type),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = {
                    onDismiss()
                    onOpenDocs()
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.rule_docs_action_tooltip))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search rule type (e.g. port, ip, app, regex...)") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RuleTypeCategory.entries.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category && searchQuery.isBlank(),
                        onClick = {
                            selectedCategory = category
                            searchQuery = ""
                        },
                        label = { Text(category.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredEntries, key = { it.type }) { meta ->
                    val isSelected = meta.type.equals(selectedType, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectType(meta.type)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = meta.type,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (meta.isAdvanced) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = "Advanced",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = meta.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "e.g. ${meta.example}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
