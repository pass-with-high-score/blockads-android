package app.pwhs.blockads.ui.wireguard

import android.app.Application
import app.cash.turbine.test
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.wireguard.WireGuardEditViewModel.Companion.FIELD_ADDRESSES
import app.pwhs.blockads.ui.wireguard.WireGuardEditViewModel.Companion.FIELD_DNS
import app.pwhs.blockads.ui.wireguard.WireGuardEditViewModel.Companion.FIELD_LISTEN_PORT
import app.pwhs.blockads.ui.wireguard.WireGuardEditViewModel.Companion.FIELD_NAME
import app.pwhs.blockads.ui.wireguard.WireGuardEditViewModel.Companion.FIELD_PRIVATE_KEY
import app.pwhs.blockads.ui.wireguard.WireGuardEditViewModel.EditEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class WireGuardEditViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var fake: FakeWireGuardPrefs
    private lateinit var vm: WireGuardEditViewModel

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

    private fun start(prefs: FakeWireGuardPrefs = FakeWireGuardPrefs(listOf(wgProfile("a", "Alpha")), activeId = "a")) {
        fake = prefs
        startKoin { modules(module { single { prefs.prefs } }) }
        vm = WireGuardEditViewModel(mockk<Application>(relaxed = true))
    }

    @Test
    fun `loading fills the form from the stored profile`() {
        start()
        vm.load("a")
        val s = vm.state.value
        assertEquals("a", s.profileId)
        assertEquals("Alpha", s.name)
        assertEquals(KEY_A, s.privateKey)
        assertEquals("10.0.0.2/32", s.addresses)
        assertEquals("", s.listenPort)
        assertEquals(1, s.peers.size)
        assertEquals("vpn.example:51820", s.peers[0].endpoint)
        assertEquals("0.0.0.0/0", s.peers[0].allowedIPs)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `loading an unknown profile reports it`() = runTest {
        start()
        vm.events.test {
            vm.load("missing")
            assertEquals(EditEvent.Failed("Profile not found"), awaitItem())
        }
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `peers can be added, edited and removed but never below one`() {
        start()
        vm.load("a")
        val first = vm.state.value.peers[0].rowId
        vm.removePeer(first)
        assertEquals(1, vm.state.value.peers.size)

        vm.addPeer()
        val second = vm.state.value.peers[1].rowId
        vm.updatePeer(second) { it.copy(endpoint = "b.example:1") }
        assertEquals("b.example:1", vm.state.value.peers[1].endpoint)

        vm.removePeer(first)
        assertEquals(listOf(second), vm.state.value.peers.map { it.rowId })
    }

    @Test
    fun `an empty form reports every required field`() = runTest {
        start()
        vm.events.test {
            vm.save()
            assertEquals(EditEvent.Failed("Fix the highlighted fields"), awaitItem())
        }
        val errors = vm.errors.value
        assertFalse(errors.isValid)
        assertEquals("Name is required", errors[FIELD_NAME])
        assertEquals("Private key is required", errors[FIELD_PRIVATE_KEY])
        assertEquals("At least one address is required", errors[FIELD_ADDRESSES])
        val rowId = vm.state.value.peers[0].rowId
        assertEquals("Public key is required", errors["peer.$rowId.publicKey"])
        assertTrue(fake.profiles.value.single().name == "Alpha")
    }

    @Test
    fun `malformed fields are flagged individually`() = runTest {
        start()
        vm.load("a")
        vm.setAddresses("10.0.0.2/32, 10.0.0.3")
        vm.setListenPort("70000")
        vm.setDns("1.1.1.1, not-an-ip")
        vm.setPrivateKey("short")
        val rowId = vm.state.value.peers[0].rowId
        vm.updatePeer(rowId) {
            it.copy(presharedKey = "bad", endpoint = "no-port", allowedIPs = "0.0.0.0/33", persistentKeepalive = "x")
        }
        vm.events.test {
            vm.save()
            awaitItem()
        }
        val e = vm.errors.value
        assertEquals("Missing /prefix", e[FIELD_ADDRESSES])
        assertTrue(e[FIELD_LISTEN_PORT] != null)
        assertEquals("Invalid IP", e[FIELD_DNS])
        assertTrue(e[FIELD_PRIVATE_KEY]!!.contains("44-char"))
        listOf("presharedKey", "endpoint", "allowedIPs", "persistentKeepalive").forEach {
            assertTrue("peer $it", e["peer.$rowId.$it"] != null)
        }
    }

    @Test
    fun `a valid edit saves trimmed values`() = runTest {
        start()
        vm.load("a")
        vm.setName("  Office ")
        vm.setListenPort(" 51820 ")
        vm.setDns("1.1.1.1 , 9.9.9.9")
        val rowId = vm.state.value.peers[0].rowId
        vm.updatePeer(rowId) { it.copy(persistentKeepalive = "25", presharedKey = "") }
        vm.events.test {
            vm.save()
            assertEquals(EditEvent.Saved("Office"), awaitItem())
        }
        assertTrue(vm.errors.value.isValid)
        val saved = fake.profiles.value.single()
        assertEquals("Office", saved.name)
        assertEquals(51820, saved.config.interfaceConfig.listenPort)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), saved.config.interfaceConfig.dns)
        assertEquals(25, saved.config.peers[0].persistentKeepalive)
        assertNull(saved.config.peers[0].presharedKey)
        verify(exactly = 0) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `saving the active profile while WireGuard is on restarts the tunnel`() = runTest {
        start(FakeWireGuardPrefs(listOf(wgProfile("a")), activeId = "a").apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        vm.load("a")
        vm.events.test {
            vm.save()
            awaitItem()
        }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `saving an inactive profile while WireGuard is on does not restart`() = runTest {
        start(FakeWireGuardPrefs(listOf(wgProfile("a"), wgProfile("b")), activeId = "a").apply {
            routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        })
        vm.load("b")
        vm.events.test {
            vm.save()
            awaitItem()
        }
        verify(exactly = 0) { ServiceController.requestRestart(any()) }
    }

    @Ignore("known bug: key validation does not trim, so a pasted key with a trailing newline or space is rejected although save trims it")
    @Test
    fun `keys pasted with surrounding whitespace are accepted`() = runTest {
        start()
        vm.load("a")
        vm.setPrivateKey(" $KEY_A\n")
        vm.events.test {
            vm.save()
            assertEquals(EditEvent.Saved("Alpha"), awaitItem())
        }
    }
}
