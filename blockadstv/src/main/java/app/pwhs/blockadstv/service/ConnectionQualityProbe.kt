package app.pwhs.blockadstv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel

enum class ConnectionStatus {
    HEALTHY,
    NO_PHYSICAL_INTERNET,
    VPN_TUNNEL_STALLED,
}

data class ProbeResult(
    val status: ConnectionStatus,
    val physicalOk: Boolean,
    val engineOk: Boolean,
    val latencyMs: Long,
)

/**
 * Link-quality diagnostic probe that distinguishes between physical network loss
 * and a stalled VPN tunnel engine.
 */
class ConnectionQualityProbe(
    private val socketProtector: ((Socket) -> Boolean)? = null,
    private val isEngineRunning: () -> Boolean = { true },
) {
    companion object {
        private const val PROBE_TIMEOUT_MS = 2500
        private const val CANARY_IP = "1.1.1.1"
        private const val SECONDARY_CANARY_IP = "8.8.8.8"
        private const val CANARY_PORT = 53

        fun classify(physicalOk: Boolean, engineOk: Boolean): ConnectionStatus = when {
            !physicalOk -> ConnectionStatus.NO_PHYSICAL_INTERNET
            !engineOk -> ConnectionStatus.VPN_TUNNEL_STALLED
            else -> ConnectionStatus.HEALTHY
        }
    }

    suspend fun probePhysicalInternet(): Boolean = withContext(Dispatchers.IO) {
        if (probeSocket(CANARY_IP, CANARY_PORT)) return@withContext true
        probeSocket(SECONDARY_CANARY_IP, CANARY_PORT)
    }

    private fun probeSocket(ip: String, port: Int): Boolean {
        var channel: SocketChannel? = null
        return try {
            channel = SocketChannel.open()
            channel.configureBlocking(true)
            val socket = channel.socket()
            socketProtector?.let { protector ->
                if (!protector(socket)) {
                    Timber.w("Failed to protect probe socket")
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
                channel?.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun runDiagnosis(): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val physical = probePhysicalInternet()
        val latency = System.currentTimeMillis() - start
        val engine = isEngineRunning()

        ProbeResult(
            status = classify(physical, engine),
            physicalOk = physical,
            engineOk = engine,
            latencyMs = latency,
        )
    }
}
