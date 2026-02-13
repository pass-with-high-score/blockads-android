package app.pwhs.blockads.dns

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import java.util.Base64

/**
 * DNS-over-HTTPS (DoH) client implementation following RFC 8484
 * Supports both GET and POST methods for DNS queries
 * 
 * WARNING: Potential VPN routing loop
 * The HttpClient used for DoH queries may create sockets that need VPN protection.
 * Without VpnService.protect() on underlying sockets, DoH queries may route through
 * the VPN tunnel itself, causing routing loops. This needs testing and may require:
 * 1. Custom Ktor engine with socket protection
 * 2. Binding to specific network interface
 * 3. Using OkHttp engine with custom socket factory
 */
class DohClient(private val httpClient: HttpClient) {

    companion object {
        private const val TAG = "DohClient"
        private const val DNS_MESSAGE_CONTENT_TYPE = "application/dns-message"
        private const val QUERY_TIMEOUT_MS = 5000L
    }

    /**
     * Perform a DNS query over HTTPS using GET method
     * @param dohUrl The DoH server URL (e.g., https://dns.google/dns-query)
     * @param dnsPayload The raw DNS query packet
     * @return The DNS response packet or null if failed
     */
    suspend fun queryGet(dohUrl: String, dnsPayload: ByteArray): ByteArray? {
        return try {
            withTimeout(QUERY_TIMEOUT_MS) {
                // Encode DNS payload to base64url (RFC 4648 Section 5)
                val base64Dns = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(dnsPayload)

                Log.d(TAG, "DoH GET query to $dohUrl")
                val response = httpClient.get("$dohUrl?dns=$base64Dns") {
                    header("Accept", DNS_MESSAGE_CONTENT_TYPE)
                }

                val responseBytes = response.readBytes()
                Log.d(TAG, "DoH GET response received: ${responseBytes.size} bytes")
                responseBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH GET query failed", e)
            null
        }
    }

    /**
     * Perform a DNS query over HTTPS using POST method
     * @param dohUrl The DoH server URL (e.g., https://dns.google/dns-query)
     * @param dnsPayload The raw DNS query packet
     * @return The DNS response packet or null if failed
     */
    suspend fun queryPost(dohUrl: String, dnsPayload: ByteArray): ByteArray? {
        return try {
            withTimeout(QUERY_TIMEOUT_MS) {
                Log.d(TAG, "DoH POST query to $dohUrl")
                val response = httpClient.post(dohUrl) {
                    contentType(ContentType.parse(DNS_MESSAGE_CONTENT_TYPE))
                    header("Accept", DNS_MESSAGE_CONTENT_TYPE)
                    setBody(dnsPayload)
                }

                val responseBytes = response.readBytes()
                Log.d(TAG, "DoH POST response received: ${responseBytes.size} bytes")
                responseBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH POST query failed", e)
            null
        }
    }

    /**
     * Perform a DNS query over HTTPS (tries POST first, falls back to GET)
     * @param dohUrl The DoH server URL
     * @param dnsPayload The raw DNS query packet
     * @return The DNS response packet or null if failed
     */
    suspend fun query(dohUrl: String, dnsPayload: ByteArray): ByteArray? {
        // Try POST method first (more standard for larger queries)
        return queryPost(dohUrl, dnsPayload) ?: queryGet(dohUrl, dnsPayload)
    }
}
