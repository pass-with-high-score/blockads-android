package app.pwhs.blockads.ui.wireguard

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.settle
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WireGuardImportViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var fake = FakeWireGuardPrefs()

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun newVm(prefs: FakeWireGuardPrefs = fake): WireGuardImportViewModel {
        fake = prefs
        startKoin { modules(module { single { prefs.prefs } }) }
        return WireGuardImportViewModel(app)
    }

    private val validConf = """
        [Interface]
        PrivateKey = $KEY_A
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = $KEY_B
        Endpoint = vpn.example:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    private fun conf(text: String, name: String = "office.conf"): Uri {
        val file = File(tempFolder.newFolder(), name)
        file.writeText(text)
        return Uri.fromFile(file)
    }

    @Test
    fun `init reflects stored profiles and settings, defaulting the active id to the first`() {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"), wgProfile("b"))).apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        assertEquals(listOf("a", "b"), vm.profiles.value.map { it.id })
        assertEquals("a", vm.activeProfileId.value)
        assertTrue(vm.isWgActive.value)
        assertEquals("corp.example", vm.splitDnsZones.value)
        assertTrue(vm.excludeLan.value)
        assertFalse(vm.allowAppBypass.value)
    }

    @Test
    fun `the first import becomes active and is named from the fallback`() = runTest {
        val vm = newVm()
        vm.events.test {
            vm.settle { importFromUri(conf(validConf), fallbackName = "Office") }
            assertEquals(WireGuardUiEvent.ProfileImported("Office"), awaitItem())
        }
        assertEquals(listOf("Office"), fake.profiles.value.map { it.name })
        assertEquals(fake.profiles.value.single().id, fake.activeId.value)
        assertFalse(vm.isLoading.value)
        verify(exactly = 0) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `a later import does not steal the active slot`() {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a")), activeId = "a"))
        vm.settle { importFromUri(conf(validConf), fallbackName = "Second") }
        assertEquals(2, fake.profiles.value.size)
        assertEquals("a", fake.activeId.value)
    }

    @Test
    fun `importing the first profile while WireGuard is on restarts the tunnel`() {
        val vm = newVm(FakeWireGuardPrefs().apply { routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD })
        vm.settle { importFromUri(conf(validConf), fallbackName = "x") }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `without a fallback name the next free Tunnel N is used`() {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a", "Tunnel 1"))))
        vm.settle { importFromUri(conf(validConf)) }
        assertEquals(listOf("Tunnel 1", "Tunnel 2"), fake.profiles.value.map { it.name })
    }

    @Test
    fun `an invalid config stores nothing and surfaces the parser message`() {
        val vm = newVm()
        vm.settle { importFromUri(conf("[Interface]\nAddress = 10.0.0.2/32\n")) }
        assertTrue(vm.error.value!!.contains("PrivateKey"))
        assertTrue(fake.profiles.value.isEmpty())
        assertNull(fake.activeId.value)
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun `empty and unreadable files are reported`() {
        val vm = newVm()
        vm.settle { importFromUri(conf("  \n")) }
        assertEquals("File is empty", vm.error.value)

        vm.settle { importFromUri(Uri.fromFile(File(tempFolder.root, "missing.conf"))) }
        assertTrue(vm.error.value!!.isNotBlank())
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `activating a profile announces it and restarts only when WireGuard is on`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"), wgProfile("b", "Bravo"))))
        vm.events.test {
            vm.setActiveProfile("b")
            assertEquals(WireGuardUiEvent.ProfileActivated("Bravo"), awaitItem())
        }
        assertEquals("b", vm.activeProfileId.value)
        verify(exactly = 0) { ServiceController.requestRestart(any()) }

        vm.setActiveProfile("missing")
        coVerify(exactly = 1) { fake.prefs.setActiveWgProfile(any()) }
    }

    @Test
    fun `activating while WireGuard is on restarts`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"), wgProfile("b"))).apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        vm.events.test {
            vm.setActiveProfile("b")
            awaitItem()
        }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `rename trims and ignores blank names`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"))))
        vm.renameProfile("a", "   ")
        vm.events.test {
            vm.renameProfile("a", "  Home  ")
            assertEquals(WireGuardUiEvent.ProfileRenamed, awaitItem())
        }
        assertEquals("Home", fake.profiles.value.single().name)
    }

    @Test
    fun `deleting the last active profile while on falls back to direct routing`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a", "Alpha")), activeId = "a").apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        vm.events.test {
            vm.deleteProfile("a")
            assertEquals(WireGuardUiEvent.ProfileDeleted("Alpha"), awaitItem())
            assertEquals(WireGuardUiEvent.WireGuardToggled(false), awaitItem())
        }
        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, fake.routingMode.value)
        assertFalse(vm.isWgActive.value)
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `deleting the active profile with others left moves on and restarts`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"), wgProfile("b")), activeId = "a").apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        vm.events.test {
            vm.deleteProfile("a")
            awaitItem()
        }
        assertEquals("b", vm.activeProfileId.value)
        assertEquals(AppPreferences.ROUTING_MODE_WIREGUARD, fake.routingMode.value)
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `deleting an inactive or unknown profile does not restart`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"), wgProfile("b")), activeId = "a").apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        vm.deleteProfile("missing")
        vm.events.test {
            vm.deleteProfile("b")
            awaitItem()
        }
        verify(exactly = 0) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `turning WireGuard on without a profile asks for an import`() {
        val vm = newVm()
        vm.toggleWireGuard()
        assertEquals("Import a config first", vm.error.value)
        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, fake.routingMode.value)
        assertFalse(vm.isWgActive.value)
    }

    @Test
    fun `turning WireGuard on disables HTTPS filtering first`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"))).apply { httpsFiltering.value = true })
        vm.events.test {
            vm.toggleWireGuard()
            assertEquals(WireGuardUiEvent.HttpsFilteringDisabledForWg, awaitItem())
            assertEquals(WireGuardUiEvent.WireGuardToggled(true), awaitItem())
        }
        assertFalse(fake.httpsFiltering.value)
        assertEquals(AppPreferences.ROUTING_MODE_WIREGUARD, fake.routingMode.value)
        assertTrue(vm.isWgActive.value)
    }

    @Test
    fun `turning WireGuard off returns to direct routing`() = runTest {
        val vm = newVm(FakeWireGuardPrefs(listOf(wgProfile("a"))).apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        vm.events.test {
            vm.toggleWireGuard()
            assertEquals(WireGuardUiEvent.WireGuardToggled(false), awaitItem())
        }
        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, fake.routingMode.value)
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `tunnel options update state immediately and persist`() {
        val vm = newVm()
        vm.setExcludeLan(false)
        vm.setAllowAppBypass(true)
        vm.setSplitDnsZones("lan")
        assertFalse(vm.excludeLan.value)
        assertTrue(vm.allowAppBypass.value)
        assertEquals("lan", vm.splitDnsZones.value)
        coVerify {
            fake.prefs.setExcludeLan(false)
            fake.prefs.setAllowAppBypass(true)
            fake.prefs.setSplitDnsZones("lan")
        }
        verify(exactly = 1) { ServiceController.requestRestart(any()) }
    }
}
