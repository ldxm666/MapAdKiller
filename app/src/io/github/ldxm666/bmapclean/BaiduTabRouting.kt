package io.github.ldxm666.bmapclean

import android.view.View
import android.widget.LinearLayout

/** Keep the SDK's transparent proxy hit regions in step with native tab cells. */
class BaiduTabRouting private constructor() {

    companion object {

        private val CELLS: Array<String> = arrayOf("newRoute", "newNearby", "newThird", "newFourth", "newUser")

        private val LISTENERS: Array<String> = arrayOf("routeClickListener", "nearbyClickListener", "thirdClickListener", "fourthClickListener", "userClickListener")

        private val PROXIES: Array<String> = arrayOf("aihome_route_proxy", "aihome_nearby_proxy", "aihome_third_proxy", "aihome_fourth_proxy", "aihome_user_proxy")

        private var logged = false

        @Throws(Exception::class)
        @JvmStatic
        fun refreshPresenter(presenter: Any?) {
            refreshComponent(BaiduHooks.field(presenter, "component"))
        }

        @Throws(Exception::class)
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun refreshComponent(component: Any?) {
            if (component == null) return
            val binding = BaiduHooks.field(component, "ufoBinding")
            val presenter = BaiduHooks.field(component, "homeTabPresenter")
            if (binding == null || presenter == null) return
            val master = Cfg.visible(Spec.K_BAR)
            val show = BooleanArray(CELLS.size)
            val listeners = arrayOfNulls<View.OnClickListener>(CELLS.size)
            for (i in 0 until CELLS.size) {
                val cell = BaiduHooks.field(binding, CELLS[i]) as View?
                show[i] = master && (i == 0 || Cfg.visible(Spec.K_TAB + Spec.TABS[i])) && cell != null && cell.visibility == View.VISIBLE
                val listener = BaiduHooks.field(presenter, LISTENERS[i])
                // Third can be an SDK voice TouchListener with deliberately no
                // click callback. Keep bindThirdClickEvent's displayed semantics.
                if (i != 2 && listener is View.OnClickListener) listeners[i] = listener
                if (cell != null) {
                    if (show[i]) {
                        cell.isEnabled = true
                        val l = listeners[i]
                        if (l != null) cell.setOnClickListener(l)
                    } else disable(cell)
                }
            }
            if (!show[2]) disable(BaiduHooks.field(binding, "newHomeAiTouchView") as View?)
            val homeBinding = BaiduHooks.field(component, "binding")
            if (homeBinding == null) return
            val rowView = BaiduHooks.field(homeBinding, "preventEvent") as View?
            if (rowView !is LinearLayout) return
            val row = rowView
            val proxies = arrayOfNulls<View>(PROXIES.size)
            for (i in 0 until proxies.size) {
                val id = row.resources.getIdentifier(PROXIES[i], "id", MainHook.PKG_BMAP)
                if (id != 0) proxies[i] = row.findViewById(id)
            }
            // This verified XML row is alpha=0 and fills the drawer's hit area.
            // Its padding, height, alpha and SDK visibility state stay intact.
            applyProxyRow(row, proxies as Array<View>, show, listeners as Array<View.OnClickListener>)
            if (!master) row.visibility = View.GONE
            if (!logged) {
                logged = true
                H.log("tab_routing native_proxy_slots=synchronized identity=listener")
            }
        }

        @JvmStatic
        fun applyProxyRow(row: LinearLayout, proxies: Array<View>, show: BooleanArray, listeners: Array<View.OnClickListener>) {
            var count = 0
            for (i in 0 until proxies.size) {
                val proxy: View? = proxies[i]
                if (proxy == null) continue
                if (!show[i]) {
                    disable(proxy)
                    continue
                }
                proxy.visibility = View.VISIBLE
                proxy.isEnabled = true
                val listener: View.OnClickListener? = listeners[i]
                if (listener != null) proxy.setOnClickListener(listener)
                val params = proxy.layoutParams
                if (params is LinearLayout.LayoutParams) {
                    if (params.width != 0 || params.weight != 1f) {
                        params.width = 0
                        params.weight = 1f
                        proxy.layoutParams = params
                    }
                }
                count++
            }
            if (row.weightSum != count.toFloat()) row.weightSum = count.toFloat()
        }

        @JvmStatic
        private fun disable(view: View?) {
            if (view == null) return
            view.setOnTouchListener(null)
            view.setOnClickListener(null)
            view.setTouchDelegate(null)
            view.isClickable = false
            view.isEnabled = false
            view.visibility = View.GONE
        }
    }
}