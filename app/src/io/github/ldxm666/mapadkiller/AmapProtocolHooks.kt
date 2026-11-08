package io.github.ldxm666.mapadkiller

import android.content.Context

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

import org.json.JSONObject

import io.github.libxposed.api.XposedInterface

/** Exact signatures verified from the installed 17.00.0.2005 DEX via JADX MCP. */
object AmapProtocolHooks {
    private val sessions: MutableMap<Any?, Boolean> = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    @Volatile
    private var sHostLoader: ClassLoader? = null

    private data class RequestScope(val path: String, val taxiHome: Boolean)
    private val requests: MutableMap<Any?, RequestScope> = Collections.synchronizedMap(WeakHashMap<Any, RequestScope>())
    private val reportedCards: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    @JvmStatic
    fun compatible(cl: ClassLoader): Boolean {
        val p = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP)
        H.log("amap_adapter version=" + (if (p == null) "unknown" else p.versionName) + " mode=capabilities")
        return true
    }

    @JvmStatic
    fun install(cl: ClassLoader) {
        installStream(cl)
        installTools(cl)
        installResponses(cl)
        installIntentProbe(cl)
        installNavUriFix(cl)
        H.hookSig(H.cls(cl, "com.autonavi.inter.impl.HomeTabInitConfigFactory"), "obtainTabList", "amap_tabs", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val original = chain.proceed()
                if (original !is List<*>) return original
                val source = original
                val out = ArrayList<Any?>()
                for (tab in source) {
                    val id = tab.toString()
                    val label = if ("Nearby" == id) "探索" else if ("Message" == id) "长按说话"
                    else if ("Taxi" == id) "打车" else if ("Mine" == id) "我的" else null
                    if (label == null || Config.tabVisible(label)) out.add(tab)
                }
                return if (out.size == source.size) original else out
            }
        }, Context::class.java)
    }

    private fun installStream(cl: ClassLoader) {
        val session = H.cls(cl, "com.autonavi.bundle.vui.llm.d")
        val callback = H.cls(cl, "com.autonavi.bundle.vui.api.ILLMSession\$Callback")
        if (callback == null) return
        H.hookSig(session, "setSceneId", "amap_stream_scope", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                sessions.put(chain.getThisObject(), "home_qs" == chain.getArg(0))
                return chain.proceed()
            }
        }, String::class.java)
        H.hookSig(session, "start", "amap_stream_start", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val target = chain.getArg(0)
                H.log("amap_stream scoped=" + (java.lang.Boolean.TRUE == sessions.get(chain.getThisObject())))
                if (target == null || java.lang.Boolean.TRUE != sessions.get(chain.getThisObject())) return chain.proceed()
                val proxy = Proxy.newProxyInstance(cl, arrayOf<Class<*>>(callback), object : InvocationHandler {
                    override fun invoke(self: Any?, method: Method, args: Array<Any?>?): Any? {
                        var a = args
                        if ("onMessageReceived" == method.name && a != null && a.size == 1 && a[0] is JSONObject) {
                            val source = a[0] as JSONObject
                            var result: JSONObject? = source
                            try {
                                result = AmapData.stream(source, AmapData.CURRENT)
                            } catch (e: Exception) {
                                diagnose("stream", e)
                            }
                            val component = source.optJSONObject("component")
                            val extra = if (component == null) null else component.optJSONObject("extra_info")
                            val name = if (extra == null) "terminal" else extra.optString("cardName")
                            if (reportedCards.add(name)) H.log("amap_stream card=" + name + " keep=" + (result != null))
                            if (result == null) return null
                            a = arrayOf<Any?>(result)
                        }
                        try {
                            return method.invoke(target, *(a ?: emptyArray<Any?>()))
                        } catch (e: InvocationTargetException) {
                            throw e.cause!!
                        }
                    }
                })
                return chain.proceed(arrayOf<Any?>(proxy))
            }
        }, callback)
    }

    private fun installTools(cl: ClassLoader) {
        val storage = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage")
        H.hookSig(storage, "getItemSync", "amap_tool_storage", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if ("toolbox" != chain.getArg(0) || result !is String) return result
                try {
                    return AmapData.storage(chain.getArg(1) as String, result, AmapData.CURRENT)
                } catch (e: Exception) {
                    diagnose("tool_storage", e)
                    return result
                }
            }
        }, String::class.java, String::class.java)
        val callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback")
        if (callback != null && storage != null) {
            try {
                val callbackMethod = callback.getMethod("callback", Array<Any?>::class.java)
                val getItem = storage.getMethod("getItemSync", String::class.java, String::class.java)
                H.hookSig(storage, "getItemASYNC", "amap_tool_async", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if ("toolbox" != chain.getArg(0) || chain.getArg(2) == null) return chain.proceed()
                        val value: Any?
                        try {
                            value = getItem.invoke(chain.getThisObject(), chain.getArg(0), chain.getArg(1))
                        } catch (e: Exception) {
                            diagnose("tool_async_read", e)
                            return chain.proceed()
                        }
                        try {
                            callbackMethod.invoke(chain.getArg(2), arrayOf<Any?>(arrayOf<Any?>(value)))
                        } catch (e: InvocationTargetException) {
                            throw e.cause!!
                        }
                        return null
                    }
                }, String::class.java, String::class.java, callback)
            } catch (e: Exception) {
                diagnose("tool_async_signature", e)
            }
        }
        H.hookSig(H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl"), "getToolboxData", "amap_tool_boot", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (result !is JSONObject) return result
                try {
                    // fill=true: this is the payload the home toolbar actually renders, and it
                    // is the only place where switching a tool on may ADD a cell.
                    val out = AmapData.toolbox(result, AmapData.CURRENT, true)
                    if (out !== result) H.log("amap_tool_boot slots=" + AmapData.slotIds(out))
                    return out
                } catch (e: Exception) {
                    diagnose("tool_boot", e)
                    return result
                }
            }
        })
    }

    private fun installResponses(cl: ClassLoader) {
        val request = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest")
        val callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback")
        if (callback == null) return
        val register = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    val options = chain.getArg(1)
                    val j = if (options is JSONObject) options else JSONObject(options as String)
                    val path = AmapData.requestPath(j.optString("url"))
                    val taxiHome = AmapTaxiData.handles(path) && AmapTaxiData.isHomeRequest(j)
                    if ((AmapData.handles(path) || taxiHome) && chain.getArg(2) != null) {
                        requests.put(chain.getArg(2), RequestScope(path, taxiHome))
                    }
                    if (Config.debugLog() && AmapTaxiData.handles(path)) {
                        H.log("event=taxi_request path=$path home=$taxiHome")
                    }
                } catch (ignored: Exception) {}
                return chain.proceed()
            }
        }
        H.hookSig(request, "fetch", "amap_request_fetch", register, String::class.java, String::class.java, callback)
        H.hookSig(request, "sendHttp", "amap_request_http", register, String::class.java, JSONObject::class.java, callback)
        H.hookSig(request, "notifyJs", "amap_request_response", response(true), callback, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java)
        H.hookSig(request, "notifyResponse", "amap_http_response", response(false), callback, Int::class.javaPrimitiveType, JSONObject::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java)
    }

    private fun response(xhr: Boolean): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (xhr && (chain.getArg(2) as? Int) != 4) return chain.proceed()
                val scope = requests.remove(chain.getArg(0))
                val raw = chain.getArg(3)
                if (scope == null || raw !is String || (chain.getArg(1) as? Int) != 200) return chain.proceed()
                val args = chain.getArgs().toTypedArray()
                try {
                    args[3] = if (scope.taxiHome) AmapTaxiData.response(scope.path, raw, AmapData.CURRENT)
                        else AmapData.response(scope.path, raw, AmapData.CURRENT)
                    if (scope.taxiHome && Config.debugLog()) {
                        H.log("event=taxi_response path=${scope.path} changed=${args[3] != raw}" +
                            " before=[${AmapTaxiData.summary(scope.path, raw)}]" +
                            " after=[${AmapTaxiData.summary(scope.path, args[3] as String)}]")
                    }
                } catch (e: Exception) {
                    diagnose("response", e)
                    return chain.proceed()
                }
                return chain.proceed(args)
            }
        }
    }

    private fun diagnose(area: String, e: Exception) {
        if (Config.debugLog()) H.log("amap_adapter area=" + area + " error=" + e.javaClass.simpleName)
    }

    // ── 外部 intent 落点探针（Bug1：冷启动导航跳转只开首页、不进导航选择页）───────────
    // 只观测，不改行为。每个落点打一条 event=nav_probe 日志，一次实机即可判定 intent 断在哪环。
    //
    // 17.02 实测链路（classes5 / classes6，锚点见 amap_17.02.0.1541_registry.md）：
    //   androidamap://navi?… → SchemeHandleActivity.onCreate 复制 intent 后
    //     setComponent("com.autonavi.map.activity.SplashActivity") + startActivity
    //   ★ "SplashActivity" 是 <activity-alias>，targetActivity = NewMapActivity
    //     ⇒ 反射 "com.autonavi.map.activity.SplashActivity" 取类必然 NOT FOUND，
    //       探针只能挂 NewMapActivity 这一侧（本类 NewMapActivity 自身没有覆写
    //       onCreate/onNewIntent，全部逻辑在 presenter com.autonavi.minimap.lite.a 里）。
    //   NewMapActivity.solveScheme(Intent) → lite.a.solveScheme → IntentController.f(intent,cb)
    //     → IntentController.c(intent,cb) 才是真正分发（f 内部分支可丢弃并回 callback(FALSE)）。
    //   lite.a.<init> / onNewIntent 把 launch intent 存进字段 b，dispatchIntent 再取出送分发。
    //   ⇒ 冷启动丢页只可能是「b 没存 / dispatchIntent 没走到 / f 内部分支丢弃」三者之一，
    //     下面这些探针点正好覆盖三种可能，配合 ctl_f→ctl_c 是否成对出现即可一眼定位。
    private fun installIntentProbe(cl: ClassLoader) {
        sHostLoader = cl
        val presenter = H.cls(cl, "com.autonavi.minimap.lite.a")
        val controller = H.cls(cl, "com.autonavi.minimap.intent.IntentController")
        var n = 0
        n += probeAll(presenter, "<init>", "presenter_init")
        n += probeAll(presenter, "solveScheme", "solveScheme")
        n += probeAll(presenter, "onNewIntent", "presenter_onNewIntent")
        n += probeAll(presenter, "dispatchIntent", "dispatchIntent")
        n += probeAll(presenter, "loadPage", "loadPage")
        n += probeAll(controller, "f", "ctl_f")
        n += probeAll(controller, "c", "ctl_c")
        n += probeAll(controller, "d", "ctl_d")
        n += probeAll(controller, "g", "ctl_g")
        // 外部 scheme 的真实入口：私有 scheme 与通用 geo/地图链接都声明在这个 activity 上。
        val entry = H.cls(cl, "com.autonavi.map.activity.SchemeHandleActivity")
        n += probeAll(entry, "onCreate", "scheme_entry")
        H.log("event=nav_probe_install hooks=" + n
                + " presenter=" + (presenter != null) + " controller=" + (controller != null)
                + " entry=" + (entry != null))
    }

    /** 按方法名挂该类全部同名重载（不猜签名），返回挂上的个数。 */
    private fun probeAll(c: Class<*>?, name: String, tag: String): Int {
        val mod = H.module
        if (c == null || mod == null) return 0
        var n = 0
        try {
            if ("<init>" == name) {
                for (k in c.declaredConstructors) {
                    if (attach(k, tag, 0)) n++
                }
                return n
            }
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (attach(m, tag, m.parameterTypes.size)) n++
            }
        } catch (t: Throwable) {
            H.log("event=nav_probe_error tag=" + tag + " err=" + t)
        }
        return n
    }

    private fun attach(e: java.lang.reflect.Executable, tag: String, arity: Int): Boolean {
        try {
            e.isAccessible = true
            val mod = H.module ?: return false
            mod.hook(e)
                    .setId("nav_probe_" + tag + "_" + arity)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(probeHook(tag))
            return true
        } catch (t: Throwable) {
            H.log("event=nav_probe_attach_failed tag=" + tag + " err=" + t.javaClass.simpleName)
            return false
        }
    }

    private fun probeHook(tag: String): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var hits = 0
            override fun intercept(chain: XposedInterface.Chain): Any? {
                var intent: Any? = null
                val args = chain.getArgs().toTypedArray()
                for (a in args) {
                    if (a is android.content.Intent) {
                        intent = a
                        break
                    }
                }
                // onCreate(Bundle) 这类入口没有 Intent 参数，直接读宿主的 getIntent()。
                if (intent == null) {
                    val self = chain.getThisObject()
                    if (self is android.app.Activity) {
                        try {
                            intent = self.intent
                        } catch (ignored: Throwable) {}
                    }
                }
                val before = describe(intent)
                // 默认只留首 2 次（每次启动约 20 行，够判定断点）；设置页打开调试日志后放全。
                val verbose = hits < (if (Config.debugLog()) 12 else 2)
                if (verbose) H.log("event=nav_probe tag=" + tag + " phase=enter " + before)
                val result = chain.proceed()
                if (verbose) {
                    hits++
                    H.log("event=nav_probe tag=" + tag + " phase=exit ret=" + brief(result)
                            + " launch=" + launchRecord()
                            + " at=" + frame())
                }
                return result
            }
        }
    }

    private fun describe(intent: Any?): String {
        if (intent !is android.content.Intent) return "intent=null"
        val i = intent
        val sb = StringBuilder("action=").append(i.action)
        sb.append(" data=").append(i.dataString)
        try {
            val b = i.extras
            if (b == null) sb.append(" extras=null")
            else {
                sb.append(" extras={")
                var first = true
                for (k in b.keySet()) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(k)
                }
                sb.append('}')
            }
        } catch (t: Throwable) {
            sb.append(" extras=?")
        }
        return sb.toString()
    }

    private fun brief(o: Any?): String {
        if (o == null) return "null"
        if (o is Boolean || o is Number || o is String) return o.toString()
        return o.javaClass.simpleName
    }

    /** com.amap.bundle.utils.app.LaunchRecord.h 是宿主保存的启动 Uri，冷启动是否丢页看它最直接。 */
    private fun launchRecord(): String {
        try {
            val c = H.cls(sHostLoader, "com.amap.bundle.utils.app.LaunchRecord") ?: return "?"
            val f = c.getDeclaredField("h")
            f.isAccessible = true
            val v = f.get(null)
            return v?.toString() ?: "null"
        } catch (t: Throwable) {
            return "?"
        }
    }

    private fun frame(): String {
        try {
            val st = Throwable().stackTrace
            val sb = StringBuilder()
            var n = 0
            for (e in st) {
                val cn = e.className
                if (cn.startsWith("io.github.libxposed") || cn.startsWith("java.lang.Thread")) continue
                if (cn.startsWith("io.github.ldxm666.mapadkiller.AmapProtocolHooks")) continue
                if (n++ > 0) sb.append('<')
                sb.append(cn).append('.').append(e.methodName)
                if (n >= 5) break
            }
            return sb.toString()
        } catch (t: Throwable) {
            return "?"
        }
    }

    // ── 外部导航跳转 URI 归一化（只补缺失参数，绝不覆盖已有值）───────────────────────
    //
    // 为什么在 SchemeHandleActivity.onCreate 上做：它是外部 scheme 的唯一入口
    // （androidamap:// / amapuri:// 在 manifest 里都指向它），onCreate 里先
    // new Intent(原intent) → setComponent(别名 SplashActivity) → startActivity。
    // 我们在 proceed 之前改 Activity.getIntent()，宿主后面复制到的就是改好的那份，
    // 冷/热启动两条路都覆盖到，且不需要碰任何高德业务类。
    //
    // 为什么这是「无复现证据下唯一站得住的修复」：实测证明高德自身的分发链是好的
    // （nav_probe 里 intent 完整送到 IntentController.c），所以问题只可能出在
    // 「送进来的 URI 本身参数不全」——缺 dev / dname / t 时高德解析不出终点，
    // 只能停在首页。补缺失参数不改变任何既有值，最坏情况等于没改。
    private val sNavSkipLogged: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())

    private fun installNavUriFix(cl: ClassLoader) {
        val entry = H.cls(cl, "com.autonavi.map.activity.SchemeHandleActivity")
        val mod = H.module
        if (entry == null || mod == null) {
            H.log("event=nav_uri_fix_install hooks=0 found=" + (entry != null))
            return
        }
        var n = 0
        for (m in entry.declaredMethods) {
            if ("onCreate" != m.name) continue
            val ps = m.parameterTypes
            if (ps.size != 1 || !(android.os.Bundle::class.java == ps[0])) continue
            try {
                m.isAccessible = true
                mod.hook(m).setId("nav_uri_fix")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                try {
                                    normalizeNav(chain.getThisObject())
                                } catch (t: Throwable) {
                                    H.log("event=nav_uri_fix applied=false reason=exception err=" + t)
                                }
                                return chain.proceed()
                            }
                        })
                n++
            } catch (t: Throwable) {
                H.log("event=nav_uri_fix_install hook_failed err=" + t)
            }
        }
        H.log("event=nav_uri_fix_install hooks=" + n + " enabled=" + Config.visible(Config.K_NAV_URI_FIX))
    }

    private fun normalizeNav(self: Any?) {
        if (self !is android.app.Activity) return
        val act = self
        val input = act.intent
        if (input == null) {
            navSkip("no_data", null)
            return
        }
        if (android.content.Intent.ACTION_VIEW != input.action) {
            navSkip("not_external_nav", null)
            return
        }
        val data = input.data
        if (data == null) {
            navSkip("no_data", null)
            return
        }
        val raw = data.toString()
        val scheme = lower(data.scheme)

        // ── 通用地图 intent（系统选择器里点「高德地图」走的就是这一支）─────────────
        // 其他 App 发的是 geo:/pois:/wechatnav:/maps.google.com 这类**通用** URI，
        // 系统弹选择器后把原样 intent 交给高德；高德自己只会把它当「打开地图」，
        // 冷启动时停在首页。这里把它改写成高德自己的路线规划 URI。
        if ("geo" == scheme || "pois" == scheme || "wechatnav" == scheme
                || (("http" == scheme || "https" == scheme) && isMapHost(lower(data.host)))) {
            normalizeGeneric(act, input, data, raw)
            return
        }
        if ("androidamap" != scheme && "amapuri" != scheme) {
            navSkip("scheme_unsupported", raw)
            return
        }
        // 模块自己发的 toolbox 跳转（amapuri://ajx?path=path://amap_bundle_toolbox/…）一律不碰。
        if (raw.contains("path://")) {
            navSkip("not_external_nav", raw)
            return
        }
        val host = lower(data.host)
        val path = lower(data.path)
        val route = contains(host, "route") || contains(path, "route")
        val navi = contains(host, "navi") || contains(path, "navi")
        if (!route && !navi) {
            navSkip("not_external_nav", raw)
            return
        }
        if (!Config.visible(Config.K_NAV_URI_FIX)) {
            navSkip("switch_off", raw)
            return
        }

        val have = HashSet<String>()
        try {
            have.addAll(data.queryParameterNames)
        } catch (ignored: Throwable) {}

        val b = data.buildUpon()
        var added = 0
        if (!have.contains("dev")) {
            b.appendQueryParameter("dev", "0")
            added++
        }
        if (!have.contains("sourceApplication")) {
            b.appendQueryParameter("sourceApplication", "MapAdKiller")
            added++
        }
        val hasCoord = have.contains("lat") || have.contains("lon")
                || have.contains("dlat") || have.contains("dlon")
        if (!have.contains("dname") && hasCoord) {
            val name = firstNonEmpty(data.getQueryParameter("poiname"),
                    data.getQueryParameter("name"), data.getQueryParameter("dname"))
            b.appendQueryParameter("dname", name ?: "目的地")
            added++
        }
        if (route && !have.contains("t")) {
            b.appendQueryParameter("t", "0")
            added++
        }

        if (added == 0) {
            H.log("event=nav_uri_fix applied=false reason=already_ok data=" + raw)
            return
        }
        val fixed = b.build()
        val out = android.content.Intent(input)
        out.data = fixed
        act.intent = out
        H.log("event=nav_uri_fix applied=true added=" + added
                + " before=" + raw + " after=" + fixed)
    }

    // ── 通用地图 intent 归一化（geo: / pois: / wechatnav: / Google·高德 地图链接）──────
    //
    // 用户在系统「选择要打开的方式」里点高德时，高德收到的就是这一支：通用地图 URI。
    // 高德 manifest 里这些 scheme 全部声明在 SchemeHandleActivity 上（geo/pois/wechatnav/
    // http(s) 的 12 个 host），所以入口和私有 scheme 是同一个类，钩子不用换。
    // 高德自身只会把它当「打开地图」，不解析终点 ⇒ 停在首页。
    // 这里把经纬度/名称抽出来，改写成高德自己的 amapuri://route/plan/ 路线规划 URI。
    private val MAP_HOSTS = arrayOf(
            "amap.com", "m.amap.com", "wb.amap.com", "f.amap.com", "mo.amap.com", "dypx.amap.com",
            "amap.onelink.me", "surl.test.myyamap.com",
            "maps.google.com", "maps.google.cn", "ditu.google.com", "ditu.google.cn"
    )

    private val COORD_PAIR: java.util.regex.Pattern = java.util.regex.Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)")

    private val PAREN_NAME: java.util.regex.Pattern = java.util.regex.Pattern.compile(
            "\\(([^)]{1,80})\\)")

    private fun isMapHost(host: String?): Boolean {
        if (host == null || host.isEmpty()) return false
        for (h in MAP_HOSTS) {
            if (host == h || host.endsWith(".$h")) return true
        }
        return false
    }

    private fun normalizeGeneric(act: android.app.Activity, input: android.content.Intent,
                                 data: android.net.Uri, raw: String) {
        if (!Config.visible(Config.K_NAV_URI_FIX)) {
            navSkip("switch_off", raw)
            return
        }
        val p = parseCoord(data)
        if (p == null) {
            navSkip("no_coord", raw)
            return
        }
        var name = parseName(data)
        if (name == null || name.isEmpty()) name = "目的地"
        val fixed: android.net.Uri
        try {
            fixed = android.net.Uri.parse("amapuri://route/plan/?dlat=" + fmtCoord(p[0])
                    + "&dlon=" + fmtCoord(p[1])
                    + "&dname=" + android.net.Uri.encode(name)
                    + "&dev=0&t=0")
        } catch (t: Throwable) {
            navSkip("parse_error", raw)
            return
        }
        val out = android.content.Intent(input)
        out.data = fixed
        act.intent = out
        H.log("event=nav_uri_fix applied=true kind=generic before=" + raw + " after=" + fixed)
    }

    /** 从通用地图 URI 里抽 (lat, lon)；抽不出或越界返回 null。 */
    private fun parseCoord(data: android.net.Uri): DoubleArray? {
        val s = data.toString()
        val qm = s.indexOf('?')
        val pathPart = if (qm >= 0) s.substring(0, qm) else s
        val colon = pathPart.indexOf(':')
        val body = if (colon >= 0) pathPart.substring(colon + 1) else pathPart
        // ★ wechatnav://type=nav&fromcoord=..&tocoord=.. 这种**没有 `?`** 的形式：
        //   参数全在 authority（`//` 之后）里。以前 queryPart 直接取空串 ⇒ 一个参数都读不到
        //   ⇒ 日志 `skip reason=no_coord`，修复完全不生效。这里把 authority 当 query 用。
        val queryPart: String
        if (qm >= 0) {
            queryPart = s.substring(qm + 1)
        } else {
            queryPart = if (body.startsWith("//")) body.substring(2) else ""
        }

        // ★ 0) 终点专用键，优先级最高。
        //   微信 `wechatnav://type=nav&fromcoord=<起点>&tocoord=<终点>&from=..&to=..`
        //   以前没认 tocoord，就回落到「正文里第一个坐标对」= fromcoord（起点）⇒ 终点变成
        //   用户当前位置，路线显示「1 分钟 6 米」。这是实测踩到的真 bug，务必保持 tocoord 优先。
        for (key in arrayOf("tocoord", "toCoord", "destcoord", "endcoord", "to")) {
            val v = queryValue(queryPart, key) ?: continue
            val q = pair(v)
            if (q != null && !(q[0] == 0.0 && q[1] == 0.0)) return q
        }

        // 1) geo:lat,lng 主体（仅限 geo: —— 这类正文本身就是终点坐标）。
        //    ⚠ wechatnav / pois 的正文是 `k=v&k=v`，第一个坐标对通常是 **fromcoord（起点）**，
        //    绝不能拿来当终点。这条限制就是上面那个 bug 的根因。
        val scheme = if (data.scheme == null) "" else data.scheme!!.lowercase(java.util.Locale.US)
        if ("wechatnav" != scheme && "pois" != scheme) {
            val p0 = pair(body)
            if (p0 != null && !(p0[0] == 0.0 && p0[1] == 0.0)) return p0
        }

        // 2) query 里的坐标：q / ll / daddr / saddr / position / center / latlng / dlat
        for (key in arrayOf("q", "ll", "daddr", "saddr", "position", "center", "latlng", "dlat")) {
            val v = queryValue(queryPart, key) ?: continue
            val p = pair(v)
            if (p != null && !(p[0] == 0.0 && p[1] == 0.0)) return p
        }
        // 3) 分开写的 lat/lon、dlat/dlon、latitude/longitude
        val lat = num(queryValue(queryPart, "lat"), queryValue(queryPart, "latitude"),
                queryValue(queryPart, "dlat"))
        val lon = num(queryValue(queryPart, "lon"), queryValue(queryPart, "lng"),
                queryValue(queryPart, "longitude"), queryValue(queryPart, "dlon"))
        if (lat != null && lon != null) {
            val out = doubleArrayOf(lat, lon)
            return if (valid(out)) out else null
        }
        return null
    }

    private fun parseName(data: android.net.Uri): String? {
        val s = data.toString()
        val qm = s.indexOf('?')
        val queryPart: String
        if (qm >= 0) {
            queryPart = s.substring(qm + 1)
        } else {
            val colon = s.indexOf(':')
            val body = if (colon >= 0) s.substring(colon + 1) else s
            queryPart = if (body.startsWith("//")) body.substring(2) else ""   // 同 parseCoord：authority 当 query
        }
        for (key in arrayOf("dname", "to", "toname", "toName", "q", "name", "title", "daddr", "saddr", "position")) {
            val v = queryValue(queryPart, key)
            if (v == null || v.isEmpty()) continue
            val m = PAREN_NAME.matcher(v)
            if (m.find()) return m.group(1).trim()
            if (!COORD_PAIR.matcher(v).find()) return v.trim()   // 纯文字（地址）
        }
        return null
    }

    /** 取 query 里某个键的原始（已解码）值；手工解析，geo: 是不透明 URI，getQueryParameter 拿不到。 */
    private fun queryValue(query: String?, key: String): String? {
        if (query == null || query.isEmpty()) return null
        for (kv in query.split("&")) {
            val eq = kv.indexOf('=')
            val k = if (eq >= 0) kv.substring(0, eq) else kv
            if (key != k) continue
            val v = if (eq >= 0) kv.substring(eq + 1) else ""
            try {
                return android.net.Uri.decode(v)
            } catch (t: Throwable) {
                return v
            }
        }
        return null
    }

    private fun pair(text: String?): DoubleArray? {
        if (text == null) return null
        val m = COORD_PAIR.matcher(text)
        if (!m.find()) return null
        try {
            val out = doubleArrayOf(m.group(1).toDouble(), m.group(2).toDouble())
            return if (valid(out)) out else null
        } catch (t: Throwable) {
            return null
        }
    }

    private fun valid(p: DoubleArray?): Boolean {
        if (p == null) return false
        return p[0] >= -90.0 && p[0] <= 90.0 && p[1] >= -180.0 && p[1] <= 180.0
                && !(Math.abs(p[0]) < 1e-9 && Math.abs(p[1]) < 1e-9)
    }

    private fun num(vararg values: String?): Double? {
        for (v in values) {
            if (v == null || v.isEmpty()) continue
            try {
                return java.lang.Double.valueOf(v.trim())
            } catch (ignored: Throwable) {}
        }
        return null
    }

    private fun fmtCoord(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)

    private fun navSkip(reason: String, raw: String?) {
        // 每个原因每个进程只记一次，避免每次启动都刷屏；打开调试日志后全量记录。
        if (Config.debugLog() || sNavSkipLogged.add(reason)) {
            H.log("event=nav_uri_fix skip reason=" + reason + (if (raw == null) "" else " data=" + raw))
        }
    }

    private fun lower(s: String?): String = s?.lowercase() ?: ""

    private fun contains(s: String?, part: String): Boolean = s != null && s.contains(part)

    private fun firstNonEmpty(vararg values: String?): String? {
        for (v in values) {
            if (v != null && v.isNotEmpty()) return v
        }
        return null
    }
}
