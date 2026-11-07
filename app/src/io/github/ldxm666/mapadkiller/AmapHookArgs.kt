package io.github.ldxm666.mapadkiller

import io.github.libxposed.api.XposedInterface

/**
 * Argument-rewriting hookers for 高德's tunable setters.
 *
 * <h3>Why a class of its own instead of adding to [H]</h3>
 * `H` is shared with the 百度 and 腾讯 halves through one APK; anything added there is
 * compiled into every variant and has to stay valid for three hosts. These three helpers are
 * only ever used by the 高德 tuning hooks, so they live next to them.
 *
 * <h3>The API-102 trap this exists to avoid</h3>
 * `Chain` has both `proceed(Object[] newArgs)` and `proceedWith(Object
 * thisObject)`. Passing an `Object[]` to the latter is silently accepted at compile time
 * (arrays are Objects) and then fails at runtime with
 * `IllegalArgumentException: thisObject must be an instance of …` — inside the host
 * process, i.e. a crash on every call. AmapTune hit exactly that and it is recorded in its
 * test report; every rewrite here goes through `proceed(Object[])`.
 *
 * <p>Values are boxed to the <em>declared</em> parameter type, read back from the executable,
 * so an overload that declares `long` or `float` still works instead of throwing
 * `IllegalArgumentException` from reflection boxing.
 */
object AmapHookArgs {

    /** Supplies the value to force into an argument slot; `null` result means "leave it". */
    fun interface Value {
        fun get(): Any?
    }

    /**
     * Force argument `index` to `value` on every call.
     *
     * <p>Zero-allocation fast path: when the caller already passes the value we would force,
     * `chain.proceed()` runs and no array is built. These setters can be called in a
     * render loop, so the steady state must not allocate.
     */
    @JvmStatic
    fun arg(index: Int, value: Value): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val wanted = value.get() ?: return chain.proceed()
                val args = chain.getArgs()
                if (index < 0 || index >= args.size) return chain.proceed()
                if (wanted == args[index]) return chain.proceed()
                val copy = args.toTypedArray()
                copy[index] = box(chain, index, wanted)
                return chain.proceed(copy)
            }
        }
    }

    /**
     * Force two argument slots: the fps value at `fpsIndex` and the "enabled" flag at
     * `flagIndex`.
     *
     * <p>`setRenderFpsWithTimer(int, int, boolean)` is the reason: handing the engine the
     * right fps while it is passed `enabled=false` does nothing at all, so both slots have
     * to move together or the hook is decoration.
     */
    @JvmStatic
    fun argPlusFlag(fpsIndex: Int, fps: Value, flagIndex: Int): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val wanted = fps.get() ?: return chain.proceed()
                val args = chain.getArgs()
                if (fpsIndex >= args.size || flagIndex >= args.size) return chain.proceed()
                val same = wanted == args[fpsIndex] &&
                        java.lang.Boolean.TRUE == args[flagIndex]
                if (same) return chain.proceed()
                val copy = args.toTypedArray()
                copy[fpsIndex] = box(chain, fpsIndex, wanted)
                copy[flagIndex] = java.lang.Boolean.TRUE
                return chain.proceed(copy)
            }
        }
    }

    /** Box to the executable's declared parameter type, not to the target's natural type. */
    private fun box(chain: XposedInterface.Chain, index: Int, value: Any?): Any? {
        try {
            val types = chain.executable.parameterTypes
            if (index >= types.size) return value
            val want = types[index]
            if (want == Boolean::class.javaPrimitiveType) {
                return java.lang.Boolean.valueOf(java.lang.Boolean.TRUE == value ||
                        (value is Number && value.toInt() != 0))
            }
            if (want == Int::class.javaPrimitiveType) {
                return java.lang.Integer.valueOf(
                        if (value is Number) value.toInt() else Integer.parseInt(value.toString()))
            }
            if (want == Long::class.javaPrimitiveType) {
                return java.lang.Long.valueOf(
                        if (value is Number) value.toLong() else java.lang.Long.parseLong(value.toString()))
            }
            if (want == Float::class.javaPrimitiveType) {
                return java.lang.Float.valueOf(
                        if (value is Number) value.toFloat() else java.lang.Float.parseFloat(value.toString()))
            }
            return value
        } catch (ignored: Throwable) {
            return value
        }
    }
}
