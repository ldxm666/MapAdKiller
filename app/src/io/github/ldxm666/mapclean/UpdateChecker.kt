package io.github.ldxm666.mapclean

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.HashMap

/**
 * 版本检查：打开设置页 / 面板时去**作者自己的服务器**拉最新版本号，比本机新就提示更新。
 *
 * <h3>数据源</h3>
 * `<BASE>/mc/version.json`，BASE 与公告**完全同一套**候选列表（[ServerFetch]）：
 * 高端口域名优先、IP 兜底，串行降级、ETag 按 base 分开缓存、3 秒超时。
 * 这份实现和公告共用同一个 GET，不存在「一边修了另一边没修」的漂移。
 *
 * <h3>服务端清单契约</h3>
 * <pre>
 * {
 *   "versionCode": 2301,
 *   "versionName": "2.3.1",
 *   "notes": "修复了…",
 *   "apkUrl": "http://47.109.49.93/mc/MapClean-lsp-2.3.1.apk",
 *   "apkUrlEmbedded": {
 *     "com.autonavi.minimap": "...", "com.baidu.BaiduMap": "...", "com.tencent.map": "..."
 *   },
 *   "publishedAt": 1767225600,
 *   "sig": "..."
 * }
 * </pre>
 * 签名：`HEX(HMAC-SHA256(SIG_KEY, "<versionCode>|<versionName>|<apkUrl>"))`，
 * 空 sig 按失败处理。验签失败 = 这一轮失败（不落盘、不提示），绝不把服务器被改过的包
 * 当成「新版本」推给用户。
 *
 * <h3>公开 API</h3>
 * 与改造前**完全一致**（[latest] / [source] / [isOutdated] /
 * [check] / [maybeAutoCheck] / [shouldNotify] / [isChecking]），
 * 所以 `FloatingPanel` / `MainActivity` 的调用点一行都不用改。
 * 新增 [downloadUrl]：按「内置版选宿主包名那条、模块版用 apkUrl」的规则
 * 给出下载地址，未获取 / 不在白名单时返回 null。
 *
 * <h3>不变的部分</h3>
 * 24 小时自动检查节流、6 小时手动节流、`sRunning` 防重入、失败静默（只写一行日志，
 * 绝不弹错误框）、结果缓存在 prefs（冷启动先显示上次结果再后台刷新）。
 */
class UpdateChecker private constructor() {

    /**
     * 单抽象方法接口：**必须是 `fun interface`**。
     *
     * <p>转译前这里是 Java interface，`MainActivity` 用的是 SAM 构造器写法
     * `UpdateChecker.Callback { latest, outdated, source -> … }`。Kotlin 的普通 interface
     * 不支持这种写法，只有 `fun interface` 才会生成 SAM 转换入口 —— 去掉 `fun` 会让
     * MainActivity 那条调用点编译不过。
     */
    fun interface Callback {
        /**
         * @param latest   线上最新版本号（如 "1.1.0"）；null = 这次没查到
         * @param outdated true = 线上比本机新
         * @param source   命中的源（或最后一次的错误），便于诊断
         */
        fun onResult(latest: String?, outdated: Boolean, source: String?)
    }

    /** 服务端清单（v2.3.1 起）。 */
    private class Info {
        var code = 0
        var name = ""
        var notes = ""
        var apkUrl = ""
        var sig = ""
        var publishedAt = 0L
        val embedded: MutableMap<String, String> = HashMap()
    }

