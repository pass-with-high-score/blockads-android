package app.pwhs.blockads.ui.settings.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.about.AboutScreen
import app.pwhs.blockads.ui.appearance.AppearanceLanguageScreen
import app.pwhs.blockads.ui.appearance.AppearanceNavigationScreen
import app.pwhs.blockads.ui.appearance.AppearanceThemeScreen
import app.pwhs.blockads.ui.appmanagement.AppManagementScreen
import app.pwhs.blockads.ui.dnsprovider.DnsProviderScreen
import app.pwhs.blockads.ui.httpsfiltering.HttpsDebugLogsScreen
import app.pwhs.blockads.ui.httpsfiltering.HttpsFilteringScreen
import app.pwhs.blockads.ui.httpsfiltering.HttpsFilteringSettingsScreen
import app.pwhs.blockads.ui.httpsfiltering.HttpsSkippedDomainsScreen
import app.pwhs.blockads.ui.settings.SettingsDetailPane
import app.pwhs.blockads.ui.settings.SettingsViewModel
import app.pwhs.blockads.ui.settings.geoip.GeoIpScreen
import app.pwhs.blockads.ui.tailscale.TailscaleScreen
import app.pwhs.blockads.ui.trustednetworks.TrustedNetworksScreen
import app.pwhs.blockads.update.UpdateChannel

