package io.github.ldxm666.bmapclean;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;

/**
 * 百度地图首页控件锚点表。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 主键 = 资源 id **名字**，不是数值
 * ══════════════════════════════════════════════════════════════════════════
 * 运行时用 `Resources#getIdentifier(name, "id", "com.baidu.BaiduMap")` 从目标
 * App 自己的资源表解析 id —— 这样即使百度在某次更新里重排了 0x7f01xxxx 数值，
 * 只要资源名还在（百度自己的布局 id 名字一直很稳定，且不像类名那样被混淆），
 * 规则依旧命中。
 *
 * 数值（0x7f0xxxxx）是 **v22.0.0 (versionCode 1650) 真机 dumpsys activity top
 * 实测值**，只在名字解析失败时兜底 —— 两个都失败才记 miss，并且 miss 只影响
 * 这一条规则，不影响其它规则与 App 功能。
 *
 * 证据来源：真机 2509FPN0BC / com.baidu.BaiduMap 22.0.0 首页
 * `adb shell dumpsys activity top` 的 View Hierarchy 段（含 V/G 可见性、bounds、
 * 资源 id 名），切片见 targets/com.baidu.BaiduMap/versions/v22.0.0/。
 */
public final class Anchors {

    public static final String PKG = "com.baidu.BaiduMap";

    // ── 底部五入口栏 ───────────────────────────────────────────────────
    public static final String ID_UFO_ROOT   = "ufo_root";        public static final int NUM_UFO_ROOT   = 0x7f025458;
    public static final String ID_NEW_BTNS   = "new_btns";        public static final int NUM_NEW_BTNS   = 0x7f0232e9;
    public static final String ID_NEW_ROUTE  = "new_route";       public static final int NUM_NEW_ROUTE  = 0x7f023309;
    public static final String ID_NEW_NEARBY = "new_nearby";      public static final int NUM_NEW_NEARBY = 0x7f023304;
    public static final String ID_NEW_THIRD  = "new_third";       public static final int NUM_NEW_THIRD  = 0x7f02330f;
    public static final String ID_NEW_FOURTH = "new_fourth";      public static final int NUM_NEW_FOURTH = 0x7f0232fe;
    public static final String ID_NEW_USER   = "new_user";        public static final int NUM_NEW_USER   = 0x7f023316;
    /**
     * 中央「长按说话」在 ufo_root 里还有一份**兄弟层**覆盖控件
     * （FrameLayout 422,0-658,223，压在 new_btns 之上）。
     * 证据：new_third 的 icon/text 都是 GONE，真正可见/可点的是
     * home_ai_container + 它的子节点 new_home_ai_touch_view。
     * 只藏 new_third 会留下这层可点的空壳 —— 两者必须同进同退。
     */
    public static final String ID_HOME_AI_CONTAINER = "home_ai_container"; public static final int NUM_HOME_AI_CONTAINER = 0x7f021e88;
    public static final String ID_NEW_HOME_AI_TOUCH = "new_home_ai_touch_view"; public static final int NUM_NEW_HOME_AI_TOUCH = 0x7f023301;

    // 点击代理（视觉隐藏后同步摘除，避免"看不见但点得到"）
    public static final String ID_PXY_ROUTE  = "aihome_route_proxy";  public static final int NUM_PXY_ROUTE  = 0x7f020262;
    public static final String ID_PXY_NEARBY = "aihome_nearby_proxy"; public static final int NUM_PXY_NEARBY = 0x7f02025c;
    public static final String ID_PXY_THIRD  = "aihome_third_proxy";  public static final int NUM_PXY_THIRD  = 0x7f020264;
    public static final String ID_PXY_FOURTH = "aihome_fourth_proxy"; public static final int NUM_PXY_FOURTH = 0x7f02025a;
    public static final String ID_PXY_USER   = "aihome_user_proxy";   public static final int NUM_PXY_USER   = 0x7f020265;

    // ── 首页工具宫格 ───────────────────────────────────────────────────
    public static final String ID_TOOL_WRAP = "component_container3"; public static final int NUM_TOOL_WRAP = 0x7f021360;
    public static final String ID_ROW1      = "row1_container";       public static final int NUM_ROW1      = 0x7f023fde;
    public static final String ID_ROW2      = "row2_container";       public static final int NUM_ROW2      = 0x7f023fdf;
    public static final String ID_ROW3      = "row3_container";       public static final int NUM_ROW3      = 0x7f023fe0;

