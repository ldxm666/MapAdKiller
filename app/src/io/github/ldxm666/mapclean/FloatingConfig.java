package io.github.ldxm666.mapclean;

import android.util.Log;

import io.github.libxposed.service.XposedService;

/**
 * Read/write access to the hook-side configuration from inside the host (map) process.
 *
 * <h3>Why two paths</h3>
 * In a normal LSPosed install the module's own App process holds the writable
 * {@code RemotePreferences}, and the hook process only gets a read-only view. In an LSPatch
 * <i>integrated</i> build there is no separate module process at all: the patcher hands the
 * host a writable Binder, which arrives as an {@link XposedService} via
 * {@code XposedServiceHelper}.
 *
 * <p>So writes try, in order:
 * <ol>
 *   <li>the embedded service (present in the integrated build),</li>
 *   <li>the hook-side preferences (writable in frameworks that expose it).</li>
 * </ol>
 * Reads always use the same snapshot the hooks read, so the panel can never disagree with
 * what the hooks actually enforce.
 *
 * <h3>Which vendor</h3>
 * Decided by {@link Vendor}: the orb is drawn inside the host, so it can only ever be
 * configuring the map it is running in. The per-vendor details (group name, default for a
 * given key, whether a write needs pushing into a live view tree) live in {@link AmapPanel},
 * {@link BaiduPanel} and {@link TmapPanel}.
 */
public final class FloatingConfig {

    /**
     * The 悬浮窗 master switch: true = the orb and its sheet are attached, false = no overlay.
     *
     * <p>Deliberately <b>not</b> in {@code mapadkiller.Config} / {@code bmapclean.Spec}: this
     * switch belongs to {@link FloatingController}, which every variant keeps, and putting it
     * here means all three vendors read the same key through the same {@link #read} /
     * {@link #write} pair without touching a vendor catalogue.
     *
     * <p>Default is <b>on</b>: an unset key must keep the orb, because in the 免 root build the
     * orb is the only way into the settings sheet. All three vendor default tables fall through
     * to {@code true} for an unknown key, so no per-vendor edit is needed -- see the note on
     * {@link #floatEnabled()}.
     */
    public static final String K_FLOAT_ENABLED = "float_enabled";

    /* ---------------------------------------------------------------- 悬浮窗外观 */

    /**
     * Appearance of the orb and the sheet, adjustable from the sheet's 外观 page.
     *
     * <h3>Why these are ints, and why they need their own channel</h3>
     * {@link #read(String)} / {@link #write(String, boolean)} are the <b>switch</b> channel:
     * every vendor panel resolves a missing boolean through its own {@code defaultVisible}
     * table, which is exactly right for on/off rows and useless for a size. These four keys
     * are therefore stored as ints and read through {@link #readInt} / {@link #writeInt},
     * which never touch that table.
     *
     * <h3>Defaults are the pre-2.2.2 behaviour</h3>
     * An unset key must reproduce what shipped before this page existed: a
     * {@link #BALL_SIZE_DEFAULT} orb, fully opaque, and a sheet that spans the screen. A user
     * who never opens the page must not see the orb change after upgrading.
     */
    public static final String K_FLOAT_BALL_SIZE = "float_ball_size";
    public static final String K_FLOAT_BALL_ALPHA = "float_ball_alpha";
    public static final String K_FLOAT_PANEL_WIDTH = "float_panel_width";
    public static final String K_FLOAT_PANEL_ALPHA = "float_panel_alpha";

    /** Orb diameter in dp. 36dp still leaves a 52dp tap target with the 8dp pad. */
    public static final int BALL_SIZE_MIN = 36, BALL_SIZE_MAX = 72, BALL_SIZE_DEFAULT = 46;
    /** Orb opacity in percent. Below ~30% the parked grab tab stops reading as a control. */
    public static final int BALL_ALPHA_MIN = 30, BALL_ALPHA_MAX = 100, BALL_ALPHA_DEFAULT = 100;
    /** Sheet width in dp; {@code 0} is the "自动" stop = full width, the pre-2.2.2 look. */
    public static final int PANEL_WIDTH_AUTO = 0;
    public static final int PANEL_WIDTH_MIN = 280, PANEL_WIDTH_MAX = 420, PANEL_WIDTH_DEFAULT = 0;
    /** Sheet plate opacity in percent; the scrim behind it is deliberately not affected. */
    public static final int PANEL_ALPHA_MIN = 60, PANEL_ALPHA_MAX = 100, PANEL_ALPHA_DEFAULT = 100;

