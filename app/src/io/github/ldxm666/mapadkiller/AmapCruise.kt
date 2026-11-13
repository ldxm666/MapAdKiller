package io.github.ldxm666.mapadkiller

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

/**
 * 「自动进入巡航」模块接管驱动器。
 *
 * <h3>为什么由模块接管</h3>
 * 高德 17.02 设置页「通用 → 出行设置 → 自动进入巡航」是服务端下发的 AJX 页面：
 * 开关能点、状态跨进程重启保持，但实测点击时 **不写任何本地存储**
 * （hook SharedPreferences.EditorImpl / MapSharePreference.put* / AjxModuleLocalStorage.setItem*
 * 全部零命中，2218 个文件 stat 快照零变化），说明高德自己的触发链没有落地。
 * 而云控配置里功能是放开的：
 * <pre>
 * cloudconfig_aocs_sp_key_value.xml → MainMapCruise =
 *   {"switch&amp;ABTest":1,"params":{"minSpeed":15,"maxSpeed":200,"driveTime":5,
 *    "operateTime":5,"quickBackTime":5,"quickBackCountThreshold":2,"foregroundContinue":300}}
 * navi_cloud.mainCruiseSwitch = 1
 * </pre>
 * 所以模块按这套云控参数自己驱动：读高德定位服务的实时速度，满足条件就发
 * {@code amapuri://edog/home}（实测可直接进入"已巡航"界面）。
 *
 * <h3>2.3.0：从「串联 gate 乘法」改成「宽松默认 + 可观测」</h3>
 * 2.2.6 那一版把 6 道 gate 串起来、还要求连续 15 帧全过，乘出来的实际通过率接近 0，
 * 用户反馈就是「一直触发不了，等于无效」。这一版的做法：
 * <ul>
 *   <li><b>只留两条主判据</b>：速度持续达标（任一路来源）+ 净位移够（证明真在移动）。
 *       路径累计、精度、步数、突变、跳点全部降级为「这一帧的位移不计入」或「只记日志」；</li>
 *   <li><b>容忍抖动</b>：连续 {@link #HOLD_GRACE_TICKS} 帧不达标才清窗，单帧掉速不再清零；</li>
 *   <li><b>任何一路速度可用即可</b>：① 高德 ILocationService 最新点 → ② 定位回调流
 *       （hook Location#setSpeed）→ ③ 系统 LocationManager 最后已知点 → ④ 两次定位位移换算；
 *       位移法单独加严（时间 ×1.5、净位移 ×2），因为它最容易被 GNSS 抖动伪造；</li>
 *   <li><b>进入失败能自愈</b>：1.2s×5 复核，第 1 次失败换「只发 scheme」的路重发，
 *       第 3 次两路各补一发；两条路都留着，类名漂移也不会把唯一的路堵死；</li>
 *   <li><b>可观测</b>：{@code event=cruise_tick} 在心跳里带
 *       {@code why= kmh= src= hold=/need= net=/need_net= grace= probe=}，
 *       用户贴一行就能看出卡在哪；状态一变立刻打一条，不等 15s；</li>
 *   <li><b>立即测试</b>：{@link Config#K_CRUISE_FORCE_ONCE} 隐藏键（面板按钮）写入即跳过全部门槛
 *       强制尝试进入一次并把键清掉 —— 把「链路通不通」和「判定过不过」分开验证。</li>
 * </ul>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>默认关闭（{@link Config#K_CRUISE_AUTO} 默认 false）⇒ 老用户行为不变；</li>
 *   <li>只在 AMap 前台、且不在导航/巡航中时判定；</li>
 *   <li>定位不可用 / 无权限 / 任何异常一律静默降级，绝不崩宿主。</li>
 * </ul>
 */
