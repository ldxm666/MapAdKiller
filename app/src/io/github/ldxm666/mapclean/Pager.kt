package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Scroller

/**
 * Horizontal paging container -- **no AndroidX**.
 *
 * This project builds with {@code javac + android.jar} (no Gradle, no dependency
 * repository), so ViewPager / ViewPager2 are unavailable. Children sit side by side at
 * {@code x = i * width} and paging is driven by the parent's {@code scrollX}:
 *
 * <ul>
 *   <li>drag tracks the finger exactly, with rubber-band resistance past the first and
 *       last page so the edge reads as elastic rather than dead;</li>
 *   <li>release settles to a whole page by a blend of throw velocity and distance, with a
 *       spring interpolator so it lands with a touch of overshoot;</li>
 *   <li>neighbouring pages fade and scale slightly by distance, giving the switch a
 *       sense of depth instead of a flat slide;</li>
 *   <li>horizontal intent is claimed only when {@code |dx| > slop && |dx| > |dy| * 1.3},
 *       so vertical scrolling inside a page is never stolen.</li>
 * </ul>
 */
class Pager(c: Context) : ViewGroup(c) {

    interface OnPageChanged {
        fun onPageChanged(index: Int)
    }

    private val scroller: Scroller
    private val touchSlop: Int
    private val minFling: Int
    private val maxFling: Int

    private var listener: OnPageChanged? = null
    private var currentPage = 0

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var settled = true
    private var vt: VelocityTracker? = null

    init {
        scroller = Scroller(c)
        val vc = ViewConfiguration.get(c)
        touchSlop = vc.scaledTouchSlop
        minFling = vc.scaledMinimumFlingVelocity
        maxFling = vc.scaledMaximumFlingVelocity
        clipToPadding = false
        setChildrenDrawingOrderEnabled(true)
    }

    fun setOnPageChanged(l: OnPageChanged?) {
        listener = l
    }

    fun page(): Int = currentPage

    fun pageCount(): Int = childCount

    /** Switch to page {@code p}; {@code animate=false} snaps (init / relayout). */
    fun setPage(p0: Int, animate: Boolean) {
        val n = childCount
        if (n == 0) return
        val p = p0.coerceIn(0, n - 1)
        val w = width
        val target = p * w
        val changed = currentPage != p
        currentPage = p
        if (animate && w > 0) {
            val distance = Math.abs(target - scrollX)
            val duration = Math.min(520L, SETTLE_BASE_MS + distance / 3L)
            settled = false
            scroller.startScroll(scrollX, 0, target - scrollX, 0, duration.toInt())
            invalidate()
        } else {
            scroller.abortAnimation()
            settled = true
            scrollTo(target, 0)
            applyTransforms()
        }
        if (changed && listener != null) listener!!.onPageChanged(p)
    }

