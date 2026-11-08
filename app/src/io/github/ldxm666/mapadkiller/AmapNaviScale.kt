package io.github.ldxm666.mapadkiller

import android.os.Handler
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 17.02 TripNaviHeaderContainer: uniform, reversible enlargement for landscape car screens. */
object AmapNaviScale {
    private const val HEADER_ID = "idTripNaviHeaderContainer"
    private val hosts = WeakHashMap<View, Host>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @JvmStatic
    fun install(cl: ClassLoader) {
        val page = H.cls(cl, "com.amap.bundle.drive.common.basepage.AjxRouteCarNaviBasePage") ?: return
        val context = H.cls(cl, "com.autonavi.minimap.ajx3.context.IAjxContext") ?: return
        val ajxPage = H.cls(cl, "com.autonavi.minimap.ajx3.Ajx3Page") ?: return
        val rootField = try { ajxPage.getDeclaredField("j").apply { isAccessible = true } }
            catch (_: Exception) { return }
        // Both real and simulated navigation call this base implementation. No global AJX layout hook.
        H.hookSig(page, "onAjxContxtCreated", "amap_navi_scale_document", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                try { track(rootField.get(chain.getThisObject()) as? View ?: return result, chain.getArg(0) ?: return result) }
                catch (error: Exception) { H.logv("event=navi_scale_error stage=attach type=${error.javaClass.simpleName}") }
                return result
            }
        }, context)
        H.hookSig(page, "resume", "amap_navi_scale_resume", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                refresh()
                return result
            }
        })
    }

    @JvmStatic
    fun clampPercent(value: Int): Int = value.coerceIn(100, 200)

    /** Fit the whole icon/text card into the current viewport; portrait preserves the native layout. */
    @JvmStatic
    fun fitScale(percent: Int, viewportWidth: Int, viewportHeight: Int,
                 left: Int, top: Int, width: Int, height: Int): Float {
        if (viewportWidth <= viewportHeight || viewportHeight <= 0 || width <= 0 || height <= 0 || left < 0 || top < 0) return 1f
        val requested = clampPercent(percent) / 100f
        val horizontal = (viewportWidth - left - left.coerceAtLeast(0)).toFloat() / width
        val vertical = (viewportHeight - top).toFloat() / height
        return minOf(requested, horizontal, vertical).coerceAtLeast(1f)
    }

    private fun track(root: View, context: Any) {
        val known = hosts[root]
        if (known != null) { known.queue(); return }
        // The verified CarNaviBasePage callback owns this context. Bundle entry paths
        // can differ after cloud updates; the exact header ID is the second scope check.
        val tree = context.javaClass.getMethod("getDomTree").invoke(context) ?: return
        val host = Host(root, tree)
        hosts[root] = host
        root.viewTreeObserver.addOnGlobalLayoutListener(host)
        root.addOnAttachStateChangeListener(host)
        H.logv("event=navi_scale_document supported=true")
        host.queue()
    }

    @JvmStatic
    fun refresh() {
        if (hosts.isEmpty() || Looper.getMainLooper() == null) return
        main.post {
            for (host in hosts.values.toList()) host.queue()
        }
    }

    private class Original(view: View) {
        val view = WeakReference(view)
        val x = view.scaleX
        val y = view.scaleY
        val pivotX = view.pivotX
        val pivotY = view.pivotY
        val explicitPivot = Build.VERSION.SDK_INT < 28 || view.isPivotSet
        private var applied = false
        fun apply(factor: Float) {
            view.get()?.let {
                it.pivotX = 0f; it.pivotY = 0f
                it.scaleX = x * factor; it.scaleY = y * factor
                applied = true
            }
        }
        fun restore() {
            if (!applied) return
            view.get()?.let {
                it.scaleX = x; it.scaleY = y
                if (Build.VERSION.SDK_INT >= 28 && !explicitPivot) it.resetPivot()
                else { it.pivotX = pivotX; it.pivotY = pivotY }
            }
            applied = false
        }
    }

    private class Clip(val children: Boolean, val padding: Boolean)

    private class Host(root: View, tree: Any) : ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private val root = WeakReference(root)
        private val tree = WeakReference(tree)
        private val lookup = tree.javaClass.getMethod("g", String::class.java, String::class.java)
        private var original: Original? = null
        private val clips = WeakHashMap<ViewGroup, Clip>()
        private var pending = false
        private var lastReport = ""

        private fun find(attribute: String, value: String): View? {
            return lookup.invoke(tree.get() ?: return null, attribute, value) as? View
        }

        fun queue() {
            val view = root.get() ?: return
            if (pending) return
            pending = true
            view.post {
                pending = false
                try { apply() }
                catch (error: Exception) {
                    restore()
                    H.logv("event=navi_scale_error stage=apply type=${error.javaClass.simpleName}")
                }
            }
        }

        private fun apply() {
            val root = root.get() ?: return
            var bar = original?.view?.get()
            if (bar == null || !bar.isAttachedToWindow) {
                restore()
                bar = find("id", HEADER_ID) ?: return
                if (bar.width <= 0 || bar.height <= 0) return
                original = Original(bar)
            }
            val location = IntArray(2); val origin = IntArray(2)
            bar.getLocationInWindow(location); root.getLocationInWindow(origin)
            val percent = Config.naviInfoPercent()
            var factor = fitScale(percent, root.width, root.height,
                location[0] - origin[0], location[1] - origin[1], bar.width, bar.height)
            // Native road-junction graphics are rendered on a separate map surface.
            // Keep their own header geometry while visible instead of covering the image.
            val cross = find("class", "trip-navi-header-cross")?.isShown == true ||
                find("class", "trip_navi_pro_header_endcross")?.isShown == true
            if (cross) factor = 1f
            if (factor > 1f) {
                val left = location[0] - origin[0]
                val top = location[1] - origin[1]
                for (id in arrayOf("carNaviFooterTipsWrap", "idCarNaviBottomNormalContainer", "idNaviEndPoiCrossView")) {
                    val obstacle = find("id", id) ?: continue
                    if (!obstacle.isShown || obstacle.width <= 0 || obstacle.height <= 0) continue
                    val point = IntArray(2); obstacle.getLocationInWindow(point)
                    val x = point[0] - origin[0]; val y = point[1] - origin[1]
                    if (y >= top + bar.height && x < left + bar.width * factor && x + obstacle.width > left)
                        factor = minOf(factor, ((y - top).toFloat() / bar.height).coerceAtLeast(1f))
                }
            }
            val saved = original ?: return
            if (factor <= 1f) {
                restoreTransform()
            } else {
                // Only ancestors of this exact card may stop clipping its enlarged contents.
                var parent = bar.parent
                while (parent is ViewGroup) {
                    if (!clips.containsKey(parent)) clips[parent] = Clip(parent.clipChildren, parent.clipToPadding)
                    parent.clipChildren = false; parent.clipToPadding = false
                    if (parent === root) break
                    parent = parent.parent
                }
                saved.apply(factor)
            }
            val report = "$percent/$factor/${root.width}x${root.height}/${bar.width}x${bar.height}"
            if (lastReport != report) {
                lastReport = report
                H.logv("event=navi_scale requested=$percent effective=$factor viewport=${root.width}x${root.height} card=${bar.width}x${bar.height}")
            }
        }

        private fun restoreTransform() {
            original?.restore()
            for ((parent, state) in clips.toMap()) {
                parent.clipChildren = state.children; parent.clipToPadding = state.padding
            }
            clips.clear()
        }

        private fun restore() { restoreTransform(); original = null }
        override fun onGlobalLayout() { queue() }
        override fun onViewAttachedToWindow(v: View) {
            hosts[v] = this
            if (v.viewTreeObserver.isAlive) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                v.viewTreeObserver.addOnGlobalLayoutListener(this)
            }
            queue()
        }
        override fun onViewDetachedFromWindow(v: View) {
            restore()
            if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnGlobalLayoutListener(this)
            hosts.remove(v)
        }
    }
}
