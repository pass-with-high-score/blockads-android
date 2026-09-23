package app.pwhs.blockads.ui.appmanagement

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.AppStat
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.appmanagement.data.AppSortOption
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppManagementViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val whitelisted = MutableStateFlow(emptySet<String>())
    private val stats = MutableStateFlow(emptyList<AppStat>())
    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { whitelistedApps } returns whitelisted
    }
    private val dnsLogDao: DnsLogDao = mockk(relaxed = true) {
        every { getPerAppStats(any()) } returns stats
    }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
        app.installApp("com.zeta", "zeta")
        app.installApp("com.alpha", "Alpha")
        app.installApp("android.sys", "System UI", system = true)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun loadedVm(): AppManagementViewModel {
        val vm = AppManagementViewModel(appPrefs, dnsLogDao, app)
        awaitUntil(message = "apps loaded") { !vm.isLoading.value }
        return vm
    }

    private fun AppManagementViewModel.packages() = apps.value.map { it.packageName }

    @Test
    fun `apps load sorted by label without the app itself`() = runTest {
        val vm = loadedVm()
        keepHot(vm.apps)
        assertEquals(listOf("com.alpha", "android.sys", "com.zeta"), vm.packages())
        assertEquals(3, vm.totalAppCount.value)
        assertEquals(listOf(false, true, false), vm.apps.value.map { it.isSystemApp })
    }

    @Test
    fun `search matches label or package, case-insensitively`() = runTest {
        val vm = loadedVm()
        keepHot(vm.apps)
        vm.setSearchQuery("ALPHA")
        assertEquals(listOf("com.alpha"), vm.packages())
        vm.setSearchQuery("android.")
        assertEquals(listOf("android.sys"), vm.packages())
        assertEquals("android.", vm.searchQuery.value)
    }

    @Test
    fun `stats and whitelist merge in, and sorting follows the option`() = runTest {
        stats.value = listOf(
            AppStat("zeta", "com.zeta", totalQueries = 50, blockedQueries = 1),
            AppStat("Alpha", "com.alpha", totalQueries = 10, blockedQueries = 9),
        )
        whitelisted.value = setOf("com.zeta")
        val vm = loadedVm()
        keepHot(vm.apps)

        vm.setSortOption(AppSortOption.QUERIES)
        assertEquals(listOf("com.zeta", "com.alpha", "android.sys"), vm.packages())
        vm.setSortOption(AppSortOption.BLOCKED)
        assertEquals(listOf("com.alpha", "com.zeta", "android.sys"), vm.packages())
        assertEquals(AppSortOption.BLOCKED, vm.sortOption.value)
        assertEquals(listOf(false, true, false), vm.apps.value.map { it.isWhitelisted })
    }

    @Test
    fun `stats recorded under the package name are used when the label has none`() = runTest {
        stats.value = listOf(AppStat("com.alpha", "com.alpha", totalQueries = 4, blockedQueries = 2))
        val vm = loadedVm()
        keepHot(vm.apps)
        assertEquals(4, vm.apps.value.first { it.packageName == "com.alpha" }.totalQueries)
    }

    @Ignore("known bug: stats are keyed by label, so two apps with the same label share counts")
    @Test
    fun `apps sharing a label keep their own stats`() = runTest {
        app.installApp("com.twin.a", "Twin")
        app.installApp("com.twin.b", "Twin")
        stats.value = listOf(
            AppStat("Twin", "com.twin.a", totalQueries = 10, blockedQueries = 1),
            AppStat("Twin", "com.twin.b", totalQueries = 3, blockedQueries = 3),
        )
        val vm = loadedVm()
        keepHot(vm.apps)
        val byPackage = vm.apps.value.associate { it.packageName to it.totalQueries }
        assertEquals(10, byPackage["com.twin.a"])
        assertEquals(3, byPackage["com.twin.b"])
    }

    @Test
    fun `toggling an app flips its whitelist entry and restarts`() {
        loadedVm().toggleApp("com.alpha")
        coVerify { appPrefs.toggleWhitelistedApp("com.alpha") }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `refresh reloads the installed list`() = runTest {
        val vm = loadedVm()
        app.installApp("com.beta", "Beta")
        vm.refreshApps()
        awaitUntil(message = "refresh") { vm.totalAppCount.value == 4 }
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `a failed app load ends loading with an empty list`() = runTest {
        val pm = mockk<PackageManager> {
            every { getInstalledApplications(any<Int>()) } throws RuntimeException("binder died")
        }
        val ctx = mockk<Context>(relaxed = true) { every { packageManager } returns pm }
        val failingApp = mockk<Application>(relaxed = true) { every { applicationContext } returns ctx }
        val vm = AppManagementViewModel(appPrefs, dnsLogDao, failingApp)
        awaitUntil(message = "loading cleared") { !vm.isLoading.value }
        keepHot(vm.apps)
        assertEquals(0, vm.totalAppCount.value)
        assertEquals(emptyList<String>(), vm.packages())
    }
}
