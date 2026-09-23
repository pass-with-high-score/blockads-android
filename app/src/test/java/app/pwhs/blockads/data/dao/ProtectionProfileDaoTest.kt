package app.pwhs.blockads.data.dao

import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.ProfileSchedule
import app.pwhs.blockads.data.entities.ProtectionProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProtectionProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProtectionProfileDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.protectionProfileDao()
    }

    @After
    fun tearDown() = db.close()

    private fun profile(name: String, type: String = ProtectionProfile.TYPE_CUSTOM, active: Boolean = false, at: Long = 0) =
        ProtectionProfile(name = name, profileType = type, isActive = active, createdAt = at)

    private fun schedule(profileId: Long, start: Int, enabled: Boolean = true) =
        ProfileSchedule(profileId = profileId, startHour = start, startMinute = 0, endHour = start + 1, endMinute = 0, isEnabled = enabled)

    @Test
    fun `profiles are listed oldest first and found by id and type`() = runTest {
        val b = dao.insert(profile("B", ProtectionProfile.TYPE_STRICT, at = 2))
        dao.insert(profile("A", ProtectionProfile.TYPE_DEFAULT, at = 1))

        assertEquals(listOf("A", "B"), dao.getAllSync().map { it.name })
        assertEquals(listOf("A", "B"), dao.getAll().first().map { it.name })
        assertEquals("B", dao.getById(b)?.name)
        assertEquals("A", dao.getByType(ProtectionProfile.TYPE_DEFAULT)?.name)
        assertNull(dao.getByType(ProtectionProfile.TYPE_GAMING))
        assertNull(dao.getById(404))
    }

    @Test
    fun `deactivateAll then activate leaves exactly one active profile`() = runTest {
        val a = dao.insert(profile("A", active = true))
        val b = dao.insert(profile("B"))

        assertEquals(a, dao.getActiveFlow().first()?.id)

        dao.deactivateAll()
        dao.activate(b)

        assertEquals(b, dao.getActiveFlow().first()?.id)
        assertEquals(b, dao.getActive()?.id)
        assertEquals(listOf(b), dao.getAllSync().filter { it.isActive }.map { it.id })
    }

    @Test
    fun `activate alone does not clear other active rows`() = runTest {
        dao.insert(profile("A", active = true))
        val b = dao.insert(profile("B"))

        dao.activate(b)

        assertEquals(2, dao.getAllSync().count { it.isActive })
        assertTrue(dao.getActive() != null)
    }

    @Test
    fun `no active profile`() = runTest {
        dao.insert(profile("A"))
        assertNull(dao.getActive())
    }

    @Test
    fun `update changes the stored profile`() = runTest {
        val id = dao.insert(profile("A"))
        dao.update(dao.getById(id)!!.copy(enabledFilterUrls = "https://x", safeSearchEnabled = true))
        val row = dao.getById(id)!!
        assertEquals("https://x", row.enabledFilterUrls)
        assertTrue(row.safeSearchEnabled)
    }

    @Test
    fun `deleting a profile cascades to its schedules`() = runTest {
        val a = dao.insert(profile("A"))
        val b = dao.insert(profile("B"))
        dao.insertSchedule(schedule(a, 8))
        dao.insertSchedule(schedule(b, 9))

        dao.delete(dao.getById(a)!!)

        assertEquals(listOf(b), dao.getAllSchedules().first().map { it.profileId })
    }

    @Test
    fun `re-inserting an existing profile id drops its schedules through the cascade`() = runTest {
        val a = dao.insert(profile("A"))
        dao.insertSchedule(schedule(a, 8))

        dao.insert(dao.getById(a)!!.copy(name = "A2"))

        assertEquals("A2", dao.getById(a)?.name)
        assertTrue(dao.getSchedulesForProfile(a).first().isEmpty())
    }

    @Test
    fun `schedule queries`() = runTest {
        val a = dao.insert(profile("A"))
        val b = dao.insert(profile("B"))
        val late = dao.insertSchedule(schedule(a, 20))
        dao.insertSchedule(schedule(a, 6))
        dao.insertSchedule(schedule(b, 12, enabled = false))

        assertEquals(listOf(6, 20), dao.getEnabledSchedules().map { it.startHour })
        assertEquals(2, dao.getSchedulesForProfile(a).first().size)
        assertEquals(3, dao.getAllSchedules().first().size)

        val lateRow = dao.getSchedulesForProfile(a).first().first { it.id == late }
        dao.updateSchedule(lateRow.copy(isEnabled = false))
        assertEquals(listOf(6), dao.getEnabledSchedules().map { it.startHour })

        dao.deleteSchedule(lateRow)
        assertEquals(1, dao.getSchedulesForProfile(a).first().size)
    }

    @Test
    fun `enabled schedules at the same start time are ordered by id`() = runTest {
        val a = dao.insert(profile("A"))
        val first = dao.insertSchedule(schedule(a, 7))
        val second = dao.insertSchedule(schedule(a, 7))
        assertEquals(listOf(first, second), dao.getEnabledSchedules().map { it.id })
    }

    @Test
    fun `schedule for a missing profile violates the foreign key`() = runTest {
        val result = runCatching { dao.insertSchedule(schedule(profileId = 999, start = 1)) }
        assertTrue(result.exceptionOrNull() is android.database.sqlite.SQLiteConstraintException)
    }
}
