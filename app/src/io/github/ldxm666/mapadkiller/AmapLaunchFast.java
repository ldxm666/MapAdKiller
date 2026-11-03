package io.github.ldxm666.mapadkiller;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 高德启动任务「延后重放」加速器（v2.3.0）。
 *
 * <h3>它凭什么能加速</h3>
 * 逆向实证（17.02.0.1541，origin.apk/classes.dex）：
 * <pre>
 *   NewMapApplication.onCreate()V
 *     → startLaunch()V                        ← 直接调；onCreate 的 callee 里没有任何
 *                                              Thread / Executor / Handler（查过，0 行）
 *         → AmapLaunchTaskScheduler.b(TaskKey)V   ×27
 *     → AmapLaunchTaskScheduler.b(TaskKey)V       ×4
 * </pre>
 * 而 {@code b(TaskKey)} 的 smali 是：EnumMap 取任务对象 → 打点 U_launcher_task_start
 * → {@code invoke-virtual task.d(Application)} 原地执行 → 打点 U_launcher_task_end。
 * <b>没有线程切换</b>。37 个启动任务里有 31 个在主线程一字排开串行跑，跑完才轮到
 * Activity 的 onCreate —— 这就是「白屏好几秒」的全部来源（白屏本身是
 * Theme.Splash.FullScreen 的 opaque 白底 StartingWindow，首帧不来就不消失）。
 *
 * <h3>它做什么</h3>
 * 在 {@code b()} 上挂 hook，把**只服务次级页面、不参与首帧渲染**的那批任务从主线程链上
 * 摘下来进队列；首帧之后按**原顺序**逐条重放。首帧之前的同步链因此变短。
 *
 * <h3>它绝不做什么</h3>
 * <ul>
 *   <li>不改任务的**实现**，只改**执行时机**；</li>
 *   <li>不做「永不执行」—— 队列有三个触发器（首帧消息 / loadMap+1.2s / 4s 兜底），
 *       哪个先到用哪个；重放失败还会在主线程重试一次；</li>
 *   <li>任何反射异常 ⇒ 立刻 {@code chain.proceed()}，等价于本类没装。</li>
 * </ul>
 *
 * <h3>开关（不需要改 Config，改完强停重开生效）</h3>
 * <pre>
 *   adb shell setprop debug.mapclean.launch_fast 1          # 延后重放，**默认关**
 *   adb shell setprop debug.mapclean.launch_fast.list "INIT_WEBVIEW,INIT_SEARCH"   # 灰度
 *   adb shell setprop debug.mapclean.launch_probe 0         # 关单任务计时日志（默认开）
 * </pre>
 *
 * <h3>★ 实测结论（975aaf17 / Android 14，17.02.0.1541）—— 为什么总闸默认关</h3>
 * 真机三轮冷启动 + 探针全量打点，把「31 个任务串行 = 启动慢」这个假设**证伪**了：
 * <pre>
 *   单个任务耗时    最大一条 INIT_APP_CONFIG=34ms，其余 0–21ms
 *   整条任务链跨度  首个任务 ms=0 → 最后一个被延的任务 ms≈268        ⇒ 上限约 270ms
 *   重放实测 cost=   INIT_CITY_INFO 1 / INIT_BIZ_PRELOAD 1 / INIT_SEARCH 1
 *                    INIT_STEP_COUNTER 4 / INIT_MEDIA 0 / INIT_WEBVIEW 0
 *   Displayed 前后   关 12562/13779/12190ms   开 11536/-/12989ms   ⇒ 差异在噪声内
 * </pre>
 * 这条链**最多值 270ms，实测只兑现了十几毫秒**，跟 12s 的 Displayed 差两个量级。
 * 所以默认**关**：它只配当「已排除更大怀疑对象之后」的收尾优化，外加留一个可复用的
 * 启动任务打点器。真正的重活不在这里（见下方 NOTES）。
 *
 * <h3>NOTES：12s 到底花在哪（下一步该查的方向）</h3>
 * 任务链只覆盖启动前 ~270ms，剩下 ~11.7s 在 `startLaunch` 之后 / `F()` 之前：
 * `InterfaceAppImpl.init` 的 native 对象树、`onDemand` 模块装配、`NewMapActivity.o()` 里的
 * presenter + AJX3 注册，以及 `F()` 里 `ViewStub.inflate()` 建 `GLMapViewGroup` 到第一帧
 * 合成上屏。下一步该用同样的探针手法量这几段，而不是继续在任务表里抠。
 */