    companion object {

        /** [source] 在成功时的返回值。UI 上的「via xxx」就是它。 */
        const val SOURCE_NAME = "服务器"

        private const val TAG = "MapCleanUpdate"
        private const val PREF = io.github.ldxm666.mapadkiller.App.UI_PREFS
        private const val K_AT = "ver_check_at"
        private const val K_VER = "ver_latest"
        private const val K_CODE = "ver_code"
        private const val K_NOTES = "ver_notes"
        private const val K_APK = "ver_apk"
        private const val K_EMB = "ver_emb_"
        private const val K_SRC = "ver_source"

        /** 已经用 Toast 提示过的新版本号，用于「每个新版本只提示一次」。 */
        private const val K_TOASTED = "ver_toast_shown"
        private const val THROTTLE_MS = 6L * 60L * 60L * 1000L

        /** 自动检查的间隔：一天一次就够，失败也照样计时（避免每次启动都重试）。 */
        private const val AUTO_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** 内置版的三个宿主包名：`apkUrlEmbedded` 的键就是它们。 */
        private val HOSTS: Array<String> = arrayOf(
            "com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map",
        )

        /**
         * 隐藏的「模拟新版」开关（默认不存在 = 关闭，不出现在任何可见界面）。
         *
         * <p>存在的唯一理由是可测试性：面板首次打开会真的后台查一次，立刻把手写进
         * `ver_latest` 的值覆盖回真实版本，于是「红色胶囊 + 新版本小标」这条分支
         * 在真机上**无法**靠改配置复现。置 true 时读取端直接返回 [SIMULATED_VERSION]
         * 并跳过一切网络请求。
         */
        private const val K_SIMULATE = "ver_simulate_new"

        /** 远高于任何真实版本，保证 [compare] 一定判「有新版」。 */
        private const val SIMULATED_VERSION = "9.9.9"

        /** 每进程只打一行 `event=update_simulated`。 */
        @Volatile
        private var simLogged = false

        private val MAIN = Handler(Looper.getMainLooper())

        @Volatile
        private var sRunning = false

        /** 本次进程是否已经查过（节流只对同一次运行生效；冷启动一律重新查） */
        @Volatile
        private var sThisProcess = false

        /** 上次查到的线上版本（可能为 null） */
        @JvmStatic
        fun latest(c: Context): String? {
            val simulated = simulatedLatest(c)
            if (simulated != null) return simulated
            try {
                return prefs(c).getString(K_VER, null)
            } catch (t: Throwable) {
                return null
            }
        }

        /** 上次查到的线上 versionCode（0 = 未知）。 */
        @JvmStatic
        fun latestCode(c: Context): Int {
            val simulated = simulatedLatest(c)
            if (simulated != null) return Int.MAX_VALUE
            try {
                return prefs(c).getInt(K_CODE, 0)
            } catch (t: Throwable) {
                return 0
            }
        }

        /** 线上版本的更新说明（服务端 notes），没有就 null。 */
        @JvmStatic
        fun notes(c: Context): String? {
            try {
                val v = prefs(c).getString(K_NOTES, null)
                return if (v == null || v.isEmpty()) null else v
            } catch (t: Throwable) {
                return null
            }
        }

        /**
         * 新版本安装包地址。
         *
         * <p>选择规则（服务端契约）：
         * <ol>
         *   <li><b>内置版</b>（LSPatch 整合包）⇒ `apkUrlEmbedded` 里**当前宿主包名**那一条
         *       —— 高德用户拿去装百度包是装不上的；</li>
         *   <li>宿主键缺失 / 值为空 / 宿主包名拿不到 ⇒ 退回 `apkUrl`；</li>
         *   <li><b>模块版</b> ⇒ `apkUrl`。</li>
         * </ol>
         * 两条路都要过 [ServerFetch.allowed] 白名单：**不允许服务器指挥客户端去装任意 URL
         * 的 APK**。返回 null = 没有可信地址，UI 会退回到「项目发布页」。
         */
        @JvmStatic
        fun downloadUrl(c: Context?): String? {
            if (c == null) return null
            try {
                val p = prefs(c)
                var embedded = false
                try {
                    embedded = FloatingConfig.isEmbedded()
                } catch (ignored: Throwable) {
                }
                if (embedded) {
                    var pkg: String? = null
                    try {
                        pkg = c.packageName
                    } catch (ignored: Throwable) {
                    }
                    if (pkg != null) {
                        val host = p.getString(K_EMB + pkg, null)
                        if (host != null && ServerFetch.allowed(host)) return host
                    }
                }
                val apk = p.getString(K_APK, null)
                if (apk != null && ServerFetch.allowed(apk)) return apk
                return null
            } catch (t: Throwable) {
                return null
            }
        }

        /**
         * 隐藏开关是否打开；打开时返回 [SIMULATED_VERSION]，否则 null。
         *
         * <p>两个库都看：嵌入版里 `ver_*` 落在 `mapclean_embedded_ui`（宿主数据目录），
         * 独立模块进程里落在 `mak_ui`。测试员把同一个键写进任意一个都能复现。
         */
        private fun simulatedLatest(c: Context?): String? {
            if (c == null) return null
            var on = false
            try {
                on = prefs(c).getBoolean(K_SIMULATE, false)
            } catch (ignored: Throwable) {
            }
            if (!on) {
                try {
                    on = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getBoolean(K_SIMULATE, false)
                } catch (ignored: Throwable) {
                }
            }
            if (!on) return null
            if (!simLogged) {
                simLogged = true
                Log.i(
                    TAG, "event=update_simulated latest=" + SIMULATED_VERSION
                            + " local=" + Version.NAME
                )
            }
            return SIMULATED_VERSION
        }

        @JvmStatic
        fun source(c: Context): String {
            if (simulatedLatest(c) != null) return "simulate"
            try {
                return prefs(c).getString(K_SRC, "") ?: ""
            } catch (t: Throwable) {
                return ""
            }
        }

        /**
         * 线上比本机新？
         *
         * <p>优先比 `versionCode`（服务端契约里的权威字段）；只有老缓存里没有 code 时
         * 才退回语义化版本号比较 —— 字符串比较遇到 `2.3.10` 这种会判错。
         */
        @JvmStatic
        fun isOutdated(c: Context): Boolean {
            if (simulatedLatest(c) != null) return true
            try {
                val code = prefs(c).getInt(K_CODE, 0)
                if (code > 0) return code > Version.CODE
            } catch (ignored: Throwable) {
            }
            val v = latest(c)
            return v != null && compare(v, Version.LABEL) > 0
        }

        /** Same question as [isOutdated], named for the call sites that ask it. */
        @JvmStatic
        fun hasNewVersion(c: Context): Boolean {
            return isOutdated(c)
        }

        /**
         * 上次检查的时间（毫秒，0 = 从未查过）。
         *
         * <p>成功和失败都会写这个值（见 [check]），所以「失败后每次启动都重试」不会发生。
         */
        @JvmStatic
        fun lastCheckAt(c: Context): Long {
            try {
                return prefs(c).getLong(K_AT, 0L)
            } catch (t: Throwable) {
                return 0L
            }
        }

        /**
         * 每天最多一次的后台自动检查。
         *
         * <p>节流：`now - lastCheckAt < 24h` 直接返回，不建线程、不发请求。
         * 失败静默：只在日志里留一行，不 Toast、不改界面。
         */
        @JvmStatic
        fun maybeAutoCheck(c: Context?) {
            if (c == null) return
            if (simulatedLatest(c) != null) return      // 模拟态：不发任何请求
            val app: Context = try {
                c.applicationContext ?: c
            } catch (t: Throwable) {
                return
            }
            val last = lastCheckAt(app)
            val now = System.currentTimeMillis()
            if (last > 0L && now - last < AUTO_INTERVAL_MS) return
            Log.i(TAG, "event=update_auto_start last=" + last)
            check(app, true, object : Callback {
                override fun onResult(latest: String?, outdated: Boolean, source: String?) {
                    if ("cache" == source) return      // 缓存回放，不是这次的结果
                    if (latest == null) {
                        Log.w(TAG, "event=update_auto_failed source=" + source)
                    } else if (outdated) {
                        Log.i(TAG, "event=update_auto_found latest=" + latest + " source=" + source)
                    } else {
                        Log.i(TAG, "event=update_auto_latest latest=" + latest + " source=" + source)
                    }
                }
            })
        }

        /**
         * 每个新版本只提示一次：返回 true 表示这次该提示（并已把版本号记下）。
         */
        @JvmStatic
        fun shouldNotify(c: Context): Boolean {
            try {
                val v = latest(c) ?: return false
                if (compare(v, Version.LABEL) <= 0) return false
                val p = prefs(c)
                if (v == p.getString(K_TOASTED, null)) return false
                p.edit().putString(K_TOASTED, v).commit()
                return true
            } catch (t: Throwable) {
                return false
            }
        }

        /** 是否已有一轮检查在跑（`check()` 会直接 return，回调也不会来）。 */
        @JvmStatic
        fun isChecking(): Boolean {
            return sRunning
        }

        /** 后台检查；force=false 时受 6 小时节流约束。回调在主线程。 */
        @JvmStatic
        fun check(c: Context, force: Boolean, cb: Callback?) {
            // 2.3.x 转译：Java 原版直接 c.getApplicationContext()，null 时后面必 NPE。
            // 这里退回 c 本身，语义等价（真机上 applicationContext 从不返回 null）。
            val app: Context = c.applicationContext ?: c
            // 模拟态：绝不发网络请求，直接在主线程把模拟结果交回去。
            val sim = simulatedLatest(app)
            if (sim != null) {
                MAIN.post(object : Runnable {
                    override fun run() {
                        cb?.onResult(sim, true, "simulate")
                    }
                })
                return
            }
            val cached = latest(app)
            if (cached != null && cb != null) {
                // 先把缓存结果吐出去，界面立刻有内容
                val cv = cached
                val co = isOutdated(app)
                val callback = cb
                MAIN.post(object : Runnable {
                    override fun run() {
                        callback.onResult(cv, co, "cache")
                    }
                })
            }
            try {
                val at = prefs(app).getLong(K_AT, 0L)
                // ⚠ 只在同一次运行内节流：冷启动必须查一次（旧版 6 小时节流挡住了冷启动检查，
                //   用户表现为「发了新版但一直不提示」）。
                if (!force && sThisProcess && System.currentTimeMillis() - at < THROTTLE_MS) return
                sThisProcess = true
            } catch (ignored: Throwable) {
            }
            if (sRunning) return
            sRunning = true

            Thread(object : Runnable {
                override fun run() {
                    val started = System.currentTimeMillis()
                    var found: String? = null
                    var src = ""
                    var err = ""
                    var base = ""
                    try {
                        val p = prefs(app)
                        var r = ServerFetch.get(
                            "update", ServerFetch.PATH_VERSION, p, null
                        )
                        // 304 但本地没有版本信息（清过数据/换机）：丢掉条件头重发一次。
                        if (r.ok && r.body == null && p.getString(K_VER, null) == null) {
                            r = ServerFetch.get("update", ServerFetch.PATH_VERSION, p, null, true)
                        }
                        base = r.base
                        if (r.ok && r.body == null) {
                            // 304：清单没变，上次落盘的版本信息仍然有效。
                            found = p.getString(K_VER, null)
                            if (found == null) err = "stale304"
                            else src = SOURCE_NAME
                        } else if (r.ok) {
                            val info = parse(r.body)
                            if (info == null) {
                                err = "parse"
                            } else if (!ServerFetch.verifySig(
                                    info.code.toString() + "|" + info.name + "|" + info.apkUrl, info.sig
                                )
                            ) {
                                err = "badsig"
                                Log.w(TAG, "event=update_badsig code=" + info.code)
                            } else {
                                persist(p, info, r.base, r.newEtag)
                                found = info.name
                                src = SOURCE_NAME
                            }
                        } else {
                            err = r.reason
                        }
                    } catch (t: Throwable) {
                        err = t.javaClass.simpleName
                    } finally {
                        sRunning = false
                    }
                    try {
                        val e = prefs(app).edit()
                        e.putLong(K_AT, System.currentTimeMillis())
                        val resolved = found
                        if (resolved != null) {
                            e.putString(K_VER, resolved)
                            e.putString(K_SRC, src)
                        } else {
                            e.putString(K_SRC, if (err.isNotEmpty()) err else "failed")
                        }
                        e.commit()
                    } catch (ignored: Throwable) {
                    }
                    val result = if (found == null) "failed" else (if (isOutdated(app)) "new" else "latest")
                    Log.i(
                        TAG, "event=update_check_done result=" + result
                                + " elapsed=" + (System.currentTimeMillis() - started)
                                + " source=" + (if (found != null) (SOURCE_NAME + " " + base) else "none")
                                + (if (found != null) (" latest=" + found) else (" err=" + err))
                    )

                    val fv = found
                    val fs = if (fv != null) src else (if (err.isNotEmpty()) err else "failed")
                    val outdated = fv != null && isOutdated(app)
                    MAIN.post(object : Runnable {
                        override fun run() {
                            cb?.onResult(fv, outdated, fs)
                        }
                    })
                }
            }, "mapclean-update").start()
        }

        // ── 内部实现 ────────────────────────────────────────────────────────

        private fun parse(body: String?): Info? {
            if (body == null || body.isEmpty()) return null
            try {
                val o = JSONObject(body)
                val info = Info()
                info.code = o.getInt("versionCode")
                info.name = o.optString("versionName", "").trim()
                info.notes = o.optString("notes", "")
                info.apkUrl = o.optString("apkUrl", "").trim()
                info.sig = o.optString("sig", "").trim()
                info.publishedAt = o.optLong("publishedAt", 0L)
                if (info.code <= 0 || info.name.isEmpty()) return null
                val emb = o.optJSONObject("apkUrlEmbedded")
                if (emb != null) {
                    val keys = emb.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = emb.optString(key, "")
                        if (value != null && value.trim().isNotEmpty()) {
                            info.embedded[key] = value.trim()
                        }
                    }
                }
                return info
            } catch (t: Throwable) {
                return null
            }
        }

