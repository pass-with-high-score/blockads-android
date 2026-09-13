package app.pwhs.blockads.ui.browser.component

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView

/**
 * WebView that exposes pull-to-refresh via [onPullToRefreshTrigger].
 * When the page is scrolled to the top and the user drags down with enough velocity/distance,
 * the callback fires and the internal animation resets.
 */
class PullRefreshWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    /** Called when a pull-to-refresh gesture is detected. */
    var onPullToRefreshTrigger: (() -> Unit)? = null

    private var startY = 0f
    private var isPulling = false
    private val PULL_THRESHOLD_PX = (80 * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY
                isPulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Only trigger pull-to-refresh when at the very top of the page
                if (scrollY == 0 && !isPulling) {
                    val dy = event.rawY - startY
                    if (dy > PULL_THRESHOLD_PX) {
                        isPulling = true
                        onPullToRefreshTrigger?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPulling = false
                startY = 0f
            }
        }
        return super.onTouchEvent(event)
    }
}
