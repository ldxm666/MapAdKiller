package io.github.ldxm666.mapclean

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Installs the floating entry point inside an integrated (rootless) host.
 *
 * <h3>Where it hooks</h3>
 * [Application.ActivityLifecycleCallbacks] is registered through a hook on
 * `Application.onCreate`. Using the public lifecycle callbacks rather than hooking
 * each map Activity keeps this independent of 高德's obfuscated activity names, and it works
 * for every Activity the map opens -- the orb follows the user.
 *
 * <h3>Scope</h3>
 * The layer is only attached to the map's own main Activities, not to every Activity. Adding
 * it to a login screen or a web-view payment page would be noise, so the controller filters on
 * "does this Activity belong to the map package and is it not one of the lightweight helpers".
 */
class FloatingHooks private constructor() {

    companion object {

        private const val TAG = "MapCleanFloat"

        /** One 「module mode, floating skipped」 line per process (install can be called per package). */
        @Volatile
        private var skippedLogged = false

        /**
         * 幂等标记：本进程是否已经挂过 `Application.onCreate`。
         *
         * <p>`onPackageReady` 在同一个进程里可能被回调多次（真机日志里见过 3 次），而
         * [install] 的调用点在 `H.markInstalled(pkg)` **之前**，没有这个标记就会
         * 重复 `module.hook(Application.onCreate)`、重复
         * `registerActivityLifecycleCallbacks`、重复 `registerReceiver`。
         * 重复的悬浮球目前被 `FloatingController` 按 Activity 去重挡住了（所以没炸），
         * 但每一条重复回调都在白跑，且今后任何一处去掉那个去重就会变成"两个球叠在一起"。
         */
        @Volatile
        private var installed = false

        /** One "I looked at this Activity class" line per class, so the log stays readable. */
        private val reported: MutableSet<String> = java.util.Collections.synchronizedSet(
            java.util.LinkedHashSet<String>()
        )

        /**
         * Activities that must never receive the overlay.
         *
         * Note these are <b>class-name</b> prefixes, and 高德's own screens live under
         * `com.autonavi.map.*` -- not under the app id. Matching the app id here would
         * never exclude anything.
         *
         * <h3>SplashActivity is deliberately absent (2.2.2)</h3>
         * It used to be listed as "transient; the orb belongs on the map". Device evidence on
         * 高德 17.02.0.1541 showed the opposite: `NewMapActivity` was the resumed Activity
         * but had <b>no window at all</b> (`dumpsys window` listed only
         * `SplashActivity`), so the layer went into a content root that is never composited
         * and the orb was invisible everywhere. That exclusion was the direct cause of
         * 「悬浮球看不见」. The [hasVisibleWindow] gate is the correct filter: attach to
         * whichever Activity actually owns a visible window, whatever it is called.
         * `HicarSplashActivity` (车机) stays excluded -- untested, and not the phone map UI.
         */
        private val EXCLUDED: Array<String> = arrayOf(
            "com.autonavi.map.activity.HicarSplashActivity",
            "com.autonavi.minimap.wxapi",       // 微信回调
            "com.autonavi.minimap.ddshare",     // 钉钉分享
            "com.autonavi.minimap.basemap.errorback",
            "com.tencent.tauth",                // QQ 授权
            "com.tencent.connect",
            "com.alipay.sdk",
            "com.google.ar.core",
            "com.unionpay",
        )

        /**
         * How often a single Activity is re-checked for a visible window, and how far apart.
         *
         * <p>`onActivityResumed` can fire before the first layout pass, so one check is not
         * enough; but the old code retried forever through `root.post()`, which on an
         * Activity that never gets a window is an endless loop. Six tries over ~1.5 s covers the
         * cold-start gap and then gives up, leaving the next real resume to try again.
         */
        private const val MAX_VISIBILITY_RETRIES = 6
        private const val VISIBILITY_RETRY_MS = 250L

        /** Per-Activity retry counters; weak so a finished Activity is not kept alive. */
        private val retries: MutableMap<Activity, Int> = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<Activity, Int>()
        )

        /** 三家地图的包名 —— 悬浮层只为它们工作，别的宿主一条钩子都不配。 */
        private val FLOATING_HOSTS: Array<String> = arrayOf(
            "com.tencent.map",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
        )

