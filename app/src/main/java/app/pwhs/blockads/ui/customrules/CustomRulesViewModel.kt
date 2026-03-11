package app.pwhs.blockads.ui.customrules

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.util.CustomRuleParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CustomRulesViewModel(
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val filterListRepository: FilterListRepository,
    private val application: Application,
) : ViewModel() {

    private val _rules = MutableStateFlow<List<CustomDnsRule>>(emptyList())
    val rules: StateFlow<List<CustomDnsRule>> = _rules.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadRules()
    }

    fun loadRules() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                customDnsRuleDao.getAllFlow().collect { rulesList ->
                    _rules.value = rulesList
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addRule(ruleText: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val parsedRule = CustomRuleParser.parseRule(ruleText)
                if (parsedRule != null) {
                    val allRules = customDnsRuleDao.getAll()
                    val isDuplicate = allRules.any {
                        if (parsedRule.ruleType == RuleType.COMMENT) {
                            it.rule == parsedRule.rule
                        } else {
                            it.ruleType == parsedRule.ruleType && it.domain == parsedRule.domain
                        }
                    }
                    if (isDuplicate) {
                        onError("Rule already exists")
                        return@launch
                    }
                    customDnsRuleDao.insert(parsedRule)
                    reloadFilters()
                    AdBlockVpnService.requestRestart(application.applicationContext)
                    onSuccess()
                } else {
                    onError("Invalid rule format")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to add rule")
            }
        }
    }

    fun addRules(rulesText: String, onSuccess: (Int) -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val parsedRules = CustomRuleParser.parseRules(rulesText)
                if (parsedRules.isNotEmpty()) {
                    val allRules = customDnsRuleDao.getAll()
                    val existingNonComments = allRules.filter { it.ruleType != RuleType.COMMENT }
                        .map { Pair(it.ruleType, it.domain) }.toSet()
                    val existingComments = allRules.filter { it.ruleType == RuleType.COMMENT }
                        .map { it.rule }.toSet()

                    // Filter out duplicates (both against DB and within the new list)
                    val newRulesToInsert = mutableListOf<CustomDnsRule>()
                    val seenNonComments = existingNonComments.toMutableSet()
                    val seenComments = existingComments.toMutableSet()

                    for (rule in parsedRules) {
                        val isDuplicate = if (rule.ruleType == RuleType.COMMENT) {
                            !seenComments.add(rule.rule)
                        } else {
                            !seenNonComments.add(Pair(rule.ruleType, rule.domain))
                        }
                        if (!isDuplicate) {
                            newRulesToInsert.add(rule)
                        }
                    }

                    if (newRulesToInsert.isNotEmpty()) {
                        customDnsRuleDao.insertAll(newRulesToInsert)
                        reloadFilters()
                        AdBlockVpnService.requestRestart(application.applicationContext)
                        onSuccess(newRulesToInsert.size)
                    } else if (parsedRules.size > newRulesToInsert.size) {
                         // All parsed rules were duplicates
                         onError("Rules already exist")
                    } else {
                        onError("No valid rules found")
                    }
                } else {
                    onError("No valid rules found")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to add rules")
            }
        }
    }

    fun deleteRule(rule: CustomDnsRule) {
        viewModelScope.launch {
            try {
                customDnsRuleDao.delete(rule)
                reloadFilters()
                AdBlockVpnService.requestRestart(application.applicationContext)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun toggleRule(rule: CustomDnsRule) {
        viewModelScope.launch {
            try {
                val updatedRule = rule.copy(isEnabled = !rule.isEnabled)
                customDnsRuleDao.update(updatedRule)
                reloadFilters()
                AdBlockVpnService.requestRestart(application.applicationContext)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun deleteAllRules() {
        viewModelScope.launch {
            try {
                customDnsRuleDao.deleteAll()
                reloadFilters()
                AdBlockVpnService.requestRestart(application.applicationContext)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun exportRules(): String {
        return _rules.value.joinToString("\n") { it.rule }
    }

    fun importRules(rulesText: String, onSuccess: (Int) -> Unit = {}, onError: (String) -> Unit = {}) {
        addRules(rulesText, onSuccess, onError)
    }

    private suspend fun reloadFilters() {
        try {
            filterListRepository.loadCustomRules()
        } catch (e: Exception) {
            _error.value = "Failed to reload filters: ${e.message}"
        }
    }

    fun clearError() {
        _error.value = null
    }
}
