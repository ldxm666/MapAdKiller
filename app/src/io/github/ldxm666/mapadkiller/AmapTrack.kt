package io.github.ldxm666.mapadkiller

import android.app.Application
import android.util.Log

import io.github.libxposed.api.XposedInterface

/**
 * 阿里 UT 埋点（`com.alibaba.analytics`）的**初始化闸门**。
 *
 * <h3>为什么闸口在主进程的 `AnalyticsMgr`，而不是子进程的 `AnalyticsService`</h3>
 * 17.02 实测（classes.dex）：
 * <pre>
 * Lcom/alibaba/analytics/AnalyticsService;   super=android.app.Service
 *   &lt;init&gt;()V / onBind(Intent)IBinder / onDestroy()V / onLowMemory()V
 *   —— **没有 onStartCommand**（上一版 power-inventory 写的"onStartCommand→stopSelf"是错的）
 * Lcom/alibaba/analytics/AnalyticsMgr;       62 个方法
 *   public static init(Landroid/app/Application;)V     ← 唯一的初始化入口
 *   public static checkInit()Z                          ← SDK 自带的"是否已初始化"查询
 *   static  bindService()Z   → Context.bindService(Intent,ServiceConnection,int)
 *                            + newLocalAnalytics()（两条分支都会调）
 * </pre>
 * `AnalyticsService` 是**纯 bound service**：只有 `AnalyticsMgr.bindService()` 会把它拉起来。
 * 因此：
 * <ul>
 *   <li>拦 `AnalyticsService.onBind` 返回 null ⇒ 客户端拿到 null binder，UT 内部大概率 NPE
 *       ⇒ **不做**（这是典型的"半初始化"事故形态）；</li>
 *   <li>拦 `AnalyticsMgr.bindService` 返回 false ⇒ 该方法体内两条分支都会调
 *       `newLocalAnalytics()`（本地降级实现），绕过它等于把 SDK 变成未定义状态 ⇒ **不做**；</li>
 *   <li>拦 `init(Application)` ⇒ UT **整体不初始化**，`checkInit()` 自然为 false，
 *       `bindService()` 也就不会被调用、`AnalyticsService` 不会被 bind。
 *       这是 SDK **自己设计好的"未初始化"状态**，所有调用方都必须先过 `checkInit()`，
 *       所以不会出现"以为初始化了其实没有"的半状态 ⇒ **这是唯一安全的闸口**。</li>
 * </ul>
 *
 * <h3>省掉什么</h3>
 * UT 的初始化链会拉起：`AnalyticsService`（在 `:locationservice` 进程）、
 * 设备标识采集、会话/页面埋点、周期上传（`triggerUpload`）。
 * 拦掉之后这些全部不发生 —— 既省电省流量，也少一条设备指纹上报通道。
 *
 * <h3>风险与回滚</h3>
 * 开关 `track_off`（默认 true = 已拦截）。**这是本轮唯一默认开启的"谨慎类"拦截**：
 * 如果真机出现任何"配置不生效 / AB 实验错乱"之类无法归因的异常，**第一个就关它**
 * （`track_off=false`）。埋点数据丢失不影响任何用户可见功能，但高德确实可能用 UT
 * 做配置下发，这一点静态无法排除，已写进报告的「未确认项」。
 */
object AmapTrack {

    private const val CLS_MGR = "com.alibaba.analytics.AnalyticsMgr"

    @JvmStatic
    fun install(cl: ClassLoader) {
        // ★ 安装期门控：track_off 关掉 ⇒ 一条都不装（零成本）。
        if (!Config.installGate(Config.K_TRACK_OFF)) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=track_off reason=switch_off")
            return
        }
        try {
            val mgr = H.cls(cl, CLS_MGR)
            if (mgr == null) {
                H.log(Log.WARN, MainHook.TAG, "event=anchor_miss anchor=AnalyticsMgr")
                return
            }
            H.hookSig(mgr, "init", "amap_track_init", BLOCK_INIT, Application::class.java)
            H.hookSig(mgr, "checkInit", "amap_track_check", BLOCK_CHECK)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=track_failed reason=" + t.javaClass.simpleName)
        }
    }

    /** `void init(Application)` ⇒ 不 proceed 直接 return null（装上就无条件）。 */
    private val BLOCK_INIT: XposedInterface.Hooker = object : XposedInterface.Hooker {
        private var reported = false
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                if (!reported) {
                    reported = true
                    H.log(Log.INFO, MainHook.TAG, "event=track_blocked method=AnalyticsMgr.init")
                }
            } catch (ignored: Throwable) {
            }
            return null
        }
    }

    /** `boolean checkInit()` ⇒ 返回 false，与"未初始化"的语义完全一致（装上就无条件）。 */
    private val BLOCK_CHECK: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            return java.lang.Boolean.FALSE
        }
    }
}
