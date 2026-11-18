package app.pwhs.blockads.ui.logs.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.data.logging.HttpDebugLogEntry
import app.pwhs.blockads.data.logging.HttpDebugLogStore
import app.pwhs.blockads.data.logging.HttpLogAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpDebugLoggerSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    initialSourceFilter: String? = null
) {
    if (!isVisible) return

    val entries by HttpDebugLogStore.entries.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedAction by remember { mutableStateOf<HttpLogAction?>(null) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filtered = remember(entries, searchQuery, selectedAction, initialSourceFilter) {
        entries.filter { e ->
            (initialSourceFilter == null || e.source.equals(initialSourceFilter, ignoreCase = true)) &&
                (selectedAction == null || e.action == selectedAction) &&
                (searchQuery.isBlank() ||
                    e.url.contains(searchQuery, ignoreCase = true) ||
                    e.rule.contains(searchQuery, ignoreCase = true) ||
                    e.initiator.contains(searchQuery, ignoreCase = true) ||
                    e.resourceType.contains(searchQuery, ignoreCase = true))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF14141A),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "HTTP & Scriptlet Logger",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${filtered.size} / ${entries.size} events",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { HttpDebugLogStore.clear() }) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear logs",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter logger content...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF7C6FE0),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )

            val blockedCount = remember(entries, initialSourceFilter) {
                entries.count { (initialSourceFilter == null || it.source.equals(initialSourceFilter, ignoreCase = true)) && it.action == HttpLogAction.BLOCKED }
            }
            val scriptletCount = remember(entries, initialSourceFilter) {
                entries.count { (initialSourceFilter == null || it.source.equals(initialSourceFilter, ignoreCase = true)) && it.action == HttpLogAction.SCRIPTLET }
            }
            val surrogateCount = remember(entries, initialSourceFilter) {
                entries.count { (initialSourceFilter == null || it.source.equals(initialSourceFilter, ignoreCase = true)) && it.action == HttpLogAction.SURROGATE }
            }
            val allowedCount = remember(entries, initialSourceFilter) {
                entries.count { (initialSourceFilter == null || it.source.equals(initialSourceFilter, ignoreCase = true)) && it.action == HttpLogAction.ALLOWED }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LoggerFilterChip("All (${entries.size})", selectedAction == null) { selectedAction = null }
                LoggerFilterChip("Blocked ($blockedCount)", selectedAction == HttpLogAction.BLOCKED) {
                    selectedAction = if (selectedAction == HttpLogAction.BLOCKED) null else HttpLogAction.BLOCKED
                }
                LoggerFilterChip("Scriptlet ($scriptletCount)", selectedAction == HttpLogAction.SCRIPTLET) {
                    selectedAction = if (selectedAction == HttpLogAction.SCRIPTLET) null else HttpLogAction.SCRIPTLET
                }
                LoggerFilterChip("Surrogate ($surrogateCount)", selectedAction == HttpLogAction.SURROGATE) {
                    selectedAction = if (selectedAction == HttpLogAction.SURROGATE) null else HttpLogAction.SURROGATE
                }
                LoggerFilterChip("Allowed ($allowedCount)", selectedAction == HttpLogAction.ALLOWED) {
                    selectedAction = if (selectedAction == HttpLogAction.ALLOWED) null else HttpLogAction.ALLOWED
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(filtered, key = { it.id }) { entry ->
                    LoggerRowItem(
                        entry = entry,
                        isExpanded = expandedId == entry.id,
                        onClick = {
                            if (entry.detail != null) {
                                expandedId = if (expandedId == entry.id) null else entry.id
                            }
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }
            }
        }
    }
}

@Composable
private fun LoggerFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF3E3566),
            selectedLabelColor = Color.White,
            labelColor = Color.White.copy(alpha = 0.7f)
        )
    )
}

@Composable
private fun LoggerRowItem(
    entry: HttpDebugLogEntry,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val rowBg = when (entry.action) {
        HttpLogAction.BLOCKED -> Color(0xFF441B1E)
        HttpLogAction.SCRIPTLET -> Color(0xFF3F3518)
        HttpLogAction.SURROGATE -> Color(0xFF17333D)
        HttpLogAction.ALLOWED -> Color.Transparent
    }
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestamp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(enabled = entry.detail != null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeStr,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.6f)
            )
            Text(
                text = entry.rule.ifBlank { entry.source },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = when (entry.action) {
                    HttpLogAction.BLOCKED -> Color(0xFFFF8A8A)
                    HttpLogAction.SCRIPTLET -> Color(0xFFFFD56B)
                    HttpLogAction.SURROGATE -> Color(0xFF7AD7F0)
                    HttpLogAction.ALLOWED -> Color.White.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.32f)
            )
            Text(
                text = entry.initiator.ifBlank { "-" },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.22f)
            )
            Text(
                text = "${entry.method.lowercase(Locale.US)} ${entry.resourceType}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1
            )
            Text(
                text = entry.url,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.46f)
            )
        }
        AnimatedVisibility(visible = isExpanded && entry.detail != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(Color(0xFF0D0E14), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = entry.detail.orEmpty(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9FB8FF),
                    lineHeight = 14.sp
                )
            }
        }
    }
}
