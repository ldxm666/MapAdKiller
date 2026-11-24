package org.navkit.tgeo;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Stub for the 高德-only and 百度-only variants.
 *
 * <p>The Tencent half is not compiled into either variant, but the shared entry still calls this
 * by name: every vendor half gets {@code onModuleLoaded} before any hook is installed, because
 * each one has to cache its own {@code H.module} first. There is nothing to cache here, so this
 * is a genuine no-op rather than a broken forward -- no variant that ships this stub ever runs
 * inside 腾讯地图.
 */
public final class MainHook {

    public static final String TAG = "NKTGeo";
    public static final String PKG_TMAP = "com.tencent.map";
    public static final String PKG_SELF = "org.navkit.geoassist";

    private MainHook() {}

    public static void onModuleLoaded(XposedModule mod,
                                      XposedModuleInterface.ModuleLoadedParam param) { }

    public static void onPackageReady(XposedModule mod,
                                      XposedModuleInterface.PackageReadyParam param) { }
}
