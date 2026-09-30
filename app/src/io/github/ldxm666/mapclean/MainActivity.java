package io.github.ldxm666.mapclean;

import io.github.ldxm666.mapadkiller.App;
import io.github.ldxm666.mapadkiller.Config;
import io.github.ldxm666.mapadkiller.DiagPage;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ── v2.0.0 分页：百度 / 高德 / 其他，左右滑动切换 ──────────────────────
    private Pager pager;
    private LinearLayout tabBar;
    private final java.util.List<TextView> tabChips = new java.util.ArrayList<>();
    private final java.util.List<android.widget.ScrollView> pages = new java.util.ArrayList<>();
    /** 百度那一页的开关（配置 group 与高德不同，要单独同步） */
    private final Map<String, Switch> bmapSwitches = new LinkedHashMap<>();

    private static final int GLASS_R = 22;   // 卡片圆角 dp
    private static final int HEAD_R = 18;    // 抽屉头/按钮圆角 dp

    private boolean dark;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = isDarkMode();
        // 深浅两套框架主题：对话框（AlertDialog）会跟随 Activity 主题，省掉自建 styles.xml
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                      : android.R.style.Theme_Material_Light_NoActionBar);
        getWindow().setBackgroundDrawable(new ColorDrawable(cBg()));

        page = new FrameLayout(this);
        setContentView(page);
        addBlobs(page);

        // ── 顶部固定区：状态卡 + 分页标签 ──────────────────────────────
        final LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        top.setPadding(p, dp(10), p, dp(2));
        buildHero(top);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        top.addView(tabBar, tlp);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        page.addView(top, topLp);

        // ── 分页容器 ──────────────────────────────────────────────────
        pager = new Pager(this);
        pager.setOnPageChanged(new Pager.OnPageChanged() {
            @Override public void onPageChanged(int index) { selectTab(index); }
        });
        final FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.topMargin = dp(180);        // 先给个近似值，量到顶部真实高度后再校正
        page.addView(pager, plp);

        pager.addView(buildBaiduPage());   // 页 0
        pager.addView(buildAmapPage());    // 页 1
        pager.addView(buildOtherPage());   // 页 2

        addTab("百度地图");
        addTab("高德地图");
        addTab("其他");
        selectTab(0);

        buildBottomBar();

        // 顶部高度是动态的（状态文案可能换行）→ 布局完成后把分页区顶边校正一次
        top.post(new Runnable() {
            @Override public void run() {
                try {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pager.getLayoutParams();
                    int want = top.getHeight() + dp(2);
                    if (lp.topMargin != want) {
                        lp.topMargin = want;
                        pager.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) {}
            }
        });

        page.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int nav = insets.getSystemWindowInsetBottom();
                if (barLp != null) {
                    barLp.setMargins(dp(14), 0, dp(14), nav + dp(14));
                    bottomBar.setLayoutParams(barLp);
                }
                for (int i = 0; i < pages.size(); i++) {
                    pages.get(i).setPadding(0, 0, 0, nav + dp(96));
                }
                return insets;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        synced = false;          // 回到设置页时按存储重刷一遍开关
        // 服务绑好之后，把收到上报时服务还没就绪而暂存的学习结果补推一次
        try { LearnedProvider.flushToRemote(this); } catch (Throwable ignored) {}
        refreshSdkRow();
        statusRefresher.run();
        // 每次打开设置页都检查一次版本（内部 6 小时节流；点版本行可强制刷新）
        try { UpdateChecker.check(this, false, updateCb); } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRefresher);
    }

    private boolean isDarkMode() {
        int m = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------------------------------------------------------- 调色板

    private int cBg()       { return dark ? 0xFF0E1013 : 0xFFF2F3F7; }
    private int cGlass()    { return dark ? 0xB01D2027 : 0xD9FFFFFF; }
    private int cRimHi()    { return dark ? 0x40FFFFFF : 0x70FFFFFF; }
    private int cRimLo()    { return dark ? 0x08FFFFFF : 0x12FFFFFF; }
    private int cTxP()      { return dark ? 0xFFF2F4F8 : 0xFF171B23; }
    private int cTxS()      { return dark ? 0xFF98A0AC : 0xFF7A8290; }
    private int cAccent()   { return dark ? 0xFF7AB1FF : 0xFF0A6CF5; }
    private int cRipple()   { return dark ? 0x267AB1FF : 0x140A6CF5; }
    private int cTrackOff() { return dark ? 0x2E8A94A4 : 0x2E8A94A4; }
    private int cDotOk()    { return dark ? 0xFF2BD88A : 0xFF12B76A; }
    private int cDotBad()   { return dark ? 0xFFFF6B70 : 0xFFE5484D; }

    private int[] blobColors() {
        return dark
                ? new int[]{0x263D6BFF, 0x1E19C37A, 0x227C5CFF}
                : new int[]{0x1E649BFF, 0x162BD9B8, 0x16A78BFA};
    }

    // ---------------------------------------------------------------- 背景光斑

    private void addBlobs(FrameLayout page) {
        int[] c = blobColors();
        page.addView(blob(c[0], dp(380), dp(-100), dp(-90), 0, 0));
        page.addView(blob(c[1], dp(300), 0, dp(150), dp(-80), 0));
        page.addView(blob(c[2], dp(360), 0, 0, dp(-70), dp(120)));
    }

    /** 径向渐变光斑；四边 margin 决定位置（可为负让它溢出屏幕） */
    private View blob(int color, int size, int ml, int mt, int mr, int mb) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, color & 0x00FFFFFF});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(size / 2f);
        g.setShape(GradientDrawable.OVAL);
        v.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.setMargins(ml, mt, mr, mb);
        v.setLayoutParams(lp);
        return v;
    }

    // ---------------------------------------------------------------- 玻璃材质

    /** 玻璃面板：底层渐变高光 rim + 内层半透明体，双层圆角模拟玻璃厚度 */
    private Drawable glass(float radiusDp) {
        GradientDrawable rim = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{cRimHi(), cRimLo()});
        rim.setCornerRadius(dp(radiusDp));
        GradientDrawable body = new GradientDrawable();
        body.setColor(cGlass());
        body.setCornerRadius(dp(radiusDp));
        LayerDrawable ld = new LayerDrawable(new Drawable[]{rim, body});
        int inset = Math.max(1, dp(1));
        ld.setLayerInset(1, inset, inset, inset, inset);
        return ld;
    }

    private Drawable ripple(Drawable content, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dp(radiusDp));
        mask.setColor(0xFF000000);
        return new RippleDrawable(ColorStateList.valueOf(cRipple()), content, mask);
    }

    private GradientDrawable capsule(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(color);
        g.setSize(dp(46), dp(28));
        return g;
    }

    private Switch makeSwitch() {
        Switch sw = new Switch(this);
        sw.setShowText(false);
        sw.setSwitchMinWidth(dp(46));
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, capsule(cAccent()));
        track.addState(new int[]{}, capsule(cTrackOff()));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setSize(dp(22), dp(22));
        thumb.setStroke(Math.max(1, dp(1)), 0x1A000000);
        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        sw.setAlpha(0.4f);   // 服务绑定前半透明，syncSwitches() 放开
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

    /** 版本行配色：已是最新=绿、有新版本=黄、未获取=灰（点一下强制刷新） */
    private void renderVersionLines() {
        if (verLine == null) return;
        String latest = UpdateChecker.latest(this);
        boolean out = UpdateChecker.isOutdated(this);
        String txt;
        if (latest == null) {
            verLine.setTextColor(cTxS());
            txt = "当前版本 v" + Version.NAME + " · 线上版本未获取（点此重试）";
        } else if (out) {
            verLine.setTextColor(0xFFE0A200);      // 黄：有新版本
            txt = "当前版本 v" + Version.NAME + " · 有新版本 v" + latest + "（点此查看/刷新）";
        } else {
            verLine.setTextColor(cDotOk());        // 绿：已是最新
            txt = "当前版本 v" + Version.NAME + " · 已是最新";
        }
        verLine.setText(txt);
        if (updateBtn != null) {
            if (out && latest != null) {
                updateBtn.setText("⬆ 立即更新 v" + latest + "（镜像/GitHub/Telegram）");
                updateBtn.setTextColor(0xFFFFFFFF);
                updateBtn.setBackground(capsuleBg(0xFFE0A200, HEAD_R));
            } else if (latest == null) {
                updateBtn.setText("检查版本更新（点此重试）");
                updateBtn.setTextColor(0xFFFFFFFF);
                updateBtn.setBackground(capsuleBg(cAccent(), HEAD_R));
            } else {
                updateBtn.setText("✓ 已是最新 v" + Version.NAME + "（点此再检查）");
                updateBtn.setTextColor(0xFFFFFFFF);
                updateBtn.setBackground(capsuleBg(dark ? 0xFF1E7A4B : 0xFF12B76A, HEAD_R));
            }
        }
        if (adLine != null) adLine.setText(adaptSummary());
    }

    /** 最佳适配：模块 hook 点验证过的目标版本 vs 本机实际版本 */
    private String adaptSummary() {
        StringBuilder sb = new StringBuilder("最佳适配：");
        for (int i = 0; i < Version.TARGETS.length; i++) {
            String pkg = Version.TARGETS[i][0];
            String label = Version.TARGETS[i][1];
            String want = Version.TARGETS[i][2];
            if (i > 0) sb.append("　");
            sb.append(label).append(' ').append(want);
            String inst = installedVersion(pkg);
            if (inst == null) {
                sb.append("（未安装）");
            } else if (inst.equals(want) || inst.startsWith(want)) {
                sb.append("（本机 ").append(inst).append(" ✔）");
            } else {
                sb.append("（本机 ").append(inst).append(" ✘）");
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
     * 发现新版本：四个出口 —— **镜像下载**（给没代理的用户）/ GitHub 下载 / Telegram / 稍后更新。
     * 用两行按钮，避免三个按钮塞不下第四个选项。
     */
    private void showUpdateDialog(final String latest) {
        final AlertDialog dlg = new AlertDialog.Builder(this).create();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glass(GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(14));

        box.addView(text("发现新版本 v" + latest, 18, Typeface.BOLD, cTxP()));

        String msg = "当前版本 v" + Version.NAME + "　→　最新版本 v" + latest
                + "\n\n【没有代理也能下载】点「镜像下载」走 gh-proxy 反代打开 Releases 页；"
                + "「GitHub 下载」是官方直链（需要能访问 GitHub）。"
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
        r1.addView(dlgButton("镜像下载", true, new Runnable() {
            @Override public void run() { openUrl(mirrorUrl()); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(spacer(dp(10)));
        r1.addView(dlgButton("GitHub 下载", false, new Runnable() {
            @Override public void run() { openUrl(Version.RELEASES); dlg.dismiss(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r1, rlp);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.topMargin = dp(10);
        r2.addView(dlgButton("Telegram 群", false, new Runnable() {
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

    /** 镜像下载页（按实测可用性排序，取第一个） */
    private String mirrorUrl() {
        String[] m = Version.DOWNLOAD_MIRRORS;
        return (m != null && m.length > 0) ? m[0] : Version.RELEASES;
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

    private void buildHero(LinearLayout root) {
        TextView title = text("MapAdKiller", 26, Typeface.BOLD, cTxP());
        title.setPadding(dp(4), dp(16), 0, dp(2));
        root.addView(title);

        statusView = text("", 13, Typeface.NORMAL, cTxS());
        statusView.setPadding(dp(4), 0, 0, dp(6));
        root.addView(statusView);
        renderStatus();

        // 版本行：绿=已是最新 / 黄=有新版本 / 灰=未获取（点一下强制刷新）
        verLine = text("", 13, Typeface.BOLD, cTxS());
        verLine.setPadding(dp(4), 0, 0, dp(2));
        verLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "正在检查更新…", Toast.LENGTH_SHORT).show();
                UpdateChecker.check(MainActivity.this, true, updateCb);
            }
        });
        root.addView(verLine);

        // 最佳适配应用版本（本机实际安装版本并排对照）
        adLine = text("", 12, Typeface.NORMAL, cTxS());
        adLine.setLineSpacing(0, 1.2f);
        adLine.setPadding(dp(4), 0, 0, dp(10));
        root.addView(adLine);

        updateBtn = text("检查版本更新", 14, Typeface.BOLD, 0xFFFFFFFF);
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        updateBtn.setBackground(capsuleBg(cAccent(), HEAD_R));
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
        root.addView(updateBtn, blp);

        renderVersionLines();
    }

    /** 去广告卡片：始终开启、不折叠，作为页面第一块「仪表盘」 */
    private void buildForcedCard(LinearLayout root) {
        LinearLayout card = newCard();
        TextView head = text("去广告 · 始终开启", 13, Typeface.BOLD, cAccent());
        head.setPadding(dp(4), dp(10), 0, dp(4));
        card.addView(head);
        addNoteRow(card, "开屏 / 横幅 / 推送 / 信息流广告卡拦截，覆盖高德、百度、腾讯三家地图（无需配置）");
        addNoteRow(card, "广告 SDK 自动检索：打开地图时模块会在其进程内扫描 dex，命中广告特征的厂商包"
                + "会自动记下来并拦截，记录长期保存，下次启动直接生效。");
        sdkRow = addActionButton(card, sdkSummary(), new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        });
        root.addView(card, cardLp());
    }

    private final List<Sec> secs = new ArrayList<>();

    private static final class Sec {
        LinearLayout group;
        LinearLayout card;
        TextView chev;
        boolean expanded;
    }

    /** 抽屉分组：玻璃药丸头 + 可折叠玻璃卡片（默认折叠） */
    private Sec newDrawer(String title) {
        final Sec s = new Sec();
        s.expanded = false;
        s.group = new LinearLayout(this);
        s.group.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(12), dp(12), dp(12));
        head.setBackground(ripple(glass(HEAD_R), HEAD_R));
        TextView t = text(title, 14, Typeface.BOLD, cTxP());
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        s.chev = text("›", 18, Typeface.BOLD, cTxS());
        s.chev.setGravity(Gravity.CENTER);
        s.chev.setPadding(dp(6), 0, dp(6), 0);
        head.addView(s.chev, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(s); }
        });
        s.group.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        s.card = newCard();
        s.card.setVisibility(View.GONE);
        s.card.setAlpha(0f);
        LinearLayout.LayoutParams clp = cardLp();
        clp.topMargin = dp(8);
        s.group.addView(s.card, clp);

        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(10);
        list.addView(s.group, glp);
        secs.add(s);
        return s;
    }

    private void toggle(final Sec s) {
        final boolean expand = !s.expanded;
        s.expanded = expand;
        s.chev.animate().rotation(expand ? 90f : 0f).setDuration(190).start();
        if (expand) {
            s.card.setVisibility(View.VISIBLE);
            s.card.setTranslationY(-dp(5));
            s.card.animate().alpha(1f).translationY(0f).setDuration(200).start();
        } else {
            s.card.animate().alpha(0f).translationY(-dp(5)).setDuration(160)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (!s.expanded) s.card.setVisibility(View.GONE);
                        }
                    }).start();
        }
    }

    private void buildDrawers() {
        Sec sec;

        sec = newDrawer("高德 · 主页标签栏");
        for (String tab : Config.TABS) addSwitch(sec.card, "显示标签「" + tab + "」", Config.K_TAB_PREFIX + tab);

        sec = newDrawer("高德 · 首页工具宫格");
        // v1.1.0：宫格 15 格全量独立开关（截图实证的完整 3×5 宫格）
        for (String tool : Config.TOOLS) addSwitch(sec.card, "显示「" + tool + "」", Config.K_TOOL_PREFIX + tool);

        sec = newDrawer("高德 · 首页推荐内容");
        addSwitch(sec.card, "显示榜单/特色推荐卡（尝尝这里 / 特色场所，全页面统一）", Config.K_FEED_BOARD);
        addSwitch(sec.card, "显示出行节/会场活动卡（库迪咖啡 4选1 / 车主服务入口等）", Config.K_FEED_FESTIVAL);
        addSwitch(sec.card, "天气卡片", Config.K_FEED_WEATHER);
        addSwitch(sec.card, "周边景区 / 景点推荐", Config.K_FEED_SCENIC);
        addSwitch(sec.card, "榜单帖子卡（景区榜 / 美食榜 / 打卡地…）", Config.K_FEED_POSTS);
        addSwitch(sec.card, "带距离的内容卡（旅行帖 / 探店帖…）", Config.K_FEED_DISTANCE);
        addSwitch(sec.card, "精选榜单 / 热门榜", Config.K_FEED_RANK);
        addSwitch(sec.card, "攻略 / 内容流卡片", Config.K_FEED_CONTENT);
        addSwitch(sec.card, "问问 AI 入口", Config.K_FEED_AI);
        addSwitch(sec.card, "推荐频道栏（关注 / 附近 / 美食…）", Config.K_FEED_FILTER);
        addSwitch(sec.card, "设置家 / 设置单位 / 常去地点", Config.K_HOME_CHIPS);
        addSwitch(sec.card, "搜索栏下方快捷入口整排（美食 / 酒店 / 景点门票 / 加油充电 / 出行节 / 扫街榜）",
                Config.K_HOME_QUICK_ROW);
        addSwitch(sec.card, "关闭首页搜索框热词轮播（文字流动 / 运营广告词）",
                Config.K_HOTWORD_OFF);
        addNoteRow(sec.card, "首页那支搜索框里的文字会自己流动（预置词/运营词，有时是广告）。"
                + "打开本开关后文字被钉死成系统默认提示词「查找地点、公交、地铁」，"
                + "热词品牌图标一并摘掉；关掉开关即可恢复原样。");
        addSwitch(sec.card, "关闭首页「去XXX 打车」打车浮窗（打车运营卡）",
                Config.K_RIDE_CARD_OFF);
        addNoteRow(sec.card, "首页信息流底部那张「去幸福路步行街 / 有座不拥挤 · 行程有保障 / 打车」"
                + "的运营浮窗卡会整张摘除（含卡片高度，不留空洞）。"
                + "识别用「卡内文案 + AJX 列表 item 结构」双判据，正常地点卡、路线卡不受影响。");

        sec = newDrawer("高德 · 「我的」页");
        addSwitch(sec.card, "订单 / 收藏 / 待评价 一栏", Config.K_MY_ORDER_ROW);
        addSwitch(sec.card, "车辆服务 / 高德运动 一栏", Config.K_MY_SERVICE_ROW);
        addSwitch(sec.card, "达人任务卡片", Config.K_MY_TASK);
        addSwitch(sec.card, "扫街新发现 / 小德果园 一栏", Config.K_MY_PROMO_ROW);
        addSwitch(sec.card, "猜你喜欢", Config.K_MY_GUESS);
        addSwitch(sec.card, "资质信息 / 协议中心", Config.K_MY_QUALITY);

        sec = newDrawer("高德 · 搜索框内页");
        // v1.1.0：搜索页整页是 AJX3 画布（无资源 id），这一层用「结构指纹 + 文案锚点」判页判广告，
        // 命中即 GONE 单卡；总开关默认开（命中面全是券包/红包/会场类运营卡）。
        addSwitch(sec.card, "搜索页去广告（券包 / 红包 / 满减 / 会场 / 运营卡）", Config.K_SEARCH_AD);
        addNoteRow(sec.card, "搜索输入页、联想页、搜索结果页里的运营推广卡会被就地隐藏（含「高德邀你畅玩十一」"
                + "这类会场流）。搜到的真实地点、路线、攻略不受影响。");
        // 「其他 · 入口与调试」一组已挪到第 3 页（其他），见 buildOtherPage()
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
        root.setPadding(p, dp(10), p, dp(96));
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
        addBmapInfoCard(root);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        list = mount;
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
                if (i == 0) toggle(sec);        // 第一组默认展开
            }
        } finally {
            list = old;
        }
        addVersionFooter(root, "首页开关：强停百度地图后重开即可生效；"
                + "「我的」页开关：关掉后重开该页即生效（数据层/JS 层在页面加载时读配置）");
        return sv;
    }

    /** 页 1：高德地图 —— 沿用 MapAdKiller 的抽屉 */
    private ScrollView buildAmapPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        buildForcedCard(root);          // 去广告（始终开启，不折叠）
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        list = mount;
        try {
            buildDrawers();
        } finally {
            list = old;
        }
        addVersionFooter(root, "改动后请强停对应地图 App 并重新打开以生效；开关可随时单独开/关");
        return sv;
    }

    /** 页 2：其他 / 调试 */
    private ScrollView buildOtherPage() {
        ScrollView sv = newPage();
        LinearLayout root = pageRoot(sv);
        LinearLayout mount = newMount();
        root.addView(mount);
        LinearLayout old = list;
        list = mount;
        try {
            Sec sec = newDrawer("入口与调试");
            addLocalSwitch(sec.card, "隐藏桌面图标", App.K_HIDE_ICON);
            addNoteRow(sec.card, "隐藏后桌面图标消失；LSPosed 管理器里的「打开」入口不受影响（v1.1.0 起），"
                    + "快捷设置磁贴与 adb 命令也始终可用。");
            addSwitch(sec.card, "工具宫格自动排序（实验性：可能与地图动画冲突）", Config.K_TOOL_SORT);
            addSwitch(sec.card, "调试日志（logcat 输出首页文本锚点）", Config.K_DEBUG_LOG);
            toggle(sec);
            Sec sec2 = newDrawer("去广告说明");
            addNoteRow(sec2.card, "去广告是**基线能力**，没有总开关：高德/百度/腾讯三家的开屏、横幅、"
                    + "信息流运营卡命中即拦截；广告 SDK 清单可在底部「SDK 清单」里查看。");
        } finally {
            list = old;
        }
        addVersionFooter(root, "MapClean v2.0.0 = BMapClean（百度界面精简）+ MapAdKiller（三家去广告）");
        return sv;
    }

    private void addTab(String label) {
        final TextView chip = text(label, 13, Typeface.BOLD, cTxS());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0x1FFFFFFF : 0x22FFFFFF);
        bg.setCornerRadius(dp(HEAD_R));
        chip.setBackground(ripple(bg, HEAD_R));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = tabChips.indexOf(v);
                if (i >= 0) { pager.setPage(i, true); selectTab(i); }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        tabChips.add(chip);
        tabBar.addView(chip, lp);
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabChips.size(); i++) {
            TextView c = tabChips.get(i);
            boolean on = (i == index);
            c.setTextColor(on ? cAccent() : cTxS());
            c.setAlpha(on ? 1f : 0.7f);
        }
    }

    /** 百度那一页的信息卡：模块状态 + 最近一次规则执行 + 一键恢复 */
    private void addBmapInfoCard(LinearLayout root) {
        LinearLayout card = newCard();
        LinearLayout.LayoutParams clp = cardLp();
        root.addView(card, clp);

        TextView head = text("百度地图 · 界面精简", 15, Typeface.BOLD, cTxP());
        head.setPadding(0, dp(2), 0, dp(8));
        card.addView(head);

        String diag = null;
        try { diag = io.github.ldxm666.bmapclean.App.diag(); } catch (Throwable ignored) {}
        int cnt = 0;
        try { cnt = io.github.ldxm666.bmapclean.App.reportCount(); } catch (Throwable ignored) {}
        String info = "最近一次规则执行：" + (diag == null ? "尚未收到回报" : diag)
                + "\n累计回报 " + cnt + " 次";
        TextView infoView = text(info, 12, Typeface.NORMAL, cTxS());
        infoView.setLineSpacing(0, 1.25f);
        card.addView(infoView);

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
    private void addBmapSwitch(final LinearLayout card, String title, final String key, String note) {
        LinearLayout row = newRow(card);
        TextView label = text(title, 15, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(12), dp(8), dp(12));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = makeSwitch();
        boolean def = io.github.ldxm666.bmapclean.Spec.defaultVisible(key);
        try {
            sw.setChecked(io.github.ldxm666.bmapclean.App.read(key, def));
            sw.setEnabled(io.github.ldxm666.bmapclean.App.connected());
        } catch (Throwable t) {
            sw.setChecked(def);
        }
        sw.setClickable(false);
        bmapSwitches.put(key, sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !sw.isChecked();
                boolean ok = false;
                try { ok = io.github.ldxm666.bmapclean.App.write(key, next); } catch (Throwable ignored) {}
                if (ok) {
                    sw.setChecked(next);
                } else {
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (note != null && note.length() > 0) addNoteRow(card, note);
    }

    private void addVersionFooter(LinearLayout root, String tip) {
        TextView t = text(tip, 12, Typeface.NORMAL, cTxS());
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(4), dp(16), 0, dp(2));
        root.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildFooter(LinearLayout root) {
        TextView tip = text("改动后请强停对应地图 App 并重新打开以生效；开关可随时单独开/关，无需全部统一调整", 12, Typeface.NORMAL, cTxS());
        tip.setPadding(dp(4), dp(16), 0, dp(2));
        root.addView(tip);

        TextView ver = text("MapAdKiller v2.0.0 · 高德/百度/腾讯去广告 + 百度界面精简", 11, Typeface.NORMAL, cTxS());
        ver.setAlpha(0.7f);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(10), 0, 0);
        root.addView(ver, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 底部悬浮玻璃操作栏 */
    private void buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottomBar.setBackground(glass(26));
        bottomBar.setElevation(dp(10));

        bottomBar.addView(barChip("SDK 清单", new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        }));
        bottomBar.addView(spacer(dp(8)));
        bottomBar.addView(barChip("配置", new Runnable() {
            @Override public void run() { showConfigManager(); }
        }));
        bottomBar.addView(spacer(dp(8)));
        bottomBar.addView(barChip("联系作者", new Runnable() {
            @Override public void run() { showContact(); }
        }));

        barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.setMargins(dp(14), 0, dp(14), dp(14));
        page.addView(bottomBar, barLp);
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
        c.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0x2E7AB1FF : 0x330A6CF5);
        bg.setCornerRadius(dp(HEAD_R));
        c.setBackground(ripple(bg, HEAD_R));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return c;
    }

    // ---------------------------------------------------------------- 状态区

    /**
     * 状态区：两个点各代表一件独立的事，绿=好，红=坏。
     *  1) 配置通道是否连上 LSPosed 服务 —— App.svc() != null
     *  2) 作用域是否勾选地图应用 —— 直接问 LSPosed 服务要已勾选的包名列表
     */
    private void renderStatus() {
        if (statusView == null) return;
        io.github.libxposed.service.XposedService s = App.svc();
        boolean active = s != null;

        String[] targets = {MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP};
        String[] labels = {"高德", "百度", "腾讯"};
        int scoped = 0;
        StringBuilder picked = new StringBuilder();
        try {
            java.util.List<String> scope = active ? s.getScope() : null;
            if (scope != null) {
                for (int i = 0; i < targets.length; i++) {
                    if (scope.contains(targets[i])) {
                        scoped++;
                        if (picked.length() > 0) picked.append(" / ");
                        picked.append(labels[i]);
                    }
                }
            }
        } catch (Throwable ignored) {}
        boolean scopeOk = scoped > 0;

        String l1 = active
                ? "已激活 · " + s.getFrameworkName() + " " + s.getFrameworkVersion()
                : "未激活 · 请在 LSPosed 中启用本模块";
        String l2;
        if (!active) l2 = "作用域未知 · 正在等待 LSPosed 服务…";
        else if (scopeOk) l2 = "作用域已勾选 · " + picked + "（" + scoped + "/3）";
        else l2 = "作用域未勾选 · 请在 LSPosed 里勾选地图应用";

        String plain = "●  " + l1 + "\n●  " + l2;
        android.text.SpannableString ss = new android.text.SpannableString(plain);
        int second = plain.indexOf('\n') + 1;
        ss.setSpan(new android.text.style.ForegroundColorSpan(active ? cDotOk() : cDotBad()),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.ForegroundColorSpan(scopeOk ? cDotOk() : cDotBad()),
                second, second + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        statusView.setText(ss);
    }

    private final android.os.Handler statusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable statusRefresher = new Runnable() {
        @Override public void run() {
            renderStatus();
            // 服务是异步绑定的：绑定前 readState() 只能回退默认 true，
            // 若此时就把开关画成"开"，用户重开设置页会以为配置全丢了。
            // 所以绑定成功后立刻按真实存储重刷一遍开关。
            if (!synced && App.svc() != null) {
                synced = true;
                syncSwitches();
            }
            statusHandler.postDelayed(this, 800);
        }
    };

    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private volatile boolean synced;

    private void syncSwitches() {
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s == null) return;
            android.content.SharedPreferences p =
                    s.getRemotePreferences(Config.PREF_GROUP);
            for (Map.Entry<String, Switch> e : switches.entrySet()) {
                Switch sw = e.getValue();
                sw.setChecked(p.getBoolean(e.getKey(), Config.defaultVisible(e.getKey())));
                sw.setEnabled(true);
                sw.setAlpha(1f);
            }
            // 百度那一页用的是另一个 group（bmapclean_config）—— 单独再刷一遍
            try {
                android.content.SharedPreferences bp =
                        s.getRemotePreferences(io.github.ldxm666.bmapclean.Cfg.GROUP);
                for (Map.Entry<String, Switch> e : bmapSwitches.entrySet()) {
                    Switch sw = e.getValue();
                    sw.setChecked(bp.getBoolean(e.getKey(),
                            io.github.ldxm666.bmapclean.Spec.defaultVisible(e.getKey())));
                    sw.setEnabled(true);
                    sw.setAlpha(1f);
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
        box.setBackground(glass(GLASS_R));
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
        boolean hasSnap = !readSnapshot().isEmpty();
        glassDialog("配置管理",
                "保存配置：把当前所有开关快照存到本地\n" +
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
                "导入：粘贴配置文本恢复",
                "导出", new Runnable() {
                    @Override public void run() { exportConfig(); }
                },
                "导入", new Runnable() {
                    @Override public void run() { importConfig(); }
                },
                "恢复默认", new Runnable() {
                    @Override public void run() {
                        glassDialog("恢复默认？", "清空全部配置键（全部显示），强停地图 App 后生效。",
                                "恢复默认", new Runnable() {
                                    @Override public void run() {
                                        if (App.clearAll()) {
                                            Toast.makeText(MainActivity.this, "已恢复默认", Toast.LENGTH_LONG).show();
                                            recreate();
                                        } else {
                                            Toast.makeText(MainActivity.this, "LSPosed 服务未连接", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                }, null, null, "取消", null);
                    }
                });
    }

    /** 当前全部开关 → JSONObject（只收 UI 登记过的键） */
    private JSONObject collectConfig() {
        JSONObject o = new JSONObject();
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            android.content.SharedPreferences p = s == null ? null
                    : s.getRemotePreferences(Config.PREF_GROUP);
            for (Map.Entry<String, Switch> e : switches.entrySet()) {
                boolean v = p != null
                        ? p.getBoolean(e.getKey(), Config.defaultVisible(e.getKey()))
                        : e.getValue().isChecked();
                o.put(e.getKey(), v);
            }
            o.put("_app", getPackageName());
            o.put("_ver", 1);
        } catch (Throwable ignored) {}
        return o;
    }

    private void saveSnapshot() {
        try {
            String json = collectConfig().toString();
            getSharedPreferences(App.UI_PREFS, MODE_PRIVATE).edit()
                    .putString(CFG_SNAPSHOT, json).commit();
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private java.util.Map<String, Boolean> readSnapshot() {
        java.util.Map<String, Boolean> m = new LinkedHashMap<>();
        try {
            String j = getSharedPreferences(App.UI_PREFS, MODE_PRIVATE)
                    .getString(CFG_SNAPSHOT, null);
            if (j != null) {
                JSONObject o = new JSONObject(j);
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.startsWith("_")) continue;
                    m.put(k, o.optBoolean(k, true));
                }
            }
        } catch (Throwable ignored) {}
        return m;
    }

    private void applyConfigMap(java.util.Map<String, Boolean> m) {
        int n = 0;
        for (Map.Entry<String, Boolean> e : m.entrySet()) {
            if (App.writeBoolean(e.getKey(), e.getValue())) n++;
        }
        Toast.makeText(this, n > 0 ? "已写入 " + n + " 项，强停地图 App 后生效"
                                   : "LSPosed 服务未连接，写入失败", Toast.LENGTH_LONG).show();
        if (n > 0) recreate();
    }

    private void restoreSnapshot() {
        java.util.Map<String, Boolean> m = readSnapshot();
        if (m.isEmpty()) {
            Toast.makeText(this, "没有可用快照", Toast.LENGTH_SHORT).show();
            return;
        }
        applyConfigMap(m);
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
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
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
        box.setBackground(glass(GLASS_R));
        int pad = dp(20);
        box.setPadding(pad, dp(18), pad, dp(10));
        box.addView(text("导入配置", 18, Typeface.BOLD, cTxP()));
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
            JSONObject o = new JSONObject(text);
            java.util.Map<String, Boolean> m = new LinkedHashMap<>();
            java.util.Iterator<String> it = o.keys();
            int n = 0;
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith("_")) continue;
                if (!switches.containsKey(k)) continue;   // 未知键忽略
                m.put(k, o.optBoolean(k, Config.defaultVisible(k)));
                n++;
            }
            if (n == 0) {
                Toast.makeText(this, "没有可识别的配置项", Toast.LENGTH_SHORT).show();
                return;
            }
            applyConfigMap(m);
        } catch (Throwable t) {
            Toast.makeText(this, "JSON 解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 联系作者：Telegram 群组 + GitHub Issues */
    private void showContact() {
        glassDialog("联系作者",
                "Telegram 群组：反馈 / 交流 / 第一时间更新\n"
                + "GitHub Issues：bug 报告 / 功能需求",
                "Telegram 群", new Runnable() {
                    @Override public void run() { openUrl("https://t.me/+2rqisPe5tJxjNTk1"); }
                },
                "GitHub Issues", new Runnable() {
                    @Override public void run() { openUrl("https://github.com/ldxm666/MapAdKiller/issues"); }
                },
                "取消", null);
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
        card.setBackground(glass(GLASS_R));
        card.setClipToOutline(true);
        card.setPadding(dp(12), 0, dp(12), 0);
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(dark ? 0x1EFFFFFF : 0x16000000);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return d;
    }

    private LinearLayout newRow(LinearLayout card) {
        if (card.getChildCount() > 0) card.addView(divider());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(4), 0, dp(4), 0);
        row.setBackground(ripple(null, 12));
        return row;
    }

    /** 统一规格开关行：52dp 高、左标题右 Switch、行间分隔线（写 RemotePreferences） */
    private void addSwitch(final LinearLayout card, String title, final String key) {
        LinearLayout row = newRow(card);

        TextView label = text(title, 15, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(12), dp(8), dp(12));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = makeSwitch();
        sw.setChecked(readState(key));
        // 服务未绑定时先禁用，等 syncSwitches() 用真实存储值刷新后再放开
        sw.setEnabled(App.svc() != null);
        sw.setClickable(false);
        switches.put(key, sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !sw.isChecked();
                if (App.writeBoolean(key, next)) {
                    sw.setChecked(next);
                } else {
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) { /* 由行点击驱动 */ }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 本地开关行（不走 LSPosed RemotePreferences），仅用于模块 App 自身组件可见性。 */
    private void addLocalSwitch(final LinearLayout card, String title, final String key) {
        LinearLayout row = newRow(card);

        TextView label = text(title, 15, Typeface.NORMAL, cTxP());
        label.setPadding(0, dp(12), dp(8), dp(12));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = makeSwitch();
        boolean on = false;
        try {
            on = getSharedPreferences(App.UI_PREFS, MODE_PRIVATE).getBoolean(key, false);
        } catch (Throwable ignored) {}
        sw.setChecked(on);
        sw.setEnabled(true);
        sw.setAlpha(1f);
        sw.setClickable(false);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final boolean next = !sw.isChecked();
                if (!next) {
                    applyHide(next, sw);
                    return;
                }
                // 桌面入口（LAUNCHER alias）禁掉后桌面看不到，但 LSPosed 管理器
                // 走 CATEGORY_INFO 通道（见 AndroidManifest 注释），入口不受影响。
                glassDialog("隐藏桌面图标？",
                        "· 桌面/抽屉里的 MapAdKiller 图标消失\n"
                        + "· LSPosed 管理器里的「打开」入口不受影响，随时可回到本页\n"
                        + "· 快捷设置磁贴与 adb 命令同样始终可用",
                        "仍然隐藏", new Runnable() {
                            @Override public void run() { applyHide(true, sw); }
                        }, null, null, "取消", null);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void applyHide(boolean hide, Switch sw) {
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
            if (s != null) return s.getRemotePreferences(Config.PREF_GROUP).getBoolean(key, def);
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
        TextView label = text(title, 15, Typeface.NORMAL, cAccent());
        label.setPadding(dp(4), dp(12), dp(8), dp(12));
        label.setGravity(Gravity.CENTER);
        label.setBackground(ripple(null, 12));
        label.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        card.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return label;
    }

    private void addNoteRow(LinearLayout card, String s) {
        TextView t = text(s, 13, Typeface.NORMAL, cTxS());
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        t.setLineSpacing(0, 1.15f);
        card.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- 基础工厂

    private TextView text(String s, int sp, int style, int color) {
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
