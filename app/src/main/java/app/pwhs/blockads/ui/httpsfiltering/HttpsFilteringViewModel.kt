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

// ── Events ──────────────────────────────────────────────────────────────────

sealed class HttpsFilteringEvent {
    /** CA cert saved to Downloads — show manual install instructions. */
    data class CaCertSavedToDownloads(val fileName: String) : HttpsFilteringEvent()

    /** Fallback: cert saved to cache for legacy intent install. */
    data class CaCertExportedLegacy(val certFile: File) : HttpsFilteringEvent()

    data class Error(val message: String) : HttpsFilteringEvent()
    data object ProxyStarted : HttpsFilteringEvent()
    data object ProxyStopped : HttpsFilteringEvent()
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
            syncUidsToGoEngine(updated)
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
                val caPem = engine.startMitmProxy("127.0.0.1:8080", certDir)
                if (caPem.isNotEmpty()) {
                    _caCertPem.value = caPem
                    _isProxyRunning.value = true
                    syncUidsToGoEngine(_browsers.value)
                    _events.emit(HttpsFilteringEvent.ProxyStarted)
                    Timber.d("MITM Proxy started")
                } else {
                    _events.emit(HttpsFilteringEvent.Error("Failed to start MITM proxy"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting MITM proxy")
                _events.emit(HttpsFilteringEvent.Error("Error: ${e.message}"))
            }
        }
    }

    private fun stopProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.stopMitmProxy()
                _isProxyRunning.value = false
                _caCertPem.value = null
                _events.emit(HttpsFilteringEvent.ProxyStopped)
                Timber.d("MITM Proxy stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping MITM proxy")
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
