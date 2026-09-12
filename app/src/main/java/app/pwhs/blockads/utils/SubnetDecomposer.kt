package app.pwhs.blockads.utils

/**
 * Represents an IPv4 CIDR prefix (e.g. 192.168.0.0/16).
 */
data class CidrBlock(
    val address: Long, // Unsigned 32-bit integer representing IPv4
    val prefix: Int
) {
    init {
        require(prefix in 0..32) { "Prefix must be between 0 and 32, got $prefix" }
    }

    val mask: Long = if (prefix == 0) 0L else ((0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL)
    val start: Long = address and mask
    val end: Long = start or (mask.inv() and 0xFFFFFFFFL)

    fun contains(other: CidrBlock): Boolean {
        return this.start <= other.start && other.end <= this.end
    }

    fun containsIp(ipLong: Long): Boolean {
        return ipLong in start..end
    }

    fun split(): Pair<CidrBlock, CidrBlock> {
        require(prefix < 32) { "Cannot split /32 block" }
        val newPrefix = prefix + 1
        val halfSize = 1L shl (32 - newPrefix)
        val left = CidrBlock(start, newPrefix)
        val right = CidrBlock(start + halfSize, newPrefix)
        return Pair(left, right)
    }

    val ipString: String
        get() = "${(start ushr 24) and 0xFF}.${(start ushr 16) and 0xFF}.${(start ushr 8) and 0xFF}.${start and 0xFF}"

    override fun toString(): String = "$ipString/$prefix"

    companion object {
        fun parse(cidr: String): CidrBlock {
            val parts = cidr.trim().split("/")
            val ipParts = parts[0].split(".").map { it.toInt() }
            require(ipParts.size == 4) { "Invalid IPv4 address: ${parts[0]}" }
            val ipLong = ((ipParts[0].toLong() and 0xFF) shl 24) or
                    ((ipParts[1].toLong() and 0xFF) shl 16) or
                    ((ipParts[2].toLong() and 0xFF) shl 8) or
                    (ipParts[3].toLong() and 0xFF)
            val prefix = if (parts.size > 1) parts[1].toInt() else 32
            return CidrBlock(ipLong, prefix)
        }

        fun fromIpParts(a: Int, b: Int, c: Int, d: Int, prefix: Int): CidrBlock {
            val ipLong = ((a.toLong() and 0xFF) shl 24) or
                    ((b.toLong() and 0xFF) shl 16) or
                    ((c.toLong() and 0xFF) shl 8) or
                    (d.toLong() and 0xFF)
            return CidrBlock(ipLong, prefix)
        }
    }
}

/**
 * ExpressVPN-inspired Subnet Decomposition utility.
 *
 * Mathematically breaks down global 0.0.0.0/0 into disjoint CIDR routes that cover
 * all public internet traffic, while completely excluding:
 * - 10.0.0.0/8 (Private Class A)
 * - 172.16.0.0/12 (Private Class B)
 * - 192.168.0.0/16 (Private Class C)
 * - 169.254.0.0/16 (Link-Local / APIPA)
 * - 224.0.0.0/4 (Multicast: mDNS 224.0.0.251, SSDP for Chromecast & IoT)
 * - 240.0.0.0/4 (Reserved / Class E)
 * - 127.0.0.0/8 (Loopback)
 *
 * This provides 100% reliable LAN bypass on all Android versions (7.0 to 15+)
 * without relying on buggy or missing OS `excludeRoute()` APIs.
 */
object SubnetDecomposer {

    val DEFAULT_EXCLUDED_SUBNETS: List<CidrBlock> = listOf(
        CidrBlock.parse("10.0.0.0/8"),
        CidrBlock.parse("172.16.0.0/12"),
        CidrBlock.parse("192.168.0.0/16"),
        CidrBlock.parse("169.254.0.0/16"),
        CidrBlock.parse("127.0.0.0/8"),
        CidrBlock.parse("224.0.0.0/4"), // Multicast
        CidrBlock.parse("240.0.0.0/4")  // Reserved
    )

    /**
     * Recursively decomposes [baseBlock] by subtracting all [excludedBlocks].
     */
    fun decompose(
        baseBlock: CidrBlock,
        excludedBlocks: List<CidrBlock>
    ): List<CidrBlock> {
        // If current block is completely contained within any excluded block, drop it
        if (excludedBlocks.any { it.contains(baseBlock) }) {
            return emptyList()
        }

        // If no excluded block overlaps with this block, keep it entirely
        val hasOverlap = excludedBlocks.any { baseBlock.contains(it) }
        if (!hasOverlap) {
            return listOf(baseBlock)
        }

        // Otherwise, split and recurse on both halves
        if (baseBlock.prefix >= 32) {
            return emptyList()
        }
        val (left, right) = baseBlock.split()
        return decompose(left, excludedBlocks) + decompose(right, excludedBlocks)
    }

    /**
     * Pre-computed or generated list of CIDR routes to add to VpnService.Builder
     * when LAN bypass / Split Kill Switch is enabled.
     */
    val lanBypassRoutes: List<CidrBlock> by lazy {
        decompose(CidrBlock.parse("0.0.0.0/0"), DEFAULT_EXCLUDED_SUBNETS)
    }
}
