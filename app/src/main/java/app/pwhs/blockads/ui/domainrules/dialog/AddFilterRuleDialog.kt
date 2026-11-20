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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    "DOMAIN-WILDCARD",
    "DOMAIN-REGEX",
    "GEOSITE",
    "IP-CIDR",
    "IP-CIDR6",
    "IP-SUFFIX",
    "IP-ASN",
    "GEOIP",
    "SRC-IP-CIDR",
    "SRC-IP-SUFFIX",
    "SRC-IP-ASN",
    "SRC-GEOIP",
    "DST-PORT",
    "SRC-PORT",
    "IN-PORT",
    "NETWORK",
    "DSCP",
    "PROCESS-NAME",
    "PROCESS-NAME-REGEX",
    "PROCESS-NAME-WILDCARD",
    "PROCESS-PATH",
    "PROCESS-PATH-REGEX",
    "PROCESS-PATH-WILDCARD",
    "UID",
    "IN-TYPE",
    "IN-USER",
    "IN-NAME",
    "USER-AGENT",
    "RULE-SET",
    "AND",
    "OR",
    "NOT",
    "SUB-RULE",
    "FINAL"
)
private val FILTER_POLICIES = listOf("REJECT", "REJECT-DROP", "DIRECT", "PASS", "PASS-RULE", "PROXY")

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

    val isRegexInvalid = remember(type, param) {
        val upper = type.uppercase()
        val isRegexType = upper == "HOST-REGEX" || upper == "DOMAIN-REGEX" ||
            upper == "PROCESS-NAME-REGEX" || upper == "PROCESS-PATH-REGEX"
        if (!isRegexType || param.isBlank()) {
            false
        } else {
            runCatching { Regex(param.trim()) }.isFailure
        }
    }

    val isConfirmEnabled = remember(type, param, policy, isRegexInvalid) {
        if (type.equals("FINAL", ignoreCase = true)) {
            policy.isNotBlank()
        } else {
            type.isNotBlank() && param.isNotBlank() && policy.isNotBlank() && !isRegexInvalid
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

                val typeDescriptionRes = when (type.uppercase()) {
                    "HOST-SUFFIX", "DOMAIN-SUFFIX" -> R.string.rule_type_desc_host_suffix
                    "HOST", "DOMAIN" -> R.string.rule_type_desc_host
                    "HOST-KEYWORD", "DOMAIN-KEYWORD" -> R.string.rule_type_desc_host_keyword
                    "HOST-WILDCARD", "DOMAIN-WILDCARD", "PROCESS-NAME-WILDCARD", "PROCESS-PATH-WILDCARD" ->
                        R.string.rule_type_desc_host_wildcard
                    "HOST-REGEX", "DOMAIN-REGEX", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX" ->
                        R.string.rule_type_desc_host_regex
                    "IP-CIDR", "SRC-IP-CIDR" -> R.string.rule_type_desc_ip_cidr
                    "IP-CIDR6" -> R.string.rule_type_desc_ip_cidr6
                    "GEOIP", "SRC-GEOIP" -> R.string.rule_type_desc_geoip
                    "USER-AGENT" -> R.string.rule_type_desc_user_agent
                    "FINAL", "MATCH" -> R.string.rule_type_desc_final
                    else -> null
                }
                if (typeDescriptionRes != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(typeDescriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Field 2: Param *
                val isParamRequired = !type.equals("FINAL", ignoreCase = true)
                val paramPlaceholder = when (type.uppercase()) {
                    "HOST-SUFFIX", "DOMAIN-SUFFIX" -> stringResource(R.string.rule_param_hint_host_suffix)
                    "HOST", "DOMAIN" -> stringResource(R.string.rule_param_hint_host)
                    "HOST-KEYWORD", "DOMAIN-KEYWORD" -> stringResource(R.string.rule_param_hint_host_keyword)
                    "HOST-WILDCARD", "DOMAIN-WILDCARD" -> stringResource(R.string.rule_param_hint_host_wildcard)
                    "HOST-REGEX", "DOMAIN-REGEX", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX" ->
                        stringResource(R.string.rule_param_hint_host_regex)
                    "GEOSITE" -> "e.g. private, apple, category-ads"
                    "IP-CIDR", "SRC-IP-CIDR" -> stringResource(R.string.rule_param_hint_ip_cidr)
                    "IP-CIDR6" -> stringResource(R.string.rule_param_hint_ip_cidr6)
                    "IP-SUFFIX", "SRC-IP-SUFFIX" -> "e.g. 0.0.0.1/8"
                    "GEOIP", "SRC-GEOIP" -> stringResource(R.string.rule_param_hint_geoip)
                    "DST-PORT", "SRC-PORT", "IN-PORT" -> "e.g. 443, 853, 3478/19302-19309"
                    "NETWORK" -> "TCP or UDP"
                    "UID" -> "e.g. 10123"
                    "IN-TYPE" -> "e.g. TUN, DNS, HTTP"
                    "AND", "OR", "NOT" -> "e.g. ((NETWORK,UDP),(DST-PORT,443))"
                    "USER-AGENT" -> stringResource(R.string.rule_param_hint_user_agent)
                    "FINAL", "MATCH" -> stringResource(R.string.rule_param_hint_final)
                    else -> stringResource(R.string.rule_field_param_hint)
                }

                FormFieldLabel(label = stringResource(R.string.rule_field_param), isRequired = isParamRequired)
                UnderlineInputField(
                    value = if (type.equals("FINAL", ignoreCase = true)) "" else param,
                    onValueChange = { param = it },
                    placeholder = paramPlaceholder,
                    enabled = isParamRequired
                )
                if (isRegexInvalid) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.rule_regex_invalid),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Field 3: Policy *
                FormFieldLabel(label = stringResource(R.string.rule_field_policy), isRequired = true)
                Box(modifier = Modifier.fillMaxWidth()) {
                    val displayPolicy = when (policy.uppercase()) {
                        "DIRECT" -> "DIRECT (Whitelist)"
                        "REJECT" -> "REJECT (Blocklist)"
                        "REJECT-DROP" -> "REJECT-DROP (Silent Drop)"
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
                                "REJECT-DROP" -> "REJECT-DROP (Silent Drop)"
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
                val appLabelMap = remember(installedApps) {
                    installedApps.associate { it.packageName to it.label }
                }
                val targetAppLabel = formatPackageNamesLabel(packageName, appLabelMap)
                    ?: stringResource(R.string.rule_target_app_all)
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
            AppMultiPickerBottomSheet(
                title = stringResource(R.string.rule_pick_app_title),
                installedApps = installedApps,
                selected = parsePackageNameSet(packageName),
                allAppsLabel = stringResource(R.string.rule_target_app_all),
                onConfirm = { selected ->
                    packageName = selected.sorted().joinToString(",")
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
