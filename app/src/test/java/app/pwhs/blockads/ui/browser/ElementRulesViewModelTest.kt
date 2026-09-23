package app.pwhs.blockads.ui.browser

import app.cash.turbine.test
import app.pwhs.blockads.data.entities.ElementRule
import app.pwhs.blockads.ui.browser.elementrules.ElementRulesUiEffect
import app.pwhs.blockads.ui.browser.elementrules.ElementRulesUiIntent
import app.pwhs.blockads.ui.browser.elementrules.ElementRulesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElementRulesViewModelTest {

    private val dao = FakeElementRuleDao(
        listOf(
            ElementRule(id = 1, domain = "news.test", cssSelector = ".banner", createdAt = 1),
            ElementRule(id = 2, domain = "news.test", cssSelector = "#popup", createdAt = 2),
            ElementRule(id = 3, domain = "video.test", cssSelector = ".overlay", createdAt = 3),
        )
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads all rules grouped by domain`() {
        val vm = ElementRulesViewModel(dao)
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(3, state.totalCount)
        assertEquals(setOf("news.test", "video.test"), state.rulesByDomain.keys)
        assertEquals(2, state.rulesByDomain.getValue("news.test").size)
    }

    @Test
    fun `search filters by domain or selector, case-insensitively`() {
        val vm = ElementRulesViewModel(dao)

        vm.processIntent(ElementRulesUiIntent.SearchQueryChanged("VIDEO"))
        assertEquals(listOf(3), vm.uiState.value.rules.map { it.id })
        assertEquals(3, vm.uiState.value.totalCount)

        vm.processIntent(ElementRulesUiIntent.SearchQueryChanged("#pop"))
        assertEquals(listOf(2), vm.uiState.value.rules.map { it.id })
        assertEquals("#pop", vm.uiState.value.searchQuery)

        vm.processIntent(ElementRulesUiIntent.SearchQueryChanged("  "))
        assertEquals(3, vm.uiState.value.rules.size)
    }

    @Test
    fun `delete removes one rule and toasts`() = runTest {
        val vm = ElementRulesViewModel(dao)
        vm.uiEffect.test {
            vm.processIntent(ElementRulesUiIntent.DeleteRule(2))
            assertTrue(awaitItem() is ElementRulesUiEffect.ShowToast)
        }
        assertEquals(listOf(1, 3), vm.uiState.value.rules.map { it.id }.sorted())
    }

    @Test
    fun `delete all for a domain toasts with the domain`() = runTest {
        val vm = ElementRulesViewModel(dao)
        vm.uiEffect.test {
            vm.processIntent(ElementRulesUiIntent.DeleteAllForDomain("news.test"))
            val toast = awaitItem() as ElementRulesUiEffect.ShowToast
            assertTrue(toast.message.contains("news.test"))
        }
        assertEquals(listOf("video.test"), vm.uiState.value.rulesByDomain.keys.toList())
        assertEquals(1, vm.uiState.value.totalCount)
    }
}
