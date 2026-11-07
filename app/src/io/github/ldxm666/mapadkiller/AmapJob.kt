package io.github.ldxm666.mapadkiller

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.util.Log

import java.util.Collections
import java.util.WeakHashMap

import io.github.libxposed.api.XposedInterface

/**
 * 两档"能真正省电"的杠杆：**周期任务**（WorkManager / JobScheduler）与**推送长连接**（ACCS / AGOO）。
 *
 * <h3>为什么需要这两个，而不是继续加 onReceive/onStartCommand</h3>
 * 上一轮的子进程 kill 集（[AmapSubprocess]）拦的是"广播到达后不做活"和"服务不常驻"，
 * 真机实测**进程数与 PSS 都没变化** —— 因为那些活本来就不体现为进程数/内存。
 * 真正持续吃 CPU 的是两类：**周期任务被反复重排**、**后台长连接的心跳与重连**。
 *
 * <h3>DEX / Manifest 证据（17.02.0.1541）</h3>
 * <pre>
 * androidx/work/impl/background/systemalarm/RescheduleReceiver   (classes.dex)  onReceive(Context,Intent)V
 *     —— Manifest 里**唯一**带 BOOT_COMPLETED 的组件（process=主进程）⇒ 开机即重排周期任务
 * androidx/work/impl/background/systemjob/SystemJobService        (classes.dex)  onStartJob(JobParameters)Z
 *     —— JobScheduler 执行通道（process=主进程）
 * com/taobao/accs/ChannelService                                 (classes7)  onCreate()V / static startKernel(Context)V
 * com/taobao/accs/internal/AccsJobService                        (classes4)  onStartJob(JobParameters)Z
 *     —— ⚠ **Manifest 里没有声明它**（components.csv 全表 342 条无此项）⇒ 挂上也只是防御，
 *        预期永不触发；JobScheduler 只认 manifest 里声明的 service。
 * org/android/agoo/accs/AgooService                              (classes8)  onCreate()V
 * com/autonavi/minimap/TaobaoIntentService                       (classes5)  onMessage(Context,Intent)V
 *     —— 以上 4 个 accs/agoo 组件都在 Manifest 的 process=":locationservice" 下
 * </pre>
 *
 * <h3>诚实的能力边界与代价</h3>
 * <ul>
 *   <li>杠杆 A 会让 **WorkManager 的周期任务不再被重排**。若高德用 WorkManager 做
 *       「离线地图续传 / 足迹后台上报」这类事，那些会停。用户可见影响：可能表现为
 *       离线地图下载进度在重启后不再自动续。</li>
 *   <li>杠杆 B 会让 **高德推送收不到**（行程提醒、活动推送、家人地图消息都在 accs/agoo 上）。
 *       这是用户明确接受换省电的代价，面板里可一键关掉。</li>
 *   <li>两个开关都是"true = 已拦截"，默认 true。</li>
 * </ul>
 */
object AmapJob {

    private const val CLS_RESCHEDULE =
            "androidx.work.impl.background.systemalarm.RescheduleReceiver"
    private const val CLS_SYSTEM_JOB =
            "androidx.work.impl.background.systemjob.SystemJobService"
    private const val CLS_ACCS_CHANNEL = "com.taobao.accs.ChannelService"
    private const val CLS_ACCS_JOB = "com.taobao.accs.internal.AccsJobService"
    private const val CLS_AGOO = "org.android.agoo.accs.AgooService"
    private const val CLS_TAOBAO_INTENT = "com.autonavi.minimap.TaobaoIntentService"

    // ── ACCS0 真凶链（见 installPushHard 的注释；全部 classes4.dex）──────────────
    private const val CLS_CONN_SERVICE = "com.taobao.accs.internal.ConnectionServiceImpl"
    private const val CLS_BASE_CONN = "com.taobao.accs.net.BaseConnection"
    private const val CLS_SPDY = "com.taobao.accs.net.SpdyConnection"
    private const val CLS_INAPP = "com.taobao.accs.net.InAppConnection"
    private const val CLS_NET_THREAD = "com.taobao.accs.net.SpdyConnection\$NetworkThread"

