package io.github.ldxm666.bmapclean

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

/**
 * 百度地图首页精简引擎。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么走「视图层按资源 id 名定位」而不是 hook 宿主方法
 * ══════════════════════════════════════════════════════════════════════════
 * 百度首页那几块（入口栏 / 工宫格 / 回家去公司）都是**原生布局**，
 * 但驱动它们的类名基本是混淆过的、方法名还会随版本重排（SplashAdManager.F/z 那种）。
 * 而资源 id 名字（new_route / component_container3 / home_info …）是百度自己写在
 * xml 里的语义名，**不参与混淆**，跨版本稳定得多 —— 所以主键取 id 名字。
 *
 * 触发方式：挂钩 Activity#onResume，resume 后按 {@link #PASSES} 的时间点复扫几遍。
 * 百度首页面板是异步搭起来的（数据回来才 addView），单次扫描抓不全，
 * 所以用有限次幂等复扫；每次扫描都先确认"这确实是首页"（ufo_root/home_panel 命中），
 * 不是首页就零成本退出。
 *
 * 失效安全：所有规则只做「找到就 GONE」，找不到/抛异常一律放行；
 * 读配置失败一律按「可见」处理 —— 模块失效时界面保持原样，不会清空到没法用。
 */
class HomeClean private constructor() {

