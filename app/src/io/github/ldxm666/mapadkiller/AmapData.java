package io.github.ldxm666.mapadkiller;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Version 17 protocol adapter: fixed paths and stable identifiers, never display text. */
public final class AmapData {
    public interface Settings {
        boolean enabled(String key);
        default boolean selected(String key) { return enabled(key); }
    }
    public static final Settings CURRENT = new Settings() {
        @Override public boolean enabled(String key) {
            // 热键走 volatile 读（零 map 查、零时间比较）；其余键保持原样。
            Config.Flag hot = Config.hotFlag(key);
            return hot != null ? hot.on() : Config.visible(key);
        }
        @Override public boolean selected(String key) {
            Config.Flag hot = Config.hotFlag(key);
            return hot != null ? hot.on() : Config.selected(key);
        }
    };
    public static final String[][] TOOLS = {
        {"102", "驾车"}, {"103", "公交地铁"}, {"168", "租车"}, {"106", "打车"},
        {"317", "订酒店"}, {"327", "火车票"}, {"469", "顺风车"}, {"483", "高德扫街"},
        {"461", "高德出行节"}, {"362", "代驾"}, {"418", "秒送"}, {"151", "实时公交"},
        {"380", "旅游度假"}, {"116", "车主服务"}, {"113", "景点游玩"}, {"115", "离线地图"},
        {"104", "步行"}, {"105", "骑行"}, {"86", "更多工具"}
    };

    /**
     * How many tool cells the home toolbar renders.
     *
     * <p>Measured from the installed build: {@code com.autonavi...toolbox.b.a} walks
     * {@code id0…id4} (its loop bound is 5) and lays the kept cells out in one row. Writing
     * more slots than this is harmless but invisible, so the payload is written for exactly
     * this many cells.
     *
     * <p><b>必须是 10（2 行 × 5 列），不能是 11。</b>宿主 {@code ToolboxPanelV2} 的宫格容器
     * 固定只排两行；写第 11 个槽位会被挤成第三行，直接压到下面的「回家 / 去单位」行上
     * —— 这就是用户报的「工具宫格有点重叠」。第 11 个及以后的工具仍然可以在「更多工具」里找到。
     */
    public static final int HOME_SLOTS = 10;

