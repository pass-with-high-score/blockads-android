package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences

@Composable
fun AdvancedSection(
    routingMode: String,
    onSetRoutingMode: (Boolean) -> Unit,
    onNavigateToHttpsFiltering: () -> Unit,
    onNavigateToWireGuardImport: () -> Unit,
    onNavigateToTailscale: () -> Unit = {},
    modifier: Modifier = Modifier,
    showVpnSetting: Boolean = true
) {
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_advanced),
            description = stringResource(R.string.settings_category_advanced_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingsCard {
            Column {
                // 1. HTTPS Filtering
                SettingItem(
                    iconPainter = painterResource(R.drawable.ic_settings_https),
                    iconTint = Color(0xFF7C3AED),
                    title = stringResource(R.string.https_filtering_title) + " (BETA)",
                    desc = stringResource(R.string.https_filtering_settings_desc),
                    onClick = onNavigateToHttpsFiltering
                )

                // 2. Proxy & VPN (shown when VPN tab is not on bottom navigation)
                if (showVpnSetting) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)
                    SettingItem(
                        iconPainter = painterResource(R.drawable.ic_nav_proxy),
                        iconTint = Color(0xFF7C3AED),
                        title = stringResource(R.string.proxy_vpn_title),
                        desc = stringResource(R.string.proxy_vpn_subtitle),
                        onClick = onNavigateToWireGuardImport
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 3. Root Proxy
                SettingsToggleItem(
                    iconPainter = painterResource(R.drawable.ic_settings_root_proxy),
                    iconTint = Color(0xFF7C3AED),
                    title = stringResource(R.string.settings_root_proxy),
                    subtitle = stringResource(R.string.settings_root_proxy_desc),
                    isChecked = routingMode == AppPreferences.ROUTING_MODE_ROOT,
                    onCheckedChange = onSetRoutingMode
                )
            }
        }
    }
}
