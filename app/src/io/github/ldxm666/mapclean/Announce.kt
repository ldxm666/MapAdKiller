package io.github.ldxm666.mapclean

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

import org.json.JSONObject

import java.io.InputStream
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.TimeUnit


/**
 * 服务器公告通道：作者在服务端放一个 JSON，客户端在**需要时**取一次并弹给用户。
 *
 * <h3>为什么是「拉」而不是「推」</h3>
 * 模块跑在地图进程里，没有自己的推送通道；2GB 的服务器也撑不起任何常驻连接。所以只有两个
 * 时机联网：**App 启动后 3 秒**（避开冷启动关键路径）和**面板 / 设置页打开时**，
 * 且都受 6 小时节流约束。不用 AlarmManager、不用 WakeLock、不起前台服务。
 *
 * <h3>省流量的三件事</h3>
 * <ol>
 *   <li>**6 小时节流**：`last_check_ms` 连失败也写，失败不会退化成每次启动重试；</li>
 *   <li>**条件请求**：带上次的 ETag，`304` 就结束 —— 不解析、不写盘。
 *       ETag **按 host 分开存**：域名和 IP 是两个不同的缓存实体，串用会拿到错配的 304；</li>
 *   <li>**短超时**：connect 3s / read 3s，失败静默（一行日志），绝不重试轰炸。</li>
 * </ol>
 *
 * <h3>双通道（候选列表，顺序降级）</h3>
 * 域名 `ldxm666.icu` 未备案，阿里云按 Host 头在所有端口上拦（公网实测 403），
 * 所以候选表是「IP → 域名」两条，**串行**按序试，第一条成功就停：
 * <pre>
 *   http://47.109.49.93/mc/announce.json          ← 首选：公网实测 200
 *   https://ldxm666.icu/mc/announce.json          ← 次选：等域名挂上 Cloudflare 代理才会通
 * </pre>
 * 每次降级记 `event=announce_fallback from=<base> to=<base> reason=dns|timeout|http`；
 * 两条都失败记 `event=announce_skip reason=all_failed tried=2` 并本轮放弃，
 * 等 6 小时后的下一轮。**不并行**、不重试轰炸。
 *
 * <h3>安全</h3>
 * <ul>
 *   <li>只允许两个前缀（域名 + IP）的地址，JSON 里的 url 和测试后门
 *       `debug.mapclean.announce_url` 都受同一白名单约束；</li>
 *   <li>`sig` = `HEX(HMAC-SHA256(SIG_KEY, "<id>|<title>|<body>"))`，
 *       参与签名的是 **JSON 解码之后的原文**；空 sig（服务端缺密钥就是这样）按失败处理，
 *       校验失败丢弃并记 `event=announce_badsig`；</li>
 *   <li>不跟随 302：白名单管的是**实际请求的地址**，一次跳转就能把它绕过去；</li>
 *   <li>客户端里只有公网 URL 和一个共享 HMAC 常量，**没有任何服务器凭据**。</li>
 * </ul>
 *
 * <h3>展示</h3>
 * 只依附当前 resumed 的 Activity，对话框是普通 `Dialog`（`AlertDialog` + [Skin]
 * 自绘卡片，和 [FirstRunNotice] 同一套），不是 `TYPE_APPLICATION_OVERLAY`。
 * 拿不到 Activity 就把公告存进 `sPending`，等下次面板打开 / Activity resume 再弹。
 * 新 id（`id > announce_seen_id`）**必然弹一次**，任何 level 都可关；关掉即推进水位。
 *
 * 转译说明：全部 `static` 进了 [Companion]；被其它 .java 直接调用的入口
 * （`onAppStart` / `overlayHost` / `playIn` / `playOut`）标了 `@JvmStatic`。对象身份比较
 * 一律 `===`（原版是 Java 的 `==`）；`long + "|" + str` 这类拼接改成显式 `toString()`。
 */
class Announce private constructor() {

