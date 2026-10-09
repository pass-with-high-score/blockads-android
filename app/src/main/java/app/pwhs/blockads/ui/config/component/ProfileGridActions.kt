package app.pwhs.blockads.ui.config.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.AccentBluePreset
import app.pwhs.blockads.ui.theme.AccentOrange
import app.pwhs.blockads.ui.theme.AccentPurple
import app.pwhs.blockads.ui.theme.AccentTeal
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.NeonGreen

@Composable
fun ProfileGridActions(
    onSnippetsClick: () -> Unit,
    onEditClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSampleClick: () -> Unit,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Row 1: Snippets & Edit
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.Layers,
                label = stringResource(R.string.profile_action_snippets),
                tint = AccentPurple,
                onClick = onSnippetsClick,
                modifier = Modifier.weight(1f)
            )
            ProfileCapsuleButton(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.profile_action_edit),
                tint = AccentBluePreset,
                onClick = onEditClick,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Import & Export
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.VerticalAlignBottom,
                label = stringResource(R.string.profile_action_import),
                tint = AccentTeal,
                onClick = onImportClick,
                modifier = Modifier.weight(1f)
            )
            ProfileCapsuleButton(
                icon = Icons.Default.VerticalAlignTop,
                label = stringResource(R.string.profile_action_export),
                tint = AccentOrange,
                onClick = onExportClick,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 3: Download & Sample
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.CloudDownload,
                label = stringResource(R.string.profile_action_download),
                tint = AccentBlue,
                onClick = onDownloadClick,
                modifier = Modifier.weight(1f)
            )
            ProfileCapsuleButton(
                icon = Icons.Default.Description,
                label = stringResource(R.string.profile_action_sample),
                tint = NeonGreen,
                onClick = onSampleClick,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 4: Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.profile_action_reset),
                tint = DangerRed,
                onClick = onResetClick,
                modifier = Modifier.weight(0.5f)
            )
        }
    }
}
