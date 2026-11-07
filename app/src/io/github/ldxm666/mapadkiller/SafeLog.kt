package io.github.ldxm666.mapadkiller

import android.util.Log

/**
 * 模块 App 进程（设置页那个普通进程）专用日志。
 *
 * <p>[H] 里有 `XposedInterface.Hooker` 的静态常量，类初始化需要 libxposed API，
 * 而设置页进程是普通 App 进程，类路径里没有那套 API —— 一碰 `H.log(...)` 就是
 * `NoClassDefFoundError`，连带把调用方（例如「重新抓取」）整个打断，用户看到的就是
 * 「请求失败」。这里只用 android.util.Log，绝不引用任何 libxposed 类型。</p>
 */
object SafeLog {

    @JvmStatic
    fun i(tag: String?, msg: String?) {
        p(Log.INFO, tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String?, msg: String?) {
        p(Log.WARN, tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String?, msg: String?, t: Throwable?) {
        p(Log.WARN, tag, msg, t)
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, t: Throwable?) {
        p(Log.ERROR, tag, msg, t)
    }

    @JvmStatic
    private fun p(priority: Int, tag: String?, msg: String?, t: Throwable?) {
        try {
            if (t == null) Log.println(priority, tag, msg ?: "")
            else Log.println(priority, tag, (msg ?: "") + " : " + t)
        } catch (ignored: Throwable) {
        }
    }
}
