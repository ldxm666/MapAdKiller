package io.github.ldxm666.tmapclean

import android.view.View
import android.view.ViewGroup

import java.lang.reflect.Constructor
import java.util.WeakHashMap

import io.github.libxposed.api.XposedInterface

/**
 * 腾讯 11.7.0 首页工具宫格：过滤完成偏好排序后的适配器输入，使剩余入口连续排列。
 *
 * <p>JCE Group.tools 是偏好队列的查询目录，禁止就地删除。ToolsItemView#setVisibility(GONE)
 * 又不减少 RecyclerView 的条目数，因而留下空槽。这里仅向 HomeFeatureAdapter(gaa) 传入
 * gbt/gbs 的副本；保留真实 Cell 对象和所有其它元数据，由腾讯原代码处理顺序、点击与布局。
 * “更多”由适配器合成，独立在该适配器中处理，首页原数据及工具总目录保持完整。
 */
object TmapTools {

    /** 排序完成后送入首页适配器的 Cell 数据。 */
    private const val HOME_GROUP_VIEW =
        "com.tencent.map.ama.newhome.widget.HomeFeatureGroupView"

    /** 首页适配器与其输入数据模型（11.7.0 classes14.dex）。 */
    private const val HOME_ADAPTER = "com.tencent.map.navisdk.a.gaa"
    private const val HOME_DATA = "com.tencent.map.navisdk.a.gbt"
    private const val HOME_CELL = "com.tencent.map.navisdk.a.gbr"

    /** 面板本体；{@code HomePageCardView} 是它的子类，用来点名档位高度。 */
    private const val SLIDE_VIEW =
        "com.tencent.map.hippy.extend.view.slideview.SlideCardView"

    /**
     * 首页宫格「永久完整三排」开关。
     *
     * <p>缺省 <b>true</b>：用户要的就是"不滑动面板也一次铺满三排"这个行为本身，
     * 所以它默认生效。留一个键只为能关回去做对照排查，不是让它默认关。
     *
     * <p>为什么键名不放进 [Spec]：{@code Spec.cats()} 里宫格那一组的
     * {@code tools[]} 长度是 {@code 1 + ids.length}，而逐项开关的循环从下标 1 开始写，
     * 正好把 {@code tools[1]} 上的 {@code K_TOOL_PRESET} 覆盖掉。要在面板上再加一行，
     * 必须同时把长度改成 {@code 3 + ids.length}、循环起点改成 {@code i + 3} ——
     * 那会顺带把一直被覆盖的「只保留常用入口」开关也放出来，是另一个改动。
     * 本次只做宫格三排、不动面板，所以键名留在本类里。
     * {@code Cfg.visible(key, def)} 读的是整张 RemotePreferences，不在面板表里也能读，
     * 因此照样可以用 {@code .scratch/tmap_prefs.py set ui_tool_grid_expand=false} 做对照。
     */
    const val K_TOOL_GRID_EXPAND = "ui_tool_grid_expand"

    /**
     * 面板静止档位加高的开关（[installGridRoom]）。
     *
     * <p>缺省 <b>true</b>。宫格长高之后，面板静止档必须跟着长高同样的量，
     * 否则第三排会落到"首页/我的"底栏后面。见 [installGridRoom] 的实证。
     */
    const val K_TOOL_GRID_ROOM = "ui_tool_grid_room"

    /**
     * 首页卡片控制器 —— 它们都实现同一个 {@code SlideCardView$Adapter#getLevelHeights()}，
     * 真机走哪个由构建/机型决定，所以三个都挂（幂等，见 [raiseMiddle]）。
     *
     * <p>实证（11.7.0）：
     * <pre>
     * classes18.dex  com.tencent.map.ama.mainpage.business.pages.home.view.HomePageCardAdapter  ← 真机实际走这个
     * classes14.dex  com.tencent.map.navisdk.a.ewt   （ewv 继承它）
     * classes14.dex  com.tencent.map.navisdk.a.ewu
     * </pre>
     * 每个都有一份 {@code getLevelHeights()} 返回 {@code [档1高, 档2高, 档3高]}（越大面板越高），
     * 以及 {@code ab()} / {@code E()} 里那句 {@code this.n = isLargeLandStatic ? 3 : 2}
     * —— <b>静止档位是 2，不是展开档 3</b>。
     */
    private val PANEL_CONTROLLERS = arrayOf(
        "com.tencent.map.ama.mainpage.business.pages.home.view.HomePageCardAdapter",
        "com.tencent.map.navisdk.a.ewt",
        "com.tencent.map.navisdk.a.ewv",
        "com.tencent.map.navisdk.a.ewu",
    )

    /**
     * 「金刚位」偏好工具表的管理者（{@code MapToolsManager} 单例）。
     *
     * <p>DEX 实证（classes14.dex，11.7.0）：
     * <pre>
     * gas.m()Ljava/util/ArrayList;   读 map_app:preferred_tool_names；空则返回硬编码 13 项
     * gas.k()Z                       该键是否为空
     * gas.b(Ljava/util/ArrayList;)V  写 map_app:preferred_tool_names
     * gas.l()V                       清空该键
     * gas.a(Response, I)V            doSortToolNames：唯一的「远程→本地」落库入口，
     *                                组装 gbt（首页宫格数据）后 runOnUiThread(updateItemList)
     * </pre>
     *
     * <p>四个方法在 DEX 里都是 package/private 可见性。libxposed 的
     * {@code XposedModule#hook} 直接按 {@link java.lang.reflect.Method} 挂载，
     * 与 Java 源码可见性无关 —— 腾讯侧已有十多个 private 方法这么挂着。
     *
     * <p>为什么挂 {@code m()} 而不是 {@code b()}：{@code doSortToolNames} 每次都会
     * {@code names.equals(gas.m())} 决定是否覆盖本地缓存。截断 {@code m()} 的返回值之后
     * 这条比较依然自洽（不一致 → 它自己就把裁剪结果写回缓存），一个钩子就够，
     * 不需要动写库路径，也不会把服务端下发的原始 prefer 改坏。
     */
    private const val PREFS_MANAGER = "com.tencent.map.navisdk.a.gas"

    /**
     * 最近一次首页数据；只保存已挂钩的 HomeFeatureGroupView，不扫描视图树。
     * WeakHashMap 防止首页销毁后被模块持有。刷新发生在调用方线程，实际更新投递到 UI 线程。
     */
    private val HOMES: MutableMap<View, HomeState> = WeakHashMap()

    private val CONSTRUCTORS: MutableMap<Class<*>, Constructor<*>> =
        java.util.concurrent.ConcurrentHashMap()

    @Volatile
    private var cellClass: Class<*>? = null

    @Volatile
    private var lastCatalog: String? = null

    private class HomeState {
        var data: Any? = null
        var signature: String? = null
        var hidden = false
        var pending = false
    }

    @JvmStatic
    fun install(cl: ClassLoader, jceIn: Class<*>?) {
        installCatalogueReport(cl, jceIn)
        installHomeDataFilter(cl)
        installNativeGridHeight(cl)
        installGridRoom(cl)
        installGridGuard(cl)
        installCompactProgress(cl)
        installPanelSettle(cl)
        installPanelLevel(cl)
        installMoreFilter(cl)
        installPresetTrim(cl)
    }