    /** Re-apply the page transform; call after layout changes. */
    fun applyTransforms() {
        val w = width
        if (w <= 0) return
        val scroll = scrollX.toFloat()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val offset = (i * w - scroll) / w.toFloat()   // 0 = centred, +-1 = one page away
            val dist = Math.min(1f, Math.abs(offset))
            // Only the current page and the one being dragged in are worth drawing. Marking
            // the rest INVISIBLE keeps the neighbours from being re-rasterised on every
            // frame of a swipe (elevation + clipToOutline makes that expensive).
            val want = if (dist < 1.01f) View.VISIBLE else View.INVISIBLE
            if (child.visibility != want) child.visibility = want
            // Pages slide 1:1 with the finger. Earlier builds also scaled and faded the
            // neighbour every frame, which forced the (elevated, clipped) cards to be
            // re-rasterised mid-drag and made the swipe feel heavy and laggy.
            if (child.alpha != 1f) child.alpha = 1f
            if (child.scaleX != 1f) {
                child.scaleX = 1f
                child.scaleY = 1f
            }
            child.translationX = 0f
            child.rotationY = 0f
        }
    }

    /**
     * Measure every page at the full container size.
     *
     * A ViewGroup that forgets onMeasure leaves its children unmeasured, so they get a
     * zero-size layout and draw nothing -- the pager then looks empty even though the
     * pages were added. Each page is forced to EXACTLY the container width/height so the
     * inner ScrollView can fill it.
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidth, childHeight)
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h)
        }
        if (!settled) return                  // do not fight an in-flight settle
        scrollTo(currentPage * w, 0)
        applyTransforms()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            applyTransforms()
            postInvalidateOnAnimation()
        } else if (!settled) {
            settled = true
            applyTransforms()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = downX
                dragging = false
                scroller.abortAnimation()
                settled = true
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                val dx = Math.abs(e.x - downX)
                val dy = Math.abs(e.y - downY)
                // Require a clear horizontal intent before stealing from a vertical scroller.
                if (dx > touchSlop && dx > dy * 1.3f) {
                    dragging = true
                    lastX = e.x
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width
        val n = childCount
        if (w <= 0 || n == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = downX
                dragging = false
                scroller.abortAnimation()
                settled = true
                var tracker = vt
                if (tracker == null) {
                    tracker = VelocityTracker.obtain()!!
                    vt = tracker
                }
                tracker.clear()
                tracker.addMovement(e)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val x = e.x
                if (!dragging) {
                    val dx = Math.abs(x - downX)
                    val dy = Math.abs(e.y - downY)
                    if (dx > touchSlop && dx > dy * 1.3f) {
                        dragging = true
                        lastX = x
                    } else {
                        return true
                    }
                }
                val delta = (lastX - x).toInt()
                lastX = x
                val max = (n - 1) * w
                var want = scrollX + delta
                if (want < 0 || want > max) {
                    // Rubber-band: move only a fraction of the overscroll.
                    val over = if (want < 0) want else want - max
                    val base = if (want < 0) 0 else max
                    want = base + (over * EDGE_RESISTANCE).toInt()
                }
                scrollTo(want, 0)
                applyTransforms()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                if (!dragging) {
                    recycleVt()
                    return true    // a plain tap belongs to the child
                }
                var vx = 0f
                val tracker = vt
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                    vx = tracker.xVelocity
                }
                recycleVt()
                val cur = scrollX
                var target: Int
                val travelled = (cur - currentPage.toFloat() * w) / w.toFloat()   // signed, 0..1
                if (Math.abs(vx) > minFling) {
                    // Baseline must be the page the drag *started* on (field {@code page},
                    // not updated mid-drag). Rounding the release offset and then adding
                    // +-1 double-counts and skips two pages on a fast half-page flick.
                    target = currentPage + (if (vx < 0) 1 else -1)
                } else if (travelled > SWIPE_COMMIT) {
                    target = currentPage + 1
                } else if (travelled < -SWIPE_COMMIT) {
                    target = currentPage - 1
                } else {
                    target = currentPage
                }
                if (target < 0) target = 0
                if (target > n - 1) target = n - 1
                setPage(target, true)
                dragging = false
                return true
            }

            else -> return true
        }
    }

    override fun getChildDrawingOrder(count: Int, i: Int): Int {
        // Draw the nearest page last so its shadow/scale sits above the neighbours.
        val w = width
        if (w <= 0) return i
        val centre = Math.round(scrollX.toFloat() / w.toFloat())
        var order = i + centre - count / 2
        if (order < 0) order += count
        if (order >= count) order -= count
        return order
    }

    private fun recycleVt() {
        val tracker = vt
        if (tracker != null) {
            tracker.recycle()
            vt = null
        }
    }

    companion object {
        /** How far past an edge the content may travel, as a fraction of the page width. */
        private const val EDGE_RESISTANCE = 0.32f
        /** Fraction of the page a drag must cover to commit without a fling (18 %). */
        private const val SWIPE_COMMIT = 0.12f
        /** Settle duration base; longer throws travel for longer. */
        private const val SETTLE_BASE_MS = 240L
    }
}