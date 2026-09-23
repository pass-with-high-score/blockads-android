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
import app.pwhs.blockads.utils.DeviceManager
import app.pwhs.blockads.utils.SystemCertificateInstaller
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
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class CertInstallationWizardViewModel(
    application: Application
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()
    private val engine = tunnel.Tunnel.newEngine()

    private val _uiState = MutableStateFlow(
        CertInstallationWizardUiState(
            installSteps = DeviceManager.getInstallSteps(),
            brandName = DeviceManager.currentBrandName,
            isRootAvailable = SystemCertificateInstaller.isRootAvailable()
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
            CertInstallationWizardUiIntent.InstallRootFast -> installRootFast()
            CertInstallationWizardUiIntent.InstallRootModule -> installRootModule()
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

    private fun initCertAndCheckStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val certDir = getApplication<Application>().filesDir.absolutePath
            var caPem = engine.getMitmCACert(certDir)
            if (caPem.isNullOrEmpty()) {
                caPem = engine.startStackMitm(certDir)
            }
            val installed = checkCertInTrustStore(caPem)
            _uiState.update {
                it.copy(
                    certStatus = if (installed) CertStatus.INSTALLED else CertStatus.NOT_INSTALLED
                )
            }
        }
    }

    private fun exportCert() {
        viewModelScope.launch {
            val certDir = getApplication<Application>().filesDir.absolutePath
            var pem = engine.getMitmCACert(certDir)
            if (pem.isNullOrEmpty()) {
                pem = withContext(Dispatchers.IO) { engine.startStackMitm(certDir) }
            }
            if (pem.isNullOrEmpty()) {
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Error: could not generate CA certificate"))
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    val fileName = _uiState.value.fileName
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = getApplication<Application>().contentResolver
                        resolver.delete(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                            arrayOf(fileName)
                        )
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw Exception("Failed to create MediaStore entry")

                        resolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
                            ?: throw Exception("Failed to write certificate")
                    } else {
                        @Suppress("DEPRECATION")
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        File(downloadsDir, fileName).writeText(pem)
                    }
                }
                _uiState.update { it.copy(isCertExported = true) }
                val context = getApplication<Application>()
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

    private fun installRootFast() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingRoot = true) }
            val certDir = getApplication<Application>().filesDir.absolutePath
            var pem = engine.getMitmCACert(certDir)
            if (pem.isNullOrEmpty()) {
                pem = withContext(Dispatchers.IO) { engine.startStackMitm(certDir) }
            }
            val result = withContext(Dispatchers.IO) {
                SystemCertificateInstaller.installToUserStoreViaRoot(pem ?: "")
            }
            _uiState.update { it.copy(isExecutingRoot = false) }

            if (result.isSuccess) {
                _uiState.update { it.copy(certStatus = CertStatus.INSTALLED, isCertExported = true) }
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Đã cài đặt thành công vào User Store!"))
                verifyCert()
            } else {
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Root install failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }

    private fun installRootModule() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingRoot = true) }
            val certDir = getApplication<Application>().filesDir.absolutePath
            var pem = engine.getMitmCACert(certDir)
            if (pem.isNullOrEmpty()) {
                pem = withContext(Dispatchers.IO) { engine.startStackMitm(certDir) }
            }
            val result = withContext(Dispatchers.IO) {
                SystemCertificateInstaller.installToSystemStore(pem ?: "")
            }
            _uiState.update { it.copy(isExecutingRoot = false) }

            if (result.isSuccess) {
                _uiState.update { it.copy(certStatus = CertStatus.INSTALLED, isCertExported = true) }
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Đã tạo Magisk Module! Khởi động lại máy để kích hoạt đầy đủ."))
                verifyCert()
            } else {
                _uiEffect.emit(CertInstallationWizardUiEffect.ShowSnackbar("Module install failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }

    private fun openSecuritySettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent("android.settings.SECURITY_SETTINGS")
        } else {
            Intent("android.credentials.INSTALL").apply {
                type = "application/x-x509-ca-cert"
            }
        }
        viewModelScope.launch {
            _uiEffect.emit(CertInstallationWizardUiEffect.OpenSettings(intent))
        }
    }

    fun verifyCert() {
        viewModelScope.launch {
            _uiState.update { it.copy(certStatus = CertStatus.CHECKING) }
            val certDir = getApplication<Application>().filesDir.absolutePath
            val caPem = engine.getMitmCACert(certDir)
            val installed = withContext(Dispatchers.IO) { checkCertInTrustStore(caPem) }
            _uiState.update {
                it.copy(certStatus = if (installed) CertStatus.INSTALLED else CertStatus.NOT_INSTALLED)
            }
        }
    }

    private fun checkCertInTrustStore(caPem: String?): Boolean {
        if (caPem.isNullOrEmpty()) return false
        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val ourCert = certFactory.generateCertificate(caPem.byteInputStream()) as X509Certificate
            val ourEncoded = ourCert.encoded

            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)

            for (alias in ks.aliases()) {
                val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                if (cert.encoded.contentEquals(ourEncoded)) {
                    Timber.d("Wizard: found matching CA in trust store (alias=$alias)")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Timber.e(e, "Wizard: verification failed")
            false
        }
    }

    private fun finishWizard() {
        viewModelScope.launch {
            // Enable HTTPS filtering & restart VPN
            appPrefs.setHttpsFilteringEnabled(true)
            ServiceController.requestRestart(getApplication())
            _uiEffect.emit(CertInstallationWizardUiEffect.FinishAndNavigateBack)
        }
    }
}
