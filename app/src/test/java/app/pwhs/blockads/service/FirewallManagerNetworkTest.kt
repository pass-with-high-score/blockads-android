package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.entities.FirewallRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FirewallManagerNetworkTest {

    private enum class Net { NONE, NO_CAPS, WIFI, CELLULAR, ETHERNET }

    private var net = Net.WIFI
    private val network: Network = mockk()
    private val cm: ConnectivityManager = mockk {
        every { activeNetwork } answers { if (net == Net.NONE) null else network }
        every { getNetworkCapabilities(network) } answers {
            if (net == Net.NO_CAPS) null else mockk<NetworkCapabilities> {
                every { hasTransport(any()) } returns false
                every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns (net == Net.WIFI)
                every { hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns (net == Net.CELLULAR)
            }
        }
    }
    private val context: Context = mockk { every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm }
    private var rules = listOf<FirewallRule>()
    private val dao: FirewallRuleDao = mockk { coEvery { getEnabledRules() } answers { rules } }

    private suspend fun manager(vararg r: FirewallRule) = FirewallManager(context, dao).also {
        rules = r.toList()
        it.loadRules()
    }

    private fun rule(pkg: String = "app", wifi: Boolean = true, mobile: Boolean = true, enabled: Boolean = true) =
        FirewallRule(packageName = pkg, blockWifi = wifi, blockMobileData = mobile, isEnabled = enabled)

    @Test
    fun `unknown, empty and disabled packages pass`() = runTest {
        val m = manager(rule(enabled = false, pkg = "off"))
        assertFalse(m.shouldBlock(""))
        assertFalse(m.shouldBlock("unknown"))
        assertFalse(m.shouldBlock("off"))
    }

    @Test
    fun `wifi and cellular follow their own flags`() = runTest {
        val m = manager(rule("wifiOnly", wifi = true, mobile = false), rule("mobileOnly", wifi = false, mobile = true))
        net = Net.WIFI
        assertTrue(m.shouldBlock("wifiOnly"))
        assertFalse(m.shouldBlock("mobileOnly"))
        net = Net.CELLULAR
        assertFalse(m.shouldBlock("wifiOnly"))
        assertTrue(m.shouldBlock("mobileOnly"))
    }

    @Test
    fun `other transports block when either flag is set`() = runTest {
        val m = manager(rule("either", wifi = false, mobile = true), rule("neither", wifi = false, mobile = false))
        net = Net.ETHERNET
        assertTrue(m.shouldBlock("either"))
        assertFalse(m.shouldBlock("neither"))
    }

    @Test
    fun `no network or no capabilities blocks`() = runTest {
        val m = manager(rule(wifi = false, mobile = false))
        net = Net.NONE
        assertTrue(m.shouldBlock("app"))
        net = Net.NO_CAPS
        assertTrue(m.shouldBlock("app"))
    }

    @Test
    fun `schedule gates an otherwise matching rule`() = runTest {
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val away = (now + 180) % 1440
        val allDay = rule("allDay").copy(scheduleEnabled = true, scheduleStartHour = 0, scheduleStartMinute = 0, scheduleEndHour = 23, scheduleEndMinute = 59)
        val elsewhen = rule("elsewhen").copy(
            scheduleEnabled = true,
            scheduleStartHour = away / 60, scheduleStartMinute = away % 60,
            scheduleEndHour = away / 60, scheduleEndMinute = away % 60,
        )
        val m = manager(allDay, elsewhen)
        assertTrue(m.shouldBlock("allDay"))
        assertFalse(m.shouldBlock("elsewhen"))
    }

    @Test
    fun `reloading replaces the cached rules`() = runTest {
        val m = manager(rule("first"))
        rules = listOf(rule("second"))
        m.loadRules()
        assertFalse(m.shouldBlock("first"))
        assertTrue(m.shouldBlock("second"))
    }
}
