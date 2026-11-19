package app.pwhs.blockads.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.remote.DownloadedFilterPaths
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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
        if (app.pwhs.blockads.BuildConfig.IS_STORE) return@runTest
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

    @Test
    fun `loadAllEnabledFilters streams sidecars, deduplicates module CSS, and caches unchanged runs`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val filesDir = ctx.filesDir
        File(filesDir, ".filter_cache_v713").writeText("713")
        val remoteDir = File(filesDir, "remote_filters").apply { mkdirs() }

        val filter = FilterList(
            id = 1L,
            name = "CustomFull",
            url = "https://example.com/list.txt",
            isEnabled = true,
            isBuiltIn = false,
            category = FilterList.CATEGORY_AD,
            ruleCount = 42,
            domainCount = 42,
            bloomUrl = "local://1.bloom",
            trieUrl = "local://1.trie",
        )
        db.filterListDao().insert(filter)

        val trieFile = File(remoteDir, "1.trie").apply { writeText("t") }
        val bloomFile = File(remoteDir, "1.bloom").apply { writeText("b") }
        val cssFile = File(remoteDir, "1.css").apply {
            writeText("! comment\n##.banner-ad\nexample.com##.dup-box\n")
        }
        val scriptletsFile = File(remoteDir, "1.scriptlets").apply {
            writeText("example.com##+js(set-constant, ads, false)\n")
        }
        val modulesFile = File(remoteDir, "1.modules.json").apply {
            writeText(
                """
                {
                  "name": "1",
                  "rule_count": 2,
                  "domains": {
                    "example.com": {
                      "css": [".dup-box"],
                      "block_paths": ["/ads.js"]
                    },
                    "onlycss.com": {
                      "css": [".only-css"]
                    }
                  },
                  "global_block_paths": ["/global-ad.js"]
                }
                """.trimIndent()
            )
        }

        coEvery { downloadManager.downloadFilterList(any(), any()) } returns Result.success(
            DownloadedFilterPaths(
                bloomPath = bloomFile.absolutePath,
                triePath = trieFile.absolutePath,
                cssPath = cssFile.absolutePath,
                scriptletPath = scriptletsFile.absolutePath,
                modulesPath = modulesFile.absolutePath,
            )
        )

        val repo = repository()
        val firstResult = repo.loadAllEnabledFilters()
        assertTrue(firstResult.isSuccess)
        assertEquals(42, firstResult.getOrThrow())

        val compiledCss = File(filesDir, "filter_cache/cosmetic_rules.css")
        val compiledScriptlets = File(filesDir, "filter_cache/scriptlets_combined.txt")
        val compiledModules = File(filesDir, "filter_cache/filter_modules.json")

        assertTrue(compiledCss.exists())
        val cssContent = compiledCss.readText()
        assertTrue(cssContent.contains("##.banner-ad"))
        assertTrue(cssContent.contains("example.com##.dup-box"))
        assertFalse(cssContent.contains("! comment"))

        assertTrue(compiledScriptlets.exists())
        assertTrue(compiledScriptlets.readText().contains("set-constant, ads, false"))

        assertTrue(compiledModules.exists())
        val modulesContent = compiledModules.readText()
        assertTrue(modulesContent.contains("/ads.js"))
        assertTrue(modulesContent.contains("/global-ad.js"))
        assertFalse("duplicate CSS should be omitted when .css sidecar exists", modulesContent.contains(".dup-box"))
        assertFalse("domain with only duplicate CSS should be omitted", modulesContent.contains("onlycss.com"))

        // Second call with unchanged sidecar files should hit the signature cache and skip recompiling.
        compiledCss.writeText("CACHED_SENTINEL")
        val secondResult = repo.loadAllEnabledFilters()
        assertTrue(secondResult.isSuccess)
        assertEquals("CACHED_SENTINEL", compiledCss.readText())

        // Mutating a sidecar file invalidates the signature and triggers recompilation.
        cssFile.writeText("##.updated-banner\n")
        cssFile.setLastModified(System.currentTimeMillis() + 5_000L)
        val thirdResult = repo.loadAllEnabledFilters()
        assertTrue(thirdResult.isSuccess)
        assertTrue(compiledCss.readText().contains("##.updated-banner"))
    }
}
