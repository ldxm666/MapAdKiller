package io.github.ldxm666.mapadkiller

import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

import org.json.JSONObject

import io.github.libxposed.api.XposedInterface

/** Filters the POI-detail feed before AJX lays out its modules, including refreshed pages. */
object AmapPlaceAds {
    private val requests: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val reported = AtomicBoolean()

    @JvmStatic
    fun install(cl: ClassLoader) {
        // ★ 安装期门控：place_recommend 开着（= 显示推荐流）⇒ 不需要过滤，一条都不装（零成本）。
        if (Config.installGate(Config.K_PLACE_RECOMMEND)) {
            H.log("event=hook_skipped key=place_recommend reason=switch_off")
            return
        }
        val request = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest")
        val callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback")
        val module = H.cls(cl, "com.autonavi.minimap.ajx3.modules.AbstractModule")
        val context = H.cls(cl, "com.autonavi.minimap.ajx3.context.IAjxContext")
        if (request == null || callback == null || module == null || context == null) return
        val getContext: Method = module.getMethod("getContext")
        val getPath: Method = context.getMethod("getJsPath")
        val register = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    val options = chain.getArg(1)
                    val source = if (options is JSONObject) options else JSONObject(options as String)
                    if (AmapPlaceData.DETAIL_PATH == AmapData.requestPath(source.optString("url"))) {
                        val pageContext = getContext.invoke(chain.getThisObject())
                        val page = if (pageContext == null) null else getPath.invoke(pageContext)
                        if (detailPage(page) && chain.getArg(2) != null) requests.put(chain.getArg(2)!!, java.lang.Boolean.TRUE)
                    }
                } catch (ignored: Exception) {}
                return chain.proceed()
            }
        }
        H.hookSig(request, "fetch", "amap_place_request_fetch", register, String::class.java, String::class.java, callback)
        H.hookSig(request, "sendHttp", "amap_place_request_http", register, String::class.java, JSONObject::class.java, callback)
        H.hookSig(request, "notifyJs", "amap_place_response_xhr", response(true),
                callback, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java)
        H.hookSig(request, "notifyResponse", "amap_place_response_http", response(false),
                callback, Int::class.javaPrimitiveType, JSONObject::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java)
    }

    private fun detailPage(value: Any?): Boolean {
        if (value !is String) return false
        var path = value
        val query = path.indexOf('?')
        val fragment = path.indexOf('#')
        var end = if (query < 0) path.length else query
        if (fragment >= 0) end = Math.min(end, fragment)
        path = path.substring(0, end)
        return "path://amap_bundle_poi/src/poi.jsx.js" == path ||
                "path://amap_bundle_poi/src/FirstPoint.page.js" == path
    }

    private fun response(xhr: Boolean): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (xhr && (chain.getArg(2) as? Int) != 4) return chain.proceed()
                val scoped = requests.remove(chain.getArg(0)) == java.lang.Boolean.TRUE
                if (!scoped || (chain.getArg(1) as? Int) != 200 || chain.getArg(3) !is String)
                    return chain.proceed()
                val raw = chain.getArg(3) as String
                val filtered = AmapPlaceData.response(AmapPlaceData.DETAIL_PATH, raw, Config.visible(Config.K_PLACE_RECOMMEND))
                if (raw == filtered) return chain.proceed()
                val args = chain.getArgs().toTypedArray()
                args[3] = filtered
                if (reported.compareAndSet(false, true)) H.log("event=amap_place_feed_drop modules=WaterFeedTitle,WaterFeed")
                return chain.proceed(args)
            }
        }
    }
}
