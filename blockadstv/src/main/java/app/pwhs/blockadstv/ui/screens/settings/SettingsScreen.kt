package app.pwhs.blockadstv.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import app.pwhs.blockadstv.utils.TvLocaleHelper
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    tvPrefs: TvPreferences = koinInject(),
    filterRepo: FilterListRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startOnBoot by tvPrefs.startOnBoot.collectAsStateWithLifecycle(initialValue = false)
    val currentResponseType by tvPrefs.dnsResponseType.collectAsStateWithLifecycle(
        initialValue = TvPreferences.DNS_RESPONSE_CUSTOM_IP
    )
    val themeMode by tvPrefs.themeMode.collectAsStateWithLifecycle(
        initialValue = TvPreferences.THEME_DARK
    )
    val appLanguage by tvPrefs.appLanguage.collectAsStateWithLifecycle(
        initialValue = TvPreferences.LANGUAGE_SYSTEM
    )
    val routingMode by tvPrefs.routingMode.collectAsStateWithLifecycle(
        initialValue = TvPreferences.ROUTING_MODE_DIRECT
    )

    var isUpdatingFilters by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    var showResponseTypeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }

    if (showModeDialog) {
        ModeSelectionDialog(
            currentMode = routingMode,
            onDismiss = { showModeDialog = false },
            onSelect = { selectedMode ->
                scope.launch {
                    tvPrefs.setRoutingMode(selectedMode)
                    app.pwhs.blockadstv.service.ServiceController.requestRestart(context)
                }
                showModeDialog = false
            },
        )
    }

    if (showResponseTypeDialog) {
        ResponseTypeDialog(
            currentType = currentResponseType,
            onSelect = { type ->
                scope.launch { tvPrefs.setDnsResponseType(type) }
                showResponseTypeDialog = false
            },
            onDismiss = { showResponseTypeDialog = false },
        )
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = themeMode,
            onDismiss = { showThemeDialog = false },
            onSelect = { selectedTheme ->
                scope.launch { tvPrefs.setThemeMode(selectedTheme) }
                showThemeDialog = false
            },
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = appLanguage,
            onDismiss = { showLanguageDialog = false },
            onSelect = { selectedLang ->
                scope.launch {
                    tvPrefs.setAppLanguage(selectedLang)
                    TvLocaleHelper.setLocale(context, selectedLang)
                }
                showLanguageDialog = false
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_setting),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Start on boot
            item {
                SettingListItem(
                    iconRes = R.drawable.ic_settings_auto_reconnect,
                    title = stringResource(R.string.settings_start_on_boot),
                    subtitle = stringResource(R.string.settings_start_on_boot_desc),
                    isToggle = true,
                    isEnabled = startOnBoot,
                    onClick = {
                        scope.launch { tvPrefs.setStartOnBoot(!startOnBoot) }
                    },
                )
            }

            // Protection Mode
            item {
                SettingListItem(
                    iconRes = R.drawable.ic_nav_proxy,
                    title = stringResource(R.string.settings_mode_title),
                    subtitle = when (routingMode) {
                        TvPreferences.ROUTING_MODE_ROOT -> stringResource(R.string.proxy_mode_root)
                        TvPreferences.ROUTING_MODE_PROXY_VPN -> stringResource(R.string.proxy_mode_proxy_vpn)
                        else -> stringResource(R.string.proxy_mode_direct)
                    },
                    isToggle = false,
                    isEnabled = false,
                    onClick = { showModeDialog = true },
                )
            }

            // Block Response Type
            item {
                SettingListItem(
                    iconRes = R.drawable.ic_settings_block_mode,
                    title = stringResource(R.string.settings_response_type_title),
                    subtitle = when (currentResponseType) {
                        "NXDOMAIN" -> "NXDOMAIN · Domain does not exist"
                        "REFUSED" -> "REFUSED · Query refused by resolver"
                        else -> "0.0.0.0 · Null IP address (Standard block)"
                    },
                    isToggle = false,
                    isEnabled = false,
                    onClick = { showResponseTypeDialog = true },
                )
            }

            // Theme Setting
            item {
                SettingListItem(
                    iconRes = R.drawable.ic_setting,
                    title = stringResource(R.string.settings_theme_title),
                    subtitle = when (themeMode) {
                        TvPreferences.THEME_OLED -> stringResource(R.string.settings_theme_oled)
                        TvPreferences.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
                        TvPreferences.THEME_SYSTEM -> stringResource(R.string.settings_theme_system)
                        else -> stringResource(R.string.settings_theme_dark)
                    },
                    isToggle = false,
                    isEnabled = false,
                    onClick = { showThemeDialog = true },
                )
            }

            // Language Setting
            item {
                SettingListItem(
                    iconRes = R.drawable.ic_setting,
                    title = stringResource(R.string.settings_language_title),
                    subtitle = getLanguageDisplayName(appLanguage),
                    isToggle = false,
                    isEnabled = false,
                    onClick = { showLanguageDialog = true },
                )
            }

            // Update filter lists
            item {
                SettingListItem(
                    iconRes = R.drawable.ic_settings_auto_update,
                    title = stringResource(R.string.settings_update_filters),
                    subtitle = when {
                        isUpdatingFilters -> "Updating..."
                        updateResult != null -> updateResult!!
                        else -> stringResource(R.string.settings_update_filters_desc)
                    },
                    isToggle = false,
                    isEnabled = false,
                    onClick = {
                        if (!isUpdatingFilters) {
                            isUpdatingFilters = true
                            updateResult = null
                            scope.launch {
                                try {
                                    val result = filterRepo.forceReloadAllFilters()
                                    val count = result.getOrDefault(0)
                                    updateResult = "Updated: $count rules loaded"
                                } catch (e: Exception) {
                                    updateResult = "Update failed: ${e.message}"
                                }
                                isUpdatingFilters = false
                            }
                        }
                    },
                )
            }

            // About
            item {
                SettingListItem(
                    iconRes = R.drawable.ic_shield,
                    title = stringResource(R.string.settings_about),
                    subtitle = "BlockAds TV v${app.pwhs.blockadstv.BuildConfig.VERSION_NAME} (Build ${app.pwhs.blockadstv.BuildConfig.VERSION_CODE})",
                    isToggle = false,
                    isEnabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingListItem(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    isToggle: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isToggle) {
            TvSwitch(
                checked = isEnabled,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResponseTypeDialog(
    currentType: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, NeonGreen, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_block_mode),
                        contentDescription = null,
                        tint = NeonGreen,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Block Response Type",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select how blocked domains respond to client queries:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                listOf(
                    Triple("0.0.0.0 (Null IP)", "Standard block address, fastest response", "0.0.0.0"),
                    Triple("NXDOMAIN", "Domain does not exist, standard DNS response", "NXDOMAIN"),
                    Triple("REFUSED", "Query refused by resolver", "REFUSED"),
                ).forEach { (label, desc, value) ->
                    var isFocused by remember { mutableStateOf(false) }
                    val isSelected = currentType == value

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) NeonGreen.copy(alpha = 0.15f)
                                else if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .border(
                                width = if (isFocused || isSelected) 2.dp else 1.dp,
                                color = if (isFocused || isSelected) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .onFocusChanged { isFocused = it.isFocused }
                            .clickable(onClick = { onSelect(value) })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) NeonGreen else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = "Selected",
                                tint = NeonGreen,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun getLanguageDisplayName(code: String): String = when (code) {
    TvPreferences.LANGUAGE_VI -> stringResource(R.string.settings_lang_vi) + " (🇻🇳)"
    TvPreferences.LANGUAGE_EN -> stringResource(R.string.settings_lang_en) + " (🇺🇸)"
    TvPreferences.LANGUAGE_AR -> stringResource(R.string.settings_lang_ar) + " (🇸🇦)"
    TvPreferences.LANGUAGE_CS -> stringResource(R.string.settings_lang_cs) + " (🇨🇿)"
    TvPreferences.LANGUAGE_DE -> stringResource(R.string.settings_lang_de) + " (🇩🇪)"
    TvPreferences.LANGUAGE_ES -> stringResource(R.string.settings_lang_es) + " (🇪🇸)"
    TvPreferences.LANGUAGE_FR -> stringResource(R.string.settings_lang_fr) + " (🇫🇷)"
    TvPreferences.LANGUAGE_IN -> stringResource(R.string.settings_lang_in) + " (🇮🇩)"
    TvPreferences.LANGUAGE_IT -> stringResource(R.string.settings_lang_it) + " (🇮🇹)"
    TvPreferences.LANGUAGE_IW -> stringResource(R.string.settings_lang_iw) + " (🇮🇱)"
    TvPreferences.LANGUAGE_JA -> stringResource(R.string.settings_lang_ja) + " (🇯🇵)"
    TvPreferences.LANGUAGE_KK -> stringResource(R.string.settings_lang_kk) + " (🇰🇿)"
    TvPreferences.LANGUAGE_KO -> stringResource(R.string.settings_lang_ko) + " (🇰🇷)"
    TvPreferences.LANGUAGE_PL -> stringResource(R.string.settings_lang_pl) + " (🇵🇱)"
    TvPreferences.LANGUAGE_PT_BR -> stringResource(R.string.settings_lang_pt_br) + " (🇧🇷)"
    TvPreferences.LANGUAGE_RU -> stringResource(R.string.settings_lang_ru) + " (🇷🇺)"
    TvPreferences.LANGUAGE_TH -> stringResource(R.string.settings_lang_th) + " (🇹🇭)"
    TvPreferences.LANGUAGE_TR -> stringResource(R.string.settings_lang_tr) + " (🇹🇷)"
    TvPreferences.LANGUAGE_UK -> stringResource(R.string.settings_lang_uk) + " (🇺🇦)"
    TvPreferences.LANGUAGE_ZH -> stringResource(R.string.settings_lang_zh) + " (🇨🇳)"
    else -> stringResource(R.string.settings_lang_system)
}
