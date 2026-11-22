package app.pwhs.blockads.ui.filter.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.utils.formatCount

@Composable
internal fun FilterRuleBreakdownCard(
    breakdown: FilterRuleBreakdown,
    modifier: Modifier = Modifier
) {
    if (breakdown.items.isEmpty()) return

    var expandedKind by remember { mutableStateOf<FilterRuleKind?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.filter_detail_rule_breakdown),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (breakdown.totalDiskBytes > 0L) {
                Text(
                    text = formatDiskSize(breakdown.totalDiskBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                breakdown.items.forEachIndexed { index, item ->
                    val isExpanded = expandedKind == item.kind
                    val hasSamples = item.samples.isNotEmpty()
                    val meta = ruleKindVisualMeta(item.kind)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (hasSamples) {
                                    Modifier.clickable {
                                        expandedKind = if (isExpanded) null else item.kind
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(meta.tint.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = meta.icon,
                                    contentDescription = null,
                                    tint = meta.tint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(meta.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(meta.descRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(meta.tint.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = formatCount(item.count),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = meta.tint
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            if (hasSamples) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.size(18.dp))
                            }
                        }

                        AnimatedVisibility(visible = isExpanded && hasSamples) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                item.samples.forEach { sample ->
                                    Text(
                                        text = sample,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (index < breakdown.items.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class RuleKindMeta(
    val labelRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val tint: Color
)

@Composable
private fun ruleKindVisualMeta(kind: FilterRuleKind): RuleKindMeta = when (kind) {
    FilterRuleKind.DOMAINS -> RuleKindMeta(
        R.string.filter_rule_kind_domains,
        R.string.filter_rule_kind_domains_desc,
        Icons.Default.Dns,
        MaterialTheme.colorScheme.primary
    )
    FilterRuleKind.IPS -> RuleKindMeta(
        R.string.filter_rule_kind_ips,
        R.string.filter_rule_kind_ips_desc,
        Icons.Default.Lan,
        Color(0xFFEF5350)
    )
    FilterRuleKind.COSMETIC -> RuleKindMeta(
        R.string.filter_rule_kind_cosmetic,
        R.string.filter_rule_kind_cosmetic_desc,
        Icons.Default.VisibilityOff,
        Color(0xFF7E57C2)
    )
    FilterRuleKind.COSMETIC_EXCEPTIONS -> RuleKindMeta(
        R.string.filter_rule_kind_cosmetic_exceptions,
        R.string.filter_rule_kind_cosmetic_exceptions_desc,
        Icons.Default.RemoveCircleOutline,
        Color(0xFF26A69A)
    )
    FilterRuleKind.INJECT_CSS -> RuleKindMeta(
        R.string.filter_rule_kind_inject_css,
        R.string.filter_rule_kind_inject_css_desc,
        Icons.Default.Palette,
        Color(0xFFEC407A)
    )
    FilterRuleKind.SCRIPTLETS -> RuleKindMeta(
        R.string.filter_rule_kind_scriptlets,
        R.string.filter_rule_kind_scriptlets_desc,
        Icons.Default.Terminal,
        Color(0xFFFFA726)
    )
    FilterRuleKind.SCRIPTLET_EXCEPTIONS -> RuleKindMeta(
        R.string.filter_rule_kind_scriptlet_exceptions,
        R.string.filter_rule_kind_scriptlet_exceptions_desc,
        Icons.Default.Code,
        Color(0xFF66BB6A)
    )
    FilterRuleKind.BLOCK_PATHS -> RuleKindMeta(
        R.string.filter_rule_kind_block_paths,
        R.string.filter_rule_kind_block_paths_desc,
        Icons.Default.LinkOff,
        Color(0xFF42A5F5)
    )
    FilterRuleKind.REMOVE_PARAMS -> RuleKindMeta(
        R.string.filter_rule_kind_remove_params,
        R.string.filter_rule_kind_remove_params_desc,
        Icons.Default.FilterAlt,
        Color(0xFF29B6F6)
    )
}

private fun formatDiskSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
