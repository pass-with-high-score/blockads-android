package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRuleParserTest {

    private data class Case(val input: String, val type: RuleType?, val domain: String? = null)

    private val table = listOf(
        Case("||ads.example.com^", RuleType.BLOCK, "ads.example.com"),
        Case("||ADS.Example.COM^", RuleType.BLOCK, "ads.example.com"),
        Case("||*.ads.example.com^", RuleType.BLOCK, "*.ads.example.com"),
        Case("||*.*.example.com^", RuleType.BLOCK, "*.*.example.com"),
        Case("example.com", RuleType.BLOCK, "example.com"),
        Case("  Example.com  ", RuleType.BLOCK, "example.com"),
        Case("*.example.com", RuleType.BLOCK, "*.example.com"),
        Case("sub-domain.example.com", RuleType.BLOCK, "sub-domain.example.com"),
        Case("1.2.3.4", RuleType.BLOCK, "1.2.3.4"),
        Case("localhost", RuleType.BLOCK, "localhost"),
        Case("@@||ok.example.com^", RuleType.ALLOW, "ok.example.com"),
        Case("@@||*.OK.example^", RuleType.ALLOW, "*.ok.example"),
        Case("@@ok.example.com", RuleType.ALLOW, "ok.example.com"),
        Case("@@ Ok.Example ", RuleType.ALLOW, "ok.example"),
        Case("! a comment", RuleType.COMMENT, ""),
        Case("!", RuleType.COMMENT, ""),
        Case("", null),
        Case("   ", null),
        Case("||^", null),
        Case("@@||^", null),
        Case("@@", null),
        Case("||example.com", null),
        Case("@@||example.com", null),
        Case("||bad domain.com^", null),
        Case("@@||bad domain^", null),
        Case("@@bad..domain", null),
        Case("a*b.com", null),
        Case("*com", null),
        Case("*", null),
        Case(".example.com", null),
        Case("example.com.", null),
        Case("exa..mple.com", null),
        Case("-bad.com", null),
        Case("bad-.com", null),
        Case("http://example.com", null),
        Case("||ads.example^\$third-party", null),
        Case("0.0.0.0 ads.example", null),
        Case("# hosts comment", null),
    )

    @Test
    fun `parseRule matches the format table`() {
        for (case in table) {
            val parsed = CustomRuleParser.parseRule(case.input)
            if (case.type == null) {
                assertNull("'${case.input}' should be rejected", parsed)
            } else {
                requireNotNull(parsed) { "'${case.input}' should parse" }
                assertEquals("type of '${case.input}'", case.type, parsed.ruleType)
                assertEquals("domain of '${case.input}'", case.domain, parsed.domain)
                assertEquals("rule text of '${case.input}'", case.input.trim(), parsed.rule)
                assertTrue(parsed.isEnabled)
                assertEquals(0, parsed.id)
            }
        }
    }

    @Test
    fun `parseRules keeps valid lines in order and drops blank and invalid ones`() {
        val text = """
            ||one.example^

            not valid!
            @@||two.example^
            ! note
            three.example
        """.trimIndent()

        val rules = CustomRuleParser.parseRules(text)

        assertEquals(
            listOf(RuleType.BLOCK, RuleType.ALLOW, RuleType.COMMENT, RuleType.BLOCK),
            rules.map { it.ruleType },
        )
        assertEquals(listOf("one.example", "two.example", "", "three.example"), rules.map { it.domain })
    }

    @Test
    fun `parseRules handles CRLF line endings`() {
        val rules = CustomRuleParser.parseRules("||a.example^\r\n||b.example^\r\n")
        assertEquals(listOf("a.example", "b.example"), rules.map { it.domain })
    }

    @Test
    fun `parseRules of empty text is empty`() {
        assertEquals(emptyList<Any>(), CustomRuleParser.parseRules(""))
    }

    @Test
    fun `formatBlockRule lowercases in both formats`() {
        assertEquals("||ads.example^", CustomRuleParser.formatBlockRule("ADS.Example"))
        assertEquals("ads.example", CustomRuleParser.formatBlockRule("ADS.Example", useAdblockFormat = false))
    }

    @Test
    fun `formatted block rules parse back to the same domain`() {
        val parsed = CustomRuleParser.parseRule(CustomRuleParser.formatBlockRule("Tracker.Example"))
        assertEquals(RuleType.BLOCK, parsed?.ruleType)
        assertEquals("tracker.example", parsed?.domain)
    }
}
