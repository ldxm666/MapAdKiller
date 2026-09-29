package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * SplashProbe — 三方广告 SDK 视图识别器（高德/百度/腾讯共用）。
 *
 * 只做识别，不做任何动作；由调用方决定隐藏 / 摘除 / 放行。
 * 判据两路，任一命中即算广告：
 *   ① 类名包含已知 ADN / 聚合 / 广告位词根（大小写无关）；
 *   ② 视图树上出现「跳过 / 跳转 / 广告 / Ad」这类小角标文本。
 *
 * 关键约束（踩过的坑）：
 *   · 词根用**长词根**，不用 "ad"/"ads" 这种 2~3 字母的短串 ——
 *     否则会命中 android.widget.**Ad**apterView、xxx.**Ad**dressPicker 之类的正常控件；
 *   · 角标文本必须整串相等（不查子串），长度 ≤ 8；
 *   · 遍历深度与节点数设上限，避免在开屏热路径上爆栈/卡顿。
 */
public final class SplashProbe {

    private SplashProbe() {}

    /** 已知广告 SDK / 聚合 / 广告位类名词根（真机实测校准，keep long） */
    public static final String[] TOKENS = {
            // ---- 国内 ADN ----
            "qq.e", "gdtad", "tangramsplash",
            "bytedance.sdk", "pangle", "ttadview", "csj", "gromore", "bytedance.msdk",
            "kwad", "ksad", "kuaishou.ad",
            "baidu.mobads", "qumeng", "advlib", "splashcountdown",
            "sigmob", "mintegral", "mbridge",
            "meishu.sdk", "beizi.fusion", "alimm.tanx", "tradplus", "klevin",
            "windmill", "anythink", "topon", "jd.ad.sdk", "youxiao.ssp",
            "octopus.ad", "mimo.sdk", "smartdigimkt", "vlion.ad", "ubix",
            "taku.ad", "wangmai", "ruishi", "huawei.hms.ads", "openalliance.ad",
            // ---- 海外 ----
            "applovin", "ironsource", "vungle", "unityads", "facebook.ads",
            "fyber", "smaato", "yandex", "google.android.gms.ads",
            // ---- 通用广告位命名（长词根） ----
            "splashadview", "adloader", "iadloader", "adcontainer", "adcardview",
            "banneradview", "operationbanner", "splashview",
    };

    /** 角标文本：整串相等才算（避免"广告过滤"这类正常文案误杀） */
    private static final String[] BADGES = {
            "跳过", "跳转", "广告", "关闭广告", "skip", "ad",
    };

    public static final class Result {
        public final String hit;      // 命中特征，null = 不是广告
        public final String tree;     // 前若干层类名（诊断用）
        public final View node;       // 命中的节点
        Result(String hit, String tree, View node) {
            this.hit = hit; this.tree = tree; this.node = node;
        }
        public boolean isAd() { return hit != null; }
    }

    public static Result scan(View root) {
        StringBuilder tree = new StringBuilder(256);
        String[] hit = new String[1];
        View[] node = new View[1];
        walk(root, tree, hit, node, 0, new int[]{0});
        return new Result(hit[0], tree.toString(), node[0]);
    }

