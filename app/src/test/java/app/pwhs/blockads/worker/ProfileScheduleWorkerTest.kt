package app.pwhs.blockads.worker

import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.entities.ProfileManager
import app.pwhs.blockads.data.entities.ProfileSchedule
import app.pwhs.blockads.data.entities.ProtectionProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class ProfileScheduleWorkerTest {

    private var schedules = listOf<ProfileSchedule>()
    private var active: ProtectionProfile? = null
    private val dao: ProtectionProfileDao = mockk {
        coEvery { getEnabledSchedules() } answers { schedules }
        coEvery { getActive() } answers { active }
    }
    private val manager: ProfileManager = mockk(relaxed = true)
    private val h = WorkerHarness(module {
        single { dao }
        single { manager }
    })

    @After
    fun tearDown() = h.close()

    private val now = Calendar.getInstance()
    private val today = now.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
    private val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    /** A window of ±offset minutes around now, wrapping midnight when needed. */
    private fun aroundNow(profileId: Long, days: String = "1,2,3,4,5,6,7", offset: Int = 90): ProfileSchedule {
        val start = (nowMinutes - offset + 1440) % 1440
        val end = (nowMinutes + offset) % 1440
        return ProfileSchedule(profileId = profileId, startHour = start / 60, startMinute = start % 60, endHour = end / 60, endMinute = end % 60, daysOfWeek = days)
    }

    private fun profile(id: Long) = ProtectionProfile(id = id, name = "p$id", profileType = "custom")

    @Test
    fun `no schedules is a no-op`() {
        assertEquals(Result.success(), h.run<ProfileScheduleWorker>())
        coVerify(exactly = 0) { dao.getActive() }
    }

    @Test
    fun `an active window switches to its profile`() {
        schedules = listOf(aroundNow(profileId = 7))
        active = profile(1)
        assertEquals(Result.success(), h.run<ProfileScheduleWorker>())
        coVerify { manager.switchToProfile(7) }
    }

    @Test
    fun `already on the scheduled profile does not switch again`() {
        schedules = listOf(aroundNow(profileId = 7))
        active = profile(7)
        h.run<ProfileScheduleWorker>()
        coVerify(exactly = 0) { manager.switchToProfile(any()) }
    }

    @Test
    fun `other weekdays and far-away windows are skipped`() {
        val otherDay = (today % 7) + 1
        val far = (nowMinutes + 600) % 1440
        schedules = listOf(
            aroundNow(profileId = 7, days = "$otherDay"),
            ProfileSchedule(profileId = 8, startHour = far / 60, startMinute = far % 60, endHour = far / 60, endMinute = far % 60, daysOfWeek = "junk,$today"),
        )
        h.run<ProfileScheduleWorker>()
        coVerify(exactly = 0) { manager.switchToProfile(any()) }
    }

    @Test
    fun `dao failure retries`() {
        coEvery { dao.getEnabledSchedules() } throws IllegalStateException("db")
        assertEquals(Result.retry(), h.run<ProfileScheduleWorker>())
    }

    @Test
    fun `schedule and cancel manage the periodic work`() {
        ProfileScheduleWorker.schedule(h.app)
        ProfileScheduleWorker.schedule(h.app)
        val infos = h.uniqueWork(ProfileScheduleWorker.WORK_NAME)
        assertEquals(1, infos.size)
        assertEquals(15 * 60_000L, infos.single().periodicityInfo!!.repeatIntervalMillis)

        ProfileScheduleWorker.cancel(h.app)
        assertTrue(h.uniqueWork(ProfileScheduleWorker.WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
    }
}
