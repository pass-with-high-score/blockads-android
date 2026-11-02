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
import app.pwhs.blockads.utils.CaCertHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File

// Models, CertStatus, and HttpsFilteringEvent are in HttpsFilteringContract.kt
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

    /** HTTP/3 (QUIC) filtering. Off = pages load fully (QUIC relayed);
     *  On = drop browser QUIC to force filterable TCP (more filtering,
     *  some sites may load partially). */
    private val _filterHttp3 = MutableStateFlow(true)
    val filterHttp3: StateFlow<Boolean> = _filterHttp3.asStateFlow()

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

    val isProxyVpnActive: StateFlow<Boolean> = appPrefs.routingMode.map { mode ->
        isProxyVpnRoutingMode(mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        loadState()
        appPrefs.httpsFilteringEnabled.onEach { enabled ->
            _isEnabled.value = enabled
            if (!enabled) _isProxyRunning.value = false
        }.launchIn(viewModelScope)
    }

    private fun isProxyVpnRoutingMode(mode: String): Boolean =
        mode == AppPreferences.ROUTING_MODE_PROXY_VPN ||
            mode == AppPreferences.ROUTING_MODE_WIREGUARD ||
            mode == AppPreferences.ROUTING_MODE_SHADOWSOCKS

    // ── Public API ───────────────────────────────────────────────────────

    /** Toggle HTTPS filtering on or off. */
    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && isProxyVpnRoutingMode(appPrefs.getRoutingModeSnapshot())) {
                _events.emit(HttpsFilteringEvent.ProxyVpnActiveConflict)
                return@launch
            }
            if (enabled && _certStatus.value == CertStatus.NOT_INSTALLED) {
                _events.emit(HttpsFilteringEvent.CertNotInstalled)
                return@launch
            }
            _isEnabled.value = enabled
            appPrefs.setHttpsFilteringEnabled(enabled)

            if (enabled) {
                startProxy()
            } else {
                stopProxy()
            }

            // Restart VPN so the running engine picks up the new MITM setting
            ServiceController.requestRestart(getApplication())
        }
    }

    /**
     * Toggle HTTP/3 (QUIC) filtering. Off (default) → QUIC is relayed so
     * pages load fully. On → browser QUIC is dropped to force filterable
     * TCP TLS (max in-page filtering). Restarts the VPN so the running
     * engine picks up the new setting.
     */
    fun toggleFilterHttp3(enabled: Boolean) {
        viewModelScope.launch {
            _filterHttp3.value = enabled
            appPrefs.setFilterHttp3(enabled)
            if (_isEnabled.value) {
                ServiceController.requestRestart(getApplication())
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
     * Checks the Android trust store for a certificate matching our
     * Root CA's byte encoding. Works for both User and System (Root) stores.
     */
    fun verifyCert(showLoading: Boolean = false) {
        viewModelScope.launch {
            if (showLoading || _certStatus.value == CertStatus.UNKNOWN) {
                _certStatus.value = CertStatus.CHECKING
            }
            val installed = withContext(Dispatchers.IO) { checkCertInTrustStore() }
            _certStatus.value = if (installed) CertStatus.INSTALLED else CertStatus.NOT_INSTALLED
        }
    }

    /**
     * Checks if our Root CA is installed in the Android trust store (user or system).
     */
    fun checkCertInTrustStore(): Boolean {
        val caPem = ensureCaCertPem()
        return CaCertHelper.checkCertInTrustStore(caPem)
    }

    private fun ensureCaCertPem(): String? {
        var pem = _caCertPem.value
        if (pem.isNullOrEmpty()) {
            val certDir = getApplication<Application>().filesDir.absolutePath
            pem = engine.getMitmCACert(certDir)
            if (pem.isNullOrEmpty()) {
                pem = engine.startStackMitm(certDir)
            }
            if (!pem.isNullOrEmpty()) _caCertPem.value = pem
        }
        return pem?.takeIf { it.isNotEmpty() }
    }

    /**
     * Export the CA certificate.
     *
     * - Android 10+ (API 29+): Save to Downloads via MediaStore (no permission needed).
     * - Older: Save to cache and offer legacy intent install.
     */
    fun exportCaCert() {
        viewModelScope.launch {
            val pem = ensureCaCertPem()
            if (pem == null) {
                _events.emit(HttpsFilteringEvent.Error("Could not generate CA certificate."))
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
    fun createSecuritySettingsIntent(): Intent = CaCertHelper.createSecuritySettingsIntent()

    // ── Internal ─────────────────────────────────────────────────────────

    private fun loadState() {
        viewModelScope.launch {
            _isLoading.value = true

            // Load saved preference
            val enabled = appPrefs.getHttpsFilteringEnabledSnapshot()
            _isEnabled.value = enabled
            _filterHttp3.value = appPrefs.getFilterHttp3Snapshot()

            // Load installed browsers
            val detectedBrowsers = withContext(Dispatchers.IO) { detectBrowsers() }
            _browsers.value = detectedBrowsers

            // Check if proxy is already running or CA cert exists on disk
            val certDir = getApplication<Application>().filesDir.absolutePath
            val caCert = engine.getMitmCACert(certDir)
            if (!caCert.isNullOrEmpty()) {
                _isProxyRunning.value = enabled
                _caCertPem.value = caCert
            }

            _isLoading.value = false
        }
    }

    private fun startProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val caPem = ensureCaCertPem()
                if (!caPem.isNullOrEmpty()) {
                    _caCertPem.value = caPem
                    _isProxyRunning.value = true
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
                _isProxyRunning.value = false
                _events.emit(HttpsFilteringEvent.ProxyStopped)
                Timber.d("HTTPS filtering stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping HTTPS filtering")
            }
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
        val savedSelected = appPrefs.getSelectedBrowsersOrNullSnapshot()
        val curatedBrowsers = try {
            getApplication<Application>().assets.open("preset/browsers.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toSet()
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load preset/browsers.txt")
            emptySet()
        }

        val candidates = (activities.mapNotNull { it.activityInfo?.packageName } + curatedBrowsers).toSet()

        return candidates
            .mapNotNull { pkgName ->
                try {
                    val appInfo = pm.getApplicationInfo(pkgName, 0)
                    val isSelected = if (savedSelected == null) {
                        pkgName in curatedBrowsers
                    } else {
                        pkgName in savedSelected
                    }
                    BrowserInfo(
                        packageName = pkgName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        uid = appInfo.uid,
                        icon = try { pm.getApplicationIcon(pkgName) } catch (_: Exception) { null },
                        isSelected = isSelected
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedBy { it.appName }
    }

    private suspend fun persistSelectedBrowsers(browsers: List<BrowserInfo>) {
        val selected = browsers.filter { it.isSelected }.map { it.packageName }.toSet()
        appPrefs.setSelectedBrowsers(selected)
    }
}
