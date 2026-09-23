package app.pwhs.blockads.ui.trustednetworks

import android.app.Application
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.TrustedNetworkManager
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.keepHot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Add-current-network semantics live in TrustedNetworksViewModelTest; this covers the rest of the VM. */
class TrustedNetworksStateTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val ssids = MutableStateFlow(setOf("Home", "Office"))
    private val pause = MutableStateFlow(false)
    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { trustedSsids } returns ssids
        every { pauseOnTrustedEnabled } returns pause
        coEvery { getTrustedSsidsSnapshot() } answers { ssids.value }
        coEvery { setTrustedSsids(any()) } coAnswers { ssids.value = firstArg() }
    }
    private val vm by lazy { TrustedNetworksViewModel(appPrefs, mockk<Application>(relaxed = true)) }

    @Before
    fun setUp() {
        mockkObject(TrustedNetworkManager.Companion)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `state mirrors preferences`() = runTest {
        keepHot(vm.trustedSsids, vm.pauseOnTrustedEnabled)
        pause.value = true
        assertEquals(setOf("Home", "Office"), vm.trustedSsids.value)
        assertTrue(vm.pauseOnTrustedEnabled.value)
    }

    @Test
    fun `refresh reads the connected SSID`() {
        every { TrustedNetworkManager.currentSsid(any()) } returns "Cafe"
        vm.refreshCurrentSsid()
        assertEquals("Cafe", vm.currentSsid.value)

        every { TrustedNetworkManager.currentSsid(any()) } returns null
        vm.refreshCurrentSsid()
        assertNull(vm.currentSsid.value)
    }

    @Test
    fun `adding without a Wi-Fi SSID does nothing`() {
        every { TrustedNetworkManager.currentSsid(any()) } returns null
        vm.addCurrentNetwork()
        coVerify(exactly = 0) { appPrefs.toggleTrustedSsid(any()) }
        coVerify(exactly = 0) { appPrefs.setTrustedSsids(any()) }
    }

    @Test
    fun `removing drops only that SSID`() {
        vm.removeSsid("Home")
        assertEquals(setOf("Office"), ssids.value)
        vm.removeSsid("Nowhere")
        assertEquals(setOf("Office"), ssids.value)
    }

    @Test
    fun `the pause toggle persists`() {
        vm.setPauseOnTrustedEnabled(true)
        coVerify { appPrefs.setPauseOnTrustedEnabled(true) }
    }
}
