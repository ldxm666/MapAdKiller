package io.github.ldxm666.mapadkiller;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Shared setting keys. Hook reads use an immutable snapshot refreshed at most every two seconds. */
public final class Config {
    public static final String PKG = "io.github.ldxm666.mapadkiller";
    public static final String PREF_GROUP = "amap_enhancer_config";
    public static final String K_TAB_PREFIX = "tab_", K_TOOL_PREFIX = "tool_";
    public static final String K_TOOLS_VISIBLE = "ui_tools_visible";
    public static final String K_FEED_WEATHER = "feed_weather", K_FEED_SCENIC = "feed_scenic";
    public static final String K_FEED_RANK = "feed_rank", K_FEED_POSTS = "feed_posts", K_FEED_DISTANCE = "feed_distance";
    public static final String K_FEED_CONTENT = "feed_content", K_FEED_AI = "feed_ai", K_FEED_FILTER = "feed_filter";
    public static final String K_FEED_BOARD = "feed_board", K_FEED_FESTIVAL = "feed_festival";
    public static final String K_HOME_CHIPS = "home_chips", K_HOME_QUICK_ROW = "home_quick_row";
    public static final String K_DEBUG_LOG = "debug_log", K_TOOL_SORT = "tool_sort";
    public static final String K_SEARCH_AD = "search_ad", K_HOTWORD_OFF = "hotword_off", K_RIDE_CARD_OFF = "ride_card_off";
    public static final String K_MY_ORDER_ROW = "my_order_row", K_MY_SERVICE_ROW = "my_service_row", K_MY_TASK = "my_task";
    public static final String K_MY_PROMO_ROW = "my_promo_row", K_MY_GUESS = "my_guess", K_MY_QUALITY = "my_quality";
    public static final String K_MY_FRIENDS = "my_friends";
    public static final String K_MAP_PROMO = "map_promo";
    // The map's default page is kept; removing it invalidates navigation return targets.
    public static final String[] TABS = {"探索", "长按说话", "打车", "我的"};
    public static final String[] TOOLS = toolLabels();
    public static final java.util.Set<String> TOOLS_DEFAULT_OFF = new java.util.HashSet<String>(java.util.Arrays.asList(
            "旅游度假", "车主服务", "景点游玩", "离线地图", "高德出行节"));
    public static final boolean FORCE_DEBUG = false;
    private static volatile SharedPreferences cached;
    private static volatile Map<String, ?> snapshot = Collections.emptyMap();
    private static volatile long expiresAt;
    private static final SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) { expiresAt = 0; }
    };
    private Config() {}
    private static String[] toolLabels() {
        String[] out = new String[AmapData.TOOLS.length];
        for (int i = 0; i < out.length; i++) out[i] = AmapData.TOOLS[i][1];
        return out;
    }
    public static boolean defaultVisible(String key) {
        if (K_DEBUG_LOG.equals(key)) return false;
        if (key != null && key.startsWith(K_TOOL_PREFIX))
            return !TOOLS_DEFAULT_OFF.contains(key.substring(K_TOOL_PREFIX.length()));
        return !K_HOME_QUICK_ROW.equals(key) && !K_FEED_BOARD.equals(key) && !K_FEED_FESTIVAL.equals(key)
            && !K_FEED_WEATHER.equals(key) && !K_FEED_CONTENT.equals(key) && !K_HOME_CHIPS.equals(key)
            && !K_MY_FRIENDS.equals(key) && !K_MAP_PROMO.equals(key);
    }
    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p == null) synchronized (Config.class) {
            p = cached;
            if (p == null) {
                p = H.module.getRemotePreferences(PREF_GROUP);
                p.registerOnSharedPreferenceChangeListener(listener);
                cached = p;
            }
        }
        return p;
    }
    public static boolean visible(String key) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now >= expiresAt) synchronized (Config.class) {
            if (now >= expiresAt) {
                try { snapshot = Collections.unmodifiableMap(new HashMap<String, Object>(prefs().getAll())); }
                catch (Throwable ignored) {}
                expiresAt = now + 2000;
            }
        }
        Object value = snapshot.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultVisible(key);
    }
    public static boolean toolVisible(String label) { return visible(K_TOOL_PREFIX + label); }
    public static boolean tabVisible(String label) { return visible(K_TAB_PREFIX + label); }
    public static boolean debugLog() { return FORCE_DEBUG || visible(K_DEBUG_LOG); }
}
