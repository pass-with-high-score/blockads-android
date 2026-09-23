package app.pwhs.blockads.data.datastore.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VpnSecurityPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefs by lazy { VpnSecurityPreferences(tempFolder.newPreferencesDataStore()) }

    @Test
    fun `defaults`() = runTest {
        with(prefs) {
            assertFalse(vpnEnabled.first())
            assertTrue(autoReconnect.first())
            assertFalse(networkSwitchDelayEnabled.first())
            assertEquals(30, networkSwitchDelaySec.first())
            assertFalse(onboardingCompleted.first())
            assertEquals(emptySet<String>(), whitelistedApps.first())
            assertFalse(dailySummaryEnabled.first())
            assertFalse(milestoneNotificationsEnabled.first())
            assertEquals(0L, lastMilestoneBlocked.first())
            assertEquals(0L, lastSeenMilestoneDialog.first())
            assertEquals(-1L, activeProfileId.first())
            assertTrue(recordDnsLogs.first())
            assertFalse(firewallEnabled.first())
            assertFalse(httpsFilteringEnabled.first())
            assertFalse(getHttpsFilteringEnabledSnapshot())
            assertTrue(filterHttp3.first())
            assertTrue(getFilterHttp3Snapshot())
            assertEquals(emptySet<String>(), getSelectedBrowsersSnapshot())
            assertFalse(crashReportingEnabled.first())
            assertFalse(hideFromRecents.first())
            assertEquals(emptySet<String>(), trustedSsids.first())
            assertEquals(emptySet<String>(), getTrustedSsidsSnapshot())
            assertFalse(pauseOnTrustedEnabled.first())
            assertFalse(getPauseOnTrustedEnabledSnapshot())
            assertFalse(pausedByTrusted.first())
            assertFalse(getPausedByTrustedSnapshot())
            assertEquals("", pausedTrustedSsid.first())
        }
    }

    @Test
    fun `boolean and number round trips`() = runTest {
        with(prefs) {
            setVpnEnabled(true)
            setAutoReconnect(false)
            setNetworkSwitchDelayEnabled(true)
            setOnboardingCompleted(true)
            setDailySummaryEnabled(true)
            setMilestoneNotificationsEnabled(true)
            setLastMilestoneBlocked(10_000)
            setLastSeenMilestoneDialog(5_000)
            setActiveProfileId(7)
            setRecordDnsLogs(false)
            setFirewallEnabled(true)
            setHttpsFilteringEnabled(true)
            setFilterHttp3(false)
            setCrashReportingEnabled(true)
            setHideFromRecents(true)
            setPauseOnTrustedEnabled(true)

            assertTrue(vpnEnabled.first())
            assertFalse(autoReconnect.first())
            assertTrue(networkSwitchDelayEnabled.first())
            assertTrue(onboardingCompleted.first())
            assertTrue(dailySummaryEnabled.first())
            assertTrue(milestoneNotificationsEnabled.first())
            assertEquals(10_000L, lastMilestoneBlocked.first())
            assertEquals(5_000L, lastSeenMilestoneDialog.first())
            assertEquals(7L, activeProfileId.first())
            assertFalse(recordDnsLogs.first())
            assertTrue(firewallEnabled.first())
            assertTrue(getHttpsFilteringEnabledSnapshot())
            assertFalse(getFilterHttp3Snapshot())
            assertTrue(crashReportingEnabled.first())
            assertTrue(hideFromRecents.first())
            assertTrue(getPauseOnTrustedEnabledSnapshot())
        }
    }

    @Test
    fun `network switch delay is clamped to 5-120 seconds`() = runTest {
        prefs.setNetworkSwitchDelaySec(1)
        assertEquals(5, prefs.networkSwitchDelaySec.first())
        prefs.setNetworkSwitchDelaySec(500)
        assertEquals(120, prefs.networkSwitchDelaySec.first())
        prefs.setNetworkSwitchDelaySec(45)
        assertEquals(45, prefs.networkSwitchDelaySec.first())
    }

    @Test
    fun `whitelisted apps set and toggle`() = runTest {
        prefs.setWhitelistedApps(setOf("a.pkg", "b.pkg"))
        prefs.toggleWhitelistedApp("a.pkg")
        prefs.toggleWhitelistedApp("c.pkg")
        assertEquals(setOf("b.pkg", "c.pkg"), prefs.getWhitelistedAppsSnapshot())
        prefs.toggleWhitelistedApp("c.pkg")
        prefs.toggleWhitelistedApp("b.pkg")
        assertEquals(emptySet<String>(), prefs.whitelistedApps.first())
    }

    @Test
    fun `trusted ssids set and toggle`() = runTest {
        prefs.toggleTrustedSsid("Home")
        prefs.toggleTrustedSsid("Office")
        assertEquals(setOf("Home", "Office"), prefs.getTrustedSsidsSnapshot())
        prefs.toggleTrustedSsid("Home")
        assertEquals(setOf("Office"), prefs.trustedSsids.first())
        prefs.setTrustedSsids(setOf("Cafe"))
        assertEquals(setOf("Cafe"), prefs.trustedSsids.first())
    }

    @Test
    fun `selected browsers snapshot reads the stored set`() = runTest {
        prefs.setSelectedBrowsers(setOf("com.android.chrome"))
        assertEquals(setOf("com.android.chrome"), prefs.getSelectedBrowsersSnapshot())
    }

    @Test
    fun `pausing by trusted network records the ssid and resuming clears it`() = runTest {
        prefs.setPausedByTrusted(true, "Home")
        assertTrue(prefs.getPausedByTrustedSnapshot())
        assertEquals("Home", prefs.pausedTrustedSsid.first())

        prefs.setPausedByTrusted(false, "Ignored")
        assertFalse(prefs.pausedByTrusted.first())
        assertEquals("", prefs.pausedTrustedSsid.first())
    }
}