final class AmapLaunchFast {

    private AmapLaunchFast() {}

    // ════════════════════════════════════════════════════ 目标类

    /** 实测确认的全限定名（classes.dex file_id=0）。后面几个是版本漂移时的兜底候选。 */
    private static final String[] CANDIDATES = {
            "com.autonavi.minimap.lite.AmapLaunchTaskScheduler",
            "com.amap.bundle.launch.AmapLaunchTaskScheduler",
            "com.autonavi.bundle.launch.AmapLaunchTaskScheduler",
            "com.amap.bundle.launch.LaunchTaskScheduler",
    };

    private static final String CLS_NEW_MAP = "com.autonavi.map.activity.NewMapActivity";

    // ════════════════════════════════════════════════════ 延迟白名单

    /**
     * 默认延后的任务。判定标准只有一条：
     * <b>能从任务名直接断定它不参与「Application 同步链」也不参与「地图首帧渲染」</b>。
     * 拿不准的一律不延 —— 延错一个（后续同步代码要读它准备好的数据）就是 NPE，
     * 现场表现是「开发者自己也不知道为什么定位/搜索坏了」。
     *
     * <p>★ 与最初建议清单的差异（少延 4 个）：去掉 INIT_QT / INIT_QS_AI / INIT_HOT_FIX /
     * INIT_GD_PLUGIN。这 4 个要么名字看不出语义，要么可能持有后续同步代码要用的句柄
     * （插件框架 / 热修 dex）。要试，用 {@code debug.mapclean.launch_fast.list} 手工加，
     * 别放进默认。
     */
    private static final String[] DEFER_DEFAULT = {
            // WebView 数据目录 / 内核预热。只有 H5 页和内嵌页要用，首帧是原生 GLSurface。
            "INIT_WEBVIEW",
            // 搜索模块。搜索页才用；首页搜索框是原生控件，不吃这个初始化。
            "INIT_SEARCH",
            // ★ INIT_LOCATION 绝对不能延 —— 实测崩溃，硬依赖：
            //   java.lang.NullPointerException
            //     at com.autonavi.minimap.lite.SimpleLocationProviderImpl.getLatestLocation(SourceFile:22)
            //     at com.autonavi.map.activity.NewMapActivity.F(SourceFile:103)      ← F() = loadMap
            //     at AmapLaunchFast$2.intercept(AmapLaunchFast.java:227)             ← loadMap 后的 hook
            //     at NewMapActivity$d.onQsLoadComplete
            //     at MapHomeService.disPatchQsLoadComplete
            //   loadMap 里**同步**取「最近一次定位」，定位服务没初始化就 NPE，整个进程崩。
            //   这条不是「晚几百毫秒」的问题，是「根本没得等」。
            // 音频播放器（导航播报 / 语音）。首帧不播报。
            "INIT_MEDIA",
            // 城市信息（逆地理缓存）。地图渲染不依赖行政区划缓存。
            "INIT_CITY_INFO",
            // 计步传感器注册。与地图渲染零关系。
            "INIT_STEP_COUNTER",
            // BootBizDataPreloader.prepare —— 首页业务卡片数据，首帧之后才画。
            "INIT_BIZ_PRELOAD",
            // 名字本身就叫「异步预加载」，最安全的一条。
            "PRELOAD_ASYNC",
            // 启动 UI 的**预**加载；真正的 inflate 仍按需发生，预加载晚做只是少一次复用。
            "PRELOAD_LAUNCH_UI",
    };

