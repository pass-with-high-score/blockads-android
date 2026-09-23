package app.pwhs.blockads.ui.profile

import android.app.Application
import app.cash.turbine.test
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.ProfileManager
import app.pwhs.blockads.data.entities.ProfileSchedule
import app.pwhs.blockads.data.entities.ProtectionProfile
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.keepHot
import app.pwhs.blockads.worker.ProfileScheduleWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val profileManager: ProfileManager = mockk(relaxed = true)
    private val custom = ProtectionProfile(id = 9, name = "Mine", profileType = ProtectionProfile.TYPE_CUSTOM)
    private val defaultProfile = ProtectionProfile(id = 1, name = "Default", profileType = ProtectionProfile.TYPE_DEFAULT)
    private val schedule = ProfileSchedule(id = 3, profileId = 9, startHour = 22, startMinute = 0, endHour = 6, endMinute = 0)
    private val profileDao: ProtectionProfileDao = mockk(relaxed = true) {
        every { getAll() } returns flowOf(listOf(defaultProfile, custom))
        every { getActiveFlow() } returns flowOf(defaultProfile)
        every { getAllSchedules() } returns flowOf(listOf(schedule))
        coEvery { getByType(ProtectionProfile.TYPE_DEFAULT) } returns defaultProfile
    }
    private val filterListDao: FilterListDao = mockk(relaxed = true)
    private val vm by lazy { ProfileViewModel(profileManager, profileDao, filterListDao, mockk<Application>(relaxed = true)) }

    @Before
    fun setUp() {
        mockkObject(ProfileScheduleWorker.Companion)
        every { ProfileScheduleWorker.schedule(any()) } just Runs
        every { ProfileScheduleWorker.cancel(any()) } just Runs
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `init seeds presets and state mirrors the table`() = runTest {
        keepHot(vm.profiles, vm.activeProfile, vm.allSchedules)
        coVerify(exactly = 1) { profileManager.seedPresetsIfNeeded() }
        assertEquals(listOf(1L, 9L), vm.profiles.value.map { it.id })
        assertEquals(defaultProfile, vm.activeProfile.value)
        assertEquals(listOf(schedule), vm.allSchedules.value)
    }

    @Test
    fun `switching delegates to the profile manager and confirms`() = runTest {
        vm.events.test {
            vm.switchProfile(9)
            assertEquals(UiEvent.ToastRes(R.string.profile_switched), awaitItem())
        }
        coVerify { profileManager.switchToProfile(9) }
    }

    @Test
    fun `a new custom profile is seeded with the enabled filters and becomes active`() = runTest {
        coEvery { filterListDao.getEnabled() } returns listOf(
            FilterList(name = "a", url = "https://a"),
            FilterList(name = "b", url = "https://b"),
            FilterList(name = "a2", url = "https://a"),
        )
        val inserted = slot<ProtectionProfile>()
        coEvery { profileDao.insert(capture(inserted)) } returns 42
        vm.events.test {
            vm.createCustomProfile("Kids", safeSearchEnabled = true, youtubeRestrictedMode = false)
            assertEquals(UiEvent.ToastRes(R.string.profile_created), awaitItem())
        }
        assertEquals("https://a,https://b", inserted.captured.enabledFilterUrls)
        assertEquals(ProtectionProfile.TYPE_CUSTOM, inserted.captured.profileType)
        assertEquals(true, inserted.captured.safeSearchEnabled)
        coVerify { profileManager.switchToProfile(42) }
    }

    @Test
    fun `presets cannot be deleted`() {
        vm.deleteProfile(defaultProfile)
        coVerify(exactly = 0) { profileDao.delete(any()) }
    }

    @Test
    fun `deleting the active custom profile switches to Default first`() = runTest {
        val active = custom.copy(isActive = true)
        vm.events.test {
            vm.deleteProfile(active)
            assertEquals(UiEvent.ToastRes(R.string.profile_deleted), awaitItem())
        }
        coVerifyOrder {
            profileManager.switchToProfile(1)
            profileDao.delete(active)
        }
    }

    @Test
    fun `deleting an inactive custom profile leaves the active one alone`() {
        vm.deleteProfile(custom)
        coVerify { profileDao.delete(custom) }
        coVerify(exactly = 0) { profileManager.switchToProfile(any()) }
    }

    @Test
    fun `adding a schedule stores it and schedules the worker`() = runTest {
        val stored = slot<ProfileSchedule>()
        coEvery { profileDao.insertSchedule(capture(stored)) } returns 1
        vm.events.test {
            vm.addSchedule(9, 22, 30, 6, 15, "1,7")
            assertEquals(UiEvent.ToastRes(R.string.profile_schedule_added), awaitItem())
        }
        assertEquals(ProfileSchedule(profileId = 9, startHour = 22, startMinute = 30, endHour = 6, endMinute = 15, daysOfWeek = "1,7"), stored.captured)
        verify { ProfileScheduleWorker.schedule(any()) }
    }

    @Test
    fun `toggling the last enabled schedule off cancels the worker`() {
        coEvery { profileDao.getEnabledSchedules() } returns emptyList()
        vm.toggleSchedule(schedule)
        coVerify { profileDao.updateSchedule(schedule.copy(isEnabled = false)) }
        verify { ProfileScheduleWorker.cancel(any()) }
    }

    @Test
    fun `toggling with schedules still enabled keeps the worker scheduled`() {
        coEvery { profileDao.getEnabledSchedules() } returns listOf(schedule)
        vm.toggleSchedule(schedule.copy(isEnabled = false))
        verify { ProfileScheduleWorker.schedule(any()) }
        verify(exactly = 0) { ProfileScheduleWorker.cancel(any()) }
    }

    @Test
    fun `deleting schedules cancels the worker only when none stay enabled`() = runTest {
        coEvery { profileDao.getEnabledSchedules() } returns listOf(schedule) andThen emptyList()
        vm.events.test {
            vm.deleteSchedule(schedule)
            assertEquals(UiEvent.ToastRes(R.string.profile_schedule_deleted), awaitItem())
            verify(exactly = 0) { ProfileScheduleWorker.cancel(any()) }
            vm.deleteSchedule(schedule)
            awaitItem()
        }
        verify(exactly = 1) { ProfileScheduleWorker.cancel(any()) }
    }
}
