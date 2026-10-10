package app.pwhs.blockads.ui.domainrules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import app.pwhs.blockads.data.dao.ConfigDao
import app.pwhs.blockads.data.entities.ConfigProfile
import app.pwhs.blockads.utils.ConfigRuleHelper
import app.pwhs.blockads.utils.ParsedFilterRule
import kotlinx.coroutines.Dispatchers


class DomainRulesViewModel(
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val filterRepo: FilterListRepository,
    private val configDao: ConfigDao,
    application: Application
) : AndroidViewModel(application) {

    val activeConfig: StateFlow<ConfigProfile?> = configDao.getActiveFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allConfigs: StateFlow<List<ConfigProfile>> = configDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profileFilterRules: StateFlow<List<ParsedFilterRule>> = configDao.getActiveFlow()
        .map { it?.content?.let(ConfigRuleHelper::parseFilterRules) ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whitelistDomains: StateFlow<List<WhitelistDomain>> = whitelistDomainDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blocklistDomains: StateFlow<List<CustomDnsRule>> = customDnsRuleDao.getAllFlow()
        .map { rules -> rules.filter { it.ruleType == RuleType.BLOCK } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

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

    // ── Profile Rules ────────────────────────────────────────

    fun addProfileRule(
        type: String,
        param: String,
        policy: String,
        targetConfigId: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetConfig = (if (targetConfigId != null) configDao.getById(targetConfigId) else null)
                ?: configDao.getActive()
                ?: return@launch

            val cleanDomain = sanitizeDomain(param)
            val paramClean = if (type.startsWith("HOST", ignoreCase = true)) cleanDomain else param.trim()
            val ruleLine = if (type.equals("FINAL", ignoreCase = true)) {
                "final, ${policy.lowercase()}"
            } else {
                "${type.lowercase()}, $paramClean, ${policy.lowercase()}"
            }

            val updatedContent = ConfigRuleHelper.appendRuleToSection(targetConfig.content, "filter_local", ruleLine)
            configDao.update(targetConfig.copy(content = updatedContent))

            // Sync domain rules to Room DB if applicable
            if (cleanDomain.isNotBlank() && type.startsWith("HOST", ignoreCase = true)) {
                if (policy.equals("REJECT", ignoreCase = true)) {
                    val exists = customDnsRuleDao.existsBlockDomain(cleanDomain)
                    if (exists == 0) {
                        customDnsRuleDao.insert(
                            CustomDnsRule(
                                rule = "||$cleanDomain^",
                                ruleType = RuleType.BLOCK,
                                domain = cleanDomain,
                                isEnabled = true
                            )
                        )
                    }
                } else if (policy.equals("DIRECT", ignoreCase = true)) {
                    val exists = whitelistDomainDao.exists(cleanDomain)
                    if (exists == 0) {
                        whitelistDomainDao.insert(
                            WhitelistDomain(
                                domain = cleanDomain,
                                isEnabled = true
                            )
                        )
                    }
                }
            }

            _events.toast(R.string.config_rule_added, listOf(ruleLine))
            requestVpnRestart()
        }
    }

    fun updateProfileRule(
        oldDomain: String,
        type: String,
        param: String,
        policy: String,
        targetConfigId: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetConfig = (if (targetConfigId != null) configDao.getById(targetConfigId) else null)
                ?: configDao.getActive()

            val cleanDomain = sanitizeDomain(param)
            val oldClean = sanitizeDomain(oldDomain)
            val paramClean = if (type.startsWith("HOST", ignoreCase = true)) cleanDomain else param.trim()

            val ruleLine = if (type.equals("FINAL", ignoreCase = true)) {
                "final, ${policy.lowercase()}"
            } else {
                "${type.lowercase()}, $paramClean, ${policy.lowercase()}"
            }

            if (targetConfig != null) {
                val updatedContent = ConfigRuleHelper.replaceRuleInContent(targetConfig.content, oldDomain, ruleLine)
                configDao.update(targetConfig.copy(content = updatedContent))
            }

            if (type.startsWith("HOST", ignoreCase = true)) {
                if (policy.equals("REJECT", ignoreCase = true)) {
                    whitelistDomainDao.deleteByDomain(oldClean)
                    if (cleanDomain.isNotBlank()) {
                        val existing = customDnsRuleDao.getAll().firstOrNull { it.domain.equals(oldClean, ignoreCase = true) }
                        if (existing != null) {
                            customDnsRuleDao.update(existing.copy(domain = cleanDomain, rule = "||$cleanDomain^"))
                        } else if (customDnsRuleDao.existsBlockDomain(cleanDomain) == 0) {
                            customDnsRuleDao.insert(
                                CustomDnsRule(domain = cleanDomain, rule = "||$cleanDomain^", ruleType = RuleType.BLOCK, isEnabled = true)
                            )
                        }
                    }
                } else if (policy.equals("DIRECT", ignoreCase = true)) {
                    customDnsRuleDao.deleteBlockRuleByDomain(oldClean)
                    if (cleanDomain.isNotBlank()) {
                        val existing = whitelistDomainDao.getAllDomains().any { it.equals(oldClean, ignoreCase = true) }
                        if (existing) {
                            whitelistDomainDao.deleteByDomain(oldClean)
                        }
                        if (whitelistDomainDao.exists(cleanDomain) == 0) {
                            whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain, isEnabled = true))
                        }
                    }
                }
            }

            filterRepo.loadCustomRules()
            filterRepo.loadWhitelist()
            _events.toast(R.string.config_rule_added, listOf(ruleLine))
            requestVpnRestart()
        }
    }

    fun removeProfileRule(rawLine: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val active = configDao.getActive() ?: return@launch
            val updated = ConfigRuleHelper.removeRuleFromContent(active.content, rawLine)
            configDao.update(active.copy(content = updated))
            requestVpnRestart()
        }
    }

    private fun requestVpnRestart() {
        ServiceController.requestRestart(getApplication<Application>().applicationContext)
    }
}
