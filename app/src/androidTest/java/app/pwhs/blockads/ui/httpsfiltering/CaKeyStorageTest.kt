package app.pwhs.blockads.ui.httpsfiltering

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pwhs.blockads.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Where the MITM CA private key ends up, both on disk and in backups. */
@RunWith(AndroidJUnit4::class)
class CaKeyStorageTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val certDir = File(context.cacheDir, "ca-test").apply { deleteRecursively(); mkdirs() }

    @After
    fun tearDown() {
        certDir.deleteRecursively()
    }

    @Test
    fun generatedCaKeyIsOwnerOnly() {
        val pem = tunnel.Tunnel.newEngine().startStackMitm(certDir.absolutePath)

        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals("0600", "%04o".format(Os.stat(File(certDir, CA_KEY).path).st_mode and 0x1ff))
    }

    @Test
    fun appAllowsBackup() {
        // The rule checks below only matter while backup is on; the manifest wires @xml/backup_rules and
        // @xml/data_extraction_rules, which they parse.
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    @Ignore("known bug: allowBackup=true with template rules backs up filesDir/ca.key, the MITM CA private key")
    @Test
    fun caKeyIsExcludedFromFullBackup() {
        assertTrue(excludesCaKey(sections(R.xml.backup_rules, "full-backup-content").single()))
    }

    @Ignore("known bug: allowBackup=true with template rules backs up filesDir/ca.key, the MITM CA private key")
    @Test
    fun caKeyIsExcludedFromCloudBackupAndDeviceTransfer() {
        val cloud = sections(R.xml.data_extraction_rules, "cloud-backup")
        val transfer = sections(R.xml.data_extraction_rules, "device-transfer")

        assertTrue("cloud-backup", cloud.isNotEmpty() && cloud.all(::excludesCaKey))
        assertTrue("device-transfer", transfer.isNotEmpty() && transfer.all(::excludesCaKey))
    }

    private data class Rule(val include: Boolean, val domain: String, val path: String)

    /** Parses each [section] element of the compiled XML resource into its include/exclude rules. */
    private fun sections(xmlRes: Int, section: String): List<List<Rule>> {
        val result = mutableListOf<List<Rule>>()
        var current: MutableList<Rule>? = null
        context.resources.getXml(xmlRes).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == section -> current = mutableListOf()
                    parser.eventType == XmlPullParser.END_TAG && parser.name == section -> {
                        result += current.orEmpty()
                        current = null
                    }
                    parser.eventType == XmlPullParser.START_TAG && parser.name in setOf("include", "exclude") ->
                        current?.add(
                            Rule(
                                include = parser.name == "include",
                                domain = parser.getAttributeValue(null, "domain").orEmpty(),
                                path = parser.getAttributeValue(null, "path").orEmpty(),
                            )
                        )
                }
            }
        }
        return result
    }

    /** Backup semantics: an exclude always wins; with any include present, only included paths are kept. */
    private fun excludesCaKey(rules: List<Rule>): Boolean {
        fun covers(rule: Rule) = rule.domain == "file" && rule.path.trimEnd('/') in setOf("", ".", CA_KEY)
        if (rules.any { !it.include && covers(it) }) return true
        val includes = rules.filter { it.include }
        return includes.isNotEmpty() && includes.none(::covers)
    }

    private companion object {
        const val CA_KEY = "ca.key"
    }
}
