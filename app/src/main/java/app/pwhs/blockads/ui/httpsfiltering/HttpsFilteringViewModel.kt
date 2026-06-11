package app.pwhs.blockads.ui.httpsfiltering

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File

// ── Data Classes ────────────────────────────────────────────────────────────

/** Represents an installed browser detected on the device. */
data class BrowserInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val icon: Drawable?,
    val isSelected: Boolean = false
)

/** Certificate installation verification status. */
enum class CertStatus {
    /** Not yet checked. */
    UNKNOWN,
    /** Verification in progress. */
    CHECKING,
    /** Certificate is installed and working. */
    INSTALLED,
    /** Certificate is NOT installed or verification failed. */
    NOT_INSTALLED
}

// ── Events ──────────────────────────────────────────────────────────────────

sealed class HttpsFilteringEvent {
    /** CA cert saved to Downloads — show manual install instructions. */
    data class CaCertSavedToDownloads(val fileName: String) : HttpsFilteringEvent()

    /** Fallback: cert saved to cache for legacy intent install. */
    data class CaCertExportedLegacy(val certFile: File) : HttpsFilteringEvent()

    data class Error(val message: String) : HttpsFilteringEvent()
    data object ProxyStarted : HttpsFilteringEvent()
    data object ProxyStopped : HttpsFilteringEvent()
    /** WireGuard routing was turned off because HTTPS filtering was enabled. */
    data object WireGuardDisabledForHttps : HttpsFilteringEvent()
}

// ── ViewModel ───────────────────────────────────────────────────────────────

