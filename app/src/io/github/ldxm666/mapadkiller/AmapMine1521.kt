package io.github.ldxm666.mapadkiller

import android.util.Log

import java.util.Collections
import java.util.WeakHashMap

import io.github.libxposed.api.XposedInterface

/**
 * 高德 15.21「我的」页数据源探针 —— <b>只打印，不过滤</b>。
 *
 * <h3>为什么单独一个类</h3>
 * 17.02 的「我的」页是 `/ws/shield/dsp/profile/index/nodefaasv3` 下发
 * `data.cardList[]`，用 `dataType` 区分区块（MineNewBEntranceCardV3/V4、
 * PopularActivitiesCardV4/V5、ContentCenterFeedCard…），过滤逻辑在
 * `AmapData.response()` 里。15.21 完全不是这一套：
 * <ul>
 *   <li>15.21 的 7 个 dex 里 <b>没有</b> `nodefaas`、`dsp/profile`、
 *       也没有任何一个 17.02 的 card 类型名；</li>
 *   <li>「我的」页是 AJX 页面 `path://amap_bundle_mine/src/pages/MinePage.page.js`；</li>
 *   <li>页面上的文案（互动专区/拆福袋/小德果园/每日签到/个性化导航）在 APK 的 dex、assets、
 *       oajx 里<b>全部搜不到</b> ⇒ 这些字是服务端下发的，不是本地资源。</li>
 * </ul>
 * 所以 15.21 必须重新找「哪个响应、哪些 key」。这个类的唯一职责就是把它们打印出来。
 *
 * <h3>怎么用</h3>
 * 在 `AmapHooks.install(ClassLoader)` 里加一行（本类不改 AmapHooks，避免并行冲突）：
 * <pre>
 *   feature("mine1521", new Runnable() { public void run() { AmapMine1521.install(cl); } });
 * </pre>
 * 然后打开高德 →「我的」页，抓 logcat：
 * <pre>
 *   adb logcat -d | grep -E 'mine1521'
 * </pre>
 *
 * <h3>会打印什么</h3>
 * <ul>
 *   <li>每一个 AJX 网络请求的 URL（全量，带 `url=` 前缀），mine/profile/user 相关的高亮；</li>
 *   <li>命中 URL 的响应体前 [SNIPPET] 字符；</li>
 *   <li>响应里出现 `cardList` / `dataType` / `entranceList` 时，
 *       把每个 card 的 `dataType` 逐个列出来 —— 这就是过滤要用的 key。</li>
 * </ul>
 */
object AmapMine1521 {

    private val TAG = MainHook.TAG
    private const val SNIPPET = 1200
    private const val MAX_URL_LOGS = 400

    /** 同一个回调对象 → 请求 URL，用于把响应配回请求。 */
    private val PENDING: MutableMap<Any, String> =
            Collections.synchronizedMap(WeakHashMap<Any, String>())

    @JvmStatic
    fun install(cl: ClassLoader) {
        val version = versionName(cl)
        if (version == null || !version.startsWith("15.21")) {
            H.log(Log.INFO, TAG, "event=mine1521 skip reason=version name=" + version)
            return
        }
        AmapData.IS_1521 = true
        var n = 0
        n += hookRequest(cl)
        n += hookStorage(cl)
        H.log(Log.INFO, TAG, "event=mine1521 install hooks=" + n + " version=" + version)
    }

    // ── AJX 网络层 ────────────────────────────────────────────────────────────────
    // 15.21 里这三个类名和 17.02 一致（classes5.dex），所以探针可以直接复用同一组签名。
    private fun hookRequest(cl: ClassLoader): Int {
        val callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback")
        val request = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest")
        if (request == null || callback == null) {
            H.log(Log.WARN, TAG, "event=mine1521 request_class_missing request=" + (request != null)
                    + " callback=" + (callback != null))
            return 0
        }
        var n = 0
        H.hookSig(request, "fetch", "mine1521_fetch", remember(), String::class.java, String::class.java, callback)
        n++
        H.hookSig(request, "sendHttp", "mine1521_http", remember(), String::class.java, org.json.JSONObject::class.java, callback)
        n++
        H.hookSig(request, "notifyJs", "mine1521_notifyjs", dump(), callback, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java)
        n++
        H.hookSig(request, "notifyResponse", "mine1521_notifyresp", dump(), callback, Int::class.javaPrimitiveType,
                org.json.JSONObject::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java)
        n++
        return n
    }

