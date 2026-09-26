package io.github.libxposed.api;

import java.lang.reflect.Executable;

/**
 * compile-only stub of libxposed:api 102 XposedModule (abstract entry base).
 * The framework's real class provides these methods at runtime.
 */
public abstract class XposedModule implements XposedInterface {

    @Override
    public HookBuilder hook(Executable target) { throw new AssertionError("stub"); }

    public void log(int priority, String tag, String message) { throw new AssertionError("stub"); }
    public void log(int priority, String tag, String message, Throwable throwable) { throw new AssertionError("stub"); }

    public int getApiVersion() { return 0; }
    public String getFrameworkName() { return null; }
    public String getFrameworkVersion() { return null; }

    public void deoptimize(Executable target) { throw new AssertionError("stub"); }

    /** 框架提供：同名 group 与模块 App 侧 XposedService 写入的 RemotePreferences 共享 */
    public android.content.SharedPreferences getRemotePreferences(String group) {
        throw new AssertionError("stub");
    }

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {}
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {}
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {}
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {}
    public boolean onHotReloading() { return false; }
    public void onHotReloaded() {}
}
