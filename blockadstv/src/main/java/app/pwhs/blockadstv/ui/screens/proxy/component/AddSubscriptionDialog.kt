package app.pwhs.blockadstv.ui.screens.proxy.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.AccentBlue
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.TextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddSubscriptionDialog(
    isLoading: Boolean,
    errorMessage: String?,
    onSave: (url: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var urlText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.proxy_dialog_import_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.proxy_dialog_sub_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(14.dp))

                TvTextInput(
                    value = nameText,
                    onValueChange = { nameText = it },
                    placeholder = "Subscription name (Optional)",
                )

                Spacer(modifier = Modifier.height(10.dp))

                TvTextInput(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = "https://example.com/sub",
                    onDone = {
                        if (urlText.isNotBlank() && !isLoading) onSave(urlText.trim(), nameText.trim())
                    },
                )

                if (isLoading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Downloading & parsing nodes...",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentBlue,
                    )
                }

                if (!errorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = DangerRed,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogButton(
                        text = stringResource(R.string.proxy_cancel),
                        isPrimary = false,
                        onClick = onDismiss,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    DialogButton(
                        text = if (isLoading) "Importing..." else stringResource(R.string.proxy_save),
                        isPrimary = true,
                        onClick = {
                            if (urlText.isNotBlank() && !isLoading) onSave(urlText.trim(), nameText.trim())
                        },
                    )
                }
            }
        }
    }
}
