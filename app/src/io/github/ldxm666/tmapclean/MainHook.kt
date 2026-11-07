package io.github.ldxm666.tmapclean

import android.util.Log

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 腾讯地图：**界面精简**（底部栏 / 首页工具宫格 / 设置家 / 大家都在看 / 「我的」页卡片）。
 *
 * <p>与 {@code bmapclean.MainHook} 同构：v2.0.0 合并版起本类<b>不是</b> Xposed 入口 ——
 * 一个 APK 只能有一个 {@code META-INF/xposed/java_init.list} 入口，由
 * {@code io.github.ldxm666.mapclean.MainHook} 统一接收回调后转发到这里。
 * 这样三家地图的 hook 代码各自独立，仍用自己的 TAG / 配置 group / 日志工具。
 *
 * <p><b>与 {@code mapadkiller.TmapHooks} 的分工</b>：那边是<b>去广告</b>基线（优量汇 / 开屏 /
 * banner），本包是<b>界面精简</b>。两者都在腾讯主进程里安装，互不干涉。
 */
object MainHook {

    const val TAG = "TMapClean"

    const val PKG_TMAP = "com.tencent.map"

    /** 模块自身包名 = 合并后的包名 */
    const val PKG_SELF = "io.github.ldxm666.mapadkiller"

    @Volatile
    private var sProcess: String? = null

    private val INSTALLED = java.util.concurrent.atomic.AtomicBoolean()

    /** {@code Instrumentation#callApplicationOnCreate} 只挂一次（早挂 + 兜底两条路共用）。 */
    private val CONTEXT_HOOKED = java.util.concurrent.atomic.AtomicBoolean()

    private val MAIN = android.os.Handler(android.os.Looper.getMainLooper())
    private var refreshPending = false

    /** {@code synchronized (REFRESH)} 的锁对象（Java 侧锁的是 REFRESH 那个 Runnable 本身）。 */
    private val REFRESH_LOCK = Any()

    private val REFRESH: Runnable = object : Runnable {
        override fun run() {
            synchronized(REFRESH_LOCK) {
                refreshPending = false
            }
            // 底栏一生只装配一次，开关却是随时拨的 —— 这里就地压一遍可见性，
            // 否则用户要重进地图才看得到效果（这正是"关掉底栏它不消失"的观感）。
            TmapTabBar.refresh()
            TmapTools.refresh()
            TmapHomeFav.refresh()
            TmapLookAndLook.refresh()
            TmapMapIcons.refresh()
            TmapSeekChips.refresh()
        }
    }

    @JvmStatic
    fun onModuleLoaded(mod: XposedModule, param: XposedModuleInterface.ModuleLoadedParam) {
        sProcess = param.getProcessName()
        H.module = mod
        H.log(
            Log.INFO, TAG, "event=module_loaded process=" + sProcess
                    + " api=" + mod.getApiVersion() + " framework=" + mod.getFrameworkName()
        )
        installEarlyContextHook()
    }

    /**
     * 模块加载的瞬间就把 {@code Instrumentation#callApplicationOnCreate} 挂上。
     *
     * <h3>为什么必须在这里挂，而不是在 installSelf 里</h3>
     * 这是「底部入口栏隐藏失效」的真正根因，真机 11.7.0 冷启动时间线（pid 27789）：
     *
     * <pre>
     * 01:02:32.221  TMapClean: event=module_loaded process=com.tencent.map   ← 本方法
     * 01:02:32.617  MapCleanFloat: eligible activity com.tencent.map.WelcomeActivity
     * 01:02:33.468  TMapClean: event=install_begin pkg=com.tencent.map via=self
     * 01:02:33.608  TMapClean: event=install_done pkg=com.tencent.map ok=48 miss=0
     * 01:02:33.685  TMapClean: HIT tmap_homecard_header
     * </pre>
     *
     * 宿主 Activity 在 <b>32.617</b> 就建好了，而腾讯侧钩子到 <b>33.468</b> 才装上 ——
     * <b>晚了 0.85 秒</b>。底栏（{@code TabFrameInfo#setTabPages} /
     * {@code LiquidTabBarGroup#setTabViews}）是在 Activity 建内容视图时装配的，
     * 一生只走一次；等钩子装好时那两个方法早就跑完了，所以 logcat 里
     * 只有 {@code HOOKED tmap_tab_*}、永远没有 {@code HIT tmap_tab_*}。
     *
     * <p>对照：{@code tmap_homecard_*} / {@code tmap_tools_*} / {@code tmap_mapicons_*}
     * 都命中，因为首页卡片、工具宫格、地图工具栏是<b>反复</b>刷新的，晚装也追得上。
     * 底栏没有第二次机会。
     *
     * <p>原来 {@code installContextHook()} 是放在 [installSelf] 里的 ——
     * 而 {@code installSelf} 又只能由这个钩子自己（拿到 Application 后回调
     * [installFromHost]）或悬浮球面板那条路触发，于是「钩子等 Application、
     * Application 等钩子」绕成了死结，最后只能靠面板兜底，一兜就晚 0.85 秒。
     * 现在改成模块加载即挂钩：{@code callApplicationOnCreate} 早于任何 Activity，
     * 装钩子的时刻因此提前到 32.2 秒附近，稳在底栏装配之前。
     */
    private fun installEarlyContextHook() {
        val proc = sProcess
        if (proc == null || proc != PKG_TMAP || proc.contains(":")) return
        installContextHook()
    }

