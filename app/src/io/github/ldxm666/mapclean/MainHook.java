package io.github.ldxm666.mapclean;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * v2.0.0 合并版的**唯一 Xposed 入口**（`META-INF/xposed/java_init.list` 只有这一行）。
 *
 * 两个模块的 hook 代码原样保留，各自仍用自己的：
 *   - 日志 TAG（BMapClean / MapAdKiller）
 *   - 配置 group（bmapclean_config / amap_enhancer_config）
 *   - 静态工具类（H / Config / Spec …）
 * 这里只做分发 + 顶层日志，**不做任何合并改写**，把回归风险压到最低。
 *
 * 进程视角：
 *   com.baidu.BaiduMap   → bmapclean（界面精简）+ mapadkiller（去广告）
 *   com.autonavi.minimap → mapadkiller（去广告 + 首页/我的页 UI）
 *   com.tencent.map      → mapadkiller
 */
public final class MainHook extends XposedModule {

    public static final String TAG = "MapClean";

    private volatile String loadedProcess;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        loadedProcess = param.getProcessName();
        Log.i(TAG, "event=module_loaded process=" + loadedProcess
                + " api=" + getApiVersion() + " framework=" + getFrameworkName());
        try {
            io.github.ldxm666.bmapclean.MainHook.onModuleLoaded(this, param);
        } catch (Throwable t) {
            Log.w(TAG, "bmap module_loaded failed", t);
        }
        try {
            io.github.ldxm666.mapadkiller.MainHook.onModuleLoaded(this, param);
        } catch (Throwable t) {
            Log.w(TAG, "amap module_loaded failed", t);
        }
        NativeLabelAssets.start(loadedProcess);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        if (pkg == null) return;

        EmbeddedSettings.install(this, param, loadedProcess);

        // 百度地图：界面精简 + 去广告，两套都挂
        if (io.github.ldxm666.bmapclean.MainHook.PKG_BMAP.equals(pkg)) {
            try {
                io.github.ldxm666.bmapclean.MainHook.onPackageReady(this, param);
            } catch (Throwable t) {
                Log.e(TAG, "bmap install failed", t);
            }
        }
        // 高德 / 百度 / 腾讯：去广告基线（含 SDK 自动检索）
        try {
            io.github.ldxm666.mapadkiller.MainHook.onPackageReady(this, param);
        } catch (Throwable t) {
            Log.e(TAG, "amap install failed", t);
        }
    }
}
