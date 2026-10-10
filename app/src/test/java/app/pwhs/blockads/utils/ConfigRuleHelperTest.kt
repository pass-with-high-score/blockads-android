package app.pwhs.blockads.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRuleHelperTest {

    @Test
    fun testParseGeneralDnsExclusions() {
        val content = """
            [general]
            dns_exclusion_list = *.local, localhost, *.lan, 192.168.1.1
            
            [filter_local]
            host-suffix, example.com, reject
        """.trimIndent()

        val exclusions = ConfigRuleHelper.parseGeneralDnsExclusions(content)
        assertEquals(listOf("*.local", "localhost", "*.lan", "192.168.1.1"), exclusions)
    }

    @Test
    fun testParseDnsServers() {
        val content = """
            [dns]
            server = 1.1.1.1
            server = 8.8.8.8
            9.9.9.9
            # comment = ignored
        """.trimIndent()

        val servers = ConfigRuleHelper.parseDnsServers(content)
        assertEquals(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"), servers)
    }

    @Test
    fun testParseRemoteFilters() {
        val content = """
            [filter_remote]
            https://raw.githubusercontent.com/example/rules.txt, tag=AdGuard, update-interval=12
            https://example.com/easylist.txt
            # https://commented.out/filter.txt
        """.trimIndent()

        val filters = ConfigRuleHelper.parseRemoteFilters(content)
        assertEquals(2, filters.size)

        assertEquals("https://raw.githubusercontent.com/example/rules.txt", filters[0].url)
        assertEquals("AdGuard", filters[0].tag)
        assertEquals(12, filters[0].interval)

        assertEquals("https://example.com/easylist.txt", filters[1].url)
        assertEquals("easylist.txt", filters[1].tag)
        assertEquals(24, filters[1].interval)
    }

    @Test
    fun testParseRulesBasic() {
        val content = """
            [filter_local]
            host-suffix, adservice.google.com, reject
            geoip, vn, direct
            final, direct
        """.trimIndent()

        val rules = ConfigRuleHelper.parseFilterRules(content)
        assertEquals(3, rules.size)
        assertEquals("HOST-SUFFIX", rules[0].type)
        assertEquals("adservice.google.com", rules[0].param)
        assertEquals("REJECT", rules[0].policy)
        assertTrue(rules[0].isEnabled)

        assertEquals("GEOIP", rules[1].type)
        assertEquals("vn", rules[1].param)
        assertEquals("DIRECT", rules[1].policy)

        assertEquals("FINAL", rules[2].type)
        assertEquals("DIRECT", rules[2].policy)
    }

    @Test
    fun testUpdateDnsSection() {
        val original = """
            [general]
            dns_exclusion_list = *.local
            
            [dns]
            server = 1.1.1.1
            
            [filter_local]
            final, direct
        """.trimIndent()

        val updated = ConfigRuleHelper.updateDnsSection(original, listOf("8.8.8.8", "8.8.4.4"))
        val servers = ConfigRuleHelper.parseDnsServers(updated)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), servers)
        assertTrue(updated.contains("[filter_local]"))
    }

    @Test
    fun testStripUnsupportedSections() {
        val configWithUnsupported = """
            [general]
            dns_exclusion_list = *.local
            
            [filter_local]
            host, ad.com, reject
            
            [rewrite_local]
            ^https://example.com url reject
            
            [rewrite_remote]
            https://example.com/rewrite.js
            
            [task_local]
            0 9 * * * script.js
            
            [http_backend]
            server = 127.0.0.1
            
            [mitm]
            hostname = *.google.com
        """.trimIndent()

        val cleaned = ConfigRuleHelper.stripUnsupportedSections(configWithUnsupported)
        assertTrue(cleaned.contains("[general]"))
        assertTrue(cleaned.contains("[filter_local]"))
        assertTrue(cleaned.contains("host, ad.com, reject"))
        org.junit.Assert.assertFalse(cleaned.contains("[rewrite_local]"))
        org.junit.Assert.assertFalse(cleaned.contains("[rewrite_remote]"))
        org.junit.Assert.assertFalse(cleaned.contains("[task_local]"))
        org.junit.Assert.assertFalse(cleaned.contains("[http_backend]"))
        org.junit.Assert.assertFalse(cleaned.contains("[mitm]"))
        org.junit.Assert.assertFalse(cleaned.contains("script.js"))
    }
}