    companion object {

        @Volatile
        private var installed = false

        private val UI = Handler(Looper.getMainLooper())

        /** resume 后的复扫时间点（ms）。首页面板异步搭建，一次扫不全。 */
        private val PASSES = longArrayOf(0, 120, 300, 650, 1200, 2000, 3200)

        /** 底部行程/天气卡是百度 Talos（类 React）渲染的，没有资源 id，只能按卡内文案 + 瀑布流结构定位 */
        private val WX_WORDS = arrayOf("天气", "行程助手", "重要行程放地图")

        @JvmStatic
        fun install(cl: ClassLoader) {
            if (installed) return
            installed = true
            try {
                val onResume: Method = Activity::class.java.getDeclaredMethod("onResume")
                H.hook(onResume, "bmapclean_onResume", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        val self = chain.getThisObject()
                        if (self is Activity) {
                            sAct = self
                            post(self)
                        }
                        return r
                    }
                })
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "install onResume failed: " + t)
            }
            installContextHook()
            installWatchdog()
            Cfg.log("HomeClean rules: bar=" + Spec.TABS.size + " tabs, tools=" + Spec.TOOLS.size
                    + " cells, hc=3, weather=2, feed=3, mine=6+4")
        }

        /**
         * 「我的」页 / 展开态信息流的触发点 —— **低频看门狗**。
         *
         * 为什么不能只挂生命周期：首页 / 我的 是**同一个 MapsActivity 内的切换**，
         * 切 tab 不触发 onResume。也试过挂 `View#setVisibility` 按 id 过滤，
         * 实测切到「我的」页时并不会在那个容器上调用 setVisibility（钩子装了、首火了，
         * 但一次都没命中目标 id）。
         *
         * 看门狗每 1s 只做 2 次 findViewById（我的页容器 / 信息流容器），
         * 只在「可见性由不可见变可见」的那一次执行完整规则 —— 平均开销可以忽略，
         * 也不会像 hook setVisibility 那样往热路径里塞钩子（减少被检测面）。
         */
        @Volatile
        private var sAct: Activity? = null
        @Volatile
        private var sMineVisible = false
        @Volatile
        private var sMineTicks = 0
        @Volatile
        private var sFeedVisible = false
        @Volatile
        private var sWatchdog = false

        private fun installWatchdog() {
            if (sWatchdog) return
            sWatchdog = true
            UI.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        val a = sAct
                        if (a != null && !a.isFinishing) watchPass(a)
                    } catch (ignored: Throwable) {}
                    UI.postDelayed(this, 1000)
                }
            }, 1500)
        }

        private fun watchPass(act: Activity) {
            val decor: View? = try {
                if (act.window == null) null else act.window.decorView
            } catch (t: Throwable) { return }
            if (decor == null) return

            // ① 「我的」页
            val uc: View? = Anchors.find(decor, Anchors.ID_USER_CENTER, Anchors.NUM_USER_CENTER)
            val ucVis = uc != null && uc.visibility != View.GONE
            if (ucVis && !sMineVisible) {
                sMineVisible = true
                sMineTicks = 0
                Cfg.log("mine page appeared -> apply rules")
                scheduleMine(uc!!)
            } else if (!ucVis) {
                sMineVisible = false
                sMineTicks = 0
            } else if (ucVis && Spec.mineEnabled() && sMineTicks < 60) {
                // 该页区块是**懒加载**的（热门活动在 2.6s 复扫窗口之后才渲染 → 实测漏关），
                // 所以在页面可见期间持续复扫 60 次（≈1 分钟）；已处理过的节点上是空操作，开销可忽略。
                sMineTicks++
                applyMine(uc!!)
            }

            // ② 展开态信息流（面板上拉后才可见）
            val feed: View? = Anchors.find(decor, Anchors.ID_FEED, Anchors.NUM_FEED)
            val feedVis = feed != null && feed.visibility != View.GONE
            if (feedVis && !sFeedVisible) {
                sFeedVisible = true
                if (Cfg.debug()) Cfg.log("feed visible -> apply feed rules")
                applyFeed(decor)
            } else if (!feedVis) {
                sFeedVisible = false
            }
        }

        private fun scheduleMine(container: View) {
            val passes = longArrayOf(0, 120, 320, 700, 1500, 2600)
            for (i in passes.indices) {
                UI.postDelayed(object : Runnable {
                    override fun run() {
                        try {
                            if (container.visibility == View.GONE) return
                            val n = applyMine(container)
                            if (n > 0 && Cfg.debug()) {
                                H.log(Log.INFO, MainHook.TAG, "mine re-apply hidden=" + n)
                            }
                        } catch (ignored: Throwable) {}
                    }
                }, passes[i])
            }
        }
        /**
         * 拿 Context 的唯一可靠路径。
         *
         * ⚠ **不要用 PackageReadyParam#getApplication()** —— 运行时的
         * XposedModuleInterface$PackageReadyParam 没有这个方法，一调就是
         * NoSuchMethodError（v0.1.1 实测，见 stub 里的说明）。
         *
         * Instrumentation#callApplicationOnCreate(Application) 在每个进程里
         * 恰好走一次，而且晚于 onPackageReady（钩子已经装好），参数就是 Application 本体。
         * 用它同时完成两件事：登记 Context + 发出「钩子已注入」回报。
         */
        private fun installContextHook() {
            try {
                val instr: Class<*> = Class.forName("android.app.Instrumentation")
                val m: Method = instr.getDeclaredMethod("callApplicationOnCreate", android.app.Application::class.java)
                H.hook(m, "bmapclean_appctx", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val a = chain.getArg(0)
                        if (a is android.content.Context) {
                            Report.attach(a)
                            Report.send(a, "hook", "钩子已注入百度地图主进程 · ok="
                                    + H.ok.get() + " miss=" + H.miss.get(), true)
                        }
                        return chain.proceed()
                    }
                })
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "install appctx failed: " + t)
            }
        }

        private fun post(act: Activity) {
            for (i in PASSES.indices) {
                val at = PASSES[i]
                UI.postDelayed(object : Runnable {
                    override fun run() { apply(act, at) }
                }, at)
            }
        }

        private fun apply(act: Activity?, at: Long) {
            try {
                if (act == null || act.isFinishing) return
                if (act.window == null) return
                val decor: View? = act.window.decorView
                if (decor == null) return

                // 「这一页是不是百度首页」——不是就直接退出，其它页面零开销
                val ufo: View? = Anchors.find(decor, Anchors.ID_UFO_ROOT, Anchors.NUM_UFO_ROOT)
                val panel: View? = Anchors.find(decor, Anchors.ID_HOME_PANEL, Anchors.NUM_HOME_PANEL)
                if (ufo == null && panel == null) return

                var n = 0
                n += applyBottomBar(decor)
                n += applyToolGrid(decor)
                n += applyHomeCompany(decor)
                n += applyWeather(decor)
                n += applyFeed(decor)
                n += applyMine(decor)
                n += applySearchChips(decor)
                dumpComponentIds(decor)   // TEMP probe

                if (Cfg.debug()) {
                    H.log(Log.INFO, MainHook.TAG, "apply@" + at + "ms act="
                            + act.javaClass.name + " hidden=" + n + " | " + Anchors.stats())
                }
                // 取证：链尾把整棵 decor 连同**文本**打进 logcat（dumpsys 无文本、uiautomator 卡在非 idle）
                if (Cfg.debug() && at >= PASSES[PASSES.size - 1]) {
                    TreeDump.dump(decor, "all")
                }
                if (at >= PASSES[PASSES.size - 1]) {
                    // 链尾把本次结果回报给设置页（广播，见 Report 的说明）
                    Report.send(act, "scan", "act=" + act.javaClass.simpleName
                            + " hidden=" + n
                            + " · anchors " + Anchors.stats(), false)
                }
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "apply failed @" + at + "ms: " + t)
            }
        }
        // ══════════════════════════════════════════════════ ① 底部五入口栏

        private val TAB_IDS = arrayOf(
                Anchors.ID_NEW_ROUTE, Anchors.ID_NEW_NEARBY, Anchors.ID_NEW_THIRD,
                Anchors.ID_NEW_FOURTH, Anchors.ID_NEW_USER,
        )
        private val TAB_NUMS = intArrayOf(
                Anchors.NUM_NEW_ROUTE, Anchors.NUM_NEW_NEARBY, Anchors.NUM_NEW_THIRD,
                Anchors.NUM_NEW_FOURTH, Anchors.NUM_NEW_USER,
        )
        private val TAB_PXY_IDS = arrayOf(
                Anchors.ID_PXY_ROUTE, Anchors.ID_PXY_NEARBY, Anchors.ID_PXY_THIRD,
                Anchors.ID_PXY_FOURTH, Anchors.ID_PXY_USER,
        )
        private val TAB_PXY_NUMS = intArrayOf(
                Anchors.NUM_PXY_ROUTE, Anchors.NUM_PXY_NEARBY, Anchors.NUM_PXY_THIRD,
                Anchors.NUM_PXY_FOURTH, Anchors.NUM_PXY_USER,
        )

        private fun applyBottomBar(decor: View): Int {
            var n = 0
            val barOn = Cfg.visible(Spec.K_BAR)

            // 整栏
            val bar: View? = Anchors.find(decor, Anchors.ID_NEW_BTNS, Anchors.NUM_NEW_BTNS)
            if (bar != null && !barOn && hide(bar)) n++

            // 逐项（栏被整块关掉时，逐项一律视为隐藏）
            var anyTabHidden = false
            for (i in 0 until minOf(Spec.TABS.size, TAB_IDS.size)) {
                val vis = barOn && Cfg.visible(Spec.K_TAB + Spec.TABS[i])
                if (vis) continue
                anyTabHidden = true
                val item: View? = Anchors.find(decor, TAB_IDS[i], TAB_NUMS[i])
                if (item != null && hide(item)) n++
                // 视觉隐藏后同步摘掉点击代理，避免"看不见但点得到"
                val proxy: View? = Anchors.find(decor, TAB_PXY_IDS[i], TAB_PXY_NUMS[i])
                if (proxy != null) hide(proxy)
            }

            // 隐藏后必须**均分重排**：ufo_root/new_btns 是 LinearLayout，5 个 tab 原本各占 1/5 固定宽，
            // 少一个就只在左侧留一块空白（用户实测："挤在最左边"）。给剩余项 weight=1 后自动铺满。
            if (barOn && anyTabHidden) reflow(bar as? ViewGroup)

            // 中央「长按说话」还有一份覆盖层（home_ai_container），与 new_third 同进同退
            val thirdVis = barOn && Cfg.visible(Spec.K_TAB + Spec.TABS[2])
            if (!thirdVis) {
                val ai: View? = Anchors.find(decor, Anchors.ID_HOME_AI_CONTAINER, Anchors.NUM_HOME_AI_CONTAINER)
                if (ai != null && hide(ai)) n++
            }
            return n
        }

        // ══════════════════════════════════════════════════ ② 首页工具宫格

        private val ROW_IDS = arrayOf(Anchors.ID_ROW1, Anchors.ID_ROW2, Anchors.ID_ROW3)
        private val ROW_NUMS = intArrayOf(Anchors.NUM_ROW1, Anchors.NUM_ROW2, Anchors.NUM_ROW3)

        private fun applyToolGrid(decor: View): Int {
            var n = 0
            val wrap: View? = Anchors.find(decor, Anchors.ID_TOOL_WRAP, Anchors.NUM_TOOL_WRAP)
            if (wrap == null) return 0

            if (!Cfg.visible(Spec.K_TOOLS)) {
                return if (hide(wrap)) 1 else 0
            }

            for (r in ROW_IDS.indices) {
                val rowView: View? = Anchors.find(wrap, ROW_IDS[r], ROW_NUMS[r])
                if (rowView !is ViewGroup) continue
                val row = rowView
                for (i in 0 until row.childCount) {
                    val cell = row.getChildAt(i)
                    val label = label(cell)
                    if (label == null || label.isEmpty()) continue   // 无文案的装饰格不动
                    if (Cfg.debug()) {
                        H.log(Log.INFO, MainHook.TAG, "toolGrid row" + (r + 1) + "[" + i + "]=" + label)
                    }
                    if (toolCellHidden(label) && hide(cell)) n++
                }
                // Packing belongs to the native model adapter, never a second geometry pass.
            }
            return n
        }

        /**
         * 宫格文案 → 配置键。服务端可能下发别名（例如「公交地铁」/「公共交通」），
         * 所以做双向 contains；未知文案一律返回 false（**保持可见**，失效安全）。
         *
         * <p>遍历的是 {@link Spec#toolLabels()}（静态清单 ∪ 已发现文案），
         * 与设置页生成开关用的清单**完全一致** —— 否则新发现文案会出现
         * 「设置页有开关、勾了不生效」。
         */
        private fun toolCellHidden(label: String): Boolean {
            if (label == "公交地铁") return !BaiduHooks.toolVisible(label)
            val labels = Spec.toolLabels()
            for (i in labels.indices) {
                val k = labels[i]
                if (k == label || label.contains(k) || k.contains(label)) {
                    return !Cfg.visible(Spec.K_TOOL + k)
                }
            }
            // 未知文案：保持可见，并回报给设置页补开关。
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "toolGrid unknown cell kept visible: " + label)
            BaiduHooks.reportUnknownTool(label)
            return false
        }

        // ══════════════════════════════════════════════════ ③ 回家 / 去公司

        private fun applyHomeCompany(decor: View): Int {
            // One reversible owner for native commute views. Text-only hiding used
            // to remove the Home setup prompt and never restore it after re-enabling.
            BaiduCommute.applyCurrent()
            return 0
        }

        // ══════════════════════════════════════════════════ ④ 天气 / 行程栏

        private fun applyWeather(decor: View): Int {
            var n = 0
            val all = Cfg.visible(Spec.K_WEATHER)

            // 地图右侧原生天气条（weather_limited）
            val wx: View? = Anchors.find(decor, Anchors.ID_WX_MAP, Anchors.NUM_WX_MAP)
            if (wx != null && !(all && Cfg.visible(Spec.K_WX_MAP)) && hide(wx)) n++

            // 底部行程/天气卡（Talos 渲染）
            if (!(all && Cfg.visible(Spec.K_WX_CARD))) n += hideWeatherCard(decor)
            return n
        }

        private fun hideWeatherCard(decor: View): Int {
            val feed: View? = Anchors.find(decor, Anchors.ID_FEED, Anchors.NUM_FEED)
            if (feed == null) return 0
            val card = findCardByText(feed, 0)
            if (card == null) return 0
            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "weather card hit: " + card.javaClass.name)
            }
            return if (hideCollapse(card)) 1 else 0
        }

        /** 在容器的可见子树里找"卡内文案命中天气关键词"的节点，返回它所属的瀑布流 item 根 */
        private fun findCardByText(v: View?, depth: Int): View? {
            if (v == null || depth > 40) return null
            try {
                if (v.visibility == View.GONE) return null
                if (v is TextView) {
                    val t = text(v)
                    if (t != null) {
                        for (i in WX_WORDS.indices) {
                            if (t.contains(WX_WORDS[i])) return feedItemRoot(v)
                        }
                    }
                    return null
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val r = findCardByText(v.getChildAt(i), depth + 1)
                        if (r != null) return r
                    }
                }
            } catch (ignored: Throwable) {}
            return null
        }
        // ══════════════════════════════════════════════════ ⑤ 推荐信息流（展开态）

        /**
         * 频道栏「推荐 / 看世界 / 成都市 …」+「优质内容精选」及推荐卡片。
         * 全部由 Baidu Talos（类 React）渲染 → **没有资源 id**，只能按
         * 「同容器内命中 ≥2 个文案锚点 + 容器高度足够小」这种结构约束定位，避免误伤卡片正文。
         */
        private fun applyFeed(decor: View): Int {
            val feed: View? = Anchors.find(decor, Anchors.ID_FEED, Anchors.NUM_FEED)
            if (feed == null) return 0

            val all = Cfg.visible(Spec.K_FEED)
            val chips = all && Cfg.visible(Spec.K_FEED_CHIPS)
            val quality = all && Cfg.visible(Spec.K_FEED_QUALITY)
            if (all && chips && quality) return 0

            if (!all) return if (hide(feed)) 1 else 0   // 整块信息流

            var n = 0
            if (!chips) n += hideChipRow(feed)
            if (!quality) n += hideFromQualityDown(feed)
            return n
        }

        /** 频道栏：找一个「高度小 + 子树里同时出现 ≥2 个频道锚点」的容器 */
        private fun hideChipRow(feed: View): Int {
            val row = findAnchorContainer(feed, Spec.CHIP_ANCHORS, 0, 320)
            if (row == null) {
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "chips: no container hit")
                return 0
            }
            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "chips hit " + row.javaClass.name
                        + " h=" + (row.bottom - row.top))
            }
            val dirty = java.util.ArrayList<ViewGroup>()
            val ok = collapse(row, dirty)
            for (i in dirty.indices) restackColumn(dirty[i])
            return if (ok) 1 else 0
        }

        /** 「优质内容精选」及其下方全部推荐卡片：从锚点所在瀑布流 item 起，往后整排摘除 */
        private fun hideFromQualityDown(feed: View): Int {
            val anchor = findByExactText(feed, Spec.QUALITY_ANCHOR, 0)
            if (anchor == null) {
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "quality: anchor not found")
                return 0
            }
            val item = feedItemRoot(anchor)
            if (item == null) {
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "quality: no WaterFallChildView ancestor")
                return 0
            }
            var n = 0
            val dirty = java.util.ArrayList<ViewGroup>()
            val p: ViewParent? = item.parent
            if (p is ViewGroup) {
                val idx = p.indexOfChild(item)
                for (i in idx until p.childCount) {
                    if (collapse(p.getChildAt(i), dirty)) n++
                }
            } else if (collapse(item, dirty)) {
                n = 1
            }
            // 推荐卡是瀑布流 item，同样不会自己塌陷 → 手工往上贴
            for (i in dirty.indices) restackColumn(dirty[i])
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "quality: removed " + n + " feed items")
            return n
        }

        /** DFS 找「子树里同时命中 >=2 个 $words（精确文本）+ 高度 <= maxDp」的最深容器 */
        private fun findAnchorContainer(v: View?, words: Array<String>, depth: Int, maxDp: Int): View? {
            if (v == null || depth > 30) return null
            try {
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val r = findAnchorContainer(v.getChildAt(i), words, depth + 1, maxDp)
                        if (r != null) return r
                    }
                }
                if (v.visibility == View.GONE) return null
                val h = v.bottom - v.top
                if (h <= 0 || h > dpPx(v, maxDp)) return null
                var hit = 0
                for (i in words.indices) if (subtreeHasExactText(v, words[i])) hit++
                return if (hit >= 2) v else null
            } catch (ignored: Throwable) {}
            return null
        }

        private fun subtreeHasExactText(v: View?, want: String): Boolean {
            try {
                if (v is TextView) {
                    val cs: CharSequence? = v.text
                    return cs != null && want == cs.toString().trim()
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        if (subtreeHasExactText(v.getChildAt(i), want)) return true
                    }
                }
            } catch (ignored: Throwable) {}
            return false
        }

        private fun findByExactText(v: View?, want: String, depth: Int): View? {
            if (v == null || depth > 40) return null
            try {
                if (v is TextView) {
                    val cs: CharSequence? = v.text
                    if (cs != null && want == cs.toString().trim()) return v
                    return null
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val r = findByExactText(v.getChildAt(i), want, depth + 1)
                        if (r != null) return r
                    }
                }
            } catch (ignored: Throwable) {}
            return null
        }

        // ══════════════════════════════════════════════════ ⑥ 「我的」页

        /**
         * 「我的」页（user_center_talos_container）。
         *
         * 结构实测（v0.1.6 probe，TREE all）：
         *   区块根 ReactViewGroup 0,2537-1079,3502
         *     └ ReactViewGroup 0,0-1079,965
         *         └ ReactViewGroup 21,11-1058,955
         *             └ 头部 ReactViewGroup 0,0-1037,84 → ReactTextView txt=热门语音
         * 全是 Talos，没有资源 id 也没有 WaterFallChildView，所以按
         * **精确文案 + 满宽区块**定位：标题往上找「宽度接近满宽、高度在合理区间的最外层祖先」。
         */
        private fun applyMine(decor: View): Int {
            // ── v0.4.0：视图层方案**整体停用**（保留代码仅作历史记录，不再执行）──────────────
            // 该页由 Talos/Yoga 自绘：GONE 不塌陷、removeView 不重排、手工 translationY 重排会
            // 把整页推乱 —— 三次回归（v0.1.7 白屏 / v0.1.8 误伤宫格 / v0.3.0 整页清空）已定型。
            // 现由 BaiduMineCards 过滤页面专属渲染副本；原始卡片列表和 serverData 保持不变。
            // 原生引擎负责重排，并支持配置事件恢复卡片。
            if (true) return 0
            if (!Spec.mineEnabled()) return 0      // 总闸（可在设置页关闭）
            val uc: View? = Anchors.find(decor, Anchors.ID_USER_CENTER, Anchors.NUM_USER_CENTER)
            if (uc == null || uc.visibility != View.VISIBLE) return 0   // 不是我的页（或不可见）

            val dirty = java.util.ArrayList<ViewGroup>()
            var n = 0
            // ① 强制清除（无开关）：广告 / 运营卡
            for (i in Spec.MINE_FORCE.indices) {
                val g = Spec.MINE_FORCE[i]
                n += if (g.size >= 2) hideByAnchors(uc, g, 250, dirty)
                     else hideSection(uc, g[0], 400, dirty)
            }
            // ② 逐项开关
            if (!Cfg.visible(Spec.K_MINE_GRID)) {
                n += hideByAnchors(uc, Spec.MINE_GRID_TEXTS, 6, 220, dirty)
            }
            if (!Cfg.visible(Spec.K_MINE_VOICE)) n += hideSection(uc, "热门语音", 400, dirty)
            if (!Cfg.visible(Spec.K_MINE_CAR)) n += hideSection(uc, "我的车", 400, dirty)
            if (!Cfg.visible(Spec.K_MINE_CARNAV)) n += hideSection(uc, "导航车标", 400, dirty)
            if (!Cfg.visible(Spec.K_MINE_SPORT)) n += hideSection(uc, "百度运动", 400, dirty)
            // 「全民共建」块的标题是图片徽章，TextView 里只有「反馈中心」——用错的锚点永远关不掉
            if (!Cfg.visible(Spec.K_MINE_BUILD)) n += hideSection(uc, "反馈中心", 400, dirty)

            // ③ 关键：Talos 不会自己重排，手工把后面的块往上贴，消除留白
            for (i in dirty.indices) restackColumn(dirty[i])
            return n
        }

        /** 组合出「除 requested 之外的其它锚点」；候选容器命中任一个就说明它是壳，拒绝 */
        private fun forbiddenFor(requested: Array<String>): Array<String> {
            val out = java.util.ArrayList<String>()
            collect(out, Spec.MINE_SECTION_ANCHORS, requested)
            collect(out, Spec.MINE_GRID_TEXTS, requested)
            return out.toTypedArray()
        }

        private fun collect(out: MutableList<String>, pool: Array<String>, requested: Array<String>) {
            for (i in pool.indices) {
                var skip = false
                for (j in requested.indices) {
                    if (pool[i] == requested[j]) { skip = true; break }
                }
                if (!skip && !out.contains(pool[i])) out.add(pool[i])
            }
        }

        private fun hideByAnchors(root: View, requested: Array<String>, maxDp: Int,
                                  dirty: MutableList<ViewGroup>?): Int {
            return hideByAnchors(root, requested, requested.size, maxDp, dirty)
        }

        /** 命中 ≥minHits 个 requested 锚点、不含任何禁词锚点、高度 ≤maxDp 的**最深**容器 → 摘除 */
        private fun hideByAnchors(root: View, requested: Array<String>, minHits: Int, maxDp: Int,
                                  dirty: MutableList<ViewGroup>?): Int {
            val forbid = forbiddenFor(requested)
            val c = searchSafe(root, requested, forbid, minHits, maxDp, 0)
            if (c == null) {
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "mine: no safe container for "
                        + requested[0] + " (need>=" + minHits + ")")
                return 0
            }
            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "mine hit [" + requested[0] + "] "
                        + c.javaClass.simpleName + " h=" + (c.bottom - c.top))
            }
            return if (collapse(c, dirty)) 1 else 0
        }

        private fun searchSafe(v: View?, req: Array<String>, forbid: Array<String>,
                               minHits: Int, maxDp: Int, depth: Int): View? {
            if (v == null || depth > 30) return null
            try {
                if (v is ViewGroup) {           // 先深后浅：取最深的满足者
                    for (i in 0 until v.childCount) {
                        val r = searchSafe(v.getChildAt(i), req, forbid, minHits, maxDp, depth + 1)
                        if (r != null) return r
                    }
                }
                if (v.visibility == View.GONE) return null
                val h = v.bottom - v.top
                if (h <= 0 || h > dpPx(v, maxDp)) return null
                var hits = 0
                for (i in req.indices) if (subtreeHasExactText(v, req[i])) hits++
                if (hits < minHits) return null
                for (i in forbid.indices) {
                    if (subtreeHasExactText(v, forbid[i])) return null   // 是壳，拒绝
                }
                return v
            } catch (ignored: Throwable) {}
            return null
        }

        /** 标题文案 → 满宽区块根（maxDp 上限 + tooBigToHide + 禁词消歧 三重保险） */
        private fun hideSection(root: View, title: String, maxDp: Int,
                                dirty: MutableList<ViewGroup>?): Int {
            val t = findByExactText(root, title, 0)
            if (t == null) {
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "mine: title not found " + title)
                return 0
            }
            val sec = sectionRootSafe(t, title, maxDp)
            if (sec == null) {
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "mine: no safe section for " + title)
                return 0
            }
            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "mine hit [" + title + "] "
                        + sec.javaClass.simpleName + " h=" + (sec.bottom - sec.top))
            }
            return if (collapse(sec, dirty)) 1 else 0
        }

        private fun sectionRootSafe(child: View, title: String, maxDp: Int): View? {
            val forbid = forbiddenFor(arrayOf(title))
            var best: View? = null
            var p: View? = child
            var guard = 0
            while (p != null && guard++ < 30) {
                val par = p.parent
                if (par !is View) break
                val parent = par
                val w = p.right - p.left
                val h = p.bottom - p.top
                val pw = parent.width
                if (pw > 0 && w >= (pw * 0.85).toInt()
                        && h > dpPx(p, 120) && h <= dpPx(p, maxDp) && !tooBigToHide(p)) {
                    var bad = false
                    for (i in forbid.indices) {
                        if (subtreeHasExactText(p, forbid[i])) { bad = true; break }
                    }
                    if (!bad) best = p      // 继续往上，取最外层**仍然安全**的
                }
                p = parent
            }
            return best
        }
        /**
         * 摘除区块，并把它所在的父容器登记进 $dirty（稍后统一做手工竖向重排）。
         *
         * ⚠ Talos（类 React / Yoga 自绘）设 GONE + 高度 0 **不塌陷**，只会留一块空白；
         * 所以这里优先**从父容器移除**，再由 {@link #restackColumn} 把后续兄弟块贴上来。
         */
        // ══════════════════════════════════════════ ⑦ 搜索框下的快捷入口

        /**
         * 搜索框下面那排快捷入口的锚点文案。
         *
         * 这排是**服务端下发**的，文案会变（美食 / 加油站 / 景点 / 厕所 / 酒店 / 超市…），所以
         * 不按某一个固定 id 抓，而是"同一容器里命中 ≥3 个"才算 —— 与首页其它区块同一套判定方式，
         * 单条命中不足以确认，避免误伤搜索结果页里的同类词。
         */
        private val SEARCH_CHIP_WORDS = arrayOf(
                "美食", "加油站", "景点", "厕所", "酒店", "超市", "银行", "充电站", "停车场",
        )

        private fun applySearchChips(decor: View): Int {
            // 开关语义是"显示"，默认开 —— 所以只有用户主动关掉才会去动它。
            if (Spec.effectiveVisible(Spec.K_SEARCH_CHIPS, Spec.HOOK_READ)) return 0
            try {
                // ① 先按资源 id。这排是 Talos（Yoga 自绘）渲染，文本不进 View 树 ⇒
                //    下面那套「文案命中 ≥3 个」的启发式在真机上永远命中 0 个，
                //    开关关掉也没反应。id 名从 22.0.0 到 22.1.0 一直没变，按 id 才落地。
                val byId = BaiduHooks.hideSearchLine2(decor)
                if (byId > 0) {
                    if (Cfg.debug()) Cfg.log("search chips hidden by id n=" + byId)
                    return byId
                }
                // ② 文案兜底（旧路径）—— 只在 id 被改名时才会走到这里。
                val row = findAnchorContainer(decor, SEARCH_CHIP_WORDS, 0, 140)
                if (row == null) return 0
                val dirty = java.util.ArrayList<ViewGroup>()
                if (!collapse(row, dirty)) return 0
                for (i in dirty.indices) restackColumn(dirty[i])
                if (Cfg.debug()) Cfg.log("search chips hidden by text " + row.javaClass.simpleName)
                return 1
            } catch (t: Throwable) {
                return 0
            }
        }

        /**
         * TEMP 探针：把首页所有 container/component 的 **id 名 + 纵向位置 + 高度** 打进 logcat。
         *
         * 那排快捷入口既不在 ViewBinding 槽位里，文本也不在无障碍树里 —— 说明它是 Talos（Yoga
         * 自绘）的 component_containerN。模块已知 3=工具宫格 / 5=通勤 / 6=信息流，缺的正是它，
         * 所以直接把全部 container 的真实 id 与高度列出来定位。确认后删掉。
         */
        private var sIdProbed = false

        private fun dumpComponentIds(decor: View) {
            if (sIdProbed) return
            sIdProbed = true
            try {
                val out = java.util.ArrayList<String>()
                collectIds(decor, out, 0)
                H.log(Log.INFO, MainHook.TAG, "idprobe: " + out)
            } catch (ignored: Throwable) {}
        }

        private fun collectIds(v: View?, out: MutableList<String>, depth: Int) {
            if (v == null || depth > 40 || out.size > 140) return
            try {
                val id = v.id
                if (id != 0 && v.visibility != View.GONE) {
                    var name: String? = null
                    try { name = v.resources.getResourceEntryName(id) } catch (ignored: Throwable) {}
                    if (name != null && (name.contains("container") || name.contains("component")
                            || name.contains("search"))) {
                        out.add(name + "@top" + v.top + "h" + v.height)
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) collectIds(v.getChildAt(i), out, depth + 1)
                }
            } catch (ignored: Throwable) {}
        }

        private fun collapse(v: View?, dirty: MutableList<ViewGroup>?): Boolean {
            if (v == null) return false
            try {
                val p: ViewParent? = v.parent
                val parent = if (p is ViewGroup) p else null
                if (parent != null && parent.indexOfChild(v) >= 0) {
                    parent.removeView(v)
                    parent.requestLayout()
                    if (dirty != null && !dirty.contains(parent)) dirty.add(parent)
                    return true
                }
            } catch (ignored: Throwable) {}
            return hideCollapse(v)
        }
        /**
         * 手工竖向重排：把容器里剩余可见子项**按原间距**依次贴紧。
         *
         * 只改 `translationY`（绘制与触摸都跟着走），**不改尺寸** —— 所以不会像上次对
         * ShortCutRow 改 width 那样把触发区撑大 / 让行串位。
         * 每次都用 layout 坐标（getTop/getHeight 不受 translation 影响）重新算，天然幂等，
         * 引擎重新布排之后下一次复扫会自动纠正。
         */
        private fun restackColumn(g: ViewGroup?) {
            if (g == null) return
            try {
                var cursor = Int.MIN_VALUE
                var prevVisible: View? = null
                var moved = 0
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c.visibility == View.GONE) continue
                    val h = c.height
                    if (h <= 0) continue                     // 还没量到尺寸，跳过
                    if (cursor == Int.MIN_VALUE) {
                        cursor = c.top                  // 第一个可见项留在原位
                    } else if (prevVisible != null) {
                        // 保留引擎原本的块间距（用 layout 坐标算，与 translation 无关）
                        val gap = c.top - prevVisible.bottom
                        cursor += if (gap > 0) gap else 0
                    }
                    c.translationY = (cursor - c.top).toFloat()
                    cursor += h
                    prevVisible = c
                    moved++
                }
                if (Cfg.debug() && moved > 1) {
                    H.log(Log.INFO, MainHook.TAG, "restackColumn " + g.javaClass.simpleName
                            + " visibleItems=" + moved)
                }
            } catch (ignored: Throwable) {}
        }

        /**
         * 隐藏「区块级」容器前的**最后一道保险**。
         *
         * v0.1.7 事故：`sectionRoot` 的阈值单位写错（按 px 推导却用 dp 换算比较），
         * 结果把整页 TalosRootView（h=2240）判成"小于上限"，直接摘掉 → 百度地图整页白屏。
         * 从此所有区块级隐藏都必须过这一关：**高度超过屏幕 45% 的容器一律不碰**。
         */
        private fun tooBigToHide(v: View): Boolean {
            try {
                val h = v.bottom - v.top
                val screen = v.resources.displayMetrics.heightPixels
                if (h <= 0) return true                 // 尺寸拿不到就不动手
                if (h >= (screen * 0.45).toInt()) {
                    H.log(Log.WARN, MainHook.TAG, "refuse to hide oversized block h=" + h
                            + " screen=" + screen + " cls=" + v.javaClass.name)
                    return true
                }
                return false
            } catch (t: Throwable) {
                return true                             // 失效安全：宁可留着
            }
        }

        /** 摘除区块（含最后保险） */
        private fun hideBlock(v: View?): Boolean {
            if (v == null || tooBigToHide(v)) return false
            return hideCollapse(v)
        }

        /**
         * 标题文案 → 往上找「区块根」：高度在 (120dp, 600dp]、宽度接近满宽的**最外层**祖先。
         *
         * ⚠ 阈值必须当 dp 用（过 dpPx 换算）。600dp ≈ 1800px 是实测值：
         *   区块实测 965px（热门语音）、500px（宫格）、400px（出行保/借钱行）都落在区间内；
         *   整页根 2240px（=747dp）> 600dp，被排除 —— 这一步就是白屏事故的修复点。
         */
        private fun sectionRoot(child: View, maxDp: Int): View? {
            var best: View? = null
            var p: View? = child
            var guard = 0
            while (p != null && guard++ < 30) {
                val par = p.parent
                if (par !is View) break
                val parent = par
                val w = p.right - p.left
                val h = p.bottom - p.top
                val pw = parent.width
                if (pw > 0 && w >= (pw * 0.85).toInt()
                        && h > dpPx(p, 120) && h <= dpPx(p, maxDp) && !tooBigToHide(p)) {
                    best = p      // 继续往上，取最外层满足条件的
                }
                p = parent
            }
            return best
        }

        /** 深度优先找「子树同时包含全部 $words（精确文本）+ 自身高度 <= maxDp」的最深容器 */
        private fun findContainerWithAll(v: View?, words: Array<String>, depth: Int, maxDp: Int): View? {
            if (v == null || depth > 30) return null
            try {
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val r = findContainerWithAll(v.getChildAt(i), words, depth + 1, maxDp)
                        if (r != null) return r
                    }
                }
                if (v.visibility == View.GONE) return null
                val h = v.bottom - v.top
                if (h <= 0 || h > dpPx(v, maxDp)) return null
                for (i in words.indices) {
                    if (!subtreeHasExactText(v, words[i])) return null
                }
                return v
            } catch (ignored: Throwable) {}
            return null
        }

        // ══════════════════════════════════════════════════ 均分重排

        /**
         * 隐藏格子/入口后把同行剩余项**均分铺满**（避免留白）。
         *
         * ⚠ 只对 LinearLayout（含子类）动手，并且靠 weight + **weightSum** 两个一起改才生效：
         *   Baidu 底栏 new_btns 带固定 weightSum=5，只给 weight=1 的话分母还是 5，
         *   两个 tab 各占 1/5 宽 → "挤在最左边"（用户实测，已修）。
         *
         * ⚠⚠ 对**自定义 ViewGroup**（如 ShortCutRow）**不做重排**。v0.1.7 事故：
         *   之前对 ShortCutRow 按「容器宽/可见数」改写子项 width，实测后果是
         *   ①被隐藏格子的相邻格触发区被撑大（点实时公交会连带触发周围区域）；
         *   ②行与行之间串位（左列勾开的功能跑到第三列才显示）。
         *   也就是说这类容器的命中测试/行排布并不吃子项 width，改它只会把布局逻辑弄坏。
         *   宫格的留白只能靠宿主自己的重排，本模块不碰 —— 宁可留白，不可改坏。
         */
        private fun reflow(group: ViewGroup?) {
            if (group == null) return
            if (group !is android.widget.LinearLayout) {
                if (Cfg.debug()) {
                    H.log(Log.INFO, MainHook.TAG, "reflow skipped (custom ViewGroup): "
                            + group.javaClass.name)
                }
                return
            }
            try {
                var visible = 0
                var hidden = 0
                for (i in 0 until group.childCount) {
                    if (group.getChildAt(i).visibility == View.GONE) hidden++
                    else visible++
                }
                if (hidden == 0 || visible == 0) return

                val ll = group
                for (i in 0 until ll.childCount) {
                    val c = ll.getChildAt(i)
                    if (c.visibility == View.GONE) continue
                    val lp = c.layoutParams
                    if (lp !is android.widget.LinearLayout.LayoutParams) continue
                    val p = lp
                    if (p.width != 0 || p.weight != 1f) {
                        p.width = 0
                        p.weight = 1f
                        c.layoutParams = p
                    }
                }
                if (ll.weightSum != visible.toFloat()) ll.weightSum = visible.toFloat()
                ll.requestLayout()
                if (Cfg.debug()) {
                    H.log(Log.INFO, MainHook.TAG, "reflow LinearLayout visible=" + visible
                            + " hidden=" + hidden + " w=" + ll.width
                            + " weightSum=" + ll.weightSum)
                }
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "reflow failed: " + t)
            }
        }

        private fun dpPx(v: View, dp: Int): Int {
            try {
                return (dp * v.resources.displayMetrics.density).toInt()
            } catch (t: Throwable) {
                return dp * 3
            }
        }

        /** 从命中文案往上找瀑布流 item 根（Talos 的 item 根类名带 WaterFallChildView）。 */
        private fun feedItemRoot(v: View?): View? {
            var p = v
            var guard = 0
            while (p != null && guard++ < 40) {
                val cn = p.javaClass.name
                if (cn.contains("WaterFallChildView")) return p
                val par = p.parent
                p = if (par is View) par else null
            }
            return null
        }

        // ══════════════════════════════════════════════════ 工具方法

        /** 摘除（GONE）。返回 true 表示本次真的改了状态（幂等性判据）。 */
        private fun hide(v: View?): Boolean {
            if (v == null) return false
            try {
                if (v.visibility == View.GONE) return false
                v.visibility = View.GONE
                return true
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * 摘除并压塌高度。
         * Talos 的瀑布流/绝对定位容器不会因为子节点 GONE 就塌陷，
         * 不压高度会留一个空洞。
         */
        private fun hideCollapse(v: View?): Boolean {
            if (v == null) return false
            try {
                var changed = false
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    changed = true
                }
                val lp = v.layoutParams
                if (lp != null && lp.height != 0) {
                    lp.height = 0
                    v.layoutParams = lp
                    changed = true
                }
                return changed
            } catch (t: Throwable) {
                return false
            }
        }

        /** 按精确文案摘除子树里所有命中的 TextView（用于「去设置」这类无独立 id 的重复项） */
        private fun hideByExactText(root: View?, want: String): Int {
            var n = 0
            try {
                if (root is TextView) {
                    val t = text(root)
                    if (want == t && hide(root)) n++
                    return n
                }
                if (root is ViewGroup) {
                    for (i in 0 until root.childCount) n += hideByExactText(root.getChildAt(i), want)
                }
            } catch (ignored: Throwable) {}
            return n
        }

        /** 取节点子树里第一个非空 TextView 文案（宫格格子 = FrameLayout(image + text)） */
        private fun label(v: View?): String? {
            try {
                if (v is TextView) return text(v)
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val s = label(v.getChildAt(i))
                        if (s != null && s.length > 0) return s
                    }
                }
            } catch (ignored: Throwable) {}
            return null
        }

        private fun text(t: TextView): String? {
            try {
                val c: CharSequence? = t.text
                return c?.toString()?.trim()
            } catch (e: Throwable) {
                return null
            }
        }
    }
}
