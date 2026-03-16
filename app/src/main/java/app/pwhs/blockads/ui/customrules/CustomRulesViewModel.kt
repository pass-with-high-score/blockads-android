package app.pwhs.blockads.ui.customrules

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.entities.CustomDnsRuleExport
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.toEntity
import app.pwhs.blockads.data.entities.toExport
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.ui.customrules.data.ExportFormat
import app.pwhs.blockads.util.CustomRuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CustomRulesViewModel(
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val filterListRepository: FilterListRepository,
    private val application: Application,
) : ViewModel() {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

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

    private fun insertRulesWithDedup(
        rules: List<CustomDnsRule>,
        onSuccess: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                if (rules.isEmpty()) {
                    onError("No valid rules found in file")
                    return@launch
                }

                val allRules = customDnsRuleDao.getAll()
                val existingNonComments = allRules.filter { it.ruleType != RuleType.COMMENT }
                    .map { Pair(it.ruleType, it.domain) }.toSet()
                val existingComments = allRules.filter { it.ruleType == RuleType.COMMENT }
                    .map { it.rule }.toSet()

                val newRulesToInsert = mutableListOf<CustomDnsRule>()
                val seenNonComments = existingNonComments.toMutableSet()
                val seenComments = existingComments.toMutableSet()

                for (rule in rules) {
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
                } else {
                    onError("Rules already exist")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to import rules")
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

    // ── SAF File Export ──────────────────────────────────────────────────

    fun exportRulesToUri(
        uri: Uri,
        format: ExportFormat,
        onSuccess: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val currentRules = _rules.value
                if (currentRules.isEmpty()) {
                    onError("No rules to export")
                    return@launch
                }

                val content = withContext(Dispatchers.IO) {
                    when (format) {
                        ExportFormat.JSON -> {
                            val exportList = currentRules.map { it.toExport() }
                            json.encodeToString(exportList)
                        }
                        ExportFormat.TXT -> {
                            currentRules.joinToString("\n") { it.rule }
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    application.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray(Charsets.UTF_8))
                    } ?: throw Exception("Cannot open output stream")
                }

                onSuccess(currentRules.size)
            } catch (e: Exception) {
                onError(e.message ?: "Export failed")
            }
        }
    }

    // ── SAF File Import ──────────────────────────────────────────────────

    fun importRulesFromUri(
        uri: Uri,
        onSuccess: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    application.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw Exception("Cannot open input stream")
                }

                if (content.isBlank()) {
                    onError("File is empty")
                    return@launch
                }

                // Try JSON first, fall back to TXT
                val rules = try {
                    val exported = json.decodeFromString<List<CustomDnsRuleExport>>(content)
                    exported.map { it.toEntity() }
                } catch (_: Exception) {
                    // Not valid JSON — treat as plain text (one rule per line)
                    CustomRuleParser.parseRules(content)
                }

                insertRulesWithDedup(rules, onSuccess, onError)
            } catch (e: Exception) {
                onError(e.message ?: "Import failed")
            }
        }
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
