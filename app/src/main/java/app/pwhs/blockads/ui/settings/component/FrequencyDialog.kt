package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
fun FrequencyDialog(
    autoUpdateFrequency: String,
    onUpdateFrequencyChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_update_frequency)) },
        text = {
            Column {
                listOf(
                    AppPreferences.UPDATE_FREQUENCY_6H to R.string.settings_auto_update_frequency_6h,
                    AppPreferences.UPDATE_FREQUENCY_12H to R.string.settings_auto_update_frequency_12h,
                    AppPreferences.UPDATE_FREQUENCY_24H to R.string.settings_auto_update_frequency_24h,
                    AppPreferences.UPDATE_FREQUENCY_48H to R.string.settings_auto_update_frequency_48h,
                    AppPreferences.UPDATE_FREQUENCY_MANUAL to R.string.settings_auto_update_frequency_manual
                ).forEach { (freq, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdateFrequencyChange(freq)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(labelRes),
                            modifier = Modifier.weight(1f)
                        )
                        if (autoUpdateFrequency == freq) {
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