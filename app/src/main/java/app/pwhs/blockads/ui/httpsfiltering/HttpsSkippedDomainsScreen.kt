package app.pwhs.blockads.ui.httpsfiltering

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.TunnelRuleLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class SkippedFilter { ALL, AUTO_CUSTOM, BUILT_IN }

private data class SkippedDomainItem(
    val domain: String,
    val isBuiltIn: Boolean,
    val isEnabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpsSkippedDomainsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SkippedDomainItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SkippedFilter.ALL) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newDomainInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        items = loadSkippedDomains(context)
    }

    val filtered = remember(items, searchQuery, filter) {
        items.filter { item ->
            val matchesFilter = when (filter) {
                SkippedFilter.ALL -> true
                SkippedFilter.AUTO_CUSTOM -> !item.isBuiltIn
                SkippedFilter.BUILT_IN -> item.isBuiltIn
            }
            val matchesQuery = searchQuery.isBlank() || item.domain.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.https_skipped_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = stringResource(R.string.https_skipped_count, filtered.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.https_skipped_add_desc),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.https_skipped_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = filter == SkippedFilter.ALL, onClick = { filter = SkippedFilter.ALL }, label = { Text(stringResource(R.string.https_debug_logs_filter_all)) })
                FilterChip(selected = filter == SkippedFilter.AUTO_CUSTOM, onClick = { filter = SkippedFilter.AUTO_CUSTOM }, label = { Text(stringResource(R.string.https_skipped_filter_auto)) })
                FilterChip(selected = filter == SkippedFilter.BUILT_IN, onClick = { filter = SkippedFilter.BUILT_IN }, label = { Text(stringResource(R.string.https_skipped_filter_builtin)) })
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.https_skipped_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { "${it.isBuiltIn}:${it.domain}" }) { item ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.domain,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Surface(
                                        color = if (item.isBuiltIn) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (item.isBuiltIn) stringResource(R.string.https_skipped_badge_builtin) else stringResource(R.string.https_skipped_badge_auto),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (item.isBuiltIn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (!item.isBuiltIn) {
                                    IconButton(onClick = {
                                        removeCustomBlacklistDomain(context, item.domain)
                                        items = items.filterNot { !it.isBuiltIn && it.domain == item.domain }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.https_skipped_remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    Switch(
                                        checked = item.isEnabled,
                                        onCheckedChange = { enabled ->
                                            TunnelRuleLoader.setBuiltInPassthroughEnabled(context, item.domain, enabled)
                                            items = items.map {
                                                if (it.isBuiltIn && it.domain == item.domain) it.copy(isEnabled = enabled) else it
                                            }
                                            scope.launch(Dispatchers.IO) {
                                                AdBlockVpnService.activeTunnelAdapter?.reloadExtraPassthrough()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.https_skipped_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = newDomainInput,
                    onValueChange = { newDomainInput = it },
                    placeholder = { Text(stringResource(R.string.https_skipped_dialog_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = newDomainInput.trim().lowercase(Locale.US)
                    if (cleaned.isNotEmpty() && !cleaned.contains(" ")) {
                        addCustomBlacklistDomain(context, cleaned)
                        if (items.none { !it.isBuiltIn && it.domain == cleaned }) {
                            items = listOf(SkippedDomainItem(cleaned, isBuiltIn = false)) + items
                        }
                    }
                    newDomainInput = ""
                    showAddDialog = false
                }) {
                    Text(stringResource(R.string.common_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

private suspend fun loadSkippedDomains(context: Context): List<SkippedDomainItem> = withContext(Dispatchers.IO) {
    val result = mutableListOf<SkippedDomainItem>()
    val blacklistFile = File(context.filesDir, "mitm_blacklist.txt")
    if (blacklistFile.exists()) {
        runCatching {
            blacklistFile.readLines()
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
                .distinct()
                .forEach { result.add(SkippedDomainItem(it, isBuiltIn = false)) }
        }
    }
    val disabledBuiltIn = TunnelRuleLoader.getDisabledBuiltInPassthrough(context)
    runCatching {
        context.assets.open("https_passthrough.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
                .distinct()
                .forEach { result.add(SkippedDomainItem(it, isBuiltIn = true, isEnabled = !disabledBuiltIn.contains(it))) }
        }
    }
    result
}

private fun addCustomBlacklistDomain(context: Context, domain: String) {
    val file = File(context.filesDir, "mitm_blacklist.txt")
    val existing = if (file.exists()) file.readLines().map { it.trim().lowercase(Locale.US) } else emptyList()
    if (!existing.contains(domain)) {
        file.appendText("$domain\n")
    }
}

private fun removeCustomBlacklistDomain(context: Context, domain: String) {
    val file = File(context.filesDir, "mitm_blacklist.txt")
    if (!file.exists()) return
    val remaining = file.readLines().filterNot { it.trim().equals(domain, ignoreCase = true) }
    file.writeText(if (remaining.isEmpty()) "" else remaining.joinToString("\n") + "\n")
}
