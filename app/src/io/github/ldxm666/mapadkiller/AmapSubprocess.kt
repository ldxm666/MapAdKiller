package io.github.ldxm666.mapadkiller

import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log

import java.util.Collections
import java.util.WeakHashMap

import io.github.libxposed.api.XposedInterface

/**
 * 高德子进程的**最小 kill 集**（子进程级省电）。
 *
 * <h3>为什么需要这个类</h3>
 * 2.2.x 起 `MainHook` 对「进程名含 `:`」直接 return，所以
 * `:locationservice` / `:widgetProvider` 这两个常驻子进程里**一个 hook 都没挂**。
 * 主进程里做的界面精简、数据层剥离，对它们完全无效 —— 它们照常跑自己的定时任务与刷新。
 *
 * <h3>边界（很重要）</h3>
 * 这里**只放子进程**的 hook。主进程的全量入口是 `AmapHooks.install`，
 * 两者不能混：把 kill 逻辑塞进 `AmapHooks` 会让主进程也去停自己的服务。
 * 也**不碰**任何 UI / 数据层代码（那些在子进程里没有 Activity，跑起来只会出错）。
 *
 * <h3>为什么"只降级不崩"</h3>
 * 子进程里抛异常会直接把宿主进程带走（用户看到的是"高德莫名闪退"，且无法归因到模块）。
 * 所以每个 Hooker 的整个函数体都在 try/catch 里，异常只写日志、然后 `proceed()`。
 *
 * <h3>诚实的能力边界</h3>
 * 模块**不能让系统不拉起子进程**：`:widgetProvider` 的 44 个组件在 Manifest 里
 * 声明了 `android:process`，系统投递广播时会先 fork 进程、再调 `onReceive`。
 * 这里能做的只有：① 让广播到达后**不做任何工作**；② 让服务 `stopSelf` 后不被重启
 * ⇒ 进程尽快退到可回收状态。要真正"不拉起"只能 root 侧 `pm disable` 那 44 个组件。
 */
object AmapSubprocess {

    @JvmField
    val PROC_LOCATION: String = MainHook.PKG_AMAP + ":locationservice"
    @JvmField
    val PROC_WIDGET: String = MainHook.PKG_AMAP + ":widgetProvider"

    /**
     * 桌面小组件基类之一：`com.autonavi.bundle.desktopwidget.mvp.BaseDesktopWidgetProvider`
     * （classes5.dex，super=`android.appwidget.AppWidgetProvider`）。
     * 直接继承它的有 ToolboxWidgetProvider / VivoWidgetProvider / BillboardWidgetProvider /
     * SubscribeWidgetProvider。
     */
    private const val CLS_WIDGET_BASE =
            "com.autonavi.bundle.desktopwidget.mvp.BaseDesktopWidgetProvider"

    /**
     * 桌面小组件基类之二：`com.amap.bundle.desktopwidget_dynamic.ability.base.DWAbsProvider`
     * （classes4.dex，super=上面那个 BaseDesktopWidgetProvider）。
     * 它是**动态卡片**那一族（35 个 `*WidgetProvider`，含 footprint ×5 / sport ×12 /
     * taxi / gasstation / charge / ranklist / myvoice / myskin / member …）的公共基类。
     */
    private const val CLS_WIDGET_DYNAMIC =
            "com.amap.bundle.desktopwidget_dynamic.ability.base.DWAbsProvider"

    /**
     * `com.amap.bundle.desktopwidget.service.DesktopWidgetService`（classes4.dex，super=android.app.Service）
     */
    private const val CLS_WIDGET_SERVICE =
            "com.amap.bundle.desktopwidget.service.DesktopWidgetService"

