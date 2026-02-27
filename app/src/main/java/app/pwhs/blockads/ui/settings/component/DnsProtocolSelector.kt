package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsProtocol

@Composable
fun DnsProtocolSelector(
    selectedProtocol: DnsProtocol,
    onProtocolSelected: (DnsProtocol) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DnsProtocol.entries.forEach { protocol ->
            FilterChip(
                selected = selectedProtocol == protocol,
                onClick = { onProtocolSelected(protocol) },
                label = {
                    Text(
                        when (protocol) {
                            DnsProtocol.PLAIN -> stringResource(R.string.settings_dns_protocol_plain)
                            DnsProtocol.DOH -> stringResource(R.string.settings_dns_protocol_doh)
                            DnsProtocol.DOT -> stringResource(R.string.settings_dns_protocol_dot)
                        }
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}
