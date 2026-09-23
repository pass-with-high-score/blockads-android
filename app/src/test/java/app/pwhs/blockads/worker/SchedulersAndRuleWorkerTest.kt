package app.pwhs.blockads.worker

import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.browser.rules.BrowserRuleRepository
import app.pwhs.blockads.ui.browser.rules.BrowserRuleUpdateWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SchedulersAndRuleWorkerTest {

    private val ruleRepo: BrowserRuleRepository = mockk()
    private var autoUpdate = true
    private var frequency = AppPreferences.UPDATE_FREQUENCY_24H
    private val prefs: AppPreferences = mockk {
        every { autoUpdateEnabled } answers { flowOf(autoUpdate) }
        every { autoUpdateFrequency } answers { flowOf(frequency) }
    }
    private val h = WorkerHarness(module { single { ruleRepo } })

    @After
    fun tearDown() = h.close()

    @Test
    fun `browser rule worker maps the repository result`() {
        coEvery { ruleRepo.checkAndUpdate(null) } returns kotlin.Result.success(false)
        assertEquals(Result.success(), h.run<BrowserRuleUpdateWorker>())
        coEvery { ruleRepo.checkAndUpdate(null) } returns kotlin.Result.failure(IllegalStateException("x"))
        assertEquals(Result.retry(), h.run<BrowserRuleUpdateWorker>())
        coEvery { ruleRepo.checkAndUpdate(null) } throws IllegalStateException("boom")
        assertEquals(Result.retry(), h.run<BrowserRuleUpdateWorker>())
    }

    @Test
    fun `browser rule worker schedules a daily connected job once`() {
        BrowserRuleUpdateWorker.schedule(h.app)
        BrowserRuleUpdateWorker.schedule(h.app)
        val info = h.uniqueWork(BrowserRuleUpdateWorker.WORK_NAME).single()
        assertEquals(TimeUnit.DAYS.toMillis(1), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    private fun scheduleFilters() = runBlocking { FilterUpdateScheduler.scheduleFilterUpdate(h.app, prefs) }

    private fun filterWork() = h.uniqueWork(FilterUpdateWorker.WORK_NAME).filter { it.state != WorkInfo.State.CANCELLED }

    @Test
    fun `filter update interval follows the frequency setting`() {
        val expected = mapOf(
            AppPreferences.UPDATE_FREQUENCY_6H to 6L,
            AppPreferences.UPDATE_FREQUENCY_12H to 12L,
            AppPreferences.UPDATE_FREQUENCY_24H to 24L,
            AppPreferences.UPDATE_FREQUENCY_48H to 48L,
            "garbage" to 24L,
        )
        for ((setting, hours) in expected) {
            frequency = setting
            scheduleFilters()
            assertEquals(setting, TimeUnit.HOURS.toMillis(hours), filterWork().single().periodicityInfo!!.repeatIntervalMillis)
        }
    }

    @Test
    fun `manual frequency or disabled auto-update cancels the job`() {
        scheduleFilters()
        assertEquals(1, filterWork().size)

        frequency = AppPreferences.UPDATE_FREQUENCY_MANUAL
        scheduleFilters()
        assertTrue(filterWork().isEmpty())

        frequency = AppPreferences.UPDATE_FREQUENCY_24H
        scheduleFilters()
        autoUpdate = false
        scheduleFilters()
        assertTrue(filterWork().isEmpty())
    }
}
