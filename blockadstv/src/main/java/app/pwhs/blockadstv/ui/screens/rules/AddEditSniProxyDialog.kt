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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.data.model.SniProxyRule
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import java.util.UUID

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddEditSniProxyDialog(
    initialRule: SniProxyRule?,
    onDismiss: () -> Unit,
    onSave: (SniProxyRule) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialRule?.name.orEmpty()) }
    var targetIp by remember { mutableStateOf(initialRule?.targetIp.orEmpty()) }
    var targetPort by remember { mutableStateOf((initialRule?.targetPort ?: 443).toString()) }
    var domainsText by remember { mutableStateOf(initialRule?.domains?.joinToString(", ").orEmpty()) }
    var packagesText by remember { mutableStateOf(initialRule?.packageNames?.joinToString(", ").orEmpty()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (initialRule == null) "Add SNI Proxy Rule" else "Edit SNI Proxy Rule",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Route matching TLS/HTTPS traffic directly to a custom SNI Proxy IP",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Rule Name (optional)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            TvTextInput(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. YouTube TV SNI Proxy",
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(2f)) {
                    Text("Target IP", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    TvTextInput(
                        value = targetIp,
                        onValueChange = {
                            targetIp = it.trim()
                            errorMsg = null
                        },
                        placeholder = "e.g. 203.0.113.10",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Port", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    TvTextInput(
                        value = targetPort,
                        onValueChange = {
                            targetPort = it.filter { ch -> ch.isDigit() }
                            errorMsg = null
                        },
                        placeholder = "443",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Domains (comma-separated, empty = all)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TvTextInput(
                value = domainsText,
                onValueChange = { domainsText = it },
                placeholder = "youtube.com, googlevideo.com",
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "App Packages (comma-separated, empty = all apps)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TvTextInput(
                value = packagesText,
                onValueChange = { packagesText = it },
                placeholder = "com.google.android.youtube.tv",
                modifier = Modifier.fillMaxWidth(),
            )

            if (errorMsg != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMsg.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = DangerRed,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (initialRule != null && onDelete != null) {
                    DialogButton(
                        label = "Delete",
                        color = DangerRed,
                        onClick = { onDelete(initialRule.id) },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                DialogButton(
                    label = "Cancel",
                    color = TextSecondary,
                    onClick = onDismiss,
                )
                Spacer(modifier = Modifier.width(8.dp))
                DialogButton(
                    label = "Save",
                    color = NeonGreen,
                    onClick = {
                        val ip = targetIp.trim()
                        val port = targetPort.toIntOrNull() ?: 443
                        if (ip.isEmpty()) {
                            errorMsg = "Target IP cannot be empty"
                            return@DialogButton
                        }
                        if (port !in 1..65535) {
                            errorMsg = "Port must be between 1 and 65535"
                            return@DialogButton
                        }
                        val domains = domainsText.split(",")
                            .map { it.trim().lowercase() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        val packages = packagesText.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        onSave(
                            SniProxyRule(
                                id = initialRule?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                targetIp = ip,
                                targetPort = port,
                                packageNames = packages,
                                domains = domains,
                                enabled = initialRule?.enabled ?: true,
                            )
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (isFocused) color else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) color else Color.Transparent,
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
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