    @JvmStatic
    fun onPackageReady(mod: XposedModule, param: XposedModuleInterface.PackageReadyParam) {
        val pkg: String? = param.getPackageName()
        val proc = sProcess
        if (pkg == null) return

        // 模块自身进程：不挂任何钩子
        if (pkg == PKG_SELF) {
            H.log(Log.INFO, TAG, "self process loaded (no hooks installed)")
            return
        }
        if (pkg != PKG_TMAP) return
        // 只做主进程：界面全在主进程
        if (proc != null && proc.contains(":")) {
            H.log(Log.INFO, TAG, "event=skip_subprocess process=" + proc)
            return
        }
        installSelf(param.getClassLoader(), pkg, "dispatcher")
    }

    /**
     * 自举安装（v2.1.7）：拿到宿主 Context 就自己把钩子装上，不等共享入口的厂商分发。
     *
     * <h3>为什么需要</h3>
     * 本类<b>不是</b> Xposed 入口 —— 一个 APK 只能有一个
     * {@code META-INF/xposed/java_init.list} 入口（{@code mapclean.MainHook}）。
     * 那个入口在 {@code onModuleLoaded} 里直接转发到本类（所以 {@code H.module} 一直是好的），
     * 但 {@code onPackageReady} 是**经 mapadkiller.MainHook 二次分发**的：
     *
     * <pre>
     * mapclean.MainHook.onPackageReady
     *   └─ mapadkiller.MainHook.onPackageReady   ← 瘦身构建里这个是 tools/variant-stubs 的替身
     *        └─ tmapclean.MainHook.onPackageReady
     * </pre>
     *
     * 那份替身是按「百度专用」写的，只转发百度、并把腾讯显式丢掉：
     *
     * <pre>
     * if (!PKG_BMAP.equals(pkg)) return;   // 高德 / 腾讯 are not part of this variant
     * </pre>
     *
     * tmap 目标复用了同一个替身（{@code build_variant.py} 的 tmap stubs 里列了
     * {@code io/github/ldxm666/mapadkiller/MainHook.java}），于是腾讯地图里
     * <b>本类的 onPackageReady 永远不会被调用</b>：
     * 底栏 / 工具宫格 / 我的页 / 地图图标 的钩子一个都没装。
     *
     * <p>真机判据：同一份 logcat 里
     * <pre>
     * I/BMapClean(…): event=install_begin pkg=com.baidu.BaiduMap     ← 百度有
     * I/TMapClean(…): （没有这一行）                                   ← 腾讯从来没有
     * </pre>
     *
     * 而 {@code onModuleLoaded} 是好的，所以日志里能看到 {@code event=module_loaded}
     * ——「模块加载了、面板能开」与「界面规则没生效」同时成立，正是用户看到的现象。
     *
     * <p>约束：{@code tools/} 不在本次改动范围内，所以这里让腾讯侧<b>自己能装</b>。
     * [INSTALLED] 的 CAS 保证与正常分发路径不会重复安装；
     * 一旦上游把替身改成同时转发腾讯，这里就自动退化成一次空调用。
     */
    @JvmStatic
    fun installFromHost(host: android.content.Context?) {
        if (host == null) return
        val pkg: String
        try {
            pkg = host.packageName
        } catch (t: Throwable) {
            return
        }
        if (pkg != PKG_TMAP) return
        val proc = sProcess
        if (proc != null && proc.contains(":")) return
        val cl = host.classLoader ?: return
        // 早挂路径（onModuleLoaded → callApplicationOnCreate）比原来的悬浮球路径早了
        // 1.2 秒左右，代价是「宿主 ClassLoader 还没长齐」这件事第一次变得可能。
        // 那时必须**什么都不做**：INSTALLED 一旦置位，后面那条兜底路径就被 CAS 挡掉，
        // 结果是「装晚了」变成「根本没装」，比修之前还糟。
        // 判据用宿主自己的 Application 类名：它就是 com.tencent.map.*，
        // 能由这个 ClassLoader 解析出来就说明宿主类已经可见。
        if (!hostLoaderReady(host, cl)) {
            H.log(Log.INFO, TAG, "event=install_deferred reason=host_loader_not_ready")
            return
        }
        // 自举路径下 installContextHook() 的钩子点（Instrumentation#callApplicationOnCreate）
        // 很可能已经走过了，那条路本来负责 Report.attach。这里直接补上，
        // 否则回报通道没有 Context，学到的工具目录发不出去。
        Report.attach(host)
        // 宿主 Application 的 ClassLoader 就是腾讯自己的 PathClassLoader，
        // 钩子必须挂在它上面才能解析 com.tencent.map.* 的类。
        installSelf(cl, pkg, "self")
    }

