package app.pwhs.blockads.ui.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.ProfileSchedule
import app.pwhs.blockads.data.ProtectionProfile
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.settings.component.SectionHeader
import app.pwhs.blockads.ui.theme.NeonGreen
import app.pwhs.blockads.ui.theme.TextSecondary
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navigator: DestinationsNavigator,
    viewModel: ProfileViewModel = koinViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val allSchedules by viewModel.allSchedules.collectAsState()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showScheduleDialog by rememberSaveable { mutableStateOf(false) }
    var scheduleTargetProfileId by rememberSaveable { mutableStateOf(-1L) }

    UiEventEffect(viewModel.events)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_cancel)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.profile_create_custom))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Quick switch section
            SectionHeader(
                title = stringResource(R.string.profile_section_profiles),
                icon = Icons.Default.Security
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.animateContentSize()) {
                    profiles.forEachIndexed { index, profile ->
                        ProfileItem(
                            profile = profile,
                            isActive = profile.id == activeProfile?.id,
                            onSelect = { viewModel.switchProfile(profile.id) },
                            onDelete = if (!ProtectionProfile.isPreset(profile.profileType)) {
                                { viewModel.deleteProfile(profile) }
                            } else null,
                            onSchedule = {
                                scheduleTargetProfileId = profile.id
                                showScheduleDialog = true
                            }
                        )
                        if (index < profiles.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // Schedules section
            if (allSchedules.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    title = stringResource(R.string.profile_section_schedules),
                    icon = Icons.Default.Schedule
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        val schedulesWithProfiles = allSchedules.mapNotNull { schedule ->
                            val profileName = profiles.find { it.id == schedule.profileId }?.name
                            profileName?.let { schedule to it }
                        }
                        schedulesWithProfiles.forEachIndexed { index, (schedule, profileName) ->
                            ScheduleItem(
                                schedule = schedule,
                                profileName = profileName,
                                onToggle = { viewModel.toggleSchedule(schedule) },
                                onDelete = { viewModel.deleteSchedule(schedule) }
                            )
                            if (index < schedulesWithProfiles.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    if (showCreateDialog) {
        CreateCustomProfileDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, safeSearch, youtubeRestricted ->
                viewModel.createCustomProfile(
                    name = name,
                    enabledFilterUrls = emptySet(),
                    safeSearchEnabled = safeSearch,
                    youtubeRestrictedMode = youtubeRestricted
                )
                showCreateDialog = false
            }
        )
    }

    if (showScheduleDialog && scheduleTargetProfileId > 0) {
        AddScheduleDialog(
            onDismiss = { showScheduleDialog = false },
            onAdd = { startH, startM, endH, endM, days ->
                viewModel.addSchedule(scheduleTargetProfileId, startH, startM, endH, endM, days)
                showScheduleDialog = false
            }
        )
    }
}

@Composable
private fun ProfileItem(
    profile: ProtectionProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    onSchedule: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) NeonGreen.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = profileIcon(profile.profileType),
                contentDescription = null,
                tint = if (isActive) NeonGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) NeonGreen else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = profileDescription(profile.profileType),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        IconButton(onClick = onSchedule, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = stringResource(R.string.profile_add_schedule),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (isActive) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.profile_active),
                tint = NeonGreen,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ScheduleItem(
    schedule: ProfileSchedule,
    profileName: String,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${formatTime(schedule.startHour, schedule.startMinute)} – ${formatTime(schedule.endHour, schedule.endMinute)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = formatDays(schedule.daysOfWeek),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Switch(
            checked = schedule.isEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = NeonGreen
            )
        )

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CreateCustomProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, Boolean) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var safeSearch by rememberSaveable { mutableStateOf(false) }
    var youtubeRestricted by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_create_custom)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_safe_search),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = safeSearch, onCheckedChange = { safeSearch = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_youtube_restricted),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = youtubeRestricted, onCheckedChange = { youtubeRestricted = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), safeSearch, youtubeRestricted) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.settings_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}

@Composable
private fun AddScheduleDialog(
    onDismiss: () -> Unit,
    onAdd: (Int, Int, Int, Int, String) -> Unit
) {
    var startHour by rememberSaveable { mutableIntStateOf(18) }
    var startMinute by rememberSaveable { mutableIntStateOf(0) }
    var endHour by rememberSaveable { mutableIntStateOf(8) }
    var endMinute by rememberSaveable { mutableIntStateOf(0) }
    var daysOfWeek by rememberSaveable { mutableStateOf("1,2,3,4,5,6,7") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_add_schedule)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.profile_schedule_start),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = startHour.toString(),
                        onValueChange = { startHour = it.toIntOrNull()?.coerceIn(0, 23) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                    Text(":")
                    OutlinedTextField(
                        value = startMinute.toString().padStart(2, '0'),
                        onValueChange = { startMinute = it.toIntOrNull()?.coerceIn(0, 59) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.profile_schedule_end),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = endHour.toString(),
                        onValueChange = { endHour = it.toIntOrNull()?.coerceIn(0, 23) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                    Text(":")
                    OutlinedTextField(
                        value = endMinute.toString().padStart(2, '0'),
                        onValueChange = { endMinute = it.toIntOrNull()?.coerceIn(0, 59) ?: 0 },
                        modifier = Modifier.width(70.dp),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.profile_schedule_days),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    text = formatDays(daysOfWeek),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(startHour, startMinute, endHour, endMinute, daysOfWeek) }) {
                Text(stringResource(R.string.settings_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}

private fun profileIcon(type: String): ImageVector = when (type) {
    ProtectionProfile.TYPE_DEFAULT -> Icons.Default.GppGood
    ProtectionProfile.TYPE_STRICT -> Icons.Default.Security
    ProtectionProfile.TYPE_FAMILY -> Icons.Default.FamilyRestroom
    ProtectionProfile.TYPE_GAMING -> Icons.Default.SportsEsports
    else -> Icons.Default.Tune
}

@Composable
private fun profileDescription(type: String): String = when (type) {
    ProtectionProfile.TYPE_DEFAULT -> stringResource(R.string.profile_desc_default)
    ProtectionProfile.TYPE_STRICT -> stringResource(R.string.profile_desc_strict)
    ProtectionProfile.TYPE_FAMILY -> stringResource(R.string.profile_desc_family)
    ProtectionProfile.TYPE_GAMING -> stringResource(R.string.profile_desc_gaming)
    else -> stringResource(R.string.profile_desc_custom)
}

private fun formatTime(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

@Composable
private fun formatDays(daysOfWeek: String): String {
    val dayNames = listOf(
        stringResource(R.string.profile_day_mon),
        stringResource(R.string.profile_day_tue),
        stringResource(R.string.profile_day_wed),
        stringResource(R.string.profile_day_thu),
        stringResource(R.string.profile_day_fri),
        stringResource(R.string.profile_day_sat),
        stringResource(R.string.profile_day_sun)
    )
    val days = daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
    return if (days.size == 7) stringResource(R.string.profile_schedule_every_day)
    else days.mapNotNull { if (it in 1..7) dayNames[it - 1] else null }.joinToString(", ")
}
