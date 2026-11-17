package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.pwhs.blockads.ui.domainrules.InstalledAppOption
import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.pwhs.blockads.R

private val FILTER_TYPES = listOf(
    "HOST-SUFFIX",
    "HOST",
    "HOST-KEYWORD",
    "IP-CIDR",
    "IP-CIDR6",
    "GEOIP",
    "USER-AGENT",
    "FINAL"
)
private val FILTER_POLICIES = listOf("REJECT", "DIRECT", "PROXY")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRuleDialog(
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String, packageName: String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.filter_rule_title),
    subtitle: String = stringResource(R.string.filter_rule_subtitle),
    initialType: String = "HOST-SUFFIX",
    initialParam: String = "",
    initialPolicy: String = "REJECT",
    initialPackageName: String = "",
    installedApps: List<InstalledAppOption> = emptyList(),
) {
    var type by remember(initialType) { mutableStateOf(initialType) }
    var param by remember(initialParam) { mutableStateOf(initialParam) }
    var policy by remember(initialPolicy) { mutableStateOf(initialPolicy) }
    var packageName by remember(initialPackageName) { mutableStateOf(initialPackageName) }

    var showTypeDropdown by remember { mutableStateOf(false) }
    var showPolicyDropdown by remember { mutableStateOf(false) }
    var showAppPickerDialog by remember { mutableStateOf(false) }
    var showDocSheet by remember { mutableStateOf(false) }

    val isConfirmEnabled = remember(type, param, policy) {
        if (type.equals("FINAL", ignoreCase = true)) {
            policy.isNotBlank()
        } else {
            type.isNotBlank() && param.isNotBlank() && policy.isNotBlank()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

        DisposableEffect(view, isLight) {
            var parent = view.parent
            var dialogWindow: android.view.Window? = null
            while (parent != null) {
                if (parent is DialogWindowProvider) {
                    dialogWindow = parent.window
                    break
                }
                parent = parent.parent
            }
            if (dialogWindow == null) {
                dialogWindow = (view.context as? Activity)?.window
            }
            dialogWindow?.let { win ->
                win.statusBarColor = AndroidColor.TRANSPARENT
                win.navigationBarColor = AndroidColor.TRANSPARENT
                val insetsController = WindowCompat.getInsetsController(win, win.decorView)
                insetsController.isAppearanceLightStatusBars = isLight
                insetsController.isAppearanceLightNavigationBars = isLight
            }
            onDispose {}
        }

        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Top Bar: Circular Close & Confirm buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularActionButton(
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_cancel),
                        onClick = onDismiss
                    )
                    CircularActionButton(
                        icon = Icons.Default.Check,
                        contentDescription = stringResource(R.string.wireguard_action_save),
                        enabled = isConfirmEnabled,
                        onClick = {
                            onSave(type, param, policy, packageName)
                            onDismiss()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Title Header
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Field 1: Type *
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormFieldLabel(label = stringResource(R.string.rule_field_type), isRequired = true)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showDocSheet = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.rule_docs_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.rule_docs_action_tooltip),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    UnderlineInputField(
                        value = type,
                        onValueChange = { type = it },
                        placeholder = stringResource(R.string.rule_field_type),
                        onClick = { showTypeDropdown = true }
                    )

                    DropdownMenu(
                        expanded = showTypeDropdown,
                        onDismissRequest = { showTypeDropdown = false }
                    ) {
                        FILTER_TYPES.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = {
                                    type = opt
                                    showTypeDropdown = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            text = {
                                Text(
                                    text = stringResource(R.string.rule_docs_title),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            onClick = {
                                showTypeDropdown = false
                                showDocSheet = true
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Field 2: Param *
                val isParamRequired = !type.equals("FINAL", ignoreCase = true)
                val paramPlaceholder = when (type.uppercase()) {
                    "HOST-SUFFIX" -> stringResource(R.string.rule_param_hint_host_suffix)
                    "HOST" -> stringResource(R.string.rule_param_hint_host)
                    "HOST-KEYWORD" -> stringResource(R.string.rule_param_hint_host_keyword)
                    "IP-CIDR" -> stringResource(R.string.rule_param_hint_ip_cidr)
                    "IP-CIDR6" -> stringResource(R.string.rule_param_hint_ip_cidr6)
                    "GEOIP" -> stringResource(R.string.rule_param_hint_geoip)
                    "USER-AGENT" -> stringResource(R.string.rule_param_hint_user_agent)
                    "FINAL" -> stringResource(R.string.rule_param_hint_final)
                    else -> stringResource(R.string.rule_field_param_hint)
                }

                FormFieldLabel(label = stringResource(R.string.rule_field_param), isRequired = isParamRequired)
                UnderlineInputField(
                    value = if (type.equals("FINAL", ignoreCase = true)) "" else param,
                    onValueChange = { param = it },
                    placeholder = paramPlaceholder,
                    enabled = isParamRequired
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Field 3: Policy *
                FormFieldLabel(label = stringResource(R.string.rule_field_policy), isRequired = true)
                Box(modifier = Modifier.fillMaxWidth()) {
                    val displayPolicy = when (policy.uppercase()) {
                        "DIRECT" -> "DIRECT (Whitelist)"
                        "REJECT" -> "REJECT (Blocklist)"
                        else -> policy
                    }
                    UnderlineInputField(
                        value = displayPolicy,
                        onValueChange = { policy = it },
                        placeholder = stringResource(R.string.rule_field_policy_hint),
                        onClick = { showPolicyDropdown = true }
                    )

                    DropdownMenu(
                        expanded = showPolicyDropdown,
                        onDismissRequest = { showPolicyDropdown = false }
                    ) {
                        FILTER_POLICIES.forEach { pol ->
                            val label = when (pol) {
                                "DIRECT" -> "DIRECT (Whitelist)"
                                "REJECT" -> "REJECT (Blocklist)"
                                else -> pol
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    policy = pol
                                    showPolicyDropdown = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Field 4: Target App (Optional — empty = Global)
                val targetAppLabel = if (packageName.isEmpty()) {
                    stringResource(R.string.rule_target_app_all)
                } else {
                    installedApps.firstOrNull { it.packageName == packageName }?.label
                        ?: packageName
                }
                FormFieldLabel(label = stringResource(R.string.rule_field_target_app), isRequired = false)
                UnderlineInputField(
                    value = targetAppLabel,
                    onValueChange = {},
                    placeholder = stringResource(R.string.rule_target_app_all),
                    onClick = { showAppPickerDialog = true }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (showAppPickerDialog) {
            TargetAppPickerDialog(
                installedApps = installedApps,
                selectedPackage = packageName,
                onSelect = {
                    packageName = it
                    showAppPickerDialog = false
                },
                onDismiss = { showAppPickerDialog = false }
            )
        }

        if (showDocSheet) {
            RuleTypesDocSheet(onDismiss = { showDocSheet = false })
        }
    }
}

@Composable
private fun TargetAppPickerDialog(
    installedApps: List<InstalledAppOption>,
    selectedPackage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(installedApps, query) {
        if (query.isBlank()) installedApps
        else installedApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rule_pick_app_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.rule_pick_app_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    item(key = "__all__") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect("") }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedPackage.isEmpty(),
                                onClick = { onSelect("") }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.rule_target_app_all),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app.packageName) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedPackage == app.packageName,
                                onClick = { onSelect(app.packageName) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
fun AddFilterRuleDialog(
    initialPolicy: String = "REJECT",
    installedApps: List<InstalledAppOption> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String, packageName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterRuleDialog(
        initialPolicy = initialPolicy,
        installedApps = installedApps,
        onDismiss = onDismiss,
        onSave = onSave,
        modifier = modifier
    )
}
