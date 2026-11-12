package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.entities.FirewallRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallManagerPromptTest {

    private val context: Context = mockk(relaxed = true)
    private var rules = listOf<FirewallRule>()
    private val dao: FirewallRuleDao = mockk { coEvery { getAllSnapshot() } answers { rules } }
    private val prompt: FirewallPromptManager = mockk(relaxed = true) {
        every { onUndecidedRequest(any()) } returns true
    }

    private suspend fun manager(promptEnabled: Boolean, vararg r: FirewallRule) =
        FirewallManager(context, dao, promptEnabled, prompt).also {
            rules = r.toList()
            it.loadRules()
        }

    @Test
    fun `explicitly allowed rule is never blocked or prompted`() = runTest {
        val m = manager(true, FirewallRule(packageName = "ok", isEnabled = false))
        assertFalse(m.shouldBlock("ok"))
        assertEquals(FirewallDecision.ALLOWED, m.decisionFor("ok"))
        verify(exactly = 0) { prompt.onUndecidedRequest(any()) }
    }

    @Test
    fun `undecided app is held back and prompted when prompt mode is on`() = runTest {
        val m = manager(true)
        assertEquals(FirewallDecision.UNDECIDED, m.decisionFor("new.app"))
        assertTrue(m.shouldBlock("new.app"))
        verify { prompt.onUndecidedRequest("new.app") }
    }

    @Test
    fun `undecided app passes when prompt mode is off`() = runTest {
        val m = manager(false)
        assertFalse(m.shouldBlock("new.app"))
        assertFalse(m.hasActiveRules())
        verify(exactly = 0) { prompt.onUndecidedRequest(any()) }
    }

    @Test
    fun `prompt mode keeps the engine checker active without blocking rules`() = runTest {
        assertTrue(manager(true).hasActiveRules())
        assertFalse(manager(false, FirewallRule(packageName = "ok", isEnabled = false)).hasActiveRules())
    }

    @Test
    fun `applied decision takes effect immediately`() = runTest {
        val m = manager(true)
        m.applyRule(FirewallRule(packageName = "new.app", isEnabled = false))
        assertFalse(m.shouldBlock("new.app"))
        assertEquals(FirewallDecision.ALLOWED, m.decisionFor("new.app"))
    }
}
