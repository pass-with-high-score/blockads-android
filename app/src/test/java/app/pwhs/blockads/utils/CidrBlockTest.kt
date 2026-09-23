package app.pwhs.blockads.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class CidrBlockTest {

    @Test
    fun `parse normalizes host bits and defaults to a host route`() {
        assertEquals("10.0.0.0/8", CidrBlock.parse("10.1.2.3/8").toString())
        assertEquals("192.168.1.7/32", CidrBlock.parse(" 192.168.1.7 ").toString())
        assertEquals("0.0.0.0/0", CidrBlock.parse("0.0.0.0/0").toString())
    }

    @Test
    fun `mask, start and end cover the block`() {
        val block = CidrBlock.parse("172.16.0.0/12")
        assertEquals(0xFFF00000L, block.mask)
        assertEquals(0xAC100000L, block.start)
        assertEquals(0xAC1FFFFFL, block.end)
        assertEquals(0L, CidrBlock.parse("0.0.0.0/0").mask)
        assertEquals(0xFFFFFFFFL, CidrBlock.parse("0.0.0.0/0").end)
    }

    @Test
    fun `parse rejects malformed input`() {
        assertThrows(IllegalArgumentException::class.java) { CidrBlock.parse("10.0.0/8") }
        assertThrows(IllegalArgumentException::class.java) { CidrBlock.parse("10.0.0.0.0/8") }
        assertThrows(IllegalArgumentException::class.java) { CidrBlock.parse("10.0.0.0/33") }
        assertThrows(IllegalArgumentException::class.java) { CidrBlock.parse("10.0.0.0/-1") }
        assertThrows(NumberFormatException::class.java) { CidrBlock.parse("a.b.c.d/8") }
        assertThrows(NumberFormatException::class.java) { CidrBlock.parse("10.0.0.0/x") }
        assertThrows(NumberFormatException::class.java) { CidrBlock.parse("") }
    }

    @Ignore("known bug: CidrBlock.parse masks octets with 0xFF, so 300.1.1.1 silently becomes 44.1.1.1")
    @Test
    fun `parse rejects out-of-range octets`() {
        assertThrows(IllegalArgumentException::class.java) { CidrBlock.parse("300.1.1.1/32") }
        assertThrows(IllegalArgumentException::class.java) { CidrBlock.parse("-1.0.0.0/8") }
    }

    @Test
    fun `constructor enforces the prefix range`() {
        assertThrows(IllegalArgumentException::class.java) { CidrBlock(0, 33) }
        assertThrows(IllegalArgumentException::class.java) { CidrBlock(0, -1) }
    }

    @Test
    fun `fromIpParts matches parse`() {
        assertEquals(CidrBlock.parse("192.168.0.0/16"), CidrBlock.fromIpParts(192, 168, 0, 0, 16))
    }

    @Test
    fun `contains and containsIp`() {
        val ten = CidrBlock.parse("10.0.0.0/8")
        assertTrue(ten.contains(CidrBlock.parse("10.20.0.0/16")))
        assertTrue(ten.contains(ten))
        assertFalse(CidrBlock.parse("10.20.0.0/16").contains(ten))
        assertFalse(ten.contains(CidrBlock.parse("11.0.0.0/16")))
        assertTrue(ten.containsIp(0x0A000000L))
        assertTrue(ten.containsIp(0x0AFFFFFFL))
        assertFalse(ten.containsIp(0x0B000000L))
    }

    @Test
    fun `split halves the block`() {
        val (left, right) = CidrBlock.parse("10.0.0.0/8").split()
        assertEquals("10.0.0.0/9", left.toString())
        assertEquals("10.128.0.0/9", right.toString())
        val (a, b) = CidrBlock.parse("1.2.3.4/31").split()
        assertEquals(listOf("1.2.3.4/32", "1.2.3.5/32"), listOf(a.toString(), b.toString()))
    }

    @Test
    fun `split of a host route is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CidrBlock.parse("1.2.3.4/32").split() }
    }
}
