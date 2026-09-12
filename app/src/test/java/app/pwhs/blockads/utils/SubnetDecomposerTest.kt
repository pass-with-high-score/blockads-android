package app.pwhs.blockads.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetDecomposerTest {

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".").map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    @Test
    fun testLanBypassExcludesPrivateAndMulticastRanges() {
        val routes = SubnetDecomposer.lanBypassRoutes
        assertTrue("Routes should not be empty", routes.isNotEmpty())

        val privateIps = listOf(
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.0.1",
            "192.168.1.100",
            "169.254.10.20",
            "127.0.0.1",
            "224.0.0.251", // mDNS (Chromecast / Apple Bonjour)
            "239.255.255.250" // SSDP (DLNA / Smart TV)
        )

        for (ip in privateIps) {
            val ipLong = ipToLong(ip)
            val matchedRoute = routes.firstOrNull { it.containsIp(ipLong) }
            assertFalse(
                "IP $ip must NOT be captured by VPN routes, but was matched by $matchedRoute",
                routes.any { it.containsIp(ipLong) }
            )
        }
    }

    @Test
    fun testLanBypassIncludesPublicInternet() {
        val routes = SubnetDecomposer.lanBypassRoutes

        val publicIps = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "9.9.9.9",
            "142.250.190.46", // Google
            "157.240.241.35", // Facebook
            "104.16.132.229", // Cloudflare
            "185.199.108.153" // GitHub
        )

        for (ip in publicIps) {
            val ipLong = ipToLong(ip)
            val matchingRoutes = routes.filter { it.containsIp(ipLong) }
            assertEquals(
                "Public IP $ip must match exactly ONE route in lanBypassRoutes",
                1,
                matchingRoutes.size
            )
        }
    }

    @Test
    fun testRoutesAreMutuallyDisjoint() {
        val routes = SubnetDecomposer.lanBypassRoutes
        for (i in routes.indices) {
            for (j in i + 1 until routes.size) {
                val a = routes[i]
                val b = routes[j]
                val overlaps = (a.start <= b.start && b.start <= a.end) ||
                        (b.start <= a.start && a.start <= b.end)
                assertFalse("Routes $a and $b must not overlap", overlaps)
            }
        }
    }
}
