package io.github.ldxm666.mapadkiller;

/** Exact application entry points. No view traversal or SDK discovery in AMap. */
public final class AmapHooks {
    private AmapHooks() {}
    public static void install(ClassLoader cl) {
        if (!AmapProtocolHooks.compatible(cl)) return;
        Class<?> preloader = H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl");
        H.hookSig(preloader, "canShowSplash", "amap_splash_gate", H.FALSE);
        HotWordGuard.install(cl);
        AmapNativeHome.install(cl);
        AmapTabLayout.install(cl);
        AmapMapWidget.install(cl);
        AmapProtocolHooks.install(cl);
        H.done(MainHook.PKG_AMAP);
    }
}
