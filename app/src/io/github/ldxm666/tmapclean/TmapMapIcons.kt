package io.github.ldxm666.tmapclean

import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Home-only native controls. Preserve each original visibility and its native click listener. */
object TmapMapIcons {
    private const val BASE = "com.tencent.map.api.view.MapBaseView"
    private const val TOOLBAR = "com.tencent.map.api.view.ToolBar"
    private val getters = arrayOf("getToolBar", "getSceneSwitch", "getCompass", "getZoomView",
        "getLocateBtn", "getScreenshotView", "getShare", "getShareView", "getDayNightView",
        "getCopyrightView", "getScale", "getScaleView", "getRouteView")
    private val bases = WeakHashMap<Any, List<WeakReference<View>>>()
    private val hidden = WeakHashMap<View, Int>()

    @JvmStatic fun install(cl: ClassLoader) {
        val after = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                chain.getThisObject()?.let { applyBase(it) }
                return result
            }
        }
        val base = H.cls(cl, BASE)
        H.hookAll(base, "showViews", "tmap_mapicons_show", after)
        H.hookAll(base, "setToolBarElementVisible", "tmap_mapicons_layout", after)
        H.hookSig(H.cls(cl, TOOLBAR), "setVisible", "tmap_mapicons_bar",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val target = chain.getArg(0) as? View ?: return chain.proceed()
                    val requested = chain.getArg(1) as? Int ?: return chain.proceed()
                    val baseView = baseOf(chain.getThisObject() as? View) ?: return chain.proceed()
                    if (H.call(baseView, "isHomePage") != true || Cfg.visible(Spec.K_MAP_ICONS))
                        return chain.proceed()
                    hidden[target] = requested
                    return chain.proceed(arrayOf<Any?>(target, View.GONE))
                }
            }, View::class.java, Integer.TYPE)
    }

    private fun baseOf(start: View?): View? {
        var node = start
        var depth = 0
        while (node != null && depth++ < 16) {
            var cls: Class<*>? = node.javaClass
            while (cls != null) {
                if (cls.name == BASE) return node
                cls = cls.superclass
            }
            node = node.parent as? View
        }
        return null
    }

    private fun applyBase(base: Any) {
        // Native controls arrive in stages. A nonempty early cache is not proof that
        // zoom/compass/location have finished creating. Merge on each native update
        // and config refresh so late controls obey the same switch without a restart.
        val unique = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        bases[base]?.forEach { it.get()?.let { view -> unique.add(view) } }
        for (getter in getters) (H.call(base, getter) as? View)?.let { unique.add(it) }
        val views = unique.map { WeakReference(it) }
        bases[base] = views
        val hide = H.call(base, "isHomePage") == true && !Cfg.visible(Spec.K_MAP_ICONS)
        for (ref in views) {
            val view = ref.get() ?: continue
            if (hide) {
                if (view.visibility != View.GONE) {
                    if (!hidden.containsKey(view)) hidden[view] = view.visibility
                    view.visibility = View.GONE
                }
            } else restore(view)
        }
    }

    private fun restore(view: View) {
        val original = hidden.remove(view) ?: return
        if (view.visibility == View.GONE) view.visibility = original
    }

    @JvmStatic fun refresh() {
        if (Cfg.visible(Spec.K_MAP_ICONS)) for (view in ArrayList(hidden.keys)) restore(view)
        for (base in ArrayList(bases.keys)) applyBase(base)
    }
}
