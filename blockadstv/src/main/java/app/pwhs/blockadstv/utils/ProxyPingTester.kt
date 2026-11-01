package app.pwhs.blockadstv.utils

import app.pwhs.blockadstv.data.entities.ProxyProfile
import app.pwhs.blockadstv.data.entities.ProxyProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    private const val DEFAULT_TIMEOUT_MS = 6000
    private const val MAX_CONCURRENT_PINGS = 5

    suspend fun pingNode(profile: ProxyProfile, timeoutMs: Int = DEFAULT_TIMEOUT_MS): NodePingResult =
        withContext(Dispatchers.IO) {
            val repaired = profile.sanitizeOrRepair()
            Timber.d("pingNode: testing ${repaired.displayName} (${repaired.server}:${repaired.port})")
            try {
                val delayMs = tunnel.Tunnel.testProxyNodeDelay(
                    repaired.protocol.value,
                    repaired.toEngineJson(),
                    timeoutMs.toLong()
                )
                if (delayMs >= 0) {
                    Timber.d("pingNode: delay for ${repaired.displayName} = ${delayMs}ms")
                    NodePingResult.Success(delayMs)
                } else {
                    fallbackPing(repaired, timeoutMs)
                }
            } catch (t: Throwable) {
                Timber.w("pingNode: proxy delay failed for ${repaired.displayName}: ${t.message}")
                val msg = t.message.orEmpty()
                if (msg.contains("timeout", ignoreCase = true) || msg.contains("deadline", ignoreCase = true)) {
                    NodePingResult.Timeout
                } else {
                    fallbackPing(repaired, timeoutMs)
                }
            }
        }

    private suspend fun fallbackPing(profile: ProxyProfile, timeoutMs: Int): NodePingResult {
        if (profile.protocol == ProxyProtocol.HYSTERIA2 ||
            profile.protocol == ProxyProtocol.TUIC ||
            profile.protocol == ProxyProtocol.MASQUE
        ) {
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
