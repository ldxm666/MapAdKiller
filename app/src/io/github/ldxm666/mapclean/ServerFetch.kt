package io.github.ldxm666.mapclean

import android.content.SharedPreferences
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.HashMap

/**
 * 模块的服务器访问层：**带降级的 GET**，公告与版本检查共用一份实现。
 *
 * <p>为什么必须只有一份：公告与「检查更新」的候选地址、白名单、端口规则、超时、ETag 语义
 * 完全一致。抄两遍的那天起，两份就会漂 —— 一边加了端口校验、另一边忘了，那条路就是洞。
 *
 * <h3>候选与降级</h3>
 * <pre>
 *   http://47.109.49.93&lt;path&gt;        ← 首选：公网实测 200，任何时候都通
 *   https://ldxm666.icu&lt;path&gt;        ← 次选：等域名挂上 Cloudflare 代理（443 + CF 证书）才会通
 * </pre>
 * 顺序刻意是「先 IP 后域名」：IP 是**已证实的通道**，不能让用户排在一条已知 403 的地址后面白等
 * 3 秒。域名那条现在必然失败并降级到 IP —— 这是预期行为，等 Cloudflare 配好再翻顺序即可，
 * 客户端不用改包。
 *
 * <h3>为什么 `http://` 走裸 socket</h3>
 * targetSdk ≥ 28 的 App 默认禁止明文 HTTP，`HttpURLConnection` 会直接抛
 * `CLEARTEXT communication not permitted`（真机实测：IP 能 ping 通，请求却 100% 失败）。
 * 而**内置版跑在地图进程里，清单是宿主的**，我们改不了它；模块版即使加
 * `usesCleartextTraffic` 也只修一半。所以 `http://` 用裸 [Socket] 发一个
 * 最小 HTTP/1.1 请求（`Connection: close`，读满即止），`https://` 仍走
 * [HttpURLConnection] 的完整 TLS 校验 —— 不给 TLS 做任何绕路。
 *
 * <h3>ETag</h3>
 * 条件请求的 ETag **按 base 分开缓存**（`etag::http://host:port`）。域名与 IP 是两个
 * 不同的缓存实体，串用会拿到错配的 304，表现为「永远不更新」。
 *
 * <h3>白名单</h3>
 * 只允许两条前缀，**host 与端口都参与字面匹配**：`http://ldxm666.icu:21119/...`、
 * `http://ldxm666.icu/...`（错协议）、`http://47.109.49.93:8080/...`（错端口）
 * 全部会被拒 —— 已知死路不该被放行。
 *
 * <p>本类全部方法都是**同步**的，只能在后台线程调用。
 */
class ServerFetch private constructor() {

    /** 一次 GET 的结果。`body` 为 null 表示 304（缓存仍有效）。 */
    class Result {
        @JvmField
        var ok = false
        @JvmField
        var code = 0
        @JvmField
        var body: String? = null

        /** `hit` = 304 命中本地缓存；`miss` = 拿到了新内容。 */
        @JvmField
        var etag = "miss"

        /** 命中的 base（`scheme://host[:port]`），用于日志与 ETag 记账。 */
        @JvmField
        var base = ""

        /** 失败原因：`dns / timeout / connect / tls / io / httpNNN / url / parse`。 */
        @JvmField
        var reason = ""

        /** 响应头里的新 ETag（可能为 null）。 */
        @JvmField
        var newEtag: String? = null
    }

