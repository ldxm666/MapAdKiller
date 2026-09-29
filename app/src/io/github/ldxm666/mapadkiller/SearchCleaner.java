package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * SearchCleaner — 高德「搜索框内页」（搜索输入页 / 联想页 / 搜索结果页 / 搜索运营落地页）去广告。
 *
 * ════════════════════════════════════════════════════════════════════════
 * 为什么单独开一层（真机实证 + 静态证据）
 * ════════════════════════════════════════════════════════════════════════
 *  · 搜索全流程**不发生 Activity 跳转**：首页点搜索框 → 搜索页，
 *    topResumedActivity 始终是 com.autonavi.map.activity.SplashActivity
 *    （真机 dumpsys 实证）。所以「按 Activity 类名做页面作用域」在这里是死路。
 *  · 搜索页整页是 **AJX3 虚拟 DOM 画布**：树里只有
 *    com.autonavi.minimap.ajx3.widget.view.*（SpringHorizontalScrollView /
 *    List / Label …）+ 一个 android.support.v7.widget.RecyclerView，
 *    **资源 id 全是空的**（uiautomator dump 实证：404 节点里带 id 的只有
 *    root_view/floating_layer/fragment_container 等 shell 级容器）。
 *    → ViewKiller 那套「按 :id/ 名匹配」在搜索页命中率≈0，必须换判据。
 *
 * 本层三条判据（互不依赖，任一命中即处理）：
 *   ① **结构**：SearchBar 之后根级别再出现第二个 EditText，或存在
 *      ajx3 SpringHorizontalScrollView —— 这是搜索页独有的结构指纹。
 *   ② **文本锚点**：AJX 文本 Hook（HomeTweaks 已挂 Label/Html）拿到的
 *      运营卡文案（满减 / 券包 / 出行节 / 扫街券 / 限时 …）直接点名。
 *   ③ **AJX 类名**：搜索页的运营卡物理节点（CardView / BannerView 等 ajx 组件）。
 *
 * 只处理「搜索页」，动作克制：GONE 单卡（不 removeAllViews、不动父链之外的容器），
 * 所有隐藏写在首帧之前（onTextSet 当场执行），看不见闪一下再消失。
 * 全过程受 Config.K_SEARCH_AD 总开关控制（默认开），读取失败回退「开」。
 */
public final class SearchCleaner {

    private SearchCleaner() {}

    // ═══════════════════════════════════════════════ 文案锚点

    /**
     * 搜索页运营 / 推广卡文案锚点。
     * 只放**搜索页实测或高置信**的运营词，宁少勿滥 ——
     * 误杀用户真正要看的搜索结果比漏一个广告更糟。
     */
    private static final String[] AD_TOKENS = {
            // —— 与已确认垃圾同源（首页实测命中的那批运营词，搜索页同源下发）——
            "出行节", "扫街券", "扫街榜", "高德出行节",
            // —— 券 / 红包 / 补贴类运营卡 ——
            "券包", "领券", "去领取", "限时领取", "立即领取", "免费抽", "抽奖",
            "瓜分", "红包", "现金", "补贴", "领补贴", "出行补贴",
            "下单立减", "立减", "满减", "膨胀", "优惠券", "免费领",
            "今日可用", "限时今日可用", "限时放送",
            // —— 活动 / 会场 ——
            "主会场", "活动会场", "去使用", "去看看", "立即查看", "点击查看",
            "限时活动", "福利", "专享", "会员日", "签到", "抽好礼", "抽好券",
            "免费领券", "领好券", "抢券", "抢购", "秒送", "半价", "五折", "折上折",
            "特卖", "聚划算", "省到底", "帮你省", "划算", "省钱", "有奖", "答题",
            // —— 封闭中文型广告角标（整串短文案）——
            "广告", "赞助", "推广", "商业推广", "品牌推广", "ad", "sponsored",
    };

    /**
     * 单字 / 短词锚点：误杀面大，只在**完整文案整串相等**时才算命中，
     * 且必须同时处于搜索页。
     */
    private static final Set<String> LOOSE_EXACT = new HashSet<>(java.util.Arrays.asList(
            "领", "抢", "秒杀", "特惠", "特价", "酒店券", "美食券", "打车券"));

