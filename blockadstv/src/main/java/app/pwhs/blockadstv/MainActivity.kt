package app.pwhs.blockadstv

import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.service.RootProxyService
import app.pwhs.blockadstv.service.ServiceController
import app.pwhs.blockadstv.service.TvVpnService
import app.pwhs.blockadstv.ui.navigation.TvNavigationDrawer
import app.pwhs.blockadstv.ui.navigation.TvScreen
import app.pwhs.blockadstv.ui.screens.apps.WhitelistAppScreen
import app.pwhs.blockadstv.ui.screens.dns.DnsSetupScreen
import app.pwhs.blockadstv.ui.screens.filters.FiltersScreen
import app.pwhs.blockadstv.ui.screens.home.HomeScreen
import app.pwhs.blockadstv.ui.screens.logs.LogsScreen
import app.pwhs.blockadstv.ui.screens.proxy.ProxyScreen
import app.pwhs.blockadstv.ui.screens.rules.DomainRulesScreen
import app.pwhs.blockadstv.ui.screens.settings.SettingsScreen
import app.pwhs.blockadstv.ui.theme.BlockadsTheme
import app.pwhs.blockadstv.utils.TvLocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private val tvPrefs: TvPreferences by inject()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            TvVpnService.start(this)
        }
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val savedLang = tvPrefs.appLanguage.first()
            TvLocaleHelper.setLocale(this@MainActivity, savedLang)
        }

        setContent {
            val themeMode by tvPrefs.themeMode.collectAsStateWithLifecycle(
                initialValue = TvPreferences.THEME_DARK
            )
            BlockadsTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                ) {
                    BlockAdsTvApp(
                        onRequestVpnPermission = { requestProtectionToggle() },
                        onStopVpn = { ServiceController.requestStop(this@MainActivity) },
                    )
                }
            }
        }
    }

    private fun requestProtectionToggle() {
        lifecycleScope.launch {
            val mode = tvPrefs.getRoutingModeSnapshot()
            if (mode == TvPreferences.ROUTING_MODE_ROOT) {
                RootProxyService.start(this@MainActivity)
            } else {
                requestVpnPermission()
            }
        }
    }

    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            TvVpnService.start(this)
        }
    }
}

@Composable
fun BlockAdsTvApp(
    onRequestVpnPermission: () -> Unit,
    onStopVpn: () -> Unit,
) {
    var selectedScreen by remember { mutableStateOf(TvScreen.Home) }

    TvNavigationDrawer(
        selectedScreen = selectedScreen,
        onScreenSelected = { selectedScreen = it },
    ) {
        when (selectedScreen) {
            TvScreen.Home -> HomeScreen(
                onRequestVpnPermission = onRequestVpnPermission,
                onStopVpn = onStopVpn,
            )
            TvScreen.Filters -> FiltersScreen()
            TvScreen.Rules -> DomainRulesScreen()
            TvScreen.Apps -> WhitelistAppScreen()
            TvScreen.Logs -> LogsScreen()
            TvScreen.Dns -> DnsSetupScreen()
            TvScreen.Proxy -> ProxyScreen()
            TvScreen.Settings -> SettingsScreen()
        }
    }
}
