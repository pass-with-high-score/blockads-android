package app.pwhs.blockads.worker

import androidx.core.app.NotificationCompat
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class DailySummaryWorkerTest {

    private var enabled = true
    private val prefs: AppPreferences = mockk { every { dailySummaryEnabled } answers { flowOf(enabled) } }
    private val cutoff = slot<Long>()
    private val dao: DnsLogDao = mockk {
        coEvery { getBlockedCountSinceSync(any()) } returns 42
        coEvery { deleteLogsOlderThan(capture(cutoff)) } returns 3
    }
    private val h = WorkerHarness(module {
        single { prefs }
        single { dao }
    })

    @After
    fun tearDown() = h.close()

    private fun expectedDelayToNext2100(): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    @Test
    fun `disabled summary does nothing`() {
        enabled = false
        assertEquals(Result.success(), h.run<DailySummaryWorker>())
        coVerify(exactly = 0) { dao.getBlockedCountSinceSync(any()) }
        assertTrue(h.uniqueWork(DailySummaryWorker.WORK_NAME).isEmpty())
    }

    @Test
    fun `posts the count, prunes 14 days and reschedules for 21h00`() {
        val before = System.currentTimeMillis()
        assertEquals(Result.success(), h.run<DailySummaryWorker>())

        val text = h.notifications.single().extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("42"))
        assertTrue(abs(before - TimeUnit.DAYS.toMillis(14) - cutoff.captured) < 5_000)

        val work = h.uniqueWork(DailySummaryWorker.WORK_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(abs(work.initialDelayMillis - expectedDelayToNext2100()) < 5_000)
    }

    @Test
    fun `quiet days skip the notification but still prune`() {
        coEvery { dao.getBlockedCountSinceSync(any()) } returns 0
        assertEquals(Result.success(), h.run<DailySummaryWorker>())
        assertTrue(h.notifications.isEmpty())
        coVerify { dao.deleteLogsOlderThan(any()) }
    }

    @Test
    fun `prune failure does not fail the summary`() {
        coEvery { dao.deleteLogsOlderThan(any()) } throws IllegalStateException("locked")
        assertEquals(Result.success(), h.run<DailySummaryWorker>())
        assertEquals(1, h.uniqueWork(DailySummaryWorker.WORK_NAME).size)
    }

    @Test
    fun `count failure retries without rescheduling`() {
        coEvery { dao.getBlockedCountSinceSync(any()) } throws IllegalStateException("db")
        assertEquals(Result.retry(), h.run<DailySummaryWorker>())
        assertTrue(h.uniqueWork(DailySummaryWorker.WORK_NAME).isEmpty())
    }

    @Test
    fun `scheduler replaces and cancels the unique work`() {
        DailySummaryScheduler.scheduleDailySummary(h.app)
        DailySummaryScheduler.scheduleDailySummary(h.app)
        val infos = h.uniqueWork(DailySummaryWorker.WORK_NAME)
        assertEquals(1, infos.count { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(infos.single { it.state == WorkInfo.State.ENQUEUED }.initialDelayMillis in 1..TimeUnit.DAYS.toMillis(1))

        DailySummaryScheduler.cancelDailySummary(h.app)
        assertTrue(h.uniqueWork(DailySummaryWorker.WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
    }
}
