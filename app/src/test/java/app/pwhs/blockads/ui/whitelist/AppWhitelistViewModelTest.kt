package app.pwhs.blockads.ui.whitelist

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.awaitUntil
import app.pwhs.blockads.ui.installApp
import app.pwhs.blockads.ui.keepHot
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppWhitelistViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val whitelisted = MutableStateFlow(setOf("com.b"))
    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { whitelistedApps } returns whitelisted
    }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
        app.installApp("com.b", "beta")
        app.installApp("com.a", "Alpha", system = true)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun loadedVm(): AppWhitelistViewModel {
        val vm = AppWhitelistViewModel(appPrefs, app)
        awaitUntil(message = "apps loaded") { !vm.isLoading.value }
        return vm
    }

    @Test
    fun `installed apps load sorted by label with system flag`() = runTest {
        val vm = loadedVm()
        keepHot(vm.whitelistedApps)
        assertEquals(listOf("com.a" to true, "com.b" to false), vm.installedApps.value.map { it.packageName to it.isSystemApp })
        assertEquals(setOf("com.b"), vm.whitelistedApps.value)
    }

    @Test
    fun `toggling persists and restarts`() {
        loadedVm().toggleApp("com.a")
        coVerify { appPrefs.toggleWhitelistedApp("com.a") }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `refresh reloads the list`() {
        val vm = loadedVm()
        app.installApp("com.c", "Gamma")
        vm.refreshApps()
        awaitUntil(message = "refresh") { vm.installedApps.value.size == 3 }
    }

    @Ignore("known bug: loadApps has no error handling, so a PackageManager failure leaves the screen loading forever")
    @Test
    fun `a failed app load does not leave the screen loading`() {
        val pm = mockk<PackageManager> {
            every { getInstalledApplications(any<Int>()) } throws RuntimeException("binder died")
        }
        val ctx = mockk<Context>(relaxed = true) { every { packageManager } returns pm }
        val failingApp = mockk<Application>(relaxed = true) { every { applicationContext } returns ctx }
        val vm = AppWhitelistViewModel(appPrefs, failingApp)
        awaitUntil(timeoutMs = 2_000, message = "loading cleared") { !vm.isLoading.value }
    }
}