    companion object {

        /** 候选 base，顺序即优先级。 */
        @JvmField
        val BASES: Array<String> = arrayOf(
            "http://47.109.49.93",
            "https://ldxm666.icu",
        )

        /** 允许的地址前缀（含 host 与端口，字面匹配）。 */
        private val PREFIXES: Array<String> = arrayOf(
            "http://47.109.49.93/",
            "https://ldxm666.icu/",
        )

        const val PATH_ANNOUNCE = "/mc/announce.json"
        const val PATH_VERSION = "/mc/version.json"

        /** 单条地址的超时；两条串行 = 最坏 6s。 */
        const val TIMEOUT_MS = 3000

        /** 响应体上限：公告与版本清单都是几百字节的东西。 */
        const val MAX_BODY = 64 * 1024

        private const val TAG = "MapCleanServer"

        /**
         * 服务端与客户端共用的 HMAC 密钥。**共享常量，不是服务器凭据**：
         * 它只用于「证明这条 JSON 是作者发的」，泄漏的最坏后果是有人能伪造一条公告，
         * 而不是拿到服务器权限。客户端里绝不允许出现 SSH / token 一类的真凭据。
         */
        const val SIG_KEY = "mc-announce-2026-v1-7f3a9c"

        /** CR / LF 的字节值。Java 里 `byte == '\r'` 靠 char→int 隐式提升，Kotlin 必须显式比 Byte。 */
        private const val CR: Byte = 13
        private const val LF: Byte = 10

        /**
         * 按 [BASES] 顺序取一个路径。
         *
         * @param tag      事件名前缀（`announce` / `update`）
         * @param path     [PATH_ANNOUNCE] 或 [PATH_VERSION]
         * @param prefs    ETag 的存放处（可为 null：不做条件请求）
         * @param override 测试后门地址；**调用方必须已经用 [allowed] 校验过**，
         *                 这里会再校验一次（双保险）。非 null 时只试这一个地址，不降级。
         */
        @JvmStatic
        fun get(tag: String, path: String, prefs: SharedPreferences?, override: String?): Result {
            return get(tag, path, prefs, override, false)
        }

        /**
         * @param ignoreEtag true = 这一次不带 `If-None-Match`。
         *        专门给「304 但本地缓存不见了」的自愈用：ETag 还在、正文没了，再问一次 304
         *        只会永远拿到空指针。调用方发现缓存为空时用这个重试一次。
         */
        @JvmStatic
        fun get(
            tag: String, path: String, prefs: SharedPreferences?, override: String?,
            ignoreEtag: Boolean
        ): Result {
            val urls = candidates(path, override)
            var last = Result()
            for (i in urls.indices) {
                val r = attempt(urls[i], prefs, ignoreEtag)
                if (r.ok) return r
                last = r
                if (i + 1 < urls.size && mayLog(tag + ":fallback", 6)) {
                    Log.i(
                        TAG, "event=" + tag + "_fallback"
                                + " from=" + r.base
                                + " to=" + baseOf(urls[i + 1])
                                + " reason=" + shortReason(r.reason)
                    )
                }
            }
            if (mayLog(tag + ":failed", 6)) {
                Log.i(
                    TAG, "event=" + tag + "_skip reason=all_failed"
                            + " tried=" + urls.size
                            + (if (last.reason.isNotEmpty()) " detail=" + last.reason else "")
                )
            }
            return last
        }

        /** 实际要试的地址。后门命中时只试后门那一个（灰度就该只打灰度地址）。 */
        @JvmStatic
        fun candidates(path: String, override: String?): Array<String> {
            if (override != null && allowed(override)) return arrayOf(override)
            val urls = Array(BASES.size) { "" }
            for (i in BASES.indices) urls[i] = BASES[i] + path
            return urls
        }

        /** 地址白名单：只认那两条前缀，host 与端口都参与字面匹配。 */
        @JvmStatic
        fun allowed(url: String?): Boolean {
            if (url == null) return false
            val value = url.trim()
            if (value.isEmpty() || value.length > 512) return false
            var prefix = false
            for (allowed in PREFIXES) {
                if (value.startsWith(allowed)) {
                    prefix = true
                    break
                }
            }
            if (!prefix) return false
            for (i in value.indices) {
                val c = value[i]
                if (c == '\n' || c == '\r' || c == ' ' || c == '\t') return false
            }
            return true
        }

        /** `scheme://host[:port]`：ETag 的记账键，也是日志里的 base。 */
        @JvmStatic
        fun baseOf(url: String): String {
            try {
                val u = URL(url)
                val host = u.host ?: return url
                val port = u.port
                return u.protocol + "://" + host + (if (port > 0) ":" + port else "")
            } catch (t: Throwable) {
                return url
            }
        }

        /**
         * 校验一条服务端 JSON 的签名。
         *
         * @param canonical 参与签名的原文（公告是 `"<id>|<title>|<body>"`，
         *                  版本清单是 `"<versionCode>|<versionName>|<apkUrl>"`）
         * @param sig       服务端下发的 HEX 串；**空串按失败处理**（服务端缺密钥时就是空串）
         */
        @JvmStatic
        fun verifySig(canonical: String, sig: String?): Boolean {
            if (sig == null || sig.isEmpty()) return false
            val expected = hmacHex(canonical)
            return expected != null && expected.equals(sig.trim(), ignoreCase = true)
        }

        private fun hmacHex(message: String): String? {
            try {
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(
                    javax.crypto.spec.SecretKeySpec(
                        SIG_KEY.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"
                    )
                )
                val digest = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
                val digits = "0123456789abcdef".toCharArray()
                val out = CharArray(digest.size * 2)
                for (i in digest.indices) {
                    val b = digest[i].toInt() and 0xFF
                    out[i * 2] = digits[b ushr 4]
                    out[i * 2 + 1] = digits[b and 0x0F]
                }
                return String(out)
            } catch (t: Throwable) {
                return null
            }
        }

        // ── 内部 ────────────────────────────────────────────────────────────

        private fun attempt(url: String, prefs: SharedPreferences?, ignoreEtag: Boolean): Result {
            val out = Result()
            out.base = baseOf(url)
            if (!allowed(url)) {
                out.reason = "url"
                return out
            }
            try {
                if (url.startsWith("http://")) {
                    return plainGet(url, prefs, out, ignoreEtag)
                }
                return tlsGet(url, prefs, out, ignoreEtag)
            } catch (t: Throwable) {
                out.reason = classify(t)
                out.ok = false
                return out
            }
        }

        /**
         * 明文 HTTP：裸 socket 手写最小请求。
         *
         * <p>只用最保守的语法：HTTP/1.1 + `Connection: close`，不跟随重定向（3xx 一律当失败），
         * 响应体读满或到上限即止。这样既绕开了 targetSdk 28+ 的明文禁令，也不依赖宿主清单。
         */
        private fun plainGet(
            url: String, prefs: SharedPreferences?, out: Result,
            ignoreEtag: Boolean
        ): Result {
            val u = URL(url)
            val host = u.host
            val port = if (u.port > 0) u.port else 80
            val rawPath = u.file
            val path = if (rawPath == null || rawPath.isEmpty()) "/" else rawPath

            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS

                val req = StringBuilder(256)
                req.append("GET ").append(path).append(" HTTP/1.1\r\n")
                req.append("Host: ").append(host)
                if (port != 80) req.append(':').append(port)
                req.append("\r\n")
                req.append("User-Agent: MapAdKiller/").append(Version.NAME).append("\r\n")
                req.append("Accept: application/json\r\n")
                req.append("Connection: close\r\n")
                val etag = if (ignoreEtag) null else etagOf(prefs, out.base)
                if (etag != null) req.append("If-None-Match: ").append(etag).append("\r\n")
                req.append("\r\n")

                val os = socket.getOutputStream()
                os.write(req.toString().toByteArray(StandardCharsets.UTF_8))
                os.flush()

                val raw = readAll(socket.getInputStream(), MAX_BODY + 8192)
                return parseResponse(raw, out)
            } finally {
                try {
                    socket.close()
                } catch (ignored: Throwable) {
                }
            }
        }

