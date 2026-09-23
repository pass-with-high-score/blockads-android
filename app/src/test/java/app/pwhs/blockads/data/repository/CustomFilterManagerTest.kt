package app.pwhs.blockads.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.remote.api.BuildResponse
import app.pwhs.blockads.data.remote.api.CustomFilterApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class CustomFilterManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private val api = mockk<CustomFilterApi>()
    private val listUrl = "https://lists.example/mine.txt"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        coEvery { api.buildFilter(any()) } returns BuildResponse("https://cdn.example/mine.zip", 3, 0)
    }

    @After
    fun tearDown() = db.close()

    private fun zip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (name in listOf("mine.trie", "mine.bloom")) {
                z.putNextEntry(ZipEntry(name))
                z.write("data".toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // The raw list URL always 404s, so any path that reaches the compiler has ignored the status.
    private fun manager() = CustomFilterManager(
        context = context,
        client = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith(".zip")) respond(zip(), HttpStatusCode.OK)
                else respondError(HttpStatusCode.NotFound, "<html>Not Found</html>")
            }
        ),
        filterListDao = db.filterListDao(),
        customFilterApi = api,
    )

    @Ignore("known bug: a failure after insert leaves a partial filter row")
    @Test
    fun `a failure after the insert leaves no filter row behind`() = runTest {
        // A non-empty directory where the trie goes makes the copy fail after the row exists.
        File(context.filesDir, "remote_filters/1.trie/blocker").apply { parentFile!!.mkdirs(); writeText("x") }

        val outcome = runCatching { manager().addCustomFilter(listUrl) }

        assertEquals(emptyList<String>(), db.filterListDao().getAllSync().map { "${it.id}:${it.trieUrl}" })
        assertEquals("threw ${outcome.exceptionOrNull()}", true, outcome.getOrNull()?.isFailure)
    }

    @Ignore("known bug: local compile compiles HTTP error pages")
    @Test
    fun `local compile refuses an HTTP error page`() = runTest {
        val outcome = runCatching { manager().addCustomFilterLocally(listUrl, null) }

        assertTrue("threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertTrue(outcome.getOrThrow().isFailure)
        assertEquals(0, db.filterListDao().getAllSync().size)
    }
}
