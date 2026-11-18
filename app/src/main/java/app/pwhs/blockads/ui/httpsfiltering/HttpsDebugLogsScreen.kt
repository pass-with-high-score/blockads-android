package app.pwhs.blockads.ui.httpsfiltering

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.logging.HttpDebugLogEntry
import app.pwhs.blockads.data.logging.HttpDebugLogStore
import app.pwhs.blockads.data.logging.HttpLogAction
import app.pwhs.blockads.ui.httpsfiltering.component.HttpsDebugLogActions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpsDebugLogsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val entries by HttpDebugLogStore.entries.collectAsStateWithLifecycle()
    val isRecording by HttpDebugLogStore.isRecording.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedAction by remember { mutableStateOf<HttpLogAction?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    val filtered = remember(entries, searchQuery, selectedAction, selectedSource) {
        entries.filter { e ->
            (selectedSource == null || e.source.equals(selectedSource, ignoreCase = true)) &&
                (selectedAction == null || e.action == selectedAction) &&
                (searchQuery.isBlank() ||
                    e.url.contains(searchQuery, ignoreCase = true) ||
                    e.rule.contains(searchQuery, ignoreCase = true) ||
                    e.initiator.contains(searchQuery, ignoreCase = true) ||
                    e.resourceType.contains(searchQuery, ignoreCase = true))
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.https_debug_logs_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.https_debug_logs_count, filtered.size, entries.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { HttpDebugLogStore.setRecordingEnabled(!isRecording) }) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (isRecording) R.string.https_debug_logs_pause else R.string.https_debug_logs_resume),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    val exportFailedPrefix = stringResource(R.string.https_debug_logs_export_failed, "")
                    IconButton(
                        enabled = filtered.isNotEmpty(),
                        onClick = {
                            HttpsDebugLogActions.exportAndShare(context, filtered).onFailure { err ->
                                scope.launch { snackbarHostState.showSnackbar("$exportFailedPrefix${err.message}") }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.https_debug_logs_export),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { HttpDebugLogStore.clear() }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.https_debug_logs_clear),
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
                placeholder = { Text(stringResource(R.string.https_debug_logs_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
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
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedAction == null,
                    onClick = { selectedAction = null },
                    label = { Text(stringResource(R.string.https_debug_logs_filter_all)) }
                )
                FilterChip(
                    selected = selectedAction == HttpLogAction.BLOCKED,
                    onClick = { selectedAction = HttpLogAction.BLOCKED },
                    label = { Text(stringResource(R.string.https_debug_logs_filter_blocked)) }
                )
                FilterChip(
                    selected = selectedAction == HttpLogAction.SCRIPTLET,
                    onClick = { selectedAction = HttpLogAction.SCRIPTLET },
                    label = { Text(stringResource(R.string.https_debug_logs_filter_scriptlet)) }
                )
                FilterChip(
                    selected = selectedAction == HttpLogAction.SURROGATE,
                    onClick = { selectedAction = HttpLogAction.SURROGATE },
                    label = { Text(stringResource(R.string.https_debug_logs_filter_surrogate)) }
                )
                FilterChip(
                    selected = selectedAction == HttpLogAction.ALLOWED,
                    onClick = { selectedAction = HttpLogAction.ALLOWED },
                    label = { Text(stringResource(R.string.https_debug_logs_filter_allowed)) }
                )
                FilterChip(
                    selected = selectedSource == "HTTPS",
                    onClick = { selectedSource = if (selectedSource == "HTTPS") null else "HTTPS" },
                    label = { Text(stringResource(R.string.https_debug_logs_filter_https_only)) }
                )
                FilterChip(
                    selected = selectedSource == "Browser",
                    onClick = { selectedSource = if (selectedSource == "Browser") null else "Browser" },
                    label = { Text(stringResource(R.string.https_debug_logs_filter_browser)) }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.https_debug_logs_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.https_debug_logs_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                val copiedUrlMsg = stringResource(R.string.https_debug_logs_copied_url)
                val copiedRuleMsg = stringResource(R.string.https_debug_logs_copied_rule)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        val host = HttpsDebugLogActions.extractHost(entry)
                        val addedMsg = stringResource(R.string.https_debug_logs_skipped_added, host)
                        val existsMsg = stringResource(R.string.https_debug_logs_skipped_exists, host)
                        DebugLogCard(
                            entry = entry,
                            isExpanded = expandedId == entry.id,
                            onClick = {
                                expandedId = if (expandedId == entry.id) null else entry.id
                            },
                            onCopyUrl = {
                                HttpsDebugLogActions.copyToClipboard(context, "URL", entry.url)
                                scope.launch { snackbarHostState.showSnackbar(copiedUrlMsg) }
                            },
                            onCopyRule = {
                                HttpsDebugLogActions.copyToClipboard(context, "Rule", entry.rule)
                                scope.launch { snackbarHostState.showSnackbar(copiedRuleMsg) }
                            },
                            onSkipHttps = {
                                val added = HttpsDebugLogActions.addHostToMitmBlacklist(context, host)
                                scope.launch { snackbarHostState.showSnackbar(if (added) addedMsg else existsMsg) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugLogCard(
    entry: HttpDebugLogEntry,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onCopyUrl: () -> Unit,
    onCopyRule: () -> Unit,
    onSkipHttps: () -> Unit
) {
    val (badgeContainer, badgeContent, cardContainer) = when (entry.action) {
        HttpLogAction.BLOCKED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
        )
        HttpLogAction.SCRIPTLET -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.32f)
        )
        HttpLogAction.SURROGATE -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        )
        HttpLogAction.ALLOWED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceContainerLow
        )
    }
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestamp))
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = badgeContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = entry.action.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeContent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = entry.rule.ifBlank { entry.source },
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "$timeStr · ${entry.source}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${entry.method} · ${entry.resourceType} · ${entry.initiator.ifBlank { "-" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (entry.detail != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = onCopyUrl,
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(stringResource(R.string.https_debug_logs_copy_url), fontSize = 11.sp) }
                        )
                        if (entry.rule.isNotBlank()) {
                            AssistChip(
                                onClick = onCopyRule,
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text(stringResource(R.string.https_debug_logs_copy_rule), fontSize = 11.sp) }
                            )
                        }
                        AssistChip(
                            onClick = onSkipHttps,
                            leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(stringResource(R.string.https_debug_logs_skip_https), fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    }
}