    /**
     * 重放必须回主线程的任务。
     *
     * <p>为什么：这些任务在主线程跑时能拿到的东西，后台线程拿不到 —— WebView 的 Looper
     * 归属、View inflate 的线程亲和、MediaPlayer 的主线程语义。放后台大概率不是「慢一点」
     * 而是「抛异常」，所以单独点名回主线程逐条 post。
     */
    private static final Set<String> MAIN_THREAD_REPLAY = new HashSet<String>(Arrays.asList(
            "INIT_WEBVIEW", "PRELOAD_LAUNCH_UI", "INIT_MEDIA"));

    // ════════════════════════════════════════════════════ 状态

    private static final Object LOCK = new Object();

    /** 待重放队列（TaskKey 实例，按被跳过的原始顺序）。 */
    private static final List<Object> QUEUE = new ArrayList<Object>();
    /** 已入队的任务名，防同 key 被 b() 调两次时重复入队。 */
    private static final Set<String> ENQUEUED = new HashSet<String>();
    /** 已重试过的名字（重试判定用，防死循环）。 */
    private static final Set<String> RETRIED = new HashSet<String>();

    private static final AtomicBoolean DRAINED = new AtomicBoolean(false);
    private static final AtomicInteger DEFER_LOGGED = new AtomicInteger();
    private static final AtomicInteger REPLAYED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();
    private static final AtomicInteger PROBE_LOGGED = new AtomicInteger();
    private static final AtomicInteger PROBE_HITS = new AtomicInteger();

    private static volatile long t0;
    private static volatile Method bMethod;
    private static volatile Set<String> deferSet = new HashSet<String>();
    private static volatile boolean fastOn;
    private static volatile boolean probeOn;
    private static volatile int deferred;

    // ════════════════════════════════════════════════════ 安装

    static void install(final ClassLoader cl) {
        try {
            t0 = SystemClock.elapsedRealtime();
            fastOn = flag("debug.mapclean.launch_fast", false);
            probeOn = flag("debug.mapclean.launch_probe", true);
            deferSet = resolveDeferSet();
            if (!fastOn && !probeOn) {
                H.log(Log.INFO, MainHook.TAG, "event=launch_fast_skip reason=both_off");
                return;
            }

            Class<?> scheduler = null;
            String used = null;
            for (String name : CANDIDATES) {
                Class<?> c = H.cls(cl, name);
                if (c != null) { scheduler = c; used = name; break; }
            }
            if (scheduler == null) {
                H.log(Log.WARN, MainHook.TAG,
                        "event=launch_fast_bypass reason=class_not_found tried=" + CANDIDATES.length);
                return;
            }

            // 精确找 b(...TaskKey...) 这一个重载：不能按名字全挂，否则同名的其它 b() 白担一层开销。
            Method target = null;
            Class<?> keyParam = null;
            for (Method m : scheduler.getDeclaredMethods()) {
                if (!"b".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) continue;
                if (!ps[0].getName().toLowerCase(Locale.US).contains("taskkey")) continue;
                target = m;
                keyParam = ps[0];
                break;
            }
            if (target == null) {
                H.log(Log.WARN, MainHook.TAG,
                        "event=launch_fast_bypass reason=no_taskkey_overload class=" + used);
                return;
            }
            target.setAccessible(true);
            bMethod = target;

            H.hookSig(scheduler, "b", "amap_launch_task", HOOK, keyParam);
            H.log(Log.INFO, MainHook.TAG, "event=launch_fast_install class=" + used
                    + " defer=" + (fastOn ? deferSet.size() : 0)
                    + " tasks=" + join(deferSet)
                    + " probe=" + (probeOn ? 1 : 0));

            if (fastOn) armReplayTriggers(cl);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=launch_fast_bypass reason=install_fail", t);
        }
    }

