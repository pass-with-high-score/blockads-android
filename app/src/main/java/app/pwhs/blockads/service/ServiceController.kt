package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.worker.RootProxyResumeWorker
import app.pwhs.blockads.worker.VpnResumeWorker
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Single entry point for starting, stopping, restarting, and reconciling the
 * protection services. The preference stores the desired routing mode; this
 * controller makes the real services match it.
 */
object ServiceController {
    const val FAIL_REASON_ROOT_ENGINE = "root_engine_unhealthy"
    const val FAIL_REASON_ROOT_ROUTING = "root_routing_inactive"

    private const val SERVICE_SWITCH_DELAY_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun protectionHealthFlow(appPrefs: AppPreferences): Flow<ProtectionHealth> = combine(
        AdBlockVpnService.state,
        RootProxyService.state,
        RootProxyService.health,
        appPrefs.routingMode
    ) { vpnState, rootState, rootHealth, desiredMode ->
        val state = deriveActualState(vpnState, rootState, rootHealth, desiredMode)
        ProtectionHealth(
            serviceRunning = state.isServiceActive(),
            engineReady = when (actualMode(vpnState, rootState, desiredMode)) {
                ActualProtectionMode.ROOT -> rootHealth.engineReady
                ActualProtectionMode.VPN,
                ActualProtectionMode.WIREGUARD -> vpnState == VpnState.RUNNING
                ActualProtectionMode.CONFLICT -> false
                ActualProtectionMode.NONE -> false
            },
            routingActive = when (actualMode(vpnState, rootState, desiredMode)) {
                ActualProtectionMode.ROOT -> rootHealth.routingActive
                ActualProtectionMode.VPN,
                ActualProtectionMode.WIREGUARD -> vpnState == VpnState.RUNNING
                ActualProtectionMode.CONFLICT -> false
                ActualProtectionMode.NONE -> false
            },
            ipv6RoutingActive = when (actualMode(vpnState, rootState, desiredMode)) {
                ActualProtectionMode.ROOT -> rootHealth.ipv6RoutingActive
                ActualProtectionMode.VPN,
                ActualProtectionMode.WIREGUARD -> true
                ActualProtectionMode.CONFLICT,
                ActualProtectionMode.NONE -> false
            },
            lastDnsActivity = rootHealth.lastDnsActivity,
            actualMode = actualMode(vpnState, rootState, desiredMode),
            desiredMode = desiredMode,
            state = state,
        )
    }

    fun deriveActualState(
        vpnState: VpnState,
        rootState: VpnState,
        rootHealth: RootProxyHealth,
        desiredMode: String,
    ): ActualProtectionState {
        val vpnActive = vpnState.isActiveState()
        val rootActive = rootState.isActiveState()

        if (vpnActive && rootActive) return ActualProtectionState.InconsistentBothRunning

        if (rootState == VpnState.RUNNING) {
            if (desiredMode != AppPreferences.ROUTING_MODE_ROOT) {
                return ActualProtectionState.InconsistentWrongMode
            }
            if (!rootHealth.engineReady) {
                return ActualProtectionState.Failed(FAIL_REASON_ROOT_ENGINE)
            }
            if (!rootHealth.routingActive) {
                return ActualProtectionState.Failed(FAIL_REASON_ROOT_ROUTING)
            }
            return ActualProtectionState.RunningRoot
        }

        if (vpnState == VpnState.RUNNING) {
            if (desiredMode == AppPreferences.ROUTING_MODE_ROOT) {
                return ActualProtectionState.InconsistentWrongMode
            }
            return if (desiredMode == AppPreferences.ROUTING_MODE_WIREGUARD) {
                ActualProtectionState.RunningWireGuard
            } else {
                ActualProtectionState.RunningVpn
            }
        }

        if (rootState == VpnState.STARTING || rootState == VpnState.RESTARTING) {
            return if (desiredMode == AppPreferences.ROUTING_MODE_ROOT) {
                ActualProtectionState.StartingRoot
            } else {
                ActualProtectionState.InconsistentWrongMode
            }
        }

        if (vpnState == VpnState.STARTING || vpnState == VpnState.RESTARTING) {
            return if (desiredMode == AppPreferences.ROUTING_MODE_ROOT) {
                ActualProtectionState.InconsistentWrongMode
            } else {
                ActualProtectionState.StartingVpn
            }
        }

        return ActualProtectionState.Stopped
    }

    fun actualMode(
        vpnState: VpnState = AdBlockVpnService.state.value,
        rootState: VpnState = RootProxyService.state.value,
        desiredMode: String,
    ): ActualProtectionMode {
        val vpnActive = vpnState.isActiveState()
        val rootActive = rootState.isActiveState()

        return when {
            vpnActive && rootActive -> ActualProtectionMode.CONFLICT
            rootActive -> ActualProtectionMode.ROOT
            vpnActive && desiredMode == AppPreferences.ROUTING_MODE_WIREGUARD -> ActualProtectionMode.WIREGUARD
            vpnActive -> ActualProtectionMode.VPN
            else -> ActualProtectionMode.NONE
        }
    }

