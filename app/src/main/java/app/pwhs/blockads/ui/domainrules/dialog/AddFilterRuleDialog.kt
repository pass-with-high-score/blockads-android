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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import app.pwhs.blockads.data.entities.ConfigProfile

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

@Composable
fun FilterRuleDialog(
    activeConfig: ConfigProfile?,
    allConfigs: List<ConfigProfile>,
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String, configId: Long?) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.filter_rule_title),
    subtitle: String = stringResource(R.string.filter_rule_subtitle),
    initialType: String = "HOST-SUFFIX",
    initialParam: String = "",
    initialPolicy: String = "REJECT",
    initialConfigId: Long? = null
) {
    var type by remember(initialType) { mutableStateOf(initialType) }
    var param by remember(initialParam) { mutableStateOf(initialParam) }
    var policy by remember(initialPolicy) { mutableStateOf(initialPolicy) }
    var selectedConfigId by remember(initialConfigId, activeConfig) {
        mutableStateOf(initialConfigId ?: activeConfig?.id)
    }

    var showTypeDropdown by remember { mutableStateOf(false) }
    var showPolicyDropdown by remember { mutableStateOf(false) }
    var showConfigDropdown by remember { mutableStateOf(false) }

    val currentConfigName = remember(selectedConfigId, activeConfig, allConfigs) {
        allConfigs.find { it.id == selectedConfigId }?.name
            ?: activeConfig?.name
            ?: "Current Configuration Profile"
    }

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
                            onSave(type, param, policy, selectedConfigId)
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
                FormFieldLabel(label = stringResource(R.string.rule_field_type), isRequired = true)
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
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Field 2: Param *
                val isParamRequired = !type.equals("FINAL", ignoreCase = true)
                FormFieldLabel(label = stringResource(R.string.rule_field_param), isRequired = isParamRequired)
                UnderlineInputField(
                    value = param,
                    onValueChange = { param = it },
                    placeholder = stringResource(R.string.rule_field_param_hint)
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

                // Field 4: Save To
                FormFieldLabel(label = stringResource(R.string.rule_field_save_to), isRequired = false)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showConfigDropdown = true }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = currentConfigName,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )

                    if (allConfigs.isNotEmpty()) {
                        DropdownMenu(
                            expanded = showConfigDropdown,
                            onDismissRequest = { showConfigDropdown = false }
                        ) {
                            allConfigs.forEach { cfg ->
                                val isCurrent = cfg.id == selectedConfigId
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = cfg.name + if (cfg.isActive) " (Active)" else "",
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        selectedConfigId = cfg.id
                                        showConfigDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun CircularActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun UnderlineInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClick: (() -> Unit)? = null
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Normal
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
                innerTextField()
            }
        }
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

@Composable
private fun FormFieldLabel(
    label: String,
    isRequired: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (isRequired) {
            Text(
                text = " *",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Red
            )
        }
    }
}

@Composable
fun AddFilterRuleDialog(
    initialPolicy: String = "REJECT",
    activeConfig: ConfigProfile?,
    allConfigs: List<ConfigProfile>,
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String, configId: Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterRuleDialog(
        initialPolicy = initialPolicy,
        activeConfig = activeConfig,
        allConfigs = allConfigs,
        onDismiss = onDismiss,
        onSave = onSave,
        modifier = modifier
    )
}

@Composable
fun EditFilterRuleDialog(
    initialDomain: String,
    initialPolicy: String,
    activeConfig: ConfigProfile?,
    allConfigs: List<ConfigProfile>,
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String, configId: Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterRuleDialog(
        initialParam = initialDomain,
        initialPolicy = initialPolicy,
        initialConfigId = activeConfig?.id,
        activeConfig = activeConfig,
        allConfigs = allConfigs,
        onDismiss = onDismiss,
        onSave = onSave,
        modifier = modifier
    )
}