    /**
     * 「运营推广位」黑名单 —— 只影响这几个 id，其它 {@code tool_id_*} 目录工具一律不动。
     *
     * <p>313 = 优惠加油：云端 {@code tool_list_data} 的服务分类（cid9）里常驻（29 项中第 16 项，
     * 带红色「领油券」角标），用户在「更多工具」里能随时再选回首页。它的 {@code tool_id_313}
     * 在 default_config.json 里是 {@code null}，面板按默认值画成「关」，而
     * {@link #toolKeep} 以前把「未设置」当透传放行 ⇒ UI 说关、代码放行，这就是「优惠加油
     * 很顽固」的根因。
     */
    private static final java.util.Set<String> PROMO_TOOLS =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(
                    java.util.Arrays.asList("313")));

    /** 15.21 专用分支开关（由 AmapMine1521.install 置位；17.02 永远 false）。 */
    public static volatile boolean IS_1521 = false;

    /** The app's fixed 「更多工具」 entry; it lives in the slot list like any other tool. */
    public static final String MORE_TOOLS = "86";

    /**
     * 官方 {@code local_tool_list_for_xml_v2} 的真实字段集 —— 从设备 toolbox.xml 逐字段核出来的，
     * 一个不多一个不少（15 个）。**没有 weight**：宿主的工具行固定切 5 列，一旦槽位被带上
     * weight，LinearLayout 会按权重重新分配宽度，多个 cell 就挤在一起、视觉上重叠。
     * 所以写槽位时只允许写这 15 个字段。
     */
    private static final String[] OFFICIAL_FIELDS = {
        "id", "schema", "name", "size", "containerClass", "isLottie", "displayLottie",
        "displayImage", "imageUrlRender", "lottieUrlRender", "imageClass", "itemDataForClick",
        "display", "imageToken", "moreItem"
    };
    private static final String[] SLOT_FIELDS = {
        "id", "schema", "name", "size", "containerClass", "isLottie", "displayLottie",
        "displayImage", "imageUrlRender", "lottieUrlRender", "imageClass", "itemDataForClick",
        "display", "imageToken", "moreItem", "label", "tips", "redDot", "badge",
        "imageLegacy", "icon", "weight", "cid", "red_dot_key"
    };
    private AmapData() {}

    public static boolean toolVisible(String id, Settings s) {
        boolean visible = toolVisibleRaw(id, s);
        reportTool(id, visible);
        return visible;
    }

    /**
     * 搜索页金刚位（美食 / 酒店 / 景点门票 / 加油充电 / 超划算 / 扫街榜 那一排）的 tool id。
     *
     * <p>真机实测（2026-10-25 23:57）：搜索页那排 **就是 toolbox payload 被 AJX 页渲染**，
     * 走的正是本类的 {@code toolVisible}；把 {@code ui_tools_visible} 关掉后那排整块消失
     * （截图 y370~554 高饱和像素 0.000 实证）。所以「整排开关」在这里收口最干净：
     * 由 {@link Config#searchGrid()} 控制，**默认 true = 完全维持原有行为**。
     *
     * <p>按 id 判定，不按文案（文案是服务端下发的，随时会变）。
     * 新出现的运营工具如果漏网，看日志 {@code event=amap_tool_visible} 补 id 即可。
     */
    private static final java.util.Set<String> SEARCH_GRID_IDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "323",   // 美食
                    "490",   // 加油充电
                    "501",   // 超市
                    "544",   // 超划算
                    "483",   // 高德扫街榜
                    "484")); // 特价酒店

    /**
     * 搜索页时间窗：`AmapSearchGrid` 在搜索页 `load` 时打一个时间戳，
     * 窗口内构建的 toolbox payload 会跳过 {@link #SEARCH_GRID_IDS}。
     *
     * <p>为什么用时间窗而不是 ThreadLocal：payload 构建可能在别的线程/更晚的时机发生，
     * 线程标记会漏；时间窗只覆盖「刚打开搜索页的那几秒」，首页/更多工具页都不在那个窗口内
     * ⇒ 默认行为（未开窗）逐字节不变。
     */
    private static volatile long searchPageUntil;

    /** 由 AmapSearchGrid 在搜索页加载时调用。 */
    public static void markSearchPage(long millis) {
        searchPageUntil = android.os.SystemClock.uptimeMillis() + millis;
    }

    private static boolean inSearchPageWindow() {
        return android.os.SystemClock.uptimeMillis() < searchPageUntil;
    }

    /** 搜索页金刚位：开关关掉 + 处于搜索页时间窗 ⇒ 从 payload 里跳过。 */
    // ★ 已证伪（勿再启用）：曾假设「搜索页那排 = 首页 toolbox payload」，据此按 id 剔除。
    //   5 轮实机否证：即使去掉时间窗，sat 仍恒为 0.341；且同一构建上关掉 ui_tools_visible
    //   也丝毫不影响那排 ⇒ 它并不走这份 payload。留着恒 false，保持原有行为逐字节不变，
    //   同时避免误伤「更多工具」页（那 6 个 id 确实在它的候选列表里）。
    private static boolean skipSearchGrid(String id) {
        return false;   // 原实现：id != null && SEARCH_GRID_IDS.contains(id) && !Config.searchGrid()
    }

    private static boolean toolVisibleRaw(String id, Settings s) {
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) return false;
        boolean allowlist = s.enabled(Config.K_TOOLS_ALLOWLIST);
        String key = AmapToolCatalog.key(id);
        return key != null && (allowlist ? s.selected(key) : s.enabled(key));
    }

    /**
     * 「宿主自有列表」的保留判定 —— 用于 {@code local_tool_list_for_xml_v2} 存储读取与
     * {@code toolbox.a.a(...)} 的 source payload。
     *
     * <p>宿主列表里的槽位是**用户自己**在高德「更多工具」里选的，模块只能剔除「显式关掉 /
     * 推广」的工具，不能因为模块面板里 {@code tool_id_<id>} 默认 false 就把它们砍掉。
     * 真机事故：用户新加一个工具后，读回 {@code local_tool_list_for_xml_v2} 时被过滤 ⇒
     * 首页宫格永远只有那 5 个（「我添加工具但是不会增加」的根因）。
     *
     * <p>规则：{@code K_TOOLS_VISIBLE} 关 ⇒ false；未知 id ⇒ true；{@code tool_id_*} ⇒
     * true（用户在高德里选的）；其余 legacy 工具走 {@code s.enabled(key)}（保留
     * {@link Config#TOOLS_DEFAULT_OFF} 那 7 条推广工具的默认剔除）。
     */
    public static boolean toolKeep(String id, Settings s) {
        if (id == null || id.length() == 0) return false;
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) {
            reportKeep(id, null, false, "switch_off");
            return false;
        }
        String key = AmapToolCatalog.key(id);
        if (key == null) {
            reportKeep(id, null, true, "unknown");
            return true;
        }
        if (key.startsWith("tool_id_")) {
            // ★ 方案 C（透传优先）：宿主 payload 里给的工具有什么就显示什么，
            //   只有用户在模块面板里**显式关掉**的才剔除。
            //   之前用 s.selected(key) 要求「显式打开才保留」，导致配置里没有记录的工具
            //   （包括宿主自己给的）全被剔掉，首页只剩「更多工具」一个格子。
            Boolean explicit = Config.explicit(key);
            // ★ 未设置时按「面板显示的默认值」处理：推广位（313 优惠加油）默认关，
            //   其余目录工具保持透传，避免历史事故「加了工具但首页不增加」。
            boolean kept = explicit != null ? explicit.booleanValue() : !PROMO_TOOLS.contains(id);
            reportKeep(id, key, kept,
                    kept ? (explicit == null ? "pass_through" : "user_selected")
                         : (explicit == null ? "promo_default_off" : "switch_off"));
            return kept;
        }
        boolean kept = s.enabled(key);
        String label = key.substring(Config.K_TOOL_PREFIX.length());
        reportKeep(id, key, kept, kept ? "user_selected"
                : (Config.TOOLS_DEFAULT_OFF.contains(label) ? "promo" : "switch_off"));
        return kept;
    }

    /**
     * 「模块补位」判定 —— 只用于 {@code fill}：只有用户在模块面板里**显式打开**的工具
     * 才会被补进空位，避免 {@code tool_id_*} 那 ~95 条目录工具全部塞进首页。
     */
    public static boolean toolFill(String id, Settings s) {
        if (id == null || id.length() == 0) return false;
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) return false;
        String key = AmapToolCatalog.key(id);
        if (key == null) return false;
        boolean filled;
        if (key.startsWith("tool_id_")) filled = s.selected(key);
        else filled = s.enabled(key);
        reportFill(id, key, filled, s.selected(key));
        return filled;
    }

    /** toolFill 诊断：每个 id 只报一次，封顶 60 条。 */
    private static final java.util.Set<String> FILL_REPORTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void reportFill(String id, String key, boolean filled, boolean selected) {
        try {
            if (FILL_REPORTED.size() >= TOOL_REPORT_MAX) return;
            if (!FILL_REPORTED.add(id)) return;
            H.logv("event=amap_tool_fill id=" + id + " key=" + key + " filled=" + filled
                    + " selected=" + selected);
        } catch (Throwable ignored) {
        }
    }

    /** toolKeep 诊断：每个 id 只报一次，封顶 60 条。 */
    private static final java.util.Set<String> KEEP_REPORTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void reportKeep(String id, String key, boolean kept, String why) {
        try {
            if (KEEP_REPORTED.size() >= TOOL_REPORT_MAX) return;
            if (!KEEP_REPORTED.add(id)) return;
            H.logv("event=amap_tool_keep id=" + id + " key=" + key + " kept=" + kept + " why=" + why);
        } catch (Throwable ignored) {
        }
    }

    // ── 节流诊断（每个 id 只打一次，总数封顶）────────────────────────────────────
    // 用途：定位「工具栏隐藏未生效」时，先在真机确认某个 id 到底有没有走这里。
    // 判读：
    //   · 日志里**没有** id=317/116  ⇒ 这条 UI 不经过 toolVisible（走的是别的构建器）；
    //   · 有且 visible=false         ⇒ 过滤算对了，是构建器在过滤后把格子又加回来了；
    //   · 有且 visible=true          ⇒ 配置/默认值判定与预期不符。
    // 封顶 60 条 + 每个 id 一次，首页反复重建也不会刷屏。
    private static final java.util.Set<String> TOOL_REPORTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final int TOOL_REPORT_MAX = 60;

    private static void reportTool(String id, boolean visible) {
        try {
            if (id == null || id.length() == 0) return;
            if (TOOL_REPORTED.size() >= TOOL_REPORT_MAX) return;
            if (!TOOL_REPORTED.add(id)) return;
            H.log("event=amap_tool_visible id=" + id
                    + " label=" + toolLabel(id)
                    + " key=" + AmapToolCatalog.key(id)
                    + " visible=" + visible);
        } catch (Throwable ignored) {
        }
    }

    /** id → 中文标题；查不到就回 "?"（不猜、不抛）。 */
    private static String toolLabel(String id) {
        try {
            for (String[] entry : AmapToolCatalog.ENTRIES) {
                if (id.equals(entry[0])) return entry[1];
            }
            for (String[] entry : TOOLS) {
                if (id.equals(entry[0])) return entry[1];
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * 把一段「工具面板 payload 字符串」过滤后返回（字符串进出，供 hook {@code k84.f()} 使用）。
     *
     * <h3>为什么需要这个入口</h3>
     * 17.02 实测（classes.dex / classes5.dex）：{@code k84.f()Ljava/lang/String;} 被 **5 处**调用：
     * <pre>
     *   quickservice/toolbox/a.a(...)        ← QS 面板工厂**内部兜底**（入参没有 id0 时）
     *   ToolboxPanelV2.init(...)             ← 兜底
     *   ToolboxPanelV3.init(...)             ← 兜底
     *   BootBizDataPreloaderImpl.getToolboxData()  ← 它自己也是用 k84.f() 建缓存字段 g
     *   BootBizDataPreloaderImpl.prepare(...)
     * </pre>
     * 也就是说：只要走到任何一条兜底，payload 就**直接来自 {@code k84.f()}**，
     * 完全不经过 {@code getToolboxData} / {@code AjxModuleLocalStorage} 上的 hook ⇒
     * 这正是「过滤逻辑没错、工具却照样显示」的根因。所以必须在**最上游**也拦一道。
     *
     * <p>参数 {@code fill} 恒为 false：{@code k84.f()} 是 App 自己的默认清单，
     * 往里"发明槽位"会污染它的默认数据。
     */
    public static String toolboxRaw(String raw, Settings s) {
        if (raw == null || raw.length() == 0 || raw.length() > 4 * 1024 * 1024) return raw;
        // 重入闸：本线程已经在这条路径上 ⇒ 说明是自我递归（hook 里又调到被 hook 的方法）。
        // 这时**原样放行**，绝不往下走 —— 见 defaultPayload() 上方那段现场栈。
        if (IN_RAW.get() != null) return raw;
        IN_RAW.set(Boolean.TRUE);
        try {
            JSONObject source = new JSONObject(raw);
            JSONObject out = toolbox(source, s, false);
            return out == source ? raw : out.toString();
        } catch (Throwable t) {
            return raw;   // 解析不了就原样放行，绝不因为诊断/过滤把面板搞崩
        } finally {
            IN_RAW.remove();
        }
    }

    public static boolean toolSelected(String id, Settings s) {
        String key = AmapToolCatalog.key(id);
        return key != null && s.selected(key);
    }

    public static String storage(String key, String raw, Settings s) throws Exception {
        if (raw == null || raw.length() == 0) return raw;
        if ("local_tool_list_for_xml_v2".equals(key)) return toolbox(new JSONObject(raw), s).toString();
        if ("tool_list_data".equals(key)) return catalog(raw, s);
        if (!"recommendTools".equals(key) && !"aosRecommendTools".equals(key)
                && !"choiceTools".equals(key)) return raw;
        JSONObject out = new JSONObject(raw);
        String listKey = "choiceTools".equals(key) ? "choiceTools" : "recommendTools";
        JSONArray source = out.optJSONArray(listKey);
        if (source == null) return raw;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject tool = source.optJSONObject(i);
            if (tool == null || toolVisible(tool.optString("id"), s)) kept.put(source.opt(i));
        }
        if (kept.length() == source.length()) return raw;
        out.put(listKey, kept);
        return out.toString();
    }

    /**
     * 过滤云端「更多工具」目录 {@code tool_list_data}（120 KB 分类 JSON）。
     *
     * <p>这是 313 的**第二个口子**：页面从 {@code AjxModuleLocalStorage#getItemSync("toolbox",
     * "tool_list_data")} 整包读走，再渲染 6 个分类；{@code storage()} 以前对这个 key 走 default
     * 分支原样返回，于是 313 永远在候选列表里，随时能被再选回首页。
     *
     * <p>只剔 {@link #PROMO_TOOLS}，且用户显式打开 {@code tool_id_313} 时仍然保留；其它分类、
     * 其它字段一个字节都不动（无改动直接返回原串）。
     */
    public static String catalog(String raw, Settings s) {
        if (raw == null || raw.length() == 0 || raw.length() > 4 * 1024 * 1024) return raw;
        if (raw.equals(CATALOG_IN)) return CATALOG_OUT;
        try {
            JSONObject src = new JSONObject(raw);
            JSONArray cats = src.optJSONArray("category");
            if (cats == null) return raw;
            int removed = 0;
            for (int i = 0; i < cats.length(); i++) {
                JSONObject cat = cats.optJSONObject(i);
                JSONArray tools = cat == null ? null : cat.optJSONArray("tools");
                if (tools == null || tools.length() == 0) continue;
                JSONArray kept = new JSONArray();
                for (int j = 0; j < tools.length(); j++) {
                    JSONObject tool = tools.optJSONObject(j);
                    String id = tool == null ? null : tool.optString("id");
                    if (id != null && PROMO_TOOLS.contains(id)
                            && !s.selected(AmapToolCatalog.key(id))) {
                        removed++;
                        continue;
                    }
                    kept.put(tools.opt(j));
                }
                if (kept.length() != tools.length()) cat.put("tools", kept);
            }
            if (removed == 0) return raw;
            String out = src.toString();
            CATALOG_IN = raw;
            CATALOG_OUT = out;
            H.log("event=amap_catalog_filter removed=" + removed);
            return out;
        } catch (Throwable t) {
            return raw;   // 解析失败原样放行，绝不把「更多工具」页搞崩
        }
    }

    /** {@link #catalog} 的单槽缓存（云端刷新才会换串，命中率接近 100%）。 */
    private static volatile String CATALOG_IN;
    private static volatile String CATALOG_OUT;

    /** Filter-only form: keep the app's own slots, drop the ones switched off. */
    public static JSONObject toolbox(JSONObject source, Settings s) throws Exception {
        return toolbox(source, s, false);
    }

    /**
     * Rewrite the home toolbar's slot payload.
     *
     * <h3>Why this is more than a filter</h3>
     * The app does not build the toolbar from a list of enabled tools: it renders whatever
     * slots its own {@code local_tool_list_for_xml_v2} happens to contain, and it rewrites
     * that list itself from the 「更多工具」 page. Filtering alone therefore only ever
     * <i>removes</i> cells -- switching 步行 or 骑行 on did nothing, because those ids were
     * never in the list. On this build the list held exactly two entries
     * ({@code 103 公交地铁} and {@code 86 更多工具}), so 「打开了步行骑行但 UI 没有」 was
     * structural, not a rendering glitch.
     *
     * <p>The payload is now reconciled instead: the app's own order is preserved for the
     * tools it placed, and enabled catalogue tools that are missing fill the remaining cells.
     *
     * @param fill only the boot payload is filled. {@code local_tool_list_for_xml_v2} is the
     *             app's own store and is read back by the 「更多工具」 page, so invented slots
     *             are never written into it.
     */
    public static JSONObject toolbox(JSONObject source, Settings s, boolean fill) throws Exception {
        return toolbox(source, s, fill, HOME_SLOTS);
    }

    /**
     * Same as {@link #toolbox(JSONObject, Settings, boolean)} with an explicit slot cap.
     *
     * <p>{@code maxSlots <= 0} means "keep the host's own slot count" -- required by 15.21,
     * whose default payload carries 15 tools and whose panel pages through them; truncating
     * to {@link #HOME_SLOTS} would silently drop tools the user enabled.
     */
    public static JSONObject toolbox(JSONObject source, Settings s, boolean fill, int maxSlots) throws Exception {
        List<Integer> kept = new ArrayList<Integer>();
        int scanLimit = maxSlots <= 0 ? 32 : Math.max(10, maxSlots);
        int count = 0;
        for (; count < scanLimit && source.has("id" + count); count++) {
            String id = source.optString("id" + count);
            if (id.length() > 0 && toolKeep(id, s) && !skipSearchGrid(id)) kept.add(count);
        }

        List<String[]> extra = new ArrayList<String[]>();
        if (fill) {
            java.util.Set<String> present = new java.util.HashSet<String>();
            for (int index : kept) present.add(source.optString("id" + index));
            for (String[] entry : AmapToolCatalog.ENTRIES) {
                if (maxSlots > 0 && kept.size() + extra.size() >= maxSlots) break;
                if (present.contains(entry[0]) || !toolFill(entry[0], s) || skipSearchGrid(entry[0])) continue;
                extra.add(entry);
                present.add(entry[0]);
            }
        }
        if (count == kept.size() && extra.isEmpty() && !Config.toolColorIcon()) return source;

        JSONObject out = new JSONObject(source.toString());
        int cells = fill ? (maxSlots <= 0 ? Math.max(count, kept.size() + extra.size())
                : Math.max(count, maxSlots)) : Math.max(count, kept.size() + extra.size());
        // 宿主自己的「空槽」是行距占位，**不能删键**。
        // toolbox.a.a 的循环条件是 has("id"+i)：键一消失就提前结束，后面本该由空槽
        // 撑出来的行距全部丢失，两行工具就被压在一起（用户看到的“重叠”）。
        // 正确做法：拿宿主本来的空槽当模板，原样回填到剩余槽位。
        JSONObject pad = emptySlotTemplate(source, count);
        for (int i = 0; i < cells; i++) {
            for (String field : SLOT_FIELDS) out.remove(field + i);
            if (i < kept.size()) copySlot(source, out, kept.get(i), i);
            else if (i - kept.size() < extra.size()) writeSlot(out, extra.get(i - kept.size()), i);
            else if (pad != null) {
                for (String field : SLOT_FIELDS) if (pad.has(field)) out.put(field + i, pad.opt(field));
            } else blankSlot(out, i);
        }
        if (kept.isEmpty() && extra.isEmpty()) out.put("king_switch", "0");
        moveMoreToolsLast(out);
        applyColorIcons(out, null);
        return out;
    }

    /** The tool ids of the first {@link #HOME_SLOTS} cells, for one-line logcat evidence. */
    public static String slotIds(JSONObject payload) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < HOME_SLOTS; i++) {
            String id = payload == null ? "" : payload.optString("id" + i);
            if (out.length() > 0) out.append(',');
            out.append(id.length() == 0 ? "-" : id);
        }
        return out.toString();
    }

    /**
     * 取宿主自己下发的一个「空槽」当行距占位模板。
     *
     * <p>实测（AMap 17.02.0.1541）：宿主 {@code local_tool_list_for_xml_v2} 线上 payload
     * 是 {@code id0..id10} 共 11 个键，后 3 个是 {@code id=""} 的空槽。这些空槽撑出每行
     * 的上下留白；一旦把键删掉，{@code toolbox.a.a} 的 {@code has("id"+i)} 立刻为假、
     * 循环提前结束，行距消失 → 两行图标贴在一起。
     */
    private static JSONObject emptySlotTemplate(JSONObject source, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            if (source.optString("id" + i).length() > 0) continue;
            JSONObject pad = new JSONObject();
            for (String field : SLOT_FIELDS) if (source.has(field + i)) pad.put(field, source.opt(field + i));
            return pad.length() == 0 ? null : pad;
        }
        return null;
    }

    /** Move one of the app's own slots, action and icon included. */
    private static void copySlot(JSONObject from, JSONObject to, int source, int target) throws Exception {
        for (String field : SLOT_FIELDS) {
            String key = field + source;
            if (from.has(key)) to.put(field + target, from.opt(key));
        }
        if (to.optString("id" + target).length() == 0) {
            blankSlot(to, target);
            return;
        }
        to.put("display" + target, "flex");
        if (!to.has("size" + target)) to.put("size" + target, "90px");
    }

    /**
     * Build a slot for a tool the app did not place on the toolbar.
     *
     * <p>Every field comes from {@link AmapToolCatalog}, which is extracted from this build's
     * own tool directory -- no id, name, route or icon is invented. The click payload matches
     * exactly what the app's own click handler parses
     * ({@code com.autonavi...toolbox.v3.f.a}: {@code Uri.decode} then {@code optString("schema")};
     * the optional {@code ut} block is only used for analytics and its absence is handled).
     */
    /**
     * Fill missing cells from the host's own tool catalogue (toolbox.xml:tool_list_data).
     *
     * <p>The static {@link AmapToolCatalog} was extracted from 17.00; a tool that exists there
     * but not in the installed build's catalogue gets silently dropped by the renderer. Using
     * the host's own item objects means every id, schema and icon is one the app already knows.
     */
    public static JSONObject toolbox(JSONObject source, Settings s, android.content.Context ctx) throws Exception {
        JSONObject base = toolbox(source, s, false);
        if (ctx == null) return toolbox(source, s, true);
        java.util.Map<String, JSONObject> host = new java.util.HashMap<String, JSONObject>();
        try {
            String raw = ctx.getSharedPreferences("toolbox", android.content.Context.MODE_PRIVATE)
                    .getString("tool_list_data", null);
            if (raw != null) {
                JSONArray cats = new JSONObject(raw).optJSONArray("category");
                if (cats != null) for (int i = 0; i < cats.length(); i++) {
                    JSONObject cat = cats.optJSONObject(i);
                    JSONArray list = cat == null ? null : cat.optJSONArray("tools");
                    if (list == null) continue;
                    for (int j = 0; j < list.length(); j++) {
                        JSONObject item = list.optJSONObject(j);
                        if (item != null) host.put(item.optString("id"), item);
                    }
                }
            }
        } catch (Throwable ignored) {}
        java.util.List<String> kept = new java.util.ArrayList<String>();
        for (int i = 0; i < 16 && source.has("id" + i); i++) {
            String id = source.optString("id" + i);
            if (id.length() > 0 && toolKeep(id, s)) kept.add(id);
        }
        java.util.List<JSONObject> extra = new java.util.ArrayList<JSONObject>();
        java.util.Set<String> present = new java.util.HashSet<String>(kept);
        for (String[] entry : AmapToolCatalog.ENTRIES) {
            if (kept.size() + extra.size() >= HOME_SLOTS) break;
            if (present.contains(entry[0]) || !toolFill(entry[0], s)) continue;
            JSONObject item = host.get(entry[0]);
            if (item == null) continue;
            mergeDefaultIcon(item, entry[0]);
            extra.add(item); present.add(entry[0]);
        }
        JSONObject out = new JSONObject(base.toString());
        int cells = Math.max(HOME_SLOTS, kept.size() + extra.size());
        for (int i = 0; i < cells; i++) for (String field : SLOT_FIELDS) out.remove(field + i);
        int slot = 0;
        for (int i = 0; i < 16 && slot < kept.size(); i++) {
            String id = source.optString("id" + i);
            if (id.length() > 0 && toolKeep(id, s)) copySlot(source, out, i, slot++);
        }
        for (JSONObject item : extra) if (slot < HOME_SLOTS) writeSlotItem(out, item, slot++);
        for (; slot < HOME_SLOTS; slot++) blankSlot(out, slot);
        if (kept.isEmpty() && extra.isEmpty()) out.put("king_switch", "0");
        moveMoreToolsLast(out);
        applyColorIcons(out, host);
        return out;
    }

    /** 高德原生宫格把「更多工具」当作收尾格：它必须在最后一个有效槽位，否则后面不再渲染。 */
    private static void moveMoreToolsLast(JSONObject out) {
        try {
            int more = -1, last = -1;
            for (int i = 0; i < HOME_SLOTS; i++) {
                String id = out.optString("id" + i);
                if (id.length() == 0) continue;
                last = i;
                if (MORE_TOOLS.equals(id)) more = i;
            }
            if (more < 0 || last < 0 || more == last) return;
            for (String f : SLOT_FIELDS) {
                String km = f + more, kl = f + last;
                boolean hm = out.has(km), hl = out.has(kl);
                Object vm = hm ? out.opt(km) : null, vl = hl ? out.opt(kl) : null;
                if (hl) out.put(km, vl); else out.remove(km);
                if (hm) out.put(kl, vm); else out.remove(kl);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 「工具图标统一彩绘（实验）」：把首页工具栏每个槽位的图标换成高德「更多工具」目录里的
     * 彩绘圆底 PNG（{@code tool_list_data[*].iconV2}）。
     *
     * <p>原生首页的 {@code imageUrlRender} 对第二行工具给的是线条 token 图，而
     * {@code tool_list_data.iconV2} 是同一工具的彩色圆底图（实测饱和度 0.76~0.87）。
     * 这里只改数据：把 URL 换成目录里的彩绘 PNG，并去掉 {@code imageToken}/{@code imageClass}，
     * 让原生 {@code DtImageView} 走 URL 分支。**不自绘、不发明资源**。
     */
    private static void applyColorIcons(JSONObject out, java.util.Map<String, JSONObject> host) {
        try {
            if (!Config.toolColorIcon()) return;
            for (int i = 0; i < HOME_SLOTS; i++) {
                String id = out.optString("id" + i);
                if (id.length() == 0) continue;
                String icon = colorIconUrl(id, host);
                if (icon.length() == 0) continue;
                out.put("imageUrlRender" + i, icon);
                out.remove("imageToken" + i);
                out.remove("imageClass" + i);
                out.put("displayImage" + i, "flex");
                out.put("display" + i, "flex");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 彩绘图标 URL：宿主目录 iconV2 → 静态目录 iconV2 → k84.f() 默认槽位 imageUrlRender。 */
    private static String colorIconUrl(String id, java.util.Map<String, JSONObject> host) {
        try {
            JSONObject item = host == null ? null : host.get(id);
            if (item != null) {
                String icon = item.optString("iconV2", item.optString("imageV2", ""));
                if (isHttp(icon)) return icon;
            }
            for (String[] entry : AmapToolCatalog.ENTRIES) {
                if (id.equals(entry[0]) && entry.length > 4 && isHttp(entry[4])) return entry[4];
            }
            JSONObject def = defaultSlot(id);
            if (def != null) {
                String url = def.optString("imageUrlRender");
                if (isHttp(url)) return url;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isHttp(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    /**
     * 用 {@code k84.f()} 默认清单里的原生 slot 字段补全目录条目缺的图标字段。
     *
     * <p>宿主的 {@code tool_list_data} 目录条目只有 icon URL（没有 {@code imageClass}），
     * 而「步行 / 骑行」这类 token 图标必须有 {@code imageClass} 才有彩色圆形底 —— 否则
     * 原生渲染出来就是一条细线。默认清单 {@code k84.f()} 里同一工具带完整原生字段。
     */
    private static void mergeDefaultIcon(JSONObject item, String id) {
        try {
            JSONObject def = defaultSlot(id);
            if (def == null) return;
            for (String f : new String[]{"imageClass", "imageToken", "imageUrlRender", "imageLegacy",
                    "icon", "size", "containerClass", "displayImage", "displayLottie",
                    "isLottie", "moreItem"}) {
                if (!item.has(f) && def.has(f)) item.put(f, def.opt(f));
            }
        } catch (Throwable ignored) {
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

    /** k84.f() 的原厂返回值（未过滤）。volatile：主线程写、其它线程读。 */
    private static volatile String sDefaultRaw;

    /** 解析后的默认清单。同样只建一次。 */
    private static volatile JSONObject sDefaultPayload;

    /** 取数闸门：非 null = 当前线程已经在 defaultSlot 的反射取数路径上。 */
    private static final ThreadLocal<Boolean> IN_DEFAULT = new ThreadLocal<Boolean>();

    /** 取数闸门：非 null = 当前线程已经在 toolboxRaw 里。用于挡住任何形式的自我重入。 */
    private static final ThreadLocal<Boolean> IN_RAW = new ThreadLocal<Boolean>();

    /**
     * 由 {@code k84.f()} 的 hook 在**过滤之前**调用，把原厂串存下来。
     *
     * <p>必须"先存再过滤"：hook 的返回值是过滤后的串，拿它当"默认清单模板"会缺槽位。
     */
    public static void rememberDefaultRaw(String raw) {
        if (raw == null || raw.length() == 0 || sDefaultRaw != null) return;
        sDefaultRaw = raw;
    }

    /** 从宿主默认工具清单 {@code k84.f()} 里取某个 id 的原始 slot 对象（只读，走缓存）。 */
    private static JSONObject defaultSlot(String id) {
        try {
            JSONObject d = defaultPayload();
            if (d == null) return null;
            for (int i = 0; i < 16; i++) {
                if (!id.equals(d.optString("id" + i))) continue;
                JSONObject slot = new JSONObject();
                for (String f : SLOT_FIELDS) if (d.has(f + i)) slot.put(f, d.opt(f + i));
                return slot;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 默认清单 JSON：缓存优先；没有缓存时反射调用一次 {@code k84.f()}。
     *
     * <p>闸门已置位（= 本线程正处在 {@code k84.f()} 的 hook 里）时直接返回 null，
     * 让调用方降级为"没有默认模板"，而不是再递归一层。
     */
    private static JSONObject defaultPayload() {
        JSONObject cached = sDefaultPayload;
        if (cached != null) return cached;
        String raw = sDefaultRaw;
        if (raw == null) {
            if (IN_DEFAULT.get() != null) return null;
            IN_DEFAULT.set(Boolean.TRUE);
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = AmapData.class.getClassLoader();
                Class<?> k = cl.loadClass("k84");
                Object value = k.getMethod("f").invoke(null);
                if (value instanceof String) raw = (String) value;
            } catch (Throwable ignored) {
            } finally {
                IN_DEFAULT.remove();
            }
            // hook 里回填的原厂串优先于上面这次 invoke 的返回值（后者已被过滤）。
            String pristine = sDefaultRaw;
            if (pristine != null) raw = pristine;
            if (raw == null) return null;
            sDefaultRaw = raw;
        }
        try {
            JSONObject parsed = new JSONObject(raw);
            sDefaultPayload = parsed;
            return parsed;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Write one cell from a host catalogue item (all ids/schemas/icons are the app's own). */
    private static void writeSlotItem(JSONObject to, JSONObject item, int target) throws Exception {
        String id = item.optString("id");
        String schema = item.optString("schema");
        // 先整份复制宿主导出的 slot 字段（含 imageClass / imageToken / containerClass 等），
        // 再按需覆盖；只挑几个字段会把「彩色圆形底」这种原生属性丢掉。
        for (String field : OFFICIAL_FIELDS) if (item.has(field)) to.put(field + target, item.opt(field));
        to.put("id" + target, id);
        to.put("name" + target, item.optString("name"));
        to.put("schema" + target, schema);
        String itemSize = item.optString("size");
        to.put("size" + target, itemSize.endsWith("px") ? itemSize : "90px");
        to.put("isLottie" + target, false);
        to.put("displayLottie" + target, "none");
        to.put("displayImage" + target, "flex");
        to.put("lottieUrlRender" + target, "");
        to.put("display" + target, "flex");
        to.put("moreItem" + target, MORE_TOOLS.equals(id));
        // 图标：宿主目录条目的图标键有 imageToken / imageLegacy / icon / iconV2 多种拼写，
        // 只读 iconV2 会让「带 token 但不带 URL」的工具在原生槽位上缺图。
        // **保守约束**：已有 imageToken / imageUrlRender 时绝不改写。
        if (!item.has("imageToken") && !item.has("imageUrlRender")) {
            String icon = item.optString("iconV2", item.optString("imageV2", item.optString("image")));
            if (icon.length() == 0) icon = AmapToolIcons.imageOf(item);
            if (icon.startsWith("@")) to.put("imageToken" + target, icon.substring(1));
            else to.put("imageUrlRender" + target, icon);
        }
        to.put("itemDataForClick" + target, android.net.Uri.encode(
                new JSONObject().put("schema", schema).toString()));
    }

    private static void writeSlot(JSONObject to, String[] entry, int target) throws Exception {
        JSONObject item = AmapToolCatalog.item(entry);
        String id = item.optString("id");
        String schema = item.optString("schema");
        String icon = item.optString("iconV2");
        to.put("id" + target, id);
        to.put("name" + target, item.optString("name"));
        to.put("schema" + target, schema);
        to.put("size" + target, "90px");
        to.put("containerClass" + target, "");
        to.put("isLottie" + target, false);
        to.put("displayLottie" + target, "none");
        to.put("displayImage" + target, "flex");
        to.put("lottieUrlRender" + target, "");
        to.put("display" + target, "flex");
        to.put("moreItem" + target, MORE_TOOLS.equals(id));
        if (icon.startsWith("@")) to.put("imageToken" + target, icon.substring(1));
        else to.put("imageUrlRender" + target, icon);
        // Uri.encode, not URLEncoder: the app decodes with Uri.decode, which does not turn
        // "+" back into a space, and several tool routes contain one.
        to.put("itemDataForClick" + target, android.net.Uri.encode(
                new JSONObject().put("schema", schema).toString()));
    }

    /**
     * A slot the host must not see at all.
     *
     * <p>Measured on 17.02 (classes5, ToolboxPanelV2.init -> toolbox.a.a):
     * {@code a.a} pre-validates every {@code size<i>} it can find with
     * {@code optString("size"+i)} -> {@code endsWith("px")} -> {@code parseInt}. A key that
     * exists but is empty (or {@code "0px"}) makes {@code parseInt} yield 0, and the whole
     * payload is thrown away and replaced by the built-in {@code k84.f()} default. Only a
     * <b>missing</b> key ({@code JSONObject.has} == false) makes the pre-check skip cleanly.
     * So an unused slot is removed, never written as an empty value.
     */
    private static void blankSlot(JSONObject to, int index) {
        for (String field : SLOT_FIELDS) to.remove(field + index);
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
    private static String normalize(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == ' ') continue;
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * Resolve a card's identity the same way the installed build does.
     *
     * The app's own helper (gu2.A) walks the tree with five key fallbacks
     * (cardName / card_name / bizName / biz_name / name). The previous version read
     * only component.extra_info.cardName, so any card using another spelling was never
     * recognised and was therefore never filtered.
     */
    public static String cardName(JSONObject source) {
        if (source == null) return "";
        JSONObject component = source.optJSONObject("component");
        String name = firstNonEmpty(component);
        if (name.length() == 0) name = firstNonEmpty(source);
        if (name.length() == 0) {
            JSONObject extra = component == null ? null : component.optJSONObject("extra_info");
            if (extra == null && component != null) extra = component.optJSONObject("extraInfo");
            name = firstNonEmpty(extra);
        }
        return name;
    }

    private static String firstNonEmpty(JSONObject o) {
        if (o == null) return "";
        for (String key : new String[]{"cardName", "card_name", "bizName", "biz_name", "name"}) {
            String value = o.optString(key);
            if (value.length() > 0 && !"null".equals(value)) return value;
        }
        return "";
    }

    public static boolean cardVisible(String name, Settings s) {
        String key = normalize(name);
        if (key.length() == 0) return true;
        if ("tipsranking".equals(key)) return s.enabled(Config.K_FEED_BOARD);
        if ("hourlyweather".equals(key) || "weatherforecast".equals(key))
            return s.enabled(Config.K_FEED_WEATHER);
        if ("frequentlocations".equals(key) || "frequentlocation".equals(key))
            return s.enabled(Config.K_HOME_CHIPS);
        if ("contentcenterfeed".equals(key)) return s.enabled(Config.K_FEED_CONTENT);
        // 「去哪里打车」卡（17.02 真机：LLM 流里 cardName=taxiQS，aiqs cardList 里
        // dataType=ShareTripTaxiCard）——统一只认新键 K_TAXI_CARD。
        // 不再看 K_RIDE_CARD_OFF：旧键是遗留开关，若继续参与判断，用户打开新开关后
        // 只要旧键还是默认「开」，打车卡就永远出不来（17.02 真机实测就是这个现象）。
        if ("taxiqs".equals(key) || "taxiqsmarket".equals(key)
                || "sharetriptaxicard".equals(key)) return s.enabled(Config.K_TAXI_CARD);
        return true;
    }

    /** Null drops only nonterminal cards; completion envelopes always reach the page. */
    public static JSONObject stream(JSONObject source, Settings s) throws Exception {
        JSONObject component = source.optJSONObject("component");
        if (component == null) return source;
        String name = cardName(source);
        boolean terminal = source.optBoolean("is_done") || source.optBoolean("isDone");
        // ★ 安全网（本次「炸开」修复的核心之一）：四个内容类开关全关时，推荐画布
        // 只可能是为了「去哪里打车」卡才建的（AmapNativeHome.COMPACT 只认 K_TAXI_CARD）。
        // 此时流里的每一张卡都不该出现 —— 不依赖 cardName 是否认识，一律丢弃。
        // 这样即使 17.02 改了卡片名（cardVisible 认不出），也不可能出现"打车 ON ⇒
        // 天气/榜单/内容流一起炸开"。
        boolean anyFeed = s.enabled(Config.K_FEED_WEATHER) || s.enabled(Config.K_FEED_BOARD)
                || s.enabled(Config.K_FEED_CONTENT) || s.enabled(Config.K_FEED_FESTIVAL);
        if (!anyFeed && !terminal) return null;
        boolean hide = !cardVisible(name, s);
        if (hide && !terminal) return null;
        boolean stopPaging = terminal && !s.enabled(Config.K_FEED_BOARD) && !s.enabled(Config.K_FEED_CONTENT);
        if (!hide && !stopPaging) return source;
        JSONObject out = new JSONObject(source.toString());
        if (hide) {
            JSONObject empty = new JSONObject();
            empty.put("type", "markdown"); empty.put("text", ""); empty.put("is_done", true);
            out.put("component", empty);
        }
        if (stopPaging) {
            // Writers disagree on the spelling; set both so whichever the reader uses is honoured.
            out.put("has_more", false);
            out.put("hasMore", false);
        }
        return out;
    }

    public static String requestPath(String url) {
        if (url == null) return "";
        int begin = url.indexOf("/ws/");
        if (begin < 0) return "";
        int end = url.indexOf('?', begin);
        return end < 0 ? url.substring(begin) : url.substring(begin, end);
    }
    public static boolean handles(String path) {
        return "/ws/promote/main-page/assets".equals(path)
            || "/ws/shield/dsp/profile/index/nodefaasv3".equals(path);
    }

    public static String response(String path, String raw, Settings s) throws Exception {
        if (raw == null || raw.length() == 0) return raw;
        JSONObject out = new JSONObject(raw);
        if ("/ws/promote/main-page/assets".equals(path)) {
            if (s.enabled(Config.K_FEED_FESTIVAL) || !out.has("data")) return raw;
            Object data = out.opt("data");
            if (data instanceof JSONArray) out.put("data", new JSONArray());
            else if (data instanceof JSONObject) out.put("data", new JSONObject());
            else return raw;
            return out.toString();
        }
        if (!"/ws/shield/dsp/profile/index/nodefaasv3".equals(path)) return raw;
        JSONObject data = out.optJSONObject("data");
        JSONArray cards = data == null ? null : data.optJSONArray("cardList");
        if (cards == null) return raw;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) { kept.put(cards.opt(i)); continue; }
            String type = card.optString("dataType");
            if (("MineRowSlideCard".equals(type) || "UserCircleCardV3".equals(type))
                    && !s.enabled(Config.K_MY_FRIENDS)) continue;
            if ("MineMemberRecommendTaskCardV2".equals(type) && !s.enabled(Config.K_MY_TASK)) continue;
            if (("PopularActivitiesCardV4".equals(type) || "PopularActivitiesCardV5".equals(type))
                    && !s.enabled(Config.K_MY_PROMO_ROW)) continue;
            if ("ContentCenterFeedCard".equals(type) && !s.enabled(Config.K_MY_GUESS)) continue;
            // ── 15.21 真值（2026-10-26 探针）：dataType 与 17.02 不同 ──
            if (IS_1521) {
                if ("MineNewBEntranceCard".equals(type)) {
                    JSONObject content = card.optJSONObject("content");
                    if (content != null) {
                        if (!s.enabled(Config.K_MY_ORDER_ROW)) {
                            content.put("entranceList_1", new JSONArray());
                            content.put("entranceList", new JSONArray());
                            H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_ORDER_ROW);
                        }
                        if (!s.enabled(Config.K_MY_SERVICE_ROW)) {
                            content.put("entranceList_2", new JSONArray());
                            H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_SERVICE_ROW);
                        }
                    }
                }
                if ("MineNewDoubleRowCard".equals(type) && !s.enabled(Config.K_MY_SERVICE_ROW)) {
                    H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_SERVICE_ROW);
                    continue;
                }
                if ("MineGoodsDisplayCard".equals(type) && !s.enabled(Config.K_MY_PROMO_ROW)) {
                    H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_PROMO_ROW);
                    continue;
                }
                if (("PopularActivitiesCard".equals(type) || "MineScrollerWithIndicatorTemplate".equals(type))
                        && !s.enabled(Config.K_MY_TASK)) {
                    H.log("event=mine1521_strip dataType=" + type + " key=" + Config.K_MY_TASK);
                    continue;
                }
            }
            if ("MineNewBEntranceCardV3".equals(type) || "MineNewBEntranceCardV4".equals(type)) {
                JSONObject content = card.optJSONObject("content");
                if (content != null) {
                    if (!s.enabled(Config.K_MY_ORDER_ROW)) content.put("entranceList_1", new JSONArray());
                    if (!s.enabled(Config.K_MY_SERVICE_ROW)) content.put("entranceList_2", new JSONArray());
                    JSONArray one = content.optJSONArray("entranceList_1");
                    JSONArray two = content.optJSONArray("entranceList_2");
                    if (one != null && two != null && one.length() == 0 && two.length() == 0) continue;
                }
            }
            kept.put(card);
        }
        data.put("cardList", kept);
        return out.toString();
    }
}
