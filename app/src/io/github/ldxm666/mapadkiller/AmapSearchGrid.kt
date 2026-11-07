// language: Kotlin, file: AmapSearchGrid.kt, module: MapAdKiller, target: 高德地图 17.02.0.1541 搜索页
package io.github.ldxm666.mapadkiller

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent

import java.lang.reflect.Field
import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

/**
 * AmapSearchGrid — 搜索页「搜索框下方那排彩色金刚位」开关（**容器级整块移除**，v4）。
 *
 * <p>零文字依赖：不读 getText / contentDescription / 任何字段；只按几何找容器，整块删。
 * 日志：event=search_grid_removed via=… node=<结构描述> cells=N / event=search_grid_fail reason=…
 */
class AmapSearchGrid private constructor() {

    companion object {

        private const val PAGE_HINT = "amap_bundle_search_home"
        private val URL_HINTS = arrayOf("searchHomeStandard", "search_home", "amap_bundle_search_home", "/af-search/search")

        /** 几何门槛（实测：金刚位 Container 1016x184 c=6；外层 1017x223 c=2＝行+指示器）。 */
        private const val MIN_CELLS = 4
        private const val MAX_CELLS = 12
        private const val WIDTH_TOLERANCE = 0.28f
        private const val ROW_MIN_DP = 46
        private const val ROW_MAX_DP = 240
        private const val INDICATOR_MAX_DP = 30

        private val PASS_DELAY_MS = longArrayOf(350L, 700L, 1200L, 2000L, 3200L)
        private const val VERIFY_MS = 120L

        /**
         * 数据层改写开关。
         *
         * <p>**当前为 false**：真机实测证明「搜索页那排金刚位的配置」不在
         * {@code af-search/search} 的响应里（那条只回 {@code data.regions.content(4)}，是历史/区域模块），
         * 而全量扫描到的唯一 icon-grid 数组是 {@code data.nineGrid(16)}（车主业务，与本功能无关）。
         * 在没锁定真实数据源之前开启改写＝有可能误删别的业务的数组，所以先关掉，
         * 只保留 {@code event=search_grid_scan} 取证，等定位到真正的数据源再打开。
         */
        private const val STRIP_ENABLED = false

        /**
         * 写侧剔除（`AjxModuleLocalStorage.setItemASYNC`）。
         *
         * <p>**已关闭**：实测「改缓存不影响当次渲染」（13 行彩色块计数逐点一致），
         * 缓存只是旁路副本，渲染源是内存里的网络响应。代码保留，等响应侧打通后再统一启用。
         */
        private const val WRITE_STRIP = false

        /**
         * 响应侧剔除（承载 quickLink 的那条 af-search 响应）。**已实测否证，默认关闭**：
         * 剔掉服务端数据后 AJX 页会回落到预载包写死的默认 6 项（加油站/休闲玩乐/超市…），
         * 那是另一批广告，比不剔更糟。数据侧（响应 + 缓存读/写）三条路全部否证，见注册表 §五。
         */
        private const val RESPONSE_STRIP = false

        /**
         * 取证阶段预留的「精确目标」：填上 module/key 后，下一轮只对这一个键做剔除，
         * 其它键一律原样返回（避免误删车主 nineGrid 之类的业务数据）。
         * 空字符串 = 尚未锁定目标。
         */
        private const val LS_TARGET_MODULE = ""
        private const val LS_TARGET_KEY = ""

        /**
         * 视图层兜底（渲染后摘容器）。
         *
         * <p>**已关闭**：数据层（{@code AmapData.toolVisibleRaw} 里按 id 剔除）已经生效，
         * 那排从一开始就不渲染 ⇒ 不需要再事后摘。留着它只会造成用户报的
         * 「进去闪一下再消失、每次进入都重演」——正是数据层要根治的毛病。
         * 只有在数据层失效（服务端换了新 id）时才临时打开它做兜底。
         */
        // ★ 实测结论（2026-10-25，5 轮否证）：数据层四条路（toolVisible 逐项 / toolbox() slot 跳过 /
        //   时间窗 / 去时间窗）**全部无效** —— 那排不是模块的 toolbox payload，真实数据源未找到。
        //   唯一真正生效的机制就是视图层摘容器，代价：进入搜索页时会先闪现约 0.3s 再消失，
        //   且原位可能留一条空白（AJX 节点属性只在创建时生效，运行期改 display/height 不触发重排）。
        //   ⇒ 如实开启并对外写明限制，不做「假根治」。
        // ★ 数据侧三条路（响应 / 缓存读 / 缓存写）已全部实测否证：剔掉数据后 AJX 页必定回落到
        //   预载包写死的默认 6 项，等于换另一批广告。唯一真正能让那排不出现的机制就是视图层摘容器。
        //   代价（如实告知用户）：进入搜索页会先闪约 0.3s，每次重进重演，原位留约 223px 空白
        //   —— 该容器高度由高德页面自身写死，模块无法回收。
        private const val VIEW_FALLBACK = true

        /** 容器级属性尝试链：挨个试，每次实测高度，谁把它压到 0 就停。 */
        private val ATTRS = arrayOf(
                arrayOf("display", "none"),
                arrayOf("visibility", "gone"),
                arrayOf("height", "0"),
                arrayOf("display", "0"),
        )

        private val DONE: MutableMap<View, Boolean> = java.util.WeakHashMap()
        private val SEEN_URLS: MutableSet<String> =
                java.util.Collections.synchronizedSet(java.util.LinkedHashSet())
        private val ONCE: MutableSet<String> =
                java.util.Collections.synchronizedSet(java.util.HashSet())
        private const val URL_LOG_MAX = 40

        @Volatile
        private var prunedLogged = false

        @JvmStatic
        fun install(cl: ClassLoader) {
            hookResponseBody(cl)
            hookAjxCallback(cl)
            hookAjxLoad(cl)
            hookRequestProbe(cl)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_install off=" + off() + " v=6")
            hookLsProbe(cl)
            hookSharedPrefs(cl)
            hookLoadData(cl)
        }

        private fun off(): Boolean {
            return try {
                !Config.searchGrid()
            } catch (t: Throwable) {
                false
            }
        }

        // ══════════════════════════════════════════ Layer ① 数据层：整条剔除金刚位数组

        private fun hookResponseBody(cl: ClassLoader) {
            val resp = H.cls(cl, "com.autonavi.core.network.inter.response.HttpResponse")
            if (resp == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=HttpResponse")
                return
            }
            H.hookSig(resp, "getResponseBodyString", "amap_search_grid_body", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val raw = chain.proceed()
                    if (raw !is String) return raw
                    try {
                        val body = raw
                        val url = urlOf(chain.getThisObject())
                        val hit = isSearchHome(url)
                        if (ONCE.add("b:" + System.identityHashCode(chain.getThisObject()))) {
                            // 取证：URL 拿不到时把 response/request 的真实形状打出来（每条响应一次）
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_body size=" + body.length +
                                    " url=" + url + " hit=" + hit + " off=" + off() +
                                    " head=" + head(body))
                        }
                        if (hit && ONCE.add("sh:" + body.length)) {
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_afsearch len=" + body.length +
                                    " off=" + off() + " arrays=" + describeArrays(body))
                        }
                        val interesting = body.length > 400 && body.indexOf("\"icon\"") > 0 ||
                                body.indexOf("\"iconUrl\"") > 0 || body.indexOf("\"img\"") > 0
                        if ((hit || interesting) && ONCE.add("u:" + (if (url == null) "" else url.hashCode()) +
                                ":" + body.length)) {
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_scan hit=" + hit +
                                    " len=" + body.length + " arrays=" + describeArrays(body) +
                                    " url=" + shorten(url))
                        }
                        if (!off()) return raw
                        // ★ 响应侧：承载 quickLinkVoData 的那条响应就是渲染源（缓存只是旁路副本）
                        if (RESPONSE_STRIP && body.indexOf("quickLink") >= 0) {
                            if (ONCE.add("ql:" + (if (url == null) "" else url.hashCode()))) {
                                H.log(Log.INFO, MainHook.TAG, "event=search_grid_ql url=" + shorten(url) +
                                        " len=" + body.length + " off=" + off())
                            }
                            if (off()) {
                                val ql = emptyQuickLinkField(body)
                                if (ql != null) {
                                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=response url=" +
                                            shorten(url) + " len=" + body.length + "->" + ql.length)
                                    return ql
                                }
                            }
                        }
                        val filtered = if (STRIP_ENABLED) pruneSearchHome(body) else null
                        return filtered ?: raw
                    } catch (t: Throwable) {
                        H.log(Log.WARN, MainHook.TAG, "event=search_grid_body_error " + t)
                        return raw
                    }
                }
            })
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=response_body")
        }

        /**
         * 取证钩子：{@code ModuleRequest$AjxCallback.onSuccess(AosResponse)} ——
         * AJX 网络响应落到 Java 的落点（DEX code_off=0x364600）。
         * 只读不写，用来确认「搜索页那次请求的 URL / 响应体形状」。
         */
        private fun hookAjxCallback(cl: ClassLoader) {
            val cb = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest\$AjxCallback")
            val aos = H.cls(cl, "com.amap.bundle.aosservice.response.AosResponse")
            if (cb == null || aos == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=AjxCallback")
                return
            }
            H.hookSig(cb, "onSuccess", "amap_search_grid_ajxcb", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val resp = chain.getArg(0)
                        val url = urlOf(resp)
                        if (ONCE.add("cb:" + System.identityHashCode(resp))) {
                            var body: String? = null
                            try {
                                body = resp!!.javaClass.getMethod("getResponseBodyString").invoke(resp) as String?
                            } catch (ignored: Throwable) {
                            }
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_cb url=" + url +
                                    " len=" + (if (body == null) -1 else body.length) +
                                    " head=" + head(body))
                        }
                    } catch (ignored: Throwable) {
                    }
                    return chain.proceed()
                }
            }, aos)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=ajx_callback")
        }

        private fun head(body: String?): String {
            if (body == null) return "null"
            val s = body.replace('\n', ' ')
            return if (s.length <= 260) s else s.substring(0, 260) + "…"
        }

        /** 从响应对象上取请求 URL：HttpRequest / IAosRequest 两条链都试。 */
        private fun urlOf(response: Any?): String? {
            if (response == null) return null
            var req: Any? = null
            for (m in arrayOf("getRequest", "getAOSRequest")) {
                try {
                    req = response.javaClass.getMethod(m).invoke(response)
                    if (req != null) break
                } catch (ignored: Throwable) {
                }
            }
            if (req == null) return null
            for (name in arrayOf("getUrl", "getUrlString", "getOriginalUrl", "getHostUrl", "getApiName")) {
                try {
                    val v = req.javaClass.getMethod(name).invoke(req)
                    if (v is String && v.length > 0) return v
                } catch (ignored: Throwable) {
                }
            }
            for (f in req.javaClass.declaredFields) {
                if (f.type !== String::class.java) continue
                if (!f.name.toLowerCase().contains("url")) continue
                try {
                    f.isAccessible = true
                    val v = f.get(req)
                    if (v is String) return v
                } catch (ignored: Throwable) {
                }
            }
            return req.javaClass.simpleName
        }

        private fun isSearchHome(url: String?): Boolean {
            if (url == null || url.isEmpty()) return false
            for (hint in URL_HINTS) if (url.contains(hint)) return true
            return false
        }

        private fun pruneSearchHome(body: String?): String? {
            if (body == null || body.length < 32 || body.length > 4 * 1024 * 1024) return null
            if (body.toLowerCase().contains("toolbox")) return null   // 首页工具栏的地盘，绝不碰
            val c = firstJsonChar(body)
            if (c != '{' && c != '[') return null
            try {
                val root: Any = if (c == '{') org.json.JSONObject(body) else org.json.JSONArray(body)
                val log = ArrayList<String>()
                val n = pruneNode(root, "", 0, log)
                if (n <= 0) {
                    if (ONCE.add("shape:" + body.length)) {
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_shape keys=" + shapeOf(root))
                    }
                    return null
                }
                if (!prunedLogged) {
                    prunedLogged = true
                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=data_strip node=" + log)
                }
                return root.toString()
            } catch (t: Throwable) {
                return null
            }
        }

        private fun pruneNode(node: Any?, key: String, depth: Int, log: MutableList<String>): Int {
            if (node == null || depth > 14) return 0
            var removed = 0
            try {
                if (node is org.json.JSONArray) {
                    for (i in 0 until node.length()) removed += pruneNode(node.opt(i), key, depth + 1, log)
                    if (looksLikeIconGrid(node) && !isNoiseKey(key)) {
                        log.add(key + "(" + node.length() + ")")
                        while (node.length() > 0) node.remove(0)
                        removed += 1
                    }
                } else if (node is org.json.JSONObject) {
                    val keys = ArrayList<String>()
                    val it = node.keys()
                    while (it.hasNext()) keys.add(it.next())
                    for (k in keys) removed += pruneNode(node.opt(k), k, depth + 1, log)
                }
            } catch (ignored: Throwable) {
            }
            return removed
        }

        private fun isNoiseKey(key: String?): Boolean {
            if (key == null) return true
            val k = key.toLowerCase()
            for (bad in arrayOf("history", "recent", "hot", "suggest", "word")) {
                if (k.contains(bad)) return true
            }
            return false
        }

        private fun looksLikeIconGrid(arr: org.json.JSONArray): Boolean {
            val n = arr.length()
            if (n < 4 || n > 14) return false
            var withText = 0
            var withIcon = 0
            for (i in 0 until n) {
                val o = arr.opt(i)
                if (o !is org.json.JSONObject) return false
                if (shortText(o) != null) withText++
                if (hasIcon(o)) withIcon++
            }
            return withText >= n - 1 && withIcon >= n - 1
        }

        private fun shortText(o: org.json.JSONObject): String? {
            for (key in arrayOf("name", "title", "text", "label", "show_name", "display_name")) {
                var v: String? = o.optString(key, null)
                if (v == null) continue
                v = v.trim()
                if (v.isNotEmpty() && v.length <= 10) return v
            }
            return null
        }

        private fun hasIcon(o: org.json.JSONObject): Boolean {
            val it = o.keys()
            while (it.hasNext()) {
                val raw = it.next()
                val k = raw.toLowerCase()
                if (k.contains("icon") || k.contains("img") || k.contains("image") ||
                        k.contains("pic") || k.endsWith("url")) {
                    val v = o.opt(raw)
                    if (v is String && v.length > 8) return true
                }
            }
            return false
        }

        /** URL 太长时只留 host + path，日志才读得动。 */
        private fun shorten(url: String?): String {
            if (url == null) return "null"
            val q = url.indexOf('?')
            val base = if (q < 0) url else url.substring(0, q)
            return if (base.length > 90) base.substring(0, 90) + "…" else base
        }

        /** 取证：把响应里所有数组的「键路径 + 长度 + 首元素键名」打出来（封顶 10 条）。 */
        private fun describeArrays(body: String?): String {
            try {
                val c = firstJsonChar(body!!)
                if (c != '{' && c != '[') return "notjson"
                val root: Any = if (c == '{') org.json.JSONObject(body) else org.json.JSONArray(body)
                val sb = StringBuilder()
                walkArrays(root, "", 0, sb, intArrayOf(0))
                return if (sb.length == 0) "none" else sb.toString()
            } catch (t: Throwable) {
                return "err:" + t.javaClass.simpleName
            }
        }

        private fun walkArrays(node: Any?, path: String, depth: Int, sb: StringBuilder, n: IntArray) {
            if (node == null || depth > 12 || n[0] >= 10) return
            try {
                if (node is org.json.JSONArray) {
                    n[0]++
                    sb.append(path).append('(').append(node.length()).append(')')
                    val first = node.opt(0)
                    if (first is org.json.JSONObject) {
                        sb.append('{')
                        val it = first.keys()
                        var i = 0
                        while (it.hasNext() && i++ < 8) sb.append(it.next()).append(',')
                        sb.append('}')
                    }
                    sb.append(' ')
                    var i = 0
                    while (i < node.length() && n[0] < 10) {
                        walkArrays(node.opt(i), path + "[]", depth + 1, sb, n)
                        i++
                    }
                } else if (node is org.json.JSONObject) {
                    val it = node.keys()
                    while (it.hasNext() && n[0] < 10) {
                        val k = it.next()
                        walkArrays(node.opt(k), if (path.isEmpty()) k else path + "." + k, depth + 1, sb, n)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun shapeOf(root: Any?): String {
            try {
                if (root is org.json.JSONObject) {
                    val sb = StringBuilder()
                    val it = root.keys()
                    var i = 0
                    while (it.hasNext() && i++ < 24) sb.append(it.next()).append(',')
                    return sb.toString()
                }
                return "array(" + (root as org.json.JSONArray).length() + ")"
            } catch (t: Throwable) {
                return "?"
            }
        }

        private fun firstJsonChar(raw: String): Char {
            for (i in 0 until raw.length) {
                val c = raw[i]
                if (c == '{' || c == '[') return c
                if (!Character.isWhitespace(c)) return '\u0000'
            }
            return '\u0000'
        }

        // ══════════════════════════════════════════ Layer ② 视图层：容器级整块移除

        // ══════════════════════════════════════════ 渲染前剔除：AmapAjxView.loadInternal
        //
        // 取证结论（真机 23:57）：搜索页那排金刚位 = **首页工具栏同一份 toolbox payload**，
        // 模块的 toolVisible 早已判定这 6 项 false，但搜索页读的是未经过滤的那份 ⇒ 照样渲染。
        // 所以根治点 = 页面初始化数据（`data` 参数）在**渲染之前**就把这几项剔掉：
        // JS 从一开始就没有这排 ⇒ 不闪、不重演、无留白。
        //
        // 为什么钩 `loadInternal` 而不是 `load`：`load` 的 6 个重载全部汇到 `loadInternal`
        // （DEX classes5.dex code_off=0x4ac72c），一处钩子覆盖所有入口，且不会和本类
        // 已有的 `load` 钩子（负责排期扫描）互相叠加。
        //
        // 判定**不看文案**（服务端会改）：只看结构 —— 数组元素是对象、带 `id` 且带
        // `schema`/`iconUrl`/`icon` 之一，长度 4~14 ⇒ 认定为“一排带图标的工具入口”。

        private val DATA_SEEN: MutableSet<String> =
                java.util.Collections.synchronizedSet(java.util.HashSet())

        private fun hookLoadData(cl: ClassLoader) {
            val ajx = H.cls(cl, "com.autonavi.minimap.ajx3.views.AmapAjxView") ?: return
            val map = H.cls(cl, "java.util.HashMap")
            H.hookSig(ajx, "loadInternal", "amap_search_grid_loaddata", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val path = chain.getArg(0)
                        if (path !is String || !path.contains(PAGE_HINT)) {
                            return chain.proceed()
                        }
                        val data = chain.getArg(1)
                        logLoadData(data)
                        if (!off()) return chain.proceed()
                        val stripped = stripToolbox(data) ?: return chain.proceed()
                        val args = chain.getArgs().toTypedArray()
                        args[1] = stripped
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=load_data cls=" +
                                (if (data == null) "null" else data.javaClass.simpleName))
                        return chain.proceed(args)
                    } catch (t: Throwable) {
                        H.log(Log.WARN, MainHook.TAG, "event=search_grid_loaddata_err " + t)
                        return chain.proceed()
                    }
                }
            }, String::class.java, Any::class.java, String::class.java, String::class.java,
                    Integer.TYPE, Integer.TYPE, String::class.java, java.lang.Long.TYPE, map!!)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=load_data")
        }

        private fun logLoadData(data: Any?) {
            try {
                if (data == null) {
                    if (DATA_SEEN.add("null")) H.log(Log.INFO, MainHook.TAG, "event=search_grid_data2 cls=null")
                    return
                }
                val s = data.toString()
                if (!DATA_SEEN.add(data.javaClass.simpleName + "|" + s.length)) return
                H.log(Log.INFO, MainHook.TAG, "event=search_grid_data2 cls=" +
                        data.javaClass.simpleName + " len=" + s.length + " head=" + lsHead(s))
            } catch (ignored: Throwable) {
            }
        }

        /** 把 data 里“一排带图标的工具入口”清空；返回改过的对象，没改动返回 null。 */
        private fun stripToolbox(data: Any?): Any? {
            if (data == null) return null
            try {
                if (data is String) {
                    val c = firstJsonChar(data)
                    if (c != '{' && c != '[') return null
                    val root: Any = if (c == '{') org.json.JSONObject(data) else org.json.JSONArray(data)
                    val n = pruneToolArrays(root, 0)
                    return if (n <= 0) null else root.toString()
                }
                if (data is org.json.JSONObject || data is org.json.JSONArray) {
                    val n = pruneToolArrays(data, 0)
                    return if (n <= 0) null else data
                }
                if (data is java.util.Map<*, *>) {
                    val n = pruneMap(data, 0)
                    return if (n <= 0) null else data
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        private fun pruneToolArrays(node: Any?, depth: Int): Int {
            if (node == null || depth > 14) return 0
            var removed = 0
            try {
                if (node is org.json.JSONArray) {
                    for (i in 0 until node.length()) removed += pruneToolArrays(node.opt(i), depth + 1)
                    if (looksLikeToolList(node)) {
                        while (node.length() > 0) node.remove(0)
                        removed++
                    }
                } else if (node is org.json.JSONObject) {
                    val keys = ArrayList<String>()
                    val it = node.keys()
                    while (it.hasNext()) keys.add(it.next())
                    for (k in keys) removed += pruneToolArrays(node.opt(k), depth + 1)
                }
            } catch (ignored: Throwable) {
            }
            return removed
        }

        @Suppress("UNCHECKED_CAST")
        private fun pruneMap(map: java.util.Map<*, *>?, depth: Int): Int {
            if (map == null || depth > 14) return 0
            var removed = 0
            try {
                val snapshot = ArrayList<Any?>()
                for (v in map.values()) snapshot.add(v)
                for (v in snapshot) {
                    if (v is java.util.List<*>) {
                        val list = v as java.util.List<Any?>
                        if (looksLikeToolListOf(list)) {
                            list.clear()
                            removed++
                            continue
                        }
                        for (item in list) removed += pruneMapItem(item, depth + 1)
                    } else if (v is java.util.Map<*, *>) {
                        removed += pruneMap(v, depth + 1)
                    } else if (v is String) {
                        val c = firstJsonChar(v)
                        if (c == '{' || c == '[') {
                            val root: Any = if (c == '{') org.json.JSONObject(v) else org.json.JSONArray(v)
                            val n = pruneToolArrays(root, depth + 1)
                            if (n > 0) {
                                val k = findKey(map, v)
                                if (k != null) (map as java.util.Map<Any?, Any?>).put(k, root.toString())
                                removed += n
                            }
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
            return removed
        }

        private fun pruneMapItem(item: Any?, depth: Int): Int {
            if (item is java.util.Map<*, *>) return pruneMap(item, depth)
            return 0
        }

        private fun findKey(map: java.util.Map<*, *>, value: Any?): Any? {
            for (e in map.entrySet()) if (e.value === value) return e.key
            return null
        }

        private fun looksLikeToolListOf(list: java.util.List<Any?>?): Boolean {
            if (list == null || list.size < 4 || list.size > 14) return false
            var ok = 0
            for (item in list) {
                if (item is java.util.Map<*, *>) {
                    if (item.containsKey("id") && (item.containsKey("schema") || item.containsKey("iconUrl") ||
                                    item.containsKey("icon") || item.containsKey("imageUrl"))) ok++
                }
            }
            return ok >= list.size - 1
        }

        private fun looksLikeToolList(arr: org.json.JSONArray): Boolean {
            val n = arr.length()
            if (n < 4 || n > 14) return false
            var ok = 0
            for (i in 0 until n) {
                val o = arr.opt(i)
                if (o !is org.json.JSONObject) return false
                val hasId = o.has("id") || o.has("toolId") || o.has("tool_id")
                val hasIcon = o.has("schema") || o.has("iconUrl") || o.has("icon") ||
                        o.has("imageUrl")
                if (hasId && hasIcon) ok++
            }
            return ok >= n - 1
        }

        private fun hookAjxLoad(cl: ClassLoader) {
            val ajx = H.cls(cl, "com.autonavi.minimap.ajx3.views.AmapAjxView") ?: return
            H.hookSig(ajx, "load", "amap_search_grid_load", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val path = chain.getArg(0)
                        if (path is String && path.contains(PAGE_HINT)) {
                            AmapData.markSearchPage(4000)   // 4s 窗口：这段时间内构建的 toolbox payload 跳过金刚位
                            val self = chain.getThisObject()
                            if (self is View) schedule(self)
                        }
                    } catch (ignored: Throwable) {
                    }
                    return result
                }
            }, String::class.java, Any::class.java, String::class.java, String::class.java,
                    Integer.TYPE, Integer.TYPE, String::class.java)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=ajx_load")
        }

        private fun schedule(page: View) {
            if (!VIEW_FALLBACK) return      // 数据层已根治，不再事后摘容器（否则会闪一下）
            synchronized(DONE) {
                if (DONE.containsKey(page)) return
            }
            for (i in PASS_DELAY_MS.indices) {
                val pass = i
                try {
                    page.postDelayed(object : Runnable {
                        override fun run() {
                            attempt(page, pass)
                        }
                    }, PASS_DELAY_MS[i])
                } catch (ignored: Throwable) {
                    return
                }
            }
        }

        private fun attempt(page: View, pass: Int) {
            if (!off()) return
            synchronized(DONE) {
                if (DONE.containsKey(page)) return
            }
            try {
                val rows = ArrayList<View>()
                collectRows(page, rows, 0)
                if (rows.isEmpty()) {
                    if (pass == PASS_DELAY_MS.size - 1) {
                        H.log(Log.WARN, MainHook.TAG, "event=search_grid_fail reason=no_container pass=" + pass)
                    }
                    return
                }
                val row = topmost(rows)!!
                val block = absorbIndicator(row, page)
                val cells = if (row is ViewGroup) row.childCount else 0
                removeBlock(page, block, cells, pass)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_error pass=" + pass + " err=" + t)
            }
        }

        private fun collectRows(v: View?, out: MutableList<View>, depth: Int) {
            if (v == null || depth > 26 || out.size > 40) return
            try {
                if (v.visibility != View.VISIBLE) return
                if (v is ViewGroup && isIconRow(v)) out.add(v)
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) collectRows(v.getChildAt(i), out, depth + 1)
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun isIconRow(g: ViewGroup): Boolean {
            val n = g.childCount
            if (n < MIN_CELLS || n > MAX_CELLS) return false
            val rowH = g.height
            if (rowH < dp(g, ROW_MIN_DP) || rowH > dp(g, ROW_MAX_DP)) return false

            var sumW = 0
            var laid = 0
            var top = Int.MIN_VALUE
            var prevLeft = Int.MIN_VALUE
            var minW = Int.MAX_VALUE
            var maxW = 0
            for (i in 0 until n) {
                val c = g.getChildAt(i)
                if (c == null || c.visibility != View.VISIBLE) return false
                val w = c.width
                val h = c.height
                if (w <= 0 || h <= 0) return false
                if (top == Int.MIN_VALUE) top = c.top
                if (Math.abs(c.top - top) > dp(g, 8)) return false
                if (c.left <= prevLeft) return false
                prevLeft = c.left
                sumW += w
                laid++
                if (w < minW) minW = w
                if (w > maxW) maxW = w
            }
            if (laid < MIN_CELLS) return false
            val mean = sumW / laid.toFloat()
            return (maxW - minW) <= mean * WIDTH_TOLERANCE
        }

        private fun topmost(rows: List<View>): View? {
            var best: View? = null
            var bestTop = Int.MAX_VALUE
            for (v in rows) {
                val t = absTop(v)
                if (t < bestTop) {
                    bestTop = t
                    best = v
                }
            }
            return best
        }

        private fun absTop(v: View): Int {
            var y = 0
            var cur: View? = v
            while (cur != null) {
                y += cur.top
                val p: ViewParent? = cur.parent
                cur = if (p is View) p else null
                if (cur == null || y > 100000) break
            }
            return y
        }

        /**
         * 把外层「行 + 分页指示器」包起来的容器作为删除目标。
         *
         * <p>实测结构（真机 dump）：
         * <pre>
         *   Container 1017x223 c=2        ← 目标：行(184) + 指示器(39)
         *     Container 1016x184 c=1      ← 单子包装
         *       Container 1016x184 c=6    ← 6 个彩色方块（本函数入参 row）
         * </pre>
         * 规则：先穿过「只有一个可见子节点」的包装层（最多 4 层），
         * 再判断这一层除本行外的兄弟是否都是矮子（≤30dp）且总高不超过本行 + 60dp
         * ⇒ 这一层就是「行 + 指示器」，整块删它，指示器不会残留半条。
         */
        private fun absorbIndicator(row: View, page: View): View {
            var cur = row
            try {
                for (level in 0 until 4) {
                    val p: ViewParent? = cur.parent
                    if (p !is ViewGroup || p === page) break
                    val pg = p
                    var visible = 0
                    var only: View? = null
                    for (i in 0 until pg.childCount) {
                        val c = pg.getChildAt(i)
                        if (c.visibility != View.VISIBLE) continue
                        visible++
                        only = c
                    }
                    if (visible == 1 && only === cur &&
                            Math.abs(pg.height - cur.height) <= dp(cur, 4)) {
                        cur = pg                       // 单子包装层，继续往上
                        continue
                    }
                    // 这一层：判断是不是「行 + 矮指示器」
                    var maxOther = 0
                    for (i in 0 until pg.childCount) {
                        val c = pg.getChildAt(i)
                        if (c === cur || c.visibility != View.VISIBLE) continue
                        maxOther = Math.max(maxOther, c.height)
                    }
                    if (visible <= 3 && maxOther > 0 && maxOther <= dp(cur, INDICATOR_MAX_DP) &&
                            pg.height <= cur.height + dp(cur, 60)) {
                        return pg
                    }
                    break
                }
            } catch (ignored: Throwable) {
            }
            return cur
        }

        /** 容器级移除：属性尝试链 → 最后从父容器摘掉。全程实测高度，不做遮挡。 */
        private fun removeBlock(page: View, block: View, cells: Int, pass: Int) {
            val node = desc(block)
            if (block.height == 0) {
                done(page, "already_zero", node, cells, pass)
                return
            }
            tryNext(page, block, cells, pass, node, 0)
        }

        private fun tryNext(page: View, block: View, cells: Int, pass: Int, node: String, idx: Int) {
            if (block.height == 0) {
                done(page, if (idx == 0) "already_zero" else ATTRS[idx - 1][0] + "=" + ATTRS[idx - 1][1],
                        node, cells, pass)
                return
            }
            if (idx >= ATTRS.size) {
                // 逐层打印祖先高度（自证哪一层还占着位），并把仍然占位的单子包装层一起归零
                var up: ViewParent? = block.parent
                var lv = 0
                while (lv < 4) {
                    val pv = up as? View ?: break
                    val h = pv.height
                    val cc = if (pv is ViewGroup) pv.childCount else -1
                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_height lv=" + lv +
                            " node=" + desc(pv) + " h=" + h)
                    if (h > 0 && cc <= 2) setAttr(pv, "display", "none")
                    up = pv.parent
                    lv++
                }
                if (detach(block)) {
                    done(page, "container_remove", node, cells, pass)
                    return
                }
                if (pass == PASS_DELAY_MS.size - 1) {
                    H.log(Log.WARN, MainHook.TAG, "event=search_grid_fail reason=no_effect node=" +
                            node + " cells=" + cells + " h=" + block.height)
                }
                return
            }
            setAttr(block, ATTRS[idx][0], ATTRS[idx][1])
            block.postDelayed(object : Runnable {
                override fun run() {
                    tryNext(page, block, cells, pass, node, idx + 1)
                }
            }, VERIFY_MS)
        }

        private fun detach(block: View): Boolean {
            try {
                val p: ViewParent? = block.parent
                if (p !is ViewGroup) return false
                p.removeView(block)
                p.requestLayout()
                p.invalidate()
                return block.parent == null
            } catch (t: Throwable) {
                return false
            }
        }

        private fun done(page: View, via: String, node: String, cells: Int, pass: Int) {
            synchronized(DONE) {
                DONE.put(page, true)
            }
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=" + via +
                    " node=" + node + " cells=" + cells + " pass=" + pass)
        }

        private fun desc(v: View): String {
            try {
                val sb = StringBuilder(v.javaClass.simpleName)
                sb.append(' ').append(v.width).append('x').append(v.height)
                if (v is ViewGroup) sb.append(" c=").append(v.childCount)
                sb.append(" t=").append(absTop(v))
                return sb.toString()
            } catch (t: Throwable) {
                return "?"
            }
        }

        // ══════════════════════════════════════════ AJX 属性入口

        /**
         * 走 AJX DOM 节点改属性：{@code AjxDomTree.i(View)J} → {@code f(J)Lvr;} → {@code Lvr.B(name,value,Z,Z,Z)}。
         * DEX 实证 {@code Lvr.B} 体内先调 {@code ViewExtension.setAttribute(...)} 再 {@code s(...)} 把属性
         * 缓存进节点 —— 只调 setAttribute 不更新节点，引擎下一帧仍按旧值重排（实测“打了没反应”）。
         */
        private fun nodeSetAttr(v: View, name: String, value: Any?): Boolean {
            try {
                val tree = Class.forName("com.autonavi.minimap.ajx3.dom.AjxDomTree",
                        false, v.javaClass.classLoader)
                var idM: Method? = null
                for (m in tree.declaredMethods) {
                    if (m.name == "i" && m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] === View::class.java &&
                            m.returnType === java.lang.Long.TYPE) {
                        idM = m
                        break
                    }
                }
                if (idM == null) return false
                idM.isAccessible = true
                val nodeId = idM.invoke(null, v) as Long
                if (nodeId == 0L) return false
                val ctx: Any? = v.context
                if (ctx == null) return false
                var treeObj: Any? = null
                try {
                    treeObj = ctx.javaClass.getMethod("getDomTree").invoke(ctx)
                } catch (ignored: Throwable) {
                }
                if (treeObj == null) return false
                var node: Any? = null
                for (m in treeObj.javaClass.methods) {
                    if (m.name != "f" || m.parameterTypes.size != 1 ||
                            m.parameterTypes[0] !== java.lang.Long.TYPE) continue
                    m.isAccessible = true
                    node = m.invoke(treeObj, nodeId)
                    break
                }
                if (node == null) return false
                for (m in node.javaClass.methods) {
                    if (m.name != "B") continue
                    val p = m.parameterTypes
                    if (p.size != 5 || p[0] !== String::class.java || p[1] !== Any::class.java) continue
                    m.isAccessible = true
                    m.invoke(node, name, value, java.lang.Boolean.TRUE, java.lang.Boolean.TRUE, java.lang.Boolean.TRUE)
                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name + " via=dom_node")
                    return true
                }
            } catch (ignored: Throwable) {
            }
            return false
        }

        /**
         * v5：正确的 AJX 节点入口。
         *
         * <p>上一版从 {@code v.getContext()} 取 DomTree —— 那是 **Activity Context**，
         * 根本没有 {@code getDomTree()}，所以整条节点路线静默失败（日志里从没出现 via=dom_node）。
         * 正确来源是 AJX 自己的 {@code IAjxContext}：从视图树往上找到第一个带
         * {@code getAjxContext()} 的视图（就是 AmapAjxView），再 {@code getDomTree()}。
         * 拿到 DomTree 后用 {@code i(View)J} → {@code f(J)Lvr;} → {@code Lvr.B(name,value,Z,Z,Z)}。
         */
        private fun nodeSetAttr2(v: View, name: String, value: Any?): Boolean {
            try {
                val treeObj = ajxTreeOf(v) ?: return false
                val tree: Class<*> = treeObj.javaClass
                var idM: Method? = null
                for (m in tree.methods) {
                    if (m.name == "i" && m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] === View::class.java &&
                            m.returnType === java.lang.Long.TYPE) {
                        idM = m
                        break
                    }
                }
                if (idM == null) return false
                idM.isAccessible = true
                val nodeId = idM.invoke(treeObj, v) as Long
                if (nodeId == 0L) return false
                var node: Any? = null
                for (m in tree.methods) {
                    if (m.name != "f" || m.parameterTypes.size != 1 ||
                            m.parameterTypes[0] !== java.lang.Long.TYPE) continue
                    m.isAccessible = true
                    node = m.invoke(treeObj, nodeId)
                    break
                }
                if (node == null) return false
                for (m in node.javaClass.methods) {
                    if (m.name != "B") continue
                    val p = m.parameterTypes
                    if (p.size != 5 || p[0] !== String::class.java || p[1] !== Any::class.java) continue
                    m.isAccessible = true
                    m.invoke(node, name, value, java.lang.Boolean.TRUE, java.lang.Boolean.TRUE, java.lang.Boolean.TRUE)
                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name +
                            " via=dom_node node=" + nodeId)
                    return true
                }
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_node_err " + t)
            }
            return false
        }

        /** 从视图树往上找 AmapAjxView 的 IAjxContext → getDomTree()。 */
        private fun ajxTreeOf(v: View): Any? {
            var cur: View? = v
            var i = 0
            while (cur != null && i < 12) {
                try {
                    val g = cur.javaClass.getMethod("getAjxContext")
                    val ctx = g.invoke(cur)
                    if (ctx != null) {
                        val tree = ctx.javaClass.getMethod("getDomTree").invoke(ctx)
                        if (tree != null) return tree
                    }
                } catch (ignored: Throwable) {
                }
                val p: ViewParent? = cur.parent
                cur = if (p is View) p else null
                i++
            }
            return null
        }

        private fun setAttr(v: View, name: String, value: Any?) {
            if (nodeSetAttr2(v, name, value)) return
            if (nodeSetAttr(v, name, value)) return
            var k: Class<*>? = v.javaClass
            while (k != null && k !== Any::class.java) {
                for (iface in allTypes(k)) {
                    var m = findSetAttribute(iface, 6)
                    if (m != null) {
                        try {
                            m.isAccessible = true
                            m.invoke(v, name, value, java.lang.Boolean.TRUE, java.lang.Boolean.TRUE,
                                    java.lang.Boolean.TRUE, java.lang.Boolean.TRUE)
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name +
                                    " via=" + iface.simpleName + "/6")
                            return
                        } catch (ignored: Throwable) {
                        }
                    }
                    m = findSetAttribute(iface, 3)
                    if (m != null) {
                        try {
                            m.isAccessible = true
                            m.invoke(v, name, value, java.lang.Boolean.TRUE)
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_attr name=" + name +
                                    " via=" + iface.simpleName + "/3")
                            return
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                k = k.superclass
            }
            H.log(Log.WARN, MainHook.TAG, "event=search_grid_attr_miss name=" + name +
                    " cls=" + v.javaClass.name)
        }

        private fun findSetAttribute(type: Class<*>, arity: Int): Method? {
            try {
                for (m in type.methods) {
                    if ("setAttribute" != m.name) continue
                    val p = m.parameterTypes
                    if (arity == 6 && p.size == 6 && p[0] === String::class.java && p[1] === Any::class.java &&
                            p[2] === java.lang.Boolean.TYPE && p[3] === java.lang.Boolean.TYPE &&
                            p[4] === java.lang.Boolean.TYPE && p[5] === java.lang.Boolean.TYPE) return m
                    if (arity == 3 && p.size == 3 && p[0] === String::class.java && p[1] === Any::class.java &&
                            p[2] === java.lang.Boolean.TYPE) return m
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        private fun allTypes(k: Class<*>): List<Class<*>> {
            val out = ArrayList<Class<*>>()
            var c: Class<*>? = k
            while (c != null && c !== Any::class.java) {
                for (i in c.interfaces) {
                    out.add(i)
                    for (j in i.interfaces) out.add(j)
                }
                c = c.superclass
            }
            return out
        }

        private fun dp(v: View, value: Int): Int {
            return try {
                (value * v.resources.displayMetrics.density).toInt()
            } catch (t: Throwable) {
                value * 3
            }
        }

        // ══════════════════════════════════════════ 探针

        // ══════════════════════════════════════════ 取证：AJX 本地存储（找金刚位配置的 module/key）
        //
        // 为什么只钩这些：`getItemSync` / `getItemASYNC` 已被 AmapProtocolHooks.installTools 占用
        // （toolbox 过滤，nav-fix 写域），本类**不碰**，避免同方法多钩把别人的功能顶掉。
        // 写入侧 + 批量读 + 加密读已经足够定位「那排配置存在哪个 module 的哪个 key」。

        private val LS_SEEN: MutableSet<String> =
                java.util.Collections.synchronizedSet(java.util.HashSet())
        private const val LS_LOG_MAX = 80
        private var lsCount = 0

        private fun hookLsProbe(cl: ClassLoader) {
            val store = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage")
            val cb = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback")
            if (store == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=AjxModuleLocalStorage")
                return
            }
            // 写入侧：配置被缓存进存储的瞬间
            H.hookSig(store, "setItemSync", "amap_search_grid_ls_set", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        lsLog("setItemSync", chain.getArg(0), chain.getArg(1), chain.getArg(2))
                    } catch (ignored: Throwable) {
                    }
                    return chain.proceed()
                }
            }, String::class.java, String::class.java, Any::class.java)
            // 批量读（同步）：整个桶都回来了，最可能直接看到那排配置
            H.hookSig(store, "getAllItemsEncryptedSync", "amap_search_grid_ls_allenc", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        lsLog("getAllItemsEncryptedSync", chain.getArg(0), "-", r)
                    } catch (ignored: Throwable) {
                    }
                    return r
                }
            }, String::class.java)
            // 模块是否存在（用来枚举页面到底碰了哪些 module）
            H.hookSig(store, "preferenceExist", "amap_search_grid_ls_exist", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        lsLog("preferenceExist", chain.getArg(0), "-", r)
                    } catch (ignored: Throwable) {
                    }
                    return r
                }
            }, String::class.java)
            if (cb != null) {
                H.hookSig(store, "getItem", "amap_search_grid_ls_get", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            lsLog("getItem", chain.getArg(0), chain.getArg(1), null)
                        } catch (ignored: Throwable) {
                        }
                        return chain.proceed()
                    }
                }, String::class.java, String::class.java, cb)
                H.hookSig(store, "getItemEncrypted", "amap_search_grid_ls_getenc", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            lsLog("getItemEncrypted", chain.getArg(0), chain.getArg(1), null)
                        } catch (ignored: Throwable) {
                        }
                        return chain.proceed()
                    }
                }, String::class.java, String::class.java, cb)
                H.hookSig(store, "getAllItems", "amap_search_grid_ls_all", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            lsLog("getAllItems", chain.getArg(0), "-", null)
                        } catch (ignored: Throwable) {
                        }
                        return chain.proceed()
                    }
                }, String::class.java, cb)
            }
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=ls_probe")
        }

        /** 一行一条，op+module+key 去重，总量封顶，避免刷爆 logcat。 */
        private fun lsLog(op: String, module: Any?, key: Any?, value: Any?) {
            if (lsCount >= LS_LOG_MAX) return
            val m = if (module == null) "null" else module.toString()
            val k = if (key == null) "null" else key.toString()
            if (!LS_SEEN.add(op + "|" + m + "|" + k)) return
            synchronized(this) {
                if (lsCount >= LS_LOG_MAX) return
                lsCount++
            }
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_ls op=" + op + " module=" + m +
                    " key=" + k + " len=" + (if (value == null) -1 else value.toString().length) +
                    " head=" + lsHead(value))
        }

        /** 值的头部片段（200 字，换行压成空格），足够看清 JSON 结构。 */
        private fun lsHead(value: Any?): String {
            if (value == null) return "null"
            val s: String
            try {
                s = value.toString()
            } catch (t: Throwable) {
                return "?"
            }
            val flat = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            return if (flat.length <= 200) flat else flat.substring(0, 200) + "…"
        }

        // ══════════════════════════════════════════ 读侧：AJX 存储 helper（真正的剔除点）
        //
        // 取证结论：搜索页配置的存储 module = `cachedSearchHomeData`（preferenceExist 命中）。
        // `AjxModuleLocalStorage.getItemSync` 被 toolbox 占用，但它的底层实现是
        // `Lwm3;->c(String,String)String`（DEX: classes5.dex code_off=0x4057c0，helper 的单键读），
        // 这个类没有任何其它模块碰过 ⇒ 在这里过滤，既不抢别人的钩子，也拿到同一份数据。

        private val HELPER_SEEN: MutableSet<String> =
                java.util.Collections.synchronizedSet(java.util.HashSet())
        private var helperLogs = 0

        private fun hookStorageHelper(cl: ClassLoader) {
            // 混淆名会重排：国内 wm3.c，Play 17.02.2.2014 = lv3.c（候选表 AmapData.PREFS_HELPER）。
            val helperAt = arrayOfNulls<String>(2)
            val helper = H.anchor(cl, AmapData.PREFS_HELPER as Array<Array<String>?>, helperAt)
            if (helper == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=prefs_helper")
                return
            }
            // 单键读：c(String key, String def) -> String
            H.hookSig(helper, helperAt[1]!!, "amap_search_grid_helper_get", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (r !is String) return r
                    val value = r
                    try {
                        val module = moduleOf(chain.getThisObject())
                        if (helperLogs < 40 && HELPER_SEEN.add(module + "|" + chain.getArg(0))) {
                            helperLogs++
                            H.logv("event=search_grid_helper op=get module=" + module +
                                    " key=" + chain.getArg(0) + " len=" + value.length +
                                    " head=" + lsHead(value))
                        }
                        if (!off()) return r
                        if (module != null && !module.toLowerCase().contains("searchhome")) return r
                        if (value.toLowerCase().contains("toolbox")) return r
                        val filtered = pruneSearchHome(value) ?: return r
                        H.log(Log.INFO, MainHook.TAG, "event=search_grid_removed via=data_strip key=" +
                                chain.getArg(0))
                        return filtered
                    } catch (t: Throwable) {
                        return r
                    }
                }
            }, String::class.java, String::class.java)
            // 整桶读：b() -> HashMap（同一条数据源的批量路径）
            H.hookSig(helper, "b", "amap_search_grid_helper_all", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        val module = moduleOf(chain.getThisObject())
                        if (helperLogs < 40 && HELPER_SEEN.add(module + "|ALL")) {
                            helperLogs++
                            H.logv("event=search_grid_helper op=all module=" + module +
                                    " keys=" + (if (r is java.util.Map<*, *>) r.keySet() else "?"))
                        }
                    } catch (ignored: Throwable) {
                    }
                    return r
                }
            })
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=storage_helper")
        }

        /** 反射读 helper 实例上的 module 名（wm3 只有 2 个字段，其中一个就是它）。 */
        private fun moduleOf(helper: Any?): String {
            if (helper == null) return "null"
            try {
                for (f in helper.javaClass.declaredFields) {
                    if (f.type !== String::class.java) continue
                    f.isAccessible = true
                    val v = f.get(helper)
                    if (v is String) return v
                }
            } catch (ignored: Throwable) {
            }
            return "?"
        }

        // ══════════════════════════════════════════ JS 取证：把 AJX 页面的 JS 源码落盘
        //
        // APK 里的 `assets/ajx.bundle/bundles.oajx`(74MB) 与设备缓存
        // `files/ajx-biz/local_bundles_map.bin` 都是加密的（熵 7.98、无明文 bundle 名），
        // 静态取不到。唯一确定性入口 = 运行时把喂给引擎的 JS 源码抓下来。
        // `AmapAjxView.loadJs(ng3)` 是 bundle 对象进入渲染前的落点（DEX code_off=0x4aca2c）。

        private var jsDumpCount = 0

        private fun hookJsDump(cl: ClassLoader) {
            val ajx = H.cls(cl, "com.autonavi.minimap.ajx3.views.AmapAjxView")
            val bundle = H.cls(cl, "ng3")
            if (ajx == null || bundle == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=loadJs/ng3")
                return
            }
            H.hookSig(ajx, "loadJs", "amap_search_grid_jsdump", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val self = chain.getThisObject()
                        if (self is View && jsDumpCount < 6) {
                            val b = chain.getArg(0)
                            val src = biggestString(b!!)
                            if (src != null && src.length > 500) {
                                jsDumpCount++
                                writeDump(self, "ajx_dump_" + jsDumpCount + ".js", src)
                                H.log(Log.INFO, MainHook.TAG, "event=search_grid_jsdump n=" + jsDumpCount +
                                        " len=" + src.length + " cls=" + b.javaClass.simpleName)
                            }
                        }
                    } catch (t: Throwable) {
                        H.log(Log.WARN, MainHook.TAG, "event=search_grid_jsdump_err " + t)
                    }
                    return chain.proceed()
                }
            }, bundle)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=js_dump")
        }

        /** 反射取对象里最长的 String 字段（bundle 的 JS 源码就在其中）。 */
        private fun biggestString(obj: Any): String? {
            var best: String? = null
            var k: Class<*>? = obj.javaClass
            while (k != null && k !== Any::class.java) {
                for (f in k.declaredFields) {
                    if (f.type !== String::class.java) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v !is String) continue
                        if (best == null || v.length > best.length) best = v
                    } catch (ignored: Throwable) {
                    }
                }
                k = k.superclass
            }
            return best
        }

        private fun writeDump(v: View, name: String, src: String) {
            try {
                val dir = v.context.filesDir
                val out = java.io.File(dir, name)
                val fos = java.io.FileOutputStream(out)
                fos.write(src.toByteArray(charset("UTF-8")))
                fos.close()
                H.log(Log.INFO, MainHook.TAG, "event=search_grid_jsdump_path " + out.absolutePath)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_jsdump_write_err " + t)
            }
        }

        // ══════════════════════════════════════════ 根治点：cachedSearchHomeData.QuickLinkVODataV2-internal
        //
        // 取证（Lead 在设备上挖穿私有目录）：搜索页那排金刚位的唯一数据源是
        //   /data/data/com.autonavi.minimap/shared_prefs/cachedSearchHomeData.xml
        //   key = QuickLinkVODataV2-internal
        //   {"quickLinkVoData":{"list":[20 项 {title,icon,schema,badge,log_param,…}]}, "cacheVerification":{…}}
        // 20 个 title = 美食/酒店/景点门票/加油充电/超划算/扫街榜/充电站/厕所/…（图标全来自 optimus-ads.amap.com
        // ⇒ 这排是广告位）。此前所有「toolbox / AJX storage / 网络响应」假设因此全部作废。
        //
        // 落点：SharedPreferencesImpl.getString —— 框架层读接口，与 nav-fix 的
        // AjxModuleLocalStorage 钩子零冲突。
        //
        // 手法：**字符串级手术**，不做 JSON 往返 —— 该值里含 HTML 转义（&quot;）和
        // 大量 log_param/__trackInfo__ 字段，往返序列化会改变字节、可能让高德的缓存校验失败。
        // 这里只把 "list":[ … ] 的内容替换成空，其余字节原样保留。

        private const val SP_KEY = "QuickLinkVODataV2-internal"
        private val STRIP_LOGGED: MutableSet<String> =
                java.util.Collections.synchronizedSet(java.util.HashSet())

        // ══════════════════════════════════════════ 写侧探针：谁把 quickLink 写进缓存
        //
        // `cachedSearchHomeData.xml` 是服务端写进来的（mtime 随刷新变化）。
        // 写入路径：Editor.putString → EditorImpl.mMap → commit/apply → SharedPreferencesImpl。
        // 这里钩 EditorImpl.putString，命中 key 时打印长度 + 调用栈 ⇒ 直接指出发起该请求的代码。

        private var spWriteLogs = 0

        private fun hookSpWriter(cl: ClassLoader) {
            val editor = H.cls(cl, "android.app.SharedPreferencesImpl\$EditorImpl")
            if (editor == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=EditorImpl")
                return
            }
            H.hookSig(editor, "putString", "amap_search_grid_spwrite", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val key = chain.getArg(0)
                        val `val` = chain.getArg(1)
                        if (SP_KEY == key && spWriteLogs < 3) {
                            spWriteLogs++
                            H.log(Log.INFO, MainHook.TAG, "event=search_grid_spwrite key=" + key +
                                    " len=" + (if (`val` == null) -1 else `val`.toString().length) +
                                    " stack=" + stack())
                        }
                    } catch (ignored: Throwable) {
                    }
                    return chain.proceed()
                }
            }, String::class.java, String::class.java)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=sp_writer")
        }

        /** 调用栈前 12 帧（过滤本模块帧，只留业务/框架）。 */
        private fun stack(): String {
            try {
                val st = Throwable().stackTrace
                val sb = StringBuilder()
                var n = 0
                for (e in st) {
                    val c = e.className
                    if (c.startsWith("io.github.ldxm666")) continue
                    if (c.startsWith("java.lang.Thread")) continue
                    if (c.startsWith("io.github.libxposed")) continue
                    sb.append(c).append('#').append(e.methodName).append(' ')
                    if (++n >= 12) break
                }
                return sb.toString()
            } catch (t: Throwable) {
                return "?"
            }
        }

        // ══════════════════════════════════════════ 写侧根治：AJX 存储写入时把 quickLinkVoData 清空
        //
        // 真机调用栈实证（2026-10-26 00:xx）：
        //   wm3#f → AjxModuleLocalStorage#setItemInner → #setItemASYNC → #handleMessage → HandlerThread
        // ⇒ 这份缓存是 **JS 侧** 调 `ajxLocalStorage.setItem('cachedSearchHomeData',
        //   'QuickLinkVODataV2-internal', json)` 写进来的，值由 JS 从服务端响应里拿到。
        // 所以在 setItemASYNC 上把它换成 `quickLinkVoData:{}`（list 变 undefined），
        // 缓存里就永远没有那 20 项，页面按「服务端没下发」处理。

        private const val LS_MODULE_SEARCH_HOME = "cachedSearchHomeData"
        private var writeStripLogs = 0

        private fun hookStorageWrite(cl: ClassLoader) {
            val store = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage")
            val cb = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback")
            if (store == null || cb == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=setItemASYNC")
                return
            }
            H.hookSig(store, "setItemASYNC", "amap_search_grid_lswrite", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val module = chain.getArg(0)
                        val key = chain.getArg(1)
                        val v = chain.getArg(2)
                        if (LS_MODULE_SEARCH_HOME == module.toString() &&
                                SP_KEY == key.toString() &&
                                v is String &&
                                WRITE_STRIP && !Config.searchGrid()) {
                            val stripped = emptyQuickLinkField(v)
                            if (stripped != null) {
                                val args = chain.getArgs().toTypedArray()
                                args[2] = stripped
                                if (writeStripLogs++ < 3) {
                                    H.log(Log.INFO, MainHook.TAG, "event=search_grid_write_strip" +
                                            " module=" + module + " key=" + key +
                                            " len=" + v.length + " -> " + stripped.length)
                                }
                                return chain.proceed(args)
                            }
                        }
                    } catch (t: Throwable) {
                        H.log(Log.WARN, MainHook.TAG, "event=search_grid_write_strip_err " + t)
                    }
                    return chain.proceed()
                }
            }, String::class.java, String::class.java, Any::class.java, cb)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=storage_write")
        }

        /**
         * 把 {@code "quickLinkVoData":{...}} 换成 {@code "quickLinkVoData":{}} —— 让 {@code list} 变 undefined。
         * 字符串级手术，其余字节一个不动（该值含 {@code &quot;} 转义与大量 log_param 字段，
         * JSON 往返会改字节、可能让高德缓存校验失败）。
         */
        private fun emptyQuickLinkField(raw: String?): String? {
            if (raw == null) return null
            var p = raw.indexOf("\"quickLink\"")
            if (p < 0) p = raw.indexOf("quickLinkVoData")
            if (p < 0) {
                stripLog("skip", "no_field", -1)
                return null
            }
            val open = raw.indexOf('{', p)
            if (open < 0) {
                stripLog("skip", "no_brace", -1)
                return null
            }
            val close = matchBrace(raw, open)
            if (close < 0) {
                stripLog("skip", "no_close_brace", -1)
                return null
            }
            val items = countOccurrences(raw.substring(open, close), "\"title\"")
            val out = raw.substring(0, open + 1) + raw.substring(close)
            stripLog("done", "ok", items)
            return out
        }

        /** 从 open（指向 '{'）找到配对的 '}'，跳过字符串与转义。 */
        private fun matchBrace(s: String, open: Int): Int {
            var depth = 0
            var inStr = false
            var i = open
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') inStr = false
                    i++
                    continue
                }
                if (c == '"') {
                    inStr = true
                    i++
                    continue
                }
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        private fun hookSharedPrefs(cl: ClassLoader) {
            hookStorageWrite(cl)
            hookSpWriter(cl)
            val sp = H.cls(cl, "android.app.SharedPreferencesImpl")
            if (sp == null) {
                H.log(Log.WARN, MainHook.TAG, "event=search_grid_miss what=SharedPreferencesImpl")
                return
            }
            H.hookSig(sp, "getString", "amap_search_grid_sp", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (r !is String) return r
                    try {
                        if (SP_KEY != chain.getArg(0)) return r
                        if (true) {   // ★ 读侧已撤销：实测「清空/返回 null」会让页面回落到 JS 预载包默认 6 项（另一批广告），有害 ⇒ 一律透传
                            stripLog("skip", "switch_on", -1)
                            return r
                        }
                        // ★ 变体 B（实测）：**不返回空 list，而是让整个 key「不存在」**。
                        // 原因：把 list 清空会让 AJX 页回落到它预载包里写死的默认 6 项
                        //       （美食/酒店/景点门票/加油站/休闲玩乐/超市）—— 那同样是广告位，
                        //       等于换了另一批广告，比不清更糟。
                        // 返回 null = 页面认为「没有缓存」，不给它任何可回落的空数组。
                        stripLog("done", "return_null", countOccurrences(r, "\"title\""))
                        return null
                    } catch (t: Throwable) {
                        stripLog("skip", "error", -1)
                        return r
                    }
                }
            }, String::class.java, String::class.java)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=shared_prefs")
        }

        /**
         * 把 {@code "list":[ … ]} 的内容清空，其余字节一个不动。
         * 解析失败/结构不符 ⇒ 返回 null（调用方原样返回，绝不破坏缓存读取）。
         */
        private fun emptyQuickLinkList(raw: String?): String? {
            if (raw == null || raw.length < 32) return null
            val listKey = raw.indexOf("\"list\"")
            if (listKey < 0) {
                stripLog("skip", "no_list_key", -1)
                return null
            }
            val open = raw.indexOf('[', listKey)
            if (open < 0) {
                stripLog("skip", "no_open", -1)
                return null
            }
            val close = matchBracket(raw, open)
            if (close < 0) {
                stripLog("skip", "no_close", -1)
                return null
            }
            val body = raw.substring(open + 1, close)
            val items = countOccurrences(body, "\"title\"")
            if (items <= 0) {
                stripLog("skip", "empty_already", 0)
                return null
            }
            val out = raw.substring(0, open + 1) + raw.substring(close)
            stripLog("done", "ok", items)
            return out
        }

        /** 从 open（指向 '['）找到配对的 ']'，跳过字符串内的括号与转义。 */
        private fun matchBracket(s: String, open: Int): Int {
            var depth = 0
            var inStr = false
            var i = open
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') inStr = false
                    i++
                    continue
                }
                if (c == '"') {
                    inStr = true
                    i++
                    continue
                }
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        private fun countOccurrences(hay: String, needle: String): Int {
            var n = 0
            var i = 0
            while (true) {
                i = hay.indexOf(needle, i)
                if (i < 0) break
                n++
                i += needle.length
            }
            return n
        }

        private fun stripLog(what: String, reason: String, items: Int) {
            if (!STRIP_LOGGED.add(what + "|" + reason + "|" + items)) return
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_strip src=sp key=" + SP_KEY +
                    " " + (if (items >= 0) ("items=" + items + " -> 0") else ("skip reason=" + reason)))
        }

        private fun hookRequestProbe(cl: ClassLoader) {
            hookStorageHelper(cl)
            val req = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest") ?: return
            val cb = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback")
            H.hookSig(req, "fetch", "amap_search_grid_fetch", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val opt = chain.getArg(1)
                        if (opt is String) {
                            val o = opt
                            val u = o.indexOf("\"url\"")
                            var pick = o
                            if (u >= 0) pick = o.substring(u, Math.min(o.length, u + 180))
                            if (SEEN_URLS.size < URL_LOG_MAX && SEEN_URLS.add(pick)) {
                                H.log(Log.INFO, MainHook.TAG, "event=ajx_fetch_opt " + pick.replace('\n', ' '))
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                    return chain.proceed()
                }
            }, String::class.java, String::class.java, cb)
            H.hookSig(req, "optionsToRequestInfo", "amap_search_grid_probe", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val url = chain.getArg(0)
                        if (url is String) noteUrl(url)
                    } catch (ignored: Throwable) {
                    }
                    return chain.proceed()
                }
            }, String::class.java, String::class.java)
            H.log(Log.INFO, MainHook.TAG, "event=search_grid_hook name=probe")
        }

        private fun noteUrl(url: String?) {
            if (url == null) return
            try {
                if (!isSearchHome(url)) {
                    // 取证期：把所有 amap 域名的 AJX 请求也记下来（封顶），用于找 quickLink 的来源
                    if (url.indexOf("amap.com") < 0) return
                    if (SEEN_URLS.size >= URL_LOG_MAX) return
                    val q = url.indexOf('?')
                    val base = if (q < 0) url else url.substring(0, q)
                    if (!SEEN_URLS.add(base)) return
                    H.log(Log.INFO, MainHook.TAG, "event=ajx_req_all url=" + base)
                    return
                }
                if (SEEN_URLS.size >= URL_LOG_MAX || !SEEN_URLS.add(url)) return
                H.log(Log.INFO, MainHook.TAG, "event=ajx_req url=" + url)
            } catch (ignored: Throwable) {
            }
        }
    }
}

