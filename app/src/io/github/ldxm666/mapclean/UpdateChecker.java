package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 版本检查：打开设置页 / 面板时去**作者自己的服务器**拉最新版本号，比本机新就提示更新。
 *
 * <h3>数据源</h3>
 * {@code <BASE>/mc/version.json}，BASE 与公告**完全同一套**候选列表（{@link ServerFetch}）：
 * 高端口域名优先、IP 兜底，串行降级、ETag 按 base 分开缓存、3 秒超时。
 * 这份实现和公告共用同一个 GET，不存在「一边修了另一边没修」的漂移。
 *
 * <h3>服务端清单契约</h3>
 * <pre>
 * {
 *   "versionCode": 2301,
 *   "versionName": "2.3.1",
 *   "notes": "修复了…",
 *   "apkUrl": "http://47.109.49.93/mc/MapClean-lsp-2.3.1.apk",
 *   "apkUrlEmbedded": {
 *     "com.autonavi.minimap": "...", "com.baidu.BaiduMap": "...", "com.tencent.map": "..."
 *   },
 *   "publishedAt": 1767225600,
 *   "sig": "..."
 * }
 * </pre>
 * 签名：{@code HEX(HMAC-SHA256(SIG_KEY, "<versionCode>|<versionName>|<apkUrl>"))}，
 * 空 sig 按失败处理。验签失败 = 这一轮失败（不落盘、不提示），绝不把服务器被改过的包
 * 当成「新版本」推给用户。
 *
 * <h3>公开 API</h3>
 * 与改造前**完全一致**（{@link #latest} / {@link #source} / {@link #isOutdated} /
 * {@link #check} / {@link #maybeAutoCheck} / {@link #shouldNotify} / {@link #isChecking}），
 * 所以 {@code FloatingPanel} / {@code MainActivity} 的调用点一行都不用改。
 * 新增 {@link #downloadUrl(Context)}：按「内置版选宿主包名那条、模块版用 apkUrl」的规则
 * 给出下载地址，未获取 / 不在白名单时返回 null。
 *
 * <h3>不变的部分</h3>
 * 24 小时自动检查节流、6 小时手动节流、{@code sRunning} 防重入、失败静默（只写一行日志，
 * 绝不弹错误框）、结果缓存在 prefs（冷启动先显示上次结果再后台刷新）。
 */
public final class UpdateChecker {

    public interface Callback {
        /**
         * @param latest   线上最新版本号（如 "1.1.0"）；null = 这次没查到
         * @param outdated true = 线上比本机新
         * @param source   命中的源（或最后一次的错误），便于诊断
         */
        void onResult(String latest, boolean outdated, String source);
    }

    /** {@link #source(Context)} 在成功时的返回值。UI 上的「via xxx」就是它。 */
    public static final String SOURCE_NAME = "服务器";

    private static final String TAG = "MapCleanUpdate";
    private static final String PREF = io.github.ldxm666.mapadkiller.App.UI_PREFS;
    private static final String K_AT = "ver_check_at";
    private static final String K_VER = "ver_latest";
    private static final String K_CODE = "ver_code";
    private static final String K_NOTES = "ver_notes";
    private static final String K_APK = "ver_apk";
    private static final String K_EMB = "ver_emb_";
    private static final String K_SRC = "ver_source";
    /** 已经用 Toast 提示过的新版本号，用于「每个新版本只提示一次」。 */
    private static final String K_TOASTED = "ver_toast_shown";
    private static final long THROTTLE_MS = 6L * 60L * 60L * 1000L;
    /** 自动检查的间隔：一天一次就够，失败也照样计时（避免每次启动都重试）。 */
    private static final long AUTO_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    /** 内置版的三个宿主包名：{@code apkUrlEmbedded} 的键就是它们。 */
    private static final String[] HOSTS = {
            "com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map",
    };

    /**
     * 隐藏的「模拟新版」开关（默认不存在 = 关闭，不出现在任何可见界面）。
     *
     * <p>存在的唯一理由是可测试性：面板首次打开会真的后台查一次，立刻把手写进
     * {@code ver_latest} 的值覆盖回真实版本，于是「红色胶囊 + 新版本小标」这条分支
     * 在真机上**无法**靠改配置复现。置 true 时读取端直接返回 {@link #SIMULATED_VERSION}
     * 并跳过一切网络请求。
     */
    private static final String K_SIMULATE = "ver_simulate_new";
    /** 远高于任何真实版本，保证 {@link #compare} 一定判「有新版」。 */
    private static final String SIMULATED_VERSION = "9.9.9";
    /** 每进程只打一行 {@code event=update_simulated}。 */
    private static volatile boolean simLogged;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sRunning;
    /** 本次进程是否已经查过（节流只对同一次运行生效；冷启动一律重新查） */
    private static volatile boolean sThisProcess;

    private UpdateChecker() {}

    /** 上次查到的线上版本（可能为 null） */
    public static String latest(Context c) {
        String simulated = simulatedLatest(c);
        if (simulated != null) return simulated;
        try {
            return prefs(c).getString(K_VER, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 上次查到的线上 versionCode（0 = 未知）。 */
    public static int latestCode(Context c) {
        String simulated = simulatedLatest(c);
        if (simulated != null) return Integer.MAX_VALUE;
        try {
            return prefs(c).getInt(K_CODE, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 线上版本的更新说明（服务端 notes），没有就 null。 */
    public static String notes(Context c) {
        try {
            String v = prefs(c).getString(K_NOTES, null);
            return v == null || v.length() == 0 ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 新版本安装包地址。
     *
     * <p>选择规则（服务端契约）：
     * <ol>
     *   <li><b>内置版</b>（LSPatch 整合包）⇒ {@code apkUrlEmbedded} 里**当前宿主包名**那一条
     *       —— 高德用户拿去装百度包是装不上的；</li>
     *   <li>宿主键缺失 / 值为空 / 宿主包名拿不到 ⇒ 退回 {@code apkUrl}；</li>
     *   <li><b>模块版</b> ⇒ {@code apkUrl}。</li>
     * </ol>
     * 两条路都要过 {@link ServerFetch#allowed} 白名单：**不允许服务器指挥客户端去装任意 URL
     * 的 APK**。返回 null = 没有可信地址，UI 会退回到「项目发布页」。
     */
    public static String downloadUrl(Context c) {
        if (c == null) return null;
        try {
            SharedPreferences p = prefs(c);
            boolean embedded = false;
            try { embedded = FloatingConfig.isEmbedded(); } catch (Throwable ignored) { }
            if (embedded) {
                String pkg = null;
                try { pkg = c.getPackageName(); } catch (Throwable ignored) { }
                if (pkg != null) {
                    String host = p.getString(K_EMB + pkg, null);
                    if (host != null && ServerFetch.allowed(host)) return host;
                }
            }
            String apk = p.getString(K_APK, null);
            if (apk != null && ServerFetch.allowed(apk)) return apk;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 隐藏开关是否打开；打开时返回 {@link #SIMULATED_VERSION}，否则 null。
     *
     * <p>两个库都看：嵌入版里 {@code ver_*} 落在 {@code mapclean_embedded_ui}（宿主数据目录），
     * 独立模块进程里落在 {@code mak_ui}。测试员把同一个键写进任意一个都能复现。
     */
    private static String simulatedLatest(Context c) {
        if (c == null) return null;
        boolean on = false;
        try {
            on = prefs(c).getBoolean(K_SIMULATE, false);
        } catch (Throwable ignored) {}
        if (!on) {
            try {
                on = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getBoolean(K_SIMULATE, false);
            } catch (Throwable ignored) {}
        }
        if (!on) return null;
        if (!simLogged) {
            simLogged = true;
            Log.i(TAG, "event=update_simulated latest=" + SIMULATED_VERSION
                    + " local=" + Version.NAME);
        }
        return SIMULATED_VERSION;
    }

    public static String source(Context c) {
        if (simulatedLatest(c) != null) return "simulate";
        try {
            return prefs(c).getString(K_SRC, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 线上比本机新？
     *
     * <p>优先比 {@code versionCode}（服务端契约里的权威字段）；只有老缓存里没有 code 时
     * 才退回语义化版本号比较 —— 字符串比较遇到 {@code 2.3.10} 这种会判错。
     */
    public static boolean isOutdated(Context c) {
        if (simulatedLatest(c) != null) return true;
        try {
            int code = prefs(c).getInt(K_CODE, 0);
            if (code > 0) return code > Version.CODE;
        } catch (Throwable ignored) { }
        String v = latest(c);
        return v != null && compare(v, Version.LABEL) > 0;
    }

    /** Same question as {@link #isOutdated}, named for the call sites that ask it. */
    public static boolean hasNewVersion(Context c) {
        return isOutdated(c);
    }

    /**
     * 上次检查的时间（毫秒，0 = 从未查过）。
     *
     * <p>成功和失败都会写这个值（见 {@link #check}），所以「失败后每次启动都重试」不会发生。
     */
    public static long lastCheckAt(Context c) {
        try {
            return prefs(c).getLong(K_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 每天最多一次的后台自动检查。
     *
     * <p>节流：{@code now - lastCheckAt < 24h} 直接返回，不建线程、不发请求。
     * 失败静默：只在日志里留一行，不 Toast、不改界面。
     */
    public static void maybeAutoCheck(final Context c) {
        if (c == null) return;
        if (simulatedLatest(c) != null) return;      // 模拟态：不发任何请求
        final Context app;
        try {
            app = c.getApplicationContext();
        } catch (Throwable t) {
            return;
        }
        if (app == null) return;
        long last = lastCheckAt(app);
        long now = System.currentTimeMillis();
        if (last > 0L && now - last < AUTO_INTERVAL_MS) return;
        Log.i(TAG, "event=update_auto_start last=" + last);
        check(app, true, new Callback() {
            @Override public void onResult(String latest, boolean outdated, String source) {
                if ("cache".equals(source)) return;      // 缓存回放，不是这次的结果
                if (latest == null) {
                    Log.w(TAG, "event=update_auto_failed source=" + source);
                } else if (outdated) {
                    Log.i(TAG, "event=update_auto_found latest=" + latest + " source=" + source);
                } else {
                    Log.i(TAG, "event=update_auto_latest latest=" + latest + " source=" + source);
                }
            }
        });
    }

    /**
     * 每个新版本只提示一次：返回 true 表示这次该提示（并已把版本号记下）。
     */
    public static boolean shouldNotify(Context c) {
        try {
            String v = latest(c);
            if (v == null || compare(v, Version.LABEL) <= 0) return false;
            SharedPreferences p = prefs(c);
            if (v.equals(p.getString(K_TOASTED, null))) return false;
            p.edit().putString(K_TOASTED, v).commit();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已有一轮检查在跑（{@code check()} 会直接 return，回调也不会来）。 */
    public static boolean isChecking() {
        return sRunning;
    }

    /** 后台检查；force=false 时受 6 小时节流约束。回调在主线程。 */
    public static void check(final Context c, final boolean force, final Callback cb) {
        final Context app = c.getApplicationContext();
        // 模拟态：绝不发网络请求，直接在主线程把模拟结果交回去。
        final String sim = simulatedLatest(app);
        if (sim != null) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    if (cb != null) cb.onResult(sim, true, "simulate");
                }
            });
            return;
        }
        String cached = latest(app);
        if (cached != null && cb != null) {
            // 先把缓存结果吐出去，界面立刻有内容
            final String cv = cached;
            final boolean co = isOutdated(app);
            MAIN.post(new Runnable() {
                @Override public void run() { cb.onResult(cv, co, "cache"); }
            });
        }
        try {
            long at = prefs(app).getLong(K_AT, 0L);
            // ⚠ 只在同一次运行内节流：冷启动必须查一次（旧版 6 小时节流挡住了冷启动检查，
            //   用户表现为「发了新版但一直不提示」）。
            if (!force && sThisProcess && System.currentTimeMillis() - at < THROTTLE_MS) return;
            sThisProcess = true;
        } catch (Throwable ignored) { }
        if (sRunning) return;
        sRunning = true;

        new Thread(new Runnable() {
            @Override public void run() {
                final long started = System.currentTimeMillis();
                String found = null;
                String src = "";
                String err = "";
                String base = "";
                try {
                    SharedPreferences p = prefs(app);
                    ServerFetch.Result r = ServerFetch.get(
                            "update", ServerFetch.PATH_VERSION, p, null);
                    // 304 但本地没有版本信息（清过数据/换机）：丢掉条件头重发一次。
                    if (r.ok && r.body == null && p.getString(K_VER, null) == null) {
                        r = ServerFetch.get("update", ServerFetch.PATH_VERSION, p, null, true);
                    }
                    base = r.base;
                    if (r.ok && r.body == null) {
                        // 304：清单没变，上次落盘的版本信息仍然有效。
                        found = p.getString(K_VER, null);
                        if (found == null) err = "stale304";
                        else src = SOURCE_NAME;
                    } else if (r.ok) {
                        Info info = parse(r.body);
                        if (info == null) {
                            err = "parse";
                        } else if (!ServerFetch.verifySig(
                                info.code + "|" + info.name + "|" + info.apkUrl, info.sig)) {
                            err = "badsig";
                            Log.w(TAG, "event=update_badsig code=" + info.code);
                        } else {
                            persist(p, info, r.base, r.newEtag);
                            found = info.name;
                            src = SOURCE_NAME;
                        }
                    } else {
                        err = r.reason;
                    }
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName();
                } finally {
                    sRunning = false;
                }
                try {
                    SharedPreferences.Editor e = prefs(app).edit();
                    e.putLong(K_AT, System.currentTimeMillis());
                    if (found != null) {
                        e.putString(K_VER, found);
                        e.putString(K_SRC, src);
                    } else {
                        e.putString(K_SRC, err.length() > 0 ? err : "failed");
                    }
                    e.commit();
                } catch (Throwable ignored) { }
                final String result = found == null ? "failed"
                        : (isOutdated(app) ? "new" : "latest");
                Log.i(TAG, "event=update_check_done result=" + result
                        + " elapsed=" + (System.currentTimeMillis() - started)
                        + " source=" + (found != null ? (SOURCE_NAME + " " + base) : "none")
                        + (found != null ? (" latest=" + found) : (" err=" + err)));

                final String fv = found;
                final String fs = found != null ? src : (err.length() > 0 ? err : "failed");
                final boolean outdated = found != null && isOutdated(app);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onResult(fv, outdated, fs);
                    }
                });
            }
        }, "mapclean-update").start();
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    /** 服务端清单（v2.3.1 起）。 */
    private static final class Info {
        int code;
        String name = "";
        String notes = "";
        String apkUrl = "";
        String sig = "";
        long publishedAt;
        final Map<String, String> embedded = new HashMap<String, String>();
    }

    private static Info parse(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(body);
            Info info = new Info();
            info.code = o.getInt("versionCode");
            info.name = o.optString("versionName", "").trim();
            info.notes = o.optString("notes", "");
            info.apkUrl = o.optString("apkUrl", "").trim();
            info.sig = o.optString("sig", "").trim();
            info.publishedAt = o.optLong("publishedAt", 0L);
            if (info.code <= 0 || info.name.length() == 0) return null;
            JSONObject emb = o.optJSONObject("apkUrlEmbedded");
            if (emb != null) {
                Iterator<String> keys = emb.keys();
                while (keys != null && keys.hasNext()) {
                    String key = keys.next();
                    String value = emb.optString(key, "");
                    if (value != null && value.trim().length() > 0) {
                        info.embedded.put(key, value.trim());
                    }
                }
            }
            return info;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 落盘。下载地址**逐个过白名单**：不在名单里的直接丢弃（宁可不给下载入口，
     * 也不让一份被篡改的清单把用户带到任意 APK）。
     */
    private static void persist(SharedPreferences p, Info info, String base, String etag) {
        try {
            SharedPreferences.Editor e = p.edit();
            e.putString(K_VER, info.name);
            e.putInt(K_CODE, info.code);
            e.putString(K_NOTES, info.notes);
            if (ServerFetch.allowed(info.apkUrl)) e.putString(K_APK, info.apkUrl);
            // 先清掉三个宿主键，避免上一版有、这一版没有的条目残留成死链。
            for (String host : HOSTS) e.remove(K_EMB + host);
            for (Map.Entry<String, String> entry : info.embedded.entrySet()) {
                if (ServerFetch.allowed(entry.getValue())) {
                    e.putString(K_EMB + entry.getKey(), entry.getValue());
                }
            }
            if (etag != null && etag.length() > 0) e.putString("etag::" + base, etag);
            e.commit();
        } catch (Throwable ignored) { }
    }

    private static SharedPreferences prefs(Context c) {
        // Always compare Version.LABEL (this module), never the host map's PackageInfo.
        return c.getSharedPreferences(EmbeddedSettingsPolicy.isHostPackage(c.getPackageName())
                ? EmbeddedSettingsPolicy.UI_PREFS : PREF, Context.MODE_PRIVATE);
    }

    /** 语义化比较：a>b 返回 1，相等 0，a<b -1（位数不同按 0 补齐） */
    static int compare(String a, String b) {
        if (a == null || b == null) return 0;
        String ab = a, as = "";
        int da = a.indexOf('-');
        if (da >= 0) { ab = a.substring(0, da); as = a.substring(da + 1); }
        String bb = b, bs = "";
        int db = b.indexOf('-');
        if (db >= 0) { bb = b.substring(0, db); bs = b.substring(db + 1); }
        String[] x = ab.split("\\.");
        String[] y = bb.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? toInt(x[i]) : 0;
            int yi = i < y.length ? toInt(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        // Same 2.2.3 base: "-step2" is newer than a plain build, step2 < step3.
        int sa = stepOf(as), sb = stepOf(bs);
        return sa == sb ? 0 : (sa > sb ? 1 : -1);
    }

    private static int stepOf(String suffix) {
        if (suffix == null || suffix.length() == 0) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("step(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(suffix);
        return m.find() ? toInt(m.group(1)) : 0;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
