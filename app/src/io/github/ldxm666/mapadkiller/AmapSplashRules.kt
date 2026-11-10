package io.github.ldxm666.mapadkiller

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Verified advertising readiness rules, not generic SDK show/load stubs.
 * CN 17.02.0.2010: y96.g(int,String) -> y96$a.
 * Play 17.02.2.2014: wm6.g(int,String) -> wm6$a.
 * Both callers are com.autonavi.minimap.g.o(int,String): result.a == 1
 * calls e(SplashFinishReason.NO_SPLASH), including normal lifecycle completion.
 * This covers cached materials without deleting cache or replacing the boot preloader.
 */
object AmapSplashRules {
    @JvmField val CACHE_GATES = arrayOf("y96", "wm6")
    private val presentationDepth = ThreadLocal<Int>()

    @JvmStatic fun enabled(embedded: Boolean, local: Boolean?, fallback: Boolean): Boolean =
            if (embedded && local != null) local else fallback

    @JvmStatic fun beginPresentation(): Int {
        val previous = presentationDepth.get() ?: 0
        presentationDepth.set(previous + 1)
        return previous
    }
    @JvmStatic fun endPresentation(previous: Int) {
        if (previous == 0) presentationDepth.remove() else presentationDepth.set(previous)
    }

    /** Fail open when an obfuscated name has been reused for another class. */
    @JvmStatic
    fun resolveGate(owner: Class<*>?): Method? = try {
        if (owner == null) null else {
            val method = owner.getDeclaredMethod("g", Int::class.javaPrimitiveType, String::class.java)
            val result = method.returnType
            val singleton = owner.getDeclaredMethod("b")
            if (Modifier.isStatic(method.modifiers) || result.name != owner.name + "\$a"
                    || owner.getDeclaredMethod("h", Int::class.javaPrimitiveType, String::class.java).returnType != result
                    || owner.getDeclaredMethod("a").returnType != Boolean::class.javaPrimitiveType
                    || owner.getDeclaredMethod("f", Int::class.javaPrimitiveType).returnType != Boolean::class.javaPrimitiveType
                    || !Modifier.isStatic(singleton.modifiers) || singleton.returnType != owner
                    || !validResult(result)) null else method.apply { isAccessible = true }
        }
    } catch (_: Throwable) { null }

    private fun validResult(type: Class<*>): Boolean {
        // R8 removed the constructors from both real result classes. Their g/h
        // methods allocate a fresh result directly; never require reflection newInstance.
        for ((name, expected) in arrayOf("a" to Int::class.javaPrimitiveType,
                "b" to String::class.java, "c" to String::class.java)) {
            val field = type.getDeclaredField(name)
            if (field.type != expected || Modifier.isStatic(field.modifiers)
                    || Modifier.isFinal(field.modifiers)) return false
        }
        return true
    }

    /** Change only this call's fresh readiness result, never cached advertising data.
     * The CN/Play DEX g/h methods allocate every returned result anew (no shared model).
     * Return whether an allowed advertisement changed to the native NO_SPLASH branch.
     */
    @JvmStatic
    fun denyAd(method: Method, native: Any?): Boolean = try {
        val type = method.returnType
        // The same readiness method also serves boot preloading and schema routing.
        // Only the synchronous advertising presentation caller may change its result.
        if ((presentationDepth.get() ?: 0) == 0 || native == null || native.javaClass != type) false else {
            val state = type.getDeclaredField("a").apply { isAccessible = true }
            if (state.getInt(native) != 0) false else {
                // Fill the diagnostic reason before the final state write. If reflection
                // fails the native allowed state is retained and the host can continue.
                type.getDeclaredField("b").apply { isAccessible = true }.set(native, "mapclean_ad_blocked")
                state.setInt(native, 1)
                true
            }
        }
    } catch (_: Throwable) { false }
}
