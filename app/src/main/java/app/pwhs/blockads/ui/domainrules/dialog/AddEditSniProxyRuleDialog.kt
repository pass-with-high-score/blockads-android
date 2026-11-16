package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.model.SniProxyRule
import app.pwhs.blockads.data.model.SniProxyRuleCodec
import app.pwhs.blockads.ui.domainrules.InstalledAppOption
import java.net.InetAddress

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddEditSniProxyRuleDialog(
    initialRule: SniProxyRule? = null,
    presetYouTube: Boolean = false,
    installedApps: List<InstalledAppOption>,
    onDismiss: () -> Unit,
    onSave: (SniProxyRule) -> Unit
) {
    val hasYouTubeInstalled = remember(installedApps) {
        installedApps.any { it.packageName == SniProxyRuleCodec.YOUTUBE_PACKAGE }
    }
    val hasRedditInstalled = remember(installedApps) {
        installedApps.any { it.packageName == SniProxyRuleCodec.REDDIT_PACKAGE }
    }
    var name by remember {
        mutableStateOf(
            initialRule?.name ?: if (presetYouTube) "YouTube SNI Proxy" else ""
        )
    }
    var targetInput by remember {
        mutableStateOf(
            initialRule?.let { if (it.targetPort == 443) it.targetIp else it.targetAddress } ?: ""
        )
    }
    var selectedPackages by remember {
        mutableStateOf(
            initialRule?.packageNames?.toSet()
                ?: if (presetYouTube && hasYouTubeInstalled) setOf(SniProxyRuleCodec.YOUTUBE_PACKAGE) else emptySet()
        )
    }
    var domainsInput by remember {
        mutableStateOf(
            initialRule?.domains?.joinToString("\n")
                ?: if (presetYouTube) SniProxyRuleCodec.YOUTUBE_PRESET_DOMAINS.joinToString("\n") else ""
        )
    }
    var showAppPicker by remember { mutableStateOf(false) }
    var ipError by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.AltRoute,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(
                            if (initialRule == null) R.string.sni_proxy_add_title else R.string.sni_proxy_edit_title
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.sni_proxy_empty_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = targetInput,
                onValueChange = {
                    targetInput = it
                    ipError = false
                },
                label = { Text(stringResource(R.string.sni_proxy_target_ip_label)) },
                placeholder = { Text(stringResource(R.string.sni_proxy_target_ip_hint)) },
                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                isError = ipError,
                supportingText = if (ipError) {
                    { Text(stringResource(R.string.sni_proxy_invalid_ip)) }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.sni_proxy_name_label)) },
                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.sni_proxy_apps_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val appPickerLabel = if (selectedPackages.isEmpty()) {
                            stringResource(R.string.sni_proxy_apps_all)
                        } else {
                            stringResource(R.string.sni_proxy_apps_pick, selectedPackages.size)
                        }
                        FilterChip(
                            selected = selectedPackages.isEmpty(),
                            onClick = { showAppPicker = true },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text(appPickerLabel, maxLines = 1, softWrap = false) }
                        )
                        if (hasYouTubeInstalled) {
                            val ytSelected = SniProxyRuleCodec.YOUTUBE_PACKAGE in selectedPackages
                            FilterChip(
                                selected = ytSelected,
                                onClick = {
                                    selectedPackages = if (ytSelected) {
                                        selectedPackages - SniProxyRuleCodec.YOUTUBE_PACKAGE
                                    } else {
                                        selectedPackages + SniProxyRuleCodec.YOUTUBE_PACKAGE
                                    }
                                },
                                leadingIcon = {
                                    if (ytSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_brand_youtube),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                label = { Text("YouTube", maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.sni_proxy_domains_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    SniProxyPresetButtons(
                        onSelectYouTube = {
                            domainsInput = SniProxyRuleCodec.YOUTUBE_PRESET_DOMAINS.joinToString("\n")
                            if (name.isBlank()) name = "YouTube SNI Proxy"
                            if (selectedPackages.isEmpty() && hasYouTubeInstalled) {
                                selectedPackages = setOf(SniProxyRuleCodec.YOUTUBE_PACKAGE)
                            }
                        },
                        onSelectReddit = {
                            domainsInput = SniProxyRuleCodec.REDDIT_PRESET_DOMAINS.joinToString("\n")
                            if (name.isBlank()) name = "Reddit SNI Proxy"
                            if (selectedPackages.isEmpty() && hasRedditInstalled) {
                                selectedPackages = setOf(SniProxyRuleCodec.REDDIT_PACKAGE)
                            }
                        },
                    )
                    OutlinedTextField(
                        value = domainsInput,
                        onValueChange = { domainsInput = it },
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = {
                        val parsed = parseIpAndPort(targetInput)
                        if (parsed == null) {
                            ipError = true
                            return@Button
                        }
                        val domains = domainsInput
                            .split(',', '\n', ' ')
                            .map { it.trim().lowercase().removePrefix("*.").removePrefix(".") }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        val rule = (initialRule ?: SniProxyRule(targetIp = parsed.first)).copy(
                            name = name.trim(),
                            targetIp = parsed.first,
                            targetPort = parsed.second,
                            packageNames = selectedPackages.toList(),
                            domains = domains
                        )
                        onSave(rule)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }

    if (showAppPicker) {
        AppMultiPickerBottomSheet(
            installedApps = installedApps,
            selected = selectedPackages,
            onDismiss = { showAppPicker = false },
            onConfirm = {
                selectedPackages = it
                showAppPicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppMultiPickerBottomSheet(
    installedApps: List<InstalledAppOption>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(selected) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val filtered = remember(installedApps, query) {
        if (query.isBlank()) installedApps
        else installedApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.sni_proxy_apps_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in draft
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                draft = if (checked) draft - app.packageName else draft + app.packageName
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                draft = if (checked) draft - app.packageName else draft + app.packageName
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = { onConfirm(draft) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
}

private fun parseIpAndPort(raw: String): Pair<String, Int>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val (host, port) = if (trimmed.startsWith("[")) {
        val close = trimmed.indexOf(']')
        if (close == -1) return null
        val h = trimmed.substring(1, close)
        val rest = trimmed.substring(close + 1)
        val p = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else 443
        h to p
    } else if (trimmed.count { it == ':' } == 1) {
        val parts = trimmed.split(':')
        val p = parts[1].toIntOrNull() ?: return null
        parts[0] to p
    } else {
        trimmed to 443
    }
    if (port !in 1..65535) return null
    val isValidIp = runCatching { InetAddress.getByName(host).hostAddress != null }.getOrDefault(false) &&
        (host.contains('.') || host.contains(':'))
    return if (isValidIp) host to port else null
}
