package app.pwhs.blockadstv.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Manages VPN connection retry logic with gentle backoff.
 */
class VpnRetryManager(
    private val maxRetries: Int = 5,
    private val maxDelayMs: Long = 60000L,
) {
    private var retryCount = 0
    private var lastAttemptTime = 0L

    private val delaySteps = longArrayOf(1000L, 1000L, 2000L, 3000L, 5000L)

    fun reset() {
        retryCount = 0
        lastAttemptTime = 0L
        Timber.d("Retry counter reset")
    }

    fun shouldRetry(): Boolean = retryCount < maxRetries

    fun getRetryCount(): Int = retryCount

    fun getMaxRetries(): Int = maxRetries

    suspend fun waitForRetry(): Boolean {
        if (!shouldRetry()) {
            Timber.w("Max retries ($maxRetries) reached")
            return false
        }

        retryCount++
        lastAttemptTime = System.currentTimeMillis()

        val delayMs = minOf(
            delaySteps.getOrElse(retryCount - 1) { delaySteps.last() },
            maxDelayMs,
        )

        Timber.d("Retry attempt $retryCount/$maxRetries - waiting ${delayMs}ms before retry")

        return try {
            delay(delayMs)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Retry wait interrupted: $e")
            false
        }
    }
}
