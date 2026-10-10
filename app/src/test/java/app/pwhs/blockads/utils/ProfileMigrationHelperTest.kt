package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.WhitelistDomain
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationHelperTest {

    @Test
    fun testGenerateProfileFromCurrentSettings() {
        val customRules = listOf(
            CustomDnsRule(id = 1, rule = "||ads.example.com^", ruleType = RuleType.BLOCK, domain = "ads.example.com", isEnabled = true),
            CustomDnsRule(id = 2, rule = "||*.tracker.net^", ruleType = RuleType.BLOCK, domain = "*.tracker.net", isEnabled = true),
            CustomDnsRule(id = 3, rule = "@@||safe.org^", ruleType = RuleType.ALLOW, domain = "safe.org", isEnabled = true),
            CustomDnsRule(id = 4, rule = "||disabled.com^", ruleType = RuleType.BLOCK, domain = "disabled.com", isEnabled = false)
        )

        val whitelist = listOf(
            WhitelistDomain(id = 1, domain = "mycompany.internal", isEnabled = true)
        )

        val filterLists = listOf(
            FilterList(
                id = 10,
                name = "AdGuard DNS",
                url = "https://filters.adtidy.org/android/filters/15_optimized.txt",
                isEnabled = true
            )
        )

        val profileContent = ProfileMigrationHelper.generateProfileFromCurrentSettings(
            selectedDnsProviderId = "cloudflare",
            customRules = customRules,
            whitelistDomains = whitelist,
            enabledFilterLists = filterLists
        )

        // General section
        assertTrue(profileContent.contains("[general]"))
        assertTrue(profileContent.contains("dns_exclusion_list = *.local, localhost, *.lan"))

        // DNS section
        assertTrue(profileContent.contains("[dns]"))
        assertTrue(profileContent.contains("server = 1.1.1.1"))

        // Filter local
        assertTrue(profileContent.contains("[filter_local]"))
        assertTrue(profileContent.contains("host-suffix, mycompany.internal, direct"))
        assertTrue(profileContent.contains("host-suffix, ads.example.com, reject"))
        assertTrue(profileContent.contains("host-wildcard, *.tracker.net, reject"))
        assertTrue(profileContent.contains("host-suffix, safe.org, direct"))
        assertTrue(profileContent.contains("# host-suffix, disabled.com, reject"))
        assertTrue(profileContent.contains("final, direct"))

        // Filter remote
        assertTrue(profileContent.contains("[filter_remote]"))
        assertTrue(profileContent.contains("https://filters.adtidy.org/android/filters/15_optimized.txt, tag=AdGuard_DNS, update-interval=24"))
    }

    @Test
    fun testDefaultFilterListsWhenEmpty() {
        val profileContent = ProfileMigrationHelper.generateProfileFromCurrentSettings(
            selectedDnsProviderId = "cloudflare",
            customRules = emptyList(),
            whitelistDomains = emptyList(),
            enabledFilterLists = emptyList()
        )

        assertTrue(profileContent.contains("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts, tag=StevenBlack, update-interval=24"))
        assertTrue(profileContent.contains("https://easylist.to/easylist/easyprivacy.txt, tag=EasyPrivacy, update-interval=24"))
    }
}
