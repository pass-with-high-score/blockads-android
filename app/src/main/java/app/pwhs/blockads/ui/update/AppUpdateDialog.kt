package app.pwhs.blockads.ui.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.update.UpdateChannel
import app.pwhs.blockads.update.UpdateState
import kotlin.math.roundToInt

@Composable
fun AppUpdateDialog(
    state: UpdateState,
    onStartUpdate: () -> Unit,
    onCompleteUpdate: () -> Unit,
    onDismiss: (skipVersion: Boolean) -> Unit,
) {
    when (state) {
        is UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { if (!state.info.isImmediate) onDismiss(false) },
                title = {
                    Text(text = stringResource(R.string.update_available_title, state.info.versionName))
                },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        val notes = state.info.changelog.ifBlank {
                            stringResource(R.string.update_available_default_notes)
                        }
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onStartUpdate) {
                        Text(stringResource(R.string.update_action_now))
                    }
                },
                dismissButton = {
                    if (!state.info.isImmediate) {
                        Row {
                            TextButton(onClick = { onDismiss(true) }) {
                                Text(stringResource(R.string.update_action_skip))
                            }
                            TextButton(onClick = { onDismiss(false) }) {
                                Text(stringResource(R.string.update_action_later))
                            }
                        }
                    }
                },
            )
        }

        is UpdateState.Downloading -> {
            val percent = (state.progress * 100f).roundToInt().coerceIn(0, 100)
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_downloading, percent)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {},
            )
        }

        is UpdateState.Downloaded -> {
            AlertDialog(
                onDismissRequest = { onDismiss(false) },
                title = { Text(stringResource(R.string.update_ready_install)) },
                text = { Text(stringResource(R.string.update_available_default_notes)) },
                confirmButton = {
                    TextButton(onClick = onStartUpdate) {
                        Text(stringResource(R.string.update_action_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss(false) }) {
                        Text(stringResource(R.string.update_action_later))
                    }
                },
            )
        }

        is UpdateState.PlayReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { onDismiss(false) },
                title = { Text(stringResource(R.string.update_ready_install)) },
                text = { Text(stringResource(R.string.update_available_default_notes)) },
                confirmButton = {
                    TextButton(onClick = onCompleteUpdate) {
                        Text(stringResource(R.string.update_action_restart))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss(false) }) {
                        Text(stringResource(R.string.update_action_later))
                    }
                },
            )
        }

        is UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { onDismiss(false) },
                title = { Text(stringResource(R.string.update_check_title)) },
                text = { Text(stringResource(R.string.update_error_prefix, state.message)) },
                confirmButton = {
                    TextButton(onClick = { onDismiss(false) }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        else -> Unit
    }
}

@Composable
fun UpdateChannelDialog(
    selectedChannel: UpdateChannel,
    onSelect: (UpdateChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_channel_title)) },
        text = {
            Column {
                UpdateChannel.entries.forEach { channel ->
                    val label = when (channel) {
                        UpdateChannel.STABLE -> stringResource(R.string.update_channel_stable)
                        UpdateChannel.BETA -> stringResource(R.string.update_channel_beta)
                        UpdateChannel.NIGHTLY -> stringResource(R.string.update_channel_nightly)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = channel == selectedChannel,
                            onClick = { onSelect(channel) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { onSelect(channel) }) {
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
