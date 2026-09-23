package app.pwhs.blockads.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppNameResolverTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var resolver: AppNameResolver

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    private fun row(port: String, uid: Int) =
        "  0: 0100007F:$port 00000000:0000 07 00000000:00000000 00:00000000 00000000 $uid 0 1"

    @Before
    fun setUp() {
        resolver = AppNameResolver(context)
    }

    private fun install(packageName: String, label: String, uid: Int) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    this.uid = uid
                    nonLocalizedLabel = label
                }
            }
        )
        pm.setPackagesForUid(uid, packageName)
    }

    @Test
    fun `app uid resolves to its label and package`() {
        install("com.example.browser", "Browser", 10123)
        assertEquals("Browser", resolver.getAppNameForUid(10123))
        assertEquals("com.example.browser", resolver.getPackageNameForUid(10123))
    }

    @Test
    fun `system uids without packages get descriptive names`() {
        assertEquals("System (root)", resolver.getAppNameForUid(0))
        assertEquals("Android System", resolver.getAppNameForUid(1000))
        assertEquals("System (1051)", resolver.getAppNameForUid(1051))
        assertEquals("", resolver.getAppNameForUid(10500))
        assertEquals("", resolver.getPackageNameForUid(10500))
    }

    @Test
    fun `uninstalled package falls back to the package name`() {
        shadowOf(context.packageManager).setPackagesForUid(10321, "com.example.ghost")
        assertEquals("com.example.ghost", resolver.getAppNameForUid(10321))
    }

    @Test
    fun `names are cached per uid`() {
        install("com.example.one", "One", 10200)
        assertEquals("One", resolver.getAppNameForUid(10200))
        assertEquals("com.example.one", resolver.getPackageNameForUid(10200))
        shadowOf(context.packageManager).setPackagesForUid(10200, "com.example.two")
        assertEquals("One", resolver.getAppNameForUid(10200))
        assertEquals("com.example.one", resolver.getPackageNameForUid(10200))
    }

    private fun resolverWithOwner(owner: () -> Int): AppNameResolver {
        val cm = mockk<ConnectivityManager> {
            every { getConnectionOwnerUid(any(), any(), any()) } answers { owner() }
        }
        return AppNameResolver(object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) cm else super.getSystemService(name)
        })
    }

    private val src = byteArrayOf(10, 0, 0, 2)
    private val dst = byteArrayOf(10, 0, 0, 1)

    @Test
    fun `connection owner uid resolves to the app identity`() {
        install("com.example.chat", "Chat", 10777)
        val r = resolverWithOwner { 10777 }
        assertEquals("Chat", r.resolve(40000, src, dst, 53))
        assertEquals(AppNameResolver.AppIdentity("Chat", "com.example.chat"), r.resolveIdentity(40000, src, dst, 53))
    }

    @Test
    fun `unknown owner yields an empty identity`() {
        val r = resolverWithOwner { -1 }
        assertEquals("", r.resolve(40000, src, dst, 53))
        assertEquals(AppNameResolver.AppIdentity("", ""), r.resolveIdentity(40000, src, dst, 53))
    }

    @Test
    fun `owner lookup failure falls back without throwing`() {
        val r = resolverWithOwner { throw SecurityException("denied") }
        r.stopSnapshotter()
        assertEquals("", r.resolve(40000, src, dst, 53))
    }

    @Test
    fun `findUidInProcFile matches the port case-insensitively after the header`() {
        val file = File(tempFolder.root, "udp").apply {
            writeText(listOf(header, row("0035", 10123), row("d431", 10456)).joinToString("\n"))
        }
        assertEquals(10456, resolver.findUidInProcFile(file.path, "D431"))
        assertEquals(10123, resolver.findUidInProcFile(file.path, "0035"))
        assertNull(resolver.findUidInProcFile(file.path, "0036"))
    }

    @Test
    fun `findUidInProcFile skips the header, malformed rows and missing files`() {
        val file = File(tempFolder.root, "udp6").apply {
            writeText(listOf(row("0035", 1), "junk", "  1: noport 0 0 0 0 0 0 7 0", row("0035", 2)).joinToString("\n"))
        }
        val empty = File(tempFolder.root, "empty").apply { writeText("") }
        assertEquals(2, resolver.findUidInProcFile(file.path, "0035"))
        assertNull(resolver.findUidInProcFile(empty.path, "0035"))
        assertNull(resolver.findUidInProcFile(File(tempFolder.root, "missing").path, "0035"))
    }
}
