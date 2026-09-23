package app.pwhs.blockads.data.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.prefs.newPreferencesDataStore
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directBoot by lazy { DirectBootPreferences(context) }
    private val prefs by lazy { AppPreferences(tempFolder.newPreferencesDataStore(), directBoot) }

    @Test
    fun `vpn, reconnect and routing changes are mirrored to direct boot storage`() = runTest {
        prefs.setVpnEnabled(true)
        prefs.setAutoReconnect(false)
        prefs.setRoutingMode(AppPreferences.ROUTING_MODE_WIREGUARD)

        assertTrue(prefs.vpnEnabled.first())
        assertFalse(prefs.autoReconnect.first())
        assertEquals(AppPreferences.ROUTING_MODE_WIREGUARD, prefs.getRoutingModeSnapshot())
        assertTrue(directBoot.wasVpnEnabled)
        assertFalse(directBoot.autoReconnect)
        assertEquals(AppPreferences.ROUTING_MODE_WIREGUARD, directBoot.routingMode)

        prefs.setVpnEnabled(false)
        assertFalse(DirectBootPreferences(context).wasVpnEnabled)
    }

    @Test
    fun `other settings are not mirrored`() = runTest {
        prefs.setFirewallEnabled(true)
        prefs.setUpstreamDns("1.1.1.1")
        assertFalse(directBoot.wasVpnEnabled)
        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, directBoot.routingMode)
    }

    @Test
    fun `facade defaults come from the section preferences`() = runTest {
        with(prefs) {
            assertFalse(vpnEnabled.first())
            assertTrue(autoReconnect.first())
            assertFalse(networkSwitchDelayEnabled.first())
            assertEquals(30, networkSwitchDelaySec.first())
            assertFalse(onboardingCompleted.first())
            assertTrue(whitelistedApps.first().isEmpty())
            assertFalse(dailySummaryEnabled.first())
            assertFalse(milestoneNotificationsEnabled.first())
            assertEquals(0L, lastMilestoneBlocked.first())
            assertEquals(0L, lastSeenMilestoneDialog.first())
            assertEquals(-1L, activeProfileId.first())
            assertTrue(recordDnsLogs.first())
            assertFalse(firewallEnabled.first())
            assertFalse(crashReportingEnabled.first())
            assertFalse(hideFromRecents.first())
            assertTrue(trustedSsids.first().isEmpty())
            assertFalse(pauseOnTrustedEnabled.first())
            assertFalse(pausedByTrusted.first())
            assertEquals("", pausedTrustedSsid.first())
            assertTrue(filterHttp3.first())
            assertEquals(AppPreferences.DEFAULT_UPSTREAM_DNS, upstreamDns.first())
            assertEquals(AppPreferences.DEFAULT_FALLBACK_DNS, fallbackDns.first())
            assertEquals(DnsProtocol.PLAIN, dnsProtocol.first())
            assertEquals(AppPreferences.DEFAULT_DOH_URL, dohUrl.first())
            assertNull(dnsProviderId.first())
            assertEquals(AppPreferences.DNS_RESPONSE_CUSTOM_IP, dnsResponseType.first())
            assertEquals("", splitDnsZones.first())
            assertFalse(blockDohBypass.first())
            assertEquals(AppPreferences.THEME_SYSTEM, themeMode.first())
            assertEquals(AppPreferences.LANGUAGE_SYSTEM, appLanguage.first())
            assertEquals(AppPreferences.ACCENT_GREEN, accentColor.first())
            assertTrue(showBottomNavLabels.first())
            assertEquals(AppPreferences.DEFAULT_FILTER_URL, filterUrl.first())
            assertTrue(autoUpdateEnabled.first())
            assertEquals(AppPreferences.UPDATE_FREQUENCY_24H, autoUpdateFrequency.first())
            assertTrue(autoUpdateWifiOnly.first())
            assertEquals(AppPreferences.NOTIFICATION_SILENT, autoUpdateNotification.first())
            assertEquals(AppPreferences.PROTECTION_STANDARD, protectionLevel.first())
            assertFalse(safeSearchEnabled.first())
            assertFalse(youtubeRestrictedMode.first())
            assertEquals(AppPreferences.ROUTING_MODE_DIRECT, routingMode.first())
            assertTrue(wgProfiles.first().isEmpty())
            assertNull(wgActiveProfileId.first())
            assertFalse(excludeLan.first())
            assertFalse(allowAppBypass.first())
        }
    }

    @Test
    fun `facade setters write through to the section preferences`() = runTest {
        with(prefs) {
            setNetworkSwitchDelayEnabled(true)
            setNetworkSwitchDelaySec(60)
            setOnboardingCompleted(true)
            setWhitelistedApps(setOf("a"))
            toggleWhitelistedApp("b")
            setDailySummaryEnabled(true)
            setMilestoneNotificationsEnabled(true)
            setLastMilestoneBlocked(100)
            setLastSeenMilestoneDialog(50)
            setActiveProfileId(3)
            setRecordDnsLogs(false)
            setHttpsFilteringEnabled(true)
            setFilterHttp3(false)
            setSelectedBrowsers(setOf("com.browser"))
            setCrashReportingEnabled(true)
            setHideFromRecents(true)
            setTrustedSsids(setOf("Home"))
            toggleTrustedSsid("Office")
            setPauseOnTrustedEnabled(true)
            setPausedByTrusted(true, "Home")
            setFallbackDns("8.8.4.4")
            setDnsProtocol(DnsProtocol.DOT)
            setDohUrl("https://doh.example")
            setDnsProviderId("custom")
            setDnsResponseType(AppPreferences.DNS_RESPONSE_NXDOMAIN)
            setSplitDnsZones("lan=192.168.1.1")
            setBlockDohBypass(true)
            setThemeMode(AppPreferences.THEME_LIGHT)
            setAppLanguage(AppPreferences.LANGUAGE_DE)
            setAccentColor(AppPreferences.ACCENT_TEAL)
            setShowBottomNavLabels(false)
            setFilterUrl("https://f.example")
            setAutoUpdateEnabled(false)
            setAutoUpdateFrequency(AppPreferences.UPDATE_FREQUENCY_6H)
            setAutoUpdateWifiOnly(false)
            setAutoUpdateNotification(AppPreferences.NOTIFICATION_NORMAL)
            setProtectionLevel(AppPreferences.PROTECTION_BASIC)
            setSafeSearchEnabled(true)
            setYoutubeRestrictedMode(true)
            setExcludeLan(true)
            setAllowAppBypass(true)

            assertTrue(networkSwitchDelayEnabled.first())
            assertEquals(60, networkSwitchDelaySec.first())
            assertTrue(onboardingCompleted.first())
            assertEquals(setOf("a", "b"), getWhitelistedAppsSnapshot())
            assertTrue(dailySummaryEnabled.first())
            assertTrue(milestoneNotificationsEnabled.first())
            assertEquals(100L, lastMilestoneBlocked.first())
            assertEquals(50L, lastSeenMilestoneDialog.first())
            assertEquals(3L, activeProfileId.first())
            assertFalse(recordDnsLogs.first())
            assertTrue(getHttpsFilteringEnabledSnapshot())
            assertFalse(getFilterHttp3Snapshot())
            assertEquals(setOf("com.browser"), getSelectedBrowsersSnapshot())
            assertTrue(crashReportingEnabled.first())
            assertTrue(hideFromRecents.first())
            assertEquals(setOf("Home", "Office"), getTrustedSsidsSnapshot())
            assertTrue(getPauseOnTrustedEnabledSnapshot())
            assertTrue(getPausedByTrustedSnapshot())
            assertEquals("8.8.4.4", fallbackDns.first())
            assertEquals(DnsProtocol.DOT, dnsProtocol.first())
            assertEquals("https://doh.example", dohUrl.first())
            assertEquals(AppPreferences.CUSTOM_DNS_PROVIDER_ID, dnsProviderId.first())
            assertEquals(AppPreferences.DNS_RESPONSE_NXDOMAIN, dnsResponseType.first())
            assertEquals("lan=192.168.1.1", splitDnsZones.first())
            assertTrue(getBlockDohBypassSnapshot())
            assertEquals(AppPreferences.THEME_LIGHT, themeMode.first())
            assertEquals(AppPreferences.LANGUAGE_DE, appLanguage.first())
            assertEquals(AppPreferences.ACCENT_TEAL, accentColor.first())
            assertFalse(showBottomNavLabels.first())
            assertEquals("https://f.example", filterUrl.first())
            assertFalse(autoUpdateEnabled.first())
            assertEquals(AppPreferences.UPDATE_FREQUENCY_6H, autoUpdateFrequency.first())
            assertFalse(autoUpdateWifiOnly.first())
            assertEquals(AppPreferences.NOTIFICATION_NORMAL, autoUpdateNotification.first())
            assertEquals(AppPreferences.PROTECTION_BASIC, protectionLevel.first())
            assertTrue(safeSearchEnabled.first())
            assertTrue(youtubeRestrictedMode.first())
            assertTrue(excludeLan.first())
            assertTrue(allowAppBypass.first())
        }
    }

    @Test
    fun `facade WireGuard profile operations`() = runTest {
        val cfg = WireGuardConfig(WireGuardInterface("k", listOf("10.0.0.2/32")), emptyList())
        with(prefs) {
            addOrUpdateWgProfile(WireGuardProfile("a", "A", cfg), makeActive = true)
            addOrUpdateWgProfile(WireGuardProfile("b", "B", cfg))
            renameWgProfile("b", "Bee")
            setActiveWgProfile("b")
            assertEquals("Bee", getActiveWgProfileSnapshot()?.name)
            assertEquals(cfg.toJson(), getWgConfigJsonSnapshot())
            removeWgProfile("b")
            assertEquals(listOf("a"), getWgProfilesSnapshot().map { it.id })
            migrateLegacyWgConfigIfNeeded()
            clearAllWgProfiles()
            assertTrue(getWgProfilesSnapshot().isEmpty())
        }
    }
}
