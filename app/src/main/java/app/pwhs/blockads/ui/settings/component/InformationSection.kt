package app.pwhs.blockads.ui.settings.component

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pwhs.blockads.R

@Composable
fun InformationSection(
    onNavigateToAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_info),
            icon = Icons.Default.Info,
            description = stringResource(R.string.settings_category_info_desc)
        )
        SettingsCard {
            SettingItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_about),
                desc = stringResource(R.string.settings_about_desc),
                onClick = onNavigateToAbout
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
            )
            SettingItem(
                icon = Icons.Default.Favorite,
                iconTint = Color(0xFFE91E63),
                title = stringResource(R.string.settings_sponsor),
                desc = stringResource(R.string.settings_sponsor_desc),
                onClick = {
                    val uri = "https://github.com/sponsors/pass-with-high-score".toUri()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            )
        }
    }
}
