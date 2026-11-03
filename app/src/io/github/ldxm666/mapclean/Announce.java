package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * 服务器公告通道：作者在服务端放一个 JSON，客户端在**需要时**取一次并弹给用户。
 *
 * <h3>为什么是「拉」而不是「推」</h3>
 * 模块跑在地图进程里，没有自己的推送通道；2GB 的服务器也撑不起任何常驻连接。所以只有两个
 * 时机联网：<b>App 启动后 3 秒</b>（避开冷启动关键路径）和<b>面板 / 设置页打开时</b>，
 * 且都受 6 小时节流约束。不用 AlarmManager、不用 WakeLock、不起前台服务。
 *
 * <h3>省流量的三件事</h3>
 * <ol>
 *   <li><b>6 小时节流</b>：{@code last_check_ms} 连失败也写，失败不会退化成每次启动重试；</li>
 *   <li><b>条件请求</b>：带上次的 {@code ETag}，{@code 304} 就结束 —— 不解析、不写盘。
 *       ETag **按 host 分开存**：域名和 IP 是两个不同的缓存实体，串用会拿到错配的 304；</li>
 *   <li><b>短超时</b>：connect 3s / read 3s，失败静默（一行日志），绝不重试轰炸。</li>
 * </ol>
 *
 * <h3>双通道（候选列表，顺序降级）</h3>
 * 域名 {@code ldxm666.icu} 未备案，阿里云按 Host 头在所有端口上拦（公网实测 403），
 * 所以候选表是「IP → 域名」两条，**串行**按序试，第一条成功就停：
 * <pre>
 *   http://47.109.49.93/mc/announce.json          ← 首选：公网实测 200
 *   https://ldxm666.icu/mc/announce.json          ← 次选：等域名挂上 Cloudflare 代理才会通
 * </pre>
 * 每次降级记 {@code event=announce_fallback from=<base> to=<base> reason=dns|timeout|http}；
 * 两条都失败记 {@code event=announce_skip reason=all_failed tried=2} 并本轮放弃，
 * 等 6 小时后的下一轮。**不并行**、不重试轰炸。
 *
 * <h3>安全</h3>
 * <ul>
 *   <li>只允许两个前缀（域名 + IP）的地址，JSON 里的 {@code url} 和测试后门
 *       {@code debug.mapclean.announce_url} 都受同一白名单约束；</li>
 *   <li>{@code sig} = {@code HEX(HMAC-SHA256(SIG_KEY, "<id>|<title>|<body>"))}，
 *       参与签名的是 **JSON 解码之后的原文**；空 sig（服务端缺密钥就是这样）按失败处理，
 *       校验失败丢弃并记 {@code event=announce_badsig}；</li>
 *   <li>不跟随 302：白名单管的是**实际请求的地址**，一次跳转就能把它绕过去；</li>
 *   <li>客户端里只有公网 URL 和这一个共享 HMAC 常量，**没有任何服务器凭据**。</li>
 * </ul>
 *
 * <h3>展示</h3>
 * 只依附当前 resumed 的 Activity，对话框是普通 {@code Dialog}（{@code AlertDialog} + {@link Skin}
 * 自绘卡片，和 {@link FirstRunNotice} 同一套），不是 {@code TYPE_APPLICATION_OVERLAY}。
 * 拿不到 Activity 就把公告存进 {@code sPending}，等下次面板打开 / Activity resume 再弹。
 * 新 id（{@code id > announce_seen_id}）**必然弹一次**，任何 level 都可关；关掉即推进水位。
 */
public final class Announce {

    /**
     * 「接收服务器公告」开关。默认 <b>true</b>：三条厂商的 defaultVisible 表对未知键都返回
     * true（高德 {@code Config.defaultVisible} / 百度 {@code Spec.defaultVisible} /
     * 腾讯 {@code Spec.defaultVisible}），所以那张表一行都不用改。
     */
    public static final String K_ON = "announce_on";

    /** 已读到的最大公告 id（秒级 unix 时间戳，单调递增）。 */
    public static final String K_SEEN_ID = "announce_seen_id";

    /**
     * 公告 JSON 的路径。候选 base、端口白名单、超时、ETag 记账全在 {@link ServerFetch} ——
     * 公告和「检查更新」共用同一份带降级的 GET，两边不会漂。
     */
    private static final String PATH = ServerFetch.PATH_ANNOUNCE;

    /**
     * 测试 / 灰度后门：{@code adb shell setprop debug.mapclean.announce_url <url>}。
     *
     * <p>存在的理由：服务端换路径、或灰度发一份 {@code announce-test.json} 时，不必重新打包。
     * **仍然受 {@link ServerFetch} 约束**，否则它就是上面那个洞的旁路。
     * 每个进程只读一次，不参与任何热路径。
     */
    private static final String TEST_PROP = "debug.mapclean.announce_url";

    private static final String TAG = "MapCleanAnnounce";

    private static final String PREFS = "mapclean_announce";
    private static final String P_AT = "last_check_ms";
    private static final String P_ETAG_PREFIX = "etag::";
    private static final String P_CACHE = "cached_json";

