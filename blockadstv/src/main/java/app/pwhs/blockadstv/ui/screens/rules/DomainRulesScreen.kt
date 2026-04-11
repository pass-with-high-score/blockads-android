package app.pwhs.blockadstv.ui.screens.rules

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
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import app.pwhs.blockadstv.data.dao.CustomDnsRuleDao
import app.pwhs.blockadstv.data.dao.WhitelistDomainDao
import app.pwhs.blockadstv.data.entities.CustomDnsRule
import app.pwhs.blockadstv.data.entities.RuleType
import app.pwhs.blockadstv.data.entities.WhitelistDomain
import app.pwhs.blockadstv.ui.components.TvTextInput
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import app.pwhs.blockadstv.ui.theme.TextTertiary
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DomainRulesScreen(
    modifier: Modifier = Modifier,
    whitelistDao: WhitelistDomainDao = koinInject(),
    customRuleDao: CustomDnsRuleDao = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val whitelistDomains by whitelistDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val customRules by customRuleDao.getAllFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    var domainInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Default.Rule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Domain Rules", style = MaterialTheme.typography.headlineLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Whitelist or blacklist specific domains",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvTextInput(
                value = domainInput,
                onValueChange = { domainInput = it.trim() },
                placeholder = "Enter domain (e.g. example.com)",
                modifier = Modifier.weight(1f),
                onDone = {},
            )

            ActionButton(
                label = "Allow",
                color = NeonGreen,
                onClick = {
                    val domain = domainInput.trim().lowercase()
                    if (domain.isNotEmpty()) {
                        scope.launch {
                            whitelistDao.insert(WhitelistDomain(domain = domain))
                            domainInput = ""
                        }
                    }
                },
            )

            ActionButton(
                label = "Block",
                color = DangerRed,
                onClick = {
                    val domain = domainInput.trim().lowercase()
                    if (domain.isNotEmpty()) {
                        scope.launch {
                            customRuleDao.insert(
                                CustomDnsRule(
                                    rule = domain,
                                    ruleType = RuleType.BLOCK,
                                    domain = domain,
                                )
                            )
                            domainInput = ""
                        }
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Whitelist section
        if (whitelistDomains.isNotEmpty()) {
            Text(
                text = "Allowed Domains (${whitelistDomains.size})",
                style = MaterialTheme.typography.titleMedium,
                color = NeonGreen,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Blacklist section
        val blockRules = customRules.filter { it.ruleType == RuleType.BLOCK }
        if (blockRules.isNotEmpty()) {
            if (whitelistDomains.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = "Blocked Domains (${blockRules.size})",
                style = MaterialTheme.typography.titleMedium,
                color = DangerRed,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (whitelistDomains.isEmpty() && blockRules.isEmpty()) {
            Text(
                text = "No custom domain rules yet. Add a domain above.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(whitelistDomains, key = { "w_${it.id}" }) { item ->
                    DomainRuleRow(
                        domain = item.domain,
                        isAllow = true,
                        onDelete = {
                            scope.launch { whitelistDao.delete(item) }
                        },
                    )
                }
                items(blockRules, key = { "b_${it.id}" }) { item ->
                    DomainRuleRow(
                        domain = item.domain,
                        isAllow = false,
                        onDelete = {
                            scope.launch { customRuleDao.delete(item) }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DomainRuleRow(
    domain: String,
    isAllow: Boolean,
    onDelete: () -> Unit,
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
                color = if (isFocused) (if (isAllow) NeonGreen else DangerRed) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    onDelete()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isAllow) Icons.Default.CheckCircle else Icons.Default.Block,
            contentDescription = null,
            tint = if (isAllow) NeonGreen else DangerRed,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = domain,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (isAllow) "ALLOW" else "BLOCK",
            style = MaterialTheme.typography.labelMedium,
            color = if (isAllow) NeonGreen else DangerRed,
        )
        if (isFocused) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Delete",
                tint = DangerRed,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) color else MaterialTheme.colorScheme.surfaceVariant,
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = if (isFocused) color else TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) color else TextSecondary,
        )
    }
}
