package app.pwhs.blockads.ui.firewall.component

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.ui.common.AppInfoData
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallConfigSheet(
    app: AppInfoData,
    existingRule: FirewallRule?,
    onSave: (FirewallRule) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
    ) {
        FirewallConfigContent(
            app = app,
            existingRule = existingRule,
            onSave = onSave,
            onDelete = onDelete,
            modifier = Modifier.navigationBarsPadding()
        )
    }
}

@Composable
fun FirewallConfigContent(
    app: AppInfoData,
    existingRule: FirewallRule?,
    onSave: (FirewallRule) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var blockWifi by remember(app.packageName, existingRule) {
        mutableStateOf(existingRule?.blockWifi ?: true)
    }
    var blockMobileData by remember(app.packageName, existingRule) {
        mutableStateOf(existingRule?.blockMobileData ?: true)
    }
    var scheduleEnabled by remember(app.packageName, existingRule) {
        mutableStateOf(existingRule?.scheduleEnabled ?: false)
    }
    var startHour by remember(app.packageName, existingRule) {
        mutableIntStateOf(existingRule?.scheduleStartHour ?: 22)
    }
    var startMinute by remember(app.packageName, existingRule) {
        mutableIntStateOf(existingRule?.scheduleStartMinute ?: 0)
    }
    var endHour by remember(app.packageName, existingRule) {
        mutableIntStateOf(existingRule?.scheduleEndHour ?: 6)
    }
    var endMinute by remember(app.packageName, existingRule) {
        mutableIntStateOf(existingRule?.scheduleEndMinute ?: 0)
    }

    fun persistRule(
        wifi: Boolean = blockWifi,
        mobile: Boolean = blockMobileData,
        schedule: Boolean = scheduleEnabled,
        sHour: Int = startHour,
        sMinute: Int = startMinute,
        eHour: Int = endHour,
        eMinute: Int = endMinute
    ) {
        onSave(
            FirewallRule(
                id = existingRule?.id ?: 0,
                packageName = app.packageName,
                blockWifi = wifi,
                blockMobileData = mobile,
                scheduleEnabled = schedule,
                scheduleStartHour = sHour,
                scheduleStartMinute = sMinute,
                scheduleEndHour = eHour,
                scheduleEndMinute = eMinute,
                isEnabled = true
            )
        )
    }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // App Header + App Info action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = rememberDrawablePainter(drawable = app.icon),
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.firewall_app_info),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Network Block Controls
            Text(
                text = stringResource(R.string.firewall_configure),
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            ConfigToggleRow(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.firewall_block_wifi),
                checked = blockWifi,
                onCheckedChange = {
                    blockWifi = it
                    persistRule(wifi = it)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ConfigToggleRow(
                icon = Icons.Default.SignalCellularAlt,
                title = stringResource(R.string.firewall_block_mobile),
                checked = blockMobileData,
                onCheckedChange = {
                    blockMobileData = it
                    persistRule(mobile = it)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Schedule Section
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.firewall_schedule),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.firewall_schedule_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = scheduleEnabled,
                            onCheckedChange = {
                                scheduleEnabled = it
                                persistRule(schedule = it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    AnimatedVisibility(visible = scheduleEnabled) {
                        var editingStartTime by remember { mutableStateOf<Boolean?>(null) }

                        Column {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ScheduleTimeCard(
                                    label = stringResource(R.string.firewall_schedule_from),
                                    hour = startHour,
                                    minute = startMinute,
                                    onClick = { editingStartTime = true },
                                    modifier = Modifier.weight(1f)
                                )
                                ScheduleTimeCard(
                                    label = stringResource(R.string.firewall_schedule_to),
                                    hour = endHour,
                                    minute = endMinute,
                                    onClick = { editingStartTime = false },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        editingStartTime?.let { isStart ->
                            ScheduleTimePickerDialog(
                                initialHour = if (isStart) startHour else endHour,
                                initialMinute = if (isStart) startMinute else endMinute,
                                title = stringResource(
                                    if (isStart) R.string.firewall_schedule_from
                                    else R.string.firewall_schedule_to
                                ),
                                onConfirm = { h, m ->
                                    if (isStart) {
                                        startHour = h
                                        startMinute = m
                                        persistRule(sHour = h, sMinute = m)
                                    } else {
                                        endHour = h
                                        endMinute = m
                                        persistRule(eHour = h, eMinute = m)
                                    }
                                    editingStartTime = null
                                },
                                onDismiss = { editingStartTime = null }
                            )
                        }
                    }
                }
            }

            if (existingRule != null) {
                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = stringResource(R.string.firewall_reset_rule),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
}

@Composable
private fun ConfigToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.error else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.error
                )
            )
        }
    }
}

@Composable
private fun ScheduleTimeCard(
    label: String,
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = String.format(Locale.US, "%02d:%02d", hour, minute),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    title: String,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text(
                    text = stringResource(android.R.string.ok),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}
