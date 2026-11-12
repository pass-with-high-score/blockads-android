package app.pwhs.blockadstv.data.geoip

import android.content.Context
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GeoIpLookup {

    private const val FILE_NAME = "geoip_ipv4.bin"
    private const val ASSET_FILE = "preset/geoip_ipv4.bin"
    private const val RECORD_SIZE = 10

    @Volatile
    private var buffer: ByteBuffer? = null

    @Volatile
    private var recordCount: Int = 0

    @Volatile
    private var isInitialized = false

    private fun getStorageFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            loadDatabase(context)
        }
    }

    fun reload(context: Context) {
        synchronized(this) {
            loadDatabase(context)
        }
    }

    private fun loadDatabase(context: Context) {
        val file = getStorageFile(context)
        if (file.exists() && file.length() > 0) {
            try {
                val bytes = file.readBytes()
                buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                recordCount = bytes.size / RECORD_SIZE
                isInitialized = true
                Timber.d("GeoIpLookup loaded from local storage with $recordCount records")
                return
            } catch (e: Exception) {
                Timber.w(e, "Failed to load GeoIP database from local storage")
            }
        }

        try {
            context.assets.open(ASSET_FILE).use { input ->
                val bytes = input.readBytes()
                buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                recordCount = bytes.size / RECORD_SIZE
                isInitialized = true
                Timber.d("GeoIpLookup loaded from asset with $recordCount records")
            }
        } catch (_: Exception) {
            Timber.d("No bundled GeoIP asset found (using ccTLD fallback until downloaded)")
        }
    }

    private val CC_TLD_MAP = mapOf(
        "vn" to "VN", "us" to "US", "uk" to "GB", "sg" to "SG", "jp" to "JP",
        "de" to "DE", "fr" to "FR", "au" to "AU", "ca" to "CA", "kr" to "KR",
        "th" to "TH", "id" to "ID", "my" to "MY", "ph" to "PH", "tw" to "TW",
        "cn" to "CN", "in" to "IN", "br" to "BR", "ru" to "RU", "nl" to "NL",
        "se" to "SE", "ch" to "CH", "it" to "IT", "es" to "ES", "pl" to "PL",
        "mx" to "MX", "za" to "ZA", "nz" to "NZ", "hk" to "HK", "ie" to "IE",
        "no" to "NO", "fi" to "FI", "dk" to "DK", "be" to "BE", "at" to "AT",
        "cz" to "CZ", "ro" to "RO", "hu" to "HU", "pt" to "PT", "gr" to "GR",
        "il" to "IL", "ae" to "AE", "sa" to "SA", "tr" to "TR", "ua" to "UA",
    )

    fun getCountryCode(ipString: String, domain: String = ""): String {
        val trimmedIp = ipString.trim()
        if (trimmedIp.isNotEmpty() && !isPrivateIp(trimmedIp)) {
            val ipNum = parseIpv4(trimmedIp)
            if (ipNum != null) {
                val cc = lookupIpv4(ipNum)
                if (!cc.isNullOrEmpty()) return cc
            }
        }
        return getCountryCodeFromDomain(domain)
    }

    fun getCountryCodeFromDomain(domain: String): String {
        if (domain.isBlank()) return ""
        val parts = domain.trim().lowercase().split(".")
        if (parts.size >= 2) {
            CC_TLD_MAP[parts.last()]?.let { return it }
        }
        return ""
    }

    private fun lookupIpv4(ipNum: Long): String? {
        val buf = buffer ?: return null
        var low = 0
        var high = recordCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val offset = mid * RECORD_SIZE

            val startIp = buf.getInt(offset).toLong() and 0xFFFFFFFFL
            val endIp = buf.getInt(offset + 4).toLong() and 0xFFFFFFFFL

            if (ipNum < startIp) {
                high = mid - 1
            } else if (ipNum > endIp) {
                low = mid + 1
            } else {
                val c1 = buf.get(offset + 8).toInt().toChar()
                val c2 = buf.get(offset + 9).toInt().toChar()
                return "$c1$c2"
            }
        }
        return null
    }

    fun parseIpv4(ip: String): Long? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return try {
            var result = 0L
            for (p in parts) {
                val octet = p.toInt()
                if (octet !in 0..255) return null
                result = (result shl 8) or (octet.toLong() and 0xFFL)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun isPrivateIp(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "0.0.0.0" || ip == "::1" ||
            ip.startsWith("fe80:") || ip.startsWith("fc00:")
        ) {
            return true
        }
        val ipNum = parseIpv4(ip) ?: return false
        if (ipNum in 167772160L..184549375L) return true
        if (ipNum in 2886729728L..2887778303L) return true
        if (ipNum in 3232235520L..3232301055L) return true
        return false
    }

    fun countryCodeToEmoji(countryCode: String): String {
        if (countryCode.length != 2) return "🌐"
        val upper = countryCode.uppercase()
        val first = upper[0].code - 'A'.code + 0x1F1E6
        val second = upper[1].code - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
