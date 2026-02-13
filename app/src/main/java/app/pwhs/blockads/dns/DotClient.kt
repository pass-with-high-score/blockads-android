package app.pwhs.blockads.dns

import android.util.Log
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * DNS-over-TLS (DoT) client implementation following RFC 7858
 * Establishes TLS connections on port 853 for encrypted DNS queries
 */
class DotClient {

    companion object {
        private const val TAG = "DotClient"
        private const val DOT_PORT = 853
        private const val QUERY_TIMEOUT_MS = 5000L
        private const val CONNECTION_TIMEOUT_MS = 3000
        // Maximum DNS response size for DNS-over-TLS. While UDP DNS is limited to 512 bytes
        // (RFC 1035), TCP/TLS-based DNS can handle larger responses. 4096 bytes is a common
        // practical limit that accommodates most DNS responses while preventing excessive memory use.
        private const val MAX_DNS_RESPONSE_LENGTH = 4096
    }

    /**
     * Perform a DNS query over TLS
     * @param dotServer The DoT server hostname or IP (e.g., dns.google)
     * @param dnsPayload The raw DNS query packet
     * @return The DNS response packet or null if failed
     */
    suspend fun query(dotServer: String, dnsPayload: ByteArray): ByteArray? {
        return try {
            withTimeout(QUERY_TIMEOUT_MS) {
                performDotQuery(dotServer, dnsPayload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoT query failed", e)
            null
        }
    }

    private fun performDotQuery(dotServer: String, dnsPayload: ByteArray): ByteArray? {
        var sslSocket: SSLSocket? = null
        try {
            // Resolve server address
            val serverAddress = InetAddress.getByName(dotServer)
            Log.d(TAG, "Connecting to DoT server: $dotServer:$DOT_PORT")

            // Create SSL context and socket
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val socketFactory = sslContext.socketFactory

            sslSocket = socketFactory.createSocket() as SSLSocket
            sslSocket.connect(InetSocketAddress(serverAddress, DOT_PORT), CONNECTION_TIMEOUT_MS)
            sslSocket.soTimeout = QUERY_TIMEOUT_MS.toInt()

            // Enable SNI (Server Name Indication) for proper TLS handshake when using hostnames
            // SNIHostName does not accept IP address literals, so skip SNI if an IP was provided
            val hostAddress = serverAddress.hostAddress
            val isIpLiteral = dotServer == hostAddress || dotServer == "[$hostAddress]"
            val sslParams = sslSocket.sslParameters
            if (!isIpLiteral) {
                sslParams.serverNames = listOf(javax.net.ssl.SNIHostName(dotServer))
            }
            sslSocket.sslParameters = sslParams

            // Start TLS handshake
            sslSocket.startHandshake()
            Log.d(TAG, "TLS handshake completed")

            // Send DNS query with length prefix (RFC 7858 Section 3.3)
            val outputStream = sslSocket.outputStream
            val lengthPrefix = byteArrayOf(
                (dnsPayload.size shr 8).toByte(),  // High byte
                (dnsPayload.size and 0xFF).toByte() // Low byte
            )
            outputStream.write(lengthPrefix)
            outputStream.write(dnsPayload)
            outputStream.flush()
            Log.d(TAG, "DoT query sent: ${dnsPayload.size} bytes")

            // Read DNS response with length prefix
            val inputStream = sslSocket.inputStream
            val lengthBytes = ByteArray(2)
            var bytesRead = 0
            while (bytesRead < 2) {
                val read = inputStream.read(lengthBytes, bytesRead, 2 - bytesRead)
                if (read == -1) throw IOException("Connection closed while reading length")
                bytesRead += read
            }

            val responseLength = ((lengthBytes[0].toInt() and 0xFF) shl 8) or
                    (lengthBytes[1].toInt() and 0xFF)

            if (responseLength <= 0 || responseLength > MAX_DNS_RESPONSE_LENGTH) {
                Log.e(TAG, "Invalid response length: $responseLength")
                return null
            }

            // Read response data
            val responseBuffer = ByteArray(responseLength)
            bytesRead = 0
            while (bytesRead < responseLength) {
                val read = inputStream.read(responseBuffer, bytesRead, responseLength - bytesRead)
                if (read == -1) throw IOException("Connection closed while reading response")
                bytesRead += read
            }

            Log.d(TAG, "DoT response received: $responseLength bytes")
            return responseBuffer

        } catch (e: Exception) {
            Log.e(TAG, "DoT query error", e)
            return null
        } finally {
            try {
                sslSocket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing SSL socket", e)
            }
        }
    }
}