        private fun isFloatingHostPackage(pkg: String?): Boolean {
            if (pkg == null) return false
            for (host in FLOATING_HOSTS) {
                if (host == pkg) return true
            }
            return false
        }

        /**
         * 本进程的进程名，取不到返回 `null`（调用方按“取不到就不拦”处理）。
         *
         * <p>`Application.getProcessName()` 自 API 28 起是公开静态方法，本模块
         * `minSdkVersion=28`，直接调即可；仍包 try/catch 是因为某些 ROM 的
         * ActivityThread 在极早期（onPackageReady 之前）还没把进程名写进去。
         */
        private fun currentProcessName(): String? {
            try {
                return Application.getProcessName()
            } catch (t: Throwable) {
                return null
            }
        }

        /** Registers the lifecycle watcher. Called once per host process. */
        @JvmStatic
        fun install(module: XposedModule, hostPackage: String, darkCapable: Boolean) {
            // 模块模式（root / LSPosed 用户）**一条 hook 都不装**：不是装上再判断，而是根本不安装，
            // 连 ActivityLifecycleCallbacks 都不注册 ⇒ 零成本、零残留。
            // 悬浮窗是免 root 内置版专属功能；root 用户用模块 App 自己的设置页。
            if (!io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()) {
                if (!skippedLogged) {
                    skippedLogged = true
                    Log.i(TAG, "event=floating_skipped reason=module_mode")
                }
                return
            }
            // ★ 2.3.1（性能/正确性）：**安装前**就按宿主包名 + 进程名过滤，绝不“装上再判断”。
            //
            //   v2.3.0 之前这里是“先挂 Application.onCreate、进回调再说”，后果有两层：
            //   1) 腾讯地图有 4 个进程（:ilink.ServiceProcess / :push / :daemon / :sandbox），
            //      每个进程都白挂一条 Application.onCreate hook、注册一次
            //      BroadcastReceiver、跑一遍 HostPrefs.diagnose()（反射四条路 + 日志）。
            //      真机日志：`floating hooks installed for com.tencent.map` 出现 3~5 次/次冷启动，
            //      而只有主进程有 Activity，其余 4 份完全不工作。
            //   2) 内置版是把模块**并进宿主**的，LSPatch 会把 onPackageReady 投递给所有加载了
            //      该包的进程；历史上日志里出现过 host=com.miui.contentcatcher /
            //      com.google.android.webview —— 那些进程里也照样装了一遍悬浮层钩子。
            //
            //   过滤条件是“三重与”：是本厂商的三个地图包之一、且进程名 == 包名（即主进程）。
            //   不满足 ⇒ 一条 hook 都不装，连日志都只打一行。
            if (!isFloatingHostPackage(hostPackage)) {
                if (!skippedLogged) {
                    skippedLogged = true
                    Log.i(TAG, "event=floating_skipped reason=foreign_package host=" + hostPackage)
                }
                return
            }
            val process = currentProcessName()
            if (process != null && hostPackage != process) {
                if (!skippedLogged) {
                    skippedLogged = true
                    Log.i(
                        TAG, "event=floating_skipped reason=subprocess host=" + hostPackage
                                + " process=" + process
                    )
                }
                return
            }
            // 幂等：放在 embedded 判定之后 —— 模块模式下提前 return 不应该占掉这个标记（虽然那种
            // 进程不会再进来，但把"真的开始挂 hook"作为置位条件是唯一不会骗人的语义）。
            synchronized(FloatingHooks::class.java) {
                if (installed) {
                    Log.i(TAG, "event=floating_install_skipped reason=already_installed")
                    return
                }
                installed = true
            }
            try {
                module.hook(Application::class.java.getDeclaredMethod("onCreate"))
                    .setId("floating.lifecycle")
                    .intercept(
                        HookGuard(
                            "floating.lifecycle",
                            object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    val result = chain.proceed()
                                    val self = chain.thisObject
                                    if (self is Application) {
                                        hook(self, hostPackage)
                                    }
                                    return result
                                }
                            },
                            object : HookGuard.Reporter {
                                override fun disabled(id: String, error: Throwable) {
                                    // 带栈顶帧：一条 lifecycle hook 掉了，后面所有悬浮窗逻辑都不工作，
                                    // 只打类名没法判断是框架不支持还是我们的代码写错了。
                                    Log.w(
                                        TAG, "event=floating_lifecycle_disabled reason="
                                                + HookGuard.describe(error)
                                    )
                                }
                            }
                        )
                    )
                Log.i(TAG, "floating hooks installed for " + hostPackage)
                // 安装时刻的 HostPrefs 状态快照：这时通常还没有 Activity，正好用来证明
                // 「反射四条路当时是空的」，以及稍后 remember() 补上之后同一条路就通了。
                HostPrefs.diagnose("install")
            } catch (t: Throwable) {
                // 这一条没挂上就把标记放回去：下次 onPackageReady 还有机会（真机上见过宿主把
                // Application.onCreate 延后到很晚才走到的情况）。
                synchronized(FloatingHooks::class.java) { installed = false }
                Log.w(TAG, "floating hooks unavailable: " + t.javaClass.simpleName, t)
            }
        }

        private fun hook(application: Application, hostPackage: String) {
            // 救命通道：内置版关掉悬浮窗后没有任何 UI 入口（MainActivity 被裁掉了）。
            // 这个 receiver 让"关掉之后还能救回来"成为可能：
            //   adb shell am broadcast -a io.github.ldxm666.mapadkiller.RESTORE_FLOAT
            // 收到后把 float_enabled 写回 true 并立刻重新挂载，不需要清数据。
            try {
                application.registerReceiver(
                    object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                            try {
                                FloatingConfig.write(FloatingConfig.K_FLOAT_ENABLED, true)
                                FloatingController.reattachAll()
                                Log.i(TAG, "event=float_restored via=broadcast")
                            } catch (t: Throwable) {
                                Log.w(TAG, "float restore failed: " + t.javaClass.name)
                            }
                        }
                    },
                    android.content.IntentFilter("io.github.ldxm666.mapadkiller.RESTORE_FLOAT")
                )
                Log.i(TAG, "event=float_restore_channel ready")
            } catch (ignored: Throwable) {
            }

            try {
                application.registerActivityLifecycleCallbacks(object :
                    Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) {
                        if (!accept(activity, hostPackage)) return
                        // ★ 2.3.0：宿主的 Application 就在这里 —— 直给 HostPrefs，别让它去反射。
                        //   腾讯地图 11.7 里那四条反射路全断（真机逐条日志见 HostPrefs.diagnose），
                        //   而写入路径本来就拿着这个 Application（DefaultConfig.apply(app) /
                        //   FloatingConfig.ensureService(application)）—— 两套 Context 就是
                        //   「配置写得进、面板显示只读」的根因。统一到这一条。
                        HostPrefs.remember(activity.application)
                        FloatingConfig.ensureService(application)
                    }

                    override fun onActivityStarted(activity: Activity) {
                        if (!accept(activity, hostPackage)) return
                        HostPrefs.remember(activity.application)
                        // The content tree may not exist during onCreate; retry on each start.
                        attachWhenVisible(activity)
                    }

                    override fun onActivityResumed(activity: Activity) {
                        if (!accept(activity, hostPackage)) return
                        HostPrefs.remember(activity.application)
                        // 每次 resume 都走一次：attach() 自己按 root 去重，adoptAsHost() 保证
                        // 「换了 Activity 就搬过去、同一时刻只有一个浮层」。
                        attachWhenVisible(activity)
                        FloatingController.refreshTheme(activity)
                        // 面板点了「抓取云数据」→ 这个进程还活着，必须自己重启一次才能进采集模式。
                        try {
                            CaptureGate.applyRecaptureRequest(activity)
                        } catch (ignored: Throwable) {
                        }
                        // 首次安装的「抓取官方数据」：抓到就提示并自动重启一次。
                        try {
                            CaptureGate.tick(activity)
                        } catch (ignored: Throwable) {
                        }
                    }

                    override fun onActivityPaused(activity: Activity) {}

                    override fun onActivityStopped(activity: Activity) {}

                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

                    /**
                     * ★ 2.3.0：先摘层，再让它走。
                     *
                     * <p>腾讯地图首启那段：`WelcomeActivity.finish()` → 挂在它 DecorView 上的
                     * 浮层跟着窗口一起没，但 `layers` / `attached` 里还留着死引用：
                     * 轻则 `WindowLeaked`，重则重建同名 Activity 时被去重闸门挡掉。
                     * 这里同步摘除，并把重试计数一起清掉。
                     */
                    override fun onActivityDestroyed(activity: Activity) {
                        FloatingController.detachFor(activity, "destroyed")
                        retries.remove(activity)
                    }
                })
                Log.i(TAG, "activity lifecycle callbacks registered")
            } catch (t: Throwable) {
                Log.w(TAG, "register callbacks failed: " + t.javaClass.simpleName)
            }
        }

        /**
         * True when this Activity owns a window that is really on screen.
         *
         * <p>This is the fix for the invisible orb. 高德 17.02 resumes `NewMapActivity` but
         * composites a different Activity's window; the old code only asked "is this our
         * package", so the layer was inserted into a content root that never gets drawn and the
         * user saw nothing. Every clause below is a separate way for that to happen:
         * finishing/destroyed, no window, decor not attached, window not `VISIBLE`
         * (covers `GONE`/`INVISIBLE` and a window that is still being added), and a
         * decor that has no measured size yet.
         *
         * <p>Never throws: a ROM that answers oddly must degrade to "not visible", not crash the
         * lifecycle callback.
         */
        private fun hasVisibleWindow(a: Activity?): Boolean {
            try {
                if (a == null || a.isFinishing || a.isDestroyed) return false
                val w = a.window ?: return false
                val decor = w.decorView ?: return false
                if (!decor.isAttachedToWindow) return false
                // View#getWindowVisibility() is the window's own visibility flag (Window has no
                // such getter), so this catches GONE/INVISIBLE windows and ones still being added.
                if (decor.windowVisibility != android.view.View.VISIBLE) return false
                return decor.width > 0 && decor.height > 0
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * Attach the layer once, and only to a window that is actually visible.
         *
         * <p>If the window is not up yet the check is repeated a bounded number of times
         * ([MAX_VISIBILITY_RETRIES] × [VISIBILITY_RETRY_MS]) instead of being
         * written off: `onActivityResumed` can legitimately run before the first layout
         * pass. Nothing is recorded as "done" while the window is missing, so a later resume of
         * any eligible Activity gets a fresh, full budget.
         */
        private fun attachWhenVisible(activity: Activity?) {
            if (activity == null) return
            // A finished/destroyed Activity can never gain a window again; retrying there would keep
            // a dead Activity (and its DecorView) alive for the whole retry budget.
            if (activity.isFinishing || activity.isDestroyed) {
                retries.remove(activity)
                return
            }
            // Record it even when the window is not up yet: turning the overlay back on has to know
            // which windows to put it into, and that can happen long after the last resume.
            FloatingController.remember(activity)
            if (hasVisibleWindow(activity)) {
                retries.remove(activity)
                FloatingController.attach(activity)
                return
            }
            val seen = retries[activity]
            val attempt = seen ?: 0
            if (attempt >= MAX_VISIBILITY_RETRIES) return
            retries[activity] = attempt + 1
            val decor = try {
                activity.window?.decorView
            } catch (t: Throwable) {
                null
            }
            if (decor == null) return
            decor.postDelayed(object : Runnable {
                override fun run() {
                    attachWhenVisible(activity)
                }
            }, VISIBILITY_RETRY_MS)
        }

        private fun accept(activity: Activity?, hostPackage: String): Boolean {
            if (activity == null) return false
            val name = activity.javaClass.name
            // Match on the package the Activity *belongs to*, not on its class name.
            // 高德 declares its screens under com.autonavi.map.* (SplashActivity,
            // NewMapActivity, ...), so a class-name prefix test against the app id
            // "com.autonavi.minimap" rejected every Activity and the layer never attached.
            try {
                if (hostPackage != activity.packageName) {
                    return false
                }
            } catch (t: Throwable) {
                return false
            }
            for (prefix in EXCLUDED) {
                if (name.startsWith(prefix)) {
                    if (reported.add(name)) Log.i(TAG, "skip excluded activity " + name)
                    return false
                }
            }
            if (reported.add(name)) Log.i(TAG, "eligible activity " + name)
            return true
        }
    }
}
