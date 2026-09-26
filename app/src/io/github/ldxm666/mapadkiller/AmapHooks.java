package io.github.ldxm666.mapadkiller;

import android.util.Log;

/**
 * 高德地图 com.autonavi.minimap（16.23 与 17.00 双版本验证）
 *  - 开屏闸门: SplashScreenServiceImpl.tryShowSplashView -> g.o() -> {za6|u96}.b().g(int,String)
 *      返回对象字段 a==1 → 应用自家 NO_SPLASH 收尾分支（不卡启动）
 *  - fetchRealTime() 实时广告拉取；isSplashShowing/isContinueLaunchMaskViewShowing 状态
 *  - 首页轮播: BannerManager.b(String,ZZ,listener)/.a(I,String) + BannerParser.a(JSONObject)
 *  - 后台推送运营弹窗: BackgroundMsgManager.a(listener)
 *  - AJX 首页联动数据: NativesModuleSplashScreen.getLinkageMsg/getCurrentLinkageMsg
 *  - 搜索页模板开屏: SplashModel.getData/getTemplate/getXmlUrl/getCssUrl
 */
public final class AmapHooks {

    private AmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- 开屏：数据层强制"无广告"（17.0=u96，16.x=za6，双试无害）----
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

        Class<?> splashSvc = H.cls(cl, "com.autonavi.minimap.impl.SplashScreenServiceImpl");
        H.hookAll(splashSvc, "fetchRealTime", "amap_rt", H.VOID);
        H.hookAll(splashSvc, "isSplashShowing", "amap_showing", H.FALSE);
        H.hookAll(splashSvc, "isContinueLaunchMaskViewShowing", "amap_mask", H.FALSE);

        // ---- 首页 banner ----
        Class<?> bannerMgr = H.cls(cl, "com.autonavi.bundle.banner.manager.BannerManager");
        H.hookAll(bannerMgr, "b", "amap_banner_b", H.VOID);
        H.hookAll(bannerMgr, "a", "amap_banner_a", H.VOID);
        Class<?> bannerParser = H.cls(cl, "com.autonavi.bundle.banner.net.BannerParser");
        H.hookAll(bannerParser, "a", "amap_banner_parse", H.VOID);

        // ---- 后台推送运营消息 ----
        Class<?> bgMsg = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.push.BackgroundMsgManager");
        H.hookAll(bgMsg, "a", "amap_bgmsg", H.VOID);

        // ---- 开屏联动数据（喂给 AJX 首页）----
        Class<?> natives = H.cls(cl, "com.autonavi.minimap.splashscreen.ajx.NativesModuleSplashScreen");
        H.hookAll(natives, "getLinkageMsg", "amap_link", H.VOID);
        H.hookAll(natives, "getCurrentLinkageMsg", "amap_curlink", H.VOID);

        // ---- 搜索页 splash 模板 ----
        Class<?> sModel = H.cls(cl, "com.autonavi.minimap.search.inter.splash.SplashModel");
        H.hookAll(sModel, "getData", "amap_sm_data", H.VOID);
        H.hookAll(sModel, "getTemplate", "amap_sm_tpl", H.VOID);
        H.hookAll(sModel, "getXmlUrl", "amap_sm_xml", H.VOID);
        H.hookAll(sModel, "getCssUrl", "amap_sm_css", H.VOID);

        // ---- 视图层兜底 ----
        Sweeper.install(new ViewKiller("AMAP-KILL",
                "^(com\\.autonavi\\.bundle\\.banner\\.view\\.DBanner)$",
                ":id/.*(banner|splash_ad|ad_container|home_ad)$"));

        H.done(MainHook.PKG_AMAP);
    }
}
