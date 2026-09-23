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
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.settings.component.SettingIconBadge
import app.pwhs.blockads.ui.settings.component.SettingsCard

@Composable
fun LanguageSelectionCard(
    currentLanguage: String,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val languages = remember {
        listOf(
            Triple(R.string.settings_lang_system, Icons.Default.SettingsBrightness, AppPreferences.LANGUAGE_SYSTEM),
            Triple(R.string.settings_lang_en, Icons.Default.Language, AppPreferences.LANGUAGE_EN),
            Triple(R.string.settings_lang_ar, Icons.Default.Language, AppPreferences.LANGUAGE_AR),
            Triple(R.string.settings_lang_cs, Icons.Default.Language, AppPreferences.LANGUAGE_CS),
            Triple(R.string.settings_lang_de, Icons.Default.Language, AppPreferences.LANGUAGE_DE),
            Triple(R.string.settings_lang_es, Icons.Default.Language, AppPreferences.LANGUAGE_ES),
            Triple(R.string.settings_lang_in, Icons.Default.Language, AppPreferences.LANGUAGE_IN),
            Triple(R.string.settings_lang_it, Icons.Default.Language, AppPreferences.LANGUAGE_IT),
            Triple(R.string.settings_lang_iw, Icons.Default.Language, AppPreferences.LANGUAGE_IW),
            Triple(R.string.settings_lang_ja, Icons.Default.Language, AppPreferences.LANGUAGE_JA),
            Triple(R.string.settings_lang_ko, Icons.Default.Language, AppPreferences.LANGUAGE_KO),
            Triple(R.string.settings_lang_pl, Icons.Default.Language, AppPreferences.LANGUAGE_PL),
            Triple(R.string.settings_lang_pt_br, Icons.Default.Language, AppPreferences.LANGUAGE_PT_BR),
            Triple(R.string.settings_lang_ru, Icons.Default.Language, AppPreferences.LANGUAGE_RU),
            Triple(R.string.settings_lang_th, Icons.Default.Language, AppPreferences.LANGUAGE_TH),
            Triple(R.string.settings_lang_tr, Icons.Default.Language, AppPreferences.LANGUAGE_TR),
            Triple(R.string.settings_lang_uk, Icons.Default.Language, AppPreferences.LANGUAGE_UK),
            Triple(R.string.settings_lang_vi, Icons.Default.Language, AppPreferences.LANGUAGE_VI),
            Triple(R.string.settings_lang_zh, Icons.Default.Language, AppPreferences.LANGUAGE_ZH),
            Triple(R.string.settings_lang_fr, Icons.Default.Language, AppPreferences.LANGUAGE_FR),
            Triple(R.string.settings_lang_kk, Icons.Default.Language, AppPreferences.LANGUAGE_KK),
        )
    }

    val sortedLanguages = remember(languages) {
        languages.subList(0, 2) + languages.drop(2).sortedBy { it.third }
    }

    val badgeTint = Color(0xFFEA580C)
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    SettingsCard(modifier = modifier) {
        Column {
            sortedLanguages.forEachIndexed { index, (labelRes, icon, langCode) ->
                val isSelected = currentLanguage == langCode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLanguage(langCode) }
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
