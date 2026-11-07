package io.github.ldxm666.mapclean;

import io.github.ldxm666.mapadkiller.App;
import io.github.ldxm666.mapadkiller.CaptureLog;
import io.github.ldxm666.mapadkiller.Config;
import io.github.ldxm666.mapadkiller.LearnedProvider;
import io.github.ldxm666.mapadkiller.MainHook;
import io.github.ldxm666.mapadkiller.SdkAutoBlock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Window;
import android.widget.EditText;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

/**
 * 设置页 — Liquid Glass 风格（设计语言参考 Kyant0/AndroidLiquidGlass）。
 *
 * 视觉体系（纯框架 API 复刻，无 AndroidX / Compose 依赖）：
 *  · 柔和径向光斑（blob）铺底，营造折射底色
 *  · 半透明玻璃卡片：渐变高光描边（LayerDrawable 双层圆角）模拟玻璃厚度
 *  · 分组抽屉：默认折叠、点击展开（LayoutTransition 平滑重排 + 淡入位移）
 *  · 底部悬浮玻璃操作栏（胶囊按钮），避开导航栏
 *  · 胶囊开关（自绘 thumb/track）、行级涟漪、深色模式全适配
 *
 * 配置写入 LSPosed RemotePreferences（App 侧经 libxposed/service，
 * Hook 侧经 XposedModule#getRemotePreferences，同名 group 双侧共享）。
 */
public final class MainActivity extends Activity {

    private TextView statusView;
    /** 版本行（绿/黄/灰）与最佳适配行 */
    private TextView verLine;
    /** 醒目的更新入口：默认「检查版本更新」，有新版时变成「立即更新 vX」 */
    private TextView updateBtn;
    private TextView adLine;
    private boolean updateDialogShown;
    /** 「已捕获广告 SDK」那一行；服务绑定后要重刷文案，否则一直显示 onCreate 时的空快照 */
    private TextView sdkRow;

    private ScrollView scroll;
    private LinearLayout list;          // 抽屉分组挂载点（LayoutTransition 目标）
    private FrameLayout page;
    private LinearLayout bottomBar;
    private FrameLayout.LayoutParams barLp;

    // ── v2.2.0 版式骨架：vertical root = hero(含 tabs) + pager(weight) + 浮层底栏 ──
    /** 活动内容根：FrameLayout 遮罩层，承载页面列 + 悬浮底栏 */
    private FrameLayout overlay;
    /** 页面列（垂直）：hero + pager */
    private LinearLayout column;
    private LinearLayout hero;
    /** 顶部右侧的框架状态药丸（已连接 / 作用域） */
    private LinearLayout navPill;
    /** hero 里的实时指标行容器 */
    private LinearLayout metricRow;

    // ── v2.0.0 分页：百度 / 高德 / 其他，左右滑动切换 ──────────────────────
    private Pager pager;
    private LinearLayout tabBar;
    private final java.util.List<TextView> tabChips = new java.util.ArrayList<>();
    private final java.util.List<android.widget.ScrollView> pages = new java.util.ArrayList<>();
    /** 百度那一页的开关（配置 group 与高德不同，要单独同步） */
    private final Map<String, GlassSwitch> bmapSwitches = new LinkedHashMap<>();
    /** 腾讯那一页的开关（第三个 group：tmapclean_config） */
    private final Map<String, GlassSwitch> tmapSwitches = new LinkedHashMap<>();

    /**
     * 设置页搜索：跨「百度 / 高德 / 腾讯 / 其他」四页过滤所有开关。
     *
     * <p>为什么值得单独一条：腾讯的工具目录在真机上有 90 多项（内置表 + 服务端下发合并），
     * 高德的「更多工具」有 100 多项。没有搜索时用户得在一个折叠抽屉里翻很久，
     * 而这恰恰是他唯一想用的功能。悬浮面板里早有同款（FloatingPanel.buildSearch），
     * 设置页一直缺，这一条补上。
     */
    private SwitchFinder finder;

    /** 展开状态快照：搜索时全部展开，退出搜索时按这个还原。 */
    private final Map<Sec, Boolean> expandSnapshot = new LinkedHashMap<>();

    // ── 搜索：页下标必须与 onCreate 里 pager.addView / addTab 的顺序一致 ──────────
    private static final int PAGE_BAIDU = 0, PAGE_AMAP = 1, PAGE_TMAP = 2, PAGE_OTHER = 3;

    /**
     * 把一个开关报给搜索索引。
     *
     * <p>调用点在开关工厂里而不是页面构建循环里：那样才能同时拿到“界面显示的标题”
     * 和“配置键”，而不必让调用方再传一遍。
     */
    private static void index(SwitchFinder f, String key, String title, String note, int page) {
        if (f == null || key == null) return;
        try { f.index(key, title, note, page); } catch (Throwable ignored) { }
    }

    private static final int GLASS_R = 22;   // 卡片圆角 dp
    private static final int HEAD_R = 18;    // 抽屉头/按钮圆角 dp

    private boolean dark;
    private String embeddedHost;

    private boolean embedded() { return embeddedHost != null; }
    private boolean embeddedAmap() { return EmbeddedSettingsPolicy.AMAP.equals(embeddedHost); }

    // ── 内置版只读闸（独立设置 App 专用） ────────────────────────────────
    /**
     * 页面 id → 该宿主是不是 LSPatch 内置版。{@code null}/缺失 = 还没测出来或测不出来，
     * 一律按模块版处理（fail-open，界面不锁）。
     */
    private final Map<Integer, Boolean> embeddedPages = new HashMap<Integer, Boolean>();
    /** 已经锁过的页面 id，避免重复遍历整棵视图树。 */
    private final Set<Integer> embeddedLocked = new HashSet<Integer>();
    /** 锁定时显示在配置页顶部的说明条。 */
    private TextView embeddedNotice;

    private String settingsTargetLabel() {
        return embedded() ? (embeddedAmap() ? "高德" : "百度") : "高德和百度";
    }
    private android.content.SharedPreferences uiPrefs() {
        return getSharedPreferences(embedded() ? EmbeddedSettingsPolicy.UI_PREFS : App.UI_PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (EmbeddedSettingsPolicy.isHostPackage(getPackageName())) {
            try {
                io.github.ldxm666.mapclean.App.attachEmbedded(getApplication());
                embeddedHost = getPackageName();
            } catch (Throwable error) {
                android.util.Log.w("MapCleanEmbedded", "settings connection unavailable", error);
                finish();
                return;
            }
        }
        dark = isDarkMode();
        // Two framework themes so AlertDialog follows day/night without a styles.xml.
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                      : android.R.style.Theme_Material_Light_NoActionBar);
        skin = new Skin(this, dark);
        getWindow().setBackgroundDrawable(new ColorDrawable(skin.bg));
        getWindow().setStatusBarColor(skin.bg);
        getWindow().setNavigationBarColor(dark ? 0xFF0A0B0E : 0xFFFFFFFF);
        int sysUi = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!dark) sysUi |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(sysUi);

        contentHost = new FrameLayout(this);
        contentHost.setBackgroundColor(0x00000000);
        pager = new Pager(this);
        pager.setOnPageChanged(new Pager.OnPageChanged() {
            @Override public void onPageChanged(int index) { onTabShown(index); }
        });
        contentHost.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 搜索索引必须先于页面构建：registerPageTarget 会在建行之前重置该页索引。
        finder = new SwitchFinder(this, dark);
        finder.setPageJumper(new SwitchFinder.PageJumper() {
            @Override public void jump(int page) {
                showTab(TAB_CONFIG, true);
                for (int i = 0; i < appPageIds.size(); i++) {
                    if (appPageIds.get(i) == page) { selectApp(i); break; }
                }
            }
        });

        // 配置页先建：首页的「N 个开关」和备份页的统计都读这三张开关表。
        configScreen = buildConfigPage();
        homePage = buildHomePage();
        backupPage = buildBackupPage();
        aboutPage = buildAboutPage();
        pager.addView(homePage);
        pager.addView(configScreen);
        pager.addView(backupPage);
        pager.addView(aboutPage);

        navBar = buildBottomNav();
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(skin.bg);
        shell.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        shell.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(shell);

