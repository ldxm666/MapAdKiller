package io.github.ldxm666.mapadkiller;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * 高德地图 com.autonavi.minimap（16.23 / 17.00 双版本验证；17.00.0.2005 真机实证）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 17.00 的开屏链路（classes5.dex 反编译实证）
 * ══════════════════════════════════════════════════════════════════════════
 *   SplashActivity → com.autonavi.minimap.g.o(int, String)      ← 开屏决策总入口
 *       ├─ if (SplashState ∈ {SHOWING, LANDING})  → g.e(...) 收尾
 *       ├─ if (SplashState == FINISHED)           → g.e(...) 收尾
 *       ├─ if (isForbidSplashScene)               → g.e(NO_SPLASH)
 *       ├─ if (!SplashFrequencyController.a/b(...)) → g.e(...)
 *       ├─ BootBizDataPreloader.canShowSplash()   ← ★语义闸门，null/false 直接走收尾
 *       │      if (Boolean == null) → 跳过实时广告段
 *       │      else if (!a() && !b()) → g.e(NO_SPLASH)   ← 应用自家"无广告"分支
 *       ├─ …（此处才 new ke5 / 注册 RealTimeFetchCallback / g.i 等广告计时）
 *       └─ g.e(SplashFinishReason)  收尾
 *
 *   SplashScreenServiceImpl.tryShowSplashView(int,String) 只是
 *       com.autonavi.minimap.g → o(int, String) 的转发壳（回归实证）。
 *
 *   g.e(reason) 是**收尾方法**：内部有 g.f 幂等闸门，重复调用直接 return，
 *   调用它等于应用自己判定"没有开屏广告"，会正常 setState(FINISHED)
 *   并广播 SplashEvent，首页照常渲染 —— 不会卡启动。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * v1.0.x 的死代码（本次修复）
 * ══════════════════════════════════════════════════════════════════════════
 *  旧版按名字硬挂 u96/za6 的 g(int,String) —— 那是 16.23 的混淆名。
 *  17.00 里 u96/za6 的类还在，但**签名已经变了**，真机日志：
 *      miss  za6.g (no such sig)
 *  → 开屏闸门整条失效，只剩视图层兜底。现在改成挂**语义方法**
 *  （canShowSplash / setSplashDrawable / showSplashMaskView），
 *  混淆名再变也不影响。
 */
public final class AmapHooks {

    /** 开屏窗口期标记：SplashActivity / SplashScreenServiceImpl 一被调用就置位 */
    static volatile boolean sSplashPhase = false;

    private AmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- ① 语义闸门：canShowSplash() → false ----
        // 反编译确认它的调用者只有三处：g.o()（开屏决策）、lite.a.loadPage()、
        // eo6.doBizLogic()（首页预加载）。返回 false 会让 g.o() 走
        // "SplashFrequencyController 不通过 → g.e(NO_SPLASH)" 的自有分支：
        // 广告不请求、不展示，开屏正常收尾进首页。
        // 注意：canShowSplash() 声明在**接口** BootBizDataPreloader 上（abstract 方法不可 hook），
        // 真正实现体是 com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl。
        // 只挂接口会得到 "miss ... canShowSplash MISS"（本次真机日志实证），
        // 所以实现类与接口都要试一遍。
        Class<?> impl = H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl");
        H.hookAll(impl, "canShowSplash", "amap_cansplash_impl", H.FALSE);
        Class<?> preloader = H.cls(cl, "com.autonavi.bundle.amaphome.api.BootBizDataPreloader");
        H.hookAll(preloader, "canShowSplash", "amap_cansplash", H.FALSE);

