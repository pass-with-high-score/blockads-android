package app.pwhs.blockads.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.FilterListDao
import app.pwhs.blockads.data.ProfileManager
import app.pwhs.blockads.data.ProfileSchedule
import app.pwhs.blockads.data.ProtectionProfile
import app.pwhs.blockads.data.ProtectionProfileDao
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.worker.ProfileScheduleWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val profileManager: ProfileManager,
    private val profileDao: ProtectionProfileDao,
    private val filterListDao: FilterListDao,
    application: Application
) : AndroidViewModel(application) {

    val profiles: StateFlow<List<ProtectionProfile>> = profileDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfile: StateFlow<ProtectionProfile?> = profileDao.getActiveFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allSchedules: StateFlow<List<ProfileSchedule>> = profileDao.getAllSchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            profileManager.seedPresetsIfNeeded()
        }
    }

    fun switchProfile(profileId: Long) {
        viewModelScope.launch {
            profileManager.switchToProfile(profileId)
            _events.toast(R.string.profile_switched)
        }
    }

    fun createCustomProfile(
        name: String,
        safeSearchEnabled: Boolean,
        youtubeRestrictedMode: Boolean
    ) {
        viewModelScope.launch {
            // Seed with currently enabled filter list URLs so the profile
            // starts with the user's current configuration
            val currentUrls = filterListDao.getEnabled()
                .map { it.url }
                .toSet()
            val profile = ProtectionProfile(
                name = name,
                profileType = ProtectionProfile.TYPE_CUSTOM,
                enabledFilterUrls = currentUrls.joinToString(","),
                safeSearchEnabled = safeSearchEnabled,
                youtubeRestrictedMode = youtubeRestrictedMode
            )
            profileDao.insert(profile)
            _events.toast(R.string.profile_created)
        }
    }

    fun deleteProfile(profile: ProtectionProfile) {
        if (ProtectionProfile.isPreset(profile.profileType)) return
        viewModelScope.launch {
            if (profile.isActive) {
                // Switch to Default before deleting
                val defaultProfile = profileDao.getByType(ProtectionProfile.TYPE_DEFAULT)
                if (defaultProfile != null) {
                    profileManager.switchToProfile(defaultProfile.id)
                }
            }
            profileDao.delete(profile)
            _events.toast(R.string.profile_deleted)
        }
    }

    fun addSchedule(
        profileId: Long,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysOfWeek: String
    ) {
        viewModelScope.launch {
            val schedule = ProfileSchedule(
                profileId = profileId,
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                daysOfWeek = daysOfWeek
            )
            profileDao.insertSchedule(schedule)
            ProfileScheduleWorker.schedule(getApplication())
            _events.toast(R.string.profile_schedule_added)
        }
    }

    fun toggleSchedule(schedule: ProfileSchedule) {
        viewModelScope.launch {
            profileDao.updateSchedule(schedule.copy(isEnabled = !schedule.isEnabled))
            val enabledSchedules = profileDao.getEnabledSchedules()
            if (enabledSchedules.isEmpty()) {
                ProfileScheduleWorker.cancel(getApplication())
            } else {
                ProfileScheduleWorker.schedule(getApplication())
            }
        }
    }

    fun deleteSchedule(schedule: ProfileSchedule) {
        viewModelScope.launch {
            profileDao.deleteSchedule(schedule)
            val enabledSchedules = profileDao.getEnabledSchedules()
            if (enabledSchedules.isEmpty()) {
                ProfileScheduleWorker.cancel(getApplication())
            }
            _events.toast(R.string.profile_schedule_deleted)
        }
    }
}
