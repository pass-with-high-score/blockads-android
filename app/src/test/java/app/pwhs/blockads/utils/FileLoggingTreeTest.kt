package app.pwhs.blockads.utils

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileLoggingTreeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logDir = File(context.cacheDir, "logs")
    private val logFile = File(logDir, "blockads_logs.txt")
    private val oldFile = File(logDir, "blockads_logs_old.txt")

    @After
    fun tearDown() = Timber.uprootAll()

    private fun plant() = Timber.plant(FileLoggingTree(context))

    @Test
    fun `lines carry timestamp, priority, tag and thread`() {
        plant()
        val priorities = listOf(Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT, 99)
        priorities.forEach { Timber.tag("Tag").log(it, "msg$it") }
        Timber.i("untagged")

        val lines = logFile.readLines()
        val thread = Thread.currentThread().name
        assertEquals(listOf("V", "D", "I", "W", "E", "WTF", "?"), lines.take(7).map { it.substringAfter(' ').substringAfter(' ').substringBefore('/') })
        val header = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} I/\[Tag] <\Q$thread\E>: msg4""")
        assertTrue(lines[2], header.matches(lines[2]))
        assertTrue(lines.last(), lines.last().contains("I/[FileLoggingTreeTest]") && lines.last().endsWith(": untagged"))
    }

    @Test
    fun `throwables are appended with their stack trace`() {
        plant()
        Timber.tag("T").e(IllegalStateException("kaboom"), "failed")
        val text = logFile.readText()
        assertTrue(text.contains(": failed\n"))
        assertTrue(text.contains("java.lang.IllegalStateException: kaboom"))
    }

    @Test
    fun `a log over 5 MB rotates to the old file and replaces the previous backup`() {
        logDir.mkdirs()
        oldFile.writeText("previous backup")
        val big = ByteArray(5 * 1024 * 1024 + 1) { 'x'.code.toByte() }
        logFile.writeBytes(big)

        plant()
        Timber.tag("T").i("fresh")

        assertEquals(big.size.toLong(), oldFile.length())
        assertTrue(logFile.readText().endsWith(": fresh\n"))
        assertTrue(logFile.length() < 1024)
    }

    @Test
    fun `logs at exactly 5 MB are not rotated`() {
        logDir.mkdirs()
        logFile.writeBytes(ByteArray(5 * 1024 * 1024))
        plant()
        Timber.tag("T").i("tail")
        assertFalse(oldFile.exists())
        assertTrue(logFile.length() > 5L * 1024 * 1024)
    }
}
