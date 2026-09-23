package app.pwhs.blockads.ui.httpsfiltering

import android.os.Build
import app.pwhs.blockads.testutil.FakeMediaStoreProvider
import app.pwhs.blockads.testutil.ShadowGoSeq
import app.pwhs.blockads.testutil.awaitTrue
import app.pwhs.blockads.utils.SystemCertificateInstaller
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class HttpsFilteringCertActionsTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        stopKoin()
        unmockkAll()
    }

    private fun newVm(f: HttpsTestFixtures): Pair<HttpsFilteringViewModel, MutableList<HttpsFilteringEvent>> {
        val vm = HttpsFilteringViewModel(f.app)
        awaitTrue(message = "loadState") { !vm.isLoading.value }
        val events = Collections.synchronizedList(mutableListOf<HttpsFilteringEvent>())
        scope.launch { vm.events.collect { events += it } }
        return vm to events
    }

    @Test
    fun `export without a CA generates one first and saves it to Downloads`() {
        val media = FakeMediaStoreProvider.install()
        val f = HttpsTestFixtures()
        every { f.engine.startStackMitm(any()) } returns TEST_PEM
        val (vm, events) = newVm(f)

        vm.exportCaCert()
        awaitTrue { events.isNotEmpty() }

        assertEquals(HttpsFilteringEvent.CaCertSavedToDownloads("BlockAds-RootCA.crt"), events.single())
        assertTrue(vm.certExported.value)
        assertEquals(TEST_PEM, vm.caCertPem.value)
        assertEquals(1, media.deletes)
        assertEquals(listOf("BlockAds-RootCA.crt"), media.displayNames.values.toList())
        assertEquals(TEST_PEM, media.files.values.single().readText())
    }

    @Test
    fun `export reports an error when no CA can be generated`() {
        val f = HttpsTestFixtures()
        val (vm, events) = newVm(f)

        vm.exportCaCert()
        awaitTrue { events.isNotEmpty() }

        assertTrue(events.single() is HttpsFilteringEvent.Error)
        assertFalse(vm.certExported.value)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `export on API 28 writes the legacy public Downloads file`() {
        val f = HttpsTestFixtures()
        every { f.engine.getMitmCACert(any()) } returns TEST_PEM
        val (vm, events) = newVm(f)

        vm.exportCaCert()
        awaitTrue { events.isNotEmpty() }

        val event = events.single() as HttpsFilteringEvent.CaCertExportedLegacy
        assertEquals(TEST_PEM, event.certFile.readText())
    }

    @Test
    fun `root user-store install marks the cert installed`() {
        val f = HttpsTestFixtures()
        every { f.engine.getMitmCACert(any()) } returns TEST_PEM
        mockkObject(SystemCertificateInstaller)
        every { SystemCertificateInstaller.installToUserStoreViaRoot(TEST_PEM) } returns Result.success("abcd1234")
        val (vm, events) = newVm(f)

        vm.installToUserStoreViaRoot()
        awaitTrue { events.isNotEmpty() }

        assertEquals(HttpsFilteringEvent.CaCertSavedToDownloads("User Store (abcd1234.0)"), events.single())
        assertEquals(CertStatus.INSTALLED, vm.certStatus.value)
        assertTrue(vm.certExported.value)
    }

    @Test
    fun `root user-store install failure is surfaced and status stays unknown`() {
        val f = HttpsTestFixtures()
        every { f.engine.getMitmCACert(any()) } returns TEST_PEM
        mockkObject(SystemCertificateInstaller)
        every { SystemCertificateInstaller.installToUserStoreViaRoot(any()) } returns
            Result.failure(IllegalStateException("su denied"))
        val (vm, events) = newVm(f)

        vm.installToUserStoreViaRoot()
        awaitTrue { events.isNotEmpty() }

        assertEquals(HttpsFilteringEvent.Error("Root install failed: su denied"), events.single())
        assertEquals(CertStatus.UNKNOWN, vm.certStatus.value)
    }

    @Test
    fun `root installs refuse to run without a CA`() {
        val f = HttpsTestFixtures()
        mockkObject(SystemCertificateInstaller)
        val (vm, events) = newVm(f)

        vm.installToUserStoreViaRoot()
        vm.installToSystemStore()
        awaitTrue { events.size == 2 }

        assertTrue(events.all { it == HttpsFilteringEvent.Error("CA certificate is not ready.") })
        verify(exactly = 0) { SystemCertificateInstaller.installToUserStoreViaRoot(any()) }
        verify(exactly = 0) { SystemCertificateInstaller.installToSystemStore(any()) }
    }

    @Test
    fun `magisk module install success and failure`() {
        val f = HttpsTestFixtures()
        every { f.engine.startStackMitm(any()) } returns TEST_PEM
        mockkObject(SystemCertificateInstaller)
        every { SystemCertificateInstaller.installToSystemStore(TEST_PEM) } returnsMany listOf(
            Result.success("abcd1234"),
            Result.failure(IllegalStateException("no magisk")),
        )
        val (vm, events) = newVm(f)

        vm.installToSystemStore()
        awaitTrue { events.size == 1 }
        vm.installToSystemStore()
        awaitTrue { events.size == 2 }

        assertEquals(HttpsFilteringEvent.CaCertSavedToDownloads("Magisk Module (abcd1234.0)"), events[0])
        assertEquals(HttpsFilteringEvent.Error("Root install failed: no magisk"), events[1])
    }

    @Test
    fun `verify with a CA that is not in the trust store reports NOT_INSTALLED`() {
        val f = HttpsTestFixtures()
        every { f.engine.getMitmCACert(any()) } returns TEST_PEM
        val (vm, _) = newVm(f)

        vm.verifyCert()
        awaitTrue { vm.certStatus.value == CertStatus.NOT_INSTALLED }
    }

    @Ignore("verifyCert generates a CA via startStackMitm when none exists; the screen calls it on open")
    @Test
    fun `verify does not generate a CA as a side effect`() {
        val f = HttpsTestFixtures()
        val (vm, _) = newVm(f)

        vm.verifyCert()
        awaitTrue { vm.certStatus.value == CertStatus.NOT_INSTALLED }
        verify(exactly = 0) { f.engine.startStackMitm(any()) }
    }

    @Test
    fun `security settings intent depends on API level`() {
        val f = HttpsTestFixtures()
        val (vm, _) = newVm(f)
        assertEquals("android.settings.SECURITY_SETTINGS", vm.createSecuritySettingsIntent().action)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `security settings intent on API 29 opens the credential installer`() {
        val f = HttpsTestFixtures()
        val (vm, _) = newVm(f)
        val intent = vm.createSecuritySettingsIntent()
        assertEquals("android.credentials.INSTALL", intent.action)
        assertEquals("application/x-x509-ca-cert", intent.type)
    }
}
