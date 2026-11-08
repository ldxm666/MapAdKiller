package io.github.ldxm666.tmapclean

import android.util.Log
import android.view.View

import io.github.libxposed.api.XposedInterface

/**
 * 底部入口栏（首页 / 探索 / 行程 / 我的）逐项开关。
 *
 * <h3>锚点（实证：真机 11.7.0 base.apk）</h3>
 * <pre>
 * 源头（页 + 标签一起定义在这里）：
 *   com.tencent.map.ama.mainpage.frame.TabFrameInfo#setTabPages(java.util.List)   classes18.dex
 *       ← 由 navisdk.a.etx.a() / etx.b() 灌入（MapStateHome#addMainPageView）
 *   元素 com.tencent.map.ama.mainpage.frame.TabFrameInfo$TabPageInfo
 *       <init>(TabFrameInfo$Page, TabFrameInfo$Tab)V   code_off=0x4ed37c
 *         [3] iput TabPageInfo->page:TabFrameInfo$Page
 *         [5] iput TabPageInfo->tab:TabFrameInfo$Tab
 *       → 页名在 page 里（Page->pageName）
 *
 * 底栏装配（11.7.0 的真实链路，全部在 classes14/18.dex 里核对过）：
 *   MapStateHome#addMainPageView()V                     classes18.dex 0x3fe324
 *       [32] etx.a() → TabFrameInfo#setTabPages(List)
 *       [53] new-instance navisdk.a.exl   ← eza 接口的唯一实现（标签视图工厂）
 *       [127] exy$a#build() → exy#a(ViewGroup)
 *           → exx#a(TabFrameInfo,TabFrameInfo,I) → exx#a(TabFrameInfo)Z
 *               → eyy#a(List)Z
 *                   → eza#a(List,TabPageGroup$a)   ← 虚分派到 exl
 *                       → LiquidTabBarGroup#setTabViews(List)   classes14.dex 0x270cf0
 *               → GradientTabBarContainer#setInnerTabGroupView(eza$a)
 *   LiquidTabBarGroup#onClick(View)                     classes18.dex 0x4f4a34
 *       [30] LinearLayout.indexOfChild(View)I   ← 页号 = 子视图索引
 * </pre>
 *
 * <h3>⚠ 这里连踩三个坑，都跟"删还是藏"有关</h3>
 *
 * <b>坑一：只删底栏视图 → 页号错位。</b>
 * 第一版在 {@code setTabViews} 的入参上做减法。界面看着对，但 {@code onClick} 用
 * {@code LinearLayout.indexOfChild(view)} 当页号 —— 摘掉「探索 / 行程」后点「我的」落到索引 1，
 * 切到的是<b>探索</b>页。用户原话："我的页我保留了，但是打开的是其他已隐藏页。"
 *
 * <b>坑二：只把视图 GONE 掉 → 选中胶囊背景填充不对。</b>
 * 改成"保留元素、就地 GONE + weight=0"之后页号是对了，但底栏很难看。原因在
 * {@code LiquidTabBarGroup.h()V}（code_off=0x4f48dc）：
 * <pre>
 *   [ 4] LinearLayout-&gt;getChildCount()I     ← 数的是**孩子数**，GONE 的孩子照样算
 *   [11] getWidth()I
 *   [21] getSelectedTabWeight()F
 *   [25] 乘法/除法 → 胶囊宽度 = getWidth() / childCount * 选中权重
 * </pre>
 * GONE 的孩子仍是 {@code d} 的孩子：childCount 还是 4，但可见宽度只由 2 个分 →
 * 胶囊按 1/4 算 → "背景没有合适填充"。
 *
 * <b>坑三（本次修的）：钩子装晚了 0.85 秒，上面两条规则一条都没跑。</b>
 * 真机 11.7.0 冷启动时间线：
 * <pre>
 * 01:02:32.221  TMapClean: event=module_loaded
 * 01:02:32.617  MapCleanFloat: eligible activity com.tencent.map.WelcomeActivity
 * 01:02:33.468  TMapClean: event=install_begin pkg=com.tencent.map via=self
 * </pre>
 * 底栏是在 Activity 建内容视图时（32.6 秒）<b>一次性</b>装配的，
 * 而腾讯侧钩子到 33.468 才装上 —— 于是 logcat 里只有 {@code HOOKED tmap_tab_*}，
 * 永远没有 {@code HIT tmap_tab_*}，开关写进去了也没人读。
 * 修法见 {@code MainHook#installEarlyContextHook()}：模块加载即挂
 * {@code Instrumentation#callApplicationOnCreate}，把安装点提前到任何 Activity 之前。
 *
 * <h3>结论</h3>
 * ① 过滤 {@code TabFrameInfo#setTabPages} 的入参 → <b>逐项</b>隐藏时页列表与标签定义一起变少，索引天然对齐；
 * ② 过滤 {@code setTabViews} 的入参 → {@code d} 的 childCount 变成 N'，胶囊几何正确；
 * ③ 整栏总开关<b>不走</b>①②，而是把容器 {@code GONE}：清空页模型会让首页一个入口都不剩
 *    （{@code MapStateHome#getPageIndex} 按索引找页），风险远大于收益；
 * ④ {@code onClick} 仍留一道保险（认得出的隐藏页直接吞掉）。
 */
