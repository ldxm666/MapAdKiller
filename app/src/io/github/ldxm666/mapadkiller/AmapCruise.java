package io.github.ldxm666.mapadkiller;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

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
final class AmapCruise {
    private AmapCruise() {}

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
    private static final float MAX_KMH = 200f;
    /** 触发速度下限（km/h）：面板「自动巡航 → 触发速度下限」，默认 12（原 18，见 Config）。 */
    private static float minKmh() { return Config.cruiseMinKmh(); }

    /** 触发持续时间（毫秒）：面板「自动巡航 → 触发持续时间」，默认 8 秒（原 15 秒）。 */
    private static long driveMs() { return Config.cruiseDriveSec() * 1000L; }
    /**
     * 窗口内「路径」累计位移下限（米）。<b>已不是否决条件</b>，只在「净位移拿不到」时当第二证据。
     * 12km/h × 8s 约 26.7m，留余量取 60m 封顶。
     */
    private static final float MIN_DIST_M = 60f;
    /** 净位移硬上限（米）：{@link #netNeedM()} 的封顶值。 */
    private static final float MIN_NET_M = 45f;
    /**
     * 「攒够这么多步可信位移」= 第二种「真的在移动」的证据（净位移拿不到时用它）。
     * 原 6：1Hz 下 15 秒窗口要 6 步，只要有一帧被 dup/jump 吃掉就凑不齐（0.9^6 约 0.53 再乘别的）；
     * 现在 3 步（0.9^3 约 0.73），而且它只是备选证据，不再是必须项。
     */
    private static final int MIN_WIN_FIXES = 3;
    /**
     * 定位精度门槛（米）：比这更差的点<b>不累计路径</b>，但速度照用（见 {@link #gate}）。
     * 原 45：城市里 AMap 定位点精度常态 30~60m、室内/Wi-Fi 点 80~300m，
     * 45m 这条线把相当大一部分帧直接判死，是「永远不触发」的头号原因。放宽到 80m。
     */
    private static final float ACC_MAX_M = 80f;
    /** 单步位移硬上限（米）：一跳几百米的是多径跳点 —— 只作废这一帧的步长，不再清窗口。 */
    private static final float STEP_MAX_M = 90f;
    /** 相邻采样允许的最大速度突变（km/h）——<b>只记日志</b>，不再否决（路口掉速是常态）。 */
    private static final float SPIKE_KMH = 45f;
    /**
     * 进入失败/退出后的冷却。原 60000：复核失败、或被吞掉的 intent 要等整整一分钟才补一次，
     * 用户开出去一公里才「自己好了」。20s 够避免「刚退出来又弹进去」，又能及时自愈。
     */
    private static final long ENTER_COOLDOWN_MS = 20000L;
    private static final long QUICK_BACK_MS = 5000L;   // quickBackTime = 5s
    private static final int QUICK_BACK_MAX = 3;       // quickBackCountThreshold（12s 内连退 3 次才算「用户不想要」）
    /** 抑制窗口：云控没给时长，取 3 分钟 —— 太短会连发，太长会让用户觉得「开了没反应」。 */
    private static final long SUPPRESS_MS = 3 * 60 * 1000L;
    /** 采样周期：1s。 */
    private static final long TICK_MS = 1000L;
    /** 定位点超过这个年龄就不算「实时速度」。 */
    private static final long SPEED_STALE_MS = 20000L;
    /** 心跳日志周期；状态（why）一变就立刻打一条，不等满 15s。 */
    private static final long HEARTBEAT_MS = 15000L;
    /**
     * 位移法：两次有效定位至少隔这么久，算出来的速度才可信。
     * 原 1500 —— <b>1Hz 采样下相邻点 dt 约 1000ms &lt; 1500，位移法永远算不出速度</b>，
     * 于是 moveKmh 恒为 0，fromDisplacement 与 hook+move 两路直接死掉：
     * 「三路速度」实际只剩两路。降到 900ms（留 100ms 抖动余量）。
     */
    private static final long MOVE_MIN_DT_MS = 900L;
    /**
     * 连续多少帧不达标才清零窗口。原实现「一帧不达标就全清」：
     * GNSS 单帧掉速/丢点就把已经攒了 7 秒的 hold 归零，等于要求连续 8 帧全过（0.7^8 约 0.06）。
     * 容忍 3 帧（约 3 秒）抖动，第 4 帧仍不达标才重来；红绿灯停车（&gt;3s）仍会重来，符合预期。
     */
    private static final int HOLD_GRACE_TICKS = 3;
    /** 进入后复核：1.2s × 5，失败逐级换路重发（见 {@link #verify}）。 */
    private static final int VERIFY_TRIES = 5;
    private static final long VERIFY_MS = 1200L;

    private static final String URI_CRUISE = "amapuri://edog/home";
    private static final String CLS_SCHEME = "com.autonavi.map.activity.SchemeHandleActivity";

    private static Context app;
    private static boolean fgAttached;
    private static ClassLoader hostLoader;
    private static Handler handler;
    private static volatile boolean started;

    private static long speedOkSince;
    private static long lastEnterAt;
    /** 上一次「立即测试」的时间，用于节流（隐藏键清不掉时的兜底）。 */
    private static long lastForceAt;
    private static int quickBackCount;
    private static long suppressUntil;
    private static String lastLoggedState = "";
    private static long lastBeatAt;
    private static int enterSeq;

