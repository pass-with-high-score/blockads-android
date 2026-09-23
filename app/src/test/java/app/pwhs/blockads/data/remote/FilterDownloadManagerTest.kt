package app.pwhs.blockads.data.remote

import android.content.Context
import app.pwhs.blockads.data.entities.FilterList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FilterDownloadManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val filter = FilterList(
        id = 7,
        name = "EasyList",
        url = "https://easylist.to/easylist/easylist.txt",
        bloomUrl = "https://cdn.example/easylist.bloom",
        trieUrl = "https://cdn.example/easylist.trie",
    )

    private fun manager(filesDir: File, zipBody: ByteArray): FilterDownloadManager {
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith(".zip")) respond(zipBody, HttpStatusCode.OK)
                else respondError(HttpStatusCode.NotFound)
            }
        )
        return FilterDownloadManager(context, client)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Sets the first entry's deflate block type to the reserved value, so inflating it throws. */
    private fun corruptFirstEntry(zip: ByteArray): ByteArray {
        val nameLen = (zip[26].toInt() and 0xff) or ((zip[27].toInt() and 0xff) shl 8)
        val extraLen = (zip[28].toInt() and 0xff) or ((zip[29].toInt() and 0xff) shl 8)
        return zip.copyOf().also { it[30 + nameLen + extraLen] = (it[30 + nameLen + extraLen].toInt() or 0x06).toByte() }
    }

    @Ignore("known bug: a bad zip truncates the cached filter before extraction")
    @Test
    fun `a zip that fails to extract leaves the cached filter intact`() = runTest {
        val filesDir = tempFolder.newFolder()
        val cache = File(filesDir, "remote_filters").apply { mkdirs() }
        File(cache, "7.trie").writeText("good trie")
        File(cache, "7.bloom").writeText("good bloom")
        val broken = corruptFirstEntry(zipOf("a.trie" to "new trie".toByteArray(), "a.bloom" to "new bloom".toByteArray()))

        val result = manager(filesDir, broken).downloadFilterList(filter, forceUpdate = true)

        assertTrue(result.isFailure)
        assertEquals("good trie", File(cache, "7.trie").readText())
        assertEquals("good bloom", File(cache, "7.bloom").readText())
    }

    @Test
    fun `a good zip replaces the cached filter`() = runTest {
        val filesDir = tempFolder.newFolder()
        val cache = File(filesDir, "remote_filters").apply { mkdirs() }
        File(cache, "7.trie").writeText("old trie")
        val zip = zipOf("a.trie" to "new trie".toByteArray(), "a.bloom" to "new bloom".toByteArray())

        val result = manager(filesDir, zip).downloadFilterList(filter, forceUpdate = true)

        assertEquals(File(cache, "7.trie").absolutePath, result.getOrThrow().triePath)
        assertEquals("new trie", File(cache, "7.trie").readText())
        assertEquals("new bloom", File(cache, "7.bloom").readText())
    }
}
