package app.pwhs.blockads.data.dao

import app.cash.turbine.test
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.DnsErrorEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DnsErrorDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DnsErrorDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.dnsErrorDao()
    }

    @After
    fun tearDown() = db.close()

    private fun error(at: Long, domain: String = "d$at.example") =
        DnsErrorEntry(domain = domain, timestamp = at, errorType = "TIMEOUT", errorMessage = "t", dnsServer = "9.9.9.9")

    @Test
    fun `errors are newest first and capped at 100`() = runTest {
        (1L..105L).forEach { dao.insert(error(it)) }

        val all = dao.getAllErrors().first()
        assertEquals(100, all.size)
        assertEquals(105L, all.first().timestamp)
        assertEquals(6L, all.last().timestamp)
    }

    @Test
    fun `count since is strictly after the cutoff`() = runTest {
        dao.getErrorCountSince(10).test {
            assertEquals(0, awaitItem())
            dao.insert(error(10))
            assertEquals(0, awaitItem())
            dao.insert(error(11))
            assertEquals(1, awaitItem())
        }
    }

    @Test
    fun `pruning keeps the cutoff row and deleteAll empties the table`() = runTest {
        listOf(5L, 10L, 15L).forEach { dao.insert(error(it)) }

        dao.deleteOlderThan(10)
        assertEquals(listOf(15L, 10L), dao.getAllErrors().first().map { it.timestamp })

        dao.deleteAll()
        assertEquals(0, dao.getAllErrors().first().size)
    }

    @Test
    fun `columns round trip including fallback flag`() = runTest {
        dao.insert(error(1).copy(errorType = "IO_ERROR", errorMessage = "reset", attemptedFallback = true))
        val row = dao.getAllErrors().first().single()
        assertEquals("IO_ERROR", row.errorType)
        assertEquals("reset", row.errorMessage)
        assertEquals("9.9.9.9", row.dnsServer)
        assertEquals(true, row.attemptedFallback)
    }
}
