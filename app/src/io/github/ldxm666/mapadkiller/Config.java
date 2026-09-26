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

    /**
     * 工具宫格「扩展页」：首页工具宫格往下还有一排没被列进常用工具里的格子
     * （景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 旅游度假）。
     * 它们不是用户选的工具，默认隐藏；想留就在设置页打开。
     */
    public static final String K_TOOL_EXTRA = "tool_extra_page";

    /**
     * 各键的默认可见性。
     *
     * 默认**关闭**的：
     *  · 扩展工具页（景点游玩 / 离线地图 / …）—— 额外推荐位
     *  · 搜索栏下方快捷入口整排 —— 用户明确说不需要它
     *  · 工具宫格自动排序 —— 实验性：AJX 数据模型会反扑（闪动/幽灵触摸），
     *    默认关保证稳定；想试的用户可手动开
     * 其余一律默认显示（失效安全）。
     */
    public static boolean defaultVisible(String key) {
        return !K_TOOL_EXTRA.equals(key) && !K_HOME_QUICK_ROW.equals(key)
                && !K_TOOL_SORT.equals(key) && !K_FEED_BOARD.equals(key);
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

    /** 首页工具清单（键名 = "tool_" + 标签文本，与首页文本锚点一致） */
    public static final String[] TOOLS = {
            "驾车", "公交地铁", "租车", "打车", "订酒店",
            "火车票", "顺风车", "高德扫街", "代驾", "更多工具",
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

    private Config() {}
}
