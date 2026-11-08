package io.github.ldxm666.tmapclean

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import io.github.libxposed.api.XposedInterface

/** Home seek-chip row: MapBaseView owns its native host and row geometry. */
object TmapSeekChips {
    private val HIDDEN = WeakHashMap<View, Int>()
    private class State {
        var height: Int? = null
        var last: Int? = null
        var suppressed = false
    }
    private val BASES = WeakHashMap<Any, State>()
    private val restoring = ThreadLocal<Boolean>()

    @JvmStatic
    fun install(cl: ClassLoader) {
        val base = H.cls(cl, "com.tencent.map.api.view.MapBaseView")
        H.hookSig(base, "setSeekChipRowHeight", "tmap_seek_row",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return request(chain)
                }
            }, java.lang.Integer.TYPE)
    }

    private fun request(chain: XposedInterface.Chain): Any? {
        if (restoring.get() == true) return chain.proceed()
        val base = chain.getThisObject() ?: return chain.proceed()
        val requested = chain.getArg(0) as? Int ?: return chain.proceed()
        val state = synchronized(BASES) { BASES.getOrPut(base) { State() } }
        state.height = requested
        if (requested > 0) state.last = requested
        val hide = H.call(base, "isHomePage") == true && !Cfg.visible(Spec.K_SEEK_CHIPS)
        val result = if (hide) chain.proceed(arrayOf<Any?>(0)) else chain.proceed()
        apply(base)
        return result
    }

    private fun apply(base: Any?) {
        if (base == null) return
        val state = synchronized(BASES) { BASES.getOrPut(base) { State() } }
        val parent = H.call(base, "getSeekParentView")
        val view = parent as? ViewGroup
        val show = H.call(base, "isHomePage") != true || Cfg.visible(Spec.K_SEEK_CHIPS)
        if (!show) {
            if (!state.suppressed) {
                if (state.height == null) state.height = (H.field(base, "seekChipRowHeight") as? Number)?.toInt()
                if (state.last == null) state.last = (H.field(base, "lastSeekChipRowHeight") as? Number)?.toInt()
                state.suppressed = true
            }
            // The host uses this field for the floating row's travel and spacing.
            H.setField(base, "seekChipRowHeight", Integer.valueOf(0))
            H.setField(base, "lastSeekChipRowHeight", Integer.valueOf(0))
        }
        if (view != null) synchronized(HIDDEN) {
            if (!show) {
                if (view.visibility != View.GONE) {
                    HIDDEN.put(view, view.visibility)
                    view.visibility = View.GONE
                }
            } else {
                val original = HIDDEN.remove(view)
                if (original != null && view.visibility == View.GONE) view.visibility = original
            }
        }
        if (show && state.suppressed) {
            try {
                state.last?.let { H.setField(base, "lastSeekChipRowHeight", it) }
                state.height?.let { height ->
                    if (H.field(base, "seekChipRowHeight") != height) {
                        restoring.set(true)
                        try { base.javaClass.getMethod("setSeekChipRowHeight", Int::class.javaPrimitiveType).invoke(base, height) }
                        finally { restoring.remove() }
                    }
                }
                state.suppressed = false
                state.height = null
                state.last = null
            } catch (ignored: Throwable) {
                // Keep the snapshot for the next native/config refresh if restoration failed.
            }
        }
    }

    @JvmStatic
    fun refresh() {
        val bases: Array<Any>
        synchronized(BASES) {
            bases = BASES.keys.toTypedArray()
        }
        for (base in bases) apply(base)
    }
}
