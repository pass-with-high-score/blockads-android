package app.pwhs.blockads.ui.config.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

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
    val darkPillColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Row 1: Snippets & Edit
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.Layers,
                label = stringResource(R.string.profile_action_snippets),
                onClick = onSnippetsClick,
                containerColor = darkPillColor,
                contentColor = textColor,
                modifier = Modifier.weight(1f)
            )
            ProfileCapsuleButton(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.profile_action_edit),
                onClick = onEditClick,
                containerColor = darkPillColor,
                contentColor = textColor,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Import & Export
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.VerticalAlignBottom,
                label = stringResource(R.string.profile_action_import),
                onClick = onImportClick,
                containerColor = darkPillColor,
                contentColor = textColor,
                modifier = Modifier.weight(1f)
            )
            ProfileCapsuleButton(
                icon = Icons.Default.VerticalAlignTop,
                label = stringResource(R.string.profile_action_export),
                onClick = onExportClick,
                containerColor = darkPillColor,
                contentColor = textColor,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 3: Download & Sample
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.CloudDownload,
                label = stringResource(R.string.profile_action_download),
                onClick = onDownloadClick,
                containerColor = darkPillColor,
                contentColor = textColor,
                modifier = Modifier.weight(1f)
            )
            ProfileCapsuleButton(
                icon = Icons.Default.Description,
                label = stringResource(R.string.profile_action_sample),
                onClick = onSampleClick,
                containerColor = darkPillColor,
                contentColor = textColor,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 4: Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProfileCapsuleButton(
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.profile_action_reset),
                onClick = onResetClick,
                containerColor = darkPillColor,
                contentColor = textColor,
                modifier = Modifier.weight(0.5f)
            )
        }
    }
}
