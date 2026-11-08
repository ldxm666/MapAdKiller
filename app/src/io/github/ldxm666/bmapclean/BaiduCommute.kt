package io.github.ldxm666.bmapclean

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import io.github.libxposed.api.XposedInterface

/** Reversible visibility for the native commute card. Never replaces address click listeners. */
class BaiduCommute private constructor() {
    companion object {
        private var component = WeakReference<Any>(null)
        private var settingWidth = 0
        private var widthField: java.lang.reflect.Field? = null
        private var slideMethod: java.lang.reflect.Method? = null
        private val localBinding = ThreadLocal<Boolean>()

        @JvmStatic
        fun install(cl: ClassLoader) {
            val type = H.cls(cl, "com.baidu.baidumaps.duhelper.commute.CAAHCUIComponent") ?: return
            val source = H.cls(cl, "com.baidu.baidumaps.duhelper.model.d") ?: return
            val update = H.method(type, "selfUpdate", 0) ?: return
            val models = H.method(source, "g", 0) ?: return
            val slide = H.method(type, "onSlide", 1) ?: return
            // Newer Baidu versions choose recent-address cards when the cloud returns
            // several models. Select its existing local Home/Company branch for this
            // render only; never modify the shared model store or synthesize addresses.
            H.hook(models, "baidu_commute_local_source", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (localBinding.get() != true) return chain.proceed()
                    localBinding.remove()
                    return emptyList<Any>()
                }
            })
            H.hook(update, "baidu_commute_native_bind", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val previous = localBinding.get()
                    localBinding.set(Cfg.visible(Spec.K_HC) &&
                        (Cfg.visible(Spec.K_HC_HOME) || Cfg.visible(Spec.K_HC_COMPANY)))
                    try {
                        val result = chain.proceed()
                        apply(chain.getThisObject())
                        return result
                    } finally {
                        if (previous == null) localBinding.remove() else localBinding.set(previous)
                    }
                }
            })
            H.hook(slide, "baidu_commute_native_motion", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return if (BaiduPanelHeight.pinned()) chain.proceed(arrayOf<Any?>(1f))
                        else chain.proceed()
                }
            })
        }

        @JvmStatic
        fun apply(self: Any?) {
            if (self == null) return
            val binding = BaiduHooks.fieldOrNull(self, "mBinding") ?: return
            if (component.get() !== self) {
                component = WeakReference(self)
                settingWidth = 0
                widthField = try {
                    self.javaClass.getDeclaredField("settingContainerWidth").also { it.isAccessible = true }
                } catch (ignored: Throwable) { null }
                slideMethod = try {
                    self.javaClass.getMethod("onSlide", Float::class.java)
                } catch (ignored: Throwable) { null }
            }
            val master = Cfg.visible(Spec.K_HC)
            val home = Cfg.visible(Spec.K_HC_HOME)
            val company = Cfg.visible(Spec.K_HC_COMPANY)
            val setting = Cfg.visible(Spec.K_HC_SETTING)
            val root = BaiduHooks.invoke(binding, "getRoot") as? View ?: return
            show(root, master && (home || company || setting))
            // The wrapper may have been hidden on an earlier scan; restore it as well.
            val wrap = if (root.parent != null)
                Anchors.find(root.rootView, Anchors.ID_HC_WRAP, Anchors.NUM_HC_WRAP) else null
            show(wrap, master && (home || company || setting))
            show(BaiduHooks.fieldOrNull(self, "homeInfo") as? View, home)
            show(BaiduHooks.fieldOrNull(self, "companyInfo") as? View, company)
            show(BaiduHooks.fieldOrNull(self, "verticalLine") as? View, home && company)
            show(BaiduHooks.fieldOrNull(binding, "settingContainer") as? View, setting)
            // Address management alone must not accidentally expose recent addresses.
            if (!home && !company) {
                show(BaiduHooks.fieldOrNull(binding, "commonAddressRecycler") as? View, false)
                show(BaiduHooks.fieldOrNull(self, "viewLocal") as? View, false)
            }
            // Older filtering zeroed the native width permanently. Save and restore the
            // actual value produced by initBg(), including theme / font / density changes.
            val width = widthField ?: return
            val nativeWidth = width.getInt(self)
            if (nativeWidth > 0) settingWidth = nativeWidth
            val wanted = if (setting) settingWidth else 0
            if (nativeWidth != wanted) width.setInt(self, wanted)
            val content = BaiduHooks.fieldOrNull(binding, "containerBesidesTitle") as? View
            val params = content?.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && (!setting || BaiduPanelHeight.pinned()) && params.rightMargin != wanted) {
                params.rightMargin = wanted
                content.layoutParams = params
            }
        }

        @JvmStatic
        fun refresh() {
            val self = component.get() ?: return
            val action = Runnable {
                try {
                    // Reuse native resource sizing and native local/cloud address binding.
                    self.javaClass.getDeclaredMethod("initBg").also { it.isAccessible = true }.invoke(self)
                    self.javaClass.getDeclaredMethod("selfUpdate").also { it.isAccessible = true }.invoke(self)
                    apply(self)
                    syncMotion()
                } catch (t: Throwable) { H.log("commute refresh: " + t.javaClass.simpleName) }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run()
            else Handler(Looper.getMainLooper()).post(action)
        }

        @JvmStatic
        fun applyCurrent() { component.get()?.let { apply(it) } }

        @JvmStatic
        fun syncMotion() {
            if (!BaiduPanelHeight.pinned()) return
            val self = component.get() ?: return
            try { slideMethod?.invoke(self, 1f) } catch (ignored: Throwable) { }
        }

        private fun show(view: View?, visible: Boolean) {
            val target = if (visible) View.VISIBLE else View.GONE
            if (view != null && view.visibility != target) view.visibility = target
        }
    }
}
