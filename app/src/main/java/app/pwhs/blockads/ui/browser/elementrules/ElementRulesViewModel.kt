package app.pwhs.blockads.ui.browser.elementrules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.dao.ElementRuleDao
import app.pwhs.blockads.data.entities.ElementRule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ElementRulesViewModel(
    private val elementRuleDao: ElementRuleDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ElementRulesUiState(isLoading = true))
    val uiState: StateFlow<ElementRulesUiState> = _uiState.asStateFlow()

    private val _uiEffect = Channel<ElementRulesUiEffect>()
    val uiEffect = _uiEffect.receiveAsFlow()

    private val searchQueryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                elementRuleDao.getAllRules(),
                searchQueryFlow
            ) { allRules, query ->
                val filtered = if (query.isBlank()) {
                    allRules
                } else {
                    allRules.filter {
                        it.domain.contains(query, ignoreCase = true) ||
                                it.cssSelector.contains(query, ignoreCase = true)
                    }
                }
                val grouped = filtered.groupBy { it.domain }
                ElementRulesUiState(
                    rules = filtered,
                    rulesByDomain = grouped,
                    totalCount = allRules.size,
                    isLoading = false,
                    searchQuery = query
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun processIntent(intent: ElementRulesUiIntent) {
        when (intent) {
            is ElementRulesUiIntent.DeleteRule -> {
                viewModelScope.launch {
                    elementRuleDao.deleteById(intent.id)
                    _uiEffect.send(ElementRulesUiEffect.ShowToast("Đã xóa quy tắc"))
                }
            }
            is ElementRulesUiIntent.DeleteAllForDomain -> {
                viewModelScope.launch {
                    elementRuleDao.deleteAllForDomain(intent.domain)
                    _uiEffect.send(ElementRulesUiEffect.ShowToast("Đã xóa tất cả quy tắc của ${intent.domain}"))
                }
            }
            is ElementRulesUiIntent.SearchQueryChanged -> {
                searchQueryFlow.value = intent.query
            }
        }
    }
}
