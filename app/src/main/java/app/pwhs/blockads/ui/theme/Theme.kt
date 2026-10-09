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
    surfaceContainerLowest = Color(0xFF0D1117),
    surfaceContainerLow = Color(0xFF13171D),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF21262D),
    surfaceContainerHighest = Color(0xFF30363D),
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
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFEFF2F5),
    surfaceContainerHigh = Color(0xFFE6EAEF),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    outline = LightTextSecondary,
    error = DangerRedDim,
    onError = Color.White
)

private val OledColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    primaryContainer = NeonGreenDim,
    onPrimaryContainer = Color.White,
    secondary = AccentBlue,
    onSecondary = Color.Black,
    secondaryContainer = AccentBlueDim,
    onSecondaryContainer = Color.White,
    tertiary = DangerRed,
    background = Color.Black,
    onBackground = TextPrimary,
    surface = Color.Black,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    outline = Color(0xFF2E2E2E),
    error = DangerRed,
    onError = Color.White
)

/**
 * Returns a pair of (primary, primaryDim) colors for the given accent color key.
 */
private fun getAccentColors(accentColor: String): Pair<Color, Color> {
    return when {
        accentColor == AppPreferences.ACCENT_BLUE -> AccentBluePreset to AccentBluePresetDim
        accentColor == AppPreferences.ACCENT_PURPLE -> AccentPurple to AccentPurpleDim
        accentColor == AppPreferences.ACCENT_ORANGE -> AccentOrange to AccentOrangeDim
        accentColor == AppPreferences.ACCENT_PINK -> AccentPink to AccentPinkDim
        accentColor == AppPreferences.ACCENT_TEAL -> AccentTeal to AccentTealDim
        accentColor == AppPreferences.ACCENT_GREY -> AccentGrey to AccentGreyDim
        accentColor.startsWith("custom_#") -> {
            try {
                val hex = accentColor.removePrefix("custom_")
                val primary = Color(android.graphics.Color.parseColor(hex))
                // Generate a dimmed variant by darkening the color ~20%
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), hsv)
                hsv[2] = (hsv[2] * 0.8f).coerceIn(0f, 1f)
                val dimmed = Color(android.graphics.Color.HSVToColor(hsv))
                primary to dimmed
            } catch (_: Exception) {
                AccentGreen to AccentGreenDim
            }
        }
        else -> AccentGreen to AccentGreenDim // default green
    }
}

@Composable
fun BlockadsTheme(
    themeMode: String = "system",
    accentColor: String = AppPreferences.ACCENT_GREEN,
    content: @Composable () -> Unit
) {
    val isOled = themeMode == AppPreferences.THEME_OLED
    val darkTheme = when (themeMode) {
        AppPreferences.THEME_OLED, AppPreferences.THEME_DARK -> true
        AppPreferences.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val baseDarkScheme = if (isOled) OledColorScheme else DarkColorScheme

    val colorScheme = when {
        // Dynamic Color (Material You) — Android 12+
        accentColor == AppPreferences.ACCENT_DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (isOled) {
                dynamic.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121212)
                )
            } else {
                dynamic
            }
        }
        // Preset or custom accent colors
        accentColor != AppPreferences.ACCENT_GREEN && accentColor != AppPreferences.ACCENT_DYNAMIC -> {
            val (primary, primaryDim) = getAccentColors(accentColor)
            if (darkTheme) {
                baseDarkScheme.copy(
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
        darkTheme -> baseDarkScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}