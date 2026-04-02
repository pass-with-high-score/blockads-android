package app.pwhs.blockads.ui.dialog

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.pwhs.blockads.R

@Composable
fun VPNConflictDialog(
    modifier: Modifier = Modifier,
    onDismissVpnConflictDialog: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
        onDismissRequest = onDismissVpnConflictDialog,
        title = {
            Text(text = stringResource(R.string.vpn_conflict_title))
        },
        text = {
            Text(text = stringResource(R.string.vpn_conflict_desc))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissVpnConflictDialog()
                    val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(fallbackIntent)
                    }
                }
            ) {
                Text(stringResource(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissVpnConflictDialog) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}