    /** ACCS 的服务基类：它的 `onStartCommand` 被 `BaseService$3.run` 周期回调（见 installPush 硬档 2）。 */
    private const val CLS_ACCS_BASE = "com.taobao.accs.base.BaseService"

    /** `Service.START_NOT_STICKY` —— 不想为这一个常量 import Service 的静态字段。 */
    private const val START_NOT_STICKY = 2

    // ══════════════════════════════════════════════ 杠杆 A：周期任务（主进程）

    /**
     * WorkManager 的两条执行通道一起掐。
     *
     * <p>为什么两条都要：`RescheduleReceiver` 负责"开机 / 时区变化后重新排期"（AlarmManager 通道），
     * `SystemJobService` 负责"到点了执行"（JobScheduler 通道）。只掐一条，另一条照样把活干完。
     *
     * <p>为什么 `onStartJob` 要顺手 `jobFinished(params,false)`：
     * 直接返回 false 而不调 jobFinished，框架会认为这个 job **还在跑**，
     * 到超时后可能重新投递 ⇒ 反而更耗电。`jobFinished(…, false)` 是"这个 job 我干完了、不要重排"
     * 的标准写法，等价于干净取消。
     */
    @JvmStatic
    fun installMain(cl: ClassLoader) {
        // ★ 安装期门控：开关关掉就**根本不装**这条链（零成本，而不是"装上进拦截器再放行"）。
        // 代价：改这个开关要强停并重开高德才生效。
        if (!Config.installGate(Config.K_SUBPROC_JOB_OFF)) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=subproc_job_off reason=switch_off")
            return
        }
        val before = H.ok.get()
        try {
            H.hookSig(H.cls(cl, CLS_RESCHEDULE), "onReceive", "amap_job_reschedule",
                    object : XposedInterface.Hooker {
                        private var reported = false
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            try {
                                if (!reported) {
                                    reported = true
                                    H.log(Log.INFO, MainHook.TAG,
                                            "event=job_reschedule_blocked receiver=RescheduleReceiver")
                                }
                            } catch (ignored: Throwable) {
                            }
                            return null   // 装上了就无条件拦；onReceive 是 void
                        }
                    }, Context::class.java, Intent::class.java)