object TmapTabBar {

    private const val TAG_ID = "tmap_tab"

    /** 标签页定义源头 */
    private const val TAB_FRAME_INFO =
        "com.tencent.map.ama.mainpage.frame.TabFrameInfo"

    /** 标签栏实现（两个都挂；不同版本 / 不同机型走不同分支） */
    private val BARS = arrayOf(
        "com.tencent.map.ama.mainpage.frame.tab.LiquidTabBarGroup",
        "com.tencent.map.ama.mainpage.frame.tab.TabPageGroup",
    )

    /**
     * 外层包装容器：11.7.0 里真正的"整栏"是它，{@code LiquidTabBarGroup} 只是它内部的
     * 那个 tab group（{@code GradientTabBarContainer#setInnerTabGroupView(eza$a)}）。
     * 整栏隐藏必须连它一起 GONE，否则外面那层壳还在，底栏位置会留一条空白。
     */
    private const val CONTAINER =
        "com.tencent.map.ama.mainpage.frame.tab.GradientTabBarContainer"

    /**
     * 页名兜底：{@code getPage()} 读不到时按视图类名认标签。
     * 类名由腾讯自己命名（非混淆），实证自 11.7.0 classes18.dex。
     */
    private val CLASS_HINTS = arrayOf(
        arrayOf("NewLiquidHomeTabView", "homePage"),
        arrayOf("HomeTabView", "homePage"),
        arrayOf("NewLiquidExploreTabView", "explorePage"),
        arrayOf("ExploreTabView", "explorePage"),
        arrayOf("NewLiquidPlanTabView", "planPage"),
        arrayOf("RouteTabView", "planPage"),
        arrayOf("NewLiquidMeTabView", "mePage"),
        arrayOf("MeTabView", "mePage"),
        arrayOf("NearByTabView", "nearbyPage"),
    )

    /**
     * 见过的底栏容器（弱引用）。
     *
     * <p>底栏一生只装配一次，而面板开关随时会被拨动。[refresh] 靠这份清单
     * 就地改可见性，用户不必重进地图才看到效果。
     */
    private val BARS_SEEN = java.util.ArrayList<java.lang.ref.WeakReference<View>>()

    @JvmStatic
    fun install(cl: ClassLoader) {
        installSourceFilter(cl)
        installBarFilter(cl)
        installContainerHook(cl)
    }

