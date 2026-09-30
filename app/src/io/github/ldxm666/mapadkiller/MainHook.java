package io.github.ldxm666.mapadkiller;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 高德/百度/腾讯 去广告（v1.1.0 逻辑原样保留）。
 *
 * v2.0.0 合并版起本类**不再是 Xposed 入口**，改由 `io.github.ldxm666.mapclean.MainHook`
 * 转发回调（见那边的说明），本类方法签名从 override 改为带 module 参数的 static。
 */
public final class MainHook {

    public static final String TAG = "MapAdKiller";

    public static final String PKG_AMAP = "com.autonavi.minimap";
    public static final String PKG_BMAP = "com.baidu.BaiduMap";
    public static final String PKG_TMAP = "com.tencent.map";
    /** 模块自身包名 = 合并后的包名 */
    public static final String PKG_SELF = "io.github.ldxm666.mapadkiller";

    private static volatile String sProcess;

    private MainHook() {}

    public static void onModuleLoaded(XposedModule mod, XposedModuleInterface.ModuleLoadedParam param) {
        sProcess = param.getProcessName();
        H.module = mod;
        H.log(Log.INFO, TAG, "event=module_loaded process=" + sProcess
                + " api=" + mod.getApiVersion() + " framework=" + mod.getFrameworkName());
    }

    public static void onPackageReady(XposedModule mod, XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        final String process = sProcess;
        if (pkg == null) return;

        // 模块自身：状态自检（LSPosed Manager 显示"已激活"）
        if (PKG_SELF.equals(pkg)) {
            try {
                Class<?> sc = param.getClassLoader().loadClass(PKG_SELF + ".StatusCheck");
                mod.hook(sc.getDeclaredMethod("amEnabled"))
                        .setId("self_check")
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) {
                                return Boolean.TRUE;
                            }
                        });
            } catch (Throwable ignored) {}
            return;
        }

        if (!PKG_AMAP.equals(pkg) && !PKG_BMAP.equals(pkg) && !PKG_TMAP.equals(pkg)) return;
        // 只挂主进程（广告 UI 均在主进程；子进程 :locationservice/:widgetProvider 等跳过）
        if (process != null && process.contains(":")) {
            H.log(Log.INFO, TAG, "event=skip_subprocess pkg=" + pkg + " process=" + process);
            return;
        }
        if (!H.markInstalled(pkg)) {
            H.log(Log.INFO, TAG, "event=install_skipped reason=already pkg=" + pkg);
            return;
        }

        ClassLoader cl = param.getClassLoader();
        try {
            switch (pkg) {
                case PKG_AMAP:
                    H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg);
                    AmapHooks.install(cl);   // 去广告基线
                    TreeDump.install();      // 取证用全树 dump（debugLog 打开时才输出）
                    HomeTweaks.install(cl);  // 首页/「我的」页 UI 自定义（配置驱动）
                    break;
                case PKG_BMAP:
                    H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg);
                    BmapHooks.install(cl);   // 百度去广告基线
                    break;
                case PKG_TMAP:
                    H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg);
                    TmapHooks.install(cl);
                    break;
                default:
                    break;
            }
            // 三家通用层：广告 SDK 自动检索 + 拦截（已知入口立刻挂，dex 扫描后台跑）
            SdkAutoBlock.install(cl, pkg);
        } catch (Throwable t) {
            H.log(Log.ERROR, TAG, "event=install_failed pkg=" + pkg, t);
        }
    }
}
