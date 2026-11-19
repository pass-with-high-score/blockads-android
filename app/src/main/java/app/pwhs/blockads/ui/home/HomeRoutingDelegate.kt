package app.pwhs.blockads.ui.home

import android.content.Context
import android.content.Intent
import android.os.Build
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.ShadowsocksProfile
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.IptablesManager
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.ui.home.component.ActiveFeatureBadge
import app.pwhs.blockads.ui.home.component.FeatureBadgeKind
import app.pwhs.blockads.utils.CustomRuleParser
import app.pwhs.blockads.utils.configIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Handles domain rule mutations and routing-mode transitions outside HomeViewModel
 * so the ViewModel stays focused on UI state and under the file-size ceiling.
 */
internal class HomeRoutingDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appPrefs: AppPreferences,
    private val filterRepo: FilterListRepository,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val events: MutableSharedFlow<UiEvent>,
) {
    fun addToWhitelist(domain: String) {
        scope.launch {
            val cleanDomain = domain.trim().lowercase()
            if (whitelistDomainDao.exists(cleanDomain) == 0) {
                whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                filterRepo.loadWhitelist()
                events.toast(R.string.log_whitelisted, listOf(": $cleanDomain"))
            } else {
                events.toast(R.string.log_already_whitelisted)
            }
        }
    }

    fun removeFromWhitelist(domain: String) {
        scope.launch {
            val cleanDomain = domain.trim().lowercase()
            whitelistDomainDao.deleteByDomain(cleanDomain)
            filterRepo.loadWhitelist()
            events.toast(R.string.whitelist_domain_removed)
        }
    }

    fun addWildcardWhitelist(domain: String) {
        scope.launch {
            val cleanDomain = domain.trim().lowercase()
            val rules = listOf("@@||$cleanDomain^", "@@||*.$cleanDomain^")
            var addedAny = false
            for (ruleText in rules) {
                if (customDnsRuleDao.exists(ruleText) == 0) {
                    val parsed = CustomRuleParser.parseRule(ruleText)
                    if (parsed != null) {
                        customDnsRuleDao.insert(parsed)
                        addedAny = true
                    }
                }
            }
            if (addedAny) {
                filterRepo.loadCustomRules()
            }
            events.toast(R.string.log_wildcard_whitelisted, listOf(cleanDomain))
        }
    }

    fun addToCustomBlockRules(domain: String) {
        scope.launch {
            val cleanDomain = domain.trim().lowercase()
            val rule = CustomRuleParser.parseRule(CustomRuleParser.formatBlockRule(cleanDomain)) ?: return@launch
            if (customDnsRuleDao.exists(rule.rule) == 0) {
                customDnsRuleDao.insert(rule)
                filterRepo.loadCustomRules()
            }
            events.toast(R.string.rule_added)
        }
    }

    fun setRoutingMode(mode: String) {
        scope.launch(Dispatchers.IO) {
            val oldMode = appPrefs.routingMode.first()
            if (oldMode == mode) return@launch
            if (!validateRoutingMode(mode)) return@launch

            appPrefs.setRoutingMode(mode)

            val isRunning = AdBlockVpnService.isRunning || RootProxyService.isRunning
            if (!isRunning) return@launch

            applyLiveRoutingTransition(oldMode = oldMode, newMode = mode)
        }
    }

    private suspend fun validateRoutingMode(mode: String): Boolean {
        if (mode == AppPreferences.ROUTING_MODE_ROOT && !IptablesManager.isRootAvailable()) {
            events.toast(R.string.root_not_available)
            return false
        }

        if (mode == AppPreferences.ROUTING_MODE_PROXY_VPN || mode == AppPreferences.ROUTING_MODE_WIREGUARD) {
            if (!validateProxyOrWireGuardMode(mode)) return false
            disableHttpsFilteringWithNotice()
        }

        if (mode == AppPreferences.ROUTING_MODE_SHADOWSOCKS) {
            if (appPrefs.getSsProfileSnapshot() == null) {
                events.toast(R.string.routing_mode_no_ss_profile)
                return false
            }
            disableHttpsFilteringWithNotice()
        }

        return true
    }

    private suspend fun validateProxyOrWireGuardMode(mode: String): Boolean {
        var activeType = appPrefs.getActiveProxyTypeSnapshot()
        val activeWg = appPrefs.getActiveWgProfileSnapshot()
        val activeProxy = appPrefs.getActiveProxyProfileSnapshot() ?: appPrefs.getSsProfileSnapshot()

        if (activeType == AppPreferences.PROXY_TYPE_WIREGUARD && activeWg == null && activeProxy != null) {
            activeType = AppPreferences.PROXY_TYPE_SHADOWSOCKS
            appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_SHADOWSOCKS)
        } else if (activeType != AppPreferences.PROXY_TYPE_WIREGUARD && activeProxy == null && activeWg != null) {
            activeType = AppPreferences.PROXY_TYPE_WIREGUARD
            appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_WIREGUARD)
        }

        if (activeType == AppPreferences.PROXY_TYPE_WIREGUARD || mode == AppPreferences.ROUTING_MODE_WIREGUARD) {
            if (activeWg == null) {
                events.toast(R.string.routing_mode_no_proxy_profile)
                return false
            }
            activeWg.configIssue?.let { issue ->
                events.toast(R.string.wireguard_issue_fix_first, listOf(activeWg.name, issue))
                return false
            }
        } else if (activeProxy == null) {
            events.toast(R.string.routing_mode_no_proxy_profile)
            return false
        }
        return true
    }

    private suspend fun disableHttpsFilteringWithNotice() {
        if (appPrefs.getHttpsFilteringEnabledSnapshot()) {
            appPrefs.setHttpsFilteringEnabled(false)
            events.toast(R.string.wireguard_https_filtering_disabled)
        }
    }

    private suspend fun applyLiveRoutingTransition(oldMode: String, newMode: String) {
        val oldWasRoot = oldMode == AppPreferences.ROUTING_MODE_ROOT
        val newIsRoot = newMode == AppPreferences.ROUTING_MODE_ROOT
        if (oldWasRoot == newIsRoot) {
            ServiceController.requestRestart(context)
            return
        }

        if (newIsRoot) {
            val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
            context.startService(stopIntent)
            delay(800)
            RootProxyService.start(context)
        } else {
            RootProxyService.stop(context)
            delay(800)
            val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    fun saveShadowsocksUri(uri: String): Result<ShadowsocksProfile> {
        return try {
            val json = tunnel.Tunnel.parseShadowsocksURI(uri)
            val profile = ShadowsocksProfile.fromJson(json).copy(rawUri = uri)
            scope.launch(Dispatchers.IO) {
                disableHttpsFilteringWithNotice()
                appPrefs.setSsProfile(profile)
                appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_SHADOWSOCKS)
                ServiceController.requestRestart(context)
            }
            Result.success(profile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse and save Shadowsocks URI")
            Result.failure(e)
        }
    }
}

internal fun buildActiveFeatureBadges(
    protocol: DnsProtocol,
    httpsEnabled: Boolean,
    firewallEnabled: Boolean,
    tailscaleEnabled: Boolean,
    mode: String,
    rulesCount: Int,
): List<ActiveFeatureBadge> = buildList {
    val dnsLabel = when (protocol) {
        DnsProtocol.DOH -> "DoH"
        DnsProtocol.DOT -> "DoT"
        DnsProtocol.DOQ -> "DoQ"
        else -> "DNS"
    }
    add(ActiveFeatureBadge(FeatureBadgeKind.DNS_PROTOCOL, dnsLabel))
    if (httpsEnabled) add(ActiveFeatureBadge(FeatureBadgeKind.HTTPS_FILTER, "HTTPS"))
    if (firewallEnabled) add(ActiveFeatureBadge(FeatureBadgeKind.FIREWALL, "Firewall"))
    if (tailscaleEnabled) add(ActiveFeatureBadge(FeatureBadgeKind.TAILSCALE, "Tailscale"))
    when (mode) {
        AppPreferences.ROUTING_MODE_WIREGUARD ->
            add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "WireGuard"))
        AppPreferences.ROUTING_MODE_SHADOWSOCKS ->
            add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "Shadowsocks"))
        AppPreferences.ROUTING_MODE_PROXY_VPN ->
            add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "Proxy"))
        AppPreferences.ROUTING_MODE_ROOT ->
            add(ActiveFeatureBadge(FeatureBadgeKind.TUNNEL_MODE, "Root"))
    }
    if (rulesCount > 0) {
        val formatted = if (rulesCount >= 1000) "${rulesCount / 1000}K" else "$rulesCount"
        add(ActiveFeatureBadge(FeatureBadgeKind.FILTER_RULES, formatted))
    }
}.take(4)
