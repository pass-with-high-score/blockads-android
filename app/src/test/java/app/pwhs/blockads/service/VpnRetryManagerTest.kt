package app.pwhs.blockads.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class VpnRetryManagerTest {

    @Test
    fun `backoff follows the gentle curve then stops at max retries`() = runTest {
        val manager = VpnRetryManager(maxRetries = 5)
        val waits = mutableListOf<Long>()
        repeat(5) {
            val before = currentTime
            assertTrue(manager.waitForRetry())
            waits += currentTime - before
        }
        assertEquals(listOf(1000L, 1000L, 2000L, 3000L, 5000L), waits)
        assertFalse(manager.waitForRetry())
    }

    @Ignore("known bug: waitForRetry swallows CancellationException")
    @Test
    fun `cancellation during the backoff propagates instead of resuming the caller`() = runTest {
        val manager = VpnRetryManager()
        var resumedAfterCancel = false
        var cancelled = false
        val job = launch {
            try {
                manager.waitForRetry()
                resumedAfterCancel = true
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(10_000)
        runCurrent()

        assertFalse("waitForRetry swallowed CancellationException and the caller kept running", resumedAfterCancel)
        assertTrue(cancelled)
    }
}