class AmapCruise private constructor() {
    companion object {
        /**
         * 进入门槛。
         *
         * <h3>2.3.0 为什么把判据整体换掉</h3>
         * 2.2.x 的判定是「6 道串联 gate × 15 个 1Hz 采样，每一帧都要全过」。串联 gate 的通过率是
         * <b>相乘</b>的。按城市实测经验给单帧通过率：
         * 精度 &lt;=45m 约 0.85 × 路口/拥堵掉速约 0.95 × GNSS 速度抖动约 0.97 ×
         * 同一定位点被反复喂导致步长不够约 0.90 &asymp; <b>0.70</b> 单帧；
         * 0.70^15 &asymp; <b>0.0047</b>，也就是平均要开 200 秒（含红绿灯）才凑得齐一个窗口，
         * 而任何一帧失败还会把 speedOkSince 与位移基线<b>全部清零</b>。
         * 用户说的「一直触发不了」，乘出来就是这个数。
         *
         * <p>新判据只留两条<b>主判据</b>：
         * <ol>
         *   <li>速度：任一路速度源连续 driveSec 秒达到 minKmh（容忍 {@link #HOLD_GRACE_TICKS} 帧抖动）；</li>
         *   <li>净位移：窗口起点到当前点的直线距离达到 {@link #netNeedM()}（证明真的在移动，
         *       排除原地抖动）；<b>拿不到地理数据时这一条降级跳过，不否决</b>。</li>
         * </ol>
         * 路径累计、精度、步数、突变、跳点全部降级为「这一帧的位移不计入」或「只记日志」。
         */
        private const val MAX_KMH = 200f

        /** 触发速度下限（km/h）：面板「自动巡航 → 触发速度下限」，默认 12（原 18，见 Config）。 */
        private fun minKmh(): Float = Config.cruiseMinKmh().toFloat()

        /** 触发持续时间（毫秒）：面板「自动巡航 → 触发持续时间」，默认 8 秒（原 15 秒）。 */
        private fun driveMs(): Long = Config.cruiseDriveSec() * 1000L

        /**
         * 窗口内「路径」累计位移下限（米）。<b>已不是否决条件</b>，只在「净位移拿不到」时当第二证据。
         * 12km/h × 8s 约 26.7m，留余量取 60m 封顶。
         */
        private const val MIN_DIST_M = 60f

        /** 净位移硬上限（米）：{@link #netNeedM()} 的封顶值。 */
        private const val MIN_NET_M = 45f

        /**
         * 「攒够这么多步可信位移」= 第二种「真的在移动」的证据（净位移拿不到时用它）。
         * 原 6：1Hz 下 15 秒窗口要 6 步，只要有一帧被 dup/jump 吃掉就凑不齐（0.9^6 约 0.53 再乘别的）；
         * 现在 3 步（0.9^3 约 0.73），而且它只是备选证据，不再是必须项。
         */
        private const val MIN_WIN_FIXES = 3

        /**
         * 定位精度门槛（米）：比这更差的点<b>不累计路径</b>，但速度照用（见 {@link #gate}）。
         * 原 45：城市里 AMap 定位点精度常态 30~60m、室内/Wi-Fi 点 80~300m，
         * 45m 这条线把相当大一部分帧直接判死，是「永远不触发」的头号原因。放宽到 80m。
         */
        private const val ACC_MAX_M = 80f

        /** 单步位移硬上限（米）：一跳几百米的是多径跳点 —— 只作废这一帧的步长，不再清窗口。 */
        private const val STEP_MAX_M = 90f

        /** 相邻采样允许的最大速度突变（km/h）——<b>只记日志</b>，不再否决（路口掉速是常态）。 */
        private const val SPIKE_KMH = 45f

        /**
         * 进入失败/退出后的冷却。原 60000：复核失败、或被吞掉的 intent 要等整整一分钟才补一次，
         * 用户开出去一公里才「自己好了」。20s 够避免「刚退出来又弹进去」，又能及时自愈。
         */
        private const val ENTER_COOLDOWN_MS = 20000L
        private const val QUICK_BACK_MS = 5000L   // quickBackTime = 5s
        private const val QUICK_BACK_MAX = 3       // quickBackCountThreshold（12s 内连退 3 次才算「用户不想要」）

        /** 抑制窗口：云控没给时长，取 3 分钟 —— 太短会连发，太长会让用户觉得「开了没反应」。 */
        private const val SUPPRESS_MS = 3 * 60 * 1000L

        /** 采样周期：1s。 */
        private const val TICK_MS = 1000L

        /** 定位点超过这个年龄就不算「实时速度」。 */
        private const val SPEED_STALE_MS = 20000L

        /** 心跳日志周期；状态（why）一变就立刻打一条，不等满 15s。 */
        private const val HEARTBEAT_MS = 15000L

        /**
         * 位移法：两次有效定位至少隔这么久，算出来的速度才可信。
         * 原 1500 —— <b>1Hz 采样下相邻点 dt 约 1000ms &lt; 1500，位移法永远算不出速度</b>，
         * 于是 moveKmh 恒为 0，fromDisplacement 与 hook+move 两路直接死掉：
         * 「三路速度」实际只剩两路。降到 900ms（留 100ms 抖动余量）。
         */
        private const val MOVE_MIN_DT_MS = 900L

        /**
         * 连续多少帧不达标才清零窗口。原实现「一帧不达标就全清」：
         * GNSS 单帧掉速/丢点就把已经攒了 7 秒的 hold 归零，等于要求连续 8 帧全过（0.7^8 约 0.06）。
         * 容忍 3 帧（约 3 秒）抖动，第 4 帧仍不达标才重来；红绿灯停车（&gt;3s）仍会重来，符合预期。
         */
        private const val HOLD_GRACE_TICKS = 3

        /** 进入后复核：1.2s × 5，失败逐级换路重发（见 {@link #verify}）。 */
        private const val VERIFY_TRIES = 5
        private const val VERIFY_MS = 1200L

        private const val URI_CRUISE = "amapuri://edog/home"
        private const val CLS_SCHEME = "com.autonavi.map.activity.SchemeHandleActivity"

        private var app: Context? = null
        private var fgAttached = false
        private var hostLoader: ClassLoader? = null
        private var handler: Handler? = null

        @Volatile
        private var started = false

        private var speedOkSince = 0L
        private var lastEnterAt = 0L

        /** 上一次「立即测试」的时间，用于节流（隐藏键清不掉时的兜底）。 */
        private var lastForceAt = 0L
        private var quickBackCount = 0
        private var suppressUntil = 0L
        private var lastLoggedState = ""
        private var lastBeatAt = 0L
        @Volatile private var enterSeq = 0
        private val sequenceCounter = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var enterAutomatic = true
        private val pendingEnters = java.util.Collections.synchronizedList(java.util.ArrayList<Runnable>())

        // 进入判定的窗口状态：窗口起点、起点时的位移基准、上一帧速度、当前否决原因
        private var winBaseDist = 0f
        private var prevKmh = -1f

        @Volatile
        private var totalDistM = 0f

        @Volatile
        private var posJumpAt = 0L

        /**
         * 当前否决原因（心跳里的 why=）。取值：init / win / net / slow / fast / nospeed /
         * bg / in_cruise / in_nav / cooldown / suppress / enter / force。
         * 用户贴一行日志就能看出卡在哪一道。
         */
        @Volatile
        private var gateWhy = ""

        @Volatile
        private var lastAcc = -1f

        @Volatile
        private var distFixSeq = 0L
        private var winBaseFix = 0L
        private var winLat = 0.0
        private var winLng = 0.0

        /** 当前窗口里连续不达标的帧数：超过 {@link #HOLD_GRACE_TICKS} 才真正清零窗口。 */
        private var missTicks = 0

        /** 上一次心跳打过的 why，用于「状态一变立刻打一条」。 */
        private var lastBeatWhy = ""

        /** cruiseAuto 开关上一次的取值，用于只打一次的 on/off 变更日志。 */
        private var autoWasOn = false

        /**
         * 高德 IDriveNaviService 反射是否成功过。
         * 失败时 inCruise()/inNavigation() 恒返回 false —— 这时「复核失败」不代表真的没进去，
         * 只能记 cruise_enter_unverified，不能记 cruise_enter_fail（否则日志会误导排障）。
         */
        @Volatile
        private var driveProbeOk = false

        // 位移法用的上一个定位点
        @Volatile
        private var lastLat = 0.0

        @Volatile
        private var lastLng = 0.0

        @Volatile
        private var lastPosAt = 0L

        @Volatile
        private var moveKmh = 0f

        // 定位回调流：hook Location#setSpeed，AMap 每算出一个定位点就刷新一次
        @Volatile
        private var hookSpeed = 0f

        @Volatile
        private var hookKmh = false

        @Volatile
        private var hookAt = 0L

        @Volatile
        private var hookLoc: Any? = null

        @Volatile
        private var unitMethod: Method? = null

        /** 采样链路探针：哪一路拿到了速度，或失败在哪一步（心跳里打成 probe=）。 */
        @Volatile
        private var why = "init"

        @Volatile
        private var dbgKmh = -1f

        @Volatile
        private var dbgAt = 0L

        @JvmStatic
        fun install(cl: ClassLoader) {
            hostLoader = cl
            try {
                val h = Handler(Looper.getMainLooper())
                handler = h
                started = true
                // onPackageReady 早于 Application.onCreate：Context 要重试拿，不能一次失败就放弃
                h.postDelayed(BOOT, 300L)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_driver install_failed err=" + t)
            }
        }

        private val BOOT: Runnable = object : Runnable {
            private var tries = 0
            override fun run() {
                try {
                    if (app == null) app = currentApplication()
                    if (app != null) {
                        if (!fgAttached) {
                            fgAttached = true
                            val a = app
                            if (a is android.app.Application) HostFg.attach(a)
                        }
                        var diag = ""
                        try {
                            val p = Config.prefs()
                            diag = " prefs=" + (if (p == null) "null" else p.javaClass.simpleName) +
                                    " n=" + (if (p == null) -1 else p.all.size) +
                                    " cruise=" + (if (p == null) "-" else p.getBoolean(Config.K_CRUISE_AUTO, false)) +
                                    " force=" + (if (p == null) "-" else p.getBoolean(Config.K_CFG_FORCE_LOCAL, false))
                        } catch (ignored: Throwable) {
                        }
                        H.log(Log.INFO, MainHook.TAG, "event=cruise_driver installed enabled=" + Config.cruiseAuto() +
                                " tries=" + tries + diag)
                        installLocationHook()
                        handler!!.postDelayed(TICK, TICK_MS)
                        return
                    }
                } catch (ignored: Throwable) {
                }
                if (++tries < 40) {
                    handler!!.postDelayed(this, 500L)
                } else {
                    H.log(Log.WARN, MainHook.TAG, "event=cruise_driver skip=no_app tries=" + tries)
                }
            }
        }

        private val TICK: Runnable = object : Runnable {
            override fun run() {
                try {
                    tick()
                } catch (ignored: Throwable) {
                }
                try {
                    if (started) handler!!.postDelayed(this, TICK_MS)
                } catch (ignored: Throwable) {
                }
            }
        }

        @JvmStatic
        fun onConfigChanged(key: String?) {
            if (key != null && key != Config.K_CRUISE_FORCE_OFF && key != Config.K_CRUISE_AUTO) return
            // Invalidate delayed automatic retries before the main-thread reset can run.
            if (enterAutomatic) enterSeq = sequenceCounter.incrementAndGet()
            val h = handler
            if (h == null || android.os.Looper.myLooper() == h.looper) cancelAutomatic()
            else h.post { cancelAutomatic() }
        }

        private fun cancelPending() {
            val tasks = synchronized(pendingEnters) { pendingEnters.toList().also { pendingEnters.clear() } }
            for (task in tasks) handler?.removeCallbacks(task)
        }

        private fun cancelAutomatic() {
            if (enterAutomatic) {
                enterSeq = sequenceCounter.incrementAndGet()
                cancelPending()
            }
            winClear()
            autoWasOn = false
        }

        private fun tick() {
            // ① 「立即测试」隐藏键（面板按钮写入）：不受开关 / 冷却 / 门槛影响。
            //    先消费再执行 —— 后面就算抛异常，也不会变成「每个 tick 强进一次」的死循环。
            if (Config.cruiseForceOnce()) {
                forceOnce()
                return
            }

            if (Config.cruiseForceOff()) {
                cancelAutomatic()
                gateWhy = "forced_off"
                return
            }

            // ② 开关：默认 false，老用户行为不变。只在状态翻转时打一条，避免 1Hz 刷屏。
            if (!Config.cruiseAuto()) {
                if (autoWasOn) {
                    autoWasOn = false
                    H.log(Log.INFO, MainHook.TAG, "event=cruise_auto state=off")
                }
                winClear()
                gateWhy = "off"
                return
            }
            if (!autoWasOn) {
                autoWasOn = true
                H.log(Log.INFO, MainHook.TAG, "event=cruise_auto state=on min_kmh=" + Math.round(minKmh()) +
                        " need_sec=" + (driveMs() / 1000L) +
                        " need_net=" + Math.round(netNeedM()) +
                        " acc_max=" + Math.round(accMaxM()))
            }

            val now = SystemClock.elapsedRealtime()
            val fg = HostFg.fg()
            if (!fg) {
                // 后台不判定，也不采样（后台强行采样只会让定位更耗电，且进不去巡航反而更糟）
                winClear()
                gateWhy = "bg"
                beat(false, false, false, -1f, "none")
                return
            }
            val cruise = inCruise()
            val nav = inNavigation()
            if (cruise || nav) {
                // 用户自己退出巡航后 5s 内又被判到 → 记一次「快速返回」
                if (lastEnterAt > 0) {
                    val dt = now - lastEnterAt
                    if (dt > 800 && dt <= QUICK_BACK_MS && !cruise) {
                        quickBackCount++
                        lastEnterAt = 0
                        H.log(Log.INFO, MainHook.TAG, "event=cruise_suppress count=" + quickBackCount)
                        if (quickBackCount >= QUICK_BACK_MAX) suppressUntil = now + SUPPRESS_MS
                    }
                }
                winClear()
                gateWhy = if (cruise) "in_cruise" else "in_nav"
                beat(true, cruise, nav, -1f, "in")
                return
            }

            // ③ 采样放在「冷却 / 抑制」**之前**：这两个窗口里照样要采。
            //    老实现一进冷却就 return，位移链断 20~60 秒，冷却结束时 moveKmh 早已过期，
            //    「位移法」两路等于每次冷却完都要从零开始攒。
            val s = sample()
            val kmh = if (s == null) -1f else s.kmh
            val src = if (s == null) "none" else s.src

            if (suppressUntil > 0 && now < suppressUntil) {
                winClear()
                gateWhy = "suppress"
                beat(true, false, false, kmh, src)
                return
            }
            if (suppressUntil > 0) {
                suppressUntil = 0
                quickBackCount = 0
            }

            // 进入冷却：失败/退出后 {@link #ENTER_COOLDOWN_MS} 内不再自己进去
            if (lastEnterAt > 0 && now - lastEnterAt < ENTER_COOLDOWN_MS) {
                winClear()
                gateWhy = "cooldown"
                beat(true, false, false, kmh, src)
                return
            }

            if (advanceWindow(now, s)) enterCruise(s!!.kmh, s.src)
            beat(true, false, false, kmh, src)
        }

        /** Deterministic window update; waiting for a slower location feed must not erase valid hold time. */
        private fun advanceWindow(now: Long, s: Sample?): Boolean {
            if (s == null || !s.kmh.isFinite() || s.kmh < 0f) {
                miss(now, "nospeed")
                return false
            }
            val bad = gate(s)
            if (bad != null) {
                miss(now, bad)
                return false
            }
            prevKmh = s.kmh
            missTicks = 0

            if (speedOkSince == 0L) {
                // 第一帧达标：开窗，位移基准钉在此刻
                speedOkSince = now
                winBaseDist = totalDistM
                winBaseFix = distFixSeq
                winLat = lastLat
                winLng = lastLng
                gateWhy = "win"
                logSpeed(s.kmh, "acc")
                return false
            }
            // 迟到锚点：开窗时还没有任何定位点，第一个点到了再从此刻起量净位移
            // （否则 netM() 永远返回「拿不到」，净位移这关形同不存在）
            if (winLat == 0.0 && winLng == 0.0 && (lastLat != 0.0 || lastLng != 0.0)) {
                winLat = lastLat
                winLng = lastLng
            }

            // ── 主判据 1 成立：hold 够长 ────────────────────────────────────────────────
            val holdMs = now - speedOkSince
            val needMs = driveMsFor(s.src)
            val net = netM()
            val netOk = netOk(net, netNeedFor(s.src))
            val bypass = "debug" == s.src     // 排障文件喂的假速度：桌面验证链路用，放行
            if (bypass || (holdMs >= needMs && netOk)) {
                speedOkSince = 0
                missTicks = 0
                gateWhy = "enter"
                return true
            } else if (holdMs >= needMs) {
                // 时间达标后继续等可信位移，避免低频定位每次都刚好错过窗口。
                gateWhy = "net"
                // Keep the same displacement baseline until a fresh point arrives.
                // Slow/no-speed grace, background, navigation and cooldown still clear it.
            } else {
                gateWhy = "win"
            }
            return false
        }

        /**
         * 面板「立即测试」：跳过全部门槛直接尝试进入一次，并立刻把隐藏键清回 false。
         * 用途是把「链路通不通（scheme / 类名）」和「判定过不过（门槛 / 有没有速度）」分开 ——
         * 用户坐在家里点一下就能验证，不用真开一趟车。
         */
        private fun forceOnce() {
            val now0 = SystemClock.elapsedRealtime()
            // 隐藏键万一清不掉（后端只读 / commit 失败），也不能变成每个 tick 都发一次 intent。
            // 10 秒一次上限，足够让用户点一次看到结果，又不会把高德刷崩。
            if (lastForceAt != 0L && now0 - lastForceAt < 10000L) return
            lastForceAt = now0
            val cleared = Config.clearCruiseForceOnce()
            try {
                val now = SystemClock.elapsedRealtime()
                val fg = HostFg.fg()
                val cruise = inCruise()
                val nav = inNavigation()
                val s = sample()
                val kmh = if (s == null) -1f else s.kmh
                val src = if (s == null) "none" else s.src
                H.log(Log.INFO, MainHook.TAG, "event=cruise_force_once cleared=" + cleared +
                        " fg=" + fg + " cruise=" + cruise + " nav=" + nav +
                        " kmh=" + Math.round(kmh) + " src=" + src +
                        " auto=" + Config.cruiseAuto())
                winClear()
                if (!fg || cruise || nav) {
                    gateWhy = if (!fg) "bg" else if (nav) "in_nav" else "in_cruise"
                    beat(fg, cruise, nav, kmh, src)
                    return
                }
                gateWhy = "force"
                enterCruise(kmh, "force")
                beat(fg, cruise, nav, kmh, src)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_force_once failed err=" + t)
            }
        }

        /** 清空判定窗口。**不动**采样链路（moveKmh / lastPosAt / totalDistM），那些要继续热着。 */
        private fun winClear() {
            speedOkSince = 0
            missTicks = 0
            prevKmh = -1f
            winBaseDist = totalDistM
            winBaseFix = distFixSeq
            winLat = 0.0
            winLng = 0.0
        }

        /**
         * 一帧不达标。老实现是「任何一帧失败就把 hold 和位移基线全部归零」：
         * 8 秒窗口等于要求连续 8 帧全过，单帧 0.7 的话 0.7^8 约 0.06。
         * 现在容忍 {@link #HOLD_GRACE_TICKS} 帧，第 4 帧才真正清窗 ——
         * 单帧掉速/丢点不再打断已经攒起来的 hold，红绿灯停车（&gt;3s）仍然会重来。
         */
        private fun miss(now: Long, reason: String) {
            gateWhy = reason
            if (speedOkSince == 0L) {
                prevKmh = -1f
                return
            }
            missTicks++
            if (missTicks > HOLD_GRACE_TICKS) {
                logSpeed(if (prevKmh < 0f) 0f else prevKmh, "reset")
                winClear()
            }
        }

        /**
         * 主判据 2：净位移够不够。三种状态，只有「明确测到没动」才否决：
         * <ul>
         *   <li>net ≥ need ⇒ 通过；</li>
         *   <li>net ≥ 0 但不够 ⇒ 再看第二证据「路径累计步数与距离」（{@link #MIN_WIN_FIXES}）；</li>
         *   <li>net &lt; 0（没有起点 / 当前点精度离谱）⇒ <b>降级放行</b>（心跳里 net=-1）。</li>
         * </ul>
         */
        private fun netOk(net: Float, need: Float): Boolean {
            try {
                val dist = totalDistM - winBaseDist
                val fixes = distFixSeq - winBaseFix
                // 明确测到了净位移：够 → 过；不够 → 再看第二证据（路面弯曲时直线距离会明显小于路程）
                if (net >= 0f) {
                    if (net >= need) return true
                    return fixes >= MIN_WIN_FIXES && dist >= distNeedM()
                }
                // net < 0 = 地理数据根本拿不到（没有起点 / 当前点精度离谱）。
                // 这一条**降级跳过**，不否决 —— 否则「没定位权限的手机」就永远别想触发。
                return true
            } catch (t: Throwable) {
                return true
            }
        }

        // ── 速度采样 ────────────────────────────────────────────────────────────────

        private class Sample(val kmh: Float, val src: String,
                             // 定位精度（米），-1 = 不知道
                             val acc: Float) {
            constructor(kmh: Float, src: String) : this(kmh, src, -1f)
        }

        /**
         * 四级兜底：高德最新点 → 定位回调流 → 系统最后已知点 → 位移换算。
         *
         * <p><b>任何一路可用即可</b>（原实现虽然写了「任一路拿到有效速度即可」，但 gate 里把
         * {@code move} / {@code hook+move} 两路直接拉黑，实际只剩两路；而这两路恰好最依赖
         * 反射类名和大范围权限，真机上常年拿不到 —— 于是 sample() 恒为 null，why=nospeed 挂到天荒地老）。
         *
         * <p>这里额外做一件事：某一路给出的速度低于门槛、但<b>位移法证明车在动</b>时改用位移速度。
         * 隧道、地库出口、冷启动时 GNSS 速度常年是 0，而经纬度一直在动。
         */
        private fun sample(): Sample? {
            try {
                val t = fromDebugFile()
                if (t != null) {
                    why = "debug"
                    return t
                }
                val probe = StringBuilder()
                val a = fromAmap()
                if (a != null) {
                    why = "amap"
                    return preferMoving(a)
                }
                probe.append("amap:").append(why).append(' ')
                val b = fromHook()
                if (b != null) {
                    why = "hook"
                    return preferMoving(b)
                }
                probe.append("hook:").append(if (hookAt == 0L) "never" else "stale").append(' ')
                val c = fromSystem()
                if (c != null) {
                    why = "sys"
                    return preferMoving(c)
                }
                probe.append("sys:none ")
                val d = fromDisplacement()
                if (d != null) {
                    why = "move"
                    return d
                }
                // 四路全空：把每一路各自卡在哪拼出来，拼成 probe=。
                // 这一行是「一直触发不了」时最有价值的信息：
                //   amap_err:ClassNotFoundException ⇒ 类名漂移；amap:svc_null ⇒ getLocationService 拿不到；
                //   hook:never ⇒ Location#setSpeed 从没被调过；hook:stale ⇒ 调过但超过 10s；
                //   sys:none ⇒ 没定位权限 / 没有 provider；move:nofix ⇒ 连一个定位点都拿不到。
                probe.append("move:").append(if (lastPosAt == 0L) "nofix" else "slow")
                why = probe.toString().trim()
                return null
            } catch (e: Throwable) {
                why = "sample_err:" + e.javaClass.simpleName
                return null
            }
        }

        /**
         * 「某一路给出了速度」但偏低时，用位移速度兜底。
         * 只在位移法自己认为在动（moveKmh ≥ 门槛且定位点新鲜）时才替换，否则原样返回。
         */
        private fun preferMoving(s: Sample?): Sample? {
            try {
                if (s == null || s.kmh >= minKmh()) return s
                if (moveKmh < minKmh()) return s
                if (lastPosAt <= 0) return s
                if (SystemClock.elapsedRealtime() - lastPosAt > SPEED_STALE_MS) return s
                return Sample(Math.min(moveKmh, MAX_KMH), "move", s.acc)
            } catch (t: Throwable) {
                return s
            }
        }

        /**
         * 排障用：/data/local/tmp/mapclean_cruise_kmh 里写一个数字（需要 root 或 adb 才能写），
         * 就按这个速度当作实时速度。用来在静止的桌面上验证「累计→进入巡航→校验」整条链路，
         * 平时该文件不存在，对本功能没有任何影响。
         */
        private fun fromDebugFile(): Sample? {
            if (!Config.debugLog()) return null
            try {
                val f = java.io.File("/data/local/tmp/mapclean_cruise_kmh")
                val now = SystemClock.elapsedRealtime()
                if (now - dbgAt < 3000L) return if (dbgKmh >= 0f) Sample(dbgKmh, "debug") else null
                dbgAt = now
                if (!f.exists()) {
                    dbgKmh = -1f
                    return null
                }
                val buf = ByteArray(32)
                val n: Int
                val ins = java.io.FileInputStream(f)
                try {
                    n = ins.read(buf)
                } finally {
                    ins.close()
                }
                if (n <= 0) {
                    dbgKmh = -1f
                    return null
                }
                dbgKmh = java.lang.Float.parseFloat(String(buf, 0, n).trim())
                return if (dbgKmh >= 0f) Sample(dbgKmh, "debug") else null
            } catch (t: Throwable) {
                dbgKmh = -1f
                return null
            }
        }

        /** ② hook 流：Location#setSpeed 每次被调用都刷新，10s 内算实时。 */
        private fun fromHook(): Sample? {
            try {
                if (hookAt == 0L) return null
                if (SystemClock.elapsedRealtime() - hookAt > 10000L) return null
                val kmh = if (unitKmh(hookLoc)) hookSpeed else hookSpeed * 3.6f
                if (kmh < 0f) return null
                if (kmh < 1f && moveKmh >= minKmh() &&
                        SystemClock.elapsedRealtime() - lastPosAt <= SPEED_STALE_MS) {
                    // GNSS 速度给 0（隧道 / 冷启动），但经纬度在动：这一路直接用位移速度。
                    // 原来标成 "hook+move" 然后被 gate 白名单挡掉，等于白算 —— 现在它就是一条正常来源。
                    return Sample(moveKmh, "move", accuracyOf(hookLoc))
                }
                return Sample(kmh, "hook", accuracyOf(hookLoc))
            } catch (t: Throwable) {
                return null
            }
        }

        /** 单位以定位点自己的 isSpeedKmh 标记为准（逐帧读，避免串帧把 km/h 又乘 3.6）。 */
        private fun unitKmh(loc: Any?): Boolean {
            if (loc != null) {
                try {
                    var m = unitMethod
                    if (m == null) {
                        m = loc.javaClass.getMethod("isSpeedKmh")
                        m.isAccessible = true
                        unitMethod = m
                    }
                    val r = m!!.invoke(loc)
                    if (r is Boolean) return r
                } catch (ignored: Throwable) {
                }
            }
            return hookKmh
        }

        /** 装定位回调 hook：AMap 自己每生成一个定位点都会 setSpeed，等于免费拿到推送。 */
        private fun installLocationHook() {
            try {
                val lc = H.cls(hostLoader, "com.amap.location.type.location.Location")
                var n = 0
                n += H.hookAll(lc, "setSpeed", "amap_cruise_speed", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try {
                            val v = chain.getArg(0)
                            if (v is Float) {
                                hookSpeed = v
                                hookAt = SystemClock.elapsedRealtime()
                                hookLoc = chain.getThisObject()
                                // 同一个定位点顺手喂给位移法：GNSS 速度可能为 0（隧道/冷启动），
                                // 但经纬度一直在动，位移法就能补上。
                                rememberPosition(chain.getThisObject())
                            }
                        } catch (ignored: Throwable) {
                        }
                        return result
                    }
                })
                n += H.hookAll(lc, "setSpeedKmh", "amap_cruise_speed_kmh", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val v = chain.getArg(0)
                            if (v is Boolean) hookKmh = v
                        } catch (ignored: Throwable) {
                        }
                        return chain.proceed()
                    }
                })
                H.log(Log.INFO, MainHook.TAG, "event=cruise_hook location=" + n)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_hook failed err=" + t)
            }
        }

        /** ① 高德自己的定位服务（精度最好，导航/巡航时就是它）。 */
        private fun fromAmap(): Sample? {
            try {
                val ai = hostLoader!!.loadClass("com.amap.AppInterfaces")
                val svc = ai.getDeclaredMethod("getLocationService").invoke(null)
                if (svc == null) {
                    why = "svc_null"
                    return null
                }
                val ils = hostLoader!!.loadClass("com.amap.location.api.ILocationService")
                val loc = ils.getMethod("getLatestLocation").invoke(svc)
                if (loc == null) {
                    why = "loc_null"
                    return null
                }
                rememberPosition(loc)
                val s = speedOf(loc, "amap")
                if (s == null) why = "stale"
                return s
            } catch (t: Throwable) {
                why = "amap_err:" + t.javaClass.simpleName
                return null
            }
        }

        /** ② 系统 LocationManager 的最后已知点：高德没在跑定位时也能拿到（20s 内有效）。 */
        private fun fromSystem(): Sample? {
            try {
                val a = app ?: return null
                val lm = a.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
                val now = System.currentTimeMillis()
                val probes = arrayOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        "fused"
                )
                for (p in probes) {
                    var l: Location? = null
                    try {
                        l = lm.getLastKnownLocation(p)
                    } catch (ignored: Throwable) {
                    }
                    if (l == null) continue
                    val age = now - l.time
                    if (age < 0 || age > SPEED_STALE_MS) continue
                    rememberAndroid(l)
                    if (!l.hasSpeed()) continue
                    val mps = l.speed
                    val kmh = mps * 3.6f
                    if (kmh >= 0f) return Sample(kmh, "sys", l.accuracy)
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        /** ③ 位移法：两个定位点之间跑了多远／多久（连速度字段都没有时兜底）。 */
        private fun fromDisplacement(): Sample? {
            try {
                if (lastPosAt <= 0 || moveKmh < minKmh()) return null
                if (SystemClock.elapsedRealtime() - lastPosAt > SPEED_STALE_MS) return null
                return Sample(moveKmh, "move", -1f)
            } catch (t: Throwable) {
                return null
            }
        }

        private fun speedOf(loc: Any?, src: String): Sample? {
            try {
                val lc = hostLoader!!.loadClass("com.amap.location.type.location.Location")
                val speed = lc.getMethod("getSpeed").invoke(loc) as Float
                var isKmh = false
                try {
                    isKmh = lc.getMethod("isSpeedKmh").invoke(loc) as Boolean
                } catch (ignored: Throwable) {
                }
                var tickTime = 0L
                try {
                    tickTime = lc.getMethod("getLocationTickTime").invoke(loc) as Long
                } catch (ignored: Throwable) {
                }
                val now = System.currentTimeMillis()
                if (tickTime > 0) {
                    val age = Math.abs(now - tickTime)
                    val ageAlt = Math.abs(SystemClock.elapsedRealtime() - tickTime)
                    if (Math.min(age, ageAlt) > SPEED_STALE_MS) return null
                }
                val kmh = if (isKmh) speed else speed * 3.6f
                if (kmh < 0f) return null
                return Sample(kmh, src, accuracyOf(loc))
            } catch (t: Throwable) {
                return null
            }
        }

        /** 精度（米）；读不到给 -1，调用方按「不校验」处理，不能因为读不到就永远不触发。 */
        private fun accuracyOf(loc: Any?): Float {
            if (loc == null) return -1f
            try {
                val a = loc.javaClass.getMethod("getAccuracy").invoke(loc)
                if (a is Float) return a
                if (a is Double) return a.toFloat()
            } catch (ignored: Throwable) {
            }
            return -1f
        }


        private fun rememberPosition(loc: Any?) {
            try {
                val lc = hostLoader!!.loadClass("com.amap.location.type.location.Location")
                val lat = lc.getMethod("getLatitude").invoke(loc) as Double
                val lng = lc.getMethod("getLongitude").invoke(loc) as Double
                if (lat == 0.0 && lng == 0.0) return
                var acc = accuracyOf(loc)
                if (acc < 0f) acc = 30f          // 高德点一般带精度；读不到时按中等精度处理
                val before = lastPosAt
                val pLat = lastLat
                val pLng = lastLng
                addFix(lat, lng, acc, true)
                // 位移法继续沿用原来的口径（只在时间够长时更新 moveKmh）
                val dt = lastPosAt - before
                if (before > 0 && dt >= MOVE_MIN_DT_MS) {
                    val m = distanceMeters(pLat, pLng, lat, lng)
                    val kmh = (m / (dt / 1000f)) * 3.6f
                    if (kmh >= minKmh()) moveKmh = kmh else moveKmh = 0f
                }
            } catch (ignored: Throwable) {
            }
        }

        /** 单步位移计入窗口的速度下限：低速档跟着 minKmh 降，默认档封顶 8 km/h。 */
        private fun stepGateKmh(): Float {
            return Math.min(8f, Math.max(1.5f, minKmh() * 0.8f))
        }

        /**
         * 窗口内「路径」累计位移的下限 —— <b>只是第二证据</b>，不再是进入门槛。
         * 由「速度 × 时间」实算：minKmh/3.6 × driveSec × 0.6（留 40% 余量给路口和弯道）。
         * 默认 12km/h × 8s：3.33 × 8 × 0.6 ≈ 16 米（原写死 60 米，12km/h 下等于要求多跑 2.3 倍时间）。
         */
        private fun distNeedM(): Float {
            val v = (minKmh() / 3.6f) * (driveMs() / 1000f) * 0.6f
            return Math.min(MIN_DIST_M, Math.max(10f, v))
        }

        /**
         * 起点到当前点的净位移下限（主判据 2 的数）。
         * 由「速度 × 时间」实算而不是写死：minKmh/3.6 × driveSec × 0.35。
         * 0.35 这个系数是因为净位移是<b>直线</b>距离，路口转弯、并线会让它明显小于路程；
         * 默认 12km/h × 8s：3.33 × 8 × 0.35 ≈ 9.3 米 —— 只要车真的在往前开就一定过，
         * 而原地抖（GNSS 噪声半径 5~15 米）大多过不了，且配合「速度持续 8 秒达标」不会误触。
         * 原写死 45 米 = 12km/h 下 13.5 秒的直线距离，是「永远不触发」的第二大来源。
         */
        private fun netNeedM(): Float {
            val v = (minKmh() / 3.6f) * (driveMs() / 1000f) * 0.35f
            return Math.min(MIN_NET_M, Math.max(5f, v))
        }

        /** 允许的最大定位精度：低速档放宽，走路时 GNSS 精度经常 50 米开外。 */
        private fun accMaxM(): Float {
            return if (minKmh() <= 6) 120f else ACC_MAX_M
        }

        /**
         * 净位移「可信」的上限精度：比这还差的当前点（基站 / 多径跳点）算出来的净位移不可信，
         * 直接按「拿不到」处理（降级放行），不用它去否决 —— 也不让它把 net 吹大骗过判据。
         */
        private fun accMaxNetM(): Float {
            return accMaxM() * 2f
        }

        /**
         * 只有「位移法」这一路可用时的持续时间门槛：× 1.5。
         *
         * <p>为什么要加严：纯位移速度是「两点距离 / 时间」算出来的，静止时 GNSS 单帧抖 3 米
         * （城市里很常见）在一秒内就是 10.8km/h，抖 5 米就是 18km/h —— 足够骗过 12km/h 的门槛。
         * 所以位移来源单独要更长的持续时间 + 更大的净位移，见 {@link #netNeedFor}。
         * 默认档：8s → 12s。
         */
        private fun driveMsFor(src: String): Long {
            return if ("move" == src) (driveMs() * 3L / 2L) else driveMs()
        }

        /**
         * 只有「位移法」这一路可用时的净位移门槛：× 2（默认档 9.3m → 18.6m）。
         * 12 秒真跑 12km/h 应该走 40 米，净位移 18.6 米是很宽松的下限；
         * 而原地抖动要连续 12 秒每帧都抖出 3 米以上且净位移凑够 18.6 米，概率低得多。
         */
        private fun netNeedFor(src: String): Float {
            return if ("move" == src) netNeedM() * 2f else netNeedM()
        }

        /**
         * 定位点记账：只有「精度可信 + 时间合理 + 单步速度自洽 + 不是原地抖动」的位移才累计<b>路径</b>。
         *
         * <p>注意语义变了：这里累计的 {@code totalDistM} / {@code distFixSeq} 现在只用于
         * (a) 心跳里的 dist/fix 诊断，(b) 净位移拿不到时的第二证据。它<b>不再否决</b>任何一帧 ——
         * 老实现里「精度 &gt;45m 就整帧作废」「一步超 90m 就作废整个窗口」才是触发不了的主力。
         */
        private fun addFix(lat: Double, lng: Double, acc: Float, trusted: Boolean) {
            addFixAt(lat, lng, acc, trusted, SystemClock.elapsedRealtime())
        }

        private fun addFixAt(lat: Double, lng: Double, acc: Float, trusted: Boolean, now: Long) {
            if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0 ||
                (lat == 0.0 && lng == 0.0)) return
            // getLatestLocation() can return the same cached point on every 1s tick.
            // It must not restart the displacement clock between two actual 2–5s updates.
            if (lastPosAt > 0 && lat == lastLat && lng == lastLng) return
            if (!trusted || acc < 0f || acc > accMaxM()) {
                // 精度不可信：只更新锚点（净位移要用），不累计路径
                lastLat = lat
                lastLng = lng
                lastPosAt = now
                return
            }
            if (lastPosAt > 0) {
                val dt = now - lastPosAt
                val m = distanceMeters(lastLat, lastLng, lat, lng)
                val stepKmh = if (dt > 0) (m / (dt / 1000f)) * 3.6f else 0f
                // dup 阈值原来是 max(1.0, min(4.0, acc*0.15))：acc=80 时等于 4 米，
                // 而 12km/h 一秒只走 3.33 米 —— 低速 + 差精度下**每一步都被判成原地抖动**，
                // 路径永远累计不起来（0.33 的乘数就是这么丢掉的）。改成 acc*0.10 且封顶 3 米。
                val dupM = Math.max(1.0f, Math.min(3.0f, acc * 0.10f))
                val dup = m < dupM                            // 同一点重复上报 / 原地抖动
                val gap = dt < 800L || dt > 10000L             // 太密或隔太久，速度不可信
                val jump = m > STEP_MAX_M || stepKmh > 220f    // 多径跳点：只作废这一帧
                if (jump) {
                    posJumpAt = now                                // 只留个时间戳给日志看
                } else if (!dup && !gap && stepKmh >= stepGateKmh()) {
                    totalDistM += m
                    distFixSeq++
                }
            }
            lastLat = lat
            lastLng = lng
            lastPosAt = now
        }

        private fun rememberAndroid(l: Location) {
            try {
                val before = lastPosAt
                var hasAcc = true
                var acc = -1f
                try {
                    acc = l.accuracy
                    hasAcc = l.hasAccuracy()
                } catch (ignored: Throwable) {
                }
                val pLat = lastLat
                val pLng = lastLng
                addFix(l.latitude, l.longitude, if (hasAcc) acc else -1f, hasAcc)
                val dt = lastPosAt - before
                if (before > 0 && dt >= MOVE_MIN_DT_MS) {
                    val m = distanceMeters(pLat, pLng, l.latitude, l.longitude)
                    val kmh = (m / (dt / 1000f)) * 3.6f
                    if (kmh >= minKmh()) moveKmh = kmh else moveKmh = 0f
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLng / 2) * Math.sin(dLng / 2)
            return (2 * r * Math.asin(Math.sqrt(a))).toFloat()
        }

        // ── 进入 + 复核 ────────────────────────────────────────────────────────────

        private fun enterCruise(kmh: Float, src: String) {
            val automatic = src != "force"
            if (automatic && !Config.cruiseAuto()) { cancelAutomatic(); return }
            cancelPending()
            val seq = sequenceCounter.incrementAndGet()
            enterSeq = seq
            enterAutomatic = automatic
            lastEnterAt = SystemClock.elapsedRealtime()
            H.log(Log.INFO, MainHook.TAG, "event=cruise_enter reason=" +
                    (if ("force" == src) "force" else "auto") +
                    " speed=" + Math.round(kmh) + " src=" + src + " seq=" + seq +
                    " hold=" + (driveMsFor(src) / 1000L) + "s")
            // 路 1：显式组件直达 SchemeHandleActivity（最强，但类名一漂就 ActivityNotFound）
            fire(1)
            // 1.2s×5 复核：没进去就逐级换路重发（见 verify）
            for (i in 1..VERIFY_TRIES) {
                val attempt = i
                val task = object : Runnable {
                    override fun run() {
                        pendingEnters.remove(this)
                        verify(seq, attempt)
                    }
                }
                pendingEnters.add(task)
                handler!!.postDelayed(task, VERIFY_MS * i)
            }
        }

        /**
         * 复核 + 自愈。老实现只在第 1 次失败时用<b>同一条</b>路重发一次，
         * 如果那条路本身不通（类名漂移、组件被禁），重发一万次也没用。
         *
         * <p>现在：1 次失败 → 换路 2（只发 scheme 让高德自己的 manifest 过滤器解析）；
         * 3 次失败 → 两路各再补一发；5 次仍不认 → 记 fail（或 unverified，见 {@link #driveProbeOk}）。
         */
        private fun verify(seq: Int, attempt: Int) {
            if (seq != enterSeq) return          // 已经被新一轮取代
            if (enterAutomatic && !Config.cruiseAuto()) { cancelAutomatic(); return }
            if (!HostFg.fg()) {
                enterSeq = 0
                H.logv("event=cruise_enter_cancel reason=page_changed")
                return
            }
            var ok = false
            try {
                ok = inCruise()
            } catch (ignored: Throwable) {
            }
            if (ok) {
                H.log(Log.INFO, MainHook.TAG, "event=cruise_enter_ok seq=" + seq + " after=" + attempt)
                enterSeq = 0
                return
            }
            // 高德的 isInNavigation() 也包含巡航/路况雷达，必须先认定巡航成功。
            // 真正切入路线导航时才取消重试，避免把正在导航的页面拉回巡航。
            if (inNavigation()) {
                enterSeq = 0
                H.logv("event=cruise_enter_cancel reason=in_navigation")
                return
            }
            if (!driveProbeOk && attempt >= 2) {
                // 拿不到 isInCruise()：不知道到底进没进，不能冤成 fail，否则用户按这个日志去查错方向
                H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_unverified seq=" + seq +
                        " reason=no_drive_service attempt=" + attempt)
                enterSeq = 0
                return
            }
            if (attempt == 1) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_retry seq=" + seq + " road=2")
                fire(2)
            } else if (attempt == 3) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_retry seq=" + seq + " road=1")
                fire(1)
            } else if (attempt == VERIFY_TRIES) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_fail seq=" + seq)
            }
        }

        /**
         * 两条进入路径，**都留着**：
         * <ul>
         *   <li>road 1 = 显式组件 {@code SchemeHandleActivity}。最直接，但高德一改类名/组件就哑。</li>
         *   <li>road 2 = 只发 {@code amapuri://edog/home} 交给高德自己的 scheme 过滤器解析
         *       （manifest 里 amapuri:// 全挂在 SchemeHandleActivity 上）。类名漂移也能进。</li>
         * </ul>
         * 任何一条抛异常都只记日志，绝不冒到宿主。
         */
        private fun fire(road: Int) {
            if (enterAutomatic && !Config.cruiseAuto()) return
            try {
                if (app == null) {
                    app = currentApplication()
                }
                val a = app
                if (a == null) {
                    H.log(Log.WARN, MainHook.TAG, "event=cruise_enter failed road=" + road + " err=no_context")
                    return
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_CRUISE))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (road == 1) {
                    intent.setClassName(a.packageName, CLS_SCHEME)
                } else {
                    // 路 2：指定包名但不定组件 —— 交给高德自己解析 scheme
                    intent.setPackage(MainHook.PKG_AMAP)
                }
                a.startActivity(intent)
                H.log(Log.INFO, MainHook.TAG, "event=cruise_fire road=" + road + " uri=" + URI_CRUISE)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_enter failed road=" + road + " err=" + t)
            }
        }

        // ── 状态与日志 ──────────────────────────────────────────────────────────────

        @JvmStatic
        fun isForeground(): Boolean {
            try {
                val a = app!!
                val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val list = am.runningAppProcesses
                if (list == null || list.isEmpty()) return true   // 拿不到列表时不当作「后台」，否则永远不触发
                val pkg = a.packageName
                for (p in list) {
                    if (pkg == p.processName) {
                        return p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    }
                }
                return true
            } catch (ignored: Throwable) {
            }
            return true
        }

        /** 调高德自己的 IDriveNaviService 判定，拿不到时退化为 false（宁可多进一次也不误锁）。 */
        // edog/home 在新版高德进入的是 DriveRadar；旧式巡航和路况雷达均视为成功。
        private fun inCruise(): Boolean = callDriveBool("isInCruise") || callDriveBool("isInDriveRadar")

        private fun inNavigation(): Boolean = callDriveBool("isInNavigation")

        private fun callDriveBool(method: String): Boolean {
            try {
                val loader = hostLoader!!.loadClass("com.autonavi.inter.impl.BundleInterfaceLoaderImpl")
                val impl = loader.getDeclaredConstructor().newInstance()
                val api = hostLoader!!.loadClass("com.amap.bundle.drive.api.IDriveNaviService")
                val svc = loader.getMethod("getBundle", Class::class.java).invoke(impl, api)
                if (svc == null) return false
                val r = api.getMethod(method).invoke(svc)
                // 只要这里拿到过 Boolean，就说明高德的服务反射是可用的。
                // verify() 靠它区分「真的没进去（fail）」和「根本没有判定能力（unverified）」。
                if (r is Boolean) {
                    driveProbeOk = true
                    return r
                }
                return false
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * 净位移（米）：窗口起点 → 当前点。
         * 返回 <b>-1 = 拿不到</b>（没有起点 / 当前点精度离谱），调用方按「不校验」处理 ——
         * 老实现「拿不到就返回 0」，等于「没有地理数据 ⇒ 永远过不了净位移这关 ⇒ 永远不触发」。
         */
        private fun netM(): Float {
            try {
                if (winLat == 0.0 && winLng == 0.0) return -1f
                if (lastLat == 0.0 && lastLng == 0.0) return -1f
                if (lastAcc > accMaxNetM()) return -1f   // 当前点太离谱，净位移不可信
                return distanceMeters(winLat, winLng, lastLat, lastLng)
            } catch (t: Throwable) {
                return -1f
            }
        }

        /**
         * 单帧闸门。返回 null = 这一帧算数；否则返回原因（心跳里的 why=）。
         *
         * <p><b>只保留两条真正该否决的</b>：
         * <ul>
         *   <li>{@code slow}：速度不到门槛（主判据 1 的逐帧形态，由 {@link #miss} 累计宽限）；</li>
         *   <li>{@code fast}：&gt; {@link #MAX_KMH}，只可能是脏数据。</li>
         * </ul>
         * 其余全部降级：
         * <ul>
         *   <li>精度差 → 只影响「路径累计不计」，速度照用（AMap/GNSS 的速度字段跟精度无关）；</li>
         *   <li>跳点 / 突变 → 只记日志（{@link #posJumpAt} 给诊断看），不再清窗口；</li>
         *   <li>来源是位移法 → 照样算数（老实现直接 {@code src=move} 拉黑，等于废掉一整条路）。</li>
         * </ul>
         */
        private fun gate(s: Sample?): String? {
            if (s == null) return "nospeed"
            lastAcc = s.acc
            if (!s.kmh.isFinite() || s.kmh < 0f) return "nospeed"
            if (s.kmh < minKmh()) return "slow"
            if (s.kmh > MAX_KMH) return "fast"
            return null
        }

        private fun logSpeed(kmh: Float, state: String) {
            if (lastLoggedState == state && "reset" == state) return
            lastLoggedState = state
            H.log(Log.INFO, MainHook.TAG, "event=cruise_probe speed=" + Math.round(kmh) + " state=" + state)
        }

        /**
         * 一行状态汇总，给「主动抓包」的 10 分钟 txt 用（每 5 秒记一条）。
         * 所有值都是现成字段，取不到就给默认值，绝不抛。字段与 {@link #beat} 对齐，便于对照。
         */
        @JvmStatic
        fun diag(): String {
            try {
                val now = SystemClock.elapsedRealtime()
                val hold = if (speedOkSince == 0L) 0L else now - speedOkSince
                val fg = HostFg.fg()
                val cruise = fg && inCruise()
                val nav = fg && inNavigation()
                var kmh = -1f
                if (hookSpeed >= 0f && hookAt > 0 && now - hookAt <= SPEED_STALE_MS) {
                    kmh = if (hookKmh) hookSpeed else hookSpeed * 3.6f
                }
                val sup = if (suppressUntil > now) (suppressUntil - now) / 1000L else 0L
                return "fg=" + fg + " cruise=" + cruise + " nav=" + nav +
                        " kmh=" + Math.round(kmh) + " src=" + why +
                        " why=" + (if (gateWhy.isEmpty()) "ok" else gateWhy) +
                        " hold=" + (hold / 1000L) + " need=" + (driveMs() / 1000L) +
                        " move=" + Math.round(moveKmh) +
                        " dist=" + Math.round(totalDistM - winBaseDist) +
                        " net=" + Math.round(netM()) + " need_net=" + Math.round(netNeedM()) +
                        " fix=" + (distFixSeq - winBaseFix) + " acc=" + Math.round(lastAcc) +
                        " grace=" + missTicks +
                        " hook_age=" + (if (hookAt == 0L) -1 else now - hookAt) +
                        " suppress=" + sup + " qb=" + quickBackCount
            } catch (t: Throwable) {
                return "diag_err=" + t.javaClass.simpleName
            }
        }

        /**
         * 心跳：{@code event=cruise_tick}。这是用户唯一能贴出来的证据，所以每一项都必须能定位卡点：
         * <pre>
         * why=       当前否决原因（slow=速度不够 / net=净位移不够 / nospeed=一路速度都没拿到 /
         *            bg=不在前台 / in_cruise=已经在巡航里 / cooldown / suppress / enter / win=正在攒）
         * kmh= src=  这一帧拿到的速度和来源（amap / hook / sys / move / debug / none）
         * hold= need= 已持续秒数 / 需要秒数
         * net= need_net= 净位移 / 需要多少米（-1 = 拿不到，已降级放行）
         * grace=     已经连续几帧不达标（超过 HOLD_GRACE_TICKS 才清窗）
         * probe=     采样链路卡在哪（none / amap_err:ClassNotFound / svc_null / loc_null / stale ...）
         * </pre>
         * 状态（why）一变就立刻打一条，不等满 {@link #HEARTBEAT_MS} —— 否则用户复现完只看到一片寂静。
         */
        private fun beat(fg: Boolean, cruise: Boolean, nav: Boolean, kmh: Float, src: String) {
            val now = SystemClock.elapsedRealtime()
            val w = if (gateWhy.isEmpty()) "ok" else gateWhy
            val changed = w != lastBeatWhy
            // 状态变了立刻打，但最快 2 秒一条：车速在门槛上下浮动时 why 会在 win/slow 之间反复，
            // 不加下限会退化成 1Hz 刷屏，反而把有用的行冲掉。
            val minGap = if (changed) 2000L else HEARTBEAT_MS
            if (lastBeatAt != 0L && now - lastBeatAt < minGap) return
            lastBeatAt = now
            lastBeatWhy = w
            val hold = if (speedOkSince == 0L) 0L else now - speedOkSince
            val net = netM()
            val spike = prevKmh > 0f && Math.abs(kmh - prevKmh) > SPIKE_KMH
            val jumpAge = if (posJumpAt == 0L) -1 else now - posJumpAt
            H.log(Log.INFO, MainHook.TAG, "event=cruise_tick" +
                    " fg=" + fg + " cruise=" + cruise + " nav=" + nav +
                    " kmh=" + Math.round(kmh) + " src=" + src +
                    " why=" + w +
                    " hold=" + (hold / 1000L) + " need=" + (driveMsFor(src) / 1000L) +
                    " net=" + Math.round(net) + " need_net=" + Math.round(netNeedFor(src)) +
                    " move=" + Math.round(moveKmh) +
                    " dist=" + Math.round(totalDistM - winBaseDist) +
                    " fix=" + (distFixSeq - winBaseFix) +
                    " acc=" + Math.round(lastAcc) +
                    " grace=" + missTicks + "/" + HOLD_GRACE_TICKS +
                    " spike=" + spike + " jump_age=" + jumpAge +
                    " cd=" + (if (lastEnterAt == 0L) -1 else (now - lastEnterAt) / 1000L) +
                    " qb=" + quickBackCount +
                    " probe=" + why)
        }

        private fun currentApplication(): Context? {
            // 复用 Config 的四级 Context 兜底（ActivityThread.currentApplication → AppGlobals →
            // mInitialApplication → 内置版 App.ctx），比单条反射稳。
            try {
                val c = Config.hostCtx()
                if (c != null) return c
            } catch (ignored: Throwable) {
            }
            try {
                val at = Class.forName("android.app.ActivityThread")
                val cur = at.getDeclaredMethod("currentApplication")
                cur.isAccessible = true
                val o = cur.invoke(null)
                return if (o is Context) o else null
            } catch (t: Throwable) {
                return null
            }
        }

        // ── 诊断探针（只在 debug_log 打开时安装，用于复核"设置页开关到底写不写"）──────────
        @JvmStatic
        fun installProbe(cl: ClassLoader) {
            if (!Config.debugLog()) return
            try {
                val c = H.cls(cl, "com.amap.bundle.mapstorage.MapSharePreference")
                val hooker: XposedInterface.Hooker = object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            H.log(Log.INFO, MainHook.TAG, "event=cruise_probe msp." +
                                    chain.getExecutable().name + " key=" + chain.getArg(0) +
                                    " val=" + chain.getArg(1))
                        } catch (ignored: Throwable) {
                        }
                        return chain.proceed()
                    }
                }
                H.hookAll(c, "putBooleanValue", "amap_cruise_msp_b", hooker)
                H.hookAll(c, "putIntValue", "amap_cruise_msp_i", hooker)
            } catch (ignored: Throwable) {
            }
        }
    }
}

