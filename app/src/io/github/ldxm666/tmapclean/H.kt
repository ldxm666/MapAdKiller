package io.github.ldxm666.tmapclean

import android.util.Log

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * API 102 通用挂钩工具（腾讯侧）。
 *
 * <p>与 {@code bmapclean.H} 同一取向：每一次挂钩都打 HOOKED 或 miss，安装结束打
 * {@code install_done ok=/miss=}。用户拿 logcat 就能判断"模块没生效"是挂钩失败还是规则没命中。
 */
object H {

    @Volatile
    @JvmField
    var module: XposedModule? = null

    @JvmField
    val ok = AtomicInteger()

    @JvmField
    val miss = AtomicInteger()

    // Resolve named members once per runtime class, including missing members.
    // Weak keys and weak values avoid retaining a host class loader on Android < 14.
    // java.lang.ClassValue is unavailable there; using it prevents H from loading.
    private class Members {
        val fields = ConcurrentHashMap<String, FieldSlot>()
        val methods = ConcurrentHashMap<String, MethodSlot>()
    }

    private class FieldSlot(val value: Field?)

    private class MethodSlot(val value: Method?)

    private val MEMBERS = WeakHashMap<Class<*>, WeakReference<Members>>()

    private fun members(type: Class<*>): Members = synchronized(MEMBERS) {
        MEMBERS[type]?.get() ?: Members().also { MEMBERS[type] = WeakReference(it) }
    }

    private fun namedField(type: Class<*>, name: String): Field? {
        val members = members(type)
        val cached = members.fields[name]
        if (cached != null) return cached.value
        var found: Field? = null
        var node: Class<*>? = type
        while (node != null) {
            val k: Class<*> = node
            try {
                val f = k.getDeclaredField(name)
                f.setAccessible(true)
                found = f
                break
            } catch (ignored: NoSuchFieldException) {
            } catch (ignored: Throwable) {
                break
            }
            node = k.getSuperclass()
        }
        val slot = FieldSlot(found)
        val previous = members.fields.putIfAbsent(name, slot)
        return if (previous == null) slot.value else previous.value
    }

    @JvmStatic
    fun method(type: Class<*>?, name: String?, vararg params: Class<*>): Method? {
        if (type == null || name == null) return null
        val key = StringBuilder(name).append('(')
        for (param in params) key.append(param.name).append(';')
        val signature = key.append(')').toString()
        val members = members(type)
        val cached = members.methods[signature]
        if (cached != null) return cached.value
        var found: Method? = null
        var node: Class<*>? = type
        while (node != null) {
            val k: Class<*> = node
            try {
                val m = k.getDeclaredMethod(name, *params)
                m.setAccessible(true)
                found = m
                break
            } catch (ignored: NoSuchMethodException) {
            } catch (ignored: Throwable) {
                break
            }
            node = k.getSuperclass()
        }
        val slot = MethodSlot(found)
        val previous = members.methods.putIfAbsent(signature, slot)
        return if (previous == null) slot.value else previous.value
    }

    @JvmStatic
    fun log(priority: Int, tag: String, msg: String) {
        val m = module
        if (m != null) {
            m.log(priority, tag, msg)
        } else {
            Log.println(priority, tag, msg)
        }
    }

    @JvmStatic
    fun log(priority: Int, tag: String, msg: String, t: Throwable) {
        val m = module
        if (m != null) {
            m.log(priority, tag, msg, t)
        } else {
            Log.println(priority, tag, msg + " : " + t)
        }
    }

    @JvmStatic
    fun log(msg: String) {
        log(Log.INFO, MainHook.TAG, msg)
    }

    @JvmStatic
    fun cls(cl: ClassLoader?, name: String?): Class<*>? {
        if (cl == null || name == null) return null
        var n = name
        try {
            // ⚠ 2.3.0 防御：描述符分隔符（{@code /}）与二进制名分隔符（{@code .}）混淆过一次，
            // 直接把「大家都在看」整条数据链变成死代码（见 TmapLookAndLook 的 ATTR）。
            // loadClass 只认点分形式，这里统一抹平 —— 再写错也不会静默失效。
            if (n.indexOf('/') >= 0) n = n.replace('/', '.')
            return cl.loadClass(n)
        } catch (t: Throwable) {
            return null
        }
    }

