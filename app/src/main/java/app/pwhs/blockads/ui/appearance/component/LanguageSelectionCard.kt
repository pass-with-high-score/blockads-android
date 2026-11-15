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
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.settings.component.SettingIconBadge
import app.pwhs.blockads.ui.settings.component.SettingsCard

private data class LanguageItem(
    val labelRes: Int,
    val langCode: String,
    val emoji: String? = null,
    val icon: ImageVector? = null
)

@Composable
fun LanguageSelectionCard(
    currentLanguage: String,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val languages = remember {
        listOf(
            LanguageItem(R.string.settings_lang_system, AppPreferences.LANGUAGE_SYSTEM, icon = Icons.Default.SettingsBrightness),
            LanguageItem(R.string.settings_lang_en, AppPreferences.LANGUAGE_EN, emoji = "🇺🇸"),
            LanguageItem(R.string.settings_lang_ar, AppPreferences.LANGUAGE_AR, emoji = "🇸🇦"),
            LanguageItem(R.string.settings_lang_cs, AppPreferences.LANGUAGE_CS, emoji = "🇨🇿"),
            LanguageItem(R.string.settings_lang_de, AppPreferences.LANGUAGE_DE, emoji = "🇩🇪"),
            LanguageItem(R.string.settings_lang_es, AppPreferences.LANGUAGE_ES, emoji = "🇪🇸"),
            LanguageItem(R.string.settings_lang_fr, AppPreferences.LANGUAGE_FR, emoji = "🇫🇷"),
            LanguageItem(R.string.settings_lang_in, AppPreferences.LANGUAGE_IN, emoji = "🇮🇩"),
            LanguageItem(R.string.settings_lang_it, AppPreferences.LANGUAGE_IT, emoji = "🇮🇹"),
            LanguageItem(R.string.settings_lang_iw, AppPreferences.LANGUAGE_IW, emoji = "🇮🇱"),
            LanguageItem(R.string.settings_lang_ja, AppPreferences.LANGUAGE_JA, emoji = "🇯🇵"),
            LanguageItem(R.string.settings_lang_kk, AppPreferences.LANGUAGE_KK, emoji = "🇰🇿"),
            LanguageItem(R.string.settings_lang_ko, AppPreferences.LANGUAGE_KO, emoji = "🇰🇷"),
            LanguageItem(R.string.settings_lang_pl, AppPreferences.LANGUAGE_PL, emoji = "🇵🇱"),
            LanguageItem(R.string.settings_lang_pt_br, AppPreferences.LANGUAGE_PT_BR, emoji = "🇧🇷"),
            LanguageItem(R.string.settings_lang_ru, AppPreferences.LANGUAGE_RU, emoji = "🇷🇺"),
            LanguageItem(R.string.settings_lang_th, AppPreferences.LANGUAGE_TH, emoji = "🇹🇭"),
            LanguageItem(R.string.settings_lang_tr, AppPreferences.LANGUAGE_TR, emoji = "🇹🇷"),
            LanguageItem(R.string.settings_lang_hu, AppPreferences.LANGUAGE_HU, emoji = "🇭🇺"),
            LanguageItem(R.string.settings_lang_uk, AppPreferences.LANGUAGE_UK, emoji = "🇺🇦"),
            LanguageItem(R.string.settings_lang_vi, AppPreferences.LANGUAGE_VI, emoji = "🇻🇳"),
            LanguageItem(R.string.settings_lang_zh, AppPreferences.LANGUAGE_ZH, emoji = "🇨🇳"),
        )
    }

    val sortedLanguages = remember(languages) {
        languages.subList(0, 2) + languages.drop(2).sortedBy { it.langCode }
    }

    val badgeTint = Color(0xFFEA580C)
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    SettingsCard(modifier = modifier) {
        Column {
            sortedLanguages.forEachIndexed { index, item ->
                val isSelected = currentLanguage == item.langCode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLanguage(item.langCode) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingIconBadge(
                        icon = item.icon,
                        emoji = item.emoji,
                        tint = if (isSelected) badgeTint else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(item.labelRes),
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
                if (index < sortedLanguages.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = dividerColor
                    )
                }
            }
        }
    }
}
