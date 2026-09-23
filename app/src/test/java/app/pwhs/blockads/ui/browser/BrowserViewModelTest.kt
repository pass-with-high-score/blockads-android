package app.pwhs.blockads.ui.browser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.entities.ElementRule
import app.pwhs.blockads.ui.browser.data.SearchEngine
import app.pwhs.blockads.ui.browser.data.SearchSuggestionRepository
import app.pwhs.blockads.ui.browser.rules.BrowserRulePackage
import app.pwhs.blockads.ui.browser.rules.BrowserRuleRepository
import app.pwhs.blockads.ui.browser.rules.BrowserRuleUpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BrowserViewModelTest {

    private val rules = MutableStateFlow(BrowserRulePackage(version = 20, adDomains = listOf("a", "b", "c")))
    private var updateResult: Result<Boolean> = Result.success(true)
    private val ruleRepo = object : BrowserRuleRepository {
        override val currentRules = rules
        override val updateStatus = MutableStateFlow<BrowserRuleUpdateStatus>(BrowserRuleUpdateStatus.Idle)
        var checks = 0
        override suspend fun checkAndUpdate(customUrl: String?): Result<Boolean> {
            checks++
            return updateResult
        }
        override suspend fun resetToDefaults() = Unit
    }
    private val suggestionQueries = mutableListOf<String>()
    private val suggestions = object : SearchSuggestionRepository {
        override suspend fun getSuggestions(query: String): List<String> {
            suggestionQueries += query
            return listOf("$query 1", "$query 2")
        }
    }
    private val dao = FakeElementRuleDao()

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newVm(): BrowserViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return BrowserViewModel(ApplicationProvider.getApplicationContext<Application>(), ruleRepo, suggestions, dao)
            .also { advanceUntilIdle() }
    }

    private fun TestScope.resolve(input: String, engine: SearchEngine = SearchEngine.GOOGLE): String {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.SelectSearchEngine(engine))
        vm.processIntent(BrowserUiIntent.LoadUrl(input))
        return vm.uiState.value.currentUrl
    }

    @Test
    fun `rule counts follow the repository`() = runTest {
        val vm = newVm()
        assertEquals(20L, vm.uiState.value.ruleVersion)
        assertEquals(3, vm.uiState.value.ruleDomainsCount)

        rules.value = BrowserRulePackage(version = 21, adDomains = listOf("x"))
        advanceUntilIdle()
        assertEquals(21L, vm.uiState.value.ruleVersion)
        assertEquals(1, vm.uiState.value.ruleDomainsCount)
    }

    @Test
    fun `URL resolution keeps schemes, adds https to hosts and searches the rest`() = runTest {
        assertEquals("http://example.com/a", resolve("  http://example.com/a "))
        assertEquals("https://example.com", resolve("example.com"))
        assertEquals("https://www.google.com/search?q=cute%20cats", resolve("cute cats"))
        assertEquals("https://duckduckgo.com/?q=cute%20cats", resolve("cute cats", SearchEngine.DUCKDUCKGO))
        assertEquals("https://search.brave.com/search?q=a.b%20c", resolve("a.b c", SearchEngine.BRAVE))
    }

    @Test
    fun `dangerous schemes are never loaded as typed`() = runTest {
        for (input in listOf("javascript:alert(1)", "javascript:alert(document.cookie)", "file:///sdcard/x.html", "about:blank")) {
            val url = resolve(input)
            assertTrue(input, url.startsWith("https://"))
        }
    }

    @Ignore("Suspected: host:port input without a dot (localhost:8080) is sent to the search engine")
    @Test
    fun `localhost with a port is loaded, not searched`() = runTest {
        assertEquals("http://localhost:8080", resolve("localhost:8080"))
    }

    @Test
    fun `LoadUrl and SubmitSearch navigate and close overlays`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.ToggleShortcuts)
        vm.processIntent(BrowserUiIntent.ToggleSearchSheet(true))
        vm.processIntent(BrowserUiIntent.LoadUrl("example.com"))
        vm.processIntent(BrowserUiIntent.SubmitSearch("kotlin"))
        advanceUntilIdle()

        val effects = vm.uiEffect.take(2).toList()
        assertEquals(
            listOf(
                BrowserUiEffect.NavigateUrl("https://example.com"),
                BrowserUiEffect.NavigateUrl("https://www.google.com/search?q=kotlin"),
            ),
            effects
        )
        assertFalse(vm.uiState.value.showShortcuts)
        assertFalse(vm.uiState.value.isSearchSheetVisible)
        assertEquals("https://www.google.com/search?q=kotlin", vm.uiState.value.displayUrl)
    }

    @Test
    fun `toggles flip their flags`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.ToggleDesktopMode)
        vm.processIntent(BrowserUiIntent.ToggleAdBlock)
        vm.processIntent(BrowserUiIntent.TogglePopupBlock)
        vm.processIntent(BrowserUiIntent.ToggleBentoMenu(true))
        vm.processIntent(BrowserUiIntent.UpdateBottomBarVisibility(false))
        vm.processIntent(BrowserUiIntent.GoBack)
        vm.processIntent(BrowserUiIntent.GoForward)
        val s = vm.uiState.value
        assertTrue(s.isDesktopMode)
        assertFalse(s.adBlockEnabled)
        assertFalse(s.popupBlockEnabled)
        assertTrue(s.isBentoMenuVisible)
        assertFalse(s.isBottomBarVisible)
    }

    @Test
    fun `page lifecycle updates progress, url and title`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.PageStarted("https://a.test/"))
        assertTrue(vm.uiState.value.isLoading)
        vm.processIntent(BrowserUiIntent.UpdateProgress(50))
        assertTrue(vm.uiState.value.isLoading)
        vm.processIntent(BrowserUiIntent.UpdateProgress(100))
        assertFalse(vm.uiState.value.isLoading)
        vm.processIntent(BrowserUiIntent.PageFinished("https://a.test/", ""))
        assertEquals("https://a.test/", vm.uiState.value.pageTitle)
        vm.processIntent(BrowserUiIntent.Reload)
        assertEquals("https://a.test/", vm.uiState.value.currentUrl)
        vm.processIntent(BrowserUiIntent.AdBlocked)
        vm.processIntent(BrowserUiIntent.AdBlocked)
        assertEquals(2, vm.uiState.value.blockedCount)
    }

    @Test
    fun `finished pages get their saved element rules, with www stripped`() = runTest {
        dao.insert(ElementRule(domain = "news.test", cssSelector = ".ad"))
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.PageFinished("https://www.news.test/story", "Story"))
        vm.processIntent(BrowserUiIntent.PageFinished("https://other.test/", "Other"))
        advanceUntilIdle()

        assertEquals(BrowserUiEffect.InjectUserElementRules(listOf(".ad")), vm.uiEffect.take(1).toList().single())
        assertEquals("Other", vm.uiState.value.pageTitle)
    }

    @Test
    fun `picking an element stores the rule and re-injects`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.ToggleBentoMenu(true))
        vm.processIntent(BrowserUiIntent.ActivateElementPicker)
        assertTrue(vm.uiState.value.isElementPickerActive)
        assertFalse(vm.uiState.value.isBentoMenuVisible)

        vm.processIntent(BrowserUiIntent.ElementRulePicked(".promo", "www.news.test"))
        advanceUntilIdle()

        assertEquals("news.test", dao.rules.value.single().domain)
        assertFalse(vm.uiState.value.isElementPickerActive)
        val effects = vm.uiEffect.take(2).toList()
        assertEquals(BrowserUiEffect.InjectUserElementRules(listOf(".promo")), effects[0])
        assertTrue(effects[1] is BrowserUiEffect.ShowToast)

        vm.processIntent(BrowserUiIntent.ActivateElementPicker)
        vm.processIntent(BrowserUiIntent.DeactivateElementPicker)
        assertFalse(vm.uiState.value.isElementPickerActive)
    }

    @Ignore("the picker bridge is on every page, and the view model saves a rule for whatever domain the page passes")
    @Test
    fun `picked rules are only saved for the page being shown`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.PageFinished("https://evil.test/", "Evil"))
        vm.processIntent(BrowserUiIntent.ElementRulePicked("body", "bank.test"))
        advanceUntilIdle()
        assertTrue(dao.rules.value.none { it.domain == "bank.test" })
    }

    @Test
    fun `search suggestions are debounced and cleared for short queries`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.UpdateSearchQuery("ca"))
        advanceTimeBy(100)
        vm.processIntent(BrowserUiIntent.UpdateSearchQuery("cat"))
        advanceUntilIdle()
        assertEquals(listOf("cat"), suggestionQueries)
        assertEquals(listOf("cat 1", "cat 2"), vm.uiState.value.suggestions)

        vm.processIntent(BrowserUiIntent.UpdateSearchQuery("c"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `search sheet opens with the current URL and closes empty`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.ToggleSearchSheet(true))
        assertEquals(vm.uiState.value.displayUrl, vm.uiState.value.searchQuery)
        vm.processIntent(BrowserUiIntent.ToggleSearchSheet(false))
        assertEquals("", vm.uiState.value.searchQuery)
    }

    @Test
    fun `rule update check toasts for updated, current and failed`() = runTest {
        val vm = newVm()
        for (result in listOf(Result.success(true), Result.success(false), Result.failure(IllegalStateException("x")))) {
            updateResult = result
            vm.processIntent(BrowserUiIntent.CheckRuleUpdates)
            assertTrue(vm.uiState.value.isCheckingRuleUpdates)
            vm.processIntent(BrowserUiIntent.CheckRuleUpdates)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isCheckingRuleUpdates)
        }
        assertEquals(3, ruleRepo.checks)
        val toasts = vm.uiEffect.take(3).toList().map { (it as BrowserUiEffect.ShowToast).message }
        assertEquals(3, toasts.distinct().size)
        assertTrue(toasts[2].contains("x"))
    }

    @Test
    fun `clear data and element-rules navigation emit effects`() = runTest {
        val vm = newVm()
        vm.processIntent(BrowserUiIntent.ToggleBentoMenu(true))
        vm.processIntent(BrowserUiIntent.ClearData)
        vm.processIntent(BrowserUiIntent.NavigateToElementRules)
        advanceUntilIdle()

        val effects = vm.uiEffect.take(2).toList()
        assertTrue(effects[0] is BrowserUiEffect.ShowToast)
        assertEquals(BrowserUiEffect.NavigateToElementRules, effects[1])
        assertFalse(vm.uiState.value.isBentoMenuVisible)
    }
}
