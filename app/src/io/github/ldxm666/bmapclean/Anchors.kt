package io.github.ldxm666.bmapclean

import android.content.res.Resources
import android.util.Log
import android.view.View

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
 * 数值（0x7f0xxxxx）是 **v22.1.0 (versionCode 1660) 的 resources.arsc 实测值**
 * （aapt2 dump resources 逐条核对；22.0.0 的旧值全部失效，见下表），只在名字
 * 解析失败时兜底 —— 两个都失败才记 miss，并且 miss 只影响这一条规则，不影响
 * 其它规则与 App 功能。
 *
 * ⚠ **22.1.0 把 30 个锚点的数值全改了，而且没有统一偏移**（六种 delta：
 * +0x1c/+0x1d、+0x22、+0x2b、+0x7a/+0x7b/+0x7c、−0x84/−0x86）。
 * 旧数值在 22.1.0 上会落到完全无关的控件上 —— 例如 row1_container 的旧值
 * 0x7f023fde 现在是 route_search_end_arrow，new_route 的旧值 0x7f023309 现在是
 * navi_switch。所以**不能靠算偏移升级，必须逐条重新量**。
 * 名字本身一个都没变（30/30 全部命中），主解析路径不受影响。
 *
 * 证据来源：真机 2509FPN0BC / com.baidu.BaiduMap 22.0.0 首页
 * `adb shell dumpsys activity top` 的 View Hierarchy 段（含 V/G 可见性、bounds、
 * 资源 id 名），切片见 targets/com.baidu.BaiduMap/versions/v22.0.0/。
 */
class Anchors private constructor() {

