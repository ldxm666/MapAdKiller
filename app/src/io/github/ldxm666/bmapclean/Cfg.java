package io.github.ldxm666.bmapclean;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * Hook 侧配置读取。
 *
 * 存储 = LSPosed RemotePreferences（同名 group 与模块 App 侧 XposedService 共享）。
 *
 * 失效安全：任何异常/缺省一律返回 **true（可见）**。
 * 也就是说模块挂不上、配置读不到、目标改版导致规则全 miss 时，
 * 百度地图的界面保持原样，不会出现"被清空到没法用"。
 */
public final class Cfg {

    /** RemotePreferences group 名（App 侧 CategoryActivity 用同一个） */
    public static final String GROUP = "bmapclean_config";

    /** 诊断日志开关（唯一默认 false 的键） */
    public static final String K_DEBUG = "debug_log";
    /** hook 侧写回的最近一次诊断摘要 */
    public static final String K_DIAG = "diag_last";

    /**
     * 真机取证期临时强制开日志（视图树 dump 走这里）。
     * 交付版必须为 false；probe 构建才临时打开。
     */
    public static final boolean FORCE_DEBUG = false;

    /**
     * 宿主本地后端（{@code /data/data/com.baidu.BaiduMap/shared_prefs/bmapclean_config.xml}）。
     *
     * <p><b>2.3.0 新增，这把「只读」真正修掉了。</b> 2.2.8 及以前 {@link #prefs()} 只有
     * {@code H.module.getRemotePreferences()} 一条路：
     * <ul>
     *   <li>hook 侧的 RemotePreferences 是**只读代理**（{@code commit()} 抛
     *       {@code UnsupportedOperationException}）；</li>
     *   <li>内置版（LSPatch）的 remote 库会 {@code SQLiteCantOpenDatabaseException}，
     *       {@code commit()} 返回 true 但读不回来。</li>
     * </ul>
     * 两条都让「开关拨了不生效」，面板头部于是恒显「只读 · 无法写入配置」。
     * 宿主自己的 SharedPreferences 永远可写，且 hook 侧与面板侧在**同一个进程**里读同一个文件，
     * 所以内置版以它为准；模块版（LSPosed）仍以 remote 为准，不受影响。
     */
    private static volatile SharedPreferences localCache;
    private static volatile SharedPreferences remoteCache;
    private static volatile java.util.Map<String, ?> snapshot = java.util.Collections.emptyMap();
    private static volatile long expires;
    private static final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (p, key) -> { expires = 0; refreshFlags(); };
    private static volatile long dbgAt;
    private static volatile boolean dbgVal;

    /** 后端标记：-1 未定案 / 0 无 / 1 本地 / 2 远程。 */
    public static final int BACKEND_NONE = 0;
    /** 宿主本地 SharedPreferences（内置版的正常状态）。 */
    public static final int BACKEND_LOCAL = 1;
    /** 框架远程 prefs（LSPosed 模块版）。 */
    public static final int BACKEND_REMOTE = 2;
    private static volatile int backend = -1;

    /**
     * 本进程是否处于**免 root 内置版**。
     *
     * <p>★ 2.3.0 修复「内置版配置不保存」。判据用
     * <b>框架名</b> {@code XposedModule#getFrameworkName()} —— LSPatch 会报 {@code LSPatch}，
     * LSPosed 会报 {@code LSPosed}/类似名。这是两种形态**唯一可靠的区分点**：
     * <ul>
     *   <li>{@code HostInfo.isEmbedded()} 在百度变体里是**桩**，早期返回 false；</li>
     *   <li>{@code mapclean.App.embedded()} 要等 {@code attachEmbedded} 之后才为 true，
     *       而面板第一次写配置就发生在那之前；</li>
     *   <li>看包名不行：模块版的 hook 侧也跑在百度进程里。</li>
     * </ul>
     *
     * <p>判错的代价就是用户反馈的那个：走服务通道写 LSPatch 的坏库，
     * {@code commit()} 返回 true 但读不回来 ⇒ 关掉切个页面又自己开了。
     */
    static boolean embedded() {
        // ★★ 2.3.0 修正：**只缓存「true」，不缓存 false**。
        //
        // 上一版把第一次算出的结果（包括 false）永久写进 {@code embState}。
        // 而第一次调用很可能发生在 {@code H.module} 赋值之前（静态初始化、
        // 面板提前渲染）⇒ 结果被钉死为 false ⇒ 后面每一次写配置都走服务通道
        // 写 LSPatch 的坏库 ⇒ commit 返回 true 但读不回来 ⇒ 「关掉切页面又自己开了」。
        //
        // 现在：true 才缓存；false 每次重算（一次字符串比对，热路径也无所谓），
        // 直到 {@code H.module} 就位后给出确定答案。
        if (embState == 1) return true;
        boolean v = false;
        String fw = null;
        try {
            if (H.module != null) {
                fw = String.valueOf(H.module.getFrameworkName());
                v = fw.toLowerCase(java.util.Locale.US).contains("lspatch");
            }
        } catch (Throwable ignored) { }
        if (!v) {
            try { v = io.github.ldxm666.mapclean.HostPrefs.embedded(); } catch (Throwable ignored) { }
        }
        if (v) {
            embState = 1;
            H.log("event=cfg_embedded v=true framework=" + fw);
        } else if (fw != null && !fwLogged) {
            fwLogged = true;
            H.log("event=cfg_embedded v=false framework=" + fw);
        }
        return v;
    }

