package app.pwhs.blockads.ui.logs.data

import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.repository.FilterListRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockSourceTest {

    @Test
    fun `resolveSources correctly identifies all block reasons`() {
        assertEquals(setOf(BlockSource.FIREWALL), BlockSource.resolveSources("FIREWALL"))
        assertEquals(setOf(BlockSource.FIREWALL), BlockSource.resolveSources("connection"))
        assertEquals(setOf(BlockSource.CUSTOM_RULE), BlockSource.resolveSources("CUSTOM_RULE"))
        assertEquals(setOf(BlockSource.CUSTOM_RULE), BlockSource.resolveSources("custom"))
        assertEquals(setOf(BlockSource.SECURITY), BlockSource.resolveSources("SECURITY"))
        assertEquals(setOf(BlockSource.SECURITY), BlockSource.resolveSources("99", securityFilterIds = setOf("99")))
        assertEquals(setOf(BlockSource.UPSTREAM_DNS), BlockSource.resolveSources("upstream_dns"))
        assertEquals(setOf(BlockSource.UPSTREAM_DNS), BlockSource.resolveSources("UPSTREAM_DNS"))
        assertEquals(setOf(BlockSource.FILTER_LIST), BlockSource.resolveSources("FILTER_LIST"))
        assertEquals(setOf(BlockSource.FILTER_LIST), BlockSource.resolveSources("7", securityFilterIds = setOf("99")))
        assertEquals(setOf(BlockSource.FILTER_LIST), BlockSource.resolveSources(""))
    }

    @Test
    fun `resolveSources handles comma-separated sources`() {
        val sources = BlockSource.resolveSources("FIREWALL, 7", securityFilterIds = setOf("99"))
        assertEquals(setOf(BlockSource.FIREWALL, BlockSource.FILTER_LIST), sources)
    }

    @Test
    fun `matches returns true for non-blocked entries regardless of sources`() {
        val entry = DnsLogEntry(domain = "google.com", isBlocked = false, blockedBy = "")
        assertTrue(BlockSource.matches(entry, emptySet()))
        assertTrue(BlockSource.matches(entry, setOf(BlockSource.FIREWALL)))
    }

    @Test
    fun `matches filters blocked entries based on selected sources`() {
        val firewallEntry = DnsLogEntry(domain = "evil.com", isBlocked = true, blockedBy = "FIREWALL")
        val adEntry = DnsLogEntry(domain = "ad.com", isBlocked = true, blockedBy = "7")
        val customEntry = DnsLogEntry(domain = "blocked.com", isBlocked = true, blockedBy = FilterListRepository.BLOCK_REASON_CUSTOM_RULE)

        val allSources = BlockSource.entries.toSet()
        assertTrue(BlockSource.matches(firewallEntry, allSources))
        assertTrue(BlockSource.matches(adEntry, allSources))
        assertTrue(BlockSource.matches(customEntry, allSources))

        // Exclude Firewall
        val withoutFirewall = allSources - BlockSource.FIREWALL
        assertFalse(BlockSource.matches(firewallEntry, withoutFirewall))
        assertTrue(BlockSource.matches(adEntry, withoutFirewall))
        assertTrue(BlockSource.matches(customEntry, withoutFirewall))

        // Only Custom Rule
        val onlyCustom = setOf(BlockSource.CUSTOM_RULE)
        assertFalse(BlockSource.matches(firewallEntry, onlyCustom))
        assertFalse(BlockSource.matches(adEntry, onlyCustom))
        assertTrue(BlockSource.matches(customEntry, onlyCustom))

        // Empty sources
        assertFalse(BlockSource.matches(firewallEntry, emptySet()))
        assertFalse(BlockSource.matches(adEntry, emptySet()))
    }
}
