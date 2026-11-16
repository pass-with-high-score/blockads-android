package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun InterfaceSection(
    onNavigateToTheme: () -> Unit,
    onNavigateToNavigation: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_interface),
            description = stringResource(R.string.settings_category_interface_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingsCard {
            SettingItem(
                iconPainter = painterResource(R.drawable.ic_settings_palette),
                iconTint = Color(0xFF8B5CF6),
                title = stringResource(R.string.settings_theme_and_colors),
                desc = stringResource(R.string.settings_theme_and_colors_desc),
                onClick = onNavigateToTheme
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            SettingItem(
                icon = Icons.Default.Menu,
                iconTint = Color(0xFF059669),
                title = stringResource(R.string.settings_navigation),
                desc = stringResource(R.string.settings_navigation_desc),
                onClick = onNavigateToNavigation
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            SettingItem(
                icon = Icons.Default.Language,
                iconTint = Color(0xFF2563EB),
                title = stringResource(R.string.settings_language),
                desc = stringResource(R.string.settings_language_desc),
                onClick = onNavigateToLanguage
            )
        }
    }
}
