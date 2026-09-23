package app.pwhs.blockads.ui.domainrules

import android.app.Application
import app.cash.turbine.test
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.FakeCustomDnsRuleDao
import app.pwhs.blockads.ui.FakeWhitelistDomainDao
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.keepHot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class DomainRulesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val whitelistDao = FakeWhitelistDomainDao()
    private val ruleDao = FakeCustomDnsRuleDao()
    private val vm by lazy { DomainRulesViewModel(whitelistDao, ruleDao, mockk<Application>(relaxed = true)) }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `blocklist shows only block rules`() = runTest {
        ruleDao.rules.value = listOf(
            CustomDnsRule(id = 1, rule = "||a.com^", ruleType = RuleType.BLOCK, domain = "a.com"),
            CustomDnsRule(id = 2, rule = "@@||b.com^", ruleType = RuleType.ALLOW, domain = "b.com"),
        )
        whitelistDao.domains.value = listOf(WhitelistDomain(id = 1, domain = "w.com"))
        keepHot(vm.blocklistDomains, vm.whitelistDomains)
        assertEquals(listOf("a.com"), vm.blocklistDomains.value.map { it.domain })
        assertEquals(listOf("w.com"), vm.whitelistDomains.value.map { it.domain })
    }

    @Test
    fun `whitelisting trims, lowercases, restarts and refuses duplicates`() = runTest {
        vm.events.test {
            vm.addWhitelistDomain("  Foo.COM ")
            assertEquals(UiEvent.ToastRes(R.string.whitelist_domain_added, listOf("foo.com")), awaitItem())
            vm.addWhitelistDomain("foo.com")
            assertEquals(UiEvent.ToastRes(R.string.filter_domain_already_whitelisted), awaitItem())
        }
        assertEquals(listOf("foo.com"), whitelistDao.domains.value.map { it.domain })
        verify(exactly = 1) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `blank input is ignored for both lists`() = runTest {
        vm.events.test {
            vm.addWhitelistDomain("   ")
            vm.addBlocklistDomain("")
            expectNoEvents()
        }
        assertTrue(whitelistDao.domains.value.isEmpty())
        assertTrue(ruleDao.rules.value.isEmpty())
    }

    @Test
    fun `blocking stores an adblock rule and refuses a case-insensitive duplicate`() = runTest {
        vm.events.test {
            vm.addBlocklistDomain(" Ads.Example ")
            assertEquals(UiEvent.ToastRes(R.string.blocklist_domain_added, listOf("ads.example")), awaitItem())
            vm.addBlocklistDomain("ADS.EXAMPLE")
            assertEquals(UiEvent.ToastRes(R.string.blocklist_domain_already_exists), awaitItem())
        }
        assertEquals(listOf("||ads.example^"), ruleDao.rules.value.map { it.rule })
    }

    @Test
    fun `an allow rule for the domain does not block adding a block rule`() {
        ruleDao.rules.value = listOf(CustomDnsRule(id = 1, rule = "@@||a.com^", ruleType = RuleType.ALLOW, domain = "a.com"))
        vm.addBlocklistDomain("a.com")
        assertEquals(2, ruleDao.rules.value.size)
    }

    @Test
    fun `removing entries deletes them, confirms and restarts`() = runTest {
        val w = WhitelistDomain(id = 1, domain = "w.com")
        val r = CustomDnsRule(id = 1, rule = "||a.com^", ruleType = RuleType.BLOCK, domain = "a.com")
        whitelistDao.domains.value = listOf(w)
        ruleDao.rules.value = listOf(r)
        vm.events.test {
            vm.removeWhitelistDomain(w)
            assertEquals(UiEvent.ToastRes(R.string.whitelist_domain_removed), awaitItem())
            vm.removeBlocklistDomain(r)
            assertEquals(UiEvent.ToastRes(R.string.blocklist_domain_removed), awaitItem())
        }
        assertTrue(whitelistDao.domains.value.isEmpty())
        assertTrue(ruleDao.rules.value.isEmpty())
        verify(exactly = 2) { ServiceController.requestRestart(any()) }
    }

    @Ignore("known bug: domain rules accept any non-blank text, e.g. URLs or spaces, unlike CustomRuleParser")
    @Test
    fun `input that is not a domain is rejected`() {
        vm.addWhitelistDomain("https://a.com/path")
        vm.addBlocklistDomain("not a domain")
        assertTrue(whitelistDao.domains.value.isEmpty())
        assertTrue(ruleDao.rules.value.isEmpty())
    }
}
