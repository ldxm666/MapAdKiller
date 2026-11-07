package io.github.ldxm666.mapadkiller

import android.content.Context

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet

/** Version 17 protocol adapter: fixed paths and stable identifiers, never display text. */
object AmapData {
    interface Settings {
        fun enabled(key: String): Boolean
        fun selected(key: String): Boolean = enabled(key)
    }
    @JvmField
    val CURRENT: Settings = object : Settings {
        override fun enabled(key: String): Boolean {
            // 热键走 volatile 读（零 map 查、零时间比较）；其余键保持原样。
            val hot = Config.hotFlag(key)
            return hot?.on() ?: Config.visible(key)
        }
        override fun selected(key: String): Boolean {
            val hot = Config.hotFlag(key)
            return hot?.on() ?: Config.selected(key)
        }
    }
    @JvmField
    val TOOLS: Array<Array<String>> = arrayOf(
        arrayOf("102", "驾车"), arrayOf("103", "公交地铁"), arrayOf("168", "租车"), arrayOf("106", "打车"),
        arrayOf("317", "订酒店"), arrayOf("327", "火车票"), arrayOf("469", "顺风车"), arrayOf("483", "高德扫街"),
        arrayOf("461", "高德出行节"), arrayOf("362", "代驾"), arrayOf("418", "秒送"), arrayOf("151", "实时公交"),
        arrayOf("380", "旅游度假"), arrayOf("116", "车主服务"), arrayOf("113", "景点游玩"), arrayOf("115", "离线地图"),
        arrayOf("104", "步行"), arrayOf("105", "骑行"), arrayOf("86", "更多工具")
    )

    /**
     * How many tool cells the home toolbar renders.
     *
     * <p>Measured from the installed build: `com.autonavi...toolbox.b.a` walks
     * `id0…id4` (its loop bound is 5) and lays the kept cells out in one row. Writing
     * more slots than this is harmless but invisible, so the payload is written for exactly
     * this many cells.
     *
     * <p><b>必须是 10（2 行 × 5 列），不能是 11。</b>宿主 `ToolboxPanelV2` 的宫格容器
     * 固定只排两行；写第 11 个槽位会被挤成第三行，直接压到下面的「回家 / 去单位」行上
     * —— 这就是用户报的「工具宫格有点重叠」。第 11 个及以后的工具仍然可以在「更多工具」里找到。
     */
    const val HOME_SLOTS = 10

    /**
     * 「运营推广位」黑名单 —— 只影响这几个 id，其它 `tool_id_*` 目录工具一律不动。
     *
     * <p>313 = 优惠加油：云端 `tool_list_data` 的服务分类（cid9）里常驻（29 项中第 16 项，
     * 带红色「领油券」角标），用户在「更多工具」里能随时再选回首页。它的 `tool_id_313`
     * 在 default_config.json 里是 `null`，面板按默认值画成「关」，而
     * [toolKeep] 以前把「未设置」当透传放行 ⇒ UI 说关、代码放行，这就是「优惠加油
     * 很顽固」的根因。
     */
    private val PROMO_TOOLS: Set<String> =
            Collections.unmodifiableSet(HashSet(listOf("313")))

    /** 15.21 专用分支开关（由 AmapMine1521.install 置位；17.02 永远 false）。 */
    @JvmField
    @Volatile
    var IS_1521 = false

    /** The app's fixed 「更多工具」 entry; it lives in the slot list like any other tool. */
    const val MORE_TOOLS = "86"

    /**
     * 官方 `local_tool_list_for_xml_v2` 的真实字段集 —— 从设备 toolbox.xml 逐字段核出来的，
     * 一个不多一个不少（15 个）。**没有 weight**：宿主的工具行固定切 5 列，一旦槽位被带上
     * weight，LinearLayout 会按权重重新分配宽度，多个 cell 就挤在一起、视觉上重叠。
     * 所以写槽位时只允许写这 15 个字段。
     */
    private val OFFICIAL_FIELDS = arrayOf(
        "id", "schema", "name", "size", "containerClass", "isLottie", "displayLottie",
        "displayImage", "imageUrlRender", "lottieUrlRender", "imageClass", "itemDataForClick",
        "display", "imageToken", "moreItem"
    )
    private val SLOT_FIELDS = arrayOf(
        "id", "schema", "name", "size", "containerClass", "isLottie", "displayLottie",
        "displayImage", "imageUrlRender", "lottieUrlRender", "imageClass", "itemDataForClick",
        "display", "imageToken", "moreItem", "label", "tips", "redDot", "badge",
        "imageLegacy", "icon", "weight", "cid", "red_dot_key"
    )

    @JvmStatic
    fun toolVisible(id: String?, s: Settings): Boolean {
        val visible = toolVisibleRaw(id, s)
        reportTool(id, visible)
        return visible
    }

    /**
     * 搜索页金刚位（美食 / 酒店 / 景点门票 / 加油充电 / 超划算 / 扫街榜 那一排）的 tool id。
     *
     * <p>真机实测（2026-10-25 23:57）：搜索页那排 **就是 toolbox payload 被 AJX 页渲染**，
     * 走的正是本类的 `toolVisible`；把 `ui_tools_visible` 关掉后那排整块消失
     * （截图 y370~554 高饱和像素 0.000 实证）。所以「整排开关」在这里收口最干净：
     * 由 [Config.searchGrid] 控制，**默认 true = 完全维持原有行为**。
     *
     * <p>按 id 判定，不按文案（文案是服务端下发的，随时会变）。
     * 新出现的运营工具如果漏网，看日志 `event=amap_tool_visible` 补 id 即可。
     */
    private val SEARCH_GRID_IDS: Set<String> = HashSet(listOf(
                    "323",   // 美食
                    "490",   // 加油充电
                    "501",   // 超市
                    "544",   // 超划算
                    "483",   // 高德扫街榜
                    "484")) // 特价酒店

    /**
     * 搜索页时间窗：`AmapSearchGrid` 在搜索页 `load` 时打一个时间戳，
     * 窗口内构建的 toolbox payload 会跳过 [SEARCH_GRID_IDS]。
     *
     * <p>为什么用时间窗而不是 ThreadLocal：payload 构建可能在别的线程/更晚的时机发生，
     * 线程标记会漏；时间窗只覆盖「刚打开搜索页的那几秒」，首页/更多工具页都不在那个窗口内
     * ⇒ 默认行为（未开窗）逐字节不变。
     */
    @Volatile
    private var searchPageUntil = 0L

