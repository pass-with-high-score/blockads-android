package app.pwhs.blockads.ui.browser.picker

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * JavascriptInterface bridge exposed as "blockadsPickerProxy" to the element picker JS.
 * All callbacks are posted to the main thread before invoking Compose lambdas.
 */
class ElementPickerBridge(
    private val onRulePicked: (cssSelector: String, domain: String) -> Unit,
    private val onDismissed: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPickerCompleted(cssSelector: String, domain: String) {
        mainHandler.post { onRulePicked(cssSelector, domain) }
    }

    @JavascriptInterface
    fun onPickerCompleted(cssSelector: String) {
        mainHandler.post { onRulePicked(cssSelector, "") }
    }

    @JavascriptInterface
    fun onPickerDismissed() {
        mainHandler.post { onDismissed() }
    }
}