    /**
     * 三重触发器，任何一个先到都能把队列排空。少一重就可能出现「任务永远不跑」——
     * 那比不优化严重得多。
     *
     * <ol>
     *   <li>{@code NewMapActivity.postMapFirstFrameMsg()} —— 地图首帧消息，最准；</li>
     *   <li>{@code NewMapActivity.F()}（=loadMap）之后 1.2s —— 首帧消息没来时的近路；</li>
     *   <li>安装后 4s —— 压根没走到地图（比如 scheme 直启子页）时的兜底。</li>
     * </ol>
     */
    private static void armReplayTriggers(ClassLoader cl) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { drain("timeout4s"); }
            }, 4000L);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=launch_fast_no_timer", t);
        }

        try {
            Class<?> act = H.cls(cl, CLS_NEW_MAP);
            if (act == null) {
                H.log(Log.WARN, MainHook.TAG, "event=launch_fast_bypass reason=no_newmap_class");
                return;
            }
            // loadMap 之后给渲染让路 1.2s，再重放。
            H.hookSig(act, "F", "amap_launch_after_loadmap", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() { drain("loadMap+1200"); }
                        }, 1200L);
                    } catch (Throwable ignored) { }
                    return r;
                }
            });
            // 首帧消息：最理想的时机。
            H.hookSig(act, "postMapFirstFrameMsg", "amap_launch_first_frame",
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try { drain("first_frame"); } catch (Throwable ignored) { }
                    return r;
                }
            });
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=launch_fast_trigger_fail", t);
        }
    }

    // ════════════════════════════════════════════════════ Hook

    private static final XposedInterface.Hooker HOOK = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object key = null;
            String name = null;
            try {
                key = chain.getArg(0);
                name = nameOf(key);
            } catch (Throwable ignored) { }

            // ── 判定：能延就延 ─────────────────────────────────────────
            if (fastOn && key != null && name != null && !DRAINED.get() && deferSet.contains(name)) {
                boolean queued = false;
                synchronized (LOCK) {
                    if (!DRAINED.get() && ENQUEUED.add(name)) {
                        QUEUE.add(key);
                        deferred = QUEUE.size();
                        queued = true;
                    }
                }
                if (queued) {
                    int n = DEFER_LOGGED.incrementAndGet();
                    if (n <= 40) {
                        H.log("event=launch_task_defer name=" + name + " ms=" + since() + " n=" + n);
                    }
                    return null;   // 返回 void ⇒ 不 proceed 即「本次不执行」，已入队
                }
                // 已在队列里（同 key 第二次调用）⇒ 照常执行，避免丢语义
            }

            // ── 计时探针（只观察，不动行为）─────────────────────────────
            if (!probeOn) return chain.proceed();
            long s = System.nanoTime();
            try {
                return chain.proceed();
            } finally {
                try {
                    long ms = (System.nanoTime() - s) / 1000000L;
                    int hits = PROBE_HITS.incrementAndGet();
                    if (ms >= 0L && PROBE_LOGGED.get() < 60) {
                        PROBE_LOGGED.incrementAndGet();
                        H.log("event=launch_task name=" + name + " ms=" + ms + " seq=" + hits);
                    }
                } catch (Throwable ignored) { }
            }
        }
    };

    // ════════════════════════════════════════════════════ 重放

    /** 只排空一次；后到的触发器直接返回。 */
    private static void drain(final String why) {
        if (!DRAINED.compareAndSet(false, true)) return;
        List<Object> work;
        synchronized (LOCK) {
            work = new ArrayList<Object>(QUEUE);
            QUEUE.clear();
        }
        int n = work.size();
        H.log("event=launch_fast_drain trigger=" + why + " n=" + n + " ms=" + since());
        if (n == 0) { done(); return; }
        try {
            Thread th = new Thread(new Runnable() {
                @Override public void run() { replayAll(work); }
            }, "mapclean-launch-replay");
            th.setDaemon(true);
            th.setPriority(Thread.MIN_PRIORITY);
            th.start();
        } catch (Throwable t) {
            // 连线程都起不来 ⇒ 逐条回主线程，宁慢不丢
            H.log(Log.WARN, MainHook.TAG, "event=launch_fast_thread_fail", t);
            for (Object k : work) postMain(k, nameOf(k), 0L);
            done();
        }
    }

    private static void replayAll(List<Object> work) {
        for (int i = 0; i < work.size(); i++) {
            Object key = work.get(i);
            String name = nameOf(key);
            if (MAIN_THREAD_REPLAY.contains(name)) {
                // 逐条 post，间隔 40ms；绝不一次性全塞（否则等于把卡顿原样搬回主线程）
                postMain(key, name, 40L * (i + 1));
            } else {
                invoke(key, name);
                sleep(25L);
            }
        }
        done();
    }

    private static void postMain(final Object key, final String name, long delay) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { invoke(key, name); }
            }, delay);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=launch_task_replay_fail name=" + name
                    + " reason=post_fail");
            invoke(key, name);
        }
    }

    /** 调一次 {@code b(TaskKey)}。失败先回主线程重试一次，再失败才记 failed（绝不静默吞）。 */
    private static void invoke(Object key, String name) {
        Method m = bMethod;
        if (m == null) { FAILED.incrementAndGet(); return; }
        long s = SystemClock.elapsedRealtime();
        try {
            m.invoke(null, key);
            REPLAYED.incrementAndGet();
            H.log("event=launch_task_replay name=" + name + " ms=" + since()
                    + " cost=" + (SystemClock.elapsedRealtime() - s));
            return;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=launch_task_replay_error name=" + name
                    + " err=" + t);
        }
        if (name != null && RETRIED.add(name)) {
            postMain(key, name, 0L);
            return;
        }
        FAILED.incrementAndGet();
    }

    private static void done() {
        H.log("event=launch_fast_done deferred=" + deferred + " replayed=" + REPLAYED.get()
                + " failed=" + FAILED.get() + " ms=" + since());
    }

    // ════════════════════════════════════════════════════ 工具

    private static String nameOf(Object key) {
        try {
            if (key == null) return null;
            if (key instanceof Enum) return ((Enum<?>) key).name();
            return String.valueOf(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long since() {
        long s = t0;
        return s == 0L ? 0L : (SystemClock.elapsedRealtime() - s);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) { }
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 灰度入口：{@code debug.mapclean.launch_fast.list} 逗号分隔覆盖默认集合。
     * 例：只想试两个最重的 ⇒ {@code setprop debug.mapclean.launch_fast.list "INIT_WEBVIEW,INIT_SEARCH"}。
     */
    private static Set<String> resolveDeferSet() {
        Set<String> s = new HashSet<String>();
        String over = prop("debug.mapclean.launch_fast.list");
        if (over != null && over.trim().length() > 0) {
            for (String part : over.split(",")) {
                String v = part.trim().toUpperCase(Locale.US);
                if (v.length() > 0) s.add(v);
            }
            if (!s.isEmpty()) {
                H.log(Log.INFO, MainHook.TAG, "event=launch_fast_list_override n=" + s.size()
                        + " tasks=" + join(s));
                return s;
            }
        }
        s.addAll(Arrays.asList(DEFER_DEFAULT));
        return s;
    }

    private static boolean flag(String key, boolean def) {
        String v = prop(key);
        if (v == null || v.trim().length() == 0) return def;
        v = v.trim().toLowerCase(Locale.US);
        return !("0".equals(v) || "false".equals(v) || "off".equals(v) || "no".equals(v));
    }

    /**
     * 读系统属性。{@code android.os.SystemProperties} 是 @hide，公开 SDK 里没有，
     * 只能反射 —— 直接 import 的话 {@code javac --release 8 android.jar} 编译不过。
     */
    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            get.setAccessible(true);
            Object v = get.invoke(null, key, null);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
