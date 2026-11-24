package app.pwhs.blockads.ui.settings.component

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.utils.ExportHelper
import app.pwhs.blockads.utils.LogcatExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@Composable
fun PrivacySection(
    crashReportingEnabled: Boolean,
    onSetCrashReportingEnabled: (Boolean) -> Unit,
    hideFromRecents: Boolean,
    onSetHideFromRecents: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resource = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    var pendingLogFile by remember { mutableStateOf<File?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val file = pendingLogFile
        if (treeUri != null && file != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val targetUri = ExportHelper.resolveTargetUri(context, treeUri, file.name, "text/plain")
                    ExportHelper.copyFileToUri(context, file, targetUri)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to copy logcat file to folder, falling back to Downloads")
                    try {
                        ExportHelper.saveFileToDownloads(context, file, "text/plain")
                    } catch (e2: Exception) {
                        Timber.e(e2, "Failed to save logcat to Downloads")
                    }
                }
            }
        }
    }

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(id = R.string.settings_privacy_diagnostics_title),
            description = stringResource(id = R.string.settings_privacy_diagnostics_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingsCard {
            Column {
                // 1. Hide from recents
                SettingsToggleItem(
                    iconPainter = painterResource(R.drawable.ic_settings_incognito),
                    iconTint = Color(0xFF64748B),
                    title = stringResource(id = R.string.settings_hide_from_recents_title),
                    subtitle = stringResource(id = R.string.settings_hide_from_recents_subtitle),
                    isChecked = hideFromRecents,
                    onCheckedChange = onSetHideFromRecents
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 2. Crash reporting
                SettingsToggleItem(
                    iconPainter = painterResource(R.drawable.ic_settings_diagnostics),
                    iconTint = Color(0xFF64748B),
                    title = stringResource(id = R.string.settings_crash_reporting_title),
                    subtitle = stringResource(id = R.string.settings_crash_reporting_subtitle),
                    isChecked = crashReportingEnabled,
                    onCheckedChange = onSetCrashReportingEnabled
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 3. Export diagnostics logs
                SettingItem(
                    iconPainter = painterResource(R.drawable.ic_settings_export),
                    iconTint = Color(0xFF64748B),
                    title = stringResource(id = R.string.settings_export_logs_title),
                    desc = stringResource(id = R.string.settings_export_logs_subtitle),
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val reportFile = LogcatExporter.createReportFile(context)
                                val shared = ExportHelper.shareFile(
                                    context = context,
                                    file = reportFile,
                                    mimeType = "text/plain",
                                    subject = "BlockAds Logcat & Diagnostics",
                                    chooserTitle = resource.getString(R.string.settings_export_logs_chooser_title)
                                )
                                if (!shared) {
                                    Timber.w("Share sheet failed for logcat, falling back to folder picker")
                                    pendingLogFile = reportFile
                                    withContext(Dispatchers.Main) {
                                        try {
                                            folderLauncher.launch(null)
                                        } catch (e: ActivityNotFoundException) {
                                            Timber.w(e, "OpenDocumentTree failed, falling back to Downloads")
                                            withContext(Dispatchers.IO) {
                                                ExportHelper.saveFileToDownloads(context, reportFile, "text/plain")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to export diagnostics logs")
                            }
                        }
                    }
                )
            }
        }
    }
}
