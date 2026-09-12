package app.pwhs.blockads.ui.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.appearance.component.AccentColorCard
import app.pwhs.blockads.ui.appearance.component.ColorPickerDialog
import app.pwhs.blockads.ui.appearance.component.LanguageSelectionCard
import app.pwhs.blockads.ui.appearance.component.NavigationCard
import app.pwhs.blockads.ui.appearance.component.ThemeSelectionCard
import app.pwhs.blockads.ui.settings.component.SectionHeader
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = { },
    viewModel: AppearanceViewModel = koinViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val showBottomNavLabels by viewModel.showBottomNavLabels.collectAsStateWithLifecycle()

    var showColorPicker by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_category_interface),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
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
            ThemeSelectionCard(
                currentTheme = themeMode,
                onSelectTheme = { viewModel.setThemeMode(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Accent Color ───────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_accent_color),
                icon = Icons.Default.Palette
            )
            AccentColorCard(
                accentColor = accentColor,
                onSelectAccentColor = { viewModel.setAccentColor(it) },
                onOpenColorPicker = { showColorPicker = true }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Navigation ─────────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_navigation),
                icon = Icons.Default.Menu
            )
            NavigationCard(
                showBottomNavLabels = showBottomNavLabels,
                onToggleShowBottomNavLabels = { viewModel.setShowBottomNavLabels(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Language ───────────────────────────────────────────
            SectionHeader(
                title = stringResource(R.string.settings_language),
                icon = Icons.Default.Language
            )
            LanguageSelectionCard(
                currentLanguage = appLanguage,
                onSelectLanguage = { viewModel.setAppLanguage(it) }
            )

            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    if (showColorPicker) {
        val initialCustomColor = if (accentColor.startsWith("custom_#")) {
            try {
                Color(accentColor.removePrefix("custom_").toColorInt())
            } catch (_: Exception) {
                Color.Red
            }
        } else {
            Color.Red
        }
        ColorPickerDialog(
            initialColor = initialCustomColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = { color ->
                val hex = String.format("#%06X", 0xFFFFFF and color.toArgb())
                viewModel.setAccentColor("custom_$hex")
                showColorPicker = false
            }
        )
    }
}
