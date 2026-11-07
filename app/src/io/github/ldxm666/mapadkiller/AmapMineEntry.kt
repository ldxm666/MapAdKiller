package io.github.ldxm666.mapadkiller

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout

import java.lang.ref.WeakReference
import java.util.Collections
import java.util.LinkedHashSet

import io.github.libxposed.api.XposedInterface
import io.github.ldxm666.mapclean.FloatingPanel

/**
 * 模块的<b>唯一入口</b>：长按底部「我的」tab → 弹出模块自己的设置面板。
 *
 * <h3>为什么是这个入口（而不是往「我的」页塞第 4 个图标）</h3>
 * 往胶囊里注入第 4 个图标这条路，在 17.01 / 17.02 真机上连试五轮都没出现过：
 * 先是 layout 监听丢失（resume 时窗口还没 attach），再是 probe 扫到的是**没有窗口的空壳树**
 * （`NewMapActivity` 是 RESUMED，但窗口属于 `SplashActivity`），再是胶囊 381px 塞不下第 4 格，
 * 最后是探针在冷启动首页就把 5 次配额烧光（`give_up reason=attempts n=5`）。
 * ⇒ **2026-10-20 定案：整条探针路径已删除**（连同 `probe/scan/inject/verify/placeInRow/
 * alignBesidePill/pillOf/iconDesc/containsEntry/buildParams/stepOf/glyph/applyState/toggle`
 * 与 `INJECTED/PENDING/DIAG/TRACKED` 及各轮询常量，约 1300 行）。
 * 模块里**不再有任何扫视图树的代码**，UI 线程零遍历 —— 这是「性能优先」的硬要求。
 *
 * <h3>为什么长按这条路能成（真机验证过）</h3>
 * <pre>
 * 2026-10-20 02:30:52 设备 975aaf17 / 高德 17.02.0.1541
 * event=mine_entry_long_press held=750 mine=true view=TabItemLayoutV2
 * event=mine_entry_open_panel near=false
 * event=mine_entry_own_panel shown=true host=DecorView decor=1080x2400 children=2
 * event=mine_entry_own_panel measured=1080x2400 vis=0 open=true hostAttached=true
 * uiautomator dump 抓到面板真实文字：「恢复默认配置」「首页工具宫格」「底栏」「其他」…
 * </pre>
 * 三个关键点（都是真机踩出来的）：
 * <ol>
 *   <li>`LiteTabBar.dispatchTabTouchEvent` 是**必然触发**的（HIT `amap_tab_dispatch*`），
 *       而 decor 的 layout 回调不一定跑；</li>
 *   <li>长按**不能靠 ACTION_UP 算时长** —— `input swipe` 式长按只送 DOWN、不送 UP；所以改成
 *       「按下后 [TAB_LONG_PRESS_MS] 自触发」，UP/CANCEL/CLICK 只负责取消；</li>
 *   <li>面板**必须在 `host.post()` 的下一帧才 `open()`**：`open()` 内部先
 *       `card.setTranslationY(420dp)` 再动画回 0，在 addView 同一帧调用会让卡片**停在屏幕外**
 *       （面板 VISIBLE、尺寸也对，但一个字都看不见）。</li>
 * </ol>
 *
 * <h3>为什么不依赖 Activity</h3>
 * 17.02 上「resume 的 Activity」和「拥有窗口的 Activity」是**分离**的（`NewMapActivity` vs
 * `SplashActivity`），Context 链也指向错的那个。所以这里全程只用 <b>View</b>：
 * `pressedView.getRootView()` 就是真正在显示的 DecorView，对象恒等，不受任何包装影响。
 * 面板也因此是**自己托管**的（[showOwnPanel]），不依赖悬浮球是否挂上。
 */
object AmapMineEntry {

