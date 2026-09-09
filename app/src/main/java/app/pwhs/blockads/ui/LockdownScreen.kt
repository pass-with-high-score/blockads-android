package app.pwhs.blockads.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.domainrules.dialog.AddDomainDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LockdownScreen(
    cooldownStart: Long,
    duration: Long,
    onStartCooldown: (Long) -> Unit,
    onCancelCooldown: () -> Unit,
    onUnlockComplete: () -> Unit,
    onTimeTamperingDetected: () -> Unit
) {
    val appPrefs: app.pwhs.blockads.data.datastore.AppPreferences = org.koin.compose.koinInject()
    val customDnsRuleDao: CustomDnsRuleDao = org.koin.compose.koinInject()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }

    // Prevent back navigation
    BackHandler(enabled = true) {}

    var totalElapsedMs by remember(cooldownStart) { mutableLongStateOf(0L) }

    // LaunchedEffect ticker running every 1 second
    LaunchedEffect(cooldownStart) {
        if (cooldownStart > 0L) {
            val lastPersistedTime = appPrefs.lastActiveTimestamp.first()
            val lastPersistedRealtime = appPrefs.lastActiveRealtime.first()
            
            val initialWall = System.currentTimeMillis()
            val initialReal = android.os.SystemClock.elapsedRealtime()
            
            if (lastPersistedTime > 0L && lastPersistedRealtime > 0L) {
                val wallDiff = initialWall - lastPersistedTime
                val realDiff = initialReal - lastPersistedRealtime
                if (initialWall < lastPersistedTime || initialReal < lastPersistedRealtime || (wallDiff - realDiff > 5 * 60 * 1000)) {
                    onTimeTamperingDetected()
                    return@LaunchedEffect
                }
            }
            
            var lastWallTime = initialWall
            var ticksSinceSave = 0
            
            while (true) {
                val currentTime = System.currentTimeMillis()
                val currentRealtime = android.os.SystemClock.elapsedRealtime()
                
                // Clock tampering detection
                if (currentTime < lastWallTime) {
                    onTimeTamperingDetected()
                    break
                }
                
                val totalWallElapsed = currentTime - initialWall
                val totalMonotonicElapsed = currentRealtime - initialReal
                
                if (totalWallElapsed - totalMonotonicElapsed > 5 * 60 * 1000) {
                     onTimeTamperingDetected()
                     break
                }
                
                lastWallTime = currentTime
                
                ticksSinceSave++
                if (ticksSinceSave >= 10) {
                    appPrefs.setLastActiveBaselines(currentTime, currentRealtime)
                    ticksSinceSave = 0
                }

                val elapsedBeforeBaseline = (initialWall - cooldownStart).coerceAtLeast(0L)
                val totalElapsed = elapsedBeforeBaseline + totalMonotonicElapsed
                totalElapsedMs = totalElapsed

                if (totalElapsed >= duration) {
                    onUnlockComplete()
                    break
                }
                delay(1000L)
            }
        }
    }
    
    val remainingMs = if (cooldownStart > 0L) (duration - totalElapsedMs).coerceAtLeast(0L) else duration
    val secondsLeft = (remainingMs / 1000).coerceAtLeast(0)
    
    val isCountingDown = cooldownStart > 0L

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = stringResource(R.string.lockdown_screen_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.lockdown_screen_desc),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (isCountingDown) {
                val hours = secondsLeft / 3600
                val minutes = (secondsLeft % 3600) / 60
                val seconds = secondsLeft % 60
                val timeString = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }
                
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LinearProgressIndicator(
                    progress = { 1f - (remainingMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                
                Spacer(modifier = Modifier.height(36.dp))
                
                Button(
                    onClick = onCancelCooldown,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.lockdown_screen_cancel))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.lockdown_screen_cancel_desc),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val durationMins = (duration / (60 * 1000)).toInt()
                val durationText = if (durationMins >= 60) {
                    val h = durationMins / 60
                    pluralStringResource(R.plurals.lockdown_duration_hours, h, h)
                } else {
                    pluralStringResource(R.plurals.lockdown_duration_minutes, durationMins, durationMins)
                }

                Text(
                    text = stringResource(R.string.lockdown_screen_duration, durationText),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(36.dp))
                
                Button(
                    onClick = { onStartCooldown(System.currentTimeMillis()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.lockdown_screen_start))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.lockdown_screen_add_blacklist))
            }
        }
    }

    if (showAddDialog) {
        AddDomainDialog(
            isAllow = false,
            onDismiss = { showAddDialog = false },
            onAdd = { inputDomain ->
                val cleanDomain = inputDomain.trim().lowercase()
                if (cleanDomain.isNotBlank()) {
                    scope.launch {
                        val allRules = customDnsRuleDao.getAll()
                        val exists = allRules.any {
                            it.ruleType == RuleType.BLOCK && it.domain.equals(cleanDomain, ignoreCase = true)
                        }
                        if (!exists) {
                            customDnsRuleDao.insert(
                                CustomDnsRule(
                                    rule = "||$cleanDomain^",
                                    ruleType = RuleType.BLOCK,
                                    domain = cleanDomain
                                )
                            )
                            Toast.makeText(
                                context,
                                resources.getString(R.string.blocklist_domain_added, cleanDomain),
                                Toast.LENGTH_SHORT
                            ).show()
                            ServiceController.requestRestart(context)
                        } else {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.blocklist_domain_already_exists),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                showAddDialog = false
            }
        )
    }
}
