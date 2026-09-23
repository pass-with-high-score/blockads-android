package app.pwhs.blockads.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionQualityProbeTest {

    @Test
    fun healthyWhenPhysicalUpAndEngineRunning() {
        assertEquals(ConnectionStatus.HEALTHY, ConnectionQualityProbe.classify(physicalOk = true, engineOk = true))
    }

    @Test
    fun stalledOnlyWhenEngineStopped() {
        assertEquals(ConnectionStatus.VPN_TUNNEL_STALLED, ConnectionQualityProbe.classify(physicalOk = true, engineOk = false))
    }

    @Test
    fun physicalLossWinsOverEngineState() {
        assertEquals(ConnectionStatus.NO_PHYSICAL_INTERNET, ConnectionQualityProbe.classify(physicalOk = false, engineOk = false))
    }
}