    /** 记下 url → callback，并把 URL 打出来。 */
    private fun remember(): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var logged = 0
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    val url = urlOf(chain.getArg(0), chain.getArg(1))
                    val cb = chain.getArgs().get(chain.getArgs().size - 1)
                    if (url != null && cb != null) PENDING.put(cb, url)
                    if (url != null && logged < MAX_URL_LOGS) {
                        logged++
                        val hot = hot(url)
                        if (hot || logged <= 40) {
                            H.log(Log.INFO, TAG, "event=mine1521 url" + (if (hot) "_hot" else "") + "=" + url)
                        }
                    }
                } catch (t: Throwable) {
                    H.log(Log.WARN, TAG, "event=mine1521 url_error err=" + t)
                }
                return chain.proceed()
            }
        }
    }

    /** 先按 15.21 的 dataType 过滤，再把关键结构打出来。 */
    private fun dump(): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val args = chain.getArgs().toTypedArray()
                var body = bodyOf(args.toList())
                if (body != null && body.contains("\"cardList\"") && body.contains("MineNew")) {
                    try {
                        val out = AmapData.response(
                                "/ws/shield/dsp/profile/index/nodefaasv3", body, AmapData.CURRENT)
                        if (out != null && out != body) {
                            for (i in args.indices) {
                                if (args[i] is String && (args[i] as String).length > 200) {
                                    args[i] = out
                                    break
                                }
                            }
                            H.log(Log.INFO, TAG, "event=mine1521_filtered before="
                                    + body.length + " after=" + out.length)
                            body = out
                        }
                    } catch (t: Throwable) {
                        H.log(Log.WARN, TAG, "event=mine1521_filter_err err=" + t.javaClass.simpleName)
                    }
                }
                try {
                    if (body != null && (body.contains("cardList") || body.contains("dataType")
                                    || body.contains("entranceList"))) {
                        H.log(Log.INFO, TAG, "event=mine1521 resp len=" + body.length
                                + " cardTypes=" + cardTypes(body))
                    }
                } catch (ignored: Throwable) { }
                return chain.proceed(args)
            }
        }
    }

    // ── AJX 本地存储：页面常把「上次渲染的卡片」缓存下来，顺手看一眼 ──────────────
    private fun hookStorage(cl: ClassLoader): Int {
        val storage = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage")
        if (storage == null) return 0
        H.hookSig(storage, "getItemSync", "mine1521_storage", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val r = chain.proceed()
                try {
                    val key = chain.getArg(0).toString() + "/" + chain.getArg(1)
                    if (r is String && hot(key) && r.length < SNIPPET) {
                        H.log(Log.INFO, TAG, "event=mine1521 storage key=" + key + " value=" + r)
                    }
                } catch (ignored: Throwable) {}
                return r
            }
        }, String::class.java, String::class.java)
        return 1
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private fun hot(url: String?): Boolean {
        if (url == null) return false
        val s = url.lowercase()
        return s.contains("mine") || s.contains("profile") || s.contains("usercenter")
                || s.contains("user_center") || s.contains("personal") || s.contains("activity")
                || s.contains("dsp") || s.contains("badge") || s.contains("voice")
    }

    private fun urlOf(a: Any?, b: Any?): String? {
        if (a is String) return a
        if (b is String) return b
        return null
    }

    private fun bodyOf(args: List<Any?>): String? {
        for (a in args) {
            if (a is String) {
                if (a.length > 2 && (a.startsWith("{") || a.startsWith("["))) return a
            }
            if (a is org.json.JSONObject) return a.toString()
        }
        return null
    }


    /** 把 data.cardList[].dataType 全量列出来 —— 这就是过滤要用的 key。 */
    private fun cardTypes(body: String): String {
        try {
            val o = org.json.JSONObject(body)
            val data = o.optJSONObject("data")
            var cards = if (data == null) null else data.optJSONArray("cardList")
            if (cards == null) cards = o.optJSONArray("cardList")
            if (cards == null) return "none"
            val sb = StringBuilder()
            for (i in 0 until cards.length()) {
                val c = cards.optJSONObject(i)
                if (c == null) continue
                if (sb.length > 0) sb.append('|')
                sb.append(c.optString("dataType", "?"))
            }
            return if (sb.length == 0) "empty" else sb.toString()
        } catch (t: Throwable) {
            return "?"
        }
    }

    private fun versionName(cl: ClassLoader): String? {
        try {
            val p = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP)
            return p?.versionName
        } catch (t: Throwable) {
            return null
        }
    }
}
