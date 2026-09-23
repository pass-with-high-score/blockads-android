package app.pwhs.blockads.data.dao

import app.cash.turbine.test
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.WhitelistDomain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhitelistDomainDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WhitelistDomainDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.whitelistDomainDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `flow lists newest first`() = runTest {
        dao.getAll().test {
            assertEquals(emptyList<WhitelistDomain>(), awaitItem())
            dao.insert(WhitelistDomain(domain = "old.example", addedTimestamp = 1))
            awaitItem()
            dao.insert(WhitelistDomain(domain = "new.example", addedTimestamp = 2))
            assertEquals(listOf("new.example", "old.example"), awaitItem().map { it.domain })
        }
    }

    @Test
    fun `without a unique index the same domain can be stored twice`() = runTest {
        dao.insert(WhitelistDomain(domain = "dup.example"))
        dao.insert(WhitelistDomain(domain = "dup.example"))

        assertEquals(2, dao.exists("dup.example"))
        assertEquals(listOf("dup.example", "dup.example"), dao.getAllDomains())

        dao.deleteByDomain("dup.example")
        assertEquals(0, dao.exists("dup.example"))
    }

    @Test
    fun `exists is exact and delete by entity removes one row`() = runTest {
        dao.insert(WhitelistDomain(id = 1, domain = "a.example"))
        dao.insert(WhitelistDomain(id = 2, domain = "b.example"))

        assertEquals(0, dao.exists("A.example"))
        assertEquals(0, dao.exists("example"))

        dao.delete(WhitelistDomain(id = 1, domain = "ignored"))
        assertEquals(listOf("b.example"), dao.getAllDomains())
    }

    @Test
    fun `insert with an existing id replaces the domain`() = runTest {
        dao.insert(WhitelistDomain(id = 5, domain = "before.example"))
        dao.insert(WhitelistDomain(id = 5, domain = "after.example"))
        assertEquals(listOf("after.example"), dao.getAllDomains())
    }
}
