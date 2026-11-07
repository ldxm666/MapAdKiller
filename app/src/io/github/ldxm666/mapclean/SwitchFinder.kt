package io.github.ldxm666.mapclean

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

import java.util.ArrayList
import java.util.Locale

/**
 * 设置页搜索：一条横向卡片，输入即跨分类过滤所有开关。
 *
 * <h3>为什么不是简单 filter(List)</h3>
 * 设置页的开关分散在三个配置 group（高德 / 百度 / 腾讯）和若干折叠抽屉里，
 * 而且腾讯的工具目录有 90 多项。让每个页面各自实现一遍过滤等于写四份，
 * 还会有四份不同的匹配规则。这里抽出一条公共契约：
 *
 * <pre>
 *   Finder.of(page).index(...)   —— 页面把"这一页有哪些开关"报上来（一次性）
 *   Finder.of(page).query(...)   —— 用户输入
 * </pre>
 *
 * <h3>索引怎么建</h3>
 * 复用开关工厂已有的 {@code key → GlassSwitch} Map（{@code switches} /
 * {@code bmapSwitches} / {@code tmapSwitches}）与"键名 → 人类可读标签"的映射。
 * 标签由开关工厂在建行时回写，所以中英文、工具 ID、备注文字都能被搜到，
 * 而且永不与界面显示的文字脱节。
 *
 * <h3>匹配规则</h3>
 * 大小写不敏感，对"标题 + 备注 + 配置键"三段拼接后的子串匹配。
 * 这样「步行」「walk」「tool_walk」「ui_tool_grid」四种敲法都能命中同一个开关。
 */
class SwitchFinder(private val context: Context, private val dark: Boolean) {

    /** 一个可被搜到的开关。 */
    class Entry(
        @JvmField val key: String,
        title: String?,
        note: String?,
        /** 该开关属于哪一页（0 百度 / 1 高德 / 2 腾讯 / 3 其他）。 */
        @JvmField val page: Int
    ) {
        @JvmField
        val title: String = title ?: ""

        @JvmField
        val note: String = note ?: ""

        fun haystack(): String {
            return (this.title + "\n" + this.note + "\n" + key).toLowerCase(Locale.ROOT)
        }
    }

    /** 目标页面的契约：给它一个查询，它自己决定显示/隐藏哪些行。 */
    interface Target {
        /** @return true = 这次查询由本页处理（用于短路，避免每页都算一遍） */
        fun applyQuery(query: String): Boolean

        /** 查询被清空时调用，恢复该页原本的展开/折叠状态。 */
        fun resetQuery()
    }

    /** 每页的开关索引；页下标与 pager.addView 的顺序一致。 */
    private val pages = ArrayList<MutableList<Entry>>()
    private val targets = ArrayList<Target?>()

    private val barView: LinearLayout
    private val searchField: EditText
    private val clear: TextView
    private val count: TextView
    private var currentQuery = ""

    /** 命中结果面板：跨页列出所有匹配项，点一下跳过去并展开所在抽屉。 */
    private val results: LinearLayout
    private val hostView: View

    private var onNavigate: Runnable? = null

