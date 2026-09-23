package app.pwhs.blockads.ui.onboarding

import android.app.Application
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.DnsProviders
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.onboarding.data.ProtectionLevel
import app.pwhs.blockads.utils.CrashReportingManager
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Fallback selection is covered by OnboardingFallbackDnsTest; this pins everything else. */
class OnboardingViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val appPrefs: AppPreferences = mockk(relaxed = true)
    private val vm by lazy { OnboardingViewModel(appPrefs, mockk<Application>(relaxed = true)) }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `defaults are standard protection with system DNS`() {
        assertEquals(ProtectionLevel.STANDARD, vm.selectedProtectionLevel.value)
        assertEquals(DnsProviders.SYSTEM, vm.selectedDnsProvider.value)
    }

    @Test
    fun `completing with a DoH provider saves level, provider, protocol and url, then marks done`() = runTest {
        vm.selectProtectionLevel(ProtectionLevel.STRICT)
        vm.selectDnsProvider(DnsProviders.CLOUDFLARE)
        vm.completeOnboarding()
        coVerifyOrder {
            appPrefs.setProtectionLevel("STRICT")
            appPrefs.setDnsProviderId("cloudflare")
            appPrefs.setUpstreamDns("1.1.1.1")
            appPrefs.setDnsProtocol(DnsProtocol.DOH)
            appPrefs.setDohUrl("https://cloudflare-dns.com/dns-query")
            appPrefs.setFallbackDns(any())
            appPrefs.setOnboardingCompleted(true)
        }
    }

    @Test
    fun `a quic provider is saved as DoQ`() = runTest {
        vm.selectDnsProvider(DnsProviders.QUAD9_DOQ)
        vm.completeOnboarding()
        coVerify { appPrefs.setDnsProtocol(DnsProtocol.DOQ) }
        coVerify { appPrefs.setDohUrl("quic://dns.quad9.net") }
    }

    @Test
    fun `a provider without an encrypted url is saved as plain DNS`() = runTest {
        vm.selectDnsProvider(DnsProviders.OPENDNS)
        vm.completeOnboarding()
        coVerify { appPrefs.setDnsProtocol(DnsProtocol.PLAIN) }
        coVerify(exactly = 0) { appPrefs.setDohUrl(any()) }
        coVerify { appPrefs.setOnboardingCompleted(true) }
    }

    @Test
    fun `crash reporting choice is saved and applied immediately`() {
        mockkObject(CrashReportingManager)
        every { CrashReportingManager.toggleSentry(any(), any()) } just Runs
        vm.setCrashReportingEnabled(true)
        coVerify { appPrefs.setCrashReportingEnabled(true) }
        verify { CrashReportingManager.toggleSentry(any(), true) }
    }
}
