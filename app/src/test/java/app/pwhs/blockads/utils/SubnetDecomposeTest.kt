package app.pwhs.blockads.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetDecomposeTest {

    private fun size(block: CidrBlock) = block.end - block.start + 1

    @Test
    fun `block with no overlap is kept whole`() {
        val base = CidrBlock.parse("8.0.0.0/8")
        assertEquals(listOf(base), SubnetDecomposer.decompose(base, listOf(CidrBlock.parse("10.0.0.0/8"))))
    }

    @Test
    fun `block inside an exclusion is dropped`() {
        val base = CidrBlock.parse("10.1.0.0/16")
        assertTrue(SubnetDecomposer.decompose(base, listOf(CidrBlock.parse("10.0.0.0/8"))).isEmpty())
    }

    @Test
    fun `excluding a slash-8 from everything leaves eight routes`() {
        val routes = SubnetDecomposer.decompose(
            CidrBlock.parse("0.0.0.0/0"),
            listOf(CidrBlock.parse("10.0.0.0/8")),
        )
        assertEquals(
            listOf("0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/1"),
            routes.map { it.toString() },
        )
    }

    @Test
    fun `excluding a host route from a slash-30 leaves the other three addresses`() {
        val routes = SubnetDecomposer.decompose(
            CidrBlock.parse("192.0.2.0/30"),
            listOf(CidrBlock.parse("192.0.2.1/32")),
        )
        assertEquals(listOf("192.0.2.0/32", "192.0.2.2/31"), routes.map { it.toString() })
    }

    @Test
    fun `no exclusions returns the base block`() {
        val base = CidrBlock.parse("0.0.0.0/0")
        assertEquals(listOf(base), SubnetDecomposer.decompose(base, emptyList()))
    }

    @Test
    fun `lan bypass routes cover exactly the non-excluded address space in ascending order`() {
        val routes = SubnetDecomposer.lanBypassRoutes
        val excluded = SubnetDecomposer.DEFAULT_EXCLUDED_SUBNETS.sumOf(::size)
        assertEquals((1L shl 32) - excluded, routes.sumOf(::size))
        assertEquals(routes.sortedBy { it.start }, routes)
        assertTrue(routes.all { it.prefix in 1..32 })
    }
}
