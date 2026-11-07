package io.github.ldxm666.tmapclean

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import io.github.libxposed.api.XposedInterface

/** Home seek-chip row: MapBaseView owns its native host and row geometry. */
object TmapSeekChips {
    private val HIDDEN = WeakHashMap<View, Boolean>()
    private val BASES = WeakHashMap<Any, Boolean>()

    @JvmStatic
    fun install(cl: ClassLoader) {
        val base = H.cls(cl, "com.tencent.map.api.view.MapBaseView")
        H.hookSig(base, "setSeekChipRowHeight", "tmap_seek_row",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val hide = !Cfg.visible(Spec.K_SEEK_CHIPS)
                    val result = if (hide) chain.proceed(arrayOf<Any?>(Integer.valueOf(0)))
                    else chain.proceed()
                    apply(chain.getThisObject())
                    return result
                }
            }, java.lang.Integer.TYPE)
    }

    private fun apply(base: Any?) {
        if (base == null) return
        synchronized(BASES) {
            BASES.put(base, java.lang.Boolean.TRUE)
        }
        if (java.lang.Boolean.FALSE == H.call(base, "isHomePage")) return
        val parent = H.call(base, "getSeekParentView")
        if (parent !is ViewGroup) return
        val view = parent
        val show = Cfg.visible(Spec.K_SEEK_CHIPS)
        if (!show) {
            // The host uses this field for the floating row's travel and spacing.
            H.setField(base, "seekChipRowHeight", Integer.valueOf(0))
            H.setField(base, "lastSeekChipRowHeight", Integer.valueOf(0))
        }
        synchronized(HIDDEN) {
            if (!show) {
                if (view.visibility != View.GONE) {
                    HIDDEN.put(view, java.lang.Boolean.TRUE)
                    view.visibility = View.GONE
                }
            } else if (HIDDEN.remove(view) != null && view.visibility == View.GONE) {
                view.visibility = View.VISIBLE
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
