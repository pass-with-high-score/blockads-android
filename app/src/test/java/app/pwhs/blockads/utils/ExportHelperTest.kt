package app.pwhs.blockads.utils

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExportHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `resolveTargetUri with null returns downloads uri`() {
        val uri = ExportHelper.resolveTargetUri(context, null, "test.txt", "text/plain")
        assertNotNull(uri)
    }

    @Test
    fun `resolveTargetUri with file uri returns same uri`() {
        val file = tempFolder.newFile("test_target.txt")
        val fileUri = Uri.fromFile(file)
        val resolved = ExportHelper.resolveTargetUri(context, fileUri, "fallback.txt", "text/plain")
        assertEquals(fileUri, resolved)
    }

    @Test
    fun `writeTextToUri writes content to file uri`() {
        val file = tempFolder.newFile("write_test.txt")
        val fileUri = Uri.fromFile(file)
        ExportHelper.writeTextToUri(context, fileUri, "Hello BlockAds")

        assertEquals("Hello BlockAds", file.readText())
    }

    @Test
    fun `copyFileToUri copies source file to target uri`() {
        val src = tempFolder.newFile("source.txt").apply { writeText("Sample source content") }
        val dest = tempFolder.newFile("dest.txt")
        val destUri = Uri.fromFile(dest)

        ExportHelper.copyFileToUri(context, src, destUri)
        assertEquals("Sample source content", dest.readText())
    }

    @Test
    fun `resolveDownloadsUri returns valid uri`() {
        val uri = ExportHelper.resolveDownloadsUri(context, "sample_download.txt", "text/plain")
        assertNotNull(uri)
    }
}
