package io.github.ldxm666.bmapclean

import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil

/** Content extent in drawer coordinates, independent of full-screen background wrappers. */
class BaiduPanelContent private constructor() {
    companion object {
        @JvmStatic
        fun bottom(root: View, drawer: View, excludedIds: IntArray): Int {
            var offset = 0f
            var node: View = root
            var depth = 0
            while (node !== drawer) {
                if (depth++ >= 48) return -1
                val parent = node.parent as? View ?: return -1
                offset += node.y - parent.scrollY
                node = parent
            }
            // offset already includes root.y. Do not add the root position twice.
            val result = scan(root, offset, excludedIds, 0)
            return if (result < 0f) -1 else ceil(result.toDouble()).toInt()
        }

        private fun scan(node: View, top: Float, excludedIds: IntArray, depth: Int): Float {
            if (node.visibility != View.VISIBLE || excludedIds.contains(node.id)) return -1f
            if (depth >= 48) return top + node.height // Unknown subtree: retain native travel.
            if (node is ViewGroup && node.childCount > 0) {
                var bottom = -1f
                for (i in 0 until node.childCount) {
                    val child = node.getChildAt(i)
                    val next = scan(child, top + child.y - node.scrollY, excludedIds, depth + 1)
                    if (next > bottom) bottom = next
                }
                // An empty wrapper with hidden children is not visible content.
                return if (bottom >= 0f) bottom + node.paddingBottom else -1f
            }
            if (node.width <= 0 || node.height <= 0) return -1f
            // Collapsed native rows use alpha=0 until expansion; include their full extent.
            return top + node.height
        }
    }
}
