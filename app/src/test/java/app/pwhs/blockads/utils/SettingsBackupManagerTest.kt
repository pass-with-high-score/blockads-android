package app.pwhs.blockads.utils

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.ConfigDao
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ConfigProfile
import app.pwhs.blockads.data.entities.ConfigProfileBackup
import app.pwhs.blockads.data.entities.SettingsBackup
import app.pwhs.blockads.data.repository.FilterListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import app.pwhs.blockads.worker.DailySummaryScheduler
import org.junit.After

@RunWith(RobolectricTestRunner::class)
class SettingsBackupManagerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val appPrefs: AppPreferences = mockk(relaxed = true)
    private val filterListDao: FilterListDao = mockk(relaxed = true)
    private val whitelistDomainDao: WhitelistDomainDao = mockk(relaxed = true)
    private val customDnsRuleDao: CustomDnsRuleDao = mockk(relaxed = true)
    private val firewallRuleDao: FirewallRuleDao = mockk(relaxed = true)
    private val configDao: ConfigDao = mockk(relaxed = true)
    private val filterRepo: FilterListRepository = mockk(relaxed = true)

    private lateinit var manager: SettingsBackupManager

    @Before
    fun setUp() {
        mockkObject(DailySummaryScheduler)
        every { DailySummaryScheduler.scheduleDailySummary(any()) } returns Unit
        every { DailySummaryScheduler.cancelDailySummary(any()) } returns Unit

        coEvery { appPrefs.upstreamDns } returns flowOf("1.1.1.1")
        coEvery { appPrefs.fallbackDns } returns flowOf("8.8.8.8")
        coEvery { appPrefs.autoReconnect } returns flowOf(true)
        coEvery { appPrefs.themeMode } returns flowOf(AppPreferences.THEME_SYSTEM)
        coEvery { appPrefs.appLanguage } returns flowOf(AppPreferences.LANGUAGE_SYSTEM)
        coEvery { appPrefs.safeSearchEnabled } returns flowOf(false)
        coEvery { appPrefs.youtubeRestrictedMode } returns flowOf(false)
        coEvery { appPrefs.dailySummaryEnabled } returns flowOf(true)
        coEvery { appPrefs.milestoneNotificationsEnabled } returns flowOf(false)
        coEvery { appPrefs.firewallEnabled } returns flowOf(false)
        coEvery { appPrefs.dnsProviderId } returns flowOf("cloudflare")
        coEvery { whitelistDomainDao.getAll() } returns flowOf(emptyList())
        coEvery { customDnsRuleDao.getAll() } returns emptyList()
        coEvery { filterListDao.getEnabled() } returns emptyList()

        manager = SettingsBackupManager(
            context = app,
            appPrefs = appPrefs,
            filterListDao = filterListDao,
            whitelistDomainDao = whitelistDomainDao,
            customDnsRuleDao = customDnsRuleDao,
            firewallRuleDao = firewallRuleDao,
            configDao = configDao,
            filterRepo = filterRepo
        )
    }

    @Test
    fun testExportIncludesConfigProfiles() = runTest {
        val testProfile = ConfigProfile(
            id = 1,
            name = "Test Profile",
            content = "[filter_local]\nhost, ad.com, reject",
            isActive = true,
            icon = "gaming"
        )
        coEvery { configDao.getAll() } returns listOf(testProfile)

        val tempFile = File(app.cacheDir, "test_backup.json")
        val uri = Uri.fromFile(tempFile)

        manager.exportBackup(uri)

        val jsonContent = tempFile.readText()
        assertTrue(jsonContent.contains("\"name\": \"Test Profile\""))
        assertTrue(jsonContent.contains("\"configProfiles\""))

        val backup = Json { ignoreUnknownKeys = true }.decodeFromString<SettingsBackup>(jsonContent)
        assertEquals(1, backup.configProfiles.size)
        assertEquals("Test Profile", backup.configProfiles[0].name)
        assertEquals("gaming", backup.configProfiles[0].icon)
        assertTrue(backup.configProfiles[0].isActive)
    }

    @Test
    fun testImportRestoresConfigProfilesAndSetsActive() = runTest {
        val backup = SettingsBackup(
            upstreamDns = "1.1.1.1",
            fallbackDns = "8.8.8.8",
            configProfiles = listOf(
                ConfigProfileBackup(
                    name = "Imported Profile",
                    content = "[filter_local]\nhost, imported.ad, reject\n[rewrite_local]\nignored",
                    isActive = true,
                    icon = "rocket"
                )
            )
        )
        val jsonContent = Json.encodeToString(SettingsBackup.serializer(), backup)
        val tempFile = File(app.cacheDir, "import_test.json")
        tempFile.writeText(jsonContent)
        val uri = Uri.fromFile(tempFile)

        coEvery { configDao.getAll() } returns emptyList()
        coEvery { configDao.insert(any()) } returns 10L

        manager.importBackup(uri)

        coVerify {
            configDao.insert(match {
                it.name == "Imported Profile" &&
                        it.icon == "rocket" &&
                        it.content.contains("host, imported.ad, reject") &&
                        !it.content.contains("[rewrite_local]")
            })
        }
        coVerify {
            configDao.setActive(10L)
        }
    }

    @Test
    fun testImportBackwardCompatibleWithoutProfiles() = runTest {
        val backup = SettingsBackup(
            upstreamDns = "9.9.9.9",
            fallbackDns = "149.112.112.112",
            configProfiles = emptyList()
        )
        val jsonContent = Json.encodeToString(SettingsBackup.serializer(), backup)
        val tempFile = File(app.cacheDir, "legacy_import.json")
        tempFile.writeText(jsonContent)
        val uri = Uri.fromFile(tempFile)

        val existingActive = ConfigProfile(
            id = 1,
            name = "Default",
            content = "[dns]\nserver = 1.1.1.1\n[filter_local]\nfinal, direct",
            isActive = true
        )
        coEvery { configDao.getActive() } returns existingActive

        manager.importBackup(uri)

        coVerify {
            configDao.update(match {
                it.id == 1L && it.content.contains("server = 9.9.9.9")
            })
        }
    }

    @After
    fun tearDown() {
        unmockkObject(DailySummaryScheduler)
    }
}
