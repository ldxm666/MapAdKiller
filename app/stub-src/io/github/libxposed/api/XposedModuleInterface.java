package io.github.libxposed.api;

import android.app.Application;
import android.content.pm.ApplicationInfo;

/**
 * compile-only stub of libxposed:api 102 XposedModuleInterface lifecycle params.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ⚠ 这个 stub 只用于编译，**它的方法表不等于运行时的真接口**。
 * ══════════════════════════════════════════════════════════════════════════
 * 规则：**只用已经被真机日志证明存在的方法**。想在模块里调一个新方法，
 * 先在设备上用日志验证它真存在（或反编译 LSPosed 的 module loader），
 * 不能因为"stub 里编译通过"就往上写 —— 代价是 NoSuchMethodError，
 * 而且它发生在 onPackageReady 内，会被自己的 try/catch 吞成 install_failed，
 * 症状和"模块没启用"一模一样，极难查。
 *
 * 已验证可用：ModuleLoadedParam#getProcessName、
 *            PackageReadyParam#getPackageName / getClassLoader。
 * 已验证不存在：PackageReadyParam#getApplication()（见下方注释）。
 */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();
        String getProcessName();
        /** 未在真机验证过：不要调用（同一族接口实测缺方法） */
        ApplicationInfo getApplication();
        ClassLoader getClassLoader();
    }

    interface PackageReadyParam {
        String getPackageName();
        ClassLoader getClassLoader();
        // ⚠ 这里曾经声明过 `android.app.Application getApplication();` —— 那是**假**的。
        // 运行时的 XposedModuleInterface$PackageReadyParam 没有这个方法，调用即：
        //   java.lang.NoSuchMethodError: No interface method
        //       getApplication()Landroid/app/Application;
        //     at io.github.ldxm666.bmapclean.MainHook.onPackageReady(MainHook.java:60)
        // v0.1.1 真机实测：异常被自己的 catch 吞成 install_failed，
        // 表现与"模块没启用"完全一样。
        // 需要 Context 时改用 Instrumentation#callApplicationOnCreate(Application) 的入参
        // （见 HomeClean.installContextHook）。
    }

    interface SystemServerStartingParam {
        String getSystemServerProcessName();
        ClassLoader getClassLoader();
    }
}
