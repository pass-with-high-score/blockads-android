package app.pwhs.blockadstv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ThemeSelectionDialog(
    currentTheme: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val themeOptions = listOf(
        Triple(stringResource(R.string.settings_theme_dark), "Dark theme optimized for TV", TvPreferences.THEME_DARK),
        Triple(stringResource(R.string.settings_theme_oled), "Pure black background for OLED/QLED TVs", TvPreferences.THEME_OLED),
        Triple(stringResource(R.string.settings_theme_light), "Clean high-contrast light theme", TvPreferences.THEME_LIGHT),
        Triple(stringResource(R.string.settings_theme_system), "Follow system display settings", TvPreferences.THEME_SYSTEM),
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, NeonGreen, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_setting),
                        contentDescription = null,
                        tint = NeonGreen,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.settings_theme_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_theme_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                themeOptions.forEach { (label, desc, value) ->
                    SelectionRow(
                        label = label,
                        description = desc,
                        isSelected = currentTheme == value,
                        onClick = { onSelect(value) },
                    )
                }
            }
        }
    }
}

private data class TvLanguageItem(
    val code: String,
    val labelRes: Int,
    val emoji: String,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LanguageSelectionDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val languages = remember {
        listOf(
            TvLanguageItem(TvPreferences.LANGUAGE_SYSTEM, R.string.settings_lang_system, emoji = "🌐"),
            TvLanguageItem(TvPreferences.LANGUAGE_EN, R.string.settings_lang_en, emoji = "🇺🇸"),
            TvLanguageItem(TvPreferences.LANGUAGE_VI, R.string.settings_lang_vi, emoji = "🇻🇳"),
            TvLanguageItem(TvPreferences.LANGUAGE_AR, R.string.settings_lang_ar, emoji = "🇸🇦"),
            TvLanguageItem(TvPreferences.LANGUAGE_CS, R.string.settings_lang_cs, emoji = "🇨🇿"),
            TvLanguageItem(TvPreferences.LANGUAGE_DE, R.string.settings_lang_de, emoji = "🇩🇪"),
            TvLanguageItem(TvPreferences.LANGUAGE_ES, R.string.settings_lang_es, emoji = "🇪🇸"),
            TvLanguageItem(TvPreferences.LANGUAGE_FR, R.string.settings_lang_fr, emoji = "🇫🇷"),
            TvLanguageItem(TvPreferences.LANGUAGE_IN, R.string.settings_lang_in, emoji = "🇮🇩"),
            TvLanguageItem(TvPreferences.LANGUAGE_IT, R.string.settings_lang_it, emoji = "🇮🇹"),
            TvLanguageItem(TvPreferences.LANGUAGE_IW, R.string.settings_lang_iw, emoji = "🇮🇱"),
            TvLanguageItem(TvPreferences.LANGUAGE_JA, R.string.settings_lang_ja, emoji = "🇯🇵"),
            TvLanguageItem(TvPreferences.LANGUAGE_KK, R.string.settings_lang_kk, emoji = "🇰🇿"),
            TvLanguageItem(TvPreferences.LANGUAGE_KO, R.string.settings_lang_ko, emoji = "🇰🇷"),
            TvLanguageItem(TvPreferences.LANGUAGE_PL, R.string.settings_lang_pl, emoji = "🇵🇱"),
            TvLanguageItem(TvPreferences.LANGUAGE_PT_BR, R.string.settings_lang_pt_br, emoji = "🇧🇷"),
            TvLanguageItem(TvPreferences.LANGUAGE_RU, R.string.settings_lang_ru, emoji = "🇷🇺"),
            TvLanguageItem(TvPreferences.LANGUAGE_TH, R.string.settings_lang_th, emoji = "🇹🇭"),
            TvLanguageItem(TvPreferences.LANGUAGE_TR, R.string.settings_lang_tr, emoji = "🇹🇷"),
            TvLanguageItem(TvPreferences.LANGUAGE_HU, R.string.settings_lang_hu, emoji = "🇭🇺"),
            TvLanguageItem(TvPreferences.LANGUAGE_UK, R.string.settings_lang_uk, emoji = "🇺🇦"),
            TvLanguageItem(TvPreferences.LANGUAGE_ZH, R.string.settings_lang_zh, emoji = "🇨🇳"),
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(660.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, NeonGreen, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_setting),
                        contentDescription = null,
                        tint = NeonGreen,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.settings_language_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_language_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(14.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(languages) { item ->
                        LanguageGridRow(
                            item = item,
                            isSelected = currentLanguage == item.code,
                            onClick = { onSelect(item.code) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LanguageGridRow(
    item: TvLanguageItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) NeonGreen.copy(alpha = 0.15f)
                else if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = if (isFocused || isSelected) 2.dp else 1.dp,
                color = if (isFocused || isSelected) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.emoji,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(item.labelRes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) NeonGreen else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (isSelected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = "Selected",
                tint = NeonGreen,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SelectionRow(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) NeonGreen.copy(alpha = 0.15f)
                else if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = if (isFocused || isSelected) 2.dp else 1.dp,
                color = if (isFocused || isSelected) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) NeonGreen else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        if (isSelected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = "Selected",
                tint = NeonGreen,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
