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
    /** 已经撤销过一次的包名：保证「失败重试」每个进程最多发生一次。 */
    private static final Set<String> retriedPkgs = new HashSet<>();

    private H() {}

    public static synchronized boolean markInstalled(String pkg) {
        return installedPkgs.add(pkg);
    }

    /**
     * 撤销 {@link #markInstalled} 的占位，让下一次 {@code onPackageReady} 能重试安装。
     *
     * <p>为什么需要：安装入口是"先占位再安装"，安装中途抛异常时占位不会退回，于是同一个包在
     * 本进程内之后的每一次 {@code onPackageReady} 都会 `install_skipped` —— 用户看到的就是
     * 「模块明明装了，整片功能没效果，必须强停重开」。撤销占位把这一次失败变成可重试。
     *
     * <p>为什么**只重试一次**：重试会重新跑一遍安装，已经成功的那些 hook 会被再挂一次
     * （libxposed 对同一方法重复 hook 会抛，被 hookMethod 吞成 hook_error 日志）。一次失败
     * 大概是宿主版本对不上，反复重试只会把日志刷满；给一次机会就够。
     */
    public static synchronized void unmarkInstalled(String pkg) {
        if (pkg == null) return;
        if (!retriedPkgs.add(pkg)) return;
        installedPkgs.remove(pkg);
    }

    public static void log(int priority, String tag, String msg) {
        // 主动抓包：顺手抄进内存环形缓冲（10 分钟窗口），抓包中则同时写 txt。
        // 只在内存里做，异常一律吞掉，绝不因为诊断功能影响宿主。
        CaptureLog.note(priority, tag, msg);
        XposedModule m = module;
        if (m != null) {
            m.log(priority, tag, msg);
        } else {
            // 模块自己的设置页进程里 module 恒为 null。以前这里直接丢掉，
            // 于是「抓取云数据」这类只在设置页执行的动作在 logcat 里完全不可见，
            // 出问题只能靠猜。落到标准 Log 上，TAG 用 mainhook 的。
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

    /**
     * 启动期「可关」日志：仅 {@code debug_log} 打开时才输出。
     *
     * <p>为什么要有这个：真机抓包发现单次冷启动会打出
     * {@code amap_tool_keep} ×~​10、{@code amap_tool_fill} ×~40、{@code search_grid_helper} ×~​10、
     * {@code feature_probe} ×~​20 —— <b>合计 80 多行</b>，全部在宿主启动的主线程上。
     * 每一行都是一次 logcat 写（binder + 锁 + 格式化），这些对用户没任何价值，
     * 只在排障时有用。所以默认关。
     */
    public static void logv(String msg) {
        if (io.github.ldxm666.mapadkiller.Config.debugLog()) log(msg);
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

    /**
     * Resolve one method by exact signature, or null.
     *
     * Used where the module needs to call the original again later -- a Chain can only be
     * proceeded once, so anything that has to be re-run (rebuilding the home panel when a
     * switch changes) needs a real Method handle rather than the chain.
     */
    public static Method method(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) { return null; }
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
            final io.github.ldxm666.mapclean.HookGuard guarded = new io.github.ldxm666.mapclean.HookGuard(id, inner,
                    new io.github.ldxm666.mapclean.HookGuard.Reporter() {
                        public void disabled(String name, Throwable error) {
                            // describe() 而不是 getMessage()：旧 Android 上 NPE 的 message 是 null，
                            // 只有类名等于没线索；describe 会补上栈顶帧（哪个类哪一行），
                            // 这是判断"是版本漂了还是控件还没 attach"的唯一依据。
                            log(Log.WARN, MainHook.TAG, "event=hook_disabled id=" + name
                                    + " reason=" + io.github.ldxm666.mapclean.HookGuard.describe(error));
                        }
                    });
            // 首火日志包装 + 冷启动耗时探针。
            //
            // 为什么把计时挂在这里、而不是逐个 hook 体里手写：这是全模块**唯一**的 hook
            // 包装点，挂一处就等于给所有 hook 都装上计时，以后新增 hook 不会再漏。
            // 代价是每次调用多两次 System.nanoTime()（~20 ns/次），且只有超过
            // Perf.thresholdNs 的那几次才写日志 —— 冷启动期间 amap_tool_default_raw
            // 这类 hook 会被调用上千次，任何一次无条件拼接都会把"测量"变成新的瓶颈。
            final Perf.Slot perf = Perf.slot(id);
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
                       long t0 = Perf.begin();
                       try {
                           return guarded.intercept(chain);
                       } finally {
                           Perf.end(perf, t0);
                       }
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
        Perf.phase("install_done");
        log(Log.INFO, MainHook.TAG, "event=install_done pkg=" + pkg + " ok=" + ok.get() + " miss=" + fail.get());
    }
}
