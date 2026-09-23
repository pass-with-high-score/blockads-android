package app.pwhs.blockads.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun clientServing(body: ByteArray, status: HttpStatusCode = HttpStatusCode.OK) =
        HttpClient(MockEngine { respond(body, status) })

    private suspend fun extractExpectingFailure(client: HttpClient, dest: File) {
        try {
            ZipUtils.downloadAndExtractZip(client, "https://example.test/f.zip", dest)
            fail("expected ZipExtractionException")
        } catch (_: ZipExtractionException) {
        }
    }

    @Test
    fun `extracts a well-formed archive`() = runTest {
        val dest = File(tempFolder.root, "filter")
        val files = ZipUtils.downloadAndExtractZip(
            clientServing(zipOf("info.json" to "{}", "a.trie" to "t")),
            "https://example.test/f.zip",
            dest
        )
        assertEquals(setOf("info.json", "a.trie"), files.map { it.name }.toSet())
    }

    @Ignore("known bug: zip-slip check lacks a trailing separator")
    @Test
    fun `rejects an entry escaping into a sibling directory sharing the prefix`() = runTest {
        val dest = File(tempFolder.root, "filter")
        val sibling = File(tempFolder.root, "filter-evil/pwned.txt")

        extractExpectingFailure(clientServing(zipOf("../filter-evil/pwned.txt" to "x")), dest)

        assertFalse("zip-slip wrote outside destDir: $sibling", sibling.exists())
    }

    @Ignore("known bug: HTTP error responses are extracted")
    @Test
    fun `rejects a non-2xx response even when its body is a zip`() = runTest {
        val dest = File(tempFolder.root, "filter")
        extractExpectingFailure(
            clientServing(zipOf("info.json" to "{}"), HttpStatusCode.NotFound),
            dest
        )
    }

    @Ignore("known bug: failed extraction deletes the whole pre-existing destDir")
    @Test
    fun `failed extraction leaves pre-existing files in destDir alone`() = runTest {
        val dest = tempFolder.newFolder("filter")
        val existing = File(dest, "keep.trie").apply { writeText("cached") }

        extractExpectingFailure(clientServing(ByteArray(0)), dest)

        assertTrue("cleanup deleted a file it did not extract", existing.exists())
    }
}