    companion object {

        /**
         * 「接收服务器公告」开关。默认 **true**：三条厂商的 defaultVisible 表对未知键都返回
         * true（高德 `Config.defaultVisible` / 百度 `Spec.defaultVisible` /
         * 腾讯 `Spec.defaultVisible`），所以那张表一行都不用改。
         */
        const val K_ON = "announce_on"

        /** 已读到的最大公告 id（秒级 unix 时间戳，单调递增）。 */
        const val K_SEEN_ID = "announce_seen_id"

        /**
         * 公告 JSON 的路径。候选 base、端口白名单、超时、ETag 记账全在 [ServerFetch] ——
         * 公告和「检查更新」共用同一份带降级的 GET，两边不会漂。
         */
        private val PATH = ServerFetch.PATH_ANNOUNCE

        /**
         * 测试 / 灰度后门：`adb shell setprop debug.mapclean.announce_url <url>`。
         *
         * 存在的理由：服务端换路径、或灰度发一份 `announce-test.json` 时，不必重新打包。
         * **仍然受 [ServerFetch] 约束**，否则它就是上面那个洞的旁路。
         * 每个进程只读一次，不参与任何热路径。
         */
        private const val TEST_PROP = "debug.mapclean.announce_url"

        private const val TAG = "MapCleanAnnounce"

        private const val PREFS = "mapclean_announce"
        private const val P_AT = "last_check_ms"
        private const val P_ETAG_PREFIX = "etag::"
        private const val P_CACHE = "cached_json"

        /**
         * 节流窗口**按上次结果分档**（2.3.1 修正）。
         *
         * 旧版只有「6 小时」一档，而且失败也按 6 小时计时 —— 联调期只要有一次网络失败，
         * 真公告上来时窗口已经被占掉，用户看到的是「通道装好了但从来不弹」。
         * 现在：拿到有效公告才等 6 小时；服务器可达但没有有效公告等 15 分钟（作者可能正在推）；
         * 两条地址都失败等 30 分钟；签名不符等 1 小时（密钥可能两边不一致，别狂刷也别等 6 小时）。
         */

        /**
         * 上一次**成功拿到有效公告**之后多久再查。
         *
         * ★ 2.3.1 修正：这里原来是 6 小时，对「事故公告」是致命的 —— 作者推了紧急通知，
         * 用户 6 小时内根本看不到，公告通道的核心价值就没了。实测：服务器换了新 id，
         * 三端 + 模块版的 prefs 全都锁在旧那条上。
         *
         * 成本核过：`/mc/announce.json` 161 字节，带 `If-None-Match` 时服务端回
         * 304（几十字节）。用户一天开 20 次 App ≈ 20 次请求 < 5KB，nginx 静态文件，服务端成本可忽略。
         * 真正贵的是「用户看不到公告」，不是这 5KB。
         */
        private const val WINDOW_SUCCESS_MS = 5L * 60L * 1000L
        private const val WINDOW_EMPTY_MS = 15L * 60L * 1000L
        private const val WINDOW_FAILED_MS = 30L * 60L * 1000L
        private const val WINDOW_BADSIG_MS = 60L * 60L * 1000L

        private const val KIND_SUCCESS = "success"
        private const val KIND_EMPTY = "empty"
        private const val KIND_FAILED = "failed"
        private const val KIND_BADSIG = "badsig"

        /** 上次检查的结果类型；**旧版本没有这个键 ⇒ 按 failed 处理（30 分钟后自愈）**。 */
        private const val P_KIND = "last_kind"
        private const val P_DISMISSED = "dismissed_id"

        /** 测试后门：置 1 ⇒ 下一次检查无视节流（每进程一次，且两次强制间隔 ≥ 60 秒）。 */
        private const val FORCE_PROP = "debug.mapclean.announce_force"
        private const val P_FORCE_AT = "force_at"
        private const val FORCE_MIN_GAP_MS = 60L * 1000L

        /** 「暂无公告」提示的最小间隔。 */
        private const val NO_ANNOUNCE_GAP_MS = 30L * 1000L

        /** 启动后的延迟：避开冷启动关键路径（地图首帧比一条公告重要）。 */
        private const val START_DELAY_MS = 3000L
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 3000

        /** 响应体上限：公告是几百字节的东西，超过这个数只可能是打错了地址。 */
        private const val MAX_BODY = 64 * 1024

        private val MAIN = Handler(Looper.getMainLooper())

        @Volatile
        private var sRunning = false

        @Volatile
        private var sWatched = false

        @Volatile
        private var sOverride: String? = null

        @Volatile
        private var sOverrideRead = false

        @Volatile
        private var sForcePropRead = false

        @Volatile
        private var sForceUsed = false

        /** 启动检查只排一次：`attachEmbedded` 会在每次面板渲染时被调用。 */
        @Volatile
        private var sStartScheduled = false

        /** 用户点了「最新公告」但当时已有一轮在飞：交给那一轮的收尾去提示。 */
        @Volatile
        private var sUserAskPending = false

        /** 「暂无公告」最多 30 秒提示一次，避免任何循环把它放大成刷屏。 */
        @Volatile
        private var sLastNoAnnounceAt = 0L

        /** 用户主动点「最新公告」这一轮，是否真的弹出了东西（没弹 ⇒ 提示「暂无公告」）。 */
        @Volatile
        private var sUserAskedServed = false

        /** 进程级 Application：`seen()` 这类没有调用方 Context 的读靠它。 */
        @Volatile
        private var sApp: Context? = null

        /**
         * 最近一个 resumed 的 Activity：对话框唯一的合法依附点。Weak，不拖住已结束的页面。
         *
         * 原版是 `new WeakReference<Activity>(null)` 当哨兵；Kotlin 侧直接用 `null` 表示同一个
         * 状态（`activity()` 里本来就是 `ref == null ? null : ref.get()`）。
         */
        @Volatile
        private var sResumed: WeakReference<Activity>? = null

        /** 拿到但还没有宿主可弹的公告。**每次 dismiss 一律清空**，绝不跨关窗重排。 */
        @Volatile
        private var sPending: Msg? = null

        /** 排队那条是不是「用户主动点开的」——它决定 advance 时还允不允许无视 seen。 */
        @Volatile
        private var sPendingFromUser = false

        /** 最近一次被关掉的 id（落盘）：自动路径见到它就永远跳过，只有用户手动点才能重看。 */
        @Volatile
        private var sDismissedId = -1L

        /** 本次关闭是哪个按钮触发的：ok / detail / cancel（点空白或返回）。 */
        @Volatile
        private var sCloseVia: String? = null

        /** 正在展示的公告 id（0 = 没有）。 */
        @Volatile
        private var sShowingId = 0L

        /** 「已读状态变了」的回调（面板横幅 / 设置页卡片据此去红点）。Weak，避免拖住 UI。 */
        @Volatile
        private var sOnChanged: WeakReference<Runnable?>? = null

        /** 一条通过解析、等待展示的公告。 */
        private class Msg {
            var id = 0L
            var title = ""
            var body = ""
            var level = "info"
            var url = ""
            var sig = ""
            var minVersion = 0L
            var maxVersion = 0L
            var expireAt = 0L
        }

        /* ══════════════════════════════════════════════════════════════════ 入口 */

        /**
         * App 启动：注册 Activity 生命周期观察者，并在 3 秒后查一次。
         *
         * 必须幂等 —— 模块版走 `App.onCreate`，内置版走 `App.attachEmbedded`，
         * 服务重绑还可能再来一次。
         */
        @JvmStatic
        fun onAppStart(context: Context?) {
            if (context == null) return
            val app = appOf(context)
            sApp = app
            watch(app)
            // ★ 只排一次：内置版的 attachEmbedded 会在每次面板渲染时被调用，以前每调一次就多排
            //   一个 3 秒后的 check —— 真机实测「500ms 内 5 次请求」就是这些定时器一起到期。
            if (sStartScheduled) return
            sStartScheduled = true
            MAIN.postDelayed(Runnable {
                try {
                    check(app, false, false)
                } catch (ignored: Throwable) {
                }
            }, START_DELAY_MS)
        }

        /**
         * 面板 / 设置页打开：补一次（节流在 `check` 内部），并把没弹出去的公告弹掉。
         *
         * 内置版没有 MainActivity，面板是它唯一的设置界面；面板打开也是唯一保证有 Activity
         * 可依附的时机之一。
         */
        @JvmStatic
        fun onPanelOpen(context: Context?) {
            if (context == null) return
            if (context is Activity) sResumed = WeakReference(context)
            val app = appOf(context)
            sApp = app
            watch(app)
            try {
                check(app, false, false)
            } catch (ignored: Throwable) {
            }
            MAIN.post(Runnable { advance() })
        }

        /** Activity 回到前台：记下它，并把等待中的公告弹出去（由生命周期回调调用）。 */
        @JvmStatic
        fun onResumed(activity: Activity?) {
            if (activity == null) return
            sResumed = WeakReference(activity)
            // ★ 回到前台必查一次：5 分钟窗口内自然被节流挡住（只读一次 prefs），
            //   超过窗口就真发一次条件请求（304 极轻）。作者推了公告、用户切回来就能看到。
            try {
                check(activity.applicationContext, false, false)
            } catch (ignored: Throwable) {
            }
            MAIN.post(Runnable { advance() })
        }

        /**
         * 「最新公告」入口：重看缓存里的那一条。
         *
         * 没有缓存时**强制查一次**（用户主动点的，不受 6 小时节流），结果直接弹给他。
         * 「用户主动重看」不动 `announce_seen_id` 的水位 —— 水位只由第一次自动提醒推进。
         */
        @JvmStatic
        fun showLatest(context: Context?) {
            if (context == null) return
            // 调用方可能把已销毁的 Activity 递进来（MainActivity.this 在异步回调里很常见）：
            // 这时候弹窗一定 BadTokenException，降级成一条 skip 日志，绝不崩。
            if (context is Activity) {
                var dead = false
                try {
                    dead = context.isFinishing || context.isDestroyed
                } catch (ignored: Throwable) {
                }
                if (dead) {
                    skip("no_ctx")
                    return
                }
                sResumed = WeakReference(context)
            }
            val app = appOf(context)
            sApp = app
            val cachedMsg = cached(app)
            if (cachedMsg != null) {
                sUserAskedServed = true;          // 重看缓存：一定有东西可弹
                offer(cachedMsg, true, "latest"); // 唯一允许无视 seen / dismissed 的路径
                return
            }
            // 没有缓存：**无条件**强制查一次（绕过一切节流）。查完什么都没有就明确提示，
            // 不能像以前那样「点了没反应」。
            sUserAskedServed = false
            sUserAskPending = true
            check(app, true, true)
        }

        /** 缓存里那条公告的标题（入口行的小字），没有就 null。 */
        @JvmStatic
        fun latestTitle(context: Context?): String? {
            val m = cached(if (context == null) null else appOf(context))
            return if (m == null || m.title.isEmpty()) null else m.title
        }

        /** 是否有一条还没提醒过的公告（入口的红点 / 角标据此显示）。 */
        @JvmStatic
        fun hasUnread(context: Context?): Boolean {
            val m = cached(if (context == null) null else appOf(context))
            if (m == null || m.title.isEmpty()) return false
            val seenId = if (context == null) seen() else seen(appOf(context))
            return m.id > seenId
        }

        /** 面板开关读的是这里。 */
        @JvmStatic
        fun enabled(context: Context?): Boolean {
            try {
                if (context != null) Vendor.from(context)
                return FloatingConfig.read(K_ON)
            } catch (t: Throwable) {
                return true      // 读不到就按默认（接收），与三条 defaultVisible 表一致
            }
        }

        /** 面板开关写的是这里。失败返回 false，调用方负责把胶囊拨回去。 */
        @JvmStatic
        fun setEnabled(on: Boolean): Boolean {
            try {
                val ok = FloatingConfig.write(K_ON, on)
                if (ok) FloatingConfig.invalidate()
                Log.i(TAG, "event=announce_switch on=" + on + " ok=" + ok)
                return ok
            } catch (t: Throwable) {
                return false
            }
        }

        /** UI 注册「已读状态变了」的回调（弱引用，注册方不必反注册）。 */
        @JvmStatic
        fun setOnChanged(callback: Runnable?) {
            sOnChanged = WeakReference<Runnable?>(callback)
        }

        /* ══════════════════════════════════════════════════════════════ 取公告 */

        /**
         * 联网取一次。`force` 只给「最新公告」入口用；`userAsked` 让结果绕过
         * 「同一条只提醒一次」的判据（用户自己点的，当然要弹）。
         */
        private fun check(context: Context?, force: Boolean, userAsked: Boolean) {
            if (context == null) return
            val app = appOf(context)
            if (!enabled(app)) {
                skip("off")
                userAskedFinished(userAsked)
                return
            }
            val p = prefs(app)
            if (p == null) {
                userAskedFinished(userAsked)
                return
            }
            var last = 0L
            var kind = KIND_FAILED
            try {
                last = p.getLong(P_AT, 0L)
                kind = p.getString(P_KIND, KIND_FAILED) ?: KIND_FAILED
            } catch (ignored: Throwable) {
            }
            val now = System.currentTimeMillis()
            val window = windowOf(kind)
            val forced = force || forcePropOnce(p)
            if (!forced && last > 0L && now - last < window) {
                // 这条日志就是上次「通道是死的」唯一缺的证据：被哪种节流挡、还要等多久。
                if (mayLog("skip:throttle", 6)) {
                    Log.i(TAG, "event=announce_skip reason=throttle remain_ms=" + (window - (now - last)) +
                            " kind=" + kind)
                }
                return
            }
            if (!online(app)) {
                skip("offline")
                markChecked(p, KIND_FAILED)     // 离线也计时，否则每次开面板都会再走一遍
                userAskedFinished(userAsked)
                return
            }
            if (sRunning) {
                // 已经有一轮在飞：不要在这里立刻说「暂无公告」（那会在自动检查期间误报），
                // 把「用户问过」记下来，交给正在跑的那一轮收尾统一提示。
                if (!userAsked) sUserAskPending = false
                return
            }
            // ★ 乐观占窗：请求是异步的，若等 worker 结束才写 P_AT，同一瞬间排进来的多个 check
            //   会**全部**通过节流判定。先按 failed 占上，结果出来再覆盖成真实 kind。
            markChecked(p, KIND_FAILED)
            sRunning = true

            Thread(Runnable {
                val started = System.currentTimeMillis()
                var ok = false
                var networkOk = false
                var resultKind = KIND_FAILED
                var etagHit = "miss"
                var reason = ""
                var host = ""
                var urls = arrayOf("?")
                try {
                    var override = testOverride()
                    if (override != null && !ServerFetch.allowed(override)) {
                        if (mayLog("override_reject", 1)) {
                            Log.w(TAG, "event=announce_skip reason=url via=prop value=" + override)
                        }
                        override = null
                    }
                    urls = ServerFetch.candidates(PATH, override)
                    var r = ServerFetch.get("announce", PATH, p, override)
                    // ★ 304 自愈：ETag 还在、本地缓存却丢了（换机/清数据/调试删键），
                    //   再问只会永远是 304。丢掉条件头重发一次，把正文补回来。
                    if (r.ok && r.body == null && cached(app) == null) {
                        if (mayLog("stale304", 3)) {
                            Log.i(TAG, "event=announce_stale304 base=" + r.base)
                        }
                        r = ServerFetch.get("announce", PATH, p, override, true)
                    }
                    networkOk = r.ok
                    ok = r.ok
                    etagHit = r.etag
                    host = r.base
                    reason = r.reason
                    if (!r.ok) {
                        resultKind = KIND_FAILED
                    } else if (r.body == null) {
                        resultKind = KIND_SUCCESS      // 304：缓存里的公告仍然有效
                    } else {
                        val msg = parse(r.body)
                        if (msg == null) {
                            ok = false
                            reason = "parse"
                            resultKind = KIND_FAILED
                            skip("parse")
                        } else if (!sigOk(msg)) {
                            ok = false
                            reason = "badsig"
                            resultKind = KIND_BADSIG
                            if (mayLog("badsig", 6)) {
                                Log.w(TAG, "event=announce_badsig id=" + msg.id)
                            }
                        } else if (msg.id <= 0L || msg.title.isEmpty()) {
                            // 服务端 --clear / 刚建站时的空公告：可达但没有有效内容。
                            resultKind = KIND_EMPTY
                            skip("empty")
                        } else {
                            cache(p, r.base, r.body, r.newEtag)
                            offer(msg, userAsked, if (userAsked) "fetch_user" else "fetch")
                            resultKind = KIND_SUCCESS
                        }
                    }
                } catch (t: Throwable) {
                    ok = false
                    reason = t.javaClass.simpleName
                } finally {
                    sRunning = false
                    markChecked(p, resultKind)
                }
                if (!ok && !networkOk && mayLog("all_failed", 6)) {
                    Log.i(TAG, "event=announce_skip reason=all_failed tried=" + urls.size)
                }
                // 304 是最常见的结果，也是最该安静的结果：不解析、不写盘。
                // 它降到 debug：`logcat -s MapCleanAnnounce:I` 看不到（不刷屏），
                // 需要核「到底有没有联网、是不是 304」时 `-s MapCleanAnnounce:D` 一条不少。
                val hit = ok && "hit" == etagHit
                val line = "event=announce_fetch ok=" + ok +
                        " ms=" + (System.currentTimeMillis() - started) +
                        " etag=" + etagHit +
                        (if (host.isNotEmpty()) " base=" + host else "") +
                        (if (reason.isNotEmpty()) " reason=" + reason else "")
                if (hit) {
                    Log.d(TAG, line)
                } else if (mayLog("fetch", 8)) {
                    Log.i(TAG, line)
                }
                if (ok && "hit" == etagHit && userAsked) {
                    // 304 = 缓存没变；用户点进来是想重看，那就给他缓存里的那条。
                    val msg = cached(app)
                    if (msg != null) offer(msg, true, "fetch304_user")
                }
                userAskedFinished(userAsked)
            }, "mapclean-announce").start()
        }

        /** 记「什么时候查的」+「查到了什么」。两者一起写，节流窗口才是自解释的。 */
        private fun markChecked(p: SharedPreferences, kind: String?) {
            try {
                p.edit()
                        .putLong(P_AT, System.currentTimeMillis())
                        .putString(P_KIND, kind ?: KIND_FAILED)
                        .commit()
            } catch (ignored: Throwable) {
            }
        }

        /** 上次结果 → 下次可再查的间隔。旧版本没有 kind，按 failed 处理即可自然自愈。 */
        private fun windowOf(kind: String?): Long {
            if (KIND_SUCCESS == kind) return WINDOW_SUCCESS_MS
            if (KIND_EMPTY == kind) return WINDOW_EMPTY_MS
            if (KIND_BADSIG == kind) return WINDOW_BADSIG_MS
            return WINDOW_FAILED_MS
        }

        /**
         * 读 `debug.mapclean.announce_url`。每个进程最多读一次。
         *
         * 两条路：先试 `android.os.SystemProperties`（反射，最轻），不行再 exec
         * `getprop`（隐藏 API 在部分 ROM 上会拒，exec 是兜底）。两条都失败 ⇒ 正式地址。
         */
        private fun testOverride(): String? {
            if (sOverrideRead) return sOverride
            sOverrideRead = true
            var value = readProp(TEST_PROP)
            if (value != null) {
                value = value.trim()
                if (value.isEmpty() || "null" == value) value = null
            }
            sOverride = value
            if (value != null && mayLog("override", 1)) {
                Log.i(TAG, "event=announce_override url=" + value)
            }
            return sOverride
        }

        /**
         * `debug.mapclean.announce_force=1` ⇒ 下一次检查无视节流。
         *
         * 每个进程只生效一次：普通 App 没有权限把 `debug.*` 清掉（那需要 shell/root），
         * 所以「查完自动清 prop」在这里退化成「本进程只认一次」——效果一样，且不会因为一条
         * 忘删的 prop 让客户端此后永不节流。
         */
        private fun forcePropOnce(p: SharedPreferences): Boolean {
            if (sForceUsed) return false
            if (!sForcePropRead) {
                sForcePropRead = true
                val value = readProp(FORCE_PROP)
                if (value != null && ("1" == value.trim() ||
                                "true".equals(value.trim(), ignoreCase = true))) {
                    // 两次强制之间至少 60 秒，且时间戳落盘（跨进程）。
                    // App 没有 setprop 权限，所以「查完清 prop」不可靠 —— 靠这两道闸兜住：
                    // 一条忘删的 prop 最多让每个进程绕过一次，绝不会变成连续联网。
                    var at = 0L
                    try {
                        at = p.getLong(P_FORCE_AT, 0L)
                    } catch (ignored: Throwable) {
                    }
                    val now = System.currentTimeMillis()
                    if (at > 0L && now - at < FORCE_MIN_GAP_MS) {
                        if (mayLog("force_skip", 3)) {
                            Log.i(TAG, "event=announce_skip reason=force_gap remain_ms=" +
                                    (FORCE_MIN_GAP_MS - (now - at)))
                        }
                        return false
                    }
                    try {
                        p.edit().putLong(P_FORCE_AT, now).commit()
                    } catch (ignored: Throwable) {
                    }
                    Log.i(TAG, "event=announce_force prop=1")
                    sForceUsed = true
                }
            }
            return sForceUsed
        }

        private fun takePendingAsk(): Boolean {
            if (!sUserAskPending) return false
            sUserAskPending = false
            return true
        }

        /** 读一个系统属性：先反射（最轻），再 exec `getprop`（隐藏 API 被拒时兜底）。 */
        private fun readProp(name: String): String? {
            try {
                val sp = Class.forName("android.os.SystemProperties")
                val v = sp.getMethod("get", String::class.java, String::class.java)
                        .invoke(null, name, "")
                if (v is String && v.trim().isNotEmpty()) {
                    return v.trim()
                }
            } catch (ignored: Throwable) {
            }
            return getprop(name)
        }

        private fun getprop(name: String): String? {
            var process: Process? = null
            try {
                val proc = ProcessBuilder("/system/bin/getprop", name)
                        .redirectErrorStream(true).start()
                process = proc
                try {
                    proc.waitFor(600, TimeUnit.MILLISECONDS)
                } catch (ignored: Throwable) {
                }
                val input: InputStream = proc.inputStream
                val buf = ByteArray(512)
                val n = input.read(buf)
                if (n <= 0) return null
                return String(buf, 0, n, StandardCharsets.UTF_8)
            } catch (t: Throwable) {
                return null
            } finally {
                if (process != null) {
                    try {
                        process!!.destroy()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        /**
         * 用户主动点「最新公告」这一轮的终态：真弹出来了就什么都不做，没弹就明说「暂无公告」。
         *
         * 不能用固定延时（请求最坏 6 秒）；这里挂在请求真正结束之后，
         * 而 `offer` 往主线程投递的 Runnable 先于它入队，所以顺序天然正确。
         */
        private fun userAskedFinished(userAskedIn: Boolean) {
            // 一次性消费「用户问过」标记：无论走哪条早退分支，都不会留到下一条自动检查里去误弹。
            val userAsked = userAskedIn || takePendingAsk()
            if (!userAsked) return
            MAIN.post(Runnable {
                if (sUserAskedServed) return@Runnable
                if (sShowingId != 0L) return@Runnable      // 已经有一条在脸上，别在底下再喊「暂无公告」
                // ★ 只有「用户主动点」这一条路会走到这里；自动检查/渲染永远不提示。
                val now = System.currentTimeMillis()
                if (now - sLastNoAnnounceAt < NO_ANNOUNCE_GAP_MS) return@Runnable
                sLastNoAnnounceAt = now
                toast("暂无公告")
            })
        }

        /** 轻量提示（「暂无公告」这种一次性反馈，不占对话框）。 */
        private fun toast(text: String) {
            try {
                val c = if (sApp != null) sApp else io.github.ldxm666.mapclean.App.ctx()
                if (c == null) return
                android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show()
            } catch (ignored: Throwable) {
            }
        }

        /* ══════════════════════════════════════════════════════════════ JSON / 签名 */

        private fun parse(body: String?): Msg? {
            if (body == null || body.isEmpty()) return null
            try {
                val o = JSONObject(body)
                val m = Msg()
                m.id = o.getLong("id")                       // 缺 id = 没有身份 = 直接放弃
                m.title = trim(o.optString("title", ""), 200)
                m.body = trim(o.optString("body", ""), 2000)
                val level = o.optString("level", "info")
                m.level = if ("force" == level || "warn" == level) level else "info"
                m.url = trim(o.optString("url", ""), 512)
                m.minVersion = o.optLong("minVersionCode", 0L)
                m.maxVersion = o.optLong("maxVersionCode", 0L)
                m.expireAt = o.optLong("expireAt", 0L)
                m.sig = o.optString("sig", "").trim()
                return m
            } catch (t: Throwable) {
                return null
            }
        }

        /** `HEX(HMAC-SHA256(SIG_KEY, "<id>|<title>|<body>"))`，大小写不敏感比对。 */
        private fun sigOk(m: Msg?): Boolean {
            if (m == null) return false
            // 空 sig（服务端缺密钥时就是这样）由 verifySig 按失败处理。
            return ServerFetch.verifySig(m.id.toString() + "|" + m.title + "|" + m.body, m.sig)
        }

        /* ══════════════════════════════════════════════════════════════ 展示 */

        /**
         * 在 UI 线程判一遍闸门，然后要么弹、要么存起来等宿主。
         *
         * @param fromUser 只有「用户主动点最新公告」这一条路是 true；自动路径必须为 false
         * @param from     调用点标记，写进 `event=announce_offer from=`，出问题一眼看出是谁弹的
         */
        private fun offer(msg: Msg?, fromUser: Boolean, from: String) {
            if (msg == null) return
            MAIN.post(Runnable {
                if (!passes(msg, fromUser, from)) return@Runnable
                val activity = activity()
                if (activity == null) {
                    sPending = msg            // 没有 Activity：存着，等面板打开 / 下次 resume
                    sPendingFromUser = fromUser
                    return@Runnable
                }
                showNow(activity, msg, fromUser, from)
            })
        }

        /** 展示前的闸门。返回 false 时已经记过 `announce_skip`。 */
        private fun passes(msg: Msg?, fromUser: Boolean, from: String): Boolean {
            if (msg == null) return false
            if (msg.title.isEmpty()) {
                // 服务端 --clear 会推 id 递增、标题正文皆空的公告：不展示，也**不推进 seen**，
                // 否则下一条真公告会被当成「已读」。
                skip("empty")
                return false
            }
            if (!enabled(null) && !fromUser) {
                skip("off")
                return false
            }
            if (!fromUser) {
                // ★ 自动路径的两道闸：已读水位 + 已关闭的 id。只有用户手动点能越过这两条。
                if (msg.id <= seen()) {
                    skip("seen")
                    return false
                }
                if (msg.id <= dismissedId()) {
                    if (mayLog("skip:dismissed", 6)) {
                        Log.i(TAG, "event=announce_skip reason=dismissed id=" + msg.id +
                                " from=" + from)
                    }
                    return false
                }
            }
            val nowSeconds = System.currentTimeMillis() / 1000L
            if (msg.expireAt > 0L && nowSeconds >= msg.expireAt) {
                skip("expired")
                return false
            }
            if (msg.minVersion > 0L && Version.CODE < msg.minVersion) {
                skip("version")
                return false
            }
            if (msg.maxVersion > 0L && Version.CODE > msg.maxVersion) {
                skip("version")
                return false
            }
            if (mayLog("offer", 30)) {
                Log.i(TAG, "event=announce_offer from=" + from +
                        " id=" + msg.id + " user=" + fromUser)
            }
            return true
        }

        /** 弹出对话框。调用方保证在 UI 线程且 `activity` 还活着。 */
        private fun showNow(activity: Activity?, msg: Msg?, fromUser: Boolean, from: String) {
            if (activity == null || msg == null) return
            if (sShowingId != 0L) {         // 已经有一条在脸上：排队，关掉后再弹
                sPending = msg
                sPendingFromUser = fromUser
                return
            }
            try {
                if (activity.isFinishing || activity.isDestroyed) return
            } catch (ignored: Throwable) {
            }
            sShowingId = msg.id
            sCloseVia = null
            sUserAskedServed = sUserAskedServed || fromUser   // 只有用户那条才算「他要的」
            rememberSeen(msg.id)           // 只在真正弹出去时推进水位：同一条永远不会提醒第二次
            // ★ 兜底计时器：窗口被系统吞掉时没有 dismiss 回调，90 秒后强制放行，避免排队死锁。
            //   原版这个方法定义了却**没有任何调用点**（死代码），这里接上，让它名副其实。
            scheduleShowingReset(msg.id)
            if (mayLog("show", 30)) {
                Log.i(TAG, "event=announce_show id=" + msg.id + " level=" + msg.level +
                        " from=" + from)
            }
            val shown = showDialog(activity, msg)
            if (!shown) {
                sShowingId = 0L
                sPending = msg             // 宿主窗口没了：留着下次再弹
                sPendingFromUser = fromUser
            }
        }

        /**
         * 真正画那个对话框：`AlertDialog` + [Skin] 自绘玻璃卡片，
         * 与 [FirstRunNotice] 完全同一套（标题粗体 / 正文 / 时间小字 / 按钮行）。
         *
         * **所有 level 都可关**：点「知道了」、点空白、按返回都行 —— 用户明确要求 force 也能关，
         * 所以这里没有 `setCancelable(false)`，也不拦返回键。
         */
        private fun showDialog(activity: Activity, msg: Msg): Boolean {
            try {
                val skin = Skin(activity, isNight(activity))
                val box = LinearLayout(activity)
                box.orientation = LinearLayout.VERTICAL
                box.background = skin.glassFor(box, Skin.R_CARD.toFloat())
                box.setPadding(skin.dp(Skin.S5.toFloat()), skin.dp(Skin.S5.toFloat()),
                        skin.dp(Skin.S5.toFloat()), skin.dp(Skin.S4.toFloat()))

                val prefix = if ("force" == msg.level) "【重要】"
                else if ("warn" == msg.level) "⚠ " else ""
                val title = skin.text(prefix + msg.title, Skin.T_TITLE, Skin.BOLD, skin.text)
                box.addView(title, skin.matchWrap())

                val body = skin.text(msg.body, 14f, Skin.REGULAR, skin.text2)
                body.setLineSpacing(skin.dp(3f).toFloat(), 1.18f)
                val bodyLp = skin.matchWrap()
                bodyLp.topMargin = skin.dp(Skin.S3.toFloat())
                box.addView(body, bodyLp)

                val meta = skin.text(metaLine(msg), Skin.T_LABEL, Skin.REGULAR, skin.text3)
                val metaLp = skin.matchWrap()
                metaLp.topMargin = skin.dp(Skin.S3.toFloat())
                box.addView(meta, metaLp)

                val url = if (allowed(msg.url)) msg.url else null
                val hideRef = arrayOfNulls<Runnable>(1)

                if (url != null) {
                    val detail = skin.secondaryButton("查看详情")
                    detail.setOnClickListener(object : View.OnClickListener {
                        override fun onClick(v: View) {
                            sCloseVia = "detail"
                            openUrl(activity, url)
                            hideRef[0]?.run()
                        }
                    })
                    val dlp = skin.matchWrap()
                    dlp.topMargin = skin.dp(Skin.S5.toFloat())
                    box.addView(detail, dlp)
                }

                val okButton = skin.primaryButton("知道了")
                okButton.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        sCloseVia = "ok"
                        hideRef[0]?.run()
                    }
                })
                val okLp = skin.matchWrap()
                okLp.topMargin = skin.dp((if (url != null) Skin.S3 else Skin.S5).toFloat())
                box.addView(okButton, okLp)

                val host = overlayHost(activity) ?: return false

                val overlay = FrameLayout(activity)
                // 遮罩自己持有 drawable 引用：入场/退场要和卡片一起做 alpha
                val dim = ColorDrawable(0xFF000000.toInt())   // 不透明黑，靠 alpha 动画到 45%（ColorDrawable 的 setAlpha 会和颜色 alpha 相乘）
                dim.alpha = 0
                overlay.background = dim
                overlay.isClickable = true

                val widthPx = activity.resources.displayMetrics.widthPixels
                val cardLp = FrameLayout.LayoutParams(
                        Math.min(widthPx - skin.dp(Skin.S7.toFloat()) * 2, skin.dp(340f)),
                        FrameLayout.LayoutParams.WRAP_CONTENT)
                cardLp.gravity = Gravity.CENTER
                overlay.addView(box, cardLp)

                val done = booleanArrayOf(false)
                val removeNow = Runnable {
                    if (done[0]) return@Runnable
                    done[0] = true
                    try {
                        host.removeView(overlay)
                    } catch (ignored: Throwable) {
                    }
                    if (mayLog("dismiss", 20)) {
                        Log.i(TAG, "event=announce_dismiss id=" + msg.id)
                    }
                    clearShowing(msg.id)
                }
                // 三条关闭路径（知道了 / 点空白 / 返回键）都从这里走：先播退场，动画结束再摘视图。
                // 动画起不来或回调不回来也不能关不掉 —— 400ms 兜底 post 一样会摘。
                val hide = Runnable {
                    try {
                        overlay.postDelayed(removeNow, 400L)
                        playOut(box, dim, removeNow)
                    } catch (ignored: Throwable) {
                        removeNow.run()
                    }
                }
                hideRef[0] = hide
                // 点空白关闭；卡片自己吃掉点击，点正文不会误关
                overlay.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        sCloseVia = "cancel"
                        hide.run()
                    }
                })
                box.isClickable = true
                // 返回键关闭（与原来 setCancelable(true) 等价）
                overlay.isFocusableInTouchMode = true
                overlay.setOnKeyListener(object : View.OnKeyListener {
                    override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
                        if (keyCode == KeyEvent.KEYCODE_BACK) {
                            sCloseVia = "cancel"
                            hide.run()
                            return true
                        }
                        return false
                    }
                })
                overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 宿主 Activity 被重建/销毁：别把 sShowingId 卡死，下一条公告还能弹
                        if (!done[0]) {
                            done[0] = true
                            clearShowing(msg.id)
                        }
                    }
                })

                // 卡片自己的软阴影：轮廓必须显式给成圆角，否则 setElevation 画出来的是方角投影。
                try {
                    val radiusPx = skin.dp(Skin.R_CARD.toFloat()).toFloat()
                    box.setOutlineProvider(object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: android.graphics.Outline) {
                            val r = Math.min(radiusPx, Math.min(v.width, v.height) / 2f)
                            outline.setRoundRect(0, 0, v.width, v.height, r)
                        }
                    })
                    skin.shadow(box, 8f)
                } catch (ignored: Throwable) {
                }

                host.addView(overlay, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                overlay.requestFocus()
                playIn(box, dim)        // 入场：0.94→1 + 淡入，和退场同一套曲线
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "event=announce_show_fail id=" + msg.id +
                        " err=" + t.javaClass.simpleName)
                return false
            }
        }

        /** 入场：卡片 0.94→1 + 淡入，遮罩同步淡入；失败就直接落到终态，绝不停在半透明。 */
        @JvmStatic
        fun playIn(card: View, dim: ColorDrawable) {
            try {
                card.alpha = 0f
                card.scaleX = 0.94f
                card.scaleY = 0.94f
                val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
                a.setDuration(150L)
                a.setInterpolator(android.view.animation.DecelerateInterpolator())
                a.addUpdateListener(android.animation.ValueAnimator.AnimatorUpdateListener { an ->
                    try {
                        val f = an.animatedValue as Float
                        card.alpha = f
                        val s = 0.94f + 0.06f * f
                        card.scaleX = s
                        card.scaleY = s
                        dim.alpha = (0x73 * f).toInt()
                    } catch (ignored: Throwable) {
                    }
                })
                a.start()
            } catch (ignored: Throwable) {
                card.alpha = 1f
                card.scaleX = 1f
                card.scaleY = 1f
                dim.alpha = 0x73
            }
        }

        /** 退场：卡片 1→0.96 + 淡出，遮罩同步淡出，130ms 后回调；失败直接回调，保证能关。 */
        @JvmStatic
        fun playOut(card: View, dim: ColorDrawable, end: Runnable) {
            try {
                val a = android.animation.ValueAnimator.ofFloat(1f, 0f)
                a.setDuration(130L)
                a.setInterpolator(android.view.animation.DecelerateInterpolator())
                a.addUpdateListener(android.animation.ValueAnimator.AnimatorUpdateListener { an ->
                    try {
                        val f = an.animatedValue as Float
                        card.alpha = f
                        card.scaleX = 0.96f + 0.04f * f
                        card.scaleY = 0.96f + 0.04f * f
                        dim.alpha = (0x73 * f).toInt()
                    } catch (ignored: Throwable) {
                    }
                })
                a.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(an: android.animation.Animator) {
                        try {
                            end.run()
                        } catch (ignored: Throwable) {
                        }
                    }
                })
                a.start()
            } catch (ignored: Throwable) {
                end.run()
            }
        }

        /** 浮层挂载点：Activity 的 DecorView，盖住整屏（含状态栏下方）。拿不到就返回 null。 */
        @JvmStatic
        fun overlayHost(activity: Activity?): ViewGroup? {
            if (activity == null) return null
            try {
                val w: Window? = activity.window
                val decor: View? = if (w != null) w.decorView else null
                if (decor is ViewGroup) return decor
            } catch (ignored: Throwable) {
            }
            return null
        }

        /** 时间 / 版本小字。id 是秒级 unix 时间戳；不在合理区间就只写等级与版本。 */
        private fun metaLine(msg: Msg): String {
            var whenText = ""
            try {
                val seconds = msg.id
                if (seconds > 946684800L && seconds < 4102444800L) {      // 2000-01-01 .. 2100-01-01
                    val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    whenText = f.format(Date(seconds * 1000L))
                }
            } catch (ignored: Throwable) {
            }
            val level = if ("force" == msg.level) "重要公告"
            else if ("warn" == msg.level) "提醒" else "公告"
            return (if (whenText.isNotEmpty()) whenText + " · " else "") + level +
                    " · 本机 v" + Version.NAME
        }

        /** 弹一条排队的公告（如果有），UI 线程。**过了闸门才弹**，dismiss 过的 id 在这里被拦下。 */
        private fun advance() {
            if (sShowingId != 0L) return
            val next = sPending ?: return
            val fromUser = sPendingFromUser
            sPending = null
            sPendingFromUser = false
            val activity = activity()
            if (activity == null) {
                sPending = next
                sPendingFromUser = fromUser
                return
            }
            if (!passes(next, fromUser, "advance")) return   // 关键：排队期间被 dismiss/已读的，就地丢掉
            showNow(activity, next, fromUser, "advance")
        }

        /**
         * 关闭之后的状态清理 —— 这里是「关了又自己弹回来」的唯一开关。
         *
         * ★ 三条铁律：
         * <ol>
         *   <li>`sPending` **一律清空**（不管是不是同一条）：重新展示只能由「下次面板打开 /
         *       下次 App 启动 / 用户点击」重新走一遍闸门，绝不从关窗的残渣里恢复；</li>
         *   <li>把 id 写进 `dismissed_id`：自动路径此后永远跳过它；</li>
         *   <li>只有 id 与当前展示一致时才动状态 —— 90 秒的兜底计时器可能是过期的。</li>
         * </ol>
         */
        private fun clearShowing(id: Long) {
            if (sShowingId != id) return
            sShowingId = 0L
            sPending = null
            sPendingFromUser = false
            if (id > dismissedId()) {
                sDismissedId = id
                try {
                    val p = prefs(sApp)
                    if (p != null) p.edit().putLong(P_DISMISSED, id).commit()
                } catch (ignored: Throwable) {
                }
            }
            if (mayLog("close", 30)) {
                Log.i(TAG, "event=announce_close id=" + id +
                        " via=" + (sCloseVia ?: "cancel"))
            }
            sCloseVia = null
            val changed = sOnChanged?.get()
            if (changed != null) {
                try {
                    changed.run()
                } catch (ignored: Throwable) {
                }
            }
            MAIN.post(Runnable { advance() })
        }

        /** `dismissed_id`：懒加载 + 进程内缓存。旧版本没有这个键 ⇒ 0（不影响 seen 的判断）。 */
        private fun dismissedId(): Long {
            val value = sDismissedId
            if (value >= 0L) return value
            var loaded: Long
            try {
                val p = prefs(sApp)
                loaded = if (p == null) 0L else p.getLong(P_DISMISSED, 0L)
            } catch (t: Throwable) {
                loaded = 0L
            }
            sDismissedId = loaded
            return loaded
        }

        /** 窗口被系统吞掉时没有 dismiss 回调：90 秒后强制放行，避免排队死锁。 */
        private fun scheduleShowingReset(id: Long) {
            MAIN.postDelayed(Runnable { clearShowing(id) }, 90L * 1000L)
        }

        private fun openUrl(context: Context, url: String?) {
            if (!allowed(url)) return
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (t: Throwable) {
                try {
                    android.widget.Toast.makeText(context, url, android.widget.Toast.LENGTH_LONG).show()
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun isNight(context: Context): Boolean {
            try {
                val cfg = context.resources.configuration
                return (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            } catch (t: Throwable) {
                return false
            }
        }

        /* ══════════════════════════════════════════════════════════════ 生命周期 */

        /** 注册一次 Activity 观察者。模块版走模块自己的 Application，内置版走宿主 Application。 */
        private fun watch(context: Context) {
            if (sWatched) return
            if (context !is Application) return
            try {
                context.registerActivityLifecycleCallbacks(
                        object : Application.ActivityLifecycleCallbacks {
                            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {
                            }

                            override fun onActivityStarted(a: Activity) {
                            }

                            override fun onActivityResumed(a: Activity) {
                                onResumed(a)
                            }

                            override fun onActivityPaused(a: Activity) {
                                val current = activity()
                                if (current === a) sResumed = null
                            }

                            override fun onActivityStopped(a: Activity) {
                            }

                            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {
                            }

                            override fun onActivityDestroyed(a: Activity) {
                                val current = activity()
                                if (current === a) sResumed = null
                            }
                        })
                sWatched = true
            } catch (t: Throwable) {
                Log.w(TAG, "event=announce_watch_fail err=" + t.javaClass.simpleName)
            }
        }

        private fun activity(): Activity? {
            val a = sResumed?.get() ?: return null
            try {
                if (a.isFinishing || a.isDestroyed) return null
            } catch (ignored: Throwable) {
            }
            return a
        }

        /* ══════════════════════════════════════════════════════════════ 存储 / 小工具 */

        private fun appOf(context: Context): Context {
            try {
                val app = context.applicationContext
                return app ?: context
            } catch (t: Throwable) {
                return context
            }
        }

        private fun prefs(context: Context?): SharedPreferences? {
            if (context == null) return null
            try {
                return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            } catch (t: Throwable) {
                return null
            }
        }

        private fun seen(): Long {
            return seen(if (sApp != null) sApp else io.github.ldxm666.mapclean.App.ctx())
        }

        private fun seen(context: Context?): Long {
            try {
                val p = prefs(if (context != null) context
                else (if (sApp != null) sApp else io.github.ldxm666.mapclean.App.ctx()))
                return if (p == null) 0L else p.getLong(K_SEEN_ID, 0L)
            } catch (t: Throwable) {
                return 0L
            }
        }

        private fun rememberSeen(id: Long) {
            try {
                val p = prefs(if (sApp != null) sApp else io.github.ldxm666.mapclean.App.ctx())
                if (p == null) return
                val current = p.getLong(K_SEEN_ID, 0L)
                if (id > current) p.edit().putLong(K_SEEN_ID, id).commit()
            } catch (ignored: Throwable) {
            }
        }

        /** 存原文 + **按 host 分开的** ETag。串用 ETag 会让域名请求拿到 IP 的 304。 */
        private fun cache(p: SharedPreferences, host: String?, body: String?, etag: String?) {
            try {
                val e = p.edit().putString(P_CACHE, body)
                if (etag != null && etag.isNotEmpty()) {
                    e.putString(P_ETAG_PREFIX + host, etag)
                }
                e.commit()
            } catch (ignored: Throwable) {
            }
        }

        /** 缓存里的公告（入库时已验签，这里再验一次 —— 缓存文件也可能被改）。 */
        private fun cached(context: Context?): Msg? {
            try {
                val p = prefs(context)
                if (p == null) return null
                val body = p.getString(P_CACHE, null)
                if (body == null || body.isEmpty()) return null
                val m = parse(body)
                return if (m != null && sigOk(m) && m.title.isNotEmpty()) m else null
            } catch (t: Throwable) {
                return null
            }
        }

        private fun online(context: Context): Boolean {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                if (cm == null) return true
                val info: NetworkInfo? = cm.activeNetworkInfo
                return info != null && info.isConnected
            } catch (t: Throwable) {
                return true      // 拿不到判据就交给请求自己失败（3 秒超时，代价可接受）
            }
        }

        private fun allowed(url: String?): Boolean {
            return ServerFetch.allowed(url)
        }

        private fun trim(value: String?, max: Int): String {
            if (value == null) return ""
            val s = value.trim()
            return if (s.length <= max) s else s.substring(0, max)
        }

        /* ══════════════════════════════════════════════════════════════ 日志限频 */

        private val LOGGED = HashMap<String, Int>()

        /** 同一类事件每进程最多 N 行：公告每次联网都会跑，不设闸就是刷屏。 */
        private fun mayLog(key: String, cap: Int): Boolean {
            synchronized(LOGGED) {
                val seen = LOGGED[key]
                val count = seen ?: 0
                if (count >= cap) return false
                LOGGED[key] = count + 1
                return true
            }
        }

        private fun skip(reason: String) {
            if (mayLog("skip:" + reason, 6)) {
                Log.i(TAG, "event=announce_skip reason=" + reason)
            }
        }
    }
}
