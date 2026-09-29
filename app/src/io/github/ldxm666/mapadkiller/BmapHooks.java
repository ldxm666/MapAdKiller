package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 百度地图 com.baidu.BaiduMap（21.18 ~ 22.0.0 适配）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 22.0.0（versionCode 1650）真机 + smali 实证的完整开屏链路
 * ══════════════════════════════════════════════════════════════════════════
 *   入口 Activity: com.baidu.baidumaps.WelcomeScreen.Alias0（桌面图标）
 *   WelcomeScreen.onCreate → t()          ← 开屏广告的**唯一决策点**
 *   HomeSplashPresenter.n(String,Z,String) ← 首页再次入口（热启动/回到首页）
 *
 *   WelcomeScreen.t() 反编译（classes11.dex）:
 *       if (intent == null) return;                       // 无 intent 直接结束
 *       if (!intent.getBooleanExtra("start_up_splash_flag", false)) return;
 *       SplashAdManager.f();
 *       if (!SplashAdManager.F()) return;                 // ←←← 广告闸门 1
 *       if (!SplashAdManager.y()) return;
 *       if (WelcomeScreen.o())  return;
 *       ...
 *       SplashAdManager.G(ctx, hi.a)   // 真正发起 SDK 广告请求
 *       SplashAdManager.L(true)
 *
 *   HomeSplashPresenter.n() 反编译（classes12.dex）:
 *       ... 如果 f/z 为 true 才 new SplashViewContainer(ctx) → adContainer
 *       SplashAdManager.G(ctx, params) / SplashAdManager.n(ctx, hot, container, url, cb)
 *
 *   SplashAdManager.F() → com.baidu.baidumaps.splash.c.q()  （配置缓存里的布尔开关）
 *   SplashAdManager.z() → SplashAdManager.c  静态布尔（"开屏 SDK 广告展示中"）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么 v1.1.0 会因为"闸门"被改成 OBSERVE 而复发开屏广告
 * ══════════════════════════════════════════════════════════════════════════
 *  v1.1.0 把 F()/z() 一律改 false + 同时吞掉 IAdLoader 加载入口 → 卡开屏。
 *  v1.1.0 误判成"闸门不能动"，把它们降级为 OBSERVE（真机日志：
 *      OBSERVE SplashAdManager.F() -> true ）
 *  → 闸门重新为 true，开屏广告 100% 复发。
 *
 *  真正的因果是两条独立的线：
 *    ① F()/z() = **纯查询**，没有任何回调依赖。返回 false 时 WelcomeScreen.t()
 *      在第 43 字节直接 return-void —— 这正是应用自己"没有广告"时的原生分支，
 *      后面的 G()（广告请求）、L()（标记展示中）压根不会执行，不存在"等回调"。
 *    ② v1.1.0 卡开屏的真凶是 **IAdLoader 加载入口被 VOID 吞掉**：
 *      广告流程已经启动、宿主在等 onAdFinish/onSkip 回调，回调永不到达 → 卡死。
 *      v1.1.0 把它改回 observe 后这条线已经修好了。
 *
 *  所以正确组合是：**闸门强制 false（断源）+ 加载入口只观察不吞（保回调）**。
 *  两者叠加时 G() 根本不会被调用，回调链路自然不会成为问题。
 * ══════════════════════════════════════════════════════════════════════════
 */
public final class BmapHooks {

    private static final String P = "com.baidu.baidumaps.";
    private static volatile boolean sPassthroughLogged = false;
    /** 开屏阶段标记：WelcomeScreen/HomeSplashPresenter 出现过才启用视图探测 */
    private static volatile boolean sSplashPhase = false;

    private BmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- ① 开屏闸门：断源（决定性修复）----
        // F()/z() 是纯查询，强制 false 后宿主走自家"无广告"分支，
        // 既不显示广告、也不会等待任何回调。
        Class<?> mgr = H.cls(cl, P + "splash.SplashAdManager");
        H.hookAllBool0(mgr, "F", "bmap_gate_F", gate("F", true));
        H.hookAllBool0(mgr, "z", "bmap_gate_z", gate("z", true));
        // w()=是否允许开屏（另一个布尔闸门），y()=开屏展示中，一并钉成 false。
        // 只影响开屏链路：反编译确认这两个方法的调用者只有 WelcomeScreen /
        // HomeSplashPresenter / operation.j（埋点上报），没有共享状态副作用。
        //
        // ⚠ 注意：这几个方法内部会走 splash.c.<clinit> → SplashPreference →
        // JNIInitializer.getCachedContext()。**只有 App 自己调用它们时才安全**，
        // 安装期（Application.onCreate 之前）反射调用必然 NPE 并把 App 打死。
        H.hookAllBool0(mgr, "w", "bmap_gate_w", gate("w", true));
        H.hookAllBool0(mgr, "y", "bmap_gate_y", gate("y", true));

