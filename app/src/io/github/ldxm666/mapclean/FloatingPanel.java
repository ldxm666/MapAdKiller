package io.github.ldxm666.mapclean;

import android.animation.ValueAnimator;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.ldxm666.mapadkiller.CaptureLog;


/**
 * The settings sheet the floating orb opens: a glass bottom sheet with a category rail
 * and animated switch rows.
 *
 * <h3>Structure</h3>
 * <pre>
 *   scrim            tap to dismiss, fades with the sheet
 *   └ card           rounded glass plate, slides up from the bottom
 *     ├ header       title + live "服务已连接 / 只读" pill + close
 *     ├ rail         horizontal category chips (首页 / 工具 / 内容 / 我的 / 地图)
 *     └ body         scrollable switch rows for the selected category
 * </pre>
 *
 * <h3>Why a hand-built sheet</h3>
 * The module compiles against {@code android.jar} only -- no AndroidX, no Material
 * Components -- so there is no {@code BottomSheetDialogFragment} to lean on. Everything
 * here is plain framework views plus the shared {@link UiKit} motion kit, which keeps the
 * look identical to the main settings page.
 */
public final class FloatingPanel extends FrameLayout implements PanelTypes {

    /**
     * One toggle exposed in the sheet.
     *
     * <p>Package-private on purpose: the catalogue is built by {@link AmapPanel} or
     * {@link BaiduPanel}, one vendor per build.
     */

    /** The four appearance rows, in the order the 外观 page shows them. */
    private static final Metric[] APPEARANCE = new Metric[]{
        new Metric(FloatingConfig.K_FLOAT_BALL_SIZE, "悬浮球大小", null, "dp",
                steps(FloatingConfig.BALL_SIZE_MIN, FloatingConfig.BALL_SIZE_MAX, 2),
                null, FloatingConfig.BALL_SIZE_DEFAULT),
        new Metric(FloatingConfig.K_FLOAT_BALL_ALPHA, "悬浮球不透明度",
                "调到 100% 以下时，悬浮球与收起后的把手都会变淡。", "%",
                steps(FloatingConfig.BALL_ALPHA_MIN, FloatingConfig.BALL_ALPHA_MAX, 5),
                null, FloatingConfig.BALL_ALPHA_DEFAULT),
        new Metric(FloatingConfig.K_FLOAT_PANEL_WIDTH, "面板宽度",
                "「自动」= 跟随屏幕宽度，与之前一致。", "dp",
                widthStops(), new String[]{"自动"}, FloatingConfig.PANEL_WIDTH_DEFAULT),
        new Metric(FloatingConfig.K_FLOAT_PANEL_ALPHA, "面板不透明度", null, "%",
                steps(FloatingConfig.PANEL_ALPHA_MIN, FloatingConfig.PANEL_ALPHA_MAX, 5),
                null, FloatingConfig.PANEL_ALPHA_DEFAULT),
    };

    /** Inclusive stop list, e.g. 36..72 step 2. */
    private static int[] steps(int from, int to, int step) {
        int count = (to - from) / step + 1;
        int[] out = new int[Math.max(1, count)];
        for (int i = 0; i < out.length; i++) out[i] = from + i * step;
        return out;
    }

    /** 自动 first, then the numeric range -- so the leftmost stop is the pre-2.2.2 look. */
    private static int[] widthStops() {
        int[] range = steps(FloatingConfig.PANEL_WIDTH_MIN, FloatingConfig.PANEL_WIDTH_MAX, 10);
        int[] out = new int[range.length + 1];
        out[0] = FloatingConfig.PANEL_WIDTH_AUTO;
        System.arraycopy(range, 0, out, 1, range.length);
        return out;
    }


    private static final String[][] GROUPS = new String[0][];

    /** Log tag shared with {@code FloatingController}/{@code FloatingHooks}. */
    private static final String TAG = "MapCleanFloat";

    private final Context context;
    private final boolean dark;
    /** Rebuilt whenever the sheet opens, so the "enabled first" tool ordering stays true. */
    private Group[] groups;
    private final java.util.Map<String, GlassSwitch> switches = new java.util.LinkedHashMap<String, GlassSwitch>();
    /** Live slider rows of the 外观 page, keyed by config key, so {@link #sync()} can refresh. */
    private final java.util.Map<String, MetricRow> metricRows = new java.util.LinkedHashMap<String, MetricRow>();
    /**
     * Keys whose "on" is the literal stored value (see {@link Item#strict}).
     *
     * <p>{@link #sync()} only has the key, not the {@code Item}, so the mode is remembered here
     * when the row is built.
     */
    private final java.util.Set<String> strictKeys = new java.util.LinkedHashSet<String>();

    /* ------------------------------------------------------- 版本 / 更新（单一状态机） */

    /** Not checked yet (or the last answer was lost) -- grey. */
    private static final int U_UNKNOWN = 0;
    /** A check is in flight -- grey, 「检查中…」. */
    private static final int U_CHECKING = 1;
    /** Online == local -- green. */
    private static final int U_LATEST = 2;
    /** Online > local -- red plus the 「新版本」 badge. */
    private static final int U_NEW = 3;
    /** The check found nothing online -- grey. */
    private static final int U_FAILED = 4;

    /**
     * One state for both places that show it: the header chip and the 「其他」 page rows.
     *
     * <p>They must never query independently -- that would double the network requests for a
     * purely cosmetic line. {@link #startUpdateCheck} is the only caller of
     * {@code UpdateChecker.check}, and {@link #paintUpdate} repaints every surface that is
     * currently attached.
     */
    private int updateState = U_UNKNOWN;
    private String updateLatest;
    /** The process-wide automatic check runs once, on the first open of the sheet. */
    private boolean updateAutoChecked;
    /** A check is in flight; guards against a second round that would never get a callback. */
    private boolean updateInFlight;
    /** Hard cap on the 「检查中…」 state: UpdateChecker's own deadline is 12s, this is the net. */
    private static final long WATCHDOG_MS = 13000L;

    private TextView versionChip;
    private TextView versionBadge;
    /** The 「其他」 page's copy of the same state; null while another page is showing. */
    private TextView aboutUpdateRow;
    /** 「镜像下载」 link on the same page, visible only when a newer version is known. */
    private TextView aboutMirrorRow;

    /* ------------------------------------------------------- 写入失败提示（持久行） */

    /** 「⚠ 上次写入失败：<原因>（点此重试）」, always in the footer, hidden while writes work. */
    private TextView writeWarnRow;
    private String lastFailedKey;
    private boolean lastFailedValue;
    /** Uptime of the last {@link #open()}, for the scrim's grace window. */
    private long openedAt;
    /** A scrim tap inside this window is the UP of the press that opened the sheet. */
    private static final long SCRIM_GRACE_MS = 800L;

    /**
     * The orb of this layer, wired by {@link FloatingController#attach}.
     *
     * <p>The sheet previews orb changes on the very orb the user is about to touch, which is
     * the whole point of "拖动时球立即变". Null until the controller has built the ball; every
     * use is null-guarded so a sheet opened in a half-built layer cannot throw.
     */
    private FloatingBall ball;

    /** Persisted plate opacity, 0.6..1. Applied to the card only -- never to the scrim. */
    private float panelAlpha = FloatingConfig.PANEL_ALPHA_DEFAULT / 100f;
    /** Last width handed to {@link #applyPanelWidth}; guards redundant layout passes. */
    private int appliedPanelWidth = Integer.MIN_VALUE;

    private final View scrim;
    private final LinearLayout card;
    private final LinearLayout body;

    /** Built in {@link #buildRail()} / {@link #buildHeader()}, both called from the constructor. */
    private LinearLayout rail;
    /** The horizontal scroller around {@link #rail}; used to bring the selected chip into view. */
    private HorizontalScrollView railScroll;
    private TextView statusPill;

    /** Search box; typing filters every category at once. */
    private EditText searchField;
    private TextView searchClear;
    private TextView searchCount;
    private String query = "";

    /* ---- horizontal swipe between categories ---- */
    /** Where the finger went down, so a swipe can be told from a scroll or a tap. */
    private float swipeDownX, swipeDownY;
    /** True once the gesture is unambiguously horizontal and we have taken it over. */
    private boolean swipeActive;
    /** A swipe that starts on the category rail must scroll the rail, not change category. */
    private boolean swipeFromRail;

    private int selected;
    private boolean open;
    private Runnable onClose;

    private boolean capturePrompted;
    private boolean captureFinished;

