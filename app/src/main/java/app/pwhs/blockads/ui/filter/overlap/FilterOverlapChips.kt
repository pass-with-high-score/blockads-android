package app.pwhs.blockads.ui.filter.overlap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.models.FilterRuleKind
import app.pwhs.blockads.utils.OverlapRuleCategory

@Composable
fun OverlapCategoryChips(
    selectedCategory: OverlapRuleCategory,
    onSelectCategory: (OverlapRuleCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = remember {
        listOf(
            OverlapRuleCategory.ALL to R.string.filter_overlap_category_all,
            OverlapRuleCategory.DOMAINS to R.string.filter_overlap_category_domains,
            OverlapRuleCategory.COSMETIC to R.string.filter_overlap_category_cosmetic,
            OverlapRuleCategory.SCRIPTLETS to R.string.filter_overlap_category_scriptlets,
            OverlapRuleCategory.IPS to R.string.filter_overlap_category_ips,
            OverlapRuleCategory.URL_RULES to R.string.filter_overlap_category_url_rules
        )
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories, key = { it.first.name }) { (cat, labelRes) ->
            FilterChip(
                selected = selectedCategory == cat,
                onClick = { onSelectCategory(cat) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
}

@Composable
fun RuleKindBadge(
    kind: FilterRuleKind,
    modifier: Modifier = Modifier
) {
    val (labelRes, icon, color) = when (kind) {
        FilterRuleKind.DOMAINS -> Triple(
            R.string.filter_rule_kind_domains,
            Icons.Default.Dns,
            MaterialTheme.colorScheme.primary
        )
        FilterRuleKind.IPS -> Triple(
            R.string.filter_rule_kind_ips,
            Icons.Default.Lan,
            Color(0xFFEF5350)
        )
        FilterRuleKind.COSMETIC -> Triple(
            R.string.filter_rule_kind_cosmetic,
            Icons.Default.VisibilityOff,
            Color(0xFF7E57C2)
        )
        FilterRuleKind.COSMETIC_EXCEPTIONS -> Triple(
            R.string.filter_rule_kind_cosmetic_exceptions,
            Icons.Default.RemoveCircleOutline,
            Color(0xFF26A69A)
        )
        FilterRuleKind.INJECT_CSS -> Triple(
            R.string.filter_rule_kind_inject_css,
            Icons.Default.Palette,
            Color(0xFFEC407A)
        )
        FilterRuleKind.SCRIPTLETS -> Triple(
            R.string.filter_rule_kind_scriptlets,
            Icons.Default.Terminal,
            Color(0xFFFFA726)
        )
        FilterRuleKind.SCRIPTLET_EXCEPTIONS -> Triple(
            R.string.filter_rule_kind_scriptlet_exceptions,
            Icons.Default.Code,
            Color(0xFF66BB6A)
        )
        FilterRuleKind.BLOCK_PATHS -> Triple(
            R.string.filter_rule_kind_block_paths,
            Icons.Default.LinkOff,
            Color(0xFF42A5F5)
        )
        FilterRuleKind.REMOVE_PARAMS -> Triple(
            R.string.filter_rule_kind_remove_params,
            Icons.Default.FilterAlt,
            Color(0xFF29B6F6)
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
