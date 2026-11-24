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

    private val jsonPretty = Json {
        prettyPrint = true
        encodeDefaults = false
    }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val currentDnsId = appPrefs.dnsProviderId.first()
        val currentUpstream = appPrefs.upstreamDns.first()
        val currentFallback = appPrefs.fallbackDns.first()
        val customRules = customDnsRuleDao.getAll()
        val whitelistDomains = whitelistDomainDao.getAll().first()
        val filterLists = filterListDao.getEnabled()
        val whitelistedApps = appPrefs.getWhitelistedAppsSnapshot()
        val firewallRules = firewallRuleDao.getEnabledRules()
        val autoReconnect = appPrefs.autoReconnect.first()
        val safeSearch = appPrefs.safeSearchEnabled.first()
        val youtubeRestricted = appPrefs.youtubeRestrictedMode.first()
        val firewallEnabled = appPrefs.firewallEnabled.first()
        val dailySummary = appPrefs.dailySummaryEnabled.first()
        val milestoneNotifs = appPrefs.milestoneNotificationsEnabled.first()
        val themeMode = appPrefs.themeMode.first()
        val appLanguage = appPrefs.appLanguage.first()

        val fullActiveContent = ProfileUnifiedConfigHelper.generateFullProfile(
            selectedDnsProviderId = currentDnsId,
            customDnsIps = listOf(currentUpstream, currentFallback),
            customRules = customRules,
            whitelistDomains = whitelistDomains,
            enabledFilterLists = filterLists,
            whitelistedApps = whitelistedApps,
            firewallRules = firewallRules,
            autoReconnect = autoReconnect,
            safeSearchEnabled = safeSearch,
            youtubeRestrictedMode = youtubeRestricted,
            firewallEnabled = firewallEnabled,
            dailySummaryEnabled = dailySummary,
            milestoneNotificationsEnabled = milestoneNotifs,
            themeMode = themeMode,
            appLanguage = appLanguage
        )

        val existingProfiles = configDao.getAll()
        val configProfiles = if (existingProfiles.isEmpty()) {
            listOf(
                ConfigProfileBackup(
                    name = ConfigProfile.DEFAULT_NAME,
                    content = fullActiveContent,
                    isActive = true,
                    isBuiltIn = true
                )
            )
        } else {
            val active = configDao.getActive() ?: existingProfiles.first()
            existingProfiles.map { c ->
                ConfigProfileBackup(
                    name = c.name,
                    content = if (c.id == active.id) fullActiveContent else c.content,
                    remoteUrl = c.remoteUrl,
                    autoUpdate = c.autoUpdate,
                    isActive = c.isActive,
                    isBuiltIn = c.isBuiltIn,
                    icon = c.icon
                )
            }
        }

        val backup = SettingsBackup(
            version = 2,
            configProfiles = configProfiles
        )

        ExportHelper.openOutputStream(context, uri)?.use { out ->
            out.write(jsonPretty.encodeToString(SettingsBackup.serializer(), backup).toByteArray())
        } ?: throw IllegalStateException("Cannot open output stream for URI: $uri")
    }

    suspend fun importBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw IllegalStateException("Cannot read file from URI: $uri")

        val backup = jsonLenient.decodeFromString(SettingsBackup.serializer(), jsonStr)

        // 1. Config profiles — restore all profiles and apply active profile's .conf
        if (backup.configProfiles.isNotEmpty()) {
            val existingProfiles = configDao.getAll()
            var activeProfileIdToSet: Long? = null
            var activeProfileContent: String? = null

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
                        activeProfileContent = cleanedContent
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
                        activeProfileContent = cleanedContent
                    }
                }
            }

            if (activeProfileIdToSet != null) {
                configDao.setActive(activeProfileIdToSet)
            } else {
                val firstProfile = configDao.getAll().firstOrNull()
                if (firstProfile != null) {
                    configDao.setActive(firstProfile.id)
                    activeProfileContent = firstProfile.content
                }
            }

            if (activeProfileContent != null) {
                ProfileUnifiedConfigHelper.applyProfileToSystem(
                    content = activeProfileContent,
                    appPrefs = appPrefs,
                    whitelistDomainDao = whitelistDomainDao,
                    customDnsRuleDao = customDnsRuleDao,
                    filterListDao = filterListDao,
                    firewallRuleDao = firewallRuleDao,
                    context = context
                )
            }
        }

        // 2. Backward-compatible fallback for legacy v1 backups
        if (backup.version == 1 || backup.configProfiles.isEmpty()) {
            restoreLegacySettings(backup)
            if (backup.configProfiles.isEmpty()) {
                val active = configDao.getActive() ?: configDao.getAll().firstOrNull()
                if (active != null) {
                    val migratedContent = ProfileUnifiedConfigHelper.generateFullProfile(
                        selectedDnsProviderId = appPrefs.dnsProviderId.first(),
                        customDnsIps = listOf(backup.upstreamDns, backup.fallbackDns),
                        customRules = customDnsRuleDao.getAll(),
                        whitelistDomains = whitelistDomainDao.getAll().first(),
                        enabledFilterLists = filterListDao.getEnabled(),
                        whitelistedApps = appPrefs.getWhitelistedAppsSnapshot(),
                        firewallRules = firewallRuleDao.getEnabledRules()
                    )
                    configDao.update(active.copy(content = migratedContent))
                }
            }
        }

        // 3. Reload repository in-memory caches
        filterRepo.loadWhitelist()
        filterRepo.loadCustomRules()
    }

    private suspend fun restoreLegacySettings(backup: SettingsBackup) {
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

        backup.whitelistDomains.forEach { domain ->
            val clean = domain.trim().lowercase()
            if (clean.isNotBlank() && whitelistDomainDao.exists(clean) == 0) {
                whitelistDomainDao.insert(WhitelistDomain(domain = clean))
            }
        }

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

        val current = appPrefs.getWhitelistedAppsSnapshot()
        appPrefs.setWhitelistedApps(current + backup.whitelistedApps.toSet())

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
    }
}
