package io.github.ldxm666.tmapclean

import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Tencent 11.7.0 base annotations are one native layer: POI AND road names.
 * Never substitute rich bubbles, sub-content, or a 3D feature for this layer.
 * Apply only on the home map; search and navigation retain their native policy.
 */
object TmapAnnotations {
    private const val ANNOTATION = 8 // MapContentType.MapContent_Annotation
    private class State {
        // The SDK creates its base annotations visible. Observe every subsequent native
        // content request, including ALL masks, before applying our home-only mask.
        var requested = true
        var hidden = false
        var home = false
    }
    private val renderers = WeakHashMap<Any, State>()
    private val bases = WeakHashMap<Any, WeakReference<Any>>()
    private val generations = WeakHashMap<Any, Int>()
    private val applying = ThreadLocal<Boolean>()

    @JvmStatic fun install(cl: ClassLoader) {
        H.hookSig(H.cls(cl, "com.tencent.mapsdk2.internal.controllers.e"),
            "setContentVisible", "tmap_annotation_native", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (applying.get() == true) return chain.proceed()
                    val target = chain.getThisObject() ?: return chain.proceed()
                    val type = chain.getArg(0) as? Int ?: return chain.proceed()
                    val visible = chain.getArg(1) as? Boolean ?: return chain.proceed()
                    return request(chain, target, type, visible)
                }
            }, Integer.TYPE, java.lang.Boolean.TYPE)
        val base = H.cls(cl, "com.tencent.map.api.view.MapBaseView")
        val after = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                chain.getThisObject()?.let { observe(it) }
                return result
            }
        }
        H.hookSig(base, "showViews", "tmap_annotation_ready", after)
        H.hookSig(base, "setHomeView", "tmap_annotation_scene", after, java.lang.Boolean.TYPE)
        H.hookSig(base, "onAttachedToWindow", "tmap_annotation_attach", after)
        H.hookSig(base, "destroy", "tmap_annotation_destroy", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                chain.getThisObject()?.let { target ->
                    synchronized(bases) { bases.remove(target); generations.remove(target) }
                }
                return chain.proceed()
            }
        })
    }

    private fun state(target: Any): State = synchronized(renderers) {
        renderers.getOrPut(target) { State() }
    }

    private fun request(chain: XposedInterface.Chain, target: Any, type: Int, visible: Boolean): Any? {
        if (type and ANNOTATION == 0) return chain.proceed()
        val s = state(target)
        // Preserve the complete original call (possibly ALL). Mask only bit 8 afterward;
        // turning ALL false here would also remove the actual roads and terrain.
        val result = chain.proceed()
        synchronized(s) {
            // A failed original call must not replace the last successful native intent.
            s.requested = visible
            s.hidden = false // the original call has overwritten our previous mask
            if (s.home && !Cfg.visible(Spec.K_MAP_ANNOTATIONS, true) && visible) {
                s.hidden = write(target, false)
            }
        }
        return result
    }

    private fun observe(base: Any) {
        val generation = synchronized(bases) {
            if (!bases.containsKey(base)) bases[base] = WeakReference<Any>(null)
            ((generations[base] ?: 0) + 1).also { generations[base] = it }
        }
        bind(base)
        // Native engines may appear AFTER attach/showViews. Keep the base even when
        // binding fails, and retry a bounded number of times. No perpetual polling.
        val view = base as? View ?: return
        val weak = WeakReference(base)
        for (delay in longArrayOf(80L, 350L, 1200L)) view.postDelayed({
            val current = weak.get() ?: return@postDelayed
            val valid = synchronized(bases) { generations[current] == generation }
            if (valid) bind(current)
        }, delay)
    }

    private fun bind(base: Any) {
        val previous = synchronized(bases) {
            if (!bases.containsKey(base)) bases[base] = WeakReference<Any>(null)
            bases[base]?.get()
        }
        val home = H.call(base, "isHomePage") as? Boolean ?: return
        val map = H.field(H.call(base, "getSceneSwitch"), "map")
        val sdk = H.field(H.field(H.field(map, "mMapEngine"), "mJNIWrapper"), "mTXMap")
        val renderer = H.call(sdk, "getMapRenderController")
        if (renderer == null) {
            if (previous != null) update(previous, home)
            return
        }
        if (previous != null && previous !== renderer) update(previous, false)
        synchronized(bases) { bases[base] = WeakReference(renderer) }
        update(renderer, home)
    }

    private fun update(target: Any, home: Boolean) {
        val s = state(target)
        synchronized(s) {
            s.home = home
            val hide = home && !Cfg.visible(Spec.K_MAP_ANNOTATIONS, true)
            if (hide) {
                if (!s.hidden && s.requested && write(target, false)) s.hidden = true
            } else if (s.hidden && write(target, s.requested)) s.hidden = false
        }
    }

    private fun write(target: Any, visible: Boolean): Boolean {
        val setter = H.method(target.javaClass, "setContentVisible", Integer.TYPE, java.lang.Boolean.TYPE)
            ?: return false
        val previous = applying.get()
        applying.set(true)
        return try {
            setter.invoke(target, ANNOTATION, visible)
            Diag.note("base_annotations", "native_applied=$visible layer=8")
            if (Cfg.debug()) Cfg.log("TMAnnotations applied=$visible layer=8")
            true
        } catch (_: Throwable) { false }
        finally { if (previous == null) applying.remove() else applying.set(previous) }
    }

    @JvmStatic fun refresh() {
        // Re-resolve every time: a style/engine rebuild can replace the renderer
        // while the MapBaseView itself stays alive.
        val snapshot = synchronized(bases) { bases.keys.toList() }
        for (base in snapshot) observe(base)
    }
}
