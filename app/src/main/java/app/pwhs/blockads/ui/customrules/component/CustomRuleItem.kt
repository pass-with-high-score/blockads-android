package app.pwhs.blockads.ui.customrules.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.data.CustomDnsRule
import app.pwhs.blockads.data.RuleType

@Composable
fun CustomRuleItem(
    rule: CustomDnsRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (rule.ruleType) {
                    RuleType.BLOCK -> Icons.Default.Block
                    RuleType.ALLOW -> Icons.Default.CheckCircle
                    RuleType.COMMENT -> Icons.Default.Info
                },
                contentDescription = null,
                tint = when (rule.ruleType) {
                    RuleType.BLOCK -> Color(0xFFF44336)
                    RuleType.ALLOW -> Color(0xFF4CAF50)
                    RuleType.COMMENT -> Color.Gray
                },
                modifier = Modifier.size(24.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = rule.rule,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    color = if (rule.isEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
                if (rule.domain.isNotEmpty()) {
                    Text(
                        text = rule.domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            if (rule.ruleType != RuleType.COMMENT) {
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = { onToggle() }
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFF44336)
                )
            }
        }
    }
}
