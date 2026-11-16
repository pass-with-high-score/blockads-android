package app.pwhs.blockads.ui.appearance.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.settings.component.SettingIconBadge
import app.pwhs.blockads.ui.settings.component.SettingsCard

@Composable
fun NavigationCard(
    showBottomNavLabels: Boolean,
    onToggleShowBottomNavLabels: (Boolean) -> Unit,
    bottomNavSecondaryTab: String,
    onSelectBottomNavSecondaryTab: (String) -> Unit,
    floatingCapsuleNav: Boolean,
    onToggleFloatingCapsuleNav: (Boolean) -> Unit,
    isTabletOrFold: Boolean,
    navRailPosition: String,
    onSelectNavRailPosition: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val secondaryTabOptions = listOf(
        AppPreferences.BOTTOM_NAV_TAB_AUTO to stringResource(R.string.settings_bottom_nav_tab_auto),
        AppPreferences.BOTTOM_NAV_TAB_FILTER to stringResource(R.string.settings_bottom_nav_tab_filter),
        AppPreferences.BOTTOM_NAV_TAB_VPN to stringResource(R.string.settings_bottom_nav_tab_vpn)
    )
    val navPositionOptions = listOf(
        AppPreferences.NAV_POSITION_LEFT to stringResource(R.string.settings_nav_position_left),
        AppPreferences.NAV_POSITION_RIGHT to stringResource(R.string.settings_nav_position_right),
        AppPreferences.NAV_POSITION_BOTTOM to stringResource(R.string.settings_nav_position_bottom)
    )

    SettingsCard(modifier = modifier) {
        Column {
            // Row 1: Labels
            NavigationToggleRow(
                icon = Icons.Default.Menu,
                iconTint = Color(0xFF059669),
                title = stringResource(R.string.settings_show_bottom_nav_labels),
                description = stringResource(R.string.settings_show_bottom_nav_labels_desc),
                checked = showBottomNavLabels,
                onCheckedChange = onToggleShowBottomNavLabels
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )

            // Row 2: Capsule Navigation Style (Both phone & tablet/fold)
            NavigationToggleRow(
                icon = Icons.Default.RoundedCorner,
                iconTint = Color(0xFFEA580C),
                title = stringResource(R.string.settings_nav_capsule_style),
                description = stringResource(R.string.settings_nav_capsule_style_desc),
                checked = floatingCapsuleNav,
                onCheckedChange = onToggleFloatingCapsuleNav
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )

            // Row 3: Secondary Tab Picker (Filter vs VPN)
            NavigationDropdownRow(
                icon = Icons.Default.ViewCarousel,
                iconTint = Color(0xFF2563EB),
                title = stringResource(R.string.settings_bottom_nav_secondary_tab),
                options = secondaryTabOptions,
                selectedKey = bottomNavSecondaryTab,
                onSelect = onSelectBottomNavSecondaryTab
            )

            if (isTabletOrFold) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 0.5.dp
                )

                // Row 4: Navigation Position (Left / Right / Bottom) — Tablet & Fold only
                NavigationDropdownRow(
                    icon = Icons.Default.SwapHoriz,
                    iconTint = Color(0xFF7C3AED),
                    title = stringResource(R.string.settings_nav_position),
                    options = navPositionOptions,
                    selectedKey = navRailPosition,
                    onSelect = onSelectNavRailPosition
                )
            }
        }
    }
}

@Composable
private fun NavigationToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIconBadge(icon = icon, tint = iconTint)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun NavigationDropdownRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second
        ?: options.first().second

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingIconBadge(icon = icon, tint = iconTint)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (key, label) ->
                val isSelected = key == selectedKey
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        onSelect(key)
                        expanded = false
                    }
                )
            }
        }
    }
}