    /**
     * 应急开关（**默认 false = 入口开启**）。置 true 时 [install] 直接返回，连 hook 都不装。
     *
     * <p>读法注意：不能用 `FloatingConfig.read(K_MINE_ENTRY_OFF)` —— 那条路径对未知 key
     * 走 `Config.defaultVisible()`，而它对未知 key 返回 **true**，会让入口默认变成「关」。
     * 这里要「原始布尔 + 默认 false」，所以用 `Config.selected()`。
     *
     * <p>⚠️ 用这个开关做 A/B 之后**必须改回 false**：本轮的「改了没反应」就是它被留在 true 造成的。
     */
    private const val K_MINE_ENTRY_OFF = "mine_entry_off"

    /**
     * 「我的」tab 的**可接受文案集合**。
     *
     * <p>★ 真机 r21 阻断性 bug：**未登录时高德把 tab 文案渲染成「未登录」**
     * （`TextView t='未登录' rid='tab_name_v2'`），只认「我的」会让**所有全新安装 / 未登录用户
     * 100% 打不开面板**。所以判据必须是不依赖单一文案的集合 + 身份判定。
     */
    private val MINE_LABELS = arrayOf("我的", "未登录", "我的 ", "My")

    /** 已知的「非我的」tab 文案：末位兜底判据先排除它们，避免误弹。 */
    private val OTHER_LABELS = arrayOf(
            "首页", "探索", "工具", "内容", "地图", "打车", "长按说话", "附近", "消息", "我的位置"
    )

    /** `mine_tab_probe` 去重：每个 `label|via` 只打一次，最多 32 条。 */
    private val SEEN_LABELS: MutableSet<String> =
            Collections.synchronizedSet(LinkedHashSet<String>())

    /** 按住多久算长按。 */
    private const val TAB_LONG_PRESS_MS = 700L

    /** 自己托管的面板与其宿主（弱引用，随窗口回收）。 */
    @Volatile
    private var SELF_HOST: WeakReference<ViewGroup?> = WeakReference<ViewGroup?>(null)
    @Volatile
    private var SELF_PANEL: WeakReference<FloatingPanel?> = WeakReference<FloatingPanel?>(null)

    /** 当前这一次按下的状态（长按自触发用）。 */
    @Volatile
    private var tabDownAt: Long = 0
    @Volatile
    private var tabFired = false

    /** DOWN 那一刻就判好的「是不是我的 tab」+ 命中的判据（供 750ms 后自触发直接读）。 */
    @Volatile
    private var tabDownMine = false
    @Volatile
    private var matchVia = "none"
    @Volatile
    private var tabDownView: WeakReference<View?> = WeakReference<View?>(null)

    /**
     * 长按弹过面板后，记录「这一次点击是长按跟来的」（**仅记录，不拦截**）。
     *
     * <p>历史：v1 曾在这里「吞掉」这次点击（不调用 `chain.proceed()`），结果短路了
     * 整条 hook 链、把宿主的 tab 状态机弄坏 —— 首次长按之后就再也弹不出来（r22）。
     * 现在只做记录，**永远 proceed**。
     */
    @Volatile
    private var swallowNextClick = false
    @Volatile
    private var swallowAt: Long = 0
    private const val SWALLOW_WINDOW_MS = 800L

    /** 由 [AmapHooks] 的 feature 机制调用；任何异常都只会变成一条日志。 */
    @JvmStatic
    fun install(cl: ClassLoader) {
        // ★★ 模块模式（root / LSPosed 独立安装）**一条 hook 都不装**。
        //
        // 用户明确要求：「长按我的页弹出设置页面不需要对于模块用户，这个只给免 root 用户用的，请移除！」
        // ⇒ 模块用户改用模块 App 自己的设置页（MainActivity），不需要应用内入口。
        //
        // 注意这是**入口处的门控**，不是「装上再 return」：`installTabTrigger` 根本不会被调用，
        // 所以模块模式下 `LiteTabBar` 上一条 hook 都没有 —— 与「关闭 = 不加载、不消耗性能」同一原则。
        // 实现代码全部保留（以后可能还要用），只是不执行。
        val embedded: Boolean
        try {
            embedded = HostInfo.isEmbedded()
        } catch (t: Throwable) {
            // 判定失败时按「模块模式」处理：宁可不装，也不要在 root 用户身上多挂 hook。
            // 单独一条日志，避免和「确实是模块模式」混淆（排查时一眼能分开）。
            H.log("event=mine_entry_skipped reason=hostinfo_error err="
                    + t.javaClass.simpleName)
            return
        }
        if (!embedded) {
            H.log("event=mine_entry_skipped reason=module_mode")
            return
        }
        val off: Boolean = try {
            Config.selected(K_MINE_ENTRY_OFF)
        } catch (t: Throwable) {
            false   // 读不到配置就按「开启」处理，绝不因为读配置失败把入口弄没
        }
        if (off) {
            H.log("event=mine_entry_off switch=" + K_MINE_ENTRY_OFF + " (hooks NOT installed)")
            return
        }
        installTabTrigger(cl)
    }

