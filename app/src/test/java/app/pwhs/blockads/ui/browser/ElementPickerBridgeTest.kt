package app.pwhs.blockads.ui.browser

import android.os.Looper
import app.pwhs.blockads.ui.browser.picker.ElementPickerBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ElementPickerBridgeTest {

    private val picked = mutableListOf<Pair<String, String>>()
    private var dismissed = 0
    private val bridge = ElementPickerBridge(onRulePicked = { s, d -> picked += s to d }, onDismissed = { dismissed++ })

    @Test
    fun `callbacks are posted to the main thread`() {
        val worker = Thread {
            bridge.onPickerCompleted(".ad", "news.test")
            bridge.onPickerCompleted("#banner")
            bridge.onPickerDismissed()
        }
        worker.start()
        worker.join()
        assertTrue(picked.isEmpty())

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(".ad" to "news.test", "#banner" to ""), picked)
        assertEquals(1, dismissed)
    }
}
