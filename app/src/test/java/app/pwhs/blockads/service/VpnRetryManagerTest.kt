package app.pwhs.blockads.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VpnRetryManagerTest {

    private lateinit var retryManager: VpnRetryManager

    @Before
    fun setup() {
        retryManager = VpnRetryManager(maxRetries = 5, initialDelayMs = 100L, maxDelayMs = 1000L)
    }

    @Test
    fun `initial retry count is zero`() {
        assertEquals(0, retryManager.getRetryCount())
    }

    @Test
    fun `shouldRetry returns true when under max retries`() {
        assertTrue(retryManager.shouldRetry())
    }

    @Test
    fun `shouldRetry returns false after max retries`() {
        runBlocking {
            // Exhaust all retries
            repeat(5) {
                retryManager.waitForRetry()
            }
        }
        assertFalse(retryManager.shouldRetry())
    }

    @Test
    fun `retry count increments correctly`() {
        runBlocking {
            retryManager.waitForRetry()
            assertEquals(1, retryManager.getRetryCount())
            
            retryManager.waitForRetry()
            assertEquals(2, retryManager.getRetryCount())
        }
    }

    @Test
    fun `reset clears retry count`() {
        runBlocking {
            retryManager.waitForRetry()
            retryManager.waitForRetry()
            assertEquals(2, retryManager.getRetryCount())
            
            retryManager.reset()
            assertEquals(0, retryManager.getRetryCount())
            assertTrue(retryManager.shouldRetry())
        }
    }

    @Test
    fun `exponential backoff delay increases correctly`() {
        // Initial delay should be 100ms
        assertEquals(100L, retryManager.getNextRetryDelay())
        
        runBlocking {
            retryManager.waitForRetry()
        }
        // Second delay should be 200ms (100 * 2^1)
        assertEquals(200L, retryManager.getNextRetryDelay())
        
        runBlocking {
            retryManager.waitForRetry()
        }
        // Third delay should be 400ms (100 * 2^2)
        assertEquals(400L, retryManager.getNextRetryDelay())
        
        runBlocking {
            retryManager.waitForRetry()
        }
        // Fourth delay should be 800ms (100 * 2^3)
        assertEquals(800L, retryManager.getNextRetryDelay())
        
        runBlocking {
            retryManager.waitForRetry()
        }
        // Fifth delay should be capped at maxDelayMs (1000ms)
        assertEquals(1000L, retryManager.getNextRetryDelay())
    }

    @Test
    fun `max delay cap is enforced`() {
        // Use a manager with smaller max delay to test capping
        val manager = VpnRetryManager(maxRetries = 10, initialDelayMs = 100L, maxDelayMs = 500L)
        
        runBlocking {
            // Perform enough retries to exceed max delay
            repeat(5) {
                manager.waitForRetry()
            }
        }
        
        // Delay should be capped at 500ms
        val nextDelay = manager.getNextRetryDelay()
        assertTrue(nextDelay <= 500L)
    }

    @Test
    fun `getMaxRetries returns correct value`() {
        assertEquals(5, retryManager.getMaxRetries())
        
        val customManager = VpnRetryManager(maxRetries = 3)
        assertEquals(3, customManager.getMaxRetries())
    }

    @Test
    fun `waitForRetry returns false when max retries reached`() {
        runBlocking {
            // Exhaust all retries
            repeat(5) {
                assertTrue(retryManager.waitForRetry())
            }
            
            // Next retry should return false
            assertFalse(retryManager.waitForRetry())
        }
    }

    @Test
    fun `timeSinceLastAttempt is zero initially`() {
        assertEquals(0L, retryManager.getTimeSinceLastAttempt())
    }

    @Test
    fun `timeSinceLastAttempt updates after retry`() {
        runBlocking {
            retryManager.waitForRetry()
            
            // Give some time to pass
            kotlinx.coroutines.delay(50)
            
            val timeSince = retryManager.getTimeSinceLastAttempt()
            assertTrue(timeSince > 0)
            assertTrue(timeSince >= 50)
        }
    }

    @Test
    fun `getNextRetryDelay returns zero after max retries`() {
        runBlocking {
            repeat(5) {
                retryManager.waitForRetry()
            }
        }
        
        assertEquals(0L, retryManager.getNextRetryDelay())
    }
}