    companion object {

        const val PKG = "com.baidu.BaiduMap"

        // ── 底部五入口栏 ───────────────────────────────────────────────────
        const val ID_UFO_ROOT = "ufo_root"
        const val NUM_UFO_ROOT = 0x7f0253d2
        const val ID_NEW_BTNS = "new_btns"
        const val NUM_NEW_BTNS = 0x7f023365
        const val ID_NEW_ROUTE = "new_route"
        const val NUM_NEW_ROUTE = 0x7f023384
        const val ID_NEW_NEARBY = "new_nearby"
        const val NUM_NEW_NEARBY = 0x7f02337f
        const val ID_NEW_THIRD = "new_third"
        const val NUM_NEW_THIRD = 0x7f02338a
        const val ID_NEW_FOURTH = "new_fourth"
        const val NUM_NEW_FOURTH = 0x7f023379
        const val ID_NEW_USER = "new_user"
        const val NUM_NEW_USER = 0x7f023391

        /**
         * 中央「长按说话」在 ufo_root 里还有一份**兄弟层**覆盖控件
         * （FrameLayout 422,0-658,223，压在 new_btns 之上）。
         * 证据：new_third 的 icon/text 都是 GONE，真正可见/可点的是
         * home_ai_container + 它的子节点 new_home_ai_touch_view。
         * 只藏 new_third 会留下这层可点的空壳 —— 两者必须同进同退。
         */
        const val ID_HOME_AI_CONTAINER = "home_ai_container"
        const val NUM_HOME_AI_CONTAINER = 0x7f021eaa
        const val ID_NEW_HOME_AI_TOUCH = "new_home_ai_touch_view"
        const val NUM_NEW_HOME_AI_TOUCH = 0x7f02337c

        // 点击代理（视觉隐藏后同步摘除，避免"看不见但点得到"）
        const val ID_PXY_ROUTE = "aihome_route_proxy"
        const val NUM_PXY_ROUTE = 0x7f02027f
        const val ID_PXY_NEARBY = "aihome_nearby_proxy"
        const val NUM_PXY_NEARBY = 0x7f020278
        const val ID_PXY_THIRD = "aihome_third_proxy"
        const val NUM_PXY_THIRD = 0x7f020281
        const val ID_PXY_FOURTH = "aihome_fourth_proxy"
        const val NUM_PXY_FOURTH = 0x7f020276
        const val ID_PXY_USER = "aihome_user_proxy"
        const val NUM_PXY_USER = 0x7f020282

        // ── 首页工具宫格 ───────────────────────────────────────────────────
        const val ID_TOOL_WRAP = "component_container3"
        const val NUM_TOOL_WRAP = 0x7f02138b
        const val ID_ROW1 = "row1_container"
        const val NUM_ROW1 = 0x7f024058
        const val ID_ROW2 = "row2_container"
        const val NUM_ROW2 = 0x7f024059
        const val ID_ROW3 = "row3_container"
        const val NUM_ROW3 = 0x7f02405a

        // ── 回家 / 去公司 ──────────────────────────────────────────────────
        const val ID_HC_WRAP = "component_container5"
        const val NUM_HC_WRAP = 0x7f02138c
        const val ID_HC_LOCAL = "view_local"
        const val NUM_HC_LOCAL = 0x7f02566b
        const val ID_HC_HOME = "home_info"
        const val NUM_HC_HOME = 0x7f021eca
        const val ID_HC_COMPANY = "company_info"
        const val NUM_HC_COMPANY = 0x7f021368
        const val ID_HC_SETTING = "setting_container"
        const val NUM_HC_SETTING = 0x7f024448
        const val ID_HC_DIVIDER = "vertical_line"
        const val NUM_HC_DIVIDER = 0x7f025608

        // ── 天气 ───────────────────────────────────────────────────────────
        /** 地图右侧那条「21°C 27 限行」原生天气条 */
        const val ID_WX_MAP = "weather_limited"
        const val NUM_WX_MAP = 0x7f025a59
        /** 底部信息流容器（天气/行程卡由 Baidu Talos 引擎渲染，落在它的瀑布流第一项里） */
        const val ID_FEED = "component_container6"
        const val NUM_FEED = 0x7f02138d

        // ── 首页容器（用于判定"这一页是不是百度首页"） ─────────────────────
        const val ID_TOP_CONTAINER = "top_container"
        const val NUM_TOP_CONTAINER = 0x7f024b6a
        const val ID_HOME_PANEL = "home_panel_container"
        const val NUM_HOME_PANEL = 0x7f021ed2
        const val ID_COMPONENT_LIST = "component_list_new"
        const val NUM_COMPONENT_LIST = 0x7f021390
        /** 「我的」页容器（Talos 渲染，判定这一页是不是我的页） */
        const val ID_USER_CENTER = "user_center_talos_container"
        const val NUM_USER_CENTER = 0x7f0254eb

        // ── 首页搜索框（「搜索框下一栏」= line2） ────────────────────────────
        /** 搜索框本体那一行（输入框 / 语音 / 扫码）。22.1.0 = 0x7f024349。 */
        const val ID_SEARCH_LINE1 = "search_box_line1"
        const val NUM_SEARCH_LINE1 = 0x7f024349
        /**
         * 搜索框**下面那一排快捷入口**（美食 / 加油站 / 景点 / 厕所 / 酒店…）。
         *
         * <p>这就是「搜索框下一栏隐藏」真正要抓的控件。22.1.0 实测 0x7f02434a。
         * 之前那一版靠文案命中（{@code SEARCH_CHIP_WORDS} 命中 ≥3 个）来定位，
         * 但这排是 **Talos（Yoga 自绘）** 渲染的，文本不进 View 树 ⇒ 永远命中 0 个
         * ⇒ 开关关掉也没反应。id 是稳定的，按 id 抓才落地。
         */
        const val ID_SEARCH_LINE2 = "search_box_line2"
        const val NUM_SEARCH_LINE2 = 0x7f02434a
        /** line2 的内容容器（服务端下发的 chip 都挂在这下面）。22.1.0 = 0x7f02434b。 */
        const val ID_SEARCH_LINE2_CONTENT = "search_box_line2_content"
        const val NUM_SEARCH_LINE2_CONTENT = 0x7f02434b
        /** 搜索页推荐位根（搜索结果页，不是首页那排 chip —— 别混用）。22.1.0 = 0x7f024364。 */
        const val ID_SEARCH_RECOMMEND = "search_recommend"
        const val NUM_SEARCH_RECOMMEND = 0x7f024364

        // ── 搜索联想（广告头） ─────────────────────────────────────────────
        /** 联想词列表里服务端下发的「Mystique」广告头 —— 搜索联想广告的唯一可靠锚点。22.1.0 = 0x7f024716。 */
        const val ID_SUG_MYSTIQUE = "sug_mystique_header"
        const val NUM_SUG_MYSTIQUE = 0x7f024716
        /** 搜索联想根容器。22.1.0 = 0x7f024711。 */
        const val ID_SUG_CONTAINER = "sugContainer"
        const val NUM_SUG_CONTAINER = 0x7f024711

        // ── 诊断计数 ───────────────────────────────────────────────────────
        @Volatile
        @JvmField
        var nameHits = 0

        @Volatile
        @JvmField
        var numHits = 0

        @Volatile
        @JvmField
        var notFound = 0

        @Volatile
        @JvmField
        var lastMissing = ""

        /**
         * 从 root 里按「id 名字 → 数值兜底」找控件。
         *
         * @return 找到的 View，找不到返回 null（**不抛异常**，调用方按 null 处理）
         */
        @JvmStatic
        fun find(root: View?, name: String, num: Int): View? {
            if (root == null) return null
            var id = 0
            try {
                val r: Resources? = root.resources
                if (r != null) {
                    id = r.getIdentifier(name, "id", PKG)
                    if (id == 0) id = r.getIdentifier(name, "id", null) // 全包搜索（兼容 id 归到库包）
                }
            } catch (ignored: Throwable) {}

            if (id != 0) {
                try {
                    val v: View? = root.findViewById<View>(id)
                    if (v != null) { nameHits++; return v }
                } catch (ignored: Throwable) {}
            }
            if (num != 0) {
                try {
                    val v: View? = root.findViewById<View>(num)
                    if (v != null) { numHits++; return v }
                } catch (ignored: Throwable) {}
            }
            notFound++
            lastMissing = name
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "anchor miss: " + name)
            return null
        }

        @JvmStatic
        fun stats(): String {
            return "name=" + nameHits + " num=" + numHits + " miss=" + notFound +
                    (if (lastMissing.length == 0) "" else " last=" + lastMissing)
        }
    }
}