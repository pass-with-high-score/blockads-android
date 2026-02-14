package app.pwhs.blockads.service

import java.io.ByteArrayOutputStream
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

    // IPv4 header minimum 20 bytes
    private const val IP_HEADER_SIZE = 20

    // IPv6 header is always 40 bytes (fixed)
    private const val IPV6_HEADER_SIZE = 40

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
     * Supports both IPv4 and IPv6 packets.
     */
    fun parseIpPacket(packet: ByteArray, length: Int): DnsQuery? {
        if (length < IP_HEADER_SIZE + UDP_HEADER_SIZE + DNS_HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(packet, 0, length)

        val firstByte = buffer.get().toInt() and 0xFF
        val version = firstByte shr 4

        val sourceIp: ByteArray
        val destIp: ByteArray
        val udpStart: Int

        when (version) {
            4 -> {
                val ihl = (firstByte and 0x0F) * 4
                if (ihl < IP_HEADER_SIZE) return null

                buffer.position(9)
                val protocol = buffer.get().toInt() and 0xFF
                if (protocol != 17) return null // Only UDP

                buffer.position(12)
                sourceIp = ByteArray(4)
                buffer.get(sourceIp)
                destIp = ByteArray(4)
                buffer.get(destIp)

                udpStart = ihl
            }

            6 -> {
                if (length < IPV6_HEADER_SIZE + UDP_HEADER_SIZE + DNS_HEADER_SIZE) return null

                buffer.position(6)
                val nextHeader = buffer.get().toInt() and 0xFF
                if (nextHeader != 17) return null // Only UDP (no extension headers)

                buffer.position(8)
                sourceIp = ByteArray(16)
                buffer.get(sourceIp)
                destIp = ByteArray(16)
                buffer.get(destIp)

                udpStart = IPV6_HEADER_SIZE
            }

            else -> return null
        }

        // Parse UDP header (common for both IPv4 and IPv6)
        buffer.position(udpStart)
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

    /**
     * Build a DNS SERVFAIL response for when DNS resolution fails.
     * This creates the full IP+UDP+DNS packet to write back to the TUN.
     */
    fun buildServfailResponse(query: DnsQuery): ByteArray {
        val dnsResponse = buildServfailDnsResponse(query)

        return buildIpUdpPacket(
            sourceIp = query.destIp,   // Swap: original dest becomes source
            destIp = query.sourceIp,   // Swap: original source becomes dest
            sourcePort = query.destPort,
            destPort = query.sourcePort,
            payload = dnsResponse
        )
    }

    /**
     * Build a DNS NXDOMAIN response for a blocked domain.
     * This creates the full IP+UDP+DNS packet to write back to the TUN.
     */
    fun buildNxdomainResponse(query: DnsQuery): ByteArray {
        val dnsResponse = buildNxdomainDnsResponse(query)

        return buildIpUdpPacket(
            sourceIp = query.destIp,   // Swap: original dest becomes source
            destIp = query.sourceIp,   // Swap: original source becomes dest
            sourcePort = query.destPort,
            destPort = query.sourcePort,
            payload = dnsResponse
        )
    }

    /**
     * Build a DNS REFUSED response for a blocked domain.
     * This creates the full IP+UDP+DNS packet to write back to the TUN.
     */
    fun buildRefusedResponse(query: DnsQuery): ByteArray {
        val dnsResponse = buildRefusedDnsResponse(query)

        return buildIpUdpPacket(
            sourceIp = query.destIp,   // Swap: original dest becomes source
            destIp = query.sourceIp,   // Swap: original source becomes dest
            sourcePort = query.destPort,
            destPort = query.sourcePort,
            payload = dnsResponse
        )
    }

    /**
     * Build a DNS response that redirects to a specific IPv4 address.
     * Used for SafeSearch enforcement to redirect search engines to their
     * SafeSearch equivalents.
     * For AAAA queries, returns an empty response (no AAAA record) to force IPv4.
     */
    fun buildRedirectResponse(query: DnsQuery, ipv4Address: ByteArray): ByteArray {
        val dnsResponse = buildRedirectDnsResponse(query, ipv4Address)

        return buildIpUdpPacket(
            sourceIp = query.destIp,
            destIp = query.sourceIp,
            sourcePort = query.destPort,
            destPort = query.sourcePort,
            payload = dnsResponse
        )
    }

    /**
     * Build a raw DNS query payload for resolving a domain.
     * Used internally for SafeSearch domain resolution.
     */
    fun buildDnsQueryPayload(domain: String, queryType: Int, transactionId: Int): ByteArray {
        val out = ByteArrayOutputStream()

        // Transaction ID
        out.write(transactionId shr 8)
        out.write(transactionId and 0xFF)

        // Flags: RD=1 (recursion desired)
        out.write(0x01)
        out.write(0x00)

        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00)
        // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00)
        // ARCOUNT = 0
        out.write(0x00)
        out.write(0x00)

        // Question section
        out.write(encodeDomainName(domain))
        // Query type
        out.write(queryType shr 8)
        out.write(queryType and 0xFF)
        // Query class IN (1)
        out.write(0x00)
        out.write(0x01)

        return out.toByteArray()
    }

    /**
     * Parse the first A record (IPv4 address) from a DNS response payload.
     * Returns the 4-byte IPv4 address, or null if no A record is found.
     */
    fun parseFirstARecord(dnsResponse: ByteArray): ByteArray? {
        if (dnsResponse.size < DNS_HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(dnsResponse)

        buffer.short // transaction ID
        buffer.short // flags
        val qdCount = buffer.short.toInt() and 0xFFFF
        val anCount = buffer.short.toInt() and 0xFFFF
        buffer.short // nsCount
        buffer.short // arCount

        // Skip question section
        for (i in 0 until qdCount) {
            skipDomainName(buffer) ?: return null
            if (buffer.remaining() < 4) return null
            buffer.short // query type
            buffer.short // query class
        }

        // Parse answer section
        for (i in 0 until anCount) {
            skipDomainName(buffer) ?: return null
            if (buffer.remaining() < 10) return null

            val type = buffer.short.toInt() and 0xFFFF
            buffer.short // class
            buffer.int   // TTL
            val rdLength = buffer.short.toInt() and 0xFFFF

            if (buffer.remaining() < rdLength) return null

            if (type == 1 && rdLength == 4) {
                // A record - IPv4 address
                val ip = ByteArray(4)
                buffer.get(ip)
                return ip
            } else {
                // Skip this record's data
                buffer.position(buffer.position() + rdLength)
            }
        }

        return null
    }

    private fun skipDomainName(buffer: ByteBuffer): Boolean? {
        var safety = 0
        while (buffer.hasRemaining() && safety < 128) {
            val labelLength = buffer.get().toInt() and 0xFF
            if (labelLength == 0) return true

            if ((labelLength and 0xC0) == 0xC0) {
                // Compression pointer
                if (!buffer.hasRemaining()) return null
                buffer.get() // skip offset byte
                return true
            }

            if (buffer.remaining() < labelLength) return null
            buffer.position(buffer.position() + labelLength)
            safety++
        }
        return if (safety < 128) true else null
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

        if (query.queryType == 28) {
            // AAAA record (IPv6)
            // Type AAAA (28)
            out.write(0x00)
            out.write(0x1C)
            // Class IN (1)
            out.write(0x00)
            out.write(0x01)
            // TTL = 300 seconds
            out.write(0x00)
            out.write(0x00)
            out.write(0x01)
            out.write(0x2C)
            // RDLENGTH = 16
            out.write(0x00)
            out.write(0x10)
            // RDATA = :: (16 zero bytes)
            for (i in 0 until 16) {
                out.write(0x00)
            }
        } else {
            // A record (IPv4)
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
        }

        return out.toByteArray()
    }

    private fun buildServfailDnsResponse(query: DnsQuery): ByteArray {
        val out = ByteArrayOutputStream()

        // Transaction ID
        out.write(query.transactionId shr 8)
        out.write(query.transactionId and 0xFF)

        // Flags: QR=1 (response), RD mirrors query, RA=1, RCODE=2 (SERVFAIL)
        // RD is the least significant bit of the first flags byte in the original query
        val originalFlagsByte1 = query.rawDnsPayload.getOrNull(2)?.toInt() ?: 0
        val rdSet = (originalFlagsByte1 and 0x01) == 0x01
        val responseFlagsByte1 = 0x80 or if (rdSet) 0x01 else 0x00  // QR=1, RD from query
        // 0x82 = 10000010 (RA=1, RCODE=2)
        out.write(responseFlagsByte1)
        out.write(0x82)

        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT = 0 (no answers in SERVFAIL)
        out.write(0x00)
        out.write(0x00)
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

        return out.toByteArray()
    }

    private fun buildNxdomainDnsResponse(query: DnsQuery): ByteArray {
        val out = ByteArrayOutputStream()

        // Transaction ID
        out.write(query.transactionId shr 8)
        out.write(query.transactionId and 0xFF)

        // Flags: QR=1 (response), AA=1 (authoritative), RD mirrors query, RA=1, RCODE=3 (NXDOMAIN)
        val originalFlagsByte1 = query.rawDnsPayload.getOrNull(2)?.toInt() ?: 0
        val rdSet = (originalFlagsByte1 and 0x01) == 0x01
        val responseFlagsByte1 = 0x84 or if (rdSet) 0x01 else 0x00  // QR=1, AA=1, conditionally set RD from query
        // 0x83 = 10000011 (RA=1, RCODE=3 NXDOMAIN)
        out.write(responseFlagsByte1)
        out.write(0x83)

        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT = 0 (no answers in NXDOMAIN)
        out.write(0x00)
        out.write(0x00)
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

        return out.toByteArray()
    }

    private fun buildRefusedDnsResponse(query: DnsQuery): ByteArray {
        val out = ByteArrayOutputStream()

        // Transaction ID
        out.write(query.transactionId shr 8)
        out.write(query.transactionId and 0xFF)

        // Flags: QR=1 (response), RD mirrors query, RA=1, RCODE=5 (REFUSED)
        val originalFlagsByte1 = query.rawDnsPayload.getOrNull(2)?.toInt() ?: 0
        val rdSet = (originalFlagsByte1 and 0x01) == 0x01
        val responseFlagsByte1 = 0x80 or if (rdSet) 0x01 else 0x00  // QR=1, conditionally set RD from query
        // 0x85 = 10000101 (RA=1, RCODE=5 REFUSED)
        out.write(responseFlagsByte1)
        out.write(0x85)

        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT = 0 (no answers in REFUSED)
        out.write(0x00)
        out.write(0x00)
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

        return out.toByteArray()
    }

    private fun buildRedirectDnsResponse(query: DnsQuery, ipv4Address: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()

        // Transaction ID
        out.write(query.transactionId shr 8)
        out.write(query.transactionId and 0xFF)

        if (query.queryType == 28) {
            // AAAA query — return empty response (no AAAA record) to force IPv4 path
            // Flags: QR=1 (response), AA=1 (authoritative), RD=1, RA=1
            out.write(0x81)
            out.write(0x80)

            // QDCOUNT = 1
            out.write(0x00)
            out.write(0x01)
            // ANCOUNT = 0 (no IPv6 answer)
            out.write(0x00)
            out.write(0x00)
            // NSCOUNT = 0
            out.write(0x00)
            out.write(0x00)
            // ARCOUNT = 0
            out.write(0x00)
            out.write(0x00)

            // Question section
            val domainBytes = encodeDomainName(query.domain)
            out.write(domainBytes)
            out.write(query.queryType shr 8)
            out.write(query.queryType and 0xFF)
            out.write(query.queryClass shr 8)
            out.write(query.queryClass and 0xFF)
        } else {
            // A query — return the redirect IPv4 address
            // Flags: QR=1 (response), AA=1 (authoritative), RD=1, RA=1
            out.write(0x81)
            out.write(0x80)

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

            // Question section
            val domainBytes = encodeDomainName(query.domain)
            out.write(domainBytes)
            out.write(query.queryType shr 8)
            out.write(query.queryType and 0xFF)
            out.write(query.queryClass shr 8)
            out.write(query.queryClass and 0xFF)

            // Answer section
            out.write(0xC0) // Name pointer
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
            // RDATA = redirect IP
            out.write(ipv4Address)
        }

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

    /**
     * Build a complete IP+UDP packet wrapping the given payload.
     * Automatically detects IPv4 or IPv6 based on source IP address size.
     */
    fun buildIpUdpPacket(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        return when (sourceIp.size) {
            16 -> buildIpv6UdpPacket(sourceIp, destIp, sourcePort, destPort, payload)
            4 -> buildIpv4UdpPacket(sourceIp, destIp, sourcePort, destPort, payload)
            else -> throw IllegalArgumentException("Unsupported IP address size: ${sourceIp.size}")
        }
    }

    private fun buildIpv4UdpPacket(
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

    private fun buildIpv6UdpPacket(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = UDP_HEADER_SIZE + payload.size
        val totalLength = IPV6_HEADER_SIZE + udpLength

        val packet = ByteArray(totalLength)
        val buffer = ByteBuffer.wrap(packet)

        // === IPv6 Header (40 bytes) ===
        buffer.putInt(0x60000000) // Version 6, Traffic Class 0, Flow Label 0
        buffer.putShort(udpLength.toShort()) // Payload Length
        buffer.put(17.toByte()) // Next Header: UDP
        buffer.put(64.toByte()) // Hop Limit
        buffer.put(sourceIp) // Source Address (16 bytes)
        buffer.put(destIp)   // Destination Address (16 bytes)

        // === UDP Header ===
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destPort.toShort())
        buffer.putShort(udpLength.toShort())
        val checksumPosition = buffer.position()
        buffer.putShort(0) // UDP checksum placeholder

        // === Payload ===
        buffer.put(payload)

        // Calculate UDP checksum (mandatory for IPv6)
        val udpChecksum = calculateUdpIpv6Checksum(
            sourceIp, destIp, packet, IPV6_HEADER_SIZE, udpLength
        )
        packet[checksumPosition] = (udpChecksum shr 8).toByte()
        packet[checksumPosition + 1] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    /**
     * Calculate UDP checksum for IPv6 using the pseudo-header as per RFC 2460.
     * The pseudo-header includes: source address, dest address, UDP length, next header (17).
     */
    private fun calculateUdpIpv6Checksum(
        sourceIp: ByteArray,
        destIp: ByteArray,
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        var sum = 0L

        // Pseudo-header: Source Address (16 bytes)
        for (i in 0 until 16 step 2) {
            sum += ((sourceIp[i].toInt() and 0xFF) shl 8) or (sourceIp[i + 1].toInt() and 0xFF)
        }
        // Pseudo-header: Destination Address (16 bytes)
        for (i in 0 until 16 step 2) {
            sum += ((destIp[i].toInt() and 0xFF) shl 8) or (destIp[i + 1].toInt() and 0xFF)
        }
        // Pseudo-header: UDP Length (32-bit)
        sum += udpLength.toLong()
        // Pseudo-header: Next Header = 17 (32-bit)
        sum += 17L

        // UDP header + payload
        var i = udpOffset
        val end = udpOffset + udpLength
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val result = (sum.toInt().inv()) and 0xFFFF
        // Per RFC 2460: UDP checksum of 0 must be transmitted as 0xFFFF
        return if (result == 0) 0xFFFF else result
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
