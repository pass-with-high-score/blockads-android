package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences

@Composable
fun DnsResponseTypeDialog(
    dnsResponseType: String,
    onUpdateResponseType: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dns_response_type)) },
        text = {
            Column {
                listOf(
                    Triple(
                        AppPreferences.DNS_RESPONSE_NXDOMAIN,
                        R.string.dns_response_nxdomain,
                        R.string.dns_response_nxdomain_desc
                    ),
                    Triple(
                        AppPreferences.DNS_RESPONSE_REFUSED,
                        R.string.dns_response_refused,
                        R.string.dns_response_refused_desc
                    ),
                    Triple(
                        AppPreferences.DNS_RESPONSE_CUSTOM_IP,
                        R.string.dns_response_custom_ip,
                        R.string.dns_response_custom_ip_desc
                    )
                ).forEach { (type, labelRes, descRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdateResponseType(type)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(labelRes),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (dnsResponseType == type) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}
