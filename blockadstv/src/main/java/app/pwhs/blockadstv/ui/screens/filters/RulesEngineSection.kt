package app.pwhs.blockadstv.ui.screens.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.data.datastore.RulesPreferences
import app.pwhs.blockadstv.data.models.RuleUpdateStatus
import app.pwhs.blockadstv.data.rules.TvRuleSyncRepository
import app.pwhs.blockadstv.ui.components.TvSwitch
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RulesEngineSection(
    modifier: Modifier = Modifier,
    rulesPrefs: RulesPreferences = koinInject(),
    ruleSyncRepo: TvRuleSyncRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val manifestEnabled by rulesPrefs.ruleManifestEnabled.collectAsStateWithLifecycle(initialValue = true)
    val modulesEnabled by rulesPrefs.modulesEnabled.collectAsStateWithLifecycle(initialValue = true)
    val rulesetEnabled by rulesPrefs.rulesetEnabled.collectAsStateWithLifecycle(initialValue = true)
    val updateStatus by ruleSyncRepo.updateStatus.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Engine Rules & Modules",
            style = MaterialTheme.typography.titleMedium,
            color = NeonGreen,
        )

        EngineToggleRow(
            title = "Dynamic Rule Manifest",
            subtitle = "Per-domain interception & scriptlet manifest",
            checked = manifestEnabled,
            onToggle = { scope.launch { rulesPrefs.setRuleManifestEnabled(!manifestEnabled) } },
        )

        EngineToggleRow(
            title = "Modular Rules",
            subtitle = "Filter & site-specific module rules (modules.json)",
            checked = modulesEnabled,
            onToggle = { scope.launch { rulesPrefs.setModulesEnabled(!modulesEnabled) } },
        )

        EngineToggleRow(
            title = "Custom Ruleset Profile",
            subtitle = "Apply active ruleset & remote filter subscriptions",
            checked = rulesetEnabled,
            onToggle = { scope.launch { rulesPrefs.setRulesetEnabled(!rulesetEnabled) } },
        )

        SyncRulesButton(
            status = updateStatus,
            onClick = {
                scope.launch {
                    ruleSyncRepo.checkAndUpdate()
                    ruleSyncRepo.checkAndUpdateModules()
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EngineToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onToggle()
                    true
                } else false
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        TvSwitch(checked = checked)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncRulesButton(
    status: RuleUpdateStatus,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val label = when (status) {
        is RuleUpdateStatus.Checking -> "Syncing rules..."
        is RuleUpdateStatus.Updated -> "Synced v${status.version} (${status.domainsCount} domains)"
        is RuleUpdateStatus.UpToDate -> "Rules up to date (v${status.version})"
        is RuleUpdateStatus.Error -> "Sync failed: ${status.message}"
        RuleUpdateStatus.Idle -> "Sync Rule Manifest & Modules Now"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isFocused) NeonGreen.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.onSurface,
        )
    }
}
