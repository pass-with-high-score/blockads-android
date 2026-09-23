package app.pwhs.blockads.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ProfileManager
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.IptablesManager
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.keepHot
import app.pwhs.blockads.ui.settle
import app.pwhs.blockads.utils.CrashReportingManager
import app.pwhs.blockads.worker.DailySummaryScheduler
import app.pwhs.blockads.worker.FilterUpdateScheduler
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val routingMode = MutableStateFlow(AppPreferences.ROUTING_MODE_DIRECT)
    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { routingMode } returns this@SettingsViewModelTest.routingMode
        coEvery { setRoutingMode(any()) } coAnswers { this@SettingsViewModelTest.routingMode.value = firstArg() }
    }
    private val filterRepo: FilterListRepository = mockk(relaxed = true)
    private val dnsLogDao: DnsLogDao = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(ServiceController, FilterUpdateScheduler, DailySummaryScheduler, CrashReportingManager, IptablesManager)
        every { ServiceController.requestRestart(any()) } just Runs
        coEvery { FilterUpdateScheduler.scheduleFilterUpdate(any(), any()) } just Runs
        every { DailySummaryScheduler.scheduleDailySummary(any()) } just Runs
        every { DailySummaryScheduler.cancelDailySummary(any()) } just Runs
        every { CrashReportingManager.toggleSentry(any(), any()) } just Runs
    }

    @After
    fun tearDown() = unmockkAll()

    private fun newVm() = SettingsViewModel(
        appPrefs = appPrefs,
        filterRepo = filterRepo,
        dnsLogDao = dnsLogDao,
        whitelistDomainDao = mockk<WhitelistDomainDao>(relaxed = true),
        filterListDao = mockk<FilterListDao>(relaxed = true),
        customDnsRuleDao = mockk<CustomDnsRuleDao>(relaxed = true),
        profileDao = mockk<ProtectionProfileDao>(relaxed = true),
        profileManager = mockk<ProfileManager>(relaxed = true),
        firewallRuleDao = mockk<FirewallRuleDao>(relaxed = true),
        application = app,
    )

    @Test
    fun `init seeds the default filter lists`() {
        newVm()
        coVerify(exactly = 1) { filterRepo.seedDefaultsIfNeeded() }
    }

    @Test
    fun `settings that change filtering restart the service`() {
        val vm = newVm()
        vm.setExcludeLan(true)
        vm.setDnsResponseType(AppPreferences.DNS_RESPONSE_CUSTOM_IP)
        vm.setSafeSearchEnabled(true)
        vm.setYoutubeRestrictedMode(true)

        coVerify {
            appPrefs.setExcludeLan(true)
            appPrefs.setDnsResponseType(AppPreferences.DNS_RESPONSE_CUSTOM_IP)
            appPrefs.setSafeSearchEnabled(true)
            appPrefs.setYoutubeRestrictedMode(true)
        }
        verify(exactly = 4) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `settings that do not affect filtering leave the service alone`() {
        val vm = newVm()
        vm.setAutoReconnect(false)
        vm.setHideFromRecents(true)
        vm.setNetworkSwitchDelayEnabled(true)
        vm.setNetworkSwitchDelaySec(15)
        vm.setMilestoneNotificationsEnabled(false)
        vm.setAutoUpdateNotification(AppPreferences.NOTIFICATION_SILENT)

        coVerify {
            appPrefs.setAutoReconnect(false)
            appPrefs.setHideFromRecents(true)
            appPrefs.setNetworkSwitchDelayEnabled(true)
            appPrefs.setNetworkSwitchDelaySec(15)
            appPrefs.setMilestoneNotificationsEnabled(false)
            appPrefs.setAutoUpdateNotification(AppPreferences.NOTIFICATION_SILENT)
        }
        verify(exactly = 0) { ServiceController.requestRestart(any()) }
        coVerify(exactly = 0) { FilterUpdateScheduler.scheduleFilterUpdate(any(), any()) }
    }

    @Test
    fun `every auto-update setting reschedules the filter update worker`() {
        val vm = newVm()
        vm.setAutoUpdateEnabled(false)
        vm.setAutoUpdateFrequency(AppPreferences.UPDATE_FREQUENCY_6H)
        vm.setAutoUpdateWifiOnly(false)

        coVerify {
            appPrefs.setAutoUpdateEnabled(false)
            appPrefs.setAutoUpdateFrequency(AppPreferences.UPDATE_FREQUENCY_6H)
            appPrefs.setAutoUpdateWifiOnly(false)
        }
        coVerify(exactly = 3) { FilterUpdateScheduler.scheduleFilterUpdate(any(), appPrefs) }
    }

    @Test
    fun `daily summary toggle schedules or cancels the worker`() {
        val vm = newVm()
        vm.setDailySummaryEnabled(true)
        verify(exactly = 1) { DailySummaryScheduler.scheduleDailySummary(any()) }

        vm.setDailySummaryEnabled(false)
        verify(exactly = 1) { DailySummaryScheduler.cancelDailySummary(any()) }
    }

    @Test
    fun `crash reporting toggle persists and applies to Sentry`() {
        newVm().setCrashReportingEnabled(true)
        coVerify { appPrefs.setCrashReportingEnabled(true) }
        verify { CrashReportingManager.toggleSentry(any(), true) }
    }

    @Test
    fun `clearing logs wipes the table and confirms`() = runTest {
        val vm = newVm()
        vm.events.test {
            vm.clearLogs()
            assertEquals(UiEvent.ToastRes(R.string.filter_log_cleared), awaitItem())
        }
        coVerify { dnsLogDao.clearAll() }
    }

    @Test
    fun `enabling root mode without root keeps the mode and says why`() = runTest {
        every { IptablesManager.isRootAvailable() } returns false
        val vm = newVm()
        vm.events.test {
            vm.settle { setRoutingModeEnabled(true) }
            assertEquals(UiEvent.ToastRes(R.string.root_not_available), awaitItem())
        }
        coVerify(exactly = 0) { appPrefs.setRoutingMode(any()) }
    }

    @Test
    fun `enabling root mode with root switches the routing mode`() {
        every { IptablesManager.isRootAvailable() } returns true
        newVm().settle { setRoutingModeEnabled(true) }
        assertEquals(AppPreferences.ROUTING_MODE_ROOT, routingMode.value)
    }

    @Test
    fun `selecting the current routing mode writes nothing`() {
        newVm().settle { setRoutingModeEnabled(false) }
        coVerify(exactly = 0) { appPrefs.setRoutingMode(any()) }
    }

    @Test
    fun `disabling root mode returns to direct routing`() {
        routingMode.value = AppPreferences.ROUTING_MODE_ROOT
        newVm().settle { setRoutingModeEnabled(false) }
        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, routingMode.value)
    }

    @Test
    fun `switching to root while the VPN runs stops the VPN and starts the root proxy`() {
        every { IptablesManager.isRootAvailable() } returns true
        mockkObject(AdBlockVpnService.Companion, RootProxyService.Companion)
        every { AdBlockVpnService.isRunning } returns true
        every { RootProxyService.start(any()) } just Runs

        newVm().settle { setRoutingModeEnabled(true) }

        val stop = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_STOP, stop.action)
        verify { RootProxyService.start(any()) }
    }

    @Test
    fun `switching back to direct while root runs restarts the VPN in the foreground`() {
        routingMode.value = AppPreferences.ROUTING_MODE_ROOT
        mockkObject(RootProxyService.Companion)
        every { RootProxyService.isRunning } returns true
        every { RootProxyService.stop(any()) } just Runs

        newVm().settle { setRoutingModeEnabled(false) }

        verify { RootProxyService.stop(any()) }
        val start = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_START, start.action)
    }

    @Test
    fun `routing mode state follows preferences`() = runTest {
        val vm = newVm()
        keepHot(vm.routingMode)
        routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        assertEquals(AppPreferences.ROUTING_MODE_WIREGUARD, vm.routingMode.value)
        assertNull(shadowOf(app).nextStartedService)
    }
}
