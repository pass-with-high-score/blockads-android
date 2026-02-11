package app.pwhs.blockads.service

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Manages VPN connection retry logic with exponential backoff.
 */
class VpnRetryManager(
    private val maxRetries: Int = 5,
    private val initialDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 60000L
) {
    companion object {
        private const val TAG = "VpnRetryManager"
    }

    private var retryCount = 0
    private var lastAttemptTime = 0L

    /**
     * Reset retry counter.
     */
    fun reset() {
        retryCount = 0
        lastAttemptTime = 0L
        Log.d(TAG, "Retry counter reset")
    }

    /**
     * Check if should retry based on current retry count.
     */
    fun shouldRetry(): Boolean {
        return retryCount < maxRetries
    }

    /**
     * Get current retry count.
     */
    fun getRetryCount(): Int = retryCount

    /**
     * Get maximum retry attempts.
     */
    fun getMaxRetries(): Int = maxRetries

    /**
     * Calculate and wait for the exponential backoff delay before next retry.
     * Returns true if waiting completed successfully, false if interrupted.
     */
    suspend fun waitForRetry(): Boolean {
        if (!shouldRetry()) {
            Log.w(TAG, "Max retries ($maxRetries) reached")
            return false
        }

        retryCount++
        lastAttemptTime = System.currentTimeMillis()

        // Calculate exponential backoff: 1s, 2s, 4s, 8s, 16s, capped at maxDelayMs
        val delayMs = minOf(
            initialDelayMs * (1L shl (retryCount - 1)),
            maxDelayMs
        )

        Log.d(TAG, "Retry attempt $retryCount/$maxRetries - waiting ${delayMs}ms before retry")

        try {
            delay(delayMs)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Retry wait interrupted", e)
            return false
        }
    }

    /**
     * Get time since last retry attempt in milliseconds.
     */
    fun getTimeSinceLastAttempt(): Long {
        return if (lastAttemptTime > 0) {
            System.currentTimeMillis() - lastAttemptTime
        } else {
            0L
        }
    }

    /**
     * Get next retry delay in milliseconds without incrementing counter.
     */
    fun getNextRetryDelay(): Long {
        if (!shouldRetry()) return 0L
        return minOf(
            initialDelayMs * (1L shl retryCount),
            maxDelayMs
        )
    }
}
