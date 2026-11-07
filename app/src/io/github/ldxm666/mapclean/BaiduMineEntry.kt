package io.github.ldxm666.mapclean

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 百度内置版的<b>找回入口</b>：长按底部「我的」那一格约 750ms ⇒ 弹出模块自己的设置面板。
 *
 * <h3>为什么需要它</h3>
 * 百度内置版把 `MainActivity`（模块自己的设置页）裁掉了，也没有 tab 入口 —— 用户一旦把
 * 「显示悬浮球」关掉就<b>再也回不来</b>（实测踩过，上一轮只能把那一行整行删掉，功能残缺）。
 * 这个类就是那条找回路径，与高德 `AmapMineEntry` 同一套已验证的范式：
 * <b>不注入 View、不扫视图树、不依赖 Activity</b>。
 *
 * <h3>锚点（dex-scout2 侦察，classes11，全部非混淆）</h3>
 * <table>
 *   <tr><td>`TabPresenter.doSubscribe()V`</td><td>拿 presenter 实例（`chain.getThisObject()`）</td></tr>
 *   <tr><td>`TabPresenter$Tab`（Enum）</td><td>常量 `NEARBY/ROUTE/USER/THIRD/FOURTH`，
 *       <b>`USER` = 我的</b> —— 判据只用枚举，<b>绝不用文案</b>（高德踩过「未登录」坑）</td></tr>
 *   <tr><td>`getIcon(Tab)ImageView`</td><td>拿「我的」那一格的 View（退 `getTextView` →
 *       再退 `getIconBg` → 最后 `getAnimView`）</td></tr>
 *   <tr><td>`clickTab(Tab)V`</td><td>兜底：listener 没挂上时长按仍能吞掉这次点击</td></tr>
 * </table>
 *
 * <h3>为什么不用 dispatchTabTouchEvent / 不 hook View.onTouchEvent</h3>
 * 百度<b>没有</b>高德那种单点触摸分发钩子（底部 5 格是普通 View + `OnClickListener`）；
 * 而 hook `View.onTouchEvent` 是 framework 方法，会命中整个 App 的<b>所有</b> View ——
 * 地图这种重度视图树会直接掉帧。所以正确做法是：**只给自己关心的那一格挂 OnTouchListener**，
 * 成本 O(1)，且天然只覆盖「我的」。
 */
class BaiduMineEntry private constructor() {

