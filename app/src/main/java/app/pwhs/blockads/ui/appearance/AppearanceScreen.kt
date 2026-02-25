package app.pwhs.blockads.ui.appearance

import android.os.Build
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.ui.settings.component.SectionHeader
import app.pwhs.blockads.ui.settings.component.SettingsToggleItem
import app.pwhs.blockads.ui.theme.AccentBluePreset
import app.pwhs.blockads.ui.theme.AccentGreen
import app.pwhs.blockads.ui.theme.AccentOrange
import app.pwhs.blockads.ui.theme.AccentPink
import app.pwhs.blockads.ui.theme.AccentPurple
import app.pwhs.blockads.ui.theme.AccentTeal
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    navigator: DestinationsNavigator,
    viewModel: AppearanceViewModel = koinViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val accentColor by viewModel.accentColor.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_category_interface),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
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
            Spacer(modifier = Modifier.height(8.dp))

            // ── Theme ──────────────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_theme),
                icon = Icons.Default.DarkMode
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    val themes = listOf(
                        Triple(R.string.settings_theme_system, Icons.Default.SettingsBrightness, AppPreferences.THEME_SYSTEM),
                        Triple(R.string.settings_theme_light, Icons.Default.LightMode, AppPreferences.THEME_LIGHT),
                        Triple(R.string.settings_theme_dark, Icons.Default.DarkMode, AppPreferences.THEME_DARK),
                    )
                    themes.forEachIndexed { index, (labelRes, icon, themeCode) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setThemeMode(themeCode) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon, contentDescription = null,
                                tint = if (themeMode == themeCode) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(labelRes),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (themeMode == themeCode) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (themeMode == themeCode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            if (themeMode == themeCode) {
                                Icon(
                                    Icons.Default.Check, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (index < themes.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Accent Color ─────────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_accent_color),
                icon = Icons.Default.Palette
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_accent_color_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Preset color circles
                    val presetColors = listOf(
                        AppPreferences.ACCENT_GREEN to AccentGreen,
                        AppPreferences.ACCENT_BLUE to AccentBluePreset,
                        AppPreferences.ACCENT_PURPLE to AccentPurple,
                        AppPreferences.ACCENT_ORANGE to AccentOrange,
                        AppPreferences.ACCENT_PINK to AccentPink,
                        AppPreferences.ACCENT_TEAL to AccentTeal,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        presetColors.forEach { (colorKey, displayColor) ->
                            AccentColorCircle(
                                color = displayColor,
                                isSelected = accentColor == colorKey,
                                onClick = { viewModel.setAccentColor(colorKey) }
                            )
                        }
                    }

                    // Dynamic Color option (Android 12+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setAccentColor(AppPreferences.ACCENT_DYNAMIC) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Rainbow gradient circle for Dynamic
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.sweepGradient(
                                            listOf(
                                                Color(0xFFFF6B6B),
                                                Color(0xFFFFA500),
                                                Color(0xFFFFD700),
                                                Color(0xFF39D353),
                                                Color(0xFF4285F4),
                                                Color(0xFFA855F7),
                                                Color(0xFFFF6B6B),
                                            )
                                        )
                                    )
                                    .then(
                                        if (accentColor == AppPreferences.ACCENT_DYNAMIC) {
                                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        } else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (accentColor == AppPreferences.ACCENT_DYNAMIC) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.accent_dynamic),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (accentColor == AppPreferences.ACCENT_DYNAMIC)
                                        FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (accentColor == AppPreferences.ACCENT_DYNAMIC)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.accent_dynamic_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Language ───────────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_language),
                icon = Icons.Default.Language
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    val languages = listOf(
                        Triple(R.string.settings_lang_system, Icons.Default.SettingsBrightness, AppPreferences.LANGUAGE_SYSTEM),
                        Triple(R.string.settings_lang_en, Icons.Default.Language, AppPreferences.LANGUAGE_EN),
                        Triple(R.string.settings_lang_vi, Icons.Default.Language, AppPreferences.LANGUAGE_VI),
                        Triple(R.string.settings_lang_ja, Icons.Default.Language, AppPreferences.LANGUAGE_JA),
                        Triple(R.string.settings_lang_ko, Icons.Default.Language, AppPreferences.LANGUAGE_KO),
                        Triple(R.string.settings_lang_zh, Icons.Default.Language, AppPreferences.LANGUAGE_ZH),
                        Triple(R.string.settings_lang_th, Icons.Default.Language, AppPreferences.LANGUAGE_TH),
                        Triple(R.string.settings_lang_es, Icons.Default.Language, AppPreferences.LANGUAGE_ES),
                    )
                    languages.forEachIndexed { index, (labelRes, icon, langCode) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setAppLanguage(langCode) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon, contentDescription = null,
                                tint = if (appLanguage == langCode) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(labelRes),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (appLanguage == langCode) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (appLanguage == langCode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            if (appLanguage == langCode) {
                                Icon(
                                    Icons.Default.Check, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (index < languages.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

@Composable
private fun AccentColorCircle(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
        else Color.Transparent,
        label = "borderColor"
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = borderColor,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (isSelected) 34.dp else 40.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
