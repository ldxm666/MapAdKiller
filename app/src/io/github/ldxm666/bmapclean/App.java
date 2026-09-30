package io.github.ldxm666.bmapclean;

import android.app.Application;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 进程：绑定 LSPosed 服务，用于读写 RemotePreferences。
 *
 * Hook 进程不走本类（那边是 {@link Cfg#prefs()} → XposedModule#getRemotePreferences）。
 * 两个进程读的是**同一个 group**，所以设置页改完、强停百度地图重开即生效。
 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService service;

    public static XposedService svc() {
        return service;
    }

    public static boolean connected() {
        return service != null;
    }

    /** 设置页写开关。必须 commit()：apply() 异步落盘，用户切完就退出会丢。 */
    public static boolean write(String key, boolean value) {
        XposedService s = service;
        if (s == null) return false;
        try {
            return s.getRemotePreferences(Cfg.GROUP).edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 设置页读开关（服务未连上时按「可见」返回，与 hook 侧失效安全一致）。 */
    public static boolean read(String key) {
        return read(key, true);
    }

    /** 缺省值由调用方指定（默认「关」的键，如 mine_apply） */
    public static boolean read(String key, boolean def) {
        XposedService s = service;
        if (s == null) return def;
        try {
            return s.getRemotePreferences(Cfg.GROUP).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 诊断摘要（hook 侧通过广播回报，落在本地 SharedPreferences） */
    public static String diag() {
        String[] r = lastReport();
        if (r == null) return null;
        return r[0] + " · " + r[1] + "  （" + r[2] + "）";
    }

    /** @return {stage 中文描述, detail, 时间文本} 或 null */
    public static String[] lastReport() {
        try {
            android.content.SharedPreferences sp =
                    app().getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE);
            String stage = sp.getString("stage", null);
            if (stage == null) return null;
            String detail = sp.getString("detail", "");
            long ts = sp.getLong("ts", 0L);
            String when = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(ts));
            String label = "hook".equals(stage) ? "钩子注入" : "首页规则执行";
            return new String[]{label, detail, when};
        } catch (Throwable t) {
            return null;
        }
    }

    /** hook 是否曾经回报过（= 模块确实在目标进程里跑起来了） */
    public static long hookReportAt() {
        try {
            return app().getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE)
                    .getLong("hook_ts", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 最近一次收到任何回报的时间（钩子注入 **或** 首页规则执行）。
     *
     * 判「模块是否生效」必须用这个而不是 hookReportAt()：钩子注入那条广播发得极早
     * （进程刚起、Application 还没 onCreate 完），此时设置页进程往往不在运行，
     * 系统可能直接把广播丢掉；而扫描回报一定在首页起来之后才发，必然能送达。
     * 只看 hook_ts 会误报"尚未收到回报"（真机实测）。
     */
    public static long lastReportAt() {
        try {
            android.content.SharedPreferences sp =
                    app().getSharedPreferences(ReportReceiver.P, android.content.Context.MODE_PRIVATE);
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
     * ⚠ 必须 post 到主线程：本方法由 ReportReceiver.onReceive（binder 线程）调用，
     * 若直接 run()，就会有一个非 UI 线程去 removeAllViews/addView，
     * 与 UI 线程的 render() 并发改同一棵树 —— 实测表现就是**设置页整片空白**，
     * 而且没有 FATAL、主线程还停在 epoll 上，极其难查。
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

    private static final String TAG = "BMapCleanApp";

    private static Application self;

    public static Application app() { return self; }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        try {
            XposedServiceHelper.registerListener(this);
            android.util.Log.i(TAG, "registerListener ok");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "registerListener failed", t);
        }
    }

    @Override
    public void onServiceBind(XposedService s) {
        service = s;
        android.util.Log.i(TAG, "onServiceBind ok");
        ensureToken();
        notifyReport();
    }

    @Override
    public void onServiceDied(XposedService s) {
        android.util.Log.w(TAG, "onServiceDied");
        if (service == s) service = null;
        notifyReport();
    }

    // ── v2.0.0 合并版：由 io.github.ldxm666.mapclean.App（真正的 Application）驱动 ──
    //    本类不再作为 manifest 的 application，只当静态持有者用（服务对象两边共用同一个）。
    public static void attach(Application a) { self = a; }

    public static void onBind(XposedService s) {
        service = s;
        ensureToken();
        notifyReport();
    }

    public static void onDied(XposedService s) {
        if (service == s) service = null;
        notifyReport();
    }
}