    // 进入判定的窗口状态：窗口起点、起点时的位移基准、上一帧速度、当前否决原因
    private static float winBaseDist;
    private static float prevKmh = -1f;
    private static volatile float totalDistM;
    private static volatile long posJumpAt;
    /**
     * 当前否决原因（心跳里的 why=）。取值：init / win / net / slow / fast / nospeed /
     * bg / in_cruise / in_nav / cooldown / suppress / enter / force。
     * 用户贴一行日志就能看出卡在哪一道。
     */
    private static volatile String gateWhy = "";
    private static volatile float lastAcc = -1f;
    private static volatile long distFixSeq;
    private static long winBaseFix;
    private static double winLat, winLng;
    /** 当前窗口里连续不达标的帧数：超过 {@link #HOLD_GRACE_TICKS} 才真正清零窗口。 */
    private static int missTicks;
    /** 上一次心跳打过的 why，用于「状态一变立刻打一条」。 */
    private static String lastBeatWhy = "";
    /** cruiseAuto 开关上一次的取值，用于只打一次的 on/off 变更日志。 */
    private static boolean autoWasOn;
    /**
     * 高德 IDriveNaviService 反射是否成功过。
     * 失败时 inCruise()/inNavigation() 恒返回 false —— 这时「复核失败」不代表真的没进去，
     * 只能记 cruise_enter_unverified，不能记 cruise_enter_fail（否则日志会误导排障）。
     */
    private static volatile boolean driveProbeOk;

    // 位移法用的上一个定位点
    private static volatile double lastLat, lastLng;
    private static volatile long lastPosAt;
    private static volatile float moveKmh;

    // 定位回调流：hook Location#setSpeed，AMap 每算出一个定位点就刷新一次
    private static volatile float hookSpeed;
    private static volatile boolean hookKmh;
    private static volatile long hookAt;
    private static volatile Object hookLoc;
    private static volatile java.lang.reflect.Method unitMethod;
    /** 采样链路探针：哪一路拿到了速度，或失败在哪一步（心跳里打成 probe=）。 */
    private static volatile String why = "init";
    private static volatile float dbgKmh = -1f;
    private static volatile long dbgAt;

    static void install(final ClassLoader cl) {
        hostLoader = cl;
        try {
            handler = new Handler(Looper.getMainLooper());
            started = true;
            // onPackageReady 早于 Application.onCreate：Context 要重试拿，不能一次失败就放弃
            handler.postDelayed(BOOT, 300L);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_driver install_failed err=" + t);
        }
    }

