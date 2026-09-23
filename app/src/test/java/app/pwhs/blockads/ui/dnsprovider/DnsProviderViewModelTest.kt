package app.pwhs.blockads.ui.dnsprovider

import android.app.Application
import app.cash.turbine.test
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.keepHot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class DnsProviderViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val providerId = MutableStateFlow<String?>(null)
    private val upstream = MutableStateFlow(AppPreferences.DEFAULT_UPSTREAM_DNS)
    private val fallback = MutableStateFlow(AppPreferences.DEFAULT_FALLBACK_DNS)
    private val protocol = MutableStateFlow(DnsProtocol.PLAIN)
    private val doh = MutableStateFlow("")
    private val blockDoh = MutableStateFlow(false)

    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { dnsProviderId } returns providerId
        every { upstreamDns } returns upstream
        every { fallbackDns } returns fallback
        every { dnsProtocol } returns protocol
        every { dohUrl } returns doh
        every { blockDohBypass } returns blockDoh
        coEvery { setDnsProviderId(any()) } coAnswers { providerId.value = firstArg() }
        coEvery { setUpstreamDns(any()) } coAnswers { upstream.value = firstArg() }
        coEvery { setFallbackDns(any()) } coAnswers { fallback.value = firstArg() }
        coEvery { setDnsProtocol(any()) } coAnswers { protocol.value = firstArg() }
        coEvery { setDohUrl(any()) } coAnswers { doh.value = firstArg() }
    }
    private val vm by lazy { DnsProviderViewModel(appPrefs, mockk<Application>(relaxed = true)) }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
    }

    @After
    fun tearDown() = unmockkAll()

    private fun TestScope.hot() = keepHot(vm.selectedProviderId, vm.customDnsEnabled, vm.customDnsDisplay, vm.blockDohBypass)

    @Test
    fun `parsed host strips schemes and DoH paths`() {
        val cases = mapOf(
            " 1.1.1.1 " to "1.1.1.1",
            "https://dns.google/dns-query" to "dns.google",
            "HTTPS://Dns.Example/q?x=1" to "Dns.Example",
            "tls://dns.google" to "dns.google",
            "TLS://dns.google" to "dns.google",
            "quic://dns.adguard-dns.com" to "dns.adguard-dns.com",
            "QUIC://dns.quad9.net:853" to "dns.quad9.net",
            "2606:4700:4700::1111" to "2606:4700:4700::1111",
        )
        cases.forEach { (input, host) -> assertEquals(input, host, vm.getParsedHost(input)) }
    }

    @Ignore("known bug: getParsedHost strips only all-lower or all-upper tls:// prefixes, so 'Tls://host' is saved as the upstream")
    @Test
    fun `parsed host handles mixed-case tls scheme`() {
        assertEquals("dns.google", vm.getParsedHost("Tls://dns.google"))
    }

    @Ignore("known bug: an IPv6 DoH literal keeps its brackets, so the upstream is saved as '[2606:4700::1111]'")
    @Test
    fun `parsed host unwraps bracketed IPv6 DoH hosts`() {
        assertEquals("2606:4700::1111", vm.getParsedHost("https://[2606:4700::1111]/dns-query"))
    }

    @Test
    fun `selecting a DoH provider stores its url and restarts`() = runTest {
        hot()
        vm.selectProvider(DnsProviders.CLOUDFLARE)
        assertEquals("cloudflare", providerId.value)
        assertEquals("1.1.1.1", upstream.value)
        assertEquals(DnsProtocol.DOH, protocol.value)
        assertEquals("https://cloudflare-dns.com/dns-query", doh.value)
        assertEquals("cloudflare", vm.selectedProviderId.value)
        assertFalse(vm.customDnsEnabled.value)
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `selecting a quic provider uses DoQ and a plain provider uses plain DNS`() {
        vm.selectProvider(DnsProviders.QUAD9_DOQ)
        assertEquals(DnsProtocol.DOQ, protocol.value)
        assertEquals("quic://dns.quad9.net", doh.value)

        vm.selectProvider(DnsProviders.OPENDNS)
        assertEquals(DnsProtocol.PLAIN, protocol.value)
        assertEquals("208.67.222.222", upstream.value)
    }

    @Test
    fun `the fallback is swapped only when it would equal the new primary`() {
        fallback.value = "1.1.1.1"
        vm.selectProvider(DnsProviders.GOOGLE)
        assertEquals("unchanged when distinct", "1.1.1.1", fallback.value)

        fallback.value = DnsProviders.QUAD9.ipAddress
        vm.selectProvider(DnsProviders.QUAD9_DOQ)
        assertEquals(DnsProviders.ADGUARD.ipAddress, fallback.value)

        vm.selectProvider(DnsProviders.ADGUARD)
        assertEquals(DnsProviders.QUAD9.ipAddress, fallback.value)

        fallback.value = DnsProviders.MULLVAD.ipAddress
        vm.selectProvider(DnsProviders.MULLVAD)
        assertEquals("first other privacy provider", DnsProviders.ADGUARD.ipAddress, fallback.value)
    }

    @Test
    fun `a custom provider id is never shown as a preset`() = runTest {
        providerId.value = AppPreferences.CUSTOM_DNS_PROVIDER_ID
        upstream.value = "1.1.1.1"
        hot()
        assertNull(vm.selectedProviderId.value)
        assertTrue(vm.customDnsEnabled.value)
    }

    @Test
    fun `without a stored id a plain preset ip is recognized but not over DoH`() = runTest {
        upstream.value = DnsProviders.OPENDNS.ipAddress
        hot()
        assertEquals("opendns", vm.selectedProviderId.value)

        protocol.value = DnsProtocol.DOH
        assertNull(vm.selectedProviderId.value)

        protocol.value = DnsProtocol.PLAIN
        upstream.value = DnsProviders.CLOUDFLARE.ipAddress
        assertNull("preset with DoH needs its id stored", vm.selectedProviderId.value)
    }

    @Test
    fun `custom display shows the server in the form the user typed`() = runTest {
        hot()
        upstream.value = "8.8.8.8"
        assertEquals("8.8.8.8", vm.customDnsDisplay.value)

        doh.value = "https://dns.google/dns-query"
        protocol.value = DnsProtocol.DOH
        assertEquals("https://dns.google/dns-query", vm.customDnsDisplay.value)

        upstream.value = "dns.google"
        protocol.value = DnsProtocol.DOT
        assertEquals("tls://dns.google", vm.customDnsDisplay.value)

        protocol.value = DnsProtocol.DOQ
        assertEquals("quic://dns.google/dns-query", vm.customDnsDisplay.value)
        doh.value = "QUIC://dns.quad9.net"
        assertEquals("QUIC://dns.quad9.net", vm.customDnsDisplay.value)
    }

    @Test
    fun `custom DoH, DoQ, DoT and plain entries set protocol and host`() {
        vm.setCustomDns(" https://dns.example/dns-query ")
        assertEquals(AppPreferences.CUSTOM_DNS_PROVIDER_ID, providerId.value)
        assertEquals(DnsProtocol.DOH, protocol.value)
        assertEquals("https://dns.example/dns-query", doh.value)
        assertEquals("dns.example", upstream.value)

        vm.setCustomDns("quic://doq.example")
        assertEquals(DnsProtocol.DOQ, protocol.value)
        assertEquals("quic://doq.example", doh.value)

        vm.setCustomDns("tls://dot.example")
        assertEquals(DnsProtocol.DOT, protocol.value)
        assertEquals("dot.example", upstream.value)

        vm.setCustomDns("8.8.4.4")
        assertEquals(DnsProtocol.PLAIN, protocol.value)
        assertEquals("8.8.4.4", upstream.value)
        verify(exactly = 4) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `blank custom input is ignored`() {
        vm.setCustomDns("   ")
        coVerify(exactly = 0) { appPrefs.setDnsProviderId(any()) }
    }

    @Test
    fun `a plain custom server equal to the fallback is refused`() = runTest {
        fallback.value = "8.8.8.8 "
        vm.events.test {
            vm.setCustomDns("8.8.8.8")
            assertEquals(UiEvent.ToastRes(R.string.dns_error_duplicate), awaitItem())
        }
        coVerify(exactly = 0) { appPrefs.setUpstreamDns(any()) }
    }

    @Test
    fun `an encrypted custom server may share the fallback host`() {
        fallback.value = "dns.google"
        vm.setCustomDns("tls://dns.google")
        assertEquals(DnsProtocol.DOT, protocol.value)
    }

    @Test
    fun `a fallback equal to a plain upstream is refused, case-insensitively`() = runTest {
        upstream.value = "dns.example"
        vm.events.test {
            vm.setFallbackDns(" DNS.example ")
            assertEquals(UiEvent.ToastRes(R.string.dns_error_duplicate), awaitItem())
        }
        coVerify(exactly = 0) { appPrefs.setFallbackDns(any()) }
    }

    @Test
    fun `a fallback may equal the host of an encrypted upstream`() {
        upstream.value = "1.1.1.1"
        protocol.value = DnsProtocol.DOH
        vm.setFallbackDns("1.1.1.1 ")
        assertEquals("1.1.1.1", fallback.value)
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `DoH bypass blocking persists`() = runTest {
        hot()
        vm.setBlockDohBypass(true)
        coVerify { appPrefs.setBlockDohBypass(true) }
        blockDoh.value = true
        assertTrue(vm.blockDohBypass.value)
    }

    @Test
    fun `upstream and fallback are shared eagerly`() {
        upstream.value = "4.4.4.4"
        fallback.value = "5.5.5.5"
        assertEquals("4.4.4.4", vm.upstreamDns.value)
        assertEquals("5.5.5.5", vm.fallbackDns.value)
    }
}
