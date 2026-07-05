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
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.utils.LocaleHelper
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.IptablesManager
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.BlockAdsApp
import app.pwhs.blockads.ui.theme.BlockadsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.getKoin

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_START_VPN = "extra_start_vpn"
        const val EXTRA_SHOW_VPN_CONFLICT_DIALOG = "extra_show_vpn_conflict_dialog"
        const val ACTION_TOGGLE_SHORTCUT = "app.pwhs.blockads.ACTION_TOGGLE_SHORTCUT"
    }

    private var widgetIntentHandled = false
    private val _showVpnConflictDialog = mutableStateOf(false)

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
        continueVpnToggle()
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply saved locale for pre-API 33 devices
        val appPrefs = AppPreferences(newBase)
        val savedLang = runBlocking { appPrefs.appLanguage.first() }
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, savedLang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply hide-from-recents preference
        val appPrefsInit = AppPreferences(this)
        val hideFromRecents = runBlocking { appPrefsInit.hideFromRecents.first() }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.appTasks.firstOrNull()?.setExcludeFromRecents(hideFromRecents)

        enableEdgeToEdge()
        setContent {
            val appPrefs: AppPreferences = getKoin().get()
            val themeMode by appPrefs.themeMode.collectAsState(initial = AppPreferences.THEME_SYSTEM)
            val accentColor by appPrefs.accentColor.collectAsState(initial = AppPreferences.ACCENT_GREEN)

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

            BlockadsTheme(themeMode = themeMode, accentColor = accentColor) {
                BlockAdsApp(
                    showVpnConflictDialog = _showVpnConflictDialog.value,
                    onDismissVpnConflictDialog = { _showVpnConflictDialog.value = false },
                    onShowVpnConflictDialog = { _showVpnConflictDialog.value = true },
                    onRequestVpnPermission = { handleVpnToggle() }
                )
            }
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG, false) == true) {
            _showVpnConflictDialog.value = true
            intent.removeExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG)
        }
        handleWidgetIntent(intent)
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG, false)) {
            _showVpnConflictDialog.value = true
            intent.removeExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG)
        }
        widgetIntentHandled = false
        handleWidgetIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (!widgetIntentHandled && intent?.getBooleanExtra(EXTRA_START_VPN, false) == true) {
            widgetIntentHandled = true
            val appPrefs: AppPreferences = getKoin().get()
            val routingMode = runBlocking { appPrefs.routingMode.first() }

            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                if (!RootProxyService.isRunning) handleVpnToggle()
            } else {
                if (!AdBlockVpnService.isRunning) handleVpnToggle()
            }
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_TOGGLE_SHORTCUT) {
            val appPrefs: AppPreferences = getKoin().get()
            val routingMode = runBlocking { appPrefs.routingMode.first() }

            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                if (RootProxyService.isRunning || AdBlockVpnService.isRunning) {
                    ServiceController.requestStop(this)
                } else {
                    handleVpnToggle()
                }
            } else {
                if (AdBlockVpnService.isRunning || RootProxyService.isRunning) {
                    ServiceController.requestStop(this)
                } else {
                    handleVpnToggle()
                }
            }
            // Clear the action so it doesn't re-trigger
            intent.action = null
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
        continueVpnToggle()
    }

    private fun continueVpnToggle() {
        val appPrefs: AppPreferences = getKoin().get()
        lifecycleScope.launch(Dispatchers.IO) {
            val routingMode = appPrefs.routingMode.first()

            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                if (IptablesManager.isRootAvailable()) {
                    withContext(Dispatchers.Main) {
                        ServiceController.requestStart(this@MainActivity)
                    }
                } else {
                    // If root is lost, fallback to Direct mode and request VPN permission
                    appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                    withContext(Dispatchers.Main) {
                        requestVpnPermission()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    requestVpnPermission()
                }
            }
        }
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
        ServiceController.requestStart(this)
    }
}
