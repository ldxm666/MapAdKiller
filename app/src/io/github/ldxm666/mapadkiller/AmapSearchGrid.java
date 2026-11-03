// language: Java, file: AmapSearchGrid.java, module: MapAdKiller, target: 高德地图 17.02.0.1541 搜索页
package io.github.ldxm666.mapadkiller;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * AmapSearchGrid — 搜索页「搜索框下方那排彩色金刚位」开关（**容器级整块移除**，v4）。
 *
 * <p>零文字依赖：不读 getText / contentDescription / 任何字段；只按几何找容器，整块删。
 * 日志：event=search_grid_removed via=… node=<结构描述> cells=N / event=search_grid_fail reason=…
 */
public final class AmapSearchGrid {

    private AmapSearchGrid() {}

    private static final String PAGE_HINT = "amap_bundle_search_home";
    private static final String[] URL_HINTS = {"searchHomeStandard", "search_home", "amap_bundle_search_home", "/af-search/search"};

    /** 几何门槛（实测：金刚位 Container 1016x184 c=6；外层 1017x223 c=2＝行+指示器）。 */
    private static final int MIN_CELLS = 4;
    private static final int MAX_CELLS = 12;
    private static final float WIDTH_TOLERANCE = 0.28f;
    private static final int ROW_MIN_DP = 46;
    private static final int ROW_MAX_DP = 240;
    private static final int INDICATOR_MAX_DP = 30;

    private static final long[] PASS_DELAY_MS = {350L, 700L, 1200L, 2000L, 3200L};
    private static final long VERIFY_MS = 120L;

    /**
     * 数据层改写开关。
     *
     * <p>**当前为 false**：真机实测证明「搜索页那排金刚位的配置」不在
     * {@code af-search/search} 的响应里（那条只回 {@code data.regions.content(4)}，是历史/区域模块），
     * 而全量扫描到的唯一 icon-grid 数组是 {@code data.nineGrid(16)}（车主业务，与本功能无关）。
     * 在没锁定真实数据源之前开启改写＝有可能误删别的业务的数组，所以先关掉，
     * 只保留 {@code event=search_grid_scan} 取证，等定位到真正的数据源再打开。
     */
    private static final boolean STRIP_ENABLED = false;

    /**
     * 写侧剔除（`AjxModuleLocalStorage.setItemASYNC`）。
     *
     * <p>**已关闭**：实测「改缓存不影响当次渲染」（13 行彩色块计数逐点一致），
     * 缓存只是旁路副本，渲染源是内存里的网络响应。代码保留，等响应侧打通后再统一启用。
     */
    private static final boolean WRITE_STRIP = false;
    /**
     * 响应侧剔除（承载 quickLink 的那条 af-search 响应）。**已实测否证，默认关闭**：
     * 剔掉服务端数据后 AJX 页会回落到预载包写死的默认 6 项（加油站/休闲玩乐/超市…），
     * 那是另一批广告，比不剔更糟。数据侧（响应 + 缓存读/写）三条路全部否证，见注册表 §五。
     */
    private static final boolean RESPONSE_STRIP = false;

    /**
     * 取证阶段预留的「精确目标」：填上 module/key 后，下一轮只对这一个键做剔除，
     * 其它键一律原样返回（避免误删车主 nineGrid 之类的业务数据）。
     * 空字符串 = 尚未锁定目标。
     */
    private static final String LS_TARGET_MODULE = "";
    private static final String LS_TARGET_KEY = "";

    /**
     * 视图层兜底（渲染后摘容器）。
     *
     * <p>**已关闭**：数据层（{@code AmapData.toolVisibleRaw} 里按 id 剔除）已经生效，
     * 那排从一开始就不渲染 ⇒ 不需要再事后摘。留着它只会造成用户报的
     * 「进去闪一下再消失、每次进入都重演」——正是数据层要根治的毛病。
     * 只有在数据层失效（服务端换了新 id）时才临时打开它做兜底。
     */
    // ★ 实测结论（2026-10-25，5 轮否证）：数据层四条路（toolVisible 逐项 / toolbox() slot 跳过 /
    //   时间窗 / 去时间窗）**全部无效** —— 那排不是模块的 toolbox payload，真实数据源未找到。
    //   唯一真正生效的机制就是视图层摘容器，代价：进入搜索页时会先闪现约 0.3s 再消失，
    //   且原位可能留一条空白（AJX 节点属性只在创建时生效，运行期改 display/height 不触发重排）。
    //   ⇒ 如实开启并对外写明限制，不做「假根治」。
    // ★ 数据侧三条路（响应 / 缓存读 / 缓存写）已全部实测否证：剔掉数据后 AJX 页必定回落到
    //   预载包写死的默认 6 项，等于换另一批广告。唯一真正能让那排不出现的机制就是视图层摘容器。
    //   代价（如实告知用户）：进入搜索页会先闪约 0.3s，每次重进重演，原位留约 223px 空白
    //   —— 该容器高度由高德页面自身写死，模块无法回收。
    private static final boolean VIEW_FALLBACK = true;

    /** 容器级属性尝试链：挨个试，每次实测高度，谁把它压到 0 就停。 */
    private static final String[][] ATTRS = {
            {"display", "none"},
            {"visibility", "gone"},
            {"height", "0"},
            {"display", "0"},
    };

    private static final Map<View, Boolean> DONE = new WeakHashMap<View, Boolean>();
    private static final java.util.Set<String> SEEN_URLS =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    private static final java.util.Set<String> ONCE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final int URL_LOG_MAX = 40;

    private static volatile boolean prunedLogged;

    public static void install(final ClassLoader cl) {
        hookResponseBody(cl);
        hookAjxCallback(cl);
        hookAjxLoad(cl);
        hookRequestProbe(cl);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_install off=" + off() + " v=6");
        hookLsProbe(cl);
        hookSharedPrefs(cl);
        hookLoadData(cl);
    }

    private static boolean off() {
        try { return !Config.searchGrid(); } catch (Throwable t) { return false; }
    }

    // ══════════════════════════════════════════ Layer ① 数据层：整条剔除金刚位数组

