package app.pwhs.blockadstv.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object DnsResolverUtils {

    private val ipPattern = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolveHost(host: String): String? = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return@withContext null
        if (isIpAddress(trimmed)) return@withContext trimmed

        cache[trimmed]?.let { return@withContext it }

        try {
            val addresses = InetAddress.getAllByName(trimmed)
            val ipv4 = addresses.firstOrNull { it is Inet4Address }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
            if (ipv4 != null) {
                cache[trimmed] = ipv4
                return@withContext ipv4
            }
        } catch (e: Exception) {
            Timber.d("Standard DNS resolution failed for %s: %s", trimmed, e.message)
        }

        val dohResolved = queryDoH(trimmed, "https://1.1.1.1/dns-query?name=$trimmed&type=A")
            ?: queryDoH(trimmed, "https://8.8.8.8/resolve?name=$trimmed&type=A")

        if (dohResolved != null) {
            cache[trimmed] = dohResolved
            return@withContext dohResolved
        }

        null
    }

    fun isIpAddress(host: String): Boolean =
        ipPattern.matches(host) || host.contains(":")

    private fun queryDoH(host: String, urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/dns-json")
            }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)
                val answers = json.optJSONArray("Answer") ?: return null
                for (i in 0 until answers.length()) {
                    val ans = answers.getJSONObject(i)
                    if (ans.optInt("type") == 1) {
                        val data = ans.optString("data")
                        if (isIpAddress(data)) return data
                    }
                }
            }
            null
        } catch (e: Exception) {
            Timber.d("DoH query failed for %s via %s: %s", host, urlString, e.message)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
