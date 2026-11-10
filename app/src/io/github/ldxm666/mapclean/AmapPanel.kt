package io.github.ldxm666.mapclean

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.ldxm666.mapadkiller.Config
import io.github.libxposed.service.XposedService

/**
 * 高德地图 side of the floating panel: the switch catalogue and the config plumbing.
 *
 * <p>Moved out of [FloatingPanel] / [FloatingConfig] so that each vendor's panel
 * lives in exactly one file. `tools/build_variant.py` drops this file and substitutes a
 * no-op stub when it builds the 百度 免 root variant (and vice versa for [BaiduPanel]),
 * which is what keeps a shipped APK free of the map it will never run inside.
 *
 * <p>Everything here is 高德-specific: the keys are 高德's, the group is
 * `amap_enhancer_config`, and [apply] pushes a fresh write into the running map.
 */
class AmapPanel private constructor() {

    companion object {

        /** Write-path diagnostics: a switch that refuses to flip must say why, in one line. */
        private const val TAG_WRITE = "MapCleanCfg"

        /** Shown above the tool catalogue; see the 5-cell explanation below. */
        private const val TOOL_NOTE =
            "首页工具按原生顺序连续排列，每行最多 5 格、最多 2 行；关闭的工具与空行不占位，超出的工具仍在「更多工具」页里。"

        /** 闭区间步进序列，给滑块的停靠点用（例如 5..60 步进 1）。 */
        private fun steps(from: Int, to: Int, step: Int): IntArray {
            val count = (to - from) / step + 1
            val out = IntArray(count)
            for (i in 0 until count) out[i] = from + i * step
            return out
        }

        @JvmStatic
        fun groups(): Array<PanelTypes.Group> {
            return arrayOf(
                // ── 1 首页 ────────────────────────────────────────────────────
                // 只放「首页这一屏上能看见的东西」。自动巡航不在这里了 —— 它有自己的一页
                // （见下面第 5 项），开关和阈值放在一起用户才找得到。
                PanelTypes.Group(
                    "首页", arrayOf(
                        PanelTypes.Item(
                            Config.K_TOOLS_VISIBLE, "首页工具宫格",
                            "关闭后首页不再显示驾车 / 公交等工具入口。"
                        ),
                        PanelTypes.Item(
                            Config.K_TOOLS_ALLOWLIST, "仅显示已开启的工具",
                            "开启后未设置和新增的推广工具一律不进入首页。逐项开关在「首页工具」页。"
                        ),
                        PanelTypes.Item(
                            Config.K_TOOL_COLOR_ICON, "工具图标统一彩绘（实验）",
                            "开启后首页工具栏全部使用高德「更多工具」目录里的彩色圆底图标；" +
                                    "默认关闭，保持原生「第一行彩绘 + 第二行线条」设计。"
                        ),
                        PanelTypes.Item(
                            moreToolsKey(), "更多工具入口",
                            "首页那格「更多工具」。关掉后工具栏只剩你开启的工具。"
                        ),
                        PanelTypes.Item(
                            Config.K_HOME_CHIPS, "设置家 / 设置单位 / 常去地点",
                            "控制首页那排回家与公司的快捷入口；改动立即生效。"
                        ),
                        PanelTypes.Item(
                            Config.K_SPLASH_SKIP, "跳过高德开屏页（不推荐）",
                            "高德自带的开屏页**不是广告**，属于它自己的界面，" +
                                    "所以默认不拦。真机实测：拦掉之后并不会变快（拖慢首帧的是" +
                                    "高德自己的初始化，不是这张页），反而会少掉一屏。", true
                        ),
                        PanelTypes.Item(
                            Config.K_HOTWORD_OFF, "关闭搜索框热词轮播",
                            "把搜索框文字钉死为默认提示词，去掉运营广告词。"
                        ),
                        PanelTypes.Item(
                            Config.K_SEARCH_GRID, "搜索页快捷金刚位（实验）",
                            "搜索框下方那一排彩色入口（美食 / 酒店 / 景点门票 / 加油充电 / 超划算 / 扫街榜 等，" +
                                    "随版本变化）。关掉后整排会消失；但进入搜索页时会先闪现约 0.3 秒再消失，" +
                                    "且每次进入都会重演一次，原位还会留一条空白 —— 该排由高德页面运行时绘制、" +
                                    "容器高度写死在页面里，模块只能隐藏无法回收空间。如实告知。"
                        ),
                    ), null, POWER_CHIP
                ),

                // ── 2 净化 ────────────────────────────────────────────────────
                // 必须停在第 2 位：chip 行是横滚的，用户已经两次因为「看不到入口」报障。
                PanelTypes.Group(
                    POWER_CHIP, powerSections(), POWER_NOTE,
                    object : PanelTypes.Action {
                        override fun label(): String {
                            return "一键省电（全部开启拦截）"
                        }

                        override fun run(): Int {
                            return powerAllOn()
                        }
                    }),

                // ── 3 界面 ────────────────────────────────────────────────────
                // 原来「底栏」「我的」「内容」是三个 chip，各自 4~6 行 —— 横滚三屏就为了
                // 调几个同类的东西。合并成一页用分段标题区分，chip 数直接少两个。
                PanelTypes.Group(
                    "界面", arrayOf(
                        arrayOf<Any?>(
                            "底栏", arrayOf(
                                PanelTypes.Item(Config.K_TAB_PREFIX + "探索", "显示标签「探索」"),
                                PanelTypes.Item(Config.K_TAB_PREFIX + "长按说话", "显示标签「长按说话」"),
                                PanelTypes.Item(Config.K_TAB_PREFIX + "打车", "显示标签「打车」"),
                                PanelTypes.Item(Config.K_TAB_PREFIX + "我的", "显示标签「我的」"),
                            )
                        ),
                        arrayOf<Any?>(
                            "打车页", arrayOf(
                                PanelTypes.Item(Config.K_TAXI_ACTIVITY, "活动专区",
                                    "打车页的活动推广区；关闭后整块收起。"),
                                PanelTypes.Item(Config.K_TAXI_COUPON, "出行优惠套餐"),
                                PanelTypes.Item(Config.K_TAXI_AD, "轮播广告"),
                                PanelTypes.Item(Config.K_TAXI_OPERATION, "横滑推广"),
                                PanelTypes.Item(Config.K_TAXI_TASK, "任务 / 新人推广"),
                                PanelTypes.Item(Config.K_TAXI_VIP, "优享会员卡片"),
                                PanelTypes.Item(Config.K_TAXI_MILEAGE, "会员里程活动",
                                    "这些开关只影响打车首页的推广区，不影响地址、叫车、订单和支付。" +
                                        "更改后重启高德生效；开启只恢复高德当前实际下发的内容。"),
                            )
                        ),
                        arrayOf<Any?>(
                            "语音", arrayOf(
                                PanelTypes.Item(Config.K_VOICE_CLASSIC, "原生语音兼容模式",
                                    "使用高德保留的经典语音卡片，排查登录后只显示空胶囊的问题。" +
                                        "仅适配 17.02；变更后退出并重开高德。关闭可恢复新版 AI 语音。"),
                            )
                        ),
                        arrayOf<Any?>(
                            "我的页", arrayOf(
                                PanelTypes.Item(Config.K_MY_ORDER_ROW, "订单 / 收藏 / 待评价"),
                                PanelTypes.Item(Config.K_MY_SERVICE_ROW, "车辆服务 / 高德运动"),
                                PanelTypes.Item(Config.K_MY_TASK, "达人任务卡片"),
                                PanelTypes.Item(Config.K_MY_PROMO_ROW, "热门活动卡"),
                                PanelTypes.Item(Config.K_MY_GUESS, "猜你喜欢"),
                                PanelTypes.Item(Config.K_MY_FRIENDS, "好友动态"),
                            )
                        ),
                        arrayOf<Any?>(
                            "内容卡片", arrayOf(
                                PanelTypes.Item(Config.K_FEED_WEATHER, "天气卡片"),
                                PanelTypes.Item(Config.K_FEED_BOARD, "扫街榜 / 周边精选榜单"),
                                PanelTypes.Item(Config.K_FEED_CONTENT, "攻略 / 内容流卡片"),
                                PanelTypes.Item(Config.K_FEED_FESTIVAL, "出行节 / 促销活动接口"),
                            )
                        ),
                    ), "底栏是常驻的；「我的页」「内容卡片」要滚动进去才看得到。" +
                            "开关实时隐藏或恢复宿主已有标签，无需重启；只剩「首页」时整条底栏隐藏。" +
                            "宿主当前没有提供的标签不会凭空新增；关闭当前标签时会返回首页。"
                ),

                // ── 4 地图 ────────────────────────────────────────────────────
                // ── 4 地图（含巡航）──────────────────────────────────────────
                // 巡航并入本页：它管的是"地图在什么条件下自己进入巡航态"，属于地图行为，
                // 不是独立功能。此前单列一页，chip 行横滚时要多划一格才看得到。
                PanelTypes.Group(
                    "地图", arrayOf(
                        // 真正的实现在原生层：libmapclean_labels.so 改写 map_assets/style_*X_Main
                        // 样式 protobuf，只把兴趣点要素置不可见，道路 / 水域 / 地名全部保留。
                        PanelTypes.Item(
                            Config.K_MAP_POI, "显示地图兴趣点图标和名称",
                            "兴趣点总开关。开启后可在下方按业务类别选择；关闭时全部兴趣点隐藏。道路、街道与地名保留。修改标注设置后需完全退出并重开高德。"
                        ),
                        *io.github.ldxm666.mapadkiller.AmapPoiSettings.KEYS.mapIndexed { i, key ->
                            PanelTypes.Item(key, "显示${io.github.ldxm666.mapadkiller.AmapPoiSettings.TITLES[i]} POI",
                                "需先开启兴趣点总开关。使用高德原生业务规则；未知类别保留，搜索结果和路线标记不受影响。修改后重开高德生效。")
                        }.toTypedArray(),
                        PanelTypes.Item(
                            Config.K_MAP_PROMO, "地图右侧运营挂件",
                            "扫街榜和活动入口。"
                        ),
                        PanelTypes.Item(
                            Config.K_PLACE_RECOMMEND, "地点详情「发现好去处」",
                            "长按选点详情里的照片推荐流。"
                        ),
                        PanelTypes.Item(
                            Config.K_LABELS_ON,
                            "启用第三方标注过滤",
                            "默认关闭；开启会恢复第三方标注过滤，但可能影响地图帧率。",
                            true
                        ),
                        PanelTypes.Item(
                            Config.K_CRUISE_OFFICIAL, "使用高德原生自动进入巡航（推荐）",
                            "恢复高德自己的那条链路：官方状态机本来就在，只是没人布防。" +
                                    "开启后由高德自己判（车速 20km/h 持续 10 秒），进入动作走官方 JS 桥，" +
                                    "巡航播报、红绿灯、退出都由官方处理。默认开。"
                        ),
                        PanelTypes.Item(
                            Config.K_CRUISE_AUTO, "自动进入巡航（模块接管 · 兜底）",
                            "高德自带开关在 17.02 上点击不落盘、官方状态机不启动，所以由模块接管：" +
                                    "高德在前台且不在导航/巡航中时，车速持续达到下面的「触发速度下限」" +
                                    "满「触发持续时间」，并且真的跑出去一段距离（净位移判据，原地抖动不算），" +
                                    "就自动进入巡航。走路、拿手机晃、室内跳点都不会触发。"
                        ),
                    ), arrayOf(
                        PanelTypes.Metric(
                            Config.K_NAVI_INFO_PERCENT, "导航信息栏大小（横屏）",
                            "车机横屏时，等比例放大顶部转向图标、距离和路名。100% 为原生大小；" +
                                "受屏幕可用范围限制时自动收敛，避免超出屏幕。竖屏与路口放大图显示期间保持原生布局。松手即生效。",
                            "%", steps(100, 200, 10), null, 100
                        ),
                        PanelTypes.Metric(
                            Config.K_CRUISE_KMH, "触发速度下限",
                            "速度持续超过这个值才认为在开车。调低更容易进巡航，代价是骑车、坐车也可能被算进来。",
                            "km/h", steps(Config.CRUISE_KMH_MIN, Config.CRUISE_KMH_MAX, 1), null,
                            Config.CRUISE_KMH_DEFAULT
                        ),
                        PanelTypes.Metric(
                            Config.K_CRUISE_SEC, "触发持续时间",
                            "速度持续达标多少秒后进入巡航。高德官方云控是 5 秒，这个值调小会更灵敏。",
                            "秒", steps(Config.CRUISE_SEC_MIN, Config.CRUISE_SEC_MAX, 1), null,
                            Config.CRUISE_SEC_DEFAULT
                        ),
                    ), "只影响高德。导航栏大小与模块巡航触发值松手即保存，无需重启。" +
                            "速度和时间两项仅作用于「模块接管」，不改变高德原生巡航的 20 km/h、10 秒判据。"
                ),

                // ── 6 首页工具（逐项） ────────────────────────────────────────
                // 目录是「学」出来的（首启扫描首页宫格），行数几十条，所以单列一页放最后。
                PanelTypes.Group("首页工具", toolSections(), TOOL_NOTE),

                // ── 6 其他（实验特性 + 公告 + 版本）────────────────────────────
                // 实验并进本页：两项实验开关加一个目标帧率滑块，此前单列一页纯粹是因为
                // about 标志只认纯 Item 页 —— 现在 Group 支持 (items, metrics, note, about)，
                // 一页就能同时承载。chip 行横滚，少一页就少一次"看不到入口"。
                //
                // 这两行动的是**高德自己的地图渲染**，不是广告也不是界面：判错就是地图错乱，
                // 所以缺省必须是"什么都不做"（见 Config.defaultVisible 里那两个键）。
                //
                // 两行都是**安装期门控** —— 开关只在 hook 安装时读一次，所以文案里必须写清
                // "改完要强停重开高德"。尤其是建筑那条：native 补丁是进程级全局的，
                // 就算之后把开关关掉，也要重启宿主才能把楼块恢复回来。不写这句，
                // 用户会当成"这个开关关不掉"。
                PanelTypes.Group(
                    "其他", concat(
                        othersItems(), arrayOf(
                            PanelTypes.Item(
                                Config.K_AMAP_FPS_ON, "帧率上限（实验）",
                                "默认关闭。开启后地图出帧被限制在下面选的目标帧率。" +
                                        "改变的是渲染回调的节拍，不是把引擎调快 —— 高德本身跑不满时不会拉高。" +
                                        "60 和 120 与屏幕刷新率整除，是精确值；90 需要屏幕本身有 90Hz 输出，" +
                                        "否则会落在最近的整数分频上。" +
                                        "⚠ 改完要强停并重开高德才生效。"
                            ),
                            PanelTypes.Item(
                                Config.K_AMAP_BUILDING_ON, "去除建筑物模型（实验）",
                                "默认关闭。开启后导航/巡航态不再绘制 3D 与扁平楼块，只剩路网、绿地、" +
                                        "地名与兴趣点。实现是原生层补丁（libamapr.so 里楼块图层的绘制槽），" +
                                        "不做任何 Java 层可见性改写。" +
                                        "⚠ 改完要强停并重开高德才生效；补丁一旦打上是**进程级全局**的，" +
                                        "之后把开关关掉也要重启高德，楼块才会回来。"
                            ),
                        )
                    ), arrayOf(
                        PanelTypes.Metric(
                            Config.K_AMAP_FPS_TARGET, "目标帧率",
                            "只在上面「帧率上限」打开时起作用。", " 帧",
                            intArrayOf(60, 90, 120), null, 60
                        ),
                    ), "实验特性只影响高德，默认全关。行尾标 ⚠ 的两项都需要强停重开高德；" +
                            "可在 logcat 搜 event=fps_sample / event=building_patch 看实际生效情况。",
                    true
                ),
            )
        }

        /**
         * 「其他」页的条目。
         *
         * <p>「悬浮窗入口（悬浮球）」**只在免 root 内置版出现**：模块模式（root/LSPosed）根本
         * 没有悬浮窗（`FloatingHooks.install` 一条 hook 都不装），留着这一行只会让人困惑
         * —— 用户明确要求"应用内悬浮窗整条移除，只给免 root 用"。内置版保持原样。
         */
        /** 把两段 Item 拼成一个数组 —— 其他页要同时挂动态项（悬浮球）和固定项（实验）。 */
        private fun concat(a: Array<PanelTypes.Item>, b: Array<PanelTypes.Item>): Array<PanelTypes.Item> {
            return a + b
        }

        private fun othersItems(): Array<PanelTypes.Item> {
            val out = java.util.ArrayList<PanelTypes.Item>()
            if (FloatingConfig.isEmbedded()) {
                out.add(
                    PanelTypes.Item(
                        FloatingConfig.K_FLOAT_ENABLED, "悬浮窗入口（悬浮球）",
                        "关掉后悬浮球和这个面板都不再出现。需要重新打开时：长按底部「我的」标签约 1 秒，" +
                                "面板会再次弹出，把这一项拨回开即可。"
                    )
                )
            }
            out.add(
                PanelTypes.Item(
                    Config.K_DEBUG_LOG, "调试日志",
                    "把协议适配错误输出到 logcat，排障用。"
                )
            )
            return out.toArray(arrayOf<PanelTypes.Item>())
        }

        /**
         * 净化页的 chip 文字。
         *
         * <p>两个字是刻意的：chip 行是横向滚动的，组名越长，它越容易被裁在屏幕边缘 —— 用户已经
         * 两次因为「看不到入口」而报障。完整名字写在说明第一句里。
         *
         * <p>叫「净化」而不是原来的「省电」：这一页现在装的是推送长连接、后台进程、埋点上报、
         * 开屏广告 —— 只有第一条真的省电，其余都是去广告。老用户可能在找「省电」，所以说明
         * 第一句里保留了「省电 / 后台」原词。
         */
        private const val POWER_CHIP = "净化"

        /**
         * 省电页的说明：把最容易误读的语义放在最上面，末尾再加安装期门控的生效条件。
         *
         * <p>这一页的开关「打开 = 该行为已被拦截」而不是「打开 = 启用功能」，和别的页正好相反，
         * 不写清楚用户一定会以为自己关掉了省电。
         *
         * <p>末尾那句是必需的：这批拦截已改成**安装期门控**（开关为关时根本不装 hook），
         * 所以改完必须强停并重开高德才会重新走安装流程 —— 否则用户会以为开关没生效。
         * 每行不再重复这句，避免 11 行都在说同一件事。
         */
        private const val POWER_NOTE =
            "净化 / 后台（原「省电」页）。这一页的开关：开 = 该行为已被拦截（默认全开）；关 = 恢复高德原生行为。" +
                    "关掉推送长连接后，行程提醒 / 消息 / 家人地图会恢复，但 ACCS 长连接会重新占用 CPU。" +
                    "⚠ 本页开关改动后需强停并重开高德才生效（关闭时会彻底不加载对应实现，零性能开销）。"

        /** Keys written by 「一键省电」: every gate that only removes background work. */
        private val POWER_ALL_KEYS: Array<String> = arrayOf(
            Config.K_PUSH_HARD_OFF,
            Config.K_PUSH_OFF,
            Config.K_PUSH_KILL_OFF,
            Config.K_SUBPROC_JOB_OFF,
            Config.K_SUBPROC_WIDGET_OFF,
            Config.K_SUBPROC_LOTUSPOOL_OFF,
            Config.K_TRACK_OFF,
            Config.K_ALIMAMA_SPLASH_OFF,
            Config.K_ALIMAMA_LOG_OFF,
        )

        /**
         * 净化 / 后台 的四段开关。
         *
         * <p>只在高德侧注册：`push_*` / `subproc_*` / `track_off` /
         * `alimama_*` 的实现（`AmapJob`/`AmapTrack`/`AmapAlimama`/
         * `AmapSubprocess`）只存在于高德钩子里，放进百度/腾讯面板会变成点了没反应的空开关。
         *
         * <p>`labels_on` 是唯一反义的一项：`true` = 启用第三方标注过滤，且它的消费方
         * （`AmapPoiLayer.install`）用的是 `Config.selected()` 严格读 ⇒ 默认 false，
         * 所以它带 `strict` 标记，否则开关会显示成「开」而过滤其实是关的。
         */
        @JvmStatic
        fun powerSections(): Array<Array<Any?>> {
            return arrayOf(
                arrayOf<Any?>(
                    "轻量运行", arrayOf(
                        PanelTypes.Item(Config.K_LIGHT_RUNTIME, "减少取证与诊断开销",
                            "默认开启：不安装全量网络与存储取证探针，保留搜索和页面过滤。" +
                                "变更后重开高德；调试日志开启时恢复取证。此项不代表已测得耗电降幅。"),
                    )
                ),
                arrayOf<Any?>(
                    "推送", arrayOf(
                        PanelTypes.Item(
                            Config.K_PUSH_HARD_OFF,
                            "关闭推送长连接（ACCS，省电最多）",
                            "关掉后高德推送（行程提醒 / 活动 / 消息 / 家人地图）会恢复，" +
                                    "但 ACCS 长连接会重新占用 CPU。"
                        ),
                        PanelTypes.Item(
                            Config.K_PUSH_OFF,
                            "关闭推送消息处理",
                            "不建长连接、不处理推送消息，但保留服务壳；比上面那条温和。"
                        ),
                        PanelTypes.Item(
                            Config.K_PUSH_KILL_OFF,
                            "关闭推送服务壳（激进）",
                            "推送服务一创建就 stopSelf。可能影响推送相关功能，排查用。"
                        ),
                    )
                ),
                arrayOf<Any?>(
                    "后台任务", arrayOf(
                        PanelTypes.Item(
                            Config.K_SUBPROC_JOB_OFF,
                            "关闭周期任务（WorkManager / JobScheduler）",
                            "关掉后高德的周期后台任务会恢复（更耗电）。"
                        ),
                        PanelTypes.Item(
                            Config.K_SUBPROC_OFF,
                            "关闭子进程拦截（总闸，排查用）",
                            "一次性放行所有子进程拦截；怀疑某功能被误伤时先关这个。"
                        ),
                        PanelTypes.Item(
                            Config.K_SUBPROC_WIDGET_OFF,
                            "关闭桌面小组件刷新",
                            "只拦标准小组件周期广播；保留导航通知服务与 OPPO 卡片初始化。关闭后恢复全部组件刷新。"
                        ),
                        PanelTypes.Item(
                            Config.K_SUBPROC_LOTUSPOOL_OFF,
                            "关闭云控调度池（LotusPool）",
                            "关掉后高德云控下发的周期任务会恢复。"
                        ),
                    )
                ),
                arrayOf<Any?>(
                    "埋点与广告", arrayOf(
                        PanelTypes.Item(
                            Config.K_TRACK_OFF,
                            "关闭阿里埋点 Analytics",
                            "关掉后阿里 UT 埋点会恢复上报。真机出现无法归因的异常时，第一个关它。"
                        ),
                        PanelTypes.Item(
                            Config.K_ALIMAMA_SPLASH_OFF,
                            "拦截开屏广告（含缓存）",
                            "默认开启：拦截阿里妈妈广告请求和已缓存物料，沿用高德的无广告结束流程。" +
                                    "不会强制跳过整个品牌启动页。改动后需强停并重开高德。"
                        ),
                        PanelTypes.Item(
                            Config.K_ALIMAMA_LOG_OFF,
                            "关闭广告曝光上报",
                            "只关上报，不影响广告本身是否展示。"
                        ),
                    )
                ),
                // 「地图」分段已删：它只有一行 labels_on，而同一行在「地图」页首段里
                // 已经存在。同一开关出现两次，用户在两处拨动会看到两个不同的即时状态
                // （两处各自持有一份 GlassSwitch），是"开关回弹"类报障的典型来源。
            )
        }

        /**
         * 「一键省电」：把 9 个省电键一次性写成 true。
         *
         * <p>一次 `edit()/commit()` 而不是 9 次 [write]：后者每个键都会跑一遍
         * [apply]（`AmapNativeHome.onConfigChanged` + `NativeLabelAssets.refresh`），
         * 而这两个动作对这 9 个键毫无意义 —— 它们全是 hook 侧的闸门，不改视图树。
         *
         * @return 写入成功的键数；0 表示没有任何可写路径（服务未连接且 prefs 不可用）。
         */
        @JvmStatic
        fun powerAllOn(): Int {
            val service = try {
                io.github.ldxm666.mapadkiller.App.svc()
            } catch (ignored: Throwable) {
                null
            }
            if (service != null && !FloatingConfig.isEmbedded()) {
                try {
                    val editor: SharedPreferences.Editor =
                        service.getRemotePreferences(Config.PREF_GROUP).edit()
                    for (key in POWER_ALL_KEYS) editor.putBoolean(key, true)
                    if (editor.commit()) {
                        markWrite(true, null)
                        invalidate()
                        return POWER_ALL_KEYS.size
                    }
                } catch (ignored: Throwable) {
                }
            }
            try {
                val editor = Config.edit()
                if (editor != null) {
                    for (key in POWER_ALL_KEYS) editor.putBoolean(key, true)
                    if (editor.commit()) {
                        markWrite(true, null)
                        invalidate()
                        return POWER_ALL_KEYS.size
                    }
                }
            } catch (ignored: Throwable) {
            }
            markWrite(false, R_COMMIT_FALSE)
            return 0
        }

        /**
         * Every entry of 高德's "More Tools" catalogue, grouped the way the app groups it.
         *
         * <p>Keys come from [io.github.ldxm666.mapadkiller.AmapToolCatalog.key], so a switch
         * here writes exactly the key the hook reads -- there is no parallel key scheme to drift
         * out of sync with the standalone settings page.
         *
         * <p>Within a section the enabled switches are listed first: the catalogue runs past a
         * hundred entries, and the handful that are actually on are what the user comes back to
         * adjust. The split is stable, so a row does not jump around while the sheet is open.
         */
        @JvmStatic
        fun toolSections(): Array<Array<Any?>> {
            val out = java.util.ArrayList<Array<Any?>>()
            for (group in io.github.ldxm666.mapadkiller.AmapToolCatalog.GROUPS) {
                val on = java.util.ArrayList<PanelTypes.Item>()
                val off = java.util.ArrayList<PanelTypes.Item>()
                for (entry in io.github.ldxm666.mapadkiller.AmapToolCatalog.ENTRIES) {
                    if (group != entry[2]) continue
                    // 「更多工具」 lives in the 首页 group instead: it is the app's fixed cell, not
                    // one of the catalogue's promotional tools, and listing it here as well would
                    // give the same key two switches.
                    if (io.github.ldxm666.mapadkiller.AmapData.MORE_TOOLS == entry[0]) continue
                    val key = io.github.ldxm666.mapadkiller.AmapToolCatalog.key(entry[0]) ?: continue
                    val item = PanelTypes.Item(key, entry[1], "工具 ID " + entry[0])
                    (if (Config.visible(key)) on else off).add(item)
                }
                if (on.isEmpty() && off.isEmpty()) continue
                on.addAll(off)
                out.add(arrayOf<Any?>(group, on.toArray(arrayOf<PanelTypes.Item>())))
            }
            return out.toArray(arrayOf<Array<Any?>>())
        }

        /** The key of the app's fixed 「更多工具」 cell, as the hook side spells it. */
        @JvmStatic
        fun moreToolsKey(): String {
            val key = io.github.ldxm666.mapadkiller.AmapToolCatalog.key(
                io.github.ldxm666.mapadkiller.AmapData.MORE_TOOLS
            )
            return if (key == null) Config.K_TOOL_PREFIX + "更多工具" else key
        }

        /**
         * Which write path is currently usable.
         *
         * <p>2 = the framework service (`WRITE_SVC`); 1 = a local in-process backend
         * (`WRITE_PREFS`); 0 = neither.
         */
        @JvmStatic
        fun writePath(): Int {
            return when (effectiveBackend()) {
                BACKEND_REMOTE -> 2
                BACKEND_LOCAL -> 1
                else -> 0
            }
        }

        /* ------------------------------------------------ 配置后端（对齐 amap-core2） */

        /** No backend at all: nothing can be read or written (mirrors `Config.BACKEND_NONE`). */
        const val BACKEND_NONE = Config.BACKEND_NONE

        /** Local SharedPreferences backend: works in-process, no framework channel needed. */
        const val BACKEND_LOCAL = Config.BACKEND_LOCAL

        /** Framework remote-preferences backend (the service is bound). */
        const val BACKEND_REMOTE = Config.BACKEND_REMOTE

        /**
         * `Config.backend()` -- `amap-core2` 的最终 API（L288）。
         *
         * <p>早前这里用反射，是因为那个接口还没落地、名字也没定；现在它是正式接口，直接调。
         */
        private fun configBackend(): Int {
            try {
                return Config.backend()
            } catch (t: Throwable) {
                return BACKEND_NONE
            }
        }

        /** -1 = not probed, 0 = refused, 1 = the probe write landed. One probe per process. */
        @Volatile
        private var probeState = -1

        /** Harmless key used by the probe; written and removed immediately. */
        private const val K_PROBE = "_probe"

        /**
         * Prove writability with one real write instead of trusting a static verdict.
         *
         * <p>Tries both channels (service, then in-process preferences), removes the key again, and
         * logs exactly one line: `event=cfg_probe ok|fail backend=… reason=…`. This is what
         * keeps the pill honest when `Config.backend()` answers NONE on a device where a
         * write would actually succeed.
         */
        @JvmStatic
        fun probeWritable(): Boolean {
            val known = probeState
            if (known >= 0) return known == 1
            var ok = false
            var reason = "no_channel"
            val service = try {
                io.github.ldxm666.mapadkiller.App.svc()
            } catch (ignored: Throwable) {
                null
            }
            if (service != null) {
                try {
                    val e = service.getRemotePreferences(Config.PREF_GROUP).edit()
                    e.putLong(K_PROBE, System.currentTimeMillis())
                    if (e.commit()) {
                        ok = true
                        try {
                            service.getRemotePreferences(Config.PREF_GROUP)
                                .edit().remove(K_PROBE).commit()
                        } catch (ignored: Throwable) {
                        }
                    } else {
                        reason = "svc_commit_false"
                    }
                } catch (t: Throwable) {
                    reason = "svc_" + t.javaClass.simpleName
                }
            } else {
                reason = "svc_null"
            }
            if (!ok) {
                try {
                    // Config.edit() is null when there is no backend at all -- that is the NONE
                    // case, not an error, so it must not throw.
                    val e = Config.edit()
                    if (e == null) {
                        reason = "no_backend"
                    } else {
                        e.putLong(K_PROBE, System.currentTimeMillis())
                        if (e.commit()) {
                            ok = true
                            try {
                                val r = Config.edit()
                                if (r != null) r.remove(K_PROBE).commit()   // 不留脏键
                            } catch (ignored: Throwable) {
                            }
                        } else {
                            reason = "prefs_commit_false"
                        }
                    }
                } catch (t: Throwable) {
                    reason = "prefs_" + t.javaClass.simpleName
                }
            }
            probeState = if (ok) 1 else 0
            val declared = configBackend()
            Log.i(
                TAG_WRITE, "event=cfg_probe " + (if (ok) "ok" else "fail")
                        + " backend=" + (if (declared < 0) "unknown" else declared.toString())
                        + (if (ok) "" else " reason=" + reason)
            )
            return ok
        }

        /**
         * The backend to report, after verification.
         *
         * <p>NONE is never taken at face value: it is checked with one real write, and a successful
         * write upgrades the answer to LOCAL. A stale NONE is exactly what told users 「只读」 while
         * the sheet was sitting inside the target app.
         */
        @JvmStatic
        fun effectiveBackend(): Int {
            val declared = configBackend()
            if (declared == BACKEND_NONE) return if (probeWritable()) BACKEND_LOCAL else BACKEND_NONE
            return declared
        }

        /* ------------------------------------------------ 最近一次写入结果（药丸着色用） */

        /** Nothing has been written yet in this process. */
        const val W_UNTRIED = 0

        /** The last write landed. */
        const val W_OK = 1

        /** The last write was refused (permissions / IO / read-only proxy). */
        const val W_REFUSED = 2

        @Volatile
        private var lastWrite = W_UNTRIED

        @Volatile
        private var lastWriteReason: String? = null

        /** Reason codes, kept short so they fit a log line and a toast. */
        const val R_SVC_NULL = "svc_null"
        const val R_PREFS_NULL = "prefs_null"
        const val R_COMMIT_FALSE = "commit_false"
        const val R_IO = "io"

        /** No backend at all (`Config.BACKEND_NONE`): nothing to write through. */
        const val R_NO_BACKEND = "no_backend"

        private fun markWrite(ok: Boolean, reason: String?) {
            lastWrite = if (ok) W_OK else W_REFUSED
            lastWriteReason = if (ok) null else reason
            // A successful write proves a backend exists -- upgrade a stale "no channel" verdict
            // so the pill cannot keep saying 只读 after the channel came back.
            if (ok) probeState = 1
        }

        /** [W_UNTRIED] / [W_OK] / [W_REFUSED]. */
        @JvmStatic
        fun lastWriteState(): Int {
            return lastWrite
        }

        /** [R_SVC_NULL] / [R_PREFS_NULL] / [R_COMMIT_FALSE] / [R_IO]. */
        @JvmStatic
        fun lastWriteReason(): String? {
            return lastWriteReason
        }

        /**
         * Kept as a thin alias for older call sites: the real check is now
         * [probeWritable], which also tries the service channel and uses the `_probe`
         * key the lead specified. Two probes writing two different keys was just clutter.
         */
        @JvmStatic
        fun prefsWritable(): Boolean {
            return probeWritable()
        }

        /** True when a writable path is currently available. */
        @JvmStatic
        fun writable(): Boolean {
            return writePath() > 0
        }

        /** `ok` / `null`, for the write-failure logs. */
        private fun svcState(service: XposedService?): String {
            return if (service != null) "ok" else "null"
        }

        /** `ok` / `null` for the module instance the prefs fallback needs. */
        private fun moduleState(): String {
            try {
                return if (io.github.ldxm666.mapadkiller.H.module == null) "null" else "ok"
            } catch (t: Throwable) {
                return "null"
            }
        }

        @JvmStatic
        fun read(key: String): Boolean {
            return Config.visible(key)
        }

        /**
         * True when the key has an <b>explicit</b> stored value.
         *
         * <p>Needed because `Config.visible()` cannot tell "unset" from "set to the default":
         * the mode-aware default for `float_enabled` only applies to keys the user never
         * wrote. Returns false when there is no backend at all (nothing is stored by definition).
         */
        @JvmStatic
        fun hasStored(key: String): Boolean {
            try {
                val p = Config.prefs()
                return p != null && p.contains(key)
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * The 地图兴趣点 switch, read the way the hooks read it.
         *
         * <p>Used by [NativeLabelAssets] to decide the mask it hands the native filter. The
         * key differs per vendor, which is why this is a per-panel method rather than a constant.
         */
        @JvmStatic
        fun mapPoiVisible(): Boolean {
            return Config.visible(Config.K_MAP_POI)
        }

        @JvmStatic
        fun mapPoiHiddenMask(): Int = io.github.ldxm666.mapadkiller.AmapPoiSettings.hiddenMask()

        /**
         * Persist one switch. Uses `commit()` rather than `apply()`: the user may
         * leave the map immediately after flipping a switch, and an async write would be lost.
         *
         * <p>Two paths, in this order, and <b>both are tried</b>:
         * <ol>
         *   <li>the framework service (`App.svc()`) -- writable in every build;</li>
         *   <li>`Config.put(...)` -- the local backend `amap-core2` added, which needs no
         *       framework channel at all and is therefore the path that works when the remote
         *       preferences never arrive (returns false when `Config.BACKEND_NONE`).</li>
         * </ol>
         * Every branch logs unconditionally: a switch that refuses to flip is a user-visible
         * failure, and 「服务未连接」 alone cannot say which of the two paths died.
         */
        @JvmStatic
        fun write(key: String, value: Boolean): Boolean {
            val service = try {
                io.github.ldxm666.mapadkiller.App.svc()
            } catch (ignored: Throwable) {
                null
            }
            if (service != null && !io.github.ldxm666.mapadkiller.CfgStore.embedded()) {
                try {
                    if (service.getRemotePreferences(Config.PREF_GROUP)
                            .edit().putBoolean(key, value).commit()
                    ) {
                        markWrite(true, null)
                        Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=svc")
                        apply(key)
                        return true
                    }
                    Log.w(
                        TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                                + moduleState() + " stage=svc reason=" + R_COMMIT_FALSE
                    )
                } catch (t: Throwable) {
                    Log.w(
                        TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                                + moduleState() + " stage=svc reason=" + R_IO
                                + " err=" + t.javaClass.simpleName
                    )
                }
            } else {
                Log.w(
                    TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=null module="
                            + moduleState() + " stage=svc reason=" + R_SVC_NULL
                )
            }
            try {
                if (Config.put(key, value)) {
                    markWrite(true, null)
                    Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=prefs")
                    apply(key)
                    return true
                }
                val reason = if (configBackend() == BACKEND_NONE) R_NO_BACKEND else R_COMMIT_FALSE
                markWrite(false, reason)
                Log.w(
                    TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                            + " module=" + moduleState() + " stage=prefs reason=" + reason
                )
            } catch (t: Throwable) {
                val reason = if (t is NullPointerException) R_PREFS_NULL else R_IO
                markWrite(false, reason)
                Log.w(
                    TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                            + " module=" + moduleState() + " stage=prefs reason=" + reason
                            + " err=" + t.javaClass.simpleName
                )
            }
            // Third path: the LSPosed remote-preference file itself. getRemotePreferences(group)
            // in the host is backed by the *module package's* shared_prefs/<group>.xml, so writing
            // it from the module app is what actually makes a switch visible to 高德 when the
            // module has no running service channel (the standalone-module case).
            try {
                val ctx = App.ctx()
                if (ctx != null && ctx.getSharedPreferences(
                        io.github.ldxm666.mapadkiller.Config.PREF_GROUP,
                        Context.MODE_PRIVATE
                    ).edit().putBoolean(key, value).commit()
                ) {
                    markWrite(true, null)
                    Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=module_prefs")
                    apply(key)
                    return true
                }
            } catch (t: Throwable) {
                Log.w(
                    TAG_WRITE, "event=cfg_write_fail key=" + key + " stage=module_prefs err="
                            + t.javaClass.simpleName
                )
            }
            return false
        }

        /**
         * Read one switch the way its <b>consumer</b> reads it when "on" means the literal stored
         * value rather than "the behaviour is suppressed".
         *
         * <p>Only `labels_on` uses this today: `AmapPoiLayer.install` gates on
         * `Config.selected(K_LABELS_ON)` (strict `Boolean.TRUE`), so its default is
         * false. Hydrating that row through [read] would answer true (the
         * `defaultVisible` fallback) and the switch would lie about the filter being on.
         */
        @JvmStatic
        fun readStrict(key: String): Boolean {
            try {
                return Config.selected(key)
            } catch (t: Throwable) {
                return false
            }
        }

        @JvmStatic
        fun invalidate() {
            try {
                Config.invalidate()
            } catch (ignored: Throwable) {
            }
        }

        /**
         * Read one <b>int</b>-valued key (the orb's appearance settings).
         *
         * <p>Separate from [read] because that one is the switch channel: it asks
         * `Config.visible()`, which resolves a missing key through `defaultVisible`
         * and returns a boolean. A slider value has no business in that table.
         *
         * <p>Reads the live [Config.prefs] rather than the 2 s snapshot, so a value the
         * user just released is visible to the very next call. Any failure (module instance not
         * ready, group missing) returns `fallback` -- the caller is the orb constructor and
         * must never be handed an exception.
         */
        @JvmStatic
        fun readInt(key: String, fallback: Int): Int {
            try {
                // Config.getInt 自己处理"后端不可用"（返回 fallback，不抛），比直接摸 prefs 稳。
                return Config.getInt(key, fallback)
            } catch (t: Throwable) {
                return fallback
            }
        }

        /**
         * Persist one int-valued key, same write order as [write]:
         * the embedded service first (writable in the integrated build), then the hook-side
         * preferences.
         *
         * <p>Refresh only the navigation header for its own key. Other numeric keys
         * are read by their consumers; none requires a home-page or map-label refresh.
         */
        @JvmStatic
        fun writeInt(key: String, value: Int): Boolean {
            val service = try {
                io.github.ldxm666.mapadkiller.App.svc()
            } catch (ignored: Throwable) {
                null
            }
            if (service != null && !io.github.ldxm666.mapadkiller.CfgStore.embedded()) {
                try {
                    // commit 返回 true 不代表值留得住：内置版里 remote 是只读代理，commit 说成功、
                    // 下次读还是旧值。必须回读校验，对不上就落到下面的 Config.put
                    // （内置版走 CfgStore 的 local+remote 合并路径）。
                    if (service.getRemotePreferences(Config.PREF_GROUP)
                            .edit().putInt(key, value).commit()
                        && Config.getInt(key, if (value == 0) 1 else 0) == value
                    ) {
                        markWrite(true, null)
                        Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=svc")
                        invalidate()
                        if (key == Config.K_NAVI_INFO_PERCENT) io.github.ldxm666.mapadkiller.AmapNaviScale.refresh()
                        return true
                    }
                    Log.w(
                        TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                                + moduleState() + " stage=svc reason=" + R_COMMIT_FALSE
                    )
                } catch (t: Throwable) {
                    Log.w(
                        TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=ok module="
                                + moduleState() + " stage=svc reason=" + R_IO
                                + " err=" + t.javaClass.simpleName
                    )
                }
            } else {
                Log.w(
                    TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=null module="
                            + moduleState() + " stage=svc reason=" + R_SVC_NULL
                )
            }
            try {
                if (Config.put(key, value)) {
                    markWrite(true, null)
                    Log.i(TAG_WRITE, "event=cfg_write_ok key=" + key + " via=prefs")
                    invalidate()
                    if (key == Config.K_NAVI_INFO_PERCENT) io.github.ldxm666.mapadkiller.AmapNaviScale.refresh()
                    return true
                }
                val reason = if (configBackend() == BACKEND_NONE) R_NO_BACKEND else R_COMMIT_FALSE
                markWrite(false, reason)
                Log.w(
                    TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                            + " module=" + moduleState() + " stage=prefs reason=" + reason
                )
            } catch (t: Throwable) {
                val reason = if (t is NullPointerException) R_PREFS_NULL else R_IO
                markWrite(false, reason)
                Log.w(
                    TAG_WRITE, "event=cfg_write_fail key=" + key + " svc=" + svcState(service)
                            + " module=" + moduleState() + " stage=prefs reason=" + reason
                            + " err=" + t.javaClass.simpleName
                )
            }
            return false
        }

        /**
         * Push a freshly written switch into the running map.
         *
         * <p>The panel and the hooks share one process in the integrated build, so a toggle can be
         * applied to the view tree and to the native filter immediately. Without this the change
         * only became visible the next time the home page happened to be rebuilt -- which is what
         * made 「设置家」 and the tool switches look dead even though the write had landed.
         * Every step is best-effort: a failure here must never make the write look unsuccessful.
         */
        @JvmStatic
        fun apply(key: String?) {
            try {
                Config.invalidate(key)
            } catch (ignored: Throwable) {
            }
            try {
                NativeLabelAssets.refresh()
            } catch (ignored: Throwable) {
            }
        }
    }
}