    /**
     * 自己**覆写**了 `onReceive(Context,Intent)V` 的 4 个直接子类。
     *
     * <p>为什么必须单独挂：`onReceive` 是虚方法，系统调的是 Manifest 里那个类的实现。
     * 子类覆写后若没有 `super.onReceive(...)`，**挂基类根本不会触发**。
     * 44 个组件里只有这 4 个（+ 下面被排除的 AmapShortCutProvider）声明了自己的 onReceive，
     * 其余 39 个都继承 ⇒ 基类 + 这 4 个 = 完整闭合集。
     *
     * <p>逐个来自 DEX 实测（17.02.0.1541，`--Methods --Filter onReceive` 均列出
     * `public onReceive(Landroid/content/Context;Landroid/content/Intent;)V`）：
     * <pre>
     *  com.autonavi.bundle.amaphome.desktopwidget.ToolboxWidgetProvider                  (classes5)
     *  com.autonavi.bundle.amaphome.desktopwidget.vivo.VivoWidgetProvider                 (classes5)
     *  com.autonavi.minimap.bundle.favorites.desktopwidget.BillboardWidgetProvider        (classes6)
     *  com.autonavi.minimap.bundle.favorites.hoteldesktopwidget.SubscribeWidgetProvider   (classes6)
     * </pre>
     *
     * <p>**刻意不挂** `com.amap.bundle.desktopwidget.shortcut.AmapShortCutProvider`：
     * 它是「长按桌面图标 → 创建快捷方式」的回调，只在**用户主动操作**时触发，
     * 不构成周期耗电；拦了反而会让"添加快捷方式"失败 —— 收益为零、代价是功能坏掉。
     *
     * <p>**刻意不挂** `com.amap.bundle.desktopwidget.hiboard.HiBoardCardProvider`：
     * 它是 ContentProvider（OPPO 原子卡片，`onCreate()Z`），让它 onCreate 返回 false
     * 会让 provider 发布失败，任何查询它的系统组件都会拿到 null ⇒ 中风险，不做。
     */
    private val CLS_WIDGET_OVERRIDERS = arrayOf(
            "com.autonavi.bundle.amaphome.desktopwidget.ToolboxWidgetProvider",
            "com.autonavi.bundle.amaphome.desktopwidget.vivo.VivoWidgetProvider",
            "com.autonavi.minimap.bundle.favorites.desktopwidget.BillboardWidgetProvider",
            "com.autonavi.minimap.bundle.favorites.hoteldesktopwidget.SubscribeWidgetProvider",
            // 17.02【自验】classes4.dex：super=BaseDesktopWidgetProvider，
            // 5 个方法 = <init> / b()HashMap / onDeleted / onReceive / onUpdate ⇒ 确实覆写了 onReceive。
            // 它的"创建快捷方式"走的是**别的 action**，而本类只拦 APPWIDGET_UPDATE，
            // 所以挂上它不会让"长按图标→添加快捷方式"失败。
            "com.amap.bundle.desktopwidget.shortcut.AmapShortCutProvider"
    )

    /**
     * OPPO 系「原子卡片」：`com.amap.bundle.desktopwidget.hiboard.HiBoardCardProvider`
     * （classes4.dex，super=`com.oplus.cardwidget.serviceLayer.AppCardWidgetProvider`
     * → `BaseInterfaceLayerProvider` → `BaseCardStrategyProvider`）。
     *
     * <p>它是**独立继承树**，不归上面两个 widget 基类管，所以必须单独处理。
     *
     * <p>📌 **勘误**：`subprocess-inventory.md` 把本类描述成「ContentProvider」是**不准**的。
     * DEX 实测 super 链是
     * `AppCardWidgetProvider → BaseInterfaceLayerProvider → BaseCardStrategyProvider`，
     * 三个都在 `com.oplus.cardwidget.serviceLayer` 包内，是 **OPPO 卡片框架自己的 provider 基类**，
     * 不是 `android.content.ContentProvider` 的直接子类。
     * 它之所以容易被当成 ContentProvider，是因为 Manifest 里它确实是用 `<provider>` 标签
     * 声明的（`components.csv`）—— 声明标签 ≠ 类的父类。
     *
     * <p>⚠ **不选 `onCreate()Z → false` 的原因（重要，别照 scout2 的报告改回去）**：
     * ① 因为 Manifest 用的是 `<provider>` 标签，进程启动时系统会实例化它并调 `onCreate()`；
     * ② 而 `onCreate()Z` 在**整个 OPPO 卡片基类链上都有同签名覆写**
     *    （`AppCardWidgetProvider` / `BaseAppCardWidgetProvider` / `BaseInterfaceLayerProvider`
     *    逐个 `--Methods --Filter onCreate` 都能查到 `public onCreate()Z`），
     *    这条链最终**可能**接到 `ContentProvider.onCreate()`；
     * ③ 一旦它真是 `ContentProvider.onCreate()`，返回 `false` 就等于
     *    **provider 发布失败** —— 任何查询它的系统组件（OPPO 桌面 / 卡片服务）都会拿到 null，
     *    属**中风险**，且失败点在系统侧、模块这边看不到堆栈；
     * ④ 而 `initCardWidget()V` 是卡片 SDK 自己的初始化入口（**void**），
     *    不 proceed 的效果等价（卡片永不初始化），却**没有发布失败的风险**。
     * ⇒ 选 void 的那个：宁可少拦一层，也不冒 provider 发布失败的风险。
     * **这条结论已与 Lead 对齐（2026-10-20），不要改回 `onCreate()Z`。**
     */
    private const val CLS_HIBOARD =
            "com.amap.bundle.desktopwidget.hiboard.HiBoardCardProvider"

