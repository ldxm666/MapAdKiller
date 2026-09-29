package io.github.ldxm666.mapadkiller;

import android.content.SharedPreferences;

/**
 * 配置定义（键名模块 App 与 Hook 侧共用）+ Hook 侧读取。
 * 存储走 LSPosed RemotePreferences（存于 LSPosed 数据库，App 侧经
 * libxposed/service 写入，Hook 侧经 XposedModule#getRemotePreferences 读取，
 * 同名 group 双侧共享，写入即生效）。
 * 读取失败一律回退默认值（全部显示 / 去广告始终开启），保证失效安全。
 * 注意：不要引用 de.robv.*（API 102 模块进程内不存在 legacy API）。
 */
public final class Config {

    public static final String PKG = "io.github.ldxm666.mapadkiller";
    public static final String PREF_GROUP = "amap_enhancer_config";

    // ---- 键名 ----
    public static final String K_TAB_PREFIX = "tab_";
    public static final String K_TOOLS_VISIBLE = "ui_tools_visible";
    public static final String K_TOOL_PREFIX = "tool_";
    public static final String K_FEED_WEATHER = "feed_weather";
    public static final String K_FEED_SCENIC = "feed_scenic";
    public static final String K_FEED_RANK = "feed_rank";
    public static final String K_FEED_POSTS = "feed_posts";
    public static final String K_FEED_DISTANCE = "feed_distance";
    public static final String K_FEED_CONTENT = "feed_content";
    public static final String K_FEED_AI = "feed_ai";
    public static final String K_FEED_FILTER = "feed_filter";
    public static final String K_HOME_CHIPS = "home_chips";
    /** 搜索栏下方那一排圆形快捷入口（美食 / 酒店 / 景点门票 / 加油充电 / 出行节 / 扫街榜）总开关 */
    public static final String K_HOME_QUICK_ROW = "home_quick_row";
    public static final String K_DEBUG_LOG = "debug_log";
    /** 工具宫格自动排序（隐藏后补位重排）；默认开，重排异常时可关 */
    public static final String K_TOOL_SORT = "tool_sort";
    /** 榜单/特色推荐卡（尝尝这里的特色招牌菜 / 特色场所轻松选…）：
     *  我的页 / 首页下划 / 路线中下滑 / 目的地下滑 全部存在，统一开关，默认隐藏 */
    public static final String K_FEED_BOARD = "feed_board";

    /** 出行节/会场活动卡（库迪咖啡 4选1 等优惠券卡）：统一开关，默认隐藏（v1.1.0 用户请愿） */
    public static final String K_FEED_FESTIVAL = "feed_festival";

    /**
     * 搜索框内页（搜索输入页 / 联想页 / 搜索结果页 / 搜索运营落地页）去广告。
     * 默认**开** —— 这一层的动作全部限定在搜索页，
     * 且命中的都是券包 / 红包 / 会场这类运营卡，属于纯净收益。
     */
    public static final String K_SEARCH_AD = "search_ad";

    /**
     * 首页搜索框「热词轮播」关闭开关（v1.1.0）。
     *
     * 现象（用户截图为证）：首页那支搜索框里的文字会**自己流动**，
     * 内容是「灌县古城 / 十一出游…」这类预置词，其中混着运营/广告词。
     * DEX 实证（jadx：com.autonavi.bundle.amaphome.components.searchbar.BaseSearchBar）：
     * 所有写进 mHotWordTxtView(id=txt_hotword) 的路径都收敛到
     * setHotWordTxt(...) / setPreWordTextView(...) 两个私有方法，
     * 再往下才是 PreSetWordAnimManager 的轮播动画。
     *
     * 语义：**true = 关闭热词轮播**（把文字钉死成系统默认提示词
     * 「查找地点、公交、地铁」，同时摘掉热词品牌图标）。缺省 = true（关）。
     */
    public static final String K_HOTWORD_OFF = "hotword_off";

    /**
     * 首页「去XXX 打车」打车浮窗关闭开关（v1.1.0）。
     *
     * 现象：首页信息流底部总会冒出一张「去幸福路步行街 / 有座不拥挤 · 行程有保障 / 打车」
     * 的打车运营浮窗卡（截图实证）。真机 DiagPage 树实证它是 AJX 卡片：
     *   Html'去幸福路步行街' > Container(613x123) > Container(1006x210)×4
     *     > Container(1006x242) > AjxAbsoluteLayout(1006x242)  ← 列表 item 根 = 整张浮窗
     *   Html'有座不拥挤' / Html'行程有保障' 同挂在这张卡里。
     * 卡内文案由服务端下发（dex/资源里都没有这两串字），所以按**卡片结构 + 卡内文案**
     * 精准定位，不用任何全局形状规则。
     *
     * 语义：**true = 摘除打车浮窗**。缺省 = true（关）。
     */
    public static final String K_RIDE_CARD_OFF = "ride_card_off";
    // v1.1.0：K_TOOL_EXTRA 已删除 —— 宫格 15 格全量逐格开关（见 TOOLS/TOOLS_DEFAULT_OFF）

