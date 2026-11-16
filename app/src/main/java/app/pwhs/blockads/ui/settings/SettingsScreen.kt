package app.pwhs.blockads.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.about.AboutScreen
import app.pwhs.blockads.ui.appearance.AppearanceLanguageScreen
import app.pwhs.blockads.ui.appearance.AppearanceNavigationScreen
import app.pwhs.blockads.ui.appearance.AppearanceThemeScreen
import app.pwhs.blockads.ui.appmanagement.AppManagementScreen
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.dnsprovider.DnsProviderScreen
import app.pwhs.blockads.ui.httpsfiltering.HttpsFilteringScreen
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.logs.dialog.ConfirmClearLogDialog
import app.pwhs.blockads.ui.settings.component.AdvancedSection
import app.pwhs.blockads.ui.settings.component.ApplicationsSection
import app.pwhs.blockads.ui.settings.component.CommunitySection
import app.pwhs.blockads.ui.settings.component.ConfirmResetSettingsDialog
import app.pwhs.blockads.ui.settings.component.DataSection
import app.pwhs.blockads.ui.settings.component.DnsResponseTypeDialog
import app.pwhs.blockads.ui.settings.component.DnsResponseTypePane
import app.pwhs.blockads.ui.settings.component.FilterSetupSection
import app.pwhs.blockads.ui.settings.component.InformationSection
import app.pwhs.blockads.ui.settings.component.InterfaceSection
import app.pwhs.blockads.ui.settings.component.NotificationsSection
import app.pwhs.blockads.ui.settings.component.PrivacySection
import app.pwhs.blockads.ui.settings.component.ProtectionSection
import app.pwhs.blockads.ui.settings.component.SettingsOptionItem
import app.pwhs.blockads.ui.settings.component.SettingsOptionPane
import app.pwhs.blockads.ui.settings.geoip.GeoIpScreen
import app.pwhs.blockads.ui.tailscale.TailscaleScreen
import app.pwhs.blockads.ui.trustednetworks.TrustedNetworksScreen
import org.koin.androidx.compose.koinViewModel

