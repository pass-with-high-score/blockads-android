package app.pwhs.blockads.ui.browser

import app.pwhs.blockads.data.dao.ElementRuleDao
import app.pwhs.blockads.data.entities.ElementRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeElementRuleDao(initial: List<ElementRule> = emptyList()) : ElementRuleDao {

    val rules = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override suspend fun insert(rule: ElementRule) {
        val stored = if (rule.id == 0) rule.copy(id = nextId++) else rule
        rules.value = rules.value.filterNot { it.id == stored.id } + stored
    }

    override fun getRulesForDomain(domain: String): Flow<List<ElementRule>> =
        rules.map { all -> all.filter { it.domain == domain } }

    override fun getAllRules(): Flow<List<ElementRule>> =
        rules.map { all -> all.sortedWith(compareBy<ElementRule> { it.domain }.thenByDescending { it.createdAt }) }

    override suspend fun getSelectorsForDomain(domain: String): List<String> =
        rules.value.filter { it.domain == domain }.map { it.cssSelector }

    override suspend fun deleteById(id: Int) {
        rules.value = rules.value.filterNot { it.id == id }
    }

    override suspend fun deleteAllForDomain(domain: String) {
        rules.value = rules.value.filterNot { it.domain == domain }
    }

    override suspend fun totalCount(): Int = rules.value.size
}
