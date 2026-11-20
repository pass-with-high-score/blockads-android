package app.pwhs.blockads.ui.domainrules.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.pwhs.blockads.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainRulesTopBar(
    totalRules: Int,
    selectedCount: Int,
    isReorderMode: Boolean,
    showRuleActions: Boolean,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBulkDelete: () -> Unit,
    onToggleReorderMode: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenDocs: () -> Unit,
    onOpenImport: () -> Unit,
    onAddClick: () -> Unit
) {
    val isSelectionMode = selectedCount > 0

    TopAppBar(
        navigationIcon = {
            if (isSelectionMode) {
                IconButton(onClick = onClearSelection) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_cancel)
                    )
                }
            }
        },
        title = {
            if (isSelectionMode) {
                Text(
                    text = stringResource(R.string.rule_bulk_selected, selectedCount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.domain_rules_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isReorderMode) {
                            stringResource(R.string.rule_reorder_mode)
                        } else {
                            pluralStringResource(R.plurals.domain_rules_count, totalRules, totalRules)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isReorderMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        actions = {
            when {
                isSelectionMode -> {
                    IconButton(onClick = onSelectAll) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = "Select all"
                        )
                    }
                    IconButton(onClick = onBulkDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete selected",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                isReorderMode -> {
                    IconButton(onClick = onToggleReorderMode) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Done",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                else -> {
                    if (showRuleActions) {
                        IconButton(onClick = onOpenPresets) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.rule_presets_title),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onToggleReorderMode) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = stringResource(R.string.rule_reorder_mode)
                            )
                        }
                    }
                    IconButton(onClick = onOpenDocs) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.rule_docs_action_tooltip)
                        )
                    }
                    IconButton(onClick = onOpenImport) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = stringResource(R.string.settings_import)
                        )
                    }
                    IconButton(onClick = onAddClick) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
