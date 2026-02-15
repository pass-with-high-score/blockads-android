package app.pwhs.blockads.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    primaryContainer = NeonGreenDim,
    onPrimaryContainer = Color.White,
    secondary = AccentBlue,
    onSecondary = Color.Black,
    secondaryContainer = AccentBlueDim,
    onSecondaryContainer = Color.White,
    tertiary = DangerRed,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    error = DangerRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = NeonGreenDim,
    onPrimary = Color.White,
    primaryContainer = NeonGreen,
    onPrimaryContainer = Color.Black,
    secondary = AccentBlueDim,
    onSecondary = Color.White,
    secondaryContainer = AccentBlue,
    onSecondaryContainer = Color.Black,
    tertiary = DangerRedDim,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightTextSecondary,
    error = DangerRedDim,
    onError = Color.White
)

private val HighContrastColorScheme = darkColorScheme(
    primary = HighContrastPrimary,
    onPrimary = Color.Black,
    primaryContainer = HighContrastPrimaryDim,
    onPrimaryContainer = Color.White,
    secondary = HighContrastSecondary,
    onSecondary = Color.Black,
    secondaryContainer = HighContrastSecondary,
    onSecondaryContainer = Color.Black,
    tertiary = HighContrastError,
    background = HighContrastBackground,
    onBackground = HighContrastTextPrimary,
    surface = HighContrastSurface,
    onSurface = HighContrastTextPrimary,
    surfaceVariant = HighContrastSurfaceVariant,
    onSurfaceVariant = HighContrastTextSecondary,
    outline = HighContrastTextSecondary,
    error = HighContrastError,
    onError = Color.White
)

@Composable
fun BlockadsTheme(
    themeMode: String = "system",
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        highContrast -> HighContrastColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}