        // ---- ② 开屏阶段标记：给视图探测一个明确的时间窗 ----
        // WelcomeScreen.t() 与 HomeSplashPresenter.n() 是开屏广告的两条入口，
        // 它们一被调用就说明"本次启动在走开屏流程"。
        Class<?> welcome = H.cls(cl, P + "WelcomeScreen");
        H.hookAll(welcome, "t", "bmap_splash_entry_welcome", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                sSplashPhase = true;
                return chain.proceed();
            }
        });
        Class<?> homeSplash = H.cls(cl, P + "operation.splash.HomeSplashPresenter");
        H.hookAll(homeSplash, "n", "bmap_splash_entry_home", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                sSplashPhase = true;
                Object r = chain.proceed();
                // n() 的入参里带着 SDK 容器（ViewGroup），拿到手先清一遍
                try {
                    for (Object a : chain.getArgs()) {
                        if (a instanceof ViewGroup) probeContainer((ViewGroup) a, "home");
                    }
                } catch (Throwable ignored) {}
                return r;
            }
        });

        // ---- ③ 开屏 SDK 广告隐藏（兜底，见类注释）----
        // 注意：只挂 SplashViewContainer **自己声明** 的 addView 重载。
        // 绝不能用 hookAll —— 那会沿父类链挂到 ViewGroup.addView，等于全局所有 addView。
        Class<?> svc = H.cls(cl, P + "splash.view.SplashViewContainer");
        if (svc != null) {
            for (java.lang.reflect.Method m : svc.getDeclaredMethods()) {
                if (!m.getName().equals("addView")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 0 || !View.class.isAssignableFrom(ps[0])) continue;
                H.module.hook(m).setId("bmap_svc_addview" + ps.length)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                final View child = (View) chain.getArg(0);
                                Object r = chain.proceed();
                                if (child != null) watchForAd(child);
                                return r;
                            }
                        });
            }
            H.log(Log.INFO, MainHook.TAG, "BMAP splash addView hooked on " + svc.getName());
        }

        // ---- ④ 三方 ADN 加载入口：**只观察，绝不吞** ----
        // 反编译实证：IAdLoader.I(...) 是 ADN 的**回调完成方法**（onAdClose/onTimeOver…），
        // 吞掉它 = 回调永不到达 = 卡开屏。整条链路一律 observe。
        String[] loaders = {
            P + "commonadprovider.api.IAdLoader",
            P + "commonadprovider.business.BusinessAdLoader",
            P + "commonadprovider.gromore.GromoreAdLoader",
            P + "commonadprovider.ms.MeishuAdLoader",
            P + "commonadprovider.octopus.OctopusAdLoader",
            P + "commonadprovider.qumeng.QumengAdLoader",
            P + "commonadprovider.recommend.RecommendAdLoader1",
            P + "commonadprovider.recommend.RecommendAdLoader2",
        };
        for (String ln : loaders) {
            Class<?> c = H.cls(cl, ln);
            if (c != null) H.hookAll(c, "I",
                    "bmap_loader_" + ln.substring(ln.lastIndexOf('.') + 1),
                    H.observe(ln + ".I()"));
        }

        // ---- ⑤ BMAd 开放封装 ----
        String[] bmad = {
            P + "ad.BMAdSplashProvider",
            P + "ad.BMAdNativeProvider",
            P + "ad.BMAdRewardProvider",
            P + "ad.api.IBMapAdLoader",
        };
        for (String ln : bmad) {
            Class<?> c = H.cls(cl, ln);
            if (c == null) continue;
            String s = ln.substring(ln.lastIndexOf('.') + 1);
            H.hookAll(c, "c", "bmap_" + s + "_c", H.VOID);
            H.hookAll(c, "d", "bmap_" + s + "_d", H.VOID);
            H.hookAll(c, "x", "bmap_" + s + "_x", H.VOID);
        }

        // ---- ⑥ 首页中部横幅 / 悬浮运营黄条 ----
        Class<?> presenter = H.cls(cl, P + "aihome.panel.presenter.HomeMidBannerPresenter");
        H.hookAll(presenter, "show", "bmap_mid_show", H.VOID);
        Class<?> repo = H.cls(cl, P + "base.yellowbanner.MidBannerRepo");
        H.hookAll(repo, "c", "bmap_repo_c", H.VOID);
        H.hookAll(repo, "onEvent", "bmap_repo_evt", H.VOID);

        Class<?> yb = H.cls(cl, P + "aihome.map.presenter.YellowBannerPresenter");
        H.hookAll(yb, "tryShowYellowBanner", "bmap_yb_try", H.VOID);
        H.hookAll(yb, "showYellowBanner", "bmap_yb_show", H.VOID);
        H.hookAll(yb, "showSwitcherBanner", "bmap_yb_sw", H.VOID);
        H.hookAll(yb, "showViewSwitcher", "bmap_yb_vsw", H.VOID);
        H.hookAll(yb, "showYbBannerAnim", "bmap_yb_anim", H.VOID);
        H.hookAll(yb, "onResumeForYB", "bmap_yb_res", H.VOID);

        // ---- ⑦ 视图兜底：只杀专属广告视图 ----
        // 百度首页左上角那个"悬浮推广气泡"是自营轮播位，内容走 WebView，
        // SDK 层完全拦不到 —— 只能按宿主资源 id 处理。
        Sweeper.install(new ViewKiller("BMAP-KILL",
                "^(com\\.baidu\\.baidumaps\\.integratedads\\.view\\.BannerAdView|" +
                "com\\.baidu\\.baidumaps\\.integratedads\\.gromore\\.view\\.BannerUIView)$",
                ":id/(banner_ad|ad_banner|splash_ad_view|mid_banner_container|home_ad_view|" +
                "floatCommonContentLayout|floatSliderLayout|float_slider|promo_float)$"));

        SplashProbe.installActivitySweep(new SplashProbe.SplashPhase() {
            @Override public boolean active() { return sSplashPhase; }
        }, "bmap");

        // ---- ⑧ 开屏兜底放行 watchdog
        H.done(MainHook.PKG_BMAP);
    }

    /**
     * 闸门"运行时"自检包装器 —— **绝不能**改成安装期反射调用。
     *
     * v1.1.0 事故复盘：安装阶段（Application.onCreate 之前）用反射去 invoke
     * `SplashAdManager.w()` 会触发 `splash.c.<clinit>` → `SplashPreference` →
     * `Preferences.build()` → `JNIInitializer.getCachedContext()`。
     * 那个时刻缓存 Context 还是 null，直接 NPE → ExceptionInInitializerError →
     * NoClassDefFoundError: com.baidu.baidumaps.splash.c →
     * `BaiduMapApplication.onCreate → SplashAdManager.B()` 抛异常 → **百度地图一启动就闪退**。
     *
     * 现在改成：只在真实调用发生时才打一次日志，用 observed 值判断本次进程
     * 加载的是不是旧版模块 dex（旧版是 OBSERVE-only，observed 会是 true）。
     */
    private static XposedInterface.Hooker gate(final String name, final boolean want) {
        return new XposedInterface.Hooker() {
            private boolean logged;
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object observed = want ? null : chain.proceed();
                if (!logged) {
                    logged = true;
                    H.log(Log.INFO, MainHook.TAG, "BMAP gate " + name + "() observed=" + observed
                            + (want ? "  -> forced false" : "  (passthrough)"));
                }
                return want ? Boolean.FALSE : observed;
            }
        };
    }

    /**
     * 开屏兜底放行 watchdog（保证「一定能进主页」）。
     *
     * v1.1.0 修正：旧版的判据是「非 MapsActivity」，而 22.0.0 的开屏 Activity 是
     * WelcomeScreen.Alias0，真正显示广告的是 **MapsActivity 里的 HomeSplashPresenter**，
     * 旧判据两头都落空（要么不匹配、要么被 BMAP_MAIN 白名单挡掉），所以从没触发过。
     *
     * 新判据：
     *  1) 只处理**开屏类 Activity**（类名含 WelcomeScreen / Splash，或调过开屏入口的 Activity）；
     *  2) decor 里仍挂着**可见的 SplashViewContainer**（或广告 SDK 视图）才动手；
     *  3) 3s/6s/10s 三次机会，正常走完开屏流程一次都不会触发。
     *
     * 修复动作为：把广告子树从容器里摘掉 + 让容器 GONE，
     * **绝不 finish() 任何 Activity**（finish 会打断首页启动）。
     */
    private static void installSplashWatchdog() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            H.module.hook(onResume).setId("bmap_splash_watchdog")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                final Activity act = (Activity) chain.getThisObject();
                                if (act == null) return r;
                                if (!sSplashPhase) return r;
                                final Handler h = new Handler(Looper.getMainLooper());
                                for (final long d : new long[]{3000, 6000, 10000}) {
                                    h.postDelayed(new Runnable() {
                                        @Override public void run() { releaseStuckSplash(act, d); }
                                    }, d);
                                }
                            } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            H.log(Log.INFO, MainHook.TAG, "BMAP splash watchdog installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "BMAP splash watchdog fail " + t);
        }
    }

    private static void releaseStuckSplash(Activity act, long atMs) {
        try {
            if (act == null || act.isFinishing()) return;
            View decor;
            try { decor = act.getWindow().getDecorView(); } catch (Throwable t) { return; }
            ViewGroup container = findSplashContainer(decor);
            if (container == null) return;
            H.log(Log.INFO, MainHook.TAG, "BMAP splash watchdog release at=" + atMs
                    + "ms act=" + act.getClass().getName());
            stripAdChildren(container);
            container.setVisibility(View.GONE);
            ViewGroup parent = (ViewGroup) container.getParent();
            if (parent != null) parent.removeView(container);
        } catch (Throwable ignored) {}
    }

    /** decor 里是否还挂着可见的开屏容器。 */
    private static ViewGroup findSplashContainer(View v) {
        if (v == null) return null;
        try {
            if (v.getClass().getName().contains("SplashViewContainer")
                    && v.getVisibility() == View.VISIBLE && v instanceof ViewGroup) {
                return (ViewGroup) v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    ViewGroup r = findSplashContainer(g.getChildAt(i));
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 探测 + 隐藏：命中 AD SDK 特征则对整棵新增子树 GONE，否则放行（品牌层不动）。
     *
     * v1.1.0 改为**帧驱动**：广告内容往往是 addView 之后才异步填进去的，
     * 旧版靠固定的 16/40/90…700ms 七个 postDelayed，覆盖不到 >700ms 的填充；
     * 现在挂 OnPreDrawListener 连续探 60 帧（≈1s），命中即停。
     */
    private static void watchForAd(final View child) {
        try {
            probeAndHide(child, 0);
            if (child.getVisibility() == View.GONE) return;
            final android.view.ViewTreeObserver.OnPreDrawListener l =
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                private int n;
                @Override public boolean onPreDraw() {
                    try {
                        probeAndHide(child, -1);
                        if (++n > 60 || child.getVisibility() == View.GONE) {
                            try { child.getViewTreeObserver().removeOnPreDrawListener(this); }
                            catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    return true;
                }
            };
            child.getViewTreeObserver().addOnPreDrawListener(l);
        } catch (Throwable ignored) {}
    }

    private static void probeContainer(ViewGroup g, String from) {
        if (g == null) return;
        try {
            SplashProbe.Result res = SplashProbe.scan(g);
            if (res.hit != null) {
                H.log(Log.INFO, MainHook.TAG, "BMAP splash ad hidden from=" + from
                        + " hit=" + res.hit + " root=" + g.getClass().getName());
                stripAdChildren(g);
                g.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    private static void probeAndHide(View root, long atMs) {
        try {
            if (root.getVisibility() == View.GONE) return;
            SplashProbe.Result res = SplashProbe.scan(root);
            if (res.hit != null) {
                root.setVisibility(View.GONE);
                H.log(Log.INFO, MainHook.TAG,
                        "BMAP splash ad hidden at=" + (atMs < 0 ? "preDraw" : atMs + "ms")
                                + " hit=" + res.hit
                                + " root=" + root.getClass().getName());
            } else if (!sPassthroughLogged) {
                sPassthroughLogged = true;
                H.log(Log.INFO, MainHook.TAG,
                        "BMAP splash addview passthrough root=" + root.getClass().getName()
                                + " tree=" + res.tree);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "BMAP probe err " + t);
        }
    }

    /** 把广告子树从容器里摘掉（委托给共用探针，只摘命中的直接子节点） */
    private static void stripAdChildren(ViewGroup g) {
        SplashProbe.stripAdChildren(g);
    }
}
