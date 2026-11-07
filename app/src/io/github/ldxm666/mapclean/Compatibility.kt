package io.github.ldxm666.mapclean

import android.content.Context
import android.content.pm.PackageInfo
import java.lang.reflect.Method

/** Resolve named capabilities without rejecting an entire app by its version number. */
class Compatibility private constructor() {
    companion object {
        @JvmStatic
        fun packageInfo(cl: ClassLoader, pkg: String): PackageInfo? {
            try {
                val thread = cl.loadClass("android.app.ActivityThread")
                val current = thread.getDeclaredMethod("currentActivityThread"); current.isAccessible = true
                val context = thread.getDeclaredMethod("getSystemContext"); context.isAccessible = true
                val c = context.invoke(current.invoke(null)) as Context
                return c.packageManager.getPackageInfo(pkg, 0)
            } catch (ignored: Throwable) {
                return null
            }
        }

        @JvmStatic
        fun method(type: Class<*>?, name: String, result: Class<*>?, vararg args: Class<*>): Method? {
            if (type == null) return null
            return try {
                val m = type.getDeclaredMethod(name, *args)
                if (result == null || result == m.returnType) m else null
            } catch (ignored: Throwable) {
                null
            }
        }

        @JvmStatic
        fun field(type: Class<*>?, name: String, expected: Class<*>): Boolean {
            if (type == null) return false
            return try {
                expected.isAssignableFrom(type.getDeclaredField(name).type)
            } catch (ignored: Throwable) {
                false
            }
        }
    }
}