@Composable
fun SettingsDetailPaneContent(
    activeDetailPane: SettingsDetailPane,
    onChangeDetailPane: (SettingsDetailPane) -> Unit,
    viewModel: SettingsViewModel,
    dnsResponseType: String,
    autoUpdateFrequency: String,
    autoUpdateNotification: String,
    filterBuildMode: String,
    logRetentionDays: Int,
    updateChannel: UpdateChannel,
    onNavigateToCertWizard: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (activeDetailPane) {
        SettingsDetailPane.DNS_PROVIDER -> DnsProviderScreen(
            showBackButton = false,
            onNavigateBack = {}
        )
        SettingsDetailPane.DNS_RESPONSE_TYPE -> DnsResponseTypePane(
            dnsResponseType = dnsResponseType,
            onUpdateResponseType = { viewModel.setDnsResponseType(it) }
        )
        SettingsDetailPane.UPDATE_FREQUENCY -> SettingsOptionPane(
            title = stringResource(R.string.settings_auto_update_frequency),
            options = listOf(
                SettingsOptionItem(AppPreferences.UPDATE_FREQUENCY_6H, R.string.settings_auto_update_frequency_6h),
                SettingsOptionItem(AppPreferences.UPDATE_FREQUENCY_12H, R.string.settings_auto_update_frequency_12h),
                SettingsOptionItem(AppPreferences.UPDATE_FREQUENCY_24H, R.string.settings_auto_update_frequency_24h),
                SettingsOptionItem(AppPreferences.UPDATE_FREQUENCY_48H, R.string.settings_auto_update_frequency_48h),
                SettingsOptionItem(AppPreferences.UPDATE_FREQUENCY_MANUAL, R.string.settings_auto_update_frequency_manual)
            ),
            selectedValue = autoUpdateFrequency,
            onSelect = { viewModel.setAutoUpdateFrequency(it) }
        )
        SettingsDetailPane.UPDATE_NOTIFICATION -> SettingsOptionPane(
            title = stringResource(R.string.settings_auto_update_notification),
            options = listOf(
                SettingsOptionItem(AppPreferences.NOTIFICATION_NORMAL, R.string.settings_auto_update_notification_normal),
                SettingsOptionItem(AppPreferences.NOTIFICATION_SILENT, R.string.settings_auto_update_notification_silent),
                SettingsOptionItem(AppPreferences.NOTIFICATION_NONE, R.string.settings_auto_update_notification_none)
            ),
            selectedValue = autoUpdateNotification,
            onSelect = { viewModel.setAutoUpdateNotification(it) }
        )
        SettingsDetailPane.BUILD_MODE -> SettingsOptionPane(
            title = stringResource(R.string.filter_build_mode),
            options = listOf(
                SettingsOptionItem(
                    AppPreferences.BUILD_MODE_ONLINE,
                    R.string.filter_build_mode_server,
                    R.string.filter_build_mode_server_desc
                ),
                SettingsOptionItem(
                    AppPreferences.BUILD_MODE_LOCAL,
                    R.string.filter_build_mode_local,
                    R.string.filter_build_mode_local_desc
                )
            ),
            selectedValue = filterBuildMode,
            onSelect = { viewModel.setFilterBuildMode(it) }
        )
        SettingsDetailPane.LOG_RETENTION -> SettingsOptionPane(
            title = stringResource(R.string.log_retention_title),
            options = listOf(
                SettingsOptionItem(0, R.string.log_retention_off),
                SettingsOptionItem(1, R.string.log_retention_1d),
                SettingsOptionItem(3, R.string.log_retention_3d),
                SettingsOptionItem(7, R.string.log_retention_7d)
            ),
            selectedValue = logRetentionDays,
            onSelect = { viewModel.setLogRetentionDays(it) }
        )
        SettingsDetailPane.UPDATE_CHANNEL -> SettingsOptionPane(
            title = stringResource(R.string.update_channel_title),
            options = listOf(
                SettingsOptionItem(UpdateChannel.STABLE, R.string.update_channel_stable),
                SettingsOptionItem(UpdateChannel.BETA, R.string.update_channel_beta),
                SettingsOptionItem(UpdateChannel.NIGHTLY, R.string.update_channel_nightly)
            ),
            selectedValue = updateChannel,
            onSelect = { viewModel.setUpdateChannel(it) }
        )
        SettingsDetailPane.APP_MANAGEMENT -> AppManagementScreen(onNavigateBack = {})
        SettingsDetailPane.TRUSTED_NETWORKS -> TrustedNetworksScreen(onNavigateBack = {})
        SettingsDetailPane.TAILSCALE -> TailscaleScreen(
            showBackButton = false,
            onNavigateBack = {}
        )
        SettingsDetailPane.HTTPS_FILTERING -> HttpsFilteringScreen(
            showBackButton = false,
            onNavigateBack = {},
            onNavigateToWizard = onNavigateToCertWizard,
            onNavigateToDebugLogs = { onChangeDetailPane(SettingsDetailPane.HTTPS_DEBUG_LOGS) },
            onNavigateToSettings = { onChangeDetailPane(SettingsDetailPane.HTTPS_SETTINGS) }
        )
        SettingsDetailPane.HTTPS_SETTINGS -> HttpsFilteringSettingsScreen(
            onNavigateBack = { onChangeDetailPane(SettingsDetailPane.HTTPS_FILTERING) },
            onNavigateToSkippedDomains = { onChangeDetailPane(SettingsDetailPane.HTTPS_SKIPPED_DOMAINS) }
        )
        SettingsDetailPane.HTTPS_DEBUG_LOGS -> HttpsDebugLogsScreen(
            onNavigateBack = { onChangeDetailPane(SettingsDetailPane.HTTPS_FILTERING) }
        )
        SettingsDetailPane.HTTPS_SKIPPED_DOMAINS -> HttpsSkippedDomainsScreen(
            onNavigateBack = { onChangeDetailPane(SettingsDetailPane.HTTPS_SETTINGS) }
        )
        SettingsDetailPane.GEO_IP -> GeoIpScreen(onNavigateBack = {})
        SettingsDetailPane.APPEARANCE_THEME -> AppearanceThemeScreen(
            showBackButton = false,
            onNavigateBack = {}
        )
        SettingsDetailPane.APPEARANCE_NAVIGATION -> AppearanceNavigationScreen(
            showBackButton = false,
            onNavigateBack = {}
        )
        SettingsDetailPane.APPEARANCE_LANGUAGE -> AppearanceLanguageScreen(
            showBackButton = false,
            onNavigateBack = {}
        )
        SettingsDetailPane.ABOUT -> AboutScreen(onNavigateBack = {})
    }
}
