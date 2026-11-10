package io.github.ldxm666.tmapclean

import android.view.View
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/** Native feature masks. A visible switch permits Tencent's requested value, never forces it on. */
object TmapPoi {
    private val methods = arrayOf("setPOIRichEnable", "setAppPoiRichEnabled",
        "setBuildingPoiRichEnable", "set3DPOIEnable", "setLandmarkEnable")
    @JvmField val keys = arrayOf(Spec.K_POI_RICH, Spec.K_POI_APP_RICH,
        Spec.K_POI_BUILDING, Spec.K_POI_3D, Spec.K_POI_LANDMARK)
    private class State {
        val requested = arrayOfNulls<Boolean>(5)
        val hidden = BooleanArray(5)
    }
    private val maps = WeakHashMap<Any, State>()
    private val controllers = WeakHashMap<Any, State>()
    private val featureNames = arrayOf("PoiRich", "AppPoiRich", "BuildingRich", "", "LandMark")
    private var featureClass: Class<*>? = null
    private val features = arrayOfNulls<Any>(5)
    private val applying = ThreadLocal<Boolean>()
    private class Request(val index: Int, val value: Boolean)
    private val forwarding = ThreadLocal<Request>()
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic fun install(cl: ClassLoader) {
        TmapAnnotations.install(cl)
        TmapPoiCategories.install(cl)
        val cls = H.cls(cl, "com.tencent.map.lib.TencentMap") ?: return
        for (i in methods.indices) {
            H.hookSig(cls, methods[i], "tmap_poi_$i", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val map = chain.getThisObject() ?: return chain.proceed()
                    if (applying.get() == true) return chain.proceed()
                    val argument = chain.getArg(0) as? Boolean ?: return chain.proceed()
                    val requested = forwarding.get()?.takeIf { it.index == i }?.value ?: argument
                    val s = synchronized(maps) { maps.getOrPut(map) { State() } }
                    s.requested[i] = requested
                    val permitted = Cfg.visible(keys[i], true)
                    s.hidden[i] = !permitted
                    return forward(i, requested) {
                        if (permitted) chain.proceed()
                        else chain.proceed(arrayOf<Any?>(false))
                    }
                }
            }, java.lang.Boolean.TYPE)
        }
        installSdk(cl)
        val base = H.cls(cl, "com.tencent.map.api.view.MapBaseView")
        H.hookSig(base, "showViews", "tmap_poi_ready", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val view = chain.getThisObject() as? View ?: return result
                view.post {
                    val map = H.field(H.call(view, "getSceneSwitch"), "map")
                    if (map != null) apply(map)
                }
                return result
            }
        })
    }

    private fun forward(index: Int, requested: Boolean, action: () -> Any?): Any? {
        val old = forwarding.get()
        forwarding.set(Request(index, requested))
        return try { action() } finally {
            if (old == null) forwarding.remove() else forwarding.set(old)
        }
    }

    /** 11.7.0's real MapStatusController, verified against the original APK with DexSQL.
     * Native scene/style code can call this directly without TencentMap's facade setters.
     */
    private fun installSdk(cl: ClassLoader) {
        val type = H.cls(cl, "com.tencent.mapsdk2.api.models.enums.MapEnum\$MapFeature") ?: return
        featureClass = type
        for (i in features.indices) {
            features[i] = try { type.getField(featureNames[i]).get(null) } catch (_: Throwable) { null }
        }
        val controller = H.cls(cl, "com.tencent.mapsdk2.internal.controllers.f") ?: return
        H.hookSig(controller, "setFeatureEnable", "tmap_poi_sdk_feature",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (applying.get() == true) return chain.proceed()
                    val feature = chain.getArg(0) as? Enum<*> ?: return chain.proceed()
                    val i = featureNames.indexOf(feature.name)
                    if (i < 0) return chain.proceed()
                    val argument = chain.getArg(1) as? Boolean ?: return chain.proceed()
                    val target = chain.getThisObject() ?: return chain.proceed()
                    return sdkRequest(chain, target, i, argument, 1)
                }
            }, type, java.lang.Boolean.TYPE)
        for (i in 3..4) H.hookSig(controller, methods[i], "tmap_poi_sdk_$i",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (applying.get() == true) return chain.proceed()
                    val target = chain.getThisObject() ?: return chain.proceed()
                    val argument = chain.getArg(0) as? Boolean ?: return chain.proceed()
                    return sdkRequest(chain, target, i, argument, 0)
                }
            }, java.lang.Boolean.TYPE)
        H.hookSig(H.cls(cl, "com.tencent.mapsdk2.api.TencentMap"), "getMapStatusController",
            "tmap_poi_sdk_ready", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val target = chain.proceed() ?: return null
                    val fresh = synchronized(controllers) {
                        if (controllers.containsKey(target)) false
                        else { controllers[target] = State(); true }
                    }
                    if (fresh) main.post { applyController(target) }
                    return target
                }
            })
    }

    private fun sdkRequest(chain: XposedInterface.Chain, target: Any, index: Int,
                           argument: Boolean, position: Int): Any? {
        val requested = forwarding.get()?.takeIf { it.index == index }?.value ?: argument
        val state = synchronized(controllers) { controllers.getOrPut(target) { State() } }
        state.requested[index] = requested
        val permitted = Cfg.visible(keys[index], true)
        state.hidden[index] = !permitted
        if (Cfg.debug()) Cfg.log("TMPoi native key=${keys[index]} requested=$requested applied=${permitted && argument}")
        return forward(index, requested) {
            if (permitted || !argument) chain.proceed()
            else {
                val args = if (position == 0) arrayOf<Any?>(false)
                    else arrayOf(chain.getArg(0), false)
                chain.proceed(args)
            }
        }
    }

    private fun applyController(target: Any) {
        val type = featureClass ?: return
        val state = synchronized(controllers) { controllers.getOrPut(target) { State() } }
        val setter = H.method(target.javaClass, "setFeatureEnable", type, java.lang.Boolean.TYPE) ?: return
        val getter = H.method(target.javaClass, "getFeatureEnable", type)
        applying.set(true)
        try {
            for (i in features.indices) {
                val feature = features[i]
                if (i < 3 && feature == null) continue
                val visible = Cfg.visible(keys[i], true)
                if (state.requested[i] == null && i < 3) {
                    // Read the real native value before masking; unknown values never force-enable.
                    state.requested[i] = try { getter?.invoke(target, feature) as? Boolean }
                        catch (_: Throwable) { null }
                }
                // Do not mask an unknown baseline: for 3D/landmark the SDK has no
                // matching getter. Wait for its real setter request, which sdkRequest
                // records and masks atomically. Otherwise re-enable cannot restore it.
                val original = state.requested[i] ?: continue
                val desired = if (visible) original else false
                if (desired == null || (visible && !state.hidden[i])) continue
                try {
                    // nativeSetPOI3DEnable is separate from MapAnnotationPoi3D. Do not use
                    // an unrelated feature flag as a substitute or claim it as read-back.
                    if (i < 3) setter.invoke(target, feature, desired)
                    else if (H.call1(target, methods[i], desired) == null) continue
                    state.hidden[i] = !visible
                    val actual = if (i < 3) getter?.invoke(target, feature) as? Boolean else null
                    Diag.note("poi_${keys[i]}", if (i >= 3) "native_applied=$desired visual_pending"
                        else if (actual == desired) "native_verified=$actual"
                        else "native_unconfirmed desired=$desired actual=$actual")
                    if (Cfg.debug()) Cfg.log("TMPoi verify key=${keys[i]} desired=$desired actual=$actual")
                } catch (t: Throwable) {
                    Diag.note("poi_${keys[i]}", "apply_failed=${t.javaClass.simpleName}")
                }
            }
        } finally { applying.remove() }
    }

    private fun apply(map: Any) {
        val s = synchronized(maps) { maps.getOrPut(map) { State() } }
        applying.set(true)
        try {
            for (i in methods.indices) {
                val visible = Cfg.visible(keys[i], true)
                // No native setter observed yet: do not invent a restore value.
                // Crucial: applying=true bypasses nested SDK hooks. Masking a facade
                // with no observed request would overwrite the SDK before its getter
                // captured the original, permanently poisoning the restore value.
                val original = s.requested[i] ?: continue
                val desired = if (!visible) false else original
                if (!visible || s.hidden[i]) {
                    if (H.call1(map, methods[i], desired) != null) s.hidden[i] = !visible
                }
            }
        } finally { applying.remove() }
    }

    @JvmStatic fun refresh() {
        TmapAnnotations.refresh()
        TmapPoiCategories.refresh()
        val snapshot = synchronized(maps) { ArrayList(maps.keys) }
        for (map in snapshot) apply(map)
        val sdk = synchronized(controllers) { ArrayList(controllers.keys) }
        for (controller in sdk) applyController(controller)
    }
}
