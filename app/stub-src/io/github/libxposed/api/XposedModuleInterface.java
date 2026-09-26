package io.github.libxposed.api;

import android.app.Application;
import android.content.pm.ApplicationInfo;

/** compile-only stub of libxposed:api 102 XposedModuleInterface lifecycle params. */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();
        String getProcessName();
        ApplicationInfo getApplication();
        ClassLoader getClassLoader();
    }

    interface PackageReadyParam {
        String getPackageName();
        android.app.Application getApplication();
        ClassLoader getClassLoader();
    }

    interface SystemServerStartingParam {
        String getSystemServerProcessName();
        ClassLoader getClassLoader();
    }
}
