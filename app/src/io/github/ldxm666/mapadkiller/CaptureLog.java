package io.github.ldxm666.mapadkiller;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
 *       {@link H#log(int, String, String)}，在这里顺手抄一份内存环形缓冲即可；</li>
 *   <li>高德没有存储权限、Android 11+ 又不让直接写 /sdcard，所以走 MediaStore 的
 *       「下载」集合：不需要任何权限，写出来的文件在文件管理器里可见；</li>
 *   <li>全程 try/catch，任何异常都只丢一条日志，绝不影响宿主。</li>
 * </ul>
 *
 * <h3>输出内容</h3>
 * <ol>
 *   <li>头信息：机型 / 系统 / 包名 / 模块版本 / 抓包窗口；</li>
 *   <li>配置快照（开始 + 结束各一份，diff 出这段时间里被改过的开关）；</li>
 *   <li>宿主 {@code shared_prefs} 文件名+大小+时间戳指纹（开始/结束对比，能看出宿主写了哪些 xml）；</li>
 *   <li>窗口内事件；「前 10 分钟」就是环形缓冲里的历史。</li>
 * </ol>
 */
public final class CaptureLog {

    private CaptureLog() {}

    /** 一次抓包的时长：10 分钟。 */
    static final long DURATION_MS = 10 * 60 * 1000L;
    /** 后台环形缓冲：无论有没有在抓包都留着最近 10 分钟。 */
    private static final long RING_MS = 10 * 60 * 1000L;
    private static final int RING_MAX = 6000;
    private static final long SAMPLE_MS = 5000L;

    private static final Object LOCK = new Object();
    /** 每条都带 epoch 毫秒前缀：「1700000000123|11:33:20.123|I|tag|msg」。 */
    private static final ArrayDeque<String> RING = new ArrayDeque<>();

    private static volatile boolean live;
    /**
     * 下面这批状态只在 {@link #LOCK} 里写，但会被**任意线程**读（note 可能跑在宿主 UI 线程、
     * hook 线程、binder 线程上），所以全部加 volatile：不加的话写线程改了、读线程可能永远
     * 看到旧值 —— 典型症状是 workFile 已经就绪但 flush() 读到 null，于是整段抓包内容静默丢失。
     */
    private static volatile Context appCtx;
    private static volatile long startAt;
    private static volatile long endAt;
    private static volatile long lastFlush;
    private static volatile File workFile;
    private static volatile String liveName = "";
    private static final StringBuilder BODY = new StringBuilder(64 * 1024);
    private static volatile Map<String, String> cfgStart;
    private static volatile Map<String, String> prefsStart;
    private static volatile int lines;

    /**
     * 唯一的写盘线程。
     *
     * <p>为什么必须挪走：{@code note()} 由 {@code H.log()} 调用，而 H.log 会跑在**宿主 UI 线程**
     * 上；抓包进行中每次 flush 都是把整份 BODY（10 分钟后期可达数百 KB）重新写一遍。
     * 原来是同步写 —— 等于主线程每 2~5 秒做一次全量文件重写（O(n²)），表现为地图界面周期性
     * 卡顿，极端情况 ANR。现在只有拼快照留在调用线程（必须，BODY 是并发改的），
     * UTF-8 编码 + 打开/截断/写文件全部丢到这个单线程队列。
     *
     * <p>顺带解决并发截断：原来是两个线程各开一个 {@code FileOutputStream(f,false)} 写同一个
     * 文件，互相把对方的内容截掉；单线程 FIFO 天然不会重叠，且后写入的一定是更完整的快照。
     */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable task) {
                            Thread t = new Thread(task, "mapclean-capture-io");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    // ── 对外接口 ────────────────────────────────────────────────────────────────

    /** 「后 10 分钟」是否正在录。 */
    public static boolean running() { return live; }

    /** 剩余秒数（没在录时为 0）。 */
    public static int remainSeconds() {
        if (!live) return 0;
        long left = endAt - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000L);
    }

    /** 当前正在写的 txt 名（没在录时为空）。 */
    public static String currentName() { return liveName; }

    /**
     * 「后 10 分钟」按钮：没在录就开始，已在录就结束（3 秒内的重复点算误触）。
     * @return true = 现在正在录，false = 已结束
     */
    public static boolean toggleLive(Context ctx) {
        if (live) {
            if (System.currentTimeMillis() - startAt < 3000L) return true;
            stopLive(ctx, "manual");
            return false;
        }
        startLive(ctx);
        return true;
    }

    /** 「前 10 分钟」按钮：立刻把环形缓冲导成 txt，返回展示路径（失败返回 null）。 */
    public static String dumpRecent(Context ctx) {
        try {
            appCtx = ctx == null ? null : ctx.getApplicationContext();
            long now = System.currentTimeMillis();
            String name = "mapclean_last10m_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now)) + ".txt";
            StringBuilder b = new StringBuilder(64 * 1024);
            b.append("===== MapAdKiller 抓包：最近 10 分钟 =====\n");
            b.append("file=").append(name).append('\n');
            b.append("kind=前10分钟（点按钮之前的环形缓冲）\n");
            b.append("end=").append(ts(now)).append('\n');
            b.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" android=").append(Build.VERSION.RELEASE)
                    .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
            b.append("pkg=").append(ctx == null ? "?" : ctx.getPackageName())
                    .append(" module=").append(moduleVersion()).append('\n');
            b.append("cfg_now:\n");
            for (Map.Entry<String, String> e : cfgSnapshot().entrySet()) {
                b.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            b.append("shared_prefs_now: ").append(prefsSnapshot(ctx).size()).append(" 个 xml\n\n");
            b.append("===== 事件 =====\n");
            int n = 0;
            for (String rec : ringCopy()) { b.append(pretty(rec)).append('\n'); n++; }
            b.append("\n===== 结束 =====\n");
            b.append("source=ring events=").append(n).append('\n');
            String path = publish(ctx, name, b.toString());
            H.log(Log.INFO, MainHook.TAG, "event=capture_dump kind=last10m events=" + n
                    + " path=" + path);
            return path;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_dump_fail err=" + t);
            return null;
        }
    }

    /** 首页「运行日志」面板用：内存环形缓冲里最近 max 条事件（时间升序，已排版）。 */
    public static String recentText(int max) {
        StringBuilder b = new StringBuilder(32 * 1024);
        ArrayDeque<String> copy = ringCopy();
        int skip = Math.max(0, copy.size() - Math.max(1, max));
        int i = 0;
        for (String rec : copy) {
            if (i++ < skip) continue;
            b.append(pretty(rec)).append('\n');
        }
        return b.toString();
    }

    /** 首页「运行日志」面板用：环形缓冲当前条数（0 = 本进程还没有模块事件）。 */
    public static int recentCount() {
        synchronized (RING) { return RING.size(); }
    }

    // ══════════════════════════════════════════════════════ 抓取结果反馈（v2.3.0）

    /** 通知渠道：抓取结果。自建渠道，用户能单独关掉它而不牵连模块的其他提示。 */
    private static final String CHANNEL_RESULT = "mapclean_capture_result";
    /** 通知 id：固定值，同一次抓取的结果只会覆盖自己，不会在通知栏堆成一列。 */
    private static final int NOTIFY_RESULT = 0x4D43;

    /** 上一条已提示过的回执时间戳 + 结果，用于跨线程去重。 */
    private static volatile long lastResultAt;
    private static volatile boolean lastResultOk;

    /**
     * 抓取结束的统一反馈：一条**带数量**的结论 + 「前台 Toast / 后台通知栏」二选一。
     *
     * <p><b>为什么必须有</b>：抓取跑在宿主进程里（{@code mapadkiller.Baseline} 的后台线程），
     * 设置页这边只有一条广播回执。抓完之前界面上除了「正在抓取…」什么都没有，用户看不到
     * 成功就会一直等，最后判定成「卡死不动」。失败同理 —— 列表里那行「上次没抓全」太安静，
     * 用户根本注意不到，只会重复点抓取。
     *
     * <p><b>为什么前台 Toast、后台通知</b>：抓取期间用户基本都被切到高德去了，Android 12+
     * 对后台 Toast 有硬限制，弹不出来等于没提示；通知栏是「App 不在前台」时唯一还看得见的
     * 通道。Android 13+ 还需要 {@code POST_NOTIFICATIONS} 运行时授权，没授权时 notify()
     * 会被系统静默丢弃 —— 所以这里显式检查并打日志，让「没通知」在 logcat 里有据可查。
     *
     * @param ctx        任意 Context（内部取 ApplicationContext）
     * @param foreground 设置页此刻是否真的在前台（{@code hasWindowFocus()}）
     * @param ok         true=抓取成功，false=失败
     * @param keys       抓到的配置项数（宿主回执里的 files）
     * @param groups     分组明细，例如 {@code "高德 196"}
     * @param costMs     从点「开始抓取」到抓完的耗时；未知传 0
     * @param at         回执时间戳（宿主的 ts），用于去重
     * @param reason     失败原因（人话），成功传 null
     * @return true = 这次真的用 Toast 展示了（调用方可以据此自动退出页面）
     */
    public static boolean notifyResult(Context ctx, boolean foreground, boolean ok,
                                       int keys, String groups, long costMs,
                                       long at, String reason) {
        try {
            return notifyResult0(ctx, foreground, ok, keys, groups, costMs, at, reason);
        } catch (Throwable t) {
            // 这个方法是从设置页心跳里调的：抛出去就会把整个心跳打断，
            // 首页那行状态从此不再刷新。这里兜住并留下证据。
            ev(Log.WARN, "event=capture_feedback_fail err=" + t);
            return false;
        }
    }

    /** notifyResult 的真正实现；任何异常都由上面那层兜住。 */
    private static boolean notifyResult0(Context ctx, boolean foreground, boolean ok,
                                         int keys, String groups, long costMs,
                                         long at, String reason) {
        final long now = System.currentTimeMillis();
        final long stamp = at > 0L ? at : now;
        // 去重：一次抓取宿主会连发 done + state 两条广播，设置页心跳又每 1.5 秒读一遍 prefs，
        // 不去重就会叠出三四条一模一样的提示 —— 用户会以为抓了好几次。
        synchronized (CaptureLog.class) {
            if (stamp == lastResultAt && ok == lastResultOk) {
                ev(Log.INFO, "event=capture_done_dup at=" + stamp);
                return false;
            }
            lastResultAt = stamp;
            lastResultOk = ok;
        }
        final String head = ok ? "抓取完成" : "抓取失败";
        final String body = ok
                ? ("已抓取 " + keys + " 项配置"
                    + (groups == null || groups.length() == 0 ? "" : "（" + prettyGroups(groups) + "）"))
                : ("抓取失败：" + (reason == null || reason.length() == 0 ? "未知原因" : reason));
        // 日志契约：ok / keys / groups / ms / reason 一次打全，排障不必再去翻宿主那边的日志。
        ev(ok ? Log.INFO : Log.WARN,
                "event=capture_done ok=" + ok
                        + " keys=" + keys
                        + " groups=" + (groups == null ? "" : groups)
                        + " ms=" + Math.max(0L, costMs)
                        + (ok ? "" : " reason=" + reason));
        Context c = null;
        try {
            c = ctx == null ? null : ctx.getApplicationContext();
        } catch (Throwable t) {
            // 单独兜：ctx 取不到只是少了提示，不能让调用方（设置页心跳）跟着炸。
            ev(Log.WARN, "event=capture_feedback_ctx_fail err=" + t);
        }
        if (c == null) return false;
        // 成功用「结论 · 明细」，失败直接用那句已经在说「抓取失败：…」的正文 ——
        // 否则 Toast 会变成「抓取失败 · 抓取失败：…」，两个前缀叠在一起。
        final String line = ok ? (head + " · " + body) : body;
        if (foreground && toastNow(c, line)) return true;
        if (!postResult(c, head, body)) {
            // 通知没发出去（渠道被关 / 没授权）：退回 Toast。后台可能被系统丢掉，
            // 但总比什么都不弹强，而且上面已经留了 capture_notify_skip 的日志。
            toastNow(c, line);
        }
        return false;
    }

    /**
     * 结果反馈专用的日志出口。
     *
     * <p><b>为什么不能直接用 {@code H.log}</b>：{@code H} 的静态字段里有
     * {@code XposedInterface.Hooker VOID/FALSE/TRUE}，而设置页进程的 APK 里没有
     * {@code io.github.libxposed.api.XposedInterface$Hooker}（那是 hook 侧的 API，
     * 只有宿主进程注入后才看得到）。于是在这里碰 {@code H} 会抛
     * {@code NoClassDefFoundError: io.github.ldxm666.mapadkiller.H} —— 真机实测：
     * 它把「抓取成功提示」整条链路打崩，只在 logcat 里留一行 capture_feedback_fail。
     * 所以结果反馈只走「标准 Log + 内存环形缓冲」，两条都不依赖 hook API。
     */
    private static void ev(int priority, String msg) {
        try { note(priority, TAG_RESULT, msg); } catch (Throwable ignored) { }
        try { Log.println(priority, TAG_RESULT, msg); } catch (Throwable ignored) { }
    }

    /** 结果反馈的日志 TAG：和宿主侧一致，logcat 里一条 grep 就能串起来。 */
    private static final String TAG_RESULT = "MapAdKiller";

    /**
     * 机器可读的分组串 → 人话。
     *
     * <p>日志里必须是一眼能 grep 的 {@code amap:197,baidu:12}（排障时按厂商统计最直接），
     * 而界面上给用户看的是「高德 197 / 百度 12」。两边各自最优，所以在这一处做转换，
     * 而不是让调用方各拼一份、拼歪了还看不出来。
     */
    private static String prettyGroups(String groups) {
        try {
            String s = groups.replace(",", " / ");
            s = s.replace("amap:", "高德 ").replace("baidu:", "百度 ").replace("tmap:", "腾讯 ");
            return s;
        } catch (Throwable t) {
            ev(Log.WARN, "event=capture_groups_fail err=" + t);
            return groups;
        }
    }

    /** 前台 Toast；返回是否真的弹出去了（异常一律记日志，不静默）。 */
    private static boolean toastNow(Context c, String text) {
        try {
            android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show();
            return true;
        } catch (Throwable t) {
            ev(Log.WARN, "event=capture_toast_fail err=" + t);
            return false;
        }
    }

    /**
     * 通知栏结果。App 不在前台时唯一可见的通道。
     *
     * <p>点通知回到设置页：首页那行已经是「已抓取 N 个配置文件」，用户点开就能核对，
     * 不用再把「抓完了吗」当成一个问题。
     */
    private static boolean postResult(Context c, String head, String body) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                ev(Log.WARN, "event=capture_notify_skip reason=no_manager");
                return false;
            }
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 用户没授权：notify() 会被静默丢弃，这里必须先说清楚，否则排障时
                // 只会看到「代码调了 notify、通知栏什么都没有」。
                ev(Log.WARN, "event=capture_notify_skip reason=no_permission");
                return false;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        CHANNEL_RESULT, "抓取结果",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("抓取官方云端配置完成后在这里提示");
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, CHANNEL_RESULT)
                    : new android.app.Notification.Builder(c);
            android.app.PendingIntent pi = null;
            try {
                android.content.Intent open = c.getPackageManager()
                        .getLaunchIntentForPackage(c.getPackageName());
                if (open != null) {
                    int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 23) {
                        flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                    }
                    pi = android.app.PendingIntent.getActivity(c, NOTIFY_RESULT, open, flags);
                }
            } catch (Throwable t) {
                // 点不开只是少了个入口，通知本身还要发。
                ev(Log.WARN, "event=capture_notify_intent_fail err=" + t);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(head)
                    .setContentText(body)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            if (pi != null) b.setContentIntent(pi);
            nm.notify(NOTIFY_RESULT, b.build());
            ev(Log.INFO, "event=capture_notify_ok channel=" + CHANNEL_RESULT);
            return true;
        } catch (Throwable t) {
            ev(Log.WARN, "event=capture_notify_fail err=" + t);
            return false;
        }
    }

    /** 由 {@link H#log(int, String, String)} 每条日志调用；不在录时只维护环形缓冲。 */
    public static void note(int priority, String tag, String msg) {
        try {
            long now = System.currentTimeMillis();
            // 协议体/URL 可能是几千字的巨行，txt 里留个头就够了，否则一份日志全是乱码长串
            if (msg != null && msg.length() > 400) {
                msg = msg.substring(0, 400) + "…(len=" + msg.length() + ")";
            }
            String rec = now + "|" + ts(now) + "|" + prio(priority) + "|"
                    + (tag == null ? "" : tag) + "|" + msg;
            synchronized (RING) {
                RING.addLast(rec);
                while (RING.size() > RING_MAX) RING.removeFirst();
                long cut = now - RING_MS;
                while (!RING.isEmpty()) {
                    String h = RING.peekFirst();
                    int bar = h == null ? -1 : h.indexOf('|');
                    long t = -1;
                    try { t = Long.parseLong(h.substring(0, bar)); } catch (Throwable ignored) {}
                    if (t > 0 && t < cut) RING.removeFirst(); else break;
                }
            }
            if (!live) return;
            synchronized (LOCK) { append(pretty(rec)); }
            if (now - lastFlush >= 2000L) flush();
        } catch (Throwable ignored) {}
    }

    // ── 后 10 分钟 ──────────────────────────────────────────────────────────────

    private static void startLive(Context ctx) {
        try {
            appCtx = ctx == null ? null : ctx.getApplicationContext();
            synchronized (LOCK) {
                BODY.setLength(0);
                lines = 0;
                startAt = System.currentTimeMillis();
                endAt = startAt + DURATION_MS;
                cfgStart = cfgSnapshot();
                prefsStart = prefsSnapshot(ctx);
                liveName = "mapclean_capture_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startAt))
                        + ".txt";
                workFile = workFile(ctx, liveName);
                append("===== MapAdKiller 抓包：后 10 分钟 =====");
                append("file=" + liveName);
                append("window=从现在开始 10 分钟 start=" + ts(startAt) + " end=" + ts(endAt));
                append("device=" + Build.MANUFACTURER + " " + Build.MODEL
                        + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT);
                append("pkg=" + (ctx == null ? "?" : ctx.getPackageName())
                        + " module=" + moduleVersion());
                append("cfg_at_start:");
                for (Map.Entry<String, String> e : cfgStart.entrySet()) {
                    append("  " + e.getKey() + "=" + e.getValue());
                }
                append("shared_prefs_at_start: " + prefsStart.size() + " 个 xml（变化在结尾 diff）");
                append("");
                append("===== 事件 =====");
                live = true;
                flush();
            }
            H.log(Log.INFO, MainHook.TAG, "event=capture_start file=" + liveName
                    + " dur_ms=" + DURATION_MS);
            HandlerHolder.post(new Runnable() {
                @Override public void run() { sample(); }
            }, 0L);
        } catch (Throwable t) {
            live = false;
            H.log(Log.WARN, MainHook.TAG, "event=capture_start_fail err=" + t);
        }
    }

    private static void stopLive(Context ctx, String reason) {
        try {
            boolean was;
            synchronized (LOCK) {
                was = live;
                live = false;
            }
            if (!was) return;
            String cfgDiff = diff(cfgStart, cfgSnapshot());
            String prefsDiff = diff(prefsStart, prefsSnapshot(ctx));
            String text;
            synchronized (LOCK) {
                append("");
                append("===== 结束 =====");
                append("reason=" + reason + " end=" + ts(System.currentTimeMillis()));
                append("cfg_changed:" + (cfgDiff.isEmpty() ? " (无)" : ""));
                if (!cfgDiff.isEmpty()) for (String s : cfgDiff.split("\n")) append("  " + s);
                append("shared_prefs_changed:" + (prefsDiff.isEmpty() ? " (无)" : ""));
                if (!prefsDiff.isEmpty()) for (String s : prefsDiff.split("\n")) append("  " + s);
                append("lines=" + lines);
                text = BODY.toString();
            }
            String path = publish(ctx, liveName, text);
            H.log(Log.INFO, MainHook.TAG, "event=capture_done reason=" + reason
                    + " file=" + liveName + " path=" + path + " lines=" + lines);
            toast("抓包完成：" + path);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_done_fail err=" + t);
        }
    }

    // ── 采样 ────────────────────────────────────────────────────────────────────

    private static void sample() {
        try {
            if (!live) return;
            H.log(Log.INFO, MainHook.TAG, "event=capture_sample " + AmapCruise.diag());
            flush();
            if (System.currentTimeMillis() >= endAt) { stopLive(appCtx, "timeout"); return; }
            HandlerHolder.post(new Runnable() {
                @Override public void run() { sample(); }
            }, SAMPLE_MS);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "event=capture_sample_fail err=" + t);
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
    static String publish(Context ctx, String name, String text) {
        byte[] data = text.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        // 1) MediaStore Downloads
        try {
            if (Build.VERSION.SDK_INT >= 29 && ctx != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MapClean");
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    // try-with-resources：os.write() 抛异常（磁盘满 / provider 中途死亡）时，
                    // 原来的写法不关流也不删行 —— 每失败一次就留一个 0 字节的下载条目 + 一个
                    // 泄漏的 fd；反复抓包会累积成一排空文件。
                    try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(data);
                            return "内部存储/Download/MapClean/" + name;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(MainHook.TAG, "event=capture_publish_mediastore_fail err=" + t);
        }
        // 2) 直接路径
        try {
            File f = new File("/sdcard/Download/MapClean", name);
            if (write(f, data)) return "内部存储/Download/MapClean/" + name;
        } catch (Throwable ignored) {}
        // 3) App 外部目录
        try {
            File ext = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (ext != null) {
                File f = new File(new File(ext, "MapClean"), name);
                if (write(f, data)) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {}
        // 4) 兜底
        try {
            File f = new File(new File(ctx.getFilesDir(), "capture"), name);
            if (write(f, data)) return f.getAbsolutePath();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 录制过程中反复写的临时文件（App 自己的外部目录，免权限、可反复覆盖）。 */
    private static File workFile(Context ctx, String name) {
        try {
            File ext = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (ext != null) {
                File d = new File(ext, "MapClean");
                if (d.isDirectory() || d.mkdirs()) return new File(d, name);
            }
        } catch (Throwable ignored) {}
        try {
            File d = new File(ctx.getFilesDir(), "capture");
            if (d.isDirectory() || d.mkdirs()) return new File(d, name);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean write(File f, byte[] data) {
        FileOutputStream o = null;
        try {
            File p = f.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs()) return false;
            o = new FileOutputStream(f, false);
            o.write(data);
            o.flush();
            return true;
        } catch (Throwable t) {
            Log.w(MainHook.TAG, "event=capture_write_fail err=" + t);
            return false;
        } finally {
            if (o != null) try { o.close(); } catch (Throwable ignored) {}
        }
    }

    private static void append(String s) {
        BODY.append(s).append('\n');
        lines++;
    }

    private static void flush() {
        final File f;
        final String text;
        synchronized (LOCK) {
            f = workFile;
            if (f == null) return;
            lastFlush = System.currentTimeMillis();
            // 快照必须留在调用线程取：BODY 是并发追加的，只有在这里（锁内）拿到的一致副本
            // 才不会被下一次 append 撕开。真正贵的是编码 + 开文件 + 截断 + 写盘，那部分下放。
            try { text = BODY.toString(); } catch (Throwable t) { return; }
        }
        // 这里不能走 H.log（会把这条再喂回 note，写盘失败时无限递归）
        try {
            WRITER.execute(new Runnable() {
                @Override public void run() {
                    // 已经 stop（或者已经开了新一轮抓包）就丢掉这份中间快照：
                    // stopLive 会在锁内拼出完整文本并同步 publish 成品，中途快照没有任何价值；
                    // 而它要是落在 publish 之后，就会把已经写好的成品截回旧内容。
                    if (!live || f != workFile) return;
                    try {
                        write(f, text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────────

    private static ArrayDeque<String> ringCopy() {
        synchronized (RING) { return new ArrayDeque<>(RING); }
    }

    private static String moduleVersion() {
        try { return io.github.ldxm666.mapclean.Version.NAME; } catch (Throwable ignored) {}
        return "2.2.6";
    }

    private static void toast(String text) {
        try {
            Context c = appCtx;
            if (c == null) { Log.i(MainHook.TAG, "event=capture_toast text=" + text); return; }
            android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private static Map<String, String> cfgSnapshot() {
        Map<String, String> m = new TreeMap<>();
        try {
            for (Map.Entry<String, ?> e : Config.snapshot().entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                if (k.startsWith("tool_id_") || k.startsWith(Config.K_TOOL_PREFIX)) continue;
                m.put(k, String.valueOf(e.getValue()));
            }
        } catch (Throwable ignored) {}
        return m;
    }

    private static Map<String, String> prefsSnapshot(Context ctx) {
        Map<String, String> m = new TreeMap<>();
        try {
            File dir = ctx == null ? null
                    : new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return m;
            for (File f : fs) {
                if (f == null || !f.getName().endsWith(".xml")) continue;
                m.put(f.getName(), f.length() + "B@" + f.lastModified());
            }
        } catch (Throwable ignored) {}
        return m;
    }

    private static String diff(Map<String, String> a, Map<String, String> b) {
        StringBuilder s = new StringBuilder();
        try {
            if (a == null) a = new TreeMap<>();
            if (b == null) b = new TreeMap<>();
            TreeMap<String, String> keys = new TreeMap<>(a);
            for (String k : b.keySet()) keys.put(k, "");
            for (String k : keys.keySet()) {
                String old = a.get(k);
                String now = b.get(k);
                if (old == null && now != null) s.append(k).append(": (新增) ").append(now).append('\n');
                else if (old != null && now == null) s.append(k).append(": (删除) 原 ").append(old).append('\n');
                else if (old != null && !old.equals(now)) s.append(k).append(": ").append(old)
                        .append(" -> ").append(now).append('\n');
            }
        } catch (Throwable ignored) {}
        return s.toString().trim();
    }

    private static String pretty(String rec) {
        try {
            int a = rec.indexOf('|');
            int b = rec.indexOf('|', a + 1);
            int c = rec.indexOf('|', b + 1);
            int d = rec.indexOf('|', c + 1);
            return "[" + rec.substring(a + 1, b) + "] " + rec.substring(b + 1, c)
                    + " " + rec.substring(c + 1, d) + ": " + rec.substring(d + 1);
        } catch (Throwable t) {
            return rec;
        }
    }

    private static String prio(int p) {
        switch (p) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            default: return "A";
        }
    }

    private static String ts(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    /** 延时执行：优先主线程 Handler，拿不到时退回后台线程 sleep。 */
    private static final class HandlerHolder {
        private static android.os.Handler H;

        static void post(final Runnable r, final long delayMs) {
            try {
                if (H == null) H = new android.os.Handler(android.os.Looper.getMainLooper());
                H.postDelayed(r, delayMs);
                return;
            } catch (Throwable ignored) {}
            try {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(Math.max(0L, delayMs)); } catch (Throwable ignored) {}
                        r.run();
                    }
                }, "mapclean-capture").start();
            } catch (Throwable ignored) {}
        }
    }
}
