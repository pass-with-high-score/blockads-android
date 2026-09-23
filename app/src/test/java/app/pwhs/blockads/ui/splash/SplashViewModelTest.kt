package app.pwhs.blockads.ui.splash

import app.cash.turbine.test
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SplashViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private fun prefs(completed: Boolean): AppPreferences = mockk {
        every { onboardingCompleted } returns flowOf(completed)
    }

    @Test
    fun `a finished onboarding routes home after the splash delay`() = runTest {
        val vm = SplashViewModel(prefs(true))
        vm.events.test {
            advanceTimeBy(499)
            expectNoEvents()
            assertEquals(SplashEvent.Home, awaitItem())
        }
    }

    @Test
    fun `a first run routes to onboarding`() = runTest {
        val vm = SplashViewModel(prefs(false))
        vm.events.test {
            assertEquals(SplashEvent.Onboarding, awaitItem())
        }
    }
}
