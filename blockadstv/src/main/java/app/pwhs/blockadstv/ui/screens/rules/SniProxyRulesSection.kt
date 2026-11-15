package app.pwhs.blockadstv.ui.screens.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.model.SniProxyRule
import app.pwhs.blockadstv.data.model.SniProxyRuleCodec
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import app.pwhs.blockadstv.ui.theme.TextTertiary
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SniProxyRulesSection(
    tvPrefs: TvPreferences,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val rawRulesJson by tvPrefs.sniProxyRulesJson.collectAsStateWithLifecycle(initialValue = "")
    val rules = remember(rawRulesJson) { SniProxyRuleCodec.fromJsonList(rawRulesJson) }

    var editingRule by remember { mutableStateOf<SniProxyRule?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    fun persist(updated: List<SniProxyRule>) {
        scope.launch {
            tvPrefs.setSniProxyRulesJson(SniProxyRuleCodec.toJsonList(updated))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SniActionChip(
                label = "Add SNI Proxy",
                accent = NeonGreen,
                onClick = {
                    editingRule = null
                    showDialog = true
                },
            )
            SniActionChip(
                label = "YouTube TV Preset",
                accent = MaterialTheme.colorScheme.primary,
                onClick = {
                    editingRule = SniProxyRule(
                        name = "YouTube TV",
                        targetIp = "",
                        targetPort = 443,
                        packageNames = listOf(SniProxyRuleCodec.YOUTUBE_TV_PACKAGE),
                        domains = SniProxyRuleCodec.YOUTUBE_PRESET_DOMAINS,
                        enabled = true,
                    )
                    showDialog = true
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (rules.isEmpty()) {
            Text(
                text = "No SNI Proxy rules yet. Add a rule or use YouTube TV Preset above.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules, key = { it.id }) { rule ->
                    SniProxyRuleRow(
                        rule = rule,
                        onToggle = {
                            val updated = rules.map {
                                if (it.id == rule.id) it.copy(enabled = !it.enabled) else it
                            }
                            persist(updated)
                        },
                        onEdit = {
                            editingRule = rule
                            showDialog = true
                        },
                    )
                }
            }
        }
    }

    if (showDialog) {
        AddEditSniProxyDialog(
            initialRule = editingRule,
            onDismiss = { showDialog = false },
            onSave = { saved ->
                val exists = rules.any { it.id == saved.id }
                val updated = if (exists) {
                    rules.map { if (it.id == saved.id) saved else it }
                } else {
                    rules + saved
                }
                persist(updated)
                showDialog = false
            },
            onDelete = { id ->
                persist(rules.filterNot { it.id == id })
                showDialog = false
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SniProxyRuleRow(
    rule: SniProxyRule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    var editFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (rowFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (rowFocused) 2.dp else 1.dp,
                color = if (rowFocused) (if (rule.enabled) NeonGreen else MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { rowFocused = it.isFocused }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                        onToggle()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.name.ifBlank { rule.targetAddress },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (rule.enabled) "ACTIVE" else "PAUSED",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rule.enabled) NeonGreen else TextTertiary,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            val scopeDesc = buildList {
                add("Target: ${rule.targetAddress}")
                if (rule.domains.isNotEmpty()) add("Domains: ${rule.domains.joinToString(", ")}")
                if (rule.packageNames.isNotEmpty()) add("Apps: ${rule.packageNames.joinToString(", ")}")
            }.joinToString("  •  ")
            Text(
                text = scopeDesc,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (editFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (editFocused) 2.dp else 1.dp,
                    color = if (editFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                )
                .onFocusChanged { editFocused = it.isFocused }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                        onEdit()
                        true
                    } else {
                        false
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit",
                tint = if (editFocused) MaterialTheme.colorScheme.primary else TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Edit",
                style = MaterialTheme.typography.labelMedium,
                color = if (editFocused) MaterialTheme.colorScheme.primary else TextSecondary,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SniActionChip(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) accent else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = if (isFocused) accent else TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) accent else TextSecondary,
        )
    }
}
