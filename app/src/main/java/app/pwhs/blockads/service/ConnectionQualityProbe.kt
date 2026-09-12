package app.pwhs.blockads.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Diagnostics classification of network connectivity.
 */
enum class ConnectionStatus {
    /** Both physical internet and VPN DNS are operational. */
    HEALTHY,

    /** Physical internet (Wi-Fi/Cellular) is unavailable. */
    NO_PHYSICAL_INTERNET,

    /** Physical internet is reachable, but VPN TUN or DNS resolution is stalled. */
    VPN_TUNNEL_STALLED
}

data class ProbeResult(
    val status: ConnectionStatus,
    val physicalOk: Boolean,
    val vpnDnsOk: Boolean,
    val latencyMs: Long
)

/**
 * "Connection Copilot" link-quality diagnostic probe inspired by ExpressVPN's LinkQualityManager.
 * Distinguishes between physical carrier/Wi-Fi connection loss vs VPN tunnel deadlock.
 */
class ConnectionQualityProbe(
    private val socketProtector: ((Int) -> Boolean)? = null
) {
    companion object {
        private const val PROBE_TIMEOUT_MS = 2500
        // Reliable public DNS IP for physical internet TCP connectivity test
        private const val CANARY_IP = "1.1.1.1"
        private const val CANARY_PORT = 53
        // Local fake DNS IP of BlockAds TUN (RFC 6598 CGNAT)
        private const val TUN_DNS_IP = "100.64.100.1"
        private const val TUN_DNS_PORT = 53
    }

    /**
     * Probes external physical network using a protected socket (bypassing TUN).
     * Returns true if socket connects successfully, false otherwise.
     */
    suspend fun probePhysicalInternet(): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
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
            val endpoint = InetSocketAddress(InetAddress.getByName(CANARY_IP), CANARY_PORT)
            socket.connect(endpoint, PROBE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Timber.d("Physical internet probe failed: ${e.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Probes VPN tunnel health by sending a lightweight UDP DNS query to local TUN DNS.
     * Returns true if a valid DNS response is received, false on timeout/failure.
     */
    suspend fun probeVpnDns(): Boolean = withContext(Dispatchers.IO) {
        var dsocket: DatagramSocket? = null
        try {
            dsocket = DatagramSocket()
            dsocket.soTimeout = PROBE_TIMEOUT_MS

            // Standard DNS query packet for "cloudflare.com" type A (ID 0x1234)
            val dnsQuery = byteArrayOf(
                0x12.toByte(), 0x34.toByte(), // ID: 0x1234
                0x01.toByte(), 0x00.toByte(), // Flags: standard query, RD=1
                0x00.toByte(), 0x01.toByte(), // QDCOUNT: 1
                0x00.toByte(), 0x00.toByte(), // ANCOUNT: 0
                0x00.toByte(), 0x00.toByte(), // NSCOUNT: 0
                0x00.toByte(), 0x00.toByte(), // ARCOUNT: 0
                // QNAME: \x0a cloudflare \x03 com \x00
                10, 'c'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(),
                'd'.code.toByte(), 'f'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(),
                'r'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
                0x00.toByte(), 0x01.toByte(), // QTYPE: A
                0x00.toByte(), 0x01.toByte()  // QCLASS: IN
            )

            val destAddr = InetAddress.getByName(TUN_DNS_IP)
            val sendPacket = DatagramPacket(dnsQuery, dnsQuery.size, destAddr, TUN_DNS_PORT)
            dsocket.send(sendPacket)

            val buffer = ByteArray(512)
            val recvPacket = DatagramPacket(buffer, buffer.size)
            dsocket.receive(recvPacket)

            // Verify response ID matches query ID
            val respId = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            respId == 0x1234
        } catch (e: Exception) {
            Timber.d("VPN DNS probe failed: ${e.message}")
            false
        } finally {
            try {
                dsocket?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Executes full 2-step diagnosis:
     * 1. Physical internet reachability
     * 2. VPN DNS interception & response
     */
    suspend fun runDiagnosis(): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val physical = probePhysicalInternet()
        val latency = System.currentTimeMillis() - start

        val vpnDns = if (physical) {
            probeVpnDns()
        } else {
            false
        }

        val status = when {
            !physical -> ConnectionStatus.NO_PHYSICAL_INTERNET
            !vpnDns -> ConnectionStatus.VPN_TUNNEL_STALLED
            else -> ConnectionStatus.HEALTHY
        }

        ProbeResult(
            status = status,
            physicalOk = physical,
            vpnDnsOk = vpnDns,
            latencyMs = latency
        )
    }
}