        // 底部标签栏在布局流里（不是浮层）：内容区自然止于栏顶，不需要给每页留 92 dp 的假底。
        shell.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                contentHost.setPadding(0, top, 0, 0);
                navBar.setPadding(skin.dp(16), skin.dp(6), skin.dp(16), bottom + skin.dp(10));
                return insets;
            }
        });

        showTab(TAB_HOME, false);
        renderStatus();
        shell.post(new Runnable() {
            @Override public void run() { UiKit.enter(contentHost, skin.dp(8), 0L); }
        });

        // 首次进入的强制提示（3 秒后才能点掉）。异常全吞：设置页崩了比少弹一次严重得多。
        // 用户要求：去掉每次打开模块的提示弹窗（FirstRunNotice.maybeShow 调用已移除）

        // 抓取回执独立轮询（v2.3.0）：statusRefresher 只在 onStart~onStop 之间跑，
        // 而抓取期间用户一定被切到高德去了 —— 那段正是「抓完了」的时刻。要在后台也把
        // 结果提示（通知栏）发出去，就得有一条 onCreate~onDestroy 的独立心跳。
        // 它只读一次 prefs，不碰任何 View，代价可以忽略。
        statusHandler.removeCallbacks(captureResultPoller);
        statusHandler.post(captureResultPoller);
    }

    /**
     * 抓取回执轮询：{@code done/failed} 且「没提示过」时给一条明确反馈，
     * 成功且用户正看着设置页则 1.2 秒后自动退出。
     */
    private final Runnable captureResultPoller = new Runnable() {
        @Override public void run() {
            try {
                if (!embedded()) readCaptureReport();
            } catch (Throwable t) {
                // 心跳里任何异常都必须留痕：以前就是这里静默吞掉，用户只看到「一直转」。
                android.util.Log.w("MapCleanCapture", "event=capture_poll_fail err=" + t);
            }
            statusHandler.postDelayed(this, CAPTURE_POLL_MS);
        }
    };

    /** 回执轮询间隔。宿主发完广播到本地落盘是毫秒级，1.5 秒足够快又不会烧电。 */
    private static final long CAPTURE_POLL_MS = 1500L;

    // ── 状态心跳 ────────────────────────────────────────────────────────────
    // v2.2.0 修复：statusRefresher 此前**只被定义、从未 post**。于是 renderStatus()
    // 永不执行 —— 顶部三格指标永远停在「—」、状态药丸永远是个空横线，
    // 而 syncSwitches() 永不执行，所有开关停在构建期 setEnabled(false) 的灰胶囊上。
    // 这一个心跳同时驱动：指标文案 / 状态药丸 / 开关与真实配置对齐。
    @Override protected void onStart() {
        super.onStart();
        statusHandler.removeCallbacks(statusRefresher);
        statusHandler.post(statusRefresher);
    }

    @Override protected void onStop() {
        super.onStop();
        statusHandler.removeCallbacks(statusRefresher);
    }

    @Override protected void onDestroy() {
        statusHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Push the pager down by the measured hero height so the two never overlap. */
    private void applyHeroInset() {
        try {
            ViewGroup.LayoutParams raw = pager.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int want = hero.getHeight() + dp(4);
            if (lp.topMargin != want) {
                lp.topMargin = want;
                pager.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    /** Entrance choreography: hero first, then the tabs, then the live page. */
    private void animateIn() {
        UiKit.enter(hero, dp(10), 0L);
        if (tabBar != null) UiKit.enter(tabBar, dp(8), 70L);
        if (navPill != null) UiKit.enter(navPill, dp(12), 130L);
        final View current = pager.pageCount() > 0 ? pager.getChildAt(pager.page()) : null;
        if (current != null) UiKit.enter(current, dp(14), 40L);

        // Safety net: whatever happens above (cancelled animation, a mid-flight
        // relayout), make sure the page is visible shortly after.
        overlay.postDelayed(new Runnable() {
            @Override public void run() {
                UiKit.reveal(hero);
                UiKit.reveal(tabBar);
                if (current != null) UiKit.reveal(current);
            }
        }, 900L);
    }

    private boolean isDarkMode() {
        int m = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------------------------------------------------------- 调色板

    private int cBg()       { return skin == null ? 0xFFF4F5F7 : skin.bg; }
    private int cGlass()    { return skin == null ? 0xFFFFFFFF : skin.surface; }
    private int cRimHi()    { return skin == null ? 0xFFFFFFFF : skin.rimHi; }
    private int cRimLo()    { return skin == null ? 0x33FFFFFF : skin.rimLo; }
    private int cTxP()      { return skin == null ? 0xFF14171C : skin.text; }
    private int cTxS()      { return skin == null ? 0xFF5B6472 : skin.text2; }
    private int cAccent()   { return skin == null ? 0xFF2563EB : skin.accent; }
    private int cRipple()   { return dark ? 0x22FFFFFF : 0x0F000000; }
    private int cTrackOff() { return skin == null ? 0x2E8A94A4 : skin.chipBg; }
    private int cDotOk()    { return skin == null ? 0xFF16A34A : skin.success; }
    private int cDotBad()   { return skin == null ? 0xFFDC2626 : skin.danger; }

    private int[] blobColors() {
        return dark
                ? new int[]{0x263D6BFF, 0x1E19C37A, 0x227C5CFF}
                : new int[]{0x1E649BFF, 0x162BD9B8, 0x16A78BFA};
    }

    // ---------------------------------------------------------------- 背景光斑

    /**
     * Decorative bloom behind everything.
     *
     * Previous build added giant radial {@code View}s as a weighted band, which stole
     * half the screen and rendered as one enormous disc. Blooms now paint into a single
     * custom Drawable set as the WINDOW background, so they are truly behind the content
     * and cannot intercept touches or consume layout space.
     */
    /**
     * Decorative bloom behind everything.
     *
     * First attempt painted three huge radial gradients as the window background with
     * radii up to 0.62 of the screen diagonal; on a 1080x2400 panel that read as one
     * enormous green disc bleeding through the whole UI. Two lessons applied here:
     *   - the base colour is fully opaque first, so the blooms only tint, never flood;
     *   - alpha stays low (the caller passes faint ARGB) and radii are much smaller,
     *     confined to the top and bottom corners like a real background wash.
     */
    private void applyWindowBloom() {
        try {
            getWindow().setBackgroundDrawable(new BloomDrawable(blobColors(), cBg()));
        } catch (Throwable ignored) {
            getWindow().setBackgroundDrawable(new ColorDrawable(cBg()));
        }
    }

    /** Faint radial washes in the corners; never affects layout and never intercepts input. */
    private static final class BloomDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int[] colors;
        private final int base;
        private final float[] cx = {0.05f, 0.95f, 0.50f};
        private final float[] cy = {0.02f, 0.20f, 1.00f};
        private final float[] rr = {0.34f, 0.26f, 0.30f};

        BloomDrawable(int[] colors, int base) {
            this.colors = colors;
            this.base = base;
        }

        private final android.graphics.Shader[] shaders =
                new android.graphics.Shader[cx.length];
        private int cachedW = -1, cachedH = -1, cachedLeft = Integer.MIN_VALUE,
                cachedTop = Integer.MIN_VALUE;

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            // Opaque base first: blooms are a tint over it, not the colour itself.
            canvas.drawColor(base);
            float w = b.width(), h = b.height();
            float max = Math.max(w, h);
            // The window background keeps the same bounds for the whole session, so the
            // three radial shaders are built once instead of on every draw() pass -- the
            // old version allocated a fresh RadialGradient per bloom per frame.
            if (shaders[0] == null || w != cachedW || h != cachedH
                    || b.left != cachedLeft || b.top != cachedTop) {
                cachedW = (int) w;
                cachedH = (int) h;
                cachedLeft = b.left;
                cachedTop = b.top;
                for (int i = 0; i < colors.length && i < cx.length; i++) {
                    float x = b.left + w * cx[i];
                    float y = b.top + h * cy[i];
                    // Clamp radius hard: nothing bigger than a third of the screen.
                    float radius = Math.min(max * rr[i], max * 0.34f);
                    int solid = colors[i];
                    int clear = solid & 0x00FFFFFF;
                    shaders[i] = new android.graphics.RadialGradient(
                            x, y, radius, solid, clear, android.graphics.Shader.TileMode.CLAMP);
                }
            }
            for (int i = 0; i < shaders.length && i < cx.length; i++) {
                if (shaders[i] == null) continue;
                paint.setShader(shaders[i]);
                float x = b.left + w * cx[i];
                float y = b.top + h * cy[i];
                float radius = Math.min(max * rr[i], max * 0.34f);
                canvas.drawCircle(x, y, radius, paint);
            }
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    // ---------------------------------------------------------------- 玻璃材质

    /** 玻璃面板：底层高光 rim + 内层半透明体，双层圆角模拟玻璃厚度 */
    private Drawable glass(float radiusDp) {
        if (skin != null) return skin.glass(radiusDp);
        return UiKit.glass(cGlass(), cRimHi(), cRimLo(), dp(radiusDp), Math.max(1, dp(1)));
    }

    /** 旧签名保留：直接委托给 UiKit，保证已有调用点仍可编译 */
    private Drawable ripple(Drawable content, float radiusDp) {
        return UiKit.ripple(cRipple(), content, dp(radiusDp));
    }

    private GradientDrawable capsule(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(color);
        g.setSize(dp(46), dp(28));
        return g;
    }

    /** 自绘胶囊开关：轨道染青、thumb 带骨至挤压，抛出带弹道感 */
    private GlassSwitch makeSwitch() {
        GlassSwitch sw = new GlassSwitch(this);
        sw.setTrackColors(cTrackOff(), cAccent());
        sw.setThumbColors(0xFFFFFFFF, 0xFFFFFFFF);
        // 不再用 setAlpha 假装「未就绪」：开关从第一帧起就画真实状态、并且可点。
        // 服务未绑定时点击会走写入失败分支给出 Toast，而不是变成一颗点不动的灰胶囊。
        return sw;
    }

    // ---------------------------------------------------------------- 版本 / 更新

    private final UpdateChecker.Callback updateCb = new UpdateChecker.Callback() {
        @Override public void onResult(String latest, boolean outdated, String source) {
            renderVersionLines();
            if (outdated && latest != null && !updateDialogShown) {
                updateDialogShown = true;
                showUpdateDialog(latest);
            }
        }
    };

    /** 版本行配色：已是最新=绿、有新版本=黄、未获取=灰（点一下强制刷新）。 */
    private void renderVersionLines() {
        if (verLine == null) return;
        String latest = UpdateChecker.latest(this);
        boolean out = UpdateChecker.isOutdated(this);
        String txt;
        if (latest == null) {
            verLine.setTextColor(cTxS());
            txt = "当前 v" + Version.LABEL + " · 线上版本未获取";
        } else if (out) {
            verLine.setTextColor(skin == null ? 0xFFD97706 : skin.warn);
            txt = "当前 v" + Version.LABEL + " · 有新版本 v" + latest;
        } else {
            verLine.setTextColor(cDotOk());
            txt = "当前 v" + Version.LABEL + " · 已是最新";
        }
        verLine.setText(txt);
        if (updateBtn == null) return;
        int pill = dp(999);
        if (out && latest != null) {
            updateBtn.setText("立即更新");
            updateBtn.setTextColor(0xFFFFFFFF);
            updateBtn.setBackground(UiKit.ripple(0x33FFFFFF,
                    UiKit.plate(skin == null ? 0xFFD97706 : skin.warn, pill), pill));
        } else if (latest == null) {
            updateBtn.setText("重试");
            updateBtn.setTextColor(0xFFFFFFFF);
            updateBtn.setBackground(UiKit.ripple(0x33FFFFFF,
                    UiKit.plate(cAccent(), pill), pill));
        } else {
            updateBtn.setText("已是最新");
            updateBtn.setTextColor(cDotOk());
            updateBtn.setBackground(UiKit.plate(dark ? 0x1F34D399 : 0x1416A34A, pill));
        }
    }

    /** 最佳适配：模块 hook 点验证过的目标版本 vs 本机实际版本 */
    private String adaptSummary() {
        StringBuilder sb = new StringBuilder("已验证版本：");
        for (int i = 0; i < Version.TARGETS.length; i++) {
            String pkg = Version.TARGETS[i][0];
            if (embedded() && !embeddedHost.equals(pkg)) continue;
            String label = Version.TARGETS[i][1];
            String want = Version.TARGETS[i][2];
            if (sb.length() > "已验证版本：".length()) sb.append("　");
            sb.append(label).append(' ').append(want);
            String inst = installedVersion(pkg);
            if (inst == null) {
                sb.append("（未安装）");
            } else if (inst.equals(want) || inst.startsWith(want)) {
                sb.append("（本机 ").append(inst).append(" ✔）");
            } else {
                sb.append("（本机 ").append(inst).append("，逐项检测兼容）");
            }
        }
        return sb.toString();
    }

    private String installedVersion(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi == null ? null : pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 发现新版本：四个出口 —— **服务器下载**（主）/ GitHub 页（备用）/ Telegram / 稍后更新。
     * 用两行按钮，避免三个按钮塞不下第四个选项。
     */
    private void showUpdateDialog(final String latest) {
        final AlertDialog dlg = new AlertDialog.Builder(this).create();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(skin.glassFor(box, GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(14));

        box.addView(text("发现新版本 v" + latest, 18, Typeface.BOLD, cTxP()));

        String msg = "当前版本 v" + Version.LABEL + "　→　最新版本 v" + latest
                + "\n\n【直接从服务器下载】「服务器下载」取的是作者服务器上的安装包，"
                + "国内网络可直接打开；内置版会自动选与你当前地图对应的那个包。"
                + "\n\n「GitHub 页」是项目发布页，需要能访问 GitHub，只作备用。"
                + "\n\n更新后建议强停三家地图 App 再重新打开。";
        TextView mv = text(msg, 13, Typeface.NORMAL, cTxS());
        mv.setLineSpacing(0, 1.28f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(10);
        box.addView(mv, mlp);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        r1.addView(dlgButton("服务器下载", true, new Runnable() {
            @Override public void run() { openUrl(mirrorUrl()); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(spacer(dp(10)));
        r1.addView(dlgButton("GitHub 页", false, new Runnable() {
            @Override public void run() { openUrl(Version.RELEASES); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r1, rlp);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.topMargin = dp(10);
        r2.addView(dlgButton("TG 通知频道", false, new Runnable() {
            @Override public void run() { openUrl(Version.TG); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r2.addView(spacer(dp(10)));
        r2.addView(dlgButton("稍后更新", false, new Runnable() {
            @Override public void run() { dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r2, rlp2);

        dlg.setView(box);
        dlg.show();
        try {
            android.view.Window w = dlg.getWindow();
            if (w != null) w.setBackgroundDrawable(new ColorDrawable(0));
        } catch (Throwable ignored) {}
    }

    /**
     * 新版本安装包地址：服务端清单优先（内置版自动选宿主对应的包），
     * 拿不到才退回项目发布页（需要能访问 GitHub，只作兜底）。
     */
    private String mirrorUrl() {
        try {
            String url = UpdateChecker.downloadUrl(this);
            if (url != null) return url;
        } catch (Throwable ignored) { }
        return Version.RELEASES;
    }

    /** 圆角纯色背景（更新按钮用） */
    private GradientDrawable capsuleBg(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView dlgButton(String label, boolean primary, final Runnable action) {
        TextView b = text(label, 14, Typeface.BOLD, primary ? 0xFFFFFFFF : cAccent());
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? (dark ? 0xFF2F6BFF : 0xFF0A6CF5) : (dark ? 0x2E7AB1FF : 0x1F0A6CF5));
        bg.setCornerRadius(dp(HEAD_R));
        b.setBackground(ripple(bg, HEAD_R));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    // ---------------------------------------------------------------- 区块构建

    private void buildHero(LinearLayout host) {
        // ── 玻璃状态卡：标题 + 右侧状态药丸 + 实时指标条 ──────────────────
        // Old build stacked five full-width text rows (740 px of a 2276 px screen) and
        // let the "verified versions" line wrap onto three lines. Everything that can be
        // a compact pill is one now, and the long version string is collapsed behind tap.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(24));
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setElevation(0);
        UiKit.clipRounded(card, dp(24));

        // 首行：标题 与 状态药丸
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("MapAdKiller", 21, Typeface.BOLD, cTxP());
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        navPill = new LinearLayout(this);
        navPill.setOrientation(LinearLayout.HORIZONTAL);
        navPill.setGravity(Gravity.CENTER_VERTICAL);
        navPill.setPadding(dp(11), dp(6), dp(13), dp(6));
        navPill.setBackground(UiKit.ripple(cRipple(), UiKit.plate(pillBg(), dp(999)), dp(999)));
        navPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showStatusDetail(); }
        });
        UiKit.pressable(navPill, 0.94f);
        // Exact pixel size: WRAP_CONTENT on a bare View with only a GradientDrawable
        // resolves to nothing, so the drawable expands and floods the parent. That is
        // exactly how a 7dp status dot once rendered as a full-screen green disc.
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(6);
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        navPill.addView(dot(cDotOk(), 7), dotLp);
        statusPillText = text("—", 12, Typeface.BOLD, cTxP());
        navPill.addView(statusPillText);
        titleRow.addView(navPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(titleRow);

        // 副标题一行：作用域摘要（过长时省略，详情进弹层）
        statusView = text("", 12, Typeface.NORMAL, cTxS());
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        card.addView(statusView, slp);

        // 指标条：三个等宽小格子（版本 / 作用域 / 广告 SDK）
        metricRow = new LinearLayout(this);
        metricRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(14);
        card.addView(metricRow, mlp);
        buildMetrics();

        host.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 版本提示行（放在卡片下方，单行、可点）
        verLine = text("", 12, Typeface.BOLD, cTxS());
        verLine.setPadding(dp(6), dp(10), dp(6), 0);
        verLine.setSingleLine(true);
        verLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        verLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "正在检查更新…", Toast.LENGTH_SHORT).show();
                UpdateChecker.check(MainActivity.this, true, updateCb);
            }
        });
        host.addView(verLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adLine = text("", 11, Typeface.NORMAL, cTxS());
        adLine.setLineSpacing(0, 1.2f);
        adLine.setPadding(dp(6), dp(4), dp(6), dp(2));
        adLine.setVisibility(View.GONE);        // 详细适配信息收进弹层，不再撑高首页
        host.addView(adLine);

        updateBtn = text("检查版本更新", 13, Typeface.BOLD, 0xFFFFFFFF);
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        updateBtn.setBackground(UiKit.ripple(cRipple(), UiKit.gradient(
                cAccentTop(), cAccentBottom(), dp(16)), dp(16)));
        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (UpdateChecker.isOutdated(MainActivity.this)) {
                    showUpdateDialog(UpdateChecker.latest(MainActivity.this));
                    return;
                }
                Toast.makeText(MainActivity.this, "正在检查更新…", Toast.LENGTH_SHORT).show();
                UpdateChecker.check(MainActivity.this, true, updateCb);
            }
        });
        UiKit.pressable(updateBtn, 0.97f);
        host.addView(updateBtn, blp);

        renderVersionLines();
    }

    private TextView statusPillText;

    /** 三格实时指标（等宽，各自可点） */
    private void buildMetrics() {
        if (metricRow == null) return;
        metricRow.removeAllViews();
        metricRow.addView(metric("版本", versionMetric, new Runnable() {
            @Override public void run() { verLine.performClick(); }
        }), metricLp(0));
        metricRow.addView(metric("作用域", scopeMetric, new Runnable() {
            @Override public void run() { showStatusDetail(); }
        }), metricLp(1));
        metricRow.addView(metric("广告 SDK", sdkMetric, new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        }), metricLp(2));
    }

    private LinearLayout.LayoutParams metricLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (index > 0) lp.leftMargin = dp(8);
        return lp;
    }

    private String versionMetric = "—";
    private String scopeMetric = "—";
    private String sdkMetric = "—";
    /** 作用域那一格的配色（绿=已勾选 / 红=未勾选 / 灰=读不到）。 */
    private int scopeMetricColor = 0xFF7A8290;
    /** 已捕获 SDK 数量的短缓存：心跳里不必每次都重建 + 排序整张清单。 */
    private int sdkCacheCount = -1;
    private long sdkCacheAt;

    /** 单格指标：值 + 标签，两行，玻璃底 */
    private View metric(String label, String value, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11), dp(9), dp(11), dp(9));
        box.setBackground(UiKit.ripple(cRipple(), UiKit.plate(metricBg(), dp(13)), dp(13)));
        box.setContentDescription(label + " " + value);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (action != null) action.run(); }
        });
        UiKit.pressable(box, 0.96f);
        TextView v = text(value, 14, Typeface.BOLD, cTxP());
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(v);
        TextView l = text(label, 10, Typeface.NORMAL, cTxS());
        l.setPadding(0, dp(2), 0, 0);
        box.addView(l);
        box.setTag(v);
        return box;
    }

    /** 刷新三格指标文案 + 配色 */
    private void refreshMetrics() {
        if (metricRow == null) return;
        versionMetric = "v" + Version.LABEL;
        int sdk = sdkCount();
        sdkMetric = String.valueOf(sdk);
        for (int i = 0; i < metricRow.getChildCount(); i++) {
            View box = metricRow.getChildAt(i);
            Object tag = box.getTag();
            if (!(tag instanceof TextView)) continue;
            TextView tv = (TextView) tag;
            String value = i == 0 ? versionMetric : i == 1 ? scopeMetric : sdkMetric;
            tv.setTextSize(value.length() > 9 ? 11.5f : 14f);
            // 三格各自配色：版本=强调色、作用域=绿/红/灰、SDK=有捕获才点亮。
            int color = i == 0 ? cAccent()
                    : i == 1 ? scopeMetricColor
                    : (sdk > 0 ? cAccent() : cTxS());
            if (!value.contentEquals(tv.getText())) tv.setText(value);
            tv.setTextColor(color);
            box.setContentDescription((i == 0 ? "版本 " : i == 1 ? "作用域 " : "广告 SDK ") + value);
        }
    }

    /** SDK 计数（1.5s 短缓存）：learnedListForApp() 会走服务读 + 排序，不该每拍都跑。 */
    private int sdkCount() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sdkCacheCount >= 0 && now - sdkCacheAt < 1500L) return sdkCacheCount;
        int n = 0;
        try { n = SdkAutoBlock.learnedListForApp().size(); } catch (Throwable ignored) {}
        sdkCacheCount = n;
        sdkCacheAt = now;
        return n;
    }

    private int pillBg()  { return dark ? 0x1FFFFFFF : 0x14FFFFFF; }
    private int metricBg(){ return dark ? 0x14FFFFFF : 0x0D000000; }
    private int cAccentTop()    { return dark ? 0xFF4C8DFF : 0xFF3D82FF; }
    private int cAccentBottom() { return dark ? 0xFF2563EB : 0xFF0A5CE0; }

    /** 小圆点指示器（尺寸由调用方的 LayoutParams 决定，避免 WRAP_CONTENT 无限拉伸） */
    private View dot(int color, int sizeDp) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        return v;
    }



    /** 去广告卡片：始终开启、不折叠，作为页面第一块「仪表盘」 */
    private void buildForcedCard(LinearLayout root) {
        LinearLayout card = newCard();
        TextView head = text("高德 · 精确协议适配", 13, Typeface.BOLD, cAccent());
        head.setPadding(dp(4), dp(10), 0, dp(4));
        card.addView(head);
        addNoteRow(card, "按功能检测原生入口，不因版本号变化停用整个模块。入口不兼容时仅跳过该项；其余功能继续生效。");
        addNoteRow(card, "高德已移除运行时 dex 检索、文本扫描与坐标重排。下方 SDK 清单用于百度、腾讯地图的原有兼容功能。");
        sdkRow = addActionButton(card, sdkSummary(), new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        });
        root.addView(card, cardLp());
    }

    /**
     * 当前正在构建/正在被搜索的那一页的抽屉组。
     *
     * <p>不是 final：四页各建一次，每次换一块。正因为 {@link #secs} 只指当前页，
     * 搜索时遍历它才不会把另外三页的抽屉一起展开 —— 那会让 pager 里的每一页都被改坏。
     */
    private List<Sec> secs = new ArrayList<Sec>();

    private static final class Sec {
        LinearLayout group;
        LinearLayout card;
        /** 卡片内的头行（标题 + 下拉箭头）。搜索态会藏起来，改用卡片外的纯文字标题。 */
        View head;
        /** 搜索态专用：卡片外的纯文字分组标题（无背景、无卡片）。 */
        TextView label;
        View chev;
        boolean expanded;
    }

    /** 抽屉分组：一张卡片 = 一个分类。头行常显，展开的内容落在同一张卡里。 */
    private Sec newDrawer(String title) {
        final Sec s = new Sec();
        s.expanded = false;
        s.group = new LinearLayout(this);
        s.group.setOrientation(LinearLayout.VERTICAL);
        // 纯色底 + 1 px 描边，零 elevation。
        // 这里刻意不用 GlassDrawable：它的「1 dp 深色外圈 + 0.8 dp 白色内圈」双层 rim
        // 在白底上会被看成一条浮在卡片外面的灰色阴影带（外圈 224 被两侧 255/253 夹住），
        // 再叠一层 skin.shadow() 就是用户报的「怪状阴影」。
        s.group.setBackground(skin.glass(18));
        UiKit.clipRounded(s.group, dp(18));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(58));
        head.setPadding(dp(18), dp(12), dp(14), dp(12));
        head.setBackground(UiKit.rippleBare(cRipple(), dp(18)));
        UiKit.pressable(head, 0.995f);

        head.addView(skin.rowTitle(title),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        s.chev = new Icon(this, Icon.CHEVRON, cTxS());
        head.addView(s.chev, new LinearLayout.LayoutParams(dp(18), dp(18)));

        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(s); }
        });
        s.head = head;
        s.group.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 搜索态的分组标题：卡片外的纯文字，与抽屉头行互斥显示（平时 GONE）。
        s.label = skin.section(title);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(14);
        llp.leftMargin = dp(4);
        s.label.setVisibility(View.GONE);
        list.addView(s.label, llp);

        s.card = new LinearLayout(this);
        s.card.setOrientation(LinearLayout.VERTICAL);
        s.card.setPadding(dp(6), dp(4), dp(6), dp(10));
        s.card.setVisibility(View.GONE);
        s.group.addView(s.card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        list.addView(s.group, glp);
        secs.add(s);
        return s;
    }

    /**
     * Expand / collapse a drawer.
     *
     * The old build toggled visibility and faded a fixed-height card, so the rows below
     * teleported. Here the card's measured height is animated from 0 to its real size
     * while alpha and a small drift ride along, so the list opens like a drawer instead
     * of snapping. The chevron rotates with a spring so the tap feels acknowledged.
     */
    private void toggle(final Sec s) {
        final boolean expand = !s.expanded;
        s.expanded = expand;
        // 抽屉高度本身就是逐帧动画；再叠一层 LayoutTransition.CHANGING 会在同一帧里
        // 把整列重新测量两遍（实测展开一次刷了 1300+ 帧）。动画期间先把它摘掉，
        // 结束后再装回去，保证其它布局变化仍然有平滑过渡。
        final ViewGroup host = s.group.getParent() instanceof ViewGroup
                ? (ViewGroup) s.group.getParent() : null;
        final android.animation.LayoutTransition saved =
                host == null ? null : host.getLayoutTransition();
        if (host != null && saved != null) host.setLayoutTransition(null);
        final Runnable restore = new Runnable() {
            @Override public void run() {
                if (host != null && saved != null) host.setLayoutTransition(saved);
            }
        };
        s.chev.animate().cancel();
        s.chev.animate().rotation(expand ? 90f : 0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start();

        if (expand) {
            s.card.setVisibility(View.VISIBLE);
            s.card.getLayoutParams().height = 0;
            s.card.setAlpha(0f);
            s.card.requestLayout();
            s.card.post(new Runnable() {
                @Override public void run() {
                    final int target = measureCard(s.card);
                    if (target <= 0) { revealNow(s); restore.run(); return; }
                    UiKit.animateInt(0, target, UiKit.DECELERATE, UiKit.DUR_SLOW,
                            new android.animation.ValueAnimator.AnimatorUpdateListener() {
                                @Override public void onAnimationUpdate(
                                        android.animation.ValueAnimator a) {
                                    if (!s.expanded) return;
                                    int h = (Integer) a.getAnimatedValue();
                                    s.card.getLayoutParams().height = h;
                                    s.card.requestLayout();
                                    s.card.setAlpha(Math.min(1f, h / (float) target));
                                }
                            });
                    s.card.postDelayed(restore, UiKit.DUR_SLOW + 60L);
                }
            });
        } else {
            final int from = s.card.getHeight();
            UiKit.animateInt(from, 0, UiKit.ACCELERATE, UiKit.DUR_BASE,
                    new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            if (s.expanded) return;
                            int h = (Integer) a.getAnimatedValue();
                            s.card.getLayoutParams().height = h;
                            s.card.requestLayout();
                            s.card.setAlpha(from <= 0 ? 0f : Math.min(1f, h / (float) from));
                        }
                    });
            s.card.postDelayed(new Runnable() {
                @Override public void run() {
                    if (s.expanded) return;
                    s.card.setVisibility(View.GONE);
                    s.card.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    s.card.requestLayout();
                }
            }, UiKit.DUR_BASE + 20L);
            s.card.postDelayed(restore, UiKit.DUR_BASE + 60L);
        }
    }

    private void revealNow(Sec s) {
        s.card.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        s.card.requestLayout();
        s.card.setAlpha(0f);
        s.card.animate().alpha(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.DECELERATE).start();
    }

    /** Measure a card at wrap_content without disturbing its current layout. */
    private int measureCard(View card) {
        try {
            int width = card.getWidth();
            if (width <= 0) width = overlay == null ? 0 : overlay.getWidth() - dp(32);
            if (width <= 0) return 0;
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            return card.getMeasuredHeight();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void buildDrawers() {
        Sec sec;

        sec = newDrawer("高德 · 主页标签栏");
        for (String tab : Config.TABS) addSwitch(sec.card, "显示标签「" + tab + "」", Config.K_TAB_PREFIX + tab);

        sec = newDrawer("高德 · 地图标注");
        addNoteRow(sec.card, "统一控制地图上的文字标注：兴趣点图标、店名、道路名与城市名。"
                + "路网、水域与地形照常显示。修改后彻底关闭并重新打开高德生效。");
        addSwitch(sec.card, "显示地图标注", Config.K_MAP_POI);

        sec = newDrawer("高德 · 首页工具宫格");
        addNoteRow(sec.card, "按固定工具 ID 控制显示。关闭全部首页推荐时采用原生工具数据构建等宽宫格，自动补位；图标与点击动作同步保留。");
        addSwitch(sec.card, "显示首页工具宫格", Config.K_TOOLS_VISIBLE);
        addSwitch(sec.card, "自动进入巡航（模块接管）", Config.K_CRUISE_AUTO);
        addCruiseSlider(sec.card, "巡航触发速度下限", Config.K_CRUISE_KMH,
                Config.CRUISE_KMH_MIN, Config.CRUISE_KMH_MAX, Config.CRUISE_KMH_DEFAULT, " km/h");
        addCruiseSlider(sec.card, "巡航触发持续时间", Config.K_CRUISE_SEC,
                Config.CRUISE_SEC_MIN, Config.CRUISE_SEC_MAX, Config.CRUISE_SEC_DEFAULT, " 秒");
        addNoteRow(sec.card, "上面两个值决定「自动进入巡航」何时触发：速度持续超过下限、并保持够久才进入。"
                + "默认 18 km/h / 15 秒；想更灵敏就一起调低（高德官方云控是 15 km/h / 5 秒）。"
                + "\n\n调这两个值（以及上面那个开关）之前，高德必须先开着 —— 模块 App 和地图是两个进程，"
                + "地图没运行时写入没有通道，会提示「未连接地图」。");
        addSwitch(sec.card, "反转开关：仅显示已打开的工具", Config.K_TOOLS_ALLOWLIST);
        addSwitch(sec.card, "工具图标统一彩绘（实验）", Config.K_TOOL_COLOR_ICON);
        addNoteRow(sec.card, "开启反转后，只保留你明确打开的工具；未设置、新增和未知工具全部隐藏。关闭反转可恢复原有默认值。修改后重开高德生效。");
        addNoteRow(sec.card, "以下目录取自高德「更多工具」，按出行、服务等分类。开启的工具会补入首页；新增推广工具默认关闭。同名工具按 ID 分别控制。");
        addSwitch(sec.card, "显示「更多工具」", Config.K_TOOL_PREFIX + "更多工具");
        addSwitch(sec.card, "显示「高德出行节」", Config.K_TOOL_PREFIX + "高德出行节");
        for (String group : io.github.ldxm666.mapadkiller.AmapToolCatalog.GROUPS) {
            sec = newDrawer("高德 · 工具：" + group);
            for (String[] tool : io.github.ldxm666.mapadkiller.AmapToolCatalog.ENTRIES) {
                if (!group.equals(tool[2])) continue;
                String name = tool[1];
                if ("145".equals(tool[0])) name += "（高德入口）";
                if ("502".equals(tool[0])) name += "（支付宝入口）";
                addSwitch(sec.card, "显示「" + name + "」", io.github.ldxm666.mapadkiller.AmapToolCatalog.key(tool[0]));
            }
        }

        // 搜索页金刚位（搜索框下方那一排彩色入口）。
        // ⚠ 这个开关原先只注册在 AmapPanel，而 AmapPanel 只被 FloatingPanel 使用 ——
        //   悬浮窗**只在免 root 内置版存在**，模块模式（LSPosed）根本没有悬浮窗。
        //   ⇒ 模块用户点不到它。所以必须在模块 App 的配置页也注册一份。
        sec = newDrawer("高德 · 搜索页");
        addSwitch(sec.card, "显示搜索页快捷金刚位（实验，关闭会留白）", Config.K_SEARCH_GRID);
        addNoteRow(sec.card, "搜索框下方那一排彩色入口（美食 / 酒店 / 景点门票 / 加油充电 / 超划算 / 扫街榜 等，随版本变化）。"
                + "该排由高德 AJX 页面在运行时绘制，服务端数据、本地缓存、页面预载包三处都试过剔除，"
                + "但页面总会回落到内置默认项（等于换另一批广告），故只能在显示层隐藏。"
                + "代价：进入搜索页会先闪现约 0.3 秒再消失、每次进入重演一次、原位留一条空白。如实告知。");

        sec = newDrawer("高德 · 实验");
        addSwitch(sec.card, "帧率上限（实验）", Config.K_AMAP_FPS_ON);
        addChoiceRow(sec.card, "目标帧率（实验）", Config.K_AMAP_FPS_TARGET,
                new int[]{60, 90, 120}, 60, " 帧");
        addSwitch(sec.card, "去除建筑物模型（实验）", Config.K_AMAP_BUILDING_ON);
        addNoteRow(sec.card, "默认全关。这两项动的是高德自己的地图渲染，不是广告也不是界面，"
                + "判错就是地图错乱，所以缺省什么都不做。");
        addNoteRow(sec.card, "⚠ 两项都是安装期门控：改完必须强停并重开高德才生效。"
                + "「去除建筑物模型」是原生层补丁，一旦打上是进程级全局的 —— "
                + "之后把开关关掉也要重启高德，楼块才会回来。");
        addNoteRow(sec.card, "帧率上限是「限速」不是「提速」：高德本身跑不满时不会拉高。"
                + "60 与 120 和屏幕刷新率整除，是精确值；90 需要屏幕本身支持 90Hz 输出，"
                + "否则会落在最近的整数分频上（120Hz 屏通常约 80）。"
                + "可在 logcat 搜 event=fps_sample 看实时帧数。");
        addNoteRow(sec.card, "去除建筑物：导航 / 巡航态不再绘制 3D 与扁平楼块，只剩路网、绿地、"
                + "地名与兴趣点。可在 logcat 搜 event=building_patch 确认补丁落点。");

        sec = newDrawer("高德 · 首页推荐内容");
        addSwitch(sec.card, "扫街榜 / 周边精选榜单（首页及分页）", Config.K_FEED_BOARD);
        addSwitch(sec.card, "首页出行节 / 促销活动接口", Config.K_FEED_FESTIVAL);
        addSwitch(sec.card, "地图右侧运营挂件（扫街榜 / 活动入口）", Config.K_MAP_PROMO);
        addSwitch(sec.card, "天气卡片", Config.K_FEED_WEATHER);
        addSwitch(sec.card, "攻略 / 内容流卡片", Config.K_FEED_CONTENT);
        addSwitch(sec.card, "设置家 / 设置单位 / 常去地点", Config.K_HOME_CHIPS);
        addSwitch(sec.card, "关闭首页搜索框热词轮播（文字流动 / 运营广告词）",
                Config.K_HOTWORD_OFF);
        addNoteRow(sec.card, "首页那支搜索框里的文字会自己流动（预置词/运营词，有时是广告）。"
                + "打开本开关后文字被钉死成系统默认提示词「查找地点、公交、地铁」，"
                + "热词品牌图标一并摘掉；关掉开关即可恢复原样。");
        addNoteRow(sec.card, "适配高德 17.00.0.2005。旧版按文字区分的景区帖、距离帖等开关已合并为榜单和内容流。尚无精确入口的频道栏、快捷入口排不再提供开关。");

        sec = newDrawer("高德 · 地点详情");
        addSwitch(sec.card, "显示「发现好去处」照片推荐", Config.K_PLACE_RECOMMEND);
        addNoteRow(sec.card, "关闭后移除长按选点详情里的推荐标题和照片信息流，刷新和分页同样生效。保留地址、逛逛周边工具、收藏、分享、新增和路线按钮。重新打开地点详情生效。");

        sec = newDrawer("高德 · 「我的」页");
        addSwitch(sec.card, "订单 / 收藏 / 待评价 一栏", Config.K_MY_ORDER_ROW);
        addSwitch(sec.card, "车辆服务 / 高德运动 一栏", Config.K_MY_SERVICE_ROW);
        addSwitch(sec.card, "达人任务卡片", Config.K_MY_TASK);
        addSwitch(sec.card, "热门活动卡", Config.K_MY_PROMO_ROW);
        addSwitch(sec.card, "猜你喜欢", Config.K_MY_GUESS);
        addSwitch(sec.card, "好友动态", Config.K_MY_FRIENDS);
        addNoteRow(sec.card, "按 Mine 卡片类型与入口行数据处理。搜索页运营卡、资质页及其他未完成协议适配的区域暂不提供开关。");
        // 「其他 · 入口与调试」一组已挪到第 3 页（其他），见 buildOtherPage()

        // ── 高德 · 省电 / 后台（v2.2.2 新增）──────────────────────────────────────
        // 这批键以前只注册进了悬浮面板，模块版设置页一条都没有 —— 用户因此说「模块里没有
        // 省电开关」。label 一律写成「关闭…」：这些键的 true 表示「该行为已被拦截」，
        // 而 readState() 走 Config.defaultVisible 兜底 true ⇒ 默认显示为开。
        //
        // 刻意**不注册** labels_on：它是"启用第三方标注过滤"（反义，消费方用
        // Config.selected 严格读、默认 false），混进这一组会让「开 = 已拦截」的读法出错。
        sec = newDrawer("高德 · 省电 / 后台");
        addNoteRow(sec.card, "开 = 该行为已被拦截（默认全开）；关 = 恢复高德原生行为。"
                + "关掉推送长连接后，行程提醒 / 消息 / 家人地图会恢复，但 ACCS 长连接会重新占用 CPU。");
        addSwitch(sec.card, "关闭推送长连接（ACCS，省电最多）", Config.K_PUSH_HARD_OFF);
        addNoteRow(sec.card, "关掉后高德推送（行程提醒 / 活动 / 消息 / 家人地图）会恢复，"
                + "但 ACCS 长连接会重新占用 CPU。");
        addSwitch(sec.card, "关闭推送消息处理", Config.K_PUSH_OFF);
        addSwitch(sec.card, "关闭推送服务壳（激进）", Config.K_PUSH_KILL_OFF);
        addSwitch(sec.card, "关闭周期任务（WorkManager / JobScheduler）", Config.K_SUBPROC_JOB_OFF);
        addNoteRow(sec.card, "关掉后高德的周期后台任务会恢复（更耗电）。");
        addSwitch(sec.card, "关闭桌面小组件刷新", Config.K_SUBPROC_WIDGET_OFF);
        addSwitch(sec.card, "关闭云控调度池（LotusPool）", Config.K_SUBPROC_LOTUSPOOL_OFF);
        addSwitch(sec.card, "关闭阿里埋点 Analytics", Config.K_TRACK_OFF);
        addSwitch(sec.card, "关闭开屏广告请求", Config.K_ALIMAMA_SPLASH_OFF);
        addSwitch(sec.card, "关闭广告曝光上报", Config.K_ALIMAMA_LOG_OFF);
        addSwitch(sec.card, "关闭子进程拦截（总闸，排查用）", Config.K_SUBPROC_OFF);
        addNoteRow(sec.card, "一次性放行所有子进程拦截；怀疑某功能被误伤时先关这个。");
    }

    // ══════════════════════════════════════════════ v2.0.0 分页（百度 / 高德 / 其他）

    private ScrollView newPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        pages.add(sv);
        return sv;
    }

    private LinearLayout pageRoot(ScrollView sv) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(4), p, dp(24));
        sv.addView(root);
        return root;
    }

    /** 抽屉挂载点：CHANGING 过渡让折叠/展开时后续卡片平滑上移/下移 */
    private LinearLayout newMount() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.setDuration(190);
        m.setLayoutTransition(lt);
        return m;
    }

    /** 页 0：百度地图 —— 分类来自 io.github.ldxm666.bmapclean.Spec（开关写 bmapclean_config） */
    private ScrollView buildBaiduPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        final List<Sec> pageSecs = new ArrayList<Sec>();
        secs = pageSecs;
        list = mount;
        expandSnapshot.clear();
        registerPageTarget(PAGE_BAIDU, pageSecs);
        try {
            io.github.ldxm666.bmapclean.Spec.Cat[] cats = io.github.ldxm666.bmapclean.Spec.cats();
            for (int i = 0; i < cats.length; i++) {
                final io.github.ldxm666.bmapclean.Spec.Cat cat = cats[i];
                Sec sec = newDrawer(cat.title);
                if (cat.subtitle != null && cat.subtitle.length() > 0) addNoteRow(sec.card, cat.subtitle);
                for (int j = 0; j < cat.rows.length; j++) {
                    io.github.ldxm666.bmapclean.Spec.Row row = cat.rows[j];
                    if (row.key == null) addNoteRow(sec.card, row.title);
                    else addBmapSwitch(sec.card, row.title, row.key, row.note);
                }
                // 全部默认收起：与高德页统一（v2.2.5，用户要求「不要固定展开」）
            }
            // ── 百度 · 省电 / 后台（v2.2.3 新增）──────────────────────────────
            // 与高德那组对齐：键都是「true = 该行为已被拦截」，所以 label 一律写「关闭…」。
            // 开关写 bmapclean_config（addBmapSwitch），由 bmapclean.BaiduPower 在安装期读取：
            // 关掉的档位**一条 hook 都不装**（零开销），代价是改完要强停重开百度地图。
            Sec power = newDrawer("百度 · 省电 / 后台");
            addNoteRow(power.card, "开 = 该行为已被拦截（默认全开）；关 = 恢复百度原生行为。"
                    + "改动后需**强制停止并重开百度地图**才生效。");
            addBmapSwitch(power.card, "关闭推送长连接（硬档，省电最多）",
                    io.github.ldxm666.bmapclean.Cfg.K_PUSH_HARD_OFF,
                    "关掉后百度推送（活动 / 提醒）会恢复，但推送连接与周期闹钟会重新占用 CPU。");
            addBmapSwitch(power.card, "关闭推送消息处理",
                    io.github.ldxm666.bmapclean.Cfg.K_PUSH_OFF,
                    "不建连接、不处理推送消息，但不杀服务。");
            addBmapSwitch(power.card, "关闭推送服务（激进档）",
                    io.github.ldxm666.bmapclean.Cfg.K_PUSH_KILL_OFF,
                    "通道服务起来即退出。若发现耗电反而上升，先关这一项。");
            addBmapSwitch(power.card, "关闭后台常驻服务",
                    io.github.ldxm666.bmapclean.Cfg.K_BG_SERVICE_OFF,
                    "定位 / 云控 / 统计等自启动服务不再常驻。");
            addBmapSwitch(power.card, "关闭后台周期任务",
                    io.github.ldxm666.bmapclean.Cfg.K_TIMER_OFF,
                    "AlarmManager / JobScheduler / WorkManager 的定时唤醒不再重排。");
            addBmapSwitch(power.card, "关闭桌面小组件刷新",
                    io.github.ldxm666.bmapclean.Cfg.K_WIDGET_OFF,
                    "只拦 APPWIDGET_UPDATE，添加 / 删除组件仍正常。");
            addBmapSwitch(power.card, "关闭第三方埋点",
                    io.github.ldxm666.bmapclean.Cfg.K_TRACK_OFF,
                    "统计 / 广告 SDK 不初始化，不上报。");
        } finally {
            list = old;
        }
        addVersionFooter(root, "首页开关：强停百度地图后重开即可生效；"
                + "「我的」页开关：关掉后重开该页即生效（数据层/JS 层在页面加载时读配置）");
        TextView reset = skin.text("全部恢复显示（清空百度侧配置）", 12f, Skin.REGULAR, cDotBad());
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, dp(10), 0, dp(6));
        reset.setBackground(UiKit.rippleBare(cRipple(), dp(12)));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = false;
                try { ok = io.github.ldxm666.bmapclean.App.resetAll(); } catch (Throwable ignored) {}
                Toast.makeText(MainActivity.this,
                        ok ? "已恢复默认，重开百度地图生效" : "LSPosed 服务未连接",
                        Toast.LENGTH_SHORT).show();
                if (ok) syncSwitches();
            }
        });
        root.addView(reset, skin.matchWrap());
        return sv;
    }

    /** 页 1：高德地图 —— 沿用 MapAdKiller 的抽屉 */
    private ScrollView buildAmapPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        final List<Sec> pageSecs = new ArrayList<Sec>();
        secs = pageSecs;
        list = mount;
        registerPageTarget(PAGE_AMAP, pageSecs);
        try {
            buildDrawers();
        } finally {
            list = old;
        }
        addVersionFooter(root, "改动后请强停对应地图 App 并重新打开以生效；开关可随时单独开/关");
        return sv;
    }

    /**
     * 给一页装上搜索 Target。
     *
     * <p>在页面构建<b>之前</b>调用：{@link SwitchFinder#register} 会重建该页索引，
     * 而索引是由开关工厂在随后的建行过程中一条条填进去的。
     *
     * <p>{@code pageSecs} 必须显式传进来：四页共用同一个 Activity，搜索真正发生时
     * {@link #secs} 早已指向最后构建的那一页。捕获当页自己的那份列表，
     * 搜索才碰不到别的页的抽屉。
     */
    private void registerPageTarget(final int page, final List<Sec> pageSecs) {
        if (finder == null) return;
        finder.register(page, new SwitchFinder.Target() {
            @Override public boolean applyQuery(String query) {
                List<Sec> saved = secs;
                secs = pageSecs;
                try {
                    boolean any = applyQueryOnPage(page, query);
                    applySearch(true);
                    return any;
                } finally {
                    secs = saved;
                }
            }
            @Override public void resetQuery() {
                List<Sec> saved = secs;
                secs = pageSecs;
                try {
                    applySearch(false);
                    // 还原之后把每张卡片的行全部放出来，只显示命中项交由下次进入搜索时重算。
                    for (int i = 0; i < secs.size(); i++) {
                        Sec s = secs.get(i);
                        if (s == null || s.card == null) continue;
                        s.group.setVisibility(View.VISIBLE);
                        if (s.head != null) s.head.setVisibility(View.VISIBLE);
                        if (s.label != null) s.label.setVisibility(View.GONE);
                        filterRows(s.card, "");
                    }
                } finally {
                    secs = saved;
                }
            }
        });
    }

    /** 页 2：腾讯地图 —— 分类来自 io.github.ldxm666.tmapclean.Spec（开关写 tmapclean_config） */
    private ScrollView buildTmapPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        final List<Sec> pageSecs = new ArrayList<Sec>();
        secs = pageSecs;
        list = mount;
        registerPageTarget(PAGE_TMAP, pageSecs);
        try {
            // 工具目录由 hook 侧从腾讯地图进程里学回来；进本页时先同步一次。
            io.github.ldxm666.mapclean.TmapPanel.syncLearnedTools();
            io.github.ldxm666.tmapclean.Spec.Cat[] cats = io.github.ldxm666.tmapclean.Spec.cats();
            for (int i = 0; i < cats.length; i++) {
                final io.github.ldxm666.tmapclean.Spec.Cat cat = cats[i];
                Sec sec = newDrawer(cat.title);
                if (cat.subtitle != null && cat.subtitle.length() > 0) addNoteRow(sec.card, cat.subtitle);
                for (int j = 0; j < cat.rows.length; j++) {
                    io.github.ldxm666.tmapclean.Spec.Row row = cat.rows[j];
                    if (row.key == null) addNoteRow(sec.card, row.title);
                    else addTmapSwitch(sec.card, row.title, row.key, row.note);
                }
                // 全部默认收起：与高德页统一（v2.2.5，用户要求「不要固定展开」）
            }
        } finally {
            list = old;
        }
        addVersionFooter(root, "适配腾讯地图 11.7.0。底栏 / 工具宫格 / 设置家在重新进入首页时生效；"
                + "「我的」页与「大家都在看」在重新进入对应页面时生效。"
                + "工具逐项开关需要先打开一次腾讯地图首页（模块会在真实进程里学到工具目录）。");
        return sv;
    }

    /** 页 3：其他 / 调试 */
    private ScrollView buildOtherPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        list = mount;
        try {
            Sec sec = newDrawer("入口与调试");
            // 服务器公告（模块版）：「其他」页的兜底入口。开关走 Config/App.writeBoolean
            // 的同一条路（模块版 remote / 内置版 local 已分流），不另写 SharedPreferences。
            addSwitch(sec.card, "接收服务器公告", Announce.K_ON);
            addActionButton(sec.card, "最新公告", new Runnable() {
                @Override public void run() {
                    Announce.showLatest(MainActivity.this);
                }
            });
            if (embedded()) {
                addNoteRow(sec.card, "当前整合版设置仅控制本地图，配置保存在本地图的嵌入式模块服务中。修改后彻底退出并重开地图生效。");
                addNoteRow(sec.card, "返回可继续使用地图。更新整合版时，请下载对应地图的新版安装包。");
                if (embeddedAmap()) addSwitch(sec.card, "调试日志", Config.K_DEBUG_LOG);
                else addNoteRow(sec.card, "调试日志开关位于百度地图页的「诊断」分组。");
            } else {
                addLocalSwitch(sec.card, "隐藏桌面图标", App.K_HIDE_ICON);
                addNoteRow(sec.card, "隐藏后桌面图标消失；LSPosed 管理器里的「打开」入口不受影响（v1.1.0 起），"
                        + "快捷设置磁贴与 adb 命令也始终可用。");
                addNoteRow(sec.card, "高德工具列表隐藏后自动补位；旧版实验性坐标重排已移除。");
                addSwitch(sec.card, "调试日志（框架输出协议适配错误）", Config.K_DEBUG_LOG);
            }
            addActionButton(sec.card, "抓取前 10 分钟（立即生成 txt）", new Runnable() {
                @Override public void run() {
                    toast("正在导出最近十分钟的数据…");
                    String path = null;
                    try {
                        path = CaptureLog.dumpRecent(MainActivity.this);
                    } catch (Throwable t) {
                        android.util.Log.w("MapCleanEmbedded", "event=capture_dump_fail err="
                                + t.getClass().getName());
                    }
                    toast(path != null ? "已保存：" + path : "导出失败：系统拒绝写文件，见 logcat");
                }
            });
            addActionButton(sec.card, CaptureLog.running()
                    ? "正在抓取最近十分钟的数据…（点此结束）" : "抓取后 10 分钟（从现在开始录）",
                    new Runnable() {
                @Override public void run() {
                    boolean started = CaptureLog.toggleLive(MainActivity.this);
                    toast(started ? "正在抓取最近十分钟的数据…" : "已结束抓包，txt 已生成");
                }
            });
            addNoteRow(sec.card, "「前 10 分钟」立刻导出已经发生的事（只含这次打开高德以后的），"
                    + "「后 10 分钟」从这一刻开始录。"
                    + "两份 txt 都存到「内部存储/下载/MapClean/」，文件管理器点「下载」就能看到；"
                    + "系统不给写时自动落到 Android/data/包名/files/MapClean/。");
            toggle(sec);
            Sec sec2 = newDrawer("去广告说明");
            addNoteRow(sec2.card, "高德使用版本专用协议适配；推荐内容由高德页的开关控制。百度、腾讯保留原有广告兼容规则和 SDK 清单。");
        } finally {
            list = old;
        }
        addVersionFooter(root, embedded() ? "MapAdKiller v" + Version.LABEL + " · 免 root 整合版"
                : "MapAdKiller v" + Version.LABEL + " · 高德 / 百度地图精简");
        return sv;
    }

    private void addTab(String label) {
        if (tabBar == null) return;
        final TextView chip = text(label, 13, Typeface.BOLD, cTxS());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(10), dp(10), dp(10));
        chip.setBackground(UiKit.rippleBare(cRipple(), dp(14)));
        chip.setClickable(true);
        UiKit.pressable(chip, 0.95f);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = tabChips.indexOf(v);
                if (i >= 0) { pager.setPage(i, true); selectTab(i); }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        tabChips.add(chip);
        tabBar.addView(chip, lp);
    }

    private void selectTab(int index) {
        if (tabBar == null) return;
        // 每页可搜的范围不同（“其他”页几乎没有），切页时同步决定搜索条去留。
        if (finder != null) {
            finder.dismissKeyboard();
            finder.attach(index);
        }
        for (int i = 0; i < tabChips.size(); i++) {
            final TextView c = tabChips.get(i);
            final boolean on = (i == index);
            // 段控样式：选中=染色胶囊 + 强调色文字；未选中=主文字色 + 0.72 不透明。
            // 之前未选中用 0.62 + 灰色文字，小屏上糊成一片“全是灰按钮”。
            c.setTextColor(on ? cAccent() : cTxP());
            c.setTypeface(Typeface.DEFAULT_BOLD, on ? Typeface.BOLD : Typeface.NORMAL);
            c.animate().cancel();
            c.animate().alpha(on ? 1f : 0.72f)
                    .setDuration(UiKit.DUR_BASE)
                    .setInterpolator(UiKit.SPRING).start();
            c.setBackground(on
                    ? UiKit.ripple(cRipple(), UiKit.plate(tabOnBg(), dp(14)), dp(14))
                    : UiKit.rippleBare(cRipple(), dp(14)));
        }
    }

    private int tabOnBg() { return dark ? 0x2E7AB1FF : 0x1F0A6CF5; }

    // ── 搜索：每页的查询落地 ──────────────────────────────────────────────

    /**
     * 进入/退出搜索时统一展开或还原抽屉，并把不相关的开关行收起来。
     *
     * <p>为什么必须展开：开关都放在默认折叠的抽屉里，用户搜“步行”却看到
     * “高德 · 首页推荐内容”的折叠头，等于什么都没发生。
     * 退出搜索时按 {@link #expandSnapshot} 还原，不让一次搜索永久改变用户的展开偏好。
     *
     * @param searching true = 展开全部 + 只留命中；false = 还原
     */
    private void applySearch(boolean searching) {
        for (int i = 0; i < secs.size(); i++) {
            Sec s = secs.get(i);
            if (s == null || s.card == null) continue;
            if (searching) {
                if (!expandSnapshot.containsKey(s)) expandSnapshot.put(s, s.expanded);
                if (!s.expanded) expand(s);
            } else {
                Boolean was = expandSnapshot.get(s);
                if (was != null && was.booleanValue() != s.expanded) {
                    if (was.booleanValue()) expand(s);
                    else collapse(s);
                }
            }
            if (!searching) expandSnapshot.remove(s);
        }
    }

    private void expand(Sec s) {
        if (s == null || s.expanded || s.card == null) return;
        s.expanded = true;
        s.card.setVisibility(View.VISIBLE);
        s.card.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        s.card.setAlpha(0f);
        s.card.requestLayout();
        s.card.animate().alpha(1f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.DECELERATE).start();
        if (s.chev != null) s.chev.animate().cancel();
        if (s.chev != null) s.chev.animate().rotation(90f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start();
    }

    private void collapse(Sec s) {
        if (s == null || !s.expanded || s.card == null) return;
        s.expanded = false;
        if (s.chev != null) s.chev.animate().cancel();
        if (s.chev != null) s.chev.animate().rotation(0f)
                .setDuration(UiKit.DUR_BASE).setInterpolator(UiKit.SPRING).start();
        s.card.animate().alpha(0f).setDuration(UiKit.DUR_BASE)
                .setInterpolator(UiKit.ACCELERATE)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        s.card.setVisibility(View.GONE);
                    }
                }).start();
    }

    /**
     * 一页的查询实现：把该页所有抽屉里的开关行按命中收起/显示。
     *
     * <p>行的可见性靠“标题 + 备注 + 配置键”三段匹配（{@link SwitchFinder.Entry#haystack}），
     * 所以「步行」「walk」「tool_walk」敲法都命中同一个开关。
     */
    private boolean applyQueryOnPage(int page, String query) {
        boolean any = false;
        for (int i = 0; i < secs.size(); i++) {
            Sec s = secs.get(i);
            if (s == null || s.card == null) continue;
            int shown = filterRows(s.card, query);
            // 抽屉一个命中都没有时整组收起，避免留一堆空壳标题。
            if (shown == 0) {
                s.group.setVisibility(View.GONE);
                if (s.label != null) s.label.setVisibility(View.GONE);
            } else {
                s.group.setVisibility(View.VISIBLE);
                // 搜索态：分组标题走卡片外的纯文字 section header，卡内不再重复一条头行。
                if (s.head != null) s.head.setVisibility(View.GONE);
                if (s.label != null) s.label.setVisibility(View.VISIBLE);
                any = true;
            }
        }
        return any;
    }

    /**
     * @return 该卡片里被显示出来的开关行数
     *
     * <p>分割线必须跟着行一起藏。旧实现只藏行、不藏线：一个 20 行的工具抽屉搜出 2 行，
     * 中间就留下十几条 1 px 的 {@code divider} 叠成一条 40+ px 的灰色色带 —— 这正是用户
     * 反馈的「行与行之间有诡异的阴影/色带」。
     */
    private int filterRows(android.view.ViewGroup host, String query) {
        final int n = host.getChildCount();
        final boolean searching = query.length() > 0;
        final boolean[] vis = new boolean[n];
        int shown = 0;

        // 1) 开关行按 haystack 命中显隐；说明行 / 按钮没有 haystack，搜索态一并收起。
        for (int i = 0; i < n; i++) {
            View child = host.getChildAt(i);
            Object tag = child instanceof android.view.ViewGroup
                    ? ((android.view.ViewGroup) child).getTag(TAG_HAYSTACK) : null;
            if (tag instanceof String) {
                boolean hit = !searching
                        || ((String) tag).toLowerCase(java.util.Locale.ROOT).contains(query);
                child.setVisibility(hit ? View.VISIBLE : View.GONE);
                vis[i] = hit;
                if (hit) shown++;
            } else {
                child.setVisibility(searching ? View.GONE : View.VISIBLE);
                vis[i] = !searching;
            }
        }

        // 2) 1 px 分割线只在「前后两行都可见」时出现。
        if (searching) {
            for (int i = 1; i < n - 1; i++) {
                View child = host.getChildAt(i);
                if (child instanceof android.view.ViewGroup) continue;
                if (child instanceof TextView) continue;
                if (vis[i - 1] && vis[i + 1]) child.setVisibility(View.VISIBLE);
            }
        }
        return shown;
    }

    /** 百度那一页的信息卡：模块状态 + 最近一次规则执行 + 一键恢复 */
    private void addBmapInfoCard(LinearLayout root) {
        LinearLayout card = newCard();
        LinearLayout.LayoutParams clp = cardLp();
        root.addView(card, clp);

        TextView head = text("百度地图 · 界面精简", 15, Typeface.BOLD, cTxP());
        head.setPadding(0, dp(2), 0, dp(8));
        card.addView(head);

        // 「最近一次规则执行 / 累计回报」那一大片诊断文字已按 LO 要求移除（2026-10-14）：
        // 用户要的是开关，不是 hook 计数。诊断仍可通过 logcat 看（TAG=BMapClean）。
        TextView reset = text("全部恢复显示（清空百度侧配置）", 13, Typeface.BOLD, cDotBad());
        reset.setPadding(0, dp(14), 0, dp(4));
        reset.setBackground(ripple(capsule(0x12FF6B70), HEAD_R));
        reset.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = false;
                try { ok = io.github.ldxm666.bmapclean.App.resetAll(); } catch (Throwable ignored) {}
                Toast.makeText(MainActivity.this,
                        ok ? "已恢复默认，重开百度地图生效" : "LSPosed 服务未连接",
                        Toast.LENGTH_SHORT).show();
                if (ok) syncSwitches();
            }
        });
        card.addView(reset, rlp);
    }

    /** 百度组的开关行（配置 group 与高德不同，写 bmapclean_config） */
    /** 百度组的开关行（配置 group 与高德不同，写 bmapclean_config） */
    private void addBmapSwitch(final LinearLayout card, String title, final String key, String note) {
        LinearLayout row = newSwitchRow(card, key, title, note);
        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(11), dp(10), dp(11));
        label.setLineSpacing(0, 1.15f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        boolean def = io.github.ldxm666.bmapclean.Spec.defaultVisible(key);
        try {
            sw.setChecked(io.github.ldxm666.bmapclean.App.read(key, def), false);
        } catch (Throwable t) {
            sw.setChecked(def, false);
        }
        bmapSwitches.put(key, sw);
        index(finder, key, title, note, PAGE_BAIDU);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                boolean ok = false;
                try { ok = io.github.ldxm666.bmapclean.App.write(key, next); } catch (Throwable ignored) {}
                if (!ok) {
                    view.setChecked(!next, true);
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
        if (note != null && note.length() > 0) addNoteRow(card, note);
    }

    /** 腾讯那一页的信息卡：模块状态 + 最近一次回报 + 学到的工具目录条数 */
    private void addTmapInfoCard(LinearLayout root) {
        LinearLayout card = newCard();
        LinearLayout.LayoutParams clp = cardLp();
        root.addView(card, clp);

        TextView head = text("腾讯地图 · 界面精简", 15, Typeface.BOLD, cTxP());
        head.setPadding(0, dp(2), 0, dp(8));
        card.addView(head);

        // 「界面规则执行 / 累计回报 / 已学到 N 个首页工具」那一大片诊断文字已按 LO 要求移除
        // （2026-10-14）：用户要的是开关，不是 hook 计数。诊断仍可通过 logcat 看（TAG=TMapClean）。
    }

    /** 腾讯组的开关行（第三个配置 group：tmapclean_config） */
    private void addTmapSwitch(final LinearLayout card, String title, final String key, String note) {
        LinearLayout row = newSwitchRow(card, key, title, note);
        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(11), dp(10), dp(11));
        label.setLineSpacing(0, 1.15f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        boolean def = io.github.ldxm666.tmapclean.Spec.defaultChecked(
                key, io.github.ldxm666.tmapclean.Spec.APP_READ);
        try {
            sw.setChecked(io.github.ldxm666.tmapclean.App.read(key, def), false);
        } catch (Throwable t) {
            sw.setChecked(def, false);
        }
        tmapSwitches.put(key, sw);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                boolean ok = false;
                try { ok = io.github.ldxm666.tmapclean.App.write(key, next); } catch (Throwable ignored) {}
                if (!ok) {
                    view.setChecked(!next, true);
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
        if (note != null && note.length() > 0) addNoteRow(card, note);
    }

    private void addVersionFooter(LinearLayout root, String tip) {
        TextView t = text(tip, 12, Typeface.NORMAL, cTxS());
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(4), dp(16), 0, dp(2));
        root.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 底部悬浮玻璃操作栏：作为 root 的覆盖层浮在 pager 之上，不占布局流 */
    private void buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(7), dp(7), dp(7), dp(7));
        bottomBar.setBackground(glass(26));
        bottomBar.setElevation(0);
        UiKit.clipRounded(bottomBar, dp(26));

        bottomBar.addView(barChip("SDK 清单", new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        }));
        bottomBar.addView(spacer(dp(6)));
        bottomBar.addView(barChip("配置", new Runnable() {
            @Override public void run() { showConfigManager(); }
        }));
        bottomBar.addView(spacer(dp(6)));
        bottomBar.addView(barChip("联系作者", new Runnable() {
            @Override public void run() { showContact(); }
        }));

        // Root is a FrameLayout wrapper only for the bar overlay; the page column is the
        // first child. Using a dedicated overlay host keeps the bar out of the vertical
        // flow so the pager genuinely fills the remaining space.
        barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.setMargins(dp(14), 0, dp(14), dp(14));
    }

    private View spacer(int w) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, 1);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView barChip(String label, Runnable action) {
        TextView c = text(label, 13, Typeface.BOLD, cAccent());
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        c.setBackground(UiKit.ripple(cRipple(),
                UiKit.plate(dark ? 0x2E7AB1FF : 0x330A6CF5, dp(20)), dp(20)));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        UiKit.pressable(c, 0.93f);
        return c;
    }

    // ---------------------------------------------------------------- 状态区

    /** Render a cached public-service snapshot; framework IPC stays off the UI thread. */
    private void renderStatus() {
        if (statusView == null) return;
        FrameworkStatus.Snapshot state = io.github.ldxm666.mapclean.App.frameworkStatus();
        boolean connected = state.connected;

        String[] targets = {MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP};
        String[] labels = {"高德", "百度", "腾讯"};
        if (embedded()) {
            targets = new String[]{embeddedHost};
            labels = new String[]{embeddedAmap() ? "高德" : "百度"};
        }
        int scoped = 0;
        StringBuilder picked = new StringBuilder();
        if (state.scopeKnown) {
            for (int i = 0; i < targets.length; i++) {
                if (state.scope.contains(targets[i])) {
                    scoped++;
                    if (picked.length() > 0) picked.append(" / ");
                    picked.append(labels[i]);
                }
            }
        }
        boolean scopeOk = scoped > 0;

        String l1 = connected
                ? (state.loaded ? "已连接 · " + state.frameworkLabel() : "已连接 · 正在读取框架信息…")
                : "正在连接框架服务 · 请确认已启用模块";
        if (connected && state.apiVersion > 0 && state.apiVersion < 102) {
            l1 += "（需要 API 102+）";
        }
        String l2;
        if (!connected) l2 = "连接后读取地图作用域";
        else if (!state.loaded) l2 = "正在读取地图作用域…";
        else if (!state.scopeKnown) l2 = "作用域读取失败 · 请在框架管理器确认，稍后重试";
        else if (scopeOk) l2 = "作用域已勾选 · " + picked + "（" + scoped + "/" + targets.length + "）";
        else l2 = "作用域未包含地图 · 请在框架管理器勾选地图应用";

        int scopeColor = !state.scopeKnown ? cTxS() : (scopeOk ? cDotOk() : cDotBad());

        // The hero shows a one-line verdict plus a coloured status pill; the full
        // scope / compatibility dump now lives behind a tap so it cannot wrap and
        // stretch the header the way the old five-row block did.
        int pillColor = connected && scopeOk ? cDotOk() : (connected ? cDotBad() : cTxS());
        scopeMetric = state.scopeKnown ? (scoped + "/" + targets.length) : "未知";
        scopeMetricColor = !state.scopeKnown ? cTxS() : (scopeOk ? cDotOk() : cDotBad());

        // 心跳每几秒跑一次；文案与配色没变就不要再 setText / 重新分配 drawable。
        String sig = connected + "|" + state.loaded + "|" + state.scopeKnown + "|" + scoped
                + "|" + targets.length + "|" + state.frameworkLabel() + "|" + l1 + "|" + l2;
        if (!sig.equals(lastStatusSig)) {
            lastStatusSig = sig;
            if (statusPillText != null) {
                statusPillText.setText(connected ? (scopeOk ? "已就绪" : "待配置") : "连接中");
                statusPillText.setTextColor(pillColor);
            }
            if (navPill != null && navPill.getChildCount() > 0) {
                navPill.getChildAt(0).setBackground(pillDot(pillColor));
            }
            statusView.setText(l1 + "　·　" + l2);
        }
        refreshMetrics();
        refreshDiagRows();
    }

    private String lastStatusSig;
    private int pillDotColor;
    private GradientDrawable pillDotCache;

    /** 缓存 7dp 状态圆点：renderStatus 跑在心跳上，不该每拍都分配一个 drawable。 */
    private GradientDrawable pillDot(int color) {
        if (pillDotCache == null || pillDotColor != color) {
            pillDotColor = color;
            pillDotCache = dotDrawable(color, 7);
        }
        return pillDotCache;
    }

    /** 圆形色点 drawable（用于刷新状态药丸里的圆点） */
    private GradientDrawable dotDrawable(int color, int sizeDp) {
        int s = dp(sizeDp);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(s, s);
        return g;
    }

    /** 状态详情弹层：把 hero 折叠掉的适配 / 作用域信息集中展示 */
    private void showStatusDetail() {
        FrameworkStatus.Snapshot state = io.github.ldxm666.mapclean.App.frameworkStatus();
        StringBuilder sb = new StringBuilder();
        sb.append("框架：").append(state.connected
                ? (state.loaded ? state.frameworkLabel() : "已连接（读取中）") : "未连接");
        if (state.apiVersion > 0) sb.append("　API ").append(state.apiVersion);
        sb.append("\n");
        sb.append("作用域：").append(state.scopeKnown ? joinScope(state) : "读取失败");
        sb.append("\n");
        sb.append("本机版本：v").append(Version.LABEL);
        String latest = UpdateChecker.latest(this);
        if (latest != null && latest.length() > 0) sb.append("　最新：v").append(latest);
        sb.append("\n");
        int sdk = 0;
        try { sdk = SdkAutoBlock.learnedListForApp().size(); } catch (Throwable ignored) {}
        sb.append("已捕获广告 SDK：").append(sdk).append(" 个");
        String ready = adLine == null ? "" : String.valueOf(adLine.getText());
        if (ready.length() > 0) sb.append("\n").append(ready);

        glassDialog("运行状态", sb.toString(),
                "查看 SDK 清单", new Runnable() {
                    @Override public void run() { showLearnedSdks(); }
                },
                null, null,
                "关闭", null);
    }

    private String joinScope(FrameworkStatus.Snapshot state) {
        String[] targets = {MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP};
        String[] labels = {"高德", "百度", "腾讯"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.length; i++) {
            if (!state.scope.contains(targets[i])) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(labels[i]);
        }
        return sb.length() == 0 ? "未包含地图" : sb.toString();
    }

    private final android.os.Handler statusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 绑定前快轮询（等 LSPosed 服务），绑定后降频，避免每 800ms 白跑一遍 UI 刷新。 */
    private static final long POLL_FAST = 400L;
    private static final long POLL_SLOW = 2500L;

    private final Runnable statusRefresher = new Runnable() {
        @Override public void run() {
            io.github.ldxm666.mapclean.App.refreshFrameworkStatus();
            renderStatus();
            // 服务是异步绑定的：绑定前 readState() 只能回退默认 true，
            // 若此时就把开关画成"开"，用户重开设置页会以为配置全丢了。
            // 所以绑定成功后立刻按真实存储重刷一遍开关。
            boolean bound = App.svc() != null;
            if (bound && !synced) {
                synced = true;
                syncSwitches();
            } else if (!bound && synced) {
                synced = false;      // 服务掉了：下次绑定要重新对齐一遍开关
            }
            statusHandler.postDelayed(this, synced ? POLL_SLOW : POLL_FAST);
        }
    };

    private final Map<String, GlassSwitch> switches = new LinkedHashMap<>();
    private volatile boolean synced;

    private void syncSwitches() {
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s == null) return;
            android.content.SharedPreferences p =
                    s.getRemotePreferences(Config.PREF_GROUP);
            // 一次 getAll() 拿整张表：readVisible(prefs,key) 每个键都会复制一份 TreeMap。
            java.util.Map<String, ?> values = p.getAll();
            for (Map.Entry<String, GlassSwitch> e : switches.entrySet()) {
                GlassSwitch sw = e.getValue();
                sw.setChecked(Config.readVisible(values, e.getKey()), false);
            }
            // 百度那一页用的是另一个 group（bmapclean_config）—— 单独再刷一遍
            try {
                android.content.SharedPreferences bp =
                        s.getRemotePreferences(io.github.ldxm666.bmapclean.Cfg.GROUP);
                for (Map.Entry<String, GlassSwitch> e : bmapSwitches.entrySet()) {
                    GlassSwitch sw = e.getValue();
                    sw.setChecked(bp.getBoolean(e.getKey(),
                            io.github.ldxm666.bmapclean.Spec.defaultVisible(e.getKey())), false);
                }
            } catch (Throwable ignored) {}
            // 腾讯那一页同理（tmapclean_config）
            try {
                android.content.SharedPreferences tp =
                        s.getRemotePreferences(io.github.ldxm666.tmapclean.Cfg.GROUP);
                for (Map.Entry<String, GlassSwitch> e : tmapSwitches.entrySet()) {
                    GlassSwitch sw = e.getValue();
                    sw.setChecked(tp.getBoolean(e.getKey(),
                            io.github.ldxm666.tmapclean.Spec.defaultChecked(
                                    e.getKey(), io.github.ldxm666.tmapclean.Spec.APP_READ)), false);
                }
            } catch (Throwable ignored) {}
            refreshSdkRow();
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- 对话框

    // ------------------------------------------------------------ 玻璃对话框

    /** 框架 AlertDialog 套玻璃皮：透明窗口 + 自绘玻璃圆角容器，标题/正文/三键齐全 */
    private AlertDialog glassDialog(String title, String message,
                                    String posText, Runnable posAction,
                                    String neuText, Runnable neuAction,
                                    String negText, Runnable negAction) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(skin.glassFor(box, GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(10));
        TextView tv = text(title, 18, Typeface.BOLD, cTxP());
        box.addView(tv);
        if (message != null && message.length() > 0) {
            TextView mv = text(message, 14, Typeface.NORMAL, cTxS());
            mv.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(10);
            box.addView(mv, mlp);
        }
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        box.addView(btns, blp);
        AlertDialog dlg = new AlertDialog.Builder(this).create();
        Runnable[] dismiss = new Runnable[1];
        java.util.List<String[]> defs = new ArrayList<>();
        if (negText != null) defs.add(new String[]{negText, "neg"});
        if (neuText != null) defs.add(new String[]{neuText, "neu"});
        if (posText != null) defs.add(new String[]{posText, "pos"});
        for (final String[] def : defs) {
            TextView b = text(def[0], 14, Typeface.BOLD, cAccent());
            b.setPadding(dp(14), dp(10), dp(14), dp(10));
            b.setBackground(ripple(dark ? tintBg(0x2E7AB1FF) : tintBg(0x1E0A6CF5), HEAD_R));
            Runnable run = "pos".equals(def[1]) ? posAction : "neu".equals(def[1]) ? neuAction : negAction;
            final Runnable act = run != null ? run : new Runnable() {
                @Override public void run() { }
            };
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    act.run();
                    dlg.dismiss();
                }
            });
            LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp2.leftMargin = dp(8);
            btns.addView(b, blp2);
        }
        dlg.setView(box);
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.45f;
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
            w.setAttributes(lp);
        }
        return dlg;
    }

    private GradientDrawable tintBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(HEAD_R));
        return g;
    }

    // ------------------------------------------------------------ 配置管理

    private static final String CFG_SNAPSHOT = "cfg_snapshot";
    private static final String CFG_FILE = "MapAdKiller_config.json";

    private void showConfigManager() {
        boolean hasSnap = readSnapshot() != null;
        glassDialog("配置管理",
                "保存配置：把高德、百度及桌面图标设置存到本地\n" +
                "恢复配置：回到上次保存的快照\n" +
                "导出 / 导入：通过分享或粘贴迁移到其它设备" + (hasSnap ? "\n\n已有本地快照" : "\n\n尚未保存过快照"),
                "保存配置", new Runnable() {
                    @Override public void run() { saveSnapshot(); }
                },
                "恢复配置", hasSnap ? new Runnable() {
                    @Override public void run() {
                        glassDialog("恢复配置？", "将覆盖当前全部开关，回到上次保存的快照。",
                                "恢复", new Runnable() {
                                    @Override public void run() { restoreSnapshot(); }
                                }, null, null, "取消", null);
                    }
                } : null,
                "更多", new Runnable() {
                    @Override public void run() { showConfigMore(); }
                });
    }

    private void showConfigMore() {
        glassDialog("导入 / 导出",
                "导出：生成配置文本并分享（可发给自己 / 存文件）\n" +
                "导入：粘贴配置文本恢复\n" +
                "文件：直接读写 .json 文件（推荐，跨设备迁移用这个）",
                "导出", new Runnable() {
                    @Override public void run() { exportConfig(); }
                },
                "导入", new Runnable() {
                    @Override public void run() { importConfig(); }
                },
                "文件…", new Runnable() {
                    @Override public void run() { showConfigFile(); }
                });
    }

    /** 「文件…」二级菜单：真正的 .json 文件读写（系统文件选择器）。 */
    private void showConfigFile() {
        glassDialog("配置文件（.json）",
                "导出到文件：把" + settingsTargetLabel() + "的开关写成一个 .json，存到手机任意位置\n" +
                "从文件导入：选一个 .json，先预览将覆盖多少项，确认后才写入",
                "导出到文件", new Runnable() {
                    @Override public void run() {
                        ConfigBackup.bind(configStore(), configSchema(), getPackageName());
                        ConfigBackup.startExport(MainActivity.this, ConfigBackup.REQ_EXPORT);
                        String err = ConfigBackup.takeError();
                        if (err != null) Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
                    }
                },
                "从文件导入", new Runnable() {
                    @Override public void run() {
                        ConfigBackup.bind(configStore(), configSchema(), getPackageName());
                        ConfigBackup.startImport(MainActivity.this, ConfigBackup.REQ_IMPORT);
                        String err = ConfigBackup.takeError();
                        if (err != null) Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
                    }
                },
                "恢复默认", new Runnable() {
                    @Override public void run() {
                        glassDialog("恢复默认？", "恢复" + settingsTargetLabel() + "的默认开关，强停地图 App 后生效。",
                                "恢复默认", new Runnable() {
                                    @Override public void run() { resetConfig(); }
                                }, null, null, "取消", null);
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ConfigBackup.REQ_EXPORT) {
            String message = ConfigBackup.handleExportResult(this, requestCode, resultCode, data);
            toast(message);
            setBackupStatus(message, !message.startsWith("已导出"));
        } else if (requestCode == ConfigBackup.REQ_IMPORT) {
            String message = ConfigBackup.handleImportResult(this, requestCode, resultCode, data);
            if (ConfigBackup.hasPendingImport()) {
                String detail = ConfigBackup.pendingSummary();
                glassDialog("确认导入？",
                        message + (detail.length() == 0 ? "" : "\n\n" + detail)
                                + "\n\n确认后会覆盖同名配置，地图 App 需强停重开才生效。",
                        "导入", new Runnable() {
                            @Override public void run() {
                                String result = ConfigBackup.confirmImport();
                                toast(result);
                                setBackupStatus(result, !result.startsWith("已导入"));
                                syncAllSwitches();
                            }
                        }, null, null, "取消", null);
            } else {
                toast(message);
                setBackupStatus(message, !message.startsWith("已取消"));
            }
        }
    }

    /** 空文案不弹（ConfigBackup 在用户取消时返回空串）。 */
    private void toast(String s) {
        if (s == null || s.length() == 0) return;
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** 导入后把界面上的开关状态拉回与配置一致。 */
    private void syncAllSwitches() {
        try { syncSwitches(); } catch (Throwable ignored) { }
    }

    private Map<String, java.util.Set<String>> configSchema() {
        Map<String, java.util.Set<String>> schema = new LinkedHashMap<>();
        java.util.Set<String> amap = new java.util.LinkedHashSet<>(switches.keySet());
        amap.add(ConfigBackup.LEARNED);
        if (!embedded() || embeddedAmap()) schema.put(ConfigBackup.AMAP, amap);
        if (!embedded() || !embeddedAmap()) schema.put(ConfigBackup.BAIDU, new java.util.LinkedHashSet<>(bmapSwitches.keySet()));
        if (!embedded()) schema.put(ConfigBackup.UI, new java.util.LinkedHashSet<>(java.util.Arrays.asList(App.K_HIDE_ICON)));
        return schema;
    }

    private ConfigBackup.Store configStore() {
        final io.github.libxposed.service.XposedService service = App.svc();
        if (service == null) throw new IllegalStateException("框架服务未连接，请稍后重试");
        return new ConfigBackup.Store() {
            private android.content.SharedPreferences prefs(String group) {
                return ConfigBackup.UI.equals(group) ? uiPrefs()
                    : service.getRemotePreferences(group);
            }
            @Override public Map<String, ?> read(String group) { return prefs(group).getAll(); }
            @Override public boolean write(String group, Map<String, Object> values) {
                android.content.SharedPreferences.Editor editor = prefs(group).edit();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null) editor.remove(entry.getKey());
                    else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                    else if (value instanceof java.util.Set) editor.putStringSet(entry.getKey(), new java.util.LinkedHashSet<String>((java.util.Set<String>) value));
                    else throw new IllegalArgumentException("配置类型错误");
                }
                return editor.commit();
            }
        };
    }

    private JSONObject collectConfig() throws Exception {
        return ConfigBackup.encode(configStore(), configSchema(), getPackageName());
    }

    private void saveSnapshot() {
        try {
            String json = collectConfig().toString();
            if (!uiPrefs().edit()
                    .putString(CFG_SNAPSHOT, json).commit()) throw new IllegalStateException("本地快照写入失败");
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject readSnapshot() {
        try {
            String j = uiPrefs()
                    .getString(CFG_SNAPSHOT, null);
            if (j != null) {
                JSONObject o = new JSONObject(j);
                ConfigBackup.decode(o, configSchema());
                return o;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void applyConfig(JSONObject backup) {
        try {
            Map<String, Map<String, Object>> groups = ConfigBackup.decode(backup, configSchema());
            int count = ConfigBackup.apply(configStore(), groups);
            if (!embedded() && groups.containsKey(ConfigBackup.UI) && !App.setHideIcon(this, App.hideIcon(this)))
                throw new IllegalStateException("开关已恢复，桌面图标更新失败，请重新切换图标开关");
            String scope = embedded() ? settingsTargetLabel()
                    : (groups.containsKey(ConfigBackup.BAIDU) ? "高德和百度" : "高德");
            Toast.makeText(this, "已恢复" + scope + " " + count + " 项，重开地图后生效", Toast.LENGTH_LONG).show();
            recreate();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetConfig() {
        try {
            Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.Set<String>> entry : configSchema().entrySet()) {
                if (ConfigBackup.UI.equals(entry.getKey())) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                for (String key : entry.getValue()) values.put(key, null);
                groups.put(entry.getKey(), values);
            }
            ConfigBackup.apply(configStore(), groups);
            Toast.makeText(this, settingsTargetLabel() + "已恢复默认，重开地图后生效", Toast.LENGTH_LONG).show();
            recreate();
        } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void restoreSnapshot() {
        JSONObject backup = readSnapshot();
        if (backup == null) {
            Toast.makeText(this, "没有可用快照", Toast.LENGTH_SHORT).show();
            return;
        }
        applyConfig(backup);
    }

    private void exportConfig() {
        try {
            String json = collectConfig().toString(2);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_SUBJECT, CFG_FILE);
            send.putExtra(Intent.EXTRA_TEXT, json);
            send.putExtra(Intent.EXTRA_TITLE, CFG_FILE);
            startActivity(Intent.createChooser(send, "导出配置"));
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importConfig() {
        final ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        final EditText input = new EditText(this);
        input.setHint("粘贴导出的 JSON 配置");
        input.setTextSize(13);
        input.setTextColor(cTxP());
        input.setHintTextColor(cTxS());
        input.setBackground(tintBg(dark ? 0x33000000 : 0x14000000));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (cm != null) {
            ClipData cd = cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).getText();
                if (t != null && t.length() > 0) input.setText(t);
            }
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(skin.glassFor(box, GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(10));
        box.addView(text("导入配置", 18, Typeface.BOLD, cTxP()));
        // 内置默认配置的入口与导入同源（都是「把一份 JSON 应用过来」），放在这里而不是
        // 再挤一个按钮进「更多」—— 那个对话框的三键布局是固定的。
        box.addView(restoreDefaultsRow());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130));
        ilp.topMargin = dp(12);
        box.addView(input, ilp);
        TextView tip = text("粘贴后点导入；键名不匹配的项会被忽略", 12, Typeface.NORMAL, cTxS());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        box.addView(tip, tlp);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        box.addView(btns, blp);
        TextView cancel = text("取消", 14, Typeface.BOLD, cTxS());
        cancel.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView go = text("导入", 14, Typeface.BOLD, cAccent());
        go.setPadding(dp(14), dp(10), dp(14), dp(10));
        go.setBackground(ripple(dark ? tintBg(0x2E7AB1FF) : tintBg(0x1E0A6CF5), HEAD_R));
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doImport(String.valueOf(input.getText())); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        btns.addView(cancel, clp);
        btns.addView(go, clp);
        final AlertDialog d2 = new AlertDialog.Builder(this).create();
        d2.setView(box);
        d2.show();
        Window w = d2.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.45f;
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
            w.setAttributes(lp);
        }
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d2.dismiss(); }
        });
    }

    private void doImport(String text) {
        try {
            applyConfig(new JSONObject(text));
        } catch (Throwable t) {
            Toast.makeText(this, "JSON 解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 「恢复默认配置」一行：重新应用模块内置的那份 JSON。
     *
     * <p>与 {@link #importConfig()} 的写入逻辑同源（都走 {@code DefaultConfig}），不重复造。
     * 与应用首启的区别只有一处：这里是无条件覆盖，所以先确认。
     */
    private View restoreDefaultsRow() {
        TextView action = text("恢复默认配置", 13, Typeface.BOLD, cAccent());
        action.setPadding(dp(2), dp(6), dp(2), dp(6));
        action.setBackground(ripple(dark ? tintBg(0x2E7AB1FF) : tintBg(0x1E0A6CF5), HEAD_R));
        action.setGravity(Gravity.CENTER);
        action.setContentDescription("重新应用模块内置的默认配置，会覆盖当前开关");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        action.setLayoutParams(lp);
        action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                glassDialog("恢复默认配置？",
                        "将把模块内置的默认配置重新写入，会覆盖你已经改过的开关。\n\n"
                        + "没有值的项（null）保持原样；写入后重开地图生效。",
                        "恢复", new Runnable() {
                            @Override public void run() { applyBuiltInDefaults(); }
                        }, null, null, "取消", null);
            }
        });
        return action;
    }

    /** 应用内置默认配置并刷新界面；失败只提示，不中断。 */
    private void applyBuiltInDefaults() {
        try {
            DefaultConfig.Result result = DefaultConfig.apply(this);
            if (result.groups.isEmpty()) {
                Toast.makeText(this, "没有可写入的默认配置（服务未连接或资源缺失）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "已恢复 " + result.applied + " 项，重开地图后生效",
                    Toast.LENGTH_LONG).show();
            recreate();
        } catch (Throwable t) {
            Toast.makeText(this, "恢复失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 联系作者：Telegram 群组 + GitHub Issues */
    private void showContact() {
        glassDialog("联系作者",
                "TG 通知频道：发版公告 / 重要通知\n"
                + "TG 聊天群：反馈 / 交流 / 提问\n"
                + "GitHub Issues：bug 报告 / 功能需求",
                "TG 通知频道", new Runnable() {
                    @Override public void run() { openUrl(Version.TG); }
                },
                "TG 聊天群", new Runnable() {
                    @Override public void run() { openUrl(Version.TG_CHAT); }
                },
                "GitHub Issues", new Runnable() {
                    @Override public void run() { openUrl("https://github.com/ldxm666/MapAdKiller/issues"); }
                });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 已捕获清单；还能一键清空重新学习 */
    private void showLearnedSdks() {
        java.util.List<String> list;
        try { list = SdkAutoBlock.learnedListForApp(); } catch (Throwable t) { list = null; }
        if (list == null || list.isEmpty()) {
            glassDialog("已捕获广告 SDK",
                    "还没有捕获记录。\n\n模块会扫描三家地图 App 自身 dex 里带广告特征的类，"
                    + "把厂商包名记下来并拦截；记录跨进程保存，下次启动直接生效。",
                    "知道了", null, null, null, null, null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        glassDialog("已捕获广告 SDK（" + list.size() + "）",
                sb.toString().trim(),
                "关闭", null,
                "清空重新学习", new Runnable() {
                    @Override public void run() {
                        try { SdkAutoBlock.clearLearned(); } catch (Throwable ignored) {}
                        Toast.makeText(MainActivity.this, "已清空，强停对应地图后重新打开即重新学习",
                                Toast.LENGTH_LONG).show();
                        recreate();
                    }
                }, null, null);
    }

    // ---------------------------------------------------------------- 行工厂

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(skin.glassFor(card, 20));
        card.setPadding(dp(8), dp(6), dp(8), dp(6));
        UiKit.clipRounded(card, dp(20));
        skin.shadow(card, 3f);
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    /**
     * 行间分割线 —— 左缩进 16 dp，与文字左边缘对齐。
     *
     * <p>HyperOS 的分割线不从卡片边缘起笔。全宽分割线会把卡片切成"一格一格的表格"，
     * 缩进之后才读得出"这是一个分组内的多行"。缩进量等于行的左右 padding。
     */
    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(skin == null ? 0x0F000000 : skin.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(HyperDesign.DIVIDER_INSET);
        d.setLayoutParams(lp);
        return d;
    }

    /** 行容器：60dp 最小高、行级涟漪、可点回弹 */
    private LinearLayout newRow(LinearLayout card) {
        if (card.getChildCount() > 0) card.addView(divider());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(8), dp(2), dp(8), dp(2));
        row.setBackground(UiKit.rippleBare(cRipple(), dp(12)));
        return row;
    }

    /**
     * 开关行 + 搜索索引。
     *
     * <p>haystack（标题 + 备注 + 配置键）直接挂在行的 tag 上，搜索时
     * {@link #filterRows} 顺着视图树读出来即可 —— 不必另外维护一份
     * “行 → key”的映射，也就不可能和界面显示的文字脱节。
     */
    private LinearLayout newSwitchRow(LinearLayout card, String key, String title, String note) {
        LinearLayout row = newRow(card);
        StringBuilder sb = new StringBuilder();
        sb.append(title == null ? "" : title);
        if (note != null && note.length() > 0) sb.append('\n').append(note);
        sb.append('\n').append(key == null ? "" : key);
        row.setTag(TAG_HAYSTACK, sb.toString());
        return row;
    }

    /** 行上缓存的 haystack key。用框架不会占用的负数 id，避免再建 ids.xml。 */
    private static final int TAG_HAYSTACK = 0x7f0f0001;
    /** 统一规格开关行：60dp 最小高、左标题右侧胶囊开关、行间分隔线（写 RemotePreferences） */
    /**
     * 一行滑块（值 = min + position * 1）。高德「自动巡航」的两个触发值用它。
     *
     * <p>读写走 {@code Config.getInt/put}，和地图内悬浮球面板完全同一组 key，
     * 两边改哪边都同步。
     */
    private void addCruiseSlider(LinearLayout card, String title, String key,
                                 int min, int max, int def, String unit) {
        LinearLayout row = skin.row();
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(skin.text(title, Skin.T_ROW, Skin.MEDIUM, skin.text2),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = skin.text("", Skin.T_ROW, Skin.BOLD, skin.accent);
        head.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(head, skin.matchWrap());

        final int stops = (max - min) + 1;
        // 先问服务（模块版：地图进程的 remote prefs），拿不到再退回本地（内置版同进程）。
        final int init = Math.max(min, Math.min(max, App.readInt(key, Config.getInt(key, def))));
        final GlassSlider slider = new GlassSlider(this);
        slider.setStops(stops);
        slider.setTrackColors(0x1F8A94A4, skin.accent, skin.accent);
        slider.setTrackBandDp(40f);
        slider.setPosition(init - min);
        value.setText(init + unit);
        slider.setContentDescription(title + "，" + init + unit);
        slider.setOnPositionChange(new GlassSlider.OnPositionChange() {
            @Override public void onPositionChanged(GlassSlider view, int position, boolean settled) {
                int v = min + position;
                value.setText(v + unit);
                view.setContentDescription(title + "，" + v + unit);
                if (settled) {
                    // 只能走 App.writeInt：模块 App 和地图不是一个进程，写本地 prefs 地图侧永远读不到。
                    // 写失败（服务没绑上 = 高德没运行）就退回原值并说明原因 —— 静默丢弃会让用户
                    // 以为调好了，然后去怪巡航不触发。
                    if (!App.writeInt(key, v)) {
                        int back = Math.max(min, Math.min(max, App.readInt(key, def)));
                        view.setPosition(back - min);
                        value.setText(back + unit);
                        view.setContentDescription(title + "，" + back + unit);
                        Toast.makeText(MainActivity.this,
                                "未连接地图：先打开高德，再回来调", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(56));
        row.addView(slider, slp);
        card.addView(row, skin.matchWrap());
    }

    /**
     * 一行「离散值」选择器：60 / 90 / 120 这种只允许几档的整数配置。
     *
     * <p>为什么不复用 {@link #addCruiseSlider}：那个是「min + position，步长 1」的连续滑块，
     * 帧率上限用它会长出 61 个无意义档位（77 帧、83 帧……）。帧率上限只有在屏幕刷新率的
     * 整数分频点上才有确定含义，所以这里给三个按钮，点哪个就是哪个。
     *
     * <p>写盘走 {@code App.writeInt}：模块 App 和地图不是一个进程，写本地 prefs 地图侧永远
     * 读不到。写失败（服务没绑上 = 高德没运行）要当场说清楚并保持原高亮，否则用户会以为调好了，
     * 然后去怪帧率没变。
     */
    private void addChoiceRow(LinearLayout card, String title, final String key,
                              final int[] values, final int def, final String unit) {
        LinearLayout row = skin.row();
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(skin.text(title, Skin.T_ROW, Skin.MEDIUM, skin.text2),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(head, skin.matchWrap());

        final int init = App.readInt(key, Config.getInt(key, def));
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] buttons = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            final int accent = skin.accent;
            final int dim = skin.text2;
            TextView button = skin.text(value + unit, Skin.T_ROW, Skin.BOLD,
                    value == init ? accent : dim);
            button.setGravity(Gravity.CENTER);
            button.setPadding(0, dp(10), 0, dp(10));
            button.setContentDescription(title + "，" + value + unit);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!App.writeInt(key, value)) {
                        Toast.makeText(MainActivity.this, "未连接地图：先打开高德，再回来调",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    for (int k = 0; k < values.length; k++) {
                        buttons[k].setTextColor(values[k] == value ? accent : dim);
                    }
                }
            });
            buttons[i] = button;
            choices.addView(button, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        row.addView(choices, skin.matchWrap());
        card.addView(row, skin.matchWrap());
    }

    private void addSwitch(final LinearLayout card, String title, final String key) {
        LinearLayout row = newSwitchRow(card, key, title, null);

        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(11), dp(10), dp(11));
        label.setLineSpacing(0, 1.15f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        sw.setChecked(readState(key), false);
        switches.put(key, sw);
        index(finder, key, title, null, PAGE_AMAP);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 单一真相路径：开关自己吃掉点击并翻转 → 回调里落盘；落盘失败就回滚。
        // 行点击只是把同一个 performClick() 代理一遍，所以胶囊与行永远不会打架。
        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                if (App.writeBoolean(key, next)) {
                    if (Config.K_TOOLS_ALLOWLIST.equals(key)) syncSwitches();
                } else {
                    view.setChecked(!next, true);
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
    }

    /** 本地开关行（不走 LSPosed RemotePreferences），仅用于模块 App 自身组件可见性。 */
    /** 本地开关行（不走 LSPosed RemotePreferences），仅用于模块 App 自身组件可见性。 */
    private void addLocalSwitch(final LinearLayout card, String title, final String key) {
        LinearLayout row = newRow(card);

        TextView label = text(title, 14.5f, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(11), dp(10), dp(11));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch sw = makeSwitch();
        sw.setLabel(title);
        boolean on = false;
        try {
            on = getSharedPreferences(App.UI_PREFS, MODE_PRIVATE).getBoolean(key, false);
        } catch (Throwable ignored) {}
        sw.setChecked(on, false);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 本地开关不进 RemotePreferences：翻转后先弹确认，确认了才落盘，取消就回滚。
        sw.setOnCheckedChange(new GlassSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(GlassSwitch view, boolean next) {
                if (!next) { applyHide(false, view); return; }
                // 桌面入口（LAUNCHER alias）禁掉后桌面看不到，但 LSPosed 管理器
                // 走 CATEGORY_INFO 通道（见 AndroidManifest 注释），入口不受影响。
                glassDialog("隐藏桌面图标？",
                        "· 桌面/抽屉里的 MapAdKiller 图标消失\n"
                        + "· LSPosed 管理器里的「打开」入口不受影响，随时可回到本页\n"
                        + "· 快捷设置磁贴与 adb 命令同样始终可用",
                        "仍然隐藏", new Runnable() {
                            @Override public void run() { applyHide(true, view); }
                        }, null, null, "取消", new Runnable() {
                            @Override public void run() { view.setChecked(false, true); }
                        });
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
    }

    private void applyHide(boolean hide, GlassSwitch sw) {
        if (embedded()) return;
        boolean ok = App.setHideIcon(MainActivity.this, hide);
        sw.setChecked(hide);
        Toast.makeText(MainActivity.this,
                ok ? (hide ? "已隐藏桌面图标（LSPosed 管理器可随时再打开）" : "已恢复桌面图标")
                   : "组件状态变更被系统拒绝",
                Toast.LENGTH_SHORT).show();
    }

    /** 当前状态：优先读 RemotePreferences（Hook 侧同一数据源），读不到按各键默认值 */
    private boolean readState(String key) {
        boolean def = Config.defaultVisible(key);
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s != null) return Config.readVisible(s.getRemotePreferences(Config.PREF_GROUP), key);
        } catch (Throwable ignored) {}
        return def;
    }

    /** 把 SDK 计数刷新成实时值 */
    private void refreshSdkRow() {
        try {
            if (sdkRow != null) sdkRow.setText(sdkSummary());
        } catch (Throwable ignored) {}
    }

    /** 设置页上那一行：已自动捕获多少个广告 SDK */
    private String sdkSummary() {
        int n = 0;
        try { n = SdkAutoBlock.learnedListForApp().size(); } catch (Throwable ignored) {}
        return "已捕获广告 SDK：" + n + " 个 · 点按查看清单";
    }

    private TextView addActionButton(LinearLayout card, String title, final Runnable action) {
        if (card.getChildCount() > 0) card.addView(divider());
        TextView label = text(title, 13.5f, Typeface.BOLD, cAccent());
        label.setPadding(dp(14), dp(13), dp(14), dp(13));
        label.setGravity(Gravity.CENTER);
        label.setBackground(UiKit.ripple(cRipple(), UiKit.plate(tintBg(), dp(14)), dp(14)));
        label.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        UiKit.pressable(label, 0.97f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        card.addView(label, lp);
        return label;
    }

    private int tintBg() { return dark ? 0x2E7AB1FF : 0x1A0A6CF5; }

    /** 说明文字：左侧细色条 + 淡色正文，与开关行区分开 */
    private void addNoteRow(LinearLayout card, String s) {
        // A hint belongs to the row above, so it is a quiet 12 sp line, not its own entry.
        TextView t = skin.text(s, 12f, Skin.REGULAR, cTxS());
        t.setLineSpacing(dp(2), 1.25f);
        t.setPadding(dp(16), dp(2), dp(16), dp(10));
        card.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 版式：底部标签栏 + 首页 / 配置 / 备份 / 关于 四页
    // ══════════════════════════════════════════════════════════════════════
    //
    // 设计基线（见 Skin.java）：
    //   · 4/8/12/16/20/24/32 间距阶梯，卡片 20 dp 圆角 + 1 px 描边；
    //   · 每屏一个强调色（蓝），状态色只用于状态点；
    //   · 页面标题 26 sp / 卡片标题 15 sp / 正文 13.5 sp / 注释 12 sp / 标签 11 sp。
    // 信息架构：顶部原先那整块（标题 / 状态 / 三格指标 / 更新行）整体搬进首页；
    // 三个地图的开关页收进「配置」；配置文件的读写独立成「备份」；链接与通用设置进「关于」。

    private static final int TAB_HOME = 0, TAB_CONFIG = 1, TAB_BACKUP = 2, TAB_ABOUT = 3;

    private Skin skin;
    private FrameLayout contentHost;
    private View configScreen;
    private ScrollView homePage;
    private ScrollView backupPage;
    private ScrollView aboutPage;
    private FrameLayout configHost;
    private final List<ScrollView> appPages = new ArrayList<ScrollView>();
    private final List<Integer> appPageIds = new ArrayList<Integer>();
    private final List<TextView> appChips = new ArrayList<TextView>();
    private LinearLayout navBar;
    private final List<LinearLayout> navItems = new ArrayList<LinearLayout>();
    private final List<Icon> navIcons = new ArrayList<Icon>();
    private final List<TextView> navLabels = new ArrayList<TextView>();
    private int tabIndex = TAB_HOME;
    private boolean homeAnimated;
    private int appIndex = 0;
    private TextView backupStatus;
    private TextView configSummary;

    /* ────────────────────────────────────────────────────────────── 首页 */

    // ══════════════════════════════════════════════════════════════ 首页 · 服务器公告

    /**
     * 首页顶部的公告卡 —— 模块版的**主入口**（内置版的主入口在悬浮面板首页的横幅上）。
     *
     * <p>两种状态：有未读公告时整卡走 accent 着色 + 右侧红点，已读后退回普通玻璃卡，
     * 仍可点开重看。视觉原子全部来自 {@link Skin}（{@code card/glassFor/tintedGlass/rowTitle/
     * text/iconTile}），不新造圆角与字号。
     *
     * <p>卡片只建一次，内容用 {@link #renderAnnounceCard} 重建：这样点击事件不会随着公告
     * 状态变化丢失，也避免每次刷新往 root 里再塞一张卡。
     */
    private LinearLayout announceCard;

    private LinearLayout buildAnnounceCard() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(skin.dp(Skin.S4), skin.dp(Skin.S4), skin.dp(Skin.S4), skin.dp(Skin.S4));
        UiKit.clipRounded(card, skin.dp(Skin.R_CARD));
        skin.shadow(card, 7f);
        card.setClickable(true);
        UiKit.pressable(card, 0.995f);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Announce.showLatest(MainActivity.this);
            }
        });
        renderAnnounceCard(card);

        // 读完公告后把红点去掉。Weak 引用，Activity 没了回调自动失效。
        final java.lang.ref.WeakReference<MainActivity> self =
                new java.lang.ref.WeakReference<MainActivity>(this);
        Announce.setOnChanged(new Runnable() {
            @Override public void run() {
                MainActivity activity = self.get();
                if (activity != null && activity.announceCard != null) {
                    activity.renderAnnounceCard(activity.announceCard);
                }
            }
        });
        return card;
    }

    /** 重画卡片内容（不改卡片本身）。未读 ⇒ 着色 + 红点；已读 ⇒ 普通卡 + 标题即最近一条。 */
    private void renderAnnounceCard(LinearLayout card) {
        if (card == null) return;
        boolean unread = false;
        String latest = null;
        try {
            unread = Announce.hasUnread(this);
            latest = Announce.latestTitle(this);
        } catch (Throwable ignored) { }
        card.setBackground(unread
                ? skin.tintedGlass(skin.accent, Skin.R_CARD)
                : skin.glassFor(card, Skin.R_CARD));
        card.removeAllViews();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(iconTile(Icon.INFO, skin.accent, skin.accentSoft), fixed(skin, 38, 38));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(skin.rowTitle(unread ? "服务器公告" : "最新公告"));
        TextView line = skin.text(latest != null ? latest : "暂无公告",
                Skin.T_CAPTION, Skin.REGULAR, skin.text2);
        line.setSingleLine(true);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lineLp = skin.matchWrap();
        lineLp.topMargin = skin.dp(2);
        texts.addView(line, lineLp);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = skin.dp(12);
        row.addView(texts, textsLp);

        if (unread) {
            LinearLayout.LayoutParams dotLp = fixed(skin, 8, 8);
            dotLp.rightMargin = skin.dp(6);
            row.addView(dot(skin.danger, 8), dotLp);
        }
        row.addView(new Icon(this, Icon.CHEVRON, unread ? skin.accent : skin.text3),
                fixed(skin, 18, 18));
        card.addView(row, skin.matchWrap());
    }

    private ScrollView buildHomePage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(skin.dp(Skin.S5), skin.dp(Skin.S3), skin.dp(Skin.S5), skin.dp(Skin.S7));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题行：品牌 + 版本胶囊
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(skin.display("MapAdKiller"));
        TextView tagline = skin.caption(embedded()
                ? "地图广告净化 · 免 root 整合版"
                : "地图广告净化 · LSPosed 模块");
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = skin.dp(3);
        brand.addView(tagline, tagLp);
        top.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(skin.chip("v" + Version.LABEL, skin.accentSoft, skin.accent));
        top.setPadding(skin.dp(Skin.S2), skin.dp(Skin.S1), skin.dp(Skin.S2), skin.dp(Skin.S2));
        top.setBackground(skin.headerScrim());
        root.addView(top, skin.matchWrap());

        announceCard = buildAnnounceCard();
        root.addView(announceCard, withTop(skin, Skin.S4));

        // 状态卡：这一块就是原来屏幕顶部的那一整块，只是挪到了首页
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(skin.dp(18), skin.dp(18), skin.dp(18), skin.dp(14));
        status.setBackground(skin.glassFor(status, Skin.R_CARD,
                UiKit.blend(skin.glassTint(), skin.accent, 0.08f)));
        UiKit.clipRounded(status, skin.dp(Skin.R_CARD));
        skin.shadow(status, 6f);
        status.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showStatusDetail(); }
        });
        UiKit.pressable(status, 0.995f);

        LinearLayout statusTop = new LinearLayout(this);
        statusTop.setOrientation(LinearLayout.HORIZONTAL);
        statusTop.setGravity(Gravity.CENTER_VERTICAL);

        navPill = new LinearLayout(this);
        navPill.setOrientation(LinearLayout.HORIZONTAL);
        navPill.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(skin.dp(8), skin.dp(8));
        dotLp.rightMargin = skin.dp(7);
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        navPill.addView(dot(skin.success, 8), dotLp);
        statusPillText = skin.text("连接中", 20f, Skin.BOLD, skin.text);
        navPill.addView(statusPillText);
        statusTop.addView(navPill, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.HORIZONTAL);
        detail.setGravity(Gravity.CENTER_VERTICAL);
        detail.addView(skin.text("运行状态", Skin.T_CAPTION, Skin.MEDIUM, skin.accent));
        Icon chev = new Icon(this, Icon.CHEVRON, skin.accent);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(skin.dp(15), skin.dp(15));
        chevLp.leftMargin = skin.dp(2);
        detail.addView(chev, chevLp);
        statusTop.addView(detail);
        status.addView(statusTop, skin.matchWrap());

        statusView = skin.text("", Skin.T_CAPTION + 0.5f, Skin.REGULAR, skin.text2);
        statusView.setLineSpacing(skin.dp(2), 1.15f);
        LinearLayout.LayoutParams stLp = skin.matchWrap();
        stLp.topMargin = skin.dp(7);
        status.addView(statusView, stLp);

        View hair = new View(this);
        hair.setBackgroundColor(skin.stroke);
        LinearLayout.LayoutParams hairLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, skin.dp(1)));
        hairLp.topMargin = skin.dp(14);
        status.addView(hair, hairLp);

        metricRow = new LinearLayout(this);
        metricRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrlp = skin.matchWrap();
        mrlp.topMargin = skin.dp(12);
        status.addView(metricRow, mrlp);
        buildHomeMetrics();
        root.addView(status, withTop(skin, Skin.S4));

        // 更新卡
        LinearLayout update = skin.card();
        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setOrientation(LinearLayout.HORIZONTAL);
        updateRow.setGravity(Gravity.CENTER_VERTICAL);
        updateRow.addView(iconTile(Icon.REFRESH, skin.accent, skin.accentSoft), fixed(skin, 38, 38));
        LinearLayout updateText = new LinearLayout(this);
        updateText.setOrientation(LinearLayout.VERTICAL);
        updateText.addView(skin.rowTitle("版本更新"));
        verLine = skin.text("当前版本 v" + Version.LABEL, Skin.T_CAPTION, Skin.REGULAR, skin.text2);
        verLine.setSingleLine(true);
        verLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = skin.matchWrap();
        vlp.topMargin = skin.dp(2);
        updateText.addView(verLine, vlp);
        LinearLayout.LayoutParams utlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        utlp.leftMargin = skin.dp(12);
        updateRow.addView(updateText, utlp);
        updateBtn = skin.textButton("检查");
        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (UpdateChecker.isOutdated(MainActivity.this)) {
                    showUpdateDialog(UpdateChecker.latest(MainActivity.this));
                    return;
                }
                Toast.makeText(MainActivity.this, "正在检查更新…", Toast.LENGTH_SHORT).show();
                UpdateChecker.check(MainActivity.this, true, updateCb);
            }
        });
        updateRow.addView(updateBtn);
        update.addView(updateRow, skin.matchWrap());
        root.addView(update, withTop(skin, Skin.S3));

        // 数据与日志（v2.2.6）：抓取云数据 / 运行日志，首页直达
        skin.addSection(root, "数据与日志");
        LinearLayout diag = skin.listCard();
        diag.addView(cloudDataRow());
        diag.addView(skin.hairline());
        diag.addView(logRow());
        root.addView(diag, skin.matchWrap());
        loadCloudState();

        // 应用入口
        skin.addSection(root, "应用");
        LinearLayout apps = skin.listCard();
        // 已安装的排前面，未安装的沉底；同类之间保持 百度 → 高德 → 腾讯 的固定顺序。
        final String[] appPkgs = {"com.baidu.BaiduMap", "com.autonavi.minimap", "com.tencent.map"};
        final String[] appNames = {"百度地图", "高德地图", "腾讯地图"};
        Integer[] appOrder = {0, 1, 2};
        java.util.Arrays.sort(appOrder, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                boolean ia = installedVersion(appPkgs[a]) != null;
                boolean ib = installedVersion(appPkgs[b]) != null;
                if (ia != ib) return ia ? -1 : 1;
                return a - b;
            }
        });
        for (int k = 0; k < appOrder.length; k++) {
            int idx = appOrder[k];
            if (k > 0) apps.addView(skin.hairline());
            apps.addView(appRow(appPkgs[idx], appNames[idx], idx));
        }
        root.addView(apps, skin.matchWrap());

        // 作者与链接
        skin.addSection(root, "作者");
        root.addView(buildAuthorCard(), skin.matchWrap());

        // 赞赏：主页最后一个区块，点开收款码
        skin.addSection(root, "赞赏");
        LinearLayout donate = skin.listCard();
        donate.addView(donateRow());
        root.addView(donate, skin.matchWrap());

        TextView foot = skin.footnote("免费开源 · 禁止倒卖 · MapAdKiller v" + Version.LABEL);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = skin.matchWrap();
        flp.topMargin = skin.dp(Skin.S6);
        root.addView(foot, flp);

        adLine = skin.footnote("");
        adLine.setVisibility(View.GONE);
        renderVersionLines();
        return sv;
    }

    // ══════════════════════════════════════════════════ 首页 · 数据与日志（v2.2.6）

    /**
     * 云数据状态缓存。模块版（LSPosed）读不到宿主 UID 的 files/baseline、也没有 root 兜底，
     * 状态由宿主进程发广播回来，落在本 App 的 {@code capture_report} prefs 里
     * （见 {@code mapadkiller.CaptureReport}）。心跳里只读这份内存缓存，不碰磁盘。
     */
    private boolean cloudDone;
    private int cloudFileCount;
    private boolean cloudStateKnown;
    /** 面板已请求抓取、还没等到宿主回报结果。 */
    private boolean cloudPending;
    /** 上次抓取失败原因（宿主的 baseline_capture_retry 原因码）。 */
    private String cloudError;
    /** 上次抓取完成时间。 */
    private long cloudAt;
    private TextView cloudSub;
    private TextView logSub;

    /**
     * 已经提示过的抓取回执时间戳，**持久化**在 {@code capture_report} prefs 的 {@code ack_at}。
     *
     * <p>为什么要落盘：不落盘就只剩「本 Activity 实例内」的记忆，用户杀掉后台再打开，
     * 上一次的结果会被当成新结果再弹一遍 —— 而这正是「重复点击/重进页面叠加提示」的来源。
     * 只在收到没提示过的 done/failed 回执时才写；pending/state 不写。
     */
    private static final String K_ACK_AT = "ack_at";
    private long ackAtCache = -1L;
    /** 用户点「开始抓取 / 重新抓取」的时刻，用来算 {@code event=capture_done} 里的 ms=。 */
    private long captureAskedAt;

    /** 「抓取云数据」一行：官方云端下发的首页卡片 / 金刚位 / 工具宫格 / 云控开关，按版本抓一份基线。 */
    private LinearLayout cloudDataRow() {
        LinearLayout row = skin.row();
        row.addView(iconTile(Icon.DOWNLOAD, skin.accent, skin.accentSoft), fixed(skin, 38, 38));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(skin.rowTitle("抓取云数据"));
        cloudSub = skin.text("读取状态…", Skin.T_CAPTION, Skin.REGULAR, skin.text2);
        cloudSub.setSingleLine(true);
        cloudSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = skin.matchWrap();
        slp.topMargin = skin.dp(2);
        box.addView(cloudSub, slp);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = skin.dp(12);
        row.addView(box, blp);
        row.addView(new Icon(this, Icon.CHEVRON, skin.text3), fixed(skin, 18, 18));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startCloudCapture(); }
        });
        UiKit.pressable(row, 0.99f);
        return row;
    }

    /** 「运行日志」一行：进程内环形缓冲（最近 10 分钟）查看 / 复制 / 导出 txt。 */
    private LinearLayout logRow() {
        LinearLayout row = skin.row();
        row.addView(iconTile(Icon.ARCHIVE, skin.accent, skin.accentSoft), fixed(skin, 38, 38));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(skin.rowTitle("运行日志"));
        logSub = skin.text("点此查看 / 导出运行日志", Skin.T_CAPTION, Skin.REGULAR, skin.text2);
        logSub.setSingleLine(true);
        logSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = skin.matchWrap();
        slp.topMargin = skin.dp(2);
        box.addView(logSub, slp);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = skin.dp(12);
        row.addView(box, blp);
        row.addView(new Icon(this, Icon.CHEVRON, skin.text3), fixed(skin, 18, 18));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLogDialog(); }
        });
        UiKit.pressable(row, 0.99f);
        return row;
    }

    /** 心跳里只改文案，不碰磁盘 / 不起进程。 */
    private void refreshDiagRows() {
        try {
            if (cloudSub != null) {
                if (!embedded()) readCaptureReport();
                String text;
                if (embedded() && CaptureGate.capturing(this)) {
                    text = "正在抓取官方云端数据…";
                } else if (!cloudStateKnown) {
                    text = "读取状态…";
                } else if (embedded() && cloudDone) {
                    text = "已抓取" + (cloudFileCount > 0 ? " " + cloudFileCount + " 个配置文件" : "本版本基线")
                            + " · 点此重新抓取";
                } else if (!embedded() && cloudPending) {
                    text = "已请求抓取 · 打开高德后自动开始";
                } else if (cloudDone) {
                    text = "已抓取" + (cloudFileCount > 0 ? " " + cloudFileCount + " 个配置文件" : "本版本基线")
                            + (cloudAt > 0L ? " · " + timeText(cloudAt) : "")
                            + " · 点此重新抓取";
                } else if (cloudError != null && cloudError.length() > 0) {
                    text = "上次没抓全（" + reasonText(cloudError) + "）· 点此重试";
                } else {
                    text = "未抓取 · 点此抓取官方云端数据";
                }
                if (!text.contentEquals(cloudSub.getText())) cloudSub.setText(text);
            }
            if (logSub != null) {
                int n = 0;
                try { n = CaptureLog.recentCount(); } catch (Throwable ignored) { }
                String text = n > 0
                        ? "最近 " + n + " 条事件 · 点此查看"
                        : "点此查看 / 导出运行日志";
                if (!text.contentEquals(logSub.getText())) logSub.setText(text);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 读一次云数据状态。
     *
     * <p>内置版：与宿主同进程同 UID，直接读 {@code files/baseline}。
     * <p>模块版：跨 UID，宿主的 state 文件读不到（模块 App 也不在 KernelSU 白名单，{@code su} 静默失败），
     * 只能读宿主抓完/失败时广播回来的 {@code capture_report}。旧实现只调
     * {@code Baseline.isCaptured()}，永远返回 false —— 首页就一直显示「未抓取」，
     * 用户当然认为「抓取一直失败」。
     */
    private void loadCloudState() {
        if (embedded()) {
            try {
                cloudDone = CaptureGate.captured(this);
                cloudFileCount = CaptureGate.capturedFiles(this);
            } catch (Throwable ignored) { }
            cloudStateKnown = true;
            refreshDiagRows();
            return;
        }
        readCaptureReport();
        cloudStateKnown = true;
        refreshDiagRows();
        if (cloudDone) return;
        // 本机还没收到过任何回报（比如模块刚更新）：再用权威接口确认一次，和广播结果取并集。
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                int files = 0;
                try {
                    ok = io.github.ldxm666.mapadkiller.Baseline.isCaptured();
                    if (ok) files = io.github.ldxm666.mapadkiller.Baseline.capturedFiles();
                } catch (Throwable ignored) { }
                final boolean done = ok;
                final int n = files;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (done && !cloudDone) {
                            cloudDone = true;
                            if (n > 0) cloudFileCount = n;
                        }
                        cloudStateKnown = true;
                        refreshDiagRows();
                    }
                });
            }
        }, "mapclean-cloud-state").start();
    }

    /** 把宿主广播回来的抓取状态读进内存字段（SharedPreferences 有内存缓存，心跳里读也便宜）。 */
    private void readCaptureReport() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(
                    io.github.ldxm666.mapadkiller.CaptureReport.P, MODE_PRIVATE);
            boolean known = sp.getBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_KNOWN, false);
            if (!known) return;
            String stage = sp.getString(io.github.ldxm666.mapadkiller.CaptureReport.K_STAGE, null);
            boolean captured = sp.getBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_CAPTURED, false);
            if (io.github.ldxm666.mapadkiller.CaptureReport.S_DONE.equals(stage)
                    || io.github.ldxm666.mapadkiller.CaptureReport.S_STATE.equals(stage)
                    || io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED.equals(stage)) {
                if (captured) {
                    cloudDone = true;
                    cloudFileCount = sp.getInt(io.github.ldxm666.mapadkiller.CaptureReport.K_FILES, cloudFileCount);
                    long at = sp.getLong(io.github.ldxm666.mapadkiller.CaptureReport.K_AT, 0L);
                    if (at > 0L) cloudAt = at;
                    cloudError = null;
                    cloudPending = false;
                } else if (io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED.equals(stage)
                        || io.github.ldxm666.mapadkiller.CaptureReport.S_STATE.equals(stage)) {
                    cloudError = sp.getString(io.github.ldxm666.mapadkiller.CaptureReport.K_ERROR, null);
                    cloudPending = false;
                    if (io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED.equals(stage)) cloudDone = false;
                }
            } else if (io.github.ldxm666.mapadkiller.CaptureReport.S_PENDING.equals(stage)) {
                // 「已请求、等宿主回报」：即便手里还有上一次的成功结果，也要先显示等待中。
                cloudPending = true;
            }
            // v2.3.0：状态读完顺手做一次「结果反馈」判定。放在这里（而不是广播接收器里）
            // 有个硬理由：接收器只负责落盘，它没有 Activity，判定不了「用户到底看没看屏幕」，
            // 也就分不出该弹 Toast（看完自动退出）还是该发通知栏（用户在高德里）。
            checkCaptureResult(stage, captured,
                    sp.getInt(io.github.ldxm666.mapadkiller.CaptureReport.K_FILES, 0),
                    sp.getLong(io.github.ldxm666.mapadkiller.CaptureReport.K_AT, 0L),
                    sp.getString(io.github.ldxm666.mapadkiller.CaptureReport.K_ERROR, null));
        } catch (Throwable t) {
            // 不许静默：读回执失败 = 首页那行永远停在旧文案，没有日志就只能靠猜。
            android.util.Log.w("MapCleanCapture", "event=capture_report_read_fail err=" + t);
        }
    }

    // ══════════════════════════════════════════════ 抓取结果反馈 + 自动退出（v2.3.0）

    /** 已提示过的回执时间戳（读一次就缓存，心跳不反复碰磁盘）。 */
    private long ackAt() {
        if (ackAtCache >= 0L) return ackAtCache;
        try {
            ackAtCache = getSharedPreferences(
                    io.github.ldxm666.mapadkiller.CaptureReport.P, MODE_PRIVATE)
                    .getLong(K_ACK_AT, 0L);
        } catch (Throwable t) {
            android.util.Log.w("MapCleanCapture", "event=capture_ack_read_fail err=" + t);
            ackAtCache = 0L;
        }
        return ackAtCache;
    }

    /** 记下「这条回执已经提示过」，落盘，避免重进页面/重启进程重复弹。 */
    private void markAck(long at) {
        ackAtCache = at;
        try {
            getSharedPreferences(io.github.ldxm666.mapadkiller.CaptureReport.P, MODE_PRIVATE)
                    .edit().putLong(K_ACK_AT, at).commit();
        } catch (Throwable t) {
            // 落盘失败只影响「下次还会提示一次」，不影响本次反馈，所以只记日志不中断。
            android.util.Log.w("MapCleanCapture", "event=capture_ack_write_fail err=" + t);
        }
    }

    /**
     * 抓取结果的唯一出口：成功/失败各一条明确提示，成功且用户正看着本页时自动退出。
     *
     * <p><b>成功</b>：Toast（前台）或通知栏（后台），文案带抓到多少项；1.2 秒后 {@code finish()}，
     * 把界面还给地图。摘要留在首页那行（「已抓取 N 个配置文件 · 今天 03:47」），
     * 所以退出后用户回来仍然找得到结果，不会「一关就什么都不知道了」。
     *
     * <p><b>失败</b>：只提示、**绝不自动退出**。用户要停在这一页看到原因并重试；
     * 自动退出会把「失败」变成「什么都没发生」，那正是这次要修的老毛病。
     */
    private void checkCaptureResult(String stage, boolean captured, int files, long at, String error) {
        final boolean done = io.github.ldxm666.mapadkiller.CaptureReport.S_DONE.equals(stage) && captured;
        final boolean failed = io.github.ldxm666.mapadkiller.CaptureReport.S_FAILED.equals(stage);
        if (!done && !failed) return;
        final long stamp = at > 0L ? at : System.currentTimeMillis();
        if (stamp <= ackAt()) return;      // 这条回执已经提示过（含冷启动时的历史结果）
        final long age = System.currentTimeMillis() - stamp;
        if (age > 10L * 60L * 1000L) {
            // 隔了太久的旧结果（比如进程被杀、第二天才打开）只更新列表，不再打扰。
            android.util.Log.i("MapCleanCapture", "event=capture_feedback_skip reason=stale age_ms=" + age);
            markAck(stamp);
            return;
        }
        boolean fg = false;
        try {
            fg = !isFinishing() && hasWindowFocus();
        } catch (Throwable t) {
            android.util.Log.w("MapCleanCapture", "event=capture_fg_probe_fail err=" + t);
        }
        final boolean toasted;
        try {
            toasted = io.github.ldxm666.mapadkiller.CaptureLog.notifyResult(
                    this, fg, done, files, done ? ("amap:" + files) : "",
                    captureAskedAt > 0L ? stamp - captureAskedAt : 0L,
                    stamp, done ? null : reasonText(error));
        } catch (Throwable t) {
            // 提示这条路整个挂了：记下来并**记账**，否则心跳每 1.5 秒重试一次，
            // logcat 会被同一条异常刷满。抓取结果本身已经落盘，首页那行仍然是对的。
            android.util.Log.w("MapCleanCapture", "event=capture_feedback_fail err=" + t);
            markAck(stamp);
            return;
        }
        // 提示真的发出去了才记账：先记账再提示的话，提示一失败就永久丢了，
        // 用户永远等不到那条「抓取完成」。
        markAck(stamp);
        if (!done || !toasted) return;    // 失败 / 走的是通知栏（用户不在本页）⇒ 不退出
        // 成功且用户正看着设置页：留 1.2 秒让 Toast 被看见，然后自动退出。
        statusHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (!isFinishing()) finish();
                    android.util.Log.i("MapCleanCapture", "event=capture_finish ok=true");
                } catch (Throwable t) {
                    android.util.Log.w("MapCleanCapture", "event=capture_finish_fail err=" + t);
                }
            }
        }, 1200L);
        android.util.Log.i("MapCleanCapture", "event=capture_finish scheduled_ms=1200");
    }

    /** 抓取时间显示：「今天 14:32」/「10-27 14:32」。 */
    private String timeText(long at) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            java.util.Calendar t = java.util.Calendar.getInstance();
            t.setTimeInMillis(at);
            String hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(at));
            boolean today = c.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR)
                    && c.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR);
            if (today) return "今天 " + hm;
            return new java.text.SimpleDateFormat("MM-dd ", java.util.Locale.getDefault()).format(new java.util.Date(at)) + hm;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 抓取失败原因 → 人话。 */
    private String reasonText(String code) {
        if (code == null) return "未知原因";
        if (code.contains("prefs_incomplete")) return "高德启动太慢，配置没写完";
        if (code.contains("fallback_thin")) return "没等到首页，抓到的配置太少";
        if (code.contains("no_host_context")) return "高德进程还没起来";
        if (code.contains("timeout")) return "等待超时";
        return code;
    }

    /** 本地记一条「已请求抓取」，等宿主的广播回来后覆盖成真正的结果。 */
    private void markCapturePending() {
        try {
            getSharedPreferences(io.github.ldxm666.mapadkiller.CaptureReport.P, MODE_PRIVATE)
                    .edit()
                    .putBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_KNOWN, true)
                    .putString(io.github.ldxm666.mapadkiller.CaptureReport.K_STAGE,
                            io.github.ldxm666.mapadkiller.CaptureReport.S_PENDING)
                    .putBoolean(io.github.ldxm666.mapadkiller.CaptureReport.K_CAPTURED, cloudDone)
                    .putLong(io.github.ldxm666.mapadkiller.CaptureReport.K_AT, System.currentTimeMillis())
                    .commit();
        } catch (Throwable ignored) { }
    }

    /**
     * 把高德拉到前台：进程没起来就是干净的一次启动（冷启动会直接进入采集），
     * 进程还活着则由 {@code CaptureGate.applyRecaptureRequest} 在 onResume 写下采集标记
     * 并提示用户「关掉高德再打开一次」。
     *
     * <p>直接 {@code startActivity} 在部分 ROM（HyperOS 等）会被「链式启动 / 后台弹出界面」
     * 的 {@code ConfirmStartActivity} 确认框拦一道，所以同时排一个几秒后的系统闹钟拉起启动页，
     * 和内置版 {@code CaptureGate.restartHost} 用的是同一招。
     */
    /**
     * root 直启：{@code su -c am start} 是 shell 身份，不受 HyperOS「链式启动 /
     * 后台弹出界面」那个 {@code ConfirmStartActivity} 确认框拦截。真机上之前的
     * startActivity 全被那个框吃掉，用户看到的就是「点了抓取，什么都没发生」。
     */
    private static int sRootLaunch = -1; // -1 未知 / 0 不可用 / 1 可用

    private boolean launchHostByRoot() {
        if (sRootLaunch == 0) return false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "am start -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity"});
            boolean done = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
            int rc = done ? p.exitValue() : -1;
            if (!done) try { p.destroy(); } catch (Throwable ignored) { }
            if (rc == 0) {
                sRootLaunch = 1;
                io.github.ldxm666.mapadkiller.SafeLog.i(io.github.ldxm666.mapadkiller.MainHook.TAG,
                        "event=cloud_launch root ok");
                return true;
            }
            if (rc == 1) sRootLaunch = 0;
            io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG,
                    "event=cloud_launch root failed rc=" + rc);
        } catch (Throwable t) {
            sRootLaunch = 0;
            io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG,
                    "event=cloud_launch root failed " + t);
        }
        return false;
    }

    private void launchHost() {
        try {
            if (launchHostByRoot()) return;
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage("com.autonavi.minimap");
            if (i == null) {
                toast("没找到高德地图，请手动打开一次");
                io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch no_launch_intent");
                return;
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                startActivity(i);
                io.github.ldxm666.mapadkiller.SafeLog.i(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch direct ok");
            } catch (Throwable t) {
                io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch direct failed " + t);
            }
            try {
                int flags = android.app.PendingIntent.FLAG_CANCEL_CURRENT;
                if (android.os.Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0x4D43, i, flags);
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(android.content.Context.ALARM_SERVICE);
                if (am != null) {
                    long at = System.currentTimeMillis() + 2500L;
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                        } else {
                            am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                        }
                    } catch (Throwable tt) {
                        am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                    }
                    io.github.ldxm666.mapadkiller.SafeLog.i(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch alarm_at=+2500ms");
                }
            } catch (Throwable t) {
                io.github.ldxm666.mapadkiller.SafeLog.w(io.github.ldxm666.mapadkiller.MainHook.TAG, "event=cloud_launch alarm failed " + t);
            }
        } catch (Throwable t) {
            toast("打开高德失败：" + t.getMessage());
        }
    }

    private void startCloudCapture() {
        try {
            final boolean isEmbedded = embedded();
            final boolean done = cloudDone;
            String message;
            if (done) {
                message = "已经抓过本版本的官方云端数据"
                        + (cloudFileCount > 0 ? "（" + cloudFileCount + " 个配置文件）" : "")
                        + "。\n\n"
                        + (isEmbedded
                        ? "重新抓取会先关闭模块并重启高德：正常用一遍（首页 / 搜索 / 我的），抓完再重启一次恢复你的设置。"
                        : "点「重新抓取」会自动打开高德：先以纯官方模式重跑一遍抓数据，抓完自动再重启一次恢复模块功能。");
            } else {
                message = "高德的首页卡片、金刚位、工具宫格、云控开关都是服务端下发的。"
                        + "首次必须先让高德跑一遍纯官方，把这份云端数据抓下来，之后才按你的设置改；"
                        + "否则会出现图标不显示、宫格缺格。\n\n"
                        + (isEmbedded
                        ? "点「开始抓取」后高德会重启一次，期间模块功能全部关闭（悬浮窗保留）。"
                        : "点「开始抓取」会自动打开高德并以纯官方模式跑一遍（期间模块功能关闭），抓完自动重启恢复；若高德没有自动回来，手动打开一次即可。\n\n"
                        + "抓取时请不要退出高德，停留 20 秒左右。");
            }
            glassDialog(done ? "重新抓取云数据？" : "抓取云数据", message,
                    done ? "重新抓取" : "开始抓取",
                    new Runnable() {
                        @Override public void run() { doStartCloudCapture(isEmbedded); }
                    },
                    null, null, "取消", null);
        } catch (Throwable t) {
            toast("操作失败：" + t.getMessage());
        }
    }

    private void doStartCloudCapture(boolean isEmbedded) {
        try {
            // 记下起点：成功回执要打 event=capture_done ms=…，没有起点这个字段永远是 0。
            captureAskedAt = System.currentTimeMillis();
            // 抓取期间用户基本都在高德里，结果只能靠通知栏送达 —— 顺手把权限要了。
            // 不申请的话 Android 13+ 会静默丢掉 notify()，用户又变成「抓完了也没提示」。
            ensureNotifyPermission();
            if (isEmbedded) {
                if (!CaptureGate.setCaptureMode(this, true)) {
                    toast("写入采集标记失败，请检查存储权限");
                    return;
                }
                toast("正在重启高德，请稍候…");
                CaptureGate.restartHost(this);
                return;
            }
            toast("正在请求抓取云数据…");
            new Thread(new Runnable() {
                @Override public void run() {
                    boolean result = false;
                    String error = null;
                    try {
                        result = io.github.ldxm666.mapadkiller.Baseline.requestRecapture();
                    } catch (Throwable t) {
                        error = String.valueOf(t.getMessage());
                    }
                    final boolean ok = result;
                    final String err = error;
                    if (err != null) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { toast("请求失败：" + err); }
                        });
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (ok) {
                                // 本地先记成「已请求」，宿主真正抓完会用广播把这一行改成「已抓取 N 项」。
                                markCapturePending();
                                cloudPending = true;
                                cloudError = null;
                                cloudStateKnown = true;
                                refreshDiagRows();
                                toast("已请求抓取 · 正在打开高德，若弹出“允许启动”请点允许");
                                launchHost();
                            } else {
                                String why = io.github.ldxm666.mapadkiller.Baseline.lastRequestError();
                                toast("请求失败：" + (why == null ? "框架服务未连接" : why));
                            }
                        }
                    });
                }
            }, "mapclean-cloud-request").start();
        } catch (Throwable t) {
            toast("操作失败：" + t.getMessage());
        }
    }

    /**
     * Android 13+ 的通知权限（POST_NOTIFICATIONS）。
     *
     * <p>为什么在「开始抓取」这一刻要：后台抓取完成时设置页一定不在前台，通知栏是唯一
     * 还能看见「抓完了」的通道；没授权时 {@code notify()} 会被系统静默丢弃 —— 用户看到
     * 的仍然是「抓完没反应」。在用户主动点抓取的瞬间申请，上下文最自然，也不会一启动
     * 就弹权限框。已授权 / 低版本直接返回。
     */
    private void ensureNotifyPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return;
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x4D43);
            android.util.Log.i("MapCleanCapture", "event=capture_notify_permission_requested");
        } catch (Throwable t) {
            // 申请失败不算错误：提示会退回 Toast，抓取本身一点不受影响。
            android.util.Log.w("MapCleanCapture", "event=capture_notify_permission_fail err=" + t);
        }
    }

    /** 运行日志入口：内置版直接读环形缓冲；模块版事件在宿主进程，走 root 从 logcat 捞一份。 */
    private void showLogDialog() {
        int count = 0;
        String body = "";
        try {
            count = CaptureLog.recentCount();
            body = CaptureLog.recentText(400);
        } catch (Throwable ignored) { }
        if (body == null) body = "";
        if (body.length() > 0 || embedded()) {
            showLogSheet(body, count);
            return;
        }
        toast("正在读取宿主日志…");
        new Thread(new Runnable() {
            @Override public void run() {
                String text = "";
                try {
                    Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "logcat -d -t 400 -s MapAdKiller:V MapCleanCfg:V MapCleanFloat:V MapCleanCapture:V"});
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(pr.getInputStream()));
                    StringBuilder b = new StringBuilder(32 * 1024);
                    String line;
                    while ((line = r.readLine()) != null) b.append(line).append('\n');
                    pr.waitFor();
                    text = b.toString();
                } catch (Throwable ignored) { }
                final String result = text;
                runOnUiThread(new Runnable() {
                    @Override public void run() { showLogSheet(result, 0); }
                });
            }
        }, "mapclean-host-log").start();
    }

    /** 日志弹层：等宽只读文本 + 复制 / 导出 txt。 */
    private void showLogSheet(String body, int count) {
        try {
            if (body == null) body = "";
            final String shown = body;
            if (body.length() == 0) {
                body = embedded()
                        ? "还没有日志事件。\n\n模块加载、hook 安装、巡航判定、抓取流程都会实时写进这里。"
                        : "还没有日志事件。\n\n没能读到宿主日志（需要 root）。\n点下方「导出 txt」可让宿主侧导出一份最近 10 分钟的运行日志。";
            }

            final AlertDialog dlg = new AlertDialog.Builder(this).create();

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(skin.glassFor(box, GLASS_R));
            box.setPadding(dp(18), dp(16), dp(18), dp(8));

            box.addView(text("运行日志", 17, Typeface.BOLD, cTxP()));
            TextView sub = text((count > 0 ? "最近 " + count + " 条事件" : "暂无事件")
                    + " · 环形缓冲保留 10 分钟", 11.5f, Typeface.NORMAL, cTxS());
            LinearLayout.LayoutParams subLp = skin.matchWrap();
            subLp.topMargin = dp(3);
            box.addView(sub, subLp);

            TextView tv = new TextView(this);
            tv.setText(body);
            tv.setTextSize(10.5f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(cTxS());
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(0, 1.15f);
            tv.setPadding(dp(10), dp(10), dp(10), dp(10));
            tv.setBackground(tintBg(dark ? 0x1FFFFFFF : 0x0A000000));

            ScrollView sc = new ScrollView(this);
            sc.setVerticalScrollBarEnabled(true);
            sc.addView(tv, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(dp(420), (int) (getResources().getDisplayMetrics().heightPixels * 0.52f)));
            scLp.topMargin = dp(12);
            box.addView(sc, scLp);

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(10);
            box.addView(btns, btnLp);

            btns.addView(sheetButton("复制", dlg, new Runnable() {
                @Override public void run() {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        String text = shown.length() > 0 ? shown : CaptureLog.recentText(400);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("MapCleanLog", text));
                        toast("已复制到剪贴板");
                    } catch (Throwable t) {
                        toast("复制失败：" + t.getMessage());
                    }
                }
            }), btnMargin());

            btns.addView(sheetButton("导出 txt", dlg, new Runnable() {
                @Override public void run() {
                    String path = null;
                    try { path = CaptureLog.dumpRecent(MainActivity.this); } catch (Throwable ignored) { }
                    toast(path == null ? "导出失败：请稍后重试" : "已导出：" + path);
                }
            }), btnMargin());

            btns.addView(sheetButton("关闭", dlg, null), btnMargin());

            dlg.setView(box);
            dlg.show();
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.dimAmount = 0.45f;
                lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(460));
                w.setAttributes(lp);
            }
        } catch (Throwable t) {
            toast("打开日志失败：" + t.getMessage());
        }
    }

    private LinearLayout.LayoutParams btnMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        return lp;
    }

    private TextView sheetButton(String label, final AlertDialog dlg, final Runnable action) {
        TextView b = text(label, 14, Typeface.BOLD, cAccent());
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(ripple(dark ? tintBg(0x2E7AB1FF) : tintBg(0x1E0A6CF5), HEAD_R));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (action != null) action.run();
                if (dlg != null) dlg.dismiss();
            }
        });
        return b;
    }

    /** 三格指标（版本 / 作用域 / 广告 SDK）：继续复用 refreshMetrics() 的刷新契约。 */
    private void buildHomeMetrics() {
        if (metricRow == null) return;
        metricRow.removeAllViews();
        metricRow.addView(homeMetric("版本"), weightLp(0));
        metricRow.addView(homeMetric("作用域"), weightLp(1));
        metricRow.addView(homeMetric("广告 SDK"), weightLp(2));
    }

    private View homeMetric(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView value = skin.text("—", 19f, Skin.BOLD, skin.text);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(value);
        TextView name = skin.text(label, Skin.T_LABEL, Skin.REGULAR, skin.text2);
        LinearLayout.LayoutParams nlp = skin.matchWrap();
        nlp.topMargin = skin.dp(2);
        box.addView(name, nlp);
        box.setTag(value);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showStatusDetail(); }
        });
        return box;
    }

    private LinearLayout.LayoutParams weightLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (index > 0) lp.leftMargin = skin.dp(Skin.S4);
        return lp;
    }

    private LinearLayout appRow(final String pkg, final String label, final int vendor) {
        String version = installedVersion(pkg);
        // 未安装：收成一行紧凑条目，且不画灰底单字图标（v2.2.5）
        if (version == null) return appRowUninstalled(pkg, label, vendor);

        LinearLayout row = skin.row();
        row.addView(appIcon(pkg, label), fixedMargin(skin, 42, 42, 12));

        int count = vendor == 0 ? bmapSwitches.size() : vendor == 1 ? switches.size() : tmapSwitches.size();
        String recommended = recommendedVersion(pkg);
        String caption = "推荐 " + (recommended == null ? "—" : recommended);
        if (version == null) caption += embedded() ? " · 未安装" : " · 本机未安装";
        else caption += " · 本机 " + version + (count > 0 ? " · " + count + " 项" : "");
        row.addView(skin.rowText(label, caption), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Icon chev = new Icon(this, Icon.CHEVRON, skin.text3);
        row.addView(chev, fixed(skin, 18, 18));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTab(TAB_CONFIG, true);
                selectApp(vendor);
            }
        });
        UiKit.pressable(row, 0.99f);
        return row;
    }

    /**
     * 未安装的地图：一行紧凑条目（44dp），不占 42dp 图标位、不画灰底单字。
     * 仍然可点 —— 进对应功能页能看到有哪些开关、推荐哪个版本，只是不再占一整坨。
     */
    private LinearLayout appRowUninstalled(final String pkg, final String label, final int vendor) {
        LinearLayout row = skin.row();
        row.setMinimumHeight(skin.dp(44));

        // 图标常态化：不再用 20dp 下载箭头（像「待下载的文件」），改成品牌色圆角方形 App 图标，
        // 与已安装项的 42dp 图标同一视觉语言。装了对应 App 会自动换成它自己的真图标。
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(skin.dp(32), skin.dp(32));
        ilp.rightMargin = skin.dp(12);
        // 优先用**该 App 的原生启动图标**（从它的 APK 里提出来内嵌的）；取不到才退回品牌色圆角图标。
        // 三家的原生图标都内嵌了一份，未安装时也能显示自己的图标（不再只有腾讯有）。
        android.graphics.drawable.Drawable real =
                "com.tencent.map".equals(pkg) ? TencentIconAsset.drawable(this)
              : "com.baidu.BaiduMap".equals(pkg) ? BaiduIconAsset.drawable(this)
              : "com.autonavi.minimap".equals(pkg) ? AmapIconAsset.drawable(this) : null;
        if (real != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(real);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            row.addView(iv, ilp);
        } else {
            row.addView(new AppIcon(this, vendor), ilp);
        }

        // 名称正常色 +「未安装」红色。两个 TextView 比 SpannableString 更简单，也不会多引类。
        LinearLayout nameWrap = new LinearLayout(this);
        nameWrap.setOrientation(LinearLayout.HORIZONTAL);
        nameWrap.setGravity(android.view.Gravity.CENTER_VERTICAL);
        nameWrap.addView(skin.text(label, Skin.T_ROW, Skin.MEDIUM, skin.text2));
        nameWrap.addView(skin.text(embedded() ? " · 未安装" : " · 本机未安装",
                Skin.T_ROW, Skin.MEDIUM, 0xFFE53935));
        row.addView(nameWrap, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 未安装时**不再**显示「推荐 x」：那是模块验证过的固定版本号，跟用户手机装什么无关，
        // 摆出来只会让人以为要装这个版本（用户反馈「推荐版本是测试机的固定版本」）。
        // 已安装的行里「推荐」直接等于本机版本，见 recommendedVersion()。
        row.addView(new Icon(this, Icon.CHEVRON, skin.text3), fixed(skin, 18, 18));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTab(TAB_CONFIG, true);
                selectApp(vendor);
            }
        });
        UiKit.pressable(row, 0.99f);
        return row;
    }

    /** 模块当前验证过的目标版本（Version.TARGETS 是唯一事实源）。 */
    private String recommendedVersion(String pkg) {
        // Recommendation follows the build actually installed on this device: the module
        // is verified against that build, so showing a hard-coded version that differs
        // from the phone just makes the row look wrong.
        String installed = installedVersion(pkg);
        if (installed != null && installed.length() > 0) return installed;
        for (int i = 0; i < Version.TARGETS.length; i++) {
            if (Version.TARGETS[i][0].equals(pkg)) return Version.TARGETS[i][2];
        }
        return null;
    }

    /** 装了的应用用真实图标；取不到图标时用 accent 字标兜底（不再用灰底灰字）。未安装的行不走这里。 */
    private View appIcon(String pkg, String label) {
        FrameLayout box = new FrameLayout(this);
        Drawable icon = null;
        try { icon = getPackageManager().getApplicationIcon(pkg); } catch (Throwable ignored) { }
        if (icon != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // 字标兜底：accent 底 + 白字，看起来像有意设计，而不是灰底灰字的半成品
            TextView letter = skin.text(label.substring(0, 1), 16f, Skin.BOLD, skin.onAccent);
            letter.setGravity(Gravity.CENTER);
            box.setBackground(skin.solid(skin.accent, 11));
            box.addView(letter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        UiKit.clipRounded(box, skin.dp(11));
        return box;
    }

    private LinearLayout buildAuthorCard() {
        LinearLayout card = skin.listCard();
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setPadding(skin.dp(Skin.S2), skin.dp(Skin.S2), skin.dp(Skin.S2), skin.dp(Skin.S2));

        ImageView avatar = new ImageView(this);
        Drawable face = AvatarAsset.drawable(this);
        if (face != null) avatar.setImageDrawable(face);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(skin.dp(44), skin.dp(44));
        avLp.rightMargin = skin.dp(Skin.S3);
        who.addView(avatar, avLp);
        UiKit.clipRounded(avatar, skin.dp(22));

        who.addView(skin.rowText("ldxm666", "开源作者 · 地图广告净化"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(who);
        card.addView(skin.hairline());

        GithubMark mark = new GithubMark(this);
        mark.setColor(skin.text);
        card.addView(linkRow(mark, "GitHub 主页", "github.com/ldxm666", Version.REPO));
        card.addView(skin.hairline());
        Icon send = new Icon(this, Icon.SEND, skin.accent);
        card.addView(linkRow(send, "Telegram 群组", "t.me/+2rqisPe5tJxjNTk1", Version.TG));
        return card;
    }

    private LinearLayout linkRow(View icon, String title, String subtitle, final String url) {
        LinearLayout row = skin.row();
        LinearLayout badge = new LinearLayout(this);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(skin.solid(skin.chipBg, 10));
        badge.addView(icon, fixed(skin, 18, 18));
        row.addView(badge, fixedMargin(skin, 38, 38, 12));
        row.addView(skin.rowText(title, subtitle), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Icon chev = new Icon(this, Icon.EXTERNAL, skin.text3);
        row.addView(chev, fixed(skin, 16, 16));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(url); }
        });
        return row;
    }

    private View iconTile(int kind, int color, int fill) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(skin.solid(fill, 11));
        box.addView(new Icon(this, kind, color), fixed(skin, 20, 20));
        return box;
    }

    private static LinearLayout.LayoutParams fixed(Skin s, int wDp, int hDp) {
        return new LinearLayout.LayoutParams(s.dp(wDp), s.dp(hDp));
    }

    private static LinearLayout.LayoutParams fixedMargin(Skin s, int wDp, int hDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s.dp(wDp), s.dp(hDp));
        lp.rightMargin = s.dp(rightDp);
        return lp;
    }

    private static LinearLayout.LayoutParams withTop(Skin s, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = s.dp(topDp);
        return lp;
    }

    /* ──────────────────────────────────────────────────────────── 赞赏 */

    /** 「赞赏支持」行：骨架照抄 linkRow，只是点开的是收款码对话框而不是外链。 */
    private View donateRow() {
        LinearLayout row = skin.row();
        LinearLayout badge = new LinearLayout(this);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(skin.solid(skin.chipBg, 10));
        badge.addView(new Icon(this, Icon.SEND, skin.accent), fixed(skin, 18, 18));
        row.addView(badge, fixedMargin(skin, 38, 38, 12));
        row.addView(skin.rowText("赞赏支持", "感谢投喂 · 扫码请作者喝杯咖啡"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(new Icon(this, Icon.CHEVRON, skin.text3), fixed(skin, 16, 16));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDonateDialog(); }
        });
        return row;
    }

    /** 收款码来自 DonateAsset（base64 内嵌，无 R 类）；解码失败退化成一行文字，不崩。 */
    private void showDonateDialog() {
        // 统一到 DonateUi，模块版与内置版同一套观感
        DonateUi.show(this);
    }

    /* ──────────────────────────────────────────────────────────── 配置页 */

    private View buildConfigPage() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0x00000000);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(skin.dp(Skin.S5), skin.dp(Skin.S3), skin.dp(Skin.S5), 0);
        head.addView(skin.display("配置"));
        TextView sub = skin.caption("按应用调整模块开关");
        LinearLayout.LayoutParams subLp = skin.matchWrap();
        subLp.topMargin = skin.dp(3);
        head.addView(sub, subLp);
        head.setBackground(skin.headerScrim());
        screen.addView(head, skin.matchWrap());

        LinearLayout segment = new LinearLayout(this);
        segment.setOrientation(LinearLayout.HORIZONTAL);
        segment.setPadding(skin.dp(4), skin.dp(4), skin.dp(4), skin.dp(4));
        segment.setBackground(skin.solid(skin.surface2, Skin.R_INNER));
        if (embedded()) {
            addAppChip(segment, embeddedAmap() ? "高德地图" : "百度地图");
        } else {
            addAppChip(segment, "百度地图");
            addAppChip(segment, "高德地图");
            addAppChip(segment, "腾讯地图");
        }
        LinearLayout.LayoutParams segLp = skin.matchWrap();
        segLp.topMargin = skin.dp(Skin.S4);
        segLp.leftMargin = skin.dp(Skin.S5);
        segLp.rightMargin = skin.dp(Skin.S5);
        screen.addView(segment, segLp);

        configSummary = skin.footnote("");
        LinearLayout.LayoutParams cslp = skin.matchWrap();
        cslp.topMargin = skin.dp(Skin.S3);
        cslp.leftMargin = skin.dp(Skin.S5 + 2);
        cslp.rightMargin = skin.dp(Skin.S5);
        screen.addView(configSummary, cslp);

        // 内置版只读说明条：不是脚注 —— 整页置灰之后，这一条是用户唯一的出口提示，
        // 必须首屏可见、且「长按悬浮球」几个字要能一眼抓住（见 applyEmbeddedNotice 的 Spannable）。
        embeddedNotice = skin.text("", Skin.T_BODY, Skin.MEDIUM, skin.text);
        embeddedNotice.setVisibility(View.GONE);
        embeddedNotice.setLineSpacing(skin.dp(3), 1f);
        int noticePad = skin.dp(Skin.S3);
        embeddedNotice.setPadding(noticePad, noticePad, noticePad, noticePad);
        embeddedNotice.setBackground(skin.solid(skin.surface2, Skin.R_INNER));
        LinearLayout.LayoutParams noticeLp = skin.matchWrap();
        noticeLp.topMargin = skin.dp(Skin.S3);
        noticeLp.leftMargin = skin.dp(Skin.S5);
        noticeLp.rightMargin = skin.dp(Skin.S5);
        screen.addView(embeddedNotice, noticeLp);

        View searchBar = finder.bar();
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(44));
        searchLp.topMargin = skin.dp(Skin.S3);
        searchLp.leftMargin = skin.dp(Skin.S5);
        searchLp.rightMargin = skin.dp(Skin.S5);
        screen.addView(searchBar, searchLp);
        View resultsHost = finder.resultsHost();
        try {
            if (resultsHost instanceof android.widget.HorizontalScrollView) {
                android.widget.HorizontalScrollView hs = (android.widget.HorizontalScrollView) resultsHost;
                hs.setClipToPadding(false);
                hs.setPadding(skin.dp(Skin.S5), skin.dp(Skin.S2), skin.dp(Skin.S5), 0);
                // 结果统计胶囊：SwitchFinder 里 results 是 VERTICAL 的，塞进横向滚动容器后
                // 会一个个竖着排（截图里「百度 · 2」「高德 · 2」各占一行）。这里掰回横排。
                if (hs.getChildCount() > 0 && hs.getChildAt(0) instanceof LinearLayout) {
                    ((LinearLayout) hs.getChildAt(0)).setOrientation(LinearLayout.HORIZONTAL);
                }
            }
        } catch (Throwable ignored) { }
        screen.addView(resultsHost, skin.matchWrap());


        configHost = new FrameLayout(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hostLp.topMargin = skin.dp(Skin.S2);
        screen.addView(configHost, hostLp);

        if (embedded()) {
            addAppPage(embeddedAmap() ? buildAmapPage() : buildBaiduPage(),
                    embeddedAmap() ? PAGE_AMAP : PAGE_BAIDU);
        } else {
            addAppPage(buildBaiduPage(), PAGE_BAIDU);
            addAppPage(buildAmapPage(), PAGE_AMAP);
            addAppPage(buildTmapPage(), PAGE_TMAP);
        }
        selectApp(0);
        probeEmbeddedBuilds();
        return screen;
    }

    private void addAppChip(LinearLayout segment, String label) {
        final int index = appChips.size();
        TextView chip = skin.text(label, Skin.T_CAPTION + 0.5f, Skin.MEDIUM, skin.text2);
        chip.setGravity(Gravity.CENTER);
        chip.setClickable(true);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectApp(index); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, skin.dp(38), 1f);
        if (index > 0) lp.leftMargin = skin.dp(2);
        segment.addView(chip, lp);
        appChips.add(chip);
    }

    private void addAppPage(ScrollView page, int pageId) {
        page.setVisibility(View.GONE);
        appPages.add(page);
        appPageIds.add(pageId);
        configHost.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 切换地图应用（0 百度 / 1 高德 / 2 腾讯；内置版只有一个，下标恒为 0）。 */
    private void selectApp(int index) {
        if (index < 0 || index >= appPages.size()) return;
        appIndex = index;
        for (int i = 0; i < appPages.size(); i++) {
            appPages.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < appChips.size(); i++) {
            TextView chip = appChips.get(i);
            boolean on = i == index;
            chip.setTextColor(on ? skin.accent : skin.text2);
            chip.setTypeface(on ? Skin.MEDIUM : Skin.REGULAR);
            chip.setBackground(on ? skin.border(skin.surface, skin.stroke, 11) : null);
            if (on) {
                chip.setScaleX(0.96f);
                chip.setScaleY(0.96f);
                chip.animate().scaleX(1f).scaleY(1f)
                        .setDuration(220L).setInterpolator(UiKit.SPRING).start();
            }
        }
        if (configSummary != null && index < appChips.size() && index < appPageIds.size()) {
            int pageId = appPageIds.get(index);
            int count = pageId == PAGE_BAIDU ? bmapSwitches.size()
                    : pageId == PAGE_AMAP ? switches.size() : tmapSwitches.size();
            configSummary.setText(appChips.get(index).getText() + " · " + count + " 项开关");
        }
        if (tabIndex == TAB_CONFIG && finder != null) {
            finder.attach(appPageIds.get(index));
        }
        applyEmbeddedNotice();
    }

    // ------------------------------------------------ 内置版只读闸（独立 App 侧）

    /**
     * 后台判定三个宿主包是不是 LSPatch 内置版。
     *
     * <p>只在<b>独立 App</b> 里跑：进程内面板（{@code embedded()==true}）本身就活在地图进程里，
     * 写的就是宿主 prefs，不需要也没必要判。
     */
    private void probeEmbeddedBuilds() {
        if (embedded()) return;
        for (int i = 0; i < appPageIds.size(); i++) {
            final int pageId = appPageIds.get(i).intValue();
            final String pkg = hostPackageOf(pageId);
            if (pkg == null) continue;
            EmbeddedBuild.detect(this, pkg, new EmbeddedBuild.Callback() {
                @Override public void onResult(final boolean isEmbeddedBuild) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            embeddedPages.put(Integer.valueOf(pageId),
                                    Boolean.valueOf(isEmbeddedBuild));
                            applyEmbeddedNotice();
                        }
                    });
                }
            });
        }
    }

    private static String hostPackageOf(int pageId) {
        if (pageId == PAGE_BAIDU) return EmbeddedSettingsPolicy.BAIDU;
        if (pageId == PAGE_AMAP) return EmbeddedSettingsPolicy.AMAP;
        if (pageId == PAGE_TMAP) return EmbeddedSettingsPolicy.TENCENT;
        return null;
    }

    /**
     * 当前页若是内置版宿主：顶部出说明条 + 整页置灰不可点。
     *
     * <p>为什么必须锁而不能只提示：UI 是「先写先翻」—— 开关拨过去、Toast 也可能报成功，
     * 但写的是<b>本 App 沙箱</b>，宿主进程读的是它自己的沙箱，永远读不到。
     * 只提示不锁，用户还是会以为改好了。
     */
    private void applyEmbeddedNotice() {
        TextView notice = embeddedNotice;
        if (notice == null) return;
        int pageId = (appIndex >= 0 && appIndex < appPageIds.size())
                ? appPageIds.get(appIndex).intValue() : -1;
        boolean locked = Boolean.TRUE.equals(embeddedPages.get(Integer.valueOf(pageId)));
        notice.setVisibility(locked ? View.VISIBLE : View.GONE);
        if (!locked) return;
        notice.setText(buildEmbeddedNoticeText());
        if (embeddedLocked.add(Integer.valueOf(pageId))) {
            View page = (appIndex >= 0 && appIndex < appPages.size()) ? appPages.get(appIndex) : null;
            lockPage(page);
            if (page != null) page.setAlpha(0.6f);
        }
    }

    /**
     * 说明条文案：**「长按悬浮球」几个字单独加粗 + 主色**。
     *
     * <p>整页置灰之后用户能读到的只有这一条，所以它不能和旁边的说明文字一个灰度 ——
     * 用 {@link StyleSpan} 加粗 + {@link ForegroundColorSpan} 上主色，扫一眼就能落到
     * "该去哪儿改"这个动作上。
     */
    private CharSequence buildEmbeddedNoticeText() {
        String head = "当前是免 root 内置版：这里的开关写不进地图，改了也不生效。";
        String action = "请在地图里轻点悬浮球";
        String tail = "，用悬浮球面板修改 —— 那一套才是真正生效的配置。";
        String all = head + "\n" + action + tail;
        SpannableString span = new SpannableString(all);
        int start = head.length() + 1;
        int end = start + action.length();
        try {
            span.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(cAccent()), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Throwable ignored) { }
        return span;
    }

    /**
     * 把一页里所有可交互控件关掉，<b>但保留滚动</b>（长页面还得能看）。
     *
     * <p>两道保险：{@code setEnabled/setClickable} 走框架的分发拦截；
     * 对自绘控件（{@link GlassSwitch} / {@link GlassSlider}）再加一个吞掉手势的
     * {@code OnTouchListener}，因为它们各自重写了 {@code onTouchEvent}，
     * 只靠 enabled 状态不一定拦得住。
     */
    private static void lockPage(View v) {
        if (v == null) return;
        if (!(v instanceof ScrollView)) {
            if (v.isClickable()) v.setClickable(false);
            if (v.isLongClickable()) v.setLongClickable(false);
            if (v.isEnabled()) v.setEnabled(false);
            if (v instanceof GlassSwitch || v instanceof GlassSlider) {
                v.setOnTouchListener(new View.OnTouchListener() {
                    @Override public boolean onTouch(View view, MotionEvent event) {
                        return true;    // 吞掉，不产生任何状态变化
                    }
                });
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                lockPage(group.getChildAt(i));
            }
        }
    }

    /* ──────────────────────────────────────────────────── 底部标签栏 */

    private LinearLayout buildBottomNav() {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setBackgroundColor(0x00000000);
        navBar.setPadding(skin.dp(16), skin.dp(6), skin.dp(16), skin.dp(10));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setPadding(skin.dp(8), skin.dp(6), skin.dp(8), skin.dp(6));
        pill.setBackground(skin.glassFor(pill, 26));
        UiKit.clipRounded(pill, skin.dp(26));
        skin.shadow(pill, 10f);
        navBar.addView(pill, skin.matchWrap());

        addNavItem(pill, Icon.HOME, "首页");
        addNavItem(pill, Icon.TUNE, "配置");
        addNavItem(pill, Icon.ARCHIVE, "备份");
        addNavItem(pill, Icon.INFO, "关于");
        return navBar;
    }

    private void addNavItem(LinearLayout parent, int kind, String label) {
        final int index = navItems.size();
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, skin.dp(4), 0, skin.dp(4));
        item.setBackground(UiKit.rippleBare(dark ? 0x22FFFFFF : 0x0F000000, skin.dp(14)));

        FrameLayout pill = new FrameLayout(this);
        Icon icon = new Icon(this, kind, skin.text2);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                skin.dp(22), skin.dp(22), Gravity.CENTER);
        pill.addView(icon, iconLp);
        item.addView(pill, new LinearLayout.LayoutParams(skin.dp(46), skin.dp(28)));

        TextView text = skin.text(label, 10.5f, Skin.MEDIUM, skin.text2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = skin.dp(2);
        item.addView(text, tlp);

        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(index, true); }
        });
        parent.addView(item, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        navItems.add(item);
        navIcons.add(icon);
        navLabels.add(text);
        item.setTag(pill);
    }

    private void applyNav() {
        for (int i = 0; i < navItems.size(); i++) {
            boolean on = i == tabIndex;
            navIcons.get(i).setColor(on ? skin.accent : skin.text2);
            navLabels.get(i).setTextColor(on ? skin.accent : skin.text2);
            navLabels.get(i).setTypeface(on ? Skin.MEDIUM : Skin.REGULAR);
            navItems.get(i).setBackground(on
                    ? skin.solid(skin.accentSoft, 16) : null);
            if (on) {
                View item = navItems.get(i);
                item.setScaleX(0.94f);
                item.setScaleY(0.94f);
                item.animate().scaleX(1f).scaleY(1f)
                        .setDuration(260L).setInterpolator(UiKit.SPRING).start();
                navIcons.get(i).setScaleX(0.86f);
                navIcons.get(i).setScaleY(0.86f);
                navIcons.get(i).animate().scaleX(1f).scaleY(1f)
                        .setDuration(260L).setInterpolator(UiKit.SPRING).start();
            }
        }
    }

    private void showTab(int index, boolean animate) {
        if (index < TAB_HOME || index > TAB_ABOUT || pager == null) return;
        pager.setPage(index, animate);
        if (!animate) onTabShown(index);
    }

    /** Tab is on screen: refresh the bar, wire the search index, restore scroll state. */
    private void onTabShown(int index) {
        tabIndex = index;
        if (index == TAB_CONFIG) {
            if (finder != null && appIndex < appPageIds.size()) {
                finder.attach(appPageIds.get(appIndex));
            }
        } else if (finder != null) {
            finder.dismissKeyboard();
        }
        applyNav();
        if (index == TAB_HOME && !homeAnimated && homePage != null && homePage.getChildCount() > 0) {
            homeAnimated = true;
            UiKit.enterStaggered((ViewGroup) homePage.getChildAt(0), skin.dp(12), 32L, 30L);
        }
    }

    /* ────────────────────────────────────────────────────────────── 备份页 */

    private ScrollView buildBackupPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(skin.dp(Skin.S5), skin.dp(Skin.S3), skin.dp(Skin.S5), skin.dp(Skin.S7));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(skin.display("备份"));
        TextView sub = skin.caption("配置文件 · 快照 · 跨设备迁移");
        LinearLayout.LayoutParams subLp = skin.matchWrap();
        subLp.topMargin = skin.dp(3);
        head.addView(sub, subLp);
        head.setBackground(skin.headerScrim());
        root.addView(head, skin.matchWrap());

        backupStatus = skin.text("", Skin.T_CAPTION, Skin.REGULAR, skin.text3);
        LinearLayout.LayoutParams bslp = skin.matchWrap();
        bslp.topMargin = skin.dp(Skin.S3);
        root.addView(backupStatus, bslp);

        // 导出
        LinearLayout export = skin.card();
        LinearLayout exportHead = new LinearLayout(this);
        exportHead.setOrientation(LinearLayout.HORIZONTAL);
        exportHead.setGravity(Gravity.CENTER_VERTICAL);
        exportHead.addView(iconTile(Icon.DOWNLOAD, skin.accent, skin.accentSoft), fixed(skin, 40, 40));
        LinearLayout exportText = new LinearLayout(this);
        exportText.setOrientation(LinearLayout.VERTICAL);
        exportText.addView(skin.rowTitle("导出配置文件"));
        exportText.addView(skin.caption("保存为 .json 文件，可用于备份或迁移到其它设备"));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        etLp.leftMargin = skin.dp(Skin.S3);
        exportHead.addView(exportText, etLp);
        export.addView(exportHead, skin.matchWrap());

        TextView exportNote = skin.footnote("将包含全部开关：" + configCounts());
        LinearLayout.LayoutParams enLp = skin.matchWrap();
        enLp.topMargin = skin.dp(Skin.S3);
        export.addView(exportNote, enLp);

        TextView exportBtn = skin.primaryButton("导出 JSON 文件");
        LinearLayout.LayoutParams ebLp = skin.matchWrap();
        ebLp.topMargin = skin.dp(Skin.S4);
        export.addView(exportBtn, ebLp);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ConfigBackup.bind(configStore(), configSchema(), getPackageName());
                ConfigBackup.startExport(MainActivity.this, ConfigBackup.REQ_EXPORT);
                String error = ConfigBackup.takeError();
                if (error != null) {
                    setBackupStatus(error, true);
                } else {
                    setBackupStatus("已打开系统保存对话框，选择位置即可导出", false);
                }
            }
        });
        root.addView(export, withTop(skin, Skin.S4));

        // 导入
        LinearLayout imp = skin.card();
        LinearLayout impHead = new LinearLayout(this);
        impHead.setOrientation(LinearLayout.HORIZONTAL);
        impHead.setGravity(Gravity.CENTER_VERTICAL);
        impHead.addView(iconTile(Icon.UPLOAD, skin.accent, skin.accentSoft), fixed(skin, 40, 40));
        LinearLayout impText = new LinearLayout(this);
        impText.setOrientation(LinearLayout.VERTICAL);
        impText.addView(skin.rowTitle("导入配置文件"));
        impText.addView(skin.caption("选择 .json 文件，先预览再写入，不会静默覆盖"));
        LinearLayout.LayoutParams itLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itLp.leftMargin = skin.dp(Skin.S3);
        impHead.addView(impText, itLp);
        imp.addView(impHead, skin.matchWrap());

        TextView importBtn = skin.secondaryButton("选择 JSON 文件");
        LinearLayout.LayoutParams ibLp = skin.matchWrap();
        ibLp.topMargin = skin.dp(Skin.S4);
        imp.addView(importBtn, ibLp);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ConfigBackup.bind(configStore(), configSchema(), getPackageName());
                ConfigBackup.startImport(MainActivity.this, ConfigBackup.REQ_IMPORT);
                String error = ConfigBackup.takeError();
                if (error != null) setBackupStatus(error, true);
            }
        });
        TextView importHint = skin.footnote("兼容旧版「复制粘贴」的文本：存成 .json 后同样可以导入。");
        LinearLayout.LayoutParams ihLp = skin.matchWrap();
        ihLp.topMargin = skin.dp(Skin.S3);
        imp.addView(importHint, ihLp);
        root.addView(imp, withTop(skin, Skin.S3));

        // 本机快照
        LinearLayout snap = skin.card();
        snap.addView(skin.rowTitle("本机快照"));
        TextView snapNote = skin.caption("保存在本机；重装应用或清除数据后失效");
        LinearLayout.LayoutParams snLp = skin.matchWrap();
        snLp.topMargin = skin.dp(3);
        snap.addView(snapNote, snLp);

        LinearLayout snapRow = new LinearLayout(this);
        snapRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView saveBtn = skin.secondaryButton("保存快照");
        TextView restoreBtn = skin.secondaryButton("恢复快照");
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbLp.rightMargin = skin.dp(Skin.S2);
        snapRow.addView(saveBtn, sbLp);
        snapRow.addView(restoreBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams srLp = skin.matchWrap();
        srLp.topMargin = skin.dp(Skin.S4);
        snap.addView(snapRow, srLp);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveSnapshot();
                setBackupStatus("已保存本机快照", false);
            }
        });
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (readSnapshot() == null) {
                    setBackupStatus("还没有保存过快照", true);
                    return;
                }
                glassDialog("恢复快照？", "将覆盖当前全部开关，回到上次保存的快照。",
                        "恢复", new Runnable() {
                            @Override public void run() { restoreSnapshot(); }
                        }, null, null, "取消", null);
            }
        });
        root.addView(snap, withTop(skin, Skin.S3));

        // 高级：文本方式
        skin.addSection(root, "高级");
        LinearLayout advanced = skin.listCard();
        advanced.addView(advancedRow(Icon.COPY, "复制配置文本", "发给其它设备粘贴导入", new Runnable() {
            @Override public void run() { exportConfig(); }
        }));
        advanced.addView(skin.hairline());
        advanced.addView(advancedRow(Icon.PASTE, "从剪贴板导入", "粘贴旧版文本并预览", new Runnable() {
            @Override public void run() { importConfig(); }
        }));
        root.addView(advanced, skin.matchWrap());

        LinearLayout danger = skin.card();
        danger.setBackground(skin.glassFor(danger, Skin.R_CARD));
        danger.addView(advancedRow(Icon.TRASH, "恢复默认设置", "清空本模块写入的开关，回到出厂值",
                new Runnable() {
                    @Override public void run() {
                        glassDialog("恢复默认？", "恢复" + settingsTargetLabel()
                                        + "的默认开关，强停地图 App 后生效。",
                                "恢复默认", new Runnable() {
                                    @Override public void run() { resetConfig(); }
                                }, null, null, "取消", null);
                    }
                }));
        root.addView(danger, withTop(skin, Skin.S3));
        return sv;
    }

    private View advancedRow(int kind, String title, String caption, final Runnable action) {
        LinearLayout row = skin.row();
        Icon icon = new Icon(this, kind, skin.text2);
        row.addView(icon, fixedMargin(skin, 20, 20, 14));
        row.addView(skin.rowText(title, caption), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return row;
    }

    private void setBackupStatus(String message, boolean error) {
        if (backupStatus == null) return;
        backupStatus.setText(message);
        backupStatus.setTextColor(error ? skin.danger : skin.text3);
    }

    private String configCounts() {
        StringBuilder sb = new StringBuilder();
        if (!embedded() || !embeddedAmap()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("百度 ").append(bmapSwitches.size()).append(" 项");
        }
        if (!embedded() || embeddedAmap()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("高德 ").append(switches.size()).append(" 项");
        }
        if (!embedded()) {
            sb.append(" · 腾讯 ").append(tmapSwitches.size()).append(" 项");
        }
        return sb.toString();
    }

    /* ────────────────────────────────────────────────────────────── 关于页 */

    private ScrollView buildAboutPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(skin.dp(Skin.S5), skin.dp(Skin.S3), skin.dp(Skin.S5), skin.dp(Skin.S7));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(skin.display("关于"));
        TextView sub = skin.caption("版本信息 · 作者链接 · 通用设置");
        LinearLayout.LayoutParams subLp = skin.matchWrap();
        subLp.topMargin = skin.dp(3);
        head.addView(sub, subLp);
        head.setBackground(skin.headerScrim());
        root.addView(head, skin.matchWrap());

        // 应用身份卡
        LinearLayout identity = skin.card();
        LinearLayout identityRow = new LinearLayout(this);
        identityRow.setOrientation(LinearLayout.HORIZONTAL);
        identityRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout logo = new FrameLayout(this);
        Drawable launcher = null;
        try { launcher = ModuleResources.drawable(this, "ic_launcher"); } catch (Throwable ignored) { }
        if (launcher != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(launcher);
            logo.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        UiKit.clipRounded(logo, skin.dp(14));
        identityRow.addView(logo, fixedMargin(skin, 56, 56, 14));
        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.addView(skin.title("MapAdKiller"));
        nameCol.addView(skin.caption("版本 " + Version.LABEL + " · 构建 " + Version.CODE));
        identityRow.addView(nameCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        identityRow.addView(skin.chip(embedded() ? "整合版" : "模块版",
                skin.accentSoft, skin.accent));
        identity.addView(identityRow, skin.matchWrap());

        root.addView(identity, withTop(skin, Skin.S4));

        // 支持版本：模块真正验证过的地图版本，和本机安装版本并排显示
        skin.addSection(root, "支持版本");
        LinearLayout versions = skin.listCard();
        boolean firstVersion = true;
        for (int i = 0; i < Version.TARGETS.length; i++) {
            final String pkg = Version.TARGETS[i][0];
            if (embedded() && !pkg.equals(embeddedHost)) continue;
            if (!firstVersion) versions.addView(skin.hairline());
            firstVersion = false;
            LinearLayout row = skin.row();
            row.setClickable(false);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(skin.rowTitle(Version.TARGETS[i][1]));
            TextView rec = skin.caption("推荐版本 " + recommendedVersion(pkg));
            LinearLayout.LayoutParams recLp = skin.matchWrap();
            recLp.topMargin = skin.dp(2);
            col.addView(rec, recLp);
            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String installed = installedVersion(pkg);
            boolean match = installed != null && installed.startsWith(Version.TARGETS[i][2]);
            row.addView(skin.chip(installed == null ? "未安装" : (match ? "本机 " + installed : "本机 " + installed),
                    installed == null ? skin.chipBg : (match ? (dark ? 0x1F34D399 : 0x1416A34A)
                                                              : (dark ? 0x2AFBBF24 : 0x1AF79009)),
                    installed == null ? skin.text3 : (match ? skin.success : skin.warn)));
            versions.addView(row);
        }
        root.addView(versions, skin.matchWrap());

        // 链接卡
        skin.addSection(root, "链接");
        LinearLayout links = skin.listCard();
        GithubMark mark = new GithubMark(this);
        mark.setColor(skin.text);
        links.addView(linkRow(mark, "GitHub 仓库", "github.com/ldxm666/MapAdKiller", Version.REPO));
        links.addView(skin.hairline());
        links.addView(linkRow(new Icon(this, Icon.DOWNLOAD, skin.accent),
                "版本发布", "最新安装包与更新日志", Version.RELEASES));
        links.addView(skin.hairline());
        links.addView(linkRow(new Icon(this, Icon.SEND, skin.accent),
                "Telegram 群组", "反馈 / 交流 / 第一时间更新", Version.TG));
        links.addView(skin.hairline());
        links.addView(linkRow(new Icon(this, Icon.INFO, skin.text2),
                "问题反馈", "GitHub Issues", Version.ISSUES));
        root.addView(links, skin.matchWrap());

        // 通用设置
        skin.addSection(root, "通用");
        LinearLayout general = newCard();
        if (embedded()) {
            addNoteRow(general, "当前为免 root 整合版：设置只作用于本地图，配置保存在本地图的嵌入式模块服务中。");
            if (embeddedAmap()) addSwitch(general, "调试日志", Config.K_DEBUG_LOG);
        } else {
            addLocalSwitch(general, "隐藏桌面图标", App.K_HIDE_ICON);
            addNoteRow(general, "隐藏后桌面图标消失；LSPosed 管理器里的「打开」入口不受影响，"
                    + "快捷设置磁贴与 adb 命令也始终可用。");
            addSwitch(general, "调试日志（框架输出协议适配错误）", Config.K_DEBUG_LOG);
        }
        root.addView(general, skin.matchWrap());

        // 说明
        skin.addSection(root, "说明");
        LinearLayout note = skin.card();
        TextView body = skin.body("本模块免费开源，禁止任何形式的倒卖。\n"
                + "广告与推荐内容的拦截规则随地图版本变化，升级地图后请回来看一眼开关状态。\n"
                + "遇到问题请带上机型、地图版本与日志到 GitHub Issues 或 Telegram 群反馈。");
        body.setLineSpacing(skin.dp(3), 1.25f);
        note.addView(body, skin.matchWrap());
        root.addView(note, skin.matchWrap());

        TextView foot = skin.footnote("MapAdKiller v" + Version.LABEL + " · 免费开源 · 禁止倒卖");
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = skin.matchWrap();
        flp.topMargin = skin.dp(Skin.S6);
        root.addView(foot, flp);
        return sv;
    }





    // ---------------------------------------------------------------- 基础工厂

    private TextView text(String s, float sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(style == Typeface.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT, style);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
