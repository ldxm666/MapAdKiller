package io.github.ldxm666.tmapclean

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.WeakHashMap

/** MapBaseView -> TipBannerLazyViewImpl -> TipBannerView.updateBanner(ClientBannerInfo).
 * The same container also renders kfx billboards; only replace the marketing argument.
 */
object TmapHomeBanner {
    private class State(var data: Any?, var allowed: Boolean)
    private val views = WeakHashMap<Any, State>()
    private val replay = ThreadLocal<Boolean>()
    private var update: Method? = null

    @JvmStatic fun install(cl: ClassLoader) {
        val view = H.cls(cl, "com.tencent.map.operation.view.TipBannerView")
        val banner = H.cls(cl, "com.tencent.map.op.net.ClientBannerInfo")
        if (view == null || banner == null) { H.log("home_banner missing_class"); return }
        val method = H.method(view, "a", banner)
        val resume = H.method(view, "e")
        if (method == null || method.returnType != Void.TYPE || resume?.returnType != Void.TYPE) {
            H.log("home_banner missing_native_contract"); return
        }
        method.isAccessible = true
        update = method
        H.hook(method, "tmap_home_promo_update", bannerHook())
        H.hook(resume, "tmap_home_promo_resume", resumeHook())
        H.log("home_banner installed marketing_only=true")
    }

    @JvmStatic fun bannerHook(): XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val self = chain.getThisObject() ?: return chain.proceed()
            val data = chain.getArg(0)
            val allowed = Cfg.visible(Spec.K_HOME_BANNER)
            synchronized(views) {
                val state = views[self]
                if (state == null) views[self] = State(data, allowed)
                else {
                    if (replay.get() != true) state.data = data
                    state.allowed = allowed
                }
            }
            if (allowed || data == null) return chain.proceed()
            // Native null branch cancels animations/click masks and retains a kfx billboard.
            H.log("home_banner blocked")
            return chain.proceed(arrayOfNulls<Any>(1))
        }
    }

    @JvmStatic fun resumeHook(): XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? =
                if (Cfg.visible(Spec.K_HOME_BANNER)) chain.proceed() else null
    }

    /** MainHook dispatches preference updates on main; unchanged data is never reloaded. */
    @JvmStatic fun refresh() {
        val method = update ?: return
        val allowed = Cfg.visible(Spec.K_HOME_BANNER)
        val pending = synchronized(views) {
            views.entries.filter { it.value.allowed != allowed }.map { it.key to it.value }
        }
        for ((view, state) in pending) {
            val previous = replay.get()
            replay.set(true)
            try {
                method.invoke(view, if (allowed) state.data else null)
                state.allowed = allowed
            } catch (error: Throwable) {
                H.log("home_banner refresh_failed=" + error.javaClass.simpleName)
            } finally {
                if (previous == null) replay.remove() else replay.set(previous)
            }
        }
    }
}
