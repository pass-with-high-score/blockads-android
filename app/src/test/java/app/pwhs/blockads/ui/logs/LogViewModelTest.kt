package app.pwhs.blockads.ui.logs

import android.app.Application
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.ui.FakeCustomDnsRuleDao
import app.pwhs.blockads.ui.FakeWhitelistDomainDao
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.keepHot
import app.pwhs.blockads.ui.logs.data.LogFilterStatus
import app.pwhs.blockads.ui.logs.data.TimeRange
import app.pwhs.blockads.ui.settle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 10_000_000_000L
    private val table = MutableStateFlow<List<DnsLogEntry>>(emptyList())
    private val sinceArgs = mutableListOf<Long>()

    private fun since(cutoff: Long) = table.map { rows -> rows.filter { it.timestamp >= cutoff } }

    private val dnsLogDao: DnsLogDao = mockk(relaxed = true) {
        every { getAll() } returns table
        every { getBlockedOnly() } returns table.map { rows -> rows.filter { it.isBlocked } }
        every { getBlockedByReason(FilterListRepository.BLOCK_REASON_SECURITY) } returns
            table.map { rows -> rows.filter { it.blockedBy == FilterListRepository.BLOCK_REASON_SECURITY } }
        every { getAllSince(any()) } answers { sinceArgs += firstArg<Long>(); since(firstArg()) }
        every { getBlockedOnlySince(any()) } answers {
            sinceArgs += firstArg<Long>(); since(firstArg<Long>()).map { rows -> rows.filter { it.isBlocked } }
        }
        every { getBlockedByReasonSince(any(), any()) } answers {
            sinceArgs += secondArg<Long>(); since(secondArg<Long>()).map { rows -> rows.filter { it.blockedBy == firstArg() } }
        }
        every { getDistinctAppNames() } returns flowOf(listOf("Chrome"))
    }
    private val filterListDao: FilterListDao = mockk(relaxed = true) {
        every { getAll() } returns flowOf(listOf(FilterList(id = 7, name = "EasyList", url = "u")))
    }
    private val whitelistDao = FakeWhitelistDomainDao()
    private val ruleDao = FakeCustomDnsRuleDao()
    private val repo: FilterListRepository = mockk(relaxed = true)
    private val recordLogs = MutableStateFlow(true)
    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { recordDnsLogs } returns recordLogs
    }

    private val vm by lazy { LogViewModel(dnsLogDao, filterListDao, whitelistDao, ruleDao, repo, appPrefs, app) { now } }

    @Before
    fun resetFileProviderCache() {
        // FileProvider caches its roots statically; each Robolectric test gets a new data dir.
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun entry(
        id: Long, domain: String, blocked: Boolean, app: String = "", at: Long = now, by: String = "",
    ) = DnsLogEntry(id = id, domain = domain, timestamp = at, isBlocked = blocked, appName = app, blockedBy = by)

    @Test
    fun `status filter picks blocked-only and threat queries`() = runTest {
        table.value = listOf(
            entry(1, "ok.com", false),
            entry(2, "ad.com", true, by = "7"),
            entry(3, "mal.com", true, by = FilterListRepository.BLOCK_REASON_SECURITY),
        )
        keepHot(vm.logs, vm.showBlockedOnly)
        assertEquals(listOf(1L, 2L, 3L), vm.logs.value.map { it.id })

        vm.toggleFilter()
        assertTrue(vm.showBlockedOnly.value)
        assertEquals(listOf(2L, 3L), vm.logs.value.map { it.id })

        vm.setFilterStatus(LogFilterStatus.THREATS)
        assertEquals(listOf(3L), vm.logs.value.map { it.id })

        vm.setFilterStatus(LogFilterStatus.BLOCKED)
        vm.toggleFilter()
        assertEquals(LogFilterStatus.ALL, vm.filterStatus.value)
    }

    @Test
    fun `time range queries from now minus the range for every status`() = runTest {
        keepHot(vm.logs)
        vm.setTimeRange(TimeRange.HOUR_1)
        vm.setFilterStatus(LogFilterStatus.BLOCKED)
        vm.setFilterStatus(LogFilterStatus.THREATS)
        assertEquals(List(3) { now - TimeRange.HOUR_1.millis }, sinceArgs)
    }

    @Test
    fun `search matches domain or app, trimmed and case-insensitive, combined with the app filter`() = runTest {
        table.value = listOf(
            entry(1, "Tracker.example", true, app = "Chrome"),
            entry(2, "cdn.example", false, app = "TrackerApp"),
            entry(3, "other.org", false, app = "Chrome"),
        )
        keepHot(vm.logs)

        vm.setSearchQuery("  tracker ")
        assertEquals(listOf(1L, 2L), vm.logs.value.map { it.id })

        vm.setAppFilter("chrome")
        assertEquals(listOf(1L), vm.logs.value.map { it.id })

        vm.setSearchQuery("")
        assertEquals(listOf(1L, 3L), vm.logs.value.map { it.id })
        assertEquals("chrome", vm.appFilter.value)
    }

    @Ignore("known bug: the time-range cutoff is computed once, so 'last hour' never slides")
    @Test
    fun `the last-hour window slides as time passes`() = runTest {
        table.value = listOf(entry(1, "old.com", false, at = now - 30 * 60_000))
        keepHot(vm.logs)
        vm.setTimeRange(TimeRange.HOUR_1)
        assertEquals(listOf(1L), vm.logs.value.map { it.id })

        now += 2 * 3_600_000L
        table.value = table.value + entry(2, "new.com", false, at = now)

        assertEquals(listOf(2L), vm.logs.value.map { it.id })
    }

    @Test
    fun `lookup flows expose lowercased whitelist, filter names and app names`() = runTest {
        whitelistDao.domains.value = listOf(WhitelistDomain(id = 1, domain = "MiXed.com"))
        keepHot(vm.whitelistedDomains, vm.filterNames, vm.appNames, vm.recordDnsLogs)
        assertEquals(setOf("mixed.com"), vm.whitelistedDomains.value)
        assertEquals(mapOf("7" to "EasyList"), vm.filterNames.value)
        assertEquals(listOf("Chrome"), vm.appNames.value)

        recordLogs.value = false
        assertFalse(vm.recordDnsLogs.value)
    }

    @Test
    fun `selection toggles ids in and out`() {
        vm.toggleSelection(4)
        vm.toggleSelection(5)
        vm.toggleSelection(4)
        assertEquals(setOf(5L), vm.selectedIds.value)
    }

    @Test
    fun `clearing logs and toggling recording hit storage`() {
        vm.clearLogs()
        vm.setRecordDnsLogs(false)
        coVerify { dnsLogDao.clearAll() }
        coVerify { appPrefs.setRecordDnsLogs(false) }
    }

    @Test
    fun `whitelisting lowercases, reloads and refuses duplicates`() = runTest {
        vm.events.test {
            vm.addToWhitelist("  Ads.Example ")
            assertEquals(UiEvent.ToastRes(R.string.log_whitelisted, listOf(": ads.example")), awaitItem())

            vm.addToWhitelist("ADS.example")
            assertEquals(UiEvent.ToastRes(R.string.log_already_whitelisted), awaitItem())
        }
        assertEquals(listOf("ads.example"), whitelistDao.domains.value.map { it.domain })
        coVerify(exactly = 1) { repo.loadWhitelist() }
    }

    @Test
    fun `blocking from the log adds one lowercased block rule`() = runTest {
        vm.events.test {
            vm.addToCustomBlockRules(" Ads.Example ")
            assertEquals(UiEvent.ToastRes(R.string.rule_added), awaitItem())
        }
        assertEquals(listOf("||ads.example^" to RuleType.BLOCK), ruleDao.rules.value.map { it.rule to it.ruleType })
        coVerify(exactly = 1) { repo.loadCustomRules() }
    }

    @Ignore("known bug: blocking a domain that is already blocked still toasts 'rule added'")
    @Test
    fun `blocking an already blocked domain does not claim it was added`() = runTest {
        vm.addToCustomBlockRules("ads.example")
        vm.events.test {
            vm.addToCustomBlockRules("ads.example")
            assertNotEquals(UiEvent.ToastRes(R.string.rule_added), awaitItem())
        }
    }

    @Test
    fun `wildcard whitelist adds the domain and its subdomains once`() = runTest {
        vm.events.test {
            vm.addWildcardWhitelist("Example.com")
            assertEquals(UiEvent.ToastRes(R.string.log_wildcard_whitelisted, listOf("example.com")), awaitItem())
            vm.addWildcardWhitelist("example.com")
            awaitItem()
        }
        assertEquals(
            listOf("@@||example.com^", "@@||*.example.com^"),
            ruleDao.rules.value.map { it.rule },
        )
        assertTrue(ruleDao.rules.value.all { it.ruleType == RuleType.ALLOW })
        coVerify(exactly = 1) { repo.loadCustomRules() }
    }

    @Test
    fun `blocking filter lookup hands back the repository result`() {
        coEvery { repo.findBlockingFilterLists("x.com") } returns listOf("EasyList")
        var result: List<String>? = null
        vm.getBlockingFilterLists("x.com") { result = it }
        assertEquals(listOf("EasyList"), result)
    }

    @Test
    fun `exporting nothing says the log is empty`() = runTest {
        vm.events.test {
            vm.settle { exportLogs() }
            assertEquals(UiEvent.ToastRes(R.string.logs_empty), awaitItem())
        }
    }

    private fun exportedCsv(): List<String> {
        val dir = File(app.cacheDir, "logs")
        return dir.listFiles()!!.single().readLines()
    }

    @Test
    fun `exporting writes a CSV and offers it for sharing`() = runTest {
        table.value = listOf(entry(1, "a.com", true, app = "Chrome"))
        keepHot(vm.logs)
        vm.events.test {
            vm.settle { exportLogs() }
            val event = awaitItem()
            assertTrue("got $event", event is UiEvent.ShareFile)
            assertEquals("text/csv", (event as UiEvent.ShareFile).mimeType)
        }
        val lines = exportedCsv()
        assertEquals("Time,Domain,App,Blocked", lines[0])
        assertTrue(lines[1].endsWith(",a.com,Chrome,true"))
    }

    @Ignore("known bug: CSV export does not quote values, so a comma in an app name adds a column")
    @Test
    fun `exported CSV rows keep four columns when a value holds a comma`() = runTest {
        table.value = listOf(entry(1, "a.com", true, app = "Acme, Inc"))
        keepHot(vm.logs)
        vm.settle { exportLogs() }
        assertTrue(exportedCsv()[1].contains("\"Acme, Inc\""))
    }

    @Ignore("known bug: CSV export does not neutralize formula prefixes (=, +, -, @)")
    @Test
    fun `exported CSV neutralizes spreadsheet formulas`() = runTest {
        table.value = listOf(entry(1, "a.com", true, app = "=HYPERLINK(\"x\")"))
        keepHot(vm.logs)
        vm.settle { exportLogs() }
        assertFalse(exportedCsv()[1].contains(",=HYPERLINK"))
    }

    @Test
    fun `selection mode starts off`() {
        assertFalse(vm.selectionMode.value)
        verify(exactly = 0) { dnsLogDao.getAllSince(any()) }
    }
}