    /**
     * ① 源头过滤：{@code TabFrameInfo#setTabPages(List)}。
     *
     * <p>在这里去掉，页列表（{@code getPages/getTabPages/getTabs}）和标签定义一起变少，
     * 下游按索引找页的地方（{@code MapStateHome.getPageIndex} 等）自然对得上。
     */
    private fun installSourceFilter(cl: ClassLoader) {
        val tfi = H.cls(cl, TAB_FRAME_INFO) ?: return
        H.hookSig(tfi, "setTabPages", TAG_ID + "_source", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val arg = chain.getArg(0)
                if (arg !is List<*>) return chain.proceed()
                val selected = selectedPages(arg)
                val args = chain.getArgs().toTypedArray()
                args[0] = selected
                return chain.proceed(args)
            }
        }, java.util.List::class.java)
    }

    /** ② 底栏过滤：{@code setTabViews(List)}。去掉之后 childCount 才是可见数，胶囊几何才对。 */
    private fun installBarFilter(cl: ClassLoader) {
        for (i in BARS.indices) {
            val bar = BARS[i]
            val c = H.cls(cl, bar) ?: continue
            val shortName = bar.substring(bar.lastIndexOf('.') + 1)

            H.hookSig(c, "setTabViews", TAG_ID + "_views_" + shortName,
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val arg = chain.getArg(0)
                        val selected = if (arg is List<*>) selectedViews(arg) else null
                        val result = if (selected == null) chain.proceed() else {
                            val args = chain.getArgs().toTypedArray()
                            args[0] = selected
                            chain.proceed(args)
                        }
                        // 整栏总开关：装配完再压一次可见性，保证"关掉即消失"。
                        applyBarVisibility(chain.getThisObject())
                        if (arg is List<*>) for (item in arg) {
                            val view = item as? View ?: continue
                            val page = pageOfView(view)
                            if (page == "homePage" || page == "mePage") TmapEntry.bind(view)
                        }
                        return result
                    }
                }, java.util.List::class.java)

            H.hookSig(c, "onClick", TAG_ID + "_click_" + shortName,
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val v = chain.getArg(0)
                        if (v is View && hidden(pageOfView(v))) return null
                        return chain.proceed()
                    }
                }, android.view.View::class.java)
        }
    }

    /**
     * ③ 外层容器：{@code GradientTabBarContainer#setInnerTabGroupView(eza$a)}。
     *
     * <p>整栏 GONE 挂在这里最稳 —— 它比内层 {@code LiquidTabBarGroup} 更外层，
     * 一起隐藏之后底栏不会留一条空壳。两层都登记，[refresh] 一起压。
     */
    private fun installContainerHook(cl: ClassLoader) {
        val c = H.cls(cl, CONTAINER) ?: return
        H.hookAll(c, "setInnerTabGroupView", TAG_ID + "_container",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    applyBarVisibility(chain.getThisObject())
                    return result
                }
            })
    }

    /**
     * 把整栏总开关压到某个底栏容器上。
     *
     * <p>⚠ <b>装配期不能直接改可见性。</b>真机实测（冷启动 + 总开关关）：
     * 在 {@code setTabViews} / {@code setInnerTabGroupView} 里 GONE 掉底栏，
     * 首页整块不出现 —— 搜索框、工具宫格一起消失，屏幕只剩地图；把开关再打开，
     * 首页又整块回来。也就是说首页的布局依赖"底栏当时可见"。
     * （只 GONE 内层 {@code LiquidTabBarGroup} 也一样，所以不是藏错了层。）
     *
     * <p>所以装配期只登记，真正压可见性交给 [refresh]：它由
     * {@code MainHook.REFRESH} 在 {@code Activity#onResume} 与配置变更之后调用，
     * 那时首页已经装完 —— 与"运行中拨开关"完全同一条路径，那条路径真机验证是好的。
     * 已经 attach 的（重建场景）就地压一次，省掉一帧。
     */
    private fun applyBarVisibility(bar: Any?) {
        if (bar !is View) return
        val view = bar
        remember(view)
        try {
            if (view.isAttachedToWindow) setBarVisibility(view)
        } catch (ignored: Throwable) {
        }
    }

    /** 真正改可见性；失效安全：配置读不到时保持显示。 */
    private fun setBarVisibility(view: View) {
        val show = Cfg.visible(Spec.K_BAR, true)
        try {
            val want: Int
            if (!show) {
                if (view.visibility == View.GONE) return
                BAR_VISIBILITY[view] = view.visibility
                want = View.GONE
            } else {
                val original = BAR_VISIBILITY.remove(view) ?: return
                if (view.visibility != View.GONE) return
                want = original
            }
            view.visibility = want
            H.log(
                Log.INFO, MainHook.TAG, "tmap_tab: " + view.javaClass.simpleName
                        + " bar=" + show + " -> " + (if (show) "VISIBLE" else "GONE")
            )
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "tmap_tab: visibility failed " + t)
        }
    }

    private val BAR_VISIBILITY = java.util.WeakHashMap<View, Int>()

    private fun selectedPages(infos: List<*>): List<*> {
        val selected = ArrayList(infos)
        filterTabPageInfos(selected)
        return selected
    }

    private fun selectedViews(views: List<*>): List<*> {
        val selected = ArrayList(views)
        filterTabViews(selected)
        return selected
    }

    /** 面板改开关 / 页面 onResume 后立刻生效（{@code MainHook.REFRESH} 已经在主线程上）。 */
    @JvmStatic
    fun refresh() {
        synchronized(BARS_SEEN) {
            var i = BARS_SEEN.size - 1
            while (i >= 0) {
                val view = BARS_SEEN[i].get()
                if (view == null) {
                    BARS_SEEN.removeAt(i)
                } else {
                    setBarVisibility(view)
                }
                i--
            }
        }
    }

    private fun remember(view: View) {
        synchronized(BARS_SEEN) {
            for (i in BARS_SEEN.indices) {
                if (BARS_SEEN[i].get() === view) return
            }
            BARS_SEEN.add(java.lang.ref.WeakReference<View>(view))
        }
    }

    /** 从私有副本摘掉要隐藏的页，宿主缓存的完整列表保持不变。 */
    @Suppress("UNCHECKED_CAST")
    private fun filterTabPageInfos(infos: List<*>) {
        // ⚠ 整栏总开关**不**在这里做减法。把页列表清空 = 首页一个入口都不剩，
        // 而 MapStateHome / exy 都是按这份列表建 Fragment 与页索引的，
        // 风险远大于收益。整栏走 applyBarVisibility() 的容器 GONE。
        val mutable = infos as MutableList<Any?>
        var seen = 0
        var removed = 0
        var unresolved = 0
        try {
            val it = mutable.iterator()
            while (it.hasNext()) {
                val info = it.next()
                seen++
                val page = pageOfInfo(info)
                if (page.length == 0) {
                    unresolved++              // 认不出 → 保留（失效安全）
                    continue
                }
                if (!Cfg.tabVisible(page)) {
                    it.remove()
                    removed++
                }
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "tmap_tab: source filter failed " + t)
        }
        Diag.note("tab_unknown", unresolved.toString())
        Diag.count("tab_source", seen, removed)
    }

    /** 从标签视图列表里摘掉要隐藏的标签（childCount 因此等于可见数）。 */
    @Suppress("UNCHECKED_CAST")
    private fun filterTabViews(views: List<*>) {
        val mutable = views as MutableList<Any?>
        var seen = 0
        var removed = 0
        try {
            val it = mutable.iterator()
            while (it.hasNext()) {
                val v = it.next()
                seen++
                val page = pageOfView(v)
                if (page.length == 0) continue          // 认不出 → 保留
                if (!Cfg.tabVisible(page)) {
                    it.remove()
                    removed++
                }
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "tmap_tab: view filter failed " + t)
        }
        Diag.count("tab", seen, removed)
    }

    /**
     * {@code TabPageInfo} → 页名。
     *
     * <p>字段名实证自 {@code TabPageInfo.<init>(Page, Tab)} 的两条 iput：{@code page} / {@code tab}。
     * 页名再往 {@code Page} 里找 —— 11.6.0 的 {@code exh.a(List)} 读的是 {@code Page->pageName}，
     * 这里按候选名逐个试（都是<b>按名访问</b>，不是遍历字段猜），认不出就返回空串由调用方保留。
     */
    @JvmStatic
    fun pageOfInfo(info: Any?): String {
        if (info == null) return ""
        val page = H.field(info, "page")
        var name = firstString(page, "pageName", "name", "page")
        if (name.length > 0) return name
        val tab = H.field(info, "tab")
        name = firstString(tab, "pageName", "tabPage", "name")
        if (name.length > 0) return name
        return ""
    }

    private fun firstString(owner: Any?, vararg names: String): String {
        if (owner == null) return ""
        for (i in names.indices) {
            val v = H.field(owner, names[i])
            if (v is String && v.length > 0) return v
        }
        return ""
    }

    /**
     * 标签视图 → 页名。先读应用自己的 {@code getPage()}（权威），读不到再按类名认（兜底）。
     */
    @JvmStatic
    fun pageOfView(view: Any?): String {
        if (view == null) return ""
        val p = H.call(view, "getPage")
        if (p is String && p.length > 0) return p
        val simple = view.javaClass.simpleName
        for (i in CLASS_HINTS.indices) {
            if (CLASS_HINTS[i][0] == simple) return CLASS_HINTS[i][1]
        }
        return ""
    }

    /**
     * 该页名是否应当隐藏（**只看逐项开关**）。
     *
     * <p>⚠ 这里以前还有一句 {@code if (!Cfg.visible(K_BAR, true)) return true;} ——
     * 整栏总开关一关，[hidden] 对<b>所有</b>页都返回 true，于是
     * {@code onClick} 那道保险会把<b>每一次</b>点击都吞掉。真机后果：
     * 应用自己在启动时用 {@code LiquidTabBarGroup#onClick} 选中初始页，
     * 那一下也被吞了 —— 首页永远选不上，屏幕只剩地图（搜索框 / 工具宫格全不见），
     * 在面板里把开关打开首页又整块回来。logcat 判据（冷启动 + 总开关关）：
     * <pre>
     * HIT tmap_tab_click_LiquidTabBarGroup     ← 启动时这一下被吞了
     * </pre>
     *
     * <p>整栏总开关现在由 [applyBarVisibility] / [refresh] 用容器 GONE 表达，
     * 与"点哪个页"无关，所以这里不能再掺和它。逐项隐藏本来就已经把对应标签从
     * 列表里摘掉了（[filterTabPageInfos] / [filterTabViews]），
     * 认得出的隐藏页根本不会存在；这道保险只兜"过滤没生效"的漏网之鱼。
     */
    @JvmStatic
    fun hidden(page: String?): Boolean {
        if (page == null || page.length == 0) return false   // 认不出 → 保留
        return !Cfg.tabVisible(page)
    }
}
