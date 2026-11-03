package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 宿主进程内的**可写**配置后端 —— 免 root 内置版唯一可靠的落盘通道。
 *
 * <h3>为什么必须有这个类</h3>
 * 百度 / 腾讯 适配层（{@code bmapclean.Cfg} / {@code tmapclean.Cfg}）在 2.2.8 及以前只有一条
 * 配置通道：{@code H.module.getRemotePreferences(group)}。这条通道在两种形态下各有致命问题：
 *
 * <ol>
 *   <li><b>hook 侧是只读代理</b>。libxposed API 102 返回的 RemotePreferences 真机实测
 *       {@code edit().commit()} 抛 {@code UnsupportedOperationException: Read only implementation}。
 *       所以 hook 侧永远写不进去（这在 {@code bmapclean.Cfg} 自己的注释里就写着）。</li>
 *   <li><b>内置版（LSPatch）的 remote 库会坏</b>。{@code databases/lspatch-xposed-remote.db}
 *       在真机上会出现 {@code SQLiteCantOpenDatabaseException}（旧 uid 占用 / 文件锁），
 *       {@code commit()} 返回 true 但下次读不回来 —— 用户看到的就是「开关拨了、重开又变回去」。</li>
 * </ol>
 *
 * 高德侧早就绕开了这两条坑（{@code mapadkiller.Config} 的 remote → local → none 三级后端 +
 * {@code CfgStore} 的本地为准）。百度 / 腾讯**从来没有拿到同等待遇**，于是：
 * <ul>
 *   <li>{@code FloatingConfig.probeVendorPrefs()} 用一次真实写入判定能不能写 ⇒ 必失败；</li>
 *   <li>面板头部显示「只读 · 无法写入配置」；</li>
 *   <li>{@code Cfg.enableAllPower()} 返回 0（= 没有可写后端）；</li>
 *   <li>所有开关拨了不生效 —— 用户原话「无法使用，状态显示只读」。</li>
 * </ul>
 *
 * <h3>这个类做什么</h3>
 * 提供**不依赖框架**的宿主 {@link Context}，以及宿主自己 data 目录里的 SharedPreferences
 * （永远可写）。文件名刻意与远程 group 同名（{@code bmapclean_config} / {@code tmapclean_config}），
 * 这样同一个键在两种形态下落在同一个语义位置，抓包/排查时一眼能对上：
 * <pre>
 *   /data/data/com.baidu.BaiduMap/shared_prefs/bmapclean_config.xml
 *   /data/data/com.autonavi.minimap/shared_prefs/mapclean_cfg.xml
 * </pre>
 *
 * <h3>为什么放在 mapclean 包</h3>
 * {@code mapclean} 是**四种构建都保留**的唯一包（模块版 / 高德版 / 百度版 / 腾讯版）；
 * {@code bmapclean} 在高德版里整个被裁掉，{@code mapadkiller} 在百度/腾讯版里被裁掉。
 * 放在这里，两个适配层都能编译期直接引用，不需要反射。
 */
public final class HostPrefs {

    private static final String TAG = "MapCleanPrefs";

    private HostPrefs() {}

    /** 探测失败后多久允许重试一次（{@code visible()} 是热路径，不能每次都做一遍反射）。 */
    private static final long RETRY_MS = 500;

    private static volatile long lastProbeAt;

    /**
     * ★★ 2.3.0：**直给**的宿主 Context —— 唯一权威来源。
     *
     * <p>为什么需要：下面四条反射路在腾讯地图 11.7 里全断（真机逐条日志见
     * {@link #diagnose(String)}）。而同一时刻，宿主的 {@code Application} 明明就在手里 ——
     * {@code App.attachEmbedded(host)} 是 LSPatch 直接把宿主 Application 传进来的，
     * {@code ActivityLifecycleCallbacks} 拿到的每个 Activity 也自带 {@code getApplication()}。
     * 反射探不到、直给拿得到，这就是「写得进但面板显示只读」的全部原因：两套取 Context 的
     * 路径各给各的答案，写入走直给的那套、探测走反射那套。
     *
     * <p>所以这里把直给的那套提升为首选：只要有过一次 {@link #remember(Context)}，
     * {@link #hostContext()} 就不再走反射。{@code null} = 还没人给过。
     */
    private static volatile Context remembered;

