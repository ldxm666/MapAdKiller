package io.github.ldxm666.mapadkiller;

/** Exact application entry points. No view traversal or SDK discovery in AMap. */
public final class AmapHooks {
    private AmapHooks() {}
    public static void install(final ClassLoader cl) {
        AmapProtocolHooks.compatible(cl);
        Class<?> preloader = H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl");
        H.hookSig(preloader, "canShowSplash", "amap_splash_gate", H.FALSE);
        feature("search", new Runnable() { public void run() { HotWordGuard.install(cl); } });
        feature("home", new Runnable() { public void run() { AmapNativeHome.install(cl); } });
        feature("tabs", new Runnable() { public void run() { AmapTabLayout.install(cl); } });
        feature("widgets", new Runnable() { public void run() { AmapMapWidget.install(cl); } });
        feature("labels", new Runnable() { public void run() { AmapPoiLayer.install(cl); } });
        feature("data", new Runnable() { public void run() { AmapProtocolHooks.install(cl); } });
        feature("place_recommend", new Runnable() { public void run() {
            try { AmapPlaceAds.install(cl); }
            catch (Exception error) { throw new IllegalStateException(error); }
        } });
        H.done(MainHook.PKG_AMAP);
    }
    private static void feature(String name, Runnable installer) {
        int before = H.ok.get();
        try { installer.run(); }
        catch (Throwable error) { H.log("event=feature_skipped name=" + name + " reason=" + error.getClass().getSimpleName()); }
        H.log("event=feature_probe name=" + name + " hooks=" + (H.ok.get() - before));
    }
}
