package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.WireGuardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class WireGuardConfigParserTest {

    private val privKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val pubKey = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
    private val pubKey2 = "TrMvSoP4jYQlY6RIzBgbssQqY3vxI2Pi+y71lOWWXX0="

    @Test
    fun `parses a full config with two peers`() {
        val cfg = WireGuardConfigParser.parse(
            """
            # exported by a VPN provider
            [Interface]
            PrivateKey = $privKey
            Address = 10.0.0.2/32, fd00::2/128
            ListenPort = 51820
            DNS = 1.1.1.1,1.0.0.1

            [Peer]
            PublicKey = $pubKey
            PresharedKey = $pubKey2
            Endpoint = vpn.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25

            [Peer]
            PublicKey = $pubKey2
            AllowedIPs = 192.168.10.0/24
            """.trimIndent()
        )

        with(cfg.interfaceConfig) {
            assertEquals(privKey, privateKey)
            assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), address)
            assertEquals(51820, listenPort)
            assertEquals(listOf("1.1.1.1", "1.0.0.1"), dns)
        }
        assertEquals(2, cfg.peers.size)
        with(cfg.peers[0]) {
            assertEquals(pubKey, publicKey)
            assertEquals(pubKey2, presharedKey)
            assertEquals("vpn.example.com:51820", endpoint)
            assertEquals(listOf("0.0.0.0/0", "::/0"), allowedIPs)
            assertEquals(25, persistentKeepalive)
        }
        with(cfg.peers[1]) {
            assertEquals(pubKey2, publicKey)
            assertNull(presharedKey)
            assertNull(endpoint)
            assertNull(persistentKeepalive)
            assertEquals(listOf("192.168.10.0/24"), allowedIPs)
        }
    }

    @Test
    fun `section names and keys are case-insensitive and whitespace-tolerant`() {
        val cfg = WireGuardConfigParser.parse(
            """
            [INTERFACE]
               privatekey=$privKey
            ADDRESS   =   10.0.0.2/32
            [peer]
            publickey =$pubKey
            allowedips= 0.0.0.0/0
            """.trimIndent()
        )
        assertEquals(privKey, cfg.interfaceConfig.privateKey)
        assertEquals(listOf("10.0.0.2/32"), cfg.interfaceConfig.address)
        assertEquals(pubKey, cfg.peers.single().publicKey)
    }

    @Test
    fun `base64 padding in values survives splitting on the first equals sign`() {
        val cfg = WireGuardConfigParser.parse("[Interface]\nPrivateKey = abc==\nAddress = 10.0.0.2/32")
        assertEquals("abc==", cfg.interfaceConfig.privateKey)
    }

    @Test
    fun `repeated Address, DNS and AllowedIPs lines accumulate`() {
        val cfg = WireGuardConfigParser.parse(
            """
            [Interface]
            PrivateKey = $privKey
            Address = 10.0.0.2/32
            Address = fd00::2/128
            DNS = 1.1.1.1
            DNS = , 9.9.9.9 ,
            [Peer]
            PublicKey = $pubKey
            AllowedIPs = 10.0.0.0/8
            AllowedIPs = 172.16.0.0/12
            """.trimIndent()
        )
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), cfg.interfaceConfig.address)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), cfg.interfaceConfig.dns)
        assertEquals(listOf("10.0.0.0/8", "172.16.0.0/12"), cfg.peers.single().allowedIPs)
    }

    @Test
    fun `non-numeric ports and keepalive become null`() {
        val cfg = WireGuardConfigParser.parse(
            "[Interface]\nPrivateKey = k\nAddress = 10.0.0.2/32\nListenPort = auto\n" +
                "[Peer]\nPublicKey = p\nPersistentKeepalive = off"
        )
        assertNull(cfg.interfaceConfig.listenPort)
        assertNull(cfg.peers.single().persistentKeepalive)
    }

    @Test
    fun `unknown sections, unknown keys and stray lines are ignored`() {
        val cfg = WireGuardConfigParser.parse(
            """
            PrivateKey = outside-any-section
            [Interface]
            PrivateKey = $privKey
            Address = 10.0.0.2/32
            MTU = 1280
            PostUp = iptables -A FORWARD
            just some junk without equals
            [Extra]
            PublicKey = ignored
            [Peer]
            PublicKey = $pubKey
            """.trimIndent()
        )
        assertEquals(privKey, cfg.interfaceConfig.privateKey)
        assertEquals(pubKey, cfg.peers.single().publicKey)
    }

    @Test
    fun `interface without peers is accepted`() {
        val cfg = WireGuardConfigParser.parse("[Interface]\nPrivateKey = k\nAddress = 10.0.0.2/32")
        assertTrue(cfg.peers.isEmpty())
        assertTrue(cfg.interfaceConfig.dns.isEmpty())
    }

    @Test
    fun `later duplicate scalar keys win`() {
        val cfg = WireGuardConfigParser.parse(
            "[Interface]\nPrivateKey = first\nPrivateKey = second\nAddress = 10.0.0.2/32"
        )
        assertEquals("second", cfg.interfaceConfig.privateKey)
    }

    @Test
    fun `missing PrivateKey is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfigParser.parse("[Interface]\nAddress = 10.0.0.2/32")
        }
        assertTrue(e.message!!.contains("PrivateKey"))
    }

    @Test
    fun `missing Address is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfigParser.parse("[Interface]\nPrivateKey = k\nAddress = ,")
        }
        assertTrue(e.message!!.contains("Address"))
    }

    @Test
    fun `peer without PublicKey is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfigParser.parse("[Interface]\nPrivateKey = k\nAddress = 10.0.0.2/32\n[Peer]\nEndpoint = h:1")
        }
        assertTrue(e.message!!.contains("PublicKey"))
    }

    @Test
    fun `empty input is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WireGuardConfigParser.parse("") }
    }

    @Test
    fun `CRLF line endings parse`() {
        val cfg = WireGuardConfigParser.parse("[Interface]\r\nPrivateKey = k\r\nAddress = 10.0.0.2/32\r\n")
        assertEquals("k", cfg.interfaceConfig.privateKey)
    }

    @Ignore("known bug: inline # comments are kept in values and break section headers; wg(8) strips them")
    @Test
    fun `inline comments are stripped like wg-quick does`() {
        val cfg = WireGuardConfigParser.parse(
            """
            [Interface] # home tunnel
            PrivateKey = $privKey
            Address = 10.0.0.2/32 # v4 only
            [Peer]
            PublicKey = $pubKey
            Endpoint = vpn.example.com:51820 # primary
            """.trimIndent()
        )
        assertEquals(listOf("10.0.0.2/32"), cfg.interfaceConfig.address)
        assertEquals("vpn.example.com:51820", cfg.peers.single().endpoint)
    }

    @Ignore("known bug: a leading UTF-8 BOM hides the [Interface] header, so the import fails with missing PrivateKey")
    @Test
    fun `leading byte order mark is tolerated`() {
        val cfg = WireGuardConfigParser.parse("﻿[Interface]\nPrivateKey = k\nAddress = 10.0.0.2/32")
        assertEquals("k", cfg.interfaceConfig.privateKey)
    }

    @Test
    fun `parsed config survives a JSON round trip`() {
        val cfg = WireGuardConfigParser.parse(
            "[Interface]\nPrivateKey = $privKey\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\n" +
                "[Peer]\nPublicKey = $pubKey\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25"
        )
        val json = cfg.toJson()
        assertTrue(json.contains("\"allowedIPs\""))
        assertEquals(cfg, WireGuardConfig.fromJson(json))
    }

    @Test
    fun `fromJson ignores unknown keys and fills defaults`() {
        val cfg = WireGuardConfig.fromJson(
            """{"interfaceConfig":{"privateKey":"k","address":["10.0.0.2/32"],"mtu":1280},
               "peers":[{"publicKey":"p"}],"future":true}"""
        )
        assertEquals(emptyList<String>(), cfg.interfaceConfig.dns)
        assertNull(cfg.interfaceConfig.listenPort)
        assertEquals(emptyList<String>(), cfg.peers.single().allowedIPs)
    }
}