    /**
     * 挂 tab 钩子。`H.hookAll` **按方法名挂全部重载**（`dispatchTabTouchEvent` 有多个重载，
     * 写死签名一换版就静默失效）；同一个方法被 `AmapTabLayout` 也挂一次是合法的。
     */
    private fun installTabTrigger(cl: ClassLoader) {
        try {
            val bar = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar")
            if (bar == null) {
                // 抗混淆锚点丢了：只记一条，绝不硬编码别的类名去猜。
                H.log("event=anchor_miss anchor=LiteTabBar")
                return
            }
            var n = 0
            n += H.hookAll(bar, "dispatchTabTouchEvent", "amap_mine_entry_tab_touch", TAB_TOUCH)
            // 点击 = 不是长按：用来清掉待触发的长按状态，避免「轻点一下也弹面板」。
            n += H.hookAll(bar, "dispatchTabClick", "amap_mine_entry_tab_click", TAB_CLICK)
            n += H.hookAll(bar, "dispatchTabClickBefore", "amap_mine_entry_tab_click2", TAB_CLICK)
            H.log("event=mine_entry_tab_trigger hooks=" + n)
        } catch (t: Throwable) {
            H.log("event=mine_entry_tab_error err=" + t.javaClass.simpleName)
        }
    }

    /**
     * tab 触摸钩子：识别长按。
     *
     * <p>★ 不靠 ACTION_UP 算时长：`input swipe` 式长按**只送 DOWN、不送 UP**
     * （真机实测），靠 UP 判永远不触发。这里 DOWN 之后排一个 [TAB_LONG_PRESS_MS]+50ms
     * 的一次性任务，到点仍未取消就直接触发；UP / CANCEL / CLICK 都只负责把状态清掉。
     */
    private val TAB_TOUCH: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            try {
                var event: MotionEvent? = null
                var touched: View? = null
                var model: Any? = null
                val args = chain.getArgs()
                if (args != null) {
                    var i = 0
                    while (i < args.size && i < 4) {
                        val a = args[i]
                        if (a != null) {
                            if (event == null && a is MotionEvent) {
                                event = a
                            } else if (touched == null && a is View) {
                                touched = a
                            } else if (model == null && a !is MotionEvent && a !is View) {
                                // dispatchTabTouchEvent(tab, View, MotionEvent) 的第 1 个参数 = tab 模型
                                model = a
                            }
                        }
                        i++
                    }
                }
                if (event == null) return result
                val action = event.actionMasked
                if (action == MotionEvent.ACTION_DOWN) {
                    tabDownAt = android.os.SystemClock.uptimeMillis()
                    tabDownView = WeakReference<View?>(touched)
                    tabFired = false
                    // ★ 判定放在 DOWN 这一刻做：此时 tab 模型对象和 View 都拿得到。
                    // 750ms 之后自触发时只读结果（那时可能已经拿不到模型了）。
                    tabDownMine = matchMine(touched, model)
                    if (touched != null) {
                        val ref = WeakReference<View?>(touched)
                        touched.postDelayed(object : Runnable {
                            override fun run() {
                                try {
                                    val down = ref.get()
                                    val downAt = tabDownAt
                                    if (down == null || downAt == 0L || tabFired) return
                                    val held = android.os.SystemClock.uptimeMillis() - downAt
                                    if (held < TAB_LONG_PRESS_MS) return
                                    tabFired = true
                                    onTabLongPress(down, held)
                                } catch (ignored: Throwable) { }
                            }
                        }, TAB_LONG_PRESS_MS + 50L)
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    val downAt = tabDownAt
                    tabDownAt = 0L
                    val down = tabDownView.get()
                    if (!tabFired && down != null && downAt != 0L) {
                        val held = android.os.SystemClock.uptimeMillis() - downAt
                        if (held >= TAB_LONG_PRESS_MS) {
                            tabFired = true
                            onTabLongPress(down, held)
                        }
                    }
                }
            } catch (ignored: Throwable) {
                // 绝不影响宿主
            }
            return result
        }
    }

    /**
     * tab 点击钩子。
     *
     * <p>⚠️ **绝不能再短路**（r22 教训）：上一版在长按后「吞掉」这一次点击（不调用
     * `chain.proceed()`，直接返回 `Boolean.TRUE`），结果 **首次长按之后 tab 栏整体失效**
     * —— 连续 6 次长按连 `dispatchTabTouchEvent` 都不再进我的钩子。
     *
     * <p>原因：libxposed 的 hook 链是**串联**的。某个钩子不调用 `chain.proceed()`，
     * 就等于把排在它后面的**所有**钩子一起短路（包括 `AmapTabLayout` 自己对 tab 状态的
     * 记账）。宿主 tab 状态机从此不完整，后续触摸被它自己吞掉 —— 表现为「入口死掉」。
     *
     * <p>所以这里恢复成「永远 `proceed()`」，只在日志里记录「这次点击是长按之后跟来的」。
     * 代价是页面会顺带切到「我的」，但**远小于「入口整体失效」**。
     */
    private val TAB_CLICK: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                val at = swallowAt
                if (swallowNextClick && at != 0L
                        && android.os.SystemClock.uptimeMillis() - at < SWALLOW_WINDOW_MS) {
                    H.log("event=mine_entry_click_after_long_press")
                }
            } catch (ignored: Throwable) { }
            // 一次点击结束：长按计时与「跟随点击」标记都不该跨次存活。
            swallowNextClick = false
            swallowAt = 0L
            tabDownAt = 0L
            tabDownView = WeakReference<View?>(null)
            return chain.proceed()
        }
    }

    /** 长按真正触发点。 */
    private fun onTabLongPress(down: View, held: Long) {
        val mine = tabDownMine
        val label = textOf(down)
        H.log("event=mine_entry_long_press held=" + held + " mine=" + mine
                + " via=" + matchVia + " label=" + label
                + " view=" + down.javaClass.simpleName)
        if (!mine) {
            // ★ 负向判据：**不去重、无条件打**。放在这里而不是 matchMine 里，是因为
            // matchMine 在**每次 ACTION_DOWN** 都会跑（轻点也跑），在那里打会刷屏；
            // 而「长按了非我的 tab」是低频事件，正是验证末位兜底是否写宽的证据。
            H.log("event=mine_tab_miss label=" + label + " via=none"
                    + " view=" + down.javaClass.simpleName)
            return
        }
        // ① 按「同一窗口」找浮层开面板（不碰 Activity）
        var near = false
        try {
            near = io.github.ldxm666.mapclean.FloatingController.openPanelNear(down)
        } catch (ignored: Throwable) { }
        // ② 浮层没挂上就自己托管一个面板（只依赖按下 View 的根视图，最兜底）
        var self = false
        if (!near) self = showOwnPanel(down)
        // 面板已经弹出来了 ⇒ 吞掉紧随其后的那次点击，页面不切走。
        if (near || self) {
            swallowNextClick = true
            swallowAt = android.os.SystemClock.uptimeMillis()
        }
        // ★ 无条件日志（低频，只在长按时发生）：面板到底有没有弹、走的是哪条路。
        H.log("event=mine_entry_own_panel shown=" + (near || self)
                + " via=" + (if (near) "near" else "self"))
    }

    /**
     * 自己托管一个 [FloatingPanel]，直接挂到按下 View 的**根视图**上。
     *
     * <p>为什么不走 `FloatingController`：真机证据显示悬浮层虽然 attach 了，但 attach 到的是
     * `NewMapActivity`（没有可见窗口），而 tab 栏属于 `SplashActivity` 的窗口 ⇒ 按窗口恒等找不到。
     * 自己挂则完全不依赖这些：`getRootView()` 一定是真正在显示的那个 DecorView。
     */
    private fun showOwnPanel(anchor: View?): Boolean {
        if (anchor == null) return false
        try {
            val open = SELF_PANEL.get()
            if (open != null && open.isOpen()) {          // 再长按一次 = 收起
                open.close()
                removeOwnPanel()
                H.log("event=mine_entry_own_panel closed=true")
                return true
            }
            val root = anchor.rootView
            if (root !is ViewGroup) return false
            val decor = root
            val context = anchor.context ?: return false

            val panel = FloatingPanel(context, isNight(context))
            val host = FrameLayout(context)
            host.clipChildren = false
            host.addView(panel, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT))
            decor.addView(host, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
            SELF_HOST = WeakReference<ViewGroup?>(host)
            SELF_PANEL = WeakReference<FloatingPanel?>(panel)
            panel.setOnClose(object : Runnable {
                override fun run() {
                    removeOwnPanel()
                }
            })
            // ★ 必须等这一帧真正 attach + layout 完再 open()，否则卡片停在屏幕外（详见类注释）。
            host.post(object : Runnable {
                override fun run() {
                    try {
                        panel.open()
                        H.log("event=mine_entry_own_panel shown=true host="
                                + decor.javaClass.simpleName
                                + " size=" + decor.width + "x" + decor.height)
                    } catch (t: Throwable) {
                        H.log("event=mine_entry_own_panel_error err=" + t.javaClass.simpleName)
                    }
                }
            })
            return true
        } catch (t: Throwable) {
            H.log("event=mine_entry_own_panel_error err=" + t.javaClass.simpleName)
            return false
        }
    }

    /** 摘掉自己托管的面板（幂等）。 */
    private fun removeOwnPanel() {
        try {
            val host = SELF_HOST.get()
            SELF_HOST = WeakReference<ViewGroup?>(null)
            SELF_PANEL = WeakReference<FloatingPanel?>(null)
            if (host == null) return
            val parent = host.parent
            if (parent is ViewGroup) parent.removeView(host)
            host.removeAllViews()
        } catch (ignored: Throwable) {
            // best effort
        }
    }

    /**
     * 判定按下的是不是「我的」tab —— **三层判据，任一命中即算**。
     *
     * <ol>
     *   <li><b>文案集合</b>（[MINE_LABELS]）：`我的` / **`未登录`** / `我的 ` / `My`。
     *       未登录时高德把文案渲染成「未登录」，这一层是最小改动、立刻可用；</li>
     *   <li><b>模型身份</b>：反射 tab 模型对象（`dispatchTabTouchEvent` 的第 1 个参数）的
     *       String 字段，任一包含 `mine`（忽略大小写）即算 —— 不受文案本地化/登录态影响；</li>
     *   <li><b>末位兜底</b>：该 View 是父容器的**最后一个**子 View，且文案**不在**已知的其它 tab
     *       文案集合里。有误判风险，所以只在 ①② 都不成立时才用，并打 `via=index` 便于排查。</li>
     * </ol>
     *
     * <p>结果会通过 [matchVia] 暴露，并打一条去重的 `mine_tab_probe`。
     */
    private fun matchMine(view: View?, model: Any?): Boolean {
        matchVia = "none"
        if (view == null) return false
        val label = textOf(view)
        val mine: Boolean
        // ① 文案集合
        if (inSet(MINE_LABELS, label)) {
            matchVia = "label"
            mine = true
        } else if (modelMentionsMine(model)) {
            // ② 模型身份（id / key / name 里含 mine）
            matchVia = "id"
            mine = true
        } else if (isLastTab(view) && !inSet(OTHER_LABELS, label)) {
            // ③ 末位兜底（①② 都不成立时才用）
            matchVia = "index"
            mine = true
        } else {
            mine = false
        }
        probeOnce(label, matchVia, mine)
        return mine
    }

    /** 该 View 自己或它的直接子 View 里的文本（只看一层，不扫树）。 */
    private fun textOf(view: View?): String {
        try {
            var node: View? = view
            var up = 0
            while (up < 4 && node != null) {
                val label = labelOf(node)
                if (label != null) return label.toString().trim()
                val parent = node.parent
                if (parent !is ViewGroup) break
                node = parent
                up++
            }
        } catch (ignored: Throwable) { }
        return ""
    }

    private fun inSet(set: Array<String>, value: String?): Boolean {
        if (value == null) return false
        val v = value.trim()
        if (v.isEmpty()) return false
        for (i in set.indices) {
            if (set[i].trim() == v) return true
        }
        return false
    }

    /** 反射 tab 模型的 String 字段，任一包含 "mine"（忽略大小写）即算命中。 */
    private fun modelMentionsMine(model: Any?): Boolean {
        if (model == null) return false
        // `dispatchTabTouchEvent` 的第 1 个参数在 17.02 上是 **tab id**（可能是 String），
        // 不一定是模型对象 —— 所以先看它本身是不是字符串。
        try {
            if (model is CharSequence) {
                return model.toString().lowercase().contains("mine")
            }
        } catch (ignored: Throwable) { }
        try {
            var c: Class<*>? = model.javaClass
            var depth = 0
            while (depth < 4 && c != null && c != Any::class.java) {
                val fields = c.declaredFields
                for (i in fields.indices) {
                    if (fields[i].type != String::class.java) continue
                    try {
                        fields[i].isAccessible = true
                        val v = fields[i].get(model)
                        if (v is String && v.lowercase().contains("mine")) {
                            return true
                        }
                    } catch (ignored: Throwable) { }
                }
                c = c.superclass
                depth++
            }
        } catch (ignored: Throwable) { }
        return false
    }

    /** 该 View 是不是父容器的最后一个子 View（「我的」在高德底栏是最后一格）。 */
    private fun isLastTab(view: View): Boolean {
        try {
            val parent = view.parent
            if (parent !is ViewGroup) return false
            val index = parent.indexOfChild(view)
            return index >= 0 && index == parent.childCount - 1
        } catch (ignored: Throwable) {
            return false
        }
    }

    /** `mine_tab_probe`：每个 `label|via` 只打一次（最多 32 条），避免长按刷屏。 */
    private fun probeOnce(label: String, via: String, mine: Boolean) {
        try {
            if (SEEN_LABELS.size > 32) return
            if (SEEN_LABELS.add(label + "|" + via)) {
                H.log("event=mine_tab_probe label=" + label + " via=" + via + " mine=" + mine)
            }
        } catch (ignored: Throwable) { }
    }

    /** 该 View 自己或它的直接子 View 里的文本（只看一层）。 */
    private fun labelOf(root: View): CharSequence? {
        if (root is android.widget.TextView) {
            val text = root.text
            if (text != null && text.isNotEmpty()) return text
        }
        if (root is ViewGroup) {
            val count = root.childCount
            var i = 0
            while (i < count && i < 8) {
                val child = root.getChildAt(i)
                if (child is android.widget.TextView) {
                    val text = child.text
                    if (text != null && text.isNotEmpty()) return text
                }
                i++
            }
        }
        return null
    }

    private fun isNight(context: Context): Boolean {
        try {
            val mode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } catch (t: Throwable) {
            return false
        }
    }
}