    /**
     * Preference group for the appearance keys on the vendors whose panel has no int channel
     * (百度 / 腾讯). 高德 keeps them in its own {@code amap_enhancer_config} group through
     * {@link AmapPanel}, i.e. the same file its switch rows already write.
     *
     * <p><b>未确认</b>: this path is untested on a 百度 / 腾讯 device -- this build only ships 高德.
     */
    private static final String FLOAT_GROUP = "mapclean_float";

    private FloatingConfig() {}

    /** Orb diameter in dp, clamped to the range the sheet can show. */
    public static int ballSizeDp() {
        return clamp(readInt(K_FLOAT_BALL_SIZE, BALL_SIZE_DEFAULT), BALL_SIZE_MIN, BALL_SIZE_MAX);
    }

    /** Orb opacity in percent, clamped. */
    public static int ballAlphaPercent() {
        return clamp(readInt(K_FLOAT_BALL_ALPHA, BALL_ALPHA_DEFAULT),
                BALL_ALPHA_MIN, BALL_ALPHA_MAX);
    }

    /** Sheet width in dp; {@link #PANEL_WIDTH_AUTO} means "follow the screen". */
    public static int panelWidthDp() {
        int stored = readInt(K_FLOAT_PANEL_WIDTH, PANEL_WIDTH_DEFAULT);
        if (stored <= PANEL_WIDTH_AUTO) return PANEL_WIDTH_AUTO;
        return clamp(stored, PANEL_WIDTH_MIN, PANEL_WIDTH_MAX);
    }

