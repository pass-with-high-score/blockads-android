package app.pwhs.blockads.ui.dnsprovider.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.DnsProtocol
import app.pwhs.blockads.ui.theme.TextSecondary

@Composable
fun CustomDnsDialog(
    upstreamDns: String,
    fallbackDns: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    // Build display value from current settings
    var editDns by remember { mutableStateOf(upstreamDns) }
    var editFallback by remember { mutableStateOf(fallbackDns) }

    // Detect protocol from input
    val detectedProtocol by remember {
        derivedStateOf {
            val input = editDns.trim()
            when {
                input.startsWith("https://", ignoreCase = true) -> DnsProtocol.DOH
                input.startsWith("tls://", ignoreCase = true) -> DnsProtocol.DOT
                input.isNotBlank() -> DnsProtocol.PLAIN
                else -> null
            }
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dns_custom_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_custom_dns_server),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_custom_dns_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editDns,
                    onValueChange = { editDns = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.settings_custom_dns_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                // Protocol detection badge
                if (detectedProtocol != null && editDns.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (icon, label, color) = when (detectedProtocol) {
                            DnsProtocol.DOH -> Triple(
                                Icons.Default.Security,
                                stringResource(R.string.settings_detected_doh),
                                MaterialTheme.colorScheme.primary
                            )
                            DnsProtocol.DOT -> Triple(
                                Icons.Default.Security,
                                stringResource(R.string.settings_detected_dot),
                                MaterialTheme.colorScheme.tertiary
                            )
                            else -> Triple(
                                Icons.Default.Wifi,
                                stringResource(R.string.settings_detected_plain),
                                TextSecondary
                            )
                        }
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                }

                // Show fallback DNS only for Plain DNS
                if (detectedProtocol == DnsProtocol.PLAIN || detectedProtocol == null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.settings_fallback_dns),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editFallback,
                        onValueChange = { editFallback = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("1.1.1.1") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(editDns, editFallback) }) {
                Text(stringResource(R.string.dns_custom_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dns_custom_cancel))
            }
        }
    )
}
