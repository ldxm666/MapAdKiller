package io.github.ldxm666.mapadkiller

import android.util.Log

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.HashSet

import java.util.concurrent.atomic.AtomicInteger

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * API 102 通用挂钩工具：按名/按签名 hook、计数、容错、常用语义 Hooker。
 * 全部基于 io.github.libxposed.api（HookBuilder/Chain/Hooker），无 legacy 依赖。
 */
object H {

    @JvmField
    @Volatile
    var module: XposedModule? = null

    @JvmField
    val ok: AtomicInteger = AtomicInteger()
    @JvmField
    val fail: AtomicInteger = AtomicInteger()
    private val installedPkgs: MutableSet<String> = HashSet()
    /** 已经撤销过一次的包名：保证「失败重试」每个进程最多发生一次。 */
    private val retriedPkgs: MutableSet<String> = HashSet()

    @JvmStatic
    @Synchronized
    fun markInstalled(pkg: String): Boolean {
        return installedPkgs.add(pkg)
    }

    /**
     * 撤销 [markInstalled] 的占位，让下一次 `onPackageReady` 能重试安装。
     *
     * <p>为什么需要：安装入口是"先占位再安装"，安装中途抛异常时占位不会退回，于是同一个包在
     * 本进程内之后的每一次 `onPackageReady` 都会 `install_skipped` —— 用户看到的就是
     * 「模块明明装了，整片功能没效果，必须强停重开」。撤销占位把这一次失败变成可重试。
     *
     * <p>为什么**只重试一次**：重试会重新跑一遍安装，已经成功的那些 hook 会被再挂一次
     * （libxposed 对同一方法重复 hook 会抛，被 hookMethod 吞成 hook_error 日志）。一次失败
     * 大概是宿主版本对不上，反复重试只会把日志刷满；给一次机会就够。
     */
    @JvmStatic
    @Synchronized
    fun unmarkInstalled(pkg: String?) {
        if (pkg == null) return
        if (!retriedPkgs.add(pkg)) return
        installedPkgs.remove(pkg)
    }

    @JvmStatic
    fun log(priority: Int, tag: String?, msg: String?) {
        // 主动抓包：顺手抄进内存环形缓冲（10 分钟窗口），抓包中则同时写 txt。
        // 只在内存里做，异常一律吞掉，绝不因为诊断功能影响宿主。
        CaptureLog.note(priority, tag, msg)
        // libxposed 的 log() 与 android.util.Log.println() 参数都带 @NonNull，这里统一兜底：
        // Java 侧传 null 是合法的（原实现就是直接透传），Kotlin 的显式可空必须先落地成字符串。
        val tagS = tag ?: ""
        val msgS = msg ?: ""
        val m = module
        if (m != null) {
            m.log(priority, tagS, msgS)
        } else {
            // 模块自己的设置页进程里 module 恒为 null。以前这里直接丢掉，
            // 于是「抓取云数据」这类只在设置页执行的动作在 logcat 里完全不可见，
            // 出问题只能靠猜。落到标准 Log 上，TAG 用 mainhook 的。
            Log.println(priority, tagS, msgS)
        }
    }

    @JvmStatic
    fun log(priority: Int, tag: String?, msg: String?, t: Throwable?) {
        val m = module
        if (m != null) {
            m.log(priority, tag ?: "", msg ?: "", t)
        } else {
            Log.println(priority, tag ?: "", (msg ?: "") + " : " + t)
        }
    }

    /**
     * 启动期「可关」日志：仅 `debug_log` 打开时才输出。
     *
     * <p>为什么要有这个：真机抓包发现单次冷启动会打出
     * `amap_tool_keep` ×~​10、`amap_tool_fill` ×~40、`search_grid_helper` ×~​10、
     * `feature_probe` ×~​20 —— <b>合计 80 多行</b>，全部在宿主启动的主线程上。
     * 每一行都是一次 logcat 写（binder + 锁 + 格式化），这些对用户没任何价值，
     * 只在排障时有用。所以默认关。
     */
    @JvmStatic
    fun logv(msg: String) {
        if (Config.debugLog()) log(msg)
    }

    @JvmStatic
    fun log(msg: String) {
        log(Log.INFO, MainHook.TAG, msg)
    }