    /** 负面结论只打一次日志，不刷屏。 */
    private static volatile boolean fwLogged;

    /** -1 未定案 / 0 模块版 / 1 内置版。 */
    private static volatile int embState = -1;


    private Cfg() {}

    // ══════════════════════════════════════════════════════════════════════════
    // 省电开关（命名与高德 mapadkiller/Config 对齐）
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 语义统一为「true = 已拦截」。默认 true（读不到配置也按拦截处理）。
    // 全部走**安装期门控**（见 BaiduPower.install）：关掉 ⇒ 连 hook 都不装 ⇒ 零性能开销。
    // 代价：改这些开关要**强制停止并重开百度地图**才生效。

    /** 推送长连接安全档：不建连接 + 不处理推送消息。 */
    public static final String K_PUSH_OFF = "push_off";
    /** 推送长连接硬档：掐断连接/线程的真实创建链。 */
    public static final String K_PUSH_HARD_OFF = "push_hard_off";
    /** 推送服务激进档：通道服务起来即退出。 */
    public static final String K_PUSH_KILL_OFF = "push_kill_off";
    /** 后台常驻服务（定位/云控/统计等自启动 Service）。 */
    public static final String K_BG_SERVICE_OFF = "subproc_off";
    /** 定时任务（AlarmManager / JobScheduler / WorkManager）。 */
    public static final String K_TIMER_OFF = "subproc_job_off";
    /** 桌面小组件刷新。 */
    public static final String K_WIDGET_OFF = "subproc_widget_off";
    /** 第三方埋点/统计 SDK。 */
    public static final String K_TRACK_OFF = "track_off";

    /** 省电键全集（「一键省电」用）。 */
    public static final String[] POWER_KEYS = {
            K_PUSH_HARD_OFF, K_PUSH_OFF, K_PUSH_KILL_OFF,
            K_BG_SERVICE_OFF, K_TIMER_OFF, K_WIDGET_OFF, K_TRACK_OFF
    };

    // ── 零成本开关：常驻 hook 的拦截器里只读一个 volatile ──────────────────────
    private static final java.util.List<Flag> FLAGS =
            new java.util.concurrent.CopyOnWriteArrayList<Flag>();

    /** 一个可被 prefs 变更刷新的布尔开关。 */
    public abstract static class Flag {
        private volatile boolean value;
        private volatile boolean resolved;
        protected Flag() { FLAGS.add(this); }
        protected abstract boolean compute();
        public final boolean on() {
            if (!resolved) resolve();
            return value;
        }
        public final void resolve() {
            boolean v;
            try { v = compute(); } catch (Throwable t) { v = true; }
            value = v;
            resolved = true;
        }
    }

    public static Flag flag(final String key) {
        return new Flag() {
            @Override protected boolean compute() { return Cfg.visible(key); }
        };
    }

    private static void refreshFlags() {
        for (Flag f : FLAGS) f.resolve();
    }

    /**
     * 安装期门控：开关关掉时**根本不装**对应 hook（"关闭 = 不加载、不消耗性能"）。
     * 读不到配置按 true（已拦截）处理。
     */
    public static boolean installGate(String key) {
        try { return visible(key); } catch (Throwable t) { return true; }
    }

