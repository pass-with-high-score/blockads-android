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
 * A WebView implementation that dispatches nested scroll events to Jetpack Compose parents.
 * Enables smooth Pull-to-Refresh and dynamic bottom bar hiding.
 */
class NestedScrollWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr), NestedScrollingChild3 {

    private val childHelper = NestedScrollingChildHelper(this).apply {
        isNestedScrollingEnabled = true
    }

    private var lastMotionY = 0f
    private val scrollOffset = IntArray(2)
    private val scrollConsumed = IntArray(2)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var returnValue = false
        val eventCopy = MotionEvent.obtain(event)
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            lastMotionY = event.rawY
            startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
            returnValue = super.onTouchEvent(event)
        } else {
            val rawY = event.rawY
            val deltaY = (lastMotionY - rawY).toInt()
            lastMotionY = rawY

            when (action) {
                MotionEvent.ACTION_MOVE -> {
                    // 1. Let parent handle pre-scroll
                    if (dispatchNestedPreScroll(0, deltaY, scrollConsumed, scrollOffset, ViewCompat.TYPE_TOUCH)) {
                        eventCopy.offsetLocation(0f, scrollConsumed[1].toFloat())
                    }
                    returnValue = super.onTouchEvent(eventCopy)

                    // 2. Dispatch unconsumed scroll (e.g. overscroll at top/bottom)
                    val unconsumedY = deltaY - scrollConsumed[1]
                    dispatchNestedScroll(0, scrollConsumed[1], 0, unconsumedY, scrollOffset, ViewCompat.TYPE_TOUCH)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    returnValue = super.onTouchEvent(event)
                    stopNestedScroll(ViewCompat.TYPE_TOUCH)
                }
                else -> {
                    returnValue = super.onTouchEvent(event)
                }
            }
        }
        eventCopy.recycle()
        return returnValue
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int,
        consumed: IntArray
    ) {
        childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)
    }

    override fun startNestedScroll(axes: Int, type: Int): Boolean =
        childHelper.startNestedScroll(axes, type)

    override fun stopNestedScroll(type: Int) {
        childHelper.stopNestedScroll(type)
    }

    override fun hasNestedScrollingParent(type: Int): Boolean =
        childHelper.hasNestedScrollingParent(type)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int
    ): Boolean =
        childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type)

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int,
        consumed: IntArray?, offsetInWindow: IntArray?, type: Int
    ): Boolean =
        childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }
}
