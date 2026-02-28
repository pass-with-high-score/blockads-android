package app.pwhs.blockads.ui.home.component

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.home.data.AvailableUpdate
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.TextSecondary

@Composable
fun UpdateDialog(
    update: AvailableUpdate,
    onDismissUpdate: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismissUpdate,
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_available_title, update.version),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            if (update.changelog.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = update.changelog,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissUpdate) {
                Text(
                    text = stringResource(R.string.update_dismiss),
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, update.url.toUri())
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.update_now))
            }
        }
    )
}