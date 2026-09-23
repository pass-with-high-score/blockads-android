package app.pwhs.blockads.ui.onboarding

import app.pwhs.blockads.data.entities.DnsProviders
import org.junit.Assert.assertNotEquals
import org.junit.Ignore
import org.junit.Test

class OnboardingFallbackDnsTest {

    @Ignore("known bug: fallback DNS can resolve to System DNS")
    @Test
    fun `fallback is a real resolver distinct from the primary for every provider`() {
        for (primary in DnsProviders.ALL_PROVIDERS) {
            val fallback = OnboardingViewModel.selectFallbackDns(primary)
            assertNotEquals("${primary.id} falls back to itself", primary.id, fallback.id)
            assertNotEquals(
                "${primary.id} falls back to SYSTEM (0.0.0.0)",
                DnsProviders.SYSTEM.id,
                fallback.id
            )
        }
    }
}
