package app.pwhs.blockads.ui.domainrules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.model.SniProxyRule
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.utils.ParsedFilterRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DomainRulesViewModel(
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val filterRepo: FilterListRepository,
    appPrefs: AppPreferences,
    application: Application
) : AndroidViewModel(application) {

    private val sniProxyDelegate = SniProxyRulesDelegate(appPrefs, application, viewModelScope)
    val sniProxyRules: StateFlow<List<SniProxyRule>> = sniProxyDelegate.rules
    val installedApps: StateFlow<List<InstalledAppOption>> = sniProxyDelegate.installedApps

    val whitelistDomains: StateFlow<List<WhitelistDomain>> = whitelistDomainDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blocklistDomains: StateFlow<List<CustomDnsRule>> = customDnsRuleDao.getAllFlow()
        .map { rules -> rules.filter { it.ruleType == RuleType.BLOCK } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profileFilterRules: StateFlow<List<ParsedFilterRule>> = combine(
        whitelistDomainDao.getAll(),
        customDnsRuleDao.getAllFlow()
    ) { whitelists, customRules ->
        val whiteItems = whitelists.map { w ->
            ParsedFilterRule(
                id = "whitelist:${w.id}",
                type = "HOST",
                param = w.domain,
                policy = "DIRECT",
                isEnabled = w.isEnabled,
                rawLine = "whitelist:${w.id}"
            )
        }
        val customItems = customRules
            .filter { it.ruleType != RuleType.COMMENT }
            .map { r -> toParsedFilterRule(r) }
        whiteItems + customItems
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _undoRemovedRule = MutableSharedFlow<ParsedFilterRule>(extraBufferCapacity = 1)
    val undoRemovedRule: SharedFlow<ParsedFilterRule> = _undoRemovedRule.asSharedFlow()

    private fun sanitizeDomain(input: String): String {
        var d = input.trim().lowercase()
        if (d.startsWith("http://")) d = d.removePrefix("http://")
        if (d.startsWith("https://")) d = d.removePrefix("https://")
        val slash = d.indexOf('/')
        if (slash != -1) d = d.substring(0, slash)
        val colon = d.indexOf(':')
        if (colon != -1) d = d.substring(0, colon)
        return d.trim()
    }

    private fun toParsedFilterRule(rule: CustomDnsRule): ParsedFilterRule {
        val parts = rule.rule.split(",").map { it.trim() }
        val defaultPolicy = if (rule.ruleType == RuleType.ALLOW) "DIRECT" else "REJECT"
        val (type, param, policy) = when {
            parts.size >= 3 -> Triple(parts[0].uppercase(), parts[1], parts[2].uppercase())
            parts.size == 2 && parts[0].equals("FINAL", ignoreCase = true) ->
                Triple("FINAL", "", parts[1].uppercase())
            rule.rule.startsWith("||") && rule.rule.endsWith("^") ->
                Triple("HOST-SUFFIX", rule.domain, defaultPolicy)
            rule.rule.startsWith("@@||") && rule.rule.endsWith("^") ->
                Triple("HOST-SUFFIX", rule.domain, "DIRECT")
            else -> Triple("HOST", rule.domain, defaultPolicy)
        }
        return ParsedFilterRule(
            id = "custom:${rule.id}",
            type = type,
            param = param,
            policy = policy,
            isEnabled = rule.isEnabled,
            rawLine = "custom:${rule.id}",
            packageName = rule.packageName
        )
    }

    private fun formatRuleLine(type: String, param: String, policy: String): String {
        return if (type.equals("FINAL", ignoreCase = true)) {
            "final, ${policy.lowercase()}"
        } else {
            "${type.lowercase()}, $param, ${policy.lowercase()}"
        }
    }

    // ── Whitelist ────────────────────────────────────────────

    fun addWhitelistDomain(domain: String) {
        viewModelScope.launch {
            val cleanDomain = sanitizeDomain(domain)
            if (cleanDomain.isNotBlank()) {
                val exists = whitelistDomainDao.exists(cleanDomain)
                if (exists == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                    filterRepo.loadWhitelist()
                    _events.toast(R.string.whitelist_domain_added, listOf(cleanDomain))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.filter_domain_already_whitelisted)
                }
            }
        }
    }

    fun updateWhitelistDomain(oldDomain: WhitelistDomain, newDomain: String) {
        viewModelScope.launch {
            val clean = sanitizeDomain(newDomain)
            if (clean.isNotBlank() && clean != oldDomain.domain.lowercase()) {
                val exists = whitelistDomainDao.exists(clean)
                if (exists == 0) {
                    whitelistDomainDao.update(oldDomain.copy(domain = clean))
                    filterRepo.loadWhitelist()
                    _events.toast(R.string.whitelist_domain_added, listOf(clean))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.filter_domain_already_whitelisted)
                }
            }
        }
    }

    fun toggleWhitelistDomain(domain: WhitelistDomain) {
        viewModelScope.launch {
            whitelistDomainDao.update(domain.copy(isEnabled = !domain.isEnabled))
            filterRepo.loadWhitelist()
            requestVpnRestart()
        }
    }

    fun removeWhitelistDomain(domain: WhitelistDomain) {
        viewModelScope.launch {
            whitelistDomainDao.delete(domain)
            filterRepo.loadWhitelist()
            _events.toast(R.string.whitelist_domain_removed)
            requestVpnRestart()
        }
    }

    // ── Blocklist ────────────────────────────────────────────

    fun addBlocklistDomain(domain: String) {
        viewModelScope.launch {
            val cleanDomain = sanitizeDomain(domain)
            if (cleanDomain.isNotBlank()) {
                val allRules = customDnsRuleDao.getAll()
                val exists = allRules.any {
                    it.ruleType == RuleType.BLOCK && it.domain.equals(cleanDomain, ignoreCase = true)
                }
                if (!exists) {
                    customDnsRuleDao.insert(
                        CustomDnsRule(
                            rule = "||$cleanDomain^",
                            ruleType = RuleType.BLOCK,
                            domain = cleanDomain
                        )
                    )
                    filterRepo.loadCustomRules()
                    _events.toast(R.string.blocklist_domain_added, listOf(cleanDomain))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.blocklist_domain_already_exists)
                }
            }
        }
    }

    fun updateBlocklistDomain(oldRule: CustomDnsRule, newDomain: String) {
        viewModelScope.launch {
            val clean = sanitizeDomain(newDomain)
            if (clean.isNotBlank() && clean != oldRule.domain.lowercase()) {
                val allRules = customDnsRuleDao.getAll()
                val exists = allRules.any {
                    it.ruleType == RuleType.BLOCK && it.domain.equals(clean, ignoreCase = true) && it.id != oldRule.id
                }
                if (!exists) {
                    customDnsRuleDao.update(
                        oldRule.copy(
                            domain = clean,
                            rule = "||$clean^"
                        )
                    )
                    filterRepo.loadCustomRules()
                    _events.toast(R.string.blocklist_domain_added, listOf(clean))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.blocklist_domain_already_exists)
                }
            }
        }
    }

    fun toggleBlocklistDomain(rule: CustomDnsRule) {
        viewModelScope.launch {
            customDnsRuleDao.update(rule.copy(isEnabled = !rule.isEnabled))
            filterRepo.loadCustomRules()
            requestVpnRestart()
        }
    }

    fun removeBlocklistDomain(rule: CustomDnsRule) {
        viewModelScope.launch {
            customDnsRuleDao.delete(rule)
            filterRepo.loadCustomRules()
            _events.toast(R.string.blocklist_domain_removed)
            requestVpnRestart()
        }
    }

    // ── Bulk Import ──────────────────────────────────────────

    fun importDomains(domains: List<String>, isAllow: Boolean) {
        if (domains.isEmpty()) return
        viewModelScope.launch {
            if (isAllow) {
                val existing = whitelistDomainDao.getAllDomains().map { it.lowercase() }.toSet()
                val newDomains = domains.map { sanitizeDomain(it) }
                    .filter { it.isNotBlank() && !existing.contains(it) }
                    .distinct()
                if (newDomains.isNotEmpty()) {
                    whitelistDomainDao.insertAll(newDomains.map { WhitelistDomain(domain = it) })
                    filterRepo.loadWhitelist()
                    _events.toast(R.string.wireguard_imported, listOf("${newDomains.size} domains"))
                    requestVpnRestart()
                }
            } else {
                val existing = customDnsRuleDao.getAll()
                    .filter { it.ruleType == RuleType.BLOCK }
                    .map { it.domain.lowercase() }
                    .toSet()
                val newDomains = domains.map { sanitizeDomain(it) }
                    .filter { it.isNotBlank() && !existing.contains(it) }
                    .distinct()
                if (newDomains.isNotEmpty()) {
                    customDnsRuleDao.insertAll(
                        newDomains.map {
                            CustomDnsRule(
                                rule = "||$it^",
                                ruleType = RuleType.BLOCK,
                                domain = it
                            )
                        }
                    )
                    filterRepo.loadCustomRules()
                    _events.toast(R.string.wireguard_imported, listOf("${newDomains.size} domains"))
                    requestVpnRestart()
                }
            }
        }
    }

    // ── Domain Rules UI Actions (Room DAOs) ──────────────────

    fun addProfileRule(
        type: String,
        param: String,
        policy: String,
        packageName: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val paramClean = if (type.startsWith("HOST", ignoreCase = true)) {
                sanitizeDomain(param)
            } else {
                param.trim()
            }
            if (paramClean.isBlank() && !type.equals("FINAL", ignoreCase = true)) return@launch

            val cleanPackage = packageName.trim()
            val isDirect = policy.equals("DIRECT", ignoreCase = true)
            if (isDirect && type.equals("HOST", ignoreCase = true) && cleanPackage.isEmpty()) {
                if (whitelistDomainDao.exists(paramClean) == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = paramClean))
                    filterRepo.loadWhitelist()
                    _events.toast(R.string.whitelist_domain_added, listOf(paramClean))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.filter_domain_already_whitelisted)
                }
            } else {
                val ruleLine = formatRuleLine(type, paramClean, policy)
                val ruleType = if (isDirect) RuleType.ALLOW else RuleType.BLOCK
                val alreadyExists = if (cleanPackage.isNotEmpty()) {
                    customDnsRuleDao.existsForApp(paramClean, ruleType.name, cleanPackage) > 0
                } else {
                    customDnsRuleDao.exists(ruleLine) > 0
                }
                if (!alreadyExists) {
                    customDnsRuleDao.insert(
                        CustomDnsRule(
                            rule = ruleLine,
                            ruleType = ruleType,
                            domain = paramClean,
                            packageName = cleanPackage
                        )
                    )
                    filterRepo.loadCustomRules()
                    val toastRes = if (isDirect) R.string.whitelist_domain_added else R.string.blocklist_domain_added
                    _events.toast(toastRes, listOf(paramClean.ifEmpty { type }))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.blocklist_domain_already_exists)
                }
            }
        }
    }

    fun updateProfileRule(
        oldRawLine: String,
        oldDomain: String,
        type: String,
        param: String,
        policy: String,
        packageName: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val paramClean = if (type.startsWith("HOST", ignoreCase = true)) {
                sanitizeDomain(param)
            } else {
                param.trim()
            }
            if (paramClean.isBlank() && !type.equals("FINAL", ignoreCase = true)) return@launch

            val cleanPackage = packageName.trim()
            val isDirect = policy.equals("DIRECT", ignoreCase = true)
            val newIsWhitelist = isDirect && type.equals("HOST", ignoreCase = true) && cleanPackage.isEmpty()

            if (oldRawLine.startsWith("whitelist:")) {
                val id = oldRawLine.removePrefix("whitelist:").toIntOrNull() ?: return@launch
                val existing = whitelistDomainDao.getAll().first().firstOrNull { it.id == id }
                if (newIsWhitelist && existing != null) {
                    whitelistDomainDao.update(existing.copy(domain = paramClean))
                    filterRepo.loadWhitelist()
                } else {
                    if (existing != null) whitelistDomainDao.delete(existing)
                    customDnsRuleDao.insert(
                        CustomDnsRule(
                            rule = formatRuleLine(type, paramClean, policy),
                            ruleType = if (isDirect) RuleType.ALLOW else RuleType.BLOCK,
                            domain = paramClean,
                            packageName = cleanPackage,
                            isEnabled = existing?.isEnabled ?: true
                        )
                    )
                    filterRepo.loadWhitelist()
                    filterRepo.loadCustomRules()
                }
            } else if (oldRawLine.startsWith("custom:")) {
                val id = oldRawLine.removePrefix("custom:").toIntOrNull() ?: return@launch
                val existing = customDnsRuleDao.getAll().firstOrNull { it.id == id }
                if (newIsWhitelist) {
                    if (existing != null) customDnsRuleDao.delete(existing)
                    if (whitelistDomainDao.exists(paramClean) == 0) {
                        whitelistDomainDao.insert(
                            WhitelistDomain(
                                domain = paramClean,
                                isEnabled = existing?.isEnabled ?: true
                            )
                        )
                    }
                    filterRepo.loadWhitelist()
                    filterRepo.loadCustomRules()
                } else if (existing != null) {
                    customDnsRuleDao.update(
                        existing.copy(
                            rule = formatRuleLine(type, paramClean, policy),
                            ruleType = if (isDirect) RuleType.ALLOW else RuleType.BLOCK,
                            domain = paramClean,
                            packageName = cleanPackage
                        )
                    )
                    filterRepo.loadCustomRules()
                }
            }
            requestVpnRestart()
        }
    }

    fun toggleProfileRule(rule: ParsedFilterRule) {
        viewModelScope.launch(Dispatchers.IO) {
            if (rule.rawLine.startsWith("whitelist:")) {
                val id = rule.rawLine.removePrefix("whitelist:").toIntOrNull() ?: return@launch
                val existing = whitelistDomainDao.getAll().first().firstOrNull { it.id == id } ?: return@launch
                whitelistDomainDao.update(existing.copy(isEnabled = !existing.isEnabled))
                filterRepo.loadWhitelist()
            } else if (rule.rawLine.startsWith("custom:")) {
                val id = rule.rawLine.removePrefix("custom:").toIntOrNull() ?: return@launch
                val existing = customDnsRuleDao.getAll().firstOrNull { it.id == id } ?: return@launch
                customDnsRuleDao.update(existing.copy(isEnabled = !existing.isEnabled))
                filterRepo.loadCustomRules()
            }
            requestVpnRestart()
        }
    }

    fun removeProfileRule(rule: ParsedFilterRule) {
        viewModelScope.launch(Dispatchers.IO) {
            if (rule.rawLine.startsWith("whitelist:")) {
                val id = rule.rawLine.removePrefix("whitelist:").toIntOrNull() ?: return@launch
                val existing = whitelistDomainDao.getAll().first().firstOrNull { it.id == id } ?: return@launch
                whitelistDomainDao.delete(existing)
                filterRepo.loadWhitelist()
            } else if (rule.rawLine.startsWith("custom:")) {
                val id = rule.rawLine.removePrefix("custom:").toIntOrNull() ?: return@launch
                val existing = customDnsRuleDao.getAll().firstOrNull { it.id == id } ?: return@launch
                customDnsRuleDao.delete(existing)
                filterRepo.loadCustomRules()
            }
            _undoRemovedRule.tryEmit(rule)
            requestVpnRestart()
        }
    }

    fun restoreProfileRule(rule: ParsedFilterRule) {
        viewModelScope.launch(Dispatchers.IO) {
            val isDirect = rule.policy.equals("DIRECT", ignoreCase = true)
            val cleanPackage = rule.packageName.trim()
            if (rule.rawLine.startsWith("whitelist:") || (isDirect && rule.type.equals("HOST", ignoreCase = true) && cleanPackage.isEmpty())) {
                val clean = sanitizeDomain(rule.param)
                if (clean.isNotBlank() && whitelistDomainDao.exists(clean) == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = clean, isEnabled = rule.isEnabled))
                    filterRepo.loadWhitelist()
                }
            } else {
                val ruleLine = formatRuleLine(rule.type, rule.param, rule.policy)
                val ruleType = if (isDirect) RuleType.ALLOW else RuleType.BLOCK
                val exists = if (cleanPackage.isNotEmpty()) {
                    customDnsRuleDao.existsForApp(rule.param, ruleType.name, cleanPackage) > 0
                } else {
                    customDnsRuleDao.exists(ruleLine) > 0
                }
                if (!exists) {
                    customDnsRuleDao.insert(
                        CustomDnsRule(
                            rule = ruleLine,
                            ruleType = ruleType,
                            domain = rule.param,
                            packageName = cleanPackage,
                            isEnabled = rule.isEnabled
                        )
                    )
                    filterRepo.loadCustomRules()
                }
            }
            requestVpnRestart()
        }
    }

    fun loadInstalledAppsIfNeeded() = sniProxyDelegate.loadInstalledAppsIfNeeded()

    fun saveSniProxyRule(rule: SniProxyRule) {
        viewModelScope.launch(Dispatchers.IO) {
            sniProxyDelegate.saveRule(rule)
            _events.toast(R.string.sni_proxy_saved)
        }
    }

    fun toggleSniProxyRule(ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sniProxyDelegate.toggleRule(ruleId)
        }
    }

    fun deleteSniProxyRule(ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sniProxyDelegate.deleteRule(ruleId)
            _events.toast(R.string.sni_proxy_deleted)
        }
    }

    private fun requestVpnRestart() {
        ServiceController.requestRestart(getApplication<Application>().applicationContext)
    }
}
