package app.pwhs.blockads.service.tailscale

import app.pwhs.blockads.service.GoTunnelAdapter
import app.pwhs.blockads.service.vpn.VpnEngineCoordinator

internal class TailscaleBinder(
    private val getGoTunnelAdapter: () -> GoTunnelAdapter?,
    private val getEngineCoordinator: () -> VpnEngineCoordinator?,
) : ITailscaleService.Stub() {

    override fun getTailscaleStatus(): String {
        val adapter = getGoTunnelAdapter()
        return if (adapter != null) {
            adapter.getTailscaleStatus().ifEmpty { """{"state":"DISCONNECTED"}""" }
        } else {
            """{"state":"DISCONNECTED"}"""
        }
    }

    override fun configureTailscale(stateJson: String?, controlUrl: String?, authKey: String?): String {
        val adapter = getGoTunnelAdapter()
        return if (adapter != null) {
            adapter.configureTailscale(stateJson ?: "", controlUrl ?: "", authKey ?: "")
        } else {
            "{}"
        }
    }

    override fun startTailscaleSession(hostname: String?) {
        synchronized(this) {
            val adapter = getGoTunnelAdapter()
            val coordinator = getEngineCoordinator()
            if (adapter != null) {
                if (coordinator != null) {
                    coordinator.setupTaildropIfNeeded(adapter)
                }
                adapter.startTailscaleSession(hostname ?: "blockads-android")
                if (coordinator != null) {
                    coordinator.startTailscaleBackgroundMonitor(adapter)
                }
            }
        }
    }

    override fun stopTailscale() {
        synchronized(this) {
            val coordinator = getEngineCoordinator()
            if (coordinator != null) {
                coordinator.stopTailscaleMonitor()
            }
            val adapter = getGoTunnelAdapter()
            if (adapter != null) {
                adapter.stopTailscale()
            }
        }
    }

    override fun setTailscaleExitNode(nodeId: Long) {
        val adapter = getGoTunnelAdapter()
        if (adapter != null) {
            adapter.setTailscaleExitNode(nodeId)
        }
    }

    override fun setTailscaleAdvertiseExitNode(enabled: Boolean) {
        val adapter = getGoTunnelAdapter()
        if (adapter != null) {
            adapter.setTailscaleAdvertiseExitNode(enabled)
        }
    }

    override fun setTailscaleAdBlockEnabled(enabled: Boolean) {
        val adapter = getGoTunnelAdapter()
        if (adapter != null) {
            adapter.setTailscaleAdBlockEnabled(enabled)
        }
    }

    override fun sendTailscaleFile(target: String?, fileName: String?, filePath: String?): String {
        val adapter = getGoTunnelAdapter() ?: return "Tunnel not active"
        return adapter.sendTailscaleFile(target ?: "", fileName ?: "", filePath ?: "")
            .fold(
                onSuccess = { "" },
                onFailure = { it.message ?: "Unknown error" }
            )
    }

    override fun pingTailscalePeer(target: String?): String {
        val adapter = getGoTunnelAdapter()
        return if (adapter != null) {
            adapter.pingTailscalePeer(target ?: "")
        } else {
            """{"error":"Tunnel not active"}"""
        }
    }
}
