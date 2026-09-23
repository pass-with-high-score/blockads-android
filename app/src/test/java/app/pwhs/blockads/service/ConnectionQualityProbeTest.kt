package app.pwhs.blockads.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionQualityProbeTest {

    private fun probe(physical: Boolean, vpnDns: Boolean) = spyk(ConnectionQualityProbe()).also {
        coEvery { it.probePhysicalInternet() } returns physical
        coEvery { it.probeVpnDns() } returns vpnDns
    }

    @Test
    fun `classifier truth table`() = runTest {
        val cases = listOf(
            Triple(false, false, ConnectionStatus.NO_PHYSICAL_INTERNET),
            Triple(false, true, ConnectionStatus.NO_PHYSICAL_INTERNET),
            Triple(true, false, ConnectionStatus.VPN_TUNNEL_STALLED),
            Triple(true, true, ConnectionStatus.HEALTHY),
        )
        for ((physical, dns, expected) in cases) {
            val result = probe(physical, dns).runDiagnosis()
            assertEquals("physical=$physical dns=$dns", expected, result.status)
            assertEquals(physical, result.physicalOk)
            assertEquals(physical && dns, result.vpnDnsOk)
        }
    }

    @Test
    fun `tunnel DNS is not probed without physical internet`() = runTest {
        val p = probe(physical = false, vpnDns = true)
        p.runDiagnosis()
        coVerify(exactly = 0) { p.probeVpnDns() }
    }
}
