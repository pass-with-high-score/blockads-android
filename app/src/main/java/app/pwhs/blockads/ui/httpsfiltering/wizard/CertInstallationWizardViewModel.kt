package app.pwhs.blockads.ui.httpsfiltering.wizard

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.httpsfiltering.CertStatus
import app.pwhs.blockads.utils.CaCertHelper
import app.pwhs.blockads.utils.DeviceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File

class CertInstallationWizardViewModel(
    application: Application
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()
    private val engine = tunnel.Tunnel.newEngine()

    private val _uiState = MutableStateFlow(
        CertInstallationWizardUiState(
            installSteps = DeviceManager.getInstallSteps(application),
            brandName = DeviceManager.currentBrandName
        )
    )
    val uiState: StateFlow<CertInstallationWizardUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<CertInstallationWizardUiEffect>()
    val uiEffect: SharedFlow<CertInstallationWizardUiEffect> = _uiEffect.asSharedFlow()

    init {
        initCertAndCheckStatus()
    }

    fun processIntent(intent: CertInstallationWizardUiIntent) {
        when (intent) {
            CertInstallationWizardUiIntent.NextStep -> handleNextStep()
            CertInstallationWizardUiIntent.PrevStep -> handlePrevStep()
            is CertInstallationWizardUiIntent.GoToStep -> {
                _uiState.update { it.copy(currentStep = intent.step) }
            }
            CertInstallationWizardUiIntent.ExportCert -> exportCert()
            is CertInstallationWizardUiIntent.ExportCertToUri -> exportCertToUri(intent.uri)
            CertInstallationWizardUiIntent.OpenSecuritySettings -> openSecuritySettings()
            CertInstallationWizardUiIntent.VerifyCert -> verifyCert()
            CertInstallationWizardUiIntent.FinishWizard -> finishWizard()
        }
    }

    private fun handleNextStep() {
        val current = _uiState.value.currentStep
        val nextOrdinal = current.ordinal + 1
        if (nextOrdinal < WizardStep.entries.size) {
            val next = WizardStep.entries[nextOrdinal]
            _uiState.update { it.copy(currentStep = next) }
            if (next == WizardStep.VERIFY) {
                verifyCert()
            }
        }
    }

    private fun handlePrevStep() {
        val current = _uiState.value.currentStep
        val prevOrdinal = current.ordinal - 1
        if (prevOrdinal >= 0) {
            _uiState.update { it.copy(currentStep = WizardStep.entries[prevOrdinal]) }
        }
    }

    private suspend fun ensureCaPem(): String = withContext(Dispatchers.IO) {
        val certDir = getApplication<Application>().filesDir.absolutePath
        val caFile = File(certDir, "ca.crt")
        var caPem = if (caFile.exists()) caFile.readText().takeIf { it.isNotBlank() } else null
        if (caPem.isNullOrEmpty()) {
            caPem = engine.getMitmCACert(certDir).takeIf { it.isNotBlank() }
        }
        if (caPem.isNullOrEmpty()) {
            caPem = engine.startStackMitm(certDir)
        }
        caPem ?: ""
    }

    private fun initCertAndCheckStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val caPem = ensureCaPem()
            val installed = CaCertHelper.checkCertInTrustStore(caPem)
            _uiState.update {
                it.copy(
                    certStatus = if (installed) CertStatus.INSTALLED else CertStatus.NOT_INSTALLED
                )
            }
        }
    }

    private fun exportCert() {
        viewModelScope.launch {
            val pem = ensureCaPem()
            if (pem.isEmpty()) {
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Error: could not generate CA certificate"))
                return@launch
            }

            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    CaCertHelper.saveCertToDownloads(context, pem, _uiState.value.fileName)
                }
                _uiState.update { it.copy(isCertExported = true) }
                _uiEffect.emit(
                    CertInstallationWizardUiEffect.ShowSnackbar(
                        context.getString(R.string.https_filtering_cert_saved_downloads, _uiState.value.fileName)
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to export CA cert in wizard")
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Export failed: ${e.message}"))
            }
        }
    }

    private fun exportCertToUri(uri: android.net.Uri) {
        viewModelScope.launch {
            val pem = ensureCaPem()
            if (pem.isEmpty()) {
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Error: could not generate CA certificate"))
                return@launch
            }

            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    CaCertHelper.saveCertToUri(context, pem, uri)
                }
                _uiState.update { it.copy(isCertExported = true) }
                _uiEffect.emit(
                    CertInstallationWizardUiEffect.ShowSnackbar(
                        context.getString(R.string.https_filtering_cert_saved_downloads, _uiState.value.fileName)
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to export CA cert to URI in wizard")
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Export failed: ${e.message}"))
            }
        }
    }

    private fun openSecuritySettings() {
        val intent = CaCertHelper.createSecuritySettingsIntent()
        viewModelScope.launch {
            _uiEffect.emit(CertInstallationWizardUiEffect.OpenSettings(intent))
        }
    }

    fun verifyCert() {
        viewModelScope.launch {
            _uiState.update { it.copy(certStatus = CertStatus.CHECKING) }
            val caPem = ensureCaPem()
            val installed = withContext(Dispatchers.IO) { CaCertHelper.checkCertInTrustStore(caPem) }
            _uiState.update {
                it.copy(certStatus = if (installed) CertStatus.INSTALLED else CertStatus.NOT_INSTALLED)
            }
        }
    }

    private fun finishWizard() {
        viewModelScope.launch {
            val mode = appPrefs.getRoutingModeSnapshot()
            val isProxyVpn = mode == AppPreferences.ROUTING_MODE_PROXY_VPN ||
                mode == AppPreferences.ROUTING_MODE_WIREGUARD ||
                mode == AppPreferences.ROUTING_MODE_SHADOWSOCKS
            if (!isProxyVpn) {
                // Enable HTTPS filtering & restart VPN
                appPrefs.setHttpsFilteringEnabled(true)
                ServiceController.requestRestart(getApplication())
            }
            _uiEffect.emit(CertInstallationWizardUiEffect.FinishAndNavigateBack)
        }
    }
}
