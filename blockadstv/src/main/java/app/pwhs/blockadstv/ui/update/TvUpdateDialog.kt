package app.pwhs.blockadstv.ui.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import app.pwhs.blockadstv.update.UpdateChannel
import app.pwhs.blockadstv.update.UpdateState
import kotlin.math.roundToInt

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvUpdateDialog(
    state: UpdateState,
    onStartUpdate: () -> Unit,
    onCompleteUpdate: () -> Unit,
    onDismiss: (skipVersion: Boolean) -> Unit,
) {
    when (state) {
        is UpdateState.Available -> {
            Dialog(onDismissRequest = { if (!state.info.isImmediate) onDismiss(false) }) {
                TvDialogCard(title = stringResource(R.string.update_available_title, state.info.versionName)) {
                    val notes = state.info.changelog.ifBlank {
                        stringResource(R.string.update_available_default_notes)
                    }
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        if (!state.info.isImmediate) {
                            OutlinedButton(onClick = { onDismiss(true) }) {
                                Text(stringResource(R.string.update_action_skip))
                            }
                            OutlinedButton(onClick = { onDismiss(false) }) {
                                Text(stringResource(R.string.update_action_later))
                            }
                        }
                        Button(onClick = onStartUpdate) {
                            Text(stringResource(R.string.update_action_now))
                        }
                    }
                }
            }
        }

        is UpdateState.Downloading -> {
            val percent = (state.progress * 100f).roundToInt().coerceIn(0, 100)
            Dialog(onDismissRequest = {}) {
                TvDialogCard(title = stringResource(R.string.update_downloading, percent)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                                .height(8.dp)
                                .background(NeonGreen),
                        )
                    }
                }
            }
        }

        is UpdateState.Downloaded -> {
            Dialog(onDismissRequest = { onDismiss(false) }) {
                TvDialogCard(title = stringResource(R.string.update_ready_install)) {
                    Text(
                        text = stringResource(R.string.update_available_default_notes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = { onDismiss(false) }) {
                            Text(stringResource(R.string.update_action_later))
                        }
                        Button(onClick = onStartUpdate) {
                            Text(stringResource(R.string.update_action_install))
                        }
                    }
                }
            }
        }

        is UpdateState.PlayReadyToInstall -> {
            Dialog(onDismissRequest = { onDismiss(false) }) {
                TvDialogCard(title = stringResource(R.string.update_ready_install)) {
                    Text(
                        text = stringResource(R.string.update_available_default_notes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = { onDismiss(false) }) {
                            Text(stringResource(R.string.update_action_later))
                        }
                        Button(onClick = onCompleteUpdate) {
                            Text(stringResource(R.string.update_action_restart))
                        }
                    }
                }
            }
        }

        is UpdateState.Error -> {
            Dialog(onDismissRequest = { onDismiss(false) }) {
                TvDialogCard(title = stringResource(R.string.update_check_title)) {
                    Text(
                        text = stringResource(R.string.update_error_prefix, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = { onDismiss(false) }) {
                            Text(stringResource(R.string.proxy_cancel))
                        }
                    }
                }
            }
        }

        else -> Unit
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvUpdateChannelDialog(
    selectedChannel: UpdateChannel,
    onSelect: (UpdateChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        TvDialogCard(title = stringResource(R.string.update_channel_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateChannel.entries.forEach { channel ->
                    val label = when (channel) {
                        UpdateChannel.STABLE -> stringResource(R.string.update_channel_stable)
                        UpdateChannel.BETA -> stringResource(R.string.update_channel_beta)
                        UpdateChannel.NIGHTLY -> stringResource(R.string.update_channel_nightly)
                    }
                    var isFocused by remember { mutableStateOf(false) }
                    val isSelected = channel == selectedChannel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isSelected -> NeonGreen.copy(alpha = 0.14f)
                                    isFocused -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.surface
                                }
                            )
                            .border(
                                width = if (isFocused || isSelected) 2.dp else 1.dp,
                                color = if (isFocused || isSelected) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .onFocusChanged { isFocused = it.isFocused }
                            .clickable { onSelect(channel) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) NeonGreen else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDialogCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(460.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