    /** Sheet plate opacity in percent, clamped. */
    public static int panelAlphaPercent() {
        return clamp(readInt(K_FLOAT_PANEL_ALPHA, PANEL_ALPHA_DEFAULT),
                PANEL_ALPHA_MIN, PANEL_ALPHA_MAX);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * Read one int-valued key.
     *
     * <p>Deliberately <b>not</b> routed through {@link #read(String, boolean)}: that one
     * answers "is this switch on" and would turn a stored {@code 46} into {@code true}.
     * Every failure path returns {@code fallback}, because a preferences read that throws
     * (service not bound yet, group missing) must degrade to the default orb rather than
     * crash {@code FloatingBall}'s constructor.
     */
    public static int readInt(String key, int fallback) {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return readIntViaService(key, fallback);
            return AmapPanel.readInt(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Persist one int-valued key. {@code commit()}, not {@code apply()}: the user can leave
     * the map the instant the finger lifts off the slider.
     *
     * <p>Unlike the switch channel this does <b>not</b> run a vendor "apply" step: no hook
     * reads these keys, so pushing them into the live view tree would only cost a native
     * refresh on every slider release.
     */
    public static boolean writeInt(String key, int value) {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return writeIntViaService(key, value);
            return AmapPanel.writeInt(key, value);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int readIntViaService(String key, int fallback) {
        XposedService service = App.service();
        if (service == null) return fallback;
        return service.getRemotePreferences(FLOAT_GROUP).getInt(key, fallback);
    }

    private static boolean writeIntViaService(String key, int value) {
        XposedService service = App.service();
        if (service == null) return false;
        return service.getRemotePreferences(FLOAT_GROUP).edit().putInt(key, value).commit();
    }

    /**
     * The 悬浮窗 switch, read the way the controller reads it.
     *
     * <p>Verified per vendor: 高德 {@code Config.defaultVisible}, 百度
     * {@code Spec.defaultVisible} and 腾讯 {@code Spec.defaultVisible} all return {@code true}
     * for a key they do not know, so {@code float_enabled} defaults to on everywhere. The
     * {@code true} fallback below covers the "preferences not reachable yet" case, which must
     * also mean "keep the orb" rather than "silently lose the only entry point".
     */
    /**
     * 悬浮窗是否属于「免 root 内置版」。
     *
     * <p>产品要求：**悬浮窗是免 root 用户专属**。模块版（LSPosed / root 用户）应该用模块 App
     * 的设置页，默认不往高德里挂球 —— 但功能不删，用户在面板里显式打开即可。
     *
     * <p>判据只看 {@code App.isEmbedded()}（宿主 APK 内是否有 {@code assets/lspatch/config.json}），
     * **不看**"有没有 root" —— 那既判断不了也不可靠。该方法由 {@code amap-core2} 提供，
     * 这里用反射调用：两种构建都能编译，拿不到就按模块版处理。
     */
    public static boolean isEmbedded() {
        int known = embeddedState;
        if (known >= 0) return known == 1;
        boolean value;
        try {
            // 首选**直连**：HostInfo 是真实类（两种构建都有，不是 stub），
            // 判据是宿主 APK 内是否有 assets/lspatch/config.json。
            value = io.github.ldxm666.mapadkiller.HostInfo.isEmbedded();
        } catch (Throwable t) {
            // 兜底：万一 HostInfo 被挪走或改签名，再走一次反射。
            // ⚠ 2.3.0 修正：原来写的是 mapclean.App 的 "isEmbedded" —— 那个方法名不存在
            // （真名是 embedded()，见 App.java），所以这条兜底从写上那天起就恒返回 false。
            // 一旦 HostInfo 真的缺席，内置版会被误判成模块版 ⇒ 悬浮球默认关 ⇒ 用户失去唯一入口。
            value = reflectBool("io.github.ldxm666.mapadkiller.HostInfo", "isEmbedded")
                    || reflectBool("io.github.ldxm666.mapclean.App", "embedded");
        }
        embeddedState = value ? 1 : 0;
        return value;
    }

    /** 反射调用无参静态布尔方法；类或方法不存在一律返回 false，不抛。 */
    private static boolean reflectBool(String className, String method) {
        try {
            Object r = Class.forName(className).getMethod(method).invoke(null);
            return r instanceof Boolean && ((Boolean) r).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }
    /** -1 = 未判定, 0 = 模块版, 1 = 内置版。判定只做一次。 */
    private static volatile int embeddedState = -1;

    /** 悬浮球的**默认**开关：内置版 = 开；模块版 = 关。显式写过的值永远优先。 */
    public static boolean floatDefault() {
        return isEmbedded();
    }

    /**
     * 悬浮窗总开关。默认值按 {@link #floatDefault()} 分流，判定只在这一层做。
     *
     * <p>读配置优先：老用户若已显式写过 {@code float_enabled=true}，模块版也照样挂球。
     */
    public static boolean floatEnabled() {
        // ① 远程/面板通道（正常情况）
        try {
            if (hasStored(K_FLOAT_ENABLED)) {
                boolean v = read(K_FLOAT_ENABLED, floatDefault());
                mirrorPut(v);
                return v;
            }
        } catch (Throwable ignored) { }
        // ② 启动早期框架服务还没绑定 ⇒ hasStored 读不到，会误判成"从未写过"从而回落到默认值
        //    （默认 true）⇒ 用户明明关掉了，重开 App 球又出现。用本地镜像兜底。
        Boolean m = mirrorGet();
        if (m != null) return m.booleanValue();
        // ③ 真正从未写过
        return floatDefault();
    }

    // ── 本地镜像：把「用户最后一次显式选择」记在宿主进程自己的 SharedPreferences 里 ──
    // 远程通道（LSPatch/LSPosed 的 getRemotePreferences）在进程刚起来时可能还不可用，
    // 那一刻读不到值就会回落到默认值，导致「关掉的悬浮窗重开 App 又出现」。
    private static final String MIRROR_PREFS = "mapclean_float_mirror";
    private static final String MIRROR_KEY = "float_enabled";

    private static android.content.SharedPreferences mirror() {
        try {
            android.content.Context c = io.github.ldxm666.mapclean.App.ctx();
            if (c == null) return null;
            return c.getSharedPreferences(MIRROR_PREFS, android.content.Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean mirrorGet() {
        try {
            android.content.SharedPreferences p = mirror();
            if (p == null || !p.contains(MIRROR_KEY)) return null;
            return Boolean.valueOf(p.getBoolean(MIRROR_KEY, true));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void mirrorPut(boolean value) {
        try {
            android.content.SharedPreferences p = mirror();
            if (p != null) p.edit().putBoolean(MIRROR_KEY, value).apply();
        } catch (Throwable ignored) { }
    }

    /**
     * True when the key has an <b>explicit</b> stored value.
     *
     * <p>{@code Config.visible()} hides that distinction behind {@code defaultVisible}, and the
     * mode-aware default for {@code float_enabled} only applies to "never written". 百度/腾讯
     * 变体返回 true（按已设置处理），不改变它们原有行为 —— 只有高德变体会被构建。
     */
    private static boolean hasStored(String key) {
        if (Vendor.tmap() || Vendor.baidu()) return true;
        try {
            return AmapPanel.hasStored(key);
        } catch (Throwable t) {
            return true;
        }
    }

    /** No writable path at all (neither the service nor the module's own preferences). */
    public static final int WRITE_NONE = 0;
    /** No framework service, but {@code Config} has a local backend -- writable in-process. */
    public static final int WRITE_PREFS = 1;
    /** The framework service is bound: writable in every build. */
    public static final int WRITE_SVC = 2;

    /**
     * Which write path is usable right now, for the panel's status pill.
     *
     * <p>Three states instead of a boolean: 「只读 · 服务未连接」 used to be shown even when the
     * in-process preferences were perfectly writable, which told the user their tap could not
     * work when it could (and the other way round).
     */
    public static int writePath() {
        try {
            if (Vendor.tmap() || Vendor.baidu()) {
                // ★ 2.3.0：**先实测再下结论**。内置版（LSPatch）里服务通道常常是通的，
                //   但那条通道读的是只读代理；真正能落盘的是宿主本地 prefs。
                //   所以顺序改成：本地实测写得进 ⇒ LOCAL；否则服务在 ⇒ REMOTE；都没有 ⇒ NONE。
                //   反过来（先看 service）就会把一份完全能写的配置标成「○ 只读」——
                //   真机日志：`event=svc_null where=status path=0` 出现在 Cfg 明明能写的时候。
                if (probeVendorPrefs()) return WRITE_PREFS;
                if (App.service() != null) return WRITE_SVC;
                return WRITE_NONE;
            }
            return AmapPanel.writePath();
        } catch (Throwable t) {
            return WRITE_NONE;
        }
    }

    /** -1 未探测 / 0 写不进 / 1 可写。**只缓存成功**（见 {@link #probeVendorPrefs()}）。 */
    private static volatile int vendorPrefsProbe = -1;

    /**
     * 用一次真实写入判定厂商的偏好存储能不能写。
     *
     * <p>走反射是因为 {@code bmapclean.Cfg} / {@code tmapclean.Cfg} 在别的变体里整个包都被裁掉，
     * 编译期不能直接引用。写一个临时键、立刻删掉，不污染配置。
     *
     * <p>★ 2.3.0 的两处修正：
     * <ol>
     *   <li><b>只缓存成功</b>。原来失败也写进 {@link #vendorPrefsProbe} 并永久生效 ——
     *       只要第一次探测恰好赶在「框架服务还没绑上 / 宿主 Context 还没就绪」的那一刻，
     *       整个进程就恒判 {@link #WRITE_NONE}，面板从此死死显示「只读」。
     *       这正是用户说的「第二次打开一直只读」。</li>
     *   <li><b>探测对象换成 {@code Cfg.prefs()} 的解析结果</b>。它现在在内置版里返回宿主的
     *       本地 SharedPreferences（永远可写），不再是那个 {@code commit()} 必抛
     *       {@code UnsupportedOperationException} 的只读代理。</li>
     * </ol>
     */
    private static boolean probeVendorPrefs() {
        if (vendorPrefsProbe == 1) return true;
        boolean ok = false;
        Object sp = null;
        String cls = null;
        try {
            cls = Vendor.baidu() ? "io.github.ldxm666.bmapclean.Cfg"
                    : Vendor.tmap() ? "io.github.ldxm666.tmapclean.Cfg" : null;
            if (cls != null) {
                sp = Class.forName(cls).getMethod("prefs").invoke(null);
                if (sp instanceof android.content.SharedPreferences) {
                    ok = io.github.ldxm666.mapclean.HostPrefs
                            .writable((android.content.SharedPreferences) sp);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "event=cfg_probe threw cls=" + cls + ": " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
        // 只在成功时定案；失败留给下次（面板每次 refreshStatus 都会再试一次）。
        if (ok) {
            vendorPrefsProbe = 1;
        } else {
            // 失败必须留下证据：哪条后端、拿到的是什么对象、四条 Context 路各自给了什么。
            // 没有这三行，「写得进但显示只读」就永远只能靠猜。
            Log.w(TAG, "event=cfg_probe fail cls=" + cls
                    + " tmap=" + Vendor.tmap() + " baidu=" + Vendor.baidu()
                    + " prefs=" + io.github.ldxm666.mapclean.HostPrefs.describe(sp)
                    + " remembered=" + io.github.ldxm666.mapclean.HostPrefs.hasRemembered());
            Class<?> c = null;
            if (sp != null) c = sp.getClass();
            Log.w(TAG, "event=cfg_probe impl=" + (c == null ? "null" : c.getName()));
            io.github.ldxm666.mapclean.HostPrefs.diagnose("probe");
        }
        return ok;
    }

    /** True when a writable path is currently available. */
    public static boolean writable() {
        return writePath() > 0;
    }

    /** No backend at all (mirrors {@code Config.BACKEND_NONE}). */
    public static final int BACKEND_NONE = io.github.ldxm666.mapadkiller.Config.BACKEND_NONE;
    /** Local in-process backend: writable without any framework channel. */
    public static final int BACKEND_LOCAL = io.github.ldxm666.mapadkiller.Config.BACKEND_LOCAL;
    /** Framework remote-preferences backend (service bound). */
    public static final int BACKEND_REMOTE = io.github.ldxm666.mapadkiller.Config.BACKEND_REMOTE;

    /**
     * The configuration backend actually in use, after verification.
     *
     * <p>{@code NONE} is never reported on a static verdict alone -- the vendor panel probes it
     * with one real write first, because a stale NONE is exactly what told users 「只读」 while
     * the sheet was running inside the target app.
     */
    public static int backend() {
        try {
            if (Vendor.tmap() || Vendor.baidu()) {
                // 与 writePath() 同源、同顺序：实测能写 ⇒ 本地；服务在 ⇒ 远程；都没有 ⇒ NONE。
                // 只认 service() 会把一份完全能写的配置显示成「○ 只读」
                // （FloatingPanel 把这个分支直接印成「只读」）。
                if (probeVendorPrefs()) return BACKEND_LOCAL;
                if (App.service() != null) return BACKEND_REMOTE;
                return BACKEND_NONE;
            }
            return AmapPanel.effectiveBackend();
        } catch (Throwable t) {
            return BACKEND_NONE;
        }
    }

    /** Nothing written yet in this process. */
    public static final int W_UNTRIED = 0;
    /** The last write landed. */
    public static final int W_OK = 1;
    /** The last write was refused (permissions / IO / read-only proxy). */
    public static final int W_REFUSED = 2;

    /**
     * Result of the last write attempt, so the header pill can stop claiming 「可写入」 while
     * every tap is being refused. Never polled -- it is set by the write itself.
     *
     * <p>★ 2.3.0：原来百度/腾讯分支是「{@code App.service() == null} 即 {@link #W_REFUSED}」。
     * 内置版的服务通道本来就起不来，于是**连接完全正常也被判成「● 本地 · 写入被拒」**，
     * 而用户实际能写（本地后端）。现在只认 {@link #vLastWrite} —— 由 {@link #write(String, boolean)}
     * 的真实 commit 结果更新，没写过就是 {@link #W_UNTRIED}。
     */
    public static int lastWriteState() {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return vLastWrite;
            return AmapPanel.lastWriteState();
        } catch (Throwable t) {
            return W_UNTRIED;
        }
    }

    /** 百度/腾讯分支最近一次真实写入的结果。{@link #W_UNTRIED} = 本次进程还没写过。 */
    private static volatile int vLastWrite = W_UNTRIED;

    /** {@code svc_null} / {@code prefs_null} / {@code commit_false} / {@code io}. */
    public static String lastWriteReason() {
        try {
            return Vendor.tmap() || Vendor.baidu() ? null : AmapPanel.lastWriteReason();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read one switch. {@code float_enabled} is special: 悬浮窗是免 root 内置版专属，
     * 所以"从未写过"时用 {@link #floatDefault()} 而不是可见性表的 true。
     */
    public static boolean read(String key) {
        if (K_FLOAT_ENABLED.equals(key) && !hasStored(key)) return floatDefault();
        if (Vendor.tmap()) return TmapPanel.read(key);
        return Vendor.baidu() ? BaiduPanel.read(key) : AmapPanel.read(key);
    }

    public static boolean read(String key, boolean fallback) {
        try {
            if (Vendor.tmap()) return TmapPanel.read(key);
            return Vendor.baidu() ? BaiduPanel.read(key) : AmapPanel.read(key);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Read a switch whose "on" is the <b>literal</b> stored value rather than the visibility
     * table -- today only {@code labels_on}, whose consumer reads {@code Config.selected()}.
     *
     * <p>Without this the row would hydrate from {@link #read(String, boolean)}, which resolves
     * a missing key through {@code defaultVisible} and answers {@code true}: the switch would
     * show ON while the native label filter is actually off (its default).
     */
    public static boolean readStrict(String key) {
        try {
            if (Vendor.tmap() || Vendor.baidu()) return read(key, false);
            return AmapPanel.readStrict(key);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Persist one switch. The vendor panel uses {@code commit()} rather than {@code apply()}:
     * the user may leave the map immediately after flipping a switch, and an async write
     * would be lost.
     */
    public static boolean write(String key, boolean value) {
        boolean ok;
        if (Vendor.tmap()) ok = TmapPanel.write(key, value);
        else ok = Vendor.baidu() ? BaiduPanel.write(key, value) : AmapPanel.write(key, value);
        // 记下真实结果：药丸（FloatingPanel）据此显示「● 本地 · 写入被拒」，而不是像以前那样
        // 拿「服务有没有绑上」当写入成败的替身 —— 内置版服务恒 null，会一直谎报被拒。
        if (Vendor.tmap() || Vendor.baidu()) vLastWrite = ok ? W_OK : W_REFUSED;
        // The 悬浮窗 key is the one switch whose "apply" step is not a vendor action but the
        // controller itself: without this, flipping it in the sheet would only take effect on
        // the next resume, which reads as "the switch does nothing".
        //
        // Applied only when the write landed: the vendor panels' own readers cache the same
        // snapshot, so acting on a failed write would make the UI and the hook disagree.
        if (ok && K_FLOAT_ENABLED.equals(key)) {
            // 记住用户的显式选择：下次进程启动时远程通道可能还没就绪，靠它兜底，
            // 否则「关掉的悬浮窗重开 App 又出现」（float_enabled 读不到就回落到默认 true）。
            mirrorPut(value);
            try {
                FloatingController.setEnabled(value);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "float toggle failed: " + t.getClass().getSimpleName());
            }
        }
        return ok;
    }

    /** Drop the cached snapshot after a write so the panel and the hooks agree at once. */
    public static void invalidate() {
        // 后端可能刚变过（服务迟绑 / 宿主 Context 就绪）⇒ 允许重新探测，别让旧的失败结论卡住。
        vendorPrefsProbe = -1;
        if (Vendor.tmap()) TmapPanel.invalidate();
        else if (Vendor.baidu()) BaiduPanel.invalidate();
        else AmapPanel.invalidate();
    }

    private static final String TAG = "MapCleanFloat";

    /** One line per process: this catch used to be silent, which hid a dead service for a release. */
    private static final java.util.concurrent.atomic.AtomicBoolean gateReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** One line per process the first time the panel finds no service at all. */
    private static final java.util.concurrent.atomic.AtomicBoolean svcNullReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Best-effort recovery before a write is reported as failed.
     *
     * <p>The service binds asynchronously, and the sheet can be built before it lands. Running
     * the attach path here registers the delivery listener (it is idempotent), so the next tap
     * succeeds instead of the user concluding the module is read-only.
     */
    public static void ensureServiceFrom(android.content.Context context) {
        if (context == null) return;
        try {
            android.content.Context app = context.getApplicationContext();
            if (app instanceof android.app.Application) ensureService((android.app.Application) app);
        } catch (Throwable ignored) {
            // Nothing to recover with; the caller falls back to the failure toast.
        }
    }

    /** One-shot diagnostic: the panel found neither the service nor in-process preferences. */
    public static void reportNoWritePath(String where) {
        if (svcNullReported.compareAndSet(false, true)) {
            android.util.Log.w(TAG, "event=svc_null where=" + where + " path=0");
        }
    }

    /**
     * The embedded service binds asynchronously, and it can be ready long before the user
     * ever opens the panel. Make sure the host Application is attached to the module's
     * static holders so {@code App.svc()} has somewhere to land.
     */
    public static void ensureService(android.app.Application application) {
        if (application == null) return;
        try {
            io.github.ldxm666.mapclean.App.attachEmbedded(application);
        } catch (Throwable t) {
            // Not an LSPatch host (plain LSPosed) -- the hook-side path still works.
            //
            // Never swallow this silently: {@code attachEmbedded} registers the framework
            // service listener on its last line, so an exception here means this process has
            // *no* delivery path at all -- {@code App.svc()} stays null and every vendor
            // holder's panel reads 只读. That is exactly how the 腾讯 build shipped with a
            // dead service while 百度 worked: the gate was a vendor allow-list that did not
            // name com.tencent.map. Logged once per process, with the stack, so the next
            // occurrence is a one-line diagnosis instead of a log hunt.
            if (gateReported.compareAndSet(false, true)) {
                android.util.Log.w(TAG, "embedded service gate closed pkg="
                        + application.getPackageName(), t);
            }
        }
    }
}
