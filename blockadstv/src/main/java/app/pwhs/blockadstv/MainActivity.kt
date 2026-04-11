package app.pwhs.blockadstv

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import app.pwhs.blockadstv.service.TvVpnService
import app.pwhs.blockadstv.ui.navigation.TvNavigationDrawer
import app.pwhs.blockadstv.ui.navigation.TvScreen
import app.pwhs.blockadstv.ui.screens.apps.WhitelistAppScreen
import app.pwhs.blockadstv.ui.screens.dns.DnsSetupScreen
import app.pwhs.blockadstv.ui.screens.filters.FiltersScreen
import app.pwhs.blockadstv.ui.screens.home.HomeScreen
import app.pwhs.blockadstv.ui.screens.logs.LogsScreen
import app.pwhs.blockadstv.ui.screens.rules.DomainRulesScreen
import app.pwhs.blockadstv.ui.screens.settings.SettingsScreen
import app.pwhs.blockadstv.ui.theme.BlockadsTheme

class MainActivity : ComponentActivity() {

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
        setContent {
            BlockadsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                ) {
                    BlockAdsTvApp(
                        onRequestVpnPermission = { requestVpnPermission() },
                        onStopVpn = { TvVpnService.stop(this@MainActivity) },
                    )
                }
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
            TvScreen.Settings -> SettingsScreen()
        }
    }
}
