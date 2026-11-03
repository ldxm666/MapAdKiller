package io.github.ldxm666.tmapclean;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * 腾讯地图 Hook 侧配置读取。
 *
 * <p>存储 = LSPosed RemotePreferences（group {@link #GROUP}，与模块设置页共用同一张表）。
 *
 * <p><b>失效安全</b>：任何异常 / 缺省一律返回 <b>true（可见）</b>。
 * 也就是说模块挂不上、配置读不到、腾讯改版导致规则全 miss 时，
 * 腾讯地图的界面保持原样，不会出现"被清空到没法用"。
 */
public final class Cfg {

    /** RemotePreferences group 名（App 侧设置页用同一个） */
    public static final String GROUP = "tmapclean_config";

    /** 诊断日志开关（唯一默认 false 的键） */
    public static final String K_DEBUG = "debug_log";

    /** 真机取证期临时强制开日志。交付版必须为 false。 */
    public static final boolean FORCE_DEBUG = false;

    /**
     * 宿主本地后端（{@code /data/data/com.tencent.map/shared_prefs/tmapclean_config.xml}）。
     *
     * <p>2.3.0 新增，与百度侧同因同治：2.2.8 及以前只有
     * {@code H.module.getRemotePreferences()} 一条路 —— hook 侧是只读代理
     * （{@code commit()} 抛 {@code UnsupportedOperationException}），内置版（LSPatch）的 remote
     * 库又会 {@code SQLiteCantOpenDatabaseException}（{@code commit()} 返回 true 但读不回来）。
     * 结果就是面板恒显「只读 · 服务未连接」、每个开关都写不进去。
     */
    private static volatile SharedPreferences localCache;
    private static volatile SharedPreferences remoteCache;
    private static volatile SharedPreferences resolved;
    private static volatile long resolveAt;
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


    /** 无可用后端（宿主 Context 还没就绪，稍后重试）。 */
    public static final int BACKEND_NONE = 0;
    /** 宿主本地 SharedPreferences（内置版的正常状态）。 */
    public static final int BACKEND_LOCAL = 1;
    /** 框架远程 prefs（LSPosed 模块版）。 */
    public static final int BACKEND_REMOTE = 2;

    /** 定案失败后的重试间隔（{@code visible()} 是热路径，不能每帧跑一遍反射）。 */
    private static final long RESOLVE_RETRY_MS = 500;

    private static volatile java.util.Map<String, ?> snapshot = java.util.Collections.emptyMap();
    private static volatile long expires;
    private static volatile long dbgAt;
    private static volatile boolean dbgVal;

    private static final SharedPreferences.OnSharedPreferenceChangeListener listener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    invalidate();
                    MainHook.onConfigChanged(key);
                }
            };

    private Cfg() {}

    public static void invalidate() {
        expires = 0;
        dbgAt = 0;
        // ★★ 2.3.0：也要丢掉「已定案的读后端」。
        // 否则会出现：写进了宿主本地（{@code cfg_write_recover via=local}），
        // 但面板重渲染时 {@link #prefs()} 还返回当初定案的那个 remote，
        // 于是读出来还是旧值 ⇒ 用户看到「关掉又自己开了」。
        resetBackend();
    }

    /** 当前配置后端：本地 / 远程 / 无。 */
    public static int backend() {
        prefs();
        int b = backend;
        return b < 0 ? BACKEND_NONE : b;
    }

    /** 宿主本地 prefs（永久缓存）。 */
    private static SharedPreferences local() {
        SharedPreferences p = localCache;
        if (p != null) return p;
        synchronized (Cfg.class) {
            p = localCache;
            if (p == null) {
                p = io.github.ldxm666.mapclean.HostPrefs.local(GROUP);
                if (p != null) {
                    localCache = p;
                    try { p.registerOnSharedPreferenceChangeListener(listener); }
                    catch (Throwable ignored) {}
                }
            }
        }
        return p;
    }

    /**
     * 框架远程 prefs。
     *
     * <p>⚠ <b>不缓存 null 的失败态</b>：2.2.8 以前把 {@code null} 永久写进 {@code cached}，
     * 只要首次调用赶在框架初始化之前，整个进程就再也拿不到配置。
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
                    try { p.registerOnSharedPreferenceChangeListener(listener); }
                    catch (Throwable ignored) {}
                }
            }
        }
        return p;
    }

    /**
     * 读路径使用的 prefs，同时定案 {@link #backend}。
     *
     * <p>内置版本地优先，模块版远程优先；两个都拿不到不永久定案，500ms 后重试。
     */
    /**
     * 读路径使用的 prefs，同时定案 {@link #backend}。
     *
     * <p>★★ 2.3.0：**只有首选后端才允许写进 {@link #resolved}**，
     * 回落后端只借用一次。
     *
     * <p>这是百度侧同一个 bug 的逐字同源修复（已在百度内置版上真机验证）：
     * hook 是在 {@code onPackageLoaded} 装上的，那时宿主 {@code Application} 还没创建
     * ⇒ {@code HostPrefs.hostContext()} 四条路全空 ⇒ {@link #local()} 为 null。
     * 旧代码于是拿 LSPatch 的只读 remote 顶上并**把它定案**；
     * 那份 remote 只有几个键，用户的真值全在
     * {@code /data/data/com.tencent.map/shared_prefs/tmapclean_config.xml} ⇒
     * 从那一刻起整个进程每个键 {@code present=false} ⇒ 全部落缺省 true
     * ⇒「关掉重启又自己开了」。
     *
     * <p>腾讯内置版必然复现，所以这里提前修掉。
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
            SharedPreferences preferred = embedded ? l : r;
            SharedPreferences spare = embedded ? r : l;
            int preferredBackend = embedded ? BACKEND_LOCAL : BACKEND_REMOTE;
            int spareBackend = embedded ? BACKEND_REMOTE : BACKEND_LOCAL;

            if (preferred != null) {
                boolean wasFallback = fallback != null;
                resolved = preferred;
                backend = preferredBackend;
                fallback = null;
                // 从「回落态」升级到首选后端：快照是照回落态算的，必须一起作废。
                if (wasFallback) {
                    expires = 0;
                    H.log("event=cfg_backend_upgraded pid=" + android.os.Process.myPid()
                            + " backend=" + backend);
                }
                return preferred;
            }

            // 首选后端还没就绪（典型：内置版启动瞬间宿主 Context 未创建）。
            // 借用回落后端读这一次，但**绝不写进 resolved**。
            fallback = spare;
            backend = spare != null ? spareBackend : -1;
            return spare;
        }
    }

    /** 回落后端的临时引用：只借读，不定案。 */
    private static volatile SharedPreferences fallback;

    /** 配置来源变了：丢掉定案，下次重新选后端。 */
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
     * 写一个布尔键。内置版以宿主本地为准，remote 只做 best-effort 镜像；模块版反过来。
     *
     * @return 「至少一个后端 commit 成功」
     */
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

    /**
     * 缺省值走 {@link Spec#defaultVisible(String)} —— 与设置页、内置 JSON 同源。
     *
     * <p>★ 2.3.1：以前这里写死 {@code true}。腾讯那份 JSON 里 {@code tab_*} 根本不存在、
     * {@code ui_seek_chips} 等又是 {@code null}（不写），于是干净安装的所有键都 absent
     * ⇒ 全部回落到 {@code true} ⇒ 底栏四个 tab、首页 chip 行原样保留。
     * 现在「没设置过」不再等于「显示」：推广/运营位按 Spec 的默认表隐藏，功能位照旧显示。
     *
     * <p>循环依赖检查：{@link Spec#defaultVisible} 只引用 {@code Cfg.K_DEBUG} 这类
     * <b>编译期字符串常量</b>，不回调 {@code visible()}，因此不存在递归。
     */
    public static boolean visible(String key) {
        return visible(key, Spec.defaultVisible(key));
    }

    public static boolean visible(String key, boolean def) {
        if (key == null) return def;
        try {
            if (prefs() == null) return def;
            Object value = values().get(key);
            return value instanceof Boolean ? ((Boolean) value).booleanValue() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 是否显示某个工具（工具 id = 服务端下发的 {@code Tool.name}）。
     *
     * <p>用户显式设置过的按用户的来；<b>没设置过的走 {@link Spec#defaultToolVisible}</b>
     * —— 首页宫格那 15 格默认显示，其余默认隐藏。
     *
     * <p>⚠ 这里以前写死 {@code return true}（"未设置一律显示"），与面板侧的缺省脱节：
     * 面板要表达"只有首页那几格是开的"就无从表达。两处缺省现在同源。
     *
     * <p>失效安全：{@code filterData()} 只对**认得出 id** 的格子问这个问题，
     * 取不到名字的格子直接保留；另外它还有"清空即原样放行"的兜底。
     */
    public static boolean toolVisible(String toolId) {
        if (toolId == null || toolId.length() == 0) return true;   // 认不出 → 保留
        Object value = values().get(Spec.K_TOOL + toolId);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return Spec.defaultToolVisible(toolId);
    }

    /**
     * 该键是否被用户<b>显式</b>设置过（区别于"按缺省值"）。
     *
     * <p>给"默认隐藏会不会把宫格清空"这道兜底用：只有用户真的逐格关过，
     * 才允许宫格被清空。
     */
    public static boolean explicit(String key) {
        if (key == null) return false;
        try {
            return values().containsKey(key);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 是否显示某个底部标签（页名，例如 homePage）；总开关由调用方另行判断。
     *
     * <p>★ 2.3.1：缺省值改走 {@link Spec#defaultVisible}。以前写死 {@code true}，
     * 与面板的默认值脱节 —— 面板显示「探索/行程」是关的，钩子却按开处理（或反过来）。
     */
    public static boolean tabVisible(String pageName) {
        return visible(Spec.K_TAB + pageName);
    }

    /** 热点路径 2 秒 TTL 缓存，避免每帧都走一次 IPC */
    public static boolean debug() {
        if (FORCE_DEBUG) return true;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - dbgAt > 2000) {
            boolean v = false;
            try {
                v = Boolean.TRUE.equals(values().get(K_DEBUG));
            } catch (Throwable ignored) {}
            dbgVal = v;
            dbgAt = now;
        }
        return dbgVal;
    }

    /**
     * 设置页写入的防伪 token（App 侧可写、hook 侧只读 —— 见 {@link Report}）。
     * hook 侧回报时带上它，设置页据此过滤伪造广播。
     */
    public static String token() {
        try {
            Object value = values().get("app_token");
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 说明：libxposed API 102 在 hook 侧返回的 RemotePreferences 是<b>只读</b>实现，
     * 真机实测 {@code edit().commit()} 抛 UnsupportedOperationException: Read only implementation。
     * 因此 hook 侧一律不回写配置；诊断与「学到的工具清单」走 {@link Report} 的广播通道。
     */
    public static void log(String s) {
        H.log(Log.INFO, MainHook.TAG, s);
    }

    private static java.util.Map<String, ?> values() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= expires) {
            synchronized (Cfg.class) {
                if (now >= expires) {
                    SharedPreferences p = prefs();
                    if (p != null) {
                        try {
                            snapshot = java.util.Collections.unmodifiableMap(
                                    new java.util.HashMap<String, Object>(p.getAll()));
                        } catch (Throwable ignored) {}
                    }
                    expires = now + 2000;
                }
            }
        }
        return snapshot;
    }
}
