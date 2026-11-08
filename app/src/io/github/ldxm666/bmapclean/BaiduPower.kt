package io.github.ldxm666.bmapclean

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.util.Log

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

/**
 * 百度地图的**省电杠杆**（与高德 {@code mapadkiller/AmapJob} 同一套范式）。
 *
 * <h3>为什么放在 bmapclean</h3>
 * 用户要求「各家内置版与模块版严格分开」：百度内置版只含 {@code bmapclean + mapclean}，
 * **不能含 mapadkiller**；而省电能力又要同时进模块版（模块含三家）。所以实现落在 bmapclean，
 * 两边共用一份代码。
 *
 * <h3>锚点来源</h3>
 * 全部来自 {@code .agent_work/logs_and_dumps/baidu/power-anchors-22.0.50.md}（dex-scout2 侦察）。
 * **不猜类名**：每个锚点都用 {@link #h} 做「类/方法判空 → 找不到就记 missing 并跳过」。
 *
 * <h3>三条设计约束</h3>
 * <ol>
 *   <li><b>关闭 = 不加载</b>：{@link Cfg#installGate} 关掉 ⇒ 该档一条 hook 都不装；</li>
 *   <li><b>拦截器内零配置读</b>：装上就无条件短路；</li>
 *   <li><b>失败只降级</b>：任何异常只记日志，绝不影响宿主。</li>
 * </ol>
 */
class BaiduPower private constructor() {

    companion object {

        // 本轮统计（install 结束时一次性打出）
        private var hooks = 0
        private var skipped = 0
        private var missing = 0

        // ══════════════════════════════════════════════ 入口

        @JvmStatic
        fun install(cl: ClassLoader, pkg: String) {
            hooks = 0; skipped = 0; missing = 0
            installPush(cl)
            installBackground(cl)
            installTimer(cl)
            installWidget(cl)
            installTrack(cl)
            H.log(Log.INFO, MainHook.TAG, "event=baidu_power_install hooks=" + hooks
                    + " skipped=" + skipped + " missing=" + missing + " pkg=" + pkg)
        }

        // ══════════════════════════════════════════════ ① 推送长连接

        private fun installPush(cl: ClassLoader) {
            val safe = Cfg.installGate(Cfg.K_PUSH_OFF)
            val hard = Cfg.installGate(Cfg.K_PUSH_HARD_OFF)
            val kill = Cfg.installGate(Cfg.K_PUSH_KILL_OFF)
            val K = Cfg.K_PUSH_HARD_OFF

            if (!safe && !hard && !kill) {
                skipped += 3
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_off+push_hard_off+push_kill_off"
                        + " reason=switch_off")
                return
            }

            if (hard) {
                // ★ 单点咽喉：推送启动的唯一分发器，一次覆盖所有入口。
                h(cl, "com.baidu.android.pushservice.w", "a", 1,
                        "baidu.power.push.dispatch", K, noOp("push.w.a(Intent)"))
                // 服务入口：不常驻。
                h(cl, "com.baidu.android.pushservice.PushService", "onStartCommand", 3,
                        "baidu.power.push.service", K, notSticky("PushService.onStartCommand"))
                // Job 执行体：干净取消，系统不再重排。
                h(cl, "com.baidu.android.pushservice.job.PushJobService", "onStartJob", 1,
                        "baidu.power.push.job", K, cancelJob("PushJobService.onStartJob"))
                // Job 注册器：不再 schedule。
                h(cl, "com.baidu.android.pushservice.v.b\$a", "a", 1,
                        "baidu.power.push.jobreg", K, noOp("push.v.b\$a.a(Z)"))
                // ★ 周期闹钟：方法体只有一条 AlarmManager.setRepeating，拦掉即无周期唤醒。
                h(cl, "com.baidu.android.pushservice.c0.a", "a", 3,
                        "baidu.power.push.alarm", K, noOp("push.c0.a.a(JJ,PendingIntent)"))
            } else {
                skipped++
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_hard_off reason=switch_off")
            }

            if (safe) {
                // 安全档与硬档共用同一批锚点（百度侧没有"只拦消息不拦连接"的独立入口），
                // 所以安全档单独开时只装分发器一条，避免与硬档重复。
                if (!hard) {
                    h(cl, "com.baidu.android.pushservice.w", "a", 1,
                            "baidu.power.push.dispatch", Cfg.K_PUSH_OFF, noOp("push.w.a(Intent)"))
                }
            } else {
                skipped++
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_off reason=switch_off")
            }

            if (kill) {
                h(cl, "com.baidu.android.pushservice.manageach.components.BaseAchService",
                        "onStartCommand", 3, "baidu.power.push.ach",
                        Cfg.K_PUSH_KILL_OFF, notSticky("BaseAchService.onStartCommand"))
                h(cl, "com.baidu.android.pushservice.PushServiceReceiver", "onReceive", 2,
                        "baidu.power.push.receiver", Cfg.K_PUSH_KILL_OFF, noOp("PushServiceReceiver.onReceive"))
                h(cl, "com.baidu.android.pushservice.RegistrationReceiver", "onReceive", 2,
                        "baidu.power.push.reg", Cfg.K_PUSH_KILL_OFF, noOp("RegistrationReceiver.onReceive"))
            } else {
                skipped++
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_kill_off reason=switch_off")
            }
        }