    /**
     * 节流窗口**按上次结果分档**（2.3.1 修正）。
     *
     * <p>旧版只有「6 小时」一档，而且失败也按 6 小时计时 —— 联调期只要有一次网络失败，
     * 真公告上来时窗口已经被占掉，用户看到的是「通道装好了但从来不弹」。
     * 现在：拿到有效公告才等 6 小时；服务器可达但没有有效公告等 15 分钟（作者可能正在推）；
     * 两条地址都失败等 30 分钟；签名不符等 1 小时（密钥可能两边不一致，别狂刷也别等 6 小时）。
     */
    /**
     * 上一次**成功拿到有效公告**之后多久再查。
     *
     * <p>★ 2.3.1 修正：这里原来是 6 小时，对「事故公告」是致命的 —— 作者推了紧急通知，
     * 用户 6 小时内根本看不到，公告通道的核心价值就没了。实测：服务器换了新 id，
     * 三端 + 模块版的 prefs 全都锁在旧那条上。
     *
     * <p>成本核过：{@code /mc/announce.json} 161 字节，带 {@code If-None-Match} 时服务端回
     * 304（几十字节）。用户一天开 20 次 App ≈ 20 次请求 < 5KB，nginx 静态文件，服务端成本可忽略。
     * 真正贵的是「用户看不到公告」，不是这 5KB。
     */
    private static final long WINDOW_SUCCESS_MS = 5L * 60L * 1000L;
    private static final long WINDOW_EMPTY_MS = 15L * 60L * 1000L;
    private static final long WINDOW_FAILED_MS = 30L * 60L * 1000L;
    private static final long WINDOW_BADSIG_MS = 60L * 60L * 1000L;

    private static final String KIND_SUCCESS = "success";
    private static final String KIND_EMPTY = "empty";
    private static final String KIND_FAILED = "failed";
    private static final String KIND_BADSIG = "badsig";
    /** 上次检查的结果类型；**旧版本没有这个键 ⇒ 按 failed 处理（30 分钟后自愈）**。 */
    private static final String P_KIND = "last_kind";
    private static final String P_DISMISSED = "dismissed_id";

    /** 测试后门：置 1 ⇒ 下一次检查无视节流（每进程一次，且两次强制间隔 ≥ 60 秒）。 */
    private static final String FORCE_PROP = "debug.mapclean.announce_force";
    private static final String P_FORCE_AT = "force_at";
    private static final long FORCE_MIN_GAP_MS = 60L * 1000L;
    /** 「暂无公告」提示的最小间隔。 */
    private static final long NO_ANNOUNCE_GAP_MS = 30L * 1000L;
    /** 启动后的延迟：避开冷启动关键路径（地图首帧比一条公告重要）。 */
    private static final long START_DELAY_MS = 3000L;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    /** 响应体上限：公告是几百字节的东西，超过这个数只可能是打错了地址。 */
    private static final int MAX_BODY = 64 * 1024;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean sRunning;
    private static volatile boolean sWatched;
    private static volatile String sOverride;
    private static volatile boolean sOverrideRead;
    private static volatile boolean sForcePropRead;
    private static volatile boolean sForceUsed;
    /** 启动检查只排一次：{@code attachEmbedded} 会在每次面板渲染时被调用。 */
    private static volatile boolean sStartScheduled;
    /** 用户点了「最新公告」但当时已有一轮在飞：交给那一轮的收尾去提示。 */
    private static volatile boolean sUserAskPending;
    /** 「暂无公告」最多 30 秒提示一次，避免任何循环把它放大成刷屏。 */
    private static volatile long sLastNoAnnounceAt;
    /** 用户主动点「最新公告」这一轮，是否真的弹出了东西（没弹 ⇒ 提示「暂无公告」）。 */
    private static volatile boolean sUserAskedServed;
    /** 进程级 Application：{@code seen()} 这类没有调用方 Context 的读靠它。 */
    private static volatile Context sApp;

    /** 最近一个 resumed 的 Activity：对话框唯一的合法依附点。Weak，不拖住已结束的页面。 */
    private static volatile WeakReference<Activity> sResumed = new WeakReference<Activity>(null);
    /** 拿到但还没有宿主可弹的公告。**每次 dismiss 一律清空**，绝不跨关窗重排。 */
    private static volatile Msg sPending;
    /** 排队那条是不是「用户主动点开的」——它决定 advance 时还允不允许无视 seen。 */
    private static volatile boolean sPendingFromUser;
    /** 最近一次被关掉的 id（落盘）：自动路径见到它就永远跳过，只有用户手动点才能重看。 */
    private static volatile long sDismissedId = -1L;
    /** 本次关闭是哪个按钮触发的：ok / detail / cancel（点空白或返回）。 */
    private static volatile String sCloseVia;
    /** 正在展示的公告 id（0 = 没有）。 */
    private static volatile long sShowingId;
    /** 「已读状态变了」的回调（面板横幅 / 设置页卡片据此去红点）。Weak，避免拖住 UI。 */
    private static volatile WeakReference<Runnable> sOnChanged = new WeakReference<Runnable>(null);

    private Announce() {}

    /** 一条通过解析、等待展示的公告。 */
    private static final class Msg {
        long id;
        String title = "";
        String body = "";
        String level = "info";
        String url = "";
        String sig = "";
        long minVersion;
        long maxVersion;
        long expireAt;
    }

    /* ══════════════════════════════════════════════════════════════════ 入口 */

