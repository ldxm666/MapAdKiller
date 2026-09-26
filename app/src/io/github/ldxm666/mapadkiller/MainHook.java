package io.github.ldxm666.mapadkiller;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * MapAdKiller — 高德/百度/腾讯地图 去广告模块
 * 基于 libxposed API 102（XposedModule 入口 + META-INF/xposed 元数据），不再使用 legacy de.robv API。
 *
 * 证据链（docs/ANALYSIS.md）：
 *  - com.autonavi.minimap : 自研广告体系 splashscreen 闸门(u96/za6).g / banner / msgbox.push / AJX 联动 / 搜索模板开屏
 *  - com.baidu.BaiduMap   : SplashAdManager F/z 闸门 + G/H/n/m + commonadprovider 聚合(6 ADN) + BMAd*Provider
 *                           + 品牌开屏遮罩 HomeSplashPresenter.n 摘除 + 黄条/中部横幅
 *  - com.tencent.map      : GDT GDTADManager.initWith + 开屏流水线 tasks + HomeBannerItem + POI 广告卡(ViewKiller)
 */
public final class MainHook extends XposedModule {

    public static final String TAG = "MapAdKiller";

    public static final String PKG_AMAP = "com.autonavi.minimap";
    public static final String PKG_BMAP = "com.baidu.BaiduMap";
    public static final String PKG_TMAP = "com.tencent.map";
    public static final String PKG_SELF = "io.github.ldxm666.mapadkiller";

    private volatile String loadedProcess;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        loadedProcess = param.getProcessName();
        H.module = this;
        H.log(Log.INFO, TAG, "event=module_loaded process=" + loadedProcess
                + " api=" + getApiVersion() + " framework=" + getFrameworkName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        final String process = loadedProcess;
        if (pkg == null) return;

        // 模块自身：状态自检（LSPosed Manager 显示"已激活"）
        if (PKG_SELF.equals(pkg)) {
            try {
                Class<?> sc = param.getClassLoader().loadClass(PKG_SELF + ".StatusCheck");
                hook(sc.getDeclaredMethod("amEnabled"))
                        .setId("self_check")
                        .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                            @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) {
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
                    BmapHooks.install(cl);
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
