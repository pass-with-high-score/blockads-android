package app.pwhs.blockadstv.utils

import app.pwhs.blockadstv.data.geoip.GeoIpLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

object WireGuardGeoIpHelper {

    private val cache = ConcurrentHashMap<String, String>()

    private val ISO_COUNTRIES = setOf(
        "VN", "US", "SG", "JP", "GB", "DE", "FR", "NL", "AU", "CA", "KR", "TH",
        "ID", "MY", "PH", "TW", "CN", "IN", "BR", "RU", "CH", "IT", "ES", "PL",
        "SE", "NO", "FI", "DK", "BE", "AT", "IE", "NZ", "HK", "TR", "UA", "ZA",
        "MX", "AE", "SA", "IL", "AR", "CL", "CO", "RO", "BG", "CZ", "HU", "GR",
    )

    private val NAME_KEYWORD_TO_CC = mapOf(
        "hong kong" to "HK", "hongkong" to "HK",
        "vietnam" to "VN", "viet nam" to "VN",
        "singapore" to "SG",
        "japan" to "JP", "tokyo" to "JP",
        "united states" to "US", "usa" to "US",
        "united kingdom" to "GB", "london" to "GB",
        "germany" to "DE", "frankfurt" to "DE",
        "france" to "FR", "paris" to "FR",
        "netherlands" to "NL", "amsterdam" to "NL",
        "australia" to "AU", "sydney" to "AU",
        "canada" to "CA", "toronto" to "CA",
        "korea" to "KR", "seoul" to "KR",
        "taiwan" to "TW", "taipei" to "TW",
    )

    fun getCountryCodeFast(endpoint: String, profileName: String): String? {
        val cacheKey = "$endpoint|$profileName"
        cache[cacheKey]?.let { return it }

        val host = endpoint.substringBefore(':').trim()

        if (GeoIpLookup.parseIpv4(host) != null) {
            val cc = GeoIpLookup.getCountryCode(host)
            if (cc.isNotEmpty()) {
                cache[cacheKey] = cc
                return cc
            }
        }

        val hostPrefix = host.substringBefore('-').substringBefore('.').uppercase()
        if (hostPrefix.length == 2) {
            val cc = if (hostPrefix == "UK") "GB" else hostPrefix
            if (ISO_COUNTRIES.contains(cc)) {
                cache[cacheKey] = cc
                return cc
            }
        }

        val namePrefix = profileName.substringBefore('-').substringBefore('_').trim().uppercase()
        if (namePrefix.length == 2) {
            val cc = if (namePrefix == "UK") "GB" else namePrefix
            if (ISO_COUNTRIES.contains(cc)) {
                cache[cacheKey] = cc
                return cc
            }
        }

        val lowerName = profileName.lowercase()
        val lowerHost = host.lowercase()
        for ((keyword, cc) in NAME_KEYWORD_TO_CC) {
            if (lowerName.contains(keyword) || lowerHost.contains(keyword)) {
                cache[cacheKey] = cc
                return cc
            }
        }

        return null
    }

    suspend fun resolveCountryCode(endpoint: String, profileName: String): String? {
        val fast = getCountryCodeFast(endpoint, profileName)
        if (fast != null) return fast

        val host = endpoint.substringBefore(':').trim()
        if (host.isEmpty() || host.startsWith("—")) return null

        val cacheKey = "$endpoint|$profileName"

        return withContext(Dispatchers.IO) {
            try {
                val ip = InetAddress.getByName(host).hostAddress
                if (ip != null) {
                    val cc = GeoIpLookup.getCountryCode(ip)
                    if (cc.isNotEmpty()) {
                        cache[cacheKey] = cc
                        return@withContext cc
                    }
                }
            } catch (_: Exception) {
            }
            null
        }
    }
}