    private static final Runnable BOOT = new Runnable() {
        private int tries;
        @Override public void run() {
            try {
                if (app == null) app = currentApplication();
                if (app != null) {
                    if (!fgAttached) {
                        fgAttached = true;
                        if (app instanceof android.app.Application) HostFg.attach((android.app.Application) app);
                    }
                    String diag = "";
                    try {
                        android.content.SharedPreferences p = Config.prefs();
                        diag = " prefs=" + (p == null ? "null" : p.getClass().getSimpleName())
                                + " n=" + (p == null ? -1 : p.getAll().size())
                                + " cruise=" + (p == null ? "-" : p.getBoolean(Config.K_CRUISE_AUTO, false))
                                + " force=" + (p == null ? "-" : p.getBoolean(Config.K_CFG_FORCE_LOCAL, false));
                    } catch (Throwable ignored) {}
                    H.log(Log.INFO, MainHook.TAG, "event=cruise_driver installed enabled=" + Config.cruiseAuto()
                            + " tries=" + tries + diag);
                    installLocationHook();
                    handler.postDelayed(TICK, TICK_MS);
                    return;
                }
            } catch (Throwable ignored) {}
            if (++tries < 40) {
                handler.postDelayed(this, 500L);
            } else {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_driver skip=no_app tries=" + tries);
            }
        }
    };

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            try { tick(); } catch (Throwable ignored) {}
            try { if (started) handler.postDelayed(this, TICK_MS); } catch (Throwable ignored) {}
        }
    };

    private static void tick() {
        // ① 「立即测试」隐藏键（面板按钮写入）：不受开关 / 冷却 / 门槛影响。
        //    先消费再执行 —— 后面就算抛异常，也不会变成「每个 tick 强进一次」的死循环。
        if (Config.cruiseForceOnce()) {
            forceOnce();
            return;
        }

        // ② 开关：默认 false，老用户行为不变。只在状态翻转时打一条，避免 1Hz 刷屏。
        if (!Config.cruiseAuto()) {
            if (autoWasOn) {
                autoWasOn = false;
                H.log(Log.INFO, MainHook.TAG, "event=cruise_auto state=off");
            }
            winClear();
            gateWhy = "off";
            return;
        }
        if (!autoWasOn) {
            autoWasOn = true;
            H.log(Log.INFO, MainHook.TAG, "event=cruise_auto state=on min_kmh=" + Math.round(minKmh())
                    + " need_sec=" + (driveMs() / 1000L)
                    + " need_net=" + Math.round(netNeedM())
                    + " acc_max=" + Math.round(accMaxM()));
        }

        long now = SystemClock.elapsedRealtime();
        boolean fg = HostFg.fg();
        if (!fg) {
            // 后台不判定，也不采样（后台强行采样只会让定位更耗电，且进不去巡航反而更糟）
            winClear();
            gateWhy = "bg";
            beat(false, false, false, -1f, "none");
            return;
        }
        boolean cruise = inCruise();
        boolean nav = inNavigation();
        if (cruise || nav) {
            // 用户自己退出巡航后 5s 内又被判到 → 记一次「快速返回」
            if (lastEnterAt > 0) {
                long dt = now - lastEnterAt;
                if (dt > 800 && dt <= QUICK_BACK_MS && !cruise) {
                    quickBackCount++;
                    lastEnterAt = 0;
                    H.log(Log.INFO, MainHook.TAG, "event=cruise_suppress count=" + quickBackCount);
                    if (quickBackCount >= QUICK_BACK_MAX) suppressUntil = now + SUPPRESS_MS;
                }
            }
            winClear();
            gateWhy = cruise ? "in_cruise" : "in_nav";
            beat(true, cruise, nav, -1f, "in");
            return;
        }

        // ③ 采样放在「冷却 / 抑制」**之前**：这两个窗口里照样要采。
        //    老实现一进冷却就 return，位移链断 20~60 秒，冷却结束时 moveKmh 早已过期，
        //    「位移法」两路等于每次冷却完都要从零开始攒。
        Sample s = sample();
        float kmh = (s == null) ? -1f : s.kmh;
        String src = (s == null) ? "none" : s.src;

        if (suppressUntil > 0 && now < suppressUntil) {
            winClear();
            gateWhy = "suppress";
            beat(true, false, false, kmh, src);
            return;
        }
        if (suppressUntil > 0) { suppressUntil = 0; quickBackCount = 0; }

        // 进入冷却：失败/退出后 {@link #ENTER_COOLDOWN_MS} 内不再自己进去
        if (lastEnterAt > 0 && now - lastEnterAt < ENTER_COOLDOWN_MS) {
            winClear();
            gateWhy = "cooldown";
            beat(true, false, false, kmh, src);
            return;
        }

        // ── 主判据 1：速度。任一路拿到即可，拿不到才是「没速度」 ────────────────────
        if (s == null || s.kmh < 0f) {
            miss(now, "nospeed");
            beat(true, false, false, -1f, "none");
            return;
        }
        String bad = gate(s);
        if (bad != null) {
            miss(now, bad);
            beat(true, false, false, s.kmh, s.src);
            return;
        }
        prevKmh = s.kmh;
        missTicks = 0;

        if (speedOkSince == 0) {
            // 第一帧达标：开窗，位移基准钉在此刻
            speedOkSince = now;
            winBaseDist = totalDistM;
            winBaseFix = distFixSeq;
            winLat = lastLat;
            winLng = lastLng;
            gateWhy = "win";
            logSpeed(s.kmh, "acc");
            beat(true, false, false, s.kmh, s.src);
            return;
        }
        // 迟到锚点：开窗时还没有任何定位点，第一个点到了再从此刻起量净位移
        // （否则 netM() 永远返回「拿不到」，净位移这关形同不存在）
        if (winLat == 0d && winLng == 0d && (lastLat != 0d || lastLng != 0d)) {
            winLat = lastLat;
            winLng = lastLng;
        }

        // ── 主判据 1 成立：hold 够长 ────────────────────────────────────────────────
        long holdMs = now - speedOkSince;
        long needMs = driveMsFor(s.src);
        float net = netM();
        boolean netOk = netOk(net, netNeedFor(s.src));
        boolean bypass = "debug".equals(s.src);     // 排障文件喂的假速度：桌面验证链路用，放行
        if (bypass || (holdMs >= needMs && netOk)) {
            speedOkSince = 0;
            missTicks = 0;
            gateWhy = "enter";
            enterCruise(s.kmh, s.src);
        } else if (holdMs >= needMs) {
            // 时间够了、速度也一直达标，但净位移不够 ⇒ 原地抖 / 定位被钉死在同一个点。
            // 老实现这里静默重置，日志上看起来跟「刚开窗」一模一样，根本没法排障；
            // 现在 why=net 会一直挂在心跳里，还额外打一条 cruise_probe。
            gateWhy = "net";
            logSpeed(s.kmh, "net_reset");
            winClear();
        } else {
            gateWhy = "win";
        }
        beat(true, false, false, s.kmh, s.src);
    }

    /**
     * 面板「立即测试」：跳过全部门槛直接尝试进入一次，并立刻把隐藏键清回 false。
     * 用途是把「链路通不通（scheme / 类名）」和「判定过不过（门槛 / 有没有速度）」分开 ——
     * 用户坐在家里点一下就能验证，不用真开一趟车。
     */
    private static void forceOnce() {
        long now0 = SystemClock.elapsedRealtime();
        // 隐藏键万一清不掉（后端只读 / commit 失败），也不能变成每个 tick 都发一次 intent。
        // 10 秒一次上限，足够让用户点一次看到结果，又不会把高德刷崩。
        if (lastForceAt != 0 && now0 - lastForceAt < 10000L) return;
        lastForceAt = now0;
        boolean cleared = Config.clearCruiseForceOnce();
        try {
            long now = SystemClock.elapsedRealtime();
            boolean fg = HostFg.fg();
            boolean cruise = inCruise();
            boolean nav = inNavigation();
            Sample s = sample();
            float kmh = (s == null) ? -1f : s.kmh;
            String src = (s == null) ? "none" : s.src;
            H.log(Log.INFO, MainHook.TAG, "event=cruise_force_once cleared=" + cleared
                    + " fg=" + fg + " cruise=" + cruise + " nav=" + nav
                    + " kmh=" + Math.round(kmh) + " src=" + src
                    + " auto=" + Config.cruiseAuto());
            winClear();
            if (cruise) {
                gateWhy = "in_cruise";
                beat(fg, true, nav, kmh, src);
                return;
            }
            gateWhy = "force";
            enterCruise(kmh, "force");
            beat(fg, cruise, nav, kmh, src);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_force_once failed err=" + t);
        }
    }

    /** 清空判定窗口。**不动**采样链路（moveKmh / lastPosAt / totalDistM），那些要继续热着。 */
    private static void winClear() {
        speedOkSince = 0;
        missTicks = 0;
        prevKmh = -1f;
        winBaseDist = totalDistM;
        winBaseFix = distFixSeq;
        winLat = 0d;
        winLng = 0d;
    }

    /**
     * 一帧不达标。老实现是「任何一帧失败就把 hold 和位移基线全部归零」：
     * 8 秒窗口等于要求连续 8 帧全过，单帧 0.7 的话 0.7^8 约 0.06。
     * 现在容忍 {@link #HOLD_GRACE_TICKS} 帧，第 4 帧才真正清窗 ——
     * 单帧掉速/丢点不再打断已经攒起来的 hold，红绿灯停车（&gt;3s）仍然会重来。
     */
    private static void miss(long now, String why) {
        gateWhy = why;
        if (speedOkSince == 0) { prevKmh = -1f; return; }
        missTicks++;
        if (missTicks > HOLD_GRACE_TICKS) {
            logSpeed(prevKmh < 0f ? 0f : prevKmh, "reset");
            winClear();
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
    private static boolean netOk(float net, float need) {
        try {
            float dist = totalDistM - winBaseDist;
            long fixes = distFixSeq - winBaseFix;
            // 明确测到了净位移：够 → 过；不够 → 再看第二证据（路面弯曲时直线距离会明显小于路程）
            if (net >= 0f) {
                if (net >= need) return true;
                return fixes >= MIN_WIN_FIXES && dist >= distNeedM();
            }
            // net < 0 = 地理数据根本拿不到（没有起点 / 当前点精度离谱）。
            // 这一条**降级跳过**，不否决 —— 否则「没定位权限的手机」就永远别想触发。
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    // ── 速度采样 ────────────────────────────────────────────────────────────────

    private static final class Sample {
        final float kmh;
        final String src;
        final float acc;          // 定位精度（米），-1 = 不知道

        Sample(float kmh, String src) { this(kmh, src, -1f); }

        Sample(float kmh, String src, float acc) {
            this.kmh = kmh;
            this.src = src;
            this.acc = acc;
        }
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
    private static Sample sample() {
        try {
            Sample t = fromDebugFile();
            if (t != null) { why = "debug"; return t; }
            StringBuilder probe = new StringBuilder();
            Sample a = fromAmap();
            if (a != null) { why = "amap"; return preferMoving(a); }
            probe.append("amap:").append(why).append(' ');
            Sample b = fromHook();
            if (b != null) { why = "hook"; return preferMoving(b); }
            probe.append("hook:").append(hookAt == 0 ? "never" : "stale").append(' ');
            Sample c = fromSystem();
            if (c != null) { why = "sys"; return preferMoving(c); }
            probe.append("sys:none ");
            Sample d = fromDisplacement();
            if (d != null) { why = "move"; return d; }
            // 四路全空：把每一路各自卡在哪拼出来，拼成 probe=。
            // 这一行是「一直触发不了」时最有价值的信息：
            //   amap_err:ClassNotFoundException ⇒ 类名漂移；amap:svc_null ⇒ getLocationService 拿不到；
            //   hook:never ⇒ Location#setSpeed 从没被调过；hook:stale ⇒ 调过但超过 10s；
            //   sys:none ⇒ 没定位权限 / 没有 provider；move:nofix ⇒ 连一个定位点都拿不到。
            probe.append("move:").append(lastPosAt == 0 ? "nofix" : "slow");
            why = probe.toString().trim();
            return null;
        } catch (Throwable e) {
            why = "sample_err:" + e.getClass().getSimpleName();
            return null;
        }
    }

    /**
     * 「某一路给出了速度」但偏低时，用位移速度兜底。
     * 只在位移法自己认为在动（moveKmh ≥ 门槛且定位点新鲜）时才替换，否则原样返回。
     */
    private static Sample preferMoving(Sample s) {
        try {
            if (s == null || s.kmh >= minKmh()) return s;
            if (moveKmh < minKmh()) return s;
            if (lastPosAt <= 0) return s;
            if (SystemClock.elapsedRealtime() - lastPosAt > SPEED_STALE_MS) return s;
            return new Sample(Math.min(moveKmh, MAX_KMH), "move", s.acc);
        } catch (Throwable t) {
            return s;
        }
    }

    /**
     * 排障用：/data/local/tmp/mapclean_cruise_kmh 里写一个数字（需要 root 或 adb 才能写），
     * 就按这个速度当作实时速度。用来在静止的桌面上验证「累计→进入巡航→校验」整条链路，
     * 平时该文件不存在，对本功能没有任何影响。
     */
    private static Sample fromDebugFile() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/mapclean_cruise_kmh");
            long now = SystemClock.elapsedRealtime();
            if (now - dbgAt < 3000L) return dbgKmh >= 0f ? new Sample(dbgKmh, "debug") : null;
            dbgAt = now;
            if (!f.exists()) { dbgKmh = -1f; return null; }
            byte[] buf = new byte[32];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n;
            try { n = in.read(buf); } finally { in.close(); }
            if (n <= 0) { dbgKmh = -1f; return null; }
            dbgKmh = Float.parseFloat(new String(buf, 0, n).trim());
            return dbgKmh >= 0f ? new Sample(dbgKmh, "debug") : null;
        } catch (Throwable t) {
            dbgKmh = -1f;
            return null;
        }
    }

    /** ② hook 流：Location#setSpeed 每次被调用都刷新，10s 内算实时。 */
    private static Sample fromHook() {
        try {
            if (hookAt == 0) return null;
            if (SystemClock.elapsedRealtime() - hookAt > 10000L) return null;
            float kmh = unitKmh(hookLoc) ? hookSpeed : hookSpeed * 3.6f;
            if (kmh < 0f) return null;
            if (kmh < 1f && moveKmh >= minKmh()
                    && SystemClock.elapsedRealtime() - lastPosAt <= SPEED_STALE_MS) {
                // GNSS 速度给 0（隧道 / 冷启动），但经纬度在动：这一路直接用位移速度。
                // 原来标成 "hook+move" 然后被 gate 白名单挡掉，等于白算 —— 现在它就是一条正常来源。
                return new Sample(moveKmh, "move", accuracyOf(hookLoc));
            }
            return new Sample(kmh, "hook", accuracyOf(hookLoc));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 单位以定位点自己的 isSpeedKmh 标记为准（逐帧读，避免串帧把 km/h 又乘 3.6）。 */
    private static boolean unitKmh(Object loc) {
        if (loc != null) {
            try {
                java.lang.reflect.Method m = unitMethod;
                if (m == null) {
                    m = loc.getClass().getMethod("isSpeedKmh");
                    m.setAccessible(true);
                    unitMethod = m;
                }
                Object r = m.invoke(loc);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignored) {}
        }
        return hookKmh;
    }

    /** 装定位回调 hook：AMap 自己每生成一个定位点都会 setSpeed，等于免费拿到推送。 */
    private static void installLocationHook() {
        try {
            Class<?> lc = H.cls(hostLoader, "com.amap.location.type.location.Location");
            int n = 0;
            n += H.hookAll(lc, "setSpeed", "amap_cruise_speed", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object v = chain.getArg(0);
                        if (v instanceof Float) {
                            hookSpeed = (Float) v;
                            hookAt = SystemClock.elapsedRealtime();
                            hookLoc = chain.getThisObject();
                            // 同一个定位点顺手喂给位移法：GNSS 速度可能为 0（隧道/冷启动），
                            // 但经纬度一直在动，位移法就能补上。
                            rememberPosition(chain.getThisObject());
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
            n += H.hookAll(lc, "setSpeedKmh", "amap_cruise_speed_kmh", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object v = chain.getArg(0);
                        if (v instanceof Boolean) hookKmh = (Boolean) v;
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
            H.log(Log.INFO, MainHook.TAG, "event=cruise_hook location=" + n);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_hook failed err=" + t);
        }
    }

    /** ① 高德自己的定位服务（精度最好，导航/巡航时就是它）。 */
    private static Sample fromAmap() {
        try {
            Class<?> ai = hostLoader.loadClass("com.amap.AppInterfaces");
            Object svc = ai.getDeclaredMethod("getLocationService").invoke(null);
            if (svc == null) { why = "svc_null"; return null; }
            Class<?> ils = hostLoader.loadClass("com.amap.location.api.ILocationService");
            Object loc = ils.getMethod("getLatestLocation").invoke(svc);
            if (loc == null) { why = "loc_null"; return null; }
            rememberPosition(loc);
            Sample s = speedOf(loc, "amap");
            if (s == null) why = "stale";
            return s;
        } catch (Throwable t) {
            why = "amap_err:" + t.getClass().getSimpleName();
            return null;
        }
    }

    /** ② 系统 LocationManager 的最后已知点：高德没在跑定位时也能拿到（20s 内有效）。 */
    private static Sample fromSystem() {
        try {
            if (app == null) return null;
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            long now = System.currentTimeMillis();
            String[] probes = new String[] {
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    "fused"
            };
            for (String p : probes) {
                Location l = null;
                try { l = lm.getLastKnownLocation(p); } catch (Throwable ignored) {}
                if (l == null) continue;
                long age = now - l.getTime();
                if (age < 0 || age > SPEED_STALE_MS) continue;
                rememberAndroid(l);
                if (!l.hasSpeed()) continue;
                float mps = l.getSpeed();
                float kmh = mps * 3.6f;
                if (kmh >= 0f) return new Sample(kmh, "sys", l.getAccuracy());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** ③ 位移法：两个定位点之间跑了多远／多久（连速度字段都没有时兜底）。 */
    private static Sample fromDisplacement() {
        try {
            if (lastPosAt <= 0 || moveKmh < minKmh()) return null;
            if (SystemClock.elapsedRealtime() - lastPosAt > SPEED_STALE_MS) return null;
            return new Sample(moveKmh, "move", -1f);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Sample speedOf(Object loc, String src) {
        try {
            Class<?> lc = hostLoader.loadClass("com.amap.location.type.location.Location");
            float speed = (Float) lc.getMethod("getSpeed").invoke(loc);
            boolean isKmh = false;
            try { isKmh = (Boolean) lc.getMethod("isSpeedKmh").invoke(loc); } catch (Throwable ignored) {}
            long tickTime = 0L;
            try { tickTime = (Long) lc.getMethod("getLocationTickTime").invoke(loc); } catch (Throwable ignored) {}
            long now = System.currentTimeMillis();
            if (tickTime > 0) {
                long age = Math.abs(now - tickTime);
                long ageAlt = Math.abs(SystemClock.elapsedRealtime() - tickTime);
                if (Math.min(age, ageAlt) > SPEED_STALE_MS) return null;
            }
            float kmh = isKmh ? speed : speed * 3.6f;
            if (kmh < 0f) return null;
            return new Sample(kmh, src, accuracyOf(loc));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 精度（米）；读不到给 -1，调用方按「不校验」处理，不能因为读不到就永远不触发。 */
    private static float accuracyOf(Object loc) {
        if (loc == null) return -1f;
        try {
            Object a = loc.getClass().getMethod("getAccuracy").invoke(loc);
            if (a instanceof Float) return (Float) a;
            if (a instanceof Double) return ((Double) a).floatValue();
        } catch (Throwable ignored) {}
        return -1f;
    }

    private static void rememberPosition(Object loc) {
        try {
            Class<?> lc = hostLoader.loadClass("com.amap.location.type.location.Location");
            double lat = (Double) lc.getMethod("getLatitude").invoke(loc);
            double lng = (Double) lc.getMethod("getLongitude").invoke(loc);
            if (lat == 0d && lng == 0d) return;
            float acc = accuracyOf(loc);
            if (acc < 0f) acc = 30f;          // 高德点一般带精度；读不到时按中等精度处理
            long before = lastPosAt;
            double pLat = lastLat, pLng = lastLng;
            addFix(lat, lng, acc, true);
            // 位移法继续沿用原来的口径（只在时间够长时更新 moveKmh）
            long dt = lastPosAt - before;
            if (before > 0 && dt >= MOVE_MIN_DT_MS) {
                float m = distanceMeters(pLat, pLng, lat, lng);
                float kmh = (m / (dt / 1000f)) * 3.6f;
                if (kmh >= minKmh()) moveKmh = kmh; else moveKmh = 0f;
            }
        } catch (Throwable ignored) {}
    }

    /** 单步位移计入窗口的速度下限：低速档跟着 minKmh 降，默认档封顶 8 km/h。 */
    private static float stepGateKmh() {
        return Math.min(8f, Math.max(1.5f, minKmh() * 0.8f));
    }

    /**
     * 窗口内「路径」累计位移的下限 —— <b>只是第二证据</b>，不再是进入门槛。
     * 由「速度 × 时间」实算：minKmh/3.6 × driveSec × 0.6（留 40% 余量给路口和弯道）。
     * 默认 12km/h × 8s：3.33 × 8 × 0.6 ≈ 16 米（原写死 60 米，12km/h 下等于要求多跑 2.3 倍时间）。
     */
    private static float distNeedM() {
        float v = (minKmh() / 3.6f) * (driveMs() / 1000f) * 0.6f;
        return Math.min(MIN_DIST_M, Math.max(10f, v));
    }

    /**
     * 起点到当前点的净位移下限（主判据 2 的数）。
     * 由「速度 × 时间」实算而不是写死：minKmh/3.6 × driveSec × 0.35。
     * 0.35 这个系数是因为净位移是<b>直线</b>距离，路口转弯、并线会让它明显小于路程；
     * 默认 12km/h × 8s：3.33 × 8 × 0.35 ≈ 9.3 米 —— 只要车真的在往前开就一定过，
     * 而原地抖（GNSS 噪声半径 5~15 米）大多过不了，且配合「速度持续 8 秒达标」不会误触。
     * 原写死 45 米 = 12km/h 下 13.5 秒的直线距离，是「永远不触发」的第二大来源。
     */
    private static float netNeedM() {
        float v = (minKmh() / 3.6f) * (driveMs() / 1000f) * 0.35f;
        return Math.min(MIN_NET_M, Math.max(5f, v));
    }

    /** 允许的最大定位精度：低速档放宽，走路时 GNSS 精度经常 50 米开外。 */
    private static float accMaxM() {
        return minKmh() <= 6 ? 120f : ACC_MAX_M;
    }

    /**
     * 净位移「可信」的上限精度：比这还差的当前点（基站 / 多径跳点）算出来的净位移不可信，
     * 直接按「拿不到」处理（降级放行），不用它去否决 —— 也不让它把 net 吹大骗过判据。
     */
    private static float accMaxNetM() {
        return accMaxM() * 2f;
    }

    /**
     * 只有「位移法」这一路可用时的持续时间门槛：× 1.5。
     *
     * <p>为什么要加严：纯位移速度是「两点距离 / 时间」算出来的，静止时 GNSS 单帧抖 3 米
     * （城市里很常见）在一秒内就是 10.8km/h，抖 5 米就是 18km/h —— 足够骗过 12km/h 的门槛。
     * 所以位移来源单独要更长的持续时间 + 更大的净位移，见 {@link #netNeedFor}。
     * 默认档：8s → 12s。
     */
    private static long driveMsFor(String src) {
        return "move".equals(src) ? (driveMs() * 3L / 2L) : driveMs();
    }

    /**
     * 只有「位移法」这一路可用时的净位移门槛：× 2（默认档 9.3m → 18.6m）。
     * 12 秒真跑 12km/h 应该走 40 米，净位移 18.6 米是很宽松的下限；
     * 而原地抖动要连续 12 秒每帧都抖出 3 米以上且净位移凑够 18.6 米，概率低得多。
     */
    private static float netNeedFor(String src) {
        return "move".equals(src) ? netNeedM() * 2f : netNeedM();
    }

    /**
     * 定位点记账：只有「精度可信 + 时间合理 + 单步速度自洽 + 不是原地抖动」的位移才累计<b>路径</b>。
     *
     * <p>注意语义变了：这里累计的 {@code totalDistM} / {@code distFixSeq} 现在只用于
     * (a) 心跳里的 dist/fix 诊断，(b) 净位移拿不到时的第二证据。它<b>不再否决</b>任何一帧 ——
     * 老实现里「精度 &gt;45m 就整帧作废」「一步超 90m 就作废整个窗口」才是触发不了的主力。
     */
    private static void addFix(double lat, double lng, float acc, boolean trusted) {
        long now = SystemClock.elapsedRealtime();
        if (!trusted || acc < 0f || acc > accMaxM()) {
            // 精度不可信：只更新锚点（净位移要用），不累计路径
            lastLat = lat; lastLng = lng; lastPosAt = now;
            return;
        }
        if (lastPosAt > 0) {
            long dt = now - lastPosAt;
            float m = distanceMeters(lastLat, lastLng, lat, lng);
            float stepKmh = dt > 0 ? (m / (dt / 1000f)) * 3.6f : 0f;
            // dup 阈值原来是 max(1.0, min(4.0, acc*0.15))：acc=80 时等于 4 米，
            // 而 12km/h 一秒只走 3.33 米 —— 低速 + 差精度下**每一步都被判成原地抖动**，
            // 路径永远累计不起来（0.33 的乘数就是这么丢掉的）。改成 acc*0.10 且封顶 3 米。
            float dupM = Math.max(1.0f, Math.min(3.0f, acc * 0.10f));
            boolean dup = m < dupM;                            // 同一点重复上报 / 原地抖动
            boolean gap = dt < 800L || dt > 10000L;             // 太密或隔太久，速度不可信
            boolean jump = m > STEP_MAX_M || stepKmh > 220f;    // 多径跳点：只作废这一帧
            if (jump) {
                posJumpAt = now;                                // 只留个时间戳给日志看
            } else if (!dup && !gap && stepKmh >= stepGateKmh()) {
                totalDistM += m;
                distFixSeq++;
            }
        }
        lastLat = lat; lastLng = lng; lastPosAt = now;
    }

    private static void rememberAndroid(Location l) {
        try {
            long before = lastPosAt;
            boolean hasAcc = true;
            float acc = -1f;
            try { acc = l.getAccuracy(); hasAcc = l.hasAccuracy(); } catch (Throwable ignored) {}
            double pLat = lastLat, pLng = lastLng;
            addFix(l.getLatitude(), l.getLongitude(), hasAcc ? acc : -1f, hasAcc);
            long dt = lastPosAt - before;
            if (before > 0 && dt >= MOVE_MIN_DT_MS) {
                float m = distanceMeters(pLat, pLng, l.getLatitude(), l.getLongitude());
                float kmh = (m / (dt / 1000f)) * 3.6f;
                if (kmh >= minKmh()) moveKmh = kmh; else moveKmh = 0f;
            }
        } catch (Throwable ignored) {}
    }

    private static float distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (float) (2 * r * Math.asin(Math.sqrt(a)));
    }

    // ── 进入 + 复核 ────────────────────────────────────────────────────────────

    private static void enterCruise(final float kmh, String src) {
        final int seq = ++enterSeq;
        lastEnterAt = SystemClock.elapsedRealtime();
        H.log(Log.INFO, MainHook.TAG, "event=cruise_enter reason="
                + ("force".equals(src) ? "force" : "auto")
                + " speed=" + Math.round(kmh) + " src=" + src + " seq=" + seq
                + " hold=" + (driveMsFor(src) / 1000L) + "s");
        // 路 1：显式组件直达 SchemeHandleActivity（最强，但类名一漂就 ActivityNotFound）
        fire(1);
        // 1.2s×5 复核：没进去就逐级换路重发（见 verify）
        for (int i = 1; i <= VERIFY_TRIES; i++) {
            final int attempt = i;
            handler.postDelayed(new Runnable() {
                @Override public void run() { verify(seq, attempt); }
            }, VERIFY_MS * i);
        }
    }

    /**
     * 复核 + 自愈。老实现只在第 1 次失败时用<b>同一条</b>路重发一次，
     * 如果那条路本身不通（类名漂移、组件被禁），重发一万次也没用。
     *
     * <p>现在：1 次失败 → 换路 2（只发 scheme 让高德自己的 manifest 过滤器解析）；
     * 3 次失败 → 两路各再补一发；5 次仍不认 → 记 fail（或 unverified，见 {@link #driveProbeOk}）。
     */
    private static void verify(int seq, int attempt) {
        if (seq != enterSeq) return;          // 已经被新一轮取代
        boolean ok = false;
        try { ok = inCruise(); } catch (Throwable ignored) {}
        if (ok) {
            H.log(Log.INFO, MainHook.TAG, "event=cruise_enter_ok seq=" + seq + " after=" + attempt);
            enterSeq = 0;
            return;
        }
        if (!driveProbeOk && attempt >= 2) {
            // 拿不到 isInCruise()：不知道到底进没进，不能冤成 fail，否则用户按这个日志去查错方向
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_unverified seq=" + seq
                    + " reason=no_drive_service attempt=" + attempt);
            enterSeq = 0;
            return;
        }
        if (attempt == 1) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_retry seq=" + seq + " road=2");
            fire(2);
        } else if (attempt == 3) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_retry seq=" + seq + " road=1");
            fire(1);
        } else if (attempt == VERIFY_TRIES) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter_fail seq=" + seq);
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
    private static void fire(int road) {
        try {
            if (app == null) { app = currentApplication(); }
            if (app == null) {
                H.log(Log.WARN, MainHook.TAG, "event=cruise_enter failed road=" + road + " err=no_context");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(URI_CRUISE));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (road == 1) {
                intent.setClassName(app.getPackageName(), CLS_SCHEME);
            } else {
                // 路 2：指定包名但不定组件 —— 交给高德自己解析 scheme
                intent.setPackage(MainHook.PKG_AMAP);
            }
            app.startActivity(intent);
            H.log(Log.INFO, MainHook.TAG, "event=cruise_fire road=" + road + " uri=" + URI_CRUISE);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_enter failed road=" + road + " err=" + t);
        }
    }

    // ── 状态与日志 ──────────────────────────────────────────────────────────────

    static boolean isForeground() {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null || list.isEmpty()) return true;   // 拿不到列表时不当作「后台」，否则永远不触发
            String pkg = app.getPackageName();
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (pkg.equals(p.processName)) {
                    return p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
            return true;
        } catch (Throwable ignored) {}
        return true;
    }

    /** 调高德自己的 IDriveNaviService 判定，拿不到时退化为 false（宁可多进一次也不误锁）。 */
    private static boolean inCruise() { return callDriveBool("isInCruise"); }
    private static boolean inNavigation() { return callDriveBool("isInNavigation"); }

    private static boolean callDriveBool(String method) {
        try {
            Class<?> loader = hostLoader.loadClass("com.autonavi.inter.impl.BundleInterfaceLoaderImpl");
            Object impl = loader.getDeclaredConstructor().newInstance();
            Class<?> api = hostLoader.loadClass("com.amap.bundle.drive.api.IDriveNaviService");
            Object svc = loader.getMethod("getBundle", Class.class).invoke(impl, api);
            if (svc == null) return false;
            Object r = api.getMethod(method).invoke(svc);
            // 只要这里拿到过 Boolean，就说明高德的服务反射是可用的。
            // verify() 靠它区分「真的没进去（fail）」和「根本没有判定能力（unverified）」。
            if (r instanceof Boolean) {
                driveProbeOk = true;
                return (Boolean) r;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 净位移（米）：窗口起点 → 当前点。
     * 返回 <b>-1 = 拿不到</b>（没有起点 / 当前点精度离谱），调用方按「不校验」处理 ——
     * 老实现「拿不到就返回 0」，等于「没有地理数据 ⇒ 永远过不了净位移这关 ⇒ 永远不触发」。
     */
    private static float netM() {
        try {
            if (winLat == 0d && winLng == 0d) return -1f;
            if (lastLat == 0d && lastLng == 0d) return -1f;
            if (lastAcc > accMaxNetM()) return -1f;   // 当前点太离谱，净位移不可信
            return distanceMeters(winLat, winLng, lastLat, lastLng);
        } catch (Throwable t) {
            return -1f;
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
    private static String gate(Sample s) {
        if (s == null) return "nospeed";
        lastAcc = s.acc;
        if (s.kmh < 0f) return "nospeed";
        if (s.kmh < minKmh()) return "slow";
        if (s.kmh > MAX_KMH) return "fast";
        return null;
    }

    private static void logSpeed(float kmh, String state) {
        if (lastLoggedState.equals(state) && "reset".equals(state)) return;
        lastLoggedState = state;
        H.log(Log.INFO, MainHook.TAG, "event=cruise_probe speed=" + Math.round(kmh) + " state=" + state);
    }

    /**
     * 一行状态汇总，给「主动抓包」的 10 分钟 txt 用（每 5 秒记一条）。
     * 所有值都是现成字段，取不到就给默认值，绝不抛。字段与 {@link #beat} 对齐，便于对照。
     */
    static String diag() {
        try {
            long now = SystemClock.elapsedRealtime();
            long hold = speedOkSince == 0 ? 0 : now - speedOkSince;
            boolean fg = HostFg.fg();
            boolean cruise = fg && inCruise();
            boolean nav = fg && inNavigation();
            float kmh = -1f;
            if (hookSpeed >= 0f && hookAt > 0 && now - hookAt <= SPEED_STALE_MS) {
                kmh = hookKmh ? hookSpeed : hookSpeed * 3.6f;
            }
            long sup = suppressUntil > now ? (suppressUntil - now) / 1000L : 0L;
            return "fg=" + fg + " cruise=" + cruise + " nav=" + nav
                    + " kmh=" + Math.round(kmh) + " src=" + why
                    + " why=" + (gateWhy.length() == 0 ? "ok" : gateWhy)
                    + " hold=" + (hold / 1000L) + " need=" + (driveMs() / 1000L)
                    + " move=" + Math.round(moveKmh)
                    + " dist=" + Math.round(totalDistM - winBaseDist)
                    + " net=" + Math.round(netM()) + " need_net=" + Math.round(netNeedM())
                    + " fix=" + (distFixSeq - winBaseFix) + " acc=" + Math.round(lastAcc)
                    + " grace=" + missTicks
                    + " hook_age=" + (hookAt == 0 ? -1 : now - hookAt)
                    + " suppress=" + sup + " qb=" + quickBackCount;
        } catch (Throwable t) {
            return "diag_err=" + t.getClass().getSimpleName();
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
    private static void beat(boolean fg, boolean cruise, boolean nav, float kmh, String src) {
        long now = SystemClock.elapsedRealtime();
        String w = gateWhy.length() == 0 ? "ok" : gateWhy;
        boolean changed = !w.equals(lastBeatWhy);
        // 状态变了立刻打，但最快 2 秒一条：车速在门槛上下浮动时 why 会在 win/slow 之间反复，
        // 不加下限会退化成 1Hz 刷屏，反而把有用的行冲掉。
        long minGap = changed ? 2000L : HEARTBEAT_MS;
        if (lastBeatAt != 0 && now - lastBeatAt < minGap) return;
        lastBeatAt = now;
        lastBeatWhy = w;
        long hold = speedOkSince == 0 ? 0 : now - speedOkSince;
        float net = netM();
        boolean spike = prevKmh > 0f && Math.abs(kmh - prevKmh) > SPIKE_KMH;
        long jumpAge = posJumpAt == 0 ? -1 : now - posJumpAt;
        H.log(Log.INFO, MainHook.TAG, "event=cruise_tick"
                + " fg=" + fg + " cruise=" + cruise + " nav=" + nav
                + " kmh=" + Math.round(kmh) + " src=" + src
                + " why=" + w
                + " hold=" + (hold / 1000L) + " need=" + (driveMsFor(src) / 1000L)
                + " net=" + Math.round(net) + " need_net=" + Math.round(netNeedFor(src))
                + " move=" + Math.round(moveKmh)
                + " dist=" + Math.round(totalDistM - winBaseDist)
                + " fix=" + (distFixSeq - winBaseFix)
                + " acc=" + Math.round(lastAcc)
                + " grace=" + missTicks + "/" + HOLD_GRACE_TICKS
                + " spike=" + spike + " jump_age=" + jumpAge
                + " cd=" + (lastEnterAt == 0 ? -1 : (now - lastEnterAt) / 1000L)
                + " qb=" + quickBackCount
                + " probe=" + why);
    }

    private static Context currentApplication() {
        // 复用 Config 的四级 Context 兜底（ActivityThread.currentApplication → AppGlobals →
        // mInitialApplication → 内置版 App.ctx），比单条反射稳。
        try {
            Context c = Config.hostCtx();
            if (c != null) return c;
        } catch (Throwable ignored) {}
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentApplication");
            cur.setAccessible(true);
            Object o = cur.invoke(null);
            return o instanceof Context ? (Context) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 诊断探针（只在 debug_log 打开时安装，用于复核"设置页开关到底写不写"）──────────
    static void installProbe(final ClassLoader cl) {
        if (!Config.debugLog()) return;
        try {
            Class<?> c = H.cls(cl, "com.amap.bundle.mapstorage.MapSharePreference");
            XposedInterface.Hooker hooker = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        H.log(Log.INFO, MainHook.TAG, "event=cruise_probe msp."
                                + chain.getExecutable().getName() + " key=" + chain.getArg(0)
                                + " val=" + chain.getArg(1));
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            };
            H.hookAll(c, "putBooleanValue", "amap_cruise_msp_b", hooker);
            H.hookAll(c, "putIntValue", "amap_cruise_msp_i", hooker);
        } catch (Throwable ignored) {}
    }
}
