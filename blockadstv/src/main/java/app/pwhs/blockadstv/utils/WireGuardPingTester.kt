package app.pwhs.blockadstv.utils

import app.pwhs.blockadstv.data.entities.WireGuardProfile
import app.pwhs.blockadstv.service.TvVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object WireGuardPingTester {

    private const val DEFAULT_TIMEOUT_MS = 8000
    private const val MAX_CONCURRENT_PINGS = 1
    private val wgMutex = Mutex()

    suspend fun pingProfile(
        profile: WireGuardProfile,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): NodePingResult = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            val peer = profile.config.peers.firstOrNull { !it.endpoint.isNullOrBlank() }
            if (peer == null) {
                Timber.w("pingProfile: [${profile.name}] id=${profile.id} has no endpoint")
                return@withContext NodePingResult.Error("No endpoint")
            }

            val protector = TvVpnService.tunnelSocketProtector
            try {
                val resolvedConfig = profile.config.resolveEndpoints()
                val configJson = resolvedConfig.toJson()
                val delayMs = tunnel.Tunnel.testWireGuardDelayProtected(
                    configJson,
                    timeoutMs.toLong(),
                    protector,
                )
                if (delayMs >= 0) {
                    Timber.i("pingProfile: SUCCESS for [${profile.name}] latency=${delayMs}ms")
                    NodePingResult.Success(delayMs)
                } else {
                    Timber.w("pingProfile: NEGATIVE delay returned for [${profile.name}]: $delayMs")
                    NodePingResult.Error("Test failed")
                }
            } catch (t: Throwable) {
                Timber.w(t, "pingProfile: FAILED for [${profile.name}]: ${t.message}")
                val msg = t.message.orEmpty()
                if (t is LinkageError) {
                    fallbackPing(peer.endpoint, timeoutMs)
                } else if (msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("deadline", ignoreCase = true)
                ) {
                    NodePingResult.Timeout
                } else {
                    NodePingResult.Error(msg.ifEmpty { "Failed" })
                }
            }
        }
    }

    private suspend fun fallbackPing(endpoint: String?, timeoutMs: Int): NodePingResult {
        if (endpoint.isNullOrBlank()) return NodePingResult.Error("Invalid endpoint")
        val lastColon = endpoint.lastIndexOf(':')
        if (lastColon <= 0) return NodePingResult.Error("Invalid endpoint")
        val host = endpoint.substring(0, lastColon).trim().removeSurrounding("[", "]")
        val port = endpoint.substring(lastColon + 1).trim().toIntOrNull()
            ?: return NodePingResult.Error("Invalid port")

        val resolvedHost = DnsResolverUtils.resolveHost(host) ?: host
        val socket = Socket()
        TvVpnService.socketProtector?.invoke(socket)
        val start = System.nanoTime()
        return try {
            socket.connect(InetSocketAddress(resolvedHost, port), timeoutMs)
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
        profiles: List<WireGuardProfile>,
        onResult: suspend (id: String, NodePingResult) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_PINGS)
        profiles.map { profile ->
            async {
                semaphore.withPermit {
                    onResult(profile.id, NodePingResult.Testing)
                    val result = pingProfile(profile)
                    onResult(profile.id, result)
                }
            }
        }.awaitAll()
    }
}
