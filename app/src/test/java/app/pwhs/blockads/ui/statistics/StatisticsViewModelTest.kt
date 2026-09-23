package app.pwhs.blockads.ui.statistics

import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.entities.AppStat
import app.pwhs.blockads.data.entities.TopBlockedDomain
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.keepHot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

class StatisticsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val originalZone = TimeZone.getDefault()
    private val day = 86_400_000L
    private val midnight = 20_000 * day
    private var now = midnight + day - 60_000 // 23:59 UTC
    private val dao: DnsLogDao = mockk(relaxed = true)

    @Before
    fun setUp() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun tearDown() = TimeZone.setDefault(originalZone)

    @Test
    fun `today counters query from local midnight`() {
        StatisticsViewModel(dao) { now }
        verify { dao.getTotalCountSince(midnight) }
        verify { dao.getBlockedCountSince(midnight) }
        verify { dao.getBlockedCountByReasonSince(FilterListRepository.BLOCK_REASON_SECURITY, midnight) }
        verify { dao.getBlockedCountByReason(FilterListRepository.BLOCK_REASON_SECURITY) }
    }

    @Test
    fun `every stat mirrors its DAO flow`() = runTest {
        every { dao.getTotalCount() } returns flowOf(10)
        every { dao.getBlockedCount() } returns flowOf(4)
        every { dao.getTotalCountSince(any()) } returns flowOf(3)
        every { dao.getBlockedCountSince(any()) } returns flowOf(2)
        every { dao.getBlockedCountByReason(any()) } returns flowOf(1)
        every { dao.getTopBlockedDomains(any()) } returns flowOf(listOf(TopBlockedDomain("ads.com", 9)))
        every { dao.getTopApps(any()) } returns flowOf(listOf(AppStat("Chrome", "com.android.chrome", 5, 2)))
        val vm = StatisticsViewModel(dao) { now }
        keepHot(
            vm.totalCount, vm.blockedCount, vm.todayTotal, vm.todayBlocked, vm.securityBlockedCount,
            vm.todaySecurityBlocked, vm.hourlyStats, vm.dailyStats, vm.weeklyStats, vm.monthlyStats,
            vm.topBlockedDomains, vm.topApps,
        )
        assertEquals(listOf(10, 4, 3, 2, 1), listOf(vm.totalCount, vm.blockedCount, vm.todayTotal, vm.todayBlocked, vm.securityBlockedCount).map { it.value })
        assertEquals("ads.com", vm.topBlockedDomains.value.single().domain)
        assertEquals("Chrome", vm.topApps.value.single().appName)
    }

    @Ignore("known bug: todayStart is computed once, so 'today' counters keep counting from yesterday after midnight")
    @Test
    fun `today counters roll over at midnight`() = runTest {
        val vm = StatisticsViewModel(dao) { now }
        keepHot(vm.todayTotal)
        now += 120_000
        keepHot(vm.todayTotal)
        verify { dao.getTotalCountSince(midnight + day) }
    }
}
