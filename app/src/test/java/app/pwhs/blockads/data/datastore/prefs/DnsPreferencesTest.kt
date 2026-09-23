package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.preferences.core.edit
import app.pwhs.blockads.data.entities.DnsProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DnsPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val store by lazy { tempFolder.newPreferencesDataStore() }
    private val prefs by lazy { DnsPreferences(store) }

    @Test
    fun `defaults`() = runTest {
        assertEquals(DnsPreferences.DEFAULT_UPSTREAM_DNS, prefs.upstreamDns.first())
        assertEquals(DnsPreferences.DEFAULT_FALLBACK_DNS, prefs.fallbackDns.first())
        assertEquals(DnsProtocol.PLAIN, prefs.dnsProtocol.first())
        assertEquals(DnsPreferences.DEFAULT_DOH_URL, prefs.dohUrl.first())
        assertNull(prefs.dnsProviderId.first())
        assertEquals(DnsPreferences.DNS_RESPONSE_CUSTOM_IP, prefs.dnsResponseType.first())
        assertEquals("", prefs.splitDnsZones.first())
        assertFalse(prefs.blockDohBypass.first())
        assertFalse(prefs.getBlockDohBypassSnapshot())
    }

    @Test
    fun `round trips`() = runTest {
        prefs.setUpstreamDns("1.1.1.1")
        prefs.setFallbackDns("8.8.8.8")
        prefs.setDohUrl("https://dns.example/dns-query")
        prefs.setDnsResponseType(DnsPreferences.DNS_RESPONSE_REFUSED)
        prefs.setSplitDnsZones("corp.example=10.0.0.53")
        prefs.setBlockDohBypass(true)

        assertEquals("1.1.1.1", prefs.upstreamDns.first())
        assertEquals("8.8.8.8", prefs.fallbackDns.first())
        assertEquals("https://dns.example/dns-query", prefs.dohUrl.first())
        assertEquals(DnsPreferences.DNS_RESPONSE_REFUSED, prefs.dnsResponseType.first())
        assertEquals("corp.example=10.0.0.53", prefs.splitDnsZones.first())
        assertTrue(prefs.blockDohBypass.first())
        assertTrue(prefs.getBlockDohBypassSnapshot())
    }

    @Test
    fun `every protocol round trips`() = runTest {
        for (protocol in DnsProtocol.entries) {
            prefs.setDnsProtocol(protocol)
            assertEquals(protocol, prefs.dnsProtocol.first())
        }
    }

    @Test
    fun `unknown stored protocol falls back to PLAIN`() = runTest {
        store.edit { it[DnsPreferences.KEY_DNS_PROTOCOL] = "QUIC_FROM_THE_FUTURE" }
        assertEquals(DnsProtocol.PLAIN, prefs.dnsProtocol.first())
        store.edit { it[DnsPreferences.KEY_DNS_PROTOCOL] = "doh" }
        assertEquals(DnsProtocol.PLAIN, prefs.dnsProtocol.first())
    }

    @Test
    fun `null provider id removes the key`() = runTest {
        prefs.setDnsProviderId("quad9")
        assertEquals("quad9", prefs.dnsProviderId.first())

        prefs.setDnsProviderId(null)

        assertNull(prefs.dnsProviderId.first())
        assertFalse(store.data.first().contains(DnsPreferences.KEY_DNS_PROVIDER_ID))
    }
}
