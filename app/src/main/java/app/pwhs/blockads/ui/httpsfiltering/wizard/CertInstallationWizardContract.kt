package app.pwhs.blockads.ui.httpsfiltering.wizard

import android.content.Intent
import app.pwhs.blockads.ui.httpsfiltering.CertStatus

enum class WizardStep(val stepIndex: Int, val titleRes: Int) {
    EXPLANATION(0, app.pwhs.blockads.R.string.https_filtering_title),
    SECURITY(1, app.pwhs.blockads.R.string.https_wizard_security_title),
    SAVE_CA(2, app.pwhs.blockads.R.string.https_filtering_step1_title),
    INSTALL_CA(3, app.pwhs.blockads.R.string.https_filtering_step2_title),
    VERIFY(4, app.pwhs.blockads.R.string.https_filtering_step3_title)
}

data class CertInstallationWizardUiState(
    val currentStep: WizardStep = WizardStep.EXPLANATION,
    val certStatus: CertStatus = CertStatus.UNKNOWN,
    val isRootAvailable: Boolean = false,
    val isCertExported: Boolean = false,
    val fileName: String = "BlockAds-RootCA.crt",
    val installSteps: List<String> = emptyList(),
    val brandName: String = "",
    val isExecutingRoot: Boolean = false
)

sealed interface CertInstallationWizardUiIntent {
    data object NextStep : CertInstallationWizardUiIntent
    data object PrevStep : CertInstallationWizardUiIntent
    data class GoToStep(val step: WizardStep) : CertInstallationWizardUiIntent
    data object ExportCert : CertInstallationWizardUiIntent
    data object InstallRootFast : CertInstallationWizardUiIntent
    data object InstallRootModule : CertInstallationWizardUiIntent
    data object OpenSecuritySettings : CertInstallationWizardUiIntent
    data object VerifyCert : CertInstallationWizardUiIntent
    data object FinishWizard : CertInstallationWizardUiIntent
}

sealed interface CertInstallationWizardUiEffect {
    data class ShowSnackbar(val message: String) : CertInstallationWizardUiEffect
    data class OpenSettings(val intent: Intent) : CertInstallationWizardUiEffect
    data object FinishAndNavigateBack : CertInstallationWizardUiEffect
    data class CopyToClipboard(val text: String) : CertInstallationWizardUiEffect
}
