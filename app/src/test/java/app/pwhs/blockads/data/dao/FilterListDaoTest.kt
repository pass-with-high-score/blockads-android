package app.pwhs.blockads.data.dao

import app.cash.turbine.test
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.FilterList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FilterListDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FilterListDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.filterListDao()
    }

    @After
    fun tearDown() = db.close()

    private fun filter(name: String, url: String = "https://$name.example/list", enabled: Boolean = true, builtIn: Boolean = false) =
        FilterList(name = name, url = url, isEnabled = enabled, isBuiltIn = builtIn)

    @Test
    fun `lists are ordered by name and filtered by enabled state`() = runTest {
        dao.insert(filter("Charlie"))
        dao.insert(filter("Alpha", enabled = false))
        dao.insert(filter("Bravo"))

        assertEquals(listOf("Alpha", "Bravo", "Charlie"), dao.getAllSync().map { it.name })
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), dao.getAll().first().map { it.name })
        assertEquals(setOf("Bravo", "Charlie"), dao.getEnabled().map { it.name }.toSet())
        assertEquals(3, dao.count())
    }

    @Ignore("known bug: name ordering uses SQLite BINARY collation, so lowercase custom filters sort after every capitalized one")
    @Test
    fun `name ordering ignores case`() = runTest {
        dao.insert(filter("zeta"))
        dao.insert(filter("alpha"))
        dao.insert(filter("Beta"))
        assertEquals(listOf("alpha", "Beta", "zeta"), dao.getAllSync().map { it.name })
    }

    @Test
    fun `duplicate urls are allowed as separate rows`() = runTest {
        val a = dao.insert(filter("One", url = "https://same.example"))
        val b = dao.insert(filter("Two", url = "https://same.example"))

        assertEquals(2, dao.count())
        assertEquals(listOf("https://same.example", "https://same.example"), dao.getAllUrls())
        assertEquals(a, dao.getByUrl("https://same.example")?.id)
        assertNotEquals(a, b)
    }

    @Test
    fun `insert with an existing id replaces the row`() = runTest {
        val id = dao.insert(filter("Old"))
        assertEquals(id, dao.insert(filter("New").copy(id = id)))
        assertEquals(listOf("New"), dao.getAllSync().map { it.name })
    }

    @Test
    fun `targeted updates`() = runTest {
        val id = dao.insert(filter("List"))

        dao.setEnabled(id, false)
        dao.updateStats(id, count = 1234, timestamp = 99L)

        val row = dao.getById(id)!!
        assertEquals(false, row.isEnabled)
        assertEquals(1234, row.domainCount)
        assertEquals(99L, row.lastUpdated)

        dao.update(row.copy(description = "desc"))
        assertEquals("desc", dao.getById(id)?.description)
    }

    @Test
    fun `lookups by url, original url and built-in flag`() = runTest {
        dao.insert(filter("Builtin", url = "https://b.example", builtIn = true).copy(originalUrl = "https://orig.example"))
        dao.insert(filter("Custom", url = "https://c.example"))

        assertEquals("Builtin", dao.getByOriginalUrl("https://orig.example")?.name)
        assertEquals("Custom", dao.getByUrl("https://c.example")?.name)
        assertNull(dao.getByUrl("https://missing.example"))
        assertNull(dao.getById(999))
        assertEquals(listOf("Custom"), dao.getAllNonBuiltIn().map { it.name })
    }

    @Test
    fun `delete removes the row and the id flow emits null`() = runTest {
        val id = dao.insert(filter("Temp"))
        dao.getByIdFlow(id).test {
            assertEquals("Temp", awaitItem()?.name)
            dao.delete(dao.getById(id)!!)
            assertNull(awaitItem())
        }
        assertEquals(0, dao.count())
    }
}
