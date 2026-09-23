package app.pwhs.blockads.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class WireGuardValidatorsTest {

    private val validKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="

    private fun check(expected: Map<String, String?>, validate: (String) -> String?) {
        for ((input, error) in expected) {
            assertEquals("input '$input'", error, validate(input))
        }
    }

    @Test
    fun `key accepts 44-char base64 and names the field in errors`() {
        assertEquals(null, WireGuardValidators.key(validKey, "Private key"))
        assertEquals("Private key is required", WireGuardValidators.key("", "Private key"))
        assertEquals(null, WireGuardValidators.key("", "Preshared key", optional = true))
        val bad = "Public key must be a 44-char base64 key"
        check(
            mapOf(
                validKey.dropLast(1) to bad,
                validKey.dropLast(2) + "==" to bad,
                validKey + "A" to bad,
                validKey.replace('+', '-') to bad,
                " $validKey" to bad,
            )
        ) { WireGuardValidators.key(it, "Public key") }
    }

    @Test
    fun `cidr table`() {
        check(
            mapOf(
                "10.0.0.2/32" to null,
                " 10.0.0.0/8 " to null,
                "0.0.0.0/0" to null,
                "::/0" to null,
                "fd00::2/128" to null,
                "2001:db8::/32" to null,
                "" to "Empty",
                "   " to "Empty",
                "10.0.0.1" to "Missing /prefix",
                "10.0.0.0/x" to "Invalid prefix",
                "10.0.0.0/" to "Invalid prefix",
                "10.0.0.0/33" to "Prefix out of range",
                "10.0.0.0/-1" to "Prefix out of range",
                "fd00::/129" to "Prefix out of range",
                "10.0.0/24" to "Invalid IP",
                "256.0.0.0/8" to "Invalid IP",
                "a.b.c.d/8" to "Invalid IP",
                "example.com/32" to "Invalid IP",
                "1:2:3:4:5:6:7:8:9/64" to "Invalid IP",
            ),
            WireGuardValidators::cidr,
        )
    }

    @Test
    fun `ip table`() {
        check(
            mapOf(
                "1.1.1.1" to null,
                " 9.9.9.9 " to null,
                "::1" to null,
                "2606:4700:4700::1111" to null,
                "" to "Empty",
                "1.1.1" to "Invalid IP",
                "1.1.1.1.1" to "Invalid IP",
                "1.1.1.300" to "Invalid IP",
                "1.1.1.1/32" to "Invalid IP",
                "dns.google" to "Invalid IP",
                "12345::zz" to "Invalid IP",
            ),
            WireGuardValidators::ip,
        )
    }

    @Test
    fun `endpoint table`() {
        check(
            mapOf(
                "vpn.example.com:51820" to null,
                "1.2.3.4:1" to null,
                "1.2.3.4:65535" to null,
                "[2001:db8::1]:51820" to null,
                " host:443 " to null,
                "" to "Endpoint is required",
                "host" to "Missing :port",
                ":51820" to "Empty host",
                "[]:51820" to "Empty host",
                "host:0" to "Invalid port",
                "host:65536" to "Invalid port",
                "host:port" to "Invalid port",
                "host:" to "Invalid port",
                "[2001:db8::1" to "Missing ']' for IPv6 literal",
                "[2001:db8::1]51820" to "Missing port after ']'",
                "[2001:db8::1]" to "Missing port after ']'",
            ),
        ) { WireGuardValidators.endpoint(it) }
        assertEquals(null, WireGuardValidators.endpoint("  ", optional = true))
    }

    @Test
    fun `port table`() {
        check(
            mapOf(
                "" to null,
                "1" to null,
                "65535" to null,
                "0" to "Port out of range",
                "65536" to "Port out of range",
                "-5" to "Port out of range",
                "abc" to "Invalid port",
            ),
        ) { WireGuardValidators.port(it) }
        assertEquals("Port is required", WireGuardValidators.port(" ", optional = false))
    }

    @Test
    fun `keepalive table`() {
        check(
            mapOf(
                "" to null,
                "0" to null,
                "25" to null,
                "65535" to null,
                "65536" to "Out of range (0-65535)",
                "-1" to "Out of range (0-65535)",
                "25s" to "Invalid number",
            ),
            WireGuardValidators::keepalive,
        )
    }
}
