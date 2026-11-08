package io.github.ldxm666.tmapclean

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/** Per-home-page bounds. Gestures use cached integers; measurement runs on settled layout only. */
object TmapPanelFit {
    private const val SLIDE = "com.tencent.map.hippy.extend.view.slideview.SlideCardView"
    private class State {
        var limit = Int.MIN_VALUE
        var collapsed = Int.MIN_VALUE
        var pending = false
        var previous = Int.MIN_VALUE
        var rounds = 0
    }
    private val states = WeakHashMap<View, State>()
    private val scrollEffects = WeakHashMap<View, Int>()

    /** The compact home has no scrollable feed. Keep its native recycler from
     * stretching the toolbar at an edge; restore its own policy when the feed returns.
     */
    @JvmStatic fun applyScrollEffect(view: View, compact: Boolean) {
        if (compact) {
            if (!scrollEffects.containsKey(view)) scrollEffects[view] = view.overScrollMode
            if (view.overScrollMode != View.OVER_SCROLL_NEVER)
                view.overScrollMode = View.OVER_SCROLL_NEVER
        } else {
            val original = scrollEffects.remove(view) ?: return
            if (view.overScrollMode == View.OVER_SCROLL_NEVER) view.overScrollMode = original
        }
    }

    @JvmStatic fun install(cl: ClassLoader) {
        val slide = H.cls(cl, SLIDE) ?: return
        H.hookSig(slide, "getValidTopHeight", "tmap_fit_drag", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val native = chain.proceed()
                val card = chain.getThisObject() as? View ?: return native
                val s = states[card] ?: return native
                return if (native is Int && active() && s.limit != Int.MIN_VALUE)
                    maxOf(native, s.limit) else native
            }
        }, Integer.TYPE)
        H.hookSig(H.cls(cl, SLIDE + "\$DragCallBack"), "getTop", "tmap_fit_release",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val native = chain.proceed()
                    val content = chain.getArg(0) as? View ?: return native
                    val card = content.parent as? View ?: return native
                    val s = states[card] ?: return native
                    if (native !is Int || !active() || s.limit == Int.MIN_VALUE) return native
                    val velocity = (chain.getArg(1) as? Float) ?: 0f
                    return releaseTop(native, content.top, velocity, s.limit, s.collapsed)
                }
            }, View::class.java, java.lang.Float.TYPE)
        H.hookSig(slide, "onInterceptTouchEvent", "tmap_fit_down",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val card = chain.getThisObject() as? View
                    if (card != null && states.containsKey(card) &&
                        (chain.getArg(0) as? MotionEvent)?.actionMasked == MotionEvent.ACTION_DOWN)
                        sample(card, states[card]!!, false)
                    return chain.proceed()
                }
            }, MotionEvent::class.java)
    }

    private fun active() = !Cfg.visible(Spec.K_HOME_FEED)

    @JvmStatic fun request(inside: View?) {
        var node = inside
        var depth = 0
        while (node != null && depth++ < 24) {
            if (node.javaClass.name == "com.tencent.map.ama.newhome.widget.HomeRecycleView")
                applyScrollEffect(node, active())
            var cls: Class<*>? = node.javaClass
            while (cls != null && cls.name != SLIDE) cls = cls.superclass
            if (cls != null) { register(node); return }
            node = node.parent as? View
        }
    }

    private fun register(card: View) {
        val s = states[card] ?: State().also { fresh ->
            states[card] = fresh
            card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                fresh.rounds = 0
                schedule(card, fresh)
            }
        }
        s.previous = Int.MIN_VALUE
        s.rounds = 0
        schedule(card, s)
    }

    private fun schedule(card: View, s: State) {
        if (s.pending || s.rounds >= 12) return
        s.pending = true
        card.postOnAnimation {
            s.pending = false
            if (!card.isAttachedToWindow) return@postOnAnimation
            s.rounds++
            if (!sample(card, s, true)) schedule(card, s)
        }
    }

    private fun sample(card: View, s: State, move: Boolean): Boolean {
        if (!active()) { s.limit = Int.MIN_VALUE; return true }
        if ((H.call(card, "getDragState") as? Int ?: 0) != 0) return false
        val content = H.call(card, "getContentContainer") as? View ?: return false
        val adapter = H.call(card, "getAdapter") ?: return false
        val levels = H.call(adapter, "getLevelHeights") as? List<*> ?: return false
        val min = levels.filterIsInstance<Int>().minOrNull() ?: return false
        val middle = levels.getOrNull(1) as? Int ?: return false
        val collapsed = (card.height - min).coerceAtLeast(0)
        // At the low level Tencent deliberately renders one row. Measuring that
        // transient size on ACTION_DOWN would cap the next upward drag at one row.
        // Until expanded geometry is ready, retain the native middle endpoint.
        val bottom = if (TmapTools.expandedGeometryReady())
            TmapTools.visibleContentBottom(content, card) else -1
        val dock = findDock(card.rootView, 0)?.let {
            val a = IntArray(2); val b = IntArray(2)
            card.getLocationOnScreen(a); it.getLocationOnScreen(b)
            (a[1] + card.height - b[1]).coerceIn(0, card.height)
        } ?: 0
        val limit = TmapPanelContent.compactTop(card.height, bottom, dock,
            (8 * card.resources.displayMetrics.density).toInt(), collapsed, middle)
        if (limit == Int.MIN_VALUE) return false
        s.collapsed = collapsed
        if (s.previous != limit) { s.previous = limit; return false }
        if (s.limit != limit && Cfg.debug()) android.util.Log.i("TMapClean",
            "TMFit limit=$limit content=$bottom dock=$dock collapsed=$collapsed viewport=${card.height}")
        s.limit = limit
        if (move && content.top < limit) {
            // Use the native immediate-height path, including its scroll notification and cached height.
            H.setField(card, "currentContainerViewHeight", card.height - limit)
            H.call1(card, "setHeightImmediately", card.height - limit)
        }
        return true
    }

    private fun findDock(v: View, depth: Int): View? {
        if (depth > 18 || v.visibility != View.VISIBLE) return null
        if (v.javaClass.name.endsWith("GradientTabBarContainer")) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val found = findDock(v.getChildAt(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    @JvmStatic fun releaseTop(native: Int, current: Int, velocity: Float, limit: Int, collapsed: Int): Int {
        if (limit == Int.MIN_VALUE) return native
        if (collapsed > limit && (velocity < -50f ||
            (velocity <= 50f && current < limit + (collapsed - limit) / 2))) return limit
        return maxOf(native, limit)
    }
}