    /** `com.amap.bundle.lotuspool.internal.LotusPoolService`（classes4.dex，super=android.app.Service） */
    private const val CLS_LOTUSPOOL =
            "com.amap.bundle.lotuspool.internal.LotusPoolService"

    /** [Service.START_NOT_STICKY]：系统不会因为进程被杀而重启它。 */
    private const val START_NOT_STICKY = 2

    /** 这个子进程是否由本模块接管。只有这两个进 kill 集，其余子进程一律跳过。 */
    @JvmStatic
    fun handles(process: String?): Boolean {
        return PROC_WIDGET == process || PROC_LOCATION == process
    }

    @JvmStatic
    fun install(cl: ClassLoader, process: String) {
        // ★ 总闸（安装期）：subproc_off 关掉 ⇒ 子进程 kill 集整体不装。
        if (!masterGate()) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=subproc_off reason=switch_off"
                    + " process=" + process)
            return
        }
        val before = H.ok.get()
        try {
            if (PROC_WIDGET == process) installWidget(cl)
            else if (PROC_LOCATION == process) installLocation(cl)
        } catch (t: Throwable) {
            // 子进程里绝不能让异常冒出去。
            H.log(Log.WARN, MainHook.TAG, "event=subproc_failed process=" + process
                    + " reason=" + t.javaClass.simpleName)
        }
        H.log(Log.INFO, MainHook.TAG, "event=subproc_done process=" + process
                + " hooks=" + (H.ok.get() - before) + " miss=" + H.fail.get())
    }

    // ══════════════════════════════════════════════ :widgetProvider

    /**
     * 44 个桌面组件 → **7 个 onReceive**（2 个基类 + 5 个覆写者），这就是全部覆盖。
     *
     * <p>DEX 证据（17.02.0.1541，逐条查过 super 链）：
     * <pre>
     *  FootprintWidgetProvider …（35 个 desktopwidget_dynamic.business.*）
     *      super = DWAbsProvider            （classes4）  ← onReceive 实现
     *  SportWidgetProvider / RouteCommuteWidgetProvider / RealTimeBusWidgetProvider /
     *  CustomToolTwoProvider
     *      super = DWAbsProvider            （同上）
     *  ── DWAbsProvider 的 super 是下面这个 ──────────────────────────────────────
     *  BaseDesktopWidgetProvider            （classes5）  ← onReceive 实现
     *      super = android.appwidget.AppWidgetProvider
     *  ToolboxWidgetProvider / VivoWidgetProvider / BillboardWidgetProvider /
     *  SubscribeWidgetProvider / AmapShortCutProvider
     *      super = BaseDesktopWidgetProvider，但**各自覆写了 onReceive** ⇒ 必须单独挂
     * </pre>
     * ⇒ 挂 2 个基类 + 5 个覆写者的 `onReceive` 才覆盖了 Manifest 里
     * `:widgetProvider` 的全部 44 个组件（`components.csv` 计数一致）。
     * **只挂基类是不够的**：`onReceive` 是虚方法，Java 分派到子类实现，
     * Xposed 挂基类方法拦不到覆写者。
     *
     * <p>为什么两个基类都挂：`DWAbsProvider.onReceive` 会自己先处理 action
     * （体内调用 `getAction()` / `TextUtils.isEmpty` / `c()`），
     * 然后才调 `super.onReceive`。只挂最上面那个的话，动态那一族的自有逻辑照样先跑一遍。
     */
    private fun installWidget(cl: ClassLoader) {
        // ★ 安装期门控：开关关掉就**根本不装**这 8 条（零成本，而不是装上进拦截器再放行）。
        // 代价：改这个开关要强停并重开高德才生效。
        if (!Config.installGate(Config.K_SUBPROC_WIDGET_OFF)) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=subproc_widget_off reason=switch_off")
            return
        }
        // 1) 两个基类（覆盖 39 个继承者 + 35 个动态卡片的公共分发）
        for (name in arrayOf(CLS_WIDGET_DYNAMIC, CLS_WIDGET_BASE)) {
            H.hookSig(H.cls(cl, name), "onReceive", "amap_widget_receive_" + shortName(name),
                    WIDGET_RECEIVE, Context::class.java, Intent::class.java)
        }
        // 2) 5 个自己覆写了 onReceive 的子类（不吃基类，必须单独挂）
        for (name in CLS_WIDGET_OVERRIDERS) {
            H.hookSig(H.cls(cl, name), "onReceive", "amap_widget_receive_" + shortName(name),
                    WIDGET_RECEIVE, Context::class.java, Intent::class.java)
        }
        // 3) 服务：不让它常驻
        H.hookSig(H.cls(cl, CLS_WIDGET_SERVICE), "onStartCommand", "amap_widget_service",
                idleService("DesktopWidgetService"),
                Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        // 4) OPPO 原子卡片（独立继承树）：卡片的初始化入口直接 return。
        H.hookSig(H.cls(cl, CLS_HIBOARD), "initCardWidget", "amap_hiboard_init", WIDGET_INIT)
    }

    // ══════════════════════════════════════════════ :locationservice

    /**
     * `:locationservice` 里 14 个组件，只有**一个**能安全地做 kill。
     *
     * <p>17.02 Manifest（`components.csv`，`process=":locationservice"` 共 14 条）：
     * <ul>
     *   <li>`com.amap.bundle.location.subprocess.AmapLocationSubService` —— Manifest 里声明了，
     *       但 **17.02 的 8 个 DEX 里根本没有这个类**（73,638 类全量索引 0 命中）⇒ 无法 hook，
     *       也不需 hook（`startService` 只会 ClassNotFoundException，不会真的跑起来）；</li>
     *   <li>`...foregroundservice.LocationAliveForegroundService` —— **保活，禁动**；</li>
     *   <li>`com.amap.bundle.watchfamily.service.WatchmenService` —— 家人地图，是用户功能，
     *       本轮不动（见报告）；</li>
     *   <li>`com.taobao.accs.*` / `org.android.agoo.*` —— 推送长连接，**已改由
     *       [AmapJob.installPush] 接管**（`push_off` 安全档 + `push_kill_off` 激进档）。
     *       注意：17.01 担心的"拦一半进退避重连"针对的是"只拦消息不拦连接"；
     *       现在的安全档是把 `startKernel`（长连接入口）**整个拦死**，连接根本不会建立，
     *       所以不存在"断了重连"的循环。但**必须真机 A/B 采样确认 CPU 下降而非上升**。</li>
     *   <li>`com.alibaba.analytics.AnalyticsService` —— **不能按 onStartCommand 拦**：
     *       实测它**没有 onStartCommand**，只有 `onBind/onDestroy/onLowMemory`，
     *       是纯 bound service（`AnalyticsMgr.bindService()` 绑定）。
     *       正确的闸口在**主进程**的 `AnalyticsMgr.init(Application)` / `checkInit()`，
     *       已由 [AmapTrack] 落地 ⇒ 这里不需要再挂任何东西；</li>
     *   <li>`com.amap.bundle.lotuspool.internal.LotusPoolService` —— **本轮目标**。
     *       它是高德自己的"云控定时任务总线"（`paas.lotuspool`），有
     *       `vAppEnterBackground()` 钩子 ⇒ 进后台仍在调度。它的
     *       `onStartCommand(Intent,int,int)I` 体内**一条调用都没有**（DEX 实测），
     *       所以让它 `START_NOT_STICKY + stopSelf()` 不会砍掉 onStartCommand 里的实际工作；
     *       ⚠ 但 `stopSelf()` 会触发 `onDestroy()`（体内 5 条调用，含
     *       `Handler.post(Runnable)`），**是否连带停掉定位回传 / 路况上报 / 离线数据同步 /
     *       足迹打卡，静态无法逐条确认** —— 所以给了独立开关 `subproc_lotuspool_off`，
     *       真机一旦发现这些功能异常，第一个关它。</li>
     * </ul>
     */
    private fun installLocation(cl: ClassLoader) {
        // ★ 安装期门控（LotusPool 单独一档）
        if (Config.installGate(Config.K_SUBPROC_LOTUSPOOL_OFF)) {
            H.hookSig(H.cls(cl, CLS_LOTUSPOOL), "onStartCommand", "amap_lotuspool_service",
                    idleService("LotusPoolService"),
                    Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        } else {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=subproc_lotuspool_off reason=switch_off")
        }
        // 杠杆 B：ACCS / AGOO 推送长连接（4 个组件全在 :locationservice，见 AmapJob 类注释）。
        // 这是本进程里**唯一**真正的持续耗电源（长连接 socket + 心跳 + 重连），
        // 所以单独成档、单独开关（push_off / push_kill_off / push_hard_off），不与 widget/lotuspool 混。
        try {
            AmapJob.installPush(cl)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=push_failed", t)
        }
    }

    // ══════════════════════════════════════════════ Hooker 实现

    /**
     * 桌面组件广播：命中 `APPWIDGET_UPDATE` 就整条 return，不 proceed。
     *
     * <p>只拦 UPDATE，不拦 ENABLED / DELETED / OPTIONS_CHANGED —— 那几个是组件生命周期
     * 记账，拦了会让"添加/删除小组件"的状态机错乱，而且它们只在用户操作时发生，
     * 本来就不构成耗电。真正周期性的、会拉网络+解码图片的是 UPDATE
     * （`updatePeriodMillis` 驱动，最终走到
     * `DWAbsProvider.onUpdate → IDynamicWidgetPresenter.refreshCard(Context,boolean,String,String)`）。
     */
    private val WIDGET_RECEIVE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        private var reported = false
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                val arg = chain.getArg(1)
                if (arg is Intent && isWidgetUpdate(arg.action)) {
                    if (!reported) {
                        reported = true
                        H.log(Log.INFO, MainHook.TAG, "event=widget_update_blocked action="
                                + arg.action)
                    }
                    return null   // onReceive 是 void
                }
            } catch (ignored: Throwable) {
                // 任何异常都退回原逻辑：宁可少省一点电，也不能让桌面组件崩掉。
            }
            // 非 APPWIDGET_UPDATE（ENABLED/DELETED/OPTIONS_CHANGED…）照常放行。
            return chain.proceed()
        }
    }

    /**
     * OPPO 原子卡片的初始化入口：直接 return，卡片 SDK 永不初始化。
     *
     * <p>`initCardWidget()V` 是 void ⇒ 不 proceed 即短路。
     */
    private val WIDGET_INIT: XposedInterface.Hooker = object : XposedInterface.Hooker {
        private var reported = false
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                if (!reported) {
                    reported = true
                    H.log(Log.INFO, MainHook.TAG, "event=hiboard_blocked method=initCardWidget")
                }
            } catch (ignored: Throwable) {
            }
            // 装上就无条件短路（安装期门控已决定装不装），零 Config 读。
            return null
        }
    }

    /**
     * 服务空闲化：返回 `START_NOT_STICKY`，**每个服务实例最多 stopSelf 一次**。
     *
     * <p>为什么返回值必须显式给 2 而不是 `chain.proceed()` 之后再 stop：
     * `stopSelf()` 之后服务已经进入销毁流程，再让原方法跑一遍它的收尾逻辑
     * 会碰到已释放的成员（这是"半初始化"的经典事故形态）。直接返回是唯一安全写法。
     *
     * <p>★ 为什么必须做「每实例一次」保护（真机 `kill -3` 抓栈证据，2026-10-20）：
     * 高德会把某些 Service 入口挂在 `ScheduledThreadPoolExecutor` 上**周期性**调用。
     * 若每次调用都无条件 `stopSelf()`，就变成 binder 往返死循环
     * （`IActivityManager.stopServiceToken`），实测把 `ACCS0` 线程顶到
     * **30% 单核 / 18 万次上下文切换**，并导致帧间隔出现周期性 50~67ms 长停顿。
     * 用 `WeakHashMap` 记住"这个实例已经 stop 过"，第二次起只返回、不再 binder 往返。
     */
    private val IDLE_STOPPED: MutableMap<Any, Boolean> =
            Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private fun idleService(label: String): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    val self = chain.getThisObject()
                    // 同一个实例只 stop 一次：周期回调不会变成 binder 死循环。
                    if (self != null && IDLE_STOPPED.put(self, java.lang.Boolean.TRUE) == null) {
                        (self as? Service)?.stopSelf()
                        H.log(Log.INFO, MainHook.TAG, "event=subproc_service_idle service=" + label)
                    }
                } catch (ignored: Throwable) {
                }
                // 装上就无条件返回（安装期门控已决定装不装），零 Config 读。
                return java.lang.Integer.valueOf(START_NOT_STICKY)
            }
        }
    }

    // ══════════════════════════════════════════════ helpers

    /**
     * 总闸：`subproc_off` 关掉 ⇒ 子进程 kill 集**整体不装**（零成本）。
     *
     * <p>只在 install 时读一次。读不到远程偏好时按"开"处理（默认值 = 已拦截）。
     */
    private fun masterGate(): Boolean {
        return Config.installGate(Config.K_SUBPROC_OFF)
    }

    /**
     * `APPWIDGET_UPDATE` 的 action 判定。
     *
     * <p>不只认 `android.appwidget.action.APPWIDGET_UPDATE`：
     * 17.01 的 `--Xref appwidget` 在 `BaseDesktopWidgetProvider.onReceive` 里
     * 同时命中过 `miui.appwidget.action.APPWIDGET_UPDATE`（MIUI 变体）。
     * 用后缀匹配一次覆盖，避免下次又漏厂商分支。
     */
    private fun isWidgetUpdate(action: String?): Boolean {
        // ★ 只拦**标准** AppWidget 广播，放行厂商自己的 action（2026-10-20 用户反馈「导航不上
        // 小米灵动岛」后收紧）。
        //
        // 原来用的是 `action.endsWith("APPWIDGET_UPDATE")`，它同时命中：
        //   · android.appwidget.action.APPWIDGET_UPDATE        ← 标准周期刷新（我们要拦的）
        //   · miui.appwidget.action.APPWIDGET_UPDATE           ← 小米自己的广播
        //   · android.appwidget.action.APPWIDGET_UPDATE_OPTIONS ← 选项变更（本来就不该拦）
        //
        // 证据（`.agent_work/logs_and_dumps/amap/1702/components.csv`）：高德**所有**
        // `:widgetProvider` 下的 provider 都声明了 `action:miui.appwidget.action.APPWIDGET_UPDATE`
        // （`RouteCommuteWidgetProvider` / `RealTimeBusWidgetProvider` / `SportWidgetProvider` /
        //  `ToolboxWidgetProvider` / `BillboardWidgetProvider` / `SubscribeWidgetProvider` /
        //  `MemberWidgetProvider` / `CustomToolTwoProvider` / `FootprintWidgetProvider` /
        //  `TaxiWidgetProvider` / `GasStationWidgetProvider` / `ChargeWidgetProvider` /
        //  `RankListWidgetProvider` …）。把厂商 action 一起拦掉，会连小米侧的卡片/灵动岛
        //  刷新一起打断 —— 那是用户功能，不是广告。
        //
        // 只认标准 action：运营卡的周期性刷新仍然被拦（省电目标不变），厂商侧放行。
        return "android.appwidget.action.APPWIDGET_UPDATE" == action
    }

    private fun shortName(className: String): String {
        val dot = className.lastIndexOf('.')
        return if (dot < 0) className else className.substring(dot + 1)
    }
}
