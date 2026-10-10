package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.WhitelistDomain

/**
 * Generates or merges configuration profiles from the user's active app settings:
 * DNS provider, custom DNS rules, whitelist domains, and enabled remote filter lists.
 */
object ProfileMigrationHelper {

    fun generateProfileFromCurrentSettings(
        selectedDnsProviderId: String?,
        customDnsIps: List<String> = emptyList(),
        customRules: List<CustomDnsRule> = emptyList(),
        whitelistDomains: List<WhitelistDomain> = emptyList(),
        enabledFilterLists: List<FilterList> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Migrated Profile from App Settings")
        sb.appendLine("#")
        sb.appendLine()

        // 1. [general]
        sb.appendLine("[general]")
        sb.appendLine("dns_exclusion_list = *.local, localhost, *.lan")
        sb.appendLine()

        // 2. [dns]
        sb.appendLine("[dns]")
        val dnsServers = resolveDnsServers(selectedDnsProviderId, customDnsIps)
        for (server in dnsServers) {
            sb.appendLine("server = $server")
        }
        sb.appendLine()

        // 3. [policy]
        sb.appendLine("[policy]")
        sb.appendLine("static = DIRECT, direct")
        sb.appendLine("static = REJECT, reject")
        sb.appendLine()

        // 4. [server_local] & [server_remote]
        sb.appendLine("[server_local]")
        sb.appendLine()
        sb.appendLine("[server_remote]")
        sb.appendLine()

        // 5. [filter_local]
        sb.appendLine("[filter_local]")
        for (w in whitelistDomains) {
            if (!w.isEnabled) continue
            val d = w.domain.trim()
            if (d.isNotEmpty()) {
                sb.appendLine("host-suffix, $d, direct")
            }
        }
        for (r in customRules) {
            val prefix = if (!r.isEnabled) "# " else ""
            val domain = r.domain.trim()
            if (domain.isEmpty()) continue
            when (r.ruleType) {
                RuleType.ALLOW -> sb.appendLine("${prefix}host-suffix, $domain, direct")
                RuleType.BLOCK -> {
                    if (domain.startsWith("*.")) {
                        sb.appendLine("${prefix}host-wildcard, $domain, reject")
                    } else {
                        sb.appendLine("${prefix}host-suffix, $domain, reject")
                    }
                }
                RuleType.COMMENT -> { /* skip comment lines in filter_local */ }
            }
        }
        sb.appendLine("ip-cidr, 10.0.0.0/8, direct")
        sb.appendLine("ip-cidr, 172.16.0.0/12, direct")
        sb.appendLine("ip-cidr, 192.168.0.0/16, direct")
        sb.appendLine("final, direct")
        sb.appendLine()

        // 6. [filter_remote]
        sb.appendLine("[filter_remote]")
        val seenUrls = mutableSetOf<String>()
        for (f in enabledFilterLists) {
            val rawUrl = f.originalUrl.ifBlank { f.url }.trim()
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                if (seenUrls.add(rawUrl)) {
                    val cleanTag = f.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
                    val tag = if (cleanTag.isNotBlank()) cleanTag else "filter_${f.id}"
                    val prefix = if (!f.isEnabled) "# " else ""
                    sb.appendLine("${prefix}$rawUrl, tag=$tag, update-interval=24")
                }
            }
        }
        if (seenUrls.isEmpty()) {
            sb.appendLine("# StevenBlack Unified & EasyPrivacy (App defaults)")
            sb.appendLine("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts, tag=StevenBlack, update-interval=24")
            sb.appendLine("https://easylist.to/easylist/easyprivacy.txt, tag=EasyPrivacy, update-interval=24")
        }

        return sb.toString()
    }

    private fun resolveDnsServers(providerId: String?, customIps: List<String>): List<String> {
        if (customIps.isNotEmpty()) {
            val filtered = customIps.filter { it.isNotBlank() }
            if (filtered.isNotEmpty()) return filtered
        }
        val provider = providerId?.let { DnsProviders.getById(it) } ?: DnsProviders.CLOUDFLARE
        val servers = mutableListOf<String>()
        if (provider.ipAddress.isNotBlank()) {
            servers.add(provider.ipAddress)
        }
        DnsProviders.getSecondaryIp(provider)?.let {
            if (it.isNotBlank()) servers.add(it)
        }
        if (servers.isEmpty()) {
            servers.add("1.1.1.1")
            servers.add("1.0.0.1")
        }
        return servers
    }
}
