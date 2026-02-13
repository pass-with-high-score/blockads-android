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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBrightness
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
import app.pwhs.blockads.ui.settings.component.ThemeModeChip
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AboutScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppWhitelistScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FilterSetupScreenDestination
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
    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()

    // Auto-update Filter Lists
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsState()
    val autoUpdateFrequency by viewModel.autoUpdateFrequency.collectAsState()
    val autoUpdateWifiOnly by viewModel.autoUpdateWifiOnly.collectAsState()
    val autoUpdateNotification by viewModel.autoUpdateNotification.collectAsState()
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }

    val dnsResponseType by viewModel.dnsResponseType.collectAsState()
    var showDnsResponseTypeDialog by remember { mutableStateOf(false) }

    var editUpstreamDns by remember(upstreamDns) { mutableStateOf(upstreamDns) }
    var editFallbackDns by remember(fallbackDns) { mutableStateOf(fallbackDns) }
    var editDohUrl by remember(dohUrl) { mutableStateOf(dohUrl) }
    var showAddDomainDialog by remember { mutableStateOf(false) }

    // Search
    var searchQuery by remember { mutableStateOf("") }

    fun matchesSearch(keywords: List<String>): Boolean =
        searchQuery.isBlank() || keywords.any { it.contains(searchQuery.lowercase()) }

    val protectionKeywords = listOf("dns", "protocol", "reconnect", "doh", "dot", "upstream", "fallback", "server", "response", "nxdomain", "shield", "protection")
    val interfaceKeywords = listOf("theme", "language", "appearance", "dark", "light", "interface")
    val appsKeywords = listOf("app", "whitelist", "domain", "application", "exclude")
    val filtersKeywords = listOf("filter", "update", "auto-update", "frequency", "wifi", "notification", "list", "rule")
    val dataKeywords = listOf("export", "import", "backup", "clear", "log", "data")
    val infoKeywords = listOf("about", "version", "privacy", "source", "information")

    val showProtection by remember(searchQuery) { derivedStateOf { matchesSearch(protectionKeywords) } }
    val showInterface by remember(searchQuery) { derivedStateOf { matchesSearch(interfaceKeywords) } }
    val showApps by remember(searchQuery) { derivedStateOf { matchesSearch(appsKeywords) } }
    val showFilters by remember(searchQuery) { derivedStateOf { matchesSearch(filtersKeywords) } }
    val showData by remember(searchQuery) { derivedStateOf { matchesSearch(dataKeywords) } }
    val showInfo by remember(searchQuery) { derivedStateOf { matchesSearch(infoKeywords) } }

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
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.settings_search_hint),
                        tint = TextSecondary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                // Protection: DNS server, protocol, auto-reconnect
                if (showProtection) {
                    SectionHeader(
                        title = stringResource(R.string.settings_category_protection),
                        icon = Icons.Default.Shield,
                        description = stringResource(R.string.settings_category_protection_desc)
                    )
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
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                )
                                if (editDohUrl != dohUrl && isValidDohUrl) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { viewModel.setDohUrl(editDohUrl) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
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
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
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
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
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
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                )
                                if (editFallbackDns != fallbackDns) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { viewModel.setFallbackDns(editFallbackDns) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
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

                // Interface: Theme, language
                if (showInterface) {
                    SectionHeader(
                        title = stringResource(R.string.settings_category_interface),
                        icon = Icons.Default.Palette,
                        description = stringResource(R.string.settings_category_interface_desc)
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.DarkMode, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.settings_theme),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ThemeModeChip(
                                    label = stringResource(R.string.settings_theme_system),
                                    icon = Icons.Default.SettingsBrightness,
                                    selected = themeMode == AppPreferences.THEME_SYSTEM,
                                    onClick = { viewModel.setThemeMode(AppPreferences.THEME_SYSTEM) },
                                    modifier = Modifier.weight(1f)
                                )
                                ThemeModeChip(
                                    label = stringResource(R.string.settings_theme_light),
                                    icon = Icons.Default.LightMode,
                                    selected = themeMode == AppPreferences.THEME_LIGHT,
                                    onClick = { viewModel.setThemeMode(AppPreferences.THEME_LIGHT) },
                                    modifier = Modifier.weight(1f)
                                )
                                ThemeModeChip(
                                    label = stringResource(R.string.settings_theme_dark),
                                    icon = Icons.Default.DarkMode,
                                    selected = themeMode == AppPreferences.THEME_DARK,
                                    onClick = { viewModel.setThemeMode(AppPreferences.THEME_DARK) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Language, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.settings_language),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ThemeModeChip(
                                    label = stringResource(R.string.settings_lang_system),
                                    icon = Icons.Default.SettingsBrightness,
                                    selected = appLanguage == AppPreferences.LANGUAGE_SYSTEM,
                                    onClick = { viewModel.setAppLanguage(AppPreferences.LANGUAGE_SYSTEM) },
                                    modifier = Modifier.weight(1f)
                                )
                                ThemeModeChip(
                                    label = stringResource(R.string.settings_lang_en),
                                    icon = Icons.Default.Language,
                                    selected = appLanguage == AppPreferences.LANGUAGE_EN,
                                    onClick = { viewModel.setAppLanguage(AppPreferences.LANGUAGE_EN) },
                                    modifier = Modifier.weight(1f)
                                )
                                ThemeModeChip(
                                    label = stringResource(R.string.settings_lang_vi),
                                    icon = Icons.Default.Language,
                                    selected = appLanguage == AppPreferences.LANGUAGE_VI,
                                    onClick = { viewModel.setAppLanguage(AppPreferences.LANGUAGE_VI) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Applications: App whitelist, per-app settings
                if (showApps) {
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

                // Filters: Filter management, auto-update, custom rules
                if (showFilters) {
                    SectionHeader(
                        title = stringResource(R.string.settings_category_filters),
                        icon = Icons.Default.FilterList,
                        description = "Manage filter lists, auto-update, and custom rules via Filter setup"
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
                                                AppPreferences.UPDATE_FREQUENCY_6H -> stringResource(R.string.settings_auto_update_frequency_6h)
                                                AppPreferences.UPDATE_FREQUENCY_12H -> stringResource(R.string.settings_auto_update_frequency_12h)
                                                AppPreferences.UPDATE_FREQUENCY_24H -> stringResource(R.string.settings_auto_update_frequency_24h)
                                                AppPreferences.UPDATE_FREQUENCY_48H -> stringResource(R.string.settings_auto_update_frequency_48h)
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
                                                AppPreferences.NOTIFICATION_NORMAL -> stringResource(R.string.settings_auto_update_notification_normal)
                                                AppPreferences.NOTIFICATION_SILENT -> stringResource(R.string.settings_auto_update_notification_silent)
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
                if (showData) {
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
                }

                // Information: About
                if (showInfo) {
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
                }

                Spacer(modifier = Modifier.height(200.dp))
            }
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