    /** 由 AmapSearchGrid 在搜索页加载时调用。 */
    @JvmStatic
    fun markSearchPage(millis: Long) {
        searchPageUntil = android.os.SystemClock.uptimeMillis() + millis
    }

    private fun inSearchPageWindow(): Boolean {
        return android.os.SystemClock.uptimeMillis() < searchPageUntil
    }

    /** 搜索页金刚位：开关关掉 + 处于搜索页时间窗 ⇒ 从 payload 里跳过。 */
    // ★ 已证伪（勿再启用）：曾假设「搜索页那排 = 首页 toolbox payload」，据此按 id 剔除。
    //   5 轮实机否证：即使去掉时间窗，sat 仍恒为 0.341；且同一构建上关掉 ui_tools_visible
    //   也丝毫不影响那排 ⇒ 它并不走这份 payload。留着恒 false，保持原有行为逐字节不变，
    //   同时避免误伤「更多工具」页（那 6 个 id 确实在它的候选列表里）。
    private fun skipSearchGrid(id: String?): Boolean {
        return false   // 原实现：id != null && SEARCH_GRID_IDS.contains(id) && !Config.searchGrid()
    }

    private fun toolVisibleRaw(id: String?, s: Settings): Boolean {
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) return false
        val allowlist = s.enabled(Config.K_TOOLS_ALLOWLIST)
        val key = AmapToolCatalog.key(id) ?: return false
        return if (allowlist) s.selected(key) else s.enabled(key)
    }

    /**
     * 「宿主自有列表」的保留判定 —— 用于 `local_tool_list_for_xml_v2` 存储读取与
     * `toolbox.a.a(...)` 的 source payload。
     *
     * <p>宿主列表里的槽位是**用户自己**在高德「更多工具」里选的，模块只能剔除「显式关掉 /
     * 推广」的工具，不能因为模块面板里 `tool_id_<id>` 默认 false 就把它们砍掉。
     * 真机事故：用户新加一个工具后，读回 `local_tool_list_for_xml_v2` 时被过滤 ⇒
     * 首页宫格永远只有那 5 个（「我添加工具但是不会增加」的根因）。
     *
     * <p>规则：`K_TOOLS_VISIBLE` 关 ⇒ false；未知 id ⇒ true；`tool_id_*` ⇒
     * true（用户在高德里选的）；其余 legacy 工具走 `s.enabled(key)`（保留
     * [Config.TOOLS_DEFAULT_OFF] 那 7 条推广工具的默认剔除）。
     */
    @JvmStatic
    fun toolKeep(id: String?, s: Settings): Boolean {
        if (id == null || id.isEmpty()) return false
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) {
            reportKeep(id, null, false, "switch_off")
            return false
        }
        val key = AmapToolCatalog.key(id)
        if (key == null) {
            reportKeep(id, null, true, "unknown")
            return true
        }
        if (key.startsWith("tool_id_")) {
            // ★ 方案 C（透传优先）：宿主 payload 里给的工具有什么就显示什么，
            //   只有用户在模块面板里**显式关掉**的才剔除。
            //   之前用 s.selected(key) 要求「显式打开才保留」，导致配置里没有记录的工具
            //   （包括宿主自己给的）全被剔掉，首页只剩「更多工具」一个格子。
            val explicit = Config.explicit(key)
            // ★ 未设置时按「面板显示的默认值」处理：推广位（313 优惠加油）默认关，
            //   其余目录工具保持透传，避免历史事故「加了工具但首页不增加」。
            val kept = explicit ?: !PROMO_TOOLS.contains(id)
            reportKeep(id, key, kept,
                    if (kept) (if (explicit == null) "pass_through" else "user_selected")
                    else (if (explicit == null) "promo_default_off" else "switch_off"))
            return kept
        }
        val kept = s.enabled(key)
        val label = key.substring(Config.K_TOOL_PREFIX.length)
        reportKeep(id, key, kept, if (kept) "user_selected"
                else (if (Config.TOOLS_DEFAULT_OFF.contains(label)) "promo" else "switch_off"))
        return kept
    }

    /**
     * 「模块补位」判定 —— 只用于 `fill`：只有用户在模块面板里**显式打开**的工具
     * 才会被补进空位，避免 `tool_id_*` 那 ~95 条目录工具全部塞进首页。
     */
    @JvmStatic
    fun toolFill(id: String?, s: Settings): Boolean {
        if (id == null || id.isEmpty()) return false
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) return false
        val key = AmapToolCatalog.key(id) ?: return false
        val filled: Boolean
        if (key.startsWith("tool_id_")) filled = s.selected(key)
        else filled = s.enabled(key)
        reportFill(id, key, filled, s.selected(key))
        return filled
    }

    /** toolFill 诊断：每个 id 只报一次，封顶 60 条。 */
    private val FILL_REPORTED: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())

    private fun reportFill(id: String, key: String?, filled: Boolean, selected: Boolean) {
        try {
            if (FILL_REPORTED.size >= TOOL_REPORT_MAX) return
            if (!FILL_REPORTED.add(id)) return
            H.logv("event=amap_tool_fill id=" + id + " key=" + key + " filled=" + filled
                    + " selected=" + selected)
        } catch (ignored: Throwable) {
        }
    }

    /** toolKeep 诊断：每个 id 只报一次，封顶 60 条。 */
    private val KEEP_REPORTED: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())

    private fun reportKeep(id: String, key: String?, kept: Boolean, why: String) {
        try {
            if (KEEP_REPORTED.size >= TOOL_REPORT_MAX) return
            if (!KEEP_REPORTED.add(id)) return
            H.logv("event=amap_tool_keep id=" + id + " key=" + key + " kept=" + kept + " why=" + why)
        } catch (ignored: Throwable) {
        }
    }

    // ── 节流诊断（每个 id 只打一次，总数封顶）────────────────────────────────────
    // 用途：定位「工具栏隐藏未生效」时，先在真机确认某个 id 到底有没有走这里。
    // 判读：
    //   · 日志里**没有** id=317/116  ⇒ 这条 UI 不经过 toolVisible（走的是别的构建器）；
    //   · 有且 visible=false         ⇒ 过滤算对了，是构建器在过滤后把格子又加回来了；
    //   · 有且 visible=true          ⇒ 配置/默认值判定与预期不符。
    // 封顶 60 条 + 每个 id 一次，首页反复重建也不会刷屏。
    private val TOOL_REPORTED: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())
    private const val TOOL_REPORT_MAX = 60

    private fun reportTool(id: String?, visible: Boolean) {
        try {
            if (id == null || id.isEmpty()) return
            if (TOOL_REPORTED.size >= TOOL_REPORT_MAX) return
            if (!TOOL_REPORTED.add(id)) return
            H.log("event=amap_tool_visible id=" + id
                    + " label=" + toolLabel(id)
                    + " key=" + AmapToolCatalog.key(id)
                    + " visible=" + visible)
        } catch (ignored: Throwable) {
        }
    }

    /** id → 中文标题；查不到就回 "?"（不猜、不抛）。 */
    private fun toolLabel(id: String): String {
        try {
            for (entry in AmapToolCatalog.ENTRIES) {
                if (id == entry[0]) return entry[1]
            }
            for (entry in TOOLS) {
                if (id == entry[0]) return entry[1]
            }
        } catch (ignored: Throwable) {
        }
        return "?"
    }

    /**
     * 把一段「工具面板 payload 字符串」过滤后返回（字符串进出，供 hook `k84.f()` 使用）。
     *
     * <h3>为什么需要这个入口</h3>
     * 17.02 实测（classes.dex / classes5.dex）：`k84.f()Ljava/lang/String;` 被 **5 处**调用：
     * <pre>
     *   quickservice/toolbox/a.a(...)        ← QS 面板工厂**内部兜底**（入参没有 id0 时）
     *   ToolboxPanelV2.init(...)             ← 兜底
     *   ToolboxPanelV3.init(...)             ← 兜底
     *   BootBizDataPreloaderImpl.getToolboxData()  ← 它自己也是用 k84.f() 建缓存字段 g
     *   BootBizDataPreloaderImpl.prepare(...)
     * </pre>
     * 也就是说：只要走到任何一条兜底，payload 就**直接来自 `k84.f()`**，
     * 完全不经过 `getToolboxData` / `AjxModuleLocalStorage` 上的 hook ⇒
     * 这正是「过滤逻辑没错、工具却照样显示」的根因。所以必须在**最上游**也拦一道。
     *
     * <p>参数 `fill` 恒为 false：`k84.f()` 是 App 自己的默认清单，
     * 往里"发明槽位"会污染它的默认数据。
     */
    @JvmStatic
    fun toolboxRaw(raw: String?, s: Settings): String? {
        if (raw == null || raw.isEmpty() || raw.length > 4 * 1024 * 1024) return raw
        // 重入闸：本线程已经在这条路径上 ⇒ 说明是自我递归（hook 里又调到被 hook 的方法）。
        // 这时**原样放行**，绝不往下走 —— 见 defaultPayload() 上方那段现场栈。
        if (IN_RAW.get() != null) return raw
        IN_RAW.set(true)
        try {
            val source = JSONObject(raw)
            val out = toolbox(source, s, false)
            return if (out === source) raw else out.toString()
        } catch (t: Throwable) {
            return raw   // 解析不了就原样放行，绝不因为诊断/过滤把面板搞崩
        } finally {
            IN_RAW.remove()
        }
    }

    @JvmStatic
    fun toolSelected(id: String?, s: Settings): Boolean {
        val key = AmapToolCatalog.key(id) ?: return false
        return s.selected(key)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun storage(key: String?, raw: String?, s: Settings): String? {
        if (raw == null || raw.isEmpty()) return raw
        if ("local_tool_list_for_xml_v2" == key) return toolbox(JSONObject(raw), s).toString()
        if ("tool_list_data" == key) return catalog(raw, s)
        if ("recommendTools" != key && "aosRecommendTools" != key
                && "choiceTools" != key) return raw
        val out = JSONObject(raw)
        val listKey = if ("choiceTools" == key) "choiceTools" else "recommendTools"
        val source = out.optJSONArray(listKey) ?: return raw
        val kept = JSONArray()
        for (i in 0 until source.length()) {
            val tool = source.optJSONObject(i)
            if (tool == null || toolVisible(tool.optString("id"), s)) kept.put(source.opt(i))
        }
        if (kept.length() == source.length()) return raw
        out.put(listKey, kept)
        return out.toString()
    }

    /**
     * 过滤云端「更多工具」目录 `tool_list_data`（120 KB 分类 JSON）。
     *
     * <p>这是 313 的**第二个口子**：页面从 `AjxModuleLocalStorage#getItemSync("toolbox",
     * "tool_list_data")` 整包读走，再渲染 6 个分类；`storage()` 以前对这个 key 走 default
     * 分支原样返回，于是 313 永远在候选列表里，随时能被再选回首页。
     *
     * <p>只剔 [PROMO_TOOLS]，且用户显式打开 `tool_id_313` 时仍然保留；其它分类、
     * 其它字段一个字节都不动（无改动直接返回原串）。
     */
    @JvmStatic
    fun catalog(raw: String?, s: Settings): String? {
        if (raw == null || raw.isEmpty() || raw.length > 4 * 1024 * 1024) return raw
        if (raw == CATALOG_IN) return CATALOG_OUT
        try {
            val src = JSONObject(raw)
            val cats = src.optJSONArray("category") ?: return raw
            var removed = 0
            for (i in 0 until cats.length()) {
                val cat = cats.optJSONObject(i)
                val tools = cat?.optJSONArray("tools")
                if (tools == null || tools.length() == 0) continue
                val kept = JSONArray()
                for (j in 0 until tools.length()) {
                    val tool = tools.optJSONObject(j)
                    val id = tool?.optString("id")
                    // Java 时期 AmapToolCatalog.key() 可能返回 null，而 selected(null)
                    // 走 enabled(null) 返回 false ⇒ !false == true ⇒ 该工具被移除。
                    // Kotlin 的 selected() 形参非空，所以显式写出同一语义（null 一样移除），
                    // 不能改成 `s.selected(key(id)!!)` —— 那会在未知 id 上 NPE。
                    val toolKey = if (id == null) null else AmapToolCatalog.key(id)
                    if (id != null && PROMO_TOOLS.contains(id)
                            && (toolKey == null || !s.selected(toolKey))) {
                        removed++
                        continue
                    }
                    kept.put(tools.opt(j))
                }
                if (kept.length() != tools.length()) cat.put("tools", kept)
            }
            if (removed == 0) return raw
            val out = src.toString()
            CATALOG_IN = raw
            CATALOG_OUT = out
            H.log("event=amap_catalog_filter removed=" + removed)
            return out
        } catch (t: Throwable) {
            return raw   // 解析失败原样放行，绝不把「更多工具」页搞崩
        }
    }

    /** [catalog] 的单槽缓存（云端刷新才会换串，命中率接近 100%）。 */
    @Volatile
    private var CATALOG_IN: String? = null
    @Volatile
    private var CATALOG_OUT: String? = null

    /** Filter-only form: keep the app's own slots, drop the ones switched off. */
    @JvmStatic
    @Throws(Exception::class)
    fun toolbox(source: JSONObject, s: Settings): JSONObject {
        return toolbox(source, s, false)
    }

    /**
     * Rewrite the home toolbar's slot payload.
     *
     * <h3>Why this is more than a filter</h3>
     * The app does not build the toolbar from a list of enabled tools: it renders whatever
     * slots its own `local_tool_list_for_xml_v2` happens to contain, and it rewrites
     * that list itself from the 「更多工具」 page. Filtering alone therefore only ever
     * <i>removes</i> cells -- switching 步行 or 骑行 on did nothing, because those ids were
     * never in the list. On this build the list held exactly two entries
     * (`103 公交地铁` and `86 更多工具`), so 「打开了步行骑行但 UI 没有」 was
     * structural, not a rendering glitch.
     *
     * <p>The payload is now reconciled instead: the app's own order is preserved for the
     * tools it placed, and enabled catalogue tools that are missing fill the remaining cells.
     *
     * @param fill only the boot payload is filled. `local_tool_list_for_xml_v2` is the
     *             app's own store and is read back by the 「更多工具」 page, so invented slots
     *             are never written into it.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun toolbox(source: JSONObject, s: Settings, fill: Boolean): JSONObject {
        return toolbox(source, s, fill, HOME_SLOTS)
    }

    /**
     * Same as [toolbox] with an explicit slot cap.
     *
     * <p>`maxSlots <= 0` means "keep the host's own slot count" -- required by 15.21,
     * whose default payload carries 15 tools and whose panel pages through them; truncating
     * to [HOME_SLOTS] would silently drop tools the user enabled.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun toolbox(source: JSONObject, s: Settings, fill: Boolean, maxSlots: Int): JSONObject {
        val kept = ArrayList<Int>()
        val scanLimit = if (maxSlots <= 0) 32 else Math.max(10, maxSlots)
        var count = 0
        while (count < scanLimit && source.has("id" + count)) {
            val id = source.optString("id" + count)
            if (id.length > 0 && toolKeep(id, s) && !skipSearchGrid(id)) kept.add(count)
            count++
        }

        val extra = ArrayList<Array<String>>()
        if (fill) {
            val present = HashSet<String>()
            for (index in kept) present.add(source.optString("id" + index))
            for (entry in AmapToolCatalog.ENTRIES) {
                if (maxSlots > 0 && kept.size + extra.size >= maxSlots) break
                if (present.contains(entry[0]) || !toolFill(entry[0], s) || skipSearchGrid(entry[0])) continue
                extra.add(entry)
                present.add(entry[0])
            }
        }
        if (count == kept.size && extra.isEmpty() && !Config.toolColorIcon()) return source

        val out = JSONObject(source.toString())
        val cells = if (fill) (if (maxSlots <= 0) Math.max(count, kept.size + extra.size)
                else Math.max(count, maxSlots)) else Math.max(count, kept.size + extra.size)
        // 宿主自己的「空槽」是行距占位，**不能删键**。
        // toolbox.a.a 的循环条件是 has("id"+i)：键一消失就提前结束，后面本该由空槽
        // 撑出来的行距全部丢失，两行工具就被压在一起（用户看到的“重叠”）。
        // 正确做法：拿宿主本来的空槽当模板，原样回填到剩余槽位。
        val pad = emptySlotTemplate(source, count)
        for (i in 0 until cells) {
            for (field in SLOT_FIELDS) out.remove(field + i)
            if (i < kept.size) copySlot(source, out, kept[i], i)
            else if (i - kept.size < extra.size) writeSlot(out, extra[i - kept.size], i)
            else if (pad != null) {
                for (field in SLOT_FIELDS) if (pad.has(field)) out.put(field + i, pad.opt(field))
            } else blankSlot(out, i)
        }
        if (kept.isEmpty() && extra.isEmpty()) out.put("king_switch", "0")
        moveMoreToolsLast(out)
        applyColorIcons(out, null)
        return out
    }

    /** The tool ids of the first [HOME_SLOTS] cells, for one-line logcat evidence. */
    @JvmStatic
    fun slotIds(payload: JSONObject?): String {
        val out = StringBuilder()
        for (i in 0 until HOME_SLOTS) {
            val id = if (payload == null) "" else payload.optString("id" + i)
            if (out.length > 0) out.append(',')
            out.append(if (id.isEmpty()) "-" else id)
        }
        return out.toString()
    }

    /**
     * 取宿主自己下发的一个「空槽」当行距占位模板。
     *
     * <p>实测（AMap 17.02.0.1541）：宿主 `local_tool_list_for_xml_v2` 线上 payload
     * 是 `id0..id10` 共 11 个键，后 3 个是 `id=""` 的空槽。这些空槽撑出每行
     * 的上下留白；一旦把键删掉，`toolbox.a.a` 的 `has("id"+i)` 立刻为假、
     * 循环提前结束，行距消失 → 两行图标贴在一起。
     */
    @Throws(Exception::class)
    private fun emptySlotTemplate(source: JSONObject, count: Int): JSONObject? {
        for (i in 0 until count) {
            if (source.optString("id" + i).length > 0) continue
            val pad = JSONObject()
            for (field in SLOT_FIELDS) if (source.has(field + i)) pad.put(field, source.opt(field + i))
            return if (pad.length() == 0) null else pad
        }
        return null
    }

    /** Move one of the app's own slots, action and icon included. */
    @Throws(Exception::class)
    private fun copySlot(from: JSONObject, to: JSONObject, source: Int, target: Int) {
        for (field in SLOT_FIELDS) {
            val key = field + source
            if (from.has(key)) to.put(field + target, from.opt(key))
        }
        if (to.optString("id" + target).isEmpty()) {
            blankSlot(to, target)
            return
        }
        to.put("display" + target, "flex")
        if (!to.has("size" + target)) to.put("size" + target, "90px")
    }

    /**
     * Build a slot for a tool the app did not place on the toolbar.
     *
     * <p>Every field comes from [AmapToolCatalog], which is extracted from this build's
     * own tool directory -- no id, name, route or icon is invented. The click payload matches
     * exactly what the app's own click handler parses
     * (`com.autonavi...toolbox.v3.f.a`: `Uri.decode` then `optString("schema")`;
     * the optional `ut` block is only used for analytics and its absence is handled).
     */
    /**
     * Fill missing cells from the host's own tool catalogue (toolbox.xml:tool_list_data).
     *
     * <p>The static [AmapToolCatalog] was extracted from 17.00; a tool that exists there
     * but not in the installed build's catalogue gets silently dropped by the renderer. Using
     * the host's own item objects means every id, schema and icon is one the app already knows.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun toolbox(source: JSONObject, s: Settings, ctx: Context?): JSONObject {
        val base = toolbox(source, s, false)
        if (ctx == null) return toolbox(source, s, true)
        val host = java.util.HashMap<String, JSONObject>()
        try {
            val raw = ctx.getSharedPreferences("toolbox", Context.MODE_PRIVATE)
                    .getString("tool_list_data", null)
            if (raw != null) {
                val cats = JSONObject(raw).optJSONArray("category")
                if (cats != null) for (i in 0 until cats.length()) {
                    val cat = cats.optJSONObject(i)
                    val list = cat?.optJSONArray("tools")
                    if (list == null) continue
                    for (j in 0 until list.length()) {
                        val item = list.optJSONObject(j)
                        if (item != null) host.put(item.optString("id"), item)
                    }
                }
            }
        } catch (ignored: Throwable) {}
        val kept = java.util.ArrayList<String>()
        for (i in 0 until 16) {
            if (!source.has("id" + i)) break
            val id = source.optString("id" + i)
            if (id.length > 0 && toolKeep(id, s)) kept.add(id)
        }
        val extra = java.util.ArrayList<JSONObject>()
        val present = HashSet<String>(kept)
        for (entry in AmapToolCatalog.ENTRIES) {
            if (kept.size + extra.size >= HOME_SLOTS) break
            if (present.contains(entry[0]) || !toolFill(entry[0], s)) continue
            val item = host[entry[0]] ?: continue
            mergeDefaultIcon(item, entry[0])
            extra.add(item); present.add(entry[0])
        }
        val out = JSONObject(base.toString())
        val cells = Math.max(HOME_SLOTS, kept.size + extra.size)
        for (i in 0 until cells) for (field in SLOT_FIELDS) out.remove(field + i)
        var slot = 0
        for (i in 0 until 16) {
            if (slot >= kept.size) break
            val id = source.optString("id" + i)
            if (id.length > 0 && toolKeep(id, s)) copySlot(source, out, i, slot++)
        }
        for (item in extra) if (slot < HOME_SLOTS) writeSlotItem(out, item, slot++)
        while (slot < HOME_SLOTS) blankSlot(out, slot++)
        if (kept.isEmpty() && extra.isEmpty()) out.put("king_switch", "0")
        moveMoreToolsLast(out)
        applyColorIcons(out, host)
        return out
    }

    /** 高德原生宫格把「更多工具」当作收尾格：它必须在最后一个有效槽位，否则后面不再渲染。 */
    private fun moveMoreToolsLast(out: JSONObject) {
        try {
            var more = -1
            var last = -1
            for (i in 0 until HOME_SLOTS) {
                val id = out.optString("id" + i)
                if (id.isEmpty()) continue
                last = i
                if (MORE_TOOLS == id) more = i
            }
            if (more < 0 || last < 0 || more == last) return
            for (f in SLOT_FIELDS) {
                val km = f + more
                val kl = f + last
                val hm = out.has(km)
                val hl = out.has(kl)
                val vm = if (hm) out.opt(km) else null
                val vl = if (hl) out.opt(kl) else null
                if (hl) out.put(km, vl) else out.remove(km)
                if (hm) out.put(kl, vm) else out.remove(kl)
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 「工具图标统一彩绘（实验）」：把首页工具栏每个槽位的图标换成高德「更多工具」目录里的
     * 彩绘圆底 PNG（`tool_list_data[*].iconV2`）。
     *
     * <p>原生首页的 `imageUrlRender` 对第二行工具给的是线条 token 图，而
     * `tool_list_data.iconV2` 是同一工具的彩色圆底图（实测饱和度 0.76~0.87）。
     * 这里只改数据：把 URL 换成目录里的彩绘 PNG，并去掉 `imageToken`/`imageClass`，
     * 让原生 `DtImageView` 走 URL 分支。**不自绘、不发明资源**。
     */
    private fun applyColorIcons(out: JSONObject, host: Map<String, JSONObject>?) {
        try {
            if (!Config.toolColorIcon()) return
            for (i in 0 until HOME_SLOTS) {
                val id = out.optString("id" + i)
                if (id.isEmpty()) continue
                val icon = colorIconUrl(id, host)
                if (icon.isEmpty()) continue
                out.put("imageUrlRender" + i, icon)
                out.remove("imageToken" + i)
                out.remove("imageClass" + i)
                out.put("displayImage" + i, "flex")
                out.put("display" + i, "flex")
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 彩绘图标 URL：宿主目录 iconV2 → 静态目录 iconV2 → k84.f() 默认槽位 imageUrlRender。 */
    private fun colorIconUrl(id: String, host: Map<String, JSONObject>?): String {
        try {
            val item = host?.get(id)
            if (item != null) {
                val icon = item.optString("iconV2", item.optString("imageV2", ""))
                if (isHttp(icon)) return icon
            }
            for (entry in AmapToolCatalog.ENTRIES) {
                if (id == entry[0] && entry.size > 4 && isHttp(entry[4])) return entry[4]
            }
            val def = defaultSlot(id)
            if (def != null) {
                val url = def.optString("imageUrlRender")
                if (isHttp(url)) return url
            }
        } catch (ignored: Throwable) {
        }
        return ""
    }

    private fun isHttp(value: String?): Boolean {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"))
    }

    /**
     * 用 `k84.f()` 默认清单里的原生 slot 字段补全目录条目缺的图标字段。
     *
     * <p>宿主的 `tool_list_data` 目录条目只有 icon URL（没有 `imageClass`），
     * 而「步行 / 骑行」这类 token 图标必须有 `imageClass` 才有彩色圆形底 —— 否则
     * 原生渲染出来就是一条细线。默认清单 `k84.f()` 里同一工具带完整原生字段。
     */
    private fun mergeDefaultIcon(item: JSONObject, id: String) {
        try {
            val def = defaultSlot(id) ?: return
            for (f in arrayOf("imageClass", "imageToken", "imageUrlRender", "imageLegacy",
                    "icon", "size", "containerClass", "displayImage", "displayLottie",
                    "isLottie", "moreItem")) {
                if (!item.has(f) && def.has(f)) item.put(f, def.opt(f))
            }
        } catch (ignored: Throwable) {
        }
    }

    // ── 原厂默认工具清单缓存 + 重入闸（2026-11 冷启动修复）────────────────────────────
    //
    // 现场证据（MIUI AppScout HANG self-trace，pid 主线程）：
    //     AmapNativeHome$19.intercept (hook k84.f)          ← 我们
    //       → AmapData.toolboxRaw → toolbox → applyColorIcons
    //         → colorIconUrl → defaultSlot → k84.f() 反射调用 ← 又打回自己的 hook
    //           → AmapNativeHome$19.intercept ...            ← 无限自我递归
    // 每一层都要 new JSONObject(5.5 KB) + copySlot 拼字符串，主线程被吃掉 1.3 s + 4.3 s，
    // SplashActivity.onCreate 实测 6538 ms，系统直接判 APP_SCOUT_HANG。
    //
    // 修法：k84.f() 的返回值是**进程内恒定**的常量清单，取一次就够。
    //   1. rememberDefaultRaw() 由 hook 在**过滤之前**回填原厂串（保证拿到的是未被裁剪的）；
    //   2. defaultSlot() 只读缓存，缓存没有才反射取一次；
    //   3. 取数期间置线程本地闸门，闸门已置位时**绝不**再反射调用 —— 这一条是兜底，
    //      即使将来有人把 hook 改成别的顺序，也不会再出现递归。
    // 不改的后果：用户打开「工具图标统一彩绘」后，每次首页创建都会递归到 StackOverflow
    // 才停，冷启动白白多出 5 秒白屏。

    /**
     * 默认工具清单上游的混淆名候选，顺序 = 优先级。**唯一事实来源** ——
     * `AmapNativeHome` 挂 hook、本类 [defaultPayload] 反射取模板，两处都读这张表。
     *
     * <p>R8 的类编号跨构建整体重排：国内 17.02.0.1541 是 `k84.f()`，
     * Play 17.02.2.2014 是 `s27.d()`。两版都实测过形状 —— static、无参、返回 String、
     * 内容是带 `id0` 的工具 JSON，且被 `quickservice.toolbox.a.a` 与
     * `ToolboxPanelV2/V3.init` 调用。
     *
     * <p><b>必须连方法一起验。</b>`Lk84;` 在 Play 版依然存在，但已经是另一个无关类
     * （只有 `a`/`b`）；只按类名解析会拿到一个永远不 HIT 的 hook 点。
     */
    @JvmField
    val TOOL_DEFAULT_RAW: Array<Array<String>> = arrayOf(arrayOf("k84", "f"), arrayOf("s27", "d"))

    /**
     * 单键 prefs 读取 helper（`c(String key,String def)String`）的混淆名候选。
     * 同样按序命中：国内 `wm3`（8 方法，含 `b()HashMap`），Play 是 `lv3`
     * —— 形状逐条一致，纯重命名。Play 的 `Lwm3;` 已变成 Kotlin lambda，没有 `c`。
     */
    @JvmField
    val PREFS_HELPER: Array<Array<String>> = arrayOf(arrayOf("wm3", "c"), arrayOf("lv3", "c"))

    /** k84.f() 的原厂返回值（未过滤）。volatile：主线程写、其它线程读。 */
    @Volatile
    private var sDefaultRaw: String? = null

    /** 解析后的默认清单。同样只建一次。 */
    @Volatile
    private var sDefaultPayload: JSONObject? = null

    /** 取数闸门：非 null = 当前线程已经在 defaultSlot 的反射取数路径上。 */
    private val IN_DEFAULT = ThreadLocal<Boolean>()

    /** 取数闸门：非 null = 当前线程已经在 toolboxRaw 里。用于挡住任何形式的自我重入。 */
    private val IN_RAW = ThreadLocal<Boolean>()

    /**
     * 由 `k84.f()` 的 hook 在**过滤之前**调用，把原厂串存下来。
     *
     * <p>必须"先存再过滤"：hook 的返回值是过滤后的串，拿它当"默认清单模板"会缺槽位。
     */
    @JvmStatic
    fun rememberDefaultRaw(raw: String?) {
        if (raw == null || raw.isEmpty() || sDefaultRaw != null) return
        sDefaultRaw = raw
    }

    /** 从宿主默认工具清单 `k84.f()` 里取某个 id 的原始 slot 对象（只读，走缓存）。 */
    private fun defaultSlot(id: String): JSONObject? {
        try {
            val d = defaultPayload() ?: return null
            for (i in 0 until 16) {
                if (id != d.optString("id" + i)) continue
                val slot = JSONObject()
                for (f in SLOT_FIELDS) if (d.has(f + i)) slot.put(f, d.opt(f + i))
                return slot
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    /**
     * 默认清单 JSON：缓存优先；没有缓存时反射调用一次 `k84.f()`。
     *
     * <p>闸门已置位（= 本线程正处在 `k84.f()` 的 hook 里）时直接返回 null，
     * 让调用方降级为"没有默认模板"，而不是再递归一层。
     */
    private fun defaultPayload(): JSONObject? {
        val cached = sDefaultPayload
        if (cached != null) return cached
        var raw = sDefaultRaw
        if (raw == null) {
            if (IN_DEFAULT.get() != null) return null
            IN_DEFAULT.set(true)
            try {
                var cl = Thread.currentThread().contextClassLoader
                if (cl == null) cl = AmapData::class.java.classLoader
                // 候选表：国内 k84.f()，Play s27.d()。取第一个「类能加载 且 方法真存在」的。
                // 逐个内层 catch：Play 上 Lk84 存在但没有 f()，那只是候选落空，不是失败。
                for (c in TOOL_DEFAULT_RAW) {
                    try {
                        val value = cl!!.loadClass(c[0]).getMethod(c[1]).invoke(null)
                        if (value is String) {
                            raw = value
                            break
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (ignored: Throwable) {
            } finally {
                IN_DEFAULT.remove()
            }
            // hook 里回填的原厂串优先于上面这次 invoke 的返回值（后者已被过滤）。
            val pristine = sDefaultRaw
            if (pristine != null) raw = pristine
            if (raw == null) return null
            sDefaultRaw = raw
        }
        try {
            val parsed = JSONObject(raw)
            sDefaultPayload = parsed
            return parsed
        } catch (ignored: Throwable) {
            return null
        }
    }

    /** Write one cell from a host catalogue item (all ids/schemas/icons are the app's own). */
    @Throws(Exception::class)
    private fun writeSlotItem(to: JSONObject, item: JSONObject, target: Int) {
        val id = item.optString("id")
        val schema = item.optString("schema")
        // 先整份复制宿主导出的 slot 字段（含 imageClass / imageToken / containerClass 等），
        // 再按需覆盖；只挑几个字段会把「彩色圆形底」这种原生属性丢掉。
        for (field in OFFICIAL_FIELDS) if (item.has(field)) to.put(field + target, item.opt(field))
        to.put("id" + target, id)
        to.put("name" + target, item.optString("name"))
        to.put("schema" + target, schema)
        val itemSize = item.optString("size")
        to.put("size" + target, if (itemSize.endsWith("px")) itemSize else "90px")
        to.put("isLottie" + target, false)
        to.put("displayLottie" + target, "none")
        to.put("displayImage" + target, "flex")
        to.put("lottieUrlRender" + target, "")
        to.put("display" + target, "flex")
        to.put("moreItem" + target, MORE_TOOLS == id)
        // 图标：宿主目录条目的图标键有 imageToken / imageLegacy / icon / iconV2 多种拼写，
        // 只读 iconV2 会让「带 token 但不带 URL」的工具在原生槽位上缺图。
        // **保守约束**：已有 imageToken / imageUrlRender 时绝不改写。
        if (!item.has("imageToken") && !item.has("imageUrlRender")) {
            var icon = item.optString("iconV2", item.optString("imageV2", item.optString("image")))
            if (icon.isEmpty()) icon = AmapToolIcons.imageOf(item)
            if (icon.startsWith("@")) to.put("imageToken" + target, icon.substring(1))
            else to.put("imageUrlRender" + target, icon)
        }
        to.put("itemDataForClick" + target, android.net.Uri.encode(
                JSONObject().put("schema", schema).toString()))
    }

    @Throws(Exception::class)
    private fun writeSlot(to: JSONObject, entry: Array<String>, target: Int) {
        val item = AmapToolCatalog.item(entry)
        val id = item.optString("id")
        val schema = item.optString("schema")
        val icon = item.optString("iconV2")
        to.put("id" + target, id)
        to.put("name" + target, item.optString("name"))
        to.put("schema" + target, schema)
        to.put("size" + target, "90px")
        to.put("containerClass" + target, "")
        to.put("isLottie" + target, false)
        to.put("displayLottie" + target, "none")
        to.put("displayImage" + target, "flex")
        to.put("lottieUrlRender" + target, "")
        to.put("display" + target, "flex")
        to.put("moreItem" + target, MORE_TOOLS == id)
        if (icon.startsWith("@")) to.put("imageToken" + target, icon.substring(1))
        else to.put("imageUrlRender" + target, icon)
        // Uri.encode, not URLEncoder: the app decodes with Uri.decode, which does not turn
        // "+" back into a space, and several tool routes contain one.
        to.put("itemDataForClick" + target, android.net.Uri.encode(
                JSONObject().put("schema", schema).toString()))
    }

    /**
     * A slot the host must not see at all.
     *
     * <p>Measured on 17.02 (classes5, ToolboxPanelV2.init -> toolbox.a.a):
     * `a.a` pre-validates every `size<i>` it can find with
     * `optString("size"+i)` -> `endsWith("px")` -> `parseInt`. A key that
     * exists but is empty (or `"0px"`) makes `parseInt` yield 0, and the whole
     * payload is thrown away and replaced by the built-in `k84.f()` default. Only a
     * <b>missing</b> key (`JSONObject.has` == false) makes the pre-check skip cleanly.
     * So an unused slot is removed, never written as an empty value.
     */
    private fun blankSlot(to: JSONObject, index: Int) {
        for (field in SLOT_FIELDS) to.remove(field + index)
    }

    /**
     * Card names the home recommendation canvas is allowed to emit. Anything that
     * reaches the canvas while its switch is off is dropped here as a second line of
     * defence, so a single toggle can never release the whole feed again.
     *
     * Card names arrive as camelCase or snake_case depending on which emitter produced
     * them; the installed build reads cardName, card_name, bizName, biz_name and name.
     * Normalising here means one switch covers every spelling.
     */
    private fun normalize(name: String?): String {
        if (name == null) return ""
        val out = StringBuilder(name.length)
        for (i in 0 until name.length) {
            val c = name[i]
            if (c == '_' || c == '-' || c == ' ') continue
            out.append(c.lowercaseChar())
        }
        return out.toString()
    }

    /**
     * Resolve a card's identity the same way the installed build does.
     *
     * The app's own helper (gu2.A) walks the tree with five key fallbacks
     * (cardName / card_name / bizName / biz_name / name). The previous version read
     * only component.extra_info.cardName, so any card using another spelling was never
     * recognised and was therefore never filtered.
     */
    @JvmStatic
    fun cardName(source: JSONObject?): String {
        if (source == null) return ""
        val component = source.optJSONObject("component")
        var name = firstNonEmpty(component)
        if (name.isEmpty()) name = firstNonEmpty(source)
        if (name.isEmpty()) {
            var extra = component?.optJSONObject("extra_info")
            if (extra == null && component != null) extra = component.optJSONObject("extraInfo")
            name = firstNonEmpty(extra)
        }
        return name
    }

    private fun firstNonEmpty(o: JSONObject?): String {
        if (o == null) return ""
        for (key in arrayOf("cardName", "card_name", "bizName", "biz_name", "name")) {
            val value = o.optString(key)
            if (value.isNotEmpty() && "null" != value) return value
        }
        return ""
    }

    @JvmStatic
    fun cardVisible(name: String?, s: Settings): Boolean {
        val key = normalize(name)
        if (key.isEmpty()) return true
        if ("tipsranking" == key) return s.enabled(Config.K_FEED_BOARD)
        if ("hourlyweather" == key || "weatherforecast" == key)
            return s.enabled(Config.K_FEED_WEATHER)
        if ("frequentlocations" == key || "frequentlocation" == key)
            return s.enabled(Config.K_HOME_CHIPS)
        if ("contentcenterfeed" == key) return s.enabled(Config.K_FEED_CONTENT)
        // 「去哪里打车」卡（17.02 真机：LLM 流里 cardName=taxiQS，aiqs cardList 里
        // dataType=ShareTripTaxiCard）——统一只认新键 K_TAXI_CARD。
        // 不再看 K_RIDE_CARD_OFF：旧键是遗留开关，若继续参与判断，用户打开新开关后
        // 只要旧键还是默认「开」，打车卡就永远出不来（17.02 真机实测就是这个现象）。
        if ("taxiqs" == key || "taxiqsmarket" == key
                || "sharetriptaxicard" == key) return s.enabled(Config.K_TAXI_CARD)
        return true
    }

    /** Null drops only nonterminal cards; completion envelopes always reach the page. */
    @JvmStatic
    @Throws(Exception::class)
    fun stream(source: JSONObject, s: Settings): JSONObject? {
        if (source.optJSONObject("component") == null) return source
        val name = cardName(source)
        val terminal = source.optBoolean("is_done") || source.optBoolean("isDone")
        // ★ 安全网（本次「炸开」修复的核心之一）：四个内容类开关全关时，推荐画布
        // 只可能是为了「去哪里打车」卡才建的（AmapNativeHome.COMPACT 只认 K_TAXI_CARD）。
        // 此时流里的每一张卡都不该出现 —— 不依赖 cardName 是否认识，一律丢弃。
        // 这样即使 17.02 改了卡片名（cardVisible 认不出），也不可能出现"打车 ON ⇒
        // 天气/榜单/内容流一起炸开"。
        val anyFeed = s.enabled(Config.K_FEED_WEATHER) || s.enabled(Config.K_FEED_BOARD)
                || s.enabled(Config.K_FEED_CONTENT) || s.enabled(Config.K_FEED_FESTIVAL)
        if (!anyFeed && !terminal) return null
        val hide = !cardVisible(name, s)
        if (hide && !terminal) return null
        val stopPaging = terminal && !s.enabled(Config.K_FEED_BOARD) && !s.enabled(Config.K_FEED_CONTENT)
        if (!hide && !stopPaging) return source
        val out = JSONObject(source.toString())
        if (hide) {
            val empty = JSONObject()
            empty.put("type", "markdown"); empty.put("text", ""); empty.put("is_done", true)
            out.put("component", empty)
        }
        if (stopPaging) {
            // Writers disagree on the spelling; set both so whichever the reader uses is honoured.
            out.put("has_more", false)
            out.put("hasMore", false)
        }
        return out
    }

    @JvmStatic
    fun requestPath(url: String?): String {
        if (url == null) return ""
        val begin = url.indexOf("/ws/")
        if (begin < 0) return ""
        val end = url.indexOf('?', begin)
        return if (end < 0) url.substring(begin) else url.substring(begin, end)
    }
    @JvmStatic
    fun handles(path: String?): Boolean {
        return "/ws/promote/main-page/assets" == path
            || "/ws/shield/dsp/profile/index/nodefaasv3" == path
    }

    @JvmStatic
    @Throws(Exception::class)
    fun response(path: String?, raw: String?, s: Settings): String? {
        if (raw == null || raw.isEmpty()) return raw
        val out = JSONObject(raw)
        if ("/ws/promote/main-page/assets" == path) {
            if (s.enabled(Config.K_FEED_FESTIVAL) || !out.has("data")) return raw
            val d = out.opt("data")
            if (d is JSONArray) out.put("data", JSONArray())
            else if (d is JSONObject) out.put("data", JSONObject())
            else return raw
            return out.toString()
        }
        if ("/ws/shield/dsp/profile/index/nodefaasv3" != path) return raw
        val data = out.optJSONObject("data")
        val cards = data?.optJSONArray("cardList") ?: return raw
        val kept = JSONArray()
        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i)
            if (card == null) { kept.put(cards.opt(i)); continue }
            val type = card.optString("dataType")
            if (("MineRowSlideCard" == type || "UserCircleCardV3" == type)
                    && !s.enabled(Config.K_MY_FRIENDS)) continue
            if ("MineMemberRecommendTaskCardV2" == type && !s.enabled(Config.K_MY_TASK)) continue
            if (("PopularActivitiesCardV4" == type || "PopularActivitiesCardV5" == type)
                    && !s.enabled(Config.K_MY_PROMO_ROW)) continue
            if ("ContentCenterFeedCard" == type && !s.enabled(Config.K_MY_GUESS)) continue
            // ── 15.21 真值（2026-10-26 探针）：dataType 与 17.02 不同 ──
            if (IS_1521) {
                if ("MineNewBEntranceCard" == type) {
                    val content = card.optJSONObject("content")
                    if (content != null) {
                        if (!s.enabled(Config.K_MY_ORDER_ROW)) {
                            content.put("entranceList_1", JSONArray())
                            content.put("entranceList", JSONArray())
                            H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_ORDER_ROW)
                        }
                        if (!s.enabled(Config.K_MY_SERVICE_ROW)) {
                            content.put("entranceList_2", JSONArray())
                            H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_SERVICE_ROW)
                        }
                    }
                }
                if ("MineNewDoubleRowCard" == type && !s.enabled(Config.K_MY_SERVICE_ROW)) {
                    H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_SERVICE_ROW)
                    continue
                }
                if ("MineGoodsDisplayCard" == type && !s.enabled(Config.K_MY_PROMO_ROW)) {
                    H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_PROMO_ROW)
                    continue
                }
                if (("PopularActivitiesCard" == type || "MineScrollerWithIndicatorTemplate" == type)
                        && !s.enabled(Config.K_MY_TASK)) {
                    H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_TASK)
                    continue
                }
            }
            if ("MineNewBEntranceCardV3" == type || "MineNewBEntranceCardV4" == type) {
                val content = card.optJSONObject("content")
                if (content != null) {
                    if (!s.enabled(Config.K_MY_ORDER_ROW)) content.put("entranceList_1", JSONArray())
                    if (!s.enabled(Config.K_MY_SERVICE_ROW)) content.put("entranceList_2", JSONArray())
                    val one = content.optJSONArray("entranceList_1")
                    val two = content.optJSONArray("entranceList_2")
                    if (one != null && two != null && one.length() == 0 && two.length() == 0) continue
                }
            }
            kept.put(card)
        }
        data.put("cardList", kept)
        return out.toString()
    }
}