    /** 常用语义 Hooker（匿名类实现，避免 javac -bootclasspath android.jar 下 lambda 无法 desugar） */
    @JvmField
    val VOID: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            return null
        }
    }
    @JvmField
    val FALSE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            return java.lang.Boolean.FALSE
        }
    }
    @JvmField
    val TRUE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            return java.lang.Boolean.TRUE
        }
    }

    @JvmStatic
    fun cls(cl: ClassLoader?, name: String): Class<*>? {
        try {
            return cl!!.loadClass(name)
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * Resolve one method by exact signature, or null.
     *
     * Used where the module needs to call the original again later -- a Chain can only be
     * proceeded once, so anything that has to be re-run (rebuilding the home panel when a
     * switch changes) needs a real Method handle rather than the chain.
     */
    @JvmStatic
    fun method(c: Class<*>?, name: String, vararg params: Class<*>): Method? {
        if (c == null) return null
        try {
            val m = c.getDeclaredMethod(name, *params)
            m.isAccessible = true
            return m
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * 在候选表里取**第一个真能解析到**的 (类, 方法) 对，用于抗混淆名重排。
     *
     * <p>混淆名跨构建会整体重排：17.02.0.1541（国内）的工具清单上游是 `k84.f()`，
     * 17.02.2.2014（Play）变成了 `s27.d()`。坑在于 `k84` 这个名字在两版**都存在**，
     * 但 Play 的 `Lk84;` 已经是另一个无关类（只有 `a`/`b`，没有 `f`）。
     * 所以判据必须是「类能加载 **且** 类上真有这个方法」；只看类名会把握手点挂到错的类上，
     * 更糟的是挂上去了却永远不 HIT —— 日志里连 miss 都看不见。
     *
     * <p>首个命中即返回，不遍历全部：候选顺序就是优先级，老版本永远命中第一项，
     * 行为与不加这张表时逐字一致。
     *
     * <p>判据是「类上存在**任一**同名方法」，不是「存在某个精确签名」：候选表只负责定位
     * 类，签名由调用点的 `hookSig(..., params)` 决定。混淆后的同名方法在两个构建里
     * 可能换了参数表（`wm3.c(String,String)` 这类），这里一验签名就会把整条候选误杀。
     *
     * @param picked 长度 ≥ 2 的出参，回填实际命中的 {类名, 方法名}；未命中不动
     * @return 命中的 Class；全部候选都解析不到则返回 null
     */
    @JvmStatic
    fun anchor(cl: ClassLoader?, candidates: Array<out Array<String>?>?, picked: Array<String?>?): Class<*>? {
        if (candidates == null) return null
        for (c in candidates) {
            if (c == null || c.size < 2) continue
            val k = cls(cl, c[0])
            if (k == null) continue
            if (!declares(k, c[1])) continue
            if (picked != null && picked.size >= 2) {
                picked[0] = c[0]
                picked[1] = c[1]
            }
            return k
        }
        return null
    }

    /** 类上是否声明了**任一**同名方法（不论参数表）。 */
    private fun declares(c: Class<*>?, name: String): Boolean {
        if (c == null) return false
        try {
            for (m in c.declaredMethods) if (m.name == name) return true
        } catch (ignored: Throwable) {
        }
        return false
    }

    /** 按名 hook：本类 + 全部父类中同名方法（等价旧 hookAllMethods），返回命中数 */
    @JvmStatic
    fun hookAll(c: Class<*>?, method: String, id: String, hooker: XposedInterface.Hooker): Int {
        if (c == null) return 0
        var n = 0
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (m in k.declaredMethods) {
                if (m.name != method) continue
                if (Modifier.isAbstract(m.modifiers)) continue // 抽象方法不可 hook，跳过避免 hook_error 刷屏
                if (hookMethod(m, id + "#" + k.simpleName + "#" + n, hooker)) n++
            }
            k = k.superclass
        }
        count(n > 0, c, method + (if (n > 0) "*(" + n + ")" else " MISS"))
        return n
    }

    /**
     * 只挂「无参 + 返回 boolean」的重载。
     *
     * 混淆过的单字母方法名（SplashAdManager.F/z 这类）在大版本更新后会整体重排，
     * 用 hookAll 按名强改返回值风险极大 —— 一旦撞上的是「是否已完成」之类的状态查询，
     * 就等于把 App 永远钉在 false 上（实测表现：卡开屏，按返回键才进得去）。
     * 所以对这类方法一律只认 ()Z 签名，并且默认只观察、不篡改。
     */
    @JvmStatic
    fun hookAllBool0(c: Class<*>?, method: String, id: String, hooker: XposedInterface.Hooker): Int {
        if (c == null) return 0
        var n = 0
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (m in k.declaredMethods) {
                if (m.name != method) continue
                if (Modifier.isAbstract(m.modifiers)) continue
                if (m.parameterTypes.size != 0) continue
                val rt = m.returnType
                if (rt != Boolean::class.javaPrimitiveType && rt != java.lang.Boolean::class.java) continue
                if (hookMethod(m, id + "#" + k.simpleName + "#" + n, hooker)) n++
            }
            k = k.superclass
        }
        count(n > 0, c, method + (if (n > 0) "()Z*(" + n + ")" else " MISS"))
        return n
    }

    /** 直通观察 Hooker：原样放行，只把首次返回值记一条日志（用于校准混淆方法语义）。 */
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

    /** 按精确签名 hook */
    @JvmStatic
    fun hookSig(c: Class<*>?, method: String, id: String, hooker: XposedInterface.Hooker, vararg params: Class<*>?) {
        if (c == null) return
        try {
            val m = c.getDeclaredMethod(method, *params)
            val r = hookMethod(m, id, hooker)
            count(r, c, method + (if (r) "" else " MISS(sig)"))
        } catch (e: NoSuchMethodException) {
            count(false, c, method + " (no such sig)")
        } catch (t: Throwable) {
            count(false, c, method + " (" + t.message + ")")
        }
    }

    private fun hookMethod(m: Method, id: String, hooker: XposedInterface.Hooker): Boolean {
        try {
            m.isAccessible = true
            val mod = module ?: return false
            val inner = hooker
            val hid = id
            val guarded = io.github.ldxm666.mapclean.HookGuard(id, inner,
                    object : io.github.ldxm666.mapclean.HookGuard.Reporter {
                        override fun disabled(name: String, error: Throwable) {
                            // describe() 而不是 getMessage()：旧 Android 上 NPE 的 message 是 null，
                            // 只有类名等于没线索；describe 会补上栈顶帧（哪个类哪一行），
                            // 这是判断"是版本漂了还是控件还没 attach"的唯一依据。
                            log(Log.WARN, MainHook.TAG, "event=hook_disabled id=" + name
                                    + " reason=" + io.github.ldxm666.mapclean.HookGuard.describe(error))
                        }
                    })
            // 首火日志包装 + 冷启动耗时探针。
            //
            // 为什么把计时挂在这里、而不是逐个 hook 体里手写：这是全模块**唯一**的 hook
            // 包装点，挂一处就等于给所有 hook 都装上计时，以后新增 hook 不会再漏。
            // 代价是每次调用多两次 System.nanoTime()（~20 ns/次），且只有超过
            // Perf.thresholdNs 的那几次才写日志 —— 冷启动期间 amap_tool_default_raw
            // 这类 hook 会被调用上千次，任何一次无条件拼接都会把"测量"变成新的瓶颈。
            val perf = Perf.slot(id)
            mod.hook(m)
               .setId(id)
               .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
               .intercept(object : XposedInterface.Hooker {
                   private var hitLogged = false
                   override fun intercept(chain: XposedInterface.Chain): Any? {
                       if (!hitLogged) {
                           hitLogged = true
                           log(Log.INFO, MainHook.TAG, "HIT " + hid)
                       }
                       val t0 = Perf.begin()
                       try {
                           return guarded.intercept(chain)
                       } finally {
                           Perf.end(perf, t0)
                       }
                   }
               })
            return true
        } catch (t: Throwable) {
            log(Log.WARN, MainHook.TAG, "event=hook_error method=" + m + " err=" + t)
            return false
        }
    }

    private fun count(success: Boolean, c: Class<*>?, m: String) {
        if (success) {
            ok.incrementAndGet()
            log(Log.INFO, MainHook.TAG, "HOOKED  " + c!!.name + "." + m)
        } else {
            fail.incrementAndGet()
            log(Log.WARN, MainHook.TAG, "miss    " + (if (c == null) "?" else c.name) + "." + m)
        }
    }

    /** 反射设置实例 int 字段（含父类查找） */
    @JvmStatic
    fun setIntField(obj: Any?, name: String, value: Int): Boolean {
        try {
            var k: Class<*>? = obj!!.javaClass
            while (k != null) {
                try {
                    val f: Field = k.getDeclaredField(name)
                    f.isAccessible = true
                    f.setInt(obj, value)
                    return true
                } catch (e: NoSuchFieldException) {
                    k = k.superclass
                }
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    @JvmStatic
    fun getObjectField(obj: Any?, name: String): Any? {
        try {
            var k: Class<*>? = obj!!.javaClass
            while (k != null) {
                try {
                    val f: Field = k.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (e: NoSuchFieldException) {
                    k = k.superclass
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    @JvmStatic
    fun done(pkg: String?) {
        Perf.phase("install_done")
        log(Log.INFO, MainHook.TAG, "event=install_done pkg=" + pkg + " ok=" + ok.get() + " miss=" + fail.get())
    }
}
