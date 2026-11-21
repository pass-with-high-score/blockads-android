package app.pwhs.blockads.ui.proxyvpn.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ProxyProtocol
import app.pwhs.blockads.data.entities.ProxySortOption
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.wireguard.component.ProfileRow
import app.pwhs.blockads.utils.NodePingResult

@Composable
fun UnifiedProxyNodesContent(
    wgProfiles: List<WireGuardProfile>,
    proxyProfiles: List<ProxyProfile>,
    activeWgId: String?,
    activeProxyId: String?,
    activeProxyType: String,
    isProxyActive: Boolean,
    isLoadingWg: Boolean,
    wgPingResults: Map<String, NodePingResult>,
    proxyPingResults: Map<String, NodePingResult>,
    isPingingAll: Boolean,
    searchQuery: String,
    onPingWireGuard: (WireGuardProfile) -> Unit,
    onPingProxy: (String) -> Unit,
    onPingAll: () -> Unit,
    onSelectWireGuard: (WireGuardProfile) -> Unit,
    onSelectProxy: (ProxyProfile) -> Unit,
    onEditWireGuard: (String) -> Unit,
    onEditProxy: (ProxyProfile) -> Unit,
    onRenameWireGuard: (String, String) -> Unit,
    onRenameProxy: (String, String) -> Unit,
    onDeleteWireGuard: (String) -> Unit,
    onDeleteProxy: (String) -> Unit,
    onImportUri: (Uri) -> Unit,
    onAddClick: () -> Unit,
    onClearSearch: () -> Unit,
    hasSubscription: Boolean,
    isUpdatingSubscription: Boolean,
    onUpdateSubscription: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onRemoveDuplicates: () -> Unit,
    modifier: Modifier = Modifier
) {
    var renameWgTarget by remember { mutableStateOf<WireGuardProfile?>(null) }
    var deleteWgTarget by remember { mutableStateOf<WireGuardProfile?>(null) }
    var renameProxyTarget by remember { mutableStateOf<ProxyProfile?>(null) }
    var deleteProxyTarget by remember { mutableStateOf<ProxyProfile?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onImportUri(it) }
    }

    val totalNodes = wgProfiles.size + proxyProfiles.size

    Box(modifier = modifier.fillMaxSize()) {
        if (totalNodes == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
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
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenSubscriptions,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.RssFeed, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Subscriptions")
                    }
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.wireguard_import_button))
                    }
                }
            }
        } else {
            val availableProtocols = remember(proxyProfiles) {
                proxyProfiles.map { it.protocol }.distinct()
            }
            var selectedProtocol by remember { mutableStateOf<String?>(null) }
            var filterObfuscatedOnly by remember { mutableStateOf(false) }
            var sortOption by remember { mutableStateOf(ProxySortOption.DEFAULT) }
            var showToolsMenu by remember { mutableStateOf(false) }

            val obfuscatedCount = remember(wgProfiles, proxyProfiles) {
                wgProfiles.count { it.isAmnezia } + proxyProfiles.count { it.isObfuscated }
            }

            // Build unified list
            val isWgActive = isProxyActive && activeProxyType == AppPreferences.PROXY_TYPE_WIREGUARD
            val isProxyTypeActive = isProxyActive && (activeProxyType == AppPreferences.PROXY_TYPE_SHADOWSOCKS || activeProxyType == AppPreferences.PROXY_TYPE_NODE)

            val unifiedList: List<UnifiedNodeItem> = remember(
                wgProfiles, proxyProfiles, activeWgId, activeProxyId, isWgActive, isProxyTypeActive
            ) {
                val list = mutableListOf<UnifiedNodeItem>()
                wgProfiles.forEach {
                    list.add(UnifiedNodeItem.WireGuard(it, isActive = isWgActive && it.id == activeWgId))
                }
                proxyProfiles.forEach {
                    list.add(UnifiedNodeItem.Proxy(it, isActive = isProxyTypeActive && it.id == activeProxyId))
                }
                list
            }

            val filteredList = remember(
                unifiedList, searchQuery, selectedProtocol, filterObfuscatedOnly, sortOption,
                wgPingResults, proxyPingResults
            ) {
                val filtered = unifiedList.filter { item ->
                    val matchesObfs = !filterObfuscatedOnly || item.isObfuscated
                    val matchesProtocol = when (selectedProtocol) {
                        null -> true
                        "WIREGUARD" -> item is UnifiedNodeItem.WireGuard
                        else -> item is UnifiedNodeItem.Proxy && item.profile.protocol.name == selectedProtocol
                    }
                    val matchesQuery = if (searchQuery.isBlank()) true else {
                        when (item) {
                            is UnifiedNodeItem.WireGuard -> {
                                val ep = item.profile.config.peers.firstOrNull()?.endpoint.orEmpty()
                                val addr = item.profile.config.interfaceConfig.address.firstOrNull().orEmpty()
                                item.profile.name.contains(searchQuery, ignoreCase = true) ||
                                    ep.contains(searchQuery, ignoreCase = true) ||
                                    addr.contains(searchQuery, ignoreCase = true)
                            }
                            is UnifiedNodeItem.Proxy -> {
                                item.profile.displayName.contains(searchQuery, ignoreCase = true) ||
                                    item.profile.server.contains(searchQuery, ignoreCase = true) ||
                                    item.profile.tag.contains(searchQuery, ignoreCase = true)
                            }
                        }
                    }
                    matchesObfs && matchesProtocol && matchesQuery
                }

                when (sortOption) {
                    ProxySortOption.DEFAULT -> filtered
                    ProxySortOption.NAME -> filtered.sortedBy { it.name.lowercase() }
                    ProxySortOption.LATENCY -> filtered.sortedWith { a, b ->
                        val resA = if (a is UnifiedNodeItem.WireGuard) wgPingResults[a.id] else proxyPingResults[a.id]
                        val resB = if (b is UnifiedNodeItem.WireGuard) wgPingResults[b.id] else proxyPingResults[b.id]
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
                // 1. Filter Chips Row
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
                                    "${stringResource(R.string.proxy_filter_all)} ($totalNodes)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    if (wgProfiles.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = selectedProtocol == "WIREGUARD" && !filterObfuscatedOnly,
                                onClick = {
                                    filterObfuscatedOnly = false
                                    selectedProtocol = if (selectedProtocol == "WIREGUARD") null else "WIREGUARD"
                                },
                                label = {
                                    Text(
                                        "WireGuard (${wgProfiles.size})",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
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
                    items(availableProtocols) { protocol ->
                        val count = proxyProfiles.count { it.protocol == protocol }
                        FilterChip(
                            selected = selectedProtocol == protocol.name && !filterObfuscatedOnly,
                            onClick = {
                                filterObfuscatedOnly = false
                                selectedProtocol = if (selectedProtocol == protocol.name) null else protocol.name
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

                // 2. Sub-header: Node count & Quick actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val countText = if (filteredList.size != totalNodes) {
                        "${filteredList.size} / $totalNodes nodes"
                    } else {
                        "$totalNodes nodes"
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
                                onRemoveDuplicates = onRemoveDuplicates,
                                onImportFile = { filePickerLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                    }
                }

                // 3. Main List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = filteredList,
                        key = { it.id }
                    ) { item ->
                        when (item) {
                            is UnifiedNodeItem.WireGuard -> {
                                ProfileRow(
                                    profile = item.profile,
                                    isActive = item.isActive,
                                    pingResult = wgPingResults[item.id],
                                    onPing = { onPingWireGuard(item.profile) },
                                    onClick = { onSelectWireGuard(item.profile) },
                                    onEdit = { onEditWireGuard(item.id) },
                                    onRename = { renameWgTarget = item.profile },
                                    onDelete = { deleteWgTarget = item.profile }
                                )
                            }
                            is UnifiedNodeItem.Proxy -> {
                                ProxyNodeRow(
                                    profile = item.profile,
                                    isActive = item.isActive,
                                    pingResult = proxyPingResults[item.id],
                                    onPing = { onPingProxy(item.id) },
                                    onClick = { onSelectProxy(item.profile) },
                                    onEdit = { onEditProxy(item.profile) },
                                    onRename = { renameProxyTarget = item.profile },
                                    onDelete = { deleteProxyTarget = item.profile }
                                )
                            }
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
            onClick = onAddClick,
            modifier = Modifier
                .align(if (isExpandedLayout) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )

        AnimatedVisibility(
            visible = isLoadingWg,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            CircularProgressIndicator()
        }
    }

    // Rename WireGuard Dialog
    renameWgTarget?.let { target ->
        WireGuardRenameDialog(
            target = target,
            onDismiss = { renameWgTarget = null },
            onRename = onRenameWireGuard
        )
    }

    // Delete WireGuard Dialog
    deleteWgTarget?.let { target ->
        WireGuardDeleteDialog(
            target = target,
            onDismiss = { deleteWgTarget = null },
            onDelete = onDeleteWireGuard
        )
    }

    // Rename Proxy Dialog
    renameProxyTarget?.let { profile ->
        ProxyNodeRenameDialog(
            profile = profile,
            onDismiss = { renameProxyTarget = null },
            onRename = onRenameProxy
        )
    }

    // Delete Proxy Dialog
    deleteProxyTarget?.let { profile ->
        ProxyNodeDeleteDialog(
            profile = profile,
            onDismiss = { deleteProxyTarget = null },
            onDelete = onDeleteProxy
        )
    }
}
