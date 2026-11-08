package io.github.ldxm666.tmapclean

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** Measure native content, not the full-height RecyclerView/header/background wrappers. */
object TmapPanelContent {
    @JvmStatic fun boxBottom(view: View, ancestor: View): Int {
        if (view.visibility != View.VISIBLE || view.height <= 0) return -1
        var y = view.height.toFloat()
        var node = view
        var depth = 0
        while (node !== ancestor) {
            if (node.visibility != View.VISIBLE || depth++ > 24) return -1
            val parent = node.parent as? View ?: return -1
            y += node.y - parent.scrollY
            node = parent
        }
        return y.roundToInt()
    }
    @JvmStatic fun bottom(root: View, ancestor: View): Int {
        var offset = 0f
        var node = root
        var depth = 0
        while (node !== ancestor) {
            if (node.visibility != View.VISIBLE || depth++ > 24) return -1
            val parent = node.parent as? View ?: return -1
            offset += node.y - parent.scrollY
            node = parent
        }
        val budget = intArrayOf(1200)
        val end = extent(root, offset, ancestor.height, 0, budget).roundToInt()
        return if (budget[0] <= 0) -1 else end
    }

    private fun extent(v: View, y: Float, viewport: Int, depth: Int, budget: IntArray): Float {
        if (budget[0]-- <= 0 || depth > 24 || v.visibility != View.VISIBLE) return -1f
        val cls = v.javaClass.name
        if (cls.contains("MapLogoFooterView") || cls.contains("TabBarGroup") ||
            cls.contains("GradientTabBarContainer")) return -1f
        if (v is ViewGroup) {
            var end = -1f
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                end = max(end, extent(c, y + c.y - v.scrollY, viewport, depth + 1, budget))
            }
            // Empty native/Litho wrappers retain their old height after children are hidden.
            return if (end < 0) -1f else end + v.paddingBottom
        }
        if (v.height <= 0 || v.width <= 0) return -1f
        if (v.javaClass == View::class.java) return -1f // native separators/backgrounds
        if (viewport > 0 && v.height >= viewport * .8f) return -1f
        if (v.alpha == 0f && v !is TextView && v !is ImageView) return -1f
        return y + v.height
    }

    /** Original Tencent's two/three-row visual crop must not become the next header's top. */
    @JvmStatic fun expandedGridHeight(native: Int, count: Int, density: Float): Int {
        if (native <= 0 || count <= 0 || density <= 0) return native
        val rows = (count + 4) / 5
        val crop = when (rows) { 2 -> 30; 3 -> 40; else -> 0 }
        return native + (crop * density).roundToInt()
    }

    @JvmStatic fun topLimit(viewport: Int, contentBottom: Int, dock: Int, padding: Int,
                            collapsed: Int): Int {
        if (viewport <= 0 || contentBottom <= 0 || collapsed < 0) return Int.MIN_VALUE
        return (viewport - contentBottom - max(0, dock) - max(0, padding)).coerceIn(0, collapsed)
    }

    /** With the feed hidden, the native compact middle level is also the maximum expansion.
     * It already follows current grid rows/density; delayed Litho mounting must not unlock a page.
     */
    @JvmStatic fun compactTop(viewport: Int, contentBottom: Int, dock: Int, padding: Int,
                              collapsed: Int, middleHeight: Int): Int {
        if (viewport <= 0 || middleHeight <= 0 || collapsed < 0) return Int.MIN_VALUE
        val middleTop = (viewport - middleHeight).coerceIn(0, collapsed)
        val measured = topLimit(viewport, contentBottom, dock, padding, collapsed)
        return if (measured == Int.MIN_VALUE) middleTop else max(middleTop, measured)
    }
}
