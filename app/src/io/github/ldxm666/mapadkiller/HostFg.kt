package io.github.ldxm666.mapadkiller

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

import java.util.Collections
import java.util.WeakHashMap

/**
 * 进程内前台判定。
 *
 * <p>原来的 isForeground() 只看自己进程的 importance：别的应用（包括本模块自己的设置页）
 * 全屏盖上来时高德主进程必然掉到 SERVICE/CACHED，于是被判成后台；反过来后台跑前台服务
 * （importance=125）又会被判成前台，可能让车在库里也自动进巡航。这里改用 framework 的
 * ActivityLifecycleCallbacks 自维护 resumed 集合：只有宿主真的有 Activity 处于 resumed
 * 才算前台，完全不受别的进程影响。</p>
 *
 * <p>注册时机：AmapCruise.BOOT 拿到 Application 后立刻注册。若注册前已经有页面 resume 过
 * （热启动），everResumed 仍为 false，此时回落到旧的 isForeground() 兜底。</p>
 */
object HostFg {

    private val RESUMED: MutableMap<Activity, Boolean> =
            Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    @Volatile
    private var everResumed = false
    @Volatile
    private var topCls = "-"

    @JvmStatic
    fun attach(app: Application?) {
        if (app == null) return
        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {
                    RESUMED.put(a, java.lang.Boolean.TRUE)
                    everResumed = true
                    topCls = a.javaClass.name
                    // 模块版（普通 LSPosed 安装）没有悬浮窗，FloatingHooks 一条 hook 都不装，
                    // 所以「设置页点了抓取云数据 → 宿主下次 resume 自己重启进采集」只能在这里接。
                    // 内置版也走同一条（CaptureGate 内部每进程只判定一次，重复调用无害）。
                    try {
                        io.github.ldxm666.mapclean.CaptureGate.applyRecaptureRequest(a)
                    } catch (ignored: Throwable) { }
                }
                override fun onActivityPaused(a: Activity) {
                    RESUMED.remove(a)
                }
                override fun onActivityStopped(a: Activity) {
                    RESUMED.remove(a)
                }
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {
                    RESUMED.remove(a)
                }
            })
            H.log(Log.INFO, MainHook.TAG, "event=hostfg_attach app=" + app.javaClass.name)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=hostfg_attach failed err=" + t)
        }
    }

    /** 宿主是否在前台。 */
    @JvmStatic
    fun fg(): Boolean {
        if (!everResumed) return AmapCruise.isForeground()
        return RESUMED.isNotEmpty()
    }

    /** 最近一次 resume 的 Activity 类名，日志用。 */
    @JvmStatic
    fun top(): String {
        return topCls
    }
}
