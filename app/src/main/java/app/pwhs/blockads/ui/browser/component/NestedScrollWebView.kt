package app.pwhs.blockads.ui.browser.component

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat

/**
 * WebView that participates in Android View's nested scroll protocol.
 * Events bubble up through [NestedScrollingChildHelper] and are bridged to Compose
 * by [rememberNestedScrollInteropConnection] applied on the AndroidView.
 */
class NestedScrollWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr), NestedScrollingChild3 {

    private val childHelper = NestedScrollingChildHelper(this).apply {
        isNestedScrollingEnabled = true
    }

    private var lastY = 0f
    private val consumed = IntArray(2)
    private val offsetInWindow = IntArray(2)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            lastY = event.rawY
            startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
            return super.onTouchEvent(event)
        }

        if (action == MotionEvent.ACTION_MOVE) {
            val currentY = event.rawY
            // dy > 0 means finger moved up (scrolling content upward)
            // dy < 0 means finger moved down (overscroll / pull-to-refresh territory)
            val dy = (lastY - currentY).toInt()
            lastY = currentY

            consumed.fill(0)
            offsetInWindow.fill(0)

            // Give parent (PullToRefreshBox) first chance to consume
            val parentConsumed = dispatchNestedPreScroll(0, dy, consumed, offsetInWindow, ViewCompat.TYPE_TOUCH)
            val adjustedDy = dy - consumed[1]

            // Build adjusted event so WebView scrolls only the leftover amount
            val adjustedEvent = MotionEvent.obtain(event).also { ev ->
                ev.offsetLocation(0f, consumed[1].toFloat())
            }
            val result = super.onTouchEvent(adjustedEvent)
            adjustedEvent.recycle()

            // Report what WebView consumed and pass remainder to parent
            val webConsumedY = if (parentConsumed) consumed[1] else adjustedDy
            val unconsumedY = dy - webConsumedY
            consumed.fill(0)
            dispatchNestedScroll(0, webConsumedY, 0, unconsumedY, offsetInWindow, ViewCompat.TYPE_TOUCH, consumed)

            return result
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            stopNestedScroll(ViewCompat.TYPE_TOUCH)
        }

        return super.onTouchEvent(event)
    }

    // NestedScrollingChild3
    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int,
        consumed: IntArray
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)

    // NestedScrollingChild2
    override fun startNestedScroll(axes: Int, type: Int) = childHelper.startNestedScroll(axes, type)
    override fun stopNestedScroll(type: Int) = childHelper.stopNestedScroll(type)
    override fun hasNestedScrollingParent(type: Int) = childHelper.hasNestedScrollingParent(type)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type)

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int,
        consumed: IntArray?, offsetInWindow: IntArray?, type: Int
    ) = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    // NestedScrollingChild1
    override fun isNestedScrollingEnabled() = childHelper.isNestedScrollingEnabled
    override fun setNestedScrollingEnabled(enabled: Boolean) { childHelper.isNestedScrollingEnabled = enabled }

    override fun startNestedScroll(axes: Int) = childHelper.startNestedScroll(axes)
    override fun stopNestedScroll() = childHelper.stopNestedScroll()
    override fun hasNestedScrollingParent() = childHelper.hasNestedScrollingParent()

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ) = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int,
        consumed: IntArray?, offsetInWindow: IntArray?
    ) = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean) =
        childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float) =
        childHelper.dispatchNestedPreFling(velocityX, velocityY)
}
