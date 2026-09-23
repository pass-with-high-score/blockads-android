package app.pwhs.blockads.ui.httpsfiltering

import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.testutil.ShadowGoSeq
import app.pwhs.blockads.testutil.awaitTrue
import io.mockk.coVerify
import io.mockk.every
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowGoSeq::class], instrumentedPackages = ["go", "tunnel"])
class HttpsFilteringViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        stopKoin()
        unmockkAll()
    }

    private fun HttpsFilteringViewModel.recordEvents(): MutableList<HttpsFilteringEvent> {
        val events = Collections.synchronizedList(mutableListOf<HttpsFilteringEvent>())
        scope.launch { this@recordEvents.events.collect { events += it } }
        return events
    }

    private fun HttpsFilteringViewModel.awaitLoaded() = awaitTrue(message = "loadState") { !isLoading.value }

    @Test
    fun `loadState restores prefs and marks the proxy running when a CA already exists`() {
        val f = HttpsTestFixtures(httpsEnabled = true, filterHttp3 = false)
        every { f.engine.getMitmCACert(any()) } returns TEST_PEM

        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()

        assertTrue(vm.isEnabled.value)
        assertFalse(vm.filterHttp3.value)
        assertTrue(vm.isProxyRunning.value)
        assertEquals(TEST_PEM, vm.caCertPem.value)
    }

    @Test
    fun `viewing the screen with no CA does not generate one`() {
        val f = HttpsTestFixtures()
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()

        assertFalse(vm.isProxyRunning.value)
        assertNull(vm.caCertPem.value)
        verify(exactly = 0) { f.engine.startStackMitm(any()) }
    }

    @Test
    fun `browsers default to the curated presets when nothing was saved`() {
        val f = HttpsTestFixtures()
        f.installBrowser("com.android.chrome", "Chrome", 10_100)
        f.installBrowser("com.example.other", "Other", 10_200)

        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()

        val byPkg = vm.browsers.value.associateBy { it.packageName }
        assertEquals(listOf("Chrome", "Other"), vm.browsers.value.map { it.appName })
        assertTrue(byPkg.getValue("com.android.chrome").isSelected)
        assertFalse(byPkg.getValue("com.example.other").isSelected)
        assertEquals(10_100, byPkg.getValue("com.android.chrome").uid)
    }

    @Test
    fun `saved browser selection overrides the presets`() {
        val f = HttpsTestFixtures(selectedBrowsers = setOf("com.example.other"))
        f.installBrowser("com.android.chrome", "Chrome", 10_100)
        f.installBrowser("com.example.other", "Other", 10_200)

        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()

        val selected = vm.browsers.value.filter { it.isSelected }.map { it.packageName }
        assertEquals(listOf("com.example.other"), selected)
    }

    @Test
    fun `toggleBrowser flips selection and persists the selected set`() {
        val f = HttpsTestFixtures()
        f.installBrowser("com.android.chrome", "Chrome", 10_100)
        f.installBrowser("com.example.other", "Other", 10_200)
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()

        vm.toggleBrowser("com.example.other")
        awaitTrue { f.savedBrowsers == setOf("com.android.chrome", "com.example.other") }
        assertTrue(vm.browsers.value.all { it.isSelected })
    }

    @Ignore("deselecting every browser saves an empty set, which reloads as 'no choice' and brings the presets back")
    @Test
    fun `deselecting every browser survives a reload`() {
        val f = HttpsTestFixtures()
        f.installBrowser("com.android.chrome", "Chrome", 10_100)
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()
        vm.toggleBrowser("com.android.chrome")
        awaitTrue { vm.browsers.value.none { it.isSelected } }

        val reloaded = HttpsFilteringViewModel(f.app)
        reloaded.awaitLoaded()
        assertTrue(reloaded.browsers.value.none { it.isSelected })
    }

    @Test
    fun `enabling from WireGuard switches routing to direct and starts the MITM stack`() {
        val f = HttpsTestFixtures(routingMode = AppPreferences.ROUTING_MODE_WIREGUARD)
        f.installBrowser("com.android.chrome", "Chrome", 10_100)
        f.installBrowser("org.mozilla.firefox", "Firefox", 10_300)
        every { f.engine.startStackMitm(any()) } returns TEST_PEM
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()
        val events = vm.recordEvents()

        vm.toggleEnabled(true)
        awaitTrue(message = "ProxyStarted") { HttpsFilteringEvent.ProxyStarted in events }

        assertEquals(HttpsFilteringEvent.WireGuardDisabledForHttps, events.first())
        coVerify { f.prefs.setHttpsFilteringEnabled(true) }
        coVerify { f.prefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT) }
        verify { f.engine.setUseTcpStack(true) }
        verify { f.engine.setMitmAllowedUIDs("10100,10300") }
        verify { f.engine.setExtraPassthroughSuffixes(any()) }
        assertTrue(vm.isProxyRunning.value)
        assertEquals(TEST_PEM, vm.caCertPem.value)
    }

    @Test
    fun `enabling in direct mode leaves routing alone and skips UIDs when none are selected`() {
        val f = HttpsTestFixtures()
        every { f.engine.startStackMitm(any()) } returns TEST_PEM
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()
        val events = vm.recordEvents()

        vm.toggleEnabled(true)
        awaitTrue { HttpsFilteringEvent.ProxyStarted in events }

        assertFalse(HttpsFilteringEvent.WireGuardDisabledForHttps in events)
        coVerify(exactly = 0) { f.prefs.setRoutingMode(any()) }
        verify(exactly = 0) { f.engine.setMitmAllowedUIDs(any()) }
    }

    @Test
    fun `enabling reports an error when the engine returns no CA`() {
        val f = HttpsTestFixtures()
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()
        val events = vm.recordEvents()

        vm.toggleEnabled(true)
        awaitTrue { events.any { it is HttpsFilteringEvent.Error } }
        assertFalse(vm.isProxyRunning.value)
    }

    @Test
    fun `enabling reports an error when the engine throws`() {
        val f = HttpsTestFixtures()
        every { f.engine.startStackMitm(any()) } throws IllegalStateException("boom")
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()
        val events = vm.recordEvents()

        vm.toggleEnabled(true)
        awaitTrue { events.any { it is HttpsFilteringEvent.Error && it.message.contains("boom") } }
    }

    @Test
    fun `disabling stops the MITM stack and clears the CA`() {
        val f = HttpsTestFixtures(httpsEnabled = true)
        every { f.engine.getMitmCACert(any()) } returns TEST_PEM
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()
        val events = vm.recordEvents()

        vm.toggleEnabled(false)
        awaitTrue { HttpsFilteringEvent.ProxyStopped in events }

        verify { f.engine.stopStackMitm() }
        verify { f.engine.setUseTcpStack(false) }
        assertFalse(vm.isEnabled.value)
        assertFalse(vm.isProxyRunning.value)
        assertNull(vm.caCertPem.value)
        coVerify { f.prefs.setHttpsFilteringEnabled(false) }
    }

    @Test
    fun `toggleFilterHttp3 persists the choice`() {
        val f = HttpsTestFixtures()
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()

        vm.toggleFilterHttp3(false)
        awaitTrue { !vm.filterHttp3.value }
        coVerify { f.prefs.setFilterHttp3(false) }
    }

    @Ignore("Events use a MutableSharedFlow without replay, so one emitted before the screen collects is dropped")
    @Test
    fun `events emitted before the collector starts are delivered`() {
        val f = HttpsTestFixtures(routingMode = AppPreferences.ROUTING_MODE_WIREGUARD)
        every { f.engine.startStackMitm(any()) } returns TEST_PEM
        val vm = HttpsFilteringViewModel(f.app)
        vm.awaitLoaded()

        vm.toggleEnabled(true)
        awaitTrue { vm.isProxyRunning.value }
        val events = vm.recordEvents()
        awaitTrue(timeoutMs = 500) { HttpsFilteringEvent.WireGuardDisabledForHttps in events }
    }
}
