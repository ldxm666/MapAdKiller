package io.github.ldxm666.mapadkiller

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TreeMap

/**
 * 主动抓包（诊断用）。两种取法，都落一个 txt 到「内部存储/Download/MapClean/」——
 * 文件管理器点开「下载」就能看到，不用找 Android/data。
 *
 * <ul>
 *   <li><b>前 10 分钟</b>：立刻把内存里已经攒着的最近 10 分钟事件导出来，秒出文件；</li>
 *   <li><b>后 10 分钟</b>：从现在开始录 10 分钟（每 5 秒一条状态 + 全部模块事件），
 *       结束自动落盘，中途再点一次可提前结束。</li>
 * </ul>
 *
 * <h3>为什么在模块进程里做</h3>
 * <ul>
 *   <li>普通 App 读不到 logcat（READ_LOGS 是系统权限），但模块自己的每一条日志都经过
 *       [H.log]，在这里顺手抄一份内存环形缓冲即可；</li>
 *   <li>高德没有存储权限、Android 11+ 又不让直接写 /sdcard，所以走 MediaStore 的
 *       「下载」集合：不需要任何权限，写出来的文件在文件管理器里可见；</li>
 *   <li>全程 try/catch，任何异常都只丢一条日志，绝不影响宿主。</li>
 * </ul>
 *
 * <h3>输出内容</h3>
 * <ol>
 *   <li>头信息：机型 / 系统 / 包名 / 模块版本 / 抓包窗口；</li>
 *   <li>配置快照（开始 + 结束各一份，diff 出这段时间里被改过的开关）；</li>
 *   <li>宿主 `shared_prefs` 文件名+大小+时间戳指纹（开始/结束对比，能看出宿主写了哪些 xml）；</li>
 *   <li>窗口内事件；「前 10 分钟」就是环形缓冲里的历史。</li>
 * </ol>
 */
object CaptureLog {

    /** 一次抓包的时长：10 分钟。 */
    const val DURATION_MS = 10 * 60 * 1000L
    /** 后台环形缓冲：无论有没有在抓包都留着最近 10 分钟。 */
    private const val RING_MS = 10 * 60 * 1000L
    private const val RING_MAX = 6000
    private const val SAMPLE_MS = 5000L

    private val LOCK = Any()
    /** 每条都带 epoch 毫秒前缀：「1700000000123|11:33:20.123|I|tag|msg」。 */
    private val RING = ArrayDeque<String>()

    @Volatile
    private var live = false
    /**
     * 下面这批状态只在 [LOCK] 里写，但会被**任意线程**读（note 可能跑在宿主 UI 线程、
     * hook 线程、binder 线程上），所以全部加 volatile：不加的话写线程改了、读线程可能永远
     * 看到旧值 —— 典型症状是 workFile 已经就绪但 flush() 读到 null，于是整段抓包内容静默丢失。
     */
    @Volatile
    private var appCtx: Context? = null
    @Volatile
    private var startAt = 0L
    @Volatile
    private var endAt = 0L
    @Volatile
    private var lastFlush = 0L
    @Volatile
    private var workFile: File? = null
    @Volatile
    private var liveName = ""
    private val BODY = StringBuilder(64 * 1024)
    @Volatile
    private var cfgStart: Map<String, String>? = null
    @Volatile
    private var prefsStart: Map<String, String>? = null
    @Volatile
    private var lines = 0

