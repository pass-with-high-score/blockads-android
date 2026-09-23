package app.pwhs.blockads

import android.os.SystemClock

/** Polls [condition] every 100ms until it holds, failing with [what] after [timeoutMs]. */
fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    check(pollUntil(timeoutMs, condition)) { "Timed out waiting for $what" }
}

/** Polls [condition] every 100ms for up to [timeoutMs], returning whether it came to hold. */
fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        if (SystemClock.uptimeMillis() >= deadline) return false
        SystemClock.sleep(100)
    }
    return true
}