    init {
        barView = LinearLayout(context)
        barView.orientation = LinearLayout.HORIZONTAL
        barView.gravity = Gravity.CENTER_VERTICAL
        barView.background = UiKit.outlined(
            if (dark) 0xFF1D222A.toInt() else 0xFFF0F2F5.toInt(),
            if (dark) 0xFF262B33.toInt() else 0xFFE7E9EE.toInt(),
            Math.max(1, UiKit.dp(context, 1f)), UiKit.dpf(context, 12f)
        )
        barView.setPadding(UiKit.dp(context, 12f), 0, UiKit.dp(context, 8f), 0)

        val glyph = Icon(context, Icon.SEARCH, cTxS())
        barView.addView(glyph, LinearLayout.LayoutParams(UiKit.dp(context, 18f), UiKit.dp(context, 18f)))

        searchField = EditText(context)
        searchField.hint = "搜索开关 / 工具名 / 配置键"
        searchField.setHintTextColor(cTxS())
        searchField.setTextColor(cTxP())
        searchField.setTextSize(14f)
        searchField.isSingleLine = true
        searchField.background = null
        searchField.setPadding(UiKit.dp(context, 8f), 0, UiKit.dp(context, 4f), 0)
        // 设置页底下就是 pager，软键盘弹起会把它顶得很难看；回车即收起。
        searchField.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        searchField.inputType = EditorInfo.TYPE_CLASS_TEXT
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                setQuery(s?.toString() ?: "")
            }

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }
        })
        barView.addView(
            searchField, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
        )

        count = TextView(context)
        count.setTextSize(11.5f)
        count.setTextColor(cTxS())
        count.visibility = View.GONE
        barView.addView(count)

        clear = TextView(context)
        clear.text = "✕"
        clear.setTextSize(13f)
        clear.setTextColor(cTxS())
        clear.gravity = Gravity.CENTER
        clear.contentDescription = "清除搜索"
        clear.visibility = View.GONE
        clear.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                searchField.setText("")
            }
        })
        barView.addView(
            clear, LinearLayout.LayoutParams(
                UiKit.dp(context, 30f), LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        results = LinearLayout(context)
        results.orientation = LinearLayout.VERTICAL
        results.visibility = View.GONE

        val hs = HorizontalScrollView(context)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false
        hs.addView(
            results, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        hostView = hs
    }

    // ── 配色（与 MainActivity 同一套 Liquid Glass 色板，避免为了一条搜索框再建一个 palette）──
    private fun cTxP(): Int = if (dark) 0xFFF3F5F8.toInt() else 0xFF14171C.toInt()
    private fun cTxS(): Int = if (dark) 0xFFA3ACBA.toInt() else 0xFF5B6472.toInt()
    private fun cAccent(): Int = if (dark) 0xFF6FA0FF.toInt() else 0xFF2563EB.toInt()
    private fun cRipple(): Int = if (dark) 0x22FFFFFF else 0x0F000000

    /** 注册一页；page 下标必须与 pager 子视图顺序一致。 */
    fun register(page: Int, target: Target) {
        while (pages.size <= page) {
            pages.add(ArrayList())
            targets.add(null)
        }
        pages[page] = ArrayList()
        targets[page] = target
    }

    /** 报一个开关上来。重复 key 只保留第一条（同名键在不同 group 里本就互斥）。 */
    fun index(key: String?, title: String?, note: String?, page: Int) {
        if (key == null) return
        if (page < 0 || page >= pages.size) return
        val list = pages[page]
        for (i in list.indices) {
            if (key == list[i].key) return
        }
        list.add(Entry(key, title, note, page))
    }

    fun bar(): LinearLayout = barView

    fun resultsHost(): View = hostView

    /** 页面切换后调用：让搜索框反映当前页可搜的范围。 */
    fun attach(page: Int) {
        // 空页不显示搜索框（"其他"页只有两三项，搜了反而碍事）。
        val has = page >= 0 && page < pages.size && pages[page].isNotEmpty()
        barView.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) setQuery("")
    }

    fun setOnNavigate(r: Runnable?) {
        onNavigate = r
    }

    private fun setQuery(value: String?) {
        val next = (value ?: "").trim().toLowerCase(Locale.ROOT)
        if (next == currentQuery) return
        currentQuery = next
        val on = currentQuery.isNotEmpty()
        clear.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) count.visibility = View.GONE
        var hits = 0
        for (p in targets.indices) {
            val t = targets[p] ?: continue
            if (on) {
                hits += filterPage(p, currentQuery)
            } else {
                t.resetQuery()
            }
        }
        if (on) {
            count.visibility = View.VISIBLE
            count.text = "$hits 项"
            rebuildResults(hits)
        } else {
            results.removeAllViews()
            results.visibility = View.GONE
        }
        onNavigate?.run()
    }

    /**
     * 让一页自己过滤，并统计它的命中数。
     *
     * <p>命中统计走 [index]（页面建行时报上来的那几段文字），
     * 实际显隐走页面挂在行上的 haystack tag。两条路用的是同一套
     * 「标题 + 备注 + 配置键」文本，所以计数与画面不会打架。
     */
    private fun filterPage(page: Int, q: String): Int {
        var n = 0
        val list = pages[page]
        for (i in list.indices) {
            if (list[i].haystack().contains(q)) n++
        }
        targets[page]?.applyQuery(q)
        return n
    }

    /** 跨页结果面板：横向滚动的胶囊，点一下跳到那一页。 */
    private fun rebuildResults(hits: Int) {
        results.removeAllViews()
        if (hits == 0) {
            results.visibility = View.GONE
            return
        }
        results.visibility = View.VISIBLE
        for (p in pages.indices) {
            val list = pages[p]
            var n = 0
            for (i in list.indices) {
                if (list[i].haystack().contains(currentQuery)) n++
            }
            if (n == 0) continue
            results.addView(chip(PAGE_LABEL[p] + " · " + n, p))
        }
    }

    private fun chip(label: String, page: Int): View {
        val c = TextView(context)
        c.text = label
        c.setTextSize(12.5f)
        c.gravity = Gravity.CENTER
        c.setPadding(
            UiKit.dp(context, 13f), UiKit.dp(context, 7f),
            UiKit.dp(context, 13f), UiKit.dp(context, 7f)
        )
        c.setTextColor(cAccent())
        c.background = UiKit.rippleBare(cRipple(), UiKit.dpf(context, 16f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (results.childCount > 0) lp.marginStart = UiKit.dp(context, 6f)
        c.layoutParams = lp
        c.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                onNavigate?.run()
                jumpToPage(page)
            }
        })
        UiKit.pressable(c, 0.95f)
        return c
    }

    /** 由设置页在跳页后回调（Pager.setOnPageChanged 已经存在，直接复用）。 */
    interface PageJumper {
        fun jump(page: Int)
    }

    private var jumper: PageJumper? = null

    fun setPageJumper(j: PageJumper?) {
        jumper = j
    }

    private fun jumpToPage(page: Int) {
        jumper?.jump(page)
    }

    /** 当前查询；供页面自己决定"这一行算不算命中"时用。 */
    fun query(): String = currentQuery

    fun matches(e: Entry): Boolean = currentQuery.isNotEmpty() && e.haystack().contains(currentQuery)

    /** 切页 / 退出时收起键盘，否则键盘会留在下一页上面。 */
    fun dismissKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(searchField.windowToken, 0)
            searchField.clearFocus()
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private val PAGE_LABEL = arrayOf("百度", "高德", "腾讯")
    }
}