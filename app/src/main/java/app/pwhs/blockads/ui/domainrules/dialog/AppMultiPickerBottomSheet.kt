package app.pwhs.blockads.ui.domainrules.dialog

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.domainrules.InstalledAppOption
import com.google.accompanist.drawablepainter.rememberDrawablePainter

fun parsePackageNameSet(raw: String): Set<String> {
    if (raw.isBlank()) return emptySet()
    return raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

fun formatPackageNamesLabel(
    raw: String,
    appLabelMap: Map<String, String>
): String? {
    val pkgs = parsePackageNameSet(raw)
    if (pkgs.isEmpty()) return null
    return pkgs.joinToString(", ") { pkg ->
        appLabelMap[pkg] ?: pkg.substringAfterLast('.')
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMultiPickerBottomSheet(
    title: String,
    installedApps: List<InstalledAppOption>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    allAppsLabel: String? = null
) {
    var query by remember { mutableStateOf("") }
    var draft by remember(selected) { mutableStateOf(selected) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sortedApps = remember(installedApps, selected) {
        installedApps.sortedWith(compareByDescending<InstalledAppOption> { it.packageName in selected })
    }
    val filtered = remember(sortedApps, query) {
        if (query.isBlank()) sortedApps
        else sortedApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.rule_pick_app_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                if (allAppsLabel != null && query.isBlank()) {
                    item(key = "__all__") {
                        val isAll = draft.isEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { draft = emptySet() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Apps,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = allAppsLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isAll) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (isAll) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in draft
                    AppPickerRow(
                        app = app,
                        checked = checked,
                        onToggle = {
                            draft = if (checked) draft - app.packageName else draft + app.packageName
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = { onConfirm(draft) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val confirmLabel = if (draft.isNotEmpty()) {
                        "${stringResource(android.R.string.ok)} (${draft.size})"
                    } else {
                        stringResource(android.R.string.ok)
                    }
                    Text(confirmLabel)
                }
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    app: InstalledAppOption,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val appIcon: Drawable? = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() }
        )
        Spacer(modifier = Modifier.width(4.dp))
        if (appIcon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = appIcon),
                contentDescription = app.label,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