        /**
         * 落盘。下载地址**逐个过白名单**：不在名单里的直接丢弃（宁可不给下载入口，
         * 也不让一份被篡改的清单把用户带到任意 APK）。
         */
        private fun persist(p: SharedPreferences, info: Info, base: String, etag: String?) {
            try {
                val e = p.edit()
                e.putString(K_VER, info.name)
                e.putInt(K_CODE, info.code)
                e.putString(K_NOTES, info.notes)
                if (ServerFetch.allowed(info.apkUrl)) e.putString(K_APK, info.apkUrl)
                // 先清掉三个宿主键，避免上一版有、这一版没有的条目残留成死链。
                for (host in HOSTS) e.remove(K_EMB + host)
                for (entry in info.embedded.entries) {
                    val value = entry.value
                    if (ServerFetch.allowed(value)) {
                        e.putString(K_EMB + entry.key, value)
                    }
                }
                if (!etag.isNullOrEmpty()) e.putString("etag::" + base, etag)
                e.commit()
            } catch (ignored: Throwable) {
            }
        }

        private fun prefs(c: Context): SharedPreferences {
            // Always compare Version.LABEL (this module), never the host map's PackageInfo.
            return c.getSharedPreferences(
                if (EmbeddedSettingsPolicy.isHostPackage(c.packageName)) EmbeddedSettingsPolicy.UI_PREFS
                else PREF,
                Context.MODE_PRIVATE
            )
        }

