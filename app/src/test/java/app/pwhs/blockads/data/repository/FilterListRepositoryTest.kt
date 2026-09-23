package app.pwhs.blockads.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.remote.FilterDownloadManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FilterListRepositoryTest {

    private lateinit var db: AppDatabase
    private val downloadManager = mockk<FilterDownloadManager>(relaxed = true)
    private var catalog = "[]"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun repository() = FilterListRepository(
        context = ApplicationProvider.getApplicationContext(),
        filterListDao = db.filterListDao(),
        whitelistDomainDao = db.whitelistDomainDao(),
        customDnsRuleDao = db.customDnsRuleDao(),
        client = HttpClient(MockEngine { respond(catalog, HttpStatusCode.OK) }),
        downloadManager = downloadManager,
    )

    private fun remote(name: String, originalUrl: String) =
        """{"id":"${name.lowercase()}","name":"$name","isEnabled":false,"isBuiltIn":true,"category":"ads",""" +
            """"ruleCount":10,"originalUrl":"$originalUrl","bloomUrl":"https://cdn.example/$name.bloom",""" +
            """"trieUrl":"https://cdn.example/$name.trie"}"""

    @Ignore("known bug: catalog sync matches rows by name and converts a same-named user filter")
    @Test
    fun `sync leaves a user's filter alone when a built-in shares its name`() = runTest {
        db.filterListDao().insert(FilterList(name = "EasyList", url = "https://mine.example/list.txt"))
        catalog = "[${remote("EasyList", "https://easylist.to/easylist/easylist.txt")}]"

        repository().fetchAndSyncRemoteFilterLists()

        val mine = db.filterListDao().getAllNonBuiltIn()
        assertEquals(listOf("https://mine.example/list.txt"), mine.map { it.url })
    }

    @Ignore("known bug: catalog sync deletes enabled built-ins missing from the catalog")
    @Test
    fun `sync keeps an enabled built-in that is missing from the remote catalog`() = runTest {
        db.filterListDao().insert(FilterList(name = "Retired", url = "https://old.example", isBuiltIn = true))
        catalog = "[${remote("EasyList", "https://easylist.to/easylist/easylist.txt")}]"

        repository().fetchAndSyncRemoteFilterLists()

        assertNotNull("retired built-in was deleted", db.filterListDao().getByUrl("https://old.example"))
    }

    @Test
    fun `sync inserts new built-ins and keeps them disabled by default`() = runTest {
        catalog = "[${remote("EasyList", "https://easylist.to/easylist/easylist.txt")}]"

        repository().fetchAndSyncRemoteFilterLists()

        val row = db.filterListDao().getByUrl("https://easylist.to/easylist/easylist.txt")!!
        assertTrue(row.isBuiltIn)
        assertEquals(false, row.isEnabled)
        assertEquals("https://cdn.example/EasyList.trie", row.trieUrl)
    }

    @Ignore("known bug: forced update reports success when every download fails")
    @Test
    fun `force update fails when every enabled built-in download fails`() = runTest {
        catalog = "[${remote("EasyList", "https://easylist.to/easylist/easylist.txt")}]"
        val repo = repository()
        repo.fetchAndSyncRemoteFilterLists()
        val row = db.filterListDao().getByUrl("https://easylist.to/easylist/easylist.txt")!!
        db.filterListDao().setEnabled(row.id, true)
        coEvery { downloadManager.downloadFilterList(any(), any()) } returns Result.failure(Exception("offline"))

        val result = repo.forceUpdateAllEnabledFilters()

        assertTrue("expected failure, got $result", result.isFailure)
    }
}