        // ══════════════════════════════════════════════ ② 后台常驻服务

        private fun installBackground(cl: ClassLoader) {
            if (!Cfg.installGate(Cfg.K_BG_SERVICE_OFF)) {
                skipped++
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=subproc_off reason=switch_off")
                return
            }
            val K = Cfg.K_BG_SERVICE_OFF
            // Navigation/trip notifications belong to the user. Never stop the
            // shared NotificationService as an advertising/background task.
            h(cl, "com.baidu.mapframework.component3.update.ComUpdateService", "onStartCommand", 3,
                    "baidu.power.bg.update", K, notSticky("ComUpdateService.onStartCommand"))
            // ⚠ 父类是 IntentService ⇒ 真正的工作在 onHandleIntent；这里按 scout 建议拦 onStartCommand
            //   （IntentService.onStartCommand 只做入队，拦掉即不排队）。若实测仍有唤醒，
            //   下一版改挂 onHandleIntent。
            h(cl, "com.baidu.baidumaps.desktopwidget.realbus.MapRealtimeBusWidgetService",
                    "onStartCommand", 3, "baidu.power.bg.realtimebus",
                    K, notSticky("MapRealtimeBusWidgetService.onStartCommand"))
        }

        // ══════════════════════════════════════════════ ③ 定时/任务

        private fun installTimer(cl: ClassLoader) {
            if (!Cfg.installGate(Cfg.K_TIMER_OFF)) {
                skipped++
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=subproc_job_off reason=switch_off")
                return
            }
            val K = Cfg.K_TIMER_OFF
            // 穿山甲下载重试 Job（广告后台的一部分，但走 JobScheduler，归本档）
            h(cl, "com.byazt.n.RetryJobSchedulerService", "onStartJob", 1,
                    "baidu.power.timer.retryjob", K, cancelJob("RetryJobSchedulerService.onStartJob"))
            // 第三方 push 自己的调度
            h(cl, "com.xiaomi.push.ec", "a", 1, "baidu.power.timer.xm", K, noOp("xiaomi.push.ec.a(J)"))
            h(cl, "com.meizu.e.a", "c", 0, "baidu.power.timer.mz", K, constFalse("meizu.e.a.c()"))
            // ⚠ 明确**不挂**：org.chromium.base.compat.ApiHelperForM.setAlarmManagerExactAndAllowWhileIdle
            //   （WebView 内部实现，挂了会破坏所有 WebView 定时器）
        }

        // ══════════════════════════════════════════════ ④ 桌面小组件

