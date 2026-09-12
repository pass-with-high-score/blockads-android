package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun DataSection(
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_category_data),
            description = stringResource(R.string.settings_category_data_desc)
        )
        Spacer(modifier = Modifier.height(10.dp))

        SettingsCard {
            Column {
                // 1. Export settings
                SettingItem(
                    icon = Icons.Default.Upload,
                    iconTint = Color(0xFF64748B),
                    title = stringResource(R.string.settings_export),
                    onClick = onExport
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 2. Import settings
                SettingItem(
                    icon = Icons.Default.Download,
                    iconTint = Color(0xFF64748B),
                    title = stringResource(R.string.settings_import),
                    onClick = onImport
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor)

                // 3. Clear logs
                SettingItem(
                    icon = Icons.Default.DeleteForever,
                    iconTint = Color(0xFFE11D48),
                    title = stringResource(R.string.settings_clear_logs),
                    onClick = onClearLogs
                )
            }
        }
    }
}