        /** 语义化比较：a>b 返回 1，相等 0，a<b -1（位数不同按 0 补齐） */
        @JvmStatic
        fun compare(a: String?, b: String?): Int {
            if (a == null || b == null) return 0
            var ab = a
            var asu = ""
            val da = a.indexOf('-')
            if (da >= 0) {
                ab = a.substring(0, da)
                asu = a.substring(da + 1)
            }
            var bb = b
            var bsu = ""
            val db = b.indexOf('-')
            if (db >= 0) {
                bb = b.substring(0, db)
                bsu = b.substring(db + 1)
            }
            val x = ab.split(Regex("\\."))
            val y = bb.split(Regex("\\."))
            val n = Math.max(x.size, y.size)
            for (i in 0 until n) {
                val xi = if (i < x.size) toInt(x[i]) else 0
                val yi = if (i < y.size) toInt(y[i]) else 0
                if (xi != yi) return if (xi > yi) 1 else -1
            }
            // Same 2.2.3 base: "-step2" is newer than a plain build, step2 < step3.
            val sa = stepOf(asu)
            val sb = stepOf(bsu)
            return if (sa == sb) 0 else (if (sa > sb) 1 else -1)
        }

        private fun stepOf(suffix: String?): Int {
            if (suffix == null || suffix.isEmpty()) return 0
            val m = java.util.regex.Pattern
                .compile("step(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(suffix)
            return if (m.find()) toInt(m.group(1)) else 0
        }

        private fun toInt(s: String): Int {
            try {
                return Integer.parseInt(s.trim())
            } catch (t: Throwable) {
                return 0
            }
        }
    }
}
