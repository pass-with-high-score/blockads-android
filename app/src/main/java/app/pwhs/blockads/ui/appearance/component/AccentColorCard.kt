package app.pwhs.blockads.ui.appearance.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.settings.component.SettingsCard
import app.pwhs.blockads.ui.theme.AccentBluePreset
import app.pwhs.blockads.ui.theme.AccentGreen
import app.pwhs.blockads.ui.theme.AccentGrey
import app.pwhs.blockads.ui.theme.AccentOrange
import app.pwhs.blockads.ui.theme.AccentPink
import app.pwhs.blockads.ui.theme.AccentPurple
import app.pwhs.blockads.ui.theme.AccentTeal

@Composable
fun AccentColorCard(
    accentColor: String,
    onSelectAccentColor: (String) -> Unit,
    onOpenColorPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presetColors = listOf(
        AppPreferences.ACCENT_GREEN to AccentGreen,
        AppPreferences.ACCENT_BLUE to AccentBluePreset,
        AppPreferences.ACCENT_PURPLE to AccentPurple,
        AppPreferences.ACCENT_ORANGE to AccentOrange,
        AppPreferences.ACCENT_PINK to AccentPink,
        AppPreferences.ACCENT_TEAL to AccentTeal,
        AppPreferences.ACCENT_GREY to AccentGrey,
    )

    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    SettingsCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_accent_color_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Preset color circles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                // Custom color circle
                val isCustom = accentColor.startsWith("custom_#")
                val customDisplayColor = if (isCustom) {
                    try {
                        Color(accentColor.removePrefix("custom_").toColorInt())
                    } catch (_: Exception) {
                        MaterialTheme.colorScheme.primary
                    }
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

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
                            if (isCustom) Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            ) else Modifier
                        )
                        .clickable { onOpenColorPicker() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isCustom) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(customDisplayColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                presetColors.forEach { (colorKey, displayColor) ->
                    AccentColorCircle(
                        color = displayColor,
                        isSelected = accentColor == colorKey,
                        onClick = { onSelectAccentColor(colorKey) }
                    )
                }
            }

            // Dynamic Color option (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = dividerColor)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectAccentColor(AppPreferences.ACCENT_DYNAMIC) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                                    Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
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
                    Spacer(modifier = Modifier.width(14.dp))
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
}
