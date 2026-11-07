package io.github.ldxm666.mapadkiller

import android.app.Activity
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent

import java.util.regex.Pattern

/**
 * ViewKiller — 通用视图层兜底：Activity onResume 后遍历 DecorView，
 * 命中「类名正则 / 资源名正则 / 无障碍"广告"标签」即 GONE + 移除子树。
 * 广告角标（小"广告"标签）命中时上溯到列表项容器一并移除。
 */
class ViewKiller(tag: String, classRegex: String?, resRegex: String?) {

    private val classPat: Pattern? = if (classRegex == null) null else Pattern.compile(classRegex)
    private val resPat: Pattern? = if (resRegex == null) null else Pattern.compile(resRegex)
    private val tag: String = tag

    /**
     * 资源名按 id 缓存。
     *
     * Resources#getResourceName() 是一次真正的资源表查询，而 ViewKiller 每次 resume 要
     * 走三遍整棵视图树、每个节点都调一次 —— 这是纯浪费（同一个 id 的结果永远一样）。
     * 缓存后每棵树的成本从「节点数 × 资源查询」降到「不同 id 数 × 一次」。
     */
    private val resNames: HashMap<Int, String> = HashMap()

    fun sweep(act: Activity?) {
        if (act == null || act.isFinishing) return
        try {
            val decor: View = act.window.decorView
            sweep(decor, act.resources)
        } catch (t: Throwable) {
            H.log(android.util.Log.WARN, MainHook.TAG, tag + " sweep err " + t)
        }
    }

    private fun sweep(v: View, res: Resources) {
        var node = v
        var gone = false
        try {
            if (classPat != null && classPat.matcher(node.javaClass.name).find()) gone = true
            if (!gone && resPat != null && node.id != View.NO_ID && node.id != 0) {
                val name = resName(res, node.id)
                if (name != null && name.length > 0 && resPat.matcher(name).find()) gone = true
            }
            // 无障碍标签识别：广告角标通常自带 "广告"/"Ad" contentDescription。
            // v1.1.0 收紧：①只认**全文等于**「广告」的节点（子串会命中
            // 「广告过滤」「拦截广告」等正常文案）；②节点必须是小尺寸角标
            // （宽 ≤ 45% 屏宽），整宽的「广告」栏是页面自有元素不是角标。
            if (!gone) {
                var cd: CharSequence? = node.contentDescription
                if (cd == null && node is android.widget.TextView) {
                    cd = node.text
                }
                if (cd != null) {
                    val s = cd.toString()
                    val label = s == "广告" ||
                            s.equals("ad", ignoreCase = true) || s.equals("AD", ignoreCase = true)
                    if (label && node.width <= node.rootView.width * 45 / 100) {
                        node = bubbleToAdContainer(node)
                        gone = true
                    }
                }
            }
        } catch (ignored: Throwable) {
            // 非法 id / 资源不存在时跳过
        }
        if (gone && node.visibility != View.GONE) {
            node.visibility = View.GONE
            if (node is ViewGroup) {
                node.removeAllViews()
            }
            H.log(android.util.Log.INFO, MainHook.TAG,
                    tag + " KILLED view: " + node.javaClass.name + " id=" + safeName(node, res))
        }
        if (node is ViewGroup) {
            for (i in 0 until node.childCount) {
                sweep(node.getChildAt(i), res)
            }
        }
    }

    /** "广告"角标命中后上溯到列表项容器：第一个 高度>=96px 且 ≥3倍角标 的祖先，最多 5 层 */
    private fun bubbleToAdContainer(label: View): View {
        val lh = Math.max(label.height, 1)
        var cur: View = label
        for (i in 0 until 5) {
            val p: ViewParent = cur.parent ?: break
            if (p !is View) break
            cur = p
            if (cur.height >= 96 && cur.height >= 3 * lh) return cur
        }
        return label
    }

    /** 带缓存的资源名查询（查不到缓存空串，避免重复抛异常） */
    private fun resName(res: Resources, id: Int): String {
        val cached = resNames[id]
        if (cached != null) return cached
        var c: String? = try {
            res.getResourceName(id)
        } catch (t: Throwable) {
            ""
        }
        if (c == null) c = ""
        resNames.put(id, c)
        return c
    }

    private fun safeName(v: View, res: Resources): String {
        if (v.id <= 0) return "-"
        val n = resName(res, v.id)
        return if (n.length == 0) "?" else n
    }
}
