package app.pwhs.blockads.service

import org.junit.Assert.*
import org.junit.Test

class DnsPacketParserSafeSearchTest {

    @Test
    fun `buildDnsQueryPayload creates valid DNS query`() {
        val payload = DnsPacketParser.buildDnsQueryPayload("example.com", 1, 0x1234)

        // Check header
        assertEquals(0x12, payload[0].toInt() and 0xFF)
        assertEquals(0x34, payload[1].toInt() and 0xFF)
        // Flags: RD=1
        assertEquals(0x01, payload[2].toInt() and 0xFF)
        assertEquals(0x00, payload[3].toInt() and 0xFF)
        // QDCOUNT = 1
        assertEquals(0x00, payload[4].toInt() and 0xFF)
        assertEquals(0x01, payload[5].toInt() and 0xFF)

        // Verify it has reasonable length (12 header + domain + 4 type/class)
        assertTrue(payload.size > 12)
    }

    @Test
    fun `parseFirstARecord extracts IPv4 address from valid response`() {
        // Build a DNS response with a known IP (1.2.3.4)
        val query = DnsPacketParser.DnsQuery(
            transactionId = 0x1234,
            domain = "example.com",
            queryType = 1,
            queryClass = 1,
            rawDnsPayload = ByteArray(12),
            sourceIp = byteArrayOf(10, 0, 0, 2),
            destIp = byteArrayOf(10, 0, 0, 1),
            sourcePort = 12345,
            destPort = 53
        )

        // Use buildRedirectResponse to create a response with IP 1.2.3.4
        val testIp = byteArrayOf(1, 2, 3, 4)
        val fullPacket = DnsPacketParser.buildRedirectResponse(query, testIp)

        // Extract the DNS payload from the IP+UDP packet
        // IPv4 header = 20 bytes, UDP header = 8 bytes
        val dnsPayload = fullPacket.copyOfRange(28, fullPacket.size)

        val parsedIp = DnsPacketParser.parseFirstARecord(dnsPayload)
        assertNotNull(parsedIp)
        assertArrayEquals(testIp, parsedIp)
    }

    @Test
    fun `parseFirstARecord returns null for empty response`() {
        val result = DnsPacketParser.parseFirstARecord(ByteArray(0))
        assertNull(result)
    }

    @Test
    fun `parseFirstARecord returns null for too-short response`() {
        val result = DnsPacketParser.parseFirstARecord(ByteArray(5))
        assertNull(result)
    }

    @Test
    fun `buildRedirectResponse creates valid redirect for A query`() {
        val query = DnsPacketParser.DnsQuery(
            transactionId = 0xABCD,
            domain = "www.google.com",
            queryType = 1, // A record
            queryClass = 1,
            rawDnsPayload = ByteArray(12),
            sourceIp = byteArrayOf(10, 0, 0, 2),
            destIp = byteArrayOf(10, 0, 0, 1),
            sourcePort = 54321,
            destPort = 53
        )

        val redirectIp = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val response = DnsPacketParser.buildRedirectResponse(query, redirectIp)

        // Response should be a valid IPv4+UDP+DNS packet
        // IPv4 header: version 4, IHL 5
        assertEquals(0x45, response[0].toInt() and 0xFF)
        // Protocol: UDP (17)
        assertEquals(17, response[9].toInt() and 0xFF)
        // Response should have reasonable size
        assertTrue(response.size > 28) // IP header + UDP header + DNS response
    }

    @Test
    fun `buildRedirectResponse creates empty response for AAAA query`() {
        val query = DnsPacketParser.DnsQuery(
            transactionId = 0x5678,
            domain = "www.google.com",
            queryType = 28, // AAAA record
            queryClass = 1,
            rawDnsPayload = ByteArray(12),
            sourceIp = byteArrayOf(10, 0, 0, 2),
            destIp = byteArrayOf(10, 0, 0, 1),
            sourcePort = 54321,
            destPort = 53
        )

        val redirectIp = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val response = DnsPacketParser.buildRedirectResponse(query, redirectIp)

        // Extract DNS payload - AAAA response should have ANCOUNT = 0
        val dnsPayload = response.copyOfRange(28, response.size)
        // ANCOUNT is at bytes 6-7 of DNS payload
        assertEquals(0, dnsPayload[6].toInt() and 0xFF)
        assertEquals(0, dnsPayload[7].toInt() and 0xFF)
    }
}
