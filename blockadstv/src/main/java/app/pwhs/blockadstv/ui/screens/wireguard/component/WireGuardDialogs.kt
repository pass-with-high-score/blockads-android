package app.pwhs.blockadstv.ui.screens.wireguard.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.entities.WireGuardProfile
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ImportWireGuardDialog(
    errorMessage: String?,
    onImport: (input: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameText by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.wg_dialog_import_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.wg_dialog_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                TvTextInput(
                    value = nameText,
                    onValueChange = { nameText = it },
                    placeholder = stringResource(R.string.wg_profile_name_optional),
                )
                Spacer(modifier = Modifier.height(12.dp))

                TvTextInput(
                    value = configText,
                    onValueChange = { configText = it },
                    placeholder = "wireguard://... or [Interface]...",
                    onDone = {
                        if (configText.isNotBlank()) onImport(configText.trim(), nameText.trim())
                    },
                )

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
                    WgDialogButton(
                        text = stringResource(R.string.proxy_cancel),
                        isPrimary = false,
                        onClick = onDismiss,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    WgDialogButton(
                        text = stringResource(R.string.proxy_save),
                        isPrimary = true,
                        onClick = {
                            if (configText.isNotBlank()) onImport(configText.trim(), nameText.trim())
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RenameWireGuardDialog(
    profile: WireGuardProfile,
    errorMessage: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameText by remember(profile.id) { mutableStateOf(profile.name) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.wg_dialog_rename_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(16.dp))

                TvTextInput(
                    value = nameText,
                    onValueChange = { nameText = it },
                    placeholder = stringResource(R.string.wg_profile_name),
                    onDone = { onSave(nameText) },
                )

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
                    WgDialogButton(
                        text = stringResource(R.string.proxy_cancel),
                        isPrimary = false,
                        onClick = onDismiss,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    WgDialogButton(
                        text = stringResource(R.string.proxy_save),
                        isPrimary = true,
                        onClick = { onSave(nameText) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun WgDialogButton(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isFocused -> if (isPrimary) NeonGreen.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surfaceVariant
                    isPrimary -> NeonGreen
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isPrimary) Color.Black else MaterialTheme.colorScheme.onSurface,
        )
    }
}
