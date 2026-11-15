package app.pwhs.blockads.ui.home.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.home.HomeViewModel

@Composable
fun rememberHomeTipState(
    viewModel: HomeViewModel,
    routingMode: String,
    onNavigateToFeature: (HomeFeatureTipType) -> Unit
): HomeTipState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val stabilityDismissedAt by viewModel.stabilityTipDismissedAt.collectAsStateWithLifecycle()
    val dismissedFeatures by viewModel.dismissedFeatureTips.collectAsStateWithLifecycle()
    val homeTipsEnabled by viewModel.homeTipsEnabled.collectAsStateWithLifecycle()

    var isBatteryIgnored by remember { mutableStateOf(checkBatteryIgnored(context)) }
    var isAlwaysOn by remember { mutableStateOf(checkAlwaysOnVpn(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val currentBattery = checkBatteryIgnored(context)
                val currentAlwaysOn = checkAlwaysOnVpn(context)
                isBatteryIgnored = currentBattery
                isAlwaysOn = currentAlwaysOn
                viewModel.updateStabilityTipHardwareState(currentBattery, currentAlwaysOn)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isRootMode = routingMode == AppPreferences.ROUTING_MODE_ROOT
    val bothStabilityCompleted = isBatteryIgnored && isAlwaysOn

    // Priority 1: Check if Stability Pair (Slide 1/2) should be shown
    val showStabilityPair = remember(isRootMode, bothStabilityCompleted, stabilityDismissedAt) {
        if (isRootMode || bothStabilityCompleted) {
            false
        } else if (stabilityDismissedAt <= 0L) {
            true
        } else {
            val elapsed = System.currentTimeMillis() - stabilityDismissedAt
            elapsed >= STABILITY_TIP_SNOOZE_MILLIS
        }
    }

    val hasInteractedThisSession by viewModel.hasInteractedWithTipThisSession.collectAsStateWithLifecycle()

    if (!homeTipsEnabled || hasInteractedThisSession) {
        return HomeTipState.None
    }

    if (showStabilityPair) {
        return remember(stabilityDismissedAt, isBatteryIgnored, isAlwaysOn) {
            HomeTipState.StabilityPair(
                onDismiss = {
                    viewModel.dismissStabilityTip(
                        batteryIgnored = checkBatteryIgnored(context),
                        alwaysOn = checkAlwaysOnVpn(context)
                    )
                }
            )
        }
    }

    // Priority 2 to 5: Find the first feature tip not yet dismissed
    val nextFeature = remember(dismissedFeatures) {
        HomeFeatureTipType.PRIORITY_LIST.firstOrNull { type ->
            !dismissedFeatures.contains(type.id)
        }
    }

    if (nextFeature != null && !isRootMode) {
        return remember(nextFeature) {
            HomeTipState.SingleFeature(
                type = nextFeature,
                onAction = {
                    viewModel.dismissFeatureTip(nextFeature.id)
                    onNavigateToFeature(nextFeature)
                },
                onDismiss = {
                    viewModel.dismissFeatureTip(nextFeature.id)
                }
            )
        }
    }

    return HomeTipState.None
}

@Composable
fun HomeActiveTipCard(
    tipState: HomeTipState,
    modifier: Modifier = Modifier
) {
    when (tipState) {
        is HomeTipState.StabilityPair -> HomeBackgroundVpnTipCard(
            onDismiss = tipState.onDismiss,
            modifier = modifier
        )
        is HomeTipState.SingleFeature -> HomeFeatureTipCard(
            type = tipState.type,
            onAction = tipState.onAction,
            onDismiss = tipState.onDismiss,
            modifier = modifier
        )
        HomeTipState.None -> Unit
    }
}

