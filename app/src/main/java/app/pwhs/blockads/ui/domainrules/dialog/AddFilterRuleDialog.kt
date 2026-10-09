package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ConfigProfile
import app.pwhs.blockads.ui.domainrules.RuleCategory

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
private val POLICY_TYPES = listOf("static", "available", "round-robin", "dest-hash")
private val REWRITE_TYPES = listOf("url reject", "url 302", "url 307", "header")

@Composable
fun AddFilterRuleDialog(
    activeConfig: ConfigProfile?,
    allConfigs: List<ConfigProfile>,
    onDismiss: () -> Unit,
    onSave: (category: RuleCategory, type: String, param: String, policy: String, configId: Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(RuleCategory.FILTER) }

    var type by remember { mutableStateOf("HOST-SUFFIX") }
    var param by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf("REJECT") }
    var selectedConfigId by remember { mutableStateOf(activeConfig?.id) }

    var showTypeDropdown by remember { mutableStateOf(false) }
    var showPolicyDropdown by remember { mutableStateOf(false) }
    var showConfigDropdown by remember { mutableStateOf(false) }

    val currentConfigName = remember(selectedConfigId, activeConfig, allConfigs) {
        allConfigs.find { it.id == selectedConfigId }?.name
            ?: activeConfig?.name
            ?: "Current Configuration Profile"
    }

    val isConfirmEnabled = remember(selectedCategory, type, param, policy) {
        if (selectedCategory == RuleCategory.FILTER && type.equals("FINAL", ignoreCase = true)) {
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
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                                onSave(selectedCategory, type, param, policy, selectedConfigId)
                                onDismiss()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Title Header
                    AnimatedContent(targetState = selectedCategory, label = "HeaderAnim") { cat ->
                        Column {
                            val title = when (cat) {
                                RuleCategory.FILTER -> stringResource(R.string.filter_rule_title)
                                RuleCategory.POLICY -> stringResource(R.string.policy_rule_title)
                                RuleCategory.REWRITE -> stringResource(R.string.rewrite_rule_title)
                            }
                            val subtitle = when (cat) {
                                RuleCategory.FILTER -> stringResource(R.string.filter_rule_subtitle)
                                RuleCategory.POLICY -> stringResource(R.string.policy_rule_subtitle)
                                RuleCategory.REWRITE -> stringResource(R.string.rewrite_rule_subtitle)
                            }
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
                        }
                    }

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

                        val currentTypeOptions = when (selectedCategory) {
                            RuleCategory.FILTER -> FILTER_TYPES
                            RuleCategory.POLICY -> POLICY_TYPES
                            RuleCategory.REWRITE -> REWRITE_TYPES
                        }
                        DropdownMenu(
                            expanded = showTypeDropdown,
                            onDismissRequest = { showTypeDropdown = false }
                        ) {
                            currentTypeOptions.forEach { opt ->
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
                    val paramHint = when (selectedCategory) {
                        RuleCategory.FILTER -> stringResource(R.string.rule_field_param_hint)
                        RuleCategory.POLICY -> "The param for this type of policy."
                        RuleCategory.REWRITE -> "The pattern or URL for rewrite."
                    }
                    val isParamRequired = !(selectedCategory == RuleCategory.FILTER && type.equals("FINAL", ignoreCase = true))
                    FormFieldLabel(label = stringResource(R.string.rule_field_param), isRequired = isParamRequired)
                    UnderlineInputField(
                        value = param,
                        onValueChange = { param = it },
                        placeholder = paramHint
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Field 3: Policy *
                    FormFieldLabel(label = stringResource(R.string.rule_field_policy), isRequired = true)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        UnderlineInputField(
                            value = policy,
                            onValueChange = { policy = it },
                            placeholder = stringResource(R.string.rule_field_policy_hint),
                            onClick = { showPolicyDropdown = true }
                        )

                        DropdownMenu(
                            expanded = showPolicyDropdown,
                            onDismissRequest = { showPolicyDropdown = false }
                        ) {
                            FILTER_POLICIES.forEach { pol ->
                                DropdownMenuItem(
                                    text = { Text(pol) },
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

                    Spacer(modifier = Modifier.height(110.dp))
                }

                // Bottom Floating Pill Navigation Bar
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillTabItem(
                            icon = Icons.Default.Tune,
                            isSelected = selectedCategory == RuleCategory.POLICY,
                            onClick = {
                                selectedCategory = RuleCategory.POLICY
                                type = "static"
                                policy = "direct"
                            }
                        )
                        PillTabItem(
                            icon = Icons.Default.FilterAlt,
                            isSelected = selectedCategory == RuleCategory.FILTER,
                            onClick = {
                                selectedCategory = RuleCategory.FILTER
                                type = "HOST-SUFFIX"
                                policy = "REJECT"
                            }
                        )
                        PillTabItem(
                            icon = Icons.Default.Edit,
                            isSelected = selectedCategory == RuleCategory.REWRITE,
                            onClick = {
                                selectedCategory = RuleCategory.REWRITE
                                type = "url reject"
                                policy = "reject"
                            }
                        )
                    }
                }
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
private fun PillTabItem(
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.onSurface
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}
