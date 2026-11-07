package io.github.ldxm666.bmapclean

import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup

import java.lang.reflect.Field

/**
 * PROBE-ONLY 取证探针（交付前 {@link #ENABLED} 置 false 即完全静默）。
 *
 * <p>目的：把 {@code DrawerBehavior} 的坐标系钉死。前一份报告只有相对值
 * （{@code t=1004 h=2038}），无法判断"面板可见顶边"和"内容底边"在屏幕上的真实位置。
 * 这里全部用 {@code getLocationOnScreen()} 取绝对坐标，并逐叶子扫描出**屏幕坐标系**下
 * 的内容上/下边界，绕开所有相对坐标猜测。
 *
 * <p>输出格式（单行，便于脚本解析）：
 * <pre>
 * GEO state=.. parent=.. minOff=.. maxOff=.. extend=.. peek=..(auto=..)
 *     drawerTop=.. drawerScrTop=.. drawerScrBottom=..
 *     cTop=.. cBottom=.. cScreenTop=.. cScreenBottom=..
 *     winH=.. dispH=.. gridScr=.. tabsScr=.. listH=.. listVis=..
 * </pre>
 */
class BaiduPanelProbe private constructor() {

    companion object {

        /** 取证期 true；交付前置 false。 */
        const val ENABLED = true

        private const val TAG = "baidu_panel_geo"
        private const val MAX_LINES = 300

        @Volatile
        private var lines = 0
        @Volatile
        private var installed = false
        @Volatile
        private var lastKey = ""

        /** 记录"最近一次可见状态"下的内容边界，供幂等判断与交叉核对。 */
        @Volatile
        @JvmField
        var lastContentTop = -1
        @Volatile
        @JvmField
        var lastContentBottom = -1

        @JvmStatic
        fun install(cl: ClassLoader) {
            if (!ENABLED || installed) return
            installed = true
            H.log(Log.INFO, MainHook.TAG, TAG + " probe installed")
        }

        /** 每次 {@code onLayoutChild} 之后、{@code BaiduPanelHeight} 写字段之前调用。 */
        @JvmStatic
        fun afterLayout(sheet: Any?, drawer: View, phase: String?) {
            if (!ENABLED) return
            try {
                val state = getInt(sheet, "mState")
                val parent = getInt(sheet, "mParentHeight")
                val minOff = getInt(sheet, "mMinOffset")
                val maxOff = getInt(sheet, "mMaxOffset")
                val extend = getInt(sheet, "extendHeight")
                val peek = getInt(sheet, "mPeekHeight")
                val auto = getBool(sheet, "mPeekHeightAuto")

                val bounds = intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE)
                val all = intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE)
                val raw = intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE)
                val dr = IntArray(2)
                drawer.getLocationOnScreen(dr)
                scanScreen(drawer, bounds, 0)
                scanVisibleAll(drawer, all, 0)
                scanUnclipped(drawer, raw, 0, drawer.height)

                val cTop = if (bounds[0] == Int.MAX_VALUE) -1 else bounds[0] - dr[1]
                val cBot = if (bounds[1] == Int.MIN_VALUE) -1 else bounds[1] - dr[1]
                val aTop = if (all[0] == Int.MAX_VALUE) -1 else all[0] - dr[1]
                val aBot = if (all[1] == Int.MIN_VALUE) -1 else all[1] - dr[1]
                val rTop = if (raw[0] == Int.MAX_VALUE) -1 else raw[0] - dr[1]
                val rBot = if (raw[1] == Int.MIN_VALUE) -1 else raw[1] - dr[1]

                val grid = findById(drawer, "component_container3")
                val list = findById(drawer, "component_list_new")
                val ufo = findById(drawer, "ufo_root")

                val key = state.toString() + ":" + parent + ":" + minOff + ":" + maxOff + ":" +
                        cTop + ":" + cBot + ":" + aTop + ":" + aBot + ":" + dr[1] + ":" + drawer.top
                if (key == lastKey) return
                lastKey = key
                if (lines++ > MAX_LINES) return

                lastContentTop = cTop
                lastContentBottom = cBot

