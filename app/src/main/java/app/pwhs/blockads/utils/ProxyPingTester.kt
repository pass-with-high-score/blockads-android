package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ProxyProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

sealed interface NodePingResult {
    data object Testing : NodePingResult
    data class Success(val latencyMs: Long) : NodePingResult
    data object Timeout : NodePingResult
    data class Error(val message: String) : NodePingResult
}

object ProxyPingTester {

    private const val DEFAULT_TIMEOUT_MS = 8000
    private const val MAX_CONCURRENT_PINGS = 5

    /**
     * Measures real delay through proxy protocol to Cloudflare generate_204 endpoint.
     * Falls back to raw TCP connect if proxy protocol test is unsupported or in test environment.
     */
    suspend fun pingNode(profile: ProxyProfile, timeoutMs: Int = DEFAULT_TIMEOUT_MS): NodePingResult =
        withContext(Dispatchers.IO) {
            val repaired = profile.sanitizeOrRepair().resolveServer()
            timber.log.Timber.d("pingNode: testing ${repaired.displayName} [${repaired.protocol}] (${repaired.server}:${repaired.port}) sni=${repaired.sni}")
            timber.log.Timber.d("pingNode: json=${repaired.toEngineJson()}")
            val protector = app.pwhs.blockads.service.AdBlockVpnService.tunnelSocketProtector
            try {
                val delayMs = tunnel.Tunnel.testProxyNodeDelayProtected(
                    repaired.protocol.value,
                    repaired.toEngineJson(),
                    timeoutMs.toLong(),
                    protector
                )
                if (delayMs >= 0) {
                    timber.log.Timber.d("pingNode: success for ${repaired.displayName} latency=${delayMs}ms")
                    NodePingResult.Success(delayMs)
                } else {
                    timber.log.Timber.w("pingNode: negative delay returned for ${repaired.displayName}: $delayMs")
                    NodePingResult.Error("Test failed")
                }
            } catch (t: Throwable) {
                timber.log.Timber.w(t, "pingNode: proxy delay failed for ${repaired.displayName}: ${t.message}")
                val msg = t.message.orEmpty()
                if (t is LinkageError || msg.contains("unsupported protocol", ignoreCase = true)) {
                    fallbackPing(repaired, timeoutMs)
                } else if (msg.contains("timeout", ignoreCase = true) || msg.contains("deadline", ignoreCase = true)) {
                    NodePingResult.Timeout
                } else {
                    NodePingResult.Error(msg.ifEmpty { "Failed" })
                }
            }
        }

    private fun isUdpOnly(protocol: ProxyProtocol): Boolean =
        protocol == ProxyProtocol.HYSTERIA2 ||
        protocol == ProxyProtocol.TUIC ||
        protocol == ProxyProtocol.MASQUE

    private suspend fun fallbackPing(profile: ProxyProfile, timeoutMs: Int): NodePingResult {
        if (isUdpOnly(profile.protocol)) {
            return NodePingResult.Error("Failed")
        }
        return ping(profile.server, profile.port, timeoutMs)
    }

    suspend fun ping(server: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): NodePingResult =
        withContext(Dispatchers.IO) {
            val host = server.trim()
            if (host.isEmpty() || port <= 0 || port > 65535) {
                return@withContext NodePingResult.Error("Invalid address")
            }

            val socket = Socket()
            app.pwhs.blockads.service.AdBlockVpnService.socketProtector?.invoke(socket)
            val start = System.nanoTime()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val latency = (System.nanoTime() - start) / 1_000_000
                NodePingResult.Success(latency)
            } catch (_: SocketTimeoutException) {
                NodePingResult.Timeout
            } catch (e: Exception) {
                NodePingResult.Error(e.message ?: "Failed")
            } finally {
                runCatching { socket.close() }
            }
        }

    suspend fun pingAll(
        profiles: List<ProxyProfile>,
        onResult: suspend (id: String, NodePingResult) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_PINGS)
        profiles.map { profile ->
            async {
                semaphore.withPermit {
                    onResult(profile.id, NodePingResult.Testing)
                    val result = pingNode(profile)
                    onResult(profile.id, result)
                }
            }
        }.awaitAll()
    }
}
