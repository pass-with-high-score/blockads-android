package app.pwhs.blockadstv.ui.screens.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Start
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary

private data class SettingItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val isToggle: Boolean = false,
    val defaultEnabled: Boolean = false,
)

private val settingItems = listOf(
    SettingItem(
        icon = Icons.Default.Start,
        title = "Start on boot",
        subtitle = "Automatically start protection when device boots",
        isToggle = true,
        defaultEnabled = true,
    ),
    SettingItem(
        icon = Icons.Default.Dns,
        title = "DNS Provider",
        subtitle = "System default",
    ),
    SettingItem(
        icon = Icons.Default.Refresh,
        title = "Update filter lists",
        subtitle = "Last updated: Never",
    ),
    SettingItem(
        icon = Icons.Default.Info,
        title = "About",
        subtitle = "BlockAds TV v1.0",
    ),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val toggleStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            settingItems.filter { it.isToggle }.forEach { put(it.title, it.defaultEnabled) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Configure your ad blocking preferences",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Settings list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(settingItems) { setting ->
                val isEnabled = toggleStates[setting.title] ?: false
                SettingListItem(
                    icon = setting.icon,
                    title = setting.title,
                    subtitle = setting.subtitle,
                    isToggle = setting.isToggle,
                    isEnabled = isEnabled,
                    onClick = {
                        if (setting.isToggle) {
                            toggleStates[setting.title] = !isEnabled
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isToggle: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                }
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
                    onClick()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        if (isToggle) {
            TvSwitch(checked = isEnabled)
        }
    }
}
