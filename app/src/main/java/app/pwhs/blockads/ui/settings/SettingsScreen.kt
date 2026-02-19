package app.pwhs.blockads.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.settings.component.AddDomainDialog
import app.pwhs.blockads.ui.settings.component.DnsProtocolSelector
import app.pwhs.blockads.ui.settings.component.DnsResponseTypeDialog
import app.pwhs.blockads.ui.settings.component.FrequencyDialog
import app.pwhs.blockads.ui.settings.component.NotificationDialog
import app.pwhs.blockads.ui.settings.component.SectionHeader
import app.pwhs.blockads.ui.settings.component.SettingsToggleItem
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AboutScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppearanceScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppManagementScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppWhitelistScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FilterSetupScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FirewallScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navigator: DestinationsNavigator,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val upstreamDns by viewModel.upstreamDns.collectAsState()
    val fallbackDns by viewModel.fallbackDns.collectAsState()
    val dnsProtocol by viewModel.dnsProtocol.collectAsState()
    val dohUrl by viewModel.dohUrl.collectAsState()
    val filterLists by viewModel.filterLists.collectAsState()
    val whitelistDomains by viewModel.whitelistDomains.collectAsState()


    // Auto-update Filter Lists
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsState()
    val autoUpdateFrequency by viewModel.autoUpdateFrequency.collectAsState()
    val autoUpdateWifiOnly by viewModel.autoUpdateWifiOnly.collectAsState()
    val autoUpdateNotification by viewModel.autoUpdateNotification.collectAsState()
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }

    val dnsResponseType by viewModel.dnsResponseType.collectAsState()
    var showDnsResponseTypeDialog by remember { mutableStateOf(false) }

    val safeSearchEnabled by viewModel.safeSearchEnabled.collectAsState()
    val youtubeRestrictedMode by viewModel.youtubeRestrictedMode.collectAsState()
    val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsState()
    val milestoneNotificationsEnabled by viewModel.milestoneNotificationsEnabled.collectAsState()


    var editUpstreamDns by remember(upstreamDns) { mutableStateOf(upstreamDns) }
    var editFallbackDns by remember(fallbackDns) { mutableStateOf(fallbackDns) }
    var editDohUrl by remember(dohUrl) { mutableStateOf(dohUrl) }
    var showAddDomainDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportSettings(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importSettings(it) } }

    UiEventEffect(viewModel.events)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                onClick = { navigator.navigate(com.ramcosta.composedestinations.generated.destinations.ProfileScreenDestination) },
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


                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    onClick = { navigator.navigate(com.ramcosta.composedestinations.generated.destinations.DnsProviderScreenDestination) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // DNS Protocol Selector
                        Text(
                            stringResource(R.string.settings_dns_protocol),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        DnsProtocolSelector(
                            selectedProtocol = dnsProtocol,
                            onProtocolSelected = { viewModel.setDnsProtocol(it) }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Show DoH URL input when DoH is selected
                        if (dnsProtocol == app.pwhs.blockads.data.DnsProtocol.DOH) {
                            Text(
                                stringResource(R.string.settings_doh_server_url),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Validate DoH URL
                            val isValidDohUrl = editDohUrl.isNotBlank() &&
                                    editDohUrl.startsWith("https://", ignoreCase = true)
                            val showDohError = editDohUrl.isNotBlank() && !isValidDohUrl

                            OutlinedTextField(
                                value = editDohUrl,
                                onValueChange = { editDohUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.settings_doh_url_placeholder)) },
                                singleLine = true,
                                isError = showDohError,
                                supportingText = if (showDohError) {
                                    { Text("DoH URL must start with https://") }
                                } else null,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(
                                        alpha = 0.3f
                                    )
                                )
                            )
                            if (editDohUrl != dohUrl && isValidDohUrl) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.setDohUrl(editDohUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.15f
                                        ),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) { Text(stringResource(R.string.settings_save_doh_url)) }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Show DNS server inputs for Plain DNS and DoT
                        if (dnsProtocol != app.pwhs.blockads.data.DnsProtocol.DOH) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    if (dnsProtocol == app.pwhs.blockads.data.DnsProtocol.DOT)
                                        stringResource(R.string.settings_dot_server)
                                    else
                                        stringResource(R.string.settings_upstream_dns),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = editUpstreamDns,
                                onValueChange = { editUpstreamDns = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        if (dnsProtocol == app.pwhs.blockads.data.DnsProtocol.DOT)
                                            stringResource(R.string.settings_dot_server_placeholder)
                                        else
                                            stringResource(R.string.settings_upstream_dns_placeholder)
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(
                                        alpha = 0.3f
                                    )
                                )
                            )
                            if (
                                dnsProtocol == app.pwhs.blockads.data.DnsProtocol.DOT ||
                                editUpstreamDns != upstreamDns
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.setUpstreamDns(editUpstreamDns) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.15f
                                        ),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) { Text(stringResource(R.string.settings_save_dns)) }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Fallback DNS (only for Plain DNS)
                        if (dnsProtocol == app.pwhs.blockads.data.DnsProtocol.PLAIN) {
                            Text(
                                stringResource(R.string.settings_fallback_dns),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = editFallbackDns,
                                onValueChange = { editFallbackDns = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.settings_fallback_dns_placeholder)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(
                                        alpha = 0.3f
                                    )
                                )
                            )
                            if (editFallbackDns != fallbackDns) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.setFallbackDns(editFallbackDns) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.15f
                                        ),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) { Text(stringResource(R.string.settings_save_dns)) }
                            }
                        }
                    }
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
                                "$upstreamDns / $fallbackDns",
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

            // Firewall (Per-App Internet Control)
            Card(
                onClick = { navigator.navigate(FirewallScreenDestination) },
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
                        Icons.Default.Security, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_firewall),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.settings_firewall_desc),
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


                Spacer(modifier = Modifier.height(12.dp))

                // Whitelist Domains
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.animateContentSize()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Block, contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_whitelist_domains),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    stringResource(R.string.settings_whitelist_domains_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        if (whitelistDomains.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }

                        whitelistDomains.forEach { domain ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = domain.domain,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                IconButton(
                                    onClick = { viewModel.removeWhitelistDomain(domain) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = TextSecondary.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        TextButton(
                            onClick = { showAddDomainDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_add_domain))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Filters: Filter management, auto-update, custom rules
            SectionHeader(
                title = stringResource(R.string.settings_category_filters),
                icon = Icons.Default.FilterList,
                description = stringResource(R.string.settings_category_filters_desc)
            )
            Card(
                onClick = { navigator.navigate(FilterSetupScreenDestination) },
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
                        Icons.Default.FilterList, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.filter_setup_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(
                                R.string.settings_filter_lists,
                                filterLists.count { it.isEnabled }
                            ),
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


                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsToggleItem(
                            icon = Icons.Default.Download,
                            title = stringResource(R.string.settings_auto_update_enabled),
                            subtitle = stringResource(R.string.settings_auto_update_enabled_desc),
                            isChecked = autoUpdateEnabled,
                            onCheckedChange = { viewModel.setAutoUpdateEnabled(it) }
                        )

                        if (autoUpdateEnabled) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )

                            // Update frequency
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showFrequencyDialog = true }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.settings_auto_update_frequency),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        when (autoUpdateFrequency) {
                                            AppPreferences.UPDATE_FREQUENCY_6H -> stringResource(
                                                R.string.settings_auto_update_frequency_6h
                                            )

                                            AppPreferences.UPDATE_FREQUENCY_12H -> stringResource(
                                                R.string.settings_auto_update_frequency_12h
                                            )

                                            AppPreferences.UPDATE_FREQUENCY_24H -> stringResource(
                                                R.string.settings_auto_update_frequency_24h
                                            )

                                            AppPreferences.UPDATE_FREQUENCY_48H -> stringResource(
                                                R.string.settings_auto_update_frequency_48h
                                            )

                                            AppPreferences.UPDATE_FREQUENCY_MANUAL -> stringResource(
                                                R.string.settings_auto_update_frequency_manual
                                            )

                                            else -> stringResource(R.string.settings_auto_update_frequency_24h)
                                        },
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
                                modifier = Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )

                            // Wi-Fi only
                            SettingsToggleItem(
                                icon = Icons.Default.Wifi,
                                title = stringResource(R.string.settings_auto_update_wifi_only),
                                subtitle = stringResource(R.string.settings_auto_update_wifi_only_desc),
                                isChecked = autoUpdateWifiOnly,
                                onCheckedChange = { viewModel.setAutoUpdateWifiOnly(it) }
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )

                            // Notification preference
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showNotificationDialog = true }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.settings_auto_update_notification),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        when (autoUpdateNotification) {
                                            AppPreferences.NOTIFICATION_NORMAL -> stringResource(
                                                R.string.settings_auto_update_notification_normal
                                            )

                                            AppPreferences.NOTIFICATION_SILENT -> stringResource(
                                                R.string.settings_auto_update_notification_silent
                                            )

                                            AppPreferences.NOTIFICATION_NONE -> stringResource(R.string.settings_auto_update_notification_none)
                                            else -> stringResource(R.string.settings_auto_update_notification_normal)
                                        },
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
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Data: Export/Import, clear logs


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

            Spacer(modifier = Modifier.height(200.dp))
        }


        // Add domain whitelist dialog
        if (showAddDomainDialog) {
            AddDomainDialog(
                onDismiss = { showAddDomainDialog = false },
                onAdd = { domain ->
                    viewModel.addWhitelistDomain(domain)
                    showAddDomainDialog = false
                }
            )
        }


        // Frequency dialog
        if (showFrequencyDialog) {
            FrequencyDialog(
                autoUpdateFrequency = autoUpdateFrequency,
                onUpdateFrequencyChange = { freq ->
                    viewModel.setAutoUpdateFrequency(freq)
                    showFrequencyDialog = false
                },
                onDismiss = { showFrequencyDialog = false }
            )
        }

        // Notification dialog
        if (showNotificationDialog) {
            NotificationDialog(
                autoUpdateNotification = autoUpdateNotification,
                onUpdateNotification = { type ->
                    viewModel.setAutoUpdateNotification(type)
                    showNotificationDialog = false
                },
                onDismiss = { showNotificationDialog = false }
            )
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