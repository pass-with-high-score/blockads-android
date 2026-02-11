package app.pwhs.blockads.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DnsPacketParserTest {

    /**
     * Build a minimal IPv4/UDP/DNS query packet for testing.
     */
    private fun buildTestIpv4DnsPacket(
        sourceIp: ByteArray = byteArrayOf(10, 0, 0, 2),
        destIp: ByteArray = byteArrayOf(10, 0, 0, 1),
        sourcePort: Int = 12345,
        destPort: Int = 53,
        domain: String = "example.com",
        queryType: Int = 1, // A
        protocol: Int = 17  // UDP
    ): ByteArray {
        val dnsPayload = buildDnsQueryPayload(domain, queryType)
        val udpLength = 8 + dnsPayload.size
        val totalLength = 20 + udpLength

        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet)

        // IPv4 header (20 bytes)
        buf.put(0x45.toByte()) // version=4, IHL=5
        buf.put(0x00.toByte()) // DSCP/ECN
        buf.putShort(totalLength.toShort()) // total length
        buf.putShort(0x0000.toShort()) // identification
        buf.putShort(0x4000.toShort()) // flags
        buf.put(64.toByte()) // TTL
        buf.put(protocol.toByte()) // protocol
        buf.putShort(0) // checksum
        buf.put(sourceIp)
        buf.put(destIp)

        // UDP header (8 bytes)
        buf.putShort(sourcePort.toShort())
        buf.putShort(destPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0) // checksum

        // DNS payload
        buf.put(dnsPayload)

        return packet
    }

    /**
     * Build a minimal IPv6/UDP/DNS query packet for testing.
     */
    private fun buildTestIpv6DnsPacket(
        sourceIp: ByteArray = ByteArray(16).also { it[15] = 2 }, // ::2
        destIp: ByteArray = ByteArray(16).also { it[15] = 1 },   // ::1
        sourcePort: Int = 12345,
        destPort: Int = 53,
        domain: String = "example.com",
        queryType: Int = 28, // AAAA
        nextHeader: Int = 17 // UDP
    ): ByteArray {
        val dnsPayload = buildDnsQueryPayload(domain, queryType)
        val udpLength = 8 + dnsPayload.size
        val totalLength = 40 + udpLength

        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet)

        // IPv6 header (40 bytes)
        buf.putInt(0x60000000) // version=6, traffic class=0, flow label=0
        buf.putShort(udpLength.toShort()) // payload length
        buf.put(nextHeader.toByte()) // next header
        buf.put(64.toByte()) // hop limit
        buf.put(sourceIp) // source (16 bytes)
        buf.put(destIp)   // dest (16 bytes)

        // UDP header (8 bytes)
        buf.putShort(sourcePort.toShort())
        buf.putShort(destPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0) // checksum

        // DNS payload
        buf.put(dnsPayload)

        return packet
    }

    /**
     * Build a minimal DNS query payload for a domain.
     */
    private fun buildDnsQueryPayload(domain: String, queryType: Int): ByteArray {
        val buf = ByteBuffer.allocate(512)

        // DNS header
        buf.putShort(0x1234.toShort()) // transaction ID
        buf.putShort(0x0100.toShort()) // flags: standard query, RD=1
        buf.putShort(1) // QDCOUNT
        buf.putShort(0) // ANCOUNT
        buf.putShort(0) // NSCOUNT
        buf.putShort(0) // ARCOUNT

        // Question: domain name
        for (label in domain.split('.')) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0x00.toByte()) // null terminator

        // Query type and class
        buf.putShort(queryType.toShort())
        buf.putShort(1) // class IN

        val result = ByteArray(buf.position())
        buf.flip()
        buf.get(result)
        return result
    }

    // --- IPv4 Parsing Tests ---

    @Test
    fun `parseIpPacket parses IPv4 UDP DNS query correctly`() {
        val packet = buildTestIpv4DnsPacket(domain = "ads.example.com", queryType = 1)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)

        assertNotNull(query)
        assertEquals("ads.example.com", query!!.domain)
        assertEquals(1, query.queryType)       // A record
        assertEquals(1, query.queryClass)      // IN
        assertEquals(0x1234, query.transactionId)
        assertEquals(12345, query.sourcePort)
        assertEquals(53, query.destPort)
        assertEquals(4, query.sourceIp.size)   // IPv4
        assertEquals(4, query.destIp.size)
    }

    @Test
    fun `parseIpPacket returns null for IPv4 non-UDP packet`() {
        val packet = buildTestIpv4DnsPacket(protocol = 6) // TCP
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)
        assertNull(query)
    }

    @Test
    fun `parseIpPacket returns null for IPv4 non-DNS port`() {
        val packet = buildTestIpv4DnsPacket(destPort = 80)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)
        assertNull(query)
    }

    @Test
    fun `parseIpPacket returns null for too-short packet`() {
        val packet = ByteArray(10)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)
        assertNull(query)
    }

    // --- IPv6 Parsing Tests ---

    @Test
    fun `parseIpPacket parses IPv6 UDP DNS query correctly`() {
        val srcIp = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 } // fd00::2
        val dstIp = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 } // fd00::1
        val packet = buildTestIpv6DnsPacket(
            sourceIp = srcIp,
            destIp = dstIp,
            domain = "tracker.example.com",
            queryType = 28
        )
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)

        assertNotNull(query)
        assertEquals("tracker.example.com", query!!.domain)
        assertEquals(28, query.queryType)      // AAAA record
        assertEquals(1, query.queryClass)      // IN
        assertEquals(0x1234, query.transactionId)
        assertEquals(12345, query.sourcePort)
        assertEquals(53, query.destPort)
        assertEquals(16, query.sourceIp.size)  // IPv6
        assertEquals(16, query.destIp.size)
    }

    @Test
    fun `parseIpPacket handles IPv6 A query type`() {
        val packet = buildTestIpv6DnsPacket(domain = "example.com", queryType = 1)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)

        assertNotNull(query)
        assertEquals("example.com", query!!.domain)
        assertEquals(1, query.queryType) // A record over IPv6
    }

    @Test
    fun `parseIpPacket returns null for IPv6 non-UDP next header`() {
        val packet = buildTestIpv6DnsPacket(nextHeader = 6) // TCP
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)
        assertNull(query)
    }

    @Test
    fun `parseIpPacket returns null for IPv6 non-DNS port`() {
        val packet = buildTestIpv6DnsPacket(destPort = 443)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)
        assertNull(query)
    }

    // --- Blocked Response Tests ---

    @Test
    fun `buildBlockedResponse for IPv4 A query returns IPv4 packet with 0_0_0_0`() {
        val packet = buildTestIpv4DnsPacket(domain = "ad.example.com", queryType = 1)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)!!

        val response = DnsPacketParser.buildBlockedResponse(query)

        // Response should be an IPv4 packet (starts with version 4)
        val version = (response[0].toInt() and 0xFF) shr 4
        assertEquals(4, version)

        // Extract DNS answer from the response and verify it's type A with 0.0.0.0
        val ipHeaderLen = (response[0].toInt() and 0x0F) * 4
        val dnsOffset = ipHeaderLen + 8 // skip UDP header
        val dnsBuf = ByteBuffer.wrap(response, dnsOffset, response.size - dnsOffset)

        // Skip DNS header (12 bytes)
        dnsBuf.position(dnsBuf.position() + 12)
        // Skip question section (domain + type + class)
        skipDnsQuestion(dnsBuf)
        // Answer: name pointer (2) + type (2) + class (2) + TTL (4) + rdlength (2) + rdata
        dnsBuf.short // name pointer
        val ansType = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(1, ansType) // Type A
        dnsBuf.short // class
        dnsBuf.int   // TTL
        val rdLength = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(4, rdLength) // 4 bytes for IPv4
        val rdata = ByteArray(4)
        dnsBuf.get(rdata)
        assertEquals(0, rdata[0].toInt())
        assertEquals(0, rdata[1].toInt())
        assertEquals(0, rdata[2].toInt())
        assertEquals(0, rdata[3].toInt())
    }

    @Test
    fun `buildServfailResponse returns DNS response with SERVFAIL rcode`() {
        val packet = buildTestIpv4DnsPacket(domain = "example.com", queryType = 1)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)!!

        val response = DnsPacketParser.buildServfailResponse(query)

        // Response should be an IPv4 packet
        val version = (response[0].toInt() and 0xFF) shr 4
        assertEquals(4, version)

        // Extract DNS response and verify SERVFAIL (RCODE=2)
        val ipHeaderLen = (response[0].toInt() and 0x0F) * 4
        val dnsOffset = ipHeaderLen + 8 // skip UDP header
        val dnsBuf = ByteBuffer.wrap(response, dnsOffset, response.size - dnsOffset)

        // Transaction ID should match
        val txId = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(0x1234, txId)

        // Flags byte 2 should have RCODE=2 (SERVFAIL)
        dnsBuf.get() // flags byte 1
        val flags2 = dnsBuf.get().toInt() and 0xFF
        val rcode = flags2 and 0x0F
        assertEquals(2, rcode) // SERVFAIL

        // QDCOUNT should be 1
        val qdcount = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(1, qdcount)

        // ANCOUNT should be 0 (no answers in SERVFAIL)
        val ancount = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(0, ancount)
    }

    @Test
    fun `buildServfailResponse for IPv6 query returns IPv6 packet`() {
        val packet = buildTestIpv6DnsPacket(domain = "test.com", queryType = 28)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)!!

        val response = DnsPacketParser.buildServfailResponse(query)

        // Response should be an IPv6 packet
        val version = (response[0].toInt() and 0xFF) shr 4
        assertEquals(6, version)

        // Verify SERVFAIL rcode
        val dnsOffset = 48 // IPv6 header (40) + UDP header (8)
        val dnsBuf = ByteBuffer.wrap(response, dnsOffset, response.size - dnsOffset)
        
        val transactionId = dnsBuf.short // Skip transaction ID
        val flags1 = dnsBuf.get()  // Skip flags byte 1
        val flags2 = dnsBuf.get().toInt() and 0xFF
        val rcode = flags2 and 0x0F
        assertEquals(2, rcode) // SERVFAIL
    }

    @Test
    fun `buildBlockedResponse for IPv6 AAAA query returns IPv6 packet with all-zeros`() {
        val packet = buildTestIpv6DnsPacket(domain = "tracker.example.com", queryType = 28)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)!!

        val response = DnsPacketParser.buildBlockedResponse(query)

        // Response should be an IPv6 packet (starts with version 6)
        val version = (response[0].toInt() and 0xFF) shr 4
        assertEquals(6, version)

        // Verify total size: IPv6 header (40) + UDP header (8) + DNS response payload
        val ipv6UdpOverhead = 48 // 40 (IPv6) + 8 (UDP)
        assert(response.size > ipv6UdpOverhead) { "Response too small: ${response.size}" }

        // Extract DNS answer
        val dnsOffset = ipv6UdpOverhead
        val dnsBuf = ByteBuffer.wrap(response, dnsOffset, response.size - dnsOffset)

        // Skip DNS header (12 bytes)
        dnsBuf.position(dnsBuf.position() + 12)
        // Skip question section
        skipDnsQuestion(dnsBuf)
        // Answer
        dnsBuf.short // name pointer
        val ansType = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(28, ansType) // Type AAAA
        dnsBuf.short // class
        dnsBuf.int   // TTL
        val rdLength = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(16, rdLength) // 16 bytes for IPv6
        val rdata = ByteArray(16)
        dnsBuf.get(rdata)
        // All zeros (::)
        for (b in rdata) {
            assertEquals(0, b.toInt())
        }
    }

    @Test
    fun `buildBlockedResponse for IPv4 AAAA query returns AAAA record in IPv4 packet`() {
        // An AAAA query can arrive over IPv4 transport
        val packet = buildTestIpv4DnsPacket(domain = "example.com", queryType = 28)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)!!

        val response = DnsPacketParser.buildBlockedResponse(query)

        // Transport should be IPv4
        val version = (response[0].toInt() and 0xFF) shr 4
        assertEquals(4, version)

        // DNS answer should be AAAA with 16 zero bytes
        val ipHeaderLen = (response[0].toInt() and 0x0F) * 4
        val dnsOffset = ipHeaderLen + 8
        val dnsBuf = ByteBuffer.wrap(response, dnsOffset, response.size - dnsOffset)
        dnsBuf.position(dnsBuf.position() + 12) // skip DNS header
        skipDnsQuestion(dnsBuf)
        dnsBuf.short // name pointer
        val ansType = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(28, ansType) // Type AAAA
        dnsBuf.short // class
        dnsBuf.int   // TTL
        val rdLength = dnsBuf.short.toInt() and 0xFFFF
        assertEquals(16, rdLength)
    }

    // --- IPv6 Packet Building Tests ---

    @Test
    fun `buildIpUdpPacket with 16-byte IPs produces valid IPv6 packet`() {
        val srcIp = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
        val dstIp = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        val packet = DnsPacketParser.buildIpUdpPacket(srcIp, dstIp, 53, 12345, payload)

        // Total = 40 (IPv6) + 8 (UDP) + 3 (payload) = 51
        assertEquals(51, packet.size)

        val buf = ByteBuffer.wrap(packet)
        val versionEtc = buf.int
        val version = (versionEtc ushr 28) and 0xF
        assertEquals(6, version)

        val payloadLength = buf.short.toInt() and 0xFFFF
        assertEquals(8 + 3, payloadLength) // UDP + payload

        val nextHeader = buf.get().toInt() and 0xFF
        assertEquals(17, nextHeader) // UDP

        val hopLimit = buf.get().toInt() and 0xFF
        assertEquals(64, hopLimit)
    }

    @Test
    fun `buildIpUdpPacket with 4-byte IPs produces valid IPv4 packet`() {
        val srcIp = byteArrayOf(10, 0, 0, 1)
        val dstIp = byteArrayOf(10, 0, 0, 2)
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        val packet = DnsPacketParser.buildIpUdpPacket(srcIp, dstIp, 53, 12345, payload)

        // Total = 20 (IPv4) + 8 (UDP) + 3 (payload) = 31
        assertEquals(31, packet.size)

        val version = (packet[0].toInt() and 0xFF) shr 4
        assertEquals(4, version)
    }

    @Test
    fun `IPv6 UDP checksum is non-zero`() {
        val srcIp = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
        val dstIp = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val packet = DnsPacketParser.buildIpUdpPacket(srcIp, dstIp, 53, 12345, payload)

        // UDP checksum at offset 40 + 6 = 46
        val checksumHi = packet[46].toInt() and 0xFF
        val checksumLo = packet[47].toInt() and 0xFF
        val checksum = (checksumHi shl 8) or checksumLo
        // Checksum should be non-zero for IPv6
        assert(checksum != 0) { "IPv6 UDP checksum must not be zero" }
    }

    // --- Round-trip Test ---

    @Test
    fun `IPv6 blocked response can be parsed back as IPv6 packet`() {
        val packet = buildTestIpv6DnsPacket(domain = "ad.test.com", queryType = 28)
        val query = DnsPacketParser.parseIpPacket(packet, packet.size)!!
        val response = DnsPacketParser.buildBlockedResponse(query)

        // Verify the response is a valid IPv6 packet
        val version = (response[0].toInt() and 0xFF) shr 4
        assertEquals(6, version)

        // Verify it has the right total size: 40 (IPv6) + 8 (UDP) + DNS response
        assert(response.size > 48) { "Response too small: ${response.size}" }
    }

    // --- Helper methods ---

    private fun skipDnsQuestion(buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val len = buf.get().toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                buf.get() // skip offset
                break
            }
            buf.position(buf.position() + len)
        }
        buf.short // query type
        buf.short // query class
    }
}
