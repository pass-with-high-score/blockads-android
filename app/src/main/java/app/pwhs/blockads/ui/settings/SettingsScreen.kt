package app.pwhs.blockads.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.FilterList
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.NeonGreen
import app.pwhs.blockads.ui.theme.TextSecondary
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AboutScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppWhitelistScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navigator: DestinationsNavigator,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val upstreamDns by viewModel.upstreamDns.collectAsState()
    val filterLists by viewModel.filterLists.collectAsState()
    val isUpdatingFilter by viewModel.isUpdatingFilter.collectAsState()
    val whitelistDomains by viewModel.whitelistDomains.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()

    var editUpstreamDns by remember(upstreamDns) { mutableStateOf(upstreamDns) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddDomainDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportSettings(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importSettings(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Appearance
            SectionHeader(stringResource(R.string.settings_appearance))
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

            // Connection
            SectionHeader(stringResource(R.string.settings_connection))
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

            Spacer(modifier = Modifier.height(24.dp))

            // Filter Lists
            SectionHeader(
                stringResource(
                    R.string.settings_filter_lists,
                    filterLists.count { it.isEnabled })
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.animateContentSize()
            ) {
                Column {
                    filterLists.forEachIndexed { index, filter ->
                        FilterListItem(
                            filter = filter,
                            onToggle = { viewModel.toggleFilterList(filter) },
                            onDelete = { viewModel.deleteFilterList(filter) }
                        )
                        if (index < filterLists.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }

                    // Add button
                    TextButton(
                        onClick = { showAddDialog = true },
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
                        Text(stringResource(R.string.settings_add_custom_filter))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Update all button
            Button(
                onClick = { viewModel.updateAllFilters() },
                enabled = !isUpdatingFilter,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isUpdatingFilter) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_updating))
                } else {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_update_all))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // DNS
            SectionHeader(stringResource(R.string.settings_dns_config))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.settings_upstream_dns),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editUpstreamDns,
                        onValueChange = { editUpstreamDns = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("8.8.8.8") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    )
                    if (editUpstreamDns != upstreamDns) {
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
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Whitelist Apps
            SectionHeader(stringResource(R.string.settings_whitelist))
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

            // Data
            SectionHeader(stringResource(R.string.settings_data_backup))

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

            // About
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
    }

    // Add filter dialog
    if (showAddDialog) {
        AddFilterDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                viewModel.addFilterList(name, url)
                showAddDialog = false
            }
        )
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
}

@Composable
private fun FilterListItem(
    filter: FilterList,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = filter.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (filter.isEnabled) MaterialTheme.colorScheme.onBackground
                else TextSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filter.domainCount > 0) {
                    Text(
                        text = "${formatCount(filter.domainCount)} rules",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                if (filter.lastUpdated > 0) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = formatDate(filter.lastUpdated),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Text(
                text = filter.url,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }

        Switch(
            checked = filter.isEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = NeonGreen
            )
        )
    }
}

@Composable
private fun AddFilterDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_filter_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_add_filter_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_add_filter_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onAdd(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text(stringResource(R.string.settings_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}

@Composable
private fun AddDomainDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var domain by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_domain_title)) },
        text = {
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text(stringResource(R.string.settings_add_domain_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (domain.isNotBlank()) onAdd(domain) },
                enabled = domain.isNotBlank()
            ) { Text(stringResource(R.string.settings_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = NeonGreen
            )
        )
    }
}

private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
    count >= 1_000 -> String.format("%.1fK", count / 1_000f)
    else -> count.toString()
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
private fun ThemeModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    else Color.Transparent

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