    /**
     * 记下宿主自己的 Context（Application 或 Activity 皆可）。
     *
     * <p>调用点：{@code App.attachEmbedded}（LSPatch 直给宿主 Application，最早最可靠）、
     * {@code FloatingHooks} 的生命周期回调（每个 Activity 的 {@code getApplication()}）。
     * 拿到即永久生效（进程内），不再让反射去猜。
     *
     * <p>刻意<b>不做包名校验</b>：传进来的是调用方自己手里的宿主 Application，不是反射捡来的
     * 陌生 Context。多一层校验只会在 {@code currentPackageName()} 反射失败时把唯一可靠的
     * 来源也毙掉 —— 那正是这次要修的病。
     */
    public static void remember(Context ctx) {
        if (ctx == null) return;
        try {
            if (ctx.getPackageName() == null) return;
        } catch (Throwable t) {
            return;
        }
        remembered = ctx;
    }

    /** 有没有拿到过直给的宿主 Context（诊断用）。 */
    public static boolean hasRemembered() { return remembered != null; }

    /**
     * 宿主进程自己的 {@link Context} —— **不依赖 Xposed 框架**，四级顺序，任一成功即返回。
     *
     * <ol>
     *   <li>{@code ActivityThread.currentApplication()} —— 最常见，但**安装早期返回 null**；</li>
     *   <li>{@code AppGlobals.getInitialApplication()} —— 隐藏静态方法，Application 绑定后即可用，
     *       比 currentApplication 更早成功；</li>
     *   <li>{@code ActivityThread.currentActivityThread()} 的 {@code mInitialApplication} 字段；</li>
     *   <li>{@code mapclean.App.ctx()} —— 内置版里由 {@code attachEmbedded} 持有的宿主实例。</li>
     * </ol>
     *
     * <p>★ 必须校验包名：模块可能被注入到别的进程（实测日志里出现过
     * {@code com.miui.contentcatcher} / {@code com.google.android.webview}），
     * 把那些 Context 当宿主用会写到**别人的 data 目录** ⇒ 权限不足 ⇒ {@code commit_false}
     * ⇒ 用户看到「开关拨了不生效」。判据是 {@code ActivityThread.currentPackageName()}
     * （= 当前进程所属包），拿不到就不校验（宁可放行，也不能因为反射失败退化成不可用）。
     *
     * <p>★★ 2.3.0：**直给优先**。{@link #remembered} 有值就直接用，一条反射都不做。
     * 四条反射路只在「还没人给过」时作为兜底 —— 它们在某些宿主里恒为 null（腾讯地图 11.7
     * 实测四条全断），把唯一的探针留在它们身上就等于让面板永远显示「只读」。
     *
     * @return 宿主 Context；连直给都没有、四条路也全失败时返回 {@code null}（调用方按
     *         「未定案」处理，稍后重试）
     */
    public static Context hostContext() {
        // 0) 直给（Application / Activity 传进来的）—— 零反射、零猜测，命中即返回。
        Context handed = remembered;
        if (handed != null) return handed;

        String procPkg = currentProcessPackage();

        // 1) ActivityThread.currentApplication()
        Object app = probeCurrentApplication();
        if (isHostCtx(app, procPkg)) return (Context) app;

        // 2) AppGlobals.getInitialApplication()
        app = probeInitialApplication();
        if (isHostCtx(app, procPkg)) return (Context) app;

        // 3) ActivityThread.currentActivityThread().mInitialApplication
        app = probeActivityThreadField();
        if (isHostCtx(app, procPkg)) return (Context) app;

        // 4) mapclean.App.ctx()（内置版面板侧持有的宿主实例）
        app = probeAppCtx();
        if (isHostCtx(app, procPkg)) return (Context) app;

        return null;
    }

    // ── 四条反射路（hostContext 与 diagnose 共用同一份实现：只有一套探针） ──────