    /**
     * App 启动：注册 Activity 生命周期观察者，并在 3 秒后查一次。
     *
     * <p>必须幂等 —— 模块版走 {@code App.onCreate}，内置版走 {@code App.attachEmbedded}，
     * 服务重绑还可能再来一次。
     */
    public static void onAppStart(Context context) {
        if (context == null) return;
        final Context app = appOf(context);
        sApp = app;
        watch(app);
        // ★ 只排一次：内置版的 attachEmbedded 会在每次面板渲染时被调用，以前每调一次就多排
        //   一个 3 秒后的 check —— 真机实测「500ms 内 5 次请求」就是这些定时器一起到期。
        if (sStartScheduled) return;
        sStartScheduled = true;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try { check(app, false, false); } catch (Throwable ignored) { }
            }
        }, START_DELAY_MS);
    }

    /**
     * 面板 / 设置页打开：补一次（节流在 {@code check} 内部），并把没弹出去的公告弹掉。
     *
     * <p>内置版没有 MainActivity，面板是它唯一的设置界面；面板打开也是唯一保证有 Activity
     * 可依附的时机之一。
     */
    public static void onPanelOpen(Context context) {
        if (context == null) return;
        if (context instanceof Activity) sResumed = new WeakReference<Activity>((Activity) context);
        final Context app = appOf(context);
        sApp = app;
        watch(app);
        try { check(app, false, false); } catch (Throwable ignored) { }
        MAIN.post(new Runnable() {
            @Override public void run() { advance(); }
        });
    }

    /** Activity 回到前台：记下它，并把等待中的公告弹出去（由生命周期回调调用）。 */
    static void onResumed(Activity activity) {
        if (activity == null) return;
        sResumed = new WeakReference<Activity>(activity);
        // ★ 回到前台必查一次：5 分钟窗口内自然被节流挡住（只读一次 prefs），
        //   超过窗口就真发一次条件请求（304 极轻）。作者推了公告、用户切回来就能看到。
        try { check(activity.getApplicationContext(), false, false); } catch (Throwable ignored) { }
        MAIN.post(new Runnable() {
            @Override public void run() { advance(); }
        });
    }

    /**
     * 「最新公告」入口：重看缓存里的那一条。
     *
     * <p>没有缓存时**强制查一次**（用户主动点的，不受 6 小时节流），结果直接弹给他。
     * 「用户主动重看」不动 {@code announce_seen_id} 的水位 —— 水位只由第一次自动提醒推进。
     */
    public static void showLatest(Context context) {
        if (context == null) return;
        // 调用方可能把已销毁的 Activity 递进来（MainActivity.this 在异步回调里很常见）：
        // 这时候弹窗一定 BadTokenException，降级成一条 skip 日志，绝不崩。
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            boolean dead = false;
            try { dead = activity.isFinishing() || activity.isDestroyed(); } catch (Throwable ignored) { }
            if (dead) {
                skip("no_ctx");
                return;
            }
            sResumed = new WeakReference<Activity>(activity);
        }
        final Context app = appOf(context);
        sApp = app;
        Msg cached = cached(app);
        if (cached != null) {
            sUserAskedServed = true;      // 重看缓存：一定有东西可弹
            offer(cached, true, "latest"); // 唯一允许无视 seen / dismissed 的路径
            return;
        }
        // 没有缓存：**无条件**强制查一次（绕过一切节流）。查完什么都没有就明确提示，
        // 不能像以前那样「点了没反应」。
        sUserAskedServed = false;
        sUserAskPending = true;
        check(app, true, true);
    }

    /** 缓存里那条公告的标题（入口行的小字），没有就 null。 */
    public static String latestTitle(Context context) {
        Msg m = cached(context == null ? null : appOf(context));
        return m == null || m.title.length() == 0 ? null : m.title;
    }

    /** 是否有一条还没提醒过的公告（入口的红点 / 角标据此显示）。 */
    public static boolean hasUnread(Context context) {
        Msg m = cached(context == null ? null : appOf(context));
        if (m == null || m.title.length() == 0) return false;
        long seen = context == null ? seen() : seen(appOf(context));
        return m.id > seen;
    }

    /** 面板开关读的是这里。 */
    public static boolean enabled(Context context) {
        try {
            if (context != null) Vendor.from(context);
            return FloatingConfig.read(K_ON);
        } catch (Throwable t) {
            return true;      // 读不到就按默认（接收），与三条 defaultVisible 表一致
        }
    }

    /** 面板开关写的是这里。失败返回 false，调用方负责把胶囊拨回去。 */
    public static boolean setEnabled(boolean on) {
        try {
            boolean ok = FloatingConfig.write(K_ON, on);
            if (ok) FloatingConfig.invalidate();
            Log.i(TAG, "event=announce_switch on=" + on + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** UI 注册「已读状态变了」的回调（弱引用，注册方不必反注册）。 */
    public static void setOnChanged(Runnable callback) {
        sOnChanged = new WeakReference<Runnable>(callback);
    }

    /* ══════════════════════════════════════════════════════════════ 取公告 */

    /**
     * 联网取一次。{@code force} 只给「最新公告」入口用；{@code userAsked} 让结果绕过
     * 「同一条只提醒一次」的判据（用户自己点的，当然要弹）。
     */
    private static void check(final Context context, final boolean force, final boolean userAsked) {
        if (context == null) return;
        final Context app = appOf(context);
        if (!enabled(app)) {
            skip("off");
            userAskedFinished(userAsked);
            return;
        }
        final SharedPreferences p = prefs(app);
        if (p == null) {
            userAskedFinished(userAsked);
            return;
        }
        long last = 0L;
        String kind = KIND_FAILED;
        try {
            last = p.getLong(P_AT, 0L);
            kind = p.getString(P_KIND, KIND_FAILED);
        } catch (Throwable ignored) { }
        long now = System.currentTimeMillis();
        long window = windowOf(kind);
        boolean forced = force || forcePropOnce(p);
        if (!forced && last > 0L && now - last < window) {
            // 这条日志就是上次「通道是死的」唯一缺的证据：被哪种节流挡、还要等多久。
            if (mayLog("skip:throttle", 6)) {
                Log.i(TAG, "event=announce_skip reason=throttle remain_ms=" + (window - (now - last))
                        + " kind=" + kind);
            }
            return;
        }
        if (!online(app)) {
            skip("offline");
            markChecked(p, KIND_FAILED);     // 离线也计时，否则每次开面板都会再走一遍
            userAskedFinished(userAsked);
            return;
        }
        if (sRunning) {
            // 已经有一轮在飞：不要在这里立刻说「暂无公告」（那会在自动检查期间误报），
            // 把「用户问过」记下来，交给正在跑的那一轮收尾统一提示。
            if (!userAsked) sUserAskPending = false;
            return;
        }
        // ★ 乐观占窗：请求是异步的，若等 worker 结束才写 P_AT，同一瞬间排进来的多个 check
        //   会**全部**通过节流判定。先按 failed 占上，结果出来再覆盖成真实 kind。
        markChecked(p, KIND_FAILED);
        sRunning = true;

        new Thread(new Runnable() {
            @Override public void run() {
                long started = System.currentTimeMillis();
                boolean ok = false;
                boolean networkOk = false;
                String resultKind = KIND_FAILED;
                String etagHit = "miss";
                String reason = "";
                String host = "";
                String[] urls = new String[]{"?"};
                try {
                    String override = testOverride();
                    if (override != null && !ServerFetch.allowed(override)) {
                        if (mayLog("override_reject", 1)) {
                            Log.w(TAG, "event=announce_skip reason=url via=prop value=" + override);
                        }
                        override = null;
                    }
                    urls = ServerFetch.candidates(PATH, override);
                    ServerFetch.Result r = ServerFetch.get("announce", PATH, p, override);
                    // ★ 304 自愈：ETag 还在、本地缓存却丢了（换机/清数据/调试删键），
                    //   再问只会永远是 304。丢掉条件头重发一次，把正文补回来。
                    if (r.ok && r.body == null && cached(app) == null) {
                        if (mayLog("stale304", 3)) {
                            Log.i(TAG, "event=announce_stale304 base=" + r.base);
                        }
                        r = ServerFetch.get("announce", PATH, p, override, true);
                    }
                    networkOk = r.ok;
                    ok = r.ok;
                    etagHit = r.etag;
                    host = r.base;
                    reason = r.reason;
                    if (!r.ok) {
                        resultKind = KIND_FAILED;
                    } else if (r.body == null) {
                        resultKind = KIND_SUCCESS;      // 304：缓存里的公告仍然有效
                    } else {
                        Msg msg = parse(r.body);
                        if (msg == null) {
                            ok = false;
                            reason = "parse";
                            resultKind = KIND_FAILED;
                            skip("parse");
                        } else if (!sigOk(msg)) {
                            ok = false;
                            reason = "badsig";
                            resultKind = KIND_BADSIG;
                            if (mayLog("badsig", 6)) {
                                Log.w(TAG, "event=announce_badsig id=" + msg.id);
                            }
                        } else if (msg.id <= 0L || msg.title.length() == 0) {
                            // 服务端 --clear / 刚建站时的空公告：可达但没有有效内容。
                            resultKind = KIND_EMPTY;
                            skip("empty");
                        } else {
                            cache(p, r.base, r.body, r.newEtag);
                            offer(msg, userAsked, userAsked ? "fetch_user" : "fetch");
                            resultKind = KIND_SUCCESS;
                        }
                    }
                } catch (Throwable t) {
                    ok = false;
                    reason = t.getClass().getSimpleName();
                } finally {
                    sRunning = false;
                    markChecked(p, resultKind);
                }
                if (!ok && !networkOk && mayLog("all_failed", 6)) {
                    Log.i(TAG, "event=announce_skip reason=all_failed tried=" + urls.length);
                }
                // 304 是最常见的结果，也是最该安静的结果：不解析、不写盘、不打日志。
                // 只有「拿到了新正文」或「失败了」才留一行，日志量才配得上 5 分钟的检查频率。
                boolean quiet = ok && "hit".equals(etagHit);
                if (!quiet && mayLog("fetch", 8)) {
                    Log.i(TAG, "event=announce_fetch ok=" + ok
                            + " ms=" + (System.currentTimeMillis() - started)
                            + " etag=" + etagHit
                            + (host.length() > 0 ? " base=" + host : "")
                            + (reason.length() > 0 ? " reason=" + reason : ""));
                }
                if (ok && "hit".equals(etagHit) && userAsked) {
                    // 304 = 缓存没变；用户点进来是想重看，那就给他缓存里的那条。
                    Msg msg = cached(app);
                    if (msg != null) offer(msg, true, "fetch304_user");
                }
                userAskedFinished(userAsked);
            }
        }, "mapclean-announce").start();
    }

    /** 记「什么时候查的」+「查到了什么」。两者一起写，节流窗口才是自解释的。 */
    private static void markChecked(SharedPreferences p, String kind) {
        try {
            p.edit()
                    .putLong(P_AT, System.currentTimeMillis())
                    .putString(P_KIND, kind == null ? KIND_FAILED : kind)
                    .commit();
        } catch (Throwable ignored) { }
    }

    /** 上次结果 → 下次可再查的间隔。旧版本没有 kind，按 failed 处理即可自然自愈。 */
    private static long windowOf(String kind) {
        if (KIND_SUCCESS.equals(kind)) return WINDOW_SUCCESS_MS;
        if (KIND_EMPTY.equals(kind)) return WINDOW_EMPTY_MS;
        if (KIND_BADSIG.equals(kind)) return WINDOW_BADSIG_MS;
        return WINDOW_FAILED_MS;
    }

    /**
     * 读 {@code debug.mapclean.announce_url}。每个进程最多读一次。
     *
     * <p>两条路：先试 {@code android.os.SystemProperties}（反射，最轻），不行再 exec
     * {@code getprop}（隐藏 API 在部分 ROM 上会拒，exec 是兜底）。两条都失败 ⇒ 正式地址。
     */
    private static String testOverride() {
        if (sOverrideRead) return sOverride;
        sOverrideRead = true;
        String value = readProp(TEST_PROP);
        if (value != null) {
            value = value.trim();
            if (value.length() == 0 || "null".equals(value)) value = null;
        }
        sOverride = value;
        if (value != null && mayLog("override", 1)) {
            Log.i(TAG, "event=announce_override url=" + value);
        }
        return sOverride;
    }

    /**
     * {@code debug.mapclean.announce_force=1} ⇒ 下一次检查无视节流。
     *
     * <p>每个进程只生效一次：普通 App 没有权限把 {@code debug.*} 清掉（那需要 shell/root），
     * 所以「查完自动清 prop」在这里退化成「本进程只认一次」——效果一样，且不会因为一条
     * 忘删的 prop 让客户端此后永不节流。
     */
    private static boolean forcePropOnce(SharedPreferences p) {
        if (sForceUsed) return false;
        if (!sForcePropRead) {
            sForcePropRead = true;
            String value = readProp(FORCE_PROP);
            if (value != null && ("1".equals(value.trim())
                    || "true".equalsIgnoreCase(value.trim()))) {
                // 两次强制之间至少 60 秒，且时间戳落盘（跨进程）。
                // App 没有 setprop 权限，所以「查完清 prop」不可靠 —— 靠这两道闸兜住：
                // 一条忘删的 prop 最多让每个进程绕过一次，绝不会变成连续联网。
                long at = 0L;
                try { at = p.getLong(P_FORCE_AT, 0L); } catch (Throwable ignored) { }
                long now = System.currentTimeMillis();
                if (at > 0L && now - at < FORCE_MIN_GAP_MS) {
                    if (mayLog("force_skip", 3)) {
                        Log.i(TAG, "event=announce_skip reason=force_gap remain_ms="
                                + (FORCE_MIN_GAP_MS - (now - at)));
                    }
                    return false;
                }
                try { p.edit().putLong(P_FORCE_AT, now).commit(); } catch (Throwable ignored) { }
                Log.i(TAG, "event=announce_force prop=1");
                sForceUsed = true;
            }
        }
        return sForceUsed;
    }

    private static boolean takePendingAsk() {
        if (!sUserAskPending) return false;
        sUserAskPending = false;
        return true;
    }

    /** 读一个系统属性：先反射（最轻），再 exec {@code getprop}（隐藏 API 被拒时兜底）。 */
    private static String readProp(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, name, "");
            if (v instanceof String && ((String) v).trim().length() > 0) {
                return ((String) v).trim();
            }
        } catch (Throwable ignored) { }
        return getprop(name);
    }

    private static String getprop(String name) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", name)
                    .redirectErrorStream(true).start();
            try { process.waitFor(600, TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
            InputStream in = process.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 用户主动点「最新公告」这一轮的终态：真弹出来了就什么都不做，没弹就明说「暂无公告」。
     *
     * <p>不能用固定延时（请求最坏 6 秒）；这里挂在请求真正结束之后，
     * 而 {@code offer} 往主线程投递的 Runnable 先于它入队，所以顺序天然正确。
     */
    private static void userAskedFinished(boolean userAsked) {
        // 一次性消费「用户问过」标记：无论走哪条早退分支，都不会留到下一条自动检查里去误弹。
        userAsked = userAsked || takePendingAsk();
        if (!userAsked) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (sUserAskedServed) return;
                if (sShowingId != 0L) return;      // 已经有一条在脸上，别在底下再喊「暂无公告」
                // ★ 只有「用户主动点」这一条路会走到这里；自动检查/渲染永远不提示。
                long now = System.currentTimeMillis();
                if (now - sLastNoAnnounceAt < NO_ANNOUNCE_GAP_MS) return;
                sLastNoAnnounceAt = now;
                toast("暂无公告");
            }
        });
    }

    /** 轻量提示（「暂无公告」这种一次性反馈，不占对话框）。 */
    private static void toast(final String text) {
        try {
            Context c = sApp != null ? sApp : io.github.ldxm666.mapclean.App.ctx();
            if (c == null) return;
            android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    /* ══════════════════════════════════════════════════════════════ JSON / 签名 */

    private static Msg parse(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(body);
            Msg m = new Msg();
            m.id = o.getLong("id");                       // 缺 id = 没有身份 = 直接放弃
            m.title = trim(o.optString("title", ""), 200);
            m.body = trim(o.optString("body", ""), 2000);
            String level = o.optString("level", "info");
            m.level = "force".equals(level) || "warn".equals(level) ? level : "info";
            m.url = trim(o.optString("url", ""), 512);
            m.minVersion = o.optLong("minVersionCode", 0L);
            m.maxVersion = o.optLong("maxVersionCode", 0L);
            m.expireAt = o.optLong("expireAt", 0L);
            m.sig = o.optString("sig", "").trim();
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code HEX(HMAC-SHA256(SIG_KEY, "<id>|<title>|<body>"))}，大小写不敏感比对。 */
    private static boolean sigOk(Msg m) {
        if (m == null) return false;
        // 空 sig（服务端缺密钥时就是这样）由 verifySig 按失败处理。
        return ServerFetch.verifySig(m.id + "|" + m.title + "|" + m.body, m.sig);
    }
    /* ══════════════════════════════════════════════════════════════ 展示 */

    /**
     * 在 UI 线程判一遍闸门，然后要么弹、要么存起来等宿主。
     *
     * @param fromUser 只有「用户主动点最新公告」这一条路是 true；自动路径必须为 false
     * @param from     调用点标记，写进 {@code event=announce_offer from=}，出问题一眼看出是谁弹的
     */
    private static void offer(final Msg msg, final boolean fromUser, final String from) {
        if (msg == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (!passes(msg, fromUser, from)) return;
                Activity activity = activity();
                if (activity == null) {
                    sPending = msg;            // 没有 Activity：存着，等面板打开 / 下次 resume
                    sPendingFromUser = fromUser;
                    return;
                }
                showNow(activity, msg, fromUser, from);
            }
        });
    }

    /** 展示前的闸门。返回 false 时已经记过 {@code announce_skip}。 */
    private static boolean passes(Msg msg, boolean fromUser, String from) {
        if (msg == null) return false;
        if (msg.title.length() == 0) {
            // 服务端 --clear 会推 id 递增、标题正文皆空的公告：不展示，也**不推进 seen**，
            // 否则下一条真公告会被当成「已读」。
            skip("empty");
            return false;
        }
        if (!enabled(null) && !fromUser) {
            skip("off");
            return false;
        }
        if (!fromUser) {
            // ★ 自动路径的两道闸：已读水位 + 已关闭的 id。只有用户手动点能越过这两条。
            if (msg.id <= seen()) {
                skip("seen");
                return false;
            }
            if (msg.id <= dismissedId()) {
                if (mayLog("skip:dismissed", 6)) {
                    Log.i(TAG, "event=announce_skip reason=dismissed id=" + msg.id
                            + " from=" + from);
                }
                return false;
            }
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        if (msg.expireAt > 0L && nowSeconds >= msg.expireAt) {
            skip("expired");
            return false;
        }
        if (msg.minVersion > 0L && Version.CODE < msg.minVersion) {
            skip("version");
            return false;
        }
        if (msg.maxVersion > 0L && Version.CODE > msg.maxVersion) {
            skip("version");
            return false;
        }
        if (mayLog("offer", 30)) {
            Log.i(TAG, "event=announce_offer from=" + from
                    + " id=" + msg.id + " user=" + fromUser);
        }
        return true;
    }

    /** 弹出对话框。调用方保证在 UI 线程且 {@code activity} 还活着。 */
    private static void showNow(final Activity activity, final Msg msg,
                                final boolean fromUser, final String from) {
        if (activity == null || msg == null) return;
        if (sShowingId != 0L) {         // 已经有一条在脸上：排队，关掉后再弹
            sPending = msg;
            sPendingFromUser = fromUser;
            return;
        }
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
        } catch (Throwable ignored) { }
        sShowingId = msg.id;
        sCloseVia = null;
        sUserAskedServed = sUserAskedServed || fromUser;   // 只有用户那条才算「他要的」
        rememberSeen(msg.id);           // 只在真正弹出去时推进水位：同一条永远不会提醒第二次
        if (mayLog("show", 30)) {
            Log.i(TAG, "event=announce_show id=" + msg.id + " level=" + msg.level
                    + " from=" + from);
        }
        boolean shown = showDialog(activity, msg);
        if (!shown) {
            sShowingId = 0L;
            sPending = msg;             // 宿主窗口没了：留着下次再弹
            sPendingFromUser = fromUser;
        }
    }

    /**
     * 真正画那个对话框：{@code AlertDialog} + {@link Skin} 自绘玻璃卡片，
     * 与 {@link FirstRunNotice} 完全同一套（标题粗体 / 正文 / 时间小字 / 按钮行）。
     *
     * <p>**所有 level 都可关**：点「知道了」、点空白、按返回都行 —— 用户明确要求 force 也能关，
     * 所以这里没有 {@code setCancelable(false)}，也不拦返回键。
     */
    private static boolean showDialog(final Activity activity, final Msg msg) {
        try {
            Skin skin = new Skin(activity, isNight(activity));
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(skin.glassFor(box, Skin.R_CARD));
            box.setPadding(skin.dp(Skin.S5), skin.dp(Skin.S5),
                    skin.dp(Skin.S5), skin.dp(Skin.S4));

            String prefix = "force".equals(msg.level) ? "【重要】"
                    : ("warn".equals(msg.level) ? "⚠ " : "");
            TextView title = skin.text(prefix + msg.title, Skin.T_TITLE, Skin.BOLD, skin.text);
            box.addView(title, skin.matchWrap());

            TextView body = skin.text(msg.body, 14f, Skin.REGULAR, skin.text2);
            body.setLineSpacing(skin.dp(3), 1.18f);
            LinearLayout.LayoutParams bodyLp = skin.matchWrap();
            bodyLp.topMargin = skin.dp(Skin.S3);
            box.addView(body, bodyLp);

            TextView meta = skin.text(metaLine(msg), Skin.T_LABEL, Skin.REGULAR, skin.text3);
            LinearLayout.LayoutParams metaLp = skin.matchWrap();
            metaLp.topMargin = skin.dp(Skin.S3);
            box.addView(meta, metaLp);

            final String url = allowed(msg.url) ? msg.url : null;
            final Runnable[] hideRef = new Runnable[1];

            if (url != null) {
                TextView detail = skin.secondaryButton("查看详情");
                detail.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openUrl(activity, url);
                        if (hideRef[0] != null) hideRef[0].run();
                    }
                });
                LinearLayout.LayoutParams dlp = skin.matchWrap();
                dlp.topMargin = skin.dp(Skin.S5);
                box.addView(detail, dlp);
            }

            TextView ok = skin.primaryButton("知道了");
            ok.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (hideRef[0] != null) hideRef[0].run();
                }
            });
            LinearLayout.LayoutParams okLp = skin.matchWrap();
            okLp.topMargin = skin.dp(url != null ? Skin.S3 : Skin.S5);
            box.addView(ok, okLp);

            final ViewGroup host = overlayHost(activity);
            if (host == null) return false;

            final FrameLayout overlay = new FrameLayout(activity);
            // 遮罩自己持有 drawable 引用：入场/退场要和卡片一起做 alpha
            final ColorDrawable dim = new ColorDrawable(0xFF000000);   // 不透明黑，靠 alpha 动画到 45%（ColorDrawable 的 setAlpha 会和颜色 alpha 相乘）
            dim.setAlpha(0);
            overlay.setBackground(dim);
            overlay.setClickable(true);

            int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    Math.min(widthPx - skin.dp(Skin.S7) * 2, skin.dp(340)),
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            cardLp.gravity = Gravity.CENTER;
            overlay.addView(box, cardLp);

            final boolean[] done = { false };
            final Runnable removeNow = new Runnable() {
                @Override public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    try { host.removeView(overlay); } catch (Throwable ignored) { }
                    if (mayLog("dismiss", 20)) {
                        Log.i(TAG, "event=announce_dismiss id=" + msg.id);
                    }
                    clearShowing(msg.id);
                }
            };
            // 三条关闭路径（知道了 / 点空白 / 返回键）都从这里走：先播退场，动画结束再摘视图。
            // 动画起不来或回调不回来也不能关不掉 —— 400ms 兜底 post 一样会摘。
            final Runnable hide = new Runnable() {
                @Override public void run() {
                    try {
                        overlay.postDelayed(removeNow, 400L);
                        playOut(box, dim, removeNow);
                    } catch (Throwable ignored) {
                        removeNow.run();
                    }
                }
            };
            hideRef[0] = hide;
            // 点空白关闭；卡片自己吃掉点击，点正文不会误关
            overlay.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { hide.run(); }
            });
            box.setClickable(true);
            // 返回键关闭（与原来 setCancelable(true) 等价）
            overlay.setFocusableInTouchMode(true);
            overlay.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        hide.run();
                        return true;
                    }
                    return false;
                }
            });
            overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }
                @Override public void onViewDetachedFromWindow(View v) {
                    // 宿主 Activity 被重建/销毁：别把 sShowingId 卡死，下一条公告还能弹
                    if (!done[0]) { done[0] = true; clearShowing(msg.id); }
                }
            });

            // 卡片自己的软阴影：轮廓必须显式给成圆角，否则 setElevation 画出来的是方角投影。
            try {
                final float radiusPx = skin.dp(Skin.R_CARD);
                box.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline outline) {
                        float r = Math.min(radiusPx, Math.min(v.getWidth(), v.getHeight()) / 2f);
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                    }
                });
                skin.shadow(box, 8f);
            } catch (Throwable ignored) { }

            host.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.requestFocus();
            playIn(box, dim);        // 入场：0.94→1 + 淡入，和退场同一套曲线
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "event=announce_show_fail id=" + msg.id
                    + " err=" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 入场：卡片 0.94→1 + 淡入，遮罩同步淡入；失败就直接落到终态，绝不停在半透明。 */
    static void playIn(final View card, final ColorDrawable dim) {
        try {
            card.setAlpha(0f);
            card.setScaleX(0.94f);
            card.setScaleY(0.94f);
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(150L);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator an) {
                    try {
                        float f = (Float) an.getAnimatedValue();
                        card.setAlpha(f);
                        float s = 0.94f + 0.06f * f;
                        card.setScaleX(s);
                        card.setScaleY(s);
                        dim.setAlpha((int) (0x73 * f));
                    } catch (Throwable ignored) { }
                }
            });
            a.start();
        } catch (Throwable ignored) {
            card.setAlpha(1f);
            card.setScaleX(1f);
            card.setScaleY(1f);
            dim.setAlpha(0x73);
        }
    }

    /** 退场：卡片 1→0.96 + 淡出，遮罩同步淡出，130ms 后回调；失败直接回调，保证能关。 */
    static void playOut(final View card, final ColorDrawable dim, final Runnable end) {
        try {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
            a.setDuration(130L);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator an) {
                    try {
                        float f = (Float) an.getAnimatedValue();
                        card.setAlpha(f);
                        card.setScaleX(0.96f + 0.04f * f);
                        card.setScaleY(0.96f + 0.04f * f);
                        dim.setAlpha((int) (0x73 * f));
                    } catch (Throwable ignored) { }
                }
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    try { end.run(); } catch (Throwable ignored) { }
                }
            });
            a.start();
        } catch (Throwable ignored) {
            end.run();
        }
    }

    /** 浮层挂载点：Activity 的 DecorView，盖住整屏（含状态栏下方）。拿不到就返回 null。 */
    static ViewGroup overlayHost(Activity activity) {
        try {
            Window w = activity.getWindow();
            View decor = w != null ? w.getDecorView() : null;
            if (decor instanceof ViewGroup) return (ViewGroup) decor;
        } catch (Throwable ignored) { }
        return null;
    }

    /** 时间 / 版本小字。id 是秒级 unix 时间戳；不在合理区间就只写等级与版本。 */
    private static String metaLine(Msg msg) {
        String when = "";
        try {
            long seconds = msg.id;
            if (seconds > 946684800L && seconds < 4102444800L) {      // 2000-01-01 .. 2100-01-01
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
                when = f.format(new Date(seconds * 1000L));
            }
        } catch (Throwable ignored) { }
        String level = "force".equals(msg.level) ? "重要公告"
                : ("warn".equals(msg.level) ? "提醒" : "公告");
        return (when.length() > 0 ? when + " · " : "") + level + " · 本机 v" + Version.NAME;
    }

    /** 弹一条排队的公告（如果有），UI 线程。**过了闸门才弹**，dismiss 过的 id 在这里被拦下。 */
    private static void advance() {
        if (sShowingId != 0L) return;
        Msg next = sPending;
        if (next == null) return;
        boolean fromUser = sPendingFromUser;
        sPending = null;
        sPendingFromUser = false;
        Activity activity = activity();
        if (activity == null) {
            sPending = next;
            sPendingFromUser = fromUser;
            return;
        }
        if (!passes(next, fromUser, "advance")) return;   // 关键：排队期间被 dismiss/已读的，就地丢掉
        showNow(activity, next, fromUser, "advance");
    }

    /**
     * 关闭之后的状态清理 —— 这里是「关了又自己弹回来」的唯一开关。
     *
     * <p>★ 三条铁律：
     * <ol>
     *   <li>{@code sPending} **一律清空**（不管是不是同一条）：重新展示只能由「下次面板打开 /
     *       下次 App 启动 / 用户点击」重新走一遍闸门，绝不从关窗的残渣里恢复；</li>
     *   <li>把 id 写进 {@code dismissed_id}：自动路径此后永远跳过它；</li>
     *   <li>只有 id 与当前展示一致时才动状态 —— 90 秒的兜底计时器可能是过期的。</li>
     * </ol>
     */
    private static void clearShowing(long id) {
        if (sShowingId != id) return;
        sShowingId = 0L;
        sPending = null;
        sPendingFromUser = false;
        if (id > dismissedId()) {
            sDismissedId = id;
            try {
                SharedPreferences p = prefs(sApp);
                if (p != null) p.edit().putLong(P_DISMISSED, id).commit();
            } catch (Throwable ignored) { }
        }
        if (mayLog("close", 30)) {
            Log.i(TAG, "event=announce_close id=" + id
                    + " via=" + (sCloseVia == null ? "cancel" : sCloseVia));
        }
        sCloseVia = null;
        Runnable changed = sOnChanged == null ? null : sOnChanged.get();
        if (changed != null) {
            try { changed.run(); } catch (Throwable ignored) { }
        }
        MAIN.post(new Runnable() {
            @Override public void run() { advance(); }
        });
    }

    /** {@code dismissed_id}：懒加载 + 进程内缓存。旧版本没有这个键 ⇒ 0（不影响 seen 的判断）。 */
    private static long dismissedId() {
        long value = sDismissedId;
        if (value >= 0L) return value;
        try {
            SharedPreferences p = prefs(sApp);
            value = p == null ? 0L : p.getLong(P_DISMISSED, 0L);
        } catch (Throwable t) {
            value = 0L;
        }
        sDismissedId = value;
        return value;
    }

    /** 窗口被系统吞掉时没有 dismiss 回调：90 秒后强制放行，避免排队死锁。 */
    private static void scheduleShowingReset(final long id) {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { clearShowing(id); }
        }, 90L * 1000L);
    }

    private static void openUrl(Context context, String url) {
        if (!allowed(url)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                android.widget.Toast.makeText(context, url, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        }
    }

    private static boolean isNight(Context context) {
        try {
            Configuration cfg = context.getResources().getConfiguration();
            return (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ══════════════════════════════════════════════════════════════ 生命周期 */

    /** 注册一次 Activity 观察者。模块版走模块自己的 Application，内置版走宿主 Application。 */
    private static void watch(Context context) {
        if (sWatched) return;
        if (!(context instanceof Application)) return;
        try {
            ((Application) context).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
                        @Override public void onActivityStarted(Activity a) { }
                        @Override public void onActivityResumed(Activity a) { onResumed(a); }
                        @Override public void onActivityPaused(Activity a) {
                            Activity current = activity();
                            if (current == a) sResumed = new WeakReference<Activity>(null);
                        }
                        @Override public void onActivityStopped(Activity a) { }
                        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
                        @Override public void onActivityDestroyed(Activity a) {
                            Activity current = activity();
                            if (current == a) sResumed = new WeakReference<Activity>(null);
                        }
                    });
            sWatched = true;
        } catch (Throwable t) {
            Log.w(TAG, "event=announce_watch_fail err=" + t.getClass().getSimpleName());
        }
    }

    private static Activity activity() {
        WeakReference<Activity> ref = sResumed;
        Activity a = ref == null ? null : ref.get();
        if (a == null) return null;
        try {
            if (a.isFinishing() || a.isDestroyed()) return null;
        } catch (Throwable ignored) { }
        return a;
    }

    /* ══════════════════════════════════════════════════════════════ 存储 / 小工具 */

    private static Context appOf(Context context) {
        if (context == null) return null;
        try {
            Context app = context.getApplicationContext();
            return app != null ? app : context;
        } catch (Throwable t) {
            return context;
        }
    }

    private static SharedPreferences prefs(Context context) {
        if (context == null) return null;
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long seen() {
        return seen(sApp != null ? sApp : io.github.ldxm666.mapclean.App.ctx());
    }

    private static long seen(Context context) {
        try {
            SharedPreferences p = prefs(context != null ? context
                    : (sApp != null ? sApp : io.github.ldxm666.mapclean.App.ctx()));
            return p == null ? 0L : p.getLong(K_SEEN_ID, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void rememberSeen(long id) {
        try {
            SharedPreferences p = prefs(sApp != null ? sApp : io.github.ldxm666.mapclean.App.ctx());
            if (p == null) return;
            long current = p.getLong(K_SEEN_ID, 0L);
            if (id > current) p.edit().putLong(K_SEEN_ID, id).commit();
        } catch (Throwable ignored) { }
    }

    /** 存原文 + **按 host 分开的** ETag。串用 ETag 会让域名请求拿到 IP 的 304。 */
    private static void cache(SharedPreferences p, String host, String body, String etag) {
        try {
            SharedPreferences.Editor e = p.edit().putString(P_CACHE, body);
            if (etag != null && etag.length() > 0) {
                e.putString(P_ETAG_PREFIX + host, etag);
            }
            e.commit();
        } catch (Throwable ignored) { }
    }

    /** 缓存里的公告（入库时已验签，这里再验一次 —— 缓存文件也可能被改）。 */
    private static Msg cached(Context context) {
        try {
            SharedPreferences p = prefs(context);
            if (p == null) return null;
            String body = p.getString(P_CACHE, null);
            if (body == null || body.length() == 0) return null;
            Msg m = parse(body);
            return m != null && sigOk(m) && m.title.length() > 0 ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean online(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return true;      // 拿不到判据就交给请求自己失败（3 秒超时，代价可接受）
        }
    }

    private static boolean allowed(String url) {
        return ServerFetch.allowed(url);
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String s = value.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    /* ══════════════════════════════════════════════════════════════ 日志限频 */

    private static final Map<String, Integer> LOGGED = new HashMap<String, Integer>();

    /** 同一类事件每进程最多 N 行：公告每次联网都会跑，不设闸就是刷屏。 */
    private static boolean mayLog(String key, int cap) {
        synchronized (LOGGED) {
            Integer seen = LOGGED.get(key);
            int count = seen == null ? 0 : seen.intValue();
            if (count >= cap) return false;
            LOGGED.put(key, Integer.valueOf(count + 1));
            return true;
        }
    }

    private static void skip(String reason) {
        if (mayLog("skip:" + reason, 6)) {
            Log.i(TAG, "event=announce_skip reason=" + reason);
        }
    }
}

