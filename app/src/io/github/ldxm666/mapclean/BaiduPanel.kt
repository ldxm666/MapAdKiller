package io.github.ldxm666.mapclean

import io.github.ldxm666.bmapclean.App as BmapApp
import io.github.ldxm666.bmapclean.Spec as BmapSpec

/**
 * 百度地图 side of the floating panel: the switch catalogue and the config plumbing.
 *
 * <h3>Why this file exists separately from [AmapPanel]</h3>
 * The orb renders one vendor's switches, never both. Keeping each vendor in its own file is
 * what lets the 免 root 内置 builds stay honest: {@code tools/build_variant.py} drops the other
 * vendor's package and replaces the matching panel class with a no-op stub, so the shipped
 * APK contains no code for a map it will never run inside.
 *
 * <h3>Catalogue source</h3>
 * The categories and rows come straight from [BmapSpec.cats], which is the same catalogue
 * the standalone settings page renders and the same keys the hooks read. There is deliberately
 * no second copy of the switch list here.
 *
 * <h3>Reads and writes</h3>
 * The group is {@code bmapclean_config}. Writes go through [BmapApp], which holds the
 * LSPosed service; in an integrated LSPatch build that service is the writable Binder the
 * patcher hands the host, and {@code mapclean.App.attachEmbedded} makes sure the 百度 holder
 * has it.
 */
class BaiduPanel private constructor() {

    companion object {
        /** 百度's catalogue, mapped onto the panel's view model. */
        @JvmStatic
        fun groups(): Array<PanelTypes.Group> {
            // ★ 2.3.0 重排：原来「每个 Spec 分类一个 chip」= 底栏 / 首页宫格 / 通勤 / 天气行程 /
            // 推荐信息流 / 我的页 / 地图标注 / 搜索区 / 路线页 / 诊断 + 悬浮窗 + 省电 = **12 个 chip**，
            // 横着滑三屏才能找到想调的东西，用户反馈「太臃肿、分类很差」。
            // 现在按「用户在哪一屏看到它」归并成 6 页，页内用分段标题区分：
            //   界面（底栏/首页宫格/通勤/我的页）· 内容（天气行程/推荐流/搜索区）
            //   · 地图（标注，动态列表单列）· 路线 · 净化（含悬浮窗）· 其他（诊断+版本）
            return arrayOf(
                PanelTypes.Group(
                    "界面", sections(
                        BmapSpec.CAT_BAR, BmapSpec.CAT_TOOLS, BmapSpec.CAT_HC, BmapSpec.CAT_MINE
                    ),
                    "底部入口栏、首页工具宫格、通勤地址、我的页卡片。"
                ),

                PanelTypes.Group(
                    "内容", sections(
                        BmapSpec.CAT_WX, BmapSpec.CAT_FEED, BmapSpec.CAT_SEARCH
                    ),
                    "天气与行程栏、推荐信息流、搜索框下方快捷入口。"
                ),

                // 地图标注的行是 BaiduLabelSettings 动态生成的（按 POI 类型），几十条，
                // 混进别的页会把其它开关淹掉，所以单列。
                PanelTypes.Group(
                    "地图", sections(BmapSpec.CAT_MAP),
                    "按类型分别控制地图上的兴趣点图标与店名；地名、街道、城市名和路网始终保留。" +
                        "改动后需彻底关闭再打开百度。"
                ),

                PanelTypes.Group(
                    "路线", sections(BmapSpec.CAT_ROUTE),
                    "地铁 / 公交方案详情页上的浮层与推荐位。改动后需彻底关闭再打开百度。"
                ),

                PanelTypes.Group(
                    "净化", powerSections(), POWER_NOTE,
                    object : PanelTypes.Action {
                        override fun label(): String = "一键省电（全部开启拦截）"

                        override fun run(): Int {
                            return try {
                                io.github.ldxm666.bmapclean.Cfg.enableAllPower()
                            } catch (t: Throwable) {
                                0
                            }
                        }
                    }),

                // 诊断只剩「调试日志」这一行，并进「其他」，顺带把版本/更新两行挂上
                // （免 root 内置版没有 MainActivity，这里是它唯一的版本入口）。
                PanelTypes.Group(
                    "其他", arrayOf(
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_DEBUG, "调试日志",
                            "把协议适配错误输出到 logcat，排障用。"
                        )
                    ), "百度 22.1.0 (1660) 适配；改动后重启百度生效。", true
                )
            )
        }

