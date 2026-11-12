package app.pwhs.blockadstv.companion

import app.pwhs.blockadstv.data.dao.CustomDnsRuleDao
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.dao.FirewallRuleDao
import app.pwhs.blockadstv.data.dao.WhitelistDomainDao
import app.pwhs.blockadstv.data.datastore.ProtectionPreferences
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.entities.CustomDnsRule
import app.pwhs.blockadstv.data.entities.FilterList
import app.pwhs.blockadstv.data.entities.FirewallRule
import app.pwhs.blockadstv.data.entities.RuleType
import app.pwhs.blockadstv.data.entities.WhitelistDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CompanionFilterListBackup(
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
)

@Serializable
data class CompanionFirewallRuleBackup(
    val packageName: String,
    val isEnabled: Boolean = true,
)

@Serializable
data class CompanionSettingsBackup(
    val version: Int = 1,
    val upstreamDns: String = TvPreferences.DEFAULT_UPSTREAM_DNS,
    val fallbackDns: String = TvPreferences.DEFAULT_FALLBACK_DNS,
    val autoReconnect: Boolean = true,
    val themeMode: String = TvPreferences.THEME_DARK,
    val appLanguage: String = TvPreferences.LANGUAGE_SYSTEM,
    val dnsResponseType: String = TvPreferences.DNS_RESPONSE_CUSTOM_IP,
    val safeSearchEnabled: Boolean = false,
    val youtubeRestrictedMode: Boolean = false,
    val filterLists: List<CompanionFilterListBackup> = emptyList(),
    val whitelistDomains: List<String> = emptyList(),
    val blocklistDomains: List<String> = emptyList(),
    val whitelistedApps: List<String> = emptyList(),
    val customRules: List<String> = emptyList(),
    val firewallRules: List<CompanionFirewallRuleBackup> = emptyList(),
)

class CompanionBackupHelper(
    private val tvPrefs: TvPreferences,
    private val protectionPrefs: ProtectionPreferences,
    private val filterListDao: FilterListDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val firewallRuleDao: FirewallRuleDao,
) {
    private val jsonPretty = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val backup = CompanionSettingsBackup(
            version = 1,
            upstreamDns = tvPrefs.upstreamDns.first(),
            fallbackDns = tvPrefs.fallbackDns.first(),
            autoReconnect = tvPrefs.autoReconnect.first(),
            themeMode = tvPrefs.themeMode.first(),
            appLanguage = tvPrefs.appLanguage.first(),
            dnsResponseType = tvPrefs.dnsResponseType.first(),
            safeSearchEnabled = protectionPrefs.safeSearchEnabled.first(),
            youtubeRestrictedMode = protectionPrefs.youtubeRestrictedMode.first(),
            filterLists = filterListDao.getEnabled().map {
                CompanionFilterListBackup(name = it.name, url = it.url, isEnabled = it.isEnabled)
            },
            whitelistDomains = whitelistDomainDao.getAllDomains(),
            blocklistDomains = customDnsRuleDao.getBlockDomains(),
            whitelistedApps = tvPrefs.getWhitelistedAppsSnapshot().toList(),
            customRules = customDnsRuleDao.getAllowDomains(),
            firewallRules = firewallRuleDao.getEnabledRules().map {
                CompanionFirewallRuleBackup(packageName = it.packageName, isEnabled = it.isEnabled)
            },
        )
        jsonPretty.encodeToString(CompanionSettingsBackup.serializer(), backup)
    }

    suspend fun importBackupJson(jsonStr: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = jsonLenient.decodeFromString(CompanionSettingsBackup.serializer(), jsonStr)
            tvPrefs.setUpstreamDns(backup.upstreamDns)
            tvPrefs.setFallbackDns(backup.fallbackDns)
            tvPrefs.setAutoReconnect(backup.autoReconnect)
            tvPrefs.setThemeMode(backup.themeMode)
            tvPrefs.setAppLanguage(backup.appLanguage)
            tvPrefs.setDnsResponseType(backup.dnsResponseType)
            protectionPrefs.setSafeSearchEnabled(backup.safeSearchEnabled)
            protectionPrefs.setYoutubeRestrictedMode(backup.youtubeRestrictedMode)

            for (pkg in backup.whitelistedApps) {
                if (pkg.isNotBlank()) tvPrefs.addWhitelistedApp(pkg.trim())
            }
            for (domain in backup.whitelistDomains) {
                val clean = domain.trim().lowercase()
                if (clean.isNotEmpty() && whitelistDomainDao.exists(clean) == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = clean))
                }
            }
            for (domain in backup.blocklistDomains) {
                val clean = domain.trim().lowercase()
                if (clean.isNotEmpty() && customDnsRuleDao.exists(clean, RuleType.BLOCK.name) == 0) {
                    customDnsRuleDao.insert(
                        CustomDnsRule(rule = clean, ruleType = RuleType.BLOCK, domain = clean)
                    )
                }
            }
            for (rule in backup.customRules) {
                val clean = rule.trim()
                if (clean.isNotEmpty() && customDnsRuleDao.exists(clean, RuleType.ALLOW.name) == 0) {
                    customDnsRuleDao.insert(
                        CustomDnsRule(rule = clean, ruleType = RuleType.ALLOW, domain = clean)
                    )
                }
            }
            for (fl in backup.filterLists) {
                if (fl.url.isBlank()) continue
                val existing = filterListDao.getByUrl(fl.url) ?: filterListDao.getByOriginalUrl(fl.url)
                if (existing != null) {
                    filterListDao.setEnabled(existing.id, fl.isEnabled)
                } else {
                    filterListDao.insert(FilterList(name = fl.name, url = fl.url, isEnabled = fl.isEnabled))
                }
            }
            for (fw in backup.firewallRules) {
                if (fw.packageName.isNotBlank() && firewallRuleDao.getByPackageName(fw.packageName) == null) {
                    firewallRuleDao.insert(FirewallRule(packageName = fw.packageName, isEnabled = fw.isEnabled))
                }
            }

            "Restored settings (${backup.filterLists.size} filters, ${backup.whitelistDomains.size} whitelist)"
        }
    }
}
