package org.navkit.bgeo;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Stub for the slim (高德-only) variant.
 *
 * The whole 百度 adapter is dropped from this build, but the shared dispatchers
 * ({@code mapclean.MainHook} for {@code onModuleLoaded}, {@code mapadkiller.MainHook} for
 * {@code onPackageReady}) still forward to it -- a single APK can only declare one Xposed
 * entry point, so the other halves have to be reached by explicit forwarding.
 *
 * <b>This stub is the reason those forwardings can live in shared source.</b> An earlier
 * attempt deleted the calls instead, which compiled the slim build but silently disabled
 * 百度 in the full module: the免 root build never runs 百度, the module does.
 */
public final class MainHook {

    public static final String TAG = "NKBGeo";
    public static final String PKG_BMAP = "com.baidu.BaiduMap";
    /** 模块自身包名 = 合并后的包名 */
    public static final String PKG_SELF = "org.navkit.geoassist";

    private MainHook() {}

    public static void onModuleLoaded(XposedModule mod, XposedModuleInterface.ModuleLoadedParam param) { }

    public static void onPackageReady(XposedModule mod, XposedModuleInterface.PackageReadyParam param) { }
}
