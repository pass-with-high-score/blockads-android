package app.pwhs.blockads.ui.settings

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.ui.event.UiEvent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Export and import through [SettingsViewModel], the only backup code path today. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var prefs: AppPreferences
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        prefs = AppPreferences(app)
        vm = newViewModel(app, profileManager = mockk(relaxed = true))
    }

    private fun newViewModel(app: Application, profileManager: app.pwhs.blockads.data.entities.ProfileManager) =
        SettingsViewModel(
            appPrefs = prefs,
            filterRepo = mockk(relaxed = true),
            dnsLogDao = db.dnsLogDao(),
            whitelistDomainDao = db.whitelistDomainDao(),
            filterListDao = db.filterListDao(),
            customDnsRuleDao = db.customDnsRuleDao(),
            profileDao = db.protectionProfileDao(),
            profileManager = profileManager,
            firewallRuleDao = db.firewallRuleDao(),
            application = app,
        )

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun awaitEvent(action: () -> Unit): UiEvent = runBlocking {
        val event = async(Dispatchers.Unconfined) { withTimeout(10_000) { vm.events.first() } }
        action()
        event.await()
    }

    private fun export(): JsonObject {
        val file = tempFolder.newFile("backup.json")
        awaitEvent { vm.exportSettings(Uri.fromFile(file)) }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Ignore("known bug: needs decision on backup scope and format versioning")
    @Test
    fun `export carries the user's DNS and firewall settings`() {
        runBlocking {
            prefs.setDnsProtocol(DnsProtocol.DOH)
            prefs.setDohUrl("https://dns.example/dns-query")
            prefs.setDnsResponseType(AppPreferences.DNS_RESPONSE_NXDOMAIN)
            db.firewallRuleDao().insert(FirewallRule(packageName = "com.paused.app", isEnabled = false))
        }

        val json = export()

        val gaps = buildList {
            if (json["dnsProtocol"]?.jsonPrimitive?.content != "DOH") add("dnsProtocol")
            if (json["dohUrl"]?.jsonPrimitive?.content != "https://dns.example/dns-query") add("dohUrl")
            if (json["dnsResponseType"]?.jsonPrimitive?.content != AppPreferences.DNS_RESPONSE_NXDOMAIN) {
                add("dnsResponseType")
            }
            val pkgs = json["firewallRules"]?.jsonArray?.map { it.jsonObject["packageName"]?.jsonPrimitive?.content }
            if (pkgs?.contains("com.paused.app") != true) add("disabled firewall rule")
        }
        assertEquals("settings missing from the backup", emptyList<String>(), gaps)
    }

    @Ignore("known bug: needs decision on transactional import strategy")
    @Test
    fun `an import that fails partway leaves settings unchanged`() {
        val failing = mockk<app.pwhs.blockads.data.entities.ProfileManager> {
            coEvery { saveActiveProfileFilterUrls() } throws IllegalStateException("late failure")
        }
        vm = newViewModel(ApplicationProvider.getApplicationContext(), failing)
        runBlocking { prefs.setUpstreamDns("9.9.9.9") }
        val file = tempFolder.newFile("incoming.json").apply { writeText("""{"upstreamDns":"1.1.1.1"}""") }

        val event = awaitEvent { vm.importSettings(Uri.fromFile(file)) } as UiEvent.ToastRes

        assertEquals(listOf("late failure"), event.args)
        assertEquals("9.9.9.9", runBlocking { prefs.upstreamDns.first() })
    }
}
