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
 *
 * <h3>缓存：宁可广告，不可白屏（2.3.1 修复）</h3>
 * 真机出过「地点详情页整页空白，清掉 {@code cache/mcpoi} 才正常」。做法改成四条硬约束：
 * <ol>
 *   <li><b>键按内容算</b>（{@link #treeFingerprint}），不按路径 / mtime ——
 *       模板目录名只带组件版本，卡片却是服务端热更的，版本不变内容照换。</li>
 *   <li><b>原子发布</b>：先在 {@code <key>-<pid>-<nano>} 里建完，逐文件核对长度
 *       （{@link #verifyCopy}），{@code .mc-ok} 最后写，再整体 rename。
 *       任何一步中断都只留下「缺标记」的目录，下次必被判 invalid 并删除。</li>
 *   <li><b>命中先校验</b>（{@link #check}）：schema / key / 模板指纹 / app.js 里有没有
 *       注入标记，四样缺一就重建。旧副本、半成品、换过内容的模板全在这一步拦掉。</li>
 *   <li><b>失败降级到不注入</b>：{@link #prepare} 返回 null ⇒ 用宿主原模板加载。
 *       广告会回来，但页面一定能开。</li>
 * </ol>
 * 日志出口：{@code poi_cache hit= reason=} / {@code poi_patch_fallback reason=} /
 * {@code poi_ad_rule}（**规则回显**，不是"已删除"）。
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
            // 22.1.20 把「猜你想搜」换了实现：老名字 querycard 在这一版**已经不存在**，
            // 同一个 UI 位改成 commonnearbyrecomm（真机回读 g= 列表里只有后者）。
            // 两个名字都留着：22.1.0 及以前命 querycard，22.1.20 起命 commonnearbyrecomm。
            //
            // ⚠ **现状（2026-11-06 真机实测）：摘掉它页面没有任何可见变化。**
            // r= 通道证明它确实从渲染列表里消失了（g−r 里能看到它），但「AI推荐·你可能想搜」
            // 那一块照旧显示 ⇒ 那块不是它渲染的。同类情况还有 faqcard、poilocalrec、
            // commentareacard、ctservicecardnew、overlaycard、carcouponsnew —— 一共 7 个名字
            // 逐个屏蔽，五次截图逐像素没变。结论：那几块来自另一个模板（同屏还加载了
            // searchList / startPage），不是卡片名的锅。判据见 logs 的 poi_tmpl_seen。
            //
            // 保留这个名字的理由：它在别的 POI 类型下可能真的渲染「你可能想搜」，而我们只验过
            // 广场类一种。等 poi_tmpl_seen 证明「看错模板」之后，再决定去留 —— 现在删掉等于
            // 丢掉一种可能性。
            {Spec.K_POI_GUESS, "querycard", "commonnearbyrecomm"},
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
                    "houseoverview",
                    // faqcard：22.1.20 的新名字，摘掉后 r= 里确实消失，但页面上
                    // 「停车怎么收费 / 附近有什么地道美食」那组仍显示 —— 与 commonnearbyrecomm
                    // 同因（见上）。保留待用 poi_tmpl_seen 定性。
                    "faqcard"},
    };

    private static final Map<String, String> PREPARED = new LinkedHashMap<String, String>();
    private static final AtomicInteger LOGS = new AtomicInteger();
    private static final AtomicInteger PROBES = new AtomicInteger();
    /** 缓存命中/重建日志预算（每进程 8 条）。 */
    private static final AtomicInteger CACHE_LOG = new AtomicInteger();
    /** 降级日志预算（每进程 4 条）。白屏与「广告没去掉」必须一眼能分开。 */
    private static final AtomicInteger FALLBACK_LOG = new AtomicInteger();
    /**
     * 模板取证日志预算（每进程 12 条）与去重集合。
     *
     * <p>存在的理由：同一屏里可以加载好几个 H5 模板（detailtmpl / searchList /
     * startPage…），而我们只改写其中前缀匹配的那**一个**。用户看到的那几块推广
     * 如果来自另一个模板，那我们把卡片名加多少遍都不会有变化 —— 这件事必须能从
     * 日志一眼看出来，而不是靠猜。**只记录，不改变任何判定。**
     */
    private static final AtomicInteger SEEN_LOG = new AtomicInteger();
    private static final java.util.Set<String> SEEN_NAMES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 判定规则每进程只打一条。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RULE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 缓存最多留几份（每份约 5.8MB，cache 配额有限）。 */
    private static final int KEEP_CACHE = 2;
    /**
     * 本进程**交给过 WebView** 的缓存目录（绝对路径）。prune 永不删这些。
     *
     * <p>为什么需要：详情页打开后还会懒加载 `cards/*.umd.min.js`，WebView 可能
     * 正从某份目录里读文件。把它删掉 ⇒ 页面白屏 —— 和我们这次要修的 bug 同一个
     * 症状、不同的成因。所以「同进程里用过的目录」一律不删。
     */
    private static final java.util.Set<String> LIVE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 上次清理时间；0 = 本进程还没清过。 */
    private static volatile long prunedAt;
    /** 两次清理的最小间隔 —— 不要每开一次页就扫一遍目录。 */
    private static final long PRUNE_INTERVAL_MS = 10L * 60L * 1000L;
    /** 目录至少闲置这么久才允许删（给正在懒加载的 WebView 留足时间）。 */
    private static final long PRUNE_MIN_AGE_MS = 10L * 60L * 1000L;
    /** 补丁注入后 app.js 里必然出现的标记；命中校验靠它判断「这份副本真的打过补丁」。 */
    private static final String MARKER = "window.__mcFN=function(";
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

    /**
     * 回读通道扩展开关：{@code setprop debug.bmapclean.poi_rendered 1}。
     *
     * <p>打开后 {@code document.title} 会多一段 {@code ;r=<真正留下来渲染的卡片名>}，
     * 和 {@code g=}（检查过的）做差集，就能算出「哪些卡片被我们摘掉了」以及
     * 「哪些卡片页面渲染了但表里没有」。默认关闭：{@code r=} 会让 title 变长三倍。
     */
    private static boolean renderedChannel() {
        String value = systemProperty("debug.bmapclean.poi_rendered");
        if (value == null || value.length() == 0) return false;
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
        if (!detailTemplate(canonical)) {
            // 不是我们认的那份模板：照样记一条，patched=0。这正是「页面变了、日志里
            // 却什么都没有」的那类情况的证据来源。
            seenTemplate(canonical, false, rejectReason(canonical));
            return null;
        }
        File appJs = findAppJs(web);
        if (appJs == null) return null;
        // cards/ 为空**不是**残副本的判据：内置包里的 5.8.9 模板本来就不带 cards，
        // 卡片是服务端下发的。这里只记一条，照常注入 —— 真下完卡片后模板指纹会变，
        // 缓存自动重建并补上过滤。反过来（空就放弃注入）会让清理静默失效，更糟。
        File[] cardFiles = new File(web, "cards").listFiles();
        if (cardFiles == null || cardFiles.length == 0) {
            H.log("event=poi_cache cards_empty=1 note=patch_continues");
        }

        File patched;
        try {
            patched = prepare(web, appJs, names);
        } catch (Throwable error) {
            fallback("prepare", error);
            return null;                       // 用原模板加载：宁可广告，不可白屏
        }
        if (patched == null) {
            seenTemplate(canonical, false, "prepare_failed");
            return null;
        }
        seenTemplate(canonical, true, "detail_ok");
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
        String why = rejectReason(canonical);
        boolean accept = why == null;
        // 每进程一条：我们到底认哪些路径、为什么拒 —— 排查「改了没效果」的第一现场。
        if (RULE_LOGGED.compareAndSet(false, true)) {
            H.log("event=poi_tmpl_rule accept=" + accept + " why=" + (accept ? "detail_ok" : why));
        }
        return accept;
    }

    /** @return {@code null} 表示接受；否则是不接受的原因（进 {@code poi_tmpl_rule} 日志） */
    private static String rejectReason(String canonical) {
        if (!canonical.startsWith("/data/user/0/") && !canonical.startsWith("/data/data/")) {
            return "outside_app_data";
        }
        int at = canonical.indexOf(RUNTIME_MARK);
        if (at < 0) return "no_runtime_mark";
        String rest = canonical.substring(at + RUNTIME_MARK.length());
        String[] parts = rest.split("/");
        if (parts.length != 3) return "shape";                     // <com> / running / web
        if (!parts[0].startsWith(TEMPLATE_PREFIX)) return "prefix_mismatch";
        if (!"running".equals(parts[1]) || !"web".equals(parts[2])) return "tail_mismatch";
        return null;
    }

    /**
     * 记录「这一屏加载了哪个模板、我们有没有改它」。
     *
     * <p>同名模板每进程只打一条，总量封顶 {@code 12} 条。字段：
     * <pre>
     *   event=poi_tmpl_seen name=map.android.baidu.comdetailtmpl_5.8.9
     *                       path_tail=…/running/web/index.html patched=1 why=detail_ok
     * </pre>
     * {@code patched=0} + {@code why=prefix_mismatch} 就是「这块 UI 不是我们改的那份」的铁证。
     */
    private static void seenTemplate(String canonical, boolean patched, String why) {
        String name = templateName(canonical);
        if (!SEEN_NAMES.add(name)) return;
        if (SEEN_LOG.getAndIncrement() >= 12) return;
        String tail = canonical.length() > 64
                ? "…" + canonical.substring(canonical.length() - 64) : canonical;
        H.log("event=poi_tmpl_seen name=" + name + " path_tail=" + tail
                + " patched=" + (patched ? "1" : "0") + " why=" + why);
    }

    /** 从 {@code …/runtime/map.android.baidu.<name>_<ver>/running/web} 里取模板名；取不到返回 {@code ?}。 */
    private static String templateName(String canonical) {
        int at = canonical.indexOf("/runtime/");
        if (at < 0) return "?";
        String rest = canonical.substring(at + "/runtime/".length());
        int slash = rest.indexOf('/');
        String name = slash > 0 ? rest.substring(0, slash) : rest;
        return name.length() == 0 ? "?" : name;
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
        String fingerprint;
        try {
            fingerprint = treeFingerprint(web);
        } catch (Throwable error) {
            fallback("fingerprint", error);
            return null;
        }
        String key = cacheKey(web, fingerprint, names, renderedChannel());

        String cached = PREPARED.get(key);
        if (cached != null) {
            File dir = new File(cached);
            String bad = check(dir, key, fingerprint);
            if (bad == null) {
                logCache(true, "memory", key);
                LIVE.add(dir.getAbsolutePath());
                return dir;
            }
            PREPARED.remove(key);
        }

        Application app = application();
        if (app == null) {
            fallback("no_context", null);
            return null;
        }
        File base = new File(app.getCacheDir(), "mcpoi").getCanonicalFile();
        File out = new File(base, key.substring(0, 32));
        String bad = check(out, key, fingerprint);
        if (bad == null) {
            PREPARED.put(key, out.getAbsolutePath());
            LIVE.add(out.getAbsolutePath());
            logCache(true, "disk", key);
            return out;
        }
        // 命中但不可用（旧版本残留 / 半成品 / 模板已变）：先整份删掉再重建。
        // 半成品留在那里只会让下一次也白屏，删掉才是唯一安全动作。
        if (out.exists()) {
            deleteTree(out);
            logCache(false, "invalid:" + bad, key);
        } else {
            logCache(false, "miss", key);
        }

        mkdirs(base);
        File temp = new File(base, key.substring(0, 32) + "-" + android.os.Process.myPid()
                + "-" + System.nanoTime());
        boolean published = false;
        try {
            mkdirs(temp);
            long copied = copyTree(web, temp, 0);
            if (copied > MAX_BYTES) throw new IOException("Template too large");
            verifyCopy(web, temp);                       // 逐文件核对长度，半截副本当场发现
            File copiedApp = new File(temp, "js/" + appJs.getName());
            String original = read(copiedApp);
            String patched = patch(original, names, renderedChannel());
            if (patched.equals(original)) {
                logMiss("anchor", appJs.getName());
                return null;
            }
            write(copiedApp, patched);
            // `.mc-ok` 永远最后写：没有它 = 目录不可用。写它之前的任何中断都留下
            // 一份「缺标记」的目录，下次 check() 直接判 invalid 并删除重建。
            write(new File(temp, ".mc-ok"), (SCHEMA + "\n" + key + "\n" + fingerprint
                    + "\n" + names.length + "\n" + copied).getBytes("UTF-8"));
            if (out.exists()) deleteTree(out);           // rename 到已存在的目录会失败
            if (!temp.renameTo(out)) throw new IOException("Cannot publish template cache");
            published = true;
            PREPARED.put(key, out.getAbsolutePath());
            LIVE.add(out.getAbsolutePath());
            H.log("event=poi_ad_prepare template=" + web.getName() + " bytes=" + copied
                    + " cards=" + names.length + " key=" + key.substring(0, 12));
        } finally {
            if (!published) deleteTree(temp);            // 失败不留垃圾，也不留半成品
        }
        pruneOld(base);
        return out;
    }

    /**
     * 校验一份缓存目录是否**可用**。
     *
     * @return {@code null} 表示可用；否则返回不可用的原因（进日志）
     */
    private static String check(File dir, String key, String fingerprint) {
        if (dir == null || !dir.isDirectory()) return "no_dir";
        File ok = new File(dir, ".mc-ok");
        if (!ok.isFile()) return "no_marker";
        String text;
        try {
            text = read(ok);
        } catch (Throwable t) {
            return "unreadable";
        }
        String[] lines = text.split("\n");
        if (lines.length < 4) return "short_marker";
        if (!SCHEMA.equals(lines[0].trim())) return "schema";
        if (!key.equals(lines[1].trim())) return "key";
        // 模板内容变了 ⇒ 这份副本是按旧模板打的，注入点可能对不上 ⇒ 必须重建
        if (!fingerprint.equals(lines[2].trim())) return "stale_template";
        File appJs = findAppJs(dir);
        if (appJs == null) return "no_appjs";
        try {
            if (read(appJs).indexOf(MARKER) < 0) return "unpatched";
        } catch (Throwable t) {
            return "unreadable_appjs";
        }
        return null;
    }

    /**
     * 逐文件核对「复制出来的目录」和源目录的长度一致。
     *
     * <p>白屏那次的直接嫌疑就是半截副本：`component3` 在首次打开时才把模板下载到
     * runtime 目录，我们恰好赶上它写到一半，于是复制出一份缺文件的目录、还写上了
     * `.mc-ok`。这一步让那种情况当场失败并降级到原模板，而不是发布出去。
     */
    private static void verifyCopy(File source, File target) throws IOException {
        File[] children = source.listFiles();
        if (children == null) throw new IOException("Cannot list source");
        for (File child : children) {
            File copy = new File(target, child.getName());
            if (child.isDirectory()) {
                if (!copy.isDirectory()) throw new IOException("Missing dir " + child.getName());
                verifyCopy(child, copy);
            } else if (child.isFile()) {
                if (!copy.isFile() || copy.length() != child.length()) {
                    throw new IOException("Short copy " + child.getName());
                }
            }
        }
    }

    /**
     * 缓存键 = 模板内容的指纹，不是路径也不是 mtime。
     *
     * <p>为什么必须按内容：模板目录名里带的是**组件版本**（`...detailtmpl_5.8.9`），
     * 而卡片清单是服务端热更的 —— 版本号不动、内容照换。用路径或 mtime 做键，
     * 换完内容还会命中旧副本，注入点的结构和卡片名全对不上。
     *
     * <p>为什么不做全量哈希：5.8MB 每次开页 SHA 一遍要几十毫秒，而卡片文件名本身
     * 就带内容哈希（`adcpccard.0a8faa….umd.min.js`）—— 遍历目录取「文件名 + 长度」
     * 已经能捕捉到任何内容变化，代价是几百次 stat。
     */
    private static String treeFingerprint(File web) throws Exception {
        java.util.List<String> rows = new java.util.ArrayList<String>(512);
        collect(web, web, rows);
        java.util.Collections.sort(rows);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < rows.size(); i++) feed(digest, rows.get(i));
        // 「路径|长度」抓不到「同名同长度、内容不同」。我们**真正会改写**的只有
        // index.html 与 app.js 两个小文件（1.7KB / 166KB），给它们补一份内容哈希，
        // 代价约 1ms；卡片 JS 不补 —— 它们的文件名里本来就带内容哈希
        // （adcpccard.0a8faa….umd.min.js），而且卡片是运行时注入的，不走这两份文件。
        feed(digest, "index.html=" + fileDigest(new File(web, "index.html")));
        File app = findAppJs(web);
        feed(digest, "app.js=" + (app == null ? "-" : fileDigest(app)));
        StringBuilder out = new StringBuilder();
        char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : digest.digest()) out.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
        return out.toString();
    }

    /** 单文件内容 SHA-256（读不到返回 {@code "-"}，绝不抛）。 */
    private static String fileDigest(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder out = new StringBuilder();
            char[] hex = "0123456789abcdef".toCharArray();
            for (byte b : digest.digest()) out.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
            return out.toString();
        } catch (Throwable t) {
            return "-";
        }
    }

    private static void collect(File root, File dir, java.util.List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String rel = child.getAbsolutePath().substring(root.getAbsolutePath().length());
            if (child.isDirectory()) collect(root, child, out);
            else if (child.isFile()) out.add(rel + "|" + child.length());
        }
    }

    /**
     * 只保留最近 {@link #KEEP_CACHE} 份缓存，其余删掉（cache 配额是有限的）。
     *
     * <p>删错一份正在被 WebView 读的目录 = 白屏，所以这里**三道保险全过才动手**：
     * <ol>
     *   <li>{@link #LIVE} 里的（本进程交给过 WebView 的）不删；</li>
     *   <li>mtime 距今不足 {@link #PRUNE_MIN_AGE_MS} 的不删 —— 可能是另一个进程
     *       正在写、或者页面刚打开还没加载完卡片；</li>
     *   <li>两次清理间隔不足 {@link #PRUNE_INTERVAL_MS} 直接返回 —— 不每页扫目录。</li>
     * </ol>
     * 换句话说：**拿不准就不删**。多占几 MB 比白屏便宜得多。
     */
    private static void pruneOld(File base) {
        long now = System.currentTimeMillis();
        long last = prunedAt;
        if (last != 0 && now - last < PRUNE_INTERVAL_MS) return;
        prunedAt = now;
        try {
            File[] all = base.listFiles();
            if (all == null || all.length <= KEEP_CACHE) return;
            java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    long x = a.lastModified(), y = b.lastModified();
                    return x < y ? 1 : (x == y ? 0 : -1);        // 新 → 旧
                }
            });
            for (int i = KEEP_CACHE; i < all.length; i++) {
                File dir = all[i];
                if (LIVE.contains(dir.getAbsolutePath())) continue;
                if (now - dir.lastModified() < PRUNE_MIN_AGE_MS) continue;
                deleteTree(dir);
                H.log("event=poi_cache pruned=" + dir.getName());
            }
        } catch (Throwable ignored) {
            // 清理失败不影响正确性，只是占点空间。
        }
    }

    private static void deleteTree(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void logCache(boolean hit, String reason, String key) {
        if (CACHE_LOG.getAndIncrement() >= 8) return;
        H.log("event=poi_cache hit=" + (hit ? "1" : "0") + " reason=" + reason
                + " key=" + key.substring(0, 12));
    }

    /** 任何一步失败都走这里：记一条可检索的降级原因，然后**用原模板加载**。 */
    private static void fallback(String reason, Throwable error) {
        if (FALLBACK_LOG.getAndIncrement() >= 4) return;
        H.log("event=poi_patch_fallback reason=" + reason
                + (error == null ? "" : " err=" + error.getClass().getSimpleName()));
    }

    private static String cacheKey(File web, String fingerprint, String[] names,
                                  boolean rendered) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        feed(digest, SCHEMA);
        feed(digest, web.getCanonicalPath());
        feed(digest, fingerprint);
        feed(digest, rendered ? "r=1" : "r=0");
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
    static String patch(String source, String[] names, boolean rendered) {
        String body = inject(source, "setPageData", ".groupDataList", ".cards");
        body = inject(body, "setHeadGroupList", ".headGroupList");
        body = inject(body, "setOverlayGroupList", ".overlayCardList");
        if (body.equals(source)) return source;         // 一个锚点都没命中 ⇒ 不动，交给上层记 miss
        return preamble(names, rendered) + body;
    }

    /**
     * 过滤函数本体。同时把「这一页到底出现过哪些卡片名」记进 {@code window.__mcS}，
     * 由 {@code __mcStr()} 写到 {@code document.title} 上 —— H5 里没有 Java 回调，
     * 这是本页唯一的回读通道（uiautomator 抓 WebView 的 text 即可）。
     */
    private static String preamble(String[] names, boolean rendered) {
        StringBuilder out = new StringBuilder(1024);
        out.append("window.__mcB={");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) out.append(',');
            out.append('"').append(names[i]).append("\":1");
        }
        out.append("};window.__mcG={};window.__mcF={};window.__mcR={};");
        // 回读通道：g=groupDataList（**真正会被 v-for 渲染**的清单），
        // f=扁平的 cards 数组（含预加载/埋点用的定义，不一定有 UI）。
        // 两个名单分开记，才能回答「某个名字到底是不是可渲染卡片」。
        out.append("window.__mcStr=function(){try{document.title='mc:g='")
                .append("+Object.keys(window.__mcG).join(',')+';f='+Object.keys(window.__mcF).join(',')")
                // 只有开了 debug.bmapclean.poi_rendered 才把「真的留下来渲染的」也写进 title。
                // 默认关：g/f 已经够定位名字，r= 会让 title 变长三倍。
                .append(rendered ? "+';r='+Object.keys(window.__mcR).join(',')" : "")
                .append(";}catch(e){}};");
        out.append("window.__mcFN=function(l){try{");
        out.append("if(!l||!l.length)return l;var o=[];");
        out.append("var take=function(c,flat){var n=(c&&c.card_info&&c.card_info.name)||'';");
        // __mcG/__mcF 记录「检查过」（过滤前），__mcR 记录「留下了」（过滤后）。
        // 三者的差集才是「我们漏了什么」的精确来源 —— 详见 docs/POI-CARD-READBACK.md。
        out.append("if(n)(flat?window.__mcF:window.__mcG)[n]=1;");
        out.append("var k=!window.__mcB[n];if(k&&n)window.__mcR[n]=1;return k;};");
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
            // ⚠ 字段名是 poi_ad_rule 而不是 poi_ad_removed：这一行只是**规则回显**
            // （这个开关配了哪些卡片名），从 Java 侧看不到 H5 里到底删没删掉。
            // 以前叫 poi_ad_removed，读日志的人会以为「已删除」，比没有日志更糟。
            H.log("event=poi_ad_rule block=" + blockName(row[0]) + " how=data card="
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
