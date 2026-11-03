package io.github.ldxm666.bmapclean;

import android.app.Application;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地点详情页三块推广位（猜你想搜 / 周边推荐 / 底部「推荐附近」信息流）—— **数据层摘除**。
 *
 * <p>══════════════════════════════════════════════════════════════════════
 * 定位结论（22.1.0 / 1660：dex 反编译 + 真机双重取证）
 * <p>══════════════════════════════════════════════════════════════════════
 * 这一页**不是原生 View 拼的**，是本地 H5 模板，挂在二级容器里：
 * <pre>
 *   LContainerCordovaWebView.loadByComponent(comName, comJson, appendPrams)
 *     → url = "file://" + g(comName) + "/index.html"
 *     → g(comName) = ComFramework.getWebTemplatePath(ComToken)
 *     → 落盘目录 files/component3/runtime/map.android.baidu.comdetailtmpl_&lt;ver&gt;/running/web
 * </pre>
 * 页面里每一块都是 {@code cards/*.umd.min.js} 一个独立卡片，卡片清单由
 * {@code js/app.*.js} 的三个 Vuex mutation 收口：
 * <pre>
 *   setPageData        {tabs, groupDataList, cards}
 *   setHeadGroupList   {headGroupList}
 *   setOverlayGroupList{overlayCardList}
 * </pre>
 * 渲染侧是 {@code CardsContainer} 的 {@code v-for="group in groupDataList"}，
 * 每个卡片先渲染一个 {@code <article id="phoenix_dom_<name>">} 占位壳，再 mount 进去。
 *
 * <p>所以这一页真正的「数据层」= 三个 mutation 的入参数组。按 {@code card_info.name}
 * 把卡片从数组里摘掉，占位 {@code <article>} 根本不会被 v-for 生成 ⇒ **天然不留白**，
 * 也不需要 {@code setVisibility(GONE)} / {@code removeView} / 手动 {@code requestLayout}。
 * 等价于原生页里把 item 从 {@code getItemCount()} 里去掉。
 *
 * <p>══════════════════════════════════════════════════════════════════════
 * 做法
 * <p>══════════════════════════════════════════════════════════════════════
 * 页面加载时把 {@code file://} URL 指到我们**打好补丁的模板副本**（原目录一个字不动，
 * 和 {@link BaiduTalos} 的做法一致）。补丁只在三个 mutation 的入参上套一层按名过滤：
 * <pre>
 *   setPageData:function(e,t){var a=t||{},n=a.tabs,r=a.groupDataList,o=a.cards;
 *       try{r=window.__mcFN(r);o=window.__mcFN(o);}catch(x){}     ← 新增
 *       e.tabs=n||[],e.groupDataList=r||[],e.cards=o||[]}
 * </pre>
 * 过滤函数 {@code window.__mcFN} 同时在 {@code document.title} 上留一份
 * {@code mc:<本页出现过的卡片名>}，这是本模块对这一页唯一的「可观测出口」——
 * H5 里没有 Java 回调，uiautomator 抓 WebView 的 text 就能读到它，用来验证
 * 过滤真的发生在数据层、以及名字有没有写错。
 *
 * <p>失效安全：模板目录对不上 / 补丁锚点找不到 / 目录不可写 / 配置读不到
 * ⇒ 一律返回原 URL，页面保持原样，只记一条 miss。
 */
final class BaiduPoiAds {

    /** 本地 H5 模板的补丁 schema；动画/结构变了改这个值即可强制重建缓存。 */
    private static final String SCHEMA = "poi-detail-cards-v1";
    /** 单次复制的模板体积上限（实测 web 目录 5.8MB）。 */
    private static final long MAX_BYTES = 48L * 1024 * 1024;
    /** 模板目录特征：必须是 component3/runtime 下的详情模板。 */
    private static final String RUNTIME_MARK = "/files/component3/runtime/";
    /** app.js 体积上限。 */
    private static final long MAX_APP_JS = 4L * 1024 * 1024;

    /**
     * 开关 → 卡片名（真机 dump 出来的 {@code card_info.name}，一行可挂多个）。
     *
     * <p>补丁生效后由 {@code document.title=mc:...} 回读校验：那一串里出现的名字
     * 就是这一页真实装载过的卡片。改版后如果名字漂了，回读串里会看到新名字而
     * {@code block=... removed=0}，据此更新这张表。
     *
     * <p>{@code houseabout} / {@code housebasenew} 是「在线房源」的两种实现
     * （普通点 / 小区点各取其一），所以同一个开关下挂两个名字。
     */
    private static final String[][] CARDS = {
            {Spec.K_POI_GUESS, "querycard"},
            {Spec.K_POI_REC, "surroundingrecom"},
            {Spec.K_POI_CARDS, "mediafeedcard"},
            {Spec.K_POI_HOUSE, "houseabout", "housebasenew"},
            {Spec.K_POI_ESTATE, "houseaboutheat"},
            {Spec.K_POI_LIFE, "cityservice"},
            // 运营/推广位：带「广告」角标的热门服务推荐、商户招募、流量包、好车推荐、同类好店，
            // 以及住宅小区页的三个房产导流块（价格卡 / 购房群 / 咨询顾问）。
            {Spec.K_POI_MISC, "adcpccard", "businesssettled", "liuliangbaozhuanshu",
                    "nearbycarsale", "meglocalrelated",
                    "housephoneeventbus", "goldhousenewlayout", "housewechat", "houseconsultant",
                    // 「周边配套」（地图 + 交通/医院/餐饮/购物/学校 tab）：用户明确要求去掉。
                    "housenearby",
                    // 「大家都在问 · 立即咨询 / 咨询更多问题」：按语义消元首选，待真机确认。
                    "merchantaimergeqapop",
                    // 房价总览（15125元/m² / 在售16套）+「立即咨询」CTA —— 用户选定连数据一起摘。
                    "houseoverview"},
    };

    private static final Map<String, String> PREPARED = new LinkedHashMap<String, String>();
    private static final AtomicInteger LOGS = new AtomicInteger();
    private static final AtomicInteger PROBES = new AtomicInteger();
    private static volatile boolean missLogged;

    private BaiduPoiAds() {}

    static void install(ClassLoader cl) {
        // 这一页的 WebView 有两代宿主：二级容器 LContainerCordovaWebView，和更早的
        // ComWebView / DefaultWebView（都继承 CustomWebView，最终都是 android.webkit.WebView）。
        // 真机实测只挂 LContainerCordovaWebView 一次都没触发 —— 长按地图出来的那一页
        // 走的是 ComWebView 那条链。所以这里从基类一路挂到框架类，谁先接住谁改；
        // 同一条 URL 被多级接住也无害：第二级拿到的已经是缓存目录，不含模板特征，直接放行。
        String[] classes = {
                "com.baidu.baidumaps.poi.newpoi.second.widget.LContainerCordovaWebView",
                "com.baidu.mapframework.component.webview.ComWebView",
                "com.baidu.mapframework.braavos.core.DefaultWebView",
                "com.baidu.mapframework.braavos.CordovaWebView",
                "com.baidu.baidumaps.base.widget.CustomWebView",
                "android.webkit.WebView",
        };
        for (String name : classes) {
            Class<?> type = H.cls(cl, name);
            BaiduHooks.hook(type, "loadUrl", "poi_webview_" + type, chain -> intercept(chain), String.class);
        }
    }

    /** 所有 WebView#loadUrl 入口共用的钩子体。 */
    private static Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
        Object raw = chain.getArg(0);
        String replaced = null;
        if (raw instanceof String) {
            try {
                replaced = rewrite((String) raw);
            } catch (Throwable error) {
                logMiss("rewrite", error.getClass().getSimpleName());
            }
        }
        return replaced == null ? chain.proceed() : chain.proceed(new Object[]{replaced});
    }

    // ── 配置 ───────────────────────────────────────────────────────────

    /** 当前要摘掉的卡片名；所有开关都关 ⇒ 空数组（完全不接管页面）。 */
    static String[] hiddenCards() {
        List<String> out = new ArrayList<String>();
        for (String[] row : CARDS) {
            if (Cfg.visible(row[0], Spec.defaultVisible(row[0]))) continue;
            for (int i = 1; i < row.length; i++) if (!out.contains(row[i])) out.add(row[i]);
        }
        return out.toArray(new String[0]);
    }

    /**
     * 总闸：{@code setprop debug.bmapclean.poi_patch 0} 即整段关掉（下次进页面生效）。
     *
     * <p>默认**开**（这个特性已经把 A / A2 交出去了，默认关等于功能消失）；
     * 出问题时不需要换包，一条 setprop + 重开百度就能回到官方原样。
     */
    private static boolean patchEnabled() {
        String value = systemProperty("debug.bmapclean.poi_patch");
        if (value == null || value.length() == 0) return true;
        return !("0".equals(value) || "false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value));
    }

    private static String systemProperty(String key) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── URL 改写 ────────────────────────────────────────────────────────

    /**
     * 只改写「本地详情模板的 index.html」这一种 URL，其它一律返回 null 原样放行。
     *
     * @return 改写后的 URL，或 null 表示不动
     */
    private static String rewrite(String url) throws Exception {
        if (!patchEnabled()) return null;
        String[] names = hiddenCards();
        if (url == null) return null;
        if (names.length == 0) return null;
        probe(url);
        if (!url.startsWith("file://") || url.indexOf("/index.html") < 0) return null;
        int cut = url.indexOf("/index.html");
        String dirPath = url.substring("file://".length(), cut);
        String suffix = url.substring(cut);              // "/index.html?xxx#yyy"
        File web = new File(dirPath);
        if (!web.isDirectory()) return null;
        String canonical = web.getCanonicalPath();
        if (!detailTemplate(canonical)) return null;
        File appJs = findAppJs(web);
        if (appJs == null) return null;

        File patched = prepare(web, appJs, names);
        if (patched == null) return null;
        String tmpl = canonical.substring(canonical.indexOf(RUNTIME_MARK) + RUNTIME_MARK.length(),
                canonical.length() - "/running/web".length());
        logOnce(tmpl, names.length);
        return "file://" + patched.getAbsolutePath() + suffix;
    }

    /**
     * 严格白名单：只认
     * {@code /data/{user/0,data}/<pkg>/files/component3/runtime/<com>detailtmpl_<ver>/running/web}。
     *
     * <p>其它任何 {@code file://}（相册、离线包、swan 小程序…）与全部 {@code http(s)://}
     * 一律原样放行 —— 这里多拦一层，是为了让「只改这一份模板」这件事在代码上可审计，
     * 而不是靠注释保证。
     */
    private static boolean detailTemplate(String canonical) {
        if (!canonical.startsWith("/data/user/0/") && !canonical.startsWith("/data/data/")) return false;
        int at = canonical.indexOf(RUNTIME_MARK);
        if (at < 0) return false;
        String rest = canonical.substring(at + RUNTIME_MARK.length());
        String[] parts = rest.split("/");
        if (parts.length != 3) return false;                       // <com> / running / web
        if (!parts[0].startsWith(TEMPLATE_PREFIX)) return false;
        return "running".equals(parts[1]) && "web".equals(parts[2]);
    }

    /** 只认详情页模板这一个组件前缀。 */
    private static final String TEMPLATE_PREFIX = "map.android.baidu.comdetailtmpl_";

    /**
     * 诊断：把「看起来像本地模板」的 URL 打几条出来（限频）。
     *
     * <p>这一页的 WebView 宿主换一代，{@code loadUrl} 的调用方就换一个类 ——
     * 没有这行日志就只能靠猜。只在 URL 里出现 {@code index.html} 或
     * {@code component3/runtime} 时打，正常情况下每次开页 1 条。
     */
    private static void probe(String url) {
        if (PROBES.get() >= 8) return;
        if (url.indexOf("index.html") < 0 && url.indexOf("component3/runtime") < 0) return;
        PROBES.incrementAndGet();
        String text = url.length() > 300 ? url.substring(0, 300) + "…" : url;
        H.log("event=poi_ad_probe url=" + text.replace('\n', ' '));
    }

    private static File findAppJs(File web) {        File js = new File(web, "js");
        File[] files = js.listFiles();
        if (files == null) return null;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("app.") && name.endsWith(".js") && file.isFile()
                    && file.length() > 1024 && file.length() < MAX_APP_JS) return file;
        }
        return null;
    }

    // ── 打补丁的模板副本 ────────────────────────────────────────────────

    private static synchronized File prepare(File web, File appJs, String[] names) throws Exception {
        String key = cacheKey(web, appJs, names);
        String cached = PREPARED.get(key);
        if (cached != null) {
            File dir = new File(cached);
            if (new File(dir, ".mc-ok").isFile()) return dir;
        }
        Application app = application();
        if (app == null) return null;
        File base = new File(app.getCacheDir(), "mcpoi").getCanonicalFile();
        File out = new File(base, key.substring(0, 32));
        if (new File(out, ".mc-ok").isFile()) {
            PREPARED.put(key, out.getAbsolutePath());
            return out;
        }
        mkdirs(base);
        File temp = new File(base, key.substring(0, 32) + "-" + android.os.Process.myPid()
                + "-" + System.nanoTime());
        mkdirs(temp);
        long copied = copyTree(web, temp, 0);
        if (copied > MAX_BYTES) throw new IOException("Template too large");
        File copiedApp = new File(temp, "js/" + appJs.getName());
        String original = read(copiedApp);
        String patched = patch(original, names);
        if (patched.equals(original)) {
            logMiss("anchor", appJs.getName());
            return null;
        }
        write(copiedApp, patched);
        write(new File(temp, ".mc-ok"), (SCHEMA + "\n" + key + "\n" + names.length
                + "\n" + copied).getBytes("UTF-8"));
        if (!temp.renameTo(out) && !new File(out, ".mc-ok").isFile()) {
            throw new IOException("Cannot publish template cache");
        }
        PREPARED.put(key, out.getAbsolutePath());
        H.log("event=poi_ad_prepare template=" + web.getName() + " bytes=" + copied
                + " cards=" + names.length);
        return out;
    }

    private static String cacheKey(File web, File appJs, String[] names) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        feed(digest, SCHEMA);
        feed(digest, web.getCanonicalPath());
        feed(digest, String.valueOf(appJs.length()));
        feed(digest, String.valueOf(appJs.lastModified()));
        // 人为失效盐：setprop debug.bmapclean.poi_schema <任意值> 即整批重建缓存，
        // 不用改包、不用等模板版本号变。
        String salt = systemProperty("debug.bmapclean.poi_schema");
        if (salt != null && salt.length() > 0) feed(digest, "salt=" + salt);
        for (String name : names) feed(digest, "card=" + name);
        StringBuilder out = new StringBuilder();
        char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : digest.digest()) {
            out.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
        }
        return out.toString();
    }

    private static void feed(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /** 递归复制（只复制普通文件与目录，符号链接跳过）。返回累计字节数。 */
    private static long copyTree(File source, File target, long total) throws IOException {
        File[] children = source.listFiles();
        if (children == null) throw new IOException("Cannot list " + source.getName());
        for (File child : children) {
            File destination = new File(target, child.getName());
            if (child.isDirectory()) {
                mkdirs(destination);
                total = copyTree(child, destination, total);
            } else if (child.isFile()) {
                total += child.length();
                if (total > MAX_BYTES) throw new IOException("Template too large");
                copy(child, destination);
            }
        }
        return total;
    }

    private static void copy(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static String read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private static void write(File file, String text) throws IOException {
        write(file, text.getBytes("UTF-8"));
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void mkdirs(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory.getName());
        }
    }

    private static Application application() {
        try {
            // ActivityThread 是 @hide，android.jar 里没有；按名字反射（同 BaiduPoiLayer）。
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── JS 补丁 ────────────────────────────────────────────────────────

    /**
     * 给 app.js 打补丁：注入过滤函数 + 在三个卡片清单 mutation 的入参上套用它。
     *
     * <p>定位用「方法名 + 方法体里取的是哪个 store 字段」，不依赖局部变量名
     * （压缩产物每次发版都会把 {@code r}/{@code o} 换个字母）。
     */
    static String patch(String source, String[] names) {
        String body = inject(source, "setPageData", ".groupDataList", ".cards");
        body = inject(body, "setHeadGroupList", ".headGroupList");
        body = inject(body, "setOverlayGroupList", ".overlayCardList");
        if (body.equals(source)) return source;         // 一个锚点都没命中 ⇒ 不动，交给上层记 miss
        return preamble(names) + body;
    }

    /**
     * 过滤函数本体。同时把「这一页到底出现过哪些卡片名」记进 {@code window.__mcS}，
     * 由 {@code __mcStr()} 写到 {@code document.title} 上 —— H5 里没有 Java 回调，
     * 这是本页唯一的回读通道（uiautomator 抓 WebView 的 text 即可）。
     */
    private static String preamble(String[] names) {
        StringBuilder out = new StringBuilder(1024);
        out.append("window.__mcB={");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) out.append(',');
            out.append('"').append(names[i]).append("\":1");
        }
        out.append("};window.__mcG={};window.__mcF={};");
        // 回读通道：g=groupDataList（**真正会被 v-for 渲染**的清单），
        // f=扁平的 cards 数组（含预加载/埋点用的定义，不一定有 UI）。
        // 两个名单分开记，才能回答「某个名字到底是不是可渲染卡片」。
        out.append("window.__mcStr=function(){try{document.title='mc:g='")
                .append("+Object.keys(window.__mcG).join(',')+';f='+Object.keys(window.__mcF).join(',');")
                .append("}catch(e){}};");
        out.append("window.__mcFN=function(l){try{");
        out.append("if(!l||!l.length)return l;var o=[];");
        out.append("var take=function(c,flat){var n=(c&&c.card_info&&c.card_info.name)||'';");
        out.append("if(n)(flat?window.__mcF:window.__mcG)[n]=1;return !window.__mcB[n];};");
        out.append("for(var i=0;i<l.length;i++){var g=l[i];if(!g)continue;var cs=g.groupCards;");
        out.append("if(cs&&cs.length){var k=[];for(var j=0;j<cs.length;j++){if(take(cs[j],false))k.push(cs[j]);}");
        out.append("if(!k.length)continue;");           // 整组被摘空 ⇒ 连组壳一起丢掉，不留白
        out.append("g.groupCards=k;o.push(g);}");
        out.append("else if(take(g,true))o.push(g);}");
        out.append("return o;}catch(e){return l;}};");
        return out.toString();
    }

    /**
     * 在 {@code name:function(...){var a=t||{},x=a.<prop1>,y=a.<prop2>; ...}} 的
     * 变量声明之后插入一行「把这两个局部变量换成过滤后的数组」。
     *
     * <p>只按「方法名 + 取的是哪个 store 字段」定位，不认局部变量名 ——
     * 压缩产物每次发版都会把 {@code r}/{@code o} 换个字母。
     */
    private static String inject(String source, String method, String... props) {
        int index = source.indexOf(method + ":function(");
        if (index < 0) return source;
        int end = source.indexOf(';', index);          // "var a=t||{},n=a.tabs,...;"
        if (end < 0 || end - index > 400) return source;
        String decl = source.substring(index, end);
        StringBuilder calls = new StringBuilder();
        for (String prop : props) {
            int at = decl.indexOf(prop);
            if (at < 0) continue;
            // 声明形如 "var a=t||{},n=a.tabs,r=a.groupDataList,o=a.cards"：
            // prop 左边是接收者（a.），真正的目标变量在最近一个 '=' 的左边。
            int eq = decl.lastIndexOf('=', at);
            if (eq < 1) continue;
            int start = eq - 1;
            while (start >= 0 && isIdent(decl.charAt(start))) start--;
            String variable = decl.substring(start + 1, eq);
            if (variable.isEmpty() || !Character.isJavaIdentifierStart(variable.charAt(0))) continue;
            if (calls.indexOf(variable + "=window.__mcFN(") >= 0) continue;
            if (calls.length() > 0) calls.append(',');
            calls.append(variable).append("=window.__mcFN(").append(variable).append(')');
        }
        if (calls.length() == 0) return source;
        String hook = ";try{" + calls + ";window.__mcStr();}catch(x){}";
        return source.substring(0, end) + hook + source.substring(end);
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    // ── 日志（限频） ────────────────────────────────────────────────────

    private static void logOnce(String template, int cards) {
        if (LOGS.getAndIncrement() >= 4) return;
        H.log("event=poi_tmpl_patch hit=true patch=on tmpl=" + template + " cards=" + cards);
        for (String[] row : CARDS) {
            if (Cfg.visible(row[0], Spec.defaultVisible(row[0]))) continue;
            StringBuilder names = new StringBuilder();
            for (int i = 1; i < row.length; i++) {
                if (names.length() > 0) names.append('+');
                names.append(row[i]);
            }
            H.log("event=poi_ad_removed block=" + blockName(row[0]) + " how=data card="
                    + names + " template=" + template);
        }
    }

    private static String blockName(String key) {
        if (Spec.K_POI_GUESS.equals(key)) return "guess_search";
        if (Spec.K_POI_REC.equals(key)) return "nearby_rec";
        if (Spec.K_POI_CARDS.equals(key)) return "nearby_cards";
        if (Spec.K_POI_HOUSE.equals(key)) return "house_listing";
        if (Spec.K_POI_ESTATE.equals(key)) return "nearby_estate";
        if (Spec.K_POI_LIFE.equals(key)) return "community_life";
        if (Spec.K_POI_MISC.equals(key)) return "misc_promo";
        return key;
    }

    private static void logMiss(String reason, String detail) {
        if (missLogged) return;
        missLogged = true;
        H.log("event=poi_ad_miss block=all reason=" + reason + " detail=" + detail);
    }
}
