package app.pwhs.blockads.ui.appearance.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.settings.component.SettingIconBadge
import app.pwhs.blockads.ui.settings.component.SettingsCard

@Composable
fun ThemeSelectionCard(
    currentTheme: String,
    onSelectTheme: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val themes = listOf(
        Triple(R.string.settings_theme_system, Icons.Default.SettingsBrightness, AppPreferences.THEME_SYSTEM),
        Triple(R.string.settings_theme_light, Icons.Default.LightMode, AppPreferences.THEME_LIGHT),
        Triple(R.string.settings_theme_dark, Icons.Default.DarkMode, AppPreferences.THEME_DARK),
    )

    val badgeTint = Color(0xFF2563EB)
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    SettingsCard(modifier = modifier) {
        Column {
            themes.forEachIndexed { index, (labelRes, icon, themeCode) ->
                val isSelected = currentTheme == themeCode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTheme(themeCode) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingIconBadge(
                        icon = icon,
                        tint = if (isSelected) badgeTint else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(labelRes),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (index < themes.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = dividerColor
                    )
                }
            }
        }
    }
}