    /** 挂一个方法；返回是否成功。首火打一条 HIT，便于区分"没挂上"和"没触发"。 */
    @JvmStatic
    fun hook(m: Method?, id: String, hk: XposedInterface.Hooker): Boolean {
        if (m == null) {
            miss.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss " + id + " (method not found)")
            return false
        }
        try {
            m.setAccessible(true)
            val mod = module
            if (mod == null) {
                miss.incrementAndGet()
                log(Log.WARN, MainHook.TAG, "miss " + id + " (module null)")
                return false
            }
            val inner: XposedInterface.Hooker = hk
            val hid = id
            val guarded = io.github.ldxm666.mapclean.HookGuard(
                id, inner,
                object : io.github.ldxm666.mapclean.HookGuard.Reporter {
                    override fun disabled(name: String, error: Throwable) {
                        log(
                            Log.WARN, MainHook.TAG, "event=hook_disabled id=" + name
                                    + " reason=" + error.javaClass.simpleName + ": " + error.message
                        )
                    }
                }
            )
            mod.hook(m)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(object : XposedInterface.Hooker {
                    private var first = true
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (first) {
                            first = false
                            log(Log.INFO, MainHook.TAG, "HIT " + hid)
                        }
                        return guarded.intercept(chain)
                    }
                })
            ok.incrementAndGet()
            log(Log.INFO, MainHook.TAG, "HOOKED " + id + " -> " + m)
            return true
        } catch (t: Throwable) {
            miss.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss " + id + " err=" + t)
            return false
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
    @JvmStatic
    fun hookAll(c: Class<*>?, name: String, id: String, hk: XposedInterface.Hooker): Int {
        // 2026-10-14：原来这里是 "if (c == null) return 0;" —— 静默吞掉。
        // 类名写错（例如把目录分隔符 / 写成 .）时 H.cls() 返回 null，
        // 这个钩子就根本没挂上，但日志里既没有 HOOKED 也没有 miss，
        // 看 install_done 的 ok/miss 完全看不出来 —— 「大家都在看」就是这样
        // 静默失效了整个 2.1.x。现在显式记 miss，写错立刻可见。
        if (c == null) {
            miss.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss " + id + " (class not found: " + name + ")")
            return 0
        }
        var n = 0
        var node: Class<*>? = c
        while (node != null && node !== Any::class.java) {
            val k: Class<*> = node
            var declared: Array<Method>? = null
            try {
                declared = k.getDeclaredMethods()
            } catch (t: Throwable) {
                declared = null
            }
            val all = declared
            if (all != null) {
                for (i in all.indices) {
                    val m = all[i]
                    if (m.name != name) continue
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (hook(m, id + "#" + k.simpleName + "#" + i, hk)) n++
                }
            }
            node = k.getSuperclass()
        }
        if (n == 0) {
            miss.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss " + id + " (" + name + " not found)")
        }
        return n
    }

    /**
     * 只挂<b>本类自己声明</b>的方法（不遍历父类）。
     *
     * <p>父类是框架类时（例如 {@code MapVLCardView extends VLCardView extends View}），
     * 用 [hookAll] 会把 {@code android.view.View.onSizeChanged} 这种每帧都走的
     * 框架方法一起挂上 —— 影响面不可控。需要"只挂这个类"时用本方法。
     */
    @JvmStatic
    fun hookDeclared(c: Class<*>?, name: String, id: String,
                     hk: XposedInterface.Hooker): Int {
        if (c == null) {
            miss.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss " + id + " (class not found)")
            return 0
        }
        var n = 0
        var declared: Array<Method>? = null
        try {
            declared = c.getDeclaredMethods()
        } catch (t: Throwable) {
            declared = null
        }
        val all = declared
        if (all != null) {
            for (i in all.indices) {
                val m = all[i]
                if (m.name != name) continue
                if (Modifier.isAbstract(m.modifiers)) continue
                if (hook(m, id + "#" + c.simpleName + "#" + i, hk)) n++
            }
        }
        if (n == 0) {
            miss.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss " + id + " (" + name + " not declared)")
        }
        return n
    }

    /** 按精确签名挂钩（参数类型可解析时才用） */
    @JvmStatic
    fun hookSig(c: Class<*>?, name: String, id: String,
                hk: XposedInterface.Hooker, vararg params: Class<*>?): Boolean {
        if (c == null) return false
        try {
            val m = c.getDeclaredMethod(name, *params)
            return hook(m, id, hk)
        } catch (t: Throwable) {
            miss.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss " + id + " (no such sig: " + name + ")")
            return false
        }
    }

    /** 只挂「无参 + 返回 boolean」的重载（安全改返回值的唯一形态） */
    @JvmStatic
    fun hookBool0(c: Class<*>?, name: String, id: String, hk: XposedInterface.Hooker): Int {
        if (c == null) return 0
        var n = 0
        var node: Class<*>? = c
        while (node != null && node !== Any::class.java) {
            val k: Class<*> = node
            var declared: Array<Method>? = null
            try {
                declared = k.getDeclaredMethods()
            } catch (t: Throwable) {
                declared = null
            }
            val all = declared
            if (all != null) {
                for (i in all.indices) {
                    val m = all[i]
                    if (m.name != name) continue
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (m.parameterTypes.size != 0) continue
                    val rt = m.returnType
                    if (rt != java.lang.Boolean.TYPE && rt != java.lang.Boolean::class.java) continue
                    if (hook(m, id + "#" + k.simpleName + "#" + i, hk)) n++
                }
            }
            node = k.getSuperclass()
        }
        return n
    }

    /** 直通观察 Hooker：原样放行，只把首次返回值记一条日志（用于校准语义） */
    @JvmStatic
    fun observe(what: String): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var once = false
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val r = chain.proceed()
                if (!once) {
                    once = true
                    log(Log.INFO, MainHook.TAG, "OBSERVE " + what + " -> " + r)
                }
                return r
            }
        }
    }

    @JvmField
    val VOID: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? = null
    }

    @JvmField
    val FALSE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? = java.lang.Boolean.FALSE
    }

    @JvmField
    val TRUE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? = java.lang.Boolean.TRUE
    }

    /** 反射读实例/静态字段（含父类查找）。按名访问已实证的类，不做字段枚举。 */
    @JvmStatic
    fun field(obj: Any?, name: String): Any? {
        if (obj == null) return null
        try {
            val f = namedField(obj.javaClass, name)
            if (f != null) return f.get(obj)
        } catch (ignored: Throwable) {
        }
        return null
    }

    /** 反射写实例字段（含父类查找） */
    @JvmStatic
    fun setField(obj: Any?, name: String, value: Any?): Boolean {
        if (obj == null) return false
        val f: Field
        try {
            f = namedField(obj.javaClass, name) ?: return false
        } catch (ignored: Throwable) {
            return false
        }
        try {
            f.set(obj, value)
            return true
        } catch (ignored: Throwable) {
        }
        return false
    }

    /** 调用无参方法（按名，含父类查找），失败返回 null */
    @JvmStatic
    fun call(obj: Any?, name: String): Any? {
        if (obj == null) return null
        try {
            val m = method(obj.javaClass, name)
            if (m != null) return m.invoke(obj)
        } catch (ignored: Throwable) {
        }
        return null
    }

    /**
     * 调用单参数方法（按名，含父类查找）。命中返回 {@code TRUE}，否则返回 null。
     *
     * <p>参数类型同时试包装类与基本类型 —— 腾讯这些引擎 setter 收的是 {@code boolean}，
     * 而调用方传进来的自然是 {@code Boolean}，只试包装类会 {@code NoSuchMethodException}。
     */
    @JvmStatic
    fun call1(obj: Any?, name: String, arg: Any?): Any? {
        if (obj == null) return null
        try {
            val m = findOne(obj.javaClass, name, arg)
            if (m != null) {
                m.invoke(obj, arg)
                return java.lang.Boolean.TRUE
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun findOne(k: Class<*>, name: String, arg: Any?): Method? {
        val tries: Array<Class<*>>
        if (arg == null) {
            tries = arrayOf<Class<*>>(Any::class.java)
        } else {
            val w: Class<*> = arg.javaClass
            val p: Class<*>? = if (w == java.lang.Boolean::class.java) java.lang.Boolean.TYPE
            else if (w == java.lang.Integer::class.java) java.lang.Integer.TYPE
            else if (w == java.lang.Float::class.java) java.lang.Float.TYPE
            else if (w == java.lang.Long::class.java) java.lang.Long.TYPE
            else if (w == java.lang.Double::class.java) java.lang.Double.TYPE
            else null
            tries = if (p == null) arrayOf(w) else arrayOf(p, w)
        }
        for (i in tries.indices) {
            val m = method(k, name, tries[i])
            if (m != null) return m
        }
        return null
    }

    @JvmStatic
    fun done(pkg: String) {
        log(
            Log.INFO, MainHook.TAG, "event=install_done pkg=" + pkg
                    + " ok=" + ok.get() + " miss=" + miss.get()
        )
    }
}
