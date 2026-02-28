package app.pwhs.blockads.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.pwhs.blockads.data.datastore.AppPreferences

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

/**
 * Returns a pair of (primary, primaryDim) colors for the given accent color key.
 */
private fun getAccentColors(accentColor: String): Pair<Color, Color> {
    return when (accentColor) {
        AppPreferences.ACCENT_BLUE -> AccentBluePreset to AccentBluePresetDim
        AppPreferences.ACCENT_PURPLE -> AccentPurple to AccentPurpleDim
        AppPreferences.ACCENT_ORANGE -> AccentOrange to AccentOrangeDim
        AppPreferences.ACCENT_PINK -> AccentPink to AccentPinkDim
        AppPreferences.ACCENT_TEAL -> AccentTeal to AccentTealDim
        AppPreferences.ACCENT_GREY -> AccentGrey to AccentGreyDim
        else -> AccentGreen to AccentGreenDim // default green
    }
}

@Composable
fun BlockadsTheme(
    themeMode: String = "system",
    accentColor: String = AppPreferences.ACCENT_GREEN,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        // Dynamic Color (Material You) — Android 12+
        accentColor == AppPreferences.ACCENT_DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Preset accent colors
        accentColor != AppPreferences.ACCENT_GREEN -> {
            val (primary, primaryDim) = getAccentColors(accentColor)
            if (darkTheme) {
                DarkColorScheme.copy(
                    primary = primary,
                    primaryContainer = primaryDim
                )
            } else {
                LightColorScheme.copy(
                    primary = primaryDim,
                    primaryContainer = primary
                )
            }
        }
        // Default green
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}