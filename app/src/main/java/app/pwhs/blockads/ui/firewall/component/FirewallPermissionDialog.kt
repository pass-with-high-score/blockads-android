package app.pwhs.blockads.ui.firewall.component

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.BlockadsTheme
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun FirewallPermissionDialog(
    appLabel: String,
    packageName: String,
    appIcon: Drawable?,
    pendingCount: Int,
    onAlwaysAllow: () -> Unit,
    onAllowOnce: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { PromptAppIcon(appIcon, appLabel) },
        title = {
            Text(
                text = stringResource(R.string.firewall_prompt_dialog_title, appLabel),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.firewall_prompt_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (pendingCount > 1) {
                    Text(
                        text = stringResource(R.string.firewall_prompt_dialog_pending, pendingCount - 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            PromptActions(onAlwaysAllow = onAlwaysAllow, onAllowOnce = onAllowOnce, onBlock = onBlock)
        }
    )
}

@Composable
private fun PromptAppIcon(appIcon: Drawable?, appLabel: String) {
    if (appIcon != null) {
        Image(
            painter = rememberDrawablePainter(drawable = appIcon),
            contentDescription = appLabel,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        )
    } else {
        Icon(imageVector = Icons.Default.Android, contentDescription = appLabel)
    }
}

@Composable
private fun PromptActions(onAlwaysAllow: () -> Unit, onAllowOnce: () -> Unit, onBlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(onClick = onAlwaysAllow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.firewall_prompt_always_allow))
        }
        OutlinedButton(onClick = onAllowOnce, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.firewall_prompt_allow_once))
        }
        TextButton(
            onClick = onBlock,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text(stringResource(R.string.firewall_prompt_action_block))
        }
    }
}

@Composable
private fun DialogPreviewContent() {
    FirewallPermissionDialog(
        appLabel = "Example App",
        packageName = "com.example.app",
        appIcon = null,
        pendingCount = 3,
        onAlwaysAllow = {},
        onAllowOnce = {},
        onBlock = {},
        onDismiss = {}
    )
}

@Preview(name = "Light")
@Composable
private fun FirewallPermissionDialogLightPreview() {
    BlockadsTheme(themeMode = "light") { DialogPreviewContent() }
}

@Preview(name = "Dark")
@Composable
private fun FirewallPermissionDialogDarkPreview() {
    BlockadsTheme(themeMode = "dark") { DialogPreviewContent() }
}