class HttpsFilteringViewModel(
    application: Application
) : AndroidViewModel(application), KoinComponent {

    private val appPrefs: AppPreferences by inject()
    private val engine = tunnel.Tunnel.newEngine()

    // ── UI State ─────────────────────────────────────────────────────────

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isProxyRunning = MutableStateFlow(false)
    val isProxyRunning: StateFlow<Boolean> = _isProxyRunning.asStateFlow()

    private val _browsers = MutableStateFlow<List<BrowserInfo>>(emptyList())
    val browsers: StateFlow<List<BrowserInfo>> = _browsers.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _caCertPem = MutableStateFlow<String?>(null)
    val caCertPem: StateFlow<String?> = _caCertPem.asStateFlow()

    /** True after the cert has been exported at least once. */
    private val _certExported = MutableStateFlow(false)
    val certExported: StateFlow<Boolean> = _certExported.asStateFlow()

    /** Certificate installation verification status. */
    private val _certStatus = MutableStateFlow(CertStatus.UNKNOWN)
    val certStatus: StateFlow<CertStatus> = _certStatus.asStateFlow()

    private val _events = MutableSharedFlow<HttpsFilteringEvent>()
    val events: SharedFlow<HttpsFilteringEvent> = _events.asSharedFlow()

    init {
        loadState()
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Toggle HTTPS filtering on or off. */
    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _isEnabled.value = enabled
            appPrefs.setHttpsFilteringEnabled(enabled)

            if (enabled) {
                // HTTPS filtering and WireGuard routing are mutually
                // exclusive: TcpIpStack terminates flows and dials the
                // destination directly, bypassing the WG tunnel. Turn
                // WG off so the user gets a single, working mode.
                if (appPrefs.getRoutingModeSnapshot() == AppPreferences.ROUTING_MODE_WIREGUARD) {
                    appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                    _events.emit(HttpsFilteringEvent.WireGuardDisabledForHttps)
                }
                startProxy()
            } else {
                stopProxy()
            }
        }
    }

    /** Toggle browser selection for MITM interception. */
    fun toggleBrowser(packageName: String) {
        viewModelScope.launch {
            val updated = _browsers.value.map { browser ->
                if (browser.packageName == packageName) {
                    browser.copy(isSelected = !browser.isSelected)
                } else {
                    browser
                }
            }
            _browsers.value = updated
            persistSelectedBrowsers(updated)

            // Restart VPN so the running engine picks up the new browser UIDs.
            // (This ViewModel's engine is a separate instance from the VPN's engine,
            // so we must restart for changes to take effect.)
            ServiceController.requestRestart(getApplication())
        }
    }

    /**
     * Verify that the Root CA certificate has been installed correctly.
     *
     * Checks the Android user trust store for a certificate matching our
     * Root CA's subject DN. No network required — works even when VPN is off.
     */
    fun verifyCert() {
        viewModelScope.launch {
            _certStatus.value = CertStatus.CHECKING
            val installed = withContext(Dispatchers.IO) { checkCertInTrustStore() }
            _certStatus.value = if (installed) CertStatus.INSTALLED else CertStatus.NOT_INSTALLED
        }
    }

    /**
     * Checks if our Root CA is installed in the Android user trust store.
     *
     * Approach: Load the "AndroidCAStore" KeyStore, iterate all aliases,
     * and compare each certificate's subject DN with our CA's subject DN.
     */
    private fun checkCertInTrustStore(): Boolean {
        try {
            // Get our CA cert PEM
            val certDir = getApplication<Application>().filesDir.absolutePath
            val caPem = engine.getMitmCACert(certDir)
            if (caPem.isNullOrEmpty()) {
                Timber.d("Cert verification: no CA cert generated yet")
                return false
            }

            // Parse our CA cert
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val ourCert = certFactory.generateCertificate(
                caPem.byteInputStream()
            ) as java.security.cert.X509Certificate
            val ourSubject = ourCert.subjectX500Principal

            // Check Android user trust store
            val ks = java.security.KeyStore.getInstance("AndroidCAStore")
            ks.load(null)

            for (alias in ks.aliases()) {
                // User-installed certs have aliases starting with "user:"
                if (!alias.startsWith("user:")) continue

                val cert = ks.getCertificate(alias) as? java.security.cert.X509Certificate
                    ?: continue

                if (cert.subjectX500Principal == ourSubject) {
                    Timber.d("Cert verification: found matching CA in trust store (alias=$alias)")
                    return true
                }
            }

            Timber.d("Cert verification: CA not found in user trust store")
            return false
        } catch (e: Exception) {
            Timber.e(e, "Cert verification failed")
            return false
        }
    }

    /**
     * Export the CA certificate.
     *
     * - Android 10+ (API 29+): Save to Downloads via MediaStore (no permission needed).
     * - Older: Save to cache and offer legacy intent install.
     */
    fun exportCaCert() {
        viewModelScope.launch {
            val pem = _caCertPem.value
            if (pem.isNullOrEmpty()) {
                _events.emit(HttpsFilteringEvent.Error("MITM proxy not running. Enable HTTPS filtering first."))
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // ── MediaStore (Android 10+) ─────────────────────────
                        val fileName = "BlockAds-RootCA.crt"
                        val resolver = getApplication<Application>().contentResolver

                        // Delete old file if exists (overwrite)
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

                        resolver.openOutputStream(uri)?.use { out ->
                            out.write(pem.toByteArray())
                        } ?: throw Exception("Failed to open output stream")

                        _certExported.value = true
                        _events.emit(HttpsFilteringEvent.CaCertSavedToDownloads(fileName))
                    } else {
                        // ── Legacy (Android 9-) ─────────────────────────────
                        @Suppress("DEPRECATION")
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        val file = File(downloadsDir, "BlockAds-RootCA.crt")
                        file.writeText(pem)
                        _certExported.value = true
                        _events.emit(HttpsFilteringEvent.CaCertExportedLegacy(file))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to export CA cert")
                _events.emit(HttpsFilteringEvent.Error("Failed to export: ${e.message}"))
            }
        }
    }

    /**
     * Open Android Security Settings where user can install the certificate.
     */
    fun createSecuritySettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: direct to "Install from storage"
            Intent("android.settings.SECURITY_SETTINGS")
        } else {
            Intent("android.credentials.INSTALL").apply {
                type = "application/x-x509-ca-cert"
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun loadState() {
        viewModelScope.launch {
            _isLoading.value = true

            // Load saved preference
            val enabled = appPrefs.getHttpsFilteringEnabledSnapshot()
            _isEnabled.value = enabled

            // Load installed browsers
            val detectedBrowsers = withContext(Dispatchers.IO) { detectBrowsers() }
            _browsers.value = detectedBrowsers

            // Check if proxy is already running or CA cert exists on disk
            val certDir = getApplication<Application>().filesDir.absolutePath
            val caCert = engine.getMitmCACert(certDir)
            if (!caCert.isNullOrEmpty()) {
                _isProxyRunning.value = true
                _caCertPem.value = caCert
            }

            _isLoading.value = false
        }
    }

    private fun startProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val certDir = getApplication<Application>().filesDir.absolutePath
                engine.setUseTcpStack(true)
                val caPem = engine.startStackMitm(certDir)
                if (caPem.isNotEmpty()) {
                    _caCertPem.value = caPem
                    _isProxyRunning.value = true
                    syncUidsToGoEngine(_browsers.value)
                    try {
                        val passthrough = getApplication<Application>().assets
                            .open("https_passthrough.txt")
                            .bufferedReader().use { it.readText() }
                        engine.setExtraPassthroughSuffixes(passthrough)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load https_passthrough.txt asset")
                    }
                    _events.emit(HttpsFilteringEvent.ProxyStarted)
                    Timber.d("HTTPS filtering started (stack MITM)")
                } else {
                    _events.emit(HttpsFilteringEvent.Error("Failed to start HTTPS filtering"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting HTTPS filtering")
                _events.emit(HttpsFilteringEvent.Error("Error: ${e.message}"))
            }
        }
    }

    private fun stopProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.stopStackMitm()
                engine.setUseTcpStack(false)
                _isProxyRunning.value = false
                _caCertPem.value = null
                _events.emit(HttpsFilteringEvent.ProxyStopped)
                Timber.d("HTTPS filtering stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping HTTPS filtering")
            }
        }
    }

    private fun syncUidsToGoEngine(browsers: List<BrowserInfo>) {
        val selectedUids = browsers
            .filter { it.isSelected }
            .joinToString(",") { it.uid.toString() }
        if (selectedUids.isNotEmpty()) {
            engine.setMitmAllowedUIDs(selectedUids)
        }
    }

    @Suppress("DEPRECATION")
    private fun detectBrowsers(): List<BrowserInfo> {
        val pm = getApplication<Application>().packageManager
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))

        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                browserIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(browserIntent, PackageManager.MATCH_ALL)
        }

        // Load saved selected browsers from prefs
        val savedSelected = appPrefs.getSelectedBrowsersSnapshot()

        return activities
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkgName = activityInfo.packageName
                try {
                    val appInfo = pm.getApplicationInfo(pkgName, 0)
                    BrowserInfo(
                        packageName = pkgName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        uid = appInfo.uid,
                        icon = try { pm.getApplicationIcon(pkgName) } catch (_: Exception) { null },
                        isSelected = pkgName in savedSelected
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName }
    }

    private suspend fun persistSelectedBrowsers(browsers: List<BrowserInfo>) {
        val selected = browsers.filter { it.isSelected }.map { it.packageName }.toSet()
        appPrefs.setSelectedBrowsers(selected)
    }
}
