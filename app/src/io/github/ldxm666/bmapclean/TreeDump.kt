package io.github.ldxm666.bmapclean

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 取证用视图树 dump（只在 debug 打开时输出到 logcat）。
 *
 * 为什么必须有它：百度首页展开态的关键区域（频道栏「推荐/看世界/…」、
 * 优质内容精选、推荐卡片）都是 **Talos（类 React）渲染**的，没有资源 id，
 * 且它们的文案只有 `TextView#getText()` 拿得到 ——
 *   · `dumpsys activity top` 不给文本，还会在别的 App 上超时；
 *   · `uiautomator dump` 在百度首页 "could not get idle state"（一直动画）；
 *   · Frida 被 baiduprotect 拦。
 * 所以只能从模块内部把树（含文本）打进 logcat。
 *
 * 输出格式（每行一个节点，便于 grep / 切片）：
 *   TREE <缩进><简单类名> <可见性> <bounds> #<十六进制id> <资源名> txt=<文本>
 */
class TreeDump private constructor() {

    companion object {

        private const val MAX_NODES = 2600
        private const val MAX_DEPTH = 60

        @JvmStatic
        fun dump(root: View?, label: String) {
            if (root == null) return
            val n = intArrayOf(0)
            H.log(Log.INFO, MainHook.TAG, "TREE ---------- begin " + label
                    + " cls=" + root.javaClass.name + " ----------")
            walk(root, 0, n, label)
            H.log(Log.INFO, MainHook.TAG, "TREE ---------- end " + label + " nodes=" + n[0] + " ----------")
        }

        private fun walk(v: View?, depth: Int, n: IntArray, label: String) {
            if (v == null || depth > MAX_DEPTH || n[0] > MAX_NODES) return
            n[0]++
            try {
                val cls = v.javaClass.name
                val simple = cls.substring(cls.lastIndexOf('.') + 1)
                val id = v.id
                var idName = ""
                if (id != 0 && id != -1) {   // -1 = NO_ID（Talos 渲染的视图全是 -1，别去查名字刷错误日志）
                    var name: String? = null
                    try { name = v.resources.getResourceEntryName(id) } catch (ignored: Throwable) {}
                    idName = "#" + Integer.toHexString(id) + (if (name == null) "" else " " + name)
                }
                var txt = ""
                if (v is TextView) {
                    val cs: CharSequence? = v.text
                    if (cs != null) txt = cs.toString().replace('\n', ' ')
                }
                val vis = if (v.visibility == View.VISIBLE) "V"
                        else (if (v.visibility == View.INVISIBLE) "I" else "G")
                val ind = StringBuilder()
                for (i in 0 until depth) {
                    if (i >= 30) break
                    ind.append("  ")
                }
                H.log(Log.INFO, MainHook.TAG, "TREE " + label + " " + ind + simple + " " + vis
                        + " " + v.left + "," + v.top + "-" + v.right + "," + v.bottom
                        + " " + idName + (if (txt.length == 0) "" else " txt=" + txt))
                if (txt.length > 0) return   // 文本节点不再下钻（没有意义，省日志）
            } catch (ignored: Throwable) {}

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i), depth + 1, n, label)
                }
            }
        }
    }
}