    private static Object probeCurrentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object probeInitialApplication() {
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            return ag.getMethod("getInitialApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object probeActivityThreadField() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            java.lang.reflect.Field f = at.getDeclaredField("mInitialApplication");
            f.setAccessible(true);
            return f.get(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object probeAppCtx() {
        try {
            return App.ctx();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 每条路一次性诊断：把「拿到了什么」逐条落到日志里。{@code where} 区分调用时机。 */
    private static final java.util.Set<String> diagnosed = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<String>());

    /**
     * 逐条打印四条反射路 + 直给 Context 的真实结果，本进程内每个 {@code where} 只打一次。
     *
     * <p>只在「探测失败」和「刚挂上 hooks」两个时机调用：热路径绝不进这里。
     */
    public static void diagnose(String where) {
        if (where == null) where = "-";
        if (!diagnosed.add(where)) return;
        String procPkg = currentProcessPackage();
        Log.i(TAG, "event=hostctx where=" + where + " procPkg=" + procPkg
                + " remembered=" + describe(remembered)
                + " embedded=" + embedded());
        describePath(where, "1.currentApplication", probeCurrentApplication(), procPkg);
        describePath(where, "2.initialApplication", probeInitialApplication(), procPkg);
        describePath(where, "3.mInitialApplication", probeActivityThreadField(), procPkg);
        describePath(where, "4.appCtx", probeAppCtx(), procPkg);
        Context resolved = hostContext();
        Log.i(TAG, "event=hostctx where=" + where + " resolved=" + describe(resolved)
                + " via=" + (remembered != null && resolved == remembered ? "remembered" : "probe"));
    }

    private static void describePath(String where, String path, Object value, String procPkg) {
        Log.i(TAG, "event=hostctx where=" + where + " path=" + path
                + " value=" + describe(value)
                + " accept=" + isHostCtx(value, procPkg));
    }

    /** {@code null} / {@code pkg=<包名>} —— 日志里只关心这两件事。 */
    public static String describe(Object ctx) {
        if (ctx == null) return "null";
        if (!(ctx instanceof Context)) return "not_a_context:" + ctx.getClass().getName();
        try {
            return "pkg=" + ((Context) ctx).getPackageName();
        } catch (Throwable t) {
            return "pkg_threw:" + t.getClass().getSimpleName();
        }
    }

    /**
     * 宿主 data 目录里的普通 SharedPreferences。
     *
     * @param name 文件名（不含 .xml）。适配层传自己的 group 名。
     * @return 拿不到宿主 Context 时返回 {@code null}
     */
    public static SharedPreferences local(String name) {
        try {
            Context c = hostContext();
            if (c == null) return null;
            return c.getSharedPreferences(name, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用一次**真实写入**判定 {@code p} 能不能写 —— 只认 {@code canWrite()} 会漏掉
     * 「只读代理 + commit 恒 true」这种假可写，那正是内置版 remote 库的行为。
     *
     * <p>写一个临时键再删掉，不污染配置。结果由调用方缓存。
     */
    public static boolean writable(SharedPreferences p) {
        if (p == null) return false;
        try {
            SharedPreferences.Editor e = p.edit();
            e.putLong(PROBE_KEY, System.currentTimeMillis());
            if (!e.commit()) return false;
            try { p.edit().remove(PROBE_KEY).commit(); } catch (Throwable ignored) { }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String PROBE_KEY = "__write_probe";

    /**
     * 本进程是不是「免 root 内置版」。
     *
     * <p>判据与 {@code FloatingConfig.isEmbedded()} 同源：{@code mapadkiller.HostInfo}，
     * 各变体里那个类是**真件或返回 true 的桩**，两种构建都能链接。
     * 拿不到就按模块版处理（模块版的 remote 是可写的，行为与 2.2.8 一致）。
     */
    public static boolean embedded() {
        try {
            return io.github.ldxm666.mapadkiller.HostInfo.isEmbedded();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 重试节流：上一次探测失败后 {@link #RETRY_MS} 毫秒内不再重探。
     * 返回 {@code true} 表示「这次可以探」。
     */
    public static boolean mayProbe() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastProbeAt != 0 && now - lastProbeAt < RETRY_MS) return false;
        lastProbeAt = now;
        return true;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 当前进程所属包名；拿不到返回 {@code null}（= 不做包名校验）。 */
    private static String currentProcessPackage() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object name = at.getMethod("currentPackageName").invoke(null);
            return name instanceof String ? (String) name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code ctx} 是不是**宿主进程自己的** Context。
     *
     * <p>判据 `ctx.getPackageName() == 当前进程包名`。{@code procPkg} 为 null（反射不到）时
     * 一律放行 —— 宁可写进一个可能不对的目录，也不能因为一次反射失败让整个配置后端变只读。
     */
    private static boolean isHostCtx(Object ctx, String procPkg) {
        if (!(ctx instanceof Context)) return false;
        if (procPkg == null) return true;
        try {
            return procPkg.equals(((Context) ctx).getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
