package app.pwhs.blockads.ui.settings.component

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.update.UpdateChannelDialog
import app.pwhs.blockads.update.AppUpdateController
import app.pwhs.blockads.update.UpdateChannel
import app.pwhs.blockads.update.UpdateState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun InformationSection(
    onNavigateToAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
    val updateController: AppUpdateController = koinInject()
    val appPrefs: AppPreferences = koinInject()
    val scope = rememberCoroutineScope()
    val updateState by updateController.state.collectAsStateWithLifecycle()
    val storedChannel by appPrefs.updateChannel.collectAsStateWithLifecycle(initialValue = null)
    val currentChannel = remember(storedChannel) {
        if (storedChannel != null) {
            UpdateChannel.fromId(storedChannel)
        } else {
            UpdateChannel.fromVersionName(BuildConfig.VERSION_NAME)
        }
    }
    var showChannelDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_info),
            description = stringResource(R.string.settings_category_info_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingsCard {
            if (updateController.supportsInAppUpdate) {
                val updateDesc = when (updateState) {
                    is UpdateState.Checking -> stringResource(R.string.update_checking)
                    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                    else -> stringResource(R.string.update_check_desc)
                }
                SettingItem(
                    icon = Icons.Default.SystemUpdate,
                    iconTint = Color(0xFF0EA5E9),
                    title = stringResource(R.string.update_check_title),
                    desc = updateDesc,
                    onClick = {
                        scope.launch {
                            updateController.checkForUpdate(currentChannel, manual = true)
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = dividerColor
                )
            }
            if (updateController.supportsChannelSelection) {
                val channelLabel = when (currentChannel) {
                    UpdateChannel.STABLE -> stringResource(R.string.update_channel_stable)
                    UpdateChannel.BETA -> stringResource(R.string.update_channel_beta)
                    UpdateChannel.NIGHTLY -> stringResource(R.string.update_channel_nightly)
                }
                SettingItem(
                    icon = Icons.Default.Tune,
                    iconTint = Color(0xFF8B5CF6),
                    title = stringResource(R.string.update_channel_title),
                    desc = channelLabel,
                    onClick = { showChannelDialog = true }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = dividerColor
                )
            }
            SettingItem(
                icon = Icons.Default.Info,
                iconTint = Color(0xFF64748B),
                title = stringResource(R.string.settings_about),
                desc = stringResource(R.string.settings_about_desc),
                onClick = onNavigateToAbout
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = dividerColor
            )
            SettingItem(
                icon = Icons.Default.Favorite,
                iconTint = Color(0xFFE11D48),
                title = stringResource(R.string.settings_sponsor),
                desc = stringResource(R.string.settings_sponsor_desc),
                onClick = {
                    val uri = "https://github.com/sponsors/pass-with-high-score".toUri()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            )
        }
    }

    if (showChannelDialog) {
        UpdateChannelDialog(
            selectedChannel = currentChannel,
            onSelect = { selected ->
                showChannelDialog = false
                scope.launch {
                    appPrefs.setUpdateChannel(selected.id)
                }
            },
            onDismiss = { showChannelDialog = false }
        )
    }
}
