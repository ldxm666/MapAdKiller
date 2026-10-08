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

    private static volatile SharedPreferences cached;
    private static volatile java.util.Map<String, ?> snapshot = java.util.Collections.emptyMap();
    private static volatile long expires;
    private static final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (p, key) -> expires = 0;
    private static volatile long dbgAt;
    private static volatile boolean dbgVal;

    private Cfg() {}

    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p == null) {
            synchronized (Cfg.class) {
                p = cached;
                if (p == null) {
                    try {
                        p = H.module == null ? null : H.module.getRemotePreferences(GROUP);
                    } catch (Throwable t) {
                        p = null;
                    }
                    cached = p;
                    if (p != null) try { p.registerOnSharedPreferenceChangeListener(listener); } catch (Throwable ignored) {}
                }
            }
        }
        return p;
    }

    /** 缺省 true = 显示 */
    public static boolean visible(String key) {
        return visible(key, true);
    }

    /** 缺省值由调用方指定（用于默认「关」的键，例如 mine_apply） */
    public static boolean visible(String key, boolean def) {
        try {
            SharedPreferences p = prefs();
            if (p == null) return def;
            Object value = values().get(key);
            return value instanceof Boolean ? (Boolean) value : def;
        } catch (Throwable t) {
            return def;
        }
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
                } catch (Throwable ignored) {}
                expires = now + 2000;
            }
        }
        return snapshot;
    }
}
