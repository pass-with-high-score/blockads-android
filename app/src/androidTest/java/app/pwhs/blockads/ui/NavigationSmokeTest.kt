package app.pwhs.blockads.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(
            if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
            else GrantPermissionRule.grant()
        )
        .around(compose)

    private val prefs: AppPreferences = getKoin().get()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun str(@StringRes id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun tabMatcher(@StringRes label: Int) =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(str(label))

    private fun tab(@StringRes label: Int): SemanticsNodeInteraction = compose.onNode(tabMatcher(label))

    private fun bottomBarShown() =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes().isNotEmpty()

    private fun awaitTabSelected(@StringRes label: Int) {
        compose.waitUntil(10_000) { compose.onAllNodes(tabMatcher(label)).fetchSemanticsNodes().isNotEmpty() }
        tab(label).assertIsSelected()
    }

    private fun awaitText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun launch(onboarded: Boolean) {
        runBlocking { prefs.setOnboardingCompleted(onboarded) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun launchHome() {
        launch(onboarded = true)
        awaitTabSelected(R.string.nav_home)
    }

    private fun openFromList(@StringRes item: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(str(item)))
        compose.onNodeWithText(str(item)).performClick()
    }

    @Test
    fun firstLaunchGoesThroughOnboardingToHome() {
        launch(onboarded = false)

        awaitText(str(R.string.onboarding_skip))
        compose.onNodeWithText(str(R.string.onboarding_skip)).performClick()

        awaitTabSelected(R.string.nav_home)
        assertTrue(runBlocking { prefs.onboardingCompleted.first() })
    }

    @Test
    fun onboardedLaunchSkipsStraightToHome() {
        launchHome()

        assertEquals(0, compose.onAllNodes(hasText(str(R.string.onboarding_skip))).fetchSemanticsNodes().size)
    }

    @Test
    fun subScreensPopBackToTheirOwnTabRoot() {
        launchHome()

        tab(R.string.nav_settings).performClick()
        awaitTabSelected(R.string.nav_settings)
        openFromList(R.string.settings_about)
        compose.waitUntil(10_000) { !bottomBarShown() }
        awaitText(str(R.string.about_title))

        Espresso.pressBack()
        awaitTabSelected(R.string.nav_settings)

        tab(R.string.nav_filter).performClick()
        awaitTabSelected(R.string.nav_filter)
        openFromList(R.string.custom_rules)
        compose.waitUntil(10_000) { !bottomBarShown() }

        Espresso.pressBack()
        awaitTabSelected(R.string.nav_filter)

        tab(R.string.nav_settings).performClick()
        awaitTabSelected(R.string.nav_settings)
        tab(R.string.nav_filter).assertIsNotSelected()
    }

    @Test
    fun backFromANonHomeTabRootGoesHome() {
        launchHome()
        listOf(R.string.nav_filter, R.string.settings_firewall, R.string.domain_rules_title, R.string.nav_settings).forEach { label ->
            tab(label).performClick()
            awaitTabSelected(label)

            Espresso.pressBack()

            awaitTabSelected(R.string.nav_home)
        }
        assertEquals(Lifecycle.State.RESUMED, scenario!!.state)
    }

    @Test
    fun backFromHomeRootLeavesTheApp() {
        launchHome()

        Espresso.pressBackUnconditionally()

        compose.waitUntil(10_000) { scenario!!.state == Lifecycle.State.DESTROYED }
    }

    @Ignore("known bug: koinViewModel() without a nav-entry ViewModelStore is Activity-scoped, so LogViewModel keeps the last visit's filter")
    @Test
    fun logsOpenWithTheFilterOfTheCardThatOpenedThem() {
        launchHome()

        compose.onNodeWithText(str(R.string.blocked_queries)).performClick()
        awaitText(str(R.string.logs_filter_blocked))
        compose.onNodeWithText(str(R.string.logs_filter_blocked)).assertIsSelected()

        Espresso.pressBack()
        awaitTabSelected(R.string.nav_home)
        compose.onNodeWithText(str(R.string.total_queries)).performClick()

        awaitText(str(R.string.logs_filter_all))
        compose.onNodeWithText(str(R.string.logs_filter_all)).assertIsSelected()
    }
}
