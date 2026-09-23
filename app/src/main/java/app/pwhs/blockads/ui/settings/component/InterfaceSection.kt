package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun InterfaceSection(
    onNavigateToAppearance: () -> Unit,
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
                title = stringResource(R.string.settings_category_interface),
                desc = stringResource(R.string.settings_category_interface_desc),
                onClick = onNavigateToAppearance
            )
        }
    }
}
