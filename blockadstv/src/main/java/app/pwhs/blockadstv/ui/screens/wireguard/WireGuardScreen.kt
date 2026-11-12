package app.pwhs.blockadstv.ui.screens.wireguard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.service.ServiceController
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.screens.wireguard.component.ImportWireGuardDialog
import app.pwhs.blockadstv.ui.screens.wireguard.component.RenameWireGuardDialog
import app.pwhs.blockadstv.ui.screens.wireguard.component.WgDialogButton
import app.pwhs.blockadstv.ui.screens.wireguard.component.WireGuardProfileItem
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

@Composable
fun WireGuardScreen(
    modifier: Modifier = Modifier,
    viewModel: WireGuardViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effect.collectLatest { effect ->
            when (effect) {
                is WireGuardUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is WireGuardUiEffect.RequestRestart -> {
                    ServiceController.requestRestart(context)
                }
            }
        }
    }

    WireGuardScreenContent(
        uiState = uiState,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WireGuardScreenContent(
    uiState: WireGuardUiState,
    onIntent: (WireGuardUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.showImportDialog) {
        ImportWireGuardDialog(
            errorMessage = uiState.dialogError,
            onImport = { input, name -> onIntent(WireGuardUiIntent.ImportConfigOrUri(input, name)) },
            onDismiss = { onIntent(WireGuardUiIntent.ShowImportDialog(false)) },
        )
    }

    uiState.showRenameDialogFor?.let { profile ->
        RenameWireGuardDialog(
            profile = profile,
            errorMessage = uiState.dialogError,
            onSave = { name -> onIntent(WireGuardUiIntent.RenameProfile(profile.id, name)) },
            onDismiss = { onIntent(WireGuardUiIntent.ShowRenameDialog(null)) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_shield_on),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.wg_title),
                style = MaterialTheme.typography.headlineLarge,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.wg_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvToggleCard(
                title = stringResource(R.string.wg_mode_title),
                subtitle = stringResource(R.string.wg_mode_desc),
                checked = uiState.isWireGuardModeActive,
                onClick = { onIntent(WireGuardUiIntent.SetWireGuardMode(!uiState.isWireGuardModeActive)) },
                modifier = Modifier.weight(1f),
            )

            GeoIpStatusCard(
                recordCount = uiState.geoIpInfo?.recordCount ?: 0,
                isUpdating = uiState.isUpdatingGeoIp,
                autoUpdate = uiState.geoIpAutoUpdate,
                onUpdateClick = { onIntent(WireGuardUiIntent.UpdateGeoIpDatabase) },
                onToggleAutoUpdate = {
                    onIntent(WireGuardUiIntent.SetGeoIpAutoUpdate(!uiState.geoIpAutoUpdate))
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.wg_profiles_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WgDialogButton(
                    text = stringResource(R.string.wg_import_btn),
                    isPrimary = true,
                    onClick = { onIntent(WireGuardUiIntent.ShowImportDialog(true)) },
                )
                if (uiState.profiles.isNotEmpty()) {
                    WgDialogButton(
                        text = stringResource(R.string.proxy_ping_all),
                        isPrimary = false,
                        onClick = { onIntent(WireGuardUiIntent.PingAll) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (uiState.profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.wg_no_profiles),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.profiles, key = { it.id }) { profile ->
                    WireGuardProfileItem(
                        profile = profile,
                        isActive = profile.id == uiState.activeProfileId,
                        countryCode = uiState.countryCodes[profile.id],
                        pingResult = uiState.pingResults[profile.id],
                        onSelect = { onIntent(WireGuardUiIntent.SelectProfile(profile.id)) },
                        onPing = { onIntent(WireGuardUiIntent.PingProfile(profile)) },
                        onRename = { onIntent(WireGuardUiIntent.ShowRenameDialog(profile)) },
                        onDelete = { onIntent(WireGuardUiIntent.DeleteProfile(profile.id)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = if (isFocused) 2.dp else if (checked) 1.5.dp else 1.dp,
                color = when {
                    isFocused -> MaterialTheme.colorScheme.primary
                    checked -> NeonGreen
                    else -> MaterialTheme.colorScheme.border
                },
                shape = RoundedCornerShape(12.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        TvSwitch(checked = checked)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GeoIpStatusCard(
    recordCount: Int,
    isUpdating: Boolean,
    autoUpdate: Boolean,
    onUpdateClick: () -> Unit,
    onToggleAutoUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = stringResource(R.string.wg_geoip_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (recordCount > 0) "$recordCount IPv4 ranges" else stringResource(R.string.wg_geoip_not_downloaded),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WgDialogButton(
                text = if (isUpdating) "..." else stringResource(R.string.wg_geoip_update),
                isPrimary = false,
                onClick = onUpdateClick,
            )
            WgDialogButton(
                text = if (autoUpdate) "Auto: ON" else "Auto: OFF",
                isPrimary = false,
                onClick = onToggleAutoUpdate,
            )
        }
    }
}
