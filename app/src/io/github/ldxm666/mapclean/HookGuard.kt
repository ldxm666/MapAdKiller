package io.github.ldxm666.mapclean

import java.lang.reflect.Executable
import io.github.libxposed.api.XposedInterface

/**
 * 只下线**确实不兼容**的 hook，且绝不把宿主方法执行第二次。
 *
 * <p>下线策略见 [intercept]：结构性缺失（[LinkageError]）立即停，其余异常连续
 * [FAIL_LIMIT] 次才停 —— 一条 hook 因为一次偶发 NPE 就永久失效，是"开关没反应"的头号来源。
 */
class HookGuard(
    private val id: String,
    private val hook: XposedInterface.Hooker,
    private val reporter: Reporter
) : XposedInterface.Hooker {

    interface Reporter {
        fun disabled(id: String, error: Throwable)
    }

    /** 连续失败次数（成功一次清零）。hook 可能被多个线程同时调用，用原子量避免 ++ 丢更新。 */
    private val failures = java.util.concurrent.atomic.AtomicInteger()

    /** 已下线。Java 侧是 volatile boolean，读多写一次。 */
    @Volatile
    private var stopped: Boolean = false

    override fun intercept(original: XposedInterface.Chain): Any? {
        if (stopped) return original.proceed()
        val call = Call(original)
        try {
            val result = hook.intercept(call)
            // 成功路径必须几乎零成本：这些 hook 里有的每帧都在跑。只在"确实有历史失败"时
            // 才写这个原子量，平时只是一次 volatile 读，不产生写屏障。
            if (failures.get() != 0) failures.set(0)
            return result
        } catch (error: Throwable) {
            val pending = call.failure
            if (pending != null) throw pending
            // 结构性缺失 = 宿主版本漂了（方法/字段/父类签名不存在、类找不到、so 没加载）。
            // 这类重试没有任何意义，只会每帧抛一次并把日志刷满 ⇒ 立刻下线。
            // 其余（NPE / IllegalArgumentException / IndexOutOfBounds …）常常只是"这一刻的
            // 状态不对"，下一次调用可能就正常了 ⇒ 连续 FAIL_LIMIT 次才下线。
            val structural = error is LinkageError
            val stop = structural || failures.incrementAndGet() >= FAIL_LIMIT
            if (stop) stopped = true
            // 只在"第一次失败"和"真的下线"时记日志：否则一个每帧都在抛的 hook 会把 logcat
            // 和抓包环形缓冲一起刷爆，反而盖掉真正的现场。
            if (stop || failures.get() == 1) {
                try {
                    reporter.disabled(id, error)
                } catch (ignored: Throwable) {
                }
            }
            return if (call.completed) call.result else original.proceed()
        }
    }

    private class Call(private val chain: XposedInterface.Chain) : XposedInterface.Chain {
        var completed: Boolean = false
        var result: Any? = null
        var failure: Throwable? = null

        override fun getExecutable(): Executable = chain.executable

        override fun getThisObject(): Any? = chain.thisObject

        override fun getArgs(): List<Any> = chain.args

        override fun getArg(i: Int): Any? = chain.getArg(i)

        override fun proceed(): Any? = track { chain.proceed() }

        override fun proceed(args: Array<Any>): Any? = track { chain.proceed(args) }

        override fun proceedWith(self: Any): Any? = track { chain.proceedWith(self) }

        override fun proceedWith(self: Any, args: Array<Any>): Any? = track { chain.proceedWith(self, args) }

        /**
         * 走一遍宿主方法并记下出口状态。
         *
         * <p>原实现用一个 `run(kind, self, args)` 分发，转成 Kotlin 后必须把 self/args 声明成
         * 可空才塞得进同一个签名 —— 反而和 @NonNull 的接口参数打架。改成每个 proceed 各自
         * 包一层，签名原样，也不会多一次 null 判空。
         */
        private inline fun track(block: () -> Any?): Any? {
            try {
                val value = block()
                result = value
                completed = true
                return value
            } catch (error: Throwable) {
                failure = error
                throw error
            }
        }
    }

    companion object {
        private const val FAIL_LIMIT = 3

        /**
         * 一行可读的异常描述：类名 + message + 栈顶帧。
         *
         * <p>原来 reporter 只打 {@code getClass().getSimpleName()}，NPE 就只有 "NullPointerException"
         * —— 既不知道在哪一行，也不知道哪个字段为 null，排障等于瞎。旧 Android 上
         * {@code getMessage()} 还常常是 null，所以补一个栈顶帧兜底。只在失败路径调用，代价可忽略。
         */
        @JvmStatic
        fun describe(error: Throwable?): String {
            if (error == null) return "null"
            val out = StringBuilder(error.javaClass.simpleName)
            try {
                val msg = error.message
                if (msg != null && msg.length > 0) out.append(": ").append(msg)
            } catch (ignored: Throwable) {
            }
            try {
                val trace = error.stackTrace
                if (trace != null && trace.isNotEmpty() && trace[0] != null) {
                    out.append(" @ ").append(trace[0].className).append('.')
                        .append(trace[0].methodName).append(':').append(trace[0].lineNumber)
                }
            } catch (ignored: Throwable) {
            }
            return out.toString()
        }
    }
}