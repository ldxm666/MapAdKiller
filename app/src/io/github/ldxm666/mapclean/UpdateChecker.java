package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本检查：打开设置页时自动去 GitHub Releases 拉最新版本号，比本机新就提示更新。
 *
 * 设计要点（都是真机/网络环境下必须的）：
 *   - **多源回退**：国内直连 `api.github.com` 常失败，按 {@link Version#UPDATE_SOURCES}
 *     依次尝试（官方 → kkgithub 镜像 → 两个反代），任一成功即止；
 *   - **6 小时节流**：自动检查最多 6 小时一次，手动点版本行可强制刷新；
 *   - **失败不打扰**：全部源都拿不到时只在界面上显示「未获取（点此重试）」，
 *     绝不弹错误弹窗（模块离线也必须完全可用）；
 *   - 结果缓存在本地 SharedPreferences，冷启动先显示上次结果再后台刷新。
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

    private static final String TAG = "MapCleanUpdate";
    private static final String PREF = io.github.ldxm666.mapadkiller.App.UI_PREFS;
    private static final String K_AT = "ver_check_at";
    private static final String K_VER = "ver_latest";
    private static final String K_SRC = "ver_source";
    private static final long THROTTLE_MS = 6L * 60 * 60 * 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sRunning;

    private UpdateChecker() {}

    /** 上次查到的线上版本（可能为 null） */
    public static String latest(Context c) {
        try {
            return prefs(c).getString(K_VER, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String source(Context c) {
        try {
            return prefs(c).getString(K_SRC, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean isOutdated(Context c) {
        String v = latest(c);
        return v != null && compare(v, Version.NAME) > 0;
    }

    /** 后台检查；force=false 时受 6 小时节流约束。回调在主线程。 */
    public static void check(final Context c, final boolean force, final Callback cb) {
        final Context app = c.getApplicationContext();
        String cached = latest(app);
        if (cached != null && cb != null) {
            // 先把缓存结果吐出去，界面立刻有内容
            final String cv = cached;
            MAIN.post(new Runnable() {
                @Override public void run() { cb.onResult(cv, compare(cv, Version.NAME) > 0, "cache"); }
            });
        }
        try {
            long at = prefs(app).getLong(K_AT, 0L);
            if (!force && System.currentTimeMillis() - at < THROTTLE_MS) return;
        } catch (Throwable ignored) {}
        if (sRunning) return;
        sRunning = true;

        new Thread(new Runnable() {
            @Override public void run() {
                String found = null;
                String src = "";
                String err = "";
                try {
                    for (int i = 0; i < Version.UPDATE_SOURCES.length; i++) {
                        String url = Version.UPDATE_SOURCES[i];
                        try {
                            String body = httpGet(url);
                            String v = parse(body);
                            if (v != null && v.length() > 0) {
                                found = v;
                                src = shortUrl(url);
                                break;
                            }
                            err = shortUrl(url) + " 解析失败";
                        } catch (Throwable t) {
                            err = shortUrl(url) + " " + t.getClass().getSimpleName();
                        }
                    }
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
                        e.putString(K_SRC, err);
                    }
                    e.commit();
                } catch (Throwable ignored) {}
                if (found != null) Log.i(TAG, "latest=" + found + " via " + src);
                else Log.w(TAG, "check failed: " + err);

                final String fv = found;
                final String fs = found != null ? src : err;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (cb != null) cb.onResult(fv, fv != null && compare(fv, Version.NAME) > 0, fs);
                    }
                });
            }
        }, "mapclean-update").start();
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String shortUrl(String u) {
        try {
            String h = new URL(u).getHost();
            return h == null ? u : h;
        } catch (Throwable t) {
            return u;
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MapAdKiller/" + Version.NAME);
            conn.setRequestProperty("Accept", "application/vnd.github+json, */*");
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 512 * 1024) break;      // 防御：任何源都不该返回这么大
            }
            in.close();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final Pattern P_TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_NAME = Pattern.compile("\"name\"\\s*:\\s*\"[^\"]*?(\\d+\\.\\d+(?:\\.\\d+)*)\"");
    private static final Pattern P_VER = Pattern.compile("(?m)^\\s*version\\s*=\\s*(\\d+\\.\\d+(?:\\.\\d+)*)\\s*$");
    private static final Pattern P_ANY = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)*)");

    /** 从各种形状的响应里抠出版本号：GitHub JSON（tag_name/name）、module.prop 文本、纯版本号 */
    static String parse(String body) {
        if (body == null) return null;
        Matcher m = P_TAG.matcher(body);
        if (m.find()) return normalize(m.group(1));
        m = P_NAME.matcher(body);
        if (m.find()) return normalize(m.group(1));
        m = P_VER.matcher(body);
        if (m.find()) return normalize(m.group(1));
        String s = body.trim();
        if (s.length() < 40) {                       // 短响应才敢直接当版本号
            m = P_ANY.matcher(s);
            if (m.find()) return normalize(m.group(1));
        }
        return null;
    }

    /** tag 形如 "110-1.1.0" → 取最后一段版本号 */
    private static String normalize(String raw) {
        String s = raw.trim();
        int dash = s.lastIndexOf('-');
        if (dash >= 0 && dash < s.length() - 1) {
            String tail = s.substring(dash + 1).trim();
            if (tail.matches("\\d+(\\.\\d+)*")) s = tail;
        }
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    /** 语义化比较：a>b 返回 1，相等 0，a<b -1（位数不同按 0 补齐） */
    static int compare(String a, String b) {
        if (a == null || b == null) return 0;
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? toInt(x[i]) : 0;
            int yi = i < y.length ? toInt(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private static int toInt(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') sb.append(ch);
                else break;
            }
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return 0;
        }
    }
}
