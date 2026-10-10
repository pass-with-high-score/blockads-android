package app.pwhs.blockads.utils

import android.content.Context
import android.net.Uri
import app.pwhs.blockads.data.dao.ConfigDao
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ConfigProfile
import app.pwhs.blockads.data.entities.ConfigProfileBackup
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.FilterListBackup
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.data.entities.FirewallRuleBackup
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.SettingsBackup
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.worker.DailySummaryScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SettingsBackupManager(
    private val context: Context,
    private val appPrefs: AppPreferences,
    private val filterListDao: FilterListDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val firewallRuleDao: FirewallRuleDao,
    private val configDao: ConfigDao,
    private val filterRepo: FilterListRepository,
) {

    private val jsonPretty = Json { prettyPrint = true }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val configProfiles = configDao.getAll().map { c ->
            ConfigProfileBackup(
                name = c.name,
                content = c.content,
                remoteUrl = c.remoteUrl,
                autoUpdate = c.autoUpdate,
                isActive = c.isActive,
                isBuiltIn = c.isBuiltIn,
                icon = c.icon
            )
        }

        val backup = SettingsBackup(
            upstreamDns = appPrefs.upstreamDns.first(),
            fallbackDns = appPrefs.fallbackDns.first(),
            autoReconnect = appPrefs.autoReconnect.first(),
            themeMode = appPrefs.themeMode.first(),
            appLanguage = appPrefs.appLanguage.first(),
            safeSearchEnabled = appPrefs.safeSearchEnabled.first(),
            youtubeRestrictedMode = appPrefs.youtubeRestrictedMode.first(),
            dailySummaryEnabled = appPrefs.dailySummaryEnabled.first(),
            milestoneNotificationsEnabled = appPrefs.milestoneNotificationsEnabled.first(),
            activeProfileType = "",
            firewallEnabled = appPrefs.firewallEnabled.first(),
            filterLists = filterListDao.getAllSync().map { f ->
                FilterListBackup(name = f.name, url = f.url, isEnabled = f.isEnabled)
            },
            whitelistDomains = whitelistDomainDao.getAllDomains()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct(),
            blocklistDomains = customDnsRuleDao.getBlockDomains()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct(),
            whitelistedApps = appPrefs.getWhitelistedAppsSnapshot().toList(),
            customRules = customDnsRuleDao.getAll().map { it.rule }.distinct(),
            firewallRules = firewallRuleDao.getEnabledRules().map { r ->
                FirewallRuleBackup(
                    packageName = r.packageName,
                    blockWifi = r.blockWifi,
                    blockMobileData = r.blockMobileData,
                    scheduleEnabled = r.scheduleEnabled,
                    scheduleStartHour = r.scheduleStartHour,
                    scheduleStartMinute = r.scheduleStartMinute,
                    scheduleEndHour = r.scheduleEndHour,
                    scheduleEndMinute = r.scheduleEndMinute,
                    isEnabled = r.isEnabled
                )
            },
            configProfiles = configProfiles
        )

        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(jsonPretty.encodeToString(SettingsBackup.serializer(), backup).toByteArray())
        } ?: throw IllegalStateException("Cannot open output stream for URI: $uri")
    }

    suspend fun importBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw IllegalStateException("Cannot read file from URI: $uri")

        val backup = jsonLenient.decodeFromString(SettingsBackup.serializer(), jsonStr)

        // 1. Preferences
        appPrefs.setUpstreamDns(backup.upstreamDns)
        appPrefs.setFallbackDns(backup.fallbackDns)
        appPrefs.setAutoReconnect(backup.autoReconnect)
        appPrefs.setThemeMode(backup.themeMode)
        appPrefs.setAppLanguage(backup.appLanguage)
        appPrefs.setSafeSearchEnabled(backup.safeSearchEnabled)
        appPrefs.setYoutubeRestrictedMode(backup.youtubeRestrictedMode)
        appPrefs.setDailySummaryEnabled(backup.dailySummaryEnabled)
        if (backup.dailySummaryEnabled) {
            DailySummaryScheduler.scheduleDailySummary(context)
        } else {
            DailySummaryScheduler.cancelDailySummary(context)
        }
        appPrefs.setMilestoneNotificationsEnabled(backup.milestoneNotificationsEnabled)
        appPrefs.setFirewallEnabled(backup.firewallEnabled)

        // 2. Filter lists — add new or update isEnabled
        backup.filterLists.forEach { f ->
            val existing = filterListDao.getByUrl(f.url)
            if (existing != null) {
                if (existing.isEnabled != f.isEnabled) {
                    filterListDao.setEnabled(existing.id, f.isEnabled)
                }
            } else {
                filterListDao.insert(FilterList(name = f.name, url = f.url, isEnabled = f.isEnabled))
            }
        }

        // 3. Whitelist domains
        backup.whitelistDomains.forEach { domain ->
            val clean = domain.trim().lowercase()
            if (clean.isNotBlank() && whitelistDomainDao.exists(clean) == 0) {
                whitelistDomainDao.insert(WhitelistDomain(domain = clean))
            }
        }

        // 4. Blocklist domains
        val existingRules = customDnsRuleDao.getAll().map { it.rule }.toSet()
        backup.blocklistDomains.forEach { domain ->
            val clean = domain.trim().lowercase()
            if (clean.isNotBlank()) {
                val ruleText = "||$clean^"
                if (ruleText !in existingRules && customDnsRuleDao.exists(ruleText) == 0) {
                    customDnsRuleDao.insert(
                        CustomDnsRule(
                            rule = ruleText,
                            ruleType = RuleType.BLOCK,
                            domain = clean,
                            isEnabled = true
                        )
                    )
                }
            }
        }

        // 5. Whitelisted apps
        val current = appPrefs.getWhitelistedAppsSnapshot()
        appPrefs.setWhitelistedApps(current + backup.whitelistedApps.toSet())

        // 6. Custom rules
        val updatedRules = customDnsRuleDao.getAll().map { it.rule }.toSet()
        backup.customRules.forEach { ruleText ->
            val trimmed = ruleText.trim()
            if (trimmed.isNotBlank() && trimmed !in updatedRules) {
                val rule = CustomRuleParser.parseRule(trimmed)
                if (rule != null) {
                    customDnsRuleDao.insert(rule)
                }
            }
        }

        // 7. Firewall rules
        backup.firewallRules.forEach { r ->
            if (firewallRuleDao.getByPackageName(r.packageName) == null) {
                firewallRuleDao.insert(
                    FirewallRule(
                        packageName = r.packageName,
                        blockWifi = r.blockWifi,
                        blockMobileData = r.blockMobileData,
                        scheduleEnabled = r.scheduleEnabled,
                        scheduleStartHour = r.scheduleStartHour,
                        scheduleStartMinute = r.scheduleStartMinute,
                        scheduleEndHour = r.scheduleEndHour,
                        scheduleEndMinute = r.scheduleEndMinute,
                        isEnabled = r.isEnabled
                    )
                )
            }
        }

        // 8. Config profiles
        if (backup.configProfiles.isNotEmpty()) {
            val existingProfiles = configDao.getAll()
            var activeProfileIdToSet: Long? = null

            for (p in backup.configProfiles) {
                val cleanedContent = ConfigRuleHelper.stripUnsupportedSections(p.content)
                val existing = existingProfiles.firstOrNull { it.name.equals(p.name, ignoreCase = true) }
                if (existing != null) {
                    configDao.update(
                        existing.copy(
                            content = cleanedContent,
                            remoteUrl = p.remoteUrl,
                            autoUpdate = p.autoUpdate,
                            icon = p.icon ?: existing.icon
                        )
                    )
                    if (p.isActive) {
                        activeProfileIdToSet = existing.id
                    }
                } else {
                    val newId = configDao.insert(
                        ConfigProfile(
                            name = p.name,
                            content = cleanedContent,
                            remoteUrl = p.remoteUrl,
                            autoUpdate = p.autoUpdate,
                            isActive = p.isActive,
                            isBuiltIn = p.isBuiltIn,
                            icon = p.icon
                        )
                    )
                    if (p.isActive) {
                        activeProfileIdToSet = newId
                    }
                }
            }

            if (activeProfileIdToSet != null) {
                configDao.setActive(activeProfileIdToSet)
            }
        } else {
            // Backward-compatible: If backup lacked profiles, sync active profile from restored settings
            val active = configDao.getActive() ?: configDao.getAll().firstOrNull()
            if (active != null) {
                val migratedContent = ProfileMigrationHelper.generateProfileFromCurrentSettings(
                    selectedDnsProviderId = appPrefs.dnsProviderId.first(),
                    customDnsIps = listOf(backup.upstreamDns, backup.fallbackDns),
                    customRules = customDnsRuleDao.getAll(),
                    whitelistDomains = whitelistDomainDao.getAll().first(),
                    enabledFilterLists = filterListDao.getEnabled()
                )
                configDao.update(active.copy(content = migratedContent))
            }
        }

        // 9. Reload repository in-memory caches
        filterRepo.loadWhitelist()
        filterRepo.loadCustomRules()
    }
}
