package app.pwhs.blockads.ui.dnsprovider.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun CustomDnsDialog(
    upstreamDns: String,
    fallbackDns: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var editUpstream by remember { mutableStateOf(upstreamDns) }
    var editFallback by remember { mutableStateOf(fallbackDns) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dns_custom_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_upstream_dns),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editUpstream,
                    onValueChange = { editUpstream = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("8.8.8.8") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
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
        },
        confirmButton = {
            TextButton(onClick = { onSave(editUpstream, editFallback) }) {
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
