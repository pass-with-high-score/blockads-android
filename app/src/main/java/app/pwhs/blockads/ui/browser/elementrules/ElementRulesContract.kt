package app.pwhs.blockads.ui.browser.elementrules

import app.pwhs.blockads.data.entities.ElementRule

data class ElementRulesUiState(
    val rules: List<ElementRule> = emptyList(),
    val rulesByDomain: Map<String, List<ElementRule>> = emptyMap(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val searchQuery: String = ""
)

sealed interface ElementRulesUiIntent {
    data class DeleteRule(val id: Int) : ElementRulesUiIntent
    data class DeleteAllForDomain(val domain: String) : ElementRulesUiIntent
    data class SearchQueryChanged(val query: String) : ElementRulesUiIntent
}

sealed interface ElementRulesUiEffect {
    data class ShowToast(val message: String) : ElementRulesUiEffect
}
