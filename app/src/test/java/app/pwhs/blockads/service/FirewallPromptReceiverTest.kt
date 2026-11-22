package app.pwhs.blockads.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.entities.FirewallRule
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FirewallPromptReceiverTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val firewallRuleDao: FirewallRuleDao = mockk(relaxed = true)
    private lateinit var promptManager: FirewallPromptManager

    @Before
    fun setUp() {
        io.mockk.coEvery { firewallRuleDao.getByPackageName(any()) } returns null
        promptManager = FirewallPromptManager(app, firewallRuleDao)
        startKoin {
            modules(module {
                single { promptManager }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        AdBlockVpnService.status.state.value = VpnState.STOPPED
    }

    @Test
    fun `ACTION_ALLOW persists rule as not blocked and restarts service`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        val receiver = FirewallPromptReceiver()
        val intent = Intent(FirewallPromptReceiver.ACTION_ALLOW).apply {
            putExtra(FirewallPromptReceiver.EXTRA_PACKAGE_NAME, "com.example.app")
        }

        receiver.onReceive(app, intent)

        delay(300)

        val slot = slot<FirewallRule>()
        coVerify { firewallRuleDao.insert(capture(slot)) }
        assertEquals("com.example.app", slot.captured.packageName)
        assertFalse(slot.captured.isEnabled)

        val startedService = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_RESTART, startedService?.action)
    }

    @Test
    fun `ACTION_BLOCK persists rule as blocked and restarts service`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        val receiver = FirewallPromptReceiver()
        val intent = Intent(FirewallPromptReceiver.ACTION_BLOCK).apply {
            putExtra(FirewallPromptReceiver.EXTRA_PACKAGE_NAME, "com.blocked.app")
        }

        receiver.onReceive(app, intent)

        delay(300)

        val slot = slot<FirewallRule>()
        coVerify { firewallRuleDao.insert(capture(slot)) }
        assertEquals("com.blocked.app", slot.captured.packageName)
        assertTrue(slot.captured.isEnabled)

        val startedService = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_RESTART, startedService?.action)
    }
}
