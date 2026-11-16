package app.pwhs.blockads.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.logs.dialog.ConfirmClearLogDialog
import app.pwhs.blockads.ui.settings.component.AdvancedSection
import app.pwhs.blockads.ui.settings.component.ApplicationsSection
import app.pwhs.blockads.ui.settings.component.CommunitySection
import app.pwhs.blockads.ui.settings.component.ConfirmResetSettingsDialog
import app.pwhs.blockads.ui.settings.component.DataSection
import app.pwhs.blockads.ui.settings.component.DnsResponseTypeDialog
import app.pwhs.blockads.ui.settings.component.FilterSetupSection
import app.pwhs.blockads.ui.settings.component.InformationSection
import app.pwhs.blockads.ui.settings.component.InterfaceSection
import app.pwhs.blockads.ui.settings.component.NotificationsSection
import app.pwhs.blockads.ui.settings.component.PrivacySection
import app.pwhs.blockads.ui.settings.component.ProtectionSection
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateToAbout: () -> Unit = { },
    onNavigateToAppearance: () -> Unit = { },
    onNavigateToAppManagement: () -> Unit = { },
    onNavigateToTrustedNetworks: () -> Unit = { },
    onNavigateToWireGuardImport: () -> Unit = { },
    onNavigateToTailscale: () -> Unit = { },
    onNavigateToHttpsFiltering: () -> Unit = { },
    onNavigateToDNSProvider: () -> Unit = { },
    onNavigateToGeoIp: () -> Unit = { },
    onNavigateToFilterSetup: () -> Unit = { },
    hasVpnInBottomBar: Boolean = false,
    hasFilterInBottomBar: Boolean = false,
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

    Scaffold(
        modifier = modifier,
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
        val protectionContent: @Composable () -> Unit = {
            ProtectionSection(
                autoReconnect = autoReconnect,
                safeSearchEnabled = safeSearchEnabled,
                youtubeRestrictedMode = youtubeRestrictedMode,
                dnsResponseType = dnsResponseType,
                upstreamDNS = upstreamDNS,
                onSetAutoReconnect = { viewModel.setAutoReconnect(it) },
                onSetSafeSearchEnabled = { viewModel.setSafeSearchEnabled(it) },
                onSetYoutubeRestrictedMode = { viewModel.setYoutubeRestrictedMode(it) },
                onShowDnsResponseTypeDialog = { showDnsResponseTypeDialog = true },
                onNavigateToDNSProvider = onNavigateToDNSProvider
            )
        }
        val advancedContent: @Composable () -> Unit = {
            AdvancedSection(
                routingMode = routingMode,
                onSetRoutingMode = { viewModel.setRoutingModeEnabled(it) },
                onNavigateToHttpsFiltering = onNavigateToHttpsFiltering,
                onNavigateToWireGuardImport = onNavigateToWireGuardImport,
                onNavigateToTailscale = onNavigateToTailscale,
                showVpnSetting = !hasVpnInBottomBar
            )
        }
        val applicationsContent: @Composable () -> Unit = {
            ApplicationsSection(
                onNavigateToAppManagement = onNavigateToAppManagement,
                onNavigateToTrustedNetworks = onNavigateToTrustedNetworks,
                excludeLan = excludeLan,
                onSetExcludeLan = { viewModel.setExcludeLan(it) }
            )
        }
        val filterSetupContent: @Composable () -> Unit = {
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
                onNavigateToGeoIp = onNavigateToGeoIp
            )
        }
        val interfaceContent: @Composable () -> Unit = {
            InterfaceSection(onNavigateToAppearance = onNavigateToAppearance)
        }
        val notificationsContent: @Composable () -> Unit = {
            NotificationsSection(
                dailySummaryEnabled = dailySummaryEnabled,
                milestoneNotificationsEnabled = milestoneNotificationsEnabled,
                homeTipsEnabled = homeTipsEnabled,
                onSetDailySummaryEnabled = { viewModel.setDailySummaryEnabled(it) },
                onSetMilestoneNotificationsEnabled = { viewModel.setMilestoneNotificationsEnabled(it) },
                onSetHomeTipsEnabled = { viewModel.setHomeTipsEnabled(it) }
            )
        }
        val privacyContent: @Composable () -> Unit = {
            PrivacySection(
                crashReportingEnabled = crashReportingEnabled,
                onSetCrashReportingEnabled = { viewModel.setCrashReportingEnabled(it) },
                hideFromRecents = hideFromRecents,
                onSetHideFromRecents = { viewModel.setHideFromRecents(it) }
            )
        }
        val dataContent: @Composable () -> Unit = {
            DataSection(
                onExport = { exportLauncher.launch("blockads_settings.json") },
                onImport = { importLauncher.launch(arrayOf("application/json")) },
                onClearLogs = { showClearConfirm = true },
                onResetSettings = { showResetConfirm = true },
                logRetentionDays = logRetentionDays,
                onOpenLogRetentionDialog = { showRetentionDialog = true }
            )
        }
        val informationContent: @Composable () -> Unit = {
            InformationSection(onNavigateToAbout = onNavigateToAbout)
        }
        val communityContent: @Composable () -> Unit = {
            CommunitySection()
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isTwoColumn = maxWidth >= 600.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isTwoColumn) 24.dp else 16.dp)
            ) {
                if (isTwoColumn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            protectionContent()
                            applicationsContent()
                            interfaceContent()
                            privacyContent()
                            informationContent()
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            advancedContent()
                            filterSetupContent()
                            notificationsContent()
                            dataContent()
                            communityContent()
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        protectionContent()
                        advancedContent()
                        applicationsContent()
                        filterSetupContent()
                        interfaceContent()
                        notificationsContent()
                        privacyContent()
                        dataContent()
                        informationContent()
                        communityContent()
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Medium
                    )
                }
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