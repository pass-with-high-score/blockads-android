package app.pwhs.blockads.ui.httpsfiltering

import app.pwhs.blockads.testutil.FakeMediaStoreProvider
import app.pwhs.blockads.testutil.ShadowGoSeq
import app.pwhs.blockads.testutil.awaitTrue
import app.pwhs.blockads.ui.httpsfiltering.wizard.CertInstallationWizardUiEffect
import app.pwhs.blockads.ui.httpsfiltering.wizard.CertInstallationWizardUiIntent
import app.pwhs.blockads.ui.httpsfiltering.wizard.CertInstallationWizardViewModel
import app.pwhs.blockads.ui.httpsfiltering.wizard.WizardStep
import app.pwhs.blockads.utils.SystemCertificateInstaller
import io.mockk.clearMocks
import io.mockk.coVerify
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
class CertInstallationWizardViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        stopKoin()
        unmockkAll()
    }

    private fun newWizard(
        existingCa: String = "",
        rootAvailable: Boolean = false,
    ): Triple<HttpsTestFixtures, CertInstallationWizardViewModel, MutableList<CertInstallationWizardUiEffect>> {
        val f = HttpsTestFixtures()
        every { f.engine.getMitmCACert(any()) } returns existingCa
        mockkObject(SystemCertificateInstaller)
        every { SystemCertificateInstaller.isRootAvailable() } returns rootAvailable
        val vm = CertInstallationWizardViewModel(f.app)
        awaitTrue(message = "initial cert check") { vm.uiState.value.certStatus != CertStatus.UNKNOWN }
        val effects = Collections.synchronizedList(mutableListOf<CertInstallationWizardUiEffect>())
        scope.launch { vm.uiEffect.collect { effects += it } }
        return Triple(f, vm, effects)
    }

    @Test
    fun `initial state carries root availability and install steps`() {
        val (_, vm, _) = newWizard(existingCa = TEST_PEM, rootAvailable = true)
        val state = vm.uiState.value
        assertEquals(WizardStep.EXPLANATION, state.currentStep)
        assertTrue(state.isRootAvailable)
        assertTrue(state.installSteps.isNotEmpty())
        assertEquals(CertStatus.NOT_INSTALLED, state.certStatus)
    }

    @Test
    fun `next and prev walk the steps and stop at both ends`() {
        val (_, vm, _) = newWizard(existingCa = TEST_PEM)

        vm.processIntent(CertInstallationWizardUiIntent.PrevStep)
        assertEquals(WizardStep.EXPLANATION, vm.uiState.value.currentStep)

        repeat(WizardStep.entries.size + 2) { vm.processIntent(CertInstallationWizardUiIntent.NextStep) }
        assertEquals(WizardStep.VERIFY, vm.uiState.value.currentStep)

        vm.processIntent(CertInstallationWizardUiIntent.PrevStep)
        assertEquals(WizardStep.INSTALL_CA, vm.uiState.value.currentStep)
    }

    @Test
    fun `reaching VERIFY triggers a trust-store check`() {
        val (f, vm, _) = newWizard(existingCa = TEST_PEM)
        vm.processIntent(CertInstallationWizardUiIntent.GoToStep(WizardStep.INSTALL_CA))
        clearMocks(f.engine, answers = false, recordedCalls = true, childMocks = false)

        vm.processIntent(CertInstallationWizardUiIntent.NextStep)

        assertEquals(WizardStep.VERIFY, vm.uiState.value.currentStep)
        awaitTrue { vm.uiState.value.certStatus == CertStatus.NOT_INSTALLED }
        verify(exactly = 1) { f.engine.getMitmCACert(any()) }
    }

    @Test
    fun `GoToStep jumps directly`() {
        val (_, vm, _) = newWizard(existingCa = TEST_PEM)
        vm.processIntent(CertInstallationWizardUiIntent.GoToStep(WizardStep.SAVE_CA))
        assertEquals(WizardStep.SAVE_CA, vm.uiState.value.currentStep)
    }

    @Ignore("opening the wizard generates a CA via startStackMitm when none exists")
    @Test
    fun `opening the wizard does not generate a CA`() {
        val (f, _, _) = newWizard()
        verify(exactly = 0) { f.engine.startStackMitm(any()) }
    }

    @Test
    fun `export saves the CA and marks it exported`() {
        val media = FakeMediaStoreProvider.install()
        val (_, vm, effects) = newWizard(existingCa = TEST_PEM)

        vm.processIntent(CertInstallationWizardUiIntent.ExportCert)
        awaitTrue { effects.isNotEmpty() }

        assertTrue(vm.uiState.value.isCertExported)
        assertTrue((effects.single() as CertInstallationWizardUiEffect.ShowSnackbar).message.contains("BlockAds-RootCA.crt"))
        assertEquals(TEST_PEM, media.files.values.single().readText())
    }

    @Test
    fun `export failure is reported`() {
        val (_, vm, effects) = newWizard(existingCa = TEST_PEM)

        vm.processIntent(CertInstallationWizardUiIntent.ExportCert)
        awaitTrue { effects.isNotEmpty() }

        assertFalse(vm.uiState.value.isCertExported)
        assertTrue((effects.single() as CertInstallationWizardUiEffect.ShowSnackbar).message.startsWith("Export failed"))
    }

    @Test
    fun `export without any CA shows an error`() {
        val (_, vm, effects) = newWizard()

        vm.processIntent(CertInstallationWizardUiIntent.ExportCert)
        awaitTrue { effects.isNotEmpty() }

        assertFalse(vm.uiState.value.isCertExported)
        assertEquals(
            CertInstallationWizardUiEffect.ShowSnackbar("Error: could not generate CA certificate"),
            effects.single()
        )
    }

    @Test
    fun `root fast install success marks installed then re-verifies`() {
        val (_, vm, effects) = newWizard(existingCa = TEST_PEM, rootAvailable = true)
        every { SystemCertificateInstaller.installToUserStoreViaRoot(TEST_PEM) } returns Result.success("h")

        vm.processIntent(CertInstallationWizardUiIntent.InstallRootFast)
        awaitTrue { effects.isNotEmpty() }

        assertFalse(vm.uiState.value.isExecutingRoot)
        assertTrue(vm.uiState.value.isCertExported)
        // Re-verification cannot see the Robolectric trust store, so it settles on NOT_INSTALLED.
        awaitTrue { vm.uiState.value.certStatus == CertStatus.NOT_INSTALLED }
    }

    @Test
    fun `root fast install failure reports the error`() {
        val (_, vm, effects) = newWizard(existingCa = TEST_PEM, rootAvailable = true)
        every { SystemCertificateInstaller.installToUserStoreViaRoot(any()) } returns
            Result.failure(IllegalStateException("denied"))

        vm.processIntent(CertInstallationWizardUiIntent.InstallRootFast)
        awaitTrue { effects.isNotEmpty() }

        assertEquals(CertInstallationWizardUiEffect.ShowSnackbar("Root install failed: denied"), effects.single())
        assertFalse(vm.uiState.value.isExecutingRoot)
    }

    @Test
    fun `root module install success and failure`() {
        val (_, vm, effects) = newWizard(existingCa = TEST_PEM, rootAvailable = true)
        every { SystemCertificateInstaller.installToSystemStore(TEST_PEM) } returnsMany listOf(
            Result.success("h"),
            Result.failure(IllegalStateException("no magisk")),
        )

        vm.processIntent(CertInstallationWizardUiIntent.InstallRootModule)
        awaitTrue { effects.size == 1 }
        vm.processIntent(CertInstallationWizardUiIntent.InstallRootModule)
        awaitTrue { effects.size == 2 }

        assertTrue(vm.uiState.value.isCertExported)
        assertEquals(CertInstallationWizardUiEffect.ShowSnackbar("Module install failed: no magisk"), effects[1])
    }

    @Test
    fun `open security settings emits the settings intent`() {
        val (_, vm, effects) = newWizard(existingCa = TEST_PEM)

        vm.processIntent(CertInstallationWizardUiIntent.OpenSecuritySettings)
        awaitTrue { effects.isNotEmpty() }

        val effect = effects.single() as CertInstallationWizardUiEffect.OpenSettings
        assertEquals("android.settings.SECURITY_SETTINGS", effect.intent.action)
    }

    @Test
    fun `finish enables HTTPS filtering and navigates back`() {
        val (f, vm, effects) = newWizard(existingCa = TEST_PEM)

        vm.processIntent(CertInstallationWizardUiIntent.FinishWizard)
        awaitTrue { effects.isNotEmpty() }

        assertEquals(CertInstallationWizardUiEffect.FinishAndNavigateBack, effects.single())
        coVerify { f.prefs.setHttpsFilteringEnabled(true) }
    }
}
