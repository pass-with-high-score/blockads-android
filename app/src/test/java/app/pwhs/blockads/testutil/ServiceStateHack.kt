package app.pwhs.blockads.testutil

import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.VpnState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sets service state for tests; reset to STOPPED afterwards. The VPN side goes through its VpnStatusStore,
 * the root proxy still keeps a private companion flow and is poked via reflection.
 */
object ServiceStateHack {
    @Suppress("UNCHECKED_CAST")
    private fun flow(owner: Class<*>): MutableStateFlow<VpnState> =
        owner.getDeclaredField("_state").apply { isAccessible = true }.get(null) as MutableStateFlow<VpnState>

    fun setVpn(state: VpnState) {
        AdBlockVpnService.status.state.value = state
    }

    fun setRoot(state: VpnState) {
        flow(RootProxyService::class.java).value = state
    }

    fun reset() {
        setVpn(VpnState.STOPPED)
        setRoot(VpnState.STOPPED)
    }
}
