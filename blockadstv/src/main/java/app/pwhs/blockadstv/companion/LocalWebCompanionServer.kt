package app.pwhs.blockadstv.companion

import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.datastore.WireGuardPreferences
import app.pwhs.blockadstv.data.entities.WireGuardProfile
import app.pwhs.blockadstv.utils.ProxyLinkParser
import app.pwhs.blockadstv.utils.SubscriptionManager
import app.pwhs.blockadstv.utils.WireGuardConfigParser
import app.pwhs.blockadstv.utils.configIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

data class CompanionServerStatus(
    val isRunning: Boolean = false,
    val url: String? = null,
    val port: Int = 0,
    val lastActivity: String? = null,
    val error: String? = null,
)

class LocalWebCompanionServer(
    private val wireGuardPrefs: WireGuardPreferences,
    private val tvPrefs: TvPreferences,
    private val subscriptionManager: SubscriptionManager,
    private val backupHelper: CompanionBackupHelper,
) {
    @Serializable
    private data class WireGuardPayload(val name: String = "", val config: String = "")

    @Serializable
    private data class ProxyPayload(val link: String = "")

    @Serializable
    private data class SubscriptionPayload(val url: String = "", val name: String = "")

    @Serializable
    private data class BackupPayload(val json: String = "")

    @Serializable
    private data class ApiResponse(val ok: Boolean, val message: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _status = MutableStateFlow(CompanionServerStatus())
    val status: StateFlow<CompanionServerStatus> = _status.asStateFlow()

    @Synchronized
    fun start(preferredPort: Int = 0) {
        if (_status.value.isRunning && serverSocket?.isClosed == false) return
        stop()

        val lanIp = findLanIpAddress()
        if (lanIp == null) {
            _status.value = CompanionServerStatus(
                isRunning = false,
                error = "No LAN IPv4 address found. Connect TV to Wi-Fi or Ethernet.",
            )
            return
        }

        try {
            val bindAddr = InetAddress.getByName(lanIp)
            val socket = ServerSocket(preferredPort, 50, bindAddr).apply {
                reuseAddress = true
            }
            serverSocket = socket
            val actualPort = socket.localPort
            val url = "http://$lanIp:$actualPort/"

            _status.value = CompanionServerStatus(
                isRunning = true,
                url = url,
                port = actualPort,
                lastActivity = "Waiting for phone connection...",
            )

            acceptJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch { handleClient(client, url) }
                    } catch (_: Exception) {
                        if (!socket.isClosed) {
                            Timber.w("Companion server accept interrupted")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start LocalWebCompanionServer")
            _status.value = CompanionServerStatus(
                isRunning = false,
                error = e.message ?: "Failed to start web companion server",
            )
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _status.update { it.copy(isRunning = false, url = null, port = 0) }
    }

    private suspend fun handleClient(socket: Socket, serverUrl: String) {
        socket.use { client ->
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val request = readHttpRequest(input) ?: return

            val (statusCode, contentType, bodyBytes, extraHeaders) = when {
                request.method == "GET" && request.path == "/" -> {
                    HttpReply(
                        code = 200,
                        contentType = "text/html; charset=utf-8",
                        body = CompanionHtmlPage.render(serverUrl).toByteArray(StandardCharsets.UTF_8),
                    )
                }
                request.method == "GET" && request.path == "/api/status" -> {
                    jsonReply(200, ApiResponse(ok = true, message = "BlockAds TV Companion running"))
                }
                request.method == "GET" && request.path == "/api/backup" -> {
                    val backupJson = backupHelper.exportBackupJson()
                    _status.update { it.copy(lastActivity = "Exported settings backup to phone") }
                    HttpReply(
                        code = 200,
                        contentType = "application/json; charset=utf-8",
                        body = backupJson.toByteArray(StandardCharsets.UTF_8),
                        extraHeaders = mapOf(
                            "Content-Disposition" to "attachment; filename=\"blockadstv-backup.json\"",
                        ),
                    )
                }
                request.method == "POST" && request.path == "/api/wireguard" -> {
                    handleWireGuardPush(request.body)
                }
                request.method == "POST" && request.path == "/api/proxy" -> {
                    handleProxyPush(request.body)
                }
                request.method == "POST" && request.path == "/api/subscription" -> {
                    handleSubscriptionPush(request.body)
                }
                request.method == "POST" && request.path == "/api/backup" -> {
                    handleBackupPush(request.body)
                }
                else -> {
                    jsonReply(404, ApiResponse(ok = false, message = "Not found"))
                }
            }

            writeHttpResponse(client, statusCode, contentType, bodyBytes, extraHeaders)
        }
    }

    private suspend fun handleWireGuardPush(body: String): HttpReply {
        return runCatching {
            val payload = runCatching { json.decodeFromString<WireGuardPayload>(body) }.getOrNull()
            val rawConfig = (payload?.config ?: body).trim()
            require(rawConfig.isNotEmpty()) { "Empty WireGuard configuration" }

            val profile = when {
                rawConfig.startsWith("wireguard://", ignoreCase = true) -> {
                    val parsed = WireGuardConfigParser.parseUri(rawConfig)
                    val customName = payload?.name?.trim().orEmpty()
                    if (customName.isNotEmpty()) parsed.copy(name = customName) else parsed
                }
                rawConfig.startsWith("vpn://", ignoreCase = true) ||
                    rawConfig.startsWith("awg://", ignoreCase = true) -> {
                    val parsed = WireGuardConfigParser.parseVpnUri(rawConfig)
                    val customName = payload?.name?.trim().orEmpty()
                    if (customName.isNotEmpty()) parsed.copy(name = customName) else parsed
                }
                else -> {
                    val parsedConfig = WireGuardConfigParser.parse(rawConfig)
                    val name = payload?.name?.trim()?.ifEmpty { "Companion WG" } ?: "Companion WG"
                    WireGuardProfile(id = WireGuardProfile.newId(), name = name, config = parsedConfig)
                }
            }

            profile.configIssue?.let { issue ->
                throw IllegalArgumentException(issue.toDisplayMessage())
            }

            wireGuardPrefs.addOrUpdateWgProfile(profile, makeActive = true)
            val msg = "Saved WireGuard profile '${profile.name}'"
            _status.update { it.copy(lastActivity = msg) }
            jsonReply(200, ApiResponse(ok = true, message = msg))
        }.getOrElse { err ->
            jsonReply(400, ApiResponse(ok = false, message = err.message ?: "Invalid WireGuard config"))
        }
    }

    private suspend fun handleProxyPush(body: String): HttpReply {
        return runCatching {
            val payload = runCatching { json.decodeFromString<ProxyPayload>(body) }.getOrNull()
            val link = (payload?.link ?: body).trim()
            val profile = ProxyLinkParser.parse(link).getOrThrow()
            tvPrefs.addProxyProfile(profile)
            val msg = "Added proxy node '${profile.displayName}'"
            _status.update { it.copy(lastActivity = msg) }
            jsonReply(200, ApiResponse(ok = true, message = msg))
        }.getOrElse { err ->
            jsonReply(400, ApiResponse(ok = false, message = err.message ?: "Invalid proxy link"))
        }
    }

    private suspend fun handleSubscriptionPush(body: String): HttpReply {
        return runCatching {
            val payload = json.decodeFromString<SubscriptionPayload>(body)
            require(payload.url.isNotBlank()) { "Subscription URL is empty" }
            val (sub, parsed) = subscriptionManager
                .importOrUpdateSubscription(payload.url.trim(), payload.name.trim())
                .getOrThrow()
            val msg = "Imported ${parsed.totalParsed} nodes from '${sub.name}'"
            _status.update { it.copy(lastActivity = msg) }
            jsonReply(200, ApiResponse(ok = true, message = msg))
        }.getOrElse { err ->
            jsonReply(400, ApiResponse(ok = false, message = err.message ?: "Failed to import subscription"))
        }
    }

    private suspend fun handleBackupPush(body: String): HttpReply {
        val rawJson = runCatching {
            json.decodeFromString<BackupPayload>(body).json
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body

        return backupHelper.importBackupJson(rawJson).fold(
            onSuccess = { msg ->
                _status.update { it.copy(lastActivity = msg) }
                jsonReply(200, ApiResponse(ok = true, message = msg))
            },
            onFailure = { err ->
                jsonReply(400, ApiResponse(ok = false, message = err.message ?: "Invalid backup JSON"))
            },
        )
    }

    private fun jsonReply(code: Int, payload: ApiResponse): HttpReply {
        val encoded = json.encodeToString(ApiResponse.serializer(), payload)
        return HttpReply(
            code = code,
            contentType = "application/json; charset=utf-8",
            body = encoded.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private data class HttpRequest(val method: String, val path: String, val body: String)

    private data class HttpReply(
        val code: Int,
        val contentType: String,
        val body: ByteArray,
        val extraHeaders: Map<String, String> = emptyMap(),
    )

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val headerBuf = ByteArrayOutputStream()
        var prev = 0
        var curr: Int
        var lineEndCount = 0
        while (input.read().also { curr = it } != -1) {
            headerBuf.write(curr)
            if (headerBuf.size() > 64 * 1024) return null
            if (prev == '\r'.code && curr == '\n'.code) {
                lineEndCount++
                if (lineEndCount == 2) break
            } else if (curr != '\r'.code) {
                lineEndCount = 0
            }
            prev = curr
        }

        val headerText = headerBuf.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore("?")

        var contentLength = 0
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                val k = lines[i].substring(0, colon).trim().lowercase()
                val v = lines[i].substring(colon + 1).trim()
                if (k == "content-length") {
                    contentLength = (v.toIntOrNull() ?: 0).coerceIn(0, MAX_BODY_BYTES)
                }
            }
        }

        val bodyBytes = ByteArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val count = input.read(bodyBytes, readTotal, contentLength - readTotal)
            if (count <= 0) break
            readTotal += count
        }

        return HttpRequest(method, path, String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8))
    }

    private fun writeHttpResponse(
        socket: Socket,
        statusCode: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String>,
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024

        fun findLanIpAddress(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                val candidates = mutableListOf<Pair<Int, String>>()
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                    for (addr in ni.inetAddresses.toList()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            val priority = when {
                                ni.name.startsWith("eth") && addr.isSiteLocalAddress -> 0
                                ni.name.startsWith("wlan") && addr.isSiteLocalAddress -> 1
                                addr.isSiteLocalAddress -> 2
                                else -> 3
                            }
                            candidates.add(priority to ip)
                        }
                    }
                }
                candidates.minByOrNull { it.first }?.second
            } catch (e: Exception) {
                Timber.e(e, "Failed to resolve LAN IPv4 address")
                null
            }
        }
    }
}
