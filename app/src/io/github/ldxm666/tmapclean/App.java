package io.github.ldxm666.tmapclean;

import android.app.Application;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 腾讯侧模块 App 静态持有者：绑定 LSPosed 服务，用于读写 RemotePreferences。
 *
 * <p>Hook 进程不走本类（那边是 {@link Cfg#prefs()} → XposedModule#getRemotePreferences）。
 * 两个进程读的是<b>同一个 group</b>，所以设置页改完、重开腾讯地图即生效。
 *
 * <p>v2.0.0 合并版起，本类<b>不是</b> manifest 里的 Application ——
 * 真正的 Application 是 {@code io.github.ldxm666.mapclean.App}，由它调用
 * {@link #attach} / {@link #onBind} / {@link #onDied} 把服务对象接过来。
 *
 * <p>v2.1.7 起本类<b>自己也能绑</b>（{@link #ensureBound}）：转发那条路在腾讯内置版里
 * 会在到达 {@code onBind} 之前就断掉，详见 {@link #ensureBound} 的说明。
 */
public final class App implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "TMapCleanApp";

    private static final App INSTANCE = new App();

    private static volatile XposedService service;
    private static volatile Application self;
    private static volatile boolean listenerRegistered;

    private App() {}

    public static Application app() { return self; }

    public static XposedService svc() { return service; }

    public static boolean connected() { return service != null; }

    /** 设置页写开关。必须 commit()：apply() 异步落盘，用户切完就退出会丢。 */
    public static boolean write(String key, boolean value) {
        // ★ 免 root 内置版：服务通道写的是 LSPatch 的 lspatch-xposed-remote.db，
        //   真机实测会 SQLiteCantOpenDatabaseException —— commit() 返回 true 但读不回来。
        //   宿主自己的 SharedPreferences（tmapclean_config.xml）永远可写，且 hook 侧读同一个文件。
        if (Cfg.embedded()) return Cfg.write(key, value);
        XposedService s = service;
        if (s != null) {
            try {
                if (s.getRemotePreferences(Cfg.GROUP).edit().putBoolean(key, value).commit()) {
                    Cfg.invalidate();
                    return true;
                }
            } catch (Throwable ignored) { }
        }
        // 服务未连上（模块版首启 / 内置版降级）：本地后端兜底，不再直接返回 false。
        return Cfg.write(key, value);
    }

    /** 设置页读开关（服务未连上时按调用方给的缺省返回，与 hook 侧失效安全一致）。 */
    public static boolean read(String key) {
        return read(key, Spec.defaultVisible(key));
    }

    public static boolean read(String key, boolean def) {
        if (Cfg.embedded()) return Cfg.visible(key, def);
        XposedService s = service;
        if (s != null) {
            try {
                return s.getRemotePreferences(Cfg.GROUP).getBoolean(key, def);
            } catch (Throwable ignored) { }
        }
        return Cfg.visible(key, def);
    }

    // ── 诊断摘要（hook 侧通过广播回报，落在本地 SharedPreferences） ──────

    /** 诊断摘要：一行文字，给信息卡用 */
    public static String diag() {
        String[] r = lastReport();
        if (r == null) return null;
        return r[0] + " · " + r[1] + "  （" + r[2] + "）";
    }

    /** @return {stage 中文描述, detail, 时间文本} 或 null */
    public static String[] lastReport() {
        try {
            android.content.SharedPreferences sp = app().getSharedPreferences(
                    ReportReceiver.P, android.content.Context.MODE_PRIVATE);
            String stage = sp.getString("stage", null);
            if (stage == null) return null;
            String detail = sp.getString("detail", "");
            long ts = sp.getLong("ts", 0L);
            String when = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(ts));
            return new String[]{stageLabel(stage), detail, when};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stageLabel(String stage) {
        if ("hook".equals(stage)) return "钩子注入";
        if ("tools".equals(stage)) return "工具目录";
        return "界面规则执行";
    }

    /** 最近一次收到任何回报的时间（钩子注入 或 规则执行） */
    public static long lastReportAt() {
        try {
            android.content.SharedPreferences sp = app().getSharedPreferences(
                    ReportReceiver.P, android.content.Context.MODE_PRIVATE);
            long a = sp.getLong("hook_ts", 0L);
            long b = sp.getLong("scan_ts", 0L);
            return a > b ? a : b;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static int reportCount() {
        try {
            return app().getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE)
                    .getInt("count", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── UI 刷新通知 ────────────────────────────────────────────────────
    private static volatile Runnable reportListener;

    /** 主线程 Handler：广播是在 binder 线程收到的，绝不能在那里碰视图树 */
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public static void setReportListener(Runnable r) { reportListener = r; }

    /**
     * 通知设置页刷新。
     *
     * <p>⚠ 必须 post 到主线程：本方法由 {@code ReportReceiver.onReceive}（binder 线程）调用，
     * 若直接 run()，就会有一个非 UI 线程去改视图树，与 UI 线程并发 —— 实测表现是设置页整片空白，
     * 而且没有 FATAL。
     */
    public static void notifyReport() {
        final Runnable r = reportListener;
        if (r == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try { r.run(); } catch (Throwable t) {
                    android.util.Log.w(TAG, "report listener failed", t);
                }
            }
        });
    }

    // ── token（防伪广播） ───────────────────────────────────────────────
    public static final String K_TOKEN = "app_token";

    public static String token() {
        XposedService s = service;
        if (s == null) return "";
        try {
            return s.getRemotePreferences(Cfg.GROUP).getString(K_TOKEN, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 首次运行时生成 token 并写进 RemotePreferences（hook 侧只读得到它） */
    public static void ensureToken() {
        XposedService s = service;
        if (s == null) return;
        try {
            android.content.SharedPreferences p = s.getRemotePreferences(Cfg.GROUP);
            String cur = p.getString(K_TOKEN, "");
            if (cur == null || cur.length() == 0) {
                p.edit().putString(K_TOKEN, java.util.UUID.randomUUID().toString()).commit();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ensureToken failed: " + t);
        }
    }

    public static boolean resetAll() {
        XposedService s = service;
        if (s == null) return false;
        try {
            String tk = token();
            android.content.SharedPreferences.Editor e =
                    s.getRemotePreferences(Cfg.GROUP).edit().clear();
            if (tk != null && tk.length() > 0) e.putString(K_TOKEN, tk); // token 保留
            return e.commit();
        } catch (Throwable t) {
            return false;
        }
    }

    // ── v2.0.0 合并版：由 io.github.ldxm666.mapclean.App 驱动 ─────────────
    public static void attach(Application a) { self = a; }

    public static void onBind(XposedService s) {
        service = s;
        android.util.Log.i(TAG, "onServiceBind ok (driven by mapclean.App)");
        ensureToken();
        notifyReport();
    }

    public static void onDied(XposedService s) {
        if (service == s) service = null;
        notifyReport();
    }

    // ── v2.1.7：自举绑定（不依赖 mapclean.App 的转发） ─────────────────────
    //
    // 免 root 内置版（LSPatch 整合包）里没有模块自己的 App 进程：唯一能拿到可写
    // RemotePreferences 的途径是 XposedServiceHelper 投递的 XposedService。
    //
    // 原来只靠 mapclean.App.attachEmbedded() 转发。但那条路的第一句是闸门
    //
    //     if (host == null || !EmbeddedSettings.enabledFor(host.getPackageName()))
    //         throw new IllegalStateException("Embedded settings require the LSPatch host");
    //
    // 而 slim 构建里 EmbeddedSettings 是 tools/variant-stubs 的替身，它的 enabledFor()
    // 就是 EmbeddedSettingsPolicy.isHostPackage()。那份名单只写了
    //
    //     com.autonavi.minimap（高德） / com.baidu.BaiduMap（百度）
    //
    // —— 腾讯 com.tencent.map 不在里面。于是腾讯进程里 attachEmbedded() 在
    // registerListener() 之前就抛异常，异常又被 FloatingConfig.ensureService() 的
    // catch (Throwable ignored) 吞掉：
    //
    //   * tmapclean.App.service 恒为 null → TmapPanel.writable() 为 false
    //     → 悬浮球面板恒显「○ 只读 · 服务未连接」（FloatingPanel#refreshStatus）；
    //   * tmapclean.App.self 也恒为 null → TmapPanel.syncLearnedTools() 直接 return
    //     → 钩子学到的工具目录永远灌不进 Spec，逐项开关只剩 BUILTIN 那 78 条。
    //
    // 真机 logcat 判据（同一份日志里两个进程对照）：
    //   百度进程 pid 29372：I/MapCleanApp: onServiceBind ok / I/MapCleanApp: registerListener ok
    //   腾讯进程 pid 16181：只有 MapCleanFloat: eligible activity …，**没有** registerListener ok
    //
    // 所以腾讯侧必须能自己把监听注册上，不再借道那份厂商名单。

    /** 拿到宿主 Application 时调用（钩子侧已有它）。 */
    public static void ensureBound(Application host) {
        if (host != null) self = host;
        installHooks(host);
        register();
    }

    /**
     * 没有 Context 时的入口（面板侧兜底）。
     *
     * <p>{@code ActivityThread.currentApplication()} 在任何宿主进程里都能拿到当前
     * Application；拿不到也不影响注册 —— 注册本身与 Application 无关。
     */
    public static void ensureBound() {
        if (self == null) {
            try {
                Object current = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (current instanceof Application) self = (Application) current;
            } catch (Throwable ignored) {
                // 拿不到就算了：下面的注册才是关键。
            }
        }
        installHooks(self);
        register();
    }

    /**
     * 顺便把腾讯侧的界面钩子装上。
     *
     * <p>{@link MainHook#installFromHost} 里记了原因：共享入口的厂商分发在腾讯内置版里
     * 被替身丢掉，{@code tmapclean.MainHook.onPackageReady} 从来不会被调用。
     * 这里是最早能拿到宿主 Application（→ 宿主 ClassLoader）的地方，所以由它补上。
     * 已经装过就什么都不做（{@code INSTALLED} 的 CAS）。
     */
    private static void installHooks(Application host) {
        if (host == null) return;
        try {
            MainHook.installFromHost(host);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "self install failed", t);
        }
    }

    private static synchronized void register() {
        if (listenerRegistered) return;
        try {
            // 框架已经把 XposedService 投递过来了（logcat: "Delivered XposedService to …"），
            // 所以这一次 registerListener 会**同步**回调 onServiceBind，注册完 svc() 就非 null。
            XposedServiceHelper.registerListener(INSTANCE);
            listenerRegistered = true;
            android.util.Log.i(TAG, "registerListener ok (tmapclean self-driven)");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "registerListener failed", t);
        }
    }

    @Override public void onServiceBind(XposedService s) { onBind(s); }

    @Override public void onServiceDied(XposedService s) { onDied(s); }
}