                H.log(Log.INFO, MainHook.TAG, TAG
                        + " phase=" + phase
                        + " state=" + state
                        + " parent=" + parent
                        + " minOff=" + minOff
                        + " maxOff=" + maxOff
                        + " extend=" + extend
                        + " peek=" + peek + "(auto=" + auto + ")"
                        + " drawerTop=" + drawer.top
                        + " drawerScr=" + dr[1] + ".." + (dr[1] + drawer.height)
                        + " leafScr=" + bounds[0] + ".." + bounds[1]
                        + " leafRel=" + cTop + ".." + cBot
                        + " allScr=" + all[0] + ".." + all[1]
                        + " allRel=" + aTop + ".." + aBot
                        + " rawScr=" + raw[0] + ".." + raw[1]
                        + " rawRel=" + rTop + ".." + rBot
                        + " winH=" + winH(drawer)
                        + " dispH=" + dispH(drawer)
                        + " listH=" + h(list) + " listVis=" + vis(list)
                        + " grid=" + scr(grid)
                        + " ufo=" + scr(ufo))
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, TAG + " fail " + t)
            }
        }

        /**
         * 逐叶子扫描**屏幕坐标**下可见内容的上下边界。
         * 排除整高背景层（{@code aihome_panel_bg*} / {@code aihome_panel_top_shadow}）——
         * 它们是纯色底，不是内容。
         */
        @JvmStatic
        fun scanScreen(node: View?, out: IntArray, depth: Int) {
            if (node == null || depth > 16) return
            if (node.visibility != View.VISIBLE) return
            if (isBackground(node)) return
            val loc = IntArray(2)
            node.getLocationOnScreen(loc)
            val top = loc[1]
            val bottom = top + node.height
            var anyChild = false
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) {
                    val c = node.getChildAt(i)
                    if (c == null || c.visibility != View.VISIBLE) continue
                    anyChild = true
                    scanScreen(c, out, depth + 1)
                }
            }
            if (!anyChild && node.width > 0 && node.height > 0) {
                if (top < out[0]) out[0] = top
                if (bottom > out[1]) out[1] = bottom
            }
        }

        /**
         * 逐**叶子**扫描可见内容的屏幕矩形，用 {@code getGlobalVisibleRect} —— 它同时反映
         * 真实可见性和裁剪，比 {@code getVisibility()} 可靠（实测 layout 期
         * {@code component_list_new} 的 visibility 还是 4/INVISIBLE，但内容已经在渲染）。
         *
         * <p>排除整高背景层（纯色底，不是内容）。
         */
        @JvmStatic
        fun scanVisible(node: View?, out: IntArray, depth: Int) {
            if (node == null || depth > 16) return
            if (isBackground(node)) return
            var anyChild = false
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) {
                    val c = node.getChildAt(i)
                    if (c == null) continue
                    anyChild = true
                    scanVisible(c, out, depth + 1)
                }
            }
            if (anyChild) return
            if (node.width <= 0 || node.height <= 0) return
            val r = Rect()
            var ok = false
            try {
                ok = node.getGlobalVisibleRect(r)
            } catch (t: Throwable) {
                ok = false
            }
            if (!ok || r.isEmpty) return
            if (r.top < out[0]) out[0] = r.top
            if (r.bottom > out[1]) out[1] = r.bottom
        }

        /** 逐**节点**（不只叶子）取 {@code getGlobalVisibleRect} 的并集，用于找内容整体边界。 */
        @JvmStatic
        fun scanVisibleAll(node: View?, out: IntArray, depth: Int) {
            if (node == null || depth > 16) return
            if (isBackground(node)) return
            val r = Rect()
            var ok = false
            try {
                ok = node.getGlobalVisibleRect(r)
            } catch (t: Throwable) {
                ok = false
            }
            if (ok && !r.isEmpty) {
                if (r.top < out[0]) out[0] = r.top
                if (r.bottom > out[1]) out[1] = r.bottom
            }
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) scanVisibleAll(node.getChildAt(i), out, depth + 1)
            }
        }

        /**
         * 逐**叶子**取「未被裁剪」的屏幕矩形（{@code getLocationOnScreen + getHeight}），
         * 不看 visibility 也不看裁剪 —— 用于判断内容真实延展范围。
         *
         * <p>排除：整高背景层、以及高度等于面板高度的整高包装（它们不是内容）。
         */
        @JvmStatic
        fun scanUnclipped(node: View?, out: IntArray, depth: Int, panelH: Int) {
            if (node == null || depth > 16) return
            if (isBackground(node)) return
            var anyChild = false
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) {
                    val c = node.getChildAt(i)
                    if (c == null) continue
                    anyChild = true
                    scanUnclipped(c, out, depth + 1, panelH)
                }
            }
            if (anyChild) return
            if (node.width <= 0 || node.height <= 0) return
            if (panelH > 0 && node.height >= panelH) return   // 整高包装
            val loc = IntArray(2)
            try {
                node.getLocationOnScreen(loc)
            } catch (t: Throwable) {
                return
            }
            val top = loc[1]
            val bottom = top + node.height
            if (top < out[0]) out[0] = top
            if (bottom > out[1]) out[1] = bottom
        }

        @JvmStatic
        private fun isBackground(v: View?): Boolean {
            val n = entryName(v) ?: return false
            return n == "aihome_panel_bg" || n.startsWith("aihome_panel_bg_")
                    || n == "aihome_panel_top_shadow"
        }

        @JvmStatic
        private fun scr(v: View?): String {
            if (v == null) return "null"
            val l = IntArray(2)
            try {
                v.getLocationOnScreen(l)
            } catch (ignored: Throwable) {
            }
            return l[1].toString() + ".." + (l[1] + v.height) + "/h" + v.height + "/v" + v.visibility
        }

        @JvmStatic
        private fun h(v: View?): String {
            return if (v == null) "null" else v.height.toString()
        }

        @JvmStatic
        private fun vis(v: View?): String {
            return if (v == null) "null" else v.visibility.toString()
        }

        @JvmStatic
        private fun winH(v: View): Int {
            try {
                return v.rootView.height
            } catch (t: Throwable) {
                return -1
            }
        }

        @JvmStatic
        private fun dispH(v: View): Int {
            try {
                return v.resources.displayMetrics.heightPixels
            } catch (t: Throwable) {
                return -1
            }
        }

        @JvmStatic
        fun findById(root: View, name: String): View? {
            try {
                var id = root.resources.getIdentifier(name, "id", Anchors.PKG)
                if (id == 0) id = root.resources.getIdentifier(name, "id", null)
                if (id == 0) return null
                return root.findViewById(id)
            } catch (ignored: Throwable) {
                return null
            }
        }

        @JvmStatic
        private fun entryName(v: View?): String? {
            if (v == null) return null
            try {
                return v.resources.getResourceEntryName(v.id)
            } catch (t: Throwable) {
                return null
            }
        }

        @JvmStatic
        fun field(t: Any?, n: String): Field {
            var c: Class<*>? = t!!.javaClass
            while (true) {
                val cls = c ?: break
                try {
                    val f = cls.getDeclaredField(n)
                    f.isAccessible = true
                    return f
                } catch (ignored: NoSuchFieldException) {
                }
                c = cls.superclass
            }
            throw NoSuchFieldException(n)
        }

        @JvmStatic
        fun getInt(t: Any?, n: String): Int {
            return field(t, n).getInt(t)
        }

        @JvmStatic
        fun getBool(t: Any?, n: String): Boolean {
            return field(t, n).getBoolean(t)
        }

        /** 供外部 dump 内容树（取证用）。 */
        @JvmStatic
        fun describe(v: View?, depth: Int): List<String> {
            val out = ArrayList<String>()
            describe(v, depth, out, 0)
            return out
        }

        @JvmStatic
        private fun describe(v: View?, depth: Int, out: MutableList<String>, d: Int) {
            if (v == null || d > 10 || out.size > 120) return
            val l = IntArray(2)
            v.getLocationOnScreen(l)
            val sb = StringBuilder()
            for (i in 0 until d) sb.append("  ")
            val name = entryName(v)
            sb.append(if (name != null) name else v.javaClass.simpleName)
                    .append(" v=").append(v.visibility)
                    .append(" t=").append(v.top)
                    .append(" scr=").append(l[1]).append("..").append(l[1] + v.height)
                    .append(" h=").append(v.height)
            out.add(sb.toString())
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) describe(v.getChildAt(i), depth, out, d + 1)
            }
        }
    }
}