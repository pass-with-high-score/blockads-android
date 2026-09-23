package app.pwhs.blockads.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Diagnostics classification of network connectivity.
 */
enum class ConnectionStatus {
    /** Physical internet is reachable and the tunnel engine is running. */
    HEALTHY,

    /** Physical internet (Wi-Fi/Cellular) is unavailable. */
    NO_PHYSICAL_INTERNET,

    /** Physical internet is reachable, but the tunnel engine has stopped. */
    VPN_TUNNEL_STALLED
}

data class ProbeResult(
    val status: ConnectionStatus,
    val physicalOk: Boolean,
    val engineOk: Boolean,
    val latencyMs: Long
)

/**
 * "Connection Copilot" link-quality diagnostic probe inspired by ExpressVPN's LinkQualityManager.
 * Distinguishes between physical carrier/Wi-Fi connection loss vs VPN tunnel deadlock.
 */
class ConnectionQualityProbe(
    private val socketProtector: ((Int) -> Boolean)? = null,
    private val isEngineRunning: () -> Boolean = { true },
) {
    companion object {
        private const val PROBE_TIMEOUT_MS = 2500
        // Reliable public DNS IPs for physical internet TCP connectivity test
        private const val CANARY_IP = "1.1.1.1"
        private const val SECONDARY_CANARY_IP = "8.8.8.8"
        private const val CANARY_PORT = 53

        fun classify(physicalOk: Boolean, engineOk: Boolean): ConnectionStatus = when {
            !physicalOk -> ConnectionStatus.NO_PHYSICAL_INTERNET
            !engineOk -> ConnectionStatus.VPN_TUNNEL_STALLED
            else -> ConnectionStatus.HEALTHY
        }
    }

    /**
     * Probes external physical network using a protected socket (bypassing TUN).
     * Returns true if socket connects successfully, false otherwise.
     */
    suspend fun probePhysicalInternet(): Boolean = withContext(Dispatchers.IO) {
        if (probeSocket(CANARY_IP, CANARY_PORT)) return@withContext true
        probeSocket(SECONDARY_CANARY_IP, CANARY_PORT)
    }

    private fun probeSocket(ip: String, port: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socketProtector?.let { protector ->
                try {
                    val pfd = android.os.ParcelFileDescriptor.fromSocket(socket)
                    protector(pfd.fd)
                    pfd.detachFd()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to protect probe socket")
                }
            }
            val endpoint = InetSocketAddress(InetAddress.getByName(ip), port)
            socket.connect(endpoint, PROBE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Timber.d("Physical internet probe failed for $ip: ${e.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Executes the diagnosis: physical internet reachability, then tunnel engine liveness.
     * BlockAds excludes itself from its own VPN, so a socket here can never reach the TUN's DNS.
     */
    suspend fun runDiagnosis(): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val physical = probePhysicalInternet()
        val latency = System.currentTimeMillis() - start
        val engine = isEngineRunning()

        ProbeResult(
            status = classify(physical, engine),
            physicalOk = physical,
            engineOk = engine,
            latencyMs = latency
        )
    }
}