    private static void hookResponseBody(final ClassLoader cl) {
        Class<?> resp = H.cls(cl, "com.autonavi.core.network.inter.response.HttpResponse");
        if (resp == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=HttpResponse");
            return;
        }
        H.hookSig(resp, "getResponseBodyString", "amap_search_grid_body", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object raw = chain.proceed();
                if (!(raw instanceof String)) return raw;
                try {
                    String body = (String) raw;
                    String url = urlOf(chain.getThisObject());
                    boolean hit = isSearchHome(url);
                    if (ONCE.add("b:" + System.identityHashCode(chain.getThisObject()))) {
                        // 取证：URL 拿不到时把 response/request 的真实形状打出来（每条响应一次）
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_body size=" + body.length()
                                + " url=" + url + " hit=" + hit + " off=" + off()
                                + " head=" + head(body));
                    }
                    if (hit && ONCE.add("sh:" + body.length())) {
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_afsearch len=" + body.length()
                                + " off=" + off() + " arrays=" + describeArrays(body));
                    }
                    boolean interesting = body.length() > 400 && body.indexOf("\"icon\"") > 0
                            || body.indexOf("\"iconUrl\"") > 0 || body.indexOf("\"img\"") > 0;
                    if ((hit || interesting) && ONCE.add("u:" + (url == null ? "" : url.hashCode())
                            + ":" + body.length())) {
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_scan hit=" + hit
                                + " len=" + body.length() + " arrays=" + describeArrays(body)
                                + " url=" + shorten(url));
                    }
                    if (!off()) return raw;
                    // ★ 响应侧：承载 quickLinkVoData 的那条响应就是渲染源（缓存只是旁路副本）
                    if (RESPONSE_STRIP && body.indexOf("quickLink") >= 0) {
                        if (ONCE.add("ql:" + (url == null ? "" : url.hashCode()))) {
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_ql url=" + shorten(url)
                                    + " len=" + body.length() + " off=" + off());
                        }
                        if (off()) {
                            String ql = emptyQuickLinkField(body);
                            if (ql != null) {
                                H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=response url="
                                        + shorten(url) + " len=" + body.length() + "->" + ql.length());
                                return ql;
                            }
                        }
                    }
                    String filtered = STRIP_ENABLED ? pruneSearchHome(body) : null;
                    return filtered == null ? raw : filtered;
                } catch (Throwable t) {
                    H.log(Log.WARN, MainHook.TAG, "event=search_grid_body_error " + t);
                    return raw;
                }
            }
        });
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=response_body");
    }

    /**
     * 取证钩子：{@code ModuleRequest$AjxCallback.onSuccess(AosResponse)} ——
     * AJX 网络响应落到 Java 的落点（DEX code_off=0x364600）。
     * 只读不写，用来确认「搜索页那次请求的 URL / 响应体形状」。
     */
    private static void hookAjxCallback(final ClassLoader cl) {
        Class<?> cb = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest$AjxCallback");
        Class<?> aos = H.cls(cl, "com.amap.bundle.aosservice.response.AosResponse");
        if (cb == null || aos == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=AjxCallback");
            return;
        }
        H.hookSig(cb, "onSuccess", "amap_search_grid_ajxcb", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object resp = chain.getArg(0);
                    String url = urlOf(resp);
                    if (ONCE.add("cb:" + System.identityHashCode(resp))) {
                        String body = null;
                        try { body = (String) resp.getClass().getMethod("getResponseBodyString").invoke(resp); }
                        catch (Throwable ignored) { }
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_cb url=" + url
                                + " len=" + (body == null ? -1 : body.length())
                                + " head=" + head(body));
                    }
                } catch (Throwable ignored) { }
                return chain.proceed();
            }
        }, aos);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=ajx_callback");
    }

    private static String head(String body) {
        if (body == null) return "null";
        String s = body.replace('\n', ' ');
        return s.length() <= 260 ? s : s.substring(0, 260) + "…";
    }

    /** 从响应对象上取请求 URL：HttpRequest / IAosRequest 两条链都试。 */
    private static String urlOf(Object response) {
        if (response == null) return null;
        Object req = null;
        for (String m : new String[]{"getRequest", "getAOSRequest"}) {
            try {
                req = response.getClass().getMethod(m).invoke(response);
                if (req != null) break;
            } catch (Throwable ignored) { }
        }
        if (req == null) return null;
        for (String name : new String[]{"getUrl", "getUrlString", "getOriginalUrl", "getHostUrl", "getApiName"}) {
            try {
                Object v = req.getClass().getMethod(name).invoke(req);
                if (v instanceof String && ((String) v).length() > 0) return (String) v;
            } catch (Throwable ignored) { }
        }
        for (Field f : req.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            if (!f.getName().toLowerCase().contains("url")) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(req);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) { }
        }
        return req.getClass().getSimpleName();
    }

    private static boolean isSearchHome(String url) {
        if (url == null || url.length() == 0) return false;
        for (String hint : URL_HINTS) if (url.contains(hint)) return true;
        return false;
    }

    private static String pruneSearchHome(String body) {
        if (body == null || body.length() < 32 || body.length() > 4 * 1024 * 1024) return null;
        if (body.toLowerCase().contains("toolbox")) return null;   // 首页工具栏的地盘，绝不碰
        char c = firstJsonChar(body);
        if (c != '{' && c != '[') return null;
        try {
            Object root = c == '{' ? new org.json.JSONObject(body) : new org.json.JSONArray(body);
            List<String> log = new ArrayList<String>();
            int n = pruneNode(root, "", 0, log);
            if (n <= 0) {
                if (ONCE.add("shape:" + body.length())) {
                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_shape keys=" + shapeOf(root));
                }
                return null;
            }
            if (!prunedLogged) {
                prunedLogged = true;
                H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=data_strip node=" + log);
            }
            return root.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int pruneNode(Object node, String key, int depth, List<String> log) {
        if (node == null || depth > 14) return 0;
        int removed = 0;
        try {
            if (node instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) node;
                for (int i = 0; i < arr.length(); i++) removed += pruneNode(arr.opt(i), key, depth + 1, log);
                if (looksLikeIconGrid(arr) && !isNoiseKey(key)) {
                    log.add(key + "(" + arr.length() + ")");
                    while (arr.length() > 0) arr.remove(0);
                    removed += 1;
                }
            } else if (node instanceof org.json.JSONObject) {
                org.json.JSONObject obj = (org.json.JSONObject) node;
                List<String> keys = new ArrayList<String>();
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext()) keys.add(it.next());
                for (String k : keys) removed += pruneNode(obj.opt(k), k, depth + 1, log);
            }
        } catch (Throwable ignored) { }
        return removed;
    }

    private static boolean isNoiseKey(String key) {
        if (key == null) return true;
        String k = key.toLowerCase();
        for (String bad : new String[]{"history", "recent", "hot", "suggest", "word"}) {
            if (k.contains(bad)) return true;
        }
        return false;
    }

    private static boolean looksLikeIconGrid(org.json.JSONArray arr) {
        int n = arr.length();
        if (n < 4 || n > 14) return false;
        int withText = 0, withIcon = 0;
        for (int i = 0; i < n; i++) {
            Object o = arr.opt(i);
            if (!(o instanceof org.json.JSONObject)) return false;
            org.json.JSONObject item = (org.json.JSONObject) o;
            if (shortText(item) != null) withText++;
            if (hasIcon(item)) withIcon++;
        }
        return withText >= n - 1 && withIcon >= n - 1;
    }

    private static String shortText(org.json.JSONObject o) {
        for (String key : new String[]{"name", "title", "text", "label", "show_name", "display_name"}) {
            String v = o.optString(key, null);
            if (v == null) continue;
            v = v.trim();
            if (v.length() > 0 && v.length() <= 10) return v;
        }
        return null;
    }

    private static boolean hasIcon(org.json.JSONObject o) {
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String raw = it.next();
            String k = raw.toLowerCase();
            if (k.contains("icon") || k.contains("img") || k.contains("image")
                    || k.contains("pic") || k.endsWith("url")) {
                Object v = o.opt(raw);
                if (v instanceof String && ((String) v).length() > 8) return true;
            }
        }
        return false;
    }

    /** URL 太长时只留 host + path，日志才读得动。 */
    private static String shorten(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        return base.length() > 90 ? base.substring(0, 90) + "…" : base;
    }

    /** 取证：把响应里所有数组的「键路径 + 长度 + 首元素键名」打出来（封顶 10 条）。 */
    private static String describeArrays(String body) {
        try {
            char c = firstJsonChar(body);
            if (c != '{' && c != '[') return "notjson";
            Object root = c == '{' ? new org.json.JSONObject(body) : new org.json.JSONArray(body);
            StringBuilder sb = new StringBuilder();
            walkArrays(root, "", 0, sb, new int[]{0});
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static void walkArrays(Object node, String path, int depth, StringBuilder sb, int[] n) {
        if (node == null || depth > 12 || n[0] >= 10) return;
        try {
            if (node instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) node;
                n[0]++;
                sb.append(path).append('(').append(arr.length()).append(')');
                Object first = arr.opt(0);
                if (first instanceof org.json.JSONObject) {
                    sb.append('{');
                    java.util.Iterator<String> it = ((org.json.JSONObject) first).keys();
                    int i = 0;
                    while (it.hasNext() && i++ < 8) sb.append(it.next()).append(',');
                    sb.append('}');
                }
                sb.append(' ');
                for (int i = 0; i < arr.length() && n[0] < 10; i++) walkArrays(arr.opt(i), path + "[]", depth + 1, sb, n);
            } else if (node instanceof org.json.JSONObject) {
                org.json.JSONObject obj = (org.json.JSONObject) node;
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext() && n[0] < 10) {
                    String k = it.next();
                    walkArrays(obj.opt(k), path.isEmpty() ? k : path + "." + k, depth + 1, sb, n);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static String shapeOf(Object root) {
        try {
            if (root instanceof org.json.JSONObject) {
                org.json.JSONObject o = (org.json.JSONObject) root;
                StringBuilder sb = new StringBuilder();
                java.util.Iterator<String> it = o.keys();
                int i = 0;
                while (it.hasNext() && i++ < 24) sb.append(it.next()).append(',');
                return sb.toString();
            }
            return "array(" + ((org.json.JSONArray) root).length() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static char firstJsonChar(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{' || c == '[') return c;
            if (!Character.isWhitespace(c)) return 0;
        }
        return 0;
    }

    // ══════════════════════════════════════════ Layer ② 视图层：容器级整块移除

    // ══════════════════════════════════════════ 渲染前剔除：AmapAjxView.loadInternal
    //
    // 取证结论（真机 23:57）：搜索页那排金刚位 = **首页工具栏同一份 toolbox payload**，
    // 模块的 toolVisible 早已判定这 6 项 false，但搜索页读的是未经过滤的那份 ⇒ 照样渲染。
    // 所以根治点 = 页面初始化数据（`data` 参数）在**渲染之前**就把这几项剔掉：
    // JS 从一开始就没有这排 ⇒ 不闪、不重演、无留白。
    //
    // 为什么钩 `loadInternal` 而不是 `load`：`load` 的 6 个重载全部汇到 `loadInternal`
    // （DEX classes5.dex code_off=0x4ac72c），一处钩子覆盖所有入口，且不会和本类
    // 已有的 `load` 钩子（负责排期扫描）互相叠加。
    //
    // 判定**不看文案**（服务端会改）：只看结构 —— 数组元素是对象、带 `id` 且带
    // `schema`/`iconUrl`/`icon` 之一，长度 4~14 ⇒ 认定为“一排带图标的工具入口”。

    private static final java.util.Set<String> DATA_SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void hookLoadData(final ClassLoader cl) {
        Class<?> ajx = H.cls(cl, "com.autonavi.minimap.ajx3.views.AmapAjxView");
        if (ajx == null) return;
        Class<?> map = H.cls(cl, "java.util.HashMap");
        H.hookSig(ajx, "loadInternal", "amap_search_grid_loaddata", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object path = chain.getArg(0);
                    if (!(path instanceof String) || !((String) path).contains(PAGE_HINT)) {
                        return chain.proceed();
                    }
                    Object data = chain.getArg(1);
                    logLoadData(data);
                    if (!off()) return chain.proceed();
                    Object stripped = stripToolbox(data);
                    if (stripped == null) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[1] = stripped;
                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=load_data cls="
                            + (data == null ? "null" : data.getClass().getSimpleName()));
                    return chain.proceed(args);
                } catch (Throwable t) {
                    H.log(Log.WARN, MainHook.TAG, "event=search_grid_loaddata_err " + t);
                    return chain.proceed();
                }
            }
        }, String.class, Object.class, String.class, String.class, int.class, int.class,
                String.class, long.class, map);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=load_data");
    }

    private static void logLoadData(Object data) {
        try {
            if (data == null) {
                if (DATA_SEEN.add("null")) H.log(Log.INFO, MainHook.TAG, "event=search_grid_data2 cls=null");
                return;
            }
            String s = String.valueOf(data);
            if (!DATA_SEEN.add(data.getClass().getSimpleName() + "|" + s.length())) return;
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_data2 cls="
                    + data.getClass().getSimpleName() + " len=" + s.length() + " head=" + lsHead(s));
        } catch (Throwable ignored) { }
    }

    /** 把 data 里“一排带图标的工具入口”清空；返回改过的对象，没改动返回 null。 */
    private static Object stripToolbox(Object data) {
        if (data == null) return null;
        try {
            if (data instanceof String) {
                String s = (String) data;
                char c = firstJsonChar(s);
                if (c != '{' && c != '[') return null;
                Object root = c == '{' ? new org.json.JSONObject(s) : new org.json.JSONArray(s);
                int n = pruneToolArrays(root, 0);
                return n <= 0 ? null : root.toString();
            }
            if (data instanceof org.json.JSONObject || data instanceof org.json.JSONArray) {
                int n = pruneToolArrays(data, 0);
                return n <= 0 ? null : data;
            }
            if (data instanceof java.util.Map) {
                int n = pruneMap((java.util.Map<?, ?>) data, 0);
                return n <= 0 ? null : data;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static int pruneToolArrays(Object node, int depth) {
        if (node == null || depth > 14) return 0;
        int removed = 0;
        try {
            if (node instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) node;
                for (int i = 0; i < arr.length(); i++) removed += pruneToolArrays(arr.opt(i), depth + 1);
                if (looksLikeToolList(arr)) {
                    while (arr.length() > 0) arr.remove(0);
                    removed++;
                }
            } else if (node instanceof org.json.JSONObject) {
                org.json.JSONObject o = (org.json.JSONObject) node;
                List<String> keys = new ArrayList<String>();
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) keys.add(it.next());
                for (String k : keys) removed += pruneToolArrays(o.opt(k), depth + 1);
            }
        } catch (Throwable ignored) { }
        return removed;
    }

    @SuppressWarnings("unchecked")
    private static int pruneMap(java.util.Map<?, ?> map, int depth) {
        if (map == null || depth > 14) return 0;
        int removed = 0;
        try {
            for (Object v : new ArrayList<Object>(map.values())) {
                if (v instanceof java.util.List) {
                    java.util.List<Object> list = (java.util.List<Object>) v;
                    if (looksLikeToolListOf(list)) { list.clear(); removed++; continue; }
                    for (Object item : list) removed += pruneMapItem(item, depth + 1);
                } else if (v instanceof java.util.Map) {
                    removed += pruneMap((java.util.Map<?, ?>) v, depth + 1);
                } else if (v instanceof String) {
                    String s = (String) v;
                    char c = firstJsonChar(s);
                    if (c == '{' || c == '[') {
                        Object root = c == '{' ? new org.json.JSONObject(s) : new org.json.JSONArray(s);
                        int n = pruneToolArrays(root, depth + 1);
                        if (n > 0) {
                            Object k = findKey(map, v);
                            if (k != null) ((java.util.Map) map).put(k, root.toString());
                            removed += n;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return removed;
    }

    private static int pruneMapItem(Object item, int depth) {
        if (item instanceof java.util.Map) return pruneMap((java.util.Map<?, ?>) item, depth);
        return 0;
    }

    private static Object findKey(java.util.Map<?, ?> map, Object value) {
        for (java.util.Map.Entry<?, ?> e : map.entrySet()) if (e.getValue() == value) return e.getKey();
        return null;
    }

    private static boolean looksLikeToolListOf(java.util.List<Object> list) {
        if (list == null || list.size() < 4 || list.size() > 14) return false;
        int ok = 0;
        for (Object item : list) {
            if (item instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) item;
                if (m.containsKey("id") && (m.containsKey("schema") || m.containsKey("iconUrl")
                        || m.containsKey("icon") || m.containsKey("imageUrl"))) ok++;
            }
        }
        return ok >= list.size() - 1;
    }

    private static boolean looksLikeToolList(org.json.JSONArray arr) {
        int n = arr.length();
        if (n < 4 || n > 14) return false;
        int ok = 0;
        for (int i = 0; i < n; i++) {
            Object o = arr.opt(i);
            if (!(o instanceof org.json.JSONObject)) return false;
            org.json.JSONObject item = (org.json.JSONObject) o;
            boolean hasId = item.has("id") || item.has("toolId") || item.has("tool_id");
            boolean hasIcon = item.has("schema") || item.has("iconUrl") || item.has("icon")
                    || item.has("imageUrl");
            if (hasId && hasIcon) ok++;
        }
        return ok >= n - 1;
    }

    private static void hookAjxLoad(final ClassLoader cl) {
        Class<?> ajx = H.cls(cl, "com.autonavi.minimap.ajx3.views.AmapAjxView");
        if (ajx == null) return;
        H.hookSig(ajx, "load", "amap_search_grid_load", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Object path = chain.getArg(0);
                    if (path instanceof String && ((String) path).contains(PAGE_HINT)) {
                        AmapData.markSearchPage(4000);   // 4s 窗口：这段时间内构建的 toolbox payload 跳过金刚位
                        Object self = chain.getThisObject();
                        if (self instanceof View) schedule((View) self);
                    }
                } catch (Throwable ignored) {}
                return result;
            }
        }, String.class, Object.class, String.class, String.class, int.class, int.class, String.class);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=ajx_load");
    }

    private static void schedule(final View page) {
        if (!VIEW_FALLBACK) return;      // 数据层已根治，不再事后摘容器（否则会闪一下）
        synchronized (DONE) {
            if (DONE.containsKey(page)) return;
        }
        for (int i = 0; i < PASS_DELAY_MS.length; i++) {
            final int pass = i;
            try {
                page.postDelayed(new Runnable() {
                    @Override public void run() { attempt(page, pass); }
                }, PASS_DELAY_MS[i]);
            } catch (Throwable ignored) { return; }
        }
    }

    private static void attempt(final View page, final int pass) {
        if (!off()) return;
        synchronized (DONE) {
            if (DONE.containsKey(page)) return;
        }
        try {
            List<View> rows = new ArrayList<View>();
            collectRows(page, rows, 0);
            if (rows.isEmpty()) {
                if (pass == PASS_DELAY_MS.length - 1) {
                    H.log(Log.WARN, MainHook.TAG, "event=search_grid_fail reason=no_container pass=" + pass);
                }
                return;
            }
            View row = topmost(rows);
            View block = absorbIndicator(row, page);
            int cells = row instanceof ViewGroup ? ((ViewGroup) row).getChildCount() : 0;
            removeBlock(page, block, cells, pass);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_error pass=" + pass + " err=" + t);
        }
    }

    private static void collectRows(View v, List<View> out, int depth) {
        if (v == null || depth > 26 || out.size() > 40) return;
        try {
            if (v.getVisibility() != View.VISIBLE) return;
            if (v instanceof ViewGroup && isIconRow((ViewGroup) v)) out.add(v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) collectRows(g.getChildAt(i), out, depth + 1);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isIconRow(ViewGroup g) {
        int n = g.getChildCount();
        if (n < MIN_CELLS || n > MAX_CELLS) return false;
        int rowH = g.getHeight();
        if (rowH < dp(g, ROW_MIN_DP) || rowH > dp(g, ROW_MAX_DP)) return false;

        int sumW = 0, laid = 0, top = Integer.MIN_VALUE, prevLeft = Integer.MIN_VALUE;
        int minW = Integer.MAX_VALUE, maxW = 0;
        for (int i = 0; i < n; i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE) return false;
            int w = c.getWidth(), h = c.getHeight();
            if (w <= 0 || h <= 0) return false;
            if (top == Integer.MIN_VALUE) top = c.getTop();
            if (Math.abs(c.getTop() - top) > dp(g, 8)) return false;
            if (c.getLeft() <= prevLeft) return false;
            prevLeft = c.getLeft();
            sumW += w; laid++;
            if (w < minW) minW = w;
            if (w > maxW) maxW = w;
        }
        if (laid < MIN_CELLS) return false;
        float mean = sumW / (float) laid;
        return (maxW - minW) <= mean * WIDTH_TOLERANCE;
    }

    private static View topmost(List<View> rows) {
        View best = null;
        int bestTop = Integer.MAX_VALUE;
        for (View v : rows) {
            int t = absTop(v);
            if (t < bestTop) { bestTop = t; best = v; }
        }
        return best;
    }

    private static int absTop(View v) {
        int y = 0;
        View cur = v;
        while (cur != null) {
            y += cur.getTop();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
            if (cur == null || y > 100000) break;
        }
        return y;
    }

    /**
     * 把外层「行 + 分页指示器」包起来的容器作为删除目标。
     *
     * <p>实测结构（真机 dump）：
     * <pre>
     *   Container 1017x223 c=2        ← 目标：行(184) + 指示器(39)
     *     Container 1016x184 c=1      ← 单子包装
     *       Container 1016x184 c=6    ← 6 个彩色方块（本函数入参 row）
     * </pre>
     * 规则：先穿过「只有一个可见子节点」的包装层（最多 4 层），
     * 再判断这一层除本行外的兄弟是否都是矮子（≤30dp）且总高不超过本行 + 60dp
     * ⇒ 这一层就是「行 + 指示器」，整块删它，指示器不会残留半条。
     */
    private static View absorbIndicator(View row, View page) {
        View cur = row;
        try {
            for (int level = 0; level < 4; level++) {
                ViewParent p = cur.getParent();
                if (!(p instanceof ViewGroup) || p == page) break;
                ViewGroup pg = (ViewGroup) p;
                int visible = 0;
                View only = null;
                for (int i = 0; i < pg.getChildCount(); i++) {
                    View c = pg.getChildAt(i);
                    if (c.getVisibility() != View.VISIBLE) continue;
                    visible++;
                    only = c;
                }
                if (visible == 1 && only == cur
                        && Math.abs(pg.getHeight() - cur.getHeight()) <= dp(cur, 4)) {
                    cur = pg;                       // 单子包装层，继续往上
                    continue;
                }
                // 这一层：判断是不是「行 + 矮指示器」
                int maxOther = 0;
                for (int i = 0; i < pg.getChildCount(); i++) {
                    View c = pg.getChildAt(i);
                    if (c == cur || c.getVisibility() != View.VISIBLE) continue;
                    maxOther = Math.max(maxOther, c.getHeight());
                }
                if (visible <= 3 && maxOther > 0 && maxOther <= dp(cur, INDICATOR_MAX_DP)
                        && pg.getHeight() <= cur.getHeight() + dp(cur, 60)) {
                    return pg;
                }
                break;
            }
        } catch (Throwable ignored) { }
        return cur;
    }

    /** 容器级移除：属性尝试链 → 最后从父容器摘掉。全程实测高度，不做遮挡。 */
    private static void removeBlock(final View page, final View block, final int cells, final int pass) {
        final String node = desc(block);
        if (block.getHeight() == 0) {
            done(page, "already_zero", node, cells, pass);
            return;
        }
        tryNext(page, block, cells, pass, node, 0);
    }

    private static void tryNext(final View page, final View block, final int cells,
                                final int pass, final String node, final int idx) {
        if (block.getHeight() == 0) {
            done(page, idx == 0 ? "already_zero" : ATTRS[idx - 1][0] + "=" + ATTRS[idx - 1][1],
                    node, cells, pass);
            return;
        }
        if (idx >= ATTRS.length) {
            // 逐层打印祖先高度（自证哪一层还占着位），并把仍然占位的单子包装层一起归零
            ViewParent up = block.getParent();
            for (int lv = 0; up instanceof View && lv < 4; lv++) {
                View pv = (View) up;
                int h = pv.getHeight();
                int cc = pv instanceof ViewGroup ? ((ViewGroup) pv).getChildCount() : -1;
                H.log(Log.INFO, MainHook.TAG, "event=search_grid_height lv=" + lv
                        + " node=" + desc(pv) + " h=" + h);
                if (h > 0 && cc <= 2) setAttr(pv, "display", "none");
                up = pv.getParent();
            }
            if (detach(block)) { done(page, "container_remove", node, cells, pass); return; }
            if (pass == PASS_DELAY_MS.length - 1) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_fail reason=no_effect node="
                        + node + " cells=" + cells + " h=" + block.getHeight());
            }
            return;
        }
        setAttr(block, ATTRS[idx][0], ATTRS[idx][1]);
        block.postDelayed(new Runnable() {
            @Override public void run() { tryNext(page, block, cells, pass, node, idx + 1); }
        }, VERIFY_MS);
    }

    private static boolean detach(View block) {
        try {
            ViewParent p = block.getParent();
            if (!(p instanceof ViewGroup)) return false;
            ViewGroup parent = (ViewGroup) p;
            parent.removeView(block);
            parent.requestLayout();
            parent.invalidate();
            return block.getParent() == null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void done(View page, String via, String node, int cells, int pass) {
        synchronized (DONE) { DONE.put(page, Boolean.TRUE); }
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=" + via
                + " node=" + node + " cells=" + cells + " pass=" + pass);
    }

    private static String desc(View v) {
        try {
            StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
            sb.append(' ').append(v.getWidth()).append('x').append(v.getHeight());
            if (v instanceof ViewGroup) sb.append(" c=").append(((ViewGroup) v).getChildCount());
            sb.append(" t=").append(absTop(v));
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ══════════════════════════════════════════ AJX 属性入口

    /**
     * 走 AJX DOM 节点改属性：{@code AjxDomTree.i(View)J} → {@code f(J)Lvr;} → {@code Lvr.B(name,value,Z,Z,Z)}。
     * DEX 实证 {@code Lvr.B} 体内先调 {@code ViewExtension.setAttribute(...)} 再 {@code s(...)} 把属性
     * 缓存进节点 —— 只调 setAttribute 不更新节点，引擎下一帧仍按旧值重排（实测“打了没反应”）。
     */
    private static boolean nodeSetAttr(View v, String name, Object value) {
        try {
            Class<?> tree = Class.forName("com.autonavi.minimap.ajx3.dom.AjxDomTree",
                    false, v.getClass().getClassLoader());
            Method idM = null;
            for (Method m : tree.getDeclaredMethods()) {
                if (m.getName().equals("i") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == View.class
                        && m.getReturnType() == long.class) { idM = m; break; }
            }
            if (idM == null) return false;
            idM.setAccessible(true);
            long nodeId = (Long) idM.invoke(null, v);
            if (nodeId == 0L) return false;
            Object ctx = v.getContext();
            if (ctx == null) return false;
            Object treeObj = null;
            try { treeObj = ctx.getClass().getMethod("getDomTree").invoke(ctx); } catch (Throwable ignored) { }
            if (treeObj == null) return false;
            Object node = null;
            for (Method m : treeObj.getClass().getMethods()) {
                if (!m.getName().equals("f") || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != long.class) continue;
                m.setAccessible(true);
                node = m.invoke(treeObj, nodeId);
                break;
            }
            if (node == null) return false;
            for (Method m : node.getClass().getMethods()) {
                if (!m.getName().equals("B")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 5 || p[0] != String.class || p[1] != Object.class) continue;
                m.setAccessible(true);
                m.invoke(node, name, value, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE);
                H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name + " via=dom_node");
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * v5：正确的 AJX 节点入口。
     *
     * <p>上一版从 {@code v.getContext()} 取 DomTree —— 那是 **Activity Context**，
     * 根本没有 {@code getDomTree()}，所以整条节点路线静默失败（日志里从没出现 via=dom_node）。
     * 正确来源是 AJX 自己的 {@code IAjxContext}：从视图树往上找到第一个带
     * {@code getAjxContext()} 的视图（就是 AmapAjxView），再 {@code getDomTree()}。
     * 拿到 DomTree 后用 {@code i(View)J} → {@code f(J)Lvr;} → {@code Lvr.B(name,value,Z,Z,Z)}。
     */
    private static boolean nodeSetAttr2(View v, String name, Object value) {
        try {
            Object treeObj = ajxTreeOf(v);
            if (treeObj == null) return false;
            Class<?> tree = treeObj.getClass();
            Method idM = null;
            for (Method m : tree.getMethods()) {
                if (m.getName().equals("i") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == View.class
                        && m.getReturnType() == long.class) { idM = m; break; }
            }
            if (idM == null) return false;
            idM.setAccessible(true);
            long nodeId = (Long) idM.invoke(treeObj, v);
            if (nodeId == 0L) return false;
            Object node = null;
            for (Method m : tree.getMethods()) {
                if (!m.getName().equals("f") || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != long.class) continue;
                m.setAccessible(true);
                node = m.invoke(treeObj, nodeId);
                break;
            }
            if (node == null) return false;
            for (Method m : node.getClass().getMethods()) {
                if (!m.getName().equals("B")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 5 || p[0] != String.class || p[1] != Object.class) continue;
                m.setAccessible(true);
                m.invoke(node, name, value, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE);
                H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name
                        + " via=dom_node node=" + nodeId);
                return true;
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_node_err " + t);
        }
        return false;
    }

    /** 从视图树往上找 AmapAjxView 的 IAjxContext → getDomTree()。 */
    private static Object ajxTreeOf(View v) {
        View cur = v;
        for (int i = 0; cur != null && i < 12; i++) {
            try {
                Method g = cur.getClass().getMethod("getAjxContext");
                Object ctx = g.invoke(cur);
                if (ctx != null) {
                    Object tree = ctx.getClass().getMethod("getDomTree").invoke(ctx);
                    if (tree != null) return tree;
                }
            } catch (Throwable ignored) { }
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return null;
    }

    private static void setAttr(View v, String name, Object value) {
        if (nodeSetAttr2(v, name, value)) return;
        if (nodeSetAttr(v, name, value)) return;
        for (Class<?> k = v.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Class<?> iface : allTypes(k)) {
                Method m = findSetAttribute(iface, 6);
                if (m != null) {
                    try {
                        m.setAccessible(true);
                        m.invoke(v, name, value, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE);
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name
                                + " via=" + iface.getSimpleName() + "/6");
                        return;
                    } catch (Throwable ignored) { }
                }
                m = findSetAttribute(iface, 3);
                if (m != null) {
                    try {
                        m.setAccessible(true);
                        m.invoke(v, name, value, Boolean.TRUE);
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name
                                + " via=" + iface.getSimpleName() + "/3");
                        return;
                    } catch (Throwable ignored) { }
                }
            }
        }
        H.log(Log.WARN, MainHook.TAG, "event=search_grid_attr_miss name=" + name
                + " cls=" + v.getClass().getName());
    }

    private static Method findSetAttribute(Class<?> type, int arity) {
        try {
            for (Method m : type.getMethods()) {
                if (!"setAttribute".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (arity == 6 && p.length == 6 && p[0] == String.class && p[1] == Object.class
                        && p[2] == boolean.class && p[3] == boolean.class
                        && p[4] == boolean.class && p[5] == boolean.class) return m;
                if (arity == 3 && p.length == 3 && p[0] == String.class && p[1] == Object.class
                        && p[2] == boolean.class) return m;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static List<Class<?>> allTypes(Class<?> k) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                out.add(i);
                for (Class<?> j : i.getInterfaces()) out.add(j);
            }
        }
        return out;
    }

    private static int dp(View v, int value) {
        try { return (int) (value * v.getResources().getDisplayMetrics().density); }
        catch (Throwable t) { return value * 3; }
    }

    // ══════════════════════════════════════════ 探针

    // ══════════════════════════════════════════ 取证：AJX 本地存储（找金刚位配置的 module/key）
    //
    // 为什么只钩这些：`getItemSync` / `getItemASYNC` 已被 AmapProtocolHooks.installTools 占用
    // （toolbox 过滤，nav-fix 写域），本类**不碰**，避免同方法多钩把别人的功能顶掉。
    // 写入侧 + 批量读 + 加密读已经足够定位「那排配置存在哪个 module 的哪个 key」。

    private static final java.util.Set<String> LS_SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final int LS_LOG_MAX = 80;
    private static int lsCount;

    private static void hookLsProbe(final ClassLoader cl) {
        Class<?> store = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage");
        Class<?> cb = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        if (store == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=AjxModuleLocalStorage");
            return;
        }
        // 写入侧：配置被缓存进存储的瞬间
        H.hookSig(store, "setItemSync", "amap_search_grid_ls_set", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try { lsLog("setItemSync", chain.getArg(0), chain.getArg(1), chain.getArg(2)); }
                catch (Throwable ignored) { }
                return chain.proceed();
            }
        }, String.class, String.class, Object.class);
        // 批量读（同步）：整个桶都回来了，最可能直接看到那排配置
        H.hookSig(store, "getAllItemsEncryptedSync", "amap_search_grid_ls_allenc", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                try { lsLog("getAllItemsEncryptedSync", chain.getArg(0), "-", r); } catch (Throwable ignored) { }
                return r;
            }
        }, String.class);
        // 模块是否存在（用来枚举页面到底碰了哪些 module）
        H.hookSig(store, "preferenceExist", "amap_search_grid_ls_exist", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                try { lsLog("preferenceExist", chain.getArg(0), "-", r); } catch (Throwable ignored) { }
                return r;
            }
        }, String.class);
        if (cb != null) {
            H.hookSig(store, "getItem", "amap_search_grid_ls_get", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try { lsLog("getItem", chain.getArg(0), chain.getArg(1), null); } catch (Throwable ignored) { }
                    return chain.proceed();
                }
            }, String.class, String.class, cb);
            H.hookSig(store, "getItemEncrypted", "amap_search_grid_ls_getenc", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try { lsLog("getItemEncrypted", chain.getArg(0), chain.getArg(1), null); }
                    catch (Throwable ignored) { }
                    return chain.proceed();
                }
            }, String.class, String.class, cb);
            H.hookSig(store, "getAllItems", "amap_search_grid_ls_all", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try { lsLog("getAllItems", chain.getArg(0), "-", null); } catch (Throwable ignored) { }
                    return chain.proceed();
                }
            }, String.class, cb);
        }
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=ls_probe");
    }

    /** 一行一条，op+module+key 去重，总量封顶，避免刷爆 logcat。 */
    private static void lsLog(String op, Object module, Object key, Object value) {
        if (lsCount >= LS_LOG_MAX) return;
        String m = module == null ? "null" : String.valueOf(module);
        String k = key == null ? "null" : String.valueOf(key);
        if (!LS_SEEN.add(op + "|" + m + "|" + k)) return;
        synchronized (AmapSearchGrid.class) {
            if (lsCount >= LS_LOG_MAX) return;
            lsCount++;
        }
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_ls op=" + op + " module=" + m
                + " key=" + k + " len=" + (value == null ? -1 : String.valueOf(value).length())
                + " head=" + lsHead(value));
    }

    /** 值的头部片段（200 字，换行压成空格），足够看清 JSON 结构。 */
    private static String lsHead(Object value) {
        if (value == null) return "null";
        String s;
        try { s = String.valueOf(value); } catch (Throwable t) { return "?"; }
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    // ══════════════════════════════════════════ 读侧：AJX 存储 helper（真正的剔除点）
    //
    // 取证结论：搜索页配置的存储 module = `cachedSearchHomeData`（preferenceExist 命中）。
    // `AjxModuleLocalStorage.getItemSync` 被 toolbox 占用，但它的底层实现是
    // `Lwm3;->c(String,String)String`（DEX: classes5.dex code_off=0x4057c0，helper 的单键读），
    // 这个类没有任何其它模块碰过 ⇒ 在这里过滤，既不抢别人的钩子，也拿到同一份数据。

    private static final java.util.Set<String> HELPER_SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static int helperLogs;

    private static void hookStorageHelper(final ClassLoader cl) {
        // 混淆名会重排：国内 wm3.c，Play 17.02.2.2014 = lv3.c（候选表 AmapData.PREFS_HELPER）。
        final String[] helperAt = new String[2];
        Class<?> helper = H.anchor(cl, AmapData.PREFS_HELPER, helperAt);
        if (helper == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=prefs_helper");
            return;
        }
        // 单键读：c(String key, String def) -> String
        H.hookSig(helper, helperAt[1], "amap_search_grid_helper_get", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                if (!(r instanceof String)) return r;
                String value = (String) r;
                try {
                    String module = moduleOf(chain.getThisObject());
                    if (helperLogs < 40 && HELPER_SEEN.add(module + "|" + chain.getArg(0))) {
                        helperLogs++;
                        H.logv("event=search_grid_helper op=get module=" + module
                                + " key=" + chain.getArg(0) + " len=" + value.length()
                                + " head=" + lsHead(value));
                    }
                    if (!off()) return r;
                    if (module != null && !module.toLowerCase().contains("searchhome")) return r;
                    if (value.toLowerCase().contains("toolbox")) return r;
                    String filtered = pruneSearchHome(value);
                    if (filtered == null) return r;
                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=data_strip key="
                            + chain.getArg(0));
                    return filtered;
                } catch (Throwable t) {
                    return r;
                }
            }
        }, String.class, String.class);
        // 整桶读：b() -> HashMap（同一条数据源的批量路径）
        H.hookSig(helper, "b", "amap_search_grid_helper_all", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                try {
                    String module = moduleOf(chain.getThisObject());
                    if (helperLogs < 40 && HELPER_SEEN.add(module + "|ALL")) {
                        helperLogs++;
                        H.logv("event=search_grid_helper op=all module=" + module
                                + " keys=" + (r instanceof java.util.Map
                                ? ((java.util.Map<?, ?>) r).keySet() : "?"));
                    }
                } catch (Throwable ignored) { }
                return r;
            }
        });
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=storage_helper");
    }

    /** 反射读 helper 实例上的 module 名（wm3 只有 2 个字段，其中一个就是它）。 */
    private static String moduleOf(Object helper) {
        if (helper == null) return "null";
        try {
            for (Field f : helper.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(helper);
                if (v instanceof String) return (String) v;
            }
        } catch (Throwable ignored) { }
        return "?";
    }

    // ══════════════════════════════════════════ JS 取证：把 AJX 页面的 JS 源码落盘
    //
    // APK 里的 `assets/ajx.bundle/bundles.oajx`(74MB) 与设备缓存
    // `files/ajx-biz/local_bundles_map.bin` 都是加密的（熵 7.98、无明文 bundle 名），
    // 静态取不到。唯一确定性入口 = 运行时把喂给引擎的 JS 源码抓下来。
    // `AmapAjxView.loadJs(ng3)` 是 bundle 对象进入渲染前的落点（DEX code_off=0x4aca2c）。

    private static int jsDumpCount;

    private static void hookJsDump(final ClassLoader cl) {
        Class<?> ajx = H.cls(cl, "com.autonavi.minimap.ajx3.views.AmapAjxView");
        Class<?> bundle = H.cls(cl, "ng3");
        if (ajx == null || bundle == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=loadJs/ng3");
            return;
        }
        H.hookSig(ajx, "loadJs", "amap_search_grid_jsdump", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof View && jsDumpCount < 6) {
                        Object b = chain.getArg(0);
                        String src = biggestString(b);
                        if (src != null && src.length() > 500) {
                            jsDumpCount++;
                            writeDump((View) self, "ajx_dump_" + jsDumpCount + ".js", src);
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_jsdump n=" + jsDumpCount
                                    + " len=" + src.length() + " cls=" + b.getClass().getSimpleName());
                        }
                    }
                } catch (Throwable t) {
                    H.log(Log.WARN, MainHook.TAG, "event=search_grid_jsdump_err " + t);
                }
                return chain.proceed();
            }
        }, bundle);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=js_dump");
    }

    /** 反射取对象里最长的 String 字段（bundle 的 JS 源码就在其中）。 */
    private static String biggestString(Object obj) {
        String best = null;
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (!(v instanceof String)) continue;
                    String s = (String) v;
                    if (best == null || s.length() > best.length()) best = s;
                } catch (Throwable ignored) { }
            }
        }
        return best;
    }

    private static void writeDump(View v, String name, String src) {
        try {
            java.io.File dir = v.getContext().getFilesDir();
            java.io.File out = new java.io.File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(src.getBytes("UTF-8"));
            fos.close();
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_jsdump_path " + out.getAbsolutePath());
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_jsdump_write_err " + t);
        }
    }

    // ══════════════════════════════════════════ 根治点：cachedSearchHomeData.QuickLinkVODataV2-internal
    //
    // 取证（Lead 在设备上挖穿私有目录）：搜索页那排金刚位的唯一数据源是
    //   /data/data/com.autonavi.minimap/shared_prefs/cachedSearchHomeData.xml
    //   key = QuickLinkVODataV2-internal
    //   {"quickLinkVoData":{"list":[20 项 {title,icon,schema,badge,log_param,…}]}, "cacheVerification":{…}}
    // 20 个 title = 美食/酒店/景点门票/加油充电/超划算/扫街榜/充电站/厕所/…（图标全来自 optimus-ads.amap.com
    // ⇒ 这排是广告位）。此前所有「toolbox / AJX storage / 网络响应」假设因此全部作废。
    //
    // 落点：SharedPreferencesImpl.getString —— 框架层读接口，与 nav-fix 的
    // AjxModuleLocalStorage 钩子零冲突。
    //
    // 手法：**字符串级手术**，不做 JSON 往返 —— 该值里含 HTML 转义（&quot;）和
    // 大量 log_param/__trackInfo__ 字段，往返序列化会改变字节、可能让高德的缓存校验失败。
    // 这里只把 "list":[ … ] 的内容替换成空，其余字节原样保留。

    private static final String SP_KEY = "QuickLinkVODataV2-internal";
    private static final java.util.Set<String> STRIP_LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    // ══════════════════════════════════════════ 写侧探针：谁把 quickLink 写进缓存
    //
    // `cachedSearchHomeData.xml` 是服务端写进来的（mtime 随刷新变化）。
    // 写入路径：Editor.putString → EditorImpl.mMap → commit/apply → SharedPreferencesImpl。
    // 这里钩 EditorImpl.putString，命中 key 时打印长度 + 调用栈 ⇒ 直接指出发起该请求的代码。

    private static int spWriteLogs;

    private static void hookSpWriter(final ClassLoader cl) {
        Class<?> editor = H.cls(cl, "android.app.SharedPreferencesImpl$EditorImpl");
        if (editor == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=EditorImpl");
            return;
        }
        H.hookSig(editor, "putString", "amap_search_grid_spwrite", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object key = chain.getArg(0);
                    Object val = chain.getArg(1);
                    if (SP_KEY.equals(key) && spWriteLogs < 3) {
                        spWriteLogs++;
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_spwrite key=" + key
                                + " len=" + (val == null ? -1 : String.valueOf(val).length())
                                + " stack=" + stack());
                    }
                } catch (Throwable ignored) { }
                return chain.proceed();
            }
        }, String.class, String.class);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=sp_writer");
    }

    /** 调用栈前 12 帧（过滤本模块帧，只留业务/框架）。 */
    private static String stack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("io.github.ldxm666")) continue;
                if (c.startsWith("java.lang.Thread")) continue;
                if (c.startsWith("io.github.libxposed")) continue;
                sb.append(c).append('#').append(e.getMethodName()).append(' ');
                if (++n >= 12) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ══════════════════════════════════════════ 写侧根治：AJX 存储写入时把 quickLinkVoData 清空
    //
    // 真机调用栈实证（2026-10-26 00:xx）：
    //   wm3#f → AjxModuleLocalStorage#setItemInner → #setItemASYNC → #handleMessage → HandlerThread
    // ⇒ 这份缓存是 **JS 侧** 调 `ajxLocalStorage.setItem('cachedSearchHomeData',
    //   'QuickLinkVODataV2-internal', json)` 写进来的，值由 JS 从服务端响应里拿到。
    // 所以在 setItemASYNC 上把它换成 `quickLinkVoData:{}`（list 变 undefined），
    // 缓存里就永远没有那 20 项，页面按「服务端没下发」处理。

    private static final String LS_MODULE_SEARCH_HOME = "cachedSearchHomeData";
    private static int writeStripLogs;

    private static void hookStorageWrite(final ClassLoader cl) {
        Class<?> store = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage");
        Class<?> cb = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        if (store == null || cb == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=setItemASYNC");
            return;
        }
        H.hookSig(store, "setItemASYNC", "amap_search_grid_lswrite", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object module = chain.getArg(0);
                    Object key = chain.getArg(1);
                    Object val = chain.getArg(2);
                    if (LS_MODULE_SEARCH_HOME.equals(String.valueOf(module))
                            && SP_KEY.equals(String.valueOf(key))
                            && val instanceof String
                            && WRITE_STRIP && !Config.searchGrid()) {
                        String stripped = emptyQuickLinkField((String) val);
                        if (stripped != null) {
                            Object[] args = chain.getArgs().toArray();
                            args[2] = stripped;
                            if (writeStripLogs++ < 3) {
                                H.log(Log.INFO, MainHook.TAG, "event=search_grid_write_strip"
                                        + " module=" + module + " key=" + key
                                        + " len=" + ((String) val).length() + " -> " + stripped.length());
                            }
                            return chain.proceed(args);
                        }
                    }
                } catch (Throwable t) {
                    H.log(Log.WARN, MainHook.TAG, "event=search_grid_write_strip_err " + t);
                }
                return chain.proceed();
            }
        }, String.class, String.class, Object.class, cb);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=storage_write");
    }

    /**
     * 把 {@code "quickLinkVoData":{...}} 换成 {@code "quickLinkVoData":{}} —— 让 {@code list} 变 undefined。
     * 字符串级手术，其余字节一个不动（该值含 {@code &quot;} 转义与大量 log_param 字段，
     * JSON 往返会改字节、可能让高德缓存校验失败）。
     */
    private static String emptyQuickLinkField(String raw) {
        if (raw == null) return null;
        int p = raw.indexOf("\"quickLink\"");
        if (p < 0) p = raw.indexOf("quickLinkVoData");
        if (p < 0) { stripLog("skip", "no_field", -1); return null; }
        int open = raw.indexOf('{', p);
        if (open < 0) { stripLog("skip", "no_brace", -1); return null; }
        int close = matchBrace(raw, open);
        if (close < 0) { stripLog("skip", "no_close_brace", -1); return null; }
        int items = countOccurrences(raw.substring(open, close), "\"title\"");
        String out = raw.substring(0, open + 1) + raw.substring(close);
        stripLog("done", "ok", items);
        return out;
    }

    /** 从 open（指向 '{'）找到配对的 '}'，跳过字符串与转义。 */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static void hookSharedPrefs(final ClassLoader cl) {
        hookStorageWrite(cl);
        hookSpWriter(cl);
        Class<?> sp = H.cls(cl, "android.app.SharedPreferencesImpl");
        if (sp == null) {
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=SharedPreferencesImpl");
            return;
        }
        H.hookSig(sp, "getString", "amap_search_grid_sp", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                if (!(r instanceof String)) return r;
                try {
                    if (!SP_KEY.equals(chain.getArg(0))) return r;
                    if (true) {   // ★ 读侧已撤销：实测「清空/返回 null」会让页面回落到 JS 预载包默认 6 项（另一批广告），有害 ⇒ 一律透传
                        stripLog("skip", "switch_on", -1);
                        return r;
                    }
                    // ★ 变体 B（实测）：**不返回空 list，而是让整个 key「不存在」**。
                    // 原因：把 list 清空会让 AJX 页回落到它预载包里写死的默认 6 项
                    //       （美食/酒店/景点门票/加油站/休闲玩乐/超市）—— 那同样是广告位，
                    //       等于换了另一批广告，比不清更糟。
                    // 返回 null = 页面认为「没有缓存」，不给它任何可回落的空数组。
                    stripLog("done", "return_null", countOccurrences((String) r, "\"title\""));
                    return null;
                } catch (Throwable t) {
                    stripLog("skip", "error", -1);
                    return r;
                }
            }
        }, String.class, String.class);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=shared_prefs");
    }

    /**
     * 把 {@code "list":[ … ]} 的内容清空，其余字节一个不动。
     * 解析失败/结构不符 ⇒ 返回 null（调用方原样返回，绝不破坏缓存读取）。
     */
    private static String emptyQuickLinkList(String raw) {
        if (raw == null || raw.length() < 32) return null;
        int listKey = raw.indexOf("\"list\"");
        if (listKey < 0) { stripLog("skip", "no_list_key", -1); return null; }
        int open = raw.indexOf('[', listKey);
        if (open < 0) { stripLog("skip", "no_open", -1); return null; }
        int close = matchBracket(raw, open);
        if (close < 0) { stripLog("skip", "no_close", -1); return null; }
        String body = raw.substring(open + 1, close);
        int items = countOccurrences(body, "\"title\"");
        if (items <= 0) { stripLog("skip", "empty_already", 0); return null; }
        String out = raw.substring(0, open + 1) + raw.substring(close);
        stripLog("done", "ok", items);
        return out;
    }

    /** 从 open（指向 '['）找到配对的 ']'，跳过字符串内的括号与转义。 */
    private static int matchBracket(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int countOccurrences(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static void stripLog(String what, String reason, int items) {
        if (!STRIP_LOGGED.add(what + "|" + reason + "|" + items)) return;
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_strip src=sp key=" + SP_KEY
                + " " + (items >= 0 ? ("items=" + items + " -> 0") : ("skip reason=" + reason)));
    }

    private static void hookRequestProbe(final ClassLoader cl) {
        hookStorageHelper(cl);
        Class<?> req = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest");
        if (req == null) return;
        H.hookSig(req, "fetch", "amap_search_grid_fetch", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object opt = chain.getArg(1);
                    if (opt instanceof String) {
                        String o = (String) opt;
                        int u = o.indexOf("\"url\"");
                        String pick = o;
                        if (u >= 0) pick = o.substring(u, Math.min(o.length(), u + 180));
                        if (SEEN_URLS.size() < URL_LOG_MAX && SEEN_URLS.add(pick)) {
                            H.log(Log.INFO, MainHook.TAG, "event=ajx_fetch_opt " + pick.replace('\n', ' '));
                        }
                    }
                } catch (Throwable ignored) { }
                return chain.proceed();
            }
        }, String.class, String.class, H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback"));
        H.hookSig(req, "optionsToRequestInfo", "amap_search_grid_probe", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object url = chain.getArg(0);
                    if (url instanceof String) noteUrl((String) url);
                } catch (Throwable ignored) {}
                return chain.proceed();
            }
        }, String.class, String.class);
        H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=probe");
    }

    private static void noteUrl(String url) {
        try {
            if (!isSearchHome(url)) {
                // 取证期：把所有 amap 域名的 AJX 请求也记下来（封顶），用于找 quickLink 的来源
                if (url == null || url.indexOf("amap.com") < 0) return;
                if (SEEN_URLS.size() >= URL_LOG_MAX) return;
                int q = url.indexOf('?');
                String base = q < 0 ? url : url.substring(0, q);
                if (!SEEN_URLS.add(base)) return;
                H.log(Log.INFO, MainHook.TAG, "event=ajx_req_all url=" + base);
                return;
            }
            if (SEEN_URLS.size() >= URL_LOG_MAX || !SEEN_URLS.add(url)) return;
            H.log(Log.INFO, MainHook.TAG, "event=ajx_req url=" + url);
        } catch (Throwable ignored) { }
    }
}
