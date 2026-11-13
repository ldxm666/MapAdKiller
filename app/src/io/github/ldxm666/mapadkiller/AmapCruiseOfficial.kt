package io.github.ldxm666.mapadkiller

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.util.WeakHashMap
import io.github.libxposed.api.XposedInterface

/** Native automatic radar only. Manual radar entry and route navigation are untouched. */
object AmapCruiseOfficial {
    private const val CLS_MODULE = "com.amap.bundle.drive.ajx.module.ModuleRouteDriveResultImpl"
    private const val CLS_PAGE = "com.amap.bundle.drive.result.driveresult.opt.page.AjxBaseResultPage"
    private class Target(val setter: Method, val requested: Boolean)
    private val targets = WeakHashMap<Any, Target>()
    private val updating = ThreadLocal<Boolean>()
    private val originalRequest = ThreadLocal<Boolean>()

    @JvmStatic fun install(cl: ClassLoader) {
        var hooks = 0
        for (name in arrayOf(CLS_MODULE, CLS_PAGE)) {
            val cls = H.cls(cl, name)
            val method = H.method(cls, "setAutoEnterDriveRadar", java.lang.Boolean.TYPE)
            if (method == null || method.returnType != Void.TYPE) {
                H.log("event=cruise_official_miss class=$name reason=signature")
                continue
            }
            val before = H.ok.get()
            H.hookSig(cls, "setAutoEnterDriveRadar", "amap_cruise_official_${cls?.simpleName}", FORCE_TRUE, Boolean::class.javaPrimitiveType)
            hooks += H.ok.get() - before
        }
        // This listener belongs exclusively to SpeedDetectManager, not general GPS/navigation.
        val listener = H.cls(cl, "com.amap.bundle.drive.radar.entrance.SpeedDetectManager\$a")
        if (listener != null && android.location.LocationListener::class.java.isAssignableFrom(listener)) {
            H.hookSig(listener, "onLocationChanged", "amap_cruise_auto_speed_guard", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? =
                        if (Config.cruiseForceOff()) null else chain.proceed()
            }, android.location.Location::class.java)
        }
        H.log("event=cruise_official_install hooks=$hooks")
    }

    @JvmStatic fun onConfigChanged(key: String?) {
        if (key != null && key != Config.K_CRUISE_FORCE_OFF && key != Config.K_CRUISE_OFFICIAL) return
        if (synchronized(targets) { targets.isEmpty() }) return
        val main = Looper.getMainLooper() ?: return
        val action = Runnable {
            val snapshot = synchronized(targets) { targets.entries.map { Pair(it.key, it.value) } }
            updating.set(true)
            try {
                for ((instance, target) in snapshot) try {
                    val enabled = !Config.cruiseForceOff() && (Config.cruiseOfficial() || target.requested)
                    target.setter.isAccessible = true
                    // Native false unregisters SpeedDetectManager and resets its accumulated timer.
                    target.setter.invoke(instance, enabled)
                } catch (error: Throwable) {
                    H.log("event=cruise_official_refresh_failed err=${error.javaClass.simpleName}")
                }
            } finally { updating.remove() }
        }
        if (Looper.myLooper() == main) action.run() else Handler(main).post(action)
    }

    private val FORCE_TRUE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val list = chain.getArgs()
            if (list.size != 1 || list[0] !is Boolean) return chain.proceed()
            val incoming = list[0] as Boolean
            // ModuleRouteDriveResultImpl delegates to AjxBaseResultPage. The page must
            // remember the host's request, not the true/false forced by the outer hook.
            val previous = originalRequest.get()
            val requested = previous ?: incoming
            val instance = chain.getThisObject()
            val setter = chain.executable as? Method
            if (instance != null && setter != null && updating.get() != true) synchronized(targets) {
                targets[instance] = Target(setter, requested)
            }
            val enabled = !Config.cruiseForceOff() && (Config.cruiseOfficial() || requested)
            originalRequest.set(requested)
            try {
                return if (enabled != incoming) chain.proceed(arrayOf<Any?>(enabled)) else chain.proceed()
            } finally {
                if (previous == null) originalRequest.remove() else originalRequest.set(previous)
            }
        }
    }
}
