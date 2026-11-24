package org.navkit.geoassist;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Shim for the 百度-only variant.
 *
 * <p>The 高德 half is not compiled into this build, but this class still has to exist and still
 * has to <b>forward</b>: a single APK declares exactly one Xposed entry point
 * ({@code META-INF/xposed/java_init.list}), so the shared entry reaches every other vendor half
 * by name through this dispatcher.
 *
 * <p><b>Why not a plain no-op.</b> A no-op compiles fine and passes every structural check --
 * and then 百度 gets no hooks at all, because the only path to
 * {@code bmapclean.MainHook.onPackageReady} runs through here. That is precisely how 2.1.4
 * shipped with 百度 silently dead. So this shim keeps the package dispatch and forwards 百度 to
 * its real adapter; only 高德 and 腾讯, which genuinely are not in this build, are dropped.
 *
 * <p>{@code onModuleLoaded} is deliberately empty: {@code mapclean.MainHook} already calls
 * {@code bmapclean.MainHook.onModuleLoaded} directly, so forwarding it here as well would just
 * run the same assignment twice.
 */
public final class MainHook {

    public static final String TAG = "NKGeo";
    public static final String PKG_AMAP = "com.autonavi.minimap";
    public static final String PKG_BMAP = "com.baidu.BaiduMap";
    public static final String PKG_TMAP = "com.tencent.map";
    public static final String PKG_SELF = "org.navkit.geoassist";

    private MainHook() {}

    public static void onModuleLoaded(XposedModule module,
                                      XposedModuleInterface.ModuleLoadedParam param) { }

    public static void onPackageReady(XposedModule module,
                                      XposedModuleInterface.PackageReadyParam param) {
        String pkg = param == null ? null : param.getPackageName();
        if (!PKG_BMAP.equals(pkg)) return;   // 高德 / 腾讯 are not part of this variant
        org.navkit.bgeo.MainHook.onPackageReady(module, param);
    }
}