enum class SettingsDetailPane {
    DNS_PROVIDER,
    DNS_RESPONSE_TYPE,
    UPDATE_FREQUENCY,
    UPDATE_NOTIFICATION,
    BUILD_MODE,
    LOG_RETENTION,
    HTTPS_FILTERING,
    APP_MANAGEMENT,
    TRUSTED_NETWORKS,
    TAILSCALE,
    GEO_IP,
    APPEARANCE_THEME,
    APPEARANCE_NAVIGATION,
    APPEARANCE_LANGUAGE,
    ABOUT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateToAbout: () -> Unit = { },
    onNavigateToTheme: () -> Unit = { },
    onNavigateToNavigation: () -> Unit = { },
    onNavigateToLanguage: () -> Unit = { },
    onNavigateToAppManagement: () -> Unit = { },
    onNavigateToTrustedNetworks: () -> Unit = { },
    onNavigateToWireGuardImport: () -> Unit = { },
    onNavigateToTailscale: () -> Unit = { },
    onNavigateToHttpsFiltering: () -> Unit = { },
    onNavigateToCertWizard: () -> Unit = { },
    onNavigateToDNSProvider: () -> Unit = { },
    onNavigateToGeoIp: () -> Unit = { },
    onNavigateToFilterSetup: () -> Unit = { },
    hasVpnInBottomBar: Boolean = false,
    hasFilterInBottomBar: Boolean = false,
    externalDetailPane: SettingsDetailPane? = null,
    onExternalDetailPaneConsumed: () -> Unit = {},
) {
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
    val autoUpdateFrequency by viewModel.autoUpdateFrequency.collectAsStateWithLifecycle()
    val autoUpdateWifiOnly by viewModel.autoUpdateWifiOnly.collectAsStateWithLifecycle()
    val autoUpdateNotification by viewModel.autoUpdateNotification.collectAsStateWithLifecycle()
    val filterBuildMode by viewModel.filterBuildMode.collectAsStateWithLifecycle()
    val crashReportingEnabled by viewModel.crashReportingEnabled.collectAsStateWithLifecycle()
    val hideFromRecents by viewModel.hideFromRecents.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val excludeLan by viewModel.excludeLan.collectAsStateWithLifecycle()
    val dnsResponseType by viewModel.dnsResponseType.collectAsStateWithLifecycle()
    val safeSearchEnabled by viewModel.safeSearchEnabled.collectAsStateWithLifecycle()

    val youtubeRestrictedMode by viewModel.youtubeRestrictedMode.collectAsStateWithLifecycle()
    val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsStateWithLifecycle()
    val milestoneNotificationsEnabled by viewModel.milestoneNotificationsEnabled.collectAsStateWithLifecycle()
    val homeTipsEnabled by viewModel.homeTipsEnabled.collectAsStateWithLifecycle()
    val upstreamDNS by viewModel.upstreamDns.collectAsStateWithLifecycle()
    val logRetentionDays by viewModel.logRetentionDays.collectAsStateWithLifecycle()

    var showDnsResponseTypeDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportSettings(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importSettings(it) } }

    UiEventEffect(viewModel.events)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isListDetail = rememberIsExpandedLayout(maxWidth)
        var activeDetailPane by rememberSaveable { mutableStateOf(SettingsDetailPane.DNS_PROVIDER) }

        LaunchedEffect(externalDetailPane) {
            if (externalDetailPane != null) {
                activeDetailPane = externalDetailPane
                onExternalDetailPaneConsumed()
            }
        }

            val settingsListContent: @Composable (Modifier) -> Unit = { listModifier ->
                Column(
                    modifier = listModifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    ProtectionSection(
                        autoReconnect = autoReconnect,
                        safeSearchEnabled = safeSearchEnabled,
                        youtubeRestrictedMode = youtubeRestrictedMode,
                        dnsResponseType = dnsResponseType,
                        upstreamDNS = upstreamDNS,
                        onSetAutoReconnect = { viewModel.setAutoReconnect(it) },
                        onSetSafeSearchEnabled = { viewModel.setSafeSearchEnabled(it) },
                        onSetYoutubeRestrictedMode = { viewModel.setYoutubeRestrictedMode(it) },
                        onShowDnsResponseTypeDialog = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.DNS_RESPONSE_TYPE
                            else showDnsResponseTypeDialog = true
                        },
                        onNavigateToDNSProvider = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.DNS_PROVIDER
                            else onNavigateToDNSProvider()
                        }
                    )

                    AdvancedSection(
                        routingMode = routingMode,
                        onSetRoutingMode = { viewModel.setRoutingModeEnabled(it) },
                        onNavigateToHttpsFiltering = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.HTTPS_FILTERING
                            else onNavigateToHttpsFiltering()
                        },
                        onNavigateToWireGuardImport = onNavigateToWireGuardImport,
                        onNavigateToTailscale = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.TAILSCALE
                            else onNavigateToTailscale()
                        },
                        showVpnSetting = !hasVpnInBottomBar
                    )

                    ApplicationsSection(
                        onNavigateToAppManagement = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.APP_MANAGEMENT
                            else onNavigateToAppManagement()
                        },
                        onNavigateToTrustedNetworks = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.TRUSTED_NETWORKS
                            else onNavigateToTrustedNetworks()
                        },
                        excludeLan = excludeLan,
                        onSetExcludeLan = { viewModel.setExcludeLan(it) }
                    )

                    FilterSetupSection(
                        modifier = Modifier.fillMaxWidth(),
                        autoUpdateNotification = autoUpdateNotification,
                        autoUpdateFrequency = autoUpdateFrequency,
                        autoUpdateWifiOnly = autoUpdateWifiOnly,
                        autoUpdateEnabled = autoUpdateEnabled,
                        filterBuildMode = filterBuildMode,
                        showFilterListSetting = !hasFilterInBottomBar,
                        onNavigateToFilterSetup = onNavigateToFilterSetup,
                        onSetFilterBuildMode = { viewModel.setFilterBuildMode(it) },
                        onSetAutoUpdateWifiOnly = { viewModel.setAutoUpdateWifiOnly(it) },
                        onSetAutoUpdateFrequency = { viewModel.setAutoUpdateFrequency(it) },
                        onSetAutoUpdateNotification = { viewModel.setAutoUpdateNotification(it) },
                        onSetAutoUpdateEnable = { viewModel.setAutoUpdateEnabled(it) },
                        onNavigateToGeoIp = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.GEO_IP
                            else onNavigateToGeoIp()
                        },
                        onOpenFrequencyPane = if (isListDetail) {
                            { activeDetailPane = SettingsDetailPane.UPDATE_FREQUENCY }
                        } else null,
                        onOpenNotificationPane = if (isListDetail) {
                            { activeDetailPane = SettingsDetailPane.UPDATE_NOTIFICATION }
                        } else null,
                        onOpenBuildModePane = if (isListDetail) {
                            { activeDetailPane = SettingsDetailPane.BUILD_MODE }
                        } else null
                    )

                    InterfaceSection(
                        onNavigateToTheme = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.APPEARANCE_THEME
                            else onNavigateToTheme()
                        },
                        onNavigateToNavigation = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.APPEARANCE_NAVIGATION
                            else onNavigateToNavigation()
                        },
                        onNavigateToLanguage = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.APPEARANCE_LANGUAGE
                            else onNavigateToLanguage()
                        }
                    )

                    NotificationsSection(
                        dailySummaryEnabled = dailySummaryEnabled,
                        milestoneNotificationsEnabled = milestoneNotificationsEnabled,
                        homeTipsEnabled = homeTipsEnabled,
                        onSetDailySummaryEnabled = { viewModel.setDailySummaryEnabled(it) },
                        onSetMilestoneNotificationsEnabled = { viewModel.setMilestoneNotificationsEnabled(it) },
                        onSetHomeTipsEnabled = { viewModel.setHomeTipsEnabled(it) }
                    )

                    PrivacySection(
                        crashReportingEnabled = crashReportingEnabled,
                        onSetCrashReportingEnabled = { viewModel.setCrashReportingEnabled(it) },
                        hideFromRecents = hideFromRecents,
                        onSetHideFromRecents = { viewModel.setHideFromRecents(it) }
                    )

                    DataSection(
                        onExport = { exportLauncher.launch("blockads_settings.json") },
                        onImport = { importLauncher.launch(arrayOf("application/json")) },
                        onClearLogs = { showClearConfirm = true },
                        onResetSettings = { showResetConfirm = true },
                        logRetentionDays = logRetentionDays,
                        onOpenLogRetentionDialog = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.LOG_RETENTION
                            else showRetentionDialog = true
                        }
                    )

                    InformationSection(
                        onNavigateToAbout = {
                            if (isListDetail) activeDetailPane = SettingsDetailPane.ABOUT
                            else onNavigateToAbout()
                        }
                    )

                    CommunitySection()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.navigationBarsPadding())
                    Spacer(modifier = Modifier.height(LocalFloatingBottomBarPadding.current))
                }
            }

            if (isListDetail) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .consumeWindowInsets(WindowInsets.statusBars)
                ) {
                    Scaffold(
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight(),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        stringResource(R.string.settings_title),
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background
                                )
                            )
                        }
                    ) { listPadding ->
                        settingsListContent(
                            Modifier
                                .fillMaxSize()
                                .padding(listPadding)
                        )
                    }

                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Box(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight()
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
                            SettingsDetailPane.APP_MANAGEMENT -> AppManagementScreen(onNavigateBack = {})
                            SettingsDetailPane.TRUSTED_NETWORKS -> TrustedNetworksScreen(onNavigateBack = {})
                            SettingsDetailPane.TAILSCALE -> TailscaleScreen(
                                showBackButton = false,
                                onNavigateBack = {}
                            )
                            SettingsDetailPane.HTTPS_FILTERING -> HttpsFilteringScreen(
                                showBackButton = false,
                                onNavigateBack = {},
                                onNavigateToWizard = onNavigateToCertWizard
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
                }
            } else {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    stringResource(R.string.settings_title),
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }
                ) { innerPadding ->
                    settingsListContent(
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }

        // Dialogs
        if (showDnsResponseTypeDialog) {
            DnsResponseTypeDialog(
                dnsResponseType = dnsResponseType,
                onUpdateResponseType = { type ->
                    viewModel.setDnsResponseType(type)
                    showDnsResponseTypeDialog = false
                },
                onDismiss = { showDnsResponseTypeDialog = false }
            )
        }

        if (showClearConfirm) {
            ConfirmClearLogDialog(
                onClear = {
                    viewModel.clearLogs()
                    showClearConfirm = false
                },
                onDismiss = { showClearConfirm = false }
            )
        }

        if (showRetentionDialog) {
            app.pwhs.blockads.ui.logs.dialog.LogRetentionDialog(
                selectedDays = logRetentionDays,
                onSelectDays = { viewModel.setLogRetentionDays(it) },
                onDismiss = { showRetentionDialog = false }
            )
        }

        if (showResetConfirm) {
            ConfirmResetSettingsDialog(
                onReset = {
                    viewModel.resetSettings()
                    showResetConfirm = false
                },
                onDismiss = { showResetConfirm = false }
            )
        }
    }
}