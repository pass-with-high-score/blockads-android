package app.pwhs.blockads.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.logs.dialog.ConfirmClearLogDialog
import app.pwhs.blockads.ui.settings.component.ApplicationsSection
import app.pwhs.blockads.ui.settings.component.CommunitySection
import app.pwhs.blockads.ui.settings.component.DataSection
import app.pwhs.blockads.ui.settings.component.DnsResponseTypeDialog
import app.pwhs.blockads.ui.settings.component.FilterSetupSection
import app.pwhs.blockads.ui.settings.component.InformationSection
import app.pwhs.blockads.ui.settings.component.InterfaceSection
import app.pwhs.blockads.ui.settings.component.NotificationsSection
import app.pwhs.blockads.ui.settings.component.PrivacySection
import app.pwhs.blockads.ui.settings.component.ProtectionSection
import app.pwhs.blockads.ui.settings.component.SectionHeader
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateToAbout: () -> Unit = { },
    onNavigateToAppearance: () -> Unit = { },
    onNavigateToAppManagement: () -> Unit = { },
    onNavigateToFilterSetup: () -> Unit = { },
    onNavigateToWhitelistApps: () -> Unit = { },
    onNavigateToWireGuardImport: () -> Unit = { },
    onNavigateToHttpsFiltering: () -> Unit = { },
    onNavigateToDNSProvider: () -> Unit = { },
) {
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val networkSwitchDelayEnabled by viewModel.networkSwitchDelayEnabled.collectAsStateWithLifecycle()
    val networkSwitchDelaySec by viewModel.networkSwitchDelaySec.collectAsStateWithLifecycle()
    val filterLists by viewModel.filterLists.collectAsStateWithLifecycle()
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
    val autoUpdateFrequency by viewModel.autoUpdateFrequency.collectAsStateWithLifecycle()
    val autoUpdateWifiOnly by viewModel.autoUpdateWifiOnly.collectAsStateWithLifecycle()
    val autoUpdateNotification by viewModel.autoUpdateNotification.collectAsStateWithLifecycle()
    val crashReportingEnabled by viewModel.crashReportingEnabled.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val dnsResponseType by viewModel.dnsResponseType.collectAsStateWithLifecycle()
    val safeSearchEnabled by viewModel.safeSearchEnabled.collectAsStateWithLifecycle()
    val youtubeRestrictedMode by viewModel.youtubeRestrictedMode.collectAsStateWithLifecycle()
    val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsStateWithLifecycle()
    val milestoneNotificationsEnabled by viewModel.milestoneNotificationsEnabled.collectAsStateWithLifecycle()
    val upstreamDNS by viewModel.upstreamDns.collectAsStateWithLifecycle()

    var showDnsResponseTypeDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── Protection ───────────────────────────────────────
            ProtectionSection(
                autoReconnect = autoReconnect,
                routingMode = routingMode,
                networkSwitchDelayEnabled = networkSwitchDelayEnabled,
                networkSwitchDelaySec = networkSwitchDelaySec,
                safeSearchEnabled = safeSearchEnabled,
                youtubeRestrictedMode = youtubeRestrictedMode,
                dnsResponseType = dnsResponseType,
                upstreamDNS = upstreamDNS,
                onSetAutoReconnect = { viewModel.setAutoReconnect(it) },
                onSetRoutingMode = { viewModel.setRoutingModeEnabled(it) },
                onSetNetworkSwitchDelayEnabled = { viewModel.setNetworkSwitchDelayEnabled(it) },
                onSetNetworkSwitchDelaySec = { viewModel.setNetworkSwitchDelaySec(it) },
                onSetSafeSearchEnabled = { viewModel.setSafeSearchEnabled(it) },
                onSetYoutubeRestrictedMode = { viewModel.setYoutubeRestrictedMode(it) },
                onShowDnsResponseTypeDialog = { showDnsResponseTypeDialog = true },
                onNavigateToDNSProvider = onNavigateToDNSProvider,
                onNavigateToWireGuardImport = onNavigateToWireGuardImport,
                onNavigateToHttpsFiltering = onNavigateToHttpsFiltering
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Interface ────────────────────────────────────────
            InterfaceSection(onNavigateToAppearance = onNavigateToAppearance)

            Spacer(modifier = Modifier.height(24.dp))

            // ── Applications ─────────────────────────────────────
            ApplicationsSection(
                onNavigateToWhitelistApps = onNavigateToWhitelistApps,
                onNavigateToAppManagement = onNavigateToAppManagement
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Filters ──────────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_category_filters),
                icon = Icons.Default.FilterList,
                description = stringResource(R.string.settings_category_filters_desc)
            )
            FilterSetupSection(
                modifier = Modifier.fillMaxWidth(),
                onNavigateToFilterSetup = onNavigateToFilterSetup,
                filterLists = filterLists,
                autoUpdateNotification = autoUpdateNotification,
                autoUpdateFrequency = autoUpdateFrequency,
                autoUpdateWifiOnly = autoUpdateWifiOnly,
                autoUpdateEnabled = autoUpdateEnabled,
                onSetAutoUpdateWifiOnly = { viewModel.setAutoUpdateWifiOnly(it) },
                onSetAutoUpdateFrequency = { viewModel.setAutoUpdateFrequency(it) },
                onSetAutoUpdateNotification = { viewModel.setAutoUpdateNotification(it) },
                onSetAutoUpdateEnable = { viewModel.setAutoUpdateEnabled(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Notifications ────────────────────────────────────
            NotificationsSection(
                dailySummaryEnabled = dailySummaryEnabled,
                milestoneNotificationsEnabled = milestoneNotificationsEnabled,
                onSetDailySummaryEnabled = { viewModel.setDailySummaryEnabled(it) },
                onSetMilestoneNotificationsEnabled = { viewModel.setMilestoneNotificationsEnabled(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Data ─────────────────────────────────────────────
            DataSection(
                onExport = { exportLauncher.launch("blockads_settings.json") },
                onImport = { importLauncher.launch(arrayOf("application/json")) },
                onClearLogs = { showClearConfirm = true },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Privacy & Diagnostics ────────────────────────────
            PrivacySection(
                crashReportingEnabled = crashReportingEnabled,
                onSetCrashReportingEnabled = { viewModel.setCrashReportingEnabled(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Information ──────────────────────────────────────
            InformationSection(onNavigateToAbout = onNavigateToAbout)


            Spacer(modifier = Modifier.height(24.dp))

            // ── Community ────────────────────────────────────────
            CommunitySection()

            Spacer(modifier = Modifier.height(200.dp))
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
    }
}