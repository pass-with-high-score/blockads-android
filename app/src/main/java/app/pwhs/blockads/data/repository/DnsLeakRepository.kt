package app.pwhs.blockads.data.repository

import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakAnalyzeRequest
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakAnalyzeResponse
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakInitResponse
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakResolverItem
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakWsEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import timber.log.Timber
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

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
    }

    override suspend fun runLeakTest(onProgress: (String) -> Unit): Result<DnsLeakAnalyzeResponse> =
        runCatching {
            // Step 1: Initialize session
            onProgress("init")
            val initJson = client.get("$BASE_URL/api/dns-leak/init").bodyAsText()
            val initResponse = json.decodeFromString<DnsLeakInitResponse>(initJson)
            val sessionId = initResponse.sessionId
            Timber.d("DNS Leak session initialized: $sessionId, ws: ${initResponse.wsUrl}")

            // Step 2: Connect WebSocket and resolve probe domains
            onProgress("probing")
            val capturedResolvers = ConcurrentHashMap<String, DnsLeakResolverItem>()

            runCatching {
                client.webSocket(initResponse.wsUrl) {
                    coroutineScope {
                        // Launch DNS probe resolutions concurrently on IO dispatcher
                        launch(Dispatchers.IO) {
                            val adapter = AdBlockVpnService.activeTunnelAdapter
                                ?: RootProxyService.activeTunnelAdapter
                            initResponse.probeDomains.forEach { domain ->
                                launch {
                                    runCatching {
                                        if (adapter != null) {
                                            adapter.lookupIP(domain)
                                        } else {
                                            InetAddress.getAllByName(domain)
                                        }
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
}
