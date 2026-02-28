package app.pwhs.blockads.ui.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ProtectionProfile
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.profile.component.AddScheduleDialog
import app.pwhs.blockads.ui.profile.component.CreateCustomProfileDialog
import app.pwhs.blockads.ui.profile.component.ProfileItem
import app.pwhs.blockads.ui.profile.component.ScheduleItem
import app.pwhs.blockads.ui.settings.component.SectionHeader
import app.pwhs.blockads.util.profileDisplayName
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
                            val profile = profiles.find { it.id == schedule.profileId }
                            profile?.let { schedule to it }
                        }
                        schedulesWithProfiles.forEachIndexed { index, (schedule, profile) ->
                            ScheduleItem(
                                schedule = schedule,
                                profileName = profileDisplayName(profile),
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