    private fun installNativeGridHeight(cl: ClassLoader) {
        H.hookSig(H.cls(cl, HOME_GROUP_VIEW), "getViewMaxHeight", "tmap_tools_full_height",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val native = chain.proceed()
                    val grid = chain.getThisObject() as? View ?: return native
                    if (native !is Int || !expandOn()) return native
                    val cells = H.field(H.field(grid, "h"), "l") as? List<*> ?: return native
                    return TmapPanelContent.expandedGridHeight(native, cells.size,
                        grid.resources.displayMetrics.density)
                }
            })
    }

    private var homeSignature = ""
    private var contentSignature = ""
    /** A collapsed grid is an animation state, never the expanded content bound. */
    @JvmStatic fun expandedGeometryReady(): Boolean {
        val grid = lastGrid ?: return false
        if (!Cfg.visible(Spec.K_TOOLS) || grid.visibility == View.GONE) return true
        return roomFullLp > 0 && grid.height >= roomFullLp - 4
    }
    /** Only verified content roots; the native footer includes a viewport-sized spacer. */
    @JvmStatic fun visibleContentBottom(content: View, card: View): Int {
        val grid = lastGrid ?: return -1
        if (!TmapHomeFav.contentResolved()) return -1
        val tools = TmapPanelContent.boxBottom(grid, content)
        if (Cfg.visible(Spec.K_TOOLS) && grid.visibility == View.VISIBLE && tools < 0) return -1
        val fav = TmapHomeFav.visibleSectionBottom(content)
        val search = H.call(card, "getTopBarViewHeight") as? Int ?: return -1
        if (Cfg.debug()) {
            val signature = "$search:$tools:$fav"
            if (signature != contentSignature) {
                contentSignature = signature
                Cfg.log("TMFit roots search=$search tools=$tools fav=$fav grid=${grid.height} container=${content.javaClass.simpleName}")
            }
        }
        return maxOf(search, tools, fav)
    }
    @JvmStatic fun homeContentChanged() {
        val signature = "${Cfg.visible(Spec.K_HOME_FAV)}:${Cfg.visible(Spec.K_HOME_FEED)}"
        if (signature == homeSignature) return
        homeSignature = signature
        roomConverged = false
        scheduleRoom(lastGrid)
        TmapPanelFit.request(lastGrid)
    }

    /**
     * 常用预设：把腾讯读到的偏好工具列表裁到前 [Spec.TOOL_PRESET_COUNT] 个。
     *
     * <p>失效安全：开关关着 / 类找不到 / 拦截抛异常，一律原样放行 ——
     * 腾讯本来怎么排就怎么排，模块顶多少做一件事，绝不会把宫格弄没。
     *
     * <p>与逐项开关的关系：{@code filterData} 那层仍然按 {@code tool_<id>} 过滤，
     * 两层串联 —— 先裁数量，再按用户显式关掉的摘。两者互不依赖。
     */
    private fun installPresetTrim(cl: ClassLoader) {
        val mgr = H.cls(cl, PREFS_MANAGER)
        if (mgr == null) {
            Diag.note("tools_preset_hook", "miss_manager")
            return
        }
        var n = 0
        // getDeclaredMethods 不保证返回顺序，逐个按签名名 + 返回类型认，别按下标。
        var declared: Array<java.lang.reflect.Method>? = null
        try {
            declared = mgr.declaredMethods
        } catch (t: Throwable) {
            declared = null
        }
        val all = declared
        if (all != null) {
            for (i in all.indices) {
                val m = all[i]
                if ("m" != m.name) continue
                if (m.parameterTypes.size != 0) continue          // 只认 m()
                if (java.util.ArrayList::class.java != m.returnType) continue
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                if (H.hook(m, "tmap_tools_preset", object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            if (result !is java.util.ArrayList<*>) return result
                            if (!presetOn()) return result
                            try {
                                return trimPreset(result)
                            } catch (t: Throwable) {
                                // 裁坏了就把原列表还回去，绝不让宫格空掉。
                                Diag.note(
                                    "tools_preset_filter", "failed_"
                                            + t.javaClass.simpleName
                                )
                                return result
                            }
                        }
                    })
                ) n++
            }
        }
        if (n == 0) Diag.note("tools_preset_hook", "no_m_method")
    }

    private fun presetOn(): Boolean {
        try {
            return Cfg.visible(Spec.K_TOOL_PRESET, false) && Cfg.visible(Spec.K_TOOLS, true)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * 裁掉第 [Spec.TOOL_PRESET_COUNT] 个之后的 id。
     *
     * <p>原列表是腾讯刚 new 出来的 {@code ArrayList}（{@code gas.m()} 每次新建），
     * 就地 clear + addAll 是安全的；不新建是为了让 {@code equals} 比较仍按内容走。
     */
    @Suppress("UNCHECKED_CAST")
    private fun trimPreset(original: java.util.ArrayList<*>): Any? {
        val keep = Math.min(Spec.TOOL_PRESET_COUNT, original.size)
        if (keep == original.size) return original      // 本来就没超，什么都不做
        val list = original as java.util.ArrayList<Any?>
        val kept = ArrayList<Any?>(keep)
        for (i in 0 until keep) kept.add(list[i])
        list.clear()
        list.addAll(kept)
        Diag.count("tool_preset", list.size + keep, kept.size)
        return original
    }

    /**
     * 记录首页已经完成偏好排序的原始 gbt，供配置变化后重新送入适配器。
     *
     * <p>ToolsItemView#setVisibility(GONE) 只能隐藏像素，RecyclerView 仍按原下标布局，
     * 因而留下空槽。过滤只发生在下方 gaa.a(gbt) 的入参，首页其它数据监听器仍收到原始 gbt。
     */
    private fun installHomeDataFilter(cl: ClassLoader) {
        val home = H.cls(cl, HOME_GROUP_VIEW)
        val data = H.cls(cl, HOME_DATA)
        if (home == null || data == null) {
            Diag.note("tools_grid_hook", "miss_home_data")
            return
        }
        H.hookSig(home, "updateItemList", "tmap_tools_update",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val self = chain.getThisObject()
                    val original = chain.getArg(0)
                    if (self !is View || original == null) return chain.proceed()
                    val view = self
                    val state: HomeState
                    synchronized(HOMES) {
                        val s = HOMES[view] ?: HomeState().also {
                            HOMES[view] = it
                            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                TmapPanelFit.request(view)
                            }
                        }
                        s.data = original
                        s.signature = configSignature(original)
                        s.pending = false
                        state = s
                    }
                    val result = chain.proceed()
                    // 这一次数据更新里唯一一次"钉初始值"：a(gay.a == 3 ? 1.0f : 0.0f) 刚跑完，
                    // 冷启动传的是 0.0f（一排），这里补一次 a(1.0f) 让它落到"完全展开"。
                    lastGrid = view
                    expandGridOnce(view)
                    val adapter = H.field(self, "h")
                    val current = H.field(adapter, "l")
                    val hide = !Cfg.visible(Spec.K_TOOLS, true) ||
                        (current is List<*> && current.isEmpty())
                    synchronized(HOMES) {
                        if (hide) {
                            if (view.visibility == View.VISIBLE) {
                                state.hidden = true
                                view.visibility = View.GONE
                            }
                        } else if (state.hidden) {
                            state.hidden = false
                            if (view.visibility == View.GONE) view.visibility = View.VISIBLE
                        }
                    }
                    Diag.count("tool_data", 1, if (hide) 1 else 0)
                    // ★ 2.3.1（性能）：两条取证转储只在 debug_log 打开时才跑。
                    //   scheduleDump() 递归整棵首页视图树（最长 8 层），逐节点取
                    //   getResourceEntryName / getX / getWidth，并对 RecyclerView 反射 getAdapter；
                    //   scheduleFacts() 再补 6 次反射。两者都 post 到主线程、紧跟在 updateItemList
                    //   之后执行，而 updateItemList 在首页每次数据刷新都会命中 —— 冷启动 25 s 真机
                    //   实测 443~565 行 TPD/TPE、98 KB logcat，每行都是一次到 logd 的同步 write。
                    //   这是取证输出，不属于功能，关掉 debug 就该一条都不跑。
                    if (Cfg.debug()) {
                        scheduleDump(view, "update")
                        scheduleFacts(view, "update")
                    }
                    roomFullLp = view.layoutParams?.height ?: -1
                    scheduleRoom(view)
                    TmapPanelFit.request(view)
                    return result
                }
            }, data)
        Diag.note("tools_grid_hook", "HomeFeatureGroupView.updateItemList(gbt)")
    }

    /** Initialize the native grid once per data update and cache its expanded layout. Middle/high drag frames retain this cached height and alpha; the native low level remains available. */
    private fun expandGridOnce(grid: View?) {
        if (grid == null || !expandOn()) return
        // A second (remote) tool list can arrive after the one-row cached list.
        // Do not let our own a(F) guard reuse the previous list's expanded height.
        gridForced = false
        ratioPinned = false
        val r: Any?
        try {
            r = H.call1(grid, "a", java.lang.Float.valueOf(1.0f))
        } catch (t: Throwable) {
            Diag.note("tools_grid_expand", "failed_" + t.javaClass.simpleName)
            return
        }
        if (r == null) {
            Diag.note("tools_grid_expand", "no_a_F")
            return
        }
        gridForced = true
        roomFullLp = grid.layoutParams?.height ?: -1
        ratioPinned = true
        if (!expandLogged) {
            expandLogged = true
            Diag.note("tools_grid_expand", "updateItemList -> a(1.0f) one-shot")
            Cfg.log("TPE tmap_tools_expand one-shot a(1.0f)")
        }
    }

    @Volatile
    private var expandLogged = false

    /** 缺省 true：这是用户要的行为本身，键只是留一条能关回去做对照的路。 */
    private fun expandOn(): Boolean {
        try {
            return Cfg.visible(K_TOOL_GRID_EXPAND, true) && Cfg.visible(Spec.K_TOOLS, true)
        } catch (t: Throwable) {
            return true      // 读不到配置就当它开着 —— 这是本次交付的主行为
        }
    }

    /**
     * 宫格已经被本模块钉在"完全展开"。
     *
     * <p>只有 [expandGridOnce] 真的调用成功才置 true；[refresh]（配置变更）
     * 会清掉，等下一次 {@code updateItemList} 重新置。
     */
    @Volatile
    private var gridForced = false

    /** Keep expanded geometry and adapter alpha consistent at every native progress value. Never alternate a partial native frame with a forced full frame. The native low-level path remains unchanged. */
    private fun installGridGuard(cl: ClassLoader) {
        val home = H.cls(cl, HOME_GROUP_VIEW)
        if (home == null) {
            Diag.note("tools_grid_guard", "miss_home_view")
            return
        }
        val m = H.hookSig(home, "m", "tmap_tools_guard_m", object : XposedInterface.Hooker {
            private var seen = 0
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (seen < 3 && Cfg.debug()) {
                    seen++
                    Cfg.log("TPE diag grid.m() forced=" + gridForced + stack())
                }
                if (gridForced) {
                    Diag.hit("tools_grid_guard_m")
                    return null
                }
                return chain.proceed()
            }
        })
        val f = H.hookSig(home, "f", "tmap_tools_guard_f", object : XposedInterface.Hooker {
            private var seen = 0
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val a0 = chain.getArg(0)
                if (seen < 4 && Cfg.debug()) {
                    seen++
                    Cfg.log("TPE diag grid.f(" + a0 + ") forced=" + gridForced + stack())
                }
                if (!gridForced) return chain.proceed()
                if (a0 is Float && a0 >= 1.0f) return chain.proceed()
                Diag.hit("tools_grid_guard_f")
                return chain.proceed(arrayOf<Any?>(java.lang.Float.valueOf(1.0f)))
            }
        }, java.lang.Float.TYPE)
        // Keep every middle/high progress frame at the same expanded grid geometry.
        val pin = H.hookSig(home, "a", "tmap_tools_pin", object : XposedInterface.Hooker {
            private var seen = 0
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val a0 = chain.getArg(0)
                if (seen < 16 && Cfg.debug()) {
                    seen++
                    Cfg.log(
                        "TPE diag grid.a(" + a0 + ") forced=" + gridForced
                                + " ratioPinned=" + ratioPinned + " atMid=" + panelAtMidOrHigher()
                    )
                }
                if (!gridForced || a0 !is Float) return chain.proceed()
                if (!panelAtMidOrHigher()) {       // 最低档：保持腾讯原本的一排
                    ratioPinned = false
                    return chain.proceed()
                }
                val self = chain.getThisObject()
                if (self !is View || !pinExpanded(self, ratioPinned)) {
                    return chain.proceed()
                }
                ratioPinned = true
                Diag.hit("tools_grid_pin")
                return null
            }
        }, java.lang.Float.TYPE)
        Diag.note("tools_grid_guard", "m=" + m + " f=" + f + " pin=" + pin)
    }

    /** Native a(F) is the middle-to-high transition. With the feed hidden the
     * middle level is the upper endpoint, so its header margin, address translation
     * and Litho progress must also stay at middle, alongside the pinned grid.
     */
    private fun installCompactProgress(cl: ClassLoader) {
        for (name in arrayOf(PANEL_CONTROLLERS[0], "com.tencent.map.navisdk.a.ewt")) {
            H.hookSig(H.cls(cl, name), "a", "tmap_tools_compact_progress#${name.substringAfterLast('.')}",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val progress = chain.getArg(0) as? Float ?: return chain.proceed()
                        val target = compactProgress(progress, expandOn(), Cfg.visible(Spec.K_HOME_FEED))
                        return if (target == progress) chain.proceed()
                            else chain.proceed(arrayOf<Any?>(target))
                    }
                }, java.lang.Float.TYPE)
        }
    }

    @JvmStatic fun compactProgress(progress: Float, expanded: Boolean, feedVisible: Boolean): Float =
        if (expanded && !feedVisible && progress > 0f) 0f else progress

    /**
     * 把宫格就地按"满行"落位，<b>不走腾讯那套会打日志的算术</b>。
     *
     * <p>为什么不用 {@code chain.proceed(1.0f)}：那条路会重新执行
     * {@code getViewMidHeight()} + {@code getViewMaxHeight()}，而这两个方法内部各打一条
     * {@code LogUtil.msg}（xlog + 字符串）。上一版 ANR 的主线程栈就停在
     * {@code LogUtil.i ← getViewMidHeight ← raiseMiddle}（见 {@code /data/anr/anr_2026-10-15_02-26-36-867}）。
     * 这里改成直接写标定阶段记下来的 {@code LayoutParams.height}，再调一次
     * {@code c(1.0f)} 把适配器比值放回 1（三排都可见）—— 两个动作都不打日志。
     *
     * @return true 表示已经就地处理完，调用方直接返回、不再 proceed
     */
    private fun pinExpanded(grid: View, ratioAlreadyOne: Boolean): Boolean {
        val full = roomFullLp
        if (full <= 0) return false
        val lp = grid.layoutParams
        if (lp != null && lp.height != full) {
            lp.height = full
            grid.requestLayout()
        }
        // 高度和"格子透明度"是两件事：a(F) 里 a(h,z) 管高度、c(f2)/b(f2) 管适配器比值 s，
        // 而 s 才是 gbm.a(s) 逐格 alpha 的输入。拖动把 s 压到 ~0.005 之后，光把高度钉回
        // 满行只会得到"盒子够高、二三排全透明"—— g4-B2 那张截图就是这个样子。
        if (!ratioAlreadyOne) H.call1(grid, "c", java.lang.Float.valueOf(1.0f))
        return true
    }

    /** Cleared by native level callbacks and low-level progress; pinned once on return to middle. */
    @Volatile
    private var ratioPinned = false

    /** 标定阶段在宫格真的满行时记下的 {@code LayoutParams.height}。 */
    @Volatile
    private var roomFullLp = -1

    /** 面板最近一次换档的目标：容器顶 / 面板高 / 中档高度，供热路径零反射判断。 */
    @Volatile
    private var settleTop = Integer.MIN_VALUE

    @Volatile
    private var settlePanelH = -1

    @Volatile
    private var settleMid = -1

    /**
     * 面板现在是否停在中档及以上（只看缓存值，零反射）。
     *
     * <p>优先用 [panelLevel]：它由腾讯自己的档位回调
     * {@code HomeFeatureGroupView#b()/c()/d()}（onLow / onMiddle / onHeigh）直接给出，
     * 比"量容器高度"可靠 —— 实测那三条测量路径在真机上都取不到值（构建 0ff5ac1d 的
     * {@code no_container}）。档位未知时退回"量容器高度"，都拿不到就按"可以钉"处理。
     */
    private fun panelAtMidOrHigher(): Boolean {
        val measured = settleLevelHeight()
        if (measured > 0) return measured >= settleMid - 12
        val level = panelLevel
        if (level > 0) return level >= 2
        return true
    }

    /**
     * 面板最近一次换档后落到的档位高度（px），量不到返回 -1。
     *
     * <p>来源是 {@code SlideCardView#notifyWillChange(int top, boolean)}：它给的是容器
     * 目标顶边，{@code 面板高 - 顶边} 就是那一档的高度。实测它比
     * {@code HomeFeatureGroupView#c()}（onMiddle）可靠 —— 用户把面板拖回去时
     * {@code c()} 不一定再发（构建 dd84569c 的 {@code level=1} 就是这种过期值）。
     */
    private fun settleLevelHeight(): Int {
        val mid = settleMid
        if (mid <= 0 || settlePanelH <= 0 || settleTop == Integer.MIN_VALUE) return -1
        return settlePanelH - settleTop
    }

    /** 腾讯自己回报的首页卡片档位：1=看地图、2=中档（首页静止档）、3=完全展开。 */
    @Volatile
    private var panelLevel = 0

    /**
     * 挂 {@code HomeFeatureGroupView} 的三个档位回调，拿到"面板现在在哪一档"。
     *
     * <p>三个都是无参方法（同名重载里只有它们是无参），且都是<b>换档时各调一次</b>，
     * 不是每帧；中档/高档落稳之后合并补钉一次，
     * 低档（看地图）则把宫格交回腾讯原本的一排 —— 免得三排从底栏下面漏出来。
     */
    private fun installPanelLevel(cl: ClassLoader) {
        val home = H.cls(cl, HOME_GROUP_VIEW)
        if (home == null) {
            Diag.note("tools_grid_level", "miss_home_view")
            return
        }
        val low = H.hookSig(home, "b", "tmap_tools_level_low", levelHook(1))
        val mid = H.hookSig(home, "c", "tmap_tools_level_mid", levelHook(2))
        val high = H.hookSig(home, "d", "tmap_tools_level_high", levelHook(3))
        Diag.note("tools_grid_level", "low=" + low + " mid=" + mid + " high=" + high)
    }

    private fun levelHook(level: Int): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                try {
                    panelLevel = level
                    ratioPinned = false
                    Cfg.log("TPE level hook level=" + level + " gridForced=" + gridForced)
                } catch (t: Throwable) {
                    Diag.note("tools_grid_level", "failed_" + t.javaClass.simpleName)
                }
                return result
            }
        }
    }

    /**
     * 面板每次"要换档"时把宫格重新钉一次 —— 这样"三排"才是<b>永久</b>的。
     *
     * <h3>为什么必须补这一手（真机实测）</h3>
     * 冷启动一次性 {@code a(1.0f)} 之后确实是三排（{@code TPE room converged extra=240}），
     * 但只要用户拖一下面板，腾讯就会重新应用档位，而"中档 = 一排"那条
     * {@code HomeFeatureGroupView#a(0.0f)} 会把宫格打回一排 —— 截图
     * {@code g2-C3-open.png} 就是"面板回到中档 + 一排 + 下方一大片空白"。
     *
     * <h3>为什么钩 {@code notifyWillChange} 而不是 {@code notifyLevelStable}</h3>
     * {@code SlideCardView#notifyLevelStable} 是 {@code computeScroll()} 每帧都会走到的；
     * 挂在它上面等于每帧都去碰宫格，正是上一版 ANR 的形态。
     * {@code notifyWillChange(int, boolean)} 在 11.7.0 里<b>只有两个调用点</b>：
     * <pre>
     * SlideCardView#setLevelWithHeight(int)          ← 换档
     * SlideCardView$DragCallBack#onViewReleased(...) ← 手指抬起
     * </pre>
     * 都不是每帧，所以一次手势只补钉一次（外加一次延迟重试，防面板自己的落位动画还没跑完）。
     *
     * <h3>面板停在最低档（看地图）时不补钉</h3>
     * 最低档只给 {@code [378, ...]} 那么高，宫格撑到三排只会从底栏下面漏出来。
     * 这里比较 {@code 容器高度 vs 中档高度}：低于中档就保持腾讯原本的一排。
     */
    private fun installPanelSettle(cl: ClassLoader) {
        val slide = H.cls(cl, SLIDE_VIEW)
        if (slide == null) {
            Diag.note("tools_grid_settle", "miss_slide")
            return
        }
        val ok = H.hookSig(slide, "notifyWillChange", "tmap_tools_settle",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        onPanelWillChange(chain.getArg(0))
                    } catch (t: Throwable) {
                        Diag.note("tools_grid_settle", "failed_" + t.javaClass.simpleName)
                    }
                    return result
                }
            }, java.lang.Integer.TYPE, java.lang.Boolean.TYPE)
        H.hookSig(slide, "notifyLevelStable", "tmap_tools_stable",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val grid = lastGrid ?: return result
                    val generation = ++settleGeneration
                    grid.postOnAnimation {
                        if (generation == settleGeneration && grid.isAttachedToWindow) {
                            reassertGrid(0L)
                            TmapPanelFit.request(grid)
                        }
                    }
                    return result
                }
            }, Integer.TYPE)
        Diag.note(
            "tools_grid_settle",
            if (ok) "notifyWillChange hooked" else "no_notifyWillChange"
        )
    }

    /** Cache the native target level and cancel obsolete settled-layout work. Native animations own the transition; a single coalesced task runs after notifyLevelStable. */
    private fun onPanelWillChange(topArg: Any?) {
        val panel = findPanelView()
        if (panel != null && topArg is Int) {
            settlePanelH = panel.height
            settleTop = topArg
            val controller = roomController
            val levels = if (controller == null) null else H.call(controller, "getLevelHeights")
            if (levels is List<*> && levels.size >= 2) {
                val o = levels[1]
                if (o is Int) settleMid = o
            }
        }
        // A new gesture invalidates the previous settled-layout task. Never race the native
        // animator with delayed 400/900/1500/2200/3000ms height/alpha corrections.
        settleGeneration++
    }

    private var settleGeneration = 0

    /**
     * 面板落定后，只要它停在中档及以上，就把宫格重新钉到"完全展开"。
     *
     * <p>零反射热路径：先比 [roomAppliedMax]（标定出来的满行高度）与宫格当前高度，
     * 已经是满行就直接返回 —— 所以即使被多调几次也不会去碰腾讯那些会打日志的方法。
     */
    /**
     * 面板容器当前高度（px）；量不到返回 -1。
     *
     * <p>先读 {@code SlideCardView} 自己的 {@code protected int currentContainerViewHeight}：
     * {@code SlideCardView} 是泛型类（{@code SlideCardView<Container extends ViewGroup>}），
     * 走 {@code getContentContainer()} 那条路在真机上拿到过 null（见构建 1ef43640 的
     * {@code no_container}），所以直接读字段更稳。字段读不到再退回 {@code containerView} 的 top。
     */
    private fun containerHeightOf(panel: View): Int {
        val ch = H.field(panel, "currentContainerViewHeight")
        if (ch is Int) {
            val v = ch
            if (v > 0) return v
        }
        val cv = H.field(panel, "containerView")
        if (cv is View) return panel.height - cv.top
        val container = H.call(panel, "getContentContainer")
        if (container is View) return panel.height - container.top
        return -1
    }

    private fun reassertGrid(at: Long) {
        val grid = lastGrid
        if (grid == null || !expandOn()) return
        val h = grid.height
        val full = roomAppliedMax
        val needHeight = full <= 0 || h < full - 4
        if (!needHeight && ratioPinned) {                          // 高度与比值都到位：什么都不做
            if (Cfg.debug()) Cfg.log(
                "TPE reassert t=" + at + " skip full gridH=" + h
                        + " full=" + full
            )
            return
        }
        val panel = findPanelView()
        val controller = roomController
        if (panel == null || controller == null) {
            if (Cfg.debug()) Cfg.log("TPE reassert t=" + at + " no_panel gridH=" + h)
            return
        }
        val containerH = containerHeightOf(panel)
        var mid = -1
        val levels = H.call(controller, "getLevelHeights")
        if (levels is List<*> && levels.size >= 2) {
            val o = levels[1]
            if (o is Int) mid = o
        }
        val measured = settleLevelHeight()
        val lowLevel: Boolean
        if (measured > 0 && mid > 0) {
            lowLevel = measured < mid - 12
        } else if (panelLevel > 0 && mid > 0) {
            lowLevel = panelLevel == 1
        } else {
            lowLevel = mid > 0 && containerH > 0 && containerH < mid - 12
        }
        if (lowLevel) {
            Diag.hit("tools_grid_settle_low")                     // 最低档：保持一排
            ratioPinned = false
            if (Cfg.debug()) Cfg.log(
                "TPE reassert t=" + at + " low measured=" + measured
                        + " level=" + panelLevel + " containerH=" + containerH + " mid=" + mid
                        + " gridH=" + h
            )
            return
        }
        Diag.hit("tools_grid_settle_reassert")
        if (needHeight) {
            Cfg.log(
                "TPE reassert t=" + at + " FIX-H containerH=" + containerH + " mid=" + mid
                        + " gridH=" + h + " full=" + full
            )
            expandGridOnce(grid)
        } else {
            Cfg.log(
                "TPE reassert t=" + at + " FIX-RATIO containerH=" + containerH + " mid=" + mid
                        + " gridH=" + h + " full=" + full
            )
            pinExpanded(grid, false)
        }
        ratioPinned = true
    }

    /** 诊断用调用栈（最多 9 帧）。只在 debug 打开时被调用。 */
    private fun stack(): String {
        val frames = Throwable().stackTrace
        val b = StringBuilder(260)
        var i = 1
        while (i < frames.size && i <= 9) {
            b.append("\n    at ").append(frames[i])
            i++
        }
        return b.toString()
    }

    /**
     * 面板静止档跟着宫格一起长高 —— 否则第三排落到「首页/我的」底栏后面。
     *
     * <p>宫格那边（[expandGridOnce]）只负责把容器撑到 {@code getViewMaxHeight()}
     * （14 格 → 526px，三排，子项 top = 7 / 217 / 419）；面板自己还有一套<b>档位</b>，
     * 静止档是 2 不是展开档 3，给的高度不够，第三排会被底栏盖住。
     *
     * <p>这里只挂 {@code getLevelHeights()} 这一个点：它是面板每次问"我有哪些档位高度"
     * 的唯一入口，把缓存表里的静止档就地改大即可；<b>具体加多少由 [calibrate]
     * 按实测缺口收敛</b>（见那个方法的说明），这里不写死任何常量。
     *
     * <p>只抬下标 1（静止档）：档 0（最收起的看地图档）保持原样，
     * 用户主动下滑仍能把面板收回去。
     *
     * <p>失效安全：找不到类 / 签名 miss / 抛异常，一律返回原列表。
     */
    private fun installGridRoom(cl: ClassLoader) {
        slideClass = H.cls(cl, SLIDE_VIEW)
        var hooked = 0
        for (i in PANEL_CONTROLLERS.indices) {
            val panel = H.cls(cl, PANEL_CONTROLLERS[i]) ?: continue
            if (H.hookSig(panel, "getLevelHeights",
                    "tmap_tools_room#" + panel.simpleName,
                    object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            try {
                                return raiseMiddle(chain.getThisObject(), result)
                            } catch (t: Throwable) {
                                Diag.note(
                                    "tools_grid_room",
                                    "failed_" + t.javaClass.simpleName
                                )
                                return result
                            }
                        }
                    })
            ) hooked++
        }
        Diag.note(
            "tools_grid_room", if (hooked == 0) "no_getLevelHeights"
            else hooked.toString() + " getLevelHeights hooked"
        )
    }

    /**
     * 把档位表的下标 1（静止档）加高 [roomExtra] 像素。
     *
     * <p>就地改腾讯缓存的那张 List，并且<b>按 controller 记下原始档 1 高度</b>：
     * <ul>
     *   <li><b>就地改 ⇒ 幂等</b>：{@code getLevelHeights()} 返回的是缓存实例，第二次调用
     *       拿到的已经是抬过的值，不会叠加；子类 {@code super} 到父类时两次都进这里，
     *       也只生效一次。</li>
     *   <li><b>记原值 ⇒ 可重算</b>：{@code roomExtra} 由 [calibrate] 实测收敛，
     *       每次都按<b>原始档 1</b> 重算，不会在旧值上再加一次。</li>
     * </ul>
     *
     * <p>只抬下标 1，并夹在档 2 以内：档 0（最收起的看地图档）保持原样，
     * 用户主动下滑仍能把面板收回去。
     *
     * <p>这里<b>不</b>去读 {@code getViewMaxHeight()} / {@code getViewMidHeight()}：
     * 那两个方法每次都会打一条 {@code LogUtil.msg}，而 {@code getLevelHeights()} 会被
     * 反复调用，读它们等于给日志加流量。目标高度改由 [calibrate] 直接量屏幕坐标。
     */
    /**
     * 把档位表的下标 1（静止档）加高 [roomExtra] 像素。
     *
     * <h3>这是主线程热路径，必须零成本</h3>
     * 上一版的 ANR 主要出在这里，不在 {@code a(F)} 上。真机 {@code /data/anr/} 里三条
     * 腾讯进程的主线程栈全部指向本方法：
     * <pre>
     * anr_2026-10-15_02-10-03-646  (MotionEvent action=MOVE)
     *   at HomeFeatureGroupView.getViewMaxHeight(HomeFeatureGroupView.java:303)
     *   at java.lang.reflect.Method.invoke
     *   at io.github.ldxm666.tmapclean.H.call(H.java:308)
     *   at io.github.ldxm666.tmapclean.TmapTools.raiseMiddle(TmapTools.java:458)
     *   at io.github.ldxm666.tmapclean.TmapTools$4.intercept(TmapTools.java:403)
     * anr_2026-10-15_02-12-45-792  (MOVE)
     *   at java.util.AbstractCollection.toString(AbstractCollection.java:457)
     *   at io.github.ldxm666.tmapclean.TmapTools.noteLevels(TmapTools.java:499)
     *   at io.github.ldxm666.tmapclean.TmapTools.raiseMiddle(TmapTools.java:476)
     * anr_2026-10-15_02-26-36-867  (UP)
     *   at com.tencent.mars.xlog.Xlog.logI(Xlog.java:79)
     *   at com.tencent.map.ama.util.LogUtil.i(LogUtil.java:763)
     *   at HomeFeatureGroupView.getViewMidHeight(HomeFeatureGroupView.java:368)
     *   at io.github.ldxm666.tmapclean.TmapTools.noteLevels(TmapTools.java:507)
     *   at io.github.ldxm666.tmapclean.TmapTools.raiseMiddle(TmapTools.java:476)
     * </pre>
     * {@code getLevelHeights()} 在面板拖动期间被高频调用，而旧版每次都在这里<b>反射</b>读
     * {@code getInitHeight()} / {@code getViewMaxHeight()} / {@code getViewMidHeight()}，
     * 再拼一个几百字符的字符串；腾讯那两个方法内部各自还要打一条 {@code LogUtil.msg}
     * （xlog 队列 + 字符串）。主线程被这些活拖过 5 秒 → ANR。
     *
     * <p>现在本方法只做：类型检查 → 整数比较 → 必要时一次 {@code list.set}。
     * 稳态（已经是目标值）下<b>不分配、不反射、不打日志、不读配置</b>；
     * 只有真正改了值的那一次才落一条 {@code Diag}/{@code Cfg} 日志。
     *
     * <p>就地改腾讯缓存的那张 List ⇒ 幂等：{@code getLevelHeights()} 返回的是缓存实例，
     * 第二次调用拿到的已经是抬过的值，不会叠加；子类 {@code super} 到父类时两次都进这里，
     * 也只生效一次。每次都按<b>原始档 1</b>（[roomBase1]）重算，不在旧值上再加。
     *
     * <p>只抬下标 1，并夹在档 2 以内：档 0（最收起的看地图档）保持原样，
     * 用户主动下滑仍能把面板收回去。
     */
    @Suppress("UNCHECKED_CAST")
    private fun raiseMiddle(controller: Any?, levels: Any?): Any? {
        if (levels !is List<*>) return levels
        val list = levels
        if (list.size < 3) return levels
        val o0 = list[0]
        val o1 = list[1]
        val o2 = list[2]
        if (o0 !is Int || o1 !is Int || o2 !is Int) {
            return levels
        }
        if (roomBase1 < 0 || controller !== roomController) {
            // 第一次见到这张表（此刻 roomExtra 还是 0，拿到的就是腾讯原值）。
            roomController = controller
            roomBase1 = o1
        }
        val extra = roomExtra

        val level1 = o1
        val level2 = o2
        val target = (roomBase1 + extra).coerceIn(o0, level2)
        if (level1 == target) return levels   // 稳态：到此为止

        if (!roomOn()) return levels
        try {
            (list as MutableList<Any?>).set(1, Integer.valueOf(target))
        } catch (t: Throwable) {
            Diag.note("tools_grid_room", "immutable_" + t.javaClass.simpleName)
            return levels
        }
        Diag.note(
            "tools_grid_room", "raised base1=" + roomBase1 + " extra=" + extra
                    + " -> " + target
        )
        Cfg.log(
            "TPE room raised base1=" + roomBase1 + " extra=" + extra
                    + " -> " + target + " level2=" + level2
        )
        return levels
    }

    /** 被抬过的那次 controller / 原始档 1 高度。 */
    @Volatile
    private var roomController: Any? = null

    @Volatile
    private var roomBase1 = -1

    /** 最近一次挂钩到的宫格控件 —— 不依赖各 controller 各自的字段名。 */
    @Volatile
    private var lastGrid: View? = null

    /**
     * 宫格铺满之后，把面板静止档<b>按实测缺口</b>顶上去，直到第三排完整露在底栏之上。
     *
     * <h3>为什么光有宫格那一个钩子还不够</h3>
     * 宫格容器撑到 {@code getViewMaxHeight()}（14 格 → 526px，三排，子项 top = 7 / 217 / 419）
     * 之后，面板自己还有一套<b>档位</b>：
     * <pre>
     * ewt.ab()   this.n = isLargeLandStatic ? 3 : 2      ← 静止档位是 2，不是展开档 3
     * ewt.y()    setLevelWithHeight(getLevelHeights().get(this.n - 1))
     * </pre>
     * 静止档 2 给的高度不够，第三排会落到"首页/我的"底栏后面。
     *
     * <h3>为什么不写死"加高一个格子"</h3>
     * 档位高度 → 面板实际位移不是 1:1（实测把档 1 从 745 抬到 1007 只让面板上移约 170px），
     * 写死任何常量都会在别的机型/字号下要么留白、要么仍被盖住。这里改成<b>闭环标定</b>：
     * <ol>
     *   <li>量宫格底边与底栏顶边的屏幕坐标差 {@code overlap}；</li>
     *   <li>{@code overlap > 0} → 把 {@code overlap} 加进 {@code roomExtra}（面板往上长），再量；
     *       {@code overlap < 0} → 抬多了（下方留白），把多出来的减回去；</li>
     *   <li>{@code |overlap| <= }{@link #ROOM_MARGIN} 就收敛，最多 {@link #ROOM_ROUNDS} 轮。</li>
     * </ol>
     * 收敛点正好是"第三排底边贴住底栏顶边" —— 既露全三排，又不留空白。
     *
     * <h3>底栏怎么找</h3>
     * 不碰 {@code TmapTabBar}（已交付），自己从宫格取 root 再向下找
     * {@code GradientTabBarContainer}（11.7.0 里真正的整栏，见 {@code TmapTabBar} 的实证）。
     * 找不到就退回"按宫格长高的那一截加高"，仍然不会留一排被盖住。
     *
     * <p>失效安全：拿不到 controller / 宫格 / 抛异常，什么都不做，面板维持腾讯原本的档位。
     */
    private fun scheduleRoom(grid: View?) {
        if (grid == null || !roomOn()) return
        val max = H.call(grid, "getViewMaxHeight")
        if (max !is Int) return
        val value = max
        // 满行高度没变、且上一次已经收敛 —— 不用再动面板。
        if (value == roomAppliedMax && roomConverged) return
        roomAppliedMax = value
        roomConverged = false
        if (roomScheduled) return
        roomScheduled = true
        grid.postDelayed(object : Runnable {
            override fun run() {
                roomScheduled = false
                calibrate(0)
            }
        }, 700L)
    }

    /** 已经按哪个宫格满行高度标定过；变了才重标。 */
    @Volatile
    private var roomAppliedMax = -1

    @Volatile
    private var roomScheduled = false

    @Volatile
    private var roomConverged = false

    /** 档位 1 之上额外加高的像素；由 [calibrate] 实测收敛出来。 */
    @Volatile
    private var roomExtra = 0

    /** 缓存到的底栏容器。 */
    @Volatile
    private var barView: View? = null

    /** 第三排底边与底栏顶边之间留的余量（px）。 */
    private const val ROOM_MARGIN = 14

    /** 标定轮数上限：每轮只加"当前实测缺口"，几何收敛，3~4 轮足够。 */
    private const val ROOM_ROUNDS = 6

    /**
     * 一轮标定：量缺口 → 调 {@code roomExtra} → 落到面板 → 下一轮再量。
     *
     * <p>每轮之间 {@code postDelayed(700ms)}，等面板自己的 {@code smoothSlideViewTo}
     * 动画落定再量，避免量到过渡态。
     */
    private fun calibrate(round: Int) {
        if (!roomOn()) {
            roomConverged = true
            return
        }
        val grid = lastGrid
        val controller = roomController
        if (grid == null || controller == null || !grid.isAttachedToWindow) {
            Diag.note(
                "tools_grid_room", "calib_abort grid=" + (grid != null)
                        + " ctrl=" + (controller != null)
            )
            return
        }
        // 只在宫格真的处于"满行高度"时才量。否则量到的是腾讯刚把它收回一排的中间态，
        // 会把已经收敛的 roomExtra 误判成"抬多了"再减回去（上一版就是这样来回抖的）。
        val maxObj = H.call(grid, "getViewMaxHeight")
        val gridMax = if (maxObj is Int) maxObj else -1
        if (gridMax > 0 && grid.height < (grid.layoutParams?.height ?: grid.height) - 4) {
            Diag.note(
                "tools_grid_room", "calib r" + round + " grid_not_full h="
                        + grid.height + " max=" + gridMax
            )
            expandGridOnce(grid)            // 重新钉一次（一次性，不是每帧）
            if (round < ROOM_ROUNDS) {
                grid.postDelayed(object : Runnable {
                    override fun run() {
                        calibrate(round + 1)
                    }
                }, 500L)
            }
            return
        }
        val glp = grid.layoutParams
        if (glp != null && glp.height > 0) roomFullLp = glp.height
        val bar = findBottomBar(grid)
        val panel = findPanelView()
        val content = H.call(panel, "getContentContainer") as? View
        val measured = if (content == null || panel == null) -1 else visibleContentBottom(content, panel)
        val gridBottom = if (content != null && measured > 0) screenTop(content) + measured
            else screenBottom(grid)
        val barTop = if (bar == null) -1 else screenTop(bar)
        if (bar == null) {
            // 找不到底栏：退回"按宫格长高的那一截加高"，一轮定死，不做闭环。
            val grow = growOf(grid)
            Diag.note("tools_grid_room", "no_bar grow=" + grow)
            if (grow > 0 && roomExtra < grow) {
                roomExtra = grow
                reapplyPanel(controller, gridBottom, barTop, "no_bar")
            }
            roomConverged = true
            return
        }
        val overlap = (gridBottom - barTop) + ROOM_MARGIN
        Diag.note(
            "tools_grid_room", "calib r" + round + " extra=" + roomExtra
                    + " gridBottom=" + gridBottom + " barTop=" + barTop + " overlap=" + overlap
        )
        Cfg.log(
            "TPE room calib r" + round + " extra=" + roomExtra
                    + " gridBottom=" + gridBottom + " barTop=" + barTop
                    + " overlap=" + overlap + " levels=" + H.call(controller, "getLevelHeights")
        )
        if (Math.abs(overlap) <= ROOM_MARGIN) {
            roomConverged = true
            Diag.note("tools_grid_room", "converged extra=" + roomExtra)
            Cfg.log("TPE room converged extra=" + roomExtra + " round=" + round)
            return
        }
        if (round >= ROOM_ROUNDS) {
            Diag.note("tools_grid_room", "rounds_exhausted overlap=" + overlap)
            return
        }
        val cap = levelCap(controller)
        var next = roomExtra + overlap
        val lowestExtra = ((H.call(controller, "getLevelHeights") as? List<*>)?.firstOrNull() as? Int
            ?: roomBase1) - roomBase1
        if (next < lowestExtra) next = lowestExtra
        if (cap > 0 && next > cap) next = cap
        if (next == roomExtra) {
            Diag.note("tools_grid_room", "clamped extra=" + roomExtra + " cap=" + cap)
            return
        }
        roomExtra = next
        reapplyPanel(controller, gridBottom, barTop, "r" + round)
        TmapPanelFit.request(grid)
        grid.postDelayed(object : Runnable {
            override fun run() {
                calibrate(round + 1)
            }
        }, 700L)
    }

    /**
     * 把当前 {@code roomExtra} 真正落到面板上。
     *
     * <p>顺序要紧：先 {@code getLevelHeights()} 让本模块的钩子把缓存档位表改掉，
     * 再点名那个高度 —— {@code SlideCardView#setLevelWithHeight(i)} 只在
     * {@code adapter.getLevelHeights().indexOf(i) != -1} 时才动，改完表才叫得动。
     *
     * <p>优先直接点名 {@code setLevelWithHeight}：{@code ewt#y()} 里还有
     * {@code HomePageCardView.getCurLevel()} 与 {@code MapModule} 两道判断，
     * 任何一道不满足就落回原档（真机实测确实会这样）。
     */
    private fun reapplyPanel(controller: Any?, gridBottom: Int, barTop: Int, how: String) {
        val levels = H.call(controller, "getLevelHeights")   // 触发 raiseMiddle 重算
        if (levels !is List<*>) {
            Diag.note("tools_grid_room", "no_levels_" + how)
            return
        }
        val list = levels
        if (list.size < 2 || list[1] !is Int) return
        val panel = findPanelView()
        var applied = false
        if (panel != null) applied = H.call1(panel, "setLevelWithHeight", list[1]) != null
        if (!applied) H.call(controller, "y")     // 退路：腾讯自己的"重新取档位高度"
        Diag.note(
            "tools_grid_room", "applied " + how + " extra=" + roomExtra
                    + " level1=" + list[1] + " levels=" + list
                    + " panel=" + (if (panel == null) "null" else panel.javaClass.simpleName)
                    + " gridBottom=" + gridBottom + " barTop=" + barTop
        )
        Cfg.log(
            "TPE room applied " + how + " extra=" + roomExtra + " levels=" + list
                    + " panel=" + (if (panel == null) "null" else panel.javaClass.simpleName)
                    + " gridBottom=" + gridBottom + " barTop=" + barTop
                    + " gridH=" + (if (lastGrid == null) -1 else lastGrid!!.height)
        )
    }

    /** 档位表能接受的最大额外量：档 2 与档 0 的差（不越到展开档）。 */
    private fun levelCap(controller: Any?): Int {
        try {
            val levels = H.call(controller, "getLevelHeights")
            if (levels is List<*> && levels.size >= 3) {
                val l = levels
                if (l[0] is Int && l[2] is Int) {
                    return (l[2] as Int) - (l[0] as Int)
                }
            }
        } catch (ignored: Throwable) {
        }
        return 400
    }

    /** 宫格"一排 → 满行"多出来的高度；只在找不到底栏时当退路用。 */
    private fun growOf(grid: View): Int {
        val max = H.call(grid, "getViewMaxHeight")
        val mid = H.call(grid, "getViewMidHeight")
        if (max is Int && mid is Int) {
            return max - mid
        }
        return 0
    }

    private fun screenTop(v: View): Int {
        val p = IntArray(2)
        v.getLocationOnScreen(p)
        return p[1]
    }

    private fun screenBottom(v: View): Int {
        val p = IntArray(2)
        v.getLocationOnScreen(p)
        return p[1] + v.height
    }

    /**
     * 从宫格所在的 root 往下找底栏容器 {@code GradientTabBarContainer}。
     * 只在第一次命中后缓存；隐藏（GONE）时视为没有底栏。
     */
    private fun findBottomBar(grid: View): View? {
        val cached = barView
        if (cached != null && cached.isAttachedToWindow
            && cached.visibility == View.VISIBLE
        ) return cached
        val found = findBar(grid.rootView, 0)
        if (found != null) barView = found
        return found
    }

    private fun findBar(v: View?, depth: Int): View? {
        if (v == null || depth > 26) return null
        if (v.visibility == View.VISIBLE
            && v.javaClass.name.contains("GradientTabBarContainer")
        ) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findBar(v.getChildAt(i), depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    /**
     * 找面板本体。{@code ewt#y()} 里那句
     * {@code HomePageCardView homePageCardView = this.J} 说明真机上 {@code this.J}
     * 就是 {@code HomePageCardView} —— 它是 {@code slideview.SlideCardView} 的<b>子类</b>，
     * 类名里没有 {@code SlideCardView}，所以只按那个字符串找会一直 miss（上一版就是
     * {@code panel=null}）。这里同时认字符串、{@code HomePageCardView} 与运行时
     * {@code SlideCardView.isInstance}，父链里没有就从 root 里找。
     */
    private fun findPanelView(): View? {
        val cached = panelView
        if (cached != null && cached.isAttachedToWindow) return cached
        var v: View? = lastGrid
        while (v != null) {
            if (isPanel(v)) {
                panelView = v
                return v
            }
            val parent: android.view.ViewParent? = v.parent
            v = if (parent is View) parent else null
        }
        val root: View? = if (lastGrid == null) null else lastGrid!!.rootView
        val found = findSlide(root, 0)
        if (found != null) panelView = found
        return found
    }

    private fun isPanel(v: View): Boolean {
        val slide = slideClass
        if (slide != null && slide.isInstance(v)) return true
        var cls: Class<*>? = v.javaClass
        while (cls != null) {
            if (cls.name == SLIDE_VIEW ||
                cls.name == "com.tencent.map.ama.newhome.widget.HomePageCardView") return true
            cls = cls.superclass
        }
        return false
    }

    private fun findSlide(v: View?, depth: Int): View? {
        if (v == null || depth > 26) return null
        if (isPanel(v)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findSlide(v.getChildAt(i), depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    @Volatile
    private var panelView: View? = null

    /** {@code SlideCardView} 的运行时类，用来做 instanceof 判断（子类名可能不同）。 */
    @Volatile
    private var slideClass: Class<*>? = null

    private fun roomOn(): Boolean {
        try {
            return Cfg.visible(K_TOOL_GRID_ROOM, true) && expandOn()
        } catch (t: Throwable) {
            return true
        }
    }

    /**
     * gaa 在输入列表达到宫格容量时插入名为 {@code more} 的合成 Cell。
     * 这个 Cell 还可能包着被延后的真实工具；关闭「更多」时要解包而不是简单删除，
     * 否则第 5/第 10 个真实工具会随包装一起丢失。
     */
    private fun installMoreFilter(cl: ClassLoader) {
        val adapter = H.cls(cl, HOME_ADAPTER)
        val data = H.cls(cl, HOME_DATA)
        cellClass = H.cls(cl, HOME_CELL)
        if (adapter == null || data == null || cellClass == null) {
            Diag.note("tools_more_hook", "miss_adapter")
            return
        }
        H.hookSig(adapter, "a", "tmap_tools_more", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val original = chain.getArg(0)
                val filtered = filterData(original)
                val result = if (filtered === original || filtered == null) chain.proceed()
                else chain.proceed(arrayOf<Any?>(filtered))
                val grid = Cfg.visible(Spec.K_TOOLS, true)
                if (!grid || !Cfg.toolVisible("more")) {
                    filterSyntheticMore(chain.getThisObject(), grid)
                }
                return result
            }
        }, data)
        Diag.note("tools_more_hook", "gaa.a(gbt)")
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterSyntheticMore(adapter: Any?, grid: Boolean) {
        val raw = H.field(adapter, "l")
        if (raw !is List<*>) return
        val current = raw as MutableList<Any?>
        val compact = ArrayList<Any?>(current.size)
        var changed = !grid && !current.isEmpty()
        for (i in current.indices) {
            val cell = current[i]
            if (!grid) continue
            if ("more" == cellId(cell)) {
                val unwrapped = H.field(cell, "c")
                val type = cellClass
                if (type != null && type.isInstance(unwrapped)) compact.add(unwrapped)
                changed = true
            } else {
                compact.add(cell)
            }
        }
        if (!changed) return
        try {
            current.clear()
            current.addAll(compact)
            // gaa.k() posts the adapter notification through the existing UI path.
            H.call(adapter, "k")
            Diag.count("tool_more", 1, 1)
        } catch (t: Throwable) {
            Diag.note("tools_more_filter", "failed_" + t.javaClass.simpleName)
        }
    }

    /**
     * 通过已知 public 无参构造器复制 gbt/gbs；不修改腾讯持有的对象或其偏好队列。
     * 复制失败时返回原对象，失效安全地保持原首页。
     */
    private fun filterData(original: Any?): Any? {
        if (original == null) return null
        val group = H.call(original, "c")
        val raw = H.field(group, "b")
        if (raw !is List<*>) return original
        val cells = raw
        val kept = ArrayList<Any?>(cells.size)
        val grid = Cfg.visible(Spec.K_TOOLS, true)
        val more = Cfg.toolVisible("more")
        var changed = !more || !grid
        for (i in cells.indices) {
            val cell = cells[i]
            val id = cellId(cell)
            if (!grid || id != null && id.length > 0 && !Cfg.toolVisible(id)) {
                changed = true
                continue
            }
            kept.add(cell)
        }
        // 诊断：宫格这一组**实际**用到的 id。默认可见策略（Spec#defaultToolVisible）就是
        // 按这套 id 判的，所以核对默认开关必须看这一行，而不是看服务端目录那一份。
        // 只在「调试日志」打开时输出，正常使用不产生日志。
        if (Cfg.debug()) {
            val ids = StringBuilder()
            for (i in cells.indices) {
                if (ids.length > 0) ids.append(',')
                ids.append(cellId(cells[i]))
            }
            Cfg.log("grid_cells=" + ids + " kept=" + kept.size + "/" + cells.size)
        }
        // 兜底：默认可见策略若会把宫格**整片清空**，而用户并没有显式关掉其中任何一格，
        // 就原样放行。这正是 Spec#defaultToolVisible 里记的那次"宫格被过滤空"的教训：
        // 白名单与实际 id 一旦不是同一套，全摘 = 宫格消失。
        if (grid && kept.isEmpty() && !cells.isEmpty()) {
            var explicitOff = false
            for (i in cells.indices) {
                val id = cellId(cells[i])
                if (id != null && id.length > 0 && Cfg.explicit(Spec.K_TOOL + id)) {
                    explicitOff = true
                    break
                }
            }
            if (!explicitOff) {
                Diag.note("tools_filter", "defaults_would_empty_grid_kept_original")
                return original
            }
        }
        if (!changed) return original
        return cloneData(original, group, kept, more)
    }

    private fun cloneData(original: Any?, group: Any?, cells: List<*>, more: Boolean): Any? {
        try {
            val copy = newInstance(original!!.javaClass)
            val groupCopy = newInstance(group!!.javaClass)
            val ok = H.setField(groupCopy, "a", H.field(group, "a")) &&
                H.setField(groupCopy, "b", ArrayList<Any?>(cells)) &&
                H.setField(copy, "a", groupCopy) &&
                H.setField(copy, "b", H.field(original, "b")) &&
                H.setField(copy, "c", H.field(original, "c")) &&
                // Disable only the synthetic More wrapping when More is hidden.
                H.setField(copy, "d", if (more) H.field(original, "d") else java.lang.Boolean.FALSE)
            if (!ok) return original
            return copy
        } catch (t: Throwable) {
            Diag.note("tools_grid_clone", "failed_" + t.javaClass.simpleName)
            return original
        }
    }

    private fun newInstance(type: Class<*>): Any {
        val cached = CONSTRUCTORS[type]
        if (cached != null) return cached.newInstance()
        val created = type.getDeclaredConstructor()
        created.setAccessible(true)
        CONSTRUCTORS[type] = created
        return created.newInstance()
    }

    private fun cellId(cell: Any?): String? {
        val tool = H.field(cell, "a")
        return toolId(tool)
    }

    /** 配置变更后重送最近一次原始 gbt；仅触碰已登记的首页控件。 */
    @JvmStatic
    fun onConfigChanged(key: String?) {
        if (key == null || key == Spec.K_TOOLS || key.startsWith(Spec.K_TOOL)
            || key == Spec.K_TOOL_PRESET
        ) refresh()
    }

    @JvmStatic
    fun refresh() {
        synchronized(HOMES) {
            for (entry in HOMES.entries) {
                val view = entry.key
                val state = entry.value
                val data = state.data ?: continue
                if (state.pending) continue
                val signature = configSignature(data)
                if (signature == state.signature) continue
                gridForced = false
                state.pending = true
                if (!view.post(object : Runnable {
                        override fun run() {
                            val current: Any?
                            synchronized(HOMES) {
                                state.pending = false
                                current = state.data
                            }
                            if (current != null) H.call1(view, "updateItemList", current)
                        }
                    })
                ) state.pending = false
            }
        }
    }

    /** 只在配置/前台恢复事件上比较当前首页涉及的键，值没变就不重新布局。 */
    private fun configSignature(data: Any?): String {
        val signature = StringBuilder()
        signature.append(if (Cfg.visible(Spec.K_TOOLS, true)) '1' else '0')
        signature.append(if (Cfg.visible(Spec.K_TOOL_PRESET, false)) '1' else '0')
        signature.append(if (Cfg.toolVisible("more")) '1' else '0')
        val group = H.call(data, "c")
        val raw = H.field(group, "b")
        if (raw is List<*>) {
            val cells = raw
            for (i in cells.indices) {
                val id = cellId(cells[i])
                signature.append(id).append(':')
                    .append(if (Cfg.toolVisible(id)) '1' else '0').append(';')
            }
        }
        return signature.toString()
    }

    /**
     * 只做**只读**的目录回报：从 {@code GetHomepageGroupToolsResponse#readFrom} 之后
     * 把每个分组的真实工具 id 学回来，供设置页渲染逐项开关。
     * 这里绝不修改任何数据。
     */
    private fun installCatalogueReport(cl: ClassLoader, jceIn: Class<*>?) {
        val resp = H.cls(cl, "com.tencent.map.jce.MapTools\$GetHomepageGroupToolsResponse")
            ?: H.cls(cl, "com.tencent.map.jce.MapTools.GetHomepageGroupToolsResponse")
        H.hookSig(resp, "readFrom", "tmap_tools_resp", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                try {
                    reportCatalog(chain.getThisObject())
                } catch (ignored: Throwable) {
                }
                return result
            }
        }, jceIn)

        val group = H.cls(cl, "com.tencent.map.jce.MapTools\$Group")
            ?: H.cls(cl, "com.tencent.map.jce.MapTools.Group")
        H.hookSig(group, "readFrom", "tmap_tools_group", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                try {
                    reportGroup(chain.getThisObject())
                } catch (ignored: Throwable) {
                }
                return result
            }
        }, jceIn)
    }

    // ── 目录回报（只读） ────────────────────────────────────────────────

    private fun reportCatalog(resp: Any?) {
        val groups = H.field(resp, "groups")
        if (groups !is List<*>) return
        val catalog = LinkedHashMap<String, String>()
        val list = groups
        for (i in list.indices) {
            val g = list[i]
            val toolsRaw = H.field(g, "tools")
            val tools = if (toolsRaw is List<*>) toolsRaw else H.field(g, "cache_tools")
            if (tools !is List<*>) continue
            val tl = tools
            for (j in tl.indices) {
                val t = tl[j]
                val id = toolId(t)
                if (id == null || id.length == 0) continue
                val title = H.field(t, "title")
                if (!catalog.containsKey(id)) {
                    catalog.put(id, if (title is String) title else id)
                }
            }
        }
        val sb = StringBuilder()
        for (entry in catalog.entries) {
            if (sb.length > 0) sb.append('\n')
            sb.append(entry.key).append('\t').append(entry.value)
        }
        if (sb.length > 0) {
            val value = sb.toString()
            if (value != lastCatalog) {
                lastCatalog = value
                Report.send(null, "tools", value, false)
            }
        }
    }

    /** 只回报分组结构（组名 + id 列表），用于排障，不改数据。 */
    private fun reportGroup(group: Any?) {
        if (group == null) return
        val rawTools = H.field(group, "tools")
        val raw = if (rawTools is List<*>) rawTools else H.field(group, "cache_tools")
        if (raw !is List<*>) return
        val tools = raw
        val ids = StringBuilder()
        var seen = 0
        for (i in tools.indices) {
            seen++
            val id = toolId(tools[i])
            if (ids.length < 160) {
                if (ids.length > 0) ids.append(',')
                ids.append(if (id == null) "?" else id)
            }
        }
        val gname = H.field(group, "name")
        val tag = "grp_" + (if (gname is String && gname.length > 0) gname else "?")
        Diag.note(tag, seen.toString() + "[" + ids + "]")
        Diag.note("tools_grid", if (Cfg.visible(Spec.K_TOOLS, true)) "on" else "off")
    }

    /** 工具的稳定标识：优先服务端 name，其次 title。 */
    private fun toolId(tool: Any?): String? {
        val name = H.field(tool, "name")
        if (name is String && name.length > 0) return name
        val title = H.field(tool, "title")
        if (title is String && title.length > 0) return title
        return null
    }

    // ── 临时取证：宫格视图树落 logcat（定位重影用，定位后删除） ────────────────

    /**
     * 布局完成后再 dump：调用点跑在 {@code chain.proceed()} 之后，但 RecyclerView 的
     * 回收与重排是 post 到下一帧的，立即读会读到旧位置。
     */
    private fun scheduleDump(root: View?, tag: String) {
        if (root == null) return
        val target: View = root
        target.postDelayed(object : Runnable {
            override fun run() {
                try {
                    dumpTree(target, tag)
                } catch (t: Throwable) {
                    note("dump_" + tag, t)
                }
            }
        }, 400L)
    }

    private fun dumpTree(root: View, tag: String) {
        android.util.Log.i("TMapClean", "TPD ----- " + tag + " -----")
        dumpView(root, 0, tag)
        android.util.Log.i("TMapClean", "TPD ----- end " + tag + " -----")
    }

    private fun dumpView(v: View, depth: Int, tag: String) {
        try {
            val line = StringBuilder(200)
            for (i in 0 until depth) line.append("  ")
            line.append(v.javaClass.name)
            val id = v.id
            if (id != View.NO_ID) {
                try {
                    line.append(" #").append(v.resources.getResourceEntryName(id))
                } catch (t: Throwable) {
                    line.append(" #0x").append(Integer.toHexString(id))
                }
            }
            line.append(" vis=").append(v.visibility)
            line.append(" a=").append(v.alpha)
            line.append(" xy=").append(v.x).append(',').append(v.y)
            line.append(" wh=").append(v.width).append('x').append(v.height)
            line.append(" tXY=").append(v.translationX).append(',').append(v.translationY)
            line.append(" tZ=").append(v.translationZ)
            if (v is ViewGroup) {
                line.append(" kids=").append(v.childCount)
                line.append(" clip=").append(v.clipChildren)
                if (v.javaClass.name.contains("RecyclerView")) {
                    line.append(" rv")
                    val adapter = H.call(v, "getAdapter")
                    if (adapter != null) {
                        val count = H.call(adapter, "getItemCount")
                        line.append(" itemCount=").append(count)
                    }
                }
            }
            android.util.Log.i("TMapClean", "TPD " + line)
            if (v is ViewGroup && depth < 8) {
                for (i in 0 until v.childCount) dumpView(v.getChildAt(i), depth + 1, tag)
            }
        } catch (t: Throwable) {
            note("dumpview", t)
        }
    }

    private fun note(key: String, t: Throwable) {
        try {
            android.util.Log.w("TMapClean", "TPD fail " + key + ": " + t)
        } catch (ignored: Throwable) {
        }
    }

    // ── 宫格三排取证：关键字段值落 logcat（tag TPE） ─────────────────────
    //
    // 与上面 TPD 的整棵视图树互补：TPD 给"看得见的结果"（容器 wh、每个格子的 alpha），
    // 这里给"决定它的那几个数"（宫格自算的三档高度、适配器比值 s、宫格档位 gay.a）。
    // 判据就是这两者对齐：容器高度 == getViewMaxHeight()，且第二/三排 alpha == 1.0。

    private fun scheduleFacts(root: View?, tag: String) {
        if (root == null) return
        val target: View = root
        target.postDelayed(object : Runnable {
            override fun run() {
                try {
                    dumpFacts(target, tag)
                } catch (t: Throwable) {
                    note("facts_" + tag, t)
                }
            }
        }, 500L)
    }

    private fun dumpFacts(v: View, tag: String) {
        val b = StringBuilder(320)
        b.append("TPE ----- ").append(tag).append(" -----")
        b.append("\nTPE grid w=").append(v.width).append(" h=").append(v.height)
        b.append(" lp.h=").append(lpHeight(v))
        b.append(" minH=").append(v.minimumHeight)
        b.append(" vis=").append(v.visibility)
        b.append(" clip=").append(
            if (v is ViewGroup) v.clipChildren.toString() else "n/a"
        )

        // 宫格自己算出来的三档高度：mid = 一排，max = (itemCount-1)/5 个附加行。
        b.append("\nTPE heights mid=").append(H.call(v, "getViewMidHeight"))
        b.append(" max=").append(H.call(v, "getViewMaxHeight"))
        b.append(" single=").append(H.call(v, "getSingleLineHeight"))
        b.append(" toolsItemSize=").append(H.call(v, "getToolsItemSize"))

        // 决定 alpha 的两处：适配器比值 s，与宫格档位 gay.a（1低/2中/3高）。
        val adapter = H.field(v, "h")
        b.append("\nTPE adapter=").append(if (adapter == null) "null" else adapter.javaClass.name)
        b.append(" itemCount=").append(H.call(adapter, "getItemCount"))
        b.append(" s=").append(H.field(adapter, "s"))
        b.append(" r=").append(H.field(adapter, "r"))
        b.append(" holders=").append(sizeOf(H.field(adapter, "m")))
        b.append("\nTPE level gay.a=").append(levelA(v.javaClass.classLoader))

        // 父容器：宫格撑高后会不会被面板裁掉，看这两层的高度就够。
        if (v.parent is View) {
            val p = v.parent as View
            b.append("\nTPE parent ").append(p.javaClass.name)
            b.append(" wh=").append(p.width).append('x').append(p.height)
            b.append(" lp.h=").append(lpHeight(p))
            if (p.parent is View) {
                val g = p.parent as View
                b.append("\nTPE grandparent ").append(g.javaClass.name)
                b.append(" wh=").append(g.width).append('x').append(g.height)
                b.append(" lp.h=").append(lpHeight(g))
            }
        }

        // 每个格子的 alpha + top：一眼看出"第二/三排到底可不可见"。
        if (v is ViewGroup) {
            b.append("\nTPE kids=").append(v.childCount)
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                b.append("\nTPE   #").append(i).append(' ')
                try {
                    b.append(c.javaClass.simpleName)
                } catch (t: Throwable) {
                    b.append('?')
                }
                b.append(" top=").append(c.top).append(" h=").append(c.height)
                b.append(" a=").append(c.alpha).append(" vis=").append(c.visibility)
            }
        }
        android.util.Log.i("TMapClean", b.toString())
    }

    private fun lpHeight(v: View): Int {
        try {
            val lp = v.layoutParams
            return if (lp == null) Integer.MIN_VALUE else lp.height
        } catch (t: Throwable) {
            return Integer.MIN_VALUE
        }
    }

    private fun sizeOf(list: Any?): Int {
        if (list is List<*>) return list.size
        return -1
    }

    /** 宫格档位 {@code gay.a}：1=低（一排）、2=中、3=高（三排）。 */
    private fun levelA(cl: ClassLoader?): Any? {
        if (cl == null) return "?"
        try {
            val gay = cl.loadClass("com.tencent.map.navisdk.a.gay")
            val f = gay.getDeclaredField("a")
            f.setAccessible(true)
            return f.get(null)
        } catch (t: Throwable) {
            return "?" + t.javaClass.simpleName
        }
    }
}
