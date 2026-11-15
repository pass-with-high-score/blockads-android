package app.pwhs.blockads.ui.logs.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    isSearchVisible: Boolean,
    recordDnsLogs: Boolean,
    onNavigateBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleRecordDnsLogs: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
    logRetentionDays: Int = 0,
    onOpenRetentionSettings: () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.accessibility_navigate_back)
                )
            }
        },
        title = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "top_bar_title"
            ) { isSelecting ->
                if (isSelecting) {
                    Text(
                        text = stringResource(R.string.log_bulk_selected, selectedCount),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                } else {
                    Text(
                        text = stringResource(R.string.nav_logs),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        },
        actions = {
            // Search toggle
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = stringResource(R.string.log_search_hint),
                    tint = if (isSearchVisible) MaterialTheme.colorScheme.primary else TextSecondary
                )
            }

            // Pause/Resume recording logs toggle
            IconButton(onClick = onToggleRecordDnsLogs) {
                Icon(
                    imageVector = if (recordDnsLogs) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = stringResource(
                        if (recordDnsLogs) R.string.log_pause_recording else R.string.log_resume_recording
                    ),
                    tint = if (recordDnsLogs) MaterialTheme.colorScheme.onSurfaceVariant else DangerRed
                )
            }

            // Export logs
            IconButton(onClick = onExportLogs) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = "Export logs",
                    tint = TextSecondary
                )
            }

            // Auto-delete old logs setting
            IconButton(onClick = onOpenRetentionSettings) {
                Icon(
                    imageVector = Icons.Default.AutoDelete,
                    contentDescription = stringResource(R.string.log_retention_title),
                    tint = if (logRetentionDays > 0) MaterialTheme.colorScheme.primary else TextSecondary
                )
            }

            // Clear logs
            IconButton(onClick = onClearLogs) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Clear logs",
                    tint = TextSecondary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
