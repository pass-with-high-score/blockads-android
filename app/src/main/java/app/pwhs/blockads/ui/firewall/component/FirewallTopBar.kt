package app.pwhs.blockads.ui.firewall.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallTopBar(
    isFirewallEnabled: Boolean,
    enabledCount: Int,
    allUserEnabled: Boolean,
    allSystemEnabled: Boolean,
    isSearchVisible: Boolean,
    onToggleSearch: () -> Unit,
    onToggleFirewall: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onToggleAllUserApps: () -> Unit,
    onToggleAllSystemApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenuDropdown by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.firewall_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isFirewallEnabled) {
                        stringResource(R.string.firewall_count, enabledCount)
                    } else {
                        stringResource(R.string.firewall_status_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFirewallEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        ),
        actions = {
            // Master Switch placed compactly in TopAppBar
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = isFirewallEnabled,
                    onCheckedChange = onToggleFirewall,
                    modifier = Modifier.scale(0.85f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary
                    )
                }
                IconButton(onClick = { showMenuDropdown = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            DropdownMenu(
                containerColor = MaterialTheme.colorScheme.background,
                expanded = showMenuDropdown,
                onDismissRequest = { showMenuDropdown = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.app_management_refresh)) },
                    onClick = {
                        showMenuDropdown = false
                        onRefresh()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null
                        )
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (allUserEnabled) R.string.firewall_disable_all_user
                                else R.string.firewall_enable_all_user
                            )
                        )
                    },
                    onClick = {
                        showMenuDropdown = false
                        onToggleAllUserApps()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null
                        )
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (allSystemEnabled) R.string.firewall_disable_all_system
                                else R.string.firewall_enable_all_system
                            )
                        )
                    },
                    onClick = {
                        showMenuDropdown = false
                        onToggleAllSystemApps()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Android,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    )
}