    /** 广告容器 class 名前缀（高德自研 + 常见 ADN，取长词根，不用 2~3 字母短串） */
    private static final String[] CLASS_PREFIXES = {
            "com.autonavi.bundle.banner.",
            "com.autonavi.bundle.operation.",
            "com.autonavi.minimap.bundle.operation.",
            "com.autonavi.minimap.bundle.advert",
            "com.autonavi.minimap.search.ad",
            "com.qq.e", "com.bytedance.sdk", "com.bytedance.msdk",
            "com.kwad", "com.kuaishou.ad", "com.baidu.mobads", "com.qumeng",
            "com.sigmob", "com.mintegral", "com.tradplus", "com.meishu.sdk",
            "com.beizi.fusion", "com.alimm.tanx", "com.wangmai", "com.byazt",
            "com.google.android.gms.ads", "com.applovin",
    };

    /** 广告 id 名（**前缀**匹配，避免 "ad" 打头误伤 address/AD_check 之类正常控件） */
    private static final String[] ID_PREFIXES = {
            "ad_", "adlayout", "adview", "adcontainer", "adcard", "adbanner",
            "banner_ad", "splash_ad", "feed_card_operation", "home_ad", "ad_banner",
    };

    /** 搜索页 AJX 容器 class 名后缀：只在这里面做「id/文案」小扫，别的节点一律不碰 */
    private static final String[] SEARCH_CONTAINER_KEYS = {
            "banner", "operation", "advert", "promo", "card_operation", "marquee",
    };

    private static final int MAX_SCAN_NODES = 3000;
    private static final int MAX_SCAN_DEPTH = 34;

    // ═══════════════════════════════════════════════ 运行态

    private static volatile Activity activity;
    private static final Set<String> loggedText = new HashSet<>();
    private static final Set<String> loggedProp = new HashSet<>();
    private static final WeakHashMap<View, String> hiddenViews = new WeakHashMap<>();
    private static final List<View[]> pinned = new ArrayList<>();
    private static volatile ViewGroup pinnedParent;

    private static int pinnedAt;
    private static int pinnedEvery = 3;   // 每 3 次布局校验一次（约 3 帧一次）

    // ═══════════════════════════════════════════════ 安装

