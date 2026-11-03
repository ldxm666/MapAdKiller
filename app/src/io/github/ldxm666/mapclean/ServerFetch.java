package io.github.ldxm666.mapclean;

import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

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
 * <h3>为什么 {@code http://} 走裸 socket</h3>
 * targetSdk ≥ 28 的 App 默认禁止明文 HTTP，{@code HttpURLConnection} 会直接抛
 * {@code CLEARTEXT communication not permitted}（真机实测：IP 能 ping 通，请求却 100% 失败）。
 * 而**内置版跑在地图进程里，清单是宿主的**，我们改不了它；模块版即使加
 * {@code usesCleartextTraffic} 也只修一半。所以 {@code http://} 用裸 {@link Socket} 发一个
 * 最小 HTTP/1.1 请求（{@code Connection: close}，读满即止），{@code https://} 仍走
 * {@link HttpURLConnection} 的完整 TLS 校验 —— 不给 TLS 做任何绕路。
 *
 * <h3>ETag</h3>
 * 条件请求的 ETag **按 base 分开缓存**（{@code etag::http://host:port}）。域名与 IP 是两个
 * 不同的缓存实体，串用会拿到错配的 304，表现为「永远不更新」。
 *
 * <h3>白名单</h3>
 * 只允许两条前缀，**host 与端口都参与字面匹配**：{@code http://ldxm666.icu:21119/...}、
 * {@code http://ldxm666.icu/...}（错协议）、{@code http://47.109.49.93:8080/...}（错端口）
 * 全部会被拒 —— 已知死路不该被放行。
 *
 * <p>本类全部方法都是**同步**的，只能在后台线程调用。
 */
final class ServerFetch {

    /** 候选 base，顺序即优先级。 */
    static final String[] BASES = {
            "http://47.109.49.93",
            "https://ldxm666.icu",
    };

    /** 允许的地址前缀（含 host 与端口，字面匹配）。 */
    private static final String[] PREFIXES = {
            "http://47.109.49.93/",
            "https://ldxm666.icu/",
    };

    static final String PATH_ANNOUNCE = "/mc/announce.json";
    static final String PATH_VERSION = "/mc/version.json";

    /** 单条地址的超时；两条串行 = 最坏 6s。 */
    static final int TIMEOUT_MS = 3000;
    /** 响应体上限：公告与版本清单都是几百字节的东西。 */
    static final int MAX_BODY = 64 * 1024;

    private static final String TAG = "MapCleanServer";

    /**
     * 服务端与客户端共用的 HMAC 密钥。**共享常量，不是服务器凭据**：
     * 它只用于「证明这条 JSON 是作者发的」，泄漏的最坏后果是有人能伪造一条公告，
     * 而不是拿到服务器权限。客户端里绝不允许出现 SSH / token 一类的真凭据。
     */
    static final String SIG_KEY = "mc-announce-2026-v1-7f3a9c";

    /** 一次 GET 的结果。{@code body} 为 null 表示 304（缓存仍有效）。 */
    static final class Result {
        boolean ok;
        int code;
        String body;
        /** {@code hit} = 304 命中本地缓存；{@code miss} = 拿到了新内容。 */
        String etag = "miss";
        /** 命中的 base（{@code scheme://host[:port]}），用于日志与 ETag 记账。 */
        String base = "";
        /** 失败原因：{@code dns / timeout / connect / tls / io / httpNNN / url / parse}。 */
        String reason = "";
        /** 响应头里的新 ETag（可能为 null）。 */
        String newEtag;
    }

    private ServerFetch() {}

    /**
     * 按 {@link #BASES} 顺序取一个路径。
     *
     * @param tag      事件名前缀（{@code announce} / {@code update}）
     * @param path     {@link #PATH_ANNOUNCE} 或 {@link #PATH_VERSION}
     * @param prefs    ETag 的存放处（可为 null：不做条件请求）
     * @param override 测试后门地址；**调用方必须已经用 {@link #allowed} 校验过**，
     *                 这里会再校验一次（双保险）。非 null 时只试这一个地址，不降级。
     */
    static Result get(String tag, String path, SharedPreferences prefs, String override) {
        return get(tag, path, prefs, override, false);
    }

    /**
     * @param ignoreEtag true = 这一次不带 {@code If-None-Match}。
     *        专门给「304 但本地缓存不见了」的自愈用：ETag 还在、正文没了，再问一次 304
     *        只会永远拿到空指针。调用方发现缓存为空时用这个重试一次。
     */
    static Result get(String tag, String path, SharedPreferences prefs, String override,
                      boolean ignoreEtag) {
        String[] urls = candidates(path, override);
        Result last = new Result();
        for (int i = 0; i < urls.length; i++) {
            Result r = attempt(urls[i], prefs, ignoreEtag);
            if (r.ok) return r;
            last = r;
            if (i + 1 < urls.length && mayLog(tag + ":fallback", 6)) {
                Log.i(TAG, "event=" + tag + "_fallback"
                        + " from=" + r.base
                        + " to=" + baseOf(urls[i + 1])
                        + " reason=" + shortReason(r.reason));
            }
        }
        if (mayLog(tag + ":failed", 6)) {
            Log.i(TAG, "event=" + tag + "_skip reason=all_failed"
                    + " tried=" + urls.length
                    + (last.reason.length() > 0 ? " detail=" + last.reason : ""));
        }
        return last;
    }

    /** 实际要试的地址。后门命中时只试后门那一个（灰度就该只打灰度地址）。 */
    static String[] candidates(String path, String override) {
        if (override != null && allowed(override)) return new String[]{override};
        String[] urls = new String[BASES.length];
        for (int i = 0; i < BASES.length; i++) urls[i] = BASES[i] + path;
        return urls;
    }

    /** 地址白名单：只认那两条前缀，host 与端口都参与字面匹配。 */
    static boolean allowed(String url) {
        if (url == null) return false;
        String value = url.trim();
        if (value.length() == 0 || value.length() > 512) return false;
        boolean prefix = false;
        for (String allowed : PREFIXES) {
            if (value.startsWith(allowed)) { prefix = true; break; }
        }
        if (!prefix) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') return false;
        }
        return true;
    }

    /** {@code scheme://host[:port]}：ETag 的记账键，也是日志里的 base。 */
    static String baseOf(String url) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            if (host == null) return url;
            int port = u.getPort();
            return u.getProtocol() + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Throwable t) {
            return url;
        }
    }

    /**
     * 校验一条服务端 JSON 的签名。
     *
     * @param canonical 参与签名的原文（公告是 {@code "<id>|<title>|<body>"}，
     *                  版本清单是 {@code "<versionCode>|<versionName>|<apkUrl>"}）
     * @param sig       服务端下发的 HEX 串；**空串按失败处理**（服务端缺密钥时就是空串）
     */
    static boolean verifySig(String canonical, String sig) {
        if (sig == null || sig.length() == 0) return false;
        String expected = hmacHex(canonical);
        return expected != null && expected.equalsIgnoreCase(sig.trim());
    }

    private static String hmacHex(String message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    SIG_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray();
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xFF;
                out[i * 2] = digits[b >>> 4];
                out[i * 2 + 1] = digits[b & 0x0F];
            }
            return new String(out);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static Result attempt(String url, SharedPreferences prefs, boolean ignoreEtag) {
        Result out = new Result();
        out.base = baseOf(url);
        if (!allowed(url)) {
            out.reason = "url";
            return out;
        }
        try {
            if (url.startsWith("http://")) {
                return plainGet(url, prefs, out, ignoreEtag);
            }
            return tlsGet(url, prefs, out, ignoreEtag);
        } catch (Throwable t) {
            out.reason = classify(t);
            out.ok = false;
            return out;
        }
    }

    /**
     * 明文 HTTP：裸 socket 手写最小请求。
     *
     * <p>只用最保守的语法：HTTP/1.1 + {@code Connection: close}，不跟随重定向（3xx 一律当失败），
     * 响应体读满或到上限即止。这样既绕开了 targetSdk 28+ 的明文禁令，也不依赖宿主清单。
     */
    private static Result plainGet(String url, SharedPreferences prefs, Result out,
                                   boolean ignoreEtag) throws Exception {
        URL u = new URL(url);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String path = u.getFile();
        if (path == null || path.length() == 0) path = "/";

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            StringBuilder req = new StringBuilder(256);
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host);
            if (port != 80) req.append(':').append(port);
            req.append("\r\n");
            req.append("User-Agent: MapAdKiller/").append(Version.NAME).append("\r\n");
            req.append("Accept: application/json\r\n");
            req.append("Connection: close\r\n");
            String etag = ignoreEtag ? null : etagOf(prefs, out.base);
            if (etag != null) req.append("If-None-Match: ").append(etag).append("\r\n");
            req.append("\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();

            byte[] raw = readAll(socket.getInputStream(), MAX_BODY + 8192);
            return parseResponse(raw, out);
        } finally {
            try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    /** HTTPS：标准栈，证书与主机名校验一个都不少。 */
    private static Result tlsGet(String url, SharedPreferences prefs, Result out,
                                 boolean ignoreEtag) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // 不跟随重定向：白名单管的是**实际请求的地址**，一次 302 就能把它绕过去。
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "MapAdKiller/" + Version.NAME);
            conn.setRequestProperty("Accept", "application/json");
            String etag = ignoreEtag ? null : etagOf(prefs, out.base);
            if (etag != null) conn.setRequestProperty("If-None-Match", etag);
            int code = conn.getResponseCode();
            out.code = code;
            if (code == 304) {
                out.ok = true;
                out.etag = "hit";
                return out;
            }
            if (code < 200 || code >= 300) {
                out.reason = "http" + code;
                return out;
            }
            out.body = new String(readAll(conn.getInputStream(), MAX_BODY), StandardCharsets.UTF_8);
            out.newEtag = conn.getHeaderField("ETag");
            out.ok = true;
            return out;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 解析 {@code HTTP/1.1 200 OK} + 头部 + 体（支持 Content-Length 与 chunked）。 */
    private static Result parseResponse(byte[] raw, Result out) throws Exception {
        if (raw == null || raw.length == 0) {
            out.reason = "empty";
            return out;
        }
        int sep = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                sep = i;
                break;
            }
        }
        if (sep < 0) {
            out.reason = "badhead";
            return out;
        }
        String head = new String(raw, 0, sep, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        if (lines.length == 0) {
            out.reason = "badhead";
            return out;
        }
        String[] status = lines[0].split(" ");
        int code = 0;
        try { code = Integer.parseInt(status[1].trim()); } catch (Throwable ignored) { }
        if (code == 0) {
            out.reason = "badstatus";
            return out;
        }
        out.code = code;
        String etag = null;
        boolean chunked = false;
        long contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.US);
            String value = line.substring(colon + 1).trim();
            if ("etag".equals(name)) etag = value;
            else if ("transfer-encoding".equals(name)
                    && value.toLowerCase(java.util.Locale.US).contains("chunked")) chunked = true;
            else if ("content-length".equals(name)) {
                try { contentLength = Long.parseLong(value); } catch (Throwable ignored) { }
            }
        }
        if (code == 304) {
            out.ok = true;
            out.etag = "hit";
            return out;
        }
        if (code < 200 || code >= 300) {
            out.reason = "http" + code;
            return out;
        }
        byte[] bodyBytes;
        int bodyStart = sep + 4;
        if (chunked) {
            bodyBytes = dechunk(raw, bodyStart);
        } else if (contentLength >= 0) {
            int len = (int) Math.min(contentLength, (long) (raw.length - bodyStart));
            bodyBytes = new byte[Math.max(0, len)];
            System.arraycopy(raw, bodyStart, bodyBytes, 0, bodyBytes.length);
        } else {
            bodyBytes = new byte[Math.max(0, raw.length - bodyStart)];
            System.arraycopy(raw, bodyStart, bodyBytes, 0, bodyBytes.length);
        }
        out.body = new String(bodyBytes, StandardCharsets.UTF_8);
        out.newEtag = etag;
        out.ok = true;
        return out;
    }

    private static byte[] dechunk(byte[] raw, int start) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        int pos = start;
        while (pos < raw.length) {
            int lineEnd = -1;
            for (int i = pos; i + 1 < raw.length; i++) {
                if (raw[i] == '\r' && raw[i + 1] == '\n') { lineEnd = i; break; }
            }
            if (lineEnd < 0) break;
            String sizeLine = new String(raw, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            int size;
            try { size = Integer.parseInt(sizeLine, 16); } catch (Throwable t) { break; }
            pos = lineEnd + 2;
            if (size <= 0) break;
            int take = Math.min(size, raw.length - pos);
            out.write(raw, pos, take);
            pos += size + 2;                       // 跳过 chunk 末尾的 CRLF
        }
        return out.toByteArray();
    }

    private static String etagOf(SharedPreferences prefs, String base) {
        try {
            return prefs == null ? null : prefs.getString("etag::" + base, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total >= limit) break;
        }
        in.close();
        return out.toByteArray();
    }

    private static String classify(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++) {
            String name = t.getClass().getSimpleName();
            if (name.contains("UnknownHost")) return "dns";
            if (name.contains("Timeout")) return "timeout";
            if (name.contains("SSL") || name.contains("Cert")) return "tls";
            if (name.contains("Connect")) return "connect";
            Throwable next = t.getCause();
            if (next == t) break;
            t = next;
        }
        return "io";
    }

    /** 日志里的 reason 只有四类（dns / timeout / connect / http），其余细节留在 detail 里。 */
    private static String shortReason(String reason) {
        if ("dns".equals(reason)) return "dns";
        if ("timeout".equals(reason)) return "timeout";
        if ("connect".equals(reason)) return "connect";
        if ("tls".equals(reason)) return "tls";
        return "http";
    }

    private static final Map<String, Integer> LOGGED = new HashMap<String, Integer>();

    /** 同一类事件每进程最多 N 行：服务端每次联网都会跑，不设闸就是刷屏。 */
    private static boolean mayLog(String key, int cap) {
        synchronized (LOGGED) {
            Integer seen = LOGGED.get(key);
            int count = seen == null ? 0 : seen.intValue();
            if (count >= cap) return false;
            LOGGED.put(key, count + 1);
            return true;
        }
    }
}