        // ---- ② 开屏收尾：观察日志（诊断用，不干预）----
        Class<?> g = H.cls(cl, "com.autonavi.minimap.g");
        H.hookAll(g, "e", "amap_finish", new io.github.libxposed.api.XposedInterface.Hooker() {
            @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                sSplashPhase = true;
                return chain.proceed();
            }
        });
        // 17.00 的新服务实现类（接口方法转发壳）
        Class<?> splashSvc = H.cls(cl, "com.autonavi.minimap.impl.SplashScreenServiceImpl");
        H.hookAll(splashSvc, "fetchRealTime", "amap_rt", H.VOID);
        H.hookAll(splashSvc, "isSplashShowing", "amap_showing", H.FALSE);
        H.hookAll(splashSvc, "isContinueLaunchMaskViewShowing", "amap_mask", H.FALSE);
        H.hookAll(splashSvc, "showSplashMaskView", "amap_mask_show", H.VOID);

        // ---- ③ 老版本（16.x）混淆闸门：存在才挂，不存在就算了 ----
        for (String gateCls : new String[]{"u96", "za6"}) {
            Class<?> gate = H.cls(cl, gateCls);
            if (gate == null) continue;
            H.hookSig(gate, "g", "amap_gate_" + gateCls, new io.github.libxposed.api.XposedInterface.Hooker() {
                @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    if (r != null && H.setIntField(r, "a", 1)) {
                        H.log(Log.INFO, MainHook.TAG, "AMAP splash forced NO_SPLASH");
                    }
                    return r;
                }
            }, int.class, String.class);
        }

        // ---- ④ 首页 banner / 后台推送 / 开屏联动 / 搜索模板 ----
        Class<?> bannerMgr = H.cls(cl, "com.autonavi.bundle.banner.manager.BannerManager");
        H.hookAll(bannerMgr, "b", "amap_banner_b", H.VOID);
        H.hookAll(bannerMgr, "a", "amap_banner_a", H.VOID);
        Class<?> bannerParser = H.cls(cl, "com.autonavi.bundle.banner.net.BannerParser");
        H.hookAll(bannerParser, "a", "amap_banner_parse", H.VOID);

        Class<?> bgMsg = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.push.BackgroundMsgManager");
        H.hookAll(bgMsg, "a", "amap_bgmsg", H.VOID);

        Class<?> natives = H.cls(cl, "com.autonavi.minimap.splashscreen.ajx.NativesModuleSplashScreen");
        H.hookAll(natives, "getLinkageMsg", "amap_link", H.VOID);
        H.hookAll(natives, "getCurrentLinkageMsg", "amap_curlink", H.VOID);

        Class<?> sModel = H.cls(cl, "com.autonavi.minimap.search.inter.splash.SplashModel");
        H.hookAll(sModel, "getData", "amap_sm_data", H.VOID);
        H.hookAll(sModel, "getTemplate", "amap_sm_tpl", H.VOID);
        H.hookAll(sModel, "getXmlUrl", "amap_sm_xml", H.VOID);
        H.hookAll(sModel, "getCssUrl", "amap_sm_css", H.VOID);

        // ---- ⑤ 开屏容器视图探测（兜底）----
        // 高德开屏容器是 com.autonavi.minimap.component.SplashContainerView（FrameLayout），
        // 由 g.d() new 出来再 addOverlay 挂到首页 overlay 上。
        // 挂它的 addView：子视图若命中广告特征，立即摘除；品牌层不受影响。
        installContainerProbe(cl, "com.autonavi.minimap.component.SplashContainerView", "amap");

        // ---- ⑥ 视图兜底（DBanner + 显式广告词根）----
        Sweeper.install(new ViewKiller("AMAP-KILL",
                "^(com\\.autonavi\\.bundle\\.banner\\.view\\.DBanner)$",
                ":id/(.*_)?(splash_ad|ad_container|home_ad|ad_banner|banner_ad|ad_view)$"));

        // ---- ⑦ 开屏窗口自动清扫（通用兜底）----
        SplashProbe.installActivitySweep(new SplashProbe.SplashPhase() {
            @Override public boolean active() { return sSplashPhase; }
        }, "amap");

        // ---- ⑧ 搜索框内页去广告 ----
        // 搜索全流程不跳 Activity（真机实证 topResumedActivity 恒为 SplashActivity），
        // 页面整个是 AJX3 画布、节点无资源 id —— 所以这一层按「结构指纹 + AJX 文案锚点」
        // 判页判广告，由 HomeTweaks 的文本 Hook 驱动（见 HomeTweaks.onTextSet）。
        SearchCleaner.install(cl);
        DiagPage.install();          // 取证：debugLog 打开时按拍输出整页台账（交付零开销）

        // ---- ⑨ 首页搜索框热词轮播（文字流动 / 运营广告词）关闭 ----
        // DEX 实证：BaseSearchBar.setHotWordTxt / setPreWordTextView 是唯一出口，
        // 参数换成 title_search_hint 即可钉死文字（详见 HotWordGuard 类注释）。
        HotWordGuard.install(cl);

        H.done(MainHook.PKG_AMAP);
    }

    /**
     * 给开屏容器挂 addView / onAttachedToWindow 探测。
     * 只挂容器**自己声明**的方法，绝不沿父类链 —— 否则等于全局 ViewGroup.addView。
     */
    static void installContainerProbe(ClassLoader cl, String clsName, final String tag) {
        Class<?> c = H.cls(cl, clsName);
        if (c == null) {
            H.log(Log.INFO, MainHook.TAG, tag + " splash container NOT FOUND: " + clsName);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            String nm = m.getName();
            boolean want = nm.equals("addView")
                    || nm.equals("onAttachedToWindow")
                    || nm.equals("setContentView")
                    || nm.equals("show");
            if (!want) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (nm.equals("addView") && (ps.length == 0 || !android.view.View.class.isAssignableFrom(ps[0]))) continue;
            if (nm.equals("setContentView") && (ps.length == 0 || !android.view.View.class.isAssignableFrom(ps[0]))) continue;
            if (H.module == null) continue;
            H.module.hook(m).setId(tag + "_probe_" + nm)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof android.view.View) {
                                    final android.view.View v = (android.view.View) self;
                                    // 立即探一次 + 接下来 90 帧持续探（广告内容常是异步塞进来的）
                                    probeLoop(v, tag);
                                }
                            } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            n++;
        }
        H.log(Log.INFO, MainHook.TAG, tag + " splash container probe on " + clsName + " hooks=" + n);
    }

    /** 帧驱动探测：命中即摘广告子树，最多盯 90 帧 */
    private static void probeLoop(final android.view.View root, final String tag) {
        if (root == null) return;
        if (!(root instanceof android.view.ViewGroup)) return;
        try {
            root.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                private int n;
                @Override public boolean onPreDraw() {
                    try {
                        if (root instanceof android.view.ViewGroup) {
                            SplashProbe.Result res = SplashProbe.scan(root);
                            if (res.isAd()) {
                                android.view.ViewGroup g = (android.view.ViewGroup) root;
                                SplashProbe.stripAdChildren(g);
                                if (SplashProbe.first(tag + ":" + res.hit)) {
                                    H.log(Log.INFO, MainHook.TAG,
                                            tag + " splash ad subtree stripped hit=" + res.hit);
                                }
                            }
                        }
                        if (++n > 90) {
                            try { root.getViewTreeObserver().removeOnPreDrawListener(this); }
                            catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }
}
