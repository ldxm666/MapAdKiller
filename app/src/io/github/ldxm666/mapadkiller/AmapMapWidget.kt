package io.github.ldxm666.mapadkiller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Map promotions are independent of the home content canvas. */
class AmapMapWidget {
    companion object {
        private class State(val method: Method, val args: Array<Any?>, var allowed: Boolean)
        private val widgets = WeakHashMap<Any, State>()
        private val replay = ThreadLocal<Any>()
        private val viewVisibility = View::class.java.getMethod("setVisibility", Integer.TYPE)

        @JvmStatic
        fun install(cl: ClassLoader) {
            // The current sweep-ranking circle is an AJX template, not an
            // OperateActivityMapWidget. Its initial bean list may still be empty.
            // Match the native business type, never labels, icon URLs or position.
            val template = H.cls(cl, "com.autonavi.bundle.uitemplate.mapwidget.MapWidgetContainer")
            val contract = H.cls(cl, "com.autonavi.bundle.uitemplate.mapwidget.inter.IMapWidget")
            val property = H.cls(cl, "com.autonavi.bundle.uitemplate.api.IWidgetProperty")
            val getProperty = H.method(contract, "getWidgetProperty")
            val getType = H.method(property, "getWidgetType")
            val getView = H.method(contract, "getContentView")
            if (contract != null && getProperty != null && getType != null && getView != null) {
                H.hookSig(template, "addWidget", "amap_map_promo_template_add",
                        templateAddHook(getProperty, getType, getView), contract, Integer.TYPE)
                H.hookSig(template, "setWidgetVisible", "amap_map_promo_template_visibility",
                        templateVisibilityHook(getProperty, getType, getView), contract, Integer.TYPE)
                // This small setter can be inlined into the native manager.
                // Deoptimize its exact callers so later show/hide requests pass
                // through the same gate as the first attachment.
                for (name in arrayOf("MapWidgetManagerImpl", "MapWidgetManagerService")) {
                    val manager = H.cls(cl, "com.autonavi.bundle.uitemplate.mapwidget.impl." + name)
                    val method = H.method(manager, "setWidgetVisibleForType", String::class.java, Integer.TYPE) ?: continue
                    try { H.module?.deoptimize(method) }
                    catch (error: Throwable) { H.log("event=map_promo_deopt_failed err=" + error.javaClass.simpleName) }
                }
            }
            // AJX bypasses the presenter's private setter and calls these widget setters.
            // Install even while enabled so a later setting change remains effective.
            val widget = H.cls(cl, "com.autonavi.bundle.uitemplate.mapwidget.widget.activity.OperateActivityMapWidget")
            if (widget != null) {
                for ((name, params) in listOf(
                        "setVisibility" to arrayOf<Class<*>>(Integer.TYPE),
                        "setVisibility" to arrayOf<Class<*>>(Integer.TYPE, java.lang.Boolean.TYPE),
                        "setContentViewVisibility" to arrayOf<Class<*>>(Integer.TYPE))) {
                    val method = H.method(widget, name, *params) ?: continue
                    if (method.returnType != Void.TYPE) continue
                    method.isAccessible = true
                    H.hookSig(widget, name, "amap_map_promo_widget_" + name + params.size,
                            visibilityHook(method), *params)
                }
            }
            val presenter = H.cls(cl, "com.autonavi.bundle.uitemplate.mapwidget.widget.activity.OperateActivityWidgetPresenter")
            val message = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.entity.AmapMessage")
            val type = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.entity.ActivityMsgType")
            val callback = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.listener.ActivityEventCallback")
            if (message == null || type == null || callback == null) return
            H.hookSig(presenter, "showActivity", "amap_map_promo_start", startHook(),
                    message, java.util.HashMap::class.java, String::class.java, type, callback, Bundle::class.java)
        }

        @JvmStatic fun startHook(): XposedInterface.Hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? =
                    if (Config.visible(Config.K_MAP_PROMO)) chain.proceed() else false
        }

        private fun rankView(widget: Any?, getProperty: Method, getType: Method, getView: Method): View? {
            if (widget == null) return null
            return try {
                val property = getProperty.invoke(widget) ?: return null
                if (getType.invoke(property) != "template_rank") null else getView.invoke(widget) as? View
            } catch (error: Throwable) {
                H.logv("event=map_promo_property_failed err=" + error.javaClass.simpleName)
                null
            }
        }

        @JvmStatic fun templateAddHook(getProperty: Method, getType: Method, getView: Method): XposedInterface.Hooker =
                object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val view = rankView(chain.getArg(0), getProperty, getType, getView) ?: return result
                val allowed = Config.visible(Config.K_MAP_PROMO)
                val state = synchronized(widgets) {
                    widgets[view] ?: State(viewVisibility, arrayOf(view.visibility), allowed).also { widgets[view] = it }
                }
                // The container now owns the view; hide it before the first draw.
                // Re-attachment must not replace the saved native state with our GONE.
                if (!allowed) view.visibility = View.GONE
                else if (!state.allowed) view.visibility = state.args[0] as Int
                state.allowed = allowed
                return result
            }
        }

        @JvmStatic fun templateVisibilityHook(getProperty: Method, getType: Method, getView: Method): XposedInterface.Hooker =
                object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val view = rankView(chain.getArg(0), getProperty, getType, getView) ?: return chain.proceed()
                val allowed = Config.visible(Config.K_MAP_PROMO)
                synchronized(widgets) {
                    widgets[view] = State(viewVisibility, arrayOf(chain.getArg(1)), allowed)
                }
                val args = chain.getArgs().toTypedArray()
                if (!allowed) args[1] = View.GONE
                // Keep the native container's layout/measure work and return value.
                return chain.proceed(args)
            }
        }

        @JvmStatic fun visibilityHook(method: Method): XposedInterface.Hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val self = chain.getThisObject() ?: return chain.proceed()
                val allowed = Config.visible(Config.K_MAP_PROMO)
                val args = chain.getArgs().toTypedArray()
                if (replay.get() !== self) synchronized(widgets) {
                    widgets[self] = State(method, args.clone(), allowed)
                }
                if (!allowed) args[0] = View.GONE
                // Native content/two-argument setters delegate to setVisibility(int).
                // Preserve the outer request for restoration, rather than its forced GONE.
                val previous = replay.get()
                replay.set(self)
                try { return chain.proceed(args) }
                finally { if (previous == null) replay.remove() else replay.set(previous) }
            }
        }

        @JvmStatic fun onConfigChanged(key: String?) {
            if (key != null && key != Config.K_MAP_PROMO) return
            if (synchronized(widgets) { widgets.isEmpty() }) return
            val main = Looper.getMainLooper() ?: return
            if (Looper.myLooper() == main) refreshWidgets() else Handler(main).post { refreshWidgets() }
        }

        /** Main-thread native setter replay; never revive a widget hidden by the host. */
        @JvmStatic fun refreshWidgets() {
            val allowed = Config.visible(Config.K_MAP_PROMO)
            val pending = synchronized(widgets) { widgets.entries.map { it.key to it.value } }
            for ((widget, state) in pending) {
                if (state.allowed == allowed) continue
                val args = state.args.clone()
                if (!allowed) args[0] = View.GONE
                val previous = replay.get()
                replay.set(widget)
                try {
                    state.method.invoke(widget, *args)
                    state.allowed = allowed
                } catch (error: Throwable) {
                    H.log("event=map_promo_refresh_failed err=" + error.javaClass.simpleName)
                } finally {
                    if (previous == null) replay.remove() else replay.set(previous)
                }
            }
        }
    }
}
