package app.pwhs.blockads.data.repository

import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakAnalyzeRequest
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakAnalyzeResponse
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakInitResponse
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakResolverItem
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakWsEvent
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

interface DnsLeakRepository {
    suspend fun runLeakTest(onProgress: (String) -> Unit): Result<DnsLeakAnalyzeResponse>
}

class DnsLeakRepositoryImpl(
    private val client: HttpClient,
    private val appPrefs: AppPreferences,
) : DnsLeakRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    companion object {
        private const val BASE_URL = "https://edge-dns.pwhs.app"
        private val DNS_MESSAGE_CONTENT_TYPE = ContentType("application", "dns-message")
    }

    override suspend fun runLeakTest(onProgress: (String) -> Unit): Result<DnsLeakAnalyzeResponse> =
        runCatching {
            // Step 1: Initialize session
            onProgress("init")
            val initJson = client.get("$BASE_URL/api/dns-leak/init").bodyAsText()
            val initResponse = json.decodeFromString<DnsLeakInitResponse>(initJson)
            val sessionId = initResponse.sessionId
            Timber.d("DNS Leak session initialized: $sessionId, ws: ${initResponse.wsUrl}")

            // Snapshot active tunnel & DNS settings so probe queries test the configured resolver
            // instead of bypassing the VPN (this app's package is excluded from its own TUN).
            val isTunnelActive = AdBlockVpnService.isRunning || RootProxyService.isRunning
            val dnsProtocol = appPrefs.dnsProtocol.first()
            val dohUrl = appPrefs.dohUrl.first().trim()
            val upstreamDns = appPrefs.upstreamDns.first().trim()

            // Step 2: Connect WebSocket and resolve probe domains
            onProgress("probing")
            val capturedResolvers = ConcurrentHashMap<String, DnsLeakResolverItem>()

            runCatching {
                client.webSocket(initResponse.wsUrl) {
                    coroutineScope {
                        // Launch DNS probe resolutions concurrently on IO dispatcher
                        launch(Dispatchers.IO) {
                            initResponse.probeDomains.forEach { domain ->
                                launch {
                                    runCatching {
                                        resolveProbeDomain(
                                            domain = domain,
                                            isTunnelActive = isTunnelActive,
                                            dnsProtocol = dnsProtocol,
                                            dohUrl = dohUrl,
                                            upstreamDns = upstreamDns
                                        )
                                    }.onFailure {
                                        Timber.v("Probe resolution failed for $domain: ${it.message}")
                                    }
                                }
                            }
                        }

                        // Collect incoming resolver messages from WebSocket
                        withTimeoutOrNull(4000L) {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    val event = runCatching {
                                        json.decodeFromString<DnsLeakWsEvent>(text)
                                    }.getOrNull()

                                    event?.remoteIp?.let { ip ->
                                        capturedResolvers[ip] = DnsLeakResolverItem(
                                            ip = ip,
                                            clientSubnet = event.clientSubnet
                                        )
                                        Timber.d("Captured resolver via WS: $ip (ecs: ${event.clientSubnet})")
                                    }
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                Timber.w(it, "WebSocket connection failed or completed")
            }

            // Step 3: Analyze captured resolvers
            onProgress("analyzing")
            val resolverItems = capturedResolvers.values.toList()

            val analyzeRequest = DnsLeakAnalyzeRequest(
                clientAsn = initResponse.client?.asn,
                resolvers = resolverItems
            )

            val analyzeResponseText = client.post("$BASE_URL/api/dns-leak/analyze") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DnsLeakAnalyzeRequest.serializer(), analyzeRequest))
            }.bodyAsText()

            json.decodeFromString<DnsLeakAnalyzeResponse>(analyzeResponseText)
        }.onFailure {
            Timber.e(it, "DNS leak test execution failed")
        }

    private suspend fun resolveProbeDomain(
        domain: String,
        isTunnelActive: Boolean,
        dnsProtocol: DnsProtocol,
        dohUrl: String,
        upstreamDns: String,
    ) {
        if (!isTunnelActive) {
            InetAddress.getAllByName(domain)
            return
        }

        when (dnsProtocol) {
            DnsProtocol.DOH, DnsProtocol.DOH3, DnsProtocol.ODOH -> {
                if (dohUrl.isNotBlank()) {
                    queryViaDoh(dohUrl, domain)
                } else {
                    InetAddress.getAllByName(domain)
                }
            }
            DnsProtocol.DOT -> {
                if (upstreamDns.isNotBlank()) {
                    queryViaDot(upstreamDns, domain)
                } else {
                    InetAddress.getAllByName(domain)
                }
            }
            DnsProtocol.DOQ -> {
                when {
                    dohUrl.isNotBlank() -> queryViaDoh(dohUrl, domain)
                    upstreamDns.isNotBlank() -> queryViaDot(upstreamDns, domain)
                    else -> InetAddress.getAllByName(domain)
                }
            }
            DnsProtocol.PLAIN -> {
                val isSystemDefault = upstreamDns.isBlank() ||
                    upstreamDns == "0.0.0.0" ||
                    upstreamDns.equals("system", ignoreCase = true)
                if (!isSystemDefault) {
                    queryViaUdp(upstreamDns, domain)
                } else {
                    InetAddress.getAllByName(domain)
                }
            }
        }
    }

    private suspend fun queryViaDoh(url: String, domain: String) {
        val packet = buildDnsQueryPacket(domain)
        client.post(url) {
            header(HttpHeaders.Accept, DNS_MESSAGE_CONTENT_TYPE.toString())
            setBody(ByteArrayContent(packet, DNS_MESSAGE_CONTENT_TYPE))
        }
    }

    private fun queryViaUdp(server: String, domain: String) {
        val (host, port) = parseHostAndPort(server, defaultPort = 53)
        val packetBytes = buildDnsQueryPacket(domain)
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val address = InetAddress.getByName(host)
            val request = DatagramPacket(packetBytes, packetBytes.size, address, port)
            socket.send(request)
            val buffer = ByteArray(1500)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
        }
    }

    private fun queryViaDot(server: String, domain: String) {
        val (host, port) = parseHostAndPort(
            server.removePrefix("tls://").removePrefix("quic://"),
            defaultPort = 853
        )
        val packetBytes = buildDnsQueryPacket(domain)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = 3500
            socket.connect(InetSocketAddress(host, port), 3500)
            socket.startHandshake()
            val out = DataOutputStream(socket.outputStream)
            out.writeShort(packetBytes.size)
            out.write(packetBytes)
            out.flush()
            val input = DataInputStream(socket.inputStream)
            val responseLen = input.readUnsignedShort()
            if (responseLen > 0) {
                val buf = ByteArray(responseLen.coerceAtMost(4096))
                input.readFully(buf)
            }
        }
    }

    private fun parseHostAndPort(raw: String, defaultPort: Int): Pair<String, Int> {
        val cleaned = raw.trim().substringBefore('/')
        if (cleaned.startsWith("[")) {
            val endBracket = cleaned.indexOf(']')
            if (endBracket != -1) {
                val host = cleaned.substring(1, endBracket)
                val port = cleaned.substring(endBracket + 1)
                    .removePrefix(":")
                    .toIntOrNull() ?: defaultPort
                return host to port
            }
        }
        val colonCount = cleaned.count { it == ':' }
        return if (colonCount == 1) {
            val host = cleaned.substringBefore(':')
            val port = cleaned.substringAfter(':').toIntOrNull() ?: defaultPort
            host to port
        } else {
            cleaned to defaultPort
        }
    }

    private fun buildDnsQueryPacket(domain: String, qType: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(64)
        val txId = Random.nextInt(0, 0xFFFF)
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        // Standard recursive query (RD=1)
        out.write(0x01)
        out.write(0x00)
        // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        for (label in domain.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > 63) continue
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        // QTYPE (A = 1) and QCLASS (IN = 1)
        out.write((qType shr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }
}
