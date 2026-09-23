package app.pwhs.blockads.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.FilterList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class DnsLogDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DnsLogDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dnsLogDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun log(blockedBy: String, at: Long = 1_000, blocked: Boolean = true) =
        dao.insert(DnsLogEntry(domain = "x.example", timestamp = at, isBlocked = blocked, blockedBy = blockedBy))

    private fun localMidnight(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // Only discriminating when the JVM and SQLite run in a zone offset from UTC.
    @Ignore("known bug: daily stats bucket by UTC day")
    @Test
    fun `daily stats bucket by local day like weekly and monthly stats`() = runTest {
        val midnight = localMidnight(daysAgo = 2)
        log("", at = midnight - 30 * 60_000)
        log("", at = midnight + 30 * 60_000)

        val days = dao.getDailyStats(since = 0).first().map { it.day }

        assertEquals(listOf(localMidnight(daysAgo = 3), midnight), days)
    }

    @Test
    fun `reason match is exact within the comma list`() = runTest {
        log("11")
        log("2,11")
        log("1,2")

        assertEquals(1, dao.getBlockedCountByReason("1").first())
        assertEquals(2, dao.getBlockedCountByReason("11").first())
    }

    @Ignore("known bug: category reason misses entries blocked by several filters")
    @Test
    fun `category reason counts entries attributed to several filters`() = runTest {
        val sec = db.filterListDao().insert(FilterList(name = "Phish", url = "s", category = FilterList.CATEGORY_SECURITY))
        val ad = db.filterListDao().insert(FilterList(name = "Ads", url = "a"))
        log("$sec")
        log("$sec,$ad")
        log("$ad")

        assertEquals(2, dao.getBlockedCountByReason(FilterList.CATEGORY_SECURITY).first())
        assertEquals(2, dao.getBlockedByReason(FilterList.CATEGORY_SECURITY).first().size)
    }
}
