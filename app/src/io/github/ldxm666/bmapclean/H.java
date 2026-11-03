package io.github.ldxm666.bmapclean;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * API 102 通用挂钩工具。
 *
 * 设计取向与"静默失效"相反：每一次挂钩都打 HOOKED 或 miss，
 * 安装结束打 install_done ok=/miss=。用户拿 logcat 就能判断
 * "模块没生效" 是挂钩失败还是规则没命中。
 */
public final class H {

    public static volatile XposedModule module;

    /**
     * Per-hook chatter ("HOOKED …", first "HIT …") is useful when debugging but costly on
     * a cold start: 110 hooks x one log line each lands right in the first-frame window.
     * Off by default; the miss warnings and the install_done summary stay.
     */
    public static volatile boolean verbose = false;

    public static final AtomicInteger ok = new AtomicInteger();
    public static final AtomicInteger miss = new AtomicInteger();

    private H() {}

    public static void log(int priority, String tag, String msg) {
        XposedModule m = module;
        if (m != null) {
            m.log(priority, tag, msg);
        } else {
            Log.println(priority, tag, msg);
        }
    }

    public static void log(int priority, String tag, String msg, Throwable t) {
        XposedModule m = module;
        if (m != null) {
            m.log(priority, tag, msg, t);
        } else {
            Log.println(priority, tag, msg + " : " + t);
        }
    }

    public static void log(String msg) {
        log(Log.INFO, MainHook.TAG, msg);
    }

    public static Class<?> cls(ClassLoader cl, String name) {
        try { return cl.loadClass(name); } catch (Throwable t) { return null; }
    }

    /**
     * 挂一个可执行体（方法**或构造器**）；返回是否成功。
     *
     * <p>参数类型是 {@link java.lang.reflect.Executable} 而不是 {@code Method}：
     * 构造器在反射里是 {@code Constructor}，**不在 {@code getDeclaredMethods()} 里**，
     * 必须走 {@code getDeclaredConstructors()}。libxposed 的
     * {@code hook(Executable)} 两者都收，所以这里放宽类型。
     * （2.2.7 第一次写"创建即隐藏"时按 Method 找 {@code <init>}，结果一个都没挂上，
     * 日志里就是 {@code widgets=0/8}。）
     */
    public static boolean hook(java.lang.reflect.Executable m, String id, XposedInterface.Hooker hk) {
        if (m == null) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " (method not found)");
            return false;
        }
        try {
            m.setAccessible(true);
            XposedModule mod = module;
            if (mod == null) {
                miss.incrementAndGet();
                log(Log.WARN, MainHook.TAG, "miss " + id + " (module null)");
                return false;
            }
            final XposedInterface.Hooker inner = hk;
            final String hid = id;
            final io.github.ldxm666.mapclean.HookGuard guarded = new io.github.ldxm666.mapclean.HookGuard(id, inner,
                    new io.github.ldxm666.mapclean.HookGuard.Reporter() {
                        public void disabled(String name, Throwable error) {
                            log(Log.WARN, MainHook.TAG, "event=hook_disabled id=" + name + " reason=" + error.getClass().getSimpleName() + ": " + error.getMessage());
                        }
                    });
            mod.hook(m)
               .setId(id)
               .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
               .intercept(new XposedInterface.Hooker() {
                   private boolean first = true;
                   @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                       if (first) {
                           first = false;
                           if (verbose) log(Log.INFO, MainHook.TAG, "HIT " + hid);
                       }
                       return guarded.intercept(chain);
                   }
               });
            ok.incrementAndGet();
            if (verbose) log(Log.INFO, MainHook.TAG, "HOOKED " + id + " -> " + m);
            return true;
        } catch (Throwable t) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " err=" + t);
            return false;
        }
    }

    /**
     * 按名取方法（取第一个非抽象、参数个数匹配的）。
     * argCount &lt; 0 表示不限参数个数。
     */
    public static Method method(Class<?> c, String name, int argCount) {
        if (c == null) return null;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (argCount >= 0 && m.getParameterTypes().length != argCount) continue;
                return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void done(String pkg) {
        log(Log.INFO, MainHook.TAG, "event=install_done pkg=" + pkg
                + " ok=" + ok.get() + " miss=" + miss.get());
    }
}
