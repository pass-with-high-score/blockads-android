package app.pwhs.blockadstv.ui.screens.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class AppInfo(
    val name: String,
    val packageName: String,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WhitelistAppScreen(
    modifier: Modifier = Modifier,
    tvPrefs: TvPreferences = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whitelistedApps by tvPrefs.whitelistedApps.collectAsStateWithLifecycle(initialValue = emptySet())
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName) }
                .sortedBy { it.name.lowercase() }
            installedApps = apps
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "App Whitelist", style = MaterialTheme.typography.headlineLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Selected apps will bypass VPN filtering",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        val whitelistedCount = whitelistedApps.size
        Text(
            text = "$whitelistedCount apps whitelisted",
            style = MaterialTheme.typography.labelLarge,
            color = NeonGreen,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (installedApps.isEmpty()) {
            Text(
                text = "Loading installed apps...",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(installedApps, key = { it.packageName }) { app ->
                    val isWhitelisted = app.packageName in whitelistedApps
                    AppListItem(
                        appInfo = app,
                        isWhitelisted = isWhitelisted,
                        onToggle = {
                            scope.launch {
                                tvPrefs.toggleWhitelistedApp(app.packageName)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppListItem(
    appInfo: AppInfo,
    isWhitelisted: Boolean,
    onToggle: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    onToggle()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = appInfo.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = appInfo.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        TvSwitch(checked = isWhitelisted)
    }
}