    /**
     * ⚠ 关键约束（真机踩出来的）：**同一个方法上不要挂第二个 Hook**。
     *
     * 现象：SearchCleaner 与 HomeTweaks 各自 hook 了一次 Activity.onResume，
     * 结果只有先注册的那个回调会跑 —— SearchCleaner 的探针日志一条都不出
     * （搜索页明明在眼前，"SEARCH-PROBE" 零输出），而 HomeTweaks 的
     * TOOL-KEY / TAB-HIDE 照常刷屏。TreeDump 同样 hook onResume，同样零输出。
     * 结论：onResume 这类框架级方法在 libxposed API 102 下按方法**单挂**，
     *       重复注册不会报错，但后注册的回调不会被调用。
     *
     * 所以：SearchCleaner 不再自己 hook onResume —— 由 HomeTweaks 的
     * 「唯一的那个 onResume Hook」在链尾回调本类（见 HomeTweaks.ResumeSink）。
     */
    public static void install(ClassLoader cl) {
        try {
            installLayoutWatcher(cl);
            HomeTweaks.addResumeSink(new HomeTweaks.ResumeSink() {
                @Override public void onActivityResumed(Activity act) { onActivityResume(act); }
            });
            H.log(Log.INFO, MainHook.TAG, "search cleaner installed (resume via HomeTweaks)");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "search cleaner fail " + t);
        }
    }

    public static void onActivityResume(final Activity act) {
        if (act == null) return;
        activity = act;
        synchronized (loggedText) { loggedText.clear(); }
        synchronized (loggedProp) { loggedProp.clear(); }
        heartbeatDone = false;
        synchronized (pinned) { pinned.clear(); }
        pinnedParent = null;

        // 搜索页在 onResume 后才由 AJX 拉起，压三拍覆盖首帧与异步卡片
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        for (long d : new long[]{150, 700, 2000, 5000}) {
            h.postDelayed(new Runnable() {
                @Override public void run() { sweep(); }
            }, d);
        }
        // 取证：resume 后报告一次「这棵 decor 像不像搜索页」（debugLog 打开时）
        // 真机排查用：searchEdit/homeTab/searchId 三个计数能把误判原因直接摊开。
        if (Config.debugLog()) {
            for (long d : new long[]{600, 1800}) {
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            View decor = act.getWindow().getDecorView();
                            int[] st = new int[5];
                            walkMarks(decor, 0, st, new int[]{MAX_SCAN_NODES});
                            H.log(Log.INFO, MainHook.TAG, "SEARCH-PROBE act="
                                    + act.getClass().getSimpleName()
                                    + " searchEdit=" + st[0] + " homeTab=" + st[1]
                                    + " searchId=" + st[2] + " ajxHScroll=" + st[3]
                                    + " ajxList=" + st[4]
                                    + " => isSearchPage=" + computeSearchPage(decor));
                        } catch (Throwable ignored) {}
                    }
                }, d);
            }
        }
    }

    /**
     * 帧驱动兜底：搜索页的运营卡是**异步**塞进 Canvas 的，
     * onResume 那几拍常常抢在数据到达之前。挂注册表监听（只挂一次），
     * 只要树里有注册过 onPreDraw 且身处搜索页，就在帧回调里轻量复扫。
     */
    private static void installLayoutWatcher(ClassLoader cl) {
        try {
            Class<?> vtr = Class.forName("android.view.ViewTreeObserver");
            Method add = null;
            for (Method m : vtr.getDeclaredMethods()) {
                if (!m.getName().equals("addOnPreDrawListener")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0].getName().endsWith("OnPreDrawListener")) { add = m; break; }
            }
            if (add == null) return;
            H.module.hook(add).setId("search_cleaner_predraw")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (!(self instanceof android.view.ViewTreeObserver)) return r;
                                final android.view.View root = rootOf((android.view.ViewTreeObserver) self);
                                if (root == null) return r;
                                // 只认根级监听（decor 的注册者），避免每个 List 都挂一遍
                                ViewParent p = root.getParent();
                                if (!(root instanceof ViewGroup) || p != null) return r;
                                boolean first = watchOnce.put(root, Boolean.TRUE) == null;
                                if (first) {
                                    ((android.view.ViewTreeObserver) self).addOnPreDrawListener(
                                            new android.view.ViewTreeObserver.OnPreDrawListener() {
                                        private int n;
                                        @Override public boolean onPreDraw() {
                                            try {
                                                if (n++ > 900) return true;
                                                if ((n & 15) != 0) return true;   // 每 16 帧轻量复扫
                                                verifyPinned();
                                                sweepIfSearch();
                                            } catch (Throwable ignored) {}
                                            return true;
                                        }
                                    });
                                }
                            } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            H.log(Log.INFO, MainHook.TAG, "search cleaner predraw hook ok");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "search cleaner predraw miss " + t);
        }
    }

    private static final WeakHashMap<View, Boolean> watchOnce = new WeakHashMap<>();

    /** ViewTreeObserver → 宿主 View（走 addOnPreDrawListener 时 chain.thisObject 是 observer，需反查） */
    private static View rootOf(android.view.ViewTreeObserver obs) {
        Activity a = activity;
        if (a == null) return null;
        try { return a.getWindow().getDecorView(); } catch (Throwable t) { return null; }
    }

    // ═══════════════════════════════════════════════ 搜索页识别

    /**
     * 搜索页结构指纹（真机实证，双判据任一成立即认）。
     *
     * 判据 A：**搜索域 EditText**。首页那支搜索框只是「假的」——真机 uiautomator 里
     *   首页搜索框是 LinearLayout + TextView(txt_hotword)，根本没有 EditText；
     *   输入光标在搜索页才出现，且它的资源名里必带 search（真机实测 search_view）。
     * 判据 B：**搜索页专属容器**。搜索页整棵树只有 shell 级 id，
     *   没有 maphome_searchbar_container（首页独有），却握着搜索结果容器
     *   search_sug_container / 搜索条 search_view 之类的 id 或 ajx3 横滑组件。
     *
     * 结果带 300ms TTL 缓存：AJX 每条文本都会问一次页归属，
     * 每次全树遍历是不可接受的成本（首页实测 400+ 节点）。
     */
    private static volatile boolean pageVal;
    private static volatile long pageAt;

    /**
     * 指定根节点的页归属（走到这里的一般是「已经拿到 decor」的调用方）；
     * 带同样的 300ms TTL，避免扫描循环里反复全树遍历。
     */
    public static boolean isSearchPageCached(View root) {
        long now = System.currentTimeMillis();
        if (now - pageAtRoot < 300 && pageRootRef == root) return pageValRoot;
        boolean v = computeSearchPage(root);
        pageValRoot = v;
        pageRootRef = root;
        pageAtRoot = now;
        return v;
    }

    private static View pageRootRef;
    private static volatile boolean pageValRoot;
    private static volatile long pageAtRoot;

    public static boolean isSearchPage() {
        long now = System.currentTimeMillis();
        if (now - pageAt < 300) return pageVal;
        boolean v = computeSearchPage();
        pageVal = v;
        pageAt = now;
        return v;
    }

    private static boolean computeSearchPage() {
        Activity a = activity;
        if (a == null || a.isFinishing()) return false;
        View decor;
        try { decor = a.getWindow().getDecorView(); }
        catch (Throwable t) { return false; }
        return computeSearchPage(decor);
    }

    private static boolean computeSearchPage(View decor) {
        if (decor == null) return false;
        int[] st = new int[5];   // 0=搜索域EditText 1=首页底栏「首页」tab 2=搜索专属容器 3=ajx横滑 4=ajx列表
        walkMarks(decor, 0, st, new int[]{MAX_SCAN_NODES});
        if (st[1] > 0) return false;         // 底部 tab 栏在场 → 这是首页（搜索页没有底栏）
        return st[0] > 0 || st[2] > 0 || st[3] > 0 || st[4] > 0;
    }

    /** 底栏 tab 文本（真机实证：首页底栏是「首页 / 我的」两个 TextView） */
    private static final String[] HOME_TABS = {"首页", "我的", "探索", "打车", "长按说话"};

    /** 单次遍历收集全部结构指纹（不读文本，成本≈节点数） */
    private static void walkMarks(View v, int depth, int[] st, int[] budget) {
        if (v == null || depth > MAX_SCAN_DEPTH || budget[0] <= 0) return;
        budget[0]--;
        try {
            int id = v.getId();
            if (id != View.NO_ID && id != 0) {
                String rn = rn(v, id);
                int s = rn.indexOf('/');
                String name = s > 0 ? rn.substring(s + 1) : rn;
                String low = name.toLowerCase();
                if (low.startsWith("maphome_searchbar") || low.startsWith("maphome_search")) st[1]++;
                if (low.contains("search_sug") || low.equals("search_view")
                        || low.startsWith("search_input") || low.contains("search_result")
                        || low.startsWith("search_edit")) st[2]++;
            }
            String cn2 = v.getClass().getName();
            if (v instanceof EditText) {
                String n = idName(v).toLowerCase();
                // 真机实证：搜索页那颗输入框是**无资源名**的原生 EditText
                // （ui xml: EditText [156,140][1026,203]，resource-id 为空），
                // 所以「树里有 EditText 且它是搜索域」本身就是判据；
                // 首页那颗是 LinearLayout+TextView(txt_hotword)，不是 EditText。
                if (n.contains("search") || cn2.contains("AUSearch")
                        || cn2.contains("SearchEdit") || n.isEmpty()) st[0]++;
            }
            if (cn2.endsWith("ajx3.widget.view.SpringHorizontalScrollView")) st[3]++;
            if (cn2.endsWith("ajx3.widget.view.List")) st[4]++;
            if (v instanceof TextView && v.getWidth() > 0 && v.getHeight() > 0) {
                CharSequence tx = ((TextView) v).getText();
                if (tx != null && tx.length() <= 6) {
                    String s = norm(tx.toString());
                    for (String tab : HOME_TABS) {
                        if (tab.equals(s)) { st[1]++; break; }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkMarks(g.getChildAt(i), depth + 1, st, budget);
        }
    }

    private static final HashMap<Integer, String> RES_NAMES = new HashMap<>();

    private static String rn(View v, int id) {
        String c = RES_NAMES.get(id);
        if (c != null) return c;
        try { c = v.getResources().getResourceName(id); }
        catch (Throwable t) { c = ""; }
        if (c == null) c = "";
        RES_NAMES.put(id, c);
        return c;
    }

    // ═══════════════════════════════════════════════ 文本锚点入口（HomeTweaks 调）

    /**
     * AJX 文本落点入口。返回 true 表示「已命中广告并处理」，调用方不再走首页规则。
     *
     * HomeTweaks.onTextSet 是**全 App** 的文本入口，搜索页也在其中 ——
     * 这里先判页、再判词，命中就地 GONE（首帧之前），不排期、不闪烁。
     */
    private static volatile boolean heartbeatDone;

    public static boolean onTextSet(View v, String text) {
        if (v == null || text == null || text.length() == 0) return false;
        try {
            if (!cfgOn()) return false;
            String t = norm(text);
            if (t.length() == 0 || t.length() > 24) return false;
            if (!isSearchPage()) return false;
            if (!heartbeatDone) {
                heartbeatDone = true;
                H.log(Log.INFO, MainHook.TAG, "SEARCH-PAGE detected (first text on search page) '"
                        + trim(t) + "'");
            }

            String hit = matchAdText(t);
            if (hit == null) {
                // 取证：搜索页每一条文案都记一次（类名 + id + 尺寸 + 父链前 3 层），
                // 广告复现时这份台账里必然有它的原文 —— 拿到就往 AD_TOKENS 补一行。
                if (Config.debugLog()) traceText(v, t);
                return false;
            }

            View card = cardOf(v, t);
            if (card != null) {
                hide(card, "text:" + hit + "='" + t + "'");
                pin(card);
            } else if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                H.log(Log.INFO, MainHook.TAG, "SEARCH-AD text-only '" + t + "' hit=" + hit);
            }
            return true;
        } catch (Throwable t2) {
            H.log(Log.WARN, MainHook.TAG, "search cleaner text err " + t2);
            return false;
        }
    }

    /** 返回命中的锚点，未命中返回 null */
    private static String matchAdText(String t) {
        if (LOOSE_EXACT.contains(t)) return t;
        for (String k : AD_TOKENS) {
            if (t.equalsIgnoreCase(k)) return k;
            // 长锚点（≥3 字）允许包含命中；短锚点在 AD_TOKENS 里也要求整串
            if (k.length() >= 3 && t.contains(k)) return k;
        }
        return null;
    }

    /** 从命中文本上溯到「单张运营卡」。找不到就返回文本自身所在的最小容器。 */
    private static View cardOf(View v, String t) {
        View best = null;
        View cur = v;
        for (int i = 0; i < 8; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
            int h = cur.getHeight();
            int w = cur.getWidth();
            if (h <= 0 || w <= 0) continue;
            if (w >= 380 && h <= 760) {           // 1080 宽机型上的一张卡：约 [380,760) 高
                best = cur;                        // 取最外层仍像「一张卡」的祖先
            } else if (best != null) {
                break;                             // 已经超出卡片高度，上一轮就是最好的
            }
        }
        return best != null ? best : v;
    }

    // ═══════════════════════════════════════════════ 视图层清扫

    public static void sweepIfSearch() {
        try {
            Activity a = activity;
            if (a == null || a.isFinishing()) return;
            if (!cfgOn()) return;
            View decor = a.getWindow().getDecorView();
            if (decor == null) return;
            if (!isSearchPageCached(decor)) return;
            int[] budget = new int[]{MAX_SCAN_NODES};
            sweepNode(decor, a, 0, budget, false);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "search cleaner sweep err " + t);
        }
    }

    public static void sweep() { sweepIfSearch(); }

    /** inAdSubtree=true 表示父节点已判为广告，子孙直接清掉 */
    private static void sweepNode(View v, Activity act, int depth, int[] budget, boolean inAdSubtree) {
        if (v == null || depth > MAX_SCAN_DEPTH || budget[0] <= 0) return;
        budget[0]--;
        boolean killed = inAdSubtree;
        try {
            if (!killed) {
                String why = adReason(v, act);
                if (why != null) {
                    hide(v, why);
                    pin(v);
                    killed = true;
                }
            }
        } catch (Throwable ignored) {}
        if (killed) return;                    // 整棵广告子树不再下探
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sweepNode(g.getChildAt(i), act, depth + 1, budget, false);
        }
    }

    /** 判定单个节点是不是广告容器；是则返回可读理由 */
    private static String adReason(View v, Activity act) {
        if (v == null || v.getVisibility() == View.GONE) return null;

        // ⓪ 已经处理过的节点直接跳过（幂等）
        if (hiddenViews.containsKey(v)) return null;

        String cn = v.getClass().getName();

        // ① class 名前缀（长词根，安全）
        String low = cn.toLowerCase();
        for (String p : CLASS_PREFIXES) {
            if (low.startsWith(p)) return "class:" + p;
        }

        // ② 广告角标文案（整串短文案，且只认「广告/赞助/推广」语义）
        String badge = badgeOf(v);
        if (badge != null) return "badge:" + badge;

        // ③ 高德资源 id（前缀匹配，需要解析资源名，做缓存）
        int id = v.getId();
        if (id != View.NO_ID && id != 0) {
            String rn = rn(v, id);
            if (rn != null && rn.length() > 0) {
                String name = rn.substring(rn.indexOf('/') + 1).toLowerCase();
                for (String pre : ID_PREFIXES) {
                    if (name.startsWith(pre)) return "id:" + name;
                }
            }
        }

        // ④ AJX 容器小扫：只在容器类名含运营语义时，才细看它的文案是否命中锚点
        if (v instanceof ViewGroup && low.contains("ajx")) {
            for (String key : SEARCH_CONTAINER_KEYS) {
                if (low.contains(key)) {
                    String t = shortTextIn(v, 3);
                    if (t != null) {
                        String hit = matchAdText(t);
                        if (hit != null) return "ajx:" + key + ":'" + t + "'";
                    }
                    break;
                }
            }
        }
        return null;
    }

    /** 短角标文案：整串 <= 8 字且属于广告语义才算 */
    private static String badgeOf(View v) {
        try {
            CharSequence cd = v.getContentDescription();
            String s = cd == null ? null : cd.toString();
            if (s == null && v instanceof TextView) {
                CharSequence tx = ((TextView) v).getText();
                s = tx == null ? null : tx.toString();
            }
            if (s == null) return null;
            s = s.trim();
            if (s.length() == 0 || s.length() > 8) return null;
            if (s.equals("广告") || s.equalsIgnoreCase("ad") || s.equals("赞助")
                    || s.equals("推广") || s.equals("商业推广")) return s;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 取子树里前 n 个短文本，拼成判定串（只读，不改树） */
    private static String shortTextIn(View v, int limit) {
        StringBuilder sb = new StringBuilder(64);
        int[] n = new int[]{0};
        collectText(v, sb, n, limit, 0);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void collectText(View v, StringBuilder sb, int[] n, int limit, int depth) {
        if (v == null || n[0] >= limit || depth > 4) return;
        try {
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.length() > 0 && cd.length() <= 20) {
                sb.append(norm(cd.toString())).append(' ');
                n[0]++;
            }
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && t.length() > 0 && t.length() <= 20) {
                    sb.append(norm(t.toString())).append(' ');
                    n[0]++;
                }
            }
        } catch (Throwable ignored) {}
        if (n[0] >= limit || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        int c = Math.min(g.getChildCount(), 12);
        for (int i = 0; i < c; i++) collectText(g.getChildAt(i), sb, n, limit, depth + 1);
    }

    // ═══════════════════════════════════════════════ 隐藏 + 持续钉住

    private static void hide(View v, String why) {
        try {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            hiddenViews.put(v, why);
            H.log(Log.INFO, MainHook.TAG, "SEARCH-AD KILLED " + why
                    + " cls=" + v.getClass().getName() + " id=" + idName(v));
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "SEARCH-AD hide err " + t);
        }
    }

    /** 登记进「持续钉住」表：AJX 复用节点时会把 GONE 改回 VISIBLE，靠帧回调把它按回去 */
    private static void pin(View v) {
        synchronized (pinned) {
            pinnedParent = (ViewGroup) v.getParent();
            for (View[] e : pinned) {
                if (e[0] == v) return;
            }
            if (pinned.size() > 120) pinned.remove(0);
            pinned.add(new View[]{v});
        }
    }

    private static void verifyPinned() {
        List<View[]> snap;
        synchronized (pinned) {
            if (pinned.isEmpty()) return;
            if (pinnedAt++ % pinnedEvery != 0) return;
            snap = new ArrayList<>(pinned);
        }
        for (View[] e : snap) {
            View v = e[0];
            try {
                if (v == null) continue;
                if (v.getVisibility() != View.GONE) {
                    v.setVisibility(View.GONE);
                    H.log(Log.INFO, MainHook.TAG, "SEARCH-AD re-pin " + hiddenViews.get(v));
                }
            } catch (Throwable ignored) {}
        }
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID || id == 0) return "-";
            String n = rn(v, id);
            int s = n.indexOf('/');
            return s > 0 ? n.substring(s + 1) : n;
        } catch (Throwable t) { return "?"; }
    }

    // ═══════════════════════════════════════════════ 取证（debugLog 打开时）

    /**
     * 搜索页取证：把树里**带 id / 带文案 / 类名像运营卡**的节点打一条日志（去重）。
     * 只在设置页「调试日志」打开时输出 —— 用来锁定下一次真机复现的广告节点，
     * 拿到证据后往 AD_TOKENS / CLASS_PREFIXES / ID_PREFIXES 里补一行即可。
     */
    private static void forensics(View v, int depth, int[] budget) {
        if (v == null || depth > MAX_SCAN_DEPTH || budget[0] <= 0) return;
        budget[0]--;
        try {
            String cn = v.getClass().getName();
            String id = idName(v);
            String txt = null;
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.length() > 0) txt = cd.toString();
            if (txt == null && v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && t.length() > 0) txt = t.toString();
            }
            boolean interesting = (!"-".equals(id))
                    || (txt != null && txt.length() > 0)
                    || cn.toLowerCase().contains("ajx") && (v instanceof ViewGroup);
            if (interesting) {
                String key = cn + "|" + id + "|" + (txt == null ? "" : txt);
                if (loggedText.add(key)) {
                    H.log(Log.INFO, MainHook.TAG, "SEARCH-TREE d" + depth + " " + cn
                            + " id=" + id + (txt == null ? "" : " tv='" + trim(txt) + "'")
                            + " vis=" + (v.getVisibility() == View.VISIBLE ? "V" : (v.getVisibility() == View.INVISIBLE ? "I" : "G"))
                            + " " + v.getWidth() + "x" + v.getHeight());
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) forensics(g.getChildAt(i), depth + 1, budget);
        }
    }

    /** 搜索页文案台账（debugLog 打开时输出，去重，看一次就够） */
    private static void traceText(View v, String t) {
        try {
            String cn = v.getClass().getName();
            String key = cn + "|" + t;
            synchronized (loggedProp) {
                if (!loggedProp.add(key)) return;
                if (loggedProp.size() > 400) return;
            }
            StringBuilder chain = new StringBuilder(96);
            ViewParent p = v.getParent();
            for (int i = 0; i < 3 && p instanceof View; i++) {
                View pv = (View) p;
                chain.append(" < ").append(pv.getClass().getSimpleName())
                     .append('(').append(pv.getWidth()).append('x').append(pv.getHeight()).append(')');
                p = pv.getParent();
            }
            H.log(Log.INFO, MainHook.TAG, "SEARCH-TXT '" + trim(t) + "' " + cn
                    + " id=" + idName(v) + " " + v.getWidth() + "x" + v.getHeight() + chain);
        } catch (Throwable ignored) {}
    }

    public static void dumpSearchTree() {
        try {
            Activity a = activity;
            if (a == null) return;
            View decor = a.getWindow().getDecorView();
            if (decor == null || !isSearchPageCached(decor)) return;
            if (!Config.debugLog()) return;
            forensics(decor, 0, new int[]{MAX_SCAN_NODES});
            H.log(Log.INFO, MainHook.TAG, "SEARCH-TREE dump done");
        } catch (Throwable ignored) {}
    }

    // ═══════════════════════════════════════════════ 小工具

    private static boolean cfgOn() {
        try { return Config.visible(Config.K_SEARCH_AD); }
        catch (Throwable t) { return true; }
    }

    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\u3000' || c == '\t') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String trim(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 24 ? s.substring(0, 24) + "~" : s;
    }
}