    private static boolean walk(View v, StringBuilder tree, String[] hit, View[] node,
                                int depth, int[] budget) {
        if (v == null || depth > 26 || budget[0] > 900) return hit[0] != null;
        budget[0]++;
        String cn = v.getClass().getName();
        if (tree.length() < 3500) tree.append(cn).append(' ');
        String low = cn.toLowerCase();
        for (String t : TOKENS) {
            if (low.contains(t)) { hit[0] = t + ":" + cn; node[0] = v; return true; }
        }
        String badge = badgeOf(v);
        if (badge != null) {
            hit[0] = "badge:" + badge + ":" + cn;
            node[0] = v;
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (walk(g.getChildAt(i), tree, hit, node, depth + 1, budget)) return true;
            }
        }
        return false;
    }

    /** 短文本角标（跳过 / 广告），整串匹配 */
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
            for (String b : BADGES) {
                if (s.equalsIgnoreCase(b)) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ══════════════════════════════════════════ 开屏窗口自动清扫

    /** 是否处于开屏窗口期（各 App 在自己的入口 Hook 里置位） */
    public interface SplashPhase {
        boolean active();
    }

    private static volatile boolean sweepInstalled;

    /**
     * 在 Activity.onResume 后自动清扫开屏窗口里的广告子树。
     *
     * 为什么必须有这一层：不同 App 的开屏容器类名/挂载方式差别很大，
     * 有的容器不覆写 addView（高德 SplashContainerView 就是继承 FrameLayout 的），
     * 单靠挂容器方法会"hooks=0"。改成**在开屏窗口期扫整个 decor**，
     * 只要开屏容器和广告子树出现在窗口上就会被抓到。
     *
     * 触发时机：onResume 后 600ms / 1600ms / 3000ms 三轮 + 30 帧帧驱动，覆盖面足够。
     * 动作：只摘命中的**广告子树**，容器与品牌层原样保留。
     */
    public static synchronized void installActivitySweep(final SplashPhase phase, final String tag) {
        if (sweepInstalled) return;
        try {
            java.lang.reflect.Method onResume = Activity.class.getDeclaredMethod("onResume");
            H.module.hook(onResume).setId("splash_sweep_" + tag)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                if (!phase.active()) return r;
                                final Activity act = (Activity) chain.getThisObject();
                                if (act == null) return r;
                                final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                for (final long d : new long[]{600, 1600, 3000}) {
                                    h.postDelayed(new Runnable() {
                                        @Override public void run() { sweepActivity(act, tag, d); }
                                    }, d);
                                }
                            } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            sweepInstalled = true;
            H.log(Log.INFO, MainHook.TAG, tag + " splash activity sweep installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, tag + " splash sweep fail " + t);
        }
    }

    /** 扫一遍 decor：找到开屏容器 → 摘广告子树 */
    public static void sweepActivity(Activity act, String tag, long at) {
        try {
            if (act == null || act.isFinishing()) return;
            View decor = act.getWindow().getDecorView();
            View root = findSplashRoot(decor);
            if (root == null) return;
            Result res = scan(root);
            if (!res.isAd()) return;
            if (root instanceof ViewGroup) stripAdChildren((ViewGroup) root);
            if (first(tag + ":sweep:" + res.hit)) {
                H.log(Log.INFO, MainHook.TAG, tag + " splash ad swept at=" + at
                        + "ms hit=" + res.hit + " act=" + act.getClass().getName());
            }
        } catch (Throwable ignored) {}
    }

    // ══════════════════════════════════════════ 视图树工具

    /** 在 decor 里找第一个可见的开屏容器（类名含 SplashView / SplashContainer / SplashAdView） */
    public static View findSplashRoot(View v) {
        if (v == null) return null;
        try {
            if (v.getVisibility() == View.VISIBLE && v instanceof ViewGroup) {
                String n = v.getClass().getName();
                if (n.contains("SplashViewContainer") || n.contains("SplashContainerView")
                        || n.contains("SplashAdView") || n.contains("SplashRootView")) {
                    return v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findSplashRoot(g.getChildAt(i));
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 把开屏容器里**只**由广告 SDK 组成的子树摘掉；容器本身保留（宿主还要用它做收尾动画）。
     *
     * 判定很保守：一个直接子节点，如果它自己或其子树的**根**命中广告特征，就摘掉它。
     * 品牌层（Logo/标语）与倒计时控件不会被误伤 —— 它们的类名不含广告词根。
     */
    public static void stripAdChildren(ViewGroup container) {
        if (container == null) return;
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View c = container.getChildAt(i);
            if (c == null) continue;
            Result r = scan(c);
            if (r.isAd() && nearRoot(r.node, c)) {
                try {
                    container.removeViewAt(i);
                    H.log(Log.INFO, MainHook.TAG, "SPLASH strip ad child hit=" + r.hit
                            + " cls=" + c.getClass().getName());
                } catch (Throwable ignored) {}
            }
        }
    }

    /** 命中节点必须是这棵子树的前若干层，避免"容器里某处有个小广告角标"就把整块内容摘掉 */
    private static boolean nearRoot(View node, View root) {
        View cur = node;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur == root) return true;
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** 开屏容器是否还挂在窗口上（用于卡开屏判定） */
    public static boolean hasSplashOnScreen(Activity act) {
        try {
            View decor = act.getWindow().getDecorView();
            return findSplashRoot(decor) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 屏幕尺寸（角标尺寸判据用） */
    public static int screenWidth(View v) {
        try { return v.getRootView().getWidth(); } catch (Throwable t) { return 1080; }
    }

    /** 视图是否在屏幕可见区域内（避免把预加载的不可见视图当成广告） */
    public static boolean onScreen(View v) {
        try {
            Rect r = new Rect();
            if (!v.getGlobalVisibleRect(r)) return false;
            return r.width() > 0 && r.height() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final Set<String> seen = new HashSet<>();

    /** 诊断日志去重（同一类名只报一次） */
    public static boolean first(String key) {
        synchronized (seen) { return seen.add(key); }
    }
}