    /**
     * 唯一的写盘线程。
     *
     * <p>为什么必须挪走：`note()` 由 `H.log()` 调用，而 H.log 会跑在**宿主 UI 线程**
     * 上；抓包进行中每次 flush 都是把整份 BODY（10 分钟后期可达数百 KB）重新写一遍。
     * 原来是同步写 —— 等于主线程每 2~5 秒做一次全量文件重写（O(n²)），表现为地图界面周期性
     * 卡顿，极端情况 ANR。现在只有拼快照留在调用线程（必须，BODY 是并发改的），
     * UTF-8 编码 + 打开/截断/写文件全部丢到这个单线程队列。
     *
     * <p>顺带解决并发截断：原来是两个线程各开一个 `FileOutputStream(f,false)` 写同一个
     * 文件，互相把对方的内容截掉；单线程 FIFO 天然不会重叠，且后写入的一定是更完整的快照。
     */
    private val WRITER: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    object : java.util.concurrent.ThreadFactory {
                        override fun newThread(task: Runnable): Thread {
                            val t = Thread(task, "mapclean-capture-io")
                            t.isDaemon = true
                            return t
                        }
                    })

    // ── 对外接口 ────────────────────────────────────────────────────────────────

    /** 「后 10 分钟」是否正在录。 */
    @JvmStatic
    fun running(): Boolean = live

    /** 剩余秒数（没在录时为 0）。 */
    @JvmStatic
    fun remainSeconds(): Int {
        if (!live) return 0
        val left = endAt - System.currentTimeMillis()
        return if (left <= 0) 0 else (left / 1000L).toInt()
    }

    /** 当前正在写的 txt 名（没在录时为空）。 */
    @JvmStatic
    fun currentName(): String = liveName

    /**
     * 「后 10 分钟」按钮：没在录就开始，已在录就结束（3 秒内的重复点算误触）。
     * @return true = 现在正在录，false = 已结束
     */
    @JvmStatic
    fun toggleLive(ctx: Context?): Boolean {
        if (live) {
            if (System.currentTimeMillis() - startAt < 3000L) return true
            stopLive(ctx, "manual")
            return false
        }
        startLive(ctx)
        return true
    }

    /** 「前 10 分钟」按钮：立刻把环形缓冲导成 txt，返回展示路径（失败返回 null）。 */
    @JvmStatic
    fun dumpRecent(ctx: Context?): String? {
        try {
            appCtx = ctx?.applicationContext
            val now = System.currentTimeMillis()
            val name = "mapclean_last10m_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".txt"
            val b = StringBuilder(64 * 1024)
            b.append("===== MapAdKiller 抓包：最近 10 分钟 =====\n")
            b.append("file=").append(name).append('\n')
            b.append("kind=前10分钟（点按钮之前的环形缓冲）\n")
            b.append("end=").append(ts(now)).append('\n')
            b.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" android=").append(Build.VERSION.RELEASE)
                    .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
            b.append("pkg=").append(if (ctx == null) "?" else ctx.packageName)
                    .append(" module=").append(moduleVersion()).append('\n')
            b.append("cfg_now:\n")
            for ((k, v) in cfgSnapshot()) {
                b.append("  ").append(k).append('=').append(v).append('\n')
            }
            b.append("shared_prefs_now: ").append(prefsSnapshot(ctx).size).append(" 个 xml\n\n")
            b.append("===== 事件 =====\n")
            var n = 0
            for (rec in ringCopy()) { b.append(pretty(rec)).append('\n'); n++ }
            b.append("\n===== 结束 =====\n")
            b.append("source=ring events=").append(n).append('\n')
            val path = publish(ctx, name, b.toString())
            H.log(Log.INFO, MainHook.TAG, "event=capture_dump kind=last10m events=" + n
                    + " path=" + path)
            return path
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_dump_fail err=" + t)
            return null
        }
    }

    /** 首页「运行日志」面板用：内存环形缓冲里最近 max 条事件（时间升序，已排版）。 */
    @JvmStatic
    fun recentText(max: Int): String {
        val b = StringBuilder(32 * 1024)
        val copy = ringCopy()
        val skip = Math.max(0, copy.size - Math.max(1, max))
        var i = 0
        for (rec in copy) {
            if (i++ < skip) continue
            b.append(pretty(rec)).append('\n')
        }
        return b.toString()
    }

    /** 首页「运行日志」面板用：环形缓冲当前条数（0 = 本进程还没有模块事件）。 */
    @JvmStatic
    fun recentCount(): Int {
        synchronized(RING) { return RING.size }
    }

    // ══════════════════════════════════════════════════════ 抓取结果反馈（v2.3.0）

    /** 通知渠道：抓取结果。自建渠道，用户能单独关掉它而不牵连模块的其他提示。 */
    private const val CHANNEL_RESULT = "mapclean_capture_result"
    /** 通知 id：固定值，同一次抓取的结果只会覆盖自己，不会在通知栏堆成一列。 */
    private const val NOTIFY_RESULT = 0x4D43

    /** 上一条已提示过的回执时间戳 + 结果，用于跨线程去重。 */
    @Volatile
    private var lastResultAt = 0L
    @Volatile
    private var lastResultOk = false

    /**
     * 抓取结束的统一反馈：一条**带数量**的结论 + 「前台 Toast / 后台通知栏」二选一。
     *
     * <p><b>为什么必须有</b>：抓取跑在宿主进程里（`mapadkiller.Baseline` 的后台线程），
     * 设置页这边只有一条广播回执。抓完之前界面上除了「正在抓取…」什么都没有，用户看不到
     * 成功就会一直等，最后判定成「卡死不动」。失败同理 —— 列表里那行「上次没抓全」太安静，
     * 用户根本注意不到，只会重复点抓取。
     *
     * <p><b>为什么前台 Toast、后台通知</b>：抓取期间用户基本都被切到高德去了，Android 12+
     * 对后台 Toast 有硬限制，弹不出来等于没提示；通知栏是「App 不在前台」时唯一还看得见的
     * 通道。Android 13+ 还需要 `POST_NOTIFICATIONS` 运行时授权，没授权时 notify()
     * 会被系统静默丢弃 —— 所以这里显式检查并打日志，让「没通知」在 logcat 里有据可查。
     *
     * @param ctx        任意 Context（内部取 ApplicationContext）
     * @param foreground 设置页此刻是否真的在前台（`hasWindowFocus()`）
     * @param ok         true=抓取成功，false=失败
     * @param keys       抓到的配置项数（宿主回执里的 files）
     * @param groups     分组明细，例如 `"高德 196"`
     * @param costMs     从点「开始抓取」到抓完的耗时；未知传 0
     * @param at         回执时间戳（宿主的 ts），用于去重
     * @param reason     失败原因（人话），成功传 null
     * @return true = 这次真的用 Toast 展示了（调用方可以据此自动退出页面）
     */
    @JvmStatic
    fun notifyResult(ctx: Context?, foreground: Boolean, ok: Boolean,
                     keys: Int, groups: String?, costMs: Long,
                     at: Long, reason: String?): Boolean {
        return try {
            notifyResult0(ctx, foreground, ok, keys, groups, costMs, at, reason)
        } catch (t: Throwable) {
            // 这个方法是从设置页心跳里调的：抛出去就会把整个心跳打断，
            // 首页那行状态从此不再刷新。这里兜住并留下证据。
            ev(Log.WARN, "event=capture_feedback_fail err=" + t)
            false
        }
    }

    /** notifyResult 的真正实现；任何异常都由上面那层兜住。 */
    private fun notifyResult0(ctx: Context?, foreground: Boolean, ok: Boolean,
                              keys: Int, groups: String?, costMs: Long,
                              at: Long, reason: String?): Boolean {
        val now = System.currentTimeMillis()
        val stamp = if (at > 0L) at else now
        // 去重：一次抓取宿主会连发 done + state 两条广播，设置页心跳又每 1.5 秒读一遍 prefs，
        // 不去重就会叠出三四条一模一样的提示 —— 用户会以为抓了好几次。
        synchronized(CaptureLog::class.java) {
            if (stamp == lastResultAt && ok == lastResultOk) {
                ev(Log.INFO, "event=capture_done_dup at=" + stamp)
                return false
            }
            lastResultAt = stamp
            lastResultOk = ok
        }
        val head = if (ok) "抓取完成" else "抓取失败"
        val body = if (ok)
            ("已抓取 " + keys + " 项配置"
                    + (if (groups == null || groups.isEmpty()) "" else "（" + prettyGroups(groups) + "）"))
        else
            ("抓取失败：" + (if (reason == null || reason.isEmpty()) "未知原因" else reason))
        // 日志契约：ok / keys / groups / ms / reason 一次打全，排障不必再去翻宿主那边的日志。
        ev(if (ok) Log.INFO else Log.WARN,
                "event=capture_done ok=" + ok
                        + " keys=" + keys
                        + " groups=" + (groups ?: "")
                        + " ms=" + Math.max(0L, costMs)
                        + (if (ok) "" else " reason=" + reason))
        var c: Context? = null
        try {
            c = ctx?.applicationContext
        } catch (t: Throwable) {
            // 单独兜：ctx 取不到只是少了提示，不能让调用方（设置页心跳）跟着炸。
            ev(Log.WARN, "event=capture_feedback_ctx_fail err=" + t)
        }
        if (c == null) return false
        // 成功用「结论 · 明细」，失败直接用那句已经在说「抓取失败：…」的正文 ——
        // 否则 Toast 会变成「抓取失败 · 抓取失败：…」，两个前缀叠在一起。
        val line = if (ok) (head + " · " + body) else body
        if (foreground && toastNow(c, line)) return true
        if (!postResult(c, head, body)) {
            // 通知没发出去（渠道被关 / 没授权）：退回 Toast。后台可能被系统丢掉，
            // 但总比什么都不弹强，而且上面已经留了 capture_notify_skip 的日志。
            toastNow(c, line)
        }
        return false
    }

    /**
     * 结果反馈专用的日志出口。
     *
     * <p><b>为什么不能直接用 `H.log`</b>：`H` 的静态字段里有
     * `XposedInterface.Hooker VOID/FALSE/TRUE`，而设置页进程的 APK 里没有
     * `io.github.libxposed.api.XposedInterface$Hooker`（那是 hook 侧的 API，
     * 只有宿主进程注入后才看得到）。于是在这里碰 `H` 会抛
     * `NoClassDefFoundError: io.github.ldxm666.mapadkiller.H` —— 真机实测：
     * 它把「抓取成功提示」整条链路打崩，只在 logcat 里留一行 capture_feedback_fail。
     * 所以结果反馈只走「标准 Log + 内存环形缓冲」，两条都不依赖 hook API。
     */
    private fun ev(priority: Int, msg: String) {
        try { note(priority, TAG_RESULT, msg) } catch (ignored: Throwable) { }
        try { Log.println(priority, TAG_RESULT, msg) } catch (ignored: Throwable) { }
    }

    /** 结果反馈的日志 TAG：和宿主侧一致，logcat 里一条 grep 就能串起来。 */
    private const val TAG_RESULT = "MapAdKiller"

    /**
     * 机器可读的分组串 → 人话。
     *
     * <p>日志里必须是一眼能 grep 的 `amap:197,baidu:12`（排障时按厂商统计最直接），
     * 而界面上给用户看的是「高德 197 / 百度 12」。两边各自最优，所以在这一处做转换，
     * 而不是让调用方各拼一份、拼歪了还看不出来。
     */
    private fun prettyGroups(groups: String): String {
        return try {
            var s = groups.replace(",", " / ")
            s = s.replace("amap:", "高德 ").replace("baidu:", "百度 ").replace("tmap:", "腾讯 ")
            s
        } catch (t: Throwable) {
            ev(Log.WARN, "event=capture_groups_fail err=" + t)
            groups
        }
    }

    /** 前台 Toast；返回是否真的弹出去了（异常一律记日志，不静默）。 */
    private fun toastNow(c: Context, text: String): Boolean {
        return try {
            android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show()
            true
        } catch (t: Throwable) {
            ev(Log.WARN, "event=capture_toast_fail err=" + t)
            false
        }
    }

    /**
     * 通知栏结果。App 不在前台时唯一可见的通道。
     *
     * <p>点通知回到设置页：首页那行已经是「已抓取 N 个配置文件」，用户点开就能核对，
     * 不用再把「抓完了吗」当成一个问题。
     */
    private fun postResult(c: Context, head: String, body: String): Boolean {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            if (nm == null) {
                ev(Log.WARN, "event=capture_notify_skip reason=no_manager")
                return false
            }
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                // 用户没授权：notify() 会被静默丢弃，这里必须先说清楚，否则排障时
                // 只会看到「代码调了 notify、通知栏什么都没有」。
                ev(Log.WARN, "event=capture_notify_skip reason=no_permission")
                return false
            }
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = android.app.NotificationChannel(
                        CHANNEL_RESULT, "抓取结果",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "抓取官方云端配置完成后在这里提示"
                nm.createNotificationChannel(ch)
            }
            val b = if (Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(c, CHANNEL_RESULT)
            else
                android.app.Notification.Builder(c)
            var pi: android.app.PendingIntent? = null
            try {
                val open = c.packageManager
                        .getLaunchIntentForPackage(c.packageName)
                if (open != null) {
                    var flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= 23) {
                        flags = flags or android.app.PendingIntent.FLAG_IMMUTABLE
                    }
                    pi = android.app.PendingIntent.getActivity(c, NOTIFY_RESULT, open, flags)
                }
            } catch (t: Throwable) {
                // 点不开只是少了个入口，通知本身还要发。
                ev(Log.WARN, "event=capture_notify_intent_fail err=" + t)
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(head)
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
            if (pi != null) b.setContentIntent(pi)
            nm.notify(NOTIFY_RESULT, b.build())
            ev(Log.INFO, "event=capture_notify_ok channel=" + CHANNEL_RESULT)
            return true
        } catch (t: Throwable) {
            ev(Log.WARN, "event=capture_notify_fail err=" + t)
            return false
        }
    }

    /** 由 [H.log] 每条日志调用；不在录时只维护环形缓冲。 */
    @JvmStatic
    fun note(priority: Int, tag: String?, msg: String?) {
        try {
            val now = System.currentTimeMillis()
            var m = msg
            // 协议体/URL 可能是几千字的巨行，txt 里留个头就够了，否则一份日志全是乱码长串
            if (m != null && m.length > 400) {
                m = m.substring(0, 400) + "…(len=" + m.length + ")"
            }
            val rec = now.toString() + "|" + ts(now) + "|" + prio(priority) + "|" +
                    (tag ?: "") + "|" + m
            synchronized(RING) {
                RING.addLast(rec)
                while (RING.size > RING_MAX) RING.removeFirst()
                val cut = now - RING_MS
                while (!RING.isEmpty()) {
                    val h: String? = RING.peekFirst()
                    val bar = if (h == null) -1 else h.indexOf('|')
                    var t = -1L
                    try { t = java.lang.Long.parseLong(h!!.substring(0, bar)) } catch (ignored: Throwable) {}
                    if (t > 0 && t < cut) { RING.removeFirst() } else { break }
                }
            }
            if (!live) return
            synchronized(LOCK) { append(pretty(rec)) }
            if (now - lastFlush >= 2000L) flush()
        } catch (ignored: Throwable) {}
    }

    // ── 后 10 分钟 ──────────────────────────────────────────────────────────────

    private fun startLive(ctx: Context?) {
        try {
            appCtx = ctx?.applicationContext
            synchronized(LOCK) {
                BODY.setLength(0)
                lines = 0
                startAt = System.currentTimeMillis()
                endAt = startAt + DURATION_MS
                val cfg = cfgSnapshot()
                cfgStart = cfg
                val prefs = prefsSnapshot(ctx)
                prefsStart = prefs
                liveName = "mapclean_capture_" +
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startAt)) +
                        ".txt"
                workFile = workFile(ctx, liveName)
                append("===== MapAdKiller 抓包：后 10 分钟 =====")
                append("file=" + liveName)
                append("window=从现在开始 10 分钟 start=" + ts(startAt) + " end=" + ts(endAt))
                append("device=" + Build.MANUFACTURER + " " + Build.MODEL
                        + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT)
                append("pkg=" + (if (ctx == null) "?" else ctx.packageName)
                        + " module=" + moduleVersion())
                append("cfg_at_start:")
                for ((k, v) in cfg) {
                    append("  " + k + "=" + v)
                }
                append("shared_prefs_at_start: " + prefs.size + " 个 xml（变化在结尾 diff）")
                append("")
                append("===== 事件 =====")
                live = true
                flush()
            }
            H.log(Log.INFO, MainHook.TAG, "event=capture_start file=" + liveName
                    + " dur_ms=" + DURATION_MS)
            HandlerHolder.post(object : Runnable {
                override fun run() { sample() }
            }, 0L)
        } catch (t: Throwable) {
            live = false
            H.log(Log.WARN, MainHook.TAG, "event=capture_start_fail err=" + t)
        }
    }

    private fun stopLive(ctx: Context?, reason: String) {
        try {
            var was = false
            synchronized(LOCK) {
                was = live
                live = false
            }
            if (!was) return
            val cfgDiff = diff(cfgStart, cfgSnapshot())
            val prefsDiff = diff(prefsStart, prefsSnapshot(ctx))
            var text = ""
            synchronized(LOCK) {
                append("")
                append("===== 结束 =====")
                append("reason=" + reason + " end=" + ts(System.currentTimeMillis()))
                append("cfg_changed:" + (if (cfgDiff.isEmpty()) " (无)" else ""))
                if (cfgDiff.isNotEmpty()) for (s in cfgDiff.split("\n")) append("  " + s)
                append("shared_prefs_changed:" + (if (prefsDiff.isEmpty()) " (无)" else ""))
                if (prefsDiff.isNotEmpty()) for (s in prefsDiff.split("\n")) append("  " + s)
                append("lines=" + lines)
                text = BODY.toString()
            }
            val path = publish(ctx, liveName, text)
            H.log(Log.INFO, MainHook.TAG, "event=capture_done reason=" + reason
                    + " file=" + liveName + " path=" + path + " lines=" + lines)
            toast("抓包完成：" + path)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_done_fail err=" + t)
        }
    }

    // ── 采样 ────────────────────────────────────────────────────────────────────

    private fun sample() {
        try {
            if (!live) return
            H.log(Log.INFO, MainHook.TAG, "event=capture_sample " + AmapCruise.diag())
            flush()
            if (System.currentTimeMillis() >= endAt) { stopLive(appCtx, "timeout"); return }
            HandlerHolder.post(object : Runnable {
                override fun run() { sample() }
            }, SAMPLE_MS)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_sample_fail err=" + t)
        }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────────

    /**
     * 把内容发布成用户找得到的文件。顺序：
     * 1) MediaStore「下载」集合 → 内部存储/Download/MapClean/（API 29+，免权限，文件管理器可见）
     * 2) 直接写 /sdcard/Download/MapClean/（有全盘权限时）
     * 3) App 自己的外部目录 Android/data/<pkg>/files/MapClean/（免权限）
     * 4) filesDir/capture（兜底）
     * @return 给用户看的路径（成功）或 null（全失败）
     */
    fun publish(ctx: Context?, name: String, text: String): String? {
        val data = text.toByteArray(Charset.forName("UTF-8"))
        // 1) MediaStore Downloads
        try {
            if (Build.VERSION.SDK_INT >= 29 && ctx != null) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MapClean")
                val uri: Uri? = ctx.contentResolver
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri != null) {
                    // try-with-resources：os.write() 抛异常（磁盘满 / provider 中途死亡）时，
                    // 原来的写法不关流也不删行 —— 每失败一次就留一个 0 字节的下载条目 + 一个
                    // 泄漏的 fd；反复抓包会累积成一排空文件。
                    val os: OutputStream? = ctx.contentResolver.openOutputStream(uri)
                    if (os != null) {
                        os.use { out ->
                            out.write(data)
                            return "内部存储/Download/MapClean/" + name
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "event=capture_publish_mediastore_fail err=" + t)
        }
        // 2) 直接路径
        try {
            val f = File("/sdcard/Download/MapClean", name)
            if (write(f, data)) return "内部存储/Download/MapClean/" + name
        } catch (ignored: Throwable) {}
        // 3) App 外部目录
        try {
            val ext = ctx?.getExternalFilesDir(null)
            if (ext != null) {
                val f = File(File(ext, "MapClean"), name)
                if (write(f, data)) return f.absolutePath
            }
        } catch (ignored: Throwable) {}
        // 4) 兜底
        try {
            val f = File(File(ctx!!.filesDir, "capture"), name)
            if (write(f, data)) return f.absolutePath
        } catch (ignored: Throwable) {}
        return null
    }

    /** 录制过程中反复写的临时文件（App 自己的外部目录，免权限、可反复覆盖）。 */
    private fun workFile(ctx: Context?, name: String): File? {
        try {
            val ext = ctx?.getExternalFilesDir(null)
            if (ext != null) {
                val d = File(ext, "MapClean")
                if (d.isDirectory || d.mkdirs()) return File(d, name)
            }
        } catch (ignored: Throwable) {}
        try {
            val d = File(ctx!!.filesDir, "capture")
            if (d.isDirectory || d.mkdirs()) return File(d, name)
        } catch (ignored: Throwable) {}
        return null
    }

    private fun write(f: File, data: ByteArray): Boolean {
        var o: FileOutputStream? = null
        try {
            val p = f.parentFile
            if (p != null && !p.isDirectory && !p.mkdirs()) return false
            val out = FileOutputStream(f, false)
            o = out
            out.write(data)
            out.flush()
            return true
        } catch (t: Throwable) {
            Log.w(MainHook.TAG, "event=capture_write_fail err=" + t)
            return false
        } finally {
            if (o != null) { try { o!!.close() } catch (ignored: Throwable) {} }
        }
    }

    private fun append(s: String?) {
        BODY.append(s).append('\n')
        lines++
    }

    private fun flush() {
        var f: File? = null
        var text: String? = null
        synchronized(LOCK) {
            val wf = workFile ?: return
            f = wf
            lastFlush = System.currentTimeMillis()
            // 快照必须留在调用线程取：BODY 是并发追加的，只有在这里（锁内）拿到的一致副本
            // 才不会被下一次 append 撕开。真正贵的是编码 + 开文件 + 截断 + 写盘，那部分下放。
            try { text = BODY.toString() } catch (t: Throwable) { return }
        }
        val target = f ?: return
        val payload = text ?: return
        // 这里不能走 H.log（会把这条再喂回 note，写盘失败时无限递归）
        try {
            WRITER.execute(object : Runnable {
                override fun run() {
                    // 已经 stop（或者已经开了新一轮抓包）就丢掉这份中间快照：
                    // stopLive 会在锁内拼出完整文本并同步 publish 成品，中途快照没有任何价值；
                    // 而它要是落在 publish 之后，就会把已经写好的成品截回旧内容。
                    if (!live || target !== workFile) return
                    try {
                        write(target, payload.toByteArray(Charset.forName("UTF-8")))
                    } catch (ignored: Throwable) { }
                }
            })
        } catch (ignored: Throwable) { }
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────────

    private fun ringCopy(): ArrayDeque<String> {
        synchronized(RING) { return ArrayDeque(RING) }
    }

    private fun moduleVersion(): String {
        try { return io.github.ldxm666.mapclean.Version.NAME } catch (ignored: Throwable) {}
        return "2.2.6"
    }

    private fun toast(text: String) {
        try {
            val c = appCtx
            if (c == null) { Log.i(MainHook.TAG, "event=capture_toast text=" + text); return }
            android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show()
        } catch (ignored: Throwable) {}
    }

    private fun cfgSnapshot(): Map<String, String> {
        val m = TreeMap<String, String>()
        try {
            for ((k, v) in Config.snapshot()) {
                if (k.startsWith("tool_id_") || k.startsWith(Config.K_TOOL_PREFIX)) continue
                m.put(k, v.toString())
            }
        } catch (ignored: Throwable) {}
        return m
    }

    private fun prefsSnapshot(ctx: Context?): Map<String, String> {
        val m = TreeMap<String, String>()
        try {
            val dir: File? = if (ctx == null) null
                    else File(ctx.applicationInfo.dataDir, "shared_prefs")
            val fs = dir?.listFiles() ?: return m
            for (f in fs) {
                if (f == null || !f.name.endsWith(".xml")) continue
                m.put(f.name, f.length().toString() + "B@" + f.lastModified())
            }
        } catch (ignored: Throwable) {}
        return m
    }

    private fun diff(a0: Map<String, String>?, b0: Map<String, String>?): String {
        val s = StringBuilder()
        try {
            val a = a0 ?: TreeMap<String, String>()
            val b = b0 ?: TreeMap<String, String>()
            val keys = TreeMap(a)
            for (k in b.keys) keys[k] = ""
            for (k in keys.keys) {
                val old = a[k]
                val now = b[k]
                if (old == null && now != null) s.append(k).append(": (新增) ").append(now).append('\n')
                else if (old != null && now == null) s.append(k).append(": (删除) 原 ").append(old).append('\n')
                else if (old != null && old != now) s.append(k).append(": ").append(old)
                        .append(" -> ").append(now).append('\n')
            }
        } catch (ignored: Throwable) {}
        return s.toString().trim()
    }

    private fun pretty(rec: String): String {
        return try {
            val a = rec.indexOf('|')
            val b = rec.indexOf('|', a + 1)
            val c = rec.indexOf('|', b + 1)
            val d = rec.indexOf('|', c + 1)
            "[" + rec.substring(a + 1, b) + "] " + rec.substring(b + 1, c) +
                    " " + rec.substring(c + 1, d) + ": " + rec.substring(d + 1)
        } catch (t: Throwable) {
            rec
        }
    }

    private fun prio(p: Int): String {
        when (p) {
            Log.VERBOSE -> return "V"
            Log.DEBUG -> return "D"
            Log.INFO -> return "I"
            Log.WARN -> return "W"
            Log.ERROR -> return "E"
            else -> return "A"
        }
    }

    private fun ts(ms: Long): String {
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    }

    /** 延时执行：优先主线程 Handler，拿不到时退回后台线程 sleep。 */
    private object HandlerHolder {
        @Volatile
        private var H: android.os.Handler? = null

        fun post(r: Runnable, delayMs: Long) {
            try {
                var h = H
                if (h == null) {
                    h = android.os.Handler(android.os.Looper.getMainLooper())
                    H = h
                }
                h.postDelayed(r, delayMs)
                return
            } catch (ignored: Throwable) {}
            try {
                Thread(object : Runnable {
                    override fun run() {
                        try { Thread.sleep(Math.max(0L, delayMs)) } catch (ignored: Throwable) {}
                        r.run()
                    }
                }, "mapclean-capture").start()
            } catch (ignored: Throwable) {}
        }
    }
}
