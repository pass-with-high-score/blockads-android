package app.pwhs.blockadstv.ui.screens.dns

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private data class DnsPreset(
    val name: String,
    val primary: String,
    val secondary: String,
    val description: String,
)

private val dnsPresets = listOf(
    DnsPreset("Quad9", "9.9.9.9", "149.112.112.112", "Malware blocking, privacy"),
    DnsPreset("AdGuard DNS", "94.140.14.14", "94.140.15.15", "Ad blocking DNS"),
    DnsPreset("Cloudflare", "1.1.1.1", "1.0.0.1", "Fast, privacy-focused"),
    DnsPreset("Google", "8.8.8.8", "8.8.4.4", "Reliable, global"),
    DnsPreset("OpenDNS", "208.67.222.222", "208.67.220.220", "Security filtering"),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DnsSetupScreen(
    modifier: Modifier = Modifier,
    tvPrefs: TvPreferences = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val currentPrimary by tvPrefs.upstreamDns.collectAsStateWithLifecycle(
        initialValue = TvPreferences.DEFAULT_UPSTREAM_DNS
    )
    val currentFallback by tvPrefs.fallbackDns.collectAsStateWithLifecycle(
        initialValue = TvPreferences.DEFAULT_FALLBACK_DNS
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "DNS Setup", style = MaterialTheme.typography.headlineLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Current: $currentPrimary / $currentFallback",
            style = MaterialTheme.typography.bodyLarge,
            color = NeonGreen,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Select a DNS provider",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(dnsPresets) { preset ->
                val isActive = currentPrimary == preset.primary
                DnsPresetItem(
                    preset = preset,
                    isActive = isActive,
                    onClick = {
                        scope.launch {
                            tvPrefs.setUpstreamDns(preset.primary)
                            tvPrefs.setFallbackDns(preset.secondary)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DnsPresetItem(
    preset: DnsPreset,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) NeonGreen.copy(alpha = 0.1f)
                else if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isActive -> NeonGreen
                    isFocused -> NeonGreen
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
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
        Column(modifier = Modifier.weight(1f)) {
            Text(text = preset.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${preset.primary} / ${preset.secondary}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                text = preset.description,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
        if (isActive) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Active",
                tint = NeonGreen,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
