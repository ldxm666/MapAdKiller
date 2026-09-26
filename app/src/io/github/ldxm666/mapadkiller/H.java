package io.github.ldxm666.mapadkiller;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * API 102 通用挂钩工具：按名/按签名 hook、计数、容错、常用语义 Hooker。
 * 全部基于 io.github.libxposed.api（HookBuilder/Chain/Hooker），无 legacy 依赖。
 */
public final class H {

    public static volatile XposedModule module;

    public static final AtomicInteger ok = new AtomicInteger();
    public static final AtomicInteger fail = new AtomicInteger();
    private static final Set<String> installedPkgs = new HashSet<>();

    private H() {}

    public static synchronized boolean markInstalled(String pkg) {
        return installedPkgs.add(pkg);
    }

    public static void log(int priority, String tag, String msg) {
        XposedModule m = module;
        if (m != null) m.log(priority, tag, msg);
    }

    public static void log(int priority, String tag, String msg, Throwable t) {
        XposedModule m = module;
        if (m != null) m.log(priority, tag, msg, t);
    }

    public static void log(String msg) {
        log(Log.INFO, MainHook.TAG, msg);
    }

    /** 常用语义 Hooker（匿名类实现，避免 javac -bootclasspath android.jar 下 lambda 无法 desugar） */
    public static final XposedInterface.Hooker VOID = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) { return null; }
    };
    public static final XposedInterface.Hooker FALSE = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) { return Boolean.FALSE; }
    };
    public static final XposedInterface.Hooker TRUE = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) { return Boolean.TRUE; }
    };

    public static Class<?> cls(ClassLoader cl, String name) {
        try { return cl.loadClass(name); } catch (Throwable t) { return null; }
    }

    /** 按名 hook：本类 + 全部父类中同名方法（等价旧 hookAllMethods），返回命中数 */
    public static int hookAll(Class<?> c, String method, String id, XposedInterface.Hooker hooker) {
        if (c == null) return 0;
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(method)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue; // 抽象方法不可 hook，跳过避免 hook_error 刷屏
                if (hookMethod(m, id + "#" + k.getSimpleName() + "#" + n, hooker)) n++;
            }
        }
        count(n > 0, c, method + (n > 0 ? "*(" + n + ")" : " MISS"));
        return n;
    }

    /**
     * 只挂「无参 + 返回 boolean」的重载。
     *
     * 混淆过的单字母方法名（SplashAdManager.F/z 这类）在大版本更新后会整体重排，
     * 用 hookAll 按名强改返回值风险极大 —— 一旦撞上的是「是否已完成」之类的状态查询，
     * 就等于把 App 永远钉在 false 上（实测表现：卡开屏，按返回键才进得去）。
     * 所以对这类方法一律只认 ()Z 签名，并且默认只观察、不篡改。
     */
    public static int hookAllBool0(Class<?> c, String method, String id, XposedInterface.Hooker hooker) {
        if (c == null) return 0;
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(method)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt != boolean.class && rt != Boolean.class) continue;
                if (hookMethod(m, id + "#" + k.getSimpleName() + "#" + n, hooker)) n++;
            }
        }
        count(n > 0, c, method + (n > 0 ? "()Z*(" + n + ")" : " MISS"));
        return n;
    }

    /** 直通观察 Hooker：原样放行，只把首次返回值记一条日志（用于校准混淆方法语义）。 */
    public static XposedInterface.Hooker observe(final String what) {
        return new XposedInterface.Hooker() {
            private boolean once;
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                if (!once) {
                    once = true;
                    log(Log.INFO, MainHook.TAG, "OBSERVE " + what + " -> " + r);
                }
                return r;
            }
        };
    }

    /** 按精确签名 hook */
    public static void hookSig(Class<?> c, String method, String id, XposedInterface.Hooker hooker, Class<?>... params) {
        if (c == null) return;
        try {
            Method m = c.getDeclaredMethod(method, params);
            boolean r = hookMethod(m, id, hooker);
            count(r, c, method + (r ? "" : " MISS(sig)"));
        } catch (NoSuchMethodException e) {
            count(false, c, method + " (no such sig)");
        } catch (Throwable t) {
            count(false, c, method + " (" + t.getMessage() + ")");
        }
    }

    private static boolean hookMethod(Method m, String id, XposedInterface.Hooker hooker) {
        try {
            m.setAccessible(true);
            XposedModule mod = module;
            if (mod == null) return false;
            final XposedInterface.Hooker inner = hooker;
            final String hid = id;
            // 首火日志包装：每个 Hook 首次触发记一条 HIT，便于诊断"装了没触发"类问题
            mod.hook(m)
               .setId(id)
               .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
               .intercept(new XposedInterface.Hooker() {
                   private boolean hitLogged = false;
                   @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                       if (!hitLogged) {
                           hitLogged = true;
                           log(Log.INFO, MainHook.TAG, "HIT " + hid);
                       }
                       return inner.intercept(chain);
                   }
               });
            return true;
        } catch (Throwable t) {
            log(Log.WARN, MainHook.TAG, "event=hook_error method=" + m + " err=" + t);
            return false;
        }
    }

    private static void count(boolean success, Class<?> c, String m) {
        if (success) {
            ok.incrementAndGet();
            log(Log.INFO, MainHook.TAG, "HOOKED  " + c.getName() + "." + m);
        } else {
            fail.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss    " + (c == null ? "?" : c.getName()) + "." + m);
        }
    }

    /** 反射设置实例 int 字段（含父类查找） */
    public static boolean setIntField(Object obj, String name, int value) {
        try {
            Class<?> k = obj.getClass();
            while (k != null) {
                try {
                    Field f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    f.setInt(obj, value);
                    return true;
                } catch (NoSuchFieldException e) { k = k.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static Object getObjectField(Object obj, String name) {
        try {
            Class<?> k = obj.getClass();
            while (k != null) {
                try {
                    Field f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) { k = k.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void done(String pkg) {
        log(Log.INFO, MainHook.TAG, "event=install_done pkg=" + pkg + " ok=" + ok.get() + " miss=" + fail.get());
    }
}