    // v1.1.0：「扩展页」键删除 —— 第 3 行 5 格全部进 TOOLS 逐格开关

    /**
     * 各键的默认可见性。
     *
     * 默认**关闭**的：
     *  · 宫格第 3 行运营格（旅游度假 / 车主服务 / 景点游玩 / 离线地图 / 高德出行节）
     *  · 搜索栏下方快捷入口整排 —— 用户明确说不需要它
     *  · 工具宫格自动排序 —— 实验性：AJX 数据模型会反扑（闪动/幽灵触摸），
     *    默认关保证稳定；想试的用户可手动开
     * 其余一律默认显示（失效安全）。
     */
    /** 第 3 行运营/扩展格：默认隐藏（v1.1.0，逐格独立，用户可开） */
    public static final java.util.Set<String> TOOLS_DEFAULT_OFF = new java.util.HashSet<>(java.util.Arrays.asList(
            "旅游度假", "车主服务", "景点游玩", "离线地图", "高德出行节"));

    public static boolean defaultVisible(String key) {
        // 宫格 15 格逐格默认值；K_TOOL_SORT 默认开（幽灵格已剔出排布集合）
        if (key != null && key.startsWith(K_TOOL_PREFIX)) {
            return !TOOLS_DEFAULT_OFF.contains(key.substring(K_TOOL_PREFIX.length()));
        }
        // 注：K_SEARCH_AD（搜索框内页去广告）刻意不进关闭名单 —— 默认开
        return !K_HOME_QUICK_ROW.equals(key) && !K_FEED_BOARD.equals(key)
                && !K_FEED_FESTIVAL.equals(key);
    }

    // ---- 「我的」页 ----
    public static final String K_MY_ORDER_ROW = "my_order_row";
    public static final String K_MY_SERVICE_ROW = "my_service_row";
    public static final String K_MY_TASK = "my_task";
    public static final String K_MY_PROMO_ROW = "my_promo_row";
    public static final String K_MY_GUESS = "my_guess";
    public static final String K_MY_QUALITY = "my_quality";

    /** 底部标签栏 tab 清单（键名 = "tab_" + 标签文本） */
    public static final String[] TABS = {
            "首页", "探索", "长按说话", "打车", "我的",
    };

    /**
     * 首页工具宫格**全量 15 格**清单（v1.1.0）。
     * 截图实证的完整宫格：3 行 × 5 列。此前 TOOLS 只有前 10 格，
     * 第 3 行（旅游度假 / 车主服务 / 景点游玩 / 离线地图 / 更多工具）
     * 被当成"扩展页"用单一开关整排管 —— 现在逐格独立开关，杜绝漏放。
     * 轮播文案别名（火车票机票 / 高德扫街榜）在 HomeTweaks.TOOL_ALIAS 归一。
     */
    public static final String[] TOOLS = {
            "驾车", "公交地铁", "租车", "打车", "订酒店",
            "火车票", "顺风车", "高德扫街", "高德出行节", "代驾",
            "旅游度假", "车主服务", "景点游玩", "离线地图", "更多工具",
    };

    private static volatile SharedPreferences cached;

    /** Hook 侧读取入口：框架提供的 RemotePreferences（同名 group 与 App 侧共享） */
    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p == null) {
            synchronized (Config.class) {
                p = cached;
                if (p == null) {
                    p = H.module.getRemotePreferences(PREF_GROUP);
                    cached = p;
                }
            }
        }
        return p;
    }

    /** 默认 true = 显示；读取失败回退默认，保证失效安全 */
    public static boolean visible(String key) {
        boolean def = defaultVisible(key);
        try { return prefs().getBoolean(key, def); } catch (Throwable t) { return def; }
    }

    public static boolean toolVisible(String label) {
        return visible(K_TOOL_PREFIX + label);
    }

    public static boolean tabVisible(String label) {
        return visible(K_TAB_PREFIX + label);
    }

    /** 临时取证开关：真机测树期间强制开日志。交付版必须为 false（设置页有独立开关）。 */
    public static final boolean FORCE_DEBUG = false;

    private static volatile long dbgAt;
    private static volatile boolean dbgVal;

    /** 热点路径（每个 AJX 文本都会问一次）：2 秒 TTL 缓存，避免每次 setText 都走一次 IPC。 */
    public static boolean debugLog() {
        if (FORCE_DEBUG) return true;
        long now = System.currentTimeMillis();
        if (now - dbgAt > 2000) {
            try { dbgVal = prefs().getBoolean(K_DEBUG_LOG, false); }
            catch (Throwable t) { dbgVal = false; }
            dbgAt = now;
        }
        return dbgVal;
    }

    // 百度瘦身（BaiduTweaks）已移除；仅保留三家地图的去广告基线（BmapHooks/TmapHooks）。
    private Config() {}
}
