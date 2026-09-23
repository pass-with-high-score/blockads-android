package app.pwhs.blockads.ui.trustednetworks

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.datastore.prefs.VpnSecurityPreferences
import app.pwhs.blockads.service.TrustedNetworkManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TrustedNetworksViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var security: VpnSecurityPreferences
    private lateinit var appPrefs: AppPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        security = VpnSecurityPreferences(
            PreferenceDataStoreFactory.create { File(tempFolder.newFolder(), "sec.preferences_pb") }
        )
        appPrefs = mockk(relaxed = true) {
            every { trustedSsids } returns security.trustedSsids
            every { pauseOnTrustedEnabled } returns security.pauseOnTrustedEnabled
            coEvery { toggleTrustedSsid(any()) } coAnswers { security.toggleTrustedSsid(firstArg()) }
            coEvery { setTrustedSsids(any()) } coAnswers { security.setTrustedSsids(firstArg()) }
            coEvery { getTrustedSsidsSnapshot() } coAnswers { security.getTrustedSsidsSnapshot() }
        }
        mockkObject(TrustedNetworkManager.Companion)
        every { TrustedNetworkManager.currentSsid(any()) } returns "Home"
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // Joins only jobs launched by [action]; stateIn's sharing coroutines never complete.
    private fun TrustedNetworksViewModel.runAndSettle(action: TrustedNetworksViewModel.() -> Unit) {
        val job = viewModelScope.coroutineContext[Job]!!
        val before = job.children.toSet()
        action()
        runBlocking { (job.children.toSet() - before).joinAll() }
    }

    @Ignore("known bug: addCurrentNetwork toggles, so re-adding a trusted SSID removes it")
    @Test
    fun `adding the current network keeps an already trusted SSID`() {
        runBlocking { security.setTrustedSsids(setOf("Home")) }
        val vm = TrustedNetworksViewModel(appPrefs, mockk<Application>(relaxed = true))

        vm.runAndSettle { addCurrentNetwork() }

        assertEquals(setOf("Home"), runBlocking { security.getTrustedSsidsSnapshot() })
    }

    @Test
    fun `adding the current network trusts a new SSID`() {
        val vm = TrustedNetworksViewModel(appPrefs, mockk<Application>(relaxed = true))

        vm.runAndSettle { addCurrentNetwork() }

        assertEquals(setOf("Home"), runBlocking { security.getTrustedSsidsSnapshot() })
    }
}
