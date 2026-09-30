package io.github.ldxm666.bmapclean;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 百度地图：**界面精简**（首页规则引擎 + 「我的」页数据层/JS 层）。
 *
 * v2.0.0 合并版起本类**不再是 Xposed 入口** —— 一个 APK 只能有一个
 * `META-INF/xposed/java_init.list` 入口，改由 `io.github.ldxm666.mapclean.MainHook`
 * 统一接收回调后转发到这里（方法签名因此从 override 改成带 module 参数的 static）。
 * 这样两个模块的 hook 代码一行都不用改，各自仍用自己的 TAG / 配置 group / 日志工具。
 */
public final class MainHook {

    public static final String TAG = "BMapClean";

    public static final String PKG_BMAP = "com.baidu.BaiduMap";
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
        final String proc = sProcess;
        if (pkg == null) return;

        // 模块自身进程：不挂任何钩子（设置页读的是 XposedService 连接状态）
        if (PKG_SELF.equals(pkg)) {
            H.log(Log.INFO, TAG, "self process loaded (no hooks installed)");
            return;
        }

        if (!PKG_BMAP.equals(pkg)) return;
        // 只做主进程：首页 UI 全在主进程
        if (proc != null && proc.contains(":")) {
            H.log(Log.INFO, TAG, "event=skip_subprocess process=" + proc);
            return;
        }

        ClassLoader cl = param.getClassLoader();
        try {
            H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg);
            HomeClean.install(cl);
            // 「我的」页走 Talos 数据层（不是视图层）：挂 Talos 网络栈 / 响应交付两处
            MineData.install(cl);
            // 数据层够不到的卡（百度运动 / 全民共建 / 宫格）走 JS 层：给宿主小程序注入补丁
            MineJs.install(cl);
        } catch (Throwable t) {
            H.log(Log.ERROR, TAG, "event=install_failed pkg=" + pkg, t);
        } finally {
            H.done(pkg);
        }
    }
}
