package app.pwhs.blockads.ui.proxyvpn.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ProxySortOption

@Composable
fun ProxyNodeToolsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    sortOption: ProxySortOption,
    onSortOptionChange: (ProxySortOption) -> Unit,
    hasSubscription: Boolean,
    isUpdatingSubscription: Boolean,
    onUpdateSubscription: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onRemoveDuplicates: () -> Unit,
    onImportFile: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (sortOption == ProxySortOption.DEFAULT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            text = {
                Text(
                    stringResource(R.string.proxy_sort_default),
                    fontWeight = if (sortOption == ProxySortOption.DEFAULT) FontWeight.Bold else FontWeight.Normal
                )
            },
            onClick = {
                onSortOptionChange(ProxySortOption.DEFAULT)
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (sortOption == ProxySortOption.LATENCY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            text = {
                Text(
                    stringResource(R.string.proxy_sort_latency),
                    fontWeight = if (sortOption == ProxySortOption.LATENCY) FontWeight.Bold else FontWeight.Normal
                )
            },
            onClick = {
                onSortOptionChange(ProxySortOption.LATENCY)
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (sortOption == ProxySortOption.NAME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            text = {
                Text(
                    stringResource(R.string.proxy_sort_name),
                    fontWeight = if (sortOption == ProxySortOption.NAME) FontWeight.Bold else FontWeight.Normal
                )
            },
            onClick = {
                onSortOptionChange(ProxySortOption.NAME)
                onDismissRequest()
            }
        )

        HorizontalDivider()

        if (hasSubscription) {
            DropdownMenuItem(
                leadingIcon = {
                    if (isUpdatingSubscription) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                },
                text = { Text("Update Subscription") },
                enabled = !isUpdatingSubscription,
                onClick = {
                    onDismissRequest()
                    onUpdateSubscription()
                }
            )
        }

        if (onImportFile != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                text = { Text(stringResource(R.string.wireguard_import_button)) },
                onClick = {
                    onDismissRequest()
                    onImportFile()
                }
            )
        }

        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Default.RssFeed, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            text = { Text("Manage Subscriptions") },
            onClick = {
                onDismissRequest()
                onOpenSubscriptions()
            }
        )

        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            text = { Text("Remove Duplicates") },
            onClick = {
                onDismissRequest()
                onRemoveDuplicates()
            }
        )
    }
}
