package io.github.ldxm666.bmapclean

import android.util.Log

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * API 102 通用挂钩工具。
 *
 * 设计取向与"静默失效"相反：每一次挂钩都打 HOOKED 或 miss，
 * 安装结束打 install_done ok=/miss=。用户拿 logcat 就能判断
 * "模块没生效" 是挂钩失败还是规则没命中。
 */
class H private constructor() {

    companion object {

        @Volatile
        @JvmField
        var module: XposedModule? = null

        /**
         * Per-hook chatter ("HOOKED …", first "HIT …") is useful when debugging but costly on
         * a cold start: 110 hooks x one log line each lands right in the first-frame window.
         * Off by default; the miss warnings and the install_done summary stay.
         */
        @Volatile
        @JvmField
        var verbose = false

        @JvmField
        val ok = AtomicInteger()

        @JvmField
        val miss = AtomicInteger()

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
        fun log(priority: Int, tag: String, msg: String, t: Throwable?) {
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
        fun cls(cl: ClassLoader?, name: String): Class<*>? {
            return try { cl!!.loadClass(name) } catch (t: Throwable) { null }
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
        @JvmStatic
        fun hook(m: java.lang.reflect.Executable?, id: String, hk: XposedInterface.Hooker): Boolean {
            if (m == null) {
                miss.incrementAndGet()
                log(Log.WARN, MainHook.TAG, "miss " + id + " (method not found)")
                return false
            }
            try {
                m.isAccessible = true
                val mod = module
                if (mod == null) {
                    miss.incrementAndGet()
                    log(Log.WARN, MainHook.TAG, "miss " + id + " (module null)")
                    return false
                }
                val inner: XposedInterface.Hooker = hk
                val hid = id
                val guarded = io.github.ldxm666.mapclean.HookGuard(id, inner,
                        object : io.github.ldxm666.mapclean.HookGuard.Reporter {
                            override fun disabled(name: String, error: Throwable) {
                                log(Log.WARN, MainHook.TAG, "event=hook_disabled id=" + name + " reason=" + error.javaClass.simpleName + ": " + error.message)
                            }
                        })
                mod.hook(m)
                   .setId(id)
                   .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                   .intercept(object : XposedInterface.Hooker {
                       private var first = true
                       override fun intercept(chain: XposedInterface.Chain): Any? {
                           if (first) {
                               first = false
                               if (verbose) log(Log.INFO, MainHook.TAG, "HIT " + hid)
                           }
                           return guarded.intercept(chain)
                       }
                   })
                ok.incrementAndGet()
                if (verbose) log(Log.INFO, MainHook.TAG, "HOOKED " + id + " -> " + m)
                return true
            } catch (t: Throwable) {
                miss.incrementAndGet()
                log(Log.WARN, MainHook.TAG, "miss " + id + " err=" + t)
                return false
            }
        }

        /**
         * 按名取方法（取第一个非抽象、参数个数匹配的）。
         * argCount &lt; 0 表示不限参数个数。
         */
        @JvmStatic
        fun method(c: Class<*>?, name: String, argCount: Int): Method? {
            if (c == null) return null
            try {
                for (m in c.declaredMethods) {
                    if (m.name != name) continue
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (argCount >= 0 && m.parameterTypes.size != argCount) continue
                    return m
                }
            } catch (ignored: Throwable) {}
            return null
        }

        @JvmStatic
        fun done(pkg: String) {
            log(Log.INFO, MainHook.TAG, "event=install_done pkg=" + pkg
                    + " ok=" + ok.get() + " miss=" + miss.get())
        }
    }
}