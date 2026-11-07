package io.github.ldxm666.bmapclean

import android.util.Log

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 百度地图：**界面精简**（首页规则引擎 + 「我的」页数据层/JS 层）。
 *
 * v2.0.0 合并版起本类**不再是 Xposed 入口** —— 一个 APK 只能有一个
 * `META-INF/xposed/java_init.list` 入口，改由 `io.github.ldxm666.mapclean.MainHook`
 * 统一接收回调后转发到这里（方法签名因此从 override 改成带 module 参数的 static）。
 * 这样两个模块的 hook 代码一行都不用改，各自仍用自己的 TAG / 配置 group / 日志工具。
 */
class MainHook private constructor() {

    companion object {

        const val TAG = "BMapClean"

        const val PKG_BMAP = "com.baidu.BaiduMap"
        /** 模块自身包名 = 合并后的包名 */
        const val PKG_SELF = "io.github.ldxm666.mapadkiller"

        @Volatile
        private var sProcess: String? = null

        @JvmStatic
        fun onModuleLoaded(mod: XposedModule, param: XposedModuleInterface.ModuleLoadedParam) {
            sProcess = param.processName
            H.module = mod
            H.log(Log.INFO, TAG, "event=module_loaded process=" + sProcess
                    + " api=" + mod.apiVersion + " framework=" + mod.frameworkName)
        }

        @JvmStatic
        fun onPackageReady(mod: XposedModule, param: XposedModuleInterface.PackageReadyParam) {
            val pkg = param.packageName
            val proc = sProcess
            if (pkg == null) return

            // 模块自身进程：不挂任何钩子（设置页读的是 XposedService 连接状态）
            if (PKG_SELF == pkg) {
                H.log(Log.INFO, TAG, "self process loaded (no hooks installed)")
                return
            }

            if (PKG_BMAP != pkg) return
            // 只做主进程：首页 UI 全在主进程
            if (proc != null && proc.contains(":")) {
                H.log(Log.INFO, TAG, "event=skip_subprocess process=" + proc)
                return
            }

            val cl = param.classLoader
            H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg)
            // ⚠ 2.3.0：这两个安装器原来共用一个 try —— BaiduHooks.install 抛一次，
            // BaiduPower 就**整块不装**，而日志里只有一条 install_failed，
            // 看不出是哪半边死了。各家安装器本来就互相独立（高德侧
            // mapadkiller/MainHook 就是每条一个 try），这里照同一形态拆开。
            try {
                BaiduHooks.install(cl)
                // 省电杠杆（推送长连接 / 后台常驻服务 / 定时任务 / 桌面小组件 / 埋点）。
                // 放在 bmapclean 里 ⇒ **百度内置版和模块版共用同一份实现**（模块包含三家），
                // 不必把 mapadkiller 塞进百度内置版（用户要求"不要混包"）。
                // 每一档都是安装期门控：开关关掉 ⇒ 连 hook 都不装 ⇒ 零性能开销。
            } catch (t: Throwable) {
                H.log(Log.ERROR, TAG, "event=install_failed part=ui pkg=" + pkg, t)
            }
            // 省电杠杆（推送长连接 / 后台常驻服务 / 定时任务 / 桌面小组件 / 埋点）。
            // 放在 bmapclean 里 ⇒ **百度内置版和模块版共用同一份实现**（模块包含三家），
            // 不必把 mapadkiller 塞进百度内置版（用户要求"不要混包"）。
            // 每一档都是安装期门控：开关关掉 ⇒ 连 hook 都不装 ⇒ 零性能开销。
            try {
                BaiduPower.install(cl, pkg)
            } catch (t: Throwable) {
                H.log(Log.ERROR, TAG, "event=install_failed part=power pkg=" + pkg, t)
            }
            H.done(pkg)
        }
    }
}