    public FloatingPanel(Context c, boolean darkTheme) {
        super(c);
        this.context = c;
        this.dark = darkTheme;
        Vendor.from(c);          // the orb is drawn inside the host, so this is authoritative
        this.groups = buildGroups();

        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setClickable(true);   // swallow taps so they never reach the map behind

        // ---- scrim -------------------------------------------------------
        scrim = new View(c);
        scrim.setBackgroundColor(0x00000000);
        scrim.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                // Grace window: the long press that opens this sheet fires at ~750ms and its
                // UP can land on the freshly attached scrim, which used to dismiss the sheet
                // ~1.9s after it appeared (the 「点了没反应」 reports). A tap inside the window
                // is treated as part of the opening gesture and ignored.
                long age = android.os.SystemClock.uptimeMillis() - openedAt;
                if (age < SCRIM_GRACE_MS) {
                    Log.i(TAG, "event=sheet_close_ignored cause=scrim age=" + age);
                    return;
                }
                closeFrom("scrim");
            }
        });
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- card --------------------------------------------------------
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.glass(cGlass(), cRimHi(), cRimLo(),
                UiKit.dpf(c, 26f), UiKit.dp(c, 1)));
        card.setElevation(0);
        LayoutParams cardLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.BOTTOM;
        cardLp.leftMargin = UiKit.dp(c, 10);
        cardLp.rightMargin = UiKit.dp(c, 10);
        // Clear the gesture / navigation bar, otherwise the footer sits under it.
        cardLp.bottomMargin = UiKit.dp(c, 10) + navigationBarInset(c);
        card.setLayoutParams(cardLp);
        addView(card);

        card.addView(buildHeader());
        card.addView(buildSearch());
        card.addView(buildRail());

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiKit.dp(c, 6), UiKit.dp(c, 2), UiKit.dp(c, 6), UiKit.dp(c, 8));
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        // Fixed sheet height: a content-sized sheet would jump every time a shorter category
        // is selected, which reads as a glitch rather than as a transition.
        int bodyHeight = Math.min(UiKit.dp(c, 396),
                (int) (c.getResources().getDisplayMetrics().heightPixels * 0.46f));
        card.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, bodyHeight));

        TextView foot = new TextView(c);
        foot.setText("修改即时写入；地图内页面可能需要重新进入才会刷新。");
        foot.setTextSize(11.5f);
        foot.setTextColor(cTxS());
        foot.setPadding(UiKit.dp(c, 20), 0, UiKit.dp(c, 20), UiKit.dp(c, 14));
        card.addView(foot);

        // Persistent write-failure line. Hidden while writes work; a toast is gone in two
        // seconds and leaves the user staring at a switch that flipped back.
        writeWarnRow = new TextView(c);
        writeWarnRow.setTextSize(11.5f);
        writeWarnRow.setTextColor(cWarn());
        writeWarnRow.setPadding(UiKit.dp(c, 20), 0, UiKit.dp(c, 20), UiKit.dp(c, 10));
        writeWarnRow.setVisibility(GONE);
        writeWarnRow.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(c, 10f)));
        writeWarnRow.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (lastFailedKey == null) return;
                boolean ok = FloatingConfig.write(lastFailedKey, lastFailedValue);
                if (ok) {
                    FloatingConfig.invalidate();
                    sync();
                    toast("已重新写入");
                } else {
                    toast(writeFailedHint());
                }
                refreshStatus();
            }
        });
        card.addView(writeWarnRow);

        card.addView(buildDonateRow());
        card.addView(buildDefaultsRow());

        // Restore the persisted appearance before the first frame: the card is GONE at this
        // point, so this only sets the layout params and the plate alpha -- no visible jump.
        applyPanelWidth(FloatingConfig.panelWidthDp());
        applyPanelAlpha(FloatingConfig.panelAlphaPercent());

        select(0, false);
    }

    /** Hand the sheet the orb of its own layer so 外观 rows can preview on the real thing. */
    void setBall(FloatingBall target) {
        ball = target;
    }

    /**
     * 页脚动作行：「恢复默认配置」。
     *
     * <p>内置的那份 JSON 只在全新安装后自动应用一次；用户后来把开关改乱了，需要一条能
     * 手动再来一次的入口。它和应用首启用的是同一份资源、同一段写入逻辑
     * （{@link DefaultConfig#apply(android.content.Context)}），会覆盖当前配置，所以先确认。
     *
     * <p>刻意做成一行低调的文字链接而不是按钮：这一页的主角是开关，页脚只是兜底。
     */
    /**
     * 内置版没有 MainActivity：2.2.5 的「赞赏支持」入口补在面板底部。
     * 只做 UI，不动任何已有逻辑；DonateAsset 解码失败时退化为一行文字，绝不崩。
     */
    private View buildDonateRow() {
        // ★ 2.3.0：**点击区放大**。
        // 原来只有「赞赏支持」那一行 13sp 文字可点：高度约 21dp、
        // 宽度也只有文字那么宽，远低于 Android 无障碍的 48dp 最小触控尺寸，
        // 用户反馈「点不到」。现在**整块可点**：
        //   · 最小高度 48dp；上下各 12dp、左右各 20dp 内边距（点击热区随之扩大）；
        //   · 浪潮背景铺满整行（原来只铺在文字上，看不出哪里能点）；
        //   · 副标题也要能点（原来它是死的，点它没反应）。
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(android.view.Gravity.CENTER_VERTICAL);
        wrap.setMinimumHeight(UiKit.dp(context, 48));
        wrap.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 12),
                UiKit.dp(context, 20), UiKit.dp(context, 12));
        wrap.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 10f)));
        wrap.setContentDescription("赞赏支持，感谢投喂");
        wrap.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { showDonate(); }
        });
        UiKit.pressable(wrap, 0.96f);

        final TextView title = new TextView(context);
        title.setText("赞赏支持");
        title.setTextSize(13.5f);
        title.setTextColor(cTxP());
        wrap.addView(title);

        TextView sub = new TextView(context);
        sub.setText("点这里扫码 · 请作者喝杯咖啡");
        sub.setTextSize(11f);
        sub.setTextColor(cTxS());
        sub.setPadding(0, UiKit.dp(context, 2), 0, 0);
        wrap.addView(sub);
        return wrap;
    }

    /** 收款码对话框（复用 DonateAsset 的 base64 图，不依赖 R 资源）。 */
    private void showDonate() {
        // 统一到 DonateUi，模块版与内置版同一套观感
        DonateUi.show(context);
    }
    private View buildDefaultsRow() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 20), 0, UiKit.dp(context, 20), UiKit.dp(context, 14));

        final TextView action = new TextView(context);
        action.setText("恢复默认配置");
        action.setTextSize(12f);
        action.setTextColor(cAccent());
        action.setPaintFlags(action.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        action.setPadding(UiKit.dp(context, 4), UiKit.dp(context, 2),
                UiKit.dp(context, 4), UiKit.dp(context, 2));
        action.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 8f)));
        action.setContentDescription("重新应用模块内置的默认配置，会覆盖当前开关");
        UiKit.pressable(action, 0.96f);
        action.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { confirmDefaults(action); }
        });
        wrap.addView(action);

        TextView hint = new TextView(context);
        hint.setText("重新套用模块内置的那套配置；会覆盖当前开关，"
                + "未设置的项（null）保持原样。");
        hint.setTextSize(10.5f);
        hint.setTextColor(cTxS());
        hint.setLineSpacing(0, 1.16f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiKit.dp(context, 2);
        wrap.addView(hint, hlp);
        return wrap;
    }

    /** 覆盖前给一次确认；确认后在后台线程写入，避免 commit() 卡住界面。 */
    private void confirmDefaults(final TextView anchor) {
        // NONE 态下这个按钮只会静默失败，用户会以为"恢复了其实没有" —— 直接说清楚原因，
        // 连确认框都不弹（弹了也没意义：确认完同样写不进去）。
        if (FloatingConfig.backend() == FloatingConfig.BACKEND_NONE) {
            toast("配置通道不可用：请在框架里确认本模块已对目标 App 启用，或重装模块");
            return;
        }
        try {
            new android.app.AlertDialog.Builder(context)
                    .setTitle("恢复默认配置？")
                    .setMessage("将把模块内置的默认配置重新写入当前地图，"
                            + "会覆盖你已经改过的开关。\n\n"
                            + "没有值的项（null）保持原样；写入后需要重新进入地图页面才会刷新。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            runDefaults(anchor);
                        }
                    })
                    .show();
        } catch (Throwable t) {
            // 宿主主题异常时退化成直接执行，至少不让入口变成死键。
            runDefaults(anchor);
        }
    }

    private void runDefaults(final TextView anchor) {
        anchor.setEnabled(false);
        anchor.setText("正在恢复…");
        final Context app = context;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                DefaultConfig.Result result = null;
                Throwable failure = null;
                try {
                    result = DefaultConfig.apply(app);
                } catch (Throwable t) {
                    failure = t;
                }
                final DefaultConfig.Result outcome = result;
                final Throwable error = failure;
                post(new Runnable() {
                    @Override public void run() {
                        anchor.setEnabled(true);
                        anchor.setText("恢复默认配置");
                        if (error != null) {
                            toast("恢复失败：" + error.getMessage());
                            return;
                        }
                        if (outcome == null || outcome.groups.isEmpty()) {
                            toast("没有可写入的默认配置（服务未连接或资源缺失）");
                            return;
                        }
                        sync();
                        toast("已恢复 " + outcome.applied + " 项，重进地图页面后生效");
                    }
                });
            }
        }, "MapClean-defaults-manual");
        worker.setDaemon(true);
        worker.start();
    }

    private void toast(String message) {
        try {
            android.widget.Toast.makeText(context, message,
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }

    /* ------------------------------------------------------------------ 头部 */

    private View buildHeader() {
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 14),
                UiKit.dp(context, 12), UiKit.dp(context, 10));

        // Author avatar: the picture shipped in res/drawable, cropped to a disc. Falls
        // back to the module icon, then to a monogram, so the header never shows a hole.
        AvatarBadge avatar = new AvatarBadge(context, "M", 0);
        avatar.setRingColor(dark ? 0x33FFFFFF : 0x1A000000);
        int avatarSize = UiKit.dp(context, 38);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.setMarginEnd(UiKit.dp(context, 12));
        head.addView(avatar, avatarLp);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);

        // Line 1: product name + version chip + (optional) 「新版本」 badge.
        //
        // Width budget at the default full-width card on a 1080px/3x screen:
        //   titles ≈ 226dp  (360 - 10 - 10 card margins - 18 - 12 head padding - 44 - 12 avatar - 28 close)
        //   「MapAdKiller」 19sp ≈ 110dp, 「v2.2.2」 chip ≈ 54dp, 「新版本」 badge ≈ 42dp, gaps 8dp
        //   ⇒ 214dp: fits, and the title ellipsizes if the badge pushes it over.
        // Three-in-one-row (pill + chip + GitHub) would need 262-329dp and CANNOT fit at any
        // panel width, which is why the credit moved to line 2 and the pill owns line 2 alone.
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("MapAdKiller");
        title.setTextSize(17f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(cTxP());
        title.setLetterSpacing(0.01f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // The title absorbs any shortfall: the version chip and its 「新版本」 badge must never
        // be clipped, so they keep WRAP_CONTENT while this line gives up width first.
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(versionChip(), chipLp(0));
        titleRow.addView(versionBadge(), chipLp(UiKit.dp(context, 4)));
        titles.addView(titleRow);

        // Row 2: live connection state, then the author credit as a quiet text link.
        //
        // The author entry is deliberately understated -- a filled button here competed
        // with the switches, which are what the sheet is actually for. A muted label that
        // only picks up the accent colour under the finger reads as a link without
        // shouting, and the avatar above already identifies the author visually.
        LinearLayout meta = new LinearLayout(context);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = UiKit.dp(context, 5);
        titles.addView(meta, metaLp);

        statusPill = new TextView(context);
        statusPill.setTextSize(11.5f);
        statusPill.setPadding(UiKit.dp(context, 8), UiKit.dp(context, 3),
                UiKit.dp(context, 8), UiKit.dp(context, 3));
        // The pill can only hold a short phrase; the actionable explanation lives on long-press
        // (a NONE pill that just says 「只读」 leaves the user with nothing to do).
        statusPill.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (FloatingConfig.backend() == FloatingConfig.BACKEND_NONE) {
                    toast("配置通道不可用：请在框架里确认本模块已对目标 App 启用，或重装模块");
                } else if (FloatingConfig.lastWriteState() == FloatingConfig.W_REFUSED) {
                    toast("配置无法保存：请检查存储空间或重装模块");
                } else {
                    toast("配置后端：" + (FloatingConfig.backend()
                            == FloatingConfig.BACKEND_REMOTE ? "远程（框架服务）" : "本地")
                            + "；当前版本 v" + Version.NAME);
                }
                return true;
            }
        });
        meta.addView(statusPill);
        refreshStatus();

        meta.addView(authorLink(), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(context);
        close.setText("✕");
        close.setTextSize(17f);
        close.setTextColor(cTxS());
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭");
        close.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 20f)));
        close.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { closeFrom("close_button"); }
        });
        int cs = UiKit.dp(context, 40);
        head.addView(close, new LinearLayout.LayoutParams(cs, cs));
        UiKit.pressable(close, 0.9f);
        return head;
    }

    /**
     * The GitHub entry point, styled as a quiet text link next to the status pill.
     *
     * <p>No filled background and no accent colour at rest: it sits at the same visual
     * weight as the status pill beside it, and only the mark keeps a hint of colour so the
     * row still reads as something tappable. Pressing it ripples and brightens.
     *
     * <p>Opened through {@code ACTION_VIEW} rather than an in-app WebView. A WebView would
     * drag a browser engine into a module that otherwise never opens a network page, and the
     * user's own browser already has their GitHub session.
     */
    private View authorLink() {
        final String url = "https://github.com/ldxm666/MapAdKiller";

        LinearLayout link = new LinearLayout(context);
        link.setOrientation(LinearLayout.HORIZONTAL);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setPadding(UiKit.dp(context, 6), UiKit.dp(context, 3),
                UiKit.dp(context, 8), UiKit.dp(context, 3));
        link.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 10f)));
        link.setContentDescription("打开 GitHub 项目主页：MapAdKiller");

        LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        linkLp.setMarginStart(UiKit.dp(context, 6));
        link.setLayoutParams(linkLp);

        GithubMark mark = new GithubMark(context);
        mark.setColor(cTxS());
        int ms = UiKit.dp(context, 12);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(ms, ms);
        markLp.setMarginEnd(UiKit.dp(context, 5));
        link.addView(mark, markLp);

        TextView label = new TextView(context);
        label.setText("GitHub");
        label.setTextSize(11f);
        label.setTextColor(cTxS());
        link.addView(label);

        // Underline the label so it is unmistakably a link, and brighten the pair on press.
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        UiKit.pressable(link, 0.96f);
        link.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { openUrl(url); }
        });
        return link;
    }

    /** Fire an external VIEW intent, falling back to the raw string if nothing handles it. */
    private void openUrl(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                android.widget.Toast.makeText(context, url,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        }
    }

    /**
     * Actionable text for a refused write, plus one self-heal attempt.
     *
     * <p>「服务未连接，写入失败」 told the user nothing they could act on -- and it was often
     * simply wrong. Now the text follows the recorded reason: a refused commit is a
     * permissions/IO problem the user can actually fix, while a missing path is a "run the map
     * first" problem.
     */
    private String writeFailedHint() {
        FloatingConfig.ensureServiceFrom(context);
        if (FloatingConfig.backend() == FloatingConfig.BACKEND_NONE) {
            FloatingConfig.reportNoWritePath("write");
            return "配置通道不可用：请在框架里确认本模块已对目标 App 启用，或重装模块";
        }
        // A backend exists, so the refusal is a real storage/permission problem
        // (commit_false / io / prefs_null / no_backend all end up here).
        return "配置无法保存：请检查存储空间或重装模块";
    }

    /**
     * One refused write: say the right thing, and give the service one more chance.
     *
     * <p>The first tap after a cold start often lands before {@code onServiceBind}, so a
     * no-path result is not a failure -- it is "not yet". That case retries itself once after
     * 300ms and says 「正在连接」 instead of 「失败」, which is what the user actually needs.
     */
    private void onWriteFailed(final String key, final boolean value, final GlassSwitch sw) {
        lastFailedKey = key;
        lastFailedValue = value;
        if (FloatingConfig.writePath() == FloatingConfig.WRITE_NONE) {
            FloatingConfig.ensureServiceFrom(context);
            toast("正在连接，请稍候…");
            refreshStatus();
            postDelayed(new Runnable() {
                @Override public void run() {
                    if (!open) return;
                    if (FloatingConfig.write(key, value)) {
                        FloatingConfig.invalidate();
                        sw.setChecked(value, true);
                        Log.i(TAG, "event=float_toggle_retry_ok key=" + key);
                    } else {
                        toast(writeFailedHint());
                    }
                    refreshStatus();
                }
            }, 300L);
            return;
        }
        toast(writeFailedHint());
        refreshStatus();
    }

    private void refreshStatus() {
        int backend = FloatingConfig.backend();
        boolean refused = FloatingConfig.lastWriteState() == FloatingConfig.W_REFUSED;
        if (backend == FloatingConfig.BACKEND_NONE) {
            // 「需要目标 App 运行中」 was self-contradictory here: the sheet IS inside the target
            // app. Say what is actually wrong, not where the user supposedly is.
            statusPill.setText("○ 只读");
            statusPill.setTextColor(cDotBad());
            statusPill.setBackground(UiKit.plate(cWarnFill(),
                    UiKit.dpf(context, 20f)));
            FloatingConfig.reportNoWritePath("status");
        } else if (refused) {
            // A backend exists but the last write was refused (permissions / IO / disk full).
            // Saying 「可写入」 here is what sent the user chasing the wrong cause.
            statusPill.setText(backend == FloatingConfig.BACKEND_REMOTE
                    ? "● 已连接 · 写入被拒" : "● 本地 · 写入被拒");
            statusPill.setTextColor(cWarn());
            statusPill.setBackground(UiKit.plate(dark ? 0x26FFB020 : 0x1AF79009,
                    UiKit.dpf(context, 20f)));
        } else if (backend == FloatingConfig.BACKEND_REMOTE) {
            statusPill.setText("● 已连接");
            statusPill.setTextColor(cDotOk());
            statusPill.setBackground(UiKit.plate(dark ? 0x262BD88A : 0x1A12B76A,
                    UiKit.dpf(context, 20f)));
        } else {
            // Local SharedPreferences backend: writable in-process, no framework channel
            // needed. This is the state the 免 root build falls back to on devices where the
            // remote channel never arrives.
            statusPill.setText("● 本地");
            statusPill.setTextColor(cAccent());
            statusPill.setBackground(UiKit.plate(cRipple(),
                    UiKit.dpf(context, 20f)));
        }
        paintWriteWarn();
    }

    /**
     * Footer line that survives the toast: 「⚠ 上次写入失败：<原因>（点此重试）」.
     *
     * <p>A toast is gone in two seconds, and the user is left staring at a switch that flipped
     * back with no explanation. This row stays until a write succeeds.
     */
    private void paintWriteWarn() {
        if (writeWarnRow == null) return;
        boolean refused = FloatingConfig.lastWriteState() == FloatingConfig.W_REFUSED;
        if (!refused) {
            writeWarnRow.setVisibility(GONE);
            return;
        }
        writeWarnRow.setVisibility(VISIBLE);
        writeWarnRow.setText("⚠ 上次写入失败：" + reasonText(FloatingConfig.lastWriteReason())
                + "（点此重试）");
    }

    /** Human wording for one write-failure reason. */
    private static String reasonText(String reason) {
        if (reason == null) return "未知原因";
        if ("commit_false".equals(reason)) return "配置库不可写";
        if ("io".equals(reason)) return "读写出错";
        if ("prefs_null".equals(reason)) return "配置库不可用";
        if ("svc_null".equals(reason)) return "服务未就绪";
        if ("no_backend".equals(reason)) return "配置通道不可用";
        return reason;
    }

    /* ------------------------------------------------------- 版本胶囊 / 更新状态机 */

    /** Margin helper for the two chips in the title row. */
    private LinearLayout.LayoutParams chipLp(int startMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(startMargin);
        return lp;
    }

    /** The version capsule: one chip, four states, never clipped (WRAP_CONTENT). */
    private TextView versionChip() {
        versionChip = new TextView(context);
        versionChip.setTextSize(10.5f);
        versionChip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        versionChip.setSingleLine(true);
        versionChip.setGravity(Gravity.CENTER);
        versionChip.setPadding(UiKit.dp(context, 7), UiKit.dp(context, 2),
                UiKit.dp(context, 7), UiKit.dp(context, 2));
        versionChip.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 10f)));
        versionChip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                // A known-newer version: the useful action is opening it, not re-checking.
                if (updateState == U_NEW) {
                    openUrl(updateDownloadUrl());
                    return;
                }
                if (updateState == U_FAILED) {
                    Log.i(TAG, "event=update_check_retry");
                }
                startUpdateCheck(true);
            }
        });
        return versionChip;
    }

    /** The 「新版本」 badge, shown only in {@link #U_NEW}. */
    private TextView versionBadge() {
        versionBadge = new TextView(context);
        versionBadge.setText("新版本");
        versionBadge.setTextSize(9.5f);
        versionBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        versionBadge.setTextColor(0xFFFFFFFF);
        versionBadge.setSingleLine(true);
        versionBadge.setGravity(Gravity.CENTER);
        versionBadge.setPadding(UiKit.dp(context, 5), UiKit.dp(context, 1),
                UiKit.dp(context, 5), UiKit.dp(context, 1));
        versionBadge.setBackground(UiKit.plate(hd().error, UiKit.dpf(context, 6f)));
        versionBadge.setVisibility(GONE);
        return versionBadge;
    }

    /**
     * Repaint every surface that shows the update state: the header chip, its badge, and the
     * 「其他」 page row when that page is the one on screen.
     *
     * <p>One state machine, several surfaces -- they must never disagree, and they must never
     * each fire their own request.
     */
    private void paintUpdate() {
        int color;
        boolean badge = false;
        String text;
        switch (updateState) {
            case U_CHECKING:
                text = "检查中…";
                color = cTxS();
                break;
            case U_LATEST:
                text = "v" + Version.NAME;
                color = dark ? 0xFF6BD68A : 0xFF2E7D32;     // green
                break;
            case U_NEW:
                text = "v" + Version.NAME + " → v" + updateLatest;
                color = hd().error;
                badge = true;
                break;
            default:
                text = "v" + Version.NAME;
                color = cTxS();
                break;
        }
        if (versionChip != null) {
            versionChip.setText(text);
            versionChip.setTextColor(color);
            versionChip.setBackground(UiKit.plate(tint(color), UiKit.dpf(context, 10f)));
            versionChip.setContentDescription(badge
                    ? "当前版本 v" + Version.NAME + "，线上有 v" + updateLatest + "，点此查看"
                    : "当前版本 v" + Version.NAME + "，点此检查更新");
        }
        if (versionBadge != null) {
            versionBadge.setVisibility(badge ? VISIBLE : GONE);
        }
        if (aboutUpdateRow != null) {
            aboutUpdateRow.setText(aboutUpdateText());
            aboutUpdateRow.setTextColor(color);
        }
        if (aboutMirrorRow != null) {
            // 「镜像下载」 only makes sense when there is something to download, and it names
            // the mirror it will open so the user knows what is about to happen.
            aboutMirrorRow.setVisibility(badge ? VISIBLE : GONE);
            if (badge) aboutMirrorRow.setText("从服务器下载（" + mirrorHost() + "）");
        }
    }

    /** 下载地址的 host，用于「从服务器下载（47.109.49.93）」这类标签。 */
    private String mirrorHost() {
        try {
            String url = UpdateChecker.downloadUrl(context);
            if (url == null) return "服务器";
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            return host == null ? "服务器" : host;
        } catch (Throwable t) {
            return "服务器";
        }
    }

    /**
     * 新版本的下载地址：优先服务端清单（内置版会自动选宿主对应的那个包），
     * 拿不到才退回项目发布页 —— 后者需要能访问 GitHub，只作兜底。
     */
    private String updateDownloadUrl() {
        try {
            String url = UpdateChecker.downloadUrl(context);
            if (url != null) return url;
        } catch (Throwable ignored) { }
        return Version.RELEASES;
    }

    /** 12% tint of a state colour, used as the chip's plate. */
    private int tint(int color) {
        return (color & 0x00FFFFFF) | (dark ? 0x26 << 24 : 0x1A << 24);
    }

    /** The 「其他」 page line, worded for the same state. */
    private String aboutUpdateText() {
        switch (updateState) {
            case U_CHECKING:
                return "检查中…";
            case U_NEW:
                // 带上命中的源（host），便于诊断「为什么这台机器查不到/查到的是旧版」。
                return "有新版本 v" + updateLatest + "（点此查看）"
                        + sourceSuffix();
            case U_LATEST:
                return "✓ 已是最新 v" + Version.NAME;
            case U_FAILED:
                return "线上版本未获取（点此重试）";
            default:
                return "检查更新";
        }
    }

    /** 「 · via gh-proxy.com」, empty when the source is unknown or it was a cache replay. */
    private String sourceSuffix() {
        try {
            String src = UpdateChecker.source(context);
            if (src == null || src.length() == 0 || "cache".equals(src)) return "";
            return " · via " + src;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Read the cached answer -- no network. Called on every open, which is why the sheet can be
     * opened as often as the user likes without a single extra request.
     */
    private void refreshUpdateFromCache() {
        try {
            String latest = UpdateChecker.latest(context);
            boolean outdated = UpdateChecker.isOutdated(context);
            updateLatest = latest;
            updateState = latest == null ? U_UNKNOWN : (outdated ? U_NEW : U_LATEST);
        } catch (Throwable t) {
            updateState = U_UNKNOWN;
        }
        paintUpdate();
    }

    /**
     * The only caller of {@code UpdateChecker.check}.
     *
     * <p>{@code force} is true only for a tap; the automatic run on the first open passes
     * false, so the 6-hour throttle inside {@code UpdateChecker} still applies.
     */
    private void startUpdateCheck(final boolean userInitiated) {
        // 已经有一轮在跑（可能是 App 侧 maybeAutoCheck 起的）：不要再把自己置成 U_CHECKING ——
        // UpdateChecker 的 sRunning 会直接 return，回调永远不会来，界面就永远停在「检查中」。
        if (updateInFlight || UpdateChecker.isChecking()) {
            if (userInitiated) {
                toast("正在检查…");
                Log.i(TAG, "event=update_check_busy");
            }
            return;
        }
        updateInFlight = true;
        updateState = U_CHECKING;
        paintUpdate();
        // 看门狗：即使 UpdateChecker 内部异常导致回调没来，也必须强制落到终态。
        // 「U_CHECKING 绝不能成为最终可见状态」是这条的硬约束。
        postDelayed(new Runnable() {
            @Override public void run() {
                if (!updateInFlight || updateState != U_CHECKING) return;
                updateInFlight = false;
                updateState = resolveFromCache();
                paintUpdate();
                Log.w(TAG, "event=update_check_watchdog state=" + updateState);
            }
        }, WATCHDOG_MS);
        try {
            UpdateChecker.check(context, userInitiated, new UpdateChecker.Callback() {
                @Override public void onResult(String latest, boolean outdated, String source) {
                    // Already on the main thread (UpdateChecker posts through its Handler).
                    updateInFlight = false;
                    updateLatest = latest;
                    updateState = latest == null ? U_FAILED : (outdated ? U_NEW : U_LATEST);
                    paintUpdate();
                    Log.i(TAG, "event=update_check latest=" + latest
                            + " outdated=" + outdated + " source=" + source
                            + " user=" + userInitiated);
                }
            });
        } catch (Throwable t) {
            updateInFlight = false;
            updateState = U_FAILED;
            paintUpdate();
        }
    }

    /** Terminal state derived from the cache alone -- used by the watchdog. */
    private int resolveFromCache() {
        try {
            String latest = UpdateChecker.latest(context);
            if (latest == null) return U_FAILED;
            return UpdateChecker.isOutdated(context) ? U_NEW : U_LATEST;
        } catch (Throwable t) {
            return U_FAILED;
        }
    }

    /* ------------------------------------------------------------------ 搜索 */

    /**
     * Search field. 高德's tool catalogue runs past a hundred entries, so a category rail
     * alone forces a lot of scrolling; typing filters across <i>every</i> category at once
     * and shows the group each hit came from.
     */
    private View buildSearch() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setBackground(UiKit.plate(dark ? 0x1AFFFFFF : 0x0F000000,
                UiKit.dpf(context, 12f)));
        wrap.setPadding(UiKit.dp(context, 12), 0, UiKit.dp(context, 8), 0);

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(context, 40));
        wrapLp.leftMargin = UiKit.dp(context, 16);
        wrapLp.rightMargin = UiKit.dp(context, 16);
        wrapLp.bottomMargin = UiKit.dp(context, 10);
        wrap.setLayoutParams(wrapLp);

        TextView glyph = new TextView(context);
        glyph.setText("⌕");
        glyph.setTextSize(16f);
        glyph.setTextColor(cTxS());
        wrap.addView(glyph);

        searchField = new EditText(context);
        searchField.setHint("搜索设置项 / 工具名");
        searchField.setHintTextColor(cTxS());
        searchField.setTextColor(cTxP());
        searchField.setTextSize(13.5f);
        searchField.setSingleLine(true);
        searchField.setBackground(null);
        searchField.setPadding(UiKit.dp(context, 8), 0, UiKit.dp(context, 4), 0);
        // The map is behind the sheet; a soft keyboard opening would resize it oddly.
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                setQuery(s == null ? "" : s.toString());
            }
        });
        wrap.addView(searchField, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        searchCount = new TextView(context);
        searchCount.setTextSize(11.5f);
        searchCount.setTextColor(cTxS());
        searchCount.setVisibility(View.GONE);
        wrap.addView(searchCount);

        searchClear = new TextView(context);
        searchClear.setText("✕");
        searchClear.setTextSize(13f);
        searchClear.setTextColor(cTxS());
        searchClear.setGravity(Gravity.CENTER);
        searchClear.setContentDescription("清除搜索");
        searchClear.setVisibility(View.GONE);
        searchClear.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { searchField.setText(""); }
        });
        wrap.addView(searchClear, new LinearLayout.LayoutParams(
                UiKit.dp(context, 30), LinearLayout.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    /** Apply a new query: filter the body and, while searching, hide the category rail. */
    private void setQuery(String value) {
        String next = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (next.equals(query)) return;
        query = next;
        boolean searching = !query.isEmpty();
        searchClear.setVisibility(searching ? View.VISIBLE : View.GONE);
        // The hit counter only means anything while a query is active; leaving it up with a
        // stale number next to an unfiltered list would be actively misleading.
        if (!searching) searchCount.setVisibility(View.GONE);
        if (rail.getParent() instanceof View) {
            ((View) rail.getParent()).setVisibility(searching ? View.GONE : View.VISIBLE);
        }
        select(searching ? -1 : Math.max(0, selected), true);
    }

    /** Every item of every category, flattened -- the haystack for search. */
    private java.util.List<Object[]> flatten() {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        for (Group g : groups) {
            if (g.items != null) {
                for (Item it : g.items) out.add(new Object[]{g.label, it});
            } else if (g.sections != null) {
                for (Object[] section : g.sections) {
                    for (Item it : (Item[]) section[1]) out.add(new Object[]{section[0], it});
                }
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ 分类栏 */

    private View buildRail() {
        HorizontalScrollView hs = new HorizontalScrollView(context);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(UiKit.dp(context, 14), 0, UiKit.dp(context, 14), UiKit.dp(context, 8));
        railScroll = hs;      // kept so select() can scroll the chosen chip into view
        // No OnTouchListener here (r12 regression). A listener on the scroller -- or any
        // requestDisallowInterceptTouchEvent() issued from a view *inside* it -- propagates
        // upwards and clears the HorizontalScrollView's own interception flag, so the scroller
        // can never take the gesture over and scrollX stays 0. The panel now simply declines to
        // intercept while the DOWN was in the rail, and the scroller does the rest natively.
        rail = new LinearLayout(context);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(rail, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        rebuildRail();
        return hs;
    }

    /** (Re)populate the category chips from the current {@link #groups}. */
    private void rebuildRail() {
        if (rail == null) return;
        rail.removeAllViews();
        for (int i = 0; i < groups.length; i++) {
            final int index = i;
            TextView chip = new TextView(context);
            chip.setText(groups[i].label);
            chip.setTextSize(13f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 7),
                    UiKit.dp(context, 14), UiKit.dp(context, 7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(UiKit.dp(context, 7));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    // Tapping a category leaves search mode; otherwise the rail would be
                    // hidden while the chip that was just tapped stays selected.
                    if (searchField != null && searchField.getText().length() > 0) {
                        searchField.setText("");
                        return;
                    }
                    select(index, true);
                }
            });
            // Press feedback only -- deliberately an OnClickListener, not an OnTouchListener.
            //
            // r12 installed an OnTouchListener here that called requestDisallowInterceptTouchEvent
            // on the ancestors. That call propagates UPWARDS, and since the walk starts at the
            // rail's LinearLayout (a child of the scroller) it cleared the
            // HorizontalScrollView's own interception flag: the scroller could no longer take
            // the gesture over, scrollX stayed 0 and the last chips became unreachable.
            // Gesture ownership is now decided in onInterceptTouchEvent (isInRail ⇒ return
            // false), which needs no flag at all.
            UiKit.pressable(chip, 0.95f);
            rail.addView(chip);
        }
    }

    /**
     * Bring the selected chip fully into view.
     *
     * <p>「外观」 is the last chip and starts off-screen, so the page the user just opened was
     * unreachable without first swiping the chip row. Scrolling the rail on every selection
     * also keeps a category reachable when the list grows, and it is one short animation per
     * tap -- no loop, no idle work.
     */
    private void scrollRailToSelected(final int index) {
        if (railScroll == null || rail == null) return;
        if (index < 0 || index >= rail.getChildCount()) return;
        final View chip = rail.getChildAt(index);
        if (chip == null) return;
        railScroll.post(new Runnable() {
            @Override public void run() {
                int viewport = railScroll.getWidth();
                if (viewport <= 0) return;
                int target = chip.getLeft() - (viewport - chip.getWidth()) / 2;
                int max = Math.max(0, rail.getWidth() - viewport);
                railScroll.smoothScrollTo(Math.max(0, Math.min(max, target)), 0);
            }
        });
    }

    private void select(int index, boolean animate) {
        selected = index;
        for (int i = 0; i < rail.getChildCount(); i++) {
            TextView chip = (TextView) rail.getChildAt(i);
            boolean on = i == index;
            chip.setTextColor(on ? 0xFFFFFFFF : cTxS());
            chip.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            chip.setBackground(on
                    ? UiKit.gradient(cAccentTop(), cAccentBottom(), UiKit.dpf(context, 18f))
                    : UiKit.plate(dark ? 0x1AFFFFFF : 0x0F000000, UiKit.dpf(context, 18f)));
        }
        scrollRailToSelected(index);
        // The about row is rebuilt with every page switch; drop the stale reference so a late
        // update result cannot paint into a detached view.
        aboutUpdateRow = null;
        captureLogButton = null;
        body.removeAllViews();
        // The old rows (and their sliders) are gone with the body; drop the refs so sync()
        // can never poke a detached view.
        metricRows.clear();

        // index -1 means "showing search results", which span every category.
        if (index < 0) {
            renderSearchResults();
        } else {
            Group g = groups[index];
            // 服务器公告：**第一个 chip 的顶部**细横幅（未读时高亮 + 红点，已读后退成一行常驻）。
            // ★ 不能用 label=="首页" 判断：高德第一页叫「首页」，百度叫「界面」，腾讯叫「悬浮窗」
            //   —— 按名字匹配会让百度/腾讯永远看不到横幅（百度真机验证时踩到）。
            // 「其他」页那两行是兜底入口，主入口在这里 —— 用户不会为了看公告翻进其他页。
            if (index == 0) {
                View announce = buildAnnounceBanner();
                if (announce != null) body.addView(announce);
            }
            // 首次使用：抓取官方下发数据（只在「首页」分组顶部）
            if ("首页".equals(g.label)) {
                View capture = buildCaptureBanner();
                if (capture != null) body.addView(capture);
            }
            if (g.link != null) {
                View banner = buildLinkBanner(g.link);
                if (banner != null) body.addView(banner);
            }
            if (g.action != null) body.addView(buildActionRow(g.action));
            if (g.note != null) body.addView(groupNote(g.note));
            if (g.items != null) {
                for (Item item : g.items) body.addView(buildRow(item));
            } else if (g.sections != null) {
                for (Object[] section : g.sections) {
                    Item[] items = (Item[]) section[1];
                    if (items.length == 0) continue;
                    body.addView(sectionHeader((String) section[0], items.length));
                    for (Item item : items) body.addView(buildRow(item));
                }
            }
            // 滑块行**独立于 items/sections** 渲染：巡航页要「先开关、后阈值」。
            // 「恢复默认」和「关闭悬浮窗」只属于纯外观页 —— 巡航页挂上去就是错的，
            // 所以用 items == null 做判据，别让它跑到别的页面。
            if (g.metrics != null) {
                for (Metric m : g.metrics) body.addView(buildMetricRow(m));
                if (g.items == null) {
                    body.addView(buildAppearanceResetRow());
                    View offRow = buildFloatOffRow(); if (offRow != null) body.addView(offRow);
                }
            }
            // 当前版本 / 检查更新：挂在「其他」页底部。免 root 内置版没有 MainActivity，
            // 这两行是它唯一的版本信息与更新入口。
            if (g.about) {
                // 服务器公告：一行开关 + 一行入口。排在版本行上面 —— 公告是作者要用户
                // 立刻看到的东西，版本号是查资料时才看的。
                body.addView(buildAnnounceRows());
                body.addView(buildCaptureLogRow());
                body.addView(buildAboutRows());
            }
        }
        if (animate) {
            body.setAlpha(0f);
            body.setTranslationY(UiKit.dp(context, 10));
            UiKit.enter(body, UiKit.dp(context, 10), 0);
        }
    }

    private TextView sectionHeader(String title, int count) {
        TextView header = new TextView(context);
        header.setText(title + "　·　" + count + " 项");
        header.setTextSize(11.5f);
        header.setTextColor(cTxS());
        header.setPadding(UiKit.dp(context, 16), UiKit.dp(context, 14),
                UiKit.dp(context, 16), UiKit.dp(context, 4));
        return header;
    }

    /**
     * Filter every category by the current query and render the matches, grouped by their
     * original section so a hit's context is never lost.
     *
     * Matching is case-insensitive and covers both the title and the note (which carries the
     * tool id), so "544" and "超划算" both find the same switch.
     */
    private void renderSearchResults() {
        java.util.LinkedHashMap<String, java.util.List<Item>> hits =
                new java.util.LinkedHashMap<String, java.util.List<Item>>();
        int total = 0;
        for (Object[] pair : flatten()) {
            String section = (String) pair[0];
            Item item = (Item) pair[1];
            String haystack = item.title.toLowerCase(java.util.Locale.ROOT);
            if (item.note != null) haystack += "\n" + item.note.toLowerCase(java.util.Locale.ROOT);
            if (!haystack.contains(query)) continue;
            java.util.List<Item> bucket = hits.get(section);
            if (bucket == null) {
                bucket = new java.util.ArrayList<Item>();
                hits.put(section, bucket);
            }
            bucket.add(item);
            total++;
        }

        // 外观 rows are not switches, so flatten() cannot see them; match them separately or
        // searching "大小" would answer "没有匹配" while the row is one chip away.
        java.util.List<Metric> metricHits = new java.util.ArrayList<Metric>();
        for (Metric m : APPEARANCE) {
            String haystack = m.title.toLowerCase(java.util.Locale.ROOT);
            if (m.note != null) haystack += "\n" + m.note.toLowerCase(java.util.Locale.ROOT);
            if (!haystack.contains(query)) continue;
            metricHits.add(m);
            total++;
        }

        searchCount.setVisibility(View.VISIBLE);
        searchCount.setText(total + " 项");

        if (total == 0) {
            TextView empty = new TextView(context);
            empty.setText("没有匹配「" + query + "」的设置项");
            empty.setTextSize(13.5f);
            empty.setTextColor(cTxS());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiKit.dp(context, 40), 0, UiKit.dp(context, 40));
            body.addView(empty);
            return;
        }
        for (java.util.Map.Entry<String, java.util.List<Item>> e : hits.entrySet()) {
            body.addView(sectionHeader(e.getKey(), e.getValue().size()));
            for (Item item : e.getValue()) body.addView(buildRow(item));
        }
        if (!metricHits.isEmpty()) {
            body.addView(sectionHeader("外观", metricHits.size()));
            for (Metric m : metricHits) body.addView(buildMetricRow(m));
        }
    }

    /* ------------------------------------------------------------------ 开关行 */

    private View buildRow(final Item item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 11),
                UiKit.dp(context, 14), UiKit.dp(context, 11));
        row.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 14f)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(item.title);
        title.setTextSize(14.5f);
        title.setTextColor(cTxP());
        title.setLineSpacing(0, 1.14f);
        texts.addView(title);

        if (item.note != null && item.note.length() > 0) {
            TextView note = new TextView(context);
            note.setText(item.note);
            note.setTextSize(11.5f);
            note.setTextColor(cTxS());
            note.setLineSpacing(0, 1.16f);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = UiKit.dp(context, 3);
            texts.addView(note, nlp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = new GlassSwitch(context);
        sw.setLabel(item.title);
        sw.setTrackColors(hd().secondary, cAccent());
        // Hydrate without animating: the sheet must not fire a wave of toggles on open.
        // `strict` items are read literally (labels_on defaults to false); everything else goes
        // through the visibility table, which is what makes the 「关闭…」 rows default to ON.
        if (item.strict) strictKeys.add(item.key);
        sw.setChecked(readItem(item), false);
        switches.put(item.key, sw);
        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                boolean ok = FloatingConfig.write(item.key, next);
                if (ok) {
                    // Same-process readers cache for 2s; drop it so the next read agrees.
                    FloatingConfig.invalidate();
                } else {
                    view.setChecked(!next, true);
                    Log.w(TAG, "event=float_toggle_fail key=" + item.key
                            + " value=" + next + " reason=" + FloatingConfig.lastWriteReason());
                    onWriteFailed(item.key, next, view);
                }
            }
        });

        // One log line per toggle, always on (a switch tap is a low-frequency user action and
        // this is the only way a verification run can tell "the row was never touched" from
        // "the write was refused"). GlassSwitch.performClick() calls super.performClick()
        // BEFORE it flips, so sw.isChecked() is still the old value here.
        final boolean[] viaRow = new boolean[1];
        sw.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Log.i(TAG, "event=float_toggle_row key=" + item.key
                        + " value=" + (!sw.isChecked())
                        + " via=" + (viaRow[0] ? "row" : "switch"));
            }
        });

        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The whole row is the tap target, not just the pill at its right edge: on a 1080px
        // screen the pill is ~50dp wide, and a user aiming at the title line missed it (r9
        // report: four taps across the row, none flipped the switch). setClickable is explicit
        // -- View#setOnClickListener would do it, but stating it keeps the row's touchability
        // from depending on a side effect. The title/note TextViews stay non-clickable on
        // purpose, so a tap on them falls through to this row (and keeps the row ripple).
        row.setClickable(true);
        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                viaRow[0] = true;
                try {
                    sw.performClick();
                } finally {
                    viaRow[0] = false;
                }
            }
        });
        return row;
    }

    /* ------------------------------------------------------------------ 服务器公告 */

    /**
     * 「首页」顶部那条细横幅 —— 公告的**主入口**。
     *
     * <p>两种状态，同一处渲染：
     * <ul>
     *   <li><b>有未读</b>：accent 色调底 + 红点 + 「查看 ›」，一眼能看见；</li>
     *   <li><b>已读</b>：退成一行常驻的「最新公告 ›」，只提供重看。</li>
     * </ul>
     *
     * <p>用的全是这个文件已有的原子：{@link UiKit#plate} 做底色、{@link UiKit#ripple} 做水波、
     * {@link UiKit#pressable} 做按压反馈，配色取 {@code cAccent/cTxS/cDotBad}，
     * 和首页「首次使用」卡片是同一套观感。
     */
    private View buildAnnounceBanner() {
        boolean unread = false;
        String latest = null;
        try {
            unread = Announce.hasUnread(context);
            latest = Announce.latestTitle(context);
        } catch (Throwable ignored) { }

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 6), UiKit.dp(context, 8),
                UiKit.dp(context, 6), 0);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 10),
                UiKit.dp(context, 12), UiKit.dp(context, 10));
        int tint = unread ? cAccent() : cTxS();
        row.setBackground(UiKit.ripple(cRipple(),
                UiKit.plate((tint & 0x00FFFFFF) | (unread ? 0x1F000000 : 0x0D000000),
                        UiKit.dpf(context, 14f)), UiKit.dpf(context, 14f)));
        row.setClickable(true);
        UiKit.pressable(row, 0.99f);

        if (unread) {
            View dot = new View(context);
            dot.setBackground(UiKit.plate(cDotBad(), UiKit.dpf(context, 4f)));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    UiKit.dp(context, 8), UiKit.dp(context, 8));
            dotLp.rightMargin = UiKit.dp(context, 8);
            row.addView(dot, dotLp);
        }

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(unread ? "服务器公告" : "最新公告");
        title.setTextSize(13f);
        title.setTextColor(unread ? cAccent() : cTxP());
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        texts.addView(title);
        if (latest != null) {
            TextView note = new TextView(context);
            note.setText(latest);
            note.setTextSize(11f);
            note.setTextColor(cTxS());
            note.setSingleLine(true);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(note);
        }
        row.addView(texts, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(context);
        action.setText(unread ? "查看 ›" : "›");
        action.setTextSize(12f);
        action.setTextColor(unread ? cAccent() : cTxS());
        row.addView(action, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Announce.showLatest(context);
                } catch (Throwable t) {
                    Log.w(TAG, "event=announce_show_fail err=" + t.getClass().getSimpleName());
                }
            }
        });
        wrap.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 公告读完后的刷新：面板还开着就重建当前页，横幅的红点立刻消失。 */
    private Runnable announceRefresher() {
        return new Runnable() {
            @Override public void run() {
                if (!open) return;
                try { select(Math.max(0, selected), false); } catch (Throwable ignored) { }
            }
        };
    }

    /**
     * 「其他」页顶部的两行：**接收服务器公告**（开关）+ **最新公告**（入口）。
     *
     * <p>开关行直接走 {@link #buildRow(Item)} —— 它已经处理了「读 → 拨 → 写 → 写失败回滚 →
     * 写盘日志」整条链，且注册进 {@code switches}，面板每次 {@link #sync()} 都会重读。
     * 自己再画一个 GlassSwitch 等于把那条链复制一遍，迟早会和别处不一致。
     *
     * <p>入口行是纯动作（没有开关），所以单独画：标题 + 一行「最近一条公告是什么」+ 右侧箭头。
     * 点它重看缓存的那条；缓存为空时 {@link Announce#showLatest} 会强制查一次。
     */
    private View buildAnnounceRows() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 6), UiKit.dp(context, 4),
                UiKit.dp(context, 6), 0);
        wrap.addView(buildRow(new Item(Announce.K_ON, "接收服务器公告",
                "只在启动后和打开面板时查一次，6 小时内不重复联网；已经是同一条不会再弹。")));
        wrap.addView(buildAnnounceEntry());
        return wrap;
    }

    /** 「最新公告」入口行：动作行，样式对齐 {@link #buildRow} 的左右内边距与字阶。 */
    private View buildAnnounceEntry() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 11),
                UiKit.dp(context, 14), UiKit.dp(context, 11));
        row.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 14f)));
        row.setClickable(true);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText("最新公告");
        title.setTextSize(14.5f);
        title.setTextColor(cTxP());
        texts.addView(title);

        TextView note = new TextView(context);
        String latest = null;
        try { latest = Announce.latestTitle(context); } catch (Throwable ignored) { }
        note.setText(latest != null ? latest : "点开查看作者最近发布的一条公告");
        note.setTextSize(11.5f);
        note.setTextColor(cTxS());
        note.setLineSpacing(0, 1.16f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = UiKit.dp(context, 3);
        texts.addView(note, nlp);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextSize(20f);
        arrow.setTextColor(cTxS());
        row.addView(arrow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Announce.showLatest(context);
                } catch (Throwable t) {
                    Log.w(TAG, "event=announce_show_fail err=" + t.getClass().getSimpleName());
                }
            }
        });
        return row;
    }

    /* ------------------------------------------------------------------ 关于 / 更新 */

    /**
     * 当前版本 + 检查更新。渲染在带 {@link Group#about} 的那一页（「其他」）底部。
     *
     * <p>必须在面板里而不是 {@code MainActivity} 里：免 root 内置版**没有** MainActivity，
     * 悬浮面板是它唯一的设置界面。
     *
     * <p>用 {@link UpdateChecker} 的现有入口，不另造 API：
     * {@code latest(Context)} / {@code isOutdated(Context)} / {@code check(Context, boolean, Callback)}。
     * {@code check} 的回调**已经**在主线程（内部有 Handler），所以这里直接改控件。
     *
     * <p>四种状态与文案：检查中「检查中…」；有新版「有新版本 vX（点此查看）」→ 点击打开
     * {@link Version#RELEASES}；已最新「✓ 已是最新 v当前」；失败「线上版本未获取（点此重试）」。
     * 打开发链接沿用面板既有的 {@link #openUrl(String)}（ACTION_VIEW + NEW_TASK）。
     */
    private View buildAboutRows() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8),
                UiKit.dp(context, 20), UiKit.dp(context, 14));

        TextView version = new TextView(context);
        version.setText("当前版本 v" + Version.NAME);
        version.setTextSize(12f);
        version.setTextColor(cTxS());
        wrap.addView(version);

        final TextView update = new TextView(context);
        update.setTextSize(12f);
        update.setTextColor(cAccent());
        update.setPaintFlags(update.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        update.setPadding(UiKit.dp(context, 4), UiKit.dp(context, 6),
                UiKit.dp(context, 4), UiKit.dp(context, 6));
        update.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 8f)));
        UiKit.pressable(update, 0.96f);
        // Same state machine as the header chip: this row never queries on its own.
        aboutUpdateRow = update;
        update.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (updateState == U_NEW) {
                    openUrl(updateDownloadUrl());
                    return;
                }
                startUpdateCheck(true);
            }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = UiKit.dp(context, 2);
        wrap.addView(update, ulp);

        // 「镜像下载」：GitHub 下载页在国内常常打不开，给一个反代入口。
        // 只在有新版时出现，避免平时占位置。
        aboutMirrorRow = new TextView(context);
        aboutMirrorRow.setTextSize(12f);
        aboutMirrorRow.setTextColor(cAccent());
        aboutMirrorRow.setPaintFlags(aboutMirrorRow.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        aboutMirrorRow.setPadding(UiKit.dp(context, 4), UiKit.dp(context, 2),
                UiKit.dp(context, 4), UiKit.dp(context, 6));
        aboutMirrorRow.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 8f)));
        UiKit.pressable(aboutMirrorRow, 0.96f);
        aboutMirrorRow.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                String url = UpdateChecker.downloadUrl(context);
                if (url == null) {
                    toast("尚未获取下载地址，请先检查更新");
                    return;
                }
                openUrl(url);
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrap.addView(aboutMirrorRow, mlp);

        paintUpdate();       // paint from the shared state, not from a fresh request
        return wrap;
    }

    /* ------------------------------------------------------------------ 一键动作 */

    /** Index of the group with this chip label, or -1. */
    private int indexOfGroup(String label) {
        if (label == null) return -1;
        for (int i = 0; i < groups.length; i++) {
            if (label.equals(groups[i].label)) return i;
        }
        return -1;
    }

    /**
     * True when every <b>suppression</b> toggle in the group is on (= every behaviour blocked).
     *
     * <p>{@code strict} rows are skipped on purpose: they are the "enable a feature" kind
     * ({@code labels_on}, default OFF), not one of the 拦截 gates. Counting them would leave
     * the 首页 banner permanently in its warning state even though every 省电 gate is on.
     */
    private boolean allOn(Group g) {
        if (g.items != null) {
            for (Item item : g.items) {
                if (item.strict) continue;
                if (!readItem(item)) return false;
            }
            return true;
        }
        if (g.sections != null) {
            for (Object[] section : g.sections) {
                for (Item item : (Item[]) section[1]) {
                    if (item.strict) continue;
                    if (!readItem(item)) return false;
                }
            }
            return true;
        }
        return true;
    }

    /**
     * The banner at the top of 首页 that jumps to the 省电 page.
     *
     * <p>Two states, both one line: everything suppressed (accent gradient) or something
     * switched off (warning fill) -- so the sheet's first screen always tells the user where
     * the power controls are and whether they are still fully on. Pure navigation: it owns no
     * setting of its own.
     */
    // ─────────────────────────────────── 首次使用：抓取官方下发数据（v2.2.6）

    /**
     * 首页「首次使用」卡片：一行状态 + 一个显式按钮 + 一行说明。
     *
     * <p>状态只反映事实（没抓 / 抓取中 / 已就绪），按钮才是入口——之前的整条通栏可点，
     * 用户根本看不出那是个按钮。抓取成功后按钮变成「重新抓取」，随时能手动补抓。
     */
    private View buildCaptureBanner() {
        try {
            final boolean mode = CaptureGate.captureMode(context);
            final boolean done = CaptureGate.captured(context);
            final boolean running = CaptureGate.capturing(context);
            final String status;
            final int tint;
            if (mode && (running || !done)) {
                status = "正在抓取官方数据…";
                tint = 0xFFB26A00;
            } else if (done) {
                status = "官方数据已就绪";
                tint = 0xFF1E8E3E;
            } else {
                status = "还没抓取官方数据";
                tint = 0xFFC62828;
            }
            final String label;
            final String caption;
            if (mode) {
                label = done ? "重启并启用模块" : "查看抓取进度";
                caption = done
                        ? "数据已抓完，重启一次即可按你的设置运行"
                        : "抓取期间模块全部暂停，请正常用一次地图（首页 / 搜索 / 我的）";
            } else if (done) {
                label = "重新抓取官方数据";
                caption = "图标缺失、工具宫格不全、家/单位不生效时点这里重抓";
            } else {
                label = "立即抓取官方数据";
                caption = "首次安装必须先抓一次，否则图标不显示、宫格缺格";
            }

            LinearLayout wrap = new LinearLayout(context);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 10),
                    UiKit.dp(context, 20), UiKit.dp(context, 4));

            TextView pill = new TextView(context);
            pill.setText(status);
            pill.setTextSize(12f);
            pill.setTextColor(tint);
            pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(UiKit.dp(context, 10), UiKit.dp(context, 5),
                    UiKit.dp(context, 10), UiKit.dp(context, 5));
            pill.setBackground(UiKit.plate((tint & 0x00FFFFFF) | 0x22000000,
                    UiKit.dpf(context, 8f)));
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pillLp.bottomMargin = UiKit.dp(context, 8);
            wrap.addView(pill, pillLp);

            TextView button = new TextView(context);
            button.setText(label);
            button.setTextSize(13.5f);
            button.setTextColor(0xFFFFFFFF);
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            button.setGravity(Gravity.CENTER);
            button.setPadding(0, UiKit.dp(context, 11), 0, UiKit.dp(context, 11));
            button.setBackground(UiKit.gradient(cAccentTop(), cAccentBottom(),
                    UiKit.dpf(context, 14f)));
            button.setContentDescription(label);
            UiKit.pressable(button, 0.97f);
            button.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    onCaptureBanner(mode, done);
                }
            });
            wrap.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView hint = new TextView(context);
            hint.setText(caption);
            hint.setTextSize(11f);
            hint.setTextColor(cTxS());
            hint.setLineSpacing(0f, 1.2f);
            hint.setPadding(0, UiKit.dp(context, 6), 0, 0);
            wrap.addView(hint, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return wrap;
        } catch (Throwable t) {
            return null;
        }
    }

    private void onCaptureBanner(final boolean mode, final boolean done) {
        try {
            if (mode && done) { confirmEnableModule(); return; }
            if (!mode && done) { confirmRecapture(); return; }
            if (mode) {
                CaptureDialog.show(context, "正在抓取官方数据",
                        "现在请像平时那样用一遍地图：打开首页、搜一次、进一次「我的」。\n\n"
                                + "抓取在后台自动完成，成功后这里会变成「重启并启用模块」。",
                        "知道了", null, null, null);
                return;
            }
            CaptureDialog.show(context, "先抓一次官方数据",
                    "高德的首页卡片、金刚位、工具宫格都是服务端下发的。"
                            + "第一次必须让高德跑一遍纯官方，把这份数据抓下来，"
                            + "之后才按你的设置改；否则会出现图标不显示、宫格缺格。\n\n"
                            + "点「开始抓取」后高德会重启一次，期间模块功能全部关闭（悬浮窗保留）。",
                    "开始抓取", new Runnable() {
                        @Override public void run() {
                            if (!CaptureGate.setCaptureMode(context, true)) {
                                toast("写入采集标记失败，请检查存储权限");
                                return;
                            }
                            toast("正在重启高德，请稍候…");
                            restartHost();
                        }
                    },
                    "取消", null);
        } catch (Throwable t) {
            toast("操作失败：" + t.getMessage());
        }
    }

    /** 已抓过之后的重抓入口：先关模块（采集模式），抓完再重启恢复。 */
    private void confirmRecapture() {
        try {
            CaptureDialog.show(context, "重新抓取官方数据？",
                    "会先关闭模块功能并重启高德，正常用一次（首页 / 搜索 / 我的）后自动抓取，"
                            + "抓完再重启一次就恢复你的设置。\n\n期间悬浮窗保留，面板顶部状态会从黄色变绿色。"
                            + "\n\n什么时候需要重抓：高德大版本更新、首页图标/宫格明显不对、或者你换了常驻城市。",
                    "开始抓取", new Runnable() {
                        @Override public void run() {
                            if (!CaptureGate.setCaptureMode(context, true)) {
                                toast("写入采集标记失败，请检查存储权限");
                                return;
                            }
                            toast("正在重启高德，请稍候…");
                            restartHost();
                        }
                    },
                    "取消", null);
        } catch (Throwable t) {
            toast("操作失败：" + t.getMessage());
        }
    }

    private void confirmEnableModule() {
        try {
            CaptureDialog.show(context, "启用模块功能？",
                    "官方数据已经抓到了（共 " + CaptureGate.capturedFiles(context) + " 个配置文件）。"
                            + "点「立即重启」后高德会重启一次，模块功能正式生效。",
                    "立即重启", new Runnable() {
                        @Override public void run() { finishCapture(); }
                    },
                    "稍后", null);
        } catch (Throwable t) {
            finishCapture();
        }
    }

    /** 关掉采集模式 + 重启宿主：下一次启动就是正常的模块模式。 */
    private void finishCapture() {
        if (captureFinished) return;
        captureFinished = true;
        CaptureGate.setCaptureMode(context, false);
        toast("正在重启高德…");
        CaptureGate.restartHost(context);
    }

    /**
     * 重启宿主（内置版 = 高德自己）。
     *
     * <p>没有 root 就不能 {@code am force-stop}，所以走「先排一个 1.2 秒后的系统闹钟拉起启动页，
     * 再杀掉自己」：进程死了闹钟还在系统里，起来就是干净的一次启动。
     */
    private void restartHost() {
        // 采集模式标记由调用方自己清（「开始抓取」要带着标记重启）。
        CaptureGate.restartHost(context);
    }

    private View buildLinkBanner(final String targetLabel) {
        final int target = indexOfGroup(targetLabel);
        if (target < 0) return null;
        boolean all = allOn(groups[target]);

        final TextView banner = new TextView(context);
        banner.setText(all ? "净化拦截已开启 · 查看/调整 →" : "净化拦截有项已关闭 · 查看 →");
        banner.setTextSize(13f);
        banner.setTextColor(0xFFFFFFFF);
        banner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 11),
                UiKit.dp(context, 14), UiKit.dp(context, 11));
        banner.setBackground(all
                ? UiKit.gradient(cAccentTop(), cAccentBottom(), UiKit.dpf(context, 14f))
                : UiKit.plate(cWarn(), UiKit.dpf(context, 14f)));
        banner.setContentDescription(all
                ? "净化拦截已开启，点这里查看或调整"
                : "净化拦截有项已关闭，点这里查看");
        UiKit.pressable(banner, 0.97f);
        banner.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                int at = indexOfGroup(targetLabel);
                if (at >= 0) select(at, true);
            }
        });

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 8),
                UiKit.dp(context, 14), UiKit.dp(context, 2));
        wrap.addView(banner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** Read one row's stored state, honouring {@link Item#strict}. */
    private static boolean readItem(Item item) {
        return item.strict ? FloatingConfig.readStrict(item.key) : FloatingConfig.read(item.key);
    }

    /**
     * 「一键省电」and friends: a full-width accent button above the group's body.
     *
     * <p>Uses the same accent gradient as the selected chip and the same press feel as the
     * footer link, so it reads as part of the sheet rather than as a system button. After the
     * action runs, {@link #sync()} re-reads every switch so the rows below show the new state
     * instead of the stale one.
     */
    private View buildActionRow(final Action action) {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 10),
                UiKit.dp(context, 20), UiKit.dp(context, 4));

        final TextView button = new TextView(context);
        button.setText(action.label());
        button.setTextSize(13.5f);
        button.setTextColor(0xFFFFFFFF);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, UiKit.dp(context, 11), 0, UiKit.dp(context, 11));
        button.setBackground(UiKit.gradient(cAccentTop(), cAccentBottom(),
                UiKit.dpf(context, 14f)));
        button.setContentDescription(action.label() + "：一次把所有净化拦截打开");
        UiKit.pressable(button, 0.97f);
        button.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                int written = 0;
                try {
                    written = action.run();
                } catch (Throwable t) {
                    Log.w(TAG, "event=action_fail err=" + t.getClass().getName());
                }
                sync();                      // rows below must show the new state at once
                toast(written > 0 ? "已开启全部净化拦截" : writeFailedHint());
                refreshStatus();
                Log.i(TAG, "event=power_all written=" + written);
            }
        });
        wrap.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /* ------------------------------------------------------------ 主动抓包（诊断） */

    /** 面板重建时会被置空；倒计时线程只在还挂着窗口时才继续刷。 */
    private TextView captureLogButton;

    /**
     * 「其他」页底部的主动抓包：两行按钮。
     * 上：抓「前 10 分钟」——内存环形缓冲里已经有的，点了立刻出 txt；
     * 下：抓「后 10 分钟」——从现在开始录，再点一下提前结束。
     */
    private View buildCaptureLogRow() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 10),
                UiKit.dp(context, 20), UiKit.dp(context, 6));

        final TextView before = captureButton("抓取前 10 分钟（立即生成 txt）");
        before.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                toast("正在导出最近十分钟的数据…");
                String path = null;
                try {
                    path = CaptureLog.dumpRecent(context);
                } catch (Throwable t) {
                    Log.w(TAG, "event=capture_dump_fail err=" + t.getClass().getName());
                }
                toast(path != null ? "已保存：" + path : "导出失败：系统拒绝写文件，见 logcat");
            }
        });
        wrap.addView(before, matchWrap());

        final TextView after = captureButton(captureLogLabel());
        after.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                boolean started = false;
                try {
                    started = CaptureLog.toggleLive(context);
                } catch (Throwable t) {
                    Log.w(TAG, "event=capture_toggle_fail err=" + t.getClass().getName());
                }
                after.setText(captureLogLabel());
                toast(started ? "正在抓取最近十分钟的数据…" : "已结束抓包，txt 已生成");
                if (started) tickCaptureLog(after);
                Log.i(TAG, "event=capture_toggle running=" + started);
            }
        });
        captureLogButton = after;
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = UiKit.dp(context, 8);
        wrap.addView(after, lp);

        TextView hint = new TextView(context);
        hint.setText("「前 10 分钟」立刻导出已经发生的事（只含这次打开高德以后的），"
                + "「后 10 分钟」从这一刻开始录。"
                + "两份 txt 都存到「内部存储/下载/MapClean/」，打开文件管理器点「下载」就能看到；"
                + "万一系统不给写，会自动落到 Android/data/包名/files/MapClean/。");
        hint.setTextSize(11f);
        hint.setTextColor(cTxS());
        hint.setLineSpacing(UiKit.dp(context, 2), 1.2f);
        hint.setPadding(UiKit.dp(context, 2), UiKit.dp(context, 8), UiKit.dp(context, 2), 0);
        wrap.addView(hint, matchWrap());
        if (CaptureLog.running()) tickCaptureLog(after);
        return wrap;
    }

    private TextView captureButton(String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(13.5f);
        button.setTextColor(0xFFFFFFFF);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, UiKit.dp(context, 11), 0, UiKit.dp(context, 11));
        button.setBackground(UiKit.gradient(cAccentTop(), cAccentBottom(),
                UiKit.dpf(context, 14f)));
        UiKit.pressable(button, 0.97f);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private String captureLogLabel() {
        if (!CaptureLog.running()) return "抓取后 10 分钟（从现在开始录）";
        int left = CaptureLog.remainSeconds();
        return String.format(java.util.Locale.US,
                "正在抓取最近十分钟的数据… 剩余 %d:%02d（点此提前结束）", left / 60, left % 60);
    }

    /** 面板开着时每秒刷一次倒计时；面板关了（没有 windowToken）就自动停。 */
    private void tickCaptureLog(final TextView button) {
        button.postDelayed(new Runnable() {
            @Override public void run() {
                if (button.getWindowToken() == null) return;
                button.setText(captureLogLabel());
                if (CaptureLog.running()) button.postDelayed(this, 1000L);
            }
        }, 1000L);
    }

    /* ------------------------------------------------------------------ 外观行 */

    /** One live slider row, kept so {@link #sync()} can re-read storage into it. */
    private static final class MetricRow {
        final Metric metric;
        final GlassSlider slider;
        final TextView value;

        MetricRow(Metric metric, GlassSlider slider, TextView value) {
            this.metric = metric;
            this.slider = slider;
            this.value = value;
        }
    }

    /**
     * One 外观 row: title, current value, slider, optional hint.
     *
     * <p>Same 14dp/11dp insets and the same type ramp as {@link #buildRow}: the title is the
     * 14.5sp primary text every switch row uses, the trailing value takes the accent colour
     * the selected chip uses, and the hint is the 11.5sp secondary line. Nothing here is a
     * stock SeekBar -- {@link GlassSlider} draws the channel, gradient fill and thumb from the
     * same primitives {@link GlassSwitch} uses.
     *
     * <p>Live preview vs. persist: the slider reports every stop with {@code settled=false}
     * (apply now, no disk write) and exactly one {@code settled=true} when the finger lifts.
     * That is what keeps a drag from firing dozens of {@code commit()} calls.
     */
    private View buildMetricRow(final Metric m) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        // No vertical padding here: it used to be 4dp top/bottom OUTSIDE the slider, which left
        // two 4dp strips per row that a horizontal drag could not use -- swiping there flipped
        // the category instead of changing the value. The spacing now lives inside the zone, so
        // the whole 84dp row belongs to the slider.
        row.setPadding(UiKit.dp(context, 14), 0, UiKit.dp(context, 14), 0);

        // ---- one touch surface for the whole row --------------------------------------
        // Device report r6: the label line and the track were two separate blocks, the user
        // dragged on the label, the sheet's horizontal category swipe claimed the gesture and
        // the page flipped to another category instead of changing the value. The row is now
        // a single slider surface: the slider fills the zone and draws its channel in the
        // bottom 48dp band, while the title/value sit on top as non-touchable text.
        FrameLayout zone = new FrameLayout(context);

        final GlassSlider slider = new GlassSlider(context);
        slider.setStops(m.values.length);
        slider.setTrackColors(hd().sliderBackground, cAccent(), cAccentTop());
        slider.setTrackBandDp(48f);
        // Hydrate without notifying: opening the sheet must not write to storage.
        int position = m.positionOf(FloatingConfig.readInt(m.key, m.def));
        slider.setPosition(position);
        slider.setContentDescription(m.title + "，" + m.labelAt(position));
        zone.addView(slider, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // Text is never a touch target here: a non-clickable container returns false from
        // dispatch, so the press falls through to the slider underneath.
        head.setClickable(false);
        head.setFocusable(false);

        TextView title = new TextView(context);
        title.setText(m.title);
        title.setTextSize(14.5f);
        title.setTextColor(cTxP());
        title.setLineSpacing(0, 1.14f);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(context);
        value.setTextSize(13f);
        value.setTextColor(cAccent());
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.END);
        value.setText(m.labelAt(position));
        head.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams headLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        headLp.gravity = Gravity.TOP;
        headLp.topMargin = UiKit.dp(context, 10);      // 4dp former row padding + 6dp gap
        zone.addView(head, headLp);

        // 84dp = the old 76dp content box plus the 4dp of vertical padding that used to sit
        // outside it: 10dp gap + one 14.5sp line + the 48dp interactive band the channel is
        // drawn in. The slider fills all of it, so there is no dead strip left to swipe on.
        row.addView(zone, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(context, 84)));

        if (m.note != null && m.note.length() > 0) {
            TextView note = new TextView(context);
            note.setText(m.note);
            note.setTextSize(11.5f);
            note.setTextColor(cTxS());
            note.setLineSpacing(0, 1.16f);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = UiKit.dp(context, 2);
            nlp.bottomMargin = UiKit.dp(context, 4);   // the row itself has no vertical padding
            row.addView(note, nlp);
        }

        slider.setOnPositionChange(new GlassSlider.OnPositionChange() {
            @Override public void onPositionChanged(GlassSlider view, int next, boolean settled) {
                int stored = m.values[next];
                value.setText(m.labelAt(next));
                view.setContentDescription(m.title + "，" + m.labelAt(next));
                applyMetric(m, stored);
                if (!settled) return;               // preview only until the finger lifts
                boolean ok = FloatingConfig.writeInt(m.key, stored);
                if (ok) {
                    FloatingConfig.invalidate();
                } else {
                    toast(writeFailedHint());
                    refreshStatus();
                }
            }
        });

        metricRows.put(m.key, new MetricRow(m, slider, value));
        return row;
    }

    /**
     * 外观页脚：「恢复默认外观」。
     *
     * <p>Deliberately the same quiet underlined text link as {@link #buildDefaultsRow}: the
     * rows above it are the feature, this is the way back. It resets only the four appearance
     * keys, never the map's switches -- 「恢复默认配置」 in the sheet footer already does that,
     * and one button that silently did both would be a trap.
     */
    private View buildAppearanceResetRow() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 6),
                UiKit.dp(context, 20), UiKit.dp(context, 10));

        final TextView action = new TextView(context);
        action.setText("恢复默认外观");
        action.setTextSize(12f);
        action.setTextColor(cAccent());
        action.setPaintFlags(action.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        action.setPadding(UiKit.dp(context, 4), UiKit.dp(context, 2),
                UiKit.dp(context, 4), UiKit.dp(context, 2));
        action.setBackground(UiKit.rippleBare(cRipple(), UiKit.dpf(context, 8f)));
        action.setContentDescription("把悬浮球大小、不透明度与面板宽度、不透明度恢复为默认");
        UiKit.pressable(action, 0.96f);
        action.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { resetAppearance(); }
        });
        wrap.addView(action);

        TextView hint = new TextView(context);
        hint.setText("悬浮球 " + FloatingConfig.BALL_SIZE_DEFAULT + "dp、不透明度 "
                + FloatingConfig.BALL_ALPHA_DEFAULT + "%；面板跟随屏幕宽度、不透明度 "
                + FloatingConfig.PANEL_ALPHA_DEFAULT + "%。只影响悬浮窗外观。");
        hint.setTextSize(10.5f);
        hint.setTextColor(cTxS());
        hint.setLineSpacing(0, 1.16f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiKit.dp(context, 2);
        wrap.addView(hint, hlp);
        return wrap;
    }

    /** Reset the four appearance keys, apply live, and re-seat the sliders. */
    private void resetAppearance() {
        boolean ok = true;
        for (Metric m : APPEARANCE) {
            ok &= FloatingConfig.writeInt(m.key, m.def);
            applyMetric(m, m.def);
            MetricRow row = metricRows.get(m.key);
            if (row == null) continue;
            int position = m.positionOf(m.def);
            row.slider.setPosition(position);
            row.value.setText(m.labelAt(position));
            row.slider.setContentDescription(m.title + "，" + m.labelAt(position));
        }
        if (ok) FloatingConfig.invalidate();
        toast(ok ? "已恢复默认外观" : "部分设置未写入：" + writeFailedHint());
        refreshStatus();
    }

    /**
     * 「关闭悬浮窗」——真正把悬浮层摘掉，并持久化。
     *
     * <p>与右上角 ✕ 的区别写在文案里：✕ 只是收起这个面板（悬浮球还在），这一行才是关掉整个
     * 悬浮窗。两个动作的语义必须分得开，否则用户会以为「✕」把功能关了、下次找不到。
     *
     * <p>做成描边按钮而不是系统 Button：模块只用框架 API，`UiKit.outlined` 生成的圆角描边
     * 与面板其它玻璃元素同一套语言，同时用警示色把「这是一个会改变入口的动作」讲清楚。
     *
     * <p><b>找回路径是这个功能成立的前提</b>：关掉后 `float_enabled=false`，没有 layer，
     * 长按底部「我的」时 {@code FloatingController.openPanelNear} 必然失败，于是
     * {@code AmapMineEntry.showOwnPanel} 会自托管一个同样的面板 —— 那个面板的「其他」组里
     * 就有 {@code 悬浮窗入口（悬浮球）} 开关，翻回去即恢复。所以这里必须把找回方式写在提示里。
     */
    private View buildFloatOffRow() {
        // 百度/腾讯内置版**没有长按找回**（MainActivity 被裁掉、也没有 tab 入口），
        // 一旦关掉就彻底回不来 —— 实测用户已经踩过一次。所以这两个厂商不提供这一行。
        if (Vendor.baidu() || Vendor.tmap()) return null;

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 10),
                UiKit.dp(context, 20), UiKit.dp(context, 14));

        final TextView action = new TextView(context);
        action.setText("关闭悬浮窗");
        action.setTextSize(13f);
        action.setTextColor(cWarn());
        action.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(0, UiKit.dp(context, 10), 0, UiKit.dp(context, 10));
        action.setBackground(UiKit.outlined(cWarnFill(), cWarn(), UiKit.dp(context, 1),
                UiKit.dpf(context, 14f)));
        action.setContentDescription("关闭悬浮球和这个面板；之后可以长按底部「我的」重新打开");
        UiKit.pressable(action, 0.97f);
        action.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { closeFloatWindow(); }
        });
        wrap.addView(action, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(context);
        hint.setText("关掉后悬浮球和本面板都会消失（右上角 ✕ 只是收起面板，不会关掉悬浮球）。"
                + "要重新打开：长按底部「我的」标签，在「其他」里打开「悬浮窗入口」。");
        hint.setTextSize(10.5f);
        hint.setTextColor(cTxS());
        hint.setLineSpacing(0, 1.16f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiKit.dp(context, 6);
        wrap.addView(hint, hlp);
        return wrap;
    }

    /**
     * Persist {@code float_enabled=false} and tear the overlay down immediately.
     *
     * <p>{@link FloatingConfig#write} already applies this key through
     * {@code FloatingController.setEnabled} (that is why the switch row works without extra
     * plumbing); the explicit call here is a second, cheap guarantee that the host is really
     * detached before the toast appears -- the user must not be left looking at a ball that
     * "closed" but is still on screen.
     *
     * <p>A failed write is not silently ignored: without storage the orb would come back on
     * the next launch, so it is better to keep it and say so than to hide it now.
     */
    private void closeFloatWindow() {
        boolean ok;
        try {
            ok = FloatingConfig.write(FloatingConfig.K_FLOAT_ENABLED, false);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            toast(writeFailedHint());
            refreshStatus();
            return;
        }
        FloatingConfig.invalidate();
        try {
            FloatingController.setEnabled(false);
        } catch (Throwable t) {
            android.util.Log.w("MapCleanFloat", "close float failed: " + t.getClass().getName());
        }
        toast("悬浮窗已关闭；长按底部「我的」可重新打开");
    }

    /** Push one appearance value into the live views; no storage access. */
    private void applyMetric(Metric m, int stored) {        if (FloatingConfig.K_FLOAT_BALL_SIZE.equals(m.key)) {
            if (ball != null) ball.setSizeDp(stored);
        } else if (FloatingConfig.K_FLOAT_BALL_ALPHA.equals(m.key)) {
            if (ball != null) ball.setAlphaPercent(stored);
        } else if (FloatingConfig.K_FLOAT_PANEL_WIDTH.equals(m.key)) {
            applyPanelWidth(stored);
        } else if (FloatingConfig.K_FLOAT_PANEL_ALPHA.equals(m.key)) {
            applyPanelAlpha(stored);
        }
    }

    /**
     * Resize the sheet plate.
     *
     * <p>{@code 0} (自动) restores exactly the shipped layout: MATCH_PARENT with 10dp side
     * margins and {@code gravity=bottom}. A numeric width switches to a fixed, centred plate,
     * clamped to the window so a 420dp sheet cannot hang off a 360dp-wide screen.
     */
    private void applyPanelWidth(int widthDp) {
        if (!(card.getLayoutParams() instanceof LayoutParams)) return;
        // Re-seating identical LayoutParams still schedules a layout pass; skip it so the
        // once-per-open re-read in applyAppearance() cannot churn the tree.
        if (widthDp == appliedPanelWidth) return;
        appliedPanelWidth = widthDp;
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        int margin = UiKit.dp(context, 10);
        if (widthDp <= FloatingConfig.PANEL_WIDTH_AUTO) {
            lp.width = LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.BOTTOM;
            lp.leftMargin = margin;
            lp.rightMargin = margin;
        } else {
            int available = getResources().getDisplayMetrics().widthPixels - margin * 2;
            int px = Math.min(UiKit.dp(context, widthDp), Math.max(1, available));
            lp.width = Math.max(UiKit.dp(context, 240), px);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.leftMargin = 0;
            lp.rightMargin = 0;
        }
        card.setLayoutParams(lp);
    }

    /** Plate opacity. The scrim keeps its own dimming -- fading it too would dim the map. */
    private void applyPanelAlpha(int percent) {
        int clamped = Math.max(FloatingConfig.PANEL_ALPHA_MIN,
                Math.min(FloatingConfig.PANEL_ALPHA_MAX, percent));
        panelAlpha = clamped / 100f;
        if (open) card.setAlpha(panelAlpha);
    }

    /**
     * Re-read the appearance from storage and apply it to the orb and the sheet.
     *
     * <p>Needed because the layer can be built before the module's preferences are reachable
     * ({@code attach()} runs early in the Activity's life); without this a user who set a
     * bigger orb would see the default one until the next full attach.
     */
    private void applyAppearance() {
        if (ball != null) {
            ball.setSizeDp(FloatingConfig.ballSizeDp());
            ball.setAlphaPercent(FloatingConfig.ballAlphaPercent());
        }
        applyPanelWidth(FloatingConfig.panelWidthDp());
        applyPanelAlpha(FloatingConfig.panelAlphaPercent());
    }


    /* ------------------------------------------------------------------ 开合 */

    /** Height of the gesture / navigation bar, so the sheet never hides behind it. */
    private static int navigationBarInset(Context c) {
        try {
            android.content.res.Resources res = c.getResources();
            int id = res.getIdentifier("navigation_bar_height", "dimen", "android");
            return id == 0 ? 0 : res.getDimensionPixelSize(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Re-read every switch and appearance value from storage; called on every open. */
    public void sync() {
        for (java.util.Map.Entry<String, GlassSwitch> e : switches.entrySet()) {
            String key = e.getKey();
            e.getValue().setChecked(strictKeys.contains(key)
                    ? FloatingConfig.readStrict(key)
                    : FloatingConfig.read(key), false);
        }
        for (MetricRow row : metricRows.values()) {
            int position = row.metric.positionOf(
                    FloatingConfig.readInt(row.metric.key, row.metric.def));
            row.slider.setPosition(position);          // programmatic: never writes back
            row.value.setText(row.metric.labelAt(position));
            row.slider.setContentDescription(row.metric.title + "，" + row.metric.labelAt(position));
        }
        applyAppearance();
        refreshStatus();
    }

    /** Drop the search filter and return to the last category. */
    private void clearSearch() {
        // Clear the text first: its watcher re-runs select(), so assigning `query` afterwards
        // keeps this method from fighting the watcher over who owns the field.
        if (searchField != null && searchField.getText().length() > 0) {
            searchField.setText("");
        }
        query = "";
        if (searchCount != null) searchCount.setVisibility(View.GONE);
        if (searchClear != null) searchClear.setVisibility(View.GONE);
        if (rail != null && rail.getParent() instanceof View) {
            ((View) rail.getParent()).setVisibility(View.VISIBLE);
        }
    }

    public boolean isOpen() { return open; }

    public void setOnClose(Runnable r) { onClose = r; }

    public void open() {
        if (open) return;
        // 最后一道闸：模块模式（root/LSPosed）不该出现任何应用内悬浮面板。
        // FloatingHooks 一条 hook 都不装、Controller 也挡了，这里兜住「自托管面板」
        // 那条路（AmapMineEntry.showOwnPanel 会 new 一个本类并 post(open())）——
        // 立刻回调 onClose 让宿主把刚 add 进来的容器拆掉，不留空壳。
        if (!FloatingConfig.isEmbedded()) {
            Log.i(TAG, "event=floating_skipped reason=module_mode via=panel.open");
            if (onClose != null) {
                try { onClose.run(); } catch (Throwable ignored) { }
            }
            return;
        }
        open = true;
        openedAt = android.os.SystemClock.uptimeMillis();
        setVisibility(VISIBLE);
        // 面板展开时把球收起来：球和面板是 host 里的兄弟，z 序在面板之上，
        // 不收就会压在滑杆数值和右侧内容上（真机上是肉眼可见的“贴纸”感）。
        if (ball != null) ball.setVisibility(GONE);
        // 内置版没有 MainActivity：首启「重要提示」原本只在 MainActivity.onCreate 里调用。
        // 悬浮面板是内置版唯一的设置入口，所以在这里补上唯一一处调用（FirstRunNotice 内部
        // 全 try/catch，弹窗失败也绝不会拖垮面板）。
        // 采集模式抓到数据后的「✓ 已获取官方数据」弹窗 + 自动重启由 CaptureGate.tick() 统一负责
        // （它在 onActivityResumed 里轮询 restart.pending），这里不再重复弹一次。
        clearSearch();
        // Rebuild the category data so the tool sections reflect switches flipped since the
        // last time the sheet was open (enabled entries sort to the top of each section).
        groups = buildGroups();
        rebuildRail();
        select(Math.max(0, Math.min(selected, groups.length - 1)), false);
        sync();
        // Version state: always from the cache (no request per open); the automatic check runs
        // once per process, on the first open, in the background.
        refreshUpdateFromCache();
        if (!updateAutoChecked) {
            updateAutoChecked = true;
            startUpdateCheck(false);
        }
        // 每天一次的后台检查（24h 节流在 UpdateChecker 内部，重复调用不会多发请求）。
        try {
            UpdateChecker.maybeAutoCheck(context);
        } catch (Throwable ignored) {
        }
        // 服务器公告：面板打开是「用户在前台」的明确信号，也是唯一保证有 Activity 可依附的
        // 时机。节流（6h）在 Announce 内部；拿不到新公告时它只把之前没弹出去的补弹一次。
        try {
            Announce.setOnChanged(announceRefresher());
            Announce.onPanelOpen(context);
        } catch (Throwable ignored) {
        }
        // 每个新版本只提示一次，且只在用户打开面板时提示 —— 不在 App 启动时打扰。
        try {
            if (updateState == U_NEW && UpdateChecker.shouldNotify(context)) {
                toast("发现新版本 v" + updateLatest + "，点这里查看");
            }
        } catch (Throwable ignored) {
        }
        // Dim behind the sheet and slide the card up.
        UiKit.animateFloat(0f, 0.42f, UiKit.DECELERATE, UiKit.DUR_BASE,
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        float t = (Float) a.getAnimatedValue();
                        scrim.setBackgroundColor(UiKit.blend(0x00000000,
                                dark ? 0xB3000000 : 0x8C000000, t / 0.42f));
                    }
                });
        card.setTranslationY(UiKit.dp(context, 420));
        // Fade in to the *persisted* plate opacity, not to 1: a 70% sheet that flashes fully
        // opaque and then dims reads as a glitch.
        card.setAlpha(panelAlpha * 0.6f);
        card.animate().translationY(0f).alpha(panelAlpha)
                .setDuration(UiKit.DUR_SLOW).setInterpolator(UiKit.DECELERATE).start();
    }

    /**
     * Close with a logged cause.
     *
     * <p>There is <b>no</b> timeout anywhere in this class: a sheet only closes because the
     * user tapped the ✕ or the scrim. The log line makes that provable on a device -- a sheet
     * that vanishes on its own will name its own killer.
     */
    private void closeFrom(String cause) {
        Log.i(TAG, "event=sheet_close cause=" + cause
                + " age=" + (android.os.SystemClock.uptimeMillis() - openedAt));
        close();
    }

    public void close() {
        if (!open) return;
        open = false;
        clearSearch();
        // Drop the keyboard, otherwise it stays up over the map after the sheet is gone.
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) context.getSystemService(
                            Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
            if (searchField != null) searchField.clearFocus();
        } catch (Throwable ignored) { }
        card.animate().translationY(UiKit.dp(context, 420)).alpha(0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.ACCELERATE)
                .withEndAction(new Runnable() {
                    @Override public void run() { setVisibility(GONE); if (ball != null) ball.setVisibility(VISIBLE); }
                }).start();
        UiKit.animateFloat(0.42f, 0f, UiKit.ACCELERATE, UiKit.DUR_BASE,
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        float t = (Float) a.getAnimatedValue();
                        scrim.setBackgroundColor(UiKit.blend(0x00000000,
                                dark ? 0xB3000000 : 0x8C000000, t / 0.42f));
                    }
                });
        if (onClose != null) onClose.run();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        // The scrim already consumes taps outside the card, so events that land on a switch
        // or on the close button reach their child normally -- never intercept those.
        //
        // The one gesture the panel does claim is a deliberate horizontal swipe, which
        // changes category. It is claimed only after the direction is unambiguous, so a
        // vertical drag still scrolls the list and a tap still hits its row.
        if (!open) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = e.getX();
                swipeDownY = e.getY();
                swipeActive = false;
                // A DOWN inside the chip row belongs to the HorizontalScrollView: returning
                // false here (and in MOVE) is the whole mechanism -- no disallow flag, no touch
                // listener on the scroller. Both of those used to clear the scroller's own
                // interception and left scrollX at 0 (r12).
                swipeFromRail = isInRail(swipeDownX, swipeDownY);
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (swipeActive) return true;
                // The rail is itself a horizontal scroller, and search results are a single
                // flat list with no "next category" to move to.
                if (swipeFromRail || !query.isEmpty()) return false;
                float dx = e.getX() - swipeDownX;
                float dy = e.getY() - swipeDownY;
                if (Math.abs(dx) > UiKit.dp(context, 20)
                        && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    swipeActive = true;
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swipeActive = false;
                // Evidence line for a verification run: if the rail actually scrolled, this is
                // non-zero right after the gesture. Only logged for gestures that started in
                // the rail, so ordinary taps stay quiet.
                if (swipeFromRail && railScroll != null) {
                    Log.i(TAG, "event=rail_after scrollX=" + railScroll.getScrollX());
                }
                swipeFromRail = false;
                return false;

            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (swipeActive) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - swipeDownX;
                swipeActive = false;
                // A short drag is more likely a mis-tap than an intent to change category.
                if (Math.abs(dx) > UiKit.dp(context, 52)) step(dx < 0 ? 1 : -1);
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                swipeActive = false;
                return true;
            }
            return true;
        }
        // Only claim events when the sheet is up; otherwise let the map have them.
        return open || super.onTouchEvent(e);
    }

    /**
     * Move one category in {@code direction} (+1 = next, -1 = previous).
     *
     * <p>The body slides out the way the finger went and the next one slides in behind it, so
     * the motion reads as a single continuous pager rather than a hard cut.
     *
     * <p><b>The rail wraps around.</b> The direction maths was never asymmetric -- the caller
     * uses {@code dx < 0 ? 1 : -1} and {@code Math.abs(dx)} for the threshold, so both ways
     * intercept and fire identically. What "failed" was the end of the list: 「外观」 is the
     * last category, so a "next" swipe from there hit {@code target >= count}, played a short
     * resistance nudge and changed nothing -- reported as 「右→左 失效」 (r8). Wrapping is the
     * only way both directions can do something on the page the user is actually looking at,
     * and a closed ring is the natural model for a horizontal chip rail.
     */
    private void step(final int direction) {
        final int count = groups.length;
        if (count < 2) return;
        final int target = ((selected + direction) % count + count) % count;
        final int travel = UiKit.dp(context, 64);
        body.animate().translationX(-direction * travel).alpha(0f)
                .setDuration(UiKit.DUR_QUICK).setInterpolator(UiKit.ACCELERATE)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        select(target, false);
                        body.setTranslationX(direction * travel);
                        body.animate().translationX(0f).alpha(1f)
                                .setDuration(UiKit.DUR_BASE)
                                .setInterpolator(UiKit.DECELERATE).start();
                    }
                }).start();
    }

    /** True when the point falls inside the category rail (in this panel's coordinates). */
    private boolean isInRail(float x, float y) {
        if (rail == null || !(rail.getParent() instanceof View)) return false;
        View scroller = (View) rail.getParent();
        Rect bounds = new Rect();
        if (!boundsInPanel(scroller, bounds)) return false;
        boolean hit = bounds.contains((int) x, (int) y);
        // One line per DOWN while the sheet is open: this is the only way to tell a wrong
        // hit rectangle from a wrong decision further up (the lead asked for exactly these
        // numbers -- r8 and r10 both went wrong on coordinates, so they are printed now).
        Log.i(TAG, "event=rail_probe down=" + (int) x + "," + (int) y
                + " rect=" + bounds.left + "," + bounds.top
                + "," + bounds.right + "," + bounds.bottom
                + " scroller=(" + scroller.getLeft() + "," + scroller.getTop()
                + "," + scroller.getWidth() + "x" + scroller.getHeight()
                + ",scrollX=" + scroller.getScrollX() + ")"
                + " hit=" + hit);
        return hit;
    }

    /**
     * Translate a descendant's rectangle into this panel's coordinate space.
     *
     * <p>Scroll offsets are subtracted <b>from the parent</b>, not from the view itself: a
     * {@code ScrollView}/{@code HorizontalScrollView} moves its children on the canvas without
     * changing their layout bounds, so a child's visual position is
     * {@code child.getLeft() - parent.getScrollX()}. The r8 version subtracted the view's own
     * scroll, which shifted the reported rect of the scrolling container *itself*: once the
     * chip row had been scrolled, {@link #isInRail} answered false and a horizontal drag on the
     * rail was taken as a category swipe instead of a rail scroll.
     */
    private boolean boundsInPanel(View view, Rect out) {
        int left = 0, top = 0;
        View current = view;
        while (current != null && current != this) {
            if (!(current.getParent() instanceof View)) break;
            View parent = (View) current.getParent();
            left += current.getLeft() - parent.getScrollX();
            top += current.getTop() - parent.getScrollY();
            current = parent;
        }
        if (current != this) return false;
        out.set(left, top, left + view.getWidth(), top + view.getHeight());
        return true;
    }

    /* ------------------------------------------------------------------ 数据 */

    /**
     * The catalogue for whichever map this orb is drawn over, plus the 外观 page.
     *
     * <p>Never both vendors: each vendor's list lives in its own file, and the 免 root 内置
     * build for a given map replaces the other vendor's panel with a no-op stub (see
     * tools/build_variant.py).
     *
     * <p>外观 is appended <b>here</b> rather than inside a vendor panel on purpose: the orb's
     * own size and opacity belong to {@link FloatingController}, not to the map's catalogue,
     * and adding it here gives all three vendors the page without editing a file that the
     * variant builder substitutes (the generated stubs would not carry a new method).
     */
    private static Group[] buildGroups() {
        Group[] vendor = Vendor.tmap() ? TmapPanel.groups()
                : (Vendor.baidu() ? BaiduPanel.groups() : AmapPanel.groups());
        // 服务器公告与厂商无关，落点统一收在「其他」页：高德 / 百度的目录自带这一页
        // （about=true），腾讯的目录里没有，这里补一页。放在厂商目录之外，
        // 三份目录就不必各维护一遍同样的两行。
        vendor = withAnnouncePage(vendor);
        // 模块模式（root/LSPosed）**没有悬浮窗** ⇒ 「外观」整页（球大小/透明度/关闭悬浮窗）
        // 不注册，chip 也不出现。内置版保持原样。
        if (!FloatingConfig.isEmbedded()) return vendor;
        Group[] out = new Group[vendor.length + 1];
        System.arraycopy(vendor, 0, out, 0, vendor.length);
        out[vendor.length] = new Group("外观", APPEARANCE,
                "悬浮球与面板的外观。拖动滑块即时预览，松手后保存。");
        return out;
    }

    /**
     * 保证存在一页带 {@code about} 的「其他」。
     *
     * <p>这一页承载的东西与厂商无关：<b>服务器公告</b>（开关 + 最新公告入口）和版本行。
     * 高德 {@code othersItems()} 与百度 {@code groups()} 自己声明了 {@code about=true}；
     * 腾讯的分类全部来自 {@code Spec.cats()}，没有这一页 —— 于是它的公告入口曾经无处可放。
     */
    private static Group[] withAnnouncePage(Group[] vendor) {
        if (vendor == null) vendor = new Group[0];
        for (Group g : vendor) if (g.about) return vendor;
        Group[] out = new Group[vendor.length + 1];
        System.arraycopy(vendor, 0, out, 0, vendor.length);
        out[vendor.length] = new Group("其他", new Item[0], "服务器公告与版本信息。", true);
        return out;
    }

    /**
     * One-line explanation for the tool catalogue.
     *
     * <p>The home toolbar renders exactly five cells (the app's own builder iterates id0..id4),
     * so switching a sixth tool on cannot show up there. Saying so up front is better than
     * letting the limit read as another dead switch.
     */
    private TextView groupNote(String text) {
        TextView note = new TextView(context);
        note.setText(text);
        note.setTextSize(11.5f);
        note.setTextColor(cTxS());
        note.setLineSpacing(0, 1.18f);
        note.setPadding(UiKit.dp(context, 16), UiKit.dp(context, 10),
                UiKit.dp(context, 16), UiKit.dp(context, 2));
        return note;
    }

    /* ------------------------------------------------------------------ 配色 */

    /**
     * 色板全部转发到 {@link HyperDesign} —— 这里此前是一份独立的 Liquid Glass 色板
     * （11 个私有方法、73 处硬编码 ARGB），和模块 App 的 {@link Skin} 各说各话。
     * 改一次主题要动两个文件，且内置版和模块版的观感对不上。
     *
     * <p>方法名保持不变：调用点遍布 2844 行，改名没有收益。
     */
    private HyperDesign hd() {
        if (hdCache == null || hdCache.dark != dark) hdCache = HyperDesign.of(dark);
        return hdCache;
    }

    private HyperDesign hdCache;

    private int cBg()       { return hd().background; }
    /** 卡片底。HyperOS 浅色下与页面同色，靠页边距和圆角切分。 */
    private int cGlass()    { return hd().surfaceContainer; }
    /** 描边两端：HyperOS 不用高光边，只保留一点方向感。 */
    private int cRimHi()    { return HyperDesign.mix(hd().outline, 0xFFFFFFFF, dark ? 0.10f : 0.55f); }
    private int cRimLo()    { return hd().outline; }
    private int cTxP()      { return hd().onSurface; }
    private int cTxS()      { return hd().onSurfaceVariantSummary; }
    private int cAccent()   { return hd().primary; }
    private int cRipple()   { return HyperDesign.alpha(hd().primary, dark ? 0.16f : 0.10f); }
    private int cDotOk()    { return dark ? 0xFF34D399 : 0xFF12A150; }
    private int cDotBad()   { return hd().error; }
    /** Warning tone for the one action that removes the entry point (关闭悬浮窗). */
    private int cWarn()     { return dark ? 0xFFFFB020 : 0xFFC77700; }
    private int cWarnFill() { return HyperDesign.alpha(cWarn(), dark ? 0.16f : 0.10f); }
    private int cAccentTop()    { return HyperDesign.mix(hd().primary, 0xFFFFFFFF, dark ? 0.10f : 0.18f); }
    private int cAccentBottom() { return HyperDesign.mix(hd().primary, 0xFF000000, dark ? 0.14f : 0.10f); }
}