    /** 宿主 ClassLoader 是否已经能解析腾讯自己的类（决定这次安装能不能算数）。 */
    private fun hostLoaderReady(host: android.content.Context, cl: ClassLoader): Boolean {
        try {
            val c: Class<*>? = host.javaClass
            val name: String? = c?.name
            if (name != null && name.startsWith(PKG_TMAP + ".")) return true
        } catch (ignored: Throwable) {
        }
        // Application 被壳换掉时退回锚点类探测：拿得到底栏锚点就说明宿主 dex 已就位。
        return H.cls(cl, "com.tencent.map.ama.mainpage.frame.TabFrameInfo") != null
    }

    private fun installSelf(cl: ClassLoader?, pkg: String, via: String) {
        if (cl == null) return
        if (!INSTALLED.compareAndSet(false, true)) return
        try {
            H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg + " via=" + via)
            install(cl)
            installContextHook()
            installResumeHook()
        } catch (t: Throwable) {
            H.log(Log.ERROR, TAG, "event=install_failed pkg=" + pkg, t)
        } finally {
            H.done(pkg)
        }
    }

    /**
     * Preference callbacks may arrive on Binder threads; all view work runs on main.
     *
     * <p>注意 {@code ui_} 前缀已经覆盖了 [Spec.K_TOOL_PRESET]（{@code ui_tool_preset}），
     * 不用额外加分支。真正要提醒的是：这个键开/关会改变 {@code gas.m()} 的返回值，
     * 仅重送首页数据不够 —— 关掉预设时还要让腾讯按原始偏好重新排一次，
     * 所以 {@code onResume} 上的无条件 {@code onConfigChanged(null)} 是必需的兜底。
     */
    @JvmStatic
    fun onConfigChanged(key: String?) {
        if (!INSTALLED.get()) return
        if (key != null && !key.startsWith("ui_") && !key.startsWith(Spec.K_TOOL)
            && !key.startsWith(Spec.K_TAB)
        ) return
        synchronized(REFRESH_LOCK) {
            if (refreshPending) return
            refreshPending = true
            MAIN.postDelayed(REFRESH, 80L)
        }
    }

    private fun installResumeHook() {
        H.hookSig(
            android.app.Activity::class.java, "onResume", "tmapclean_resume",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    Cfg.invalidate()
                    onConfigChanged(null)
                    return result
                }
            })
    }

    /** 装全部界面精简钩子。 */
    private fun install(cl: ClassLoader) {
        // 开屏 / 升级两条链先装：两者都在启动任务图里，只跑一次，晚装就永远等不到那一发。
        TmapSplash.install(cl)
        TmapUpdate.install(cl)
        TmapTabBar.install(cl)
        TmapTools.install(cl, H.cls(cl, "com.qq.taf.jce.JceInputStream"))
        TmapHomeFav.install(cl)
        TmapLookAndLook.install(cl)
        TmapMine.install(cl)
        TmapMapIcons.install(cl)
        TmapSeekChips.install(cl)
    }

    /**
     * 拿 Context 的唯一可靠路径。
     *
     * <p>⚠ 不要用 {@code PackageReadyParam#getApplication()} —— 运行时的
     * {@code XposedModuleInterface$PackageReadyParam} 没有这个方法，一调就是 NoSuchMethodError
     * （百度侧 v0.1.1 实测，见 stub 里的说明）。
     *
     * <p>{@code Instrumentation#callApplicationOnCreate(Application)} 在每个进程里恰好走一次，
     * 而且早于任何 Activity（所以早于底栏装配）。参数就是 Application 本体。
     *
     * <p>安装点已经挪到 [installEarlyContextHook]（模块加载即挂）—— 这里保留
     * 兜底调用，靠 [CONTEXT_HOOKED] 保证只挂一次：早挂那条路成功时这里是空转，
     * 万一早挂没生效（框架时序不同），[installSelf] 这条老路仍然能把钩子补上。
     */
    private fun installContextHook() {
        if (!CONTEXT_HOOKED.compareAndSet(false, true)) return
        try {
            val instr = Class.forName("android.app.Instrumentation")
            val m: Method = instr.getDeclaredMethod(
                "callApplicationOnCreate", android.app.Application::class.java
            )
            H.hook(m, "tmapclean_appctx", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val a = chain.getArg(0)
                    if (a is android.content.Context) {
                        val ctx = a
                        Report.attach(ctx)
                        // 免 root 内置版：这里拿到的就是宿主 Application，是腾讯侧最早
                        // 能拿到它的地方。必须借它把可写服务绑上 —— mapclean.App 的转发
                        // 在腾讯进程里会先撞上厂商名单闸门（详见 App.ensureBound）。
                        if (a is android.app.Application) {
                            // 先把界面钩子装上：此刻宿主 ClassLoader 已就绪，而底栏
                            // 还没开始装配 —— 这正是修复「隐藏失效」的那一步。
                            installFromHost(a)
                            App.ensureBound(a)
                        }
                        Report.send(
                            ctx, "hook", "钩子已注入腾讯地图主进程 · ok="
                                    + H.ok.get() + " miss=" + H.miss.get(), true
                        )
                    }
                    return chain.proceed()
                }
            })
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "install appctx failed: " + t)
        }
    }
}
