package app.pwhs.blockadstv.ui.update

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.ui.screens.settings.SettingListItem
import app.pwhs.blockadstv.update.UpdateChannel
import app.pwhs.blockadstv.update.UpdateState

fun LazyListScope.tvUpdateSettingsItems(
    supportsInAppUpdate: Boolean,
    supportsChannelSelection: Boolean,
    updateState: UpdateState,
    currentChannel: UpdateChannel,
    onCheckUpdate: () -> Unit,
    onOpenChannelDialog: () -> Unit,
) {
    if (supportsInAppUpdate) {
        item {
            val subtitle = when (updateState) {
                is UpdateState.Checking -> stringResource(R.string.update_checking)
                is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                else -> stringResource(R.string.update_check_desc)
            }
            SettingListItem(
                iconRes = R.drawable.ic_settings_auto_update,
                title = stringResource(R.string.update_check_title),
                subtitle = subtitle,
                isToggle = false,
                isEnabled = false,
                onClick = onCheckUpdate,
            )
        }
    }
    if (supportsChannelSelection) {
        item {
            SettingListItem(
                iconRes = R.drawable.ic_setting,
                title = stringResource(R.string.update_channel_title),
                subtitle = channelLabel(currentChannel),
                isToggle = false,
                isEnabled = false,
                onClick = onOpenChannelDialog,
            )
        }
    }
}

@Composable
private fun channelLabel(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> stringResource(R.string.update_channel_stable)
    UpdateChannel.BETA -> stringResource(R.string.update_channel_beta)
    UpdateChannel.NIGHTLY -> stringResource(R.string.update_channel_nightly)
}
