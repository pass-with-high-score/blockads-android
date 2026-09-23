package app.pwhs.blockads.ui.firewall

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.awaitUntil
import app.pwhs.blockads.ui.installApp
import app.pwhs.blockads.ui.keepHot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirewallViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val enabled = MutableStateFlow(false)
    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { firewallEnabled } returns enabled
    }
    private val rules = MutableStateFlow(listOf(FirewallRule(id = 1, packageName = "com.user")))
    private val dao: FirewallRuleDao = mockk(relaxed = true) {
        every { getAll() } returns rules
        every { getEnabledCount() } returns flowOf(1)
    }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
        app.installApp("com.user", "User App")
        app.installApp("com.other", "Other App")
        app.installApp("android.sys", "System", system = true)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun loadedVm(): FirewallViewModel {
        val vm = FirewallViewModel(appPrefs, dao, app)
        awaitUntil(message = "apps loaded") { !vm.isLoading.value }
        return vm
    }

    @Test
    fun `state mirrors preferences and the rule table`() = runTest {
        val vm = loadedVm()
        keepHot(vm.firewallEnabled, vm.firewallRules, vm.enabledCount)
        enabled.value = true
        assertTrue(vm.firewallEnabled.value)
        assertEquals(listOf("com.user"), vm.firewallRules.value.map { it.packageName })
        assertEquals(1, vm.enabledCount.value)
        assertEquals(listOf("com.other", "android.sys", "com.user"), vm.installedApps.value.map { it.packageName })
    }

    @Test
    fun `master switch persists and restarts`() {
        loadedVm().setFirewallEnabled(true)
        coVerify { appPrefs.setFirewallEnabled(true) }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `toggling an app adds a default rule or removes the existing one`() {
        coEvery { dao.getByPackageName("com.user") } returns FirewallRule(id = 1, packageName = "com.user")
        coEvery { dao.getByPackageName("com.other") } returns null
        val vm = loadedVm()

        vm.toggleAppFirewall("com.user")
        vm.toggleAppFirewall("com.other")

        coVerify { dao.deleteByPackageName("com.user") }
        coVerify { dao.insert(FirewallRule(packageName = "com.other")) }
        verify(exactly = 2) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `saving and deleting single rules restart`() {
        val vm = loadedVm()
        val rule = FirewallRule(packageName = "com.user", blockWifi = false)
        vm.saveRule(rule)
        vm.deleteRule("com.user")
        coVerify { dao.insert(rule) }
        coVerify { dao.deleteByPackageName("com.user") }
        verify(exactly = 2) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `bulk actions split user and system apps`() {
        val vm = loadedVm()

        vm.enableAllUserApps()
        coVerify { dao.insertAll(listOf(FirewallRule(packageName = "com.other"), FirewallRule(packageName = "com.user"))) }
        vm.disableAllUserApps()
        coVerify { dao.deleteByPackageNames(listOf("com.other", "com.user")) }
        vm.enableAllSystemApps()
        coVerify { dao.insertAll(listOf(FirewallRule(packageName = "android.sys"))) }
        vm.disableAllSystemApps()
        coVerify { dao.deleteByPackageNames(listOf("android.sys")) }
        verify(exactly = 4) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `refresh picks up newly installed apps`() {
        val vm = loadedVm()
        app.installApp("com.new", "New")
        vm.refreshApps()
        awaitUntil(message = "refresh") { vm.installedApps.value.any { it.packageName == "com.new" } }
    }

    @Ignore("known bug: loadApps has no error handling, so a PackageManager failure leaves the screen loading forever")
    @Test
    fun `a failed app load does not leave the screen loading`() {
        val pm = mockk<PackageManager> {
            every { getInstalledApplications(any<Int>()) } throws RuntimeException("binder died")
        }
        val ctx = mockk<Context>(relaxed = true) { every { packageManager } returns pm }
        val failingApp = mockk<Application>(relaxed = true) { every { applicationContext } returns ctx }
        val vm = FirewallViewModel(appPrefs, dao, failingApp)
        awaitUntil(timeoutMs = 2_000, message = "loading cleared") { !vm.isLoading.value }
    }
}