    companion object {

        private const val TAG = "MapClean"

        /* ------------------------------------------------------------------ 锚点 */

        /** presenter 类（锚点：非混淆）。 */
        private const val PRESENTER_CLASS =
            "com.baidu.baidumaps.aihome.panel.presenter.TabPresenter"

        /** tab 枚举类的兜底全名（正常情况下从 `getIcon` 的参数类型反推，不依赖这个字符串）。 */
        private const val TAB_ENUM_HINT =
            "com.baidu.baidumaps.aihome.panel.presenter.TabPresenter\$Tab"

        /** 拿 presenter 实例的方法（0 参）。 */
        private const val M_DO_SUBSCRIBE = "doSubscribe"

        /** 兜底吞点击的方法：`clickTab(Tab)V`。 */
        private const val M_CLICK_TAB = "clickTab"

        /** 「我的」那一格 View 的取法，按优先级排列。 */
        private val M_CELL_GETTERS: Array<String> =
            arrayOf("getIcon", "getTextView", "getIconBg", "getAnimView")

        /** 「我的」在枚举里的常量名 —— **唯一判据**。 */
        private const val CONST_USER = "USER"

        /* ------------------------------------------------------------------ 常量 */

        /** 按住多久算长按。与高德一致（实测不会和系统长按冲突）。 */
        private const val LONG_PRESS_MS = 750L

        /** 长按之后多久内的 clickTab 算「这一次点击是长按跟来的」。 */
        private const val SWALLOW_MS = 800L

        /** 手指移动超过这个距离就取消长按（dp）。 */
        private const val MOVE_SLOP_DP = 6f

        /* ------------------------------------------------------------------ 状态 */

        @Volatile
        private var hostLoader: ClassLoader? = null

        @Volatile
        private var PRESENTER: WeakReference<Any?> = WeakReference<Any?>(null)

        @Volatile
        private var tabEnum: Class<*>? = null

        @Volatile
        private var userTab: Any? = null

        /** 已经挂过 listener 的那一格（同一个实例不重复挂）。 */
        @Volatile
        private var ATTACHED_CELL: WeakReference<View?> = WeakReference<View?>(null)

        @Volatile
        private var lastDownAt = 0L

        @Volatile
        private var swallowAt = 0L

        @Volatile
        private var downX = 0f

        @Volatile
        private var downY = 0f

        @Volatile
        private var moved = false

        @Volatile
        private var SELF_HOST: WeakReference<ViewGroup?> = WeakReference<ViewGroup?>(null)

        @Volatile
        private var SELF_PANEL: WeakReference<FloatingPanel?> = WeakReference<FloatingPanel?>(null)

        /**
         * 安装找回入口。由 `mapclean.MainHook.onPackageReady` 调用。
         *
         * <p>三道门控，**任何一道不满足都一条 hook 都不装**（不是装上再判断）：
         * ① 模块模式（root/LSPosed）—— 用户明确要求长按入口只给免 root 内置版；
         * ② 只在百度进程 —— 高德/腾讯进程绝不能装；
         * ③ 锚点解析失败 —— 跳过并打 `anchor_missing`，绝不盲猜。
         */
        @JvmStatic
        fun install(module: XposedModule?, pkg: String?, cl: ClassLoader?) {
            if (module == null || cl == null) return
            if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) {
                Log.i(TAG, "event=baidu_mine_entry_skipped reason=module_mode")
                return
            }
            Vendor.host(pkg)
            if (!Vendor.baidu()) {
                Log.i(TAG, "event=baidu_mine_entry_skipped reason=not_baidu host=" + Vendor.host())
                return
            }
            hostLoader = cl
            try {
                val presenter: Class<*>
                try {
                    presenter = cl.loadClass(PRESENTER_CLASS)
                } catch (t: Throwable) {
                    Log.i(
                        TAG, "event=baidu_mine_entry_anchor_missing what=presenter class="
                                + PRESENTER_CLASS
                    )
                    return
                }
                if (!resolveTabEnum(presenter)) {
                    Log.i(TAG, "event=baidu_mine_entry_anchor_missing what=tab_enum")
                    return
                }
                var n = 0
                n += hookAll(module, presenter, M_DO_SUBSCRIBE, "baidu_mine_entry_subscribe", SUBSCRIBE)
                n += hookAll(module, presenter, M_CLICK_TAB, "baidu_mine_entry_clicktab", CLICK_TAB)
                n += installResumeHook(module)
                Log.i(
                    TAG, "event=baidu_mine_entry_installed hooks=" + n
                            + " user_tab=" + (if (userTab == null) "null" else userTab.toString())
                )
            } catch (t: Throwable) {
                Log.w(TAG, "event=baidu_mine_entry_error err=" + t.javaClass.simpleName, t)
            }
        }

        /** 从 `getIcon` 的参数类型反推枚举类，并取出 `USER` 常量。 */
        private fun resolveTabEnum(presenter: Class<*>): Boolean {
            var found: Class<*>? = null
            try {
                var k: Class<*>? = presenter
                while (k != null && k !== Any::class.java) {
                    val methods = k.declaredMethods
                    for (m in methods) {
                        if ("getIcon" != m.name) continue
                        val ps = m.parameterTypes
                        if (ps.size == 1 && ps[0].isEnum) {
                            found = ps[0]
                            break
                        }
                    }
                    if (found != null) break
                    k = k.superclass
                }
            } catch (ignored: Throwable) {
            }
            if (found == null) {
                try {
                    found = hostLoader?.loadClass(TAB_ENUM_HINT)
                } catch (t: Throwable) {
                    found = null
                }
            }
            val f = found ?: return false
            try {
                // Java 原版是 `Enum.valueOf((Class) found, CONST_USER)` —— 裸类型转换。
                // Kotlin 的 `Enum.valueOf` 签名要求 `<T : Enum<T>>`，拿不到能自洽的 T；
                // 用 `Class<Nothing>` 做一次 unchecked 转换即可保持同一语义（含未知常量时抛
                // IllegalArgumentException），而不是改成「扫 enumConstants」那种等价但不完全相同的写法。
                @Suppress("UNCHECKED_CAST")
                val constant = java.lang.Enum.valueOf(f as Class<Nothing>, CONST_USER) as Any
                tabEnum = f
                userTab = constant
                return true
            } catch (t: Throwable) {
                Log.i(
                    TAG, "event=baidu_mine_entry_anchor_missing what=USER_constant err="
                            + t.javaClass.simpleName
                )
                return false
            }
        }

        /* ------------------------------------------------------------------ 钩子 */

