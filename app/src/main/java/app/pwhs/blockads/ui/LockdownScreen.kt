package app.pwhs.blockads.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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
    // Prevent back navigation
    BackHandler(enabled = true) {}

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastActiveTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastActiveRealtime by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }

    // LaunchedEffect ticker running every 1 second
    LaunchedEffect(cooldownStart) {
        if (cooldownStart > 0L) {
            val lastPersistedTime = appPrefs.lastActiveTimestamp.first()
            val lastPersistedRealtime = appPrefs.lastActiveRealtime.first()
            
            val initialWall = System.currentTimeMillis()
            val initialReal = android.os.SystemClock.elapsedRealtime()
            
            if (lastPersistedTime > 0L && lastPersistedRealtime > 0L) {
                if (initialReal >= lastPersistedRealtime) {
                    val wallDiff = initialWall - lastPersistedTime
                    val realDiff = initialReal - lastPersistedRealtime
                    if (initialWall < lastPersistedTime || (wallDiff - realDiff > 5 * 60 * 1000)) {
                        onTimeTamperingDetected()
                        return@LaunchedEffect
                    }
                }
            }
            
            lastActiveTime = initialWall
            lastActiveRealtime = initialReal
            var ticksSinceSave = 0
            
            while (true) {
                currentTime = System.currentTimeMillis()
                val currentRealtime = android.os.SystemClock.elapsedRealtime()
                
                // Clock tampering detection
                if (currentTime < lastActiveTime) {
                    onTimeTamperingDetected()
                    break
                }
                
                val wallClockElapsed = currentTime - lastActiveTime
                val monotonicElapsed = currentRealtime - lastActiveRealtime
                
                if (wallClockElapsed - monotonicElapsed > 5 * 60 * 1000) {
                     onTimeTamperingDetected()
                     break
                }
                
                lastActiveTime = currentTime
                lastActiveRealtime = currentRealtime
                
                ticksSinceSave++
                if (ticksSinceSave >= 10) {
                    appPrefs.setLastActiveTimestamp(currentTime)
                    appPrefs.setLastActiveRealtime(currentRealtime)
                    ticksSinceSave = 0
                }

                val elapsed = currentTime - cooldownStart
                if (elapsed >= duration) {
                    onUnlockComplete()
                    break
                }
                delay(1000L)
            }
        }
    }
    
    val remainingMs = if (cooldownStart > 0L) duration - (currentTime - cooldownStart) else duration
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
                
                Spacer(modifier = Modifier.height(48.dp))
                
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
                val durationMins = duration / (60 * 1000)
                val durationText = if (durationMins >= 60) {
                    val h = durationMins / 60
                    "$h hour" + (if (h > 1) "s" else "")
                } else {
                    "$durationMins minutes"
                }

                Text(
                    text = stringResource(R.string.lockdown_screen_duration, durationText),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = { onStartCooldown(System.currentTimeMillis()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.lockdown_screen_start))
                }
            }
        }
    }
}
