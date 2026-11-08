package io.github.ldxm666.bmapclean

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference

/** Filter before Baidu divides its models into rows. All icons and routes remain native. */
class BaiduToolGrid private constructor() {
    companion object {
        private val rendering = ThreadLocal<Int>()
        private var component = WeakReference<Any>(null)
        private var updateView: java.lang.reflect.Method? = null
        private var onSlide: java.lang.reflect.Method? = null

        @JvmStatic
        fun install(cl: ClassLoader) {
            val type = H.cls(cl, "com.baidu.baidumaps.duhelper.commute.RoutePanelShortCutUIComponent") ?: return
            val row = H.cls(cl, "com.baidu.baidumaps.duhelper.view.ShortCutRow") ?: return
            // Resolve both capabilities first. Never enable half of the packing adapter.
            val update: java.lang.reflect.Method
            val bind: java.lang.reflect.Method
            try {
                update = type.getDeclaredMethod("updateRow", List::class.java, Boolean::class.java,
                    List::class.java, List::class.java).also { it.isAccessible = true }
                bind = row.getDeclaredMethod("update", List::class.java, IntArray::class.java,
                    Array<String>::class.java, Int::class.java).also { it.isAccessible = true }
                updateView = type.getDeclaredMethod("updateView").also { it.isAccessible = true }
                onSlide = type.getDeclaredMethod("onSlide", Float::class.java).also { it.isAccessible = true }
            } catch (t: Throwable) {
                H.log("native tools adapter unavailable: " + t.javaClass.simpleName)
                return
            }
            H.hook(update, "baidu_tools_native_pack", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val source = chain.getArg(0) as? List<*> ?: return chain.proceed()
                    val selected = source.filter { model ->
                        val unit = BaiduHooks.fieldOrNull(model, "d")
                        val label = BaiduHooks.fieldOrNull(BaiduHooks.fieldOrNull(unit, "b"), "a") as? String
                        BaiduHooks.toolVisible(label)
                    }
                    val self = chain.getThisObject()
                    val binding = BaiduHooks.fieldOrNull(self, "mBinding") ?: return chain.proceed()
                    val rows = arrayOf("row1Container", "row2Container", "row3Container").map {
                        BaiduHooks.fieldOrNull(binding, it) as? ViewGroup
                    }
                    if (rows.any { it == null }) return chain.proceed()
                    component = WeakReference(self)
                    val root = BaiduHooks.invoke(binding, "getRoot") as? View
                    if (selected.isEmpty()) {
                        for (nativeRow in rows) nativeRow!!.visibility = View.GONE
                        root?.visibility = View.GONE
                        return null
                    }
                    root?.visibility = View.VISIBLE
                    val args = chain.getArgs().toTypedArray()
                    args[0] = selected
                    val previous = rendering.get()
                    rendering.set(selected.size)
                    try {
                        val result = chain.proceed(args)
                        syncMotion()
                        return result
                    } finally {
                        if (previous == null) rendering.remove() else rendering.set(previous)
                    }
                }
            })
            H.hook(bind, "baidu_tools_native_bind", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val total = rendering.get() ?: return chain.proceed()
                    val index = chain.getArg(3) as? Int ?: return chain.proceed()
                    val source = chain.getArg(0) as? List<*> ?: return chain.proceed()
                    val count = minOf(5, maxOf(0, total - index * 5), source.size)
                    val args = chain.getArgs().toTypedArray()
                    // Baidu appends an overlapping sixth preview cell even for a short list.
                    // Keep real models only; do not move or resize a single native cell.
                    args[0] = source.take(count)
                    val result = chain.proceed(args)
                    val view = chain.getThisObject() as? ViewGroup ?: return result
                    if (view.childCount > 5) {
                        // Finish the native crossfade before removing its overlay slot.
                        try { view.javaClass.getMethod("onSlide", Float::class.java).invoke(view, 1f) }
                        catch (ignored: Throwable) { }
                    }
                    for (i in 0 until view.childCount) {
                        view.getChildAt(i).visibility = if (i < count) View.VISIBLE else View.GONE
                    }
                    view.visibility = if (count > 0) View.VISIBLE else View.GONE
                    return result
                }
            })
            H.hook(onSlide, "baidu_tools_native_motion", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return if (BaiduPanelHeight.pinned()) chain.proceed(arrayOf<Any?>(1f))
                        else chain.proceed()
                }
            })
        }

        @JvmStatic
        fun syncMotion() {
            if (!BaiduPanelHeight.pinned()) return
            val self = component.get() ?: return
            try { onSlide?.invoke(self, 1f) } catch (ignored: Throwable) { }
        }

        /** Reuse the native controller after an in-process tool switch changes. */
        @JvmStatic
        fun refresh() {
            val self = component.get() ?: return
            val method = updateView ?: return
            val action = Runnable {
                try { method.invoke(self) }
                catch (t: Throwable) { Cfg.log("native tools refresh: " + t.javaClass.simpleName) }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action.run()
            else android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }
}
