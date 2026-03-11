package app.pwhs.blockads.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.settings.component.DnsResponseTypeDialog
import app.pwhs.blockads.ui.settings.component.FireWall
import app.pwhs.blockads.ui.settings.component.SectionHeader
import app.pwhs.blockads.ui.settings.component.SettingsToggleItem
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AboutScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppManagementScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppWhitelistScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppearanceScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DnsProviderScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FilterSetupScreenDestination
import com.ramcosta.composedestinations.generated.destinations.BlocklistDomainScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FirewallScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ProfileScreenDestination
import com.ramcosta.composedestinations.generated.destinations.WhitelistDomainScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navigator: DestinationsNavigator,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val upstreamDns by viewModel.upstreamDns.collectAsStateWithLifecycle()
    val fallbackDns by viewModel.fallbackDns.collectAsStateWithLifecycle()
    val customDnsDisplay by viewModel.customDnsDisplay.collectAsStateWithLifecycle()
    val filterLists by viewModel.filterLists.collectAsStateWithLifecycle()
    val whitelistDomains by viewModel.whitelistDomains.collectAsStateWithLifecycle()
    val blocklistDomainsCount by viewModel.blocklistDomainsCount.collectAsStateWithLifecycle()

    // Auto-update Filter Lists
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
    val autoUpdateFrequency by viewModel.autoUpdateFrequency.collectAsStateWithLifecycle()
    val autoUpdateWifiOnly by viewModel.autoUpdateWifiOnly.collectAsStateWithLifecycle()
    val autoUpdateNotification by viewModel.autoUpdateNotification.collectAsStateWithLifecycle()

    val dnsResponseType by viewModel.dnsResponseType.collectAsStateWithLifecycle()
    var showDnsResponseTypeDialog by remember { mutableStateOf(false) }

    val safeSearchEnabled by viewModel.safeSearchEnabled.collectAsStateWithLifecycle()
    val youtubeRestrictedMode by viewModel.youtubeRestrictedMode.collectAsStateWithLifecycle()
    val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsStateWithLifecycle()
    val milestoneNotificationsEnabled by viewModel.milestoneNotificationsEnabled.collectAsStateWithLifecycle()

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
            // Protection: DNS server, protocol, auto-reconnect
            SectionHeader(
                title = stringResource(R.string.settings_category_protection),
                icon = Icons.Default.Shield,
                description = stringResource(R.string.settings_category_protection_desc)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsToggleItem(
                    icon = Icons.Default.Replay,
                    title = stringResource(R.string.settings_auto_reconnect),
                    subtitle = stringResource(R.string.settings_auto_reconnect_desc),
                    isChecked = autoReconnect,
                    onCheckedChange = { viewModel.setAutoReconnect(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsToggleItem(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.settings_safe_search),
                    subtitle = stringResource(R.string.settings_safe_search_desc),
                    isChecked = safeSearchEnabled,
                    onCheckedChange = { viewModel.setSafeSearchEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsToggleItem(
                    icon = Icons.Default.OndemandVideo,
                    title = stringResource(R.string.settings_youtube_restricted),
                    subtitle = stringResource(R.string.settings_youtube_restricted_desc),
                    isChecked = youtubeRestrictedMode,
                    onCheckedChange = { viewModel.setYoutubeRestrictedMode(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                onClick = { navigator.navigate(ProfileScreenDestination) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_profiles),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.settings_profiles_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    onClick = { navigator.navigate(DnsProviderScreenDestination) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.dns_select_server),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (fallbackDns.isBlank()) customDnsDisplay else "$customDnsDisplay / $fallbackDns",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // DNS Response Type
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDnsResponseTypeDialog = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_dns_response_type),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                when (dnsResponseType) {
                                    AppPreferences.DNS_RESPONSE_NXDOMAIN ->
                                        stringResource(R.string.dns_response_nxdomain)

                                    AppPreferences.DNS_RESPONSE_REFUSED ->
                                        stringResource(R.string.dns_response_refused)

                                    else ->
                                        stringResource(R.string.dns_response_custom_ip)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interface: Theme, language
            SectionHeader(
                title = stringResource(R.string.settings_category_interface),
                icon = Icons.Default.Palette,
                description = stringResource(R.string.settings_category_interface_desc)
            )
            Card(
                onClick = { navigator.navigate(AppearanceScreenDestination) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Palette, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_category_interface),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.settings_category_interface_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Applications: App whitelist, per-app settings
            SectionHeader(
                title = stringResource(R.string.settings_category_apps),
                icon = Icons.Default.PhoneAndroid,
                description = stringResource(R.string.settings_category_apps_desc)
            )
            Card(
                onClick = { navigator.navigate(AppWhitelistScreenDestination) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AppBlocking, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_whitelist_apps),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.settings_whitelist_apps_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // App Management
            Card(
                onClick = { navigator.navigate(AppManagementScreenDestination) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Apps, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.app_management_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.app_management_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Filters: Filter management, auto-update, custom rules
            SectionHeader(
                title = stringResource(R.string.settings_category_filters),
                icon = Icons.Default.FilterList,
                description = stringResource(R.string.settings_category_filters_desc)
            )
            // Firewall (Per-App Internet Control)
            FireWall(
                modifier = Modifier.fillMaxWidth(),
                onNavigateToFirewall = {
                    navigator.navigate(FirewallScreenDestination)
                },
                onNavigateToFilterSetup = {
                    navigator.navigate(FilterSetupScreenDestination)
                },
                onNavigateToWhitelistDomains = {
                    navigator.navigate(WhitelistDomainScreenDestination)
                },
                onNavigateToBlocklistDomains = {
                    navigator.navigate(BlocklistDomainScreenDestination)
                },
                whitelistCount = whitelistDomains.size,
                blocklistCount = blocklistDomainsCount,
                filterLists = filterLists,
                autoUpdateNotification = autoUpdateNotification,
                autoUpdateFrequency = autoUpdateFrequency,
                autoUpdateWifiOnly = autoUpdateWifiOnly,
                autoUpdateEnabled = autoUpdateEnabled,
                onSetAutoUpdateWifiOnly = {
                    viewModel.setAutoUpdateWifiOnly(it)
                },
                onSetAutoUpdateFrequency = {
                    viewModel.setAutoUpdateFrequency(it)
                },
                onSetAutoUpdateNotification = {
                    viewModel.setAutoUpdateNotification(it)
                },
                onSetAutoUpdateEnable = {
                    viewModel.setAutoUpdateEnabled(it)
                }
            )

            // Data: Export/Import, clear logs
            Spacer(modifier = Modifier.height(24.dp))

            // Notifications: Daily summary, milestones
            SectionHeader(
                title = stringResource(R.string.settings_category_notifications),
                icon = Icons.Default.Notifications,
                description = stringResource(R.string.settings_category_notifications_desc)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsToggleItem(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_daily_summary),
                    subtitle = stringResource(R.string.settings_daily_summary_desc),
                    isChecked = dailySummaryEnabled,
                    onCheckedChange = { viewModel.setDailySummaryEnabled(it) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsToggleItem(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_milestone_notifications),
                    subtitle = stringResource(R.string.settings_milestone_notifications_desc),
                    isChecked = milestoneNotificationsEnabled,
                    onCheckedChange = { viewModel.setMilestoneNotificationsEnabled(it) }
                )
            }


            Spacer(modifier = Modifier.height(24.dp))

            // Data: Export/Import
            SectionHeader(
                title = stringResource(R.string.settings_category_data),
                icon = Icons.Default.Storage,
                description = stringResource(R.string.settings_category_data_desc)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { exportLauncher.launch("blockads_settings.json") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_export))
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_import))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { viewModel.clearLogs() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerRed.copy(alpha = 0.1f),
                    contentColor = DangerRed
                )
            ) {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_clear_logs))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Information: About
            SectionHeader(
                title = stringResource(R.string.settings_category_info),
                icon = Icons.Default.Info,
                description = stringResource(R.string.settings_category_info_desc)
            )
            Card(
                onClick = { navigator.navigate(AboutScreenDestination) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_about),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.settings_about_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val context = LocalContext.current

            // Sponsor
            Card(
                onClick = {
                    val uri = "https://github.com/sponsors/pass-with-high-score".toUri()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Favorite, contentDescription = null,
                        tint = Color(0xFFE91E63),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_sponsor),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.settings_sponsor_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Community ─────────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_community),
                icon = Icons.AutoMirrored.Filled.Chat,
                description = stringResource(R.string.settings_category_info_desc)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    // Reddit
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val uri = "https://www.reddit.com/r/BlockAds/".toUri()
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_reddit),
                            contentDescription = null,
                            tint = Color(0xFFFF4500),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_reddit),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.settings_reddit_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )

                    // Telegram
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val uri = "https://t.me/blockads_android".toUri()
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_telegram),
                            contentDescription = null,
                            tint = Color(0xFF0088CC),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_telegram),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.settings_telegram_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(200.dp))
        }


        // DNS Response Type dialog
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
    }
}