package app.pwhs.blockads.service

import app.pwhs.blockads.data.datastore.AppPreferences

enum class ActualProtectionMode {
    NONE,
    VPN,
    ROOT,
    WIREGUARD,
    CONFLICT,
}

sealed interface ActualProtectionState {
    data object Stopped : ActualProtectionState
    data object StartingVpn : ActualProtectionState
    data object RunningVpn : ActualProtectionState
    data object StartingRoot : ActualProtectionState
    data object RunningRoot : ActualProtectionState
    data object RunningWireGuard : ActualProtectionState
    data object InconsistentBothRunning : ActualProtectionState
    data object InconsistentWrongMode : ActualProtectionState
    data class Failed(val reason: String) : ActualProtectionState
}

enum class ProtectionProblem {
    BOTH_SERVICES_RUNNING,
    WRONG_MODE_RUNNING,
    ROOT_ENGINE_UNHEALTHY,
    ROOT_ROUTING_INACTIVE,
}

data class RootProxyHealth(
    val engineReady: Boolean = false,
    val routingActive: Boolean = false,
    val ipv6RoutingActive: Boolean = false,
    val lastDnsActivity: Long? = null,
)

data class ProtectionHealth(
    val serviceRunning: Boolean,
    val engineReady: Boolean,
    val routingActive: Boolean,
    val ipv6RoutingActive: Boolean,
    val lastDnsActivity: Long?,
    val actualMode: ActualProtectionMode,
    val desiredMode: String,
    val state: ActualProtectionState,
) {
    val problem: ProtectionProblem?
        get() = when (state) {
            ActualProtectionState.InconsistentBothRunning -> ProtectionProblem.BOTH_SERVICES_RUNNING
            ActualProtectionState.InconsistentWrongMode -> ProtectionProblem.WRONG_MODE_RUNNING
            is ActualProtectionState.Failed -> when (state.reason) {
                ServiceController.FAIL_REASON_ROOT_ENGINE -> ProtectionProblem.ROOT_ENGINE_UNHEALTHY
                ServiceController.FAIL_REASON_ROOT_ROUTING -> ProtectionProblem.ROOT_ROUTING_INACTIVE
                else -> ProtectionProblem.ROOT_ENGINE_UNHEALTHY
            }
            else -> null
        }

    val partialIpv6: Boolean
        get() = actualMode == ActualProtectionMode.ROOT && routingActive && !ipv6RoutingActive
}

fun ActualProtectionState.isServiceActive(): Boolean = when (this) {
    ActualProtectionState.RunningVpn,
    ActualProtectionState.RunningRoot,
    ActualProtectionState.RunningWireGuard,
    ActualProtectionState.InconsistentBothRunning,
    ActualProtectionState.InconsistentWrongMode,
    is ActualProtectionState.Failed -> true
    else -> false
}

fun ActualProtectionState.isStartingOrRestarting(): Boolean = when (this) {
    ActualProtectionState.StartingVpn,
    ActualProtectionState.StartingRoot -> true
    else -> false
}

fun String.isRootRoutingMode(): Boolean = this == AppPreferences.ROUTING_MODE_ROOT
