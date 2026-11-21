package app.pwhs.blockads.ui.proxyvpn.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RssFeed
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ProxySortOption
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.utils.NodePingResult

@Composable
fun ProxyNodesTabContent(
    profiles: List<ProxyProfile>,
    activeId: String?,
    isProxyActive: Boolean,
    pingResults: Map<String, NodePingResult> = emptyMap(),
    isPingingAll: Boolean = false,
    onPingNode: (String) -> Unit = {},
    onPingAll: () -> Unit = {},
    onSelectProfile: (ProxyProfile) -> Unit,
    onEditProfile: (ProxyProfile) -> Unit,
    onAddNodeClick: () -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    searchQuery: String = "",
    onClearSearch: () -> Unit = {},
    hasSubscription: Boolean = false,
    isUpdatingSubscription: Boolean = false,
    onUpdateSubscription: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onRemoveDuplicates: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var renameTarget by remember { mutableStateOf<ProxyProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<ProxyProfile?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.proxy_node_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.proxy_node_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenSubscriptions,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.RssFeed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Subscriptions")
                }
            }
        } else {
            val availableProtocols = remember(profiles) {
                profiles.map { it.protocol }.distinct()
            }
            var selectedProtocol by remember { mutableStateOf<app.pwhs.blockads.data.entities.ProxyProtocol?>(null) }
            var filterObfuscatedOnly by remember { mutableStateOf(false) }

            var sortOption by remember { mutableStateOf(ProxySortOption.DEFAULT) }
            var showToolsMenu by remember { mutableStateOf(false) }

            val obfuscatedCount = remember(profiles) { profiles.count { it.isObfuscated } }

            val filteredProfiles = remember(profiles, searchQuery, selectedProtocol, filterObfuscatedOnly, sortOption, pingResults) {
                val list = profiles.filter { profile ->
                    val matchesObfs = !filterObfuscatedOnly || profile.isObfuscated
                    val matchesProtocol = selectedProtocol == null || profile.protocol == selectedProtocol
                    val matchesQuery = searchQuery.isBlank() ||
                        profile.displayName.contains(searchQuery, ignoreCase = true) ||
                        profile.server.contains(searchQuery, ignoreCase = true) ||
                        profile.tag.contains(searchQuery, ignoreCase = true)
                    matchesObfs && matchesProtocol && matchesQuery
                }
                when (sortOption) {
                    ProxySortOption.DEFAULT -> list
                    ProxySortOption.NAME -> list.sortedBy { it.displayName.lowercase() }
                    ProxySortOption.LATENCY -> list.sortedWith { a, b ->
                        val resA = pingResults[a.id]
                        val resB = pingResults[b.id]
                        val scoreA = when (resA) {
                            is NodePingResult.Success -> resA.latencyMs
                            is NodePingResult.Testing -> Long.MAX_VALUE - 3
                            null -> Long.MAX_VALUE - 2
                            is NodePingResult.Timeout -> Long.MAX_VALUE - 1
                            is NodePingResult.Error -> Long.MAX_VALUE
                        }
                        val scoreB = when (resB) {
                            is NodePingResult.Success -> resB.latencyMs
                            is NodePingResult.Testing -> Long.MAX_VALUE - 3
                            null -> Long.MAX_VALUE - 2
                            is NodePingResult.Timeout -> Long.MAX_VALUE - 1
                            is NodePingResult.Error -> Long.MAX_VALUE
                        }
                        scoreA.compareTo(scoreB)
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Full-width Filter Chips Row
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedProtocol == null && !filterObfuscatedOnly,
                            onClick = {
                                selectedProtocol = null
                                filterObfuscatedOnly = false
                            },
                            label = {
                                Text(
                                    "${stringResource(R.string.proxy_filter_all)} (${profiles.size})",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    if (obfuscatedCount > 0) {
                        item {
                            FilterChip(
                                selected = filterObfuscatedOnly,
                                onClick = {
                                    filterObfuscatedOnly = !filterObfuscatedOnly
                                    if (filterObfuscatedOnly) selectedProtocol = null
                                },
                                label = {
                                    Text(
                                        "${stringResource(R.string.proxy_filter_obfuscated)} ($obfuscatedCount)",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                    if (availableProtocols.size > 1) {
                        items(availableProtocols) { protocol ->
                            val count = profiles.count { it.protocol == protocol }
                            FilterChip(
                                selected = selectedProtocol == protocol && !filterObfuscatedOnly,
                                onClick = {
                                    filterObfuscatedOnly = false
                                    selectedProtocol = if (selectedProtocol == protocol) null else protocol
                                },
                                label = {
                                    Text(
                                        "${protocol.displayName} ($count)",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                // 2. Sub-header: Node count & Quick actions (Ping All, More Actions Menu)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val countText = if (filteredProfiles.size != profiles.size) {
                        "${filteredProfiles.size} / ${profiles.size} nodes"
                    } else {
                        "${profiles.size} nodes"
                    }
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = onPingAll,
                            enabled = !isPingingAll,
                            modifier = Modifier.size(34.dp)
                        ) {
                            if (isPingingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.proxy_ping_test_all),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { showToolsMenu = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More actions",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (sortOption != ProxySortOption.DEFAULT) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            ProxyNodeToolsMenu(
                                expanded = showToolsMenu,
                                onDismissRequest = { showToolsMenu = false },
                                sortOption = sortOption,
                                onSortOptionChange = { sortOption = it },
                                hasSubscription = hasSubscription,
                                isUpdatingSubscription = isUpdatingSubscription,
                                onUpdateSubscription = onUpdateSubscription,
                                onOpenSubscriptions = onOpenSubscriptions,
                                onRemoveDuplicates = onRemoveDuplicates
                            )
                        }
                    }
                }

                // List or Empty Search
                if (filteredProfiles.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.proxy_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            onClearSearch()
                            selectedProtocol = null
                            filterObfuscatedOnly = false
                        }) {
                            Text(stringResource(R.string.firewall_clear_search))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = filteredProfiles,
                            key = { it.id }
                        ) { profile ->
                            ProxyNodeRow(
                                profile = profile,
                                isActive = isProxyActive && profile.id == activeId,
                                pingResult = pingResults[profile.id],
                                onPing = { onPingNode(profile.id) },
                                onClick = { onSelectProfile(profile) },
                                onEdit = { onEditProfile(it) },
                                onRename = { renameTarget = it },
                                onDelete = { deleteTarget = it }
                            )
                        }
                    }
                }
            }
        }

        // Add Node FAB
        val isExpandedLayout = rememberIsExpandedLayout()
        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.proxy_node_add_node)) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            onClick = onAddNodeClick,
            modifier = Modifier
                .align(if (isExpandedLayout) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    }

    renameTarget?.let { profile ->
        ProxyNodeRenameDialog(
            profile = profile,
            onDismiss = { renameTarget = null },
            onRename = onRenameProfile
        )
    }

    deleteTarget?.let { profile ->
        ProxyNodeDeleteDialog(
            profile = profile,
            onDismiss = { deleteTarget = null },
            onDelete = onDeleteProfile
        )
    }
}
