package app.pwhs.blockadstv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrustedNetworksDialog(
    trustedNetworks: Set<String>,
    pauseOnTrusted: Boolean,
    useSystemDnsOnTrusted: Boolean,
    currentNetwork: String?,
    onTogglePauseOnTrusted: (Boolean) -> Unit,
    onToggleUseSystemDns: (Boolean) -> Unit,
    onAddNetwork: (String) -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var inputNetwork by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, NeonGreen, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dns_system),
                        contentDescription = null,
                        tint = NeonGreen,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.trusted_networks_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (currentNetwork != null) {
                        stringResource(R.string.trusted_networks_current, currentNetwork)
                    } else {
                        stringResource(R.string.trusted_networks_current_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )

                Spacer(modifier = Modifier.height(14.dp))

                TrustedToggleRow(
                    title = stringResource(R.string.trusted_networks_pause_title),
                    subtitle = stringResource(R.string.trusted_networks_pause_desc),
                    checked = pauseOnTrusted,
                    onClick = { onTogglePauseOnTrusted(!pauseOnTrusted) },
                )

                Spacer(modifier = Modifier.height(8.dp))

                TrustedToggleRow(
                    title = stringResource(R.string.trusted_networks_system_dns_title),
                    subtitle = stringResource(R.string.trusted_networks_system_dns_desc),
                    checked = useSystemDnsOnTrusted,
                    onClick = { onToggleUseSystemDns(!useSystemDnsOnTrusted) },
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TvTextInput(
                        value = inputNetwork,
                        onValueChange = { inputNetwork = it },
                        placeholder = stringResource(R.string.trusted_networks_add_hint),
                        modifier = Modifier.weight(1f),
                        onDone = {
                            if (inputNetwork.isNotBlank()) {
                                onAddNetwork(inputNetwork.trim())
                                inputNetwork = ""
                            }
                        },
                    )
                    TrustedActionButton(
                        label = stringResource(R.string.trusted_networks_add_btn),
                        onClick = {
                            if (inputNetwork.isNotBlank()) {
                                onAddNetwork(inputNetwork.trim())
                                inputNetwork = ""
                            }
                        },
                    )
                    if (currentNetwork != null && currentNetwork !in trustedNetworks) {
                        TrustedActionButton(
                            label = stringResource(R.string.trusted_networks_add_current),
                            onClick = { onAddNetwork(currentNetwork) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (trustedNetworks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.trusted_networks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(trustedNetworks.sorted(), key = { it }) { network ->
                            TrustedNetworkItemRow(
                                name = network,
                                onRemove = { onRemoveNetwork(network) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrustedToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.background.copy(alpha = 0.4f)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        TvSwitch(checked = checked)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrustedActionButton(
    label: String,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) NeonGreen.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrustedNetworkItemRow(
    name: String,
    onRemove: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onRemove)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.trusted_networks_remove),
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) NeonGreen else TextSecondary,
        )
    }
}