        private fun installWidget(cl: ClassLoader) {
            if (!Cfg.installGate(Cfg.K_WIDGET_OFF)) {
                skipped++
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=subproc_widget_off reason=switch_off")
                return
            }
            val K = Cfg.K_WIDGET_OFF
            // 虚方法必须**逐个子类**挂：挂父类拦不住覆写。
            h(cl, "com.baidu.baidumaps.desktopwidget.common.BaseWidgetProvider", "onReceive", 2,
                    "baidu.power.widget.base", K, widgetUpdate("BaseWidgetProvider.onReceive"))
            h(cl, "com.baidu.baidumaps.desktopwidget.intelligence.IntelliWidgetProvider", "onReceive", 2,
                    "baidu.power.widget.intelli", K, widgetUpdate("IntelliWidgetProvider.onReceive"))
            h(cl, "com.baidu.baidumaps.desktopwidget.MapWidgetProvider", "onUpdate", 3,
                    "baidu.power.widget.map", K, noOp("MapWidgetProvider.onUpdate"))
        }

        // ══════════════════════════════════════════════ ⑤ 埋点 / 第三方 SDK（含广告后台）

        private fun installTrack(cl: ClassLoader) {
            if (!Cfg.installGate(Cfg.K_TRACK_OFF)) {
                skipped++
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=track_off reason=switch_off")
                return
            }
            val K = Cfg.K_TRACK_OFF
            // ⚠ 穿山甲**不能拦 init**。TTAdSdk.init(Context,TTAdConfig) 的唯一职责是把
            // config 存进静态字段；真正的 SDK 启动在 start(TTAdSdk$Callback)。
            //
            // 2.2.6 及以前这里返回 false ⇒ TTAdConfig 恒为 null。百度进路线规划拉竞价广告时
            // （IntegratedAdProvider.fetchBiddingAd → GromoreBiddingAd.u → Initializer.c）
            // 照旧调 TTAdSdk.start()，start 内部 bm() 校验 config 非空，直接抛
            //   IllegalArgumentException: TTAdConfig is null, please exec TTAdSdk.init before TTAdSdk.start.
            // 该异常落在主线程 BaseHttpResponseHandler 回调里 ⇒ 百度 BMUncaughtExceptionHandler
            // → UICrashCollector → Process.killProcess(SIG 9) 自杀 = 用户看到的「进路线规划闪退」。
            // 真机日志实证：10-27 15:57:19.842 pid 17468，12:25:06 pid 2314，同一形态。
            //
            // 正确断点是 start：init 放行（只存 config，不联网、不起线程），start 短路 ⇒
            // SDK 依然不启动、不产生广告流量，但任何 Pangle API 的 bm() 校验都能过，不会再抛。
            h(cl, "com.bytedance.sdk.openadsdk.TTAdSdk", "start", 1,
                    "baidu.power.ad.start", K, noOp("TTAdSdk.start(TTAdSdk\$Callback)"))
            // CSJ Binder 池服务：不发布 binder。
            h(cl, "com.byazt.xx.BinderPoolService", "onBind", 1,
                    "baidu.power.ad.binder", K, constNull("BinderPoolService.onBind(Intent)"))
            // 百度自家统计：锚点未在 scout 报告中给出 ⇒ 见"未覆盖"表。
        }

        // ══════════════════════════════════════════════ 锚点安装器（判空 + 计数）

        /**
         * 装一条 hook：类找不到 / 方法找不到 ⇒ 记 {@code missing} 并打
         * {@code event=baidu_power_anchor_missing}，**绝不抛异常**。
         */
        private fun h(cl: ClassLoader, clsName: String, method: String,
                      argc: Int, id: String, key: String,
                      hk: XposedInterface.Hooker) {
            try {
                val c = H.cls(cl, clsName)
                if (c == null) {
                    missing++
                    H.log(Log.INFO, MainHook.TAG, "event=baidu_power_anchor_missing key=" + key
                            + " anchor=" + clsName + " reason=no_class")
                    return
                }
                val m: Method? = H.method(c, method, argc)
                if (m == null) {
                    missing++
                    H.log(Log.INFO, MainHook.TAG, "event=baidu_power_anchor_missing key=" + key
                            + " anchor=" + clsName + "." + method + " reason=no_method")
                    return
                }
                if (H.hook(m, id, hk)) {
                    hooks++
                } else {
                    missing++
                    H.log(Log.INFO, MainHook.TAG, "event=baidu_power_anchor_missing key=" + key
                            + " anchor=" + clsName + "." + method + " reason=hook_failed")
                }
            } catch (t: Throwable) {
                missing++
                H.log(Log.WARN, MainHook.TAG, "event=baidu_power_anchor_missing key=" + key
                        + " anchor=" + clsName + "." + method + " reason=" + t.javaClass.simpleName)
            }
        }

