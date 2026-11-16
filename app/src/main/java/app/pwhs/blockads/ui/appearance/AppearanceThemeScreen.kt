package app.pwhs.blockads.ui.appearance

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.appearance.component.AccentColorCard
import app.pwhs.blockads.ui.appearance.component.AppearanceScaffold
import app.pwhs.blockads.ui.appearance.component.ColorPickerDialog
import app.pwhs.blockads.ui.appearance.component.ThemeSelectionCard
import app.pwhs.blockads.ui.settings.component.SectionHeader
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppearanceThemeScreen(
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    onNavigateBack: () -> Unit = {},
    viewModel: AppearanceViewModel = koinViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    var showColorPicker by remember { mutableStateOf(false) }

    AppearanceScaffold(
        title = stringResource(R.string.settings_theme_and_colors),
        showBackButton = showBackButton,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        SectionHeader(
            title = stringResource(R.string.settings_theme),
            icon = Icons.Default.DarkMode
        )
        ThemeSelectionCard(
            currentTheme = themeMode,
            onSelectTheme = { viewModel.setThemeMode(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader(
            title = stringResource(R.string.settings_accent_color),
            icon = Icons.Default.Palette
        )
        AccentColorCard(
            accentColor = accentColor,
            onSelectAccentColor = { viewModel.setAccentColor(it) },
            onOpenColorPicker = { showColorPicker = true }
        )
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
