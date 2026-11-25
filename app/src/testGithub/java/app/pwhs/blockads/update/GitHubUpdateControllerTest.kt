package app.pwhs.blockads.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GitHubUpdateControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var activity: Activity
    private lateinit var prefs: FakeUpdatePreferencesStore

    private val apkBytes = ByteArray(1024) { (it % 127).toByte() }
    private val assetName = "app-github-universal-release.apk"
    private val releaseTag = "v99.0.0"
    private val downloadUrl = "https://github.com/example/releases/download/$releaseTag/$assetName"

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("cache")
        val pm = mockk<PackageManager>(relaxed = true) {
            every { canRequestPackageInstalls() } returns false
        }
        context = mockk(relaxed = true) {
            every { this@mockk.cacheDir } returns this@GitHubUpdateControllerTest.cacheDir
            every { packageManager } returns pm
            every { packageName } returns "app.pwhs.blockads"
        }
        activity = mockk(relaxed = true)
        prefs = FakeUpdatePreferencesStore()
    }

    private fun releaseJson(sizeBytes: Long = apkBytes.size.toLong()): String = """
        [
          {
            "tag_name": "$releaseTag",
            "name": "$releaseTag",
            "body": "• Sample release notes",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-11-25T10:00:00Z",
            "html_url": "https://github.com/example/releases/tag/$releaseTag",
            "assets": [
              {
                "name": "$assetName",
                "browser_download_url": "$downloadUrl",
                "size": $sizeBytes
              }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `auto check exposes banner without opening dialog, manual check opens dialog`() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                content = releaseJson(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val controller = GitHubUpdateController(context, client, prefs)

        controller.checkForUpdate(UpdateChannel.STABLE, manual = false)
        assertTrue(controller.state.value is UpdateState.Available)
        assertFalse(controller.dialogVisible.value)
        assertFalse(controller.bannerDismissed.value)

        controller.checkForUpdate(UpdateChannel.STABLE, manual = true)
        assertTrue(controller.state.value is UpdateState.Available)
        assertTrue(controller.dialogVisible.value)
    }

    @Test
    fun `checkForUpdate transitions directly to Downloaded and startUpdate skips download when APK is cached`() = runBlocking {
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val cachedFile = File(updatesDir, "${releaseTag}__$assetName").apply {
            writeBytes(apkBytes)
        }

        var downloadRequestCount = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.toString() == downloadUrl) {
                downloadRequestCount++
                respond(content = apkBytes, status = HttpStatusCode.OK)
            } else {
                respond(
                    content = releaseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        })
        val controller = GitHubUpdateController(context, client, prefs)

        controller.checkForUpdate(UpdateChannel.STABLE, manual = false)
        val state = controller.state.value
        assertTrue(state is UpdateState.Downloaded)
        assertEquals(cachedFile.absolutePath, (state as UpdateState.Downloaded).filePath)
        assertEquals(releaseTag, state.info?.tagName)
        assertFalse(controller.dialogVisible.value)

        controller.startUpdate(activity)
        assertEquals(0, downloadRequestCount)
    }

    @Test
    fun `partial cached APK is ignored and downloaded once then reused on subsequent startUpdate`() = runBlocking {
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        File(updatesDir, "${releaseTag}__$assetName").writeBytes(ByteArray(128))

        var downloadRequestCount = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.toString() == downloadUrl) {
                downloadRequestCount++
                respond(
                    content = apkBytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, apkBytes.size.toString()),
                )
            } else {
                respond(
                    content = releaseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        })
        val controller = GitHubUpdateController(context, client, prefs)

        controller.checkForUpdate(UpdateChannel.STABLE, manual = true)
        assertTrue(controller.state.value is UpdateState.Available)

        controller.startUpdate(activity)
        assertEquals(1, downloadRequestCount)
        assertTrue(controller.state.value is UpdateState.Downloaded)

        // Calling startUpdate again must reuse the downloaded file without re-downloading.
        controller.startUpdate(activity)
        assertEquals(1, downloadRequestCount)
    }

    @Test
    fun `dismissUpdate with Later keeps banner state, Skip persists version and clears state`() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                content = releaseJson(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val controller = GitHubUpdateController(context, client, prefs)

        controller.checkForUpdate(UpdateChannel.STABLE, manual = true)
        assertTrue(controller.dialogVisible.value)

        controller.dismissUpdate(skipVersion = false)
        assertFalse(controller.dialogVisible.value)
        assertTrue(controller.state.value is UpdateState.Available)

        controller.dismissUpdate(skipVersion = true)
        assertEquals(releaseTag, prefs.getSkippedUpdateVersion())
        assertEquals(UpdateState.Idle, controller.state.value)
    }

    private class FakeUpdatePreferencesStore : UpdatePreferencesStore {
        private val channelFlow = MutableStateFlow<String?>(null)
        private var lastCheckMs: Long = 0L
        private var skippedVersion: String = ""

        override val updateChannel: Flow<String?> = channelFlow
        override suspend fun getUpdateChannelSnapshot(): String? = channelFlow.value
        override suspend fun setUpdateChannel(channel: String) {
            channelFlow.value = channel
        }
        override suspend fun getLastUpdateCheckMs(): Long = lastCheckMs
        override suspend fun setLastUpdateCheckMs(timestampMs: Long) {
            lastCheckMs = timestampMs
        }
        override suspend fun getSkippedUpdateVersion(): String = skippedVersion
        override suspend fun setSkippedUpdateVersion(version: String) {
            skippedVersion = version
        }
    }
}