        /**
         * 把若干个 [BmapSpec.Cat] 按 id 取出来，摊成面板的分段（{title, Item[]} 对）。
         *
         * <p>为什么要按 id 取而不是直接遍历 {@code Spec.cats()}：分类顺序由本文件决定 ——
         * 面板的 chip 顺序是产品决策，{@code Spec} 只负责「有哪些分类、每类有哪些行」。
         * 把顺序写在这里，改动分组时不用动 {@code Spec}。
         */
        private fun sections(vararg ids: Int): Array<Array<Any?>> {
            val all = BmapSpec.cats()
            val out = java.util.ArrayList<Array<Any?>>()
            for (id in ids) {
                for (c in all) {
                    if (c.id != id) continue
                    val items = arrayOfNulls<PanelTypes.Item>(c.rows.size)
                    for (j in c.rows.indices) {
                        val row = c.rows[j]
                        items[j] = PanelTypes.Item(row.key.orEmpty(), row.title.orEmpty(), row.note)
                    }
                    out.add(arrayOf<Any?>(c.title, items.requireNoNulls()))
                }
            }
            return out.toTypedArray()
        }

        /**
         * 「净化」页的分段。
         *
         * <p>⚠ 这些开关都是**安装期门控**（{@code Cfg.installGate}）：关闭时对应实现根本不装进
         * 方法链 —— 这正是用户要的「关闭 = 不加载、零性能开销」，代价是需要重启宿主。
         * 每行不再重复这句，统一写在 [POWER_NOTE] 里。
         */
        private fun powerSections(): Array<Array<Any?>> {
            return arrayOf(
                arrayOf<Any?>(
                    "悬浮窗", arrayOf(
                        PanelTypes.Item(
                            FloatingConfig.K_FLOAT_ENABLED, "显示悬浮球",
                            "关掉后悬浮球和这个面板都会消失。要再打开：**长按底部「我的」标签约 1 秒**，" +
                                "面板会再次弹出，把这一项拨回开即可。"
                        )
                    )
                ),
                arrayOf<Any?>(
                    "推送", arrayOf(
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_PUSH_HARD_OFF,
                            "关闭推送长连接（硬档）",
                            "省电最明显。代价：百度推送消息（活动/提醒）收不到。"
                        ),
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_PUSH_OFF,
                            "关闭推送消息处理",
                            "不建连接、不处理推送消息，但不杀服务。"
                        ),
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_PUSH_KILL_OFF,
                            "关闭推送服务（激进档）",
                            "通道服务起来即退出。若发现耗电反而上升，先关这一项。"
                        )
                    )
                ),
                arrayOf<Any?>(
                    "后台与埋点", arrayOf(
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_BG_SERVICE_OFF,
                            "关闭后台常驻服务",
                            "定位/云控/统计等自启动服务不再常驻。"
                        ),
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_TIMER_OFF,
                            "关闭后台周期任务",
                            "AlarmManager / JobScheduler / WorkManager 的定时唤醒不再重排。"
                        ),
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_WIDGET_OFF,
                            "关闭桌面小组件刷新",
                            "只拦 APPWIDGET_UPDATE，添加/删除组件仍正常。"
                        ),
                        PanelTypes.Item(
                            io.github.ldxm666.bmapclean.Cfg.K_TRACK_OFF,
                            "关闭第三方埋点",
                            "统计 SDK 不初始化，不上报。"
                        )
                    )
                )
            )
        }

        /** 省电页的说明：这批开关「开 = 已拦截」，和别的页正好相反，不写清一定被误读。 */
        private const val POWER_NOTE =
            "这一页的开关：开 = 该行为已被拦截（默认全开）；关 = 恢复百度原生行为。" +
                "⚠ 改动后需**强制停止并重开百度地图**才生效（关闭时会彻底不加载对应实现，零性能开销）。"

        /**
         * True when a writable path is available inside the host process.
         *
         * <p>★ 2.3.0：原来写的是 {@code App.svc() != null} —— 那只是**框架服务通道**，内置版里恒为 null，
         * 于是面板把一份完全能写的配置显示成「只读」。现在与 [FloatingConfig.writePath] 同源：
         * 服务在，或者宿主本地预置能真实写入，都算可写。
         */
        @JvmStatic
        fun writable(): Boolean {
            return try {
                FloatingConfig.writable()
            } catch (ignored: Throwable) {
                false
            }
        }

        @JvmStatic
        fun read(key: String): Boolean {
            return try {
                // 百度's catalogue carries its own defaults (a few rows default to off), and the
                // hook side uses the same table -- so the panel and the hooks cannot disagree.
                val v = BmapApp.read(key, BmapSpec.defaultVisible(key))
                tracePanelRead(key, v)
                v
            } catch (t: Throwable) {
                BmapSpec.defaultVisible(key)
            }
        }

        /**
         * 面板**自己**读到什么 —— 每键每进程一行，上限 40 行。
         *
         * <p>存在的理由：{@code Cfg} 的 {@code event=cfg_r} 是读取侧的流水，但一条日志分不清
         * 是 hook 在读还是面板在读。用户的原话是「面板里关掉、重启又变回开」，所以修完之后
         * 必须有一条**从面板这一侧发出**的值，否则只能靠截图对账。
         */
        private fun tracePanelRead(key: String, v: Boolean) {
            if (PANEL_LOG.get() >= 40) return
            if (!PANEL_SEEN.add(key)) return
            if (PANEL_LOG.incrementAndGet() > 40) return
            io.github.ldxm666.bmapclean.Cfg.log(
                "event=cfg_panel_r pid=" +
                    android.os.Process.myPid() + " key=" + key + " v=" + v +
                    " backend=" + io.github.ldxm666.bmapclean.Cfg.backend()
            )
        }

        private val PANEL_LOG = java.util.concurrent.atomic.AtomicInteger()

        private val PANEL_SEEN: MutableSet<String> = java.util.Collections.synchronizedSet(
            java.util.HashSet<String>()
        )

        /**
         * The 地图标注 switch, read the way 百度's hooks read it.
         *
         * <p>百度's own default for this key is off (see {@code Spec.defaultVisible}), and the
         * native side needs the same answer the hooks use or the panel would lie.
         */
        @JvmStatic
        fun mapPoiVisible(): Boolean {
            return try {
                io.github.ldxm666.bmapclean.Cfg.visible(
                    io.github.ldxm666.bmapclean.Spec.K_MAP_POI,
                    io.github.ldxm666.bmapclean.Spec.defaultVisible(
                        io.github.ldxm666.bmapclean.Spec.K_MAP_POI
                    )
                )
            } catch (t: Throwable) {
                false
            }
        }

        @JvmStatic
        fun write(key: String, value: Boolean): Boolean {
            return try {
                BmapApp.write(key, value)
            } catch (ignored: Throwable) {
                false
            }
        }

        @JvmStatic
        fun invalidate() {
            // ★ 2.3.0：以前这里是空实现，注释说「hook 侧自己按 2 s TTL 重读，没有快照要丢」。
            // 但 Cfg 现在除了 2 s TTL 快照之外还**缓存了 {@code Flag} 的解析态**（安装期门控、
            // 省电开关都读它）。写完不 invalidate ⇒ 拨了开关，同一进程内的 Flag 还留着旧值，
            // 要等下一次 prefs 变更回调才刷 —— 表现就是「拨了没用，重启才好」。
            try {
                io.github.ldxm666.bmapclean.Cfg.invalidate()
            } catch (ignored: Throwable) {
            }
        }

        /**
         * Nothing to push: 百度 applies its switches when the affected view is built, and the
         * map-label switch explicitly needs the app restarted (its row says so).
         */
        @JvmStatic
        fun apply(key: String) {
        }
    }
}