    fun requestStart(context: Context, startedFromBoot: Boolean = false) {
        scope.launch {
            requestStartNow(context.applicationContext, startedFromBoot)
        }
    }

    suspend fun requestStartNow(context: Context, startedFromBoot: Boolean = false) {
        val appContext = context.applicationContext
        val appPrefs = AppPreferences(appContext)
        appPrefs.setProtectionDesired(true)
        appPrefs.setPausedByTrusted(false)
        cancelResumeWork(appContext)
        reconcileToMode(appContext, appPrefs.routingMode.first(), startIfDesired = true, startedFromBoot = startedFromBoot)
    }

    fun requestStop(context: Context) {
        scope.launch {
            requestStopNow(context.applicationContext, markProtectionDisabled = true)
        }
    }

    fun requestPause(context: Context) {
        scope.launch {
            requestStopNow(context.applicationContext, markProtectionDisabled = false)
        }
    }

    suspend fun requestStopNow(context: Context, markProtectionDisabled: Boolean) {
        val appContext = context.applicationContext
        val appPrefs = AppPreferences(appContext)
        cancelResumeWork(appContext)
        if (markProtectionDisabled) {
            appPrefs.setProtectionDesired(false)
            appPrefs.setPausedByTrusted(false)
        }
        stopServices(appContext, markProtectionDisabled = markProtectionDisabled)
    }

    fun requestRestart(context: Context) {
        scope.launch {
            requestRestartNow(context.applicationContext)
        }
    }

    suspend fun requestRestartNow(context: Context) {
        val appContext = context.applicationContext
        val appPrefs = AppPreferences(appContext)
        val desiredMode = appPrefs.routingMode.first()
        val shouldBeRunning = appPrefs.protectionDesired.first() ||
            AdBlockVpnService.state.value.isActiveState() ||
            RootProxyService.state.value.isActiveState()

        if (!shouldBeRunning) return

        reconcileToMode(
            context = appContext,
            targetMode = desiredMode,
            startIfDesired = true,
            restartTarget = true,
        )
    }

    suspend fun reconcileToMode(
        context: Context,
        targetMode: String,
        startIfDesired: Boolean = true,
        restartTarget: Boolean = false,
        startedFromBoot: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val appPrefs = AppPreferences(appContext)
        appPrefs.setRoutingMode(targetMode)
        cancelResumeWork(appContext)

        val protectionDesired = appPrefs.protectionDesired.first()
        val shouldRun = startIfDesired && (
            protectionDesired ||
                AdBlockVpnService.state.value.isActiveState() ||
                RootProxyService.state.value.isActiveState()
            )

        if (targetMode == AppPreferences.ROUTING_MODE_ROOT) {
            if (AdBlockVpnService.state.value.isActiveState()) {
                AdBlockVpnService.stop(appContext, markProtectionDisabled = false)
                delay(SERVICE_SWITCH_DELAY_MS)
            }

            if (shouldRun) {
                if (RootProxyService.state.value == VpnState.RUNNING && restartTarget) {
                    RootProxyService.requestReload(appContext)
                } else if (!RootProxyService.state.value.isActiveState()) {
                    RootProxyService.start(appContext, startedFromBoot = startedFromBoot)
                }
            } else if (RootProxyService.state.value.isActiveState()) {
                RootProxyService.stop(appContext, markProtectionDisabled = false)
            }
            return
        }

        if (RootProxyService.state.value.isActiveState()) {
            RootProxyService.stop(appContext, markProtectionDisabled = false)
            delay(SERVICE_SWITCH_DELAY_MS)
        }

        if (shouldRun) {
            if (AdBlockVpnService.state.value == VpnState.RUNNING && restartTarget) {
                AdBlockVpnService.requestRestart(appContext)
            } else if (!AdBlockVpnService.state.value.isActiveState()) {
                AdBlockVpnService.start(appContext, startedFromBoot = startedFromBoot)
            }
        } else if (AdBlockVpnService.state.value.isActiveState()) {
            AdBlockVpnService.stop(appContext, markProtectionDisabled = false)
        }
    }

    fun cancelResumeWork(context: Context) {
        runCatching {
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(VpnResumeWorker.WORK_NAME)
            workManager.cancelUniqueWork(RootProxyResumeWorker.WORK_NAME)
        }.onFailure {
            Timber.w(it, "Failed to cancel resume work")
        }
    }

    private fun stopServices(context: Context, markProtectionDisabled: Boolean) {
        if (AdBlockVpnService.state.value.isActiveState()) {
            AdBlockVpnService.stop(context, markProtectionDisabled = markProtectionDisabled)
        }
        if (RootProxyService.state.value.isActiveState()) {
            RootProxyService.stop(context, markProtectionDisabled = markProtectionDisabled)
        }
    }

    private fun VpnState.isActiveState(): Boolean = when (this) {
        VpnState.STARTING,
        VpnState.RUNNING,
        VpnState.STOPPING,
        VpnState.RESTARTING -> true
        VpnState.STOPPED -> false
    }
}