            H.hookSig(H.cls(cl, CLS_SYSTEM_JOB), "onStartJob", "amap_job_system",
                    cancelJob("SystemJobService"),
                    JobParameters::class.java)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=job_failed reason=" + t.javaClass.simpleName)
        }
        H.log(Log.INFO, MainHook.TAG, "event=job_main_done hooks=" + (H.ok.get() - before))
    }

    // ══════════════════════════════════════════════ 杠杆 B：推送长连接（:locationservice）

    /**
     * ACCS / AGOO 三件套。
     *
     * <p>分两档，因为两档的副作用完全不同：
     * <ul>
     *   <li><b>安全档</b>（`push_off`）：`startKernel` 不执行（长连接根本不建）
     *       + `AgooService`/`TaobaoIntentService` 不处理消息。**不杀服务**，
     *       所以 accs 自己认为"启动成功"，**不会进退避重连**。</li>
     *   <li><b>激进档</b>（`push_kill_off`）：`ChannelService.onCreate` 直接 `stopSelf()`。
     *       这会把已经起来的通道服务按掉，收益更大，但 accs 内部可能判定"连接断了"并重试。</li>
     * </ul>
     * 分档的理由：Lead 要求"如果 A/B 采样发现 CPU 反而上升，就退一档"，
     * 而**退档不该需要重新出包**。所以给两个独立开关，默认都开。
     */
    @JvmStatic
    fun installPush(cl: ClassLoader) {
        val before = H.ok.get()
        // ★ 安装期门控（三档各自独立）：关掉的那一档**根本不装**，零成本。
        val safe = Config.installGate(Config.K_PUSH_OFF)
        val kill = Config.installGate(Config.K_PUSH_KILL_OFF)
        val hard = Config.installGate(Config.K_PUSH_HARD_OFF)
        try {
            if (safe) {
                // 安全档 1：长连接入口（static）—— 不建 socket、不起心跳线程
                H.hookSig(H.cls(cl, CLS_ACCS_CHANNEL), "startKernel", "amap_push_kernel",
                        noOp("ChannelService.startKernel"), Context::class.java)
                // 安全档 2：推送消息处理入口（void）
                H.hookSig(H.cls(cl, CLS_TAOBAO_INTENT), "onMessage", "amap_push_message",
                        noOp("TaobaoIntentService.onMessage"),
                        Context::class.java, Intent::class.java)
                // 安全档 3：agoo 的消息服务启动即退出（它是 Service，stopSelf 是幂等的）
                H.hookSig(H.cls(cl, CLS_AGOO), "onCreate", "amap_push_agoo",
                        idleService("AgooService"))
                // 防御档：Manifest 未声明，预期永不触发（见类注释），留着防未来版本补声明
                H.hookSig(H.cls(cl, CLS_ACCS_JOB), "onStartJob", "amap_push_accs_job",
                        cancelJob("AccsJobService"), JobParameters::class.java)
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_off reason=switch_off")
            }
            if (kill) {
                // 激进档：通道服务启动即退出
                H.hookSig(H.cls(cl, CLS_ACCS_CHANNEL), "onCreate", "amap_push_channel",
                        idleService("ChannelService"))
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_kill_off reason=switch_off")
            }
            if (hard) {
                // ★ 硬档 2（2026-10-20 真机 kill -3 抓栈发现）：ACCS 自己的「定时任务自循环」。
                //
                //   "ACCS0" Runnable utm=14520 stm=1221
                //     at ScheduledThreadPoolExecutor.delayedExecute / schedule / execute
                //     at com.taobao.accs.common.ThreadPoolExecutorFactory.execute
                //     at com.taobao.accs.base.BaseService.onStartCommand      ← 这里
                //     at com.taobao.accs.ChannelService.onStartCommand
                //     at com.taobao.accs.base.BaseService$3.run               ← 定时任务又调回 onStartCommand
                //
                // 也就是说 ACCS 会 `schedule → BaseService$3.run → onStartCommand → 再 schedule`
                // 自循环，把 ACCS0 顶到 96~100% 单核。前面所有 kill 点（startKernel /
                // ConnectionServiceImpl / SpdyConnection / NetworkThread）**都盖不到它**。
                //
                // 这是**纯 int 返回值**的方法（START_* 常量），不 call through ⇒ 没有 NPE 风险，
                // 也不会像 `stopSelf()` 那样产生 binder 往返（r9 那个自伤事故的教训）。
                H.hookSig(H.cls(cl, CLS_ACCS_BASE), "onStartCommand", "amap_push_base_start",
                        noOpInt("BaseService.onStartCommand", START_NOT_STICKY),
                        Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_hard_off reason=switch_off")
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=push_failed reason=" + t.javaClass.simpleName)
        }
        // 硬档：ACCS0 线程的真凶链（上面三档都拦不住它，见 installPushHard 注释）
        installPushHard(cl)
        H.log(Log.INFO, MainHook.TAG, "event=push_done hooks=" + (H.ok.get() - before))
    }

    /**
     * 硬档：直接掐断 **ACCS0 线程**的创建链。
     *
     * <h3>为什么上面三档拦不住（真机实测）</h3>
     * 17.02 装机后 `:locationservice` 线程级 top：**`ACCS0` 31.0%（TIME+ 0:09.26）**，
     * 其余 62 个线程全是 0.0%；而 `push_off` / `push_kill_off` / `AccsJobService` 三条 hook
     * **全部装上了**（`event=push_*` 有日志）。⇒ 那三条不是 ACCS 长连接的真实起点。
     *
     * <h3>真实创建链（DEX 实测，全部 classes4.dex）</h3>
     * <pre>
     *  ConnectionServiceImpl.start()V                       ← 连接服务启动（void）
     *  ConnectionServiceImpl.startChannelService()V         ← 起 ChannelService（void）
     *    → BaseConnection.startChannelService()V            ← 两种连接的共同实现（void，具体方法）
     *       → SpdyConnection.start()V / InAppConnection.start()V   ← 两种连接实现（都是 void）
     *          └ SpdyConnection.start() 是**唯一** new NetworkThread 的地方：
     *              --InvokeXref 'SpdyConnection$NetworkThread;-><init>' → 全库只有 1 处 = SpdyConnection.start()
     *              new SpdyConnection$NetworkThread(this, "<线程名>") + Thread.start()
     *                → SpdyConnection$NetworkThread.run()V    ← ★ 31% CPU 的 busy loop 本体
     *                   （同类还有 tryConnect(Z)V）
     * </pre>
     * 关键点：
     * <ul>
     *   <li>`ChannelService` **没有 `onBind`**（`--Methods` 全表 11 个方法里没有）⇒ 它是
     *       `startService` 起来的，所以 `onCreate → stopSelf()` 只杀得掉"服务壳"，
     *       **杀不掉已经在跑的连接线程** —— 这就是 `push_kill_off` 失效的原因。</li>
     *   <li>`BaseConnection.start()` 是 **abstract**，挂不上（也不能挂）；所以挂两个
     *       具体实现 `SpdyConnection.start()` / `InAppConnection.start()`，覆盖两种连接类型。</li>
     *   <li>所有目标方法都是 **void** ⇒ 短路只有一种正确写法：不 proceed、返回 null。
     *       没有返回值分支可被破坏，**不存在 NPE 风险**。</li>
     * </ul>
     *
     * <h3>为什么把 `NetworkThread.run()` 也挂上（兜底）</h3>
     * 上游两条（`start`）若因为接口分派/热更新被绕过，`run()` 是最后一道：
     * 它一 return，**ACCS0 线程立刻退出**，31% 直接归零。
     * 单独挂上游的隐患是"线程已经存在但不再受管"；单独挂 run() 的隐患是
     * "SpdyConnection 以为线程活着、不再重建"。**两个一起挂**才既保证线程消失、又不产生重建循环。
     *
     * <h3>代价</h3>
     * 高德推送（行程提醒 / 活动推送 / 消息中心）**收不到**。开关 `push_hard_off`，
     * 默认 true（用户明确要省电），面板里可一键关掉。
     */
    private fun installPushHard(cl: ClassLoader) {
        // ★ 安装期门控：硬档关掉就一条都不装（这是省电收益最大的一档，关掉通常是为了保留推送）。
        if (!Config.installGate(Config.K_PUSH_HARD_OFF)) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=push_hard_off reason=switch_off"
                    + " (installPushHard)")
            return
        }
        val before = H.ok.get()
        try {
            // 上游 1：连接服务启动（void）
            H.hookSig(H.cls(cl, CLS_CONN_SERVICE), "start", "amap_push_conn_start",
                    noOp("ConnectionServiceImpl.start"))
            // 上游 2：起通道服务（void）
            H.hookSig(H.cls(cl, CLS_CONN_SERVICE), "startChannelService", "amap_push_conn_channel",
                    noOp("ConnectionServiceImpl.startChannelService"))
            // 上游 3：两种连接类型的共同实现（BaseConnection.startChannelService 是**具体方法**，可挂）
            H.hookSig(H.cls(cl, CLS_BASE_CONN), "startChannelService", "amap_push_conn_base",
                    noOp("BaseConnection.startChannelService"))
            // 上游 4：两种连接实现（BaseConnection.start 是 abstract，不能挂，所以挂两个 override）
            H.hookSig(H.cls(cl, CLS_SPDY), "start", "amap_push_spdy_start",
                    noOp("SpdyConnection.start"))
            H.hookSig(H.cls(cl, CLS_INAPP), "start", "amap_push_inapp_start",
                    noOp("InAppConnection.start"))
            // 兜底：ACCS0 的 busy loop 本体 —— 一 return 线程立刻退出
            H.hookSig(H.cls(cl, CLS_NET_THREAD), "run", "amap_push_net_thread",
                    noOp("SpdyConnection\$NetworkThread.run"))
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=push_hard_failed reason=" + t.javaClass.simpleName)
        }
        H.log(Log.INFO, MainHook.TAG, "event=push_hard_done hooks=" + (H.ok.get() - before))
    }

    // ══════════════════════════════════════════════ Hooker

    /**
     * int 返回值方法：不 proceed，直接返回指定常量。
     *
     * <p>用于 `Service.onStartCommand(Intent,int,int)I` 这类**纯 int 返回**的入口 ——
     * 不 call through ⇒ 原方法（可能是"再 schedule 一个定时任务"）根本不执行；
     * 也不调 `stopSelf()` ⇒ **没有 binder 往返**（r9 那个自伤事故的教训）。
     */
    private fun noOpInt(label: String, value: Int): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var reported = false
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    if (!reported) {
                        reported = true
                        H.log(Log.INFO, MainHook.TAG, "event=push_blocked method=" + label
                                + " ret=" + value)
                    }
                } catch (ignored: Throwable) {
                }
                // 装上就无条件短路（安装期门控已经决定了"装不装"），零 Config 读。
                return java.lang.Integer.valueOf(value)
            }
        }
    }

    /** void 方法：不 proceed 即短路。 */
    private fun noOp(label: String): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var reported = false
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    if (!reported) {
                        reported = true
                        H.log(Log.INFO, MainHook.TAG, "event=push_blocked method=" + label)
                    }
                } catch (ignored: Throwable) {
                }
                return null
            }
        }
    }

    /**
     * Service 入口：**只短路，绝不 stopSelf**。
     *
     * <p>★ 血泪教训（真机 `kill -3` 抓栈，2026-10-20）：
     * <pre>
     * "ACCS0" ... at android.os.BinderProxy.transactNative
     *   at android.app.Service.stopSelf(Service.java:624)
     *   at io.github.ldxm666.mapadkiller.AmapJob$3.intercept(AmapJob.java:248)
     *   at com.taobao.accs.base.BaseService$3.run(SourceFile:17)   ← 高德把它放在 ScheduledThreadPoolExecutor 上**周期性**调用
     * schedstat=( ... 185607 ) utm=901 stm=1912
     * </pre>
     * 原实现在被 hook 的方法里调 `stopSelf()`。当那个方法被**周期调度**时，每次调用都走一次
     * binder 往返（`IActivityManager.stopServiceToken`），18 万次上下文切换 ⇒ ACCS0 常驻
     * **30% 单核** ⇒ 帧间隔出现周期性 50~67ms 长停顿。这不是省电，这是**自伤**。
     *
     * <p>正确做法：**只 `return null`（不执行原方法）**，被拦的工作根本不发生；
     * 服务存活交给系统回收。`stopSelf()` 一律不调。
     * 日志用 `WeakHashMap` 做**每实例一次**，避免周期回调刷爆 logcat。
     */
    private val IDLE_REPORTED: MutableMap<Any, Boolean> =
            Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private fun idleService(label: String): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    // 只短路，绝不 stopSelf()（见上方 ACCS0 死循环证据）。
                    val self = chain.getThisObject()
                    if (self != null && IDLE_REPORTED.put(self, java.lang.Boolean.TRUE) == null) {
                        H.log(Log.INFO, MainHook.TAG, "event=push_service_idle service=" + label)
                    }
                } catch (ignored: Throwable) {
                }
                return null
            }
        }
    }

    /**
     * `onStartJob(JobParameters)Z`：干净取消这个 job。
     *
     * <p>返回 false + `jobFinished(params,false)`：告诉框架"已结束、不要重排"。
     * 只返回 false 不调 jobFinished 会留下"运行中"的假状态，超时后可能被重新投递。
     */
    private fun cancelJob(label: String): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var reported = false
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    val self = chain.getThisObject()
                    val params = chain.getArg(0)
                    if (self is JobService && params is JobParameters) {
                        try {
                            self.jobFinished(params, false)
                        } catch (ignored: Throwable) { }
                    }
                    if (!reported) {
                        reported = true
                        H.log(Log.INFO, MainHook.TAG, "event=job_cancelled job=" + label)
                    }
                } catch (ignored: Throwable) {
                }
                return java.lang.Boolean.FALSE
            }
        }
    }
}
