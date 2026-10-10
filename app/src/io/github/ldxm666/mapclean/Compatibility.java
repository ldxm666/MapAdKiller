package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.lang.reflect.Method;

/** Resolve named capabilities without rejecting an entire app by its version number. */
public final class Compatibility {
    private Compatibility() {}
    public static PackageInfo packageInfo(ClassLoader cl, String pkg) {
        try {
            Class<?> thread = cl.loadClass("android.app.ActivityThread");
            Method current = thread.getDeclaredMethod("currentActivityThread"); current.setAccessible(true);
            Method context = thread.getDeclaredMethod("getSystemContext"); context.setAccessible(true);
            Context c = (Context) context.invoke(current.invoke(null));
            return c.getPackageManager().getPackageInfo(pkg, 0);
        } catch (Throwable ignored) { return null; }
    }
    public static Method method(Class<?> type, String name, Class<?> result, Class<?>... args) {
        if (type == null) return null;
        try {
            Method m = type.getDeclaredMethod(name, args);
            return result == null || result == m.getReturnType() ? m : null;
        } catch (Throwable ignored) { return null; }
    }
    public static boolean field(Class<?> type, String name, Class<?> expected) {
        if (type == null) return false;
        try { return expected.isAssignableFrom(type.getDeclaredField(name).getType()); }
        catch (Throwable ignored) { return false; }
    }
}