        /** ① 拿 presenter：`doSubscribe()` 里 `chain.getThisObject()` 就是它。 */
        private val SUBSCRIBE: XposedInterface.Hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                try {
                    capturePresenter(chain.thisObject)
                } catch (ignored: Throwable) {
                }
                return result
            }
        }

        /** ⑤ 兜底吞点击：listener 没挂上（格子被重建）时长按仍不切页。 */
        private val CLICK_TAB: XposedInterface.Hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    val tab = tabArg(chain)
                    val at = lastDownAt
                    if (tab != null && tab === userTab) {
                        if (at != 0L && android.os.SystemClock.uptimeMillis() - at >= LONG_PRESS_MS) {
                            lastDownAt = 0L
                            swallowAt = android.os.SystemClock.uptimeMillis()
                            openPanel(cellOrNull())
                            Log.i(TAG, "event=baidu_mine_entry_click_swallowed via=clickTab")
                            return null      // 不 proceed ⇒ 这一次不切页
                        }
                    } else if (tab != null && at != 0L) {
                        // 负向判据：按下过、但点的不是「我的」。不去重（低频）。
                        Log.i(TAG, "event=baidu_mine_tab_miss tab=" + tab + " via=enum")
                    }
                } catch (ignored: Throwable) {
                }
                return chain.proceed()
            }
        }

        private fun capturePresenter(self: Any?) {
            if (self == null) return
            if (PRESENTER_CLASS != self.javaClass.name) {
                // 只认 presenter 本身（父类 MVVMPresenter 也可能被同名方法命中，但那是别的类）。
                val c = self.javaClass
                var ok = false
                var k: Class<*>? = c
                while (k != null && k !== Any::class.java) {
                    if (PRESENTER_CLASS == k.name) {
                        ok = true
                        break
                    }
                    k = k.superclass
                }
                if (!ok) return
            }
            PRESENTER = WeakReference<Any?>(self)
            Log.i(TAG, "event=baidu_mine_entry_presenter captured=" + self.javaClass.simpleName)
            // doSubscribe 可能在格子建好之前就调了：稍等一帧再挂，避免拿到 null。
            postAttach(300L)
        }

        /** 在下一帧/延时后重挂 listener（必须在主线程）。 */
        private fun postAttach(delay: Long) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    object : Runnable {
                        override fun run() {
                            attach()
                        }
                    }, delay
                )
            } catch (ignored: Throwable) {
            }
        }

        /**
         * 把 [TOUCH] 挂到「我的」那一格的 View 上（幂等：同一个 View 实例只挂一次）。
         *
         * <p>为什么每次 resume 都要重挂：底部栏的格子可能被重建（主题切换 / 页面重建），
         * 新实例上没有任何 listener。
         */
        private fun attach() {
            try {
                val presenter = PRESENTER.get() ?: return
                val cell = cellOf(presenter)
                if (cell == null) {
                    Log.i(TAG, "event=baidu_mine_entry_anchor_missing what=cell")
                    return
                }
                if (ATTACHED_CELL.get() === cell) return
                cell.setOnTouchListener(TOUCH)
                ATTACHED_CELL = WeakReference<View?>(cell)
                Log.i(TAG, "event=baidu_mine_entry_attached cell=" + cell.javaClass.simpleName)
            } catch (t: Throwable) {
                Log.w(TAG, "event=baidu_mine_entry_attach_error err=" + t.javaClass.simpleName, t)
            }
        }

        /** 按优先级从 presenter 上取「我的」那一格的 View。 */
        private fun cellOf(presenter: Any): View? {
            for (name in M_CELL_GETTERS) {
                val m = oneArgMethod(presenter.javaClass, name) ?: continue
                try {
                    m.isAccessible = true
                    val r = m.invoke(presenter, userTab)
                    if (r is View) return r
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        private fun cellOrNull(): View? {
            try {
                val p = PRESENTER.get()
                if (p != null) {
                    val v = cellOf(p)
                    if (v != null) return v
                }
            } catch (ignored: Throwable) {
            }
            return ATTACHED_CELL.get()
        }

        /** 在类及其父类里找一个「只接受 Tab 枚举」的方法。 */
        private fun oneArgMethod(c: Class<*>, name: String): Method? {
            var k: Class<*>? = c
            while (k != null && k !== Any::class.java) {
                val cur = k
                val methods: Array<Method>? = try {
                    cur.declaredMethods
                } catch (t: Throwable) {
                    null
                }
                if (methods != null) {
                    for (m in methods) {
                        if (name != m.name) continue
                        val ps = m.parameterTypes
                        val te = tabEnum
                        if (ps.size == 1 && te != null && ps[0].isAssignableFrom(te)) return m
                    }
                }
                k = cur.superclass
            }
            return null
        }

        /** 从 chain 参数里找 tab 枚举实例。 */
        private fun tabArg(chain: XposedInterface.Chain): Any? {
            try {
                val args = chain.args ?: return null
                val te = tabEnum ?: return null
                for (i in args.indices) {
                    if (i >= 4) break
                    val a = args[i]
                    if (a != null && te.isInstance(a)) return a
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        /**
         * ④ 自己挂在「我的」格子上的触摸监听。
         *
         * <p>三条要点（真机踩出来的，别改）：
         * <ul>
         *   <li>DOWN 返回 <b>false</b>：让 View 自己的 onTouchEvent 继续跑，这样后续 MOVE/UP 才送得到；</li>
         *   <li>长按命中时 UP 返回 <b>true</b>：消费掉事件 ⇒ 原生 onClick 不触发 ⇒ <b>页面不切走</b>；</li>
         *   <li>未命中一律返回 false：轻点必须照常切 tab。</li>
         * </ul>
         */
        private val TOUCH: View.OnTouchListener = object : View.OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                try {
                    val action = e.actionMasked
                    if (action == MotionEvent.ACTION_DOWN) {
                        lastDownAt = android.os.SystemClock.uptimeMillis()
                        downX = e.rawX
                        downY = e.rawY
                        moved = false
                        return false
                    }
                    if (action == MotionEvent.ACTION_MOVE) {
                        if (!moved) {
                            val slop = MOVE_SLOP_DP * v.resources.displayMetrics.density
                            val dx = e.rawX - downX
                            val dy = e.rawY - downY
                            if (dx * dx + dy * dy > slop * slop) moved = true
                        }
                        return false
                    }
                    if (action == MotionEvent.ACTION_UP) {
                        val at = lastDownAt
                        lastDownAt = 0L
                        if (at == 0L || moved) return false
                        val held = android.os.SystemClock.uptimeMillis() - at
                        if (held < LONG_PRESS_MS) return false      // 轻点 ⇒ 放行，照常切页
                        swallowAt = android.os.SystemClock.uptimeMillis()
                        Log.i(
                            TAG, "event=baidu_mine_entry_long_press held=" + held
                                    + " mine=true via=enum label=" + labelOf(v)
                                    + " view=" + v.javaClass.simpleName
                        )
                        openPanel(v)
                        return true                                  // 消费 ⇒ 不切页
                    }
                    if (action == MotionEvent.ACTION_CANCEL) {
                        lastDownAt = 0L
                        moved = false
                    }
                } catch (ignored: Throwable) {
                    // 绝不影响宿主
                }
                return false
            }
        }

        /** 重挂入口：Application.onCreate → 注册生命周期回调 → 每次 resume 重挂 listener。 */
        private fun installResumeHook(module: XposedModule): Int {
            try {
                module.hook(Application::class.java.getDeclaredMethod("onCreate"))
                    .setId("baidu_mine_entry.lifecycle")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            try {
                                val self = chain.thisObject
                                if (self is Application) {
                                    self.registerActivityLifecycleCallbacks(
                                        object : Application.ActivityLifecycleCallbacks {
                                            override fun onActivityCreated(
                                                a: android.app.Activity, b: android.os.Bundle?
                                            ) {
                                            }

                                            override fun onActivityStarted(a: android.app.Activity) {}

                                            override fun onActivityResumed(a: android.app.Activity) {
                                                postAttach(200L)   // 格子可能被重建
                                            }

                                            override fun onActivityPaused(a: android.app.Activity) {}

                                            override fun onActivityStopped(a: android.app.Activity) {}

                                            override fun onActivitySaveInstanceState(
                                                a: android.app.Activity, b: android.os.Bundle
                                            ) {
                                            }

                                            override fun onActivityDestroyed(a: android.app.Activity) {}
                                        })
                                }
                            } catch (ignored: Throwable) {
                            }
                            return result
                        }
                    })
                return 1
            } catch (t: Throwable) {
                Log.w(
                    TAG, "event=baidu_mine_entry_lifecycle_error err="
                            + t.javaClass.simpleName, t
                )
                return 0
            }
        }

        /** 按方法名挂全部重载（本类 + 父类），跳过抽象方法。 */
        private fun hookAll(
            module: XposedModule, c: Class<*>, method: String,
            id: String, hooker: XposedInterface.Hooker
        ): Int {
            var n = 0
            var k: Class<*>? = c
            while (k != null && k !== Any::class.java) {
                val cur = k
                val methods: Array<Method>? = try {
                    cur.declaredMethods
                } catch (t: Throwable) {
                    null
                }
                if (methods != null) {
                    for (m in methods) {
                        if (method != m.name) continue
                        if (Modifier.isAbstract(m.modifiers)) continue
                        try {
                            m.isAccessible = true
                            module.hook(m)
                                .setId(id + "#" + cur.simpleName + "#" + n)
                                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                                .intercept(hooker)
                            n++
                        } catch (t: Throwable) {
                            Log.w(
                                TAG, "event=baidu_mine_entry_hook_error id=" + id
                                        + " err=" + t.javaClass.simpleName
                            )
                        }
                    }
                }
                k = cur.superclass
            }
            return n
        }

        /* ------------------------------------------------------------------ 面板 */

        /** 打开面板：先按「同一窗口」找浮层，失败再自托管。 */
        private fun openPanel(anchor: View?) {
            var near = false
            try {
                near = FloatingController.openPanelNear(anchor)
            } catch (ignored: Throwable) {
            }
            var self = false
            if (!near) self = showOwnPanel(anchor)
            Log.i(
                TAG, "event=baidu_mine_entry_own_panel shown=" + (near || self)
                        + " via=" + (if (near) "near" else "self")
            )
        }

        /**
         * 自己托管一个 [FloatingPanel] 挂到锚点的**根视图**上。
         *
         * <p>只依赖 `getRootView()`（真正在显示的那个 DecorView，对象恒等），
         * 不依赖 Activity 归属 —— 高德 17.02 实测过「resume 的 Activity 和拥有窗口的不是同一个」。
         */
        private fun showOwnPanel(anchor: View?): Boolean {
            if (anchor == null) return false
            try {
                val open = SELF_PANEL.get()
                if (open != null && open.isOpen()) {
                    open.close()
                    removeOwnPanel()
                    Log.i(TAG, "event=baidu_mine_entry_own_panel closed=true")
                    return true
                }
                val root = anchor.rootView
                if (root !is ViewGroup) return false
                val decor = root
                val context = anchor.context
                if (context == null) return false

                val panel = FloatingPanel(context, isNight(context))
                val host = FrameLayout(context)
                host.clipChildren = false
                host.addView(
                    panel, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                decor.addView(
                    host, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                SELF_HOST = WeakReference<ViewGroup?>(host)
                SELF_PANEL = WeakReference<FloatingPanel?>(panel)
                panel.setOnClose(object : Runnable {
                    override fun run() {
                        removeOwnPanel()
                    }
                })
                // ★ 必须等这一帧 attach + layout 完再 open()：open() 内部先 card.setTranslationY(420dp)
                // 再动画回 0，同一帧调用会让卡片停在屏幕外（面板 VISIBLE、尺寸也对，但看不见）。
                host.post(object : Runnable {
                    override fun run() {
                        try {
                            panel.open()
                            Log.i(
                                TAG, "event=baidu_mine_entry_own_panel shown=true host="
                                        + decor.javaClass.simpleName
                                        + " size=" + decor.width + "x" + decor.height
                            )
                        } catch (t: Throwable) {
                            Log.w(
                                TAG, "event=baidu_mine_entry_own_panel_error err="
                                        + t.javaClass.simpleName, t
                            )
                        }
                    }
                })
                return true
            } catch (t: Throwable) {
                Log.w(
                    TAG, "event=baidu_mine_entry_own_panel_error err="
                            + t.javaClass.simpleName, t
                )
                return false
            }
        }

        private fun removeOwnPanel() {
            try {
                val host = SELF_HOST.get()
                SELF_HOST = WeakReference<ViewGroup?>(null)
                SELF_PANEL = WeakReference<FloatingPanel?>(null)
                if (host == null) return
                val parent: ViewParent? = host.parent
                if (parent is ViewGroup) parent.removeView(host)
                host.removeAllViews()
            } catch (ignored: Throwable) {
            }
        }

        /** 该 View 自己或它的直接子 View 里的文本（只用于日志，不参与判定）。 */
        private fun labelOf(root: View?): String {
            try {
                if (root is android.widget.TextView) {
                    val t = root.text
                    if (t != null && t.isNotEmpty()) return t.toString().trim()
                }
                if (root is ViewGroup) {
                    val count = root.childCount
                    for (i in 0 until count) {
                        if (i >= 8) break
                        val child = root.getChildAt(i)
                        if (child is android.widget.TextView) {
                            val t = child.text
                            if (t != null && t.isNotEmpty()) return t.toString().trim()
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
            return ""
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
}
