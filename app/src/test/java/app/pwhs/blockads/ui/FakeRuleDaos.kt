package app.pwhs.blockads.ui

import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.WhitelistDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory stand-in mirroring the Room queries' filters; id 0 gets the next free id on insert. */
class FakeCustomDnsRuleDao(initial: List<CustomDnsRule> = emptyList()) : CustomDnsRuleDao {
    val rules = MutableStateFlow(initial)

    override fun getAllFlow(): Flow<List<CustomDnsRule>> = rules
    override suspend fun getAll() = rules.value
    override suspend fun getEnabledRules() = rules.value.filter { it.isEnabled && it.ruleType != RuleType.COMMENT }
    override suspend fun getBlockDomains() =
        rules.value.filter { it.isEnabled && it.ruleType == RuleType.BLOCK }.map { it.domain }
    override suspend fun getAllowDomains() =
        rules.value.filter { it.isEnabled && it.ruleType == RuleType.ALLOW }.map { it.domain }
    override suspend fun insert(rule: CustomDnsRule): Long {
        val stored = if (rule.id == 0) rule.copy(id = (rules.value.maxOfOrNull { it.id } ?: 0) + 1) else rule
        rules.value = rules.value.filterNot { it.id == stored.id } + stored
        return stored.id.toLong()
    }
    override suspend fun insertAll(rules: List<CustomDnsRule>) = rules.forEach { insert(it) }
    override suspend fun update(rule: CustomDnsRule) {
        rules.value = rules.value.map { if (it.id == rule.id) rule else it }
    }
    override suspend fun delete(rule: CustomDnsRule) {
        rules.value = rules.value.filterNot { it.id == rule.id }
    }
    override suspend fun deleteAll() {
        rules.value = emptyList()
    }
    override suspend fun deleteBlockRuleByDomain(domain: String) {
        rules.value = rules.value.filterNot { it.domain == domain && it.ruleType == RuleType.BLOCK }
    }
    override suspend fun getRuleCount() = rules.value.count { it.ruleType != RuleType.COMMENT }
    override suspend fun exists(ruleText: String) = rules.value.count { it.rule == ruleText }
}

class FakeWhitelistDomainDao(initial: List<WhitelistDomain> = emptyList()) : WhitelistDomainDao {
    val domains = MutableStateFlow(initial)

    override fun getAll(): Flow<List<WhitelistDomain>> = domains
    override suspend fun getAllDomains() = domains.value.map { it.domain }
    override suspend fun insert(domain: WhitelistDomain) {
        val stored = if (domain.id == 0) domain.copy(id = (domains.value.maxOfOrNull { it.id } ?: 0) + 1) else domain
        domains.value = domains.value.filterNot { it.id == stored.id } + stored
    }
    override suspend fun delete(domain: WhitelistDomain) {
        domains.value = domains.value.filterNot { it.id == domain.id }
    }
    override suspend fun deleteByDomain(domain: String) {
        domains.value = domains.value.filterNot { it.domain == domain }
    }
    override suspend fun exists(domain: String) = domains.value.count { it.domain == domain }
}