    // ── 回家 / 去公司 ──────────────────────────────────────────────────
    public static final String ID_HC_WRAP     = "component_container5"; public static final int NUM_HC_WRAP     = 0x7f021361;
    public static final String ID_HC_LOCAL    = "view_local";           public static final int NUM_HC_LOCAL    = 0x7f0256f1;
    public static final String ID_HC_HOME     = "home_info";            public static final int NUM_HC_HOME     = 0x7f021ea8;
    public static final String ID_HC_COMPANY  = "company_info";         public static final int NUM_HC_COMPANY  = 0x7f02133d;
    public static final String ID_HC_SETTING  = "setting_container";    public static final int NUM_HC_SETTING  = 0x7f0243ce;
    public static final String ID_HC_DIVIDER  = "vertical_line";        public static final int NUM_HC_DIVIDER  = 0x7f02568e;

    // ── 天气 ───────────────────────────────────────────────────────────
    /** 地图右侧那条「21°C 27 限行」原生天气条 */
    public static final String ID_WX_MAP = "weather_limited"; public static final int NUM_WX_MAP = 0x7f025add;
    /** 底部信息流容器（天气/行程卡由 Baidu Talos 引擎渲染，落在它的瀑布流第一项里） */
    public static final String ID_FEED   = "component_container6"; public static final int NUM_FEED = 0x7f021362;

    // ── 首页容器（用于判定"这一页是不是百度首页"） ─────────────────────
    public static final String ID_TOP_CONTAINER   = "top_container";        public static final int NUM_TOP_CONTAINER   = 0x7f024b60;
    public static final String ID_HOME_PANEL      = "home_panel_container"; public static final int NUM_HOME_PANEL      = 0x7f021eb0;
    public static final String ID_COMPONENT_LIST  = "component_list_new";   public static final int NUM_COMPONENT_LIST  = 0x7f021365;
    /** 「我的」页容器（Talos 渲染，判定这一页是不是我的页） */
    public static final String ID_USER_CENTER     = "user_center_talos_container"; public static final int NUM_USER_CENTER = 0;

    /** 首页搜索框标题（热词轮播就写在这上面）—— 资源名 + v22.0.0 实测数值 */
    public static final String ID_SEARCHBAR_TITLE = "tv_searchbar_title"; public static final int NUM_SEARCHBAR_TITLE = 0x7f0252b5;
    /** 搜索框文本切换器（ViewSwitcher，热词与默认词在此切换）与其另一个子容器 */
    public static final String ID_SEARCHBOX_SWITCHER = "tv_searchbox_home_text_switcher"; public static final int NUM_SEARCHBOX_SWITCHER = 0;
    public static final String ID_SEARCHBOX_CONTAINER = "searchbox_home_text_container"; public static final int NUM_SEARCHBOX_CONTAINER = 0;

    // ── 诊断计数 ───────────────────────────────────────────────────────
    public static volatile int nameHits;
    public static volatile int numHits;
    public static volatile int notFound;
    public static volatile String lastMissing = "";

    private Anchors() {}

    /**
     * 从 root 里按「id 名字 → 数值兜底」找控件。
     *
     * @return 找到的 View，找不到返回 null（**不抛异常**，调用方按 null 处理）
     */
    public static View find(View root, String name, int num) {
        if (root == null) return null;
        int id = 0;
        try {
            Resources r = root.getResources();
            if (r != null) {
                id = r.getIdentifier(name, "id", PKG);
                if (id == 0) id = r.getIdentifier(name, "id", null); // 全包搜索（兼容 id 归到库包）
            }
        } catch (Throwable ignored) {}

        if (id != 0) {
            try {
                View v = root.findViewById(id);
                if (v != null) { nameHits++; return v; }
            } catch (Throwable ignored) {}
        }
        if (num != 0) {
            try {
                View v = root.findViewById(num);
                if (v != null) { numHits++; return v; }
            } catch (Throwable ignored) {}
        }
        notFound++;
        lastMissing = name;
        if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "anchor miss: " + name);
        return null;
    }

    public static String stats() {
        return "name=" + nameHits + " num=" + numHits + " miss=" + notFound
                + (lastMissing.length() == 0 ? "" : " last=" + lastMissing);
    }
}
