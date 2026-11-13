package io.github.ldxm666.bmapclean

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** 22.1.20: clickTab -> newHomeHandleClickTab -> letTabFull -> native DragController. */
object BaiduHomeReselect {
    private val scoped = ThreadLocal<Any>()

    @JvmStatic
    fun install(cl: ClassLoader) {
        val presenter = H.cls(cl, "com.baidu.baidumaps.aihome.panel.presenter.TabPresenter")
        val tab = H.cls(cl, "com.baidu.baidumaps.aihome.panel.presenter.TabPresenter\$Tab")
        val click = H.method(presenter, "newHomeHandleClickTab", 1)
        val full = H.method(presenter, "letTabFull", 0)
        if (click == null || full == null || tab == null || click.parameterTypes[0] !== tab ||
                click.returnType != Void.TYPE || full.returnType != Void.TYPE ||
                H.method(presenter, "letTabHalf", 0) == null || H.method(presenter, "letTabHide", 0) == null) {
            H.log("home_reselect missing_native_contract")
            return
        }
        H.hook(click, "baidu_home_reselect_scope", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val self = chain.getThisObject() ?: return chain.proceed()
                val selected = BaiduHooks.invoke(self, "getTab")?.toString()
                val compact = BaiduHooks.emptyHome() || BaiduPanelHeight.pinned()
                if (!shouldScope(selected, chain.getArg(0)?.toString(), compact)) return chain.proceed()
                if (busy(self)) return null
                val previous = scoped.get()
                scoped.set(self)
                try { return chain.proceed() }
                finally { if (previous == null) scoped.remove() else scoped.set(previous) }
            }
        })
        H.hook(full, "baidu_home_reselect_full", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val self = chain.getThisObject()
                if (self != null && scoped.get() === self && toggleCompact(self)) return null
                return chain.proceed()
            }
        })
    }

    @JvmStatic
    fun shouldScope(current: String?, target: String?, compact: Boolean): Boolean =
            compact && current == "ROUTE" && target == "ROUTE"

    private fun method(target: Any, name: String): Method? = H.method(target.javaClass, name, 0)

    private fun busy(presenter: Any): Boolean {
        val component = BaiduHooks.field(presenter, "component")
        val manager = BaiduHooks.field(component, "drawerManager")
        val layout = BaiduHooks.invoke(manager, "getLayoutBehavior")
        val controller = BaiduHooks.invoke(layout, "getDragController")
        val sheet = BaiduHooks.invoke(controller, "getPanelSheetBehavior")
        val state = BaiduHooks.invoke(sheet, "getState")
        return state == 1 || state == 2
    }

    /** Uses the presenter's native methods: controller, callbacks and map insets agree. */
    @JvmStatic
    fun toggleCompact(presenter: Any): Boolean {
        return try {
            val stateMethod = method(presenter, "getDrawerState") ?: return false
            stateMethod.isAccessible = true
            val state = stateMethod.invoke(presenter)?.toString()
            if (state !in arrayOf("HIDDEN", "COLLAPSED", "EXPANDED")) return false
            val target = method(presenter, if (state == "HIDDEN") "letTabHalf" else "letTabHide") ?: return false
            target.isAccessible = true
            target.invoke(presenter)
            H.log("home_reselect native=$state -> ${if (state == "HIDDEN") "COLLAPSED" else "HIDDEN"}")
            true
        } catch (error: Throwable) {
            H.log("home_reselect failed=${error.javaClass.simpleName}")
            false
        }
    }
}
