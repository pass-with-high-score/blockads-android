package app.pwhs.blockads.testutil

import android.os.Looper
import org.robolectric.Shadows.shadowOf

/** Polls [condition] while draining the Robolectric main looper, for code that hops to Dispatchers.IO. */
fun awaitTrue(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Timed out waiting for $message")
}
