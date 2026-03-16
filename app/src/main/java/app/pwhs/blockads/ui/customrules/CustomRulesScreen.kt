package app.pwhs.blockads.ui.customrules

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.customrules.component.AddRuleDialog
import app.pwhs.blockads.ui.customrules.component.CustomRuleItem
import app.pwhs.blockads.ui.customrules.component.EmptyRulesState
import app.pwhs.blockads.ui.customrules.component.ImportRulesDialog
import app.pwhs.blockads.ui.customrules.component.InfoDialog
import app.pwhs.blockads.ui.customrules.data.ExportFormat
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRulesScreen(
    viewModel: CustomRulesViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = { }
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resource = LocalResources.current

    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showMenuDropdown by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var selectedExportFormat by remember { mutableStateOf(ExportFormat.JSON) }

    // SAF: Export file launcher
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            if (selectedExportFormat == ExportFormat.JSON) "application/json" else "text/plain"
        )
    ) { uri ->
        uri?.let {
            viewModel.exportRulesToUri(
                uri = it,
                format = selectedExportFormat,
                onSuccess = { count ->
                    Toast.makeText(
                        context,
                        resource.getString(R.string.rules_exported, count),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onError = { errorMsg ->
                    Toast.makeText(
                        context,
                        resource.getString(R.string.export_failed, errorMsg),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    // SAF: Import file launcher
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importRulesFromUri(
                uri = it,
                onSuccess = { count ->
                    Toast.makeText(
                        context,
                        resource.getString(R.string.rules_imported, count),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onError = { errorMsg ->
                    Toast.makeText(
                        context,
                        resource.getString(R.string.import_failed, errorMsg),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_rules)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Info")
                    }
                    Box {
                        IconButton(onClick = { showMenuDropdown = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            containerColor = MaterialTheme.colorScheme.background,
                            expanded = showMenuDropdown,
                            onDismissRequest = { showMenuDropdown = false }
                        ) {
                            // ── Clipboard-based ──
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_rules)) },
                                onClick = {
                                    showMenuDropdown = false
                                    showImportDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Upload, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_rules)) },
                                onClick = {
                                    showMenuDropdown = false
                                    val rulesText = viewModel.exportRules()
                                    if (rulesText.isNotEmpty()) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Custom Rules", rulesText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, resource.getString(R.string.rules_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, resource.getString(R.string.no_rules_to_export), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                }
                            )

                            HorizontalDivider()

                            // ── File-based (SAF) ──
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_file)) },
                                onClick = {
                                    showMenuDropdown = false
                                    importFileLauncher.launch(
                                        arrayOf("application/json", "text/plain", "*/*")
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileUpload, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_to_file)) },
                                onClick = {
                                    showMenuDropdown = false
                                    showExportFormatDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileDownload, contentDescription = null)
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_all_rules)) },
                                onClick = {
                                    showMenuDropdown = false
                                    showDeleteAllDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Rule")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (rules.isEmpty()) {
                EmptyRulesState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(rules) { rule ->
                        CustomRuleItem(
                            rule = rule,
                            onToggle = { viewModel.toggleRule(rule) },
                            onDelete = { viewModel.deleteRule(rule) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddRuleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { ruleText ->
                viewModel.addRule(
                    ruleText = ruleText,
                    onSuccess = {
                        showAddDialog = false
                        Toast.makeText(context, resource.getString(R.string.rule_added), Toast.LENGTH_SHORT).show()
                    },
                    onError = { errorMsg ->
                        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    if (showImportDialog) {
        ImportRulesDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { rulesText ->
                viewModel.importRules(
                    rulesText = rulesText,
                    onSuccess = { count ->
                        showImportDialog = false
                        Toast.makeText(context, resource.getString(R.string.rules_imported, count), Toast.LENGTH_SHORT).show()
                    },
                    onError = { errorMsg ->
                        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.delete_all_rules)) },
            text = { Text(stringResource(R.string.delete_all_rules_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllRules()
                    showDeleteAllDialog = false
                    Toast.makeText(context, resource.getString(R.string.all_rules_deleted), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ── Export Format Dialog ──
    if (showExportFormatDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.background,
            onDismissRequest = { showExportFormatDialog = false },
            title = { Text(stringResource(R.string.export_format_title)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showExportFormatDialog = false
                        selectedExportFormat = ExportFormat.JSON
                        exportFileLauncher.launch("blockads_rules.json")
                    }) {
                        Text(
                            stringResource(R.string.export_format_json),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    TextButton(onClick = {
                        showExportFormatDialog = false
                        selectedExportFormat = ExportFormat.TXT
                        exportFileLauncher.launch("blockads_rules.txt")
                    }) {
                        Text(
                            stringResource(R.string.export_format_txt),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportFormatDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showInfoDialog) {
        InfoDialog(onDismiss = { showInfoDialog = false })
    }

    error?.let { errorMsg ->
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
        viewModel.clearError()
    }
}

