package app.pwhs.blockads.ui.tailscale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun TailscaleExitNodeCard(
    exitNodes: List<TailscaleExitNodeItem>,
    activeExitNodeId: Long,
    onSelectExitNode: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeNode = exitNodes.firstOrNull { it.id == activeExitNodeId }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tailscale_exit_node),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = activeNode?.name ?: stringResource(R.string.tailscale_exit_node_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeNode != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (exitNodes.isEmpty()) {
                if (activeExitNodeId != 0L) {
                    ExitNodeOptionRow(
                        title = stringResource(R.string.tailscale_exit_node_none),
                        subtitle = null,
                        selected = false,
                        onClick = { onSelectExitNode(0L) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = stringResource(R.string.tailscale_exit_node_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ExitNodeOptionRow(
                    title = stringResource(R.string.tailscale_exit_node_none),
                    subtitle = null,
                    selected = activeExitNodeId == 0L,
                    onClick = { onSelectExitNode(0L) }
                )
                exitNodes.forEach { node ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    ExitNodeOptionRow(
                        title = node.name,
                        subtitle = node.ip.takeIf { it.isNotBlank() },
                        selected = activeExitNodeId == node.id,
                        onClick = { onSelectExitNode(node.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExitNodeOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
