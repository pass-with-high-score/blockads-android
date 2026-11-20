package app.pwhs.blockads.data.repository

import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomRuleMatcherTest {

    @Test
    fun `HOST matches exact domain only while HOST-SUFFIX matches subdomains`() {
        val matcher = CustomRuleMatcher.compile(
            listOf(
                CustomDnsRule(
                    id = 1,
                    rule = "host, exact.example.com, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "exact.example.com"
                ),
                CustomDnsRule(
                    id = 2,
                    rule = "host-suffix, tracker.net, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "tracker.net"
                )
            )
        )

        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchGlobal("exact.example.com"))
        assertEquals(CustomRuleMatcher.MATCH_NONE, matcher.matchGlobal("sub.exact.example.com"))
        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchGlobal("tracker.net"))
        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchGlobal("a.b.tracker.net"))
    }

    @Test
    fun `HOST-KEYWORD, HOST-WILDCARD, and HOST-REGEX evaluate accurately`() {
        val matcher = CustomRuleMatcher.compile(
            listOf(
                CustomDnsRule(
                    id = 1,
                    rule = "host-keyword, telemetry, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "telemetry"
                ),
                CustomDnsRule(
                    id = 2,
                    rule = "host-wildcard, *.ads.?.example.com, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "*.ads.?.example.com"
                ),
                CustomDnsRule(
                    id = 3,
                    rule = "host-regex, ^ad[0-9]+\\.cdn\\.io$, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "^ad[0-9]+\\.cdn\\.io$"
                ),
                CustomDnsRule(
                    id = 4,
                    rule = "host-regex, [invalid-regex(, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "[invalid-regex("
                )
            )
        )

        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchGlobal("app.TELEMETRY.vendor.org"))
        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchGlobal("img.ads.1.example.com"))
        assertEquals(CustomRuleMatcher.MATCH_NONE, matcher.matchGlobal("img.ads.12.example.com"))
        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchGlobal("ad42.cdn.io"))
        assertEquals(CustomRuleMatcher.MATCH_NONE, matcher.matchGlobal("ads.cdn.io"))
    }

    @Test
    fun `ALLOW rules override BLOCK rules and per-app rules stay scoped to packages`() {
        val matcher = CustomRuleMatcher.compile(
            listOf(
                CustomDnsRule(
                    id = 1,
                    rule = "host-keyword, analytics, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "analytics"
                ),
                CustomDnsRule(
                    id = 2,
                    rule = "host, safe.analytics.com, direct",
                    ruleType = RuleType.ALLOW,
                    domain = "safe.analytics.com"
                ),
                CustomDnsRule(
                    id = 3,
                    rule = "host-suffix, ads.app.io, reject",
                    ruleType = RuleType.BLOCK,
                    domain = "ads.app.io",
                    packageName = "com.one.app,com.two.app"
                )
            )
        )

        assertEquals(CustomRuleMatcher.MATCH_ALLOW, matcher.matchGlobal("safe.analytics.com"))
        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchGlobal("bad.analytics.com"))

        assertEquals(CustomRuleMatcher.MATCH_NONE, matcher.matchGlobal("sub.ads.app.io"))
        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchApp("sub.ads.app.io", "com.one.app"))
        assertEquals(CustomRuleMatcher.MATCH_BLOCK, matcher.matchApp("sub.ads.app.io", "com.two.app"))
        assertEquals(CustomRuleMatcher.MATCH_NONE, matcher.matchApp("sub.ads.app.io", "com.other.app"))
    }
}