        // ══════════════════════════════════════════════ Hooker（装上即无条件短路）

        /** void 方法：不 proceed 即短路。 */
        @JvmStatic
        fun noOp(label: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                private var reported = false
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        if (!reported) {
                            reported = true
                            H.log(Log.INFO, MainHook.TAG, "event=baidu_power_blocked method=" + label)
                        }
                    } catch (ignored: Throwable) { }
                    return null
                }
            }
        }

        /** {@code onStartCommand}：返回 START_NOT_STICKY，不 call through、**不 stopSelf**。 */
        @JvmStatic
        fun notSticky(label: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                private var reported = false
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        if (!reported) {
                            reported = true
                            H.log(Log.INFO, MainHook.TAG, "event=baidu_power_blocked method=" + label
                                    + " ret=START_NOT_STICKY")
                        }
                    } catch (ignored: Throwable) { }
                    return Integer.valueOf(android.app.Service.START_NOT_STICKY)
                }
            }
        }

        /** {@code onStartJob}：先 jobFinished(params,false) 再返回 false（不留"运行中"假状态）。 */
        @JvmStatic
        fun cancelJob(label: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                private var reported = false
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val self = chain.getThisObject()
                        val params = chain.getArg(0)
                        if (self is JobService && params is JobParameters) {
                            try { self.jobFinished(params, false) }
                            catch (ignored: Throwable) { }
                        }
                        if (!reported) {
                            reported = true
                            H.log(Log.INFO, MainHook.TAG, "event=baidu_power_blocked job=" + label)
                        }
                    } catch (ignored: Throwable) { }
                    return java.lang.Boolean.FALSE
                }
            }
        }

        /** 只拦 {@code APPWIDGET_UPDATE}，其余 action 照常放行（避免误伤添加/删除组件）。 */
        @JvmStatic
        fun widgetUpdate(label: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                private var reported = false
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val arg = chain.getArg(1)
                        if (arg is Intent && isWidgetUpdate(arg.action)) {
                            if (!reported) {
                                reported = true
                                H.log(Log.INFO, MainHook.TAG, "event=baidu_power_blocked widget=" + label
                                        + " action=" + arg.action)
                            }
                            return null
                        }
                    } catch (ignored: Throwable) { }
                    return chain.proceed()
                }
            }
        }

        @JvmStatic
        fun isWidgetUpdate(action: String?): Boolean {
            return "android.appwidget.action.APPWIDGET_UPDATE" == action
        }

        /** 返回常量 false。 */
        @JvmStatic
        fun constFalse(label: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                private var reported = false
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        if (!reported) {
                            reported = true
                            H.log(Log.INFO, MainHook.TAG, "event=baidu_power_blocked method=" + label
                                    + " ret=false")
                        }
                    } catch (ignored: Throwable) { }
                    return java.lang.Boolean.FALSE
                }
            }
        }

        /** 返回 null（{@code onBind} 不发布 binder）。 */
        @JvmStatic
        fun constNull(label: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                private var reported = false
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        if (!reported) {
                            reported = true
                            H.log(Log.INFO, MainHook.TAG, "event=baidu_power_blocked method=" + label
                                    + " ret=null")
                        }
                    } catch (ignored: Throwable) { }
                    return null
                }
            }
        }

        /** 保留给未来锚点：Context 判空模板。 */
        @Suppress("unused")
        private fun hasContext(o: Any?): Boolean {
            return o is Context
        }
    }
}