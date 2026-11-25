package app.pwhs.blockads.ui.home.component

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.BlockadsTheme
import app.pwhs.blockads.ui.update.findActivity
import app.pwhs.blockads.update.AppUpdateController
import app.pwhs.blockads.update.UpdateInfo
import app.pwhs.blockads.update.UpdateState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun HomeUpdateBannerSlot(
    modifier: Modifier = Modifier,
) {
    val updateController: AppUpdateController = koinInject()
    if (!updateController.supportsInAppUpdate) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by updateController.state.collectAsStateWithLifecycle()
    val bannerDismissed by updateController.bannerDismissed.collectAsStateWithLifecycle()

    val shouldShow = !bannerDismissed && (
        updateState is UpdateState.Available ||
            updateState is UpdateState.Downloading ||
            updateState is UpdateState.Downloaded ||
            updateState is UpdateState.PlayReadyToInstall
        )

    AnimatedVisibility(
        visible = shouldShow,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        HomeUpdateBanner(
            state = updateState,
            onOpenDetails = { updateController.openUpdateDialog() },
            onPrimaryAction = {
                when (updateState) {
                    is UpdateState.PlayReadyToInstall -> updateController.completeUpdate()
                    is UpdateState.Available,
                    is UpdateState.Downloaded -> {
                        val activity = context.findActivity() ?: return@HomeUpdateBanner
                        scope.launch { updateController.startUpdate(activity) }
                    }
                    else -> Unit
                }
            },
            onDismiss = { updateController.dismissBanner() },
            modifier = modifier,
        )
    }
}

@Composable
fun HomeUpdateBanner(
    state: UpdateState,
    onOpenDetails: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    val isDismissible = when (state) {
        is UpdateState.Available -> !state.info.isImmediate
        is UpdateState.Downloaded,
        is UpdateState.PlayReadyToInstall -> true
        else -> false
    }

    val isClickable = state is UpdateState.Available || state is UpdateState.Downloaded

    val changelogText = when (state) {
        is UpdateState.Available -> state.info.changelog.trim()
        is UpdateState.Downloaded -> state.info?.changelog?.trim().orEmpty()
        else -> ""
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .then(if (isClickable) Modifier.clickable(onClick = onOpenDetails) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val icon = when (state) {
                    is UpdateState.Downloading -> Icons.Default.Download
                    is UpdateState.Downloaded -> Icons.Default.InstallMobile
                    else -> Icons.Default.SystemUpdate
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))

                val title = when (state) {
                    is UpdateState.Available ->
                        stringResource(R.string.update_available_title, state.info.versionName)
                    is UpdateState.Downloading -> {
                        val percent = (state.progress * 100f).roundToInt().coerceIn(0, 100)
                        stringResource(R.string.update_downloading, percent)
                    }
                    is UpdateState.Downloaded -> {
                        val version = state.info?.versionName
                        if (!version.isNullOrBlank()) {
                            "${stringResource(R.string.update_ready_install)} ($version)"
                        } else {
                            stringResource(R.string.update_ready_install)
                        }
                    }
                    is UpdateState.PlayReadyToInstall ->
                        stringResource(R.string.update_ready_install)
                    else -> ""
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (isDismissible) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.vpn_revoked_action_dismiss),
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (state is UpdateState.Downloading) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = contentColor.copy(alpha = 0.2f),
                )
            } else {
                val bodyText = changelogText.ifBlank {
                    stringResource(R.string.update_available_default_notes)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val actionLabel = when (state) {
                        is UpdateState.Downloaded -> stringResource(R.string.update_action_install)
                        is UpdateState.PlayReadyToInstall -> stringResource(R.string.update_action_restart)
                        else -> stringResource(R.string.update_action_now)
                    }
                    Button(
                        onClick = onPrimaryAction,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Available - Light", showBackground = true)
@Preview(name = "Available - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun HomeUpdateBannerAvailablePreview() {
    BlockadsTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeUpdateBanner(
                state = UpdateState.Available(
                    UpdateInfo(
                        versionName = "v7.2.2",
                        tagName = "v7.2.2",
                        changelog = "• Quick import .conf and .txt config files\n• Auto-restart VPN when Private DNS changes\n• Fixed Tailscale LAN access on mobile data",
                        downloadUrl = "",
                        assetName = "app-github-arm64-v8a-release.apk",
                        assetSizeBytes = 25_000_000L,
                        publishedAtMillis = 0L,
                    )
                ),
                onOpenDetails = {},
                onPrimaryAction = {},
                onDismiss = {},
            )
            HomeUpdateBanner(
                state = UpdateState.Downloaded(
                    filePath = "/cache/updates/v7.2.2__app.apk",
                    info = UpdateInfo(
                        versionName = "v7.2.2",
                        tagName = "v7.2.2",
                        changelog = "• Quick import .conf and .txt config files\n• Auto-restart VPN when Private DNS changes",
                        downloadUrl = "",
                        assetName = "app-github-arm64-v8a-release.apk",
                        assetSizeBytes = 25_000_000L,
                        publishedAtMillis = 0L,
                    ),
                ),
                onOpenDetails = {},
                onPrimaryAction = {},
                onDismiss = {},
            )
            HomeUpdateBanner(
                state = UpdateState.Downloading(
                    progress = 0.62f,
                    downloadedBytes = 15_500_000L,
                    totalBytes = 25_000_000L,
                ),
                onOpenDetails = {},
                onPrimaryAction = {},
                onDismiss = {},
            )
        }
    }
}
