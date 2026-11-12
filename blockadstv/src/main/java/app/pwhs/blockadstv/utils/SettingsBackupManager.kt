package app.pwhs.blockadstv.utils

import android.content.Context
import android.net.Uri
import app.pwhs.blockadstv.data.dao.ConfigDao
import app.pwhs.blockadstv.data.dao.CustomDnsRuleDao
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.dao.FirewallRuleDao
import app.pwhs.blockadstv.data.dao.WhitelistDomainDao
import app.pwhs.blockadstv.data.datastore.ProtectionPreferences
import app.pwhs.blockadstv.data.datastore.RulesPreferences
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.datastore.WireGuardPreferences
import app.pwhs.blockadstv.data.entities.ConfigProfile
import app.pwhs.blockadstv.data.entities.CustomDnsRule
import app.pwhs.blockadstv.data.entities.DnsProtocol
import app.pwhs.blockadstv.data.entities.FilterList
import app.pwhs.blockadstv.data.entities.FirewallRule
import app.pwhs.blockadstv.data.entities.ProxyProfileList
import app.pwhs.blockadstv.data.entities.ProxySubscriptionList
import app.pwhs.blockadstv.data.entities.RuleType
import app.pwhs.blockadstv.data.entities.TvConfigProfileBackup
import app.pwhs.blockadstv.data.entities.TvCustomRuleBackup
import app.pwhs.blockadstv.data.entities.TvFilterListBackup
import app.pwhs.blockadstv.data.entities.TvFirewallRuleBackup
import app.pwhs.blockadstv.data.entities.TvSettingsBackup
import app.pwhs.blockadstv.data.entities.WhitelistDomain
import app.pwhs.blockadstv.data.entities.WireGuardProfileList
import app.pwhs.blockadstv.data.repository.FilterListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SettingsBackupManager(
    private val context: Context,
    private val tvPrefs: TvPreferences,
    private val protectionPrefs: ProtectionPreferences,
    private val rulesPrefs: RulesPreferences,
    private val wgPrefs: WireGuardPreferences,
    private val filterListDao: FilterListDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val firewallRuleDao: FirewallRuleDao,
    private val configDao: ConfigDao,
    private val filterRepo: FilterListRepository,
) {

    private val jsonPretty = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val jsonLenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun exportBackupToJson(): String = withContext(Dispatchers.IO) {
        val wgProfiles = wgPrefs.getWgProfilesSnapshot()
        val proxyProfiles = tvPrefs.getProxyProfilesSnapshot()
        val proxySubs = tvPrefs.getProxySubscriptionsSnapshot()

        val backup = TvSettingsBackup(
            version = 2,
            upstreamDns = tvPrefs.upstreamDns.first(),
            fallbackDns = tvPrefs.fallbackDns.first(),
            dnsProtocol = tvPrefs.dnsProtocol.first().name,
            dohUrl = tvPrefs.dohUrl.first(),
            odohRelayUrl = tvPrefs.odohRelayUrl.first(),
            isCustomDns = tvPrefs.isCustomDns.first(),
            customDnsEndpoint = tvPrefs.customDnsEndpoint.first(),
            dnsResponseType = tvPrefs.dnsResponseType.first(),
            autoReconnect = tvPrefs.autoReconnect.first(),
            themeMode = tvPrefs.themeMode.first(),
            appLanguage = tvPrefs.appLanguage.first(),
            startOnBoot = tvPrefs.startOnBoot.first(),
            excludeLan = tvPrefs.excludeLan.first(),
            preferredIpType = tvPrefs.preferredIPType.first(),
            recordDnsLogs = tvPrefs.recordDnsLogs.first(),
            blockDohBypass = tvPrefs.blockDohBypass.first(),
            blockWebRtcLeak = tvPrefs.blockWebRtcLeak.first(),
            filterHttp3 = tvPrefs.filterHttp3.first(),
            routingMode = tvPrefs.routingMode.first(),
            whitelistedApps = tvPrefs.getWhitelistedAppsSnapshot().toList(),
            safeSearchEnabled = protectionPrefs.getSafeSearchEnabledSnapshot(),
            youtubeRestrictedMode = protectionPrefs.getYoutubeRestrictedModeSnapshot(),
            trustedNetworks = protectionPrefs.getTrustedNetworksSnapshot().toList(),
            pauseOnTrustedEnabled = protectionPrefs.getPauseOnTrustedEnabledSnapshot(),
            useSystemDnsOnTrustedEnabled = protectionPrefs.getUseSystemDnsOnTrustedEnabledSnapshot(),
            ruleManifestEnabled = rulesPrefs.ruleManifestEnabled.first(),
            modulesEnabled = rulesPrefs.modulesEnabled.first(),
            rulesetEnabled = rulesPrefs.rulesetEnabled.first(),
            wgProfilesJson = if (wgProfiles.isNotEmpty()) WireGuardProfileList(wgProfiles).toJson() else "",
            wgActiveProfileId = wgPrefs.wgActiveProfileId.first(),
            geoIpAutoUpdate = wgPrefs.geoIpAutoUpdate.first(),
            geoIpCustomUrl = wgPrefs.geoIpCustomUrl.first(),
            filterLists = filterListDao.getAllSync().map {
                TvFilterListBackup(
                    name = it.name,
                    url = it.url,
                    isEnabled = it.isEnabled,
                    isBuiltIn = it.isBuiltIn,
                    category = it.category,
                )
            },
            whitelistDomains = whitelistDomainDao.getAllDomains(),
            customRules = customDnsRuleDao.getAllFlow().first().map {
                TvCustomRuleBackup(
                    rule = it.rule,
                    ruleType = it.ruleType.name,
                    domain = it.domain,
                    isEnabled = it.isEnabled,
                )
            },
            firewallRules = firewallRuleDao.getEnabledRules().map {
                TvFirewallRuleBackup(
                    packageName = it.packageName,
                    isEnabled = it.isEnabled,
                )
            },
            configProfiles = configDao.getAll().map {
                TvConfigProfileBackup(
                    name = it.name,
                    content = it.content,
                    remoteUrl = it.remoteUrl,
                    autoUpdate = it.autoUpdate,
                    isActive = it.isActive,
                    isBuiltIn = it.isBuiltIn,
                    icon = it.icon,
                )
            },
            proxyProfilesJson = if (proxyProfiles.isNotEmpty()) ProxyProfileList(proxyProfiles).toJson() else "",
            proxySubscriptionsJson = if (proxySubs.isNotEmpty()) ProxySubscriptionList(proxySubs).toJson() else "",
        )

        jsonPretty.encodeToString(TvSettingsBackup.serializer(), backup)
    }

    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val jsonStr = exportBackupToJson()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(jsonStr.toByteArray())
        } ?: throw IllegalStateException("Cannot open output stream for URI: $uri")
    }

    suspend fun importBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw IllegalStateException("Cannot read file from URI: $uri")
        importBackupFromJson(jsonStr)
    }

    suspend fun importBackupFromJson(jsonStr: String) = withContext(Dispatchers.IO) {
        val backup = jsonLenient.decodeFromString(TvSettingsBackup.serializer(), jsonStr)

        restoreTvPreferences(backup)
        restoreProtectionAndRulesPreferences(backup)
        restoreWireGuardAndProxy(backup)
        restoreDatabaseEntities(backup)

        filterRepo.forceReloadAllFilters()
    }

    private suspend fun restoreTvPreferences(backup: TvSettingsBackup) {
        tvPrefs.setUpstreamDns(backup.upstreamDns)
        tvPrefs.setFallbackDns(backup.fallbackDns)
        runCatching { DnsProtocol.valueOf(backup.dnsProtocol) }.getOrNull()?.let {
            tvPrefs.setDnsProtocol(it)
        }
        tvPrefs.setDohUrl(backup.dohUrl)
        tvPrefs.setOdohRelayUrl(backup.odohRelayUrl)
        tvPrefs.setIsCustomDns(backup.isCustomDns)
        tvPrefs.setCustomDnsEndpoint(backup.customDnsEndpoint)
        tvPrefs.setDnsResponseType(backup.dnsResponseType)
        tvPrefs.setAutoReconnect(backup.autoReconnect)
        tvPrefs.setThemeMode(backup.themeMode)
        tvPrefs.setAppLanguage(backup.appLanguage)
        tvPrefs.setStartOnBoot(backup.startOnBoot)
        tvPrefs.setExcludeLan(backup.excludeLan)
        tvPrefs.setPreferredIPType(backup.preferredIpType)
        tvPrefs.setRecordDnsLogs(backup.recordDnsLogs)
        tvPrefs.setBlockDohBypass(backup.blockDohBypass)
        tvPrefs.setBlockWebRtcLeak(backup.blockWebRtcLeak)
        tvPrefs.setFilterHttp3(backup.filterHttp3)
        tvPrefs.setRoutingMode(backup.routingMode)

        val currentApps = tvPrefs.getWhitelistedAppsSnapshot()
        backup.whitelistedApps.forEach { pkg ->
            val clean = pkg.trim()
            if (clean.isNotEmpty() && clean !in currentApps) {
                tvPrefs.toggleWhitelistedApp(clean)
            }
        }
    }

    private suspend fun restoreProtectionAndRulesPreferences(backup: TvSettingsBackup) {
        protectionPrefs.setSafeSearchEnabled(backup.safeSearchEnabled)
        protectionPrefs.setYoutubeRestrictedMode(backup.youtubeRestrictedMode)
        if (backup.trustedNetworks.isNotEmpty()) {
            val merged = protectionPrefs.getTrustedNetworksSnapshot() + backup.trustedNetworks
            protectionPrefs.setTrustedNetworks(merged)
        }
        protectionPrefs.setPauseOnTrustedEnabled(backup.pauseOnTrustedEnabled)
        protectionPrefs.setUseSystemDnsOnTrustedEnabled(backup.useSystemDnsOnTrustedEnabled)

        rulesPrefs.setRuleManifestEnabled(backup.ruleManifestEnabled)
        rulesPrefs.setModulesEnabled(backup.modulesEnabled)
        rulesPrefs.setRulesetEnabled(backup.rulesetEnabled)
    }

    private suspend fun restoreWireGuardAndProxy(backup: TvSettingsBackup) {
        if (backup.wgProfilesJson.isNotBlank()) {
            val profiles = WireGuardProfileList.fromJson(backup.wgProfilesJson).profiles
            profiles.forEach { profile ->
                wgPrefs.addOrUpdateWgProfile(
                    profile = profile,
                    makeActive = profile.id == backup.wgActiveProfileId,
                )
            }
        }
        wgPrefs.setGeoIpAutoUpdate(backup.geoIpAutoUpdate)
        wgPrefs.setGeoIpCustomUrl(backup.geoIpCustomUrl)

        if (backup.proxyProfilesJson.isNotBlank()) {
            val existingIds = tvPrefs.getProxyProfilesSnapshot().map { it.id }.toSet()
            ProxyProfileList.fromJson(backup.proxyProfilesJson).profiles.forEach { profile ->
                if (profile.id !in existingIds) {
                    tvPrefs.addProxyProfile(profile)
                }
            }
        }
    }

    private suspend fun restoreDatabaseEntities(backup: TvSettingsBackup) {
        backup.filterLists.forEach { f ->
            val existing = filterListDao.getByUrl(f.url)
            if (existing != null) {
                if (existing.isEnabled != f.isEnabled) {
                    filterListDao.setEnabled(existing.id, f.isEnabled)
                }
            } else {
                filterListDao.insert(
                    FilterList(
                        name = f.name,
                        url = f.url,
                        isEnabled = f.isEnabled,
                        isBuiltIn = f.isBuiltIn,
                        category = f.category,
                    )
                )
            }
        }

        backup.whitelistDomains.forEach { domain ->
            val clean = domain.trim().lowercase()
            if (clean.isNotBlank() && whitelistDomainDao.exists(clean) == 0) {
                whitelistDomainDao.insert(WhitelistDomain(domain = clean))
            }
        }

        backup.customRules.forEach { r ->
            val cleanDomain = r.domain.trim().lowercase()
            val ruleType = runCatching { RuleType.valueOf(r.ruleType) }.getOrDefault(RuleType.BLOCK)
            if (cleanDomain.isNotBlank() && customDnsRuleDao.exists(cleanDomain, ruleType.name) == 0) {
                customDnsRuleDao.insert(
                    CustomDnsRule(
                        rule = r.rule,
                        ruleType = ruleType,
                        domain = cleanDomain,
                        isEnabled = r.isEnabled,
                    )
                )
            }
        }

        backup.firewallRules.forEach { r ->
            if (firewallRuleDao.getByPackageName(r.packageName) == null) {
                firewallRuleDao.insert(
                    FirewallRule(
                        packageName = r.packageName,
                        isEnabled = r.isEnabled,
                    )
                )
            }
        }

        if (backup.configProfiles.isNotEmpty()) {
            val existingProfiles = configDao.getAll()
            var activeIdToSet: Long? = null
            for (p in backup.configProfiles) {
                val existing = existingProfiles.firstOrNull { it.name.equals(p.name, ignoreCase = true) }
                if (existing != null) {
                    configDao.update(
                        existing.copy(
                            content = p.content,
                            remoteUrl = p.remoteUrl,
                            autoUpdate = p.autoUpdate,
                            icon = p.icon ?: existing.icon,
                        )
                    )
                    if (p.isActive) activeIdToSet = existing.id
                } else {
                    val newId = configDao.insert(
                        ConfigProfile(
                            name = p.name,
                            content = p.content,
                            remoteUrl = p.remoteUrl,
                            autoUpdate = p.autoUpdate,
                            isActive = p.isActive,
                            isBuiltIn = p.isBuiltIn,
                            icon = p.icon,
                        )
                    )
                    if (p.isActive) activeIdToSet = newId
                }
            }
            activeIdToSet?.let { configDao.setActive(it) }
        }
    }
}
