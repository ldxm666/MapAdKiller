package io.github.ldxm666.tmapclean;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * API 102 通用挂钩工具（腾讯侧）。
 *
 * <p>与 {@code bmapclean.H} 同一取向：每一次挂钩都打 HOOKED 或 miss，安装结束打
 * {@code install_done ok=/miss=}。用户拿 logcat 就能判断"模块没生效"是挂钩失败还是规则没命中。
 */
public final class H {

    public static volatile XposedModule module;

    public static final AtomicInteger ok = new AtomicInteger();
    public static final AtomicInteger miss = new AtomicInteger();

    private H() {}

    // Resolve named members once per runtime class, including missing members.
    // ClassValue releases these caches together with the host class loader.
    private static final class Members {
        final ConcurrentHashMap<String, FieldSlot> fields = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, MethodSlot> methods = new ConcurrentHashMap<>();
    }
    private static final class FieldSlot {
        final Field value;
        FieldSlot(Field value) { this.value = value; }
    }
    private static final class MethodSlot {
        final Method value;
        MethodSlot(Method value) { this.value = value; }
    }
    private static final ClassValue<Members> MEMBERS = new ClassValue<Members>() {
        @Override protected Members computeValue(Class<?> type) { return new Members(); }
    };

    private static Field namedField(Class<?> type, String name) {
        Members members = MEMBERS.get(type);
        FieldSlot cached = members.fields.get(name);
        if (cached != null) return cached.value;
        Field found = null;
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            try {
                found = k.getDeclaredField(name);
                found.setAccessible(true);
                break;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) { break; }
        }
        FieldSlot slot = new FieldSlot(found);
        FieldSlot previous = members.fields.putIfAbsent(name, slot);
        return previous == null ? slot.value : previous.value;
    }

    public static Method method(Class<?> type, String name, Class<?>... params) {
        if (type == null || name == null) return null;
        StringBuilder key = new StringBuilder(name).append('(');
        for (Class<?> param : params) key.append(param.getName()).append(';');
        String signature = key.append(')').toString();
        Members members = MEMBERS.get(type);
        MethodSlot cached = members.methods.get(signature);
        if (cached != null) return cached.value;
        Method found = null;
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            try {
                found = k.getDeclaredMethod(name, params);
                found.setAccessible(true);
                break;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) { break; }
        }
        MethodSlot slot = new MethodSlot(found);
        MethodSlot previous = members.methods.putIfAbsent(signature, slot);
        return previous == null ? slot.value : previous.value;
    }

    public static void log(int priority, String tag, String msg) {
        XposedModule m = module;
        if (m != null) m.log(priority, tag, msg);
        else Log.println(priority, tag, msg);
    }

    public static void log(int priority, String tag, String msg, Throwable t) {
        XposedModule m = module;
        if (m != null) m.log(priority, tag, msg, t);
        else Log.println(priority, tag, msg + " : " + t);
    }

    public static void log(String msg) {
        log(Log.INFO, MainHook.TAG, msg);
    }

    public static Class<?> cls(ClassLoader cl, String name) {
        if (cl == null || name == null) return null;
        try {
            // ⚠ 2.3.0 防御：描述符分隔符（{@code /}）与二进制名分隔符（{@code .}）混淆过一次，
            // 直接把「大家都在看」整条数据链变成死代码（见 TmapLookAndLook 的 ATTR）。
            // loadClass 只认点分形式，这里统一抹平 —— 再写错也不会静默失效。
            if (name.indexOf('/') >= 0) name = name.replace('/', '.');
            return cl.loadClass(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 挂一个方法；返回是否成功。首火打一条 HIT，便于区分"没挂上"和"没触发"。 */
    public static boolean hook(Method m, String id, XposedInterface.Hooker hk) {
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
            final io.github.ldxm666.mapclean.HookGuard guarded =
                    new io.github.ldxm666.mapclean.HookGuard(id, inner,
                            new io.github.ldxm666.mapclean.HookGuard.Reporter() {
                                public void disabled(String name, Throwable error) {
                                    log(Log.WARN, MainHook.TAG, "event=hook_disabled id=" + name
                                            + " reason=" + error.getClass().getSimpleName() + ": " + error.getMessage());
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
                           log(Log.INFO, MainHook.TAG, "HIT " + hid);
                       }
                       return guarded.intercept(chain);
                   }
               });
            ok.incrementAndGet();
            log(Log.INFO, MainHook.TAG, "HOOKED " + id + " -> " + m);
            return true;
        } catch (Throwable t) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " err=" + t);
            return false;
        }
    }

    /**
     * 按名挂钩本类 + 全部父类中<b>全部</b>同名非抽象重载。
     *
     * <p>腾讯把工具类参数类型（例如 {@code setCardData} 的入参）随版本整体重排：
     * 11.6.0 是 {@code navisdk.a.iqr}，11.7.0 变成 {@code navisdk.a.iri}。
     * 写死参数类型等于给自己埋一个跨版本必炸的雷，所以这类方法一律按名挂，
     * 由业务侧只做"放行 / 吞掉"这类与参数类型无关的决策。
     */
    public static int hookAll(Class<?> c, String name, String id, XposedInterface.Hooker hk) {
        // 2026-10-14：原来这里是 "if (c == null) return 0;" —— 静默吞掉。
        // 类名写错（例如把目录分隔符 / 写成 .）时 H.cls() 返回 null，
        // 这个钩子就根本没挂上，但日志里既没有 HOOKED 也没有 miss，
        // 看 install_done 的 ok/miss 完全看不出来 —— 「大家都在看」就是这样
        // 静默失效了整个 2.1.x。现在显式记 miss，写错立刻可见。
        if (c == null) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " (class not found: " + name + ")");
            return 0;
        }
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] all;
            try { all = k.getDeclaredMethods(); } catch (Throwable t) { continue; }
            for (int i = 0; i < all.length; i++) {
                Method m = all[i];
                if (!m.getName().equals(name)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (hook(m, id + "#" + k.getSimpleName() + "#" + i, hk)) n++;
            }
        }
        if (n == 0) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " (" + name + " not found)");
        }
        return n;
    }

    /**
     * 只挂<b>本类自己声明</b>的方法（不遍历父类）。
     *
     * <p>父类是框架类时（例如 {@code MapVLCardView extends VLCardView extends View}），
     * 用 {@link #hookAll} 会把 {@code android.view.View.onSizeChanged} 这种每帧都走的
     * 框架方法一起挂上 —— 影响面不可控。需要"只挂这个类"时用本方法。
     */
    public static int hookDeclared(Class<?> c, String name, String id,
                                   XposedInterface.Hooker hk) {
        if (c == null) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " (class not found)");
            return 0;
        }
        int n = 0;
        Method[] all;
        try { all = c.getDeclaredMethods(); } catch (Throwable t) { all = new Method[0]; }
        for (int i = 0; i < all.length; i++) {
            Method m = all[i];
            if (!m.getName().equals(name)) continue;
            if (Modifier.isAbstract(m.getModifiers())) continue;
            if (hook(m, id + "#" + c.getSimpleName() + "#" + i, hk)) n++;
        }
        if (n == 0) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " (" + name + " not declared)");
        }
        return n;
    }

    /** 按精确签名挂钩（参数类型可解析时才用） */
    public static boolean hookSig(Class<?> c, String name, String id,
                                  XposedInterface.Hooker hk, Class<?>... params) {
        if (c == null) return false;
        try {
            Method m = c.getDeclaredMethod(name, params);
            return hook(m, id, hk);
        } catch (Throwable t) {
            miss.incrementAndGet();
            log(Log.WARN, MainHook.TAG, "miss " + id + " (no such sig: " + name + ")");
            return false;
        }
    }

    /** 只挂「无参 + 返回 boolean」的重载（安全改返回值的唯一形态） */
    public static int hookBool0(Class<?> c, String name, String id, XposedInterface.Hooker hk) {
        if (c == null) return 0;
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] all;
            try { all = k.getDeclaredMethods(); } catch (Throwable t) { continue; }
            for (int i = 0; i < all.length; i++) {
                Method m = all[i];
                if (!m.getName().equals(name)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt != boolean.class && rt != Boolean.class) continue;
                if (hook(m, id + "#" + k.getSimpleName() + "#" + i, hk)) n++;
            }
        }
        return n;
    }

    /** 直通观察 Hooker：原样放行，只把首次返回值记一条日志（用于校准语义） */
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

    public static final XposedInterface.Hooker VOID = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) { return null; }
    };
    public static final XposedInterface.Hooker FALSE = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) { return Boolean.FALSE; }
    };
    public static final XposedInterface.Hooker TRUE = new XposedInterface.Hooker() {
        @Override public Object intercept(XposedInterface.Chain chain) { return Boolean.TRUE; }
    };

    /** 反射读实例/静态字段（含父类查找）。按名访问已实证的类，不做字段枚举。 */
    public static Object field(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = namedField(obj.getClass(), name);
            if (f != null) return f.get(obj);
        } catch (Throwable ignored) {}
        return null;
    }

    /** 反射写实例字段（含父类查找） */
    public static boolean setField(Object obj, String name, Object value) {
        if (obj == null) return false;
        try {
            Field f = namedField(obj.getClass(), name);
            if (f == null) return false;
            f.set(obj, value);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 调用无参方法（按名，含父类查找），失败返回 null */
    public static Object call(Object obj, String name) {
        if (obj == null) return null;
        try {
            Method m = method(obj.getClass(), name);
            if (m != null) return m.invoke(obj);
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 调用单参数方法（按名，含父类查找）。命中返回 {@code TRUE}，否则返回 null。
     *
     * <p>参数类型同时试包装类与基本类型 —— 腾讯这些引擎 setter 收的是 {@code boolean}，
     * 而调用方传进来的自然是 {@code Boolean}，只试包装类会 {@code NoSuchMethodException}。
     */
    public static Object call1(Object obj, String name, Object arg) {
        if (obj == null) return null;
        try {
            Method m = findOne(obj.getClass(), name, arg);
            if (m != null) {
                m.invoke(obj, arg);
                return Boolean.TRUE;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findOne(Class<?> k, String name, Object arg) {
        Class<?>[] tries;
        if (arg == null) {
            tries = new Class<?>[]{Object.class};
        } else {
            Class<?> w = arg.getClass();
            Class<?> p = w == Boolean.class ? boolean.class
                    : w == Integer.class ? int.class
                    : w == Float.class ? float.class
                    : w == Long.class ? long.class
                    : w == Double.class ? double.class : null;
            tries = p == null ? new Class<?>[]{w} : new Class<?>[]{p, w};
        }
        for (int i = 0; i < tries.length; i++) {
            Method m = method(k, name, tries[i]);
            if (m != null) return m;
        }
        return null;
    }

    public static void done(String pkg) {
        log(Log.INFO, MainHook.TAG, "event=install_done pkg=" + pkg
                + " ok=" + ok.get() + " miss=" + miss.get());
    }
}
