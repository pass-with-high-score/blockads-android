package app.pwhs.blockads.ui.settings.component

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

@Composable
fun PrivacySection(
    crashReportingEnabled: Boolean,
    onSetCrashReportingEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        SectionHeader(
            title = "Privacy & Diagnostics",
            icon = Icons.Default.PrivacyTip,
            description = "Manage crash reporting and local logs"
        )
        SettingsCard {
            Column {
                SettingsToggleItem(
                    icon = Icons.Default.Security,
                    title = "Anonymous Crash Reporting",
                    subtitle = "Automatically send error logs to help us fix issues. No personal data is included.",
                    isChecked = crashReportingEnabled,
                    onCheckedChange = onSetCrashReportingEnabled
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                SettingsClickItem(
                    icon = Icons.Default.Upload,
                    title = "Export Local Logs",
                    subtitle = "Share offline debug logs directly via Email, Telegram, etc.",
                    onClick = {
                        try {
                            val logFile = java.io.File(context.cacheDir, "logs/blockads_logs.txt")
                            if (logFile.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    logFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "No logs found to export",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            }
        }
    }
}
