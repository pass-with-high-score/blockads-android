package app.pwhs.blockads.ui.settings.component

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pwhs.blockads.R

@Composable
fun CommunitySection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_community),
            icon = Icons.AutoMirrored.Filled.Chat,
            description = stringResource(R.string.settings_category_info_desc)
        )
        SettingsCard {
            SettingItem(
                iconPainter = painterResource(R.drawable.ic_reddit),
                iconTint = Color(0xFFFF4500),
                title = stringResource(R.string.settings_reddit),
                desc = stringResource(R.string.settings_reddit_desc),
                onClick = {
                    val uri = "https://www.reddit.com/r/BlockAds/".toUri()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
            )
            SettingItem(
                iconPainter = painterResource(R.drawable.ic_telegram),
                iconTint = Color(0xFF0088CC),
                title = stringResource(R.string.settings_telegram),
                desc = stringResource(R.string.settings_telegram_desc),
                onClick = {
                    val uri = "https://t.me/blockads_android".toUri()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            )
        }
    }
}