    /**
     * 「一键省电」：把 {@link #POWER_KEYS} 全部写成 true。
     *
     * @return 实际写入的键数；0 = 当前没有可写后端（面板会提示"只读"）
     */
    public static int enableAllPower() {
        SharedPreferences p = prefs();
        if (p == null) return 0;
        try {
            SharedPreferences.Editor e = p.edit();
            for (String k : POWER_KEYS) e.putBoolean(k, true);
            if (!e.commit()) return 0;
            expires = 0;
            refreshFlags();
            return POWER_KEYS.length;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 当前配置后端 —— {@link #BACKEND_LOCAL}（内置版）/ {@link #BACKEND_REMOTE}（模块版）
     * / {@link #BACKEND_NONE}（宿主 Context 还没就绪）。
     */
    public static int backend() {
        prefs();
        int b = backend;
        return b < 0 ? BACKEND_NONE : b;
    }

    /** 配置变了：丢快照 + 重算所有 {@link Flag}。面板写完必须调一次。 */
    public static void invalidate() {
        expires = 0;
        dbgAt = 0;
        // ★★ 2.3.0：也要丢掉「已定案的读后端」。
        // 否则会出现：写进了宿主本地（{@code cfg_write_recover via=local}），
        // 但面板重渲染时 {@link #prefs()} 还返回当初定案的那个 remote，
        // 于是读出来还是旧值 ⇒ 用户看到「关掉又自己开了」。
        resetBackend();
        refreshFlags();
    }

    /** 宿主本地 prefs（永久缓存，文件句柄本身就是稳定的）。 */
    private static SharedPreferences local() {
        SharedPreferences p = localCache;
        if (p != null) return p;
        synchronized (Cfg.class) {
            p = localCache;
            if (p == null) {
                p = io.github.ldxm666.mapclean.HostPrefs.local(GROUP);
                if (p != null) {
                    localCache = p;
                    try { p.registerOnSharedPreferenceChangeListener(listener); } catch (Throwable ignored) {}
                }
            }
        }
        return p;
    }

    /**
     * 框架远程 prefs。
     *
     * <p>⚠ <b>不缓存 null 的失败态</b>：2.2.8 以前把 {@code null} 永久写进 {@code cached}，
     * 只要第一次调用恰好赶在框架初始化之前（或 remote 库恰好抛异常），整个进程就再也拿不到配置。
     * 现在失败只是「这次没有」，下次 {@link io.github.ldxm666.mapclean.HostPrefs#mayProbe()}
     * 放行后重试。
     */
    private static SharedPreferences remote() {
        SharedPreferences p = remoteCache;
        if (p != null) return p;
        synchronized (Cfg.class) {
            p = remoteCache;
            if (p == null) {
                try {
                    p = H.module == null ? null : H.module.getRemotePreferences(GROUP);
                } catch (Throwable t) {
                    p = null;
                }
                if (p != null) {
                    remoteCache = p;
                    try { p.registerOnSharedPreferenceChangeListener(listener); } catch (Throwable ignored) {}
                }
            }
        }
        return p;
    }

    /**
     * 读路径使用的 prefs，同时**定案** {@link #backend}。
     *
     * <p>内置版：本地优先（remote 在 LSPatch 上不可靠）。
     * 模块版：远程优先（LSPosed 用户的配置都在 remote 里），拿不到才退本地。
     *
     * <p>★ 两个热路径约束：
     * <ul>
     *   <li>定案后走 {@link #resolved} 的 volatile 读，**不再做任何反射**；</li>
     *   <li>拿不到时**不永久定案**，但 500ms 内不再重试 —— {@link #visible(String, boolean)}
     *       是每帧都调的热路径，不能每次都跑一遍宿主 Context 四级探测。</li>
     * </ul>
     */
    /**
     * 读路径使用的 prefs，同时**定案** {@link #backend}。
     *
     * <p>内置版：本地优先（remote 在 LSPatch 上不可靠）。
     * 模块版：远程优先（LSPosed 用户的配置都在 remote 里），拿不到才退本地。
     *
     * <p>★ 两个热路径约束：
     * <ul>
     *   <li>定案后走 {@link #resolved} 的 volatile 读，**不再做任何反射**；</li>
     *   <li>拿不到时**不永久定案**，但 500ms 内不再重试 —— {@link #visible(String, boolean)}
     *       是每帧都调的热路径，不能每次都跑一遍宿主 Context 四级探测。</li>
     * </ul>
     *
     * <p>★★ 2.3.0 真正修掉「重启后配置恢复原样」（第三次反馈）：
     * <b>只有首选后端才允许写进 {@link #resolved}</b>，回落后端只借用一次。
     *
     * <p>为什么：hook 是在 {@code onPackageLoaded} 装上的，那时宿主 {@code Application}
     * 还没创建 —— {@code HostPrefs.hostContext()} 四条路全空 ⇒ {@link #local()} 为 null。
     * 老代码 `pick = l != null ? l : r` 于是拿 LSPatch 的只读 remote 顶上，并且**把它定案**。
     * 那个 remote 里只有 3 个键（{@code map_poi} / {@code _map_label_schema} / token），
     * 用户真正的 44 个值全在 {@code /data/data/com.baidu.BaiduMap/shared_prefs/bmapclean_config.xml}。
     * 从那一刻起整个进程都读 remote ⇒ 每个键 {@code present=false} ⇒ 全部落
     * {@link Spec#defaultVisible} = true ⇒ 「关掉重启又自己开了」。
     *
     * <p>真机取证（修复前，pid=9994 主进程）：
     * <pre>
     * event=cfg_resolve embedded=true local=null remote=VectorRemotePreferences backend=2
     * event=cfg_snap    n=3 backend=2
     * event=cfg_r key=tab_打车 v=true present=false n=3 backend=2
     * </pre>
     */
    public static SharedPreferences prefs() {
        SharedPreferences p = resolved;
        if (p != null) return p;
        long now = android.os.SystemClock.uptimeMillis();
        if (resolveAt != 0 && now - resolveAt < RESOLVE_RETRY_MS) return fallback;
        synchronized (Cfg.class) {
            if (resolved != null) return resolved;
            if (resolveAt != 0 && now - resolveAt < RESOLVE_RETRY_MS) return fallback;
            resolveAt = now;

            boolean embedded = embedded();
            SharedPreferences l = local();
            SharedPreferences r = remote();
            // 首选 / 回落：内置版本地优先，模块版远程优先。
            SharedPreferences preferred = embedded ? l : r;
            SharedPreferences spare = embedded ? r : l;
            int preferredBackend = embedded ? BACKEND_LOCAL : BACKEND_REMOTE;
            int spareBackend = embedded ? BACKEND_REMOTE : BACKEND_LOCAL;

            if (preferred != null) {
                boolean wasFallback = fallback != null;
                resolved = preferred;
                backend = preferredBackend;
                fallback = null;
                // 从「回落态」升级到首选后端：快照和 Flag 全是照回落态算的，必须一起作废，
                // 否则安装期门控会把「读不到」的缺省 true 一直留到进程结束。
                if (wasFallback) {
                    expires = 0;
                    refreshFlags();
                    H.log("event=cfg_backend_upgraded pid=" + android.os.Process.myPid()
                            + " backend=" + backend);
                }
                if (RES_LOGGED.compareAndSet(false, true)) {
                    H.log("event=cfg_resolve pid=" + android.os.Process.myPid()
                            + " embedded=" + embedded
                            + " local=" + (l == null ? "null" : l.getClass().getName())
                            + " remote=" + (r == null ? "null" : r.getClass().getName())
                            + " pick=" + preferred.getClass().getName()
                            + " backend=" + backend);
                }
                return preferred;
            }

            // 首选后端还没就绪（典型：内置版启动瞬间宿主 Context 未创建）。
            // 借用回落后端读这一次，但**绝不写进 resolved** —— 下次调用必须重新探测。
            fallback = spare;
            backend = spare != null ? spareBackend : -1;
            if (RES_LOGGED.compareAndSet(false, true)) {
                H.log("event=cfg_resolve pid=" + android.os.Process.myPid()
                        + " embedded=" + embedded
                        + " local=" + (l == null ? "null" : l.getClass().getName())
                        + " remote=" + (r == null ? "null" : r.getClass().getName())
                        + " pick=" + (spare == null ? "null" : spare.getClass().getName() + " (fallback)")
                        + " backend=" + backend);
            }
            return spare;
        }
    }

    /** 每个进程只打一条「后端定案」。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RES_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 快照重建的日志预算（每进程 12 条，够看启动期与一次写后的重读）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SNAP_LOG =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 每键首次读取的日志预算（每进程 48 条）。 */
    private static final java.util.concurrent.atomic.AtomicInteger READ_LOG =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 已打过「首次读取」的键。 */
    private static final java.util.Set<String> READ_SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 已定案的读后端（定案后零反射）。 */
    private static volatile SharedPreferences resolved;

    /**
     * 首选后端未就绪时的**临时**后端（内置版=remote，模块版=local）。
     *
     * <p>只做「这一次读」的替身，不进 {@link #resolved}：首选后端一出现就立刻接管，
     * 并在接管时作废快照 + 重算 Flag。
     */
    private static volatile SharedPreferences fallback;
    /** 上次尝试定案的时间（0 = 还没试过）。 */
    private static volatile long resolveAt;

    /** 定案失败后的重试间隔：500ms 内不再跑宿主 Context 四级探测。 */
    private static final long RESOLVE_RETRY_MS = 500;

    /** 配置来源变了（换宿主 / 刚绑定服务）：丢掉定案，下次重新选后端。 */
    public static void resetBackend() {
        synchronized (Cfg.class) {
            resolved = null;
            fallback = null;
            backend = -1;
            resolveAt = 0;
            expires = 0;
        }
    }

    /**
     * 写一个布尔键。<b>内置版以宿主本地为准</b>，remote 只做 best-effort 镜像；
     * 模块版反过来。返回「至少一个后端 commit 成功」。
     *
     * <p>这就是「开关拨了不生效」的修法：从前 hook 侧一律不写（只读代理），
     * 面板侧写 remote 又被 LSPatch 的坏库吞掉。现在写的是宿主自己的 SharedPreferences，
     * 与读路径是同一个文件。
     */
    /** 写入诊断计数（每进程前 60 条），超了就不再打。 */
    private static final java.util.concurrent.atomic.AtomicInteger WLOG =
            new java.util.concurrent.atomic.AtomicInteger();

    public static boolean write(String key, boolean value) {
        boolean embedded = embedded();
        boolean okLocal = commitBool(local(), key, value);
        boolean okRemote = commitBool(remote(), key, value);
        boolean ok = embedded ? okLocal : (okRemote || okLocal);
        invalidate();

        // ★★ 回读自校验（最后一道保险）。
        // 用户反馈的「关掉切个页面又自己开了」本质是：
        // **commit 返回了 true，但下一次读不回来**。
        // 所以不能信返回值，必须用与读取完全相同的路径再读一次。
        // 不一致 ⇒ 强制改写宿主本地（永远可写）并标记成功。
        try {
            android.content.SharedPreferences rp = prefs();
            if (rp != null) {
                Object back = rp.getAll().get(key);
                boolean got = back instanceof Boolean ? ((Boolean) back).booleanValue() : !value;
                if (got != value) {
                    if (commitBool(local(), key, value)) {
                        invalidate();
                        ok = true;
                        H.log("event=cfg_write_recover key=" + key + " via=local");
                    } else {
                        H.log(android.util.Log.WARN, MainHook.TAG,
                                "event=cfg_write_lost key=" + key + " embedded=" + embedded
                                        + " local=" + okLocal + " remote=" + okRemote);
                    }
                }
            }
        } catch (Throwable ignored) { }

        // ★ 应急诊断（用户反馈「重启后没保存」但文件里值是对的 ⇒ 必须看到
        // **写的时候到底 ok 不 ok、走的哪个后端**。这条日志不受 debug_log 控制，
        // 否则用户拿不到证据。限 60 条防刷屏。
        try {
            if (WLOG.incrementAndGet() <= 60) {
                H.log("event=cfg_w key=" + key + " v=" + value + " ok=" + ok
                        + " backend=" + backend + " local=" + okLocal + " remote=" + okRemote);
            }
        } catch (Throwable ignored) { }
        return ok;
    }

    /** 写一个 int 键（语义同 {@link #write(String, boolean)}）。 */
    public static boolean writeInt(String key, int value) {
        boolean embedded = embedded();
        boolean okLocal = commitInt(local(), key, value);
        boolean okRemote = commitInt(remote(), key, value);
        boolean ok = embedded ? okLocal : (okRemote || okLocal);
        invalidate();
        return ok;
    }

    private static boolean commitBool(SharedPreferences p, String key, boolean value) {
        if (p == null) return false;
        try {
            return p.edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean commitInt(SharedPreferences p, String key, int value) {
        if (p == null) return false;
        try {
            return p.edit().putInt(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写一个字符串键（目前只有防伪 token 用）。语义同 {@link #write(String, boolean)}。 */
    public static boolean writeString(String key, String value) {
        boolean embedded = embedded();
        boolean okLocal = commitString(local(), key, value);
        boolean okRemote = commitString(remote(), key, value);
        boolean ok = embedded ? okLocal : (okRemote || okLocal);
        invalidate();
        return ok;
    }

    private static boolean commitString(SharedPreferences p, String key, String value) {
        if (p == null) return false;
        try {
            return p.edit().putString(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 缺省 true = 显示 */
    public static boolean visible(String key) {
        return visible(key, true);
    }

    /** 缺省值由调用方指定（用于默认「关」的键，例如 mine_apply） */
    public static boolean visible(String key, boolean def) {
        try {
            SharedPreferences p = prefs();
            if (p == null) {
                traceRead(key, def, false, -1);
                return def;
            }
            java.util.Map<String, ?> all = values();
            Object value = all.get(key);
            boolean v = value instanceof Boolean ? (Boolean) value : def;
            traceRead(key, v, value instanceof Boolean, all.size());
            return v;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 每键「首次读取」留一条日志（每进程 ≤48 条）。
     *
     * <p>为什么要它：{@code cfg_w} 已经证明「写进去了」，但「读回来的是不是那个值」从来没有证据。
     * 这条日志把三个数放在一起 —— 读到的值、键在快照里存不存在、快照有几个键 ——
     * 一眼就能分开三种完全不同的故障：
     * <ul>
     *   <li>{@code present=false} ⇒ 读的后端是空的（remote 坏库 / 后端定错了）；</li>
     *   <li>{@code present=true v=true} 但文件里是 false ⇒ 快照太旧或写错了文件；</li>
     *   <li>{@code n=-1} ⇒ {@code prefs()} 直接拿不到后端，读的是失效安全缺省。</li>
     * </ul>
     */
    private static void traceRead(String key, boolean v, boolean present, int n) {
        if (READ_LOG.get() >= 48) return;
        if (!READ_SEEN.add(key)) return;
        if (READ_LOG.incrementAndGet() > 48) return;
        H.log("event=cfg_r pid=" + android.os.Process.myPid()
                + " key=" + key + " v=" + v + " present=" + present
                + " n=" + n + " backend=" + backend);
    }

    /** 热点路径 2 秒 TTL 缓存，避免每帧都走一次 IPC */
    public static boolean debug() {
        if (FORCE_DEBUG) return true;
        long now = System.currentTimeMillis();
        if (now - dbgAt > 2000) {
            boolean v = false;
            try {
                SharedPreferences p = prefs();
                v = p != null && p.getBoolean(K_DEBUG, false);
            } catch (Throwable ignored) {}
            dbgVal = v;
            dbgAt = now;
        }
        return dbgVal;
    }

    /**
     * 设置页写的防伪 token（App 侧可写、hook 侧只读 —— 见 {@link Report} 的说明）。
     * hook 侧回报时带上它，设置页据此过滤伪造广播。
     */
    public static String token() {
        try {
            SharedPreferences p = prefs();
            if (p == null) return "";
            Object value = values().get("app_token");
            String s = value instanceof String ? (String) value : "";
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 说明：libxposed API 102 在 hook 侧返回的 RemotePreferences 是**只读**实现，
     * 真机实测 `edit().commit()` 抛 UnsupportedOperationException: Read only implementation。
     * 因此 hook 侧一律不回写配置；诊断结果走 {@link Report} 的广播通道。
     */
    public static void log(String s) {
        H.log(Log.INFO, MainHook.TAG, s);
    }

    private static java.util.Map<String, ?> values() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= expires) synchronized (Cfg.class) {
            if (now >= expires) {
                SharedPreferences p = prefs();
                if (p != null) try {
                    snapshot = java.util.Collections.unmodifiableMap(new java.util.HashMap<String, Object>(p.getAll()));
                    if (SNAP_LOG.get() < 12) {
                        SNAP_LOG.incrementAndGet();
                        H.log("event=cfg_snap pid=" + android.os.Process.myPid()
                                + " n=" + snapshot.size() + " backend=" + backend
                                + " src=" + p.getClass().getName());
                    }
                } catch (Throwable ignored) {}
                expires = now + 2000;
            }
        }
        return snapshot;
    }
}
