package app.pwhs.blockads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.LocaleHelper
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.ui.BlockAdsApp
import app.pwhs.blockads.ui.theme.BlockadsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.getKoin

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_START_VPN = "extra_start_vpn"
    }

    private var widgetIntentHandled = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed regardless — notification is optional but nice to have
        requestVpnPermission()
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply saved locale for pre-API 33 devices
        val appPrefs = AppPreferences(newBase)
        val savedLang = runBlocking { appPrefs.appLanguage.first() }
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, savedLang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appPrefs: AppPreferences = getKoin().get()
            val themeMode by appPrefs.themeMode.collectAsState(initial = AppPreferences.THEME_SYSTEM)
            val highContrast by appPrefs.highContrast.collectAsState(initial = false)

            val isDark = when (themeMode) {
                AppPreferences.THEME_DARK -> true
                AppPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            // Update status bar icons when theme changes
            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
                onDispose {}
            }

            BlockadsTheme(themeMode = themeMode, highContrast = highContrast) {
                BlockAdsApp(
                    onRequestVpnPermission = { handleVpnToggle() }
                )
            }
        }
        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetIntentHandled = false
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (!widgetIntentHandled && intent?.getBooleanExtra(EXTRA_START_VPN, false) == true) {
            widgetIntentHandled = true
            if (!AdBlockVpnService.isRunning) {
                handleVpnToggle()
            }
        }
    }

    private fun handleVpnToggle() {
        // Check notification permission first (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already have permission
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}