        /** HTTPS：标准栈，证书与主机名校验一个都不少。 */
        private fun tlsGet(
            url: String, prefs: SharedPreferences?, out: Result,
            ignoreEtag: Boolean
        ): Result {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                // 不跟随重定向：白名单管的是**实际请求的地址**，一次 302 就能把它绕过去。
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "MapAdKiller/" + Version.NAME)
                conn.setRequestProperty("Accept", "application/json")
                val etag = if (ignoreEtag) null else etagOf(prefs, out.base)
                if (etag != null) conn.setRequestProperty("If-None-Match", etag)
                val code = conn.responseCode
                out.code = code
                if (code == 304) {
                    out.ok = true
                    out.etag = "hit"
                    return out
                }
                if (code < 200 || code >= 300) {
                    out.reason = "http" + code
                    return out
                }
                out.body = String(readAll(conn.inputStream, MAX_BODY), StandardCharsets.UTF_8)
                out.newEtag = conn.getHeaderField("ETag")
                out.ok = true
                return out
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        /** 解析 `HTTP/1.1 200 OK` + 头部 + 体（支持 Content-Length 与 chunked）。 */
        private fun parseResponse(raw: ByteArray?, out: Result): Result {
            if (raw == null || raw.isEmpty()) {
                out.reason = "empty"
                return out
            }
            var sep = -1
            for (i in 0 until raw.size - 3) {
                if (raw[i] == CR && raw[i + 1] == LF && raw[i + 2] == CR && raw[i + 3] == LF) {
                    sep = i
                    break
                }
            }
            if (sep < 0) {
                out.reason = "badhead"
                return out
            }
            val head = String(raw, 0, sep, StandardCharsets.ISO_8859_1)
            val lines = head.split(Regex("\r\n"))
            if (lines.isEmpty()) {
                out.reason = "badhead"
                return out
            }
            val status = lines[0].split(Regex(" "))
            var code = 0
            try {
                code = Integer.parseInt(status[1].trim())
            } catch (ignored: Throwable) {
            }
            if (code == 0) {
                out.reason = "badstatus"
                return out
            }
            out.code = code
            var etag: String? = null
            var chunked = false
            var contentLength = -1L
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase(java.util.Locale.US)
                val value = line.substring(colon + 1).trim()
                if ("etag" == name) etag = value
                else if ("transfer-encoding" == name
                    && value.lowercase(java.util.Locale.US).contains("chunked")
                ) chunked = true
                else if ("content-length" == name) {
                    try {
                        contentLength = java.lang.Long.parseLong(value)
                    } catch (ignored: Throwable) {
                    }
                }
            }
            if (code == 304) {
                out.ok = true
                out.etag = "hit"
                return out
            }
            if (code < 200 || code >= 300) {
                out.reason = "http" + code
                return out
            }
            val bodyBytes: ByteArray
            val bodyStart = sep + 4
            if (chunked) {
                bodyBytes = dechunk(raw, bodyStart)
            } else if (contentLength >= 0) {
                val len = Math.min(contentLength, (raw.size - bodyStart).toLong()).toInt()
                bodyBytes = ByteArray(Math.max(0, len))
                System.arraycopy(raw, bodyStart, bodyBytes, 0, bodyBytes.size)
            } else {
                bodyBytes = ByteArray(Math.max(0, raw.size - bodyStart))
                System.arraycopy(raw, bodyStart, bodyBytes, 0, bodyBytes.size)
            }
            out.body = String(bodyBytes, StandardCharsets.UTF_8)
            out.newEtag = etag
            out.ok = true
            return out
        }

        private fun dechunk(raw: ByteArray, start: Int): ByteArray {
            val out = ByteArrayOutputStream(4096)
            var pos = start
            while (pos < raw.size) {
                var lineEnd = -1
                for (i in pos until raw.size - 1) {
                    if (raw[i] == CR && raw[i + 1] == LF) {
                        lineEnd = i
                        break
                    }
                }
                if (lineEnd < 0) break
                var sizeLine = String(raw, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).trim()
                val semi = sizeLine.indexOf(';')
                if (semi >= 0) sizeLine = sizeLine.substring(0, semi)
                var size: Int
                try {
                    size = Integer.parseInt(sizeLine, 16)
                } catch (t: Throwable) {
                    break
                }
                pos = lineEnd + 2
                if (size <= 0) break
                val take = Math.min(size, raw.size - pos)
                out.write(raw, pos, take)
                pos += size + 2                       // 跳过 chunk 末尾的 CRLF
            }
            return out.toByteArray()
        }

        private fun etagOf(prefs: SharedPreferences?, base: String): String? {
            try {
                return prefs?.getString("etag::" + base, null)
            } catch (t: Throwable) {
                return null
            }
        }

        private fun readAll(input: InputStream?, limit: Int): ByteArray {
            if (input == null) return ByteArray(0)
            val out = ByteArrayOutputStream(4096)
            val buf = ByteArray(4096)
            var n = input.read(buf)
            var total = 0
            while (n > 0) {
                out.write(buf, 0, n)
                total += n
                if (total >= limit) break
                n = input.read(buf)
            }
            input.close()
            return out.toByteArray()
        }

        private fun classify(throwable: Throwable?): String {
            var t = throwable
            var depth = 0
            while (t != null && depth < 8) {
                val name = t.javaClass.simpleName
                if (name.contains("UnknownHost")) return "dns"
                if (name.contains("Timeout")) return "timeout"
                if (name.contains("SSL") || name.contains("Cert")) return "tls"
                if (name.contains("Connect")) return "connect"
                val next = t.cause
                if (next === t) break
                t = next
                depth++
            }
            return "io"
        }

        /** 日志里的 reason 只有四类（dns / timeout / connect / http），其余细节留在 detail 里。 */
        private fun shortReason(reason: String): String {
            if ("dns" == reason) return "dns"
            if ("timeout" == reason) return "timeout"
            if ("connect" == reason) return "connect"
            if ("tls" == reason) return "tls"
            return "http"
        }

        private val LOGGED: MutableMap<String, Int> = HashMap()

        /** 同一类事件每进程最多 N 行：服务端每次联网都会跑，不设闸就是刷屏。 */
        private fun mayLog(key: String, cap: Int): Boolean {
            synchronized(LOGGED) {
                val seen = LOGGED[key]
                val count = seen ?: 0
                if (count >= cap) return false
                LOGGED[key] = count + 1
                return true
            }
        }
    }
}
