package app.pwhs.blockads.service

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Utility class for parsing and building DNS packets.
 *
 * DNS packet format (RFC 1035):
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                      ID                         |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    QDCOUNT                      |
 * |                    ANCOUNT                      |
 * |                    NSCOUNT                      |
 * |                    ARCOUNT                      |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 */
object DnsPacketParser {

    private const val TAG = "DnsPacketParser"

    // DNS header is always 12 bytes
    private const val DNS_HEADER_SIZE = 12
    // IP header minimum 20 bytes
    private const val IP_HEADER_SIZE = 20
    // UDP header 8 bytes
    private const val UDP_HEADER_SIZE = 8

    data class DnsQuery(
        val transactionId: Int,
        val domain: String,
        val queryType: Int, // 1 = A, 28 = AAAA
        val queryClass: Int,
        val rawDnsPayload: ByteArray,
        // IP/UDP info for response routing
        val sourceIp: ByteArray,
        val destIp: ByteArray,
        val sourcePort: Int,
        val destPort: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DnsQuery) return false
            return transactionId == other.transactionId && domain == other.domain
        }

        override fun hashCode(): Int {
            return 31 * transactionId + domain.hashCode()
        }
    }

    /**
     * Parse a raw IP packet from the TUN device and extract DNS query info.
     * Returns null if the packet is not a DNS query.
     */
    fun parseIpPacket(packet: ByteArray, length: Int): DnsQuery? {
        if (length < IP_HEADER_SIZE + UDP_HEADER_SIZE + DNS_HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(packet, 0, length)

        // IP Header
        val versionAndIhl = buffer.get().toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // Only handle IPv4

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < IP_HEADER_SIZE) return null

        buffer.position(9)
        val protocol = buffer.get().toInt() and 0xFF
        if (protocol != 17) return null // Only UDP (protocol 17)

        buffer.position(12)
        val sourceIp = ByteArray(4)
        buffer.get(sourceIp)
        val destIp = ByteArray(4)
        buffer.get(destIp)

        // Move to UDP header
        buffer.position(ihl)
        if (buffer.remaining() < UDP_HEADER_SIZE + DNS_HEADER_SIZE) return null

        val sourcePort = buffer.short.toInt() and 0xFFFF
        val destPort = buffer.short.toInt() and 0xFFFF

        // Only intercept DNS queries (port 53)
        if (destPort != 53) return null

        val udpLength = buffer.short.toInt() and 0xFFFF
        buffer.short // skip checksum

        // DNS payload starts here
        val dnsStart = buffer.position()
        val dnsLength = length - dnsStart
        if (dnsLength < DNS_HEADER_SIZE) return null

        val dnsPayload = ByteArray(dnsLength)
        System.arraycopy(packet, dnsStart, dnsPayload, 0, dnsLength)

        return parseDnsPayload(dnsPayload, sourceIp, destIp, sourcePort, destPort)
    }

    private fun parseDnsPayload(
        dnsPayload: ByteArray,
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int
    ): DnsQuery? {
        val buffer = ByteBuffer.wrap(dnsPayload)

        val transactionId = buffer.short.toInt() and 0xFFFF
        val flags = buffer.short.toInt() and 0xFFFF
        val isQuery = (flags shr 15) == 0
        if (!isQuery) return null

        val qdCount = buffer.short.toInt() and 0xFFFF
        buffer.short // anCount
        buffer.short // nsCount
        buffer.short // arCount

        if (qdCount < 1) return null

        // Parse domain name
        val domain = parseDomainName(buffer) ?: return null
        if (buffer.remaining() < 4) return null

        val queryType = buffer.short.toInt() and 0xFFFF
        val queryClass = buffer.short.toInt() and 0xFFFF

        return DnsQuery(
            transactionId = transactionId,
            domain = domain,
            queryType = queryType,
            queryClass = queryClass,
            rawDnsPayload = dnsPayload,
            sourceIp = sourceIp,
            destIp = destIp,
            sourcePort = sourcePort,
            destPort = destPort
        )
    }

    private fun parseDomainName(buffer: ByteBuffer): String? {
        val parts = mutableListOf<String>()
        var safety = 0

        while (buffer.hasRemaining() && safety < 128) {
            val labelLength = buffer.get().toInt() and 0xFF
            if (labelLength == 0) break

            // Check for compression pointer (first two bits = 11)
            if ((labelLength and 0xC0) == 0xC0) {
                // Compression not expected in queries, but handle gracefully
                if (!buffer.hasRemaining()) return null
                buffer.get() // skip offset byte
                break
            }

            if (buffer.remaining() < labelLength) return null
            val label = ByteArray(labelLength)
            buffer.get(label)
            parts.add(String(label, Charsets.US_ASCII))
            safety++
        }

        return if (parts.isNotEmpty()) parts.joinToString(".") else null
    }

    /**
     * Build a DNS response that returns 0.0.0.0 for a blocked domain.
     * This creates the full IP+UDP+DNS packet to write back to the TUN.
     */
    fun buildBlockedResponse(query: DnsQuery): ByteArray {
        val dnsResponse = buildDnsResponse(query)

        return buildIpUdpPacket(
            sourceIp = query.destIp,   // Swap: original dest becomes source
            destIp = query.sourceIp,   // Swap: original source becomes dest
            sourcePort = query.destPort,
            destPort = query.sourcePort,
            payload = dnsResponse
        )
    }

    private fun buildDnsResponse(query: DnsQuery): ByteArray {
        val out = ByteArrayOutputStream()

        // Transaction ID
        out.write(query.transactionId shr 8)
        out.write(query.transactionId and 0xFF)

        // Flags: QR=1 (response), AA=1 (authoritative), RD=1, RA=1
        out.write(0x81) // 10000001
        out.write(0x80) // 10000000

        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00)
        // ARCOUNT = 0
        out.write(0x00)
        out.write(0x00)

        // Question section (copy from original query)
        val domainBytes = encodeDomainName(query.domain)
        out.write(domainBytes)
        // Query type
        out.write(query.queryType shr 8)
        out.write(query.queryType and 0xFF)
        // Query class
        out.write(query.queryClass shr 8)
        out.write(query.queryClass and 0xFF)

        // Answer section
        // Name pointer to question (compression: 0xC00C points to offset 12)
        out.write(0xC0)
        out.write(0x0C)

        // Type A (1)
        out.write(0x00)
        out.write(0x01)
        // Class IN (1)
        out.write(0x00)
        out.write(0x01)
        // TTL = 300 seconds
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x2C)
        // RDLENGTH = 4
        out.write(0x00)
        out.write(0x04)
        // RDATA = 0.0.0.0
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        return out.toByteArray()
    }

    private fun encodeDomainName(domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in domain.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00) // Null terminator
        return out.toByteArray()
    }

    fun buildIpUdpPacket(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = UDP_HEADER_SIZE + payload.size
        val totalLength = IP_HEADER_SIZE + udpLength

        val packet = ByteArray(totalLength)
        val buffer = ByteBuffer.wrap(packet)

        // === IP Header ===
        buffer.put((0x45).toByte()) // Version 4, IHL 5 (20 bytes)
        buffer.put(0x00) // DSCP/ECN
        buffer.putShort(totalLength.toShort()) // Total length
        buffer.putShort(0x0000) // Identification
        buffer.putShort(0x4000.toShort()) // Flags: Don't Fragment
        buffer.put(64) // TTL
        buffer.put(17) // Protocol: UDP
        buffer.putShort(0) // Header checksum (calculated below)
        buffer.put(sourceIp)
        buffer.put(destIp)

        // Calculate IP header checksum
        val ipChecksum = calculateChecksum(packet, 0, IP_HEADER_SIZE)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // === UDP Header ===
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destPort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0) // UDP checksum (optional for IPv4)

        // === Payload ===
        buffer.put(payload)

        return packet
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length

        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.toInt().inv()) and 0xFFFF
    }
}
