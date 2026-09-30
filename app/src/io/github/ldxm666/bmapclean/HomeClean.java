package io.github.ldxm666.bmapclean;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 百度地图首页精简引擎。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么走「视图层按资源 id 名定位」而不是 hook 宿主方法
 * ══════════════════════════════════════════════════════════════════════════
 * 百度首页那几块（入口栏 / 工宫格 / 回家去公司）都是**原生布局**，
 * 但驱动它们的类名基本是混淆过的、方法名还会随版本重排（SplashAdManager.F/z 那种）。
 * 而资源 id 名字（new_route / component_container3 / home_info …）是百度自己写在
 * xml 里的语义名，**不参与混淆**，跨版本稳定得多 —— 所以主键取 id 名字。
 *
 * 触发方式：挂钩 Activity#onResume，resume 后按 {@link #PASSES} 的时间点复扫几遍。
 * 百度首页面板是异步搭起来的（数据回来才 addView），单次扫描抓不全，
 * 所以用有限次幂等复扫；每次扫描都先确认"这确实是首页"（ufo_root/home_panel 命中），
 * 不是首页就零成本退出。
 *
 * 失效安全：所有规则只做「找到就 GONE」，找不到/抛异常一律放行；
 * 读配置失败一律按「可见」处理 —— 模块失效时界面保持原样，不会清空到没法用。
 */
public final class HomeClean {

    private static volatile boolean installed;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** resume 后的复扫时间点（ms）。首页面板异步搭建，一次扫不全。 */
    private static final long[] PASSES = {0, 120, 300, 650, 1200, 2000, 3200};

    /** 底部行程/天气卡是百度 Talos（类 React）渲染的，没有资源 id，只能按卡内文案 + 瀑布流结构定位 */
    private static final String[] WX_WORDS = {"天气", "行程助手", "重要行程放地图"};

    private HomeClean() {}

    public static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            H.hook(onResume, "bmapclean_onResume", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        sAct = (Activity) self;
                        post((Activity) self);
                    }
                    return r;
                }
            });
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "install onResume failed: " + t);
        }
        installContextHook();
        installWatchdog();
        Cfg.log("HomeClean rules: bar=" + Spec.TABS.length + " tabs, tools=" + Spec.TOOLS.length
                + " cells, hc=3, weather=2, feed=3, mine=6+4");
    }

    /**
     * 「我的」页 / 展开态信息流的触发点 —— **低频看门狗**。
     *
     * 为什么不能只挂生命周期：首页 / 我的 是**同一个 MapsActivity 内的切换**，
     * 切 tab 不触发 onResume。也试过挂 `View#setVisibility` 按 id 过滤，
     * 实测切到「我的」页时并不会在那个容器上调用 setVisibility（钩子装了、首火了，
     * 但一次都没命中目标 id）。
     *
     * 看门狗每 1s 只做 2 次 findViewById（我的页容器 / 信息流容器），
     * 只在「可见性由不可见变可见」的那一次执行完整规则 —— 平均开销可以忽略，
     * 也不会像 hook setVisibility 那样往热路径里塞钩子（减少被检测面）。
     */
    private static volatile Activity sAct;
    private static volatile boolean sMineVisible;
    private static volatile int sMineTicks;
    private static volatile boolean sFeedVisible;
    private static volatile boolean sWatchdog;

    private static void installWatchdog() {
        if (sWatchdog) return;
        sWatchdog = true;
        UI.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Activity a = sAct;
                    if (a != null && !a.isFinishing()) watchPass(a);
                } catch (Throwable ignored) {}
                UI.postDelayed(this, 1000);
            }
        }, 1500);
    }

    private static void watchPass(Activity act) {
        View decor;
        try { decor = act.getWindow() == null ? null : act.getWindow().getDecorView(); }
        catch (Throwable t) { return; }
        if (decor == null) return;

        // ① 「我的」页
        View uc = Anchors.find(decor, Anchors.ID_USER_CENTER, Anchors.NUM_USER_CENTER);
        final boolean ucVis = uc != null && uc.getVisibility() != View.GONE;
        if (ucVis && !sMineVisible) {
            sMineVisible = true;
            sMineTicks = 0;
            Cfg.log("mine page appeared -> apply rules");
            scheduleMine(uc);
        } else if (!ucVis) {
            sMineVisible = false;
            sMineTicks = 0;
        } else if (ucVis && Spec.mineEnabled() && sMineTicks < 60) {
            // 该页区块是**懒加载**的（热门活动在 2.6s 复扫窗口之后才渲染 → 实测漏关），
            // 所以在页面可见期间持续复扫 60 次（≈1 分钟）；已处理过的节点上是空操作，开销可忽略。
            sMineTicks++;
            applyMine(uc);
        }

        // ② 展开态信息流（面板上拉后才可见）
        View feed = Anchors.find(decor, Anchors.ID_FEED, Anchors.NUM_FEED);
        final boolean feedVis = feed != null && feed.getVisibility() != View.GONE;
        if (feedVis && !sFeedVisible) {
            sFeedVisible = true;
            if (Cfg.debug()) Cfg.log("feed visible -> apply feed rules");
            applyFeed(decor);
        } else if (!feedVis) {
            sFeedVisible = false;
        }
    }

    private static void scheduleMine(final View container) {
        final long[] passes = {0, 120, 320, 700, 1500, 2600};
        for (int i = 0; i < passes.length; i++) {
            UI.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (container.getVisibility() == View.GONE) return;
                        int n = applyMine(container);
                        if (n > 0 && Cfg.debug()) {
                            H.log(Log.INFO, MainHook.TAG, "mine re-apply hidden=" + n);
                        }
                    } catch (Throwable ignored) {}
                }
            }, passes[i]);
        }
    }

    /**
     * 拿 Context 的唯一可靠路径。
     *
     * ⚠ **不要用 PackageReadyParam#getApplication()** —— 运行时的
     * XposedModuleInterface$PackageReadyParam 没有这个方法，一调就是
     * NoSuchMethodError（v0.1.1 实测，见 stub 里的说明）。
     *
     * Instrumentation#callApplicationOnCreate(Application) 在每个进程里
     * 恰好走一次，而且晚于 onPackageReady（钩子已经装好），参数就是 Application 本体。
     * 用它同时完成两件事：登记 Context + 发出「钩子已注入」回报。
     */
    private static void installContextHook() {
        try {
            Class<?> instr = Class.forName("android.app.Instrumentation");
            Method m = instr.getDeclaredMethod("callApplicationOnCreate", android.app.Application.class);
            H.hook(m, "bmapclean_appctx", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object a = chain.getArg(0);
                    if (a instanceof android.content.Context) {
                        android.content.Context ctx = (android.content.Context) a;
                        Report.attach(ctx);
                        Report.send(ctx, "hook", "钩子已注入百度地图主进程 · ok="
                                + H.ok.get() + " miss=" + H.miss.get(), true);
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "install appctx failed: " + t);
        }
    }

    private static void post(final Activity act) {
        for (int i = 0; i < PASSES.length; i++) {
            final long at = PASSES[i];
            UI.postDelayed(new Runnable() {
                @Override public void run() { apply(act, at); }
            }, at);
        }
    }

    private static void apply(Activity act, long at) {
        try {
            if (act == null || act.isFinishing()) return;
            if (act.getWindow() == null) return;
            final View decor = act.getWindow().getDecorView();
            if (decor == null) return;

            // 「这一页是不是百度首页」——不是就直接退出，其它页面零开销
            View ufo = Anchors.find(decor, Anchors.ID_UFO_ROOT, Anchors.NUM_UFO_ROOT);
            View panel = Anchors.find(decor, Anchors.ID_HOME_PANEL, Anchors.NUM_HOME_PANEL);
            if (ufo == null && panel == null) return;

            int n = 0;
            n += applyBottomBar(decor);
            n += applyToolGrid(decor);
            n += applyHomeCompany(decor);
            n += applyWeather(decor);
            n += applyFeed(decor);
            n += applyMine(decor);
            n += applyMineCards(decor);
            n += HomeAds.applyHomeActivity(decor);   // 左上角运营浮层（一键穿越/古今地图）

            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "apply@" + at + "ms act="
                        + act.getClass().getName() + " hidden=" + n + " | " + Anchors.stats());
            }
            // 取证：链尾把整棵 decor 连同**文本**打进 logcat（dumpsys 无文本、uiautomator 卡在非 idle）
            if (Cfg.debug() && at >= PASSES[PASSES.length - 1]) {
                TreeDump.dump(decor, "all");
            }
            if (at >= PASSES[PASSES.length - 1]) {
                // 链尾把本次结果回报给设置页（广播，见 Report 的说明）
                Report.send(act, "scan", "act=" + act.getClass().getSimpleName()
                        + " hidden=" + n
                        + " · anchors " + Anchors.stats(), false);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "apply failed @" + at + "ms: " + t);
        }
    }

    // ══════════════════════════════════════════════════ ① 底部五入口栏

    private static final String[] TAB_IDS = {
            Anchors.ID_NEW_ROUTE, Anchors.ID_NEW_NEARBY, Anchors.ID_NEW_THIRD,
            Anchors.ID_NEW_FOURTH, Anchors.ID_NEW_USER,
    };
    private static final int[] TAB_NUMS = {
            Anchors.NUM_NEW_ROUTE, Anchors.NUM_NEW_NEARBY, Anchors.NUM_NEW_THIRD,
            Anchors.NUM_NEW_FOURTH, Anchors.NUM_NEW_USER,
    };
    private static final String[] TAB_PXY_IDS = {
            Anchors.ID_PXY_ROUTE, Anchors.ID_PXY_NEARBY, Anchors.ID_PXY_THIRD,
            Anchors.ID_PXY_FOURTH, Anchors.ID_PXY_USER,
    };
    private static final int[] TAB_PXY_NUMS = {
            Anchors.NUM_PXY_ROUTE, Anchors.NUM_PXY_NEARBY, Anchors.NUM_PXY_THIRD,
            Anchors.NUM_PXY_FOURTH, Anchors.NUM_PXY_USER,
    };

    private static int applyBottomBar(View decor) {
        int n = 0;
        final boolean barOn = Cfg.visible(Spec.K_BAR);

        // 整栏
        View bar = Anchors.find(decor, Anchors.ID_NEW_BTNS, Anchors.NUM_NEW_BTNS);
        if (bar != null && !barOn && hide(bar)) n++;

        // 逐项（栏被整块关掉时，逐项一律视为隐藏）
        boolean anyTabHidden = false;
        for (int i = 0; i < Spec.TABS.length && i < TAB_IDS.length; i++) {
            final boolean vis = barOn && Cfg.visible(Spec.K_TAB + Spec.TABS[i]);
            if (vis) continue;
            anyTabHidden = true;
            View item = Anchors.find(decor, TAB_IDS[i], TAB_NUMS[i]);
            if (item != null && hide(item)) n++;
            // 视觉隐藏后同步摘掉点击代理，避免"看不见但点得到"
            View proxy = Anchors.find(decor, TAB_PXY_IDS[i], TAB_PXY_NUMS[i]);
            if (proxy != null) hide(proxy);
        }

        // 隐藏后必须**均分重排**：ufo_root/new_btns 是 LinearLayout，5 个 tab 原本各占 1/5 固定宽，
        // 少一个就只在左侧留一块空白（用户实测："挤在最左边"）。给剩余项 weight=1 后自动铺满。
        if (barOn && anyTabHidden) reflow((ViewGroup) bar);

        // 中央「长按说话」还有一份覆盖层（home_ai_container），与 new_third 同进同退
        final boolean thirdVis = barOn && Cfg.visible(Spec.K_TAB + Spec.TABS[2]);
        if (!thirdVis) {
            View ai = Anchors.find(decor, Anchors.ID_HOME_AI_CONTAINER, Anchors.NUM_HOME_AI_CONTAINER);
            if (ai != null && hide(ai)) n++;
        }
        return n;
    }

    // ══════════════════════════════════════════════════ ② 首页工具宫格

    private static final String[] ROW_IDS = {Anchors.ID_ROW1, Anchors.ID_ROW2, Anchors.ID_ROW3};
    private static final int[] ROW_NUMS = {Anchors.NUM_ROW1, Anchors.NUM_ROW2, Anchors.NUM_ROW3};

    private static int applyToolGrid(View decor) {
        int n = 0;
        View wrap = Anchors.find(decor, Anchors.ID_TOOL_WRAP, Anchors.NUM_TOOL_WRAP);
        if (wrap == null) return 0;

        if (!Cfg.visible(Spec.K_TOOLS)) {
            return hide(wrap) ? 1 : 0;
        }

        for (int r = 0; r < ROW_IDS.length; r++) {
            View rowView = Anchors.find(wrap, ROW_IDS[r], ROW_NUMS[r]);
            if (!(rowView instanceof ViewGroup)) continue;
            ViewGroup row = (ViewGroup) rowView;
            boolean hidCell = false;
            for (int i = 0; i < row.getChildCount(); i++) {
                View cell = row.getChildAt(i);
                final String label = label(cell);
                if (label == null || label.length() == 0) continue;   // 无文案的装饰格不动
                if (Cfg.debug()) {
                    H.log(Log.INFO, MainHook.TAG, "toolGrid row" + (r + 1) + "[" + i + "]=" + label);
                }
                if (toolCellHidden(label) && hide(cell)) { n++; hidCell = true; }
            }
            // 宫格少一格不能留白 → 同行剩余项按等分槽位重摆（只改 translationX，不动尺寸）
            if (hidCell) spreadRowEvenly(row);
        }
        return n;
    }

    /**
     * 宫格文案 → 配置键。服务端可能下发别名（例如「公交地铁」/「公共交通」），
     * 所以做双向 contains；未知文案一律返回 false（**保持可见**，失效安全）。
     */
    private static boolean toolCellHidden(String label) {
        for (int i = 0; i < Spec.TOOLS.length; i++) {
            String k = Spec.TOOLS[i];
            if (k.equals(label) || label.contains(k) || k.contains(label)) {
                return !Cfg.visible(Spec.K_TOOL + k);
            }
        }
        if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "toolGrid unknown cell kept visible: " + label);
        return false;
    }

    // ══════════════════════════════════════════════════ ③ 回家 / 去公司

    private static int applyHomeCompany(View decor) {
        int n = 0;
        View wrap = Anchors.find(decor, Anchors.ID_HC_WRAP, Anchors.NUM_HC_WRAP);
        if (wrap == null) return 0;

        if (!Cfg.visible(Spec.K_HC)) {
            return hide(wrap) ? 1 : 0;
        }

        final boolean homeV = Cfg.visible(Spec.K_HC_HOME);
        final boolean compV = Cfg.visible(Spec.K_HC_COMPANY);

        View h = Anchors.find(decor, Anchors.ID_HC_HOME, Anchors.NUM_HC_HOME);
        if (h != null && !homeV && hide(h)) n++;

        View c = Anchors.find(decor, Anchors.ID_HC_COMPANY, Anchors.NUM_HC_COMPANY);
        if (c != null && !compV && hide(c)) n++;

        // 中间竖线：两侧有一侧没了就没意义
        View d = Anchors.find(decor, Anchors.ID_HC_DIVIDER, Anchors.NUM_HC_DIVIDER);
        if (d != null && (!homeV || !compV)) hide(d);

        // 「去设置」是纯文案（左右各一个，用了同一个 id），按文案全量摘除
        if (!Cfg.visible(Spec.K_HC_SETTING)) n += hideByExactText(wrap, "去设置");
        return n;
    }

    // ══════════════════════════════════════════════════ ④ 天气 / 行程栏

    private static int applyWeather(View decor) {
        int n = 0;
        final boolean all = Cfg.visible(Spec.K_WEATHER);

        // 地图右侧原生天气条（weather_limited）
        View wx = Anchors.find(decor, Anchors.ID_WX_MAP, Anchors.NUM_WX_MAP);
        if (wx != null && !(all && Cfg.visible(Spec.K_WX_MAP)) && hide(wx)) n++;

        // 底部行程/天气卡（Talos 渲染）
        if (!(all && Cfg.visible(Spec.K_WX_CARD))) n += hideWeatherCard(decor);
        return n;
    }

    private static int hideWeatherCard(View decor) {
        View feed = Anchors.find(decor, Anchors.ID_FEED, Anchors.NUM_FEED);
        if (feed == null) return 0;
        View card = findCardByText(feed, 0);
        if (card == null) return 0;
        if (Cfg.debug()) {
            H.log(Log.INFO, MainHook.TAG, "weather card hit: " + card.getClass().getName());
        }
        return hideCollapse(card) ? 1 : 0;
    }

    /** 在容器的可见子树里找"卡内文案命中天气关键词"的节点，返回它所属的瀑布流 item 根 */
    private static View findCardByText(View v, int depth) {
        if (v == null || depth > 40) return null;
        try {
            if (v.getVisibility() == View.GONE) return null;
            if (v instanceof TextView) {
                String t = text((TextView) v);
                if (t != null) {
                    for (int i = 0; i < WX_WORDS.length; i++) {
                        if (t.contains(WX_WORDS[i])) return feedItemRoot((View) v);
                    }
                }
                return null;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findCardByText(g.getChildAt(i), depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ══════════════════════════════════════════════════ ⑤ 推荐信息流（展开态）

    /**
     * 频道栏「推荐 / 看世界 / 成都市 …」+「优质内容精选」及推荐卡片。
     * 全部由 Baidu Talos（类 React）渲染 → **没有资源 id**，只能按
     * 「同容器内命中 ≥2 个文案锚点 + 容器高度足够小」这种结构约束定位，避免误伤卡片正文。
     */
    private static int applyFeed(View decor) {
        View feed = Anchors.find(decor, Anchors.ID_FEED, Anchors.NUM_FEED);
        if (feed == null) return 0;

        final boolean all = Cfg.visible(Spec.K_FEED);
        final boolean chips = all && Cfg.visible(Spec.K_FEED_CHIPS);
        final boolean quality = all && Cfg.visible(Spec.K_FEED_QUALITY);
        if (all && chips && quality) return 0;

        if (!all) return hide(feed) ? 1 : 0;   // 整块信息流

        int n = 0;
        if (!chips) n += hideChipRow(feed);
        if (!quality) n += hideFromQualityDown(feed);
        return n;
    }

    /** 频道栏：找一个「高度小 + 子树里同时出现 ≥2 个频道锚点」的容器 */
    private static int hideChipRow(View feed) {
        View row = findAnchorContainer(feed, Spec.CHIP_ANCHORS, 0, 320);
        if (row == null) {
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "chips: no container hit");
            return 0;
        }
        if (Cfg.debug()) {
            H.log(Log.INFO, MainHook.TAG, "chips hit " + row.getClass().getName()
                    + " h=" + (row.getBottom() - row.getTop()));
        }
        final java.util.List<ViewGroup> dirty = new java.util.ArrayList<>();
        boolean ok = collapse(row, dirty);
        for (int i = 0; i < dirty.size(); i++) restackColumn(dirty.get(i));
        return ok ? 1 : 0;
    }

    /** 「优质内容精选」及其下方全部推荐卡片：从锚点所在瀑布流 item 起，往后整排摘除 */
    private static int hideFromQualityDown(View feed) {
        View anchor = findByExactText(feed, Spec.QUALITY_ANCHOR, 0);
        if (anchor == null) {
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "quality: anchor not found");
            return 0;
        }
        View item = feedItemRoot(anchor);
        if (item == null) {
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "quality: no WaterFallChildView ancestor");
            return 0;
        }
        int n = 0;
        final java.util.List<ViewGroup> dirty = new java.util.ArrayList<>();
        ViewParent p = item.getParent();
        if (p instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) p;
            int idx = g.indexOfChild(item);
            for (int i = idx; i < g.getChildCount(); i++) {
                if (collapse(g.getChildAt(i), dirty)) n++;
            }
        } else if (collapse(item, dirty)) {
            n = 1;
        }
        // 推荐卡是瀑布流 item，同样不会自己塌陷 → 手工往上贴
        for (int i = 0; i < dirty.size(); i++) restackColumn(dirty.get(i));
        if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "quality: removed " + n + " feed items");
        return n;
    }

    /** DFS 找「子树里同时命中 >=2 个 $words（精确文本）+ 高度 <= maxDp」的最深容器 */
    private static View findAnchorContainer(View v, String[] words, int depth, int maxDp) {
        if (v == null || depth > 30) return null;
        try {
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findAnchorContainer(g.getChildAt(i), words, depth + 1, maxDp);
                    if (r != null) return r;
                }
            }
            if (v.getVisibility() == View.GONE) return null;
            final int h = v.getBottom() - v.getTop();
            if (h <= 0 || h > dpPx(v, maxDp)) return null;
            int hit = 0;
            for (int i = 0; i < words.length; i++) if (subtreeHasExactText(v, words[i])) hit++;
            return hit >= 2 ? v : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean subtreeHasExactText(View v, String want) {
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                return cs != null && want.equals(cs.toString().trim());
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (subtreeHasExactText(g.getChildAt(i), want)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static View findByExactText(View v, String want, int depth) {
        if (v == null || depth > 40) return null;
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && want.equals(cs.toString().trim())) return v;
                return null;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findByExactText(g.getChildAt(i), want, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ══════════════════════════════════════════════════ ⑥ 「我的」页

    /**
     * 「我的」页（user_center_talos_container）。
     *
     * 结构实测（v0.1.6 probe，TREE all）：
     *   区块根 ReactViewGroup 0,2537-1079,3502
     *     └ ReactViewGroup 0,0-1079,965
     *         └ ReactViewGroup 21,11-1058,955
     *             └ 头部 ReactViewGroup 0,0-1037,84 → ReactTextView txt=热门语音
     * 全是 Talos，没有资源 id 也没有 WaterFallChildView，所以按
     * **精确文案 + 满宽区块**定位：标题往上找「宽度接近满宽、高度在合理区间的最外层祖先」。
     */
    private static int applyMine(View decor) {
        // ── v0.4.0：视图层方案**整体停用**（保留代码仅作历史记录，不再执行）──────────────
        // 该页由 Talos/Yoga 自绘：GONE 不塌陷、removeView 不重排、手工 translationY 重排会
        // 把整页推乱 —— 三次回归（v0.1.7 白屏 / v0.1.8 误伤宫格 / v0.3.0 整页清空）已定型。
        // 现改在**数据层**拦 JSON：{@link MineData} 摘掉 serverData 里对应字段，
        // 卡片的 s-if 恒假 → 组件根本不创建 → 布局由引擎从零算（无留白、无误伤）。
        if (true) return 0;
        if (!Spec.mineEnabled()) return 0;      // 总闸（可在设置页关闭）
        View uc = Anchors.find(decor, Anchors.ID_USER_CENTER, Anchors.NUM_USER_CENTER);
        if (uc == null || uc.getVisibility() != View.VISIBLE) return 0;   // 不是我的页（或不可见）

        final java.util.List<ViewGroup> dirty = new java.util.ArrayList<>();
        int n = 0;
        // ① 强制清除（无开关）：广告 / 运营卡
        for (int i = 0; i < Spec.MINE_FORCE.length; i++) {
            String[] g = Spec.MINE_FORCE[i];
            n += (g.length >= 2) ? hideByAnchors(uc, g, 250, dirty)
                                 : hideSection(uc, g[0], 400, dirty);
        }
        // ② 逐项开关
        if (!Cfg.visible(Spec.K_MINE_GRID)) {
            n += hideByAnchors(uc, Spec.MINE_GRID_TEXTS, 6, 220, dirty);
        }
        if (!Cfg.visible(Spec.K_MINE_VOICE)) n += hideSection(uc, "热门语音", 400, dirty);
        if (!Cfg.visible(Spec.K_MINE_CAR)) n += hideSection(uc, "我的车", 400, dirty);
        if (!Cfg.visible(Spec.K_MINE_CARNAV)) n += hideSection(uc, "导航车标", 400, dirty);
        if (!Cfg.visible(Spec.K_MINE_SPORT)) n += hideSection(uc, "百度运动", 400, dirty);
        // 「全民共建」块的标题是图片徽章，TextView 里只有「反馈中心」——用错的锚点永远关不掉
        if (!Cfg.visible(Spec.K_MINE_BUILD)) n += hideSection(uc, "反馈中心", 400, dirty);

        // ③ 关键：Talos 不会自己重排，手工把后面的块往上贴，消除留白
        for (int i = 0; i < dirty.size(); i++) restackColumn(dirty.get(i));
        return n;
    }

    /** 组合出「除 requested 之外的其它锚点」；候选容器命中任一个就说明它是壳，拒绝 */
    private static String[] forbiddenFor(String[] requested) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        collect(out, Spec.MINE_SECTION_ANCHORS, requested);
        collect(out, Spec.MINE_GRID_TEXTS, requested);
        return out.toArray(new String[0]);
    }

    private static void collect(java.util.List<String> out, String[] pool, String[] requested) {
        for (int i = 0; i < pool.length; i++) {
            boolean skip = false;
            for (int j = 0; j < requested.length; j++) {
                if (pool[i].equals(requested[j])) { skip = true; break; }
            }
            if (!skip && !out.contains(pool[i])) out.add(pool[i]);
        }
    }

    private static int hideByAnchors(View root, String[] requested, int maxDp,
                                     java.util.List<ViewGroup> dirty) {
        return hideByAnchors(root, requested, requested.length, maxDp, dirty);
    }

    /** 命中 ≥minHits 个 requested 锚点、不含任何禁词锚点、高度 ≤maxDp 的**最深**容器 → 摘除 */
    private static int hideByAnchors(View root, String[] requested, int minHits, int maxDp,
                                     java.util.List<ViewGroup> dirty) {
        final String[] forbid = forbiddenFor(requested);
        View c = searchSafe(root, requested, forbid, minHits, maxDp, 0);
        if (c == null) {
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "mine: no safe container for "
                    + requested[0] + " (need>=" + minHits + ")");
            return 0;
        }
        if (Cfg.debug()) {
            H.log(Log.INFO, MainHook.TAG, "mine hit [" + requested[0] + "] "
                    + c.getClass().getSimpleName() + " h=" + (c.getBottom() - c.getTop()));
        }
        return collapse(c, dirty) ? 1 : 0;
    }

    private static View searchSafe(View v, String[] req, String[] forbid,
                                   int minHits, int maxDp, int depth) {
        if (v == null || depth > 30) return null;
        try {
            if (v instanceof ViewGroup) {           // 先深后浅：取最深的满足者
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = searchSafe(g.getChildAt(i), req, forbid, minHits, maxDp, depth + 1);
                    if (r != null) return r;
                }
            }
            if (v.getVisibility() == View.GONE) return null;
            final int h = v.getBottom() - v.getTop();
            if (h <= 0 || h > dpPx(v, maxDp)) return null;
            int hits = 0;
            for (int i = 0; i < req.length; i++) if (subtreeHasExactText(v, req[i])) hits++;
            if (hits < minHits) return null;
            for (int i = 0; i < forbid.length; i++) {
                if (subtreeHasExactText(v, forbid[i])) return null;   // 是壳，拒绝
            }
            return v;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 视图层兜底（v2.0.1）：JS 层注入被 App 的 bundle 加载机制挡住时，
     * 「百度运动 / 全民共建」这两张卡用**标题锚点整块摘**关掉。
     * 复用首页同款的 hideSection —— 它自带三重保险（满宽 / 高度上限 / 禁词消歧），
     * 是 v0.1.7 白屏事故之后加的，只摘单张卡不会动到页面骨架。
     */
    private static int applyMineCards(View decor) {
        int n = 0;
        try {
            java.util.List<ViewGroup> dirty = new java.util.ArrayList<ViewGroup>();
            if (!Cfg.visible(Spec.K_MINE_SPORT, Spec.defaultVisible(Spec.K_MINE_SPORT))) {
                // 宫格里也有「百度运动」这四个字，单锚点会选中宫格里的那一条 → 用双锚点 LCA
                n += hideCardByTexts(decor, "百度运动", "开始运动", "百度运动");
            }
            // 「全民共建」的标题是图片徽章，TextView 里只有「反馈中心」（Spec 里记过这条）
            if (!Cfg.visible(Spec.K_MINE_BUILD, Spec.defaultVisible(Spec.K_MINE_BUILD))) {
                n += hideSection(decor, "反馈中心", 420, dirty);
            }
            if (!Cfg.visible(Spec.K_MINE_GRID, Spec.defaultVisible(Spec.K_MINE_GRID))) {
                n += hideSection(decor, "常用功能", 420, dirty);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "mine cards failed: " + t);
        }
        if (n > 0) Cfg.log("mine cards(view): hidden=" + n);
        return n;
    }
    /**
     * 双锚点摘卡：取两张文案的**最近公共祖先**再摘。
     *
     * 为什么需要它：`百度运动` 这四个字在「我的」页出现两次 —— 卡片标题 + 顶部图标宫格里的一个图标名。
     * 单锚点 `findByExactText` 会先命中宫格里的那一条，`sectionRootSafe` 自然拒绝（不是满宽区块）→ 卡片纹丝不动。
     * 用「百度运动 + 开始运动」两张只在卡片里同时出现的文案取 LCA，唯一确定卡片根。
     */
    private static int hideCardByTexts(View decor, String t1, String t2, String tag) {
        try {
            View a = findByExactText(decor, t1, 0);
            View b = findByExactText(decor, t2, 0);
            if (a == null || b == null) {
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "mine card: anchor missing " + t1 + "/" + t2);
                return 0;
            }
            java.util.ArrayList<View> chain = new java.util.ArrayList<View>();
            View p = a;
            int guard = 0;
            while (p != null && guard++ < 40) { chain.add(p); ViewParent q = p.getParent(); p = (q instanceof View) ? (View) q : null; }
            View lca = null;
            p = b;
            guard = 0;
            while (p != null && guard++ < 40) {
                if (chain.contains(p)) { lca = p; break; }
                ViewParent q = p.getParent();
                p = (q instanceof View) ? (View) q : null;
            }
            if (lca == null) return 0;
            // 往上再找一层"满宽 + 不太高"的卡片壳（LCA 往往只是标题行）
            View card = lca;
            View par = (lca.getParent() instanceof View) ? (View) lca.getParent() : null;
            if (par != null) {
                int pw = par.getWidth();
                int w = lca.getRight() - lca.getLeft();
                if (pw <= 0 || w >= (int) (pw * 0.8)) {
                    int h = par.getBottom() - par.getTop();
                    if (h > 0 && h <= pxOf(par, 460) && !tooBigToHide(par)) card = par;
                }
            }
            if (tooBigToHide(card)) return 0;
            java.util.List<ViewGroup> dirty = new java.util.ArrayList<ViewGroup>();
            boolean ok = collapse(card, dirty);
            if (ok) Cfg.log("mine card(" + tag + "): hidden "
                    + card.getClass().getSimpleName() + " h=" + (card.getBottom() - card.getTop()));
            return ok ? 1 : 0;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "mine card " + tag + " failed: " + t);
            return 0;
        }
    }

    /** dp → px（用某个已有 View 的 metrics，省得传 Context） */
    private static int pxOf(View v, int dp) {
        try {
            return (int) (dp * v.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return dp * 3;
        }
    }

    /** 标题文案 → 满宽区块根（maxDp 上限 + tooBigToHide + 禁词消歧 三重保险） */
    private static int hideSection(View root, String title, int maxDp,
                                   java.util.List<ViewGroup> dirty) {
        View t = findByExactText(root, title, 0);
        if (t == null) {
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "mine: title not found " + title);
            return 0;
        }
        View sec = sectionRootSafe(t, title, maxDp);
        if (sec == null) {
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "mine: no safe section for " + title);
            return 0;
        }
        if (Cfg.debug()) {
            H.log(Log.INFO, MainHook.TAG, "mine hit [" + title + "] "
                    + sec.getClass().getSimpleName() + " h=" + (sec.getBottom() - sec.getTop()));
        }
        return collapse(sec, dirty) ? 1 : 0;
    }

    private static View sectionRootSafe(View child, String title, int maxDp) {
        final String[] forbid = forbiddenFor(new String[]{title});
        View best = null;
        View p = child;
        int guard = 0;
        while (p != null && guard++ < 30) {
            ViewParent par = p.getParent();
            if (!(par instanceof View)) break;
            View parent = (View) par;
            final int w = p.getRight() - p.getLeft();
            final int h = p.getBottom() - p.getTop();
            final int pw = parent.getWidth();
            if (pw > 0 && w >= (int) (pw * 0.85)
                    && h > dpPx(p, 120) && h <= dpPx(p, maxDp) && !tooBigToHide(p)) {
                boolean bad = false;
                for (int i = 0; i < forbid.length; i++) {
                    if (subtreeHasExactText(p, forbid[i])) { bad = true; break; }
                }
                if (!bad) best = p;      // 继续往上，取最外层**仍然安全**的
            }
            p = parent;
        }
        return best;
    }

    /**
     * 摘除区块，并把它所在的父容器登记进 $dirty（稍后统一做手工竖向重排）。
     *
     * ⚠ Talos（类 React / Yoga 自绘）设 GONE + 高度 0 **不塌陷**，只会留一块空白；
     * 所以这里优先**从父容器移除**，再由 {@link #restackColumn} 把后续兄弟块贴上来。
     */
    private static boolean collapse(View v, java.util.List<ViewGroup> dirty) {
        if (v == null) return false;
        try {
            final ViewParent p = v.getParent();
            ViewGroup parent = (p instanceof ViewGroup) ? (ViewGroup) p : null;
            if (parent != null && parent.indexOfChild(v) >= 0) {
                parent.removeView(v);
                parent.requestLayout();
                if (dirty != null && !dirty.contains(parent)) dirty.add(parent);
                return true;
            }
        } catch (Throwable ignored) {}
        return hideCollapse(v);
    }

    /**
     * 手工竖向重排：把容器里剩余可见子项**按原间距**依次贴紧。
     *
     * 只改 `translationY`（绘制与触摸都跟着走），**不改尺寸** —— 所以不会像上次对
     * ShortCutRow 改 width 那样把触发区撑大 / 让行串位。
     * 每次都用 layout 坐标（getTop/getHeight 不受 translation 影响）重新算，天然幂等，
     * 引擎重新布排之后下一次复扫会自动纠正。
     */
    private static void restackColumn(ViewGroup g) {
        if (g == null) return;
        try {
            int cursor = Integer.MIN_VALUE;
            View prevVisible = null;
            int moved = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                final int h = c.getHeight();
                if (h <= 0) continue;                     // 还没量到尺寸，跳过
                if (cursor == Integer.MIN_VALUE) {
                    cursor = c.getTop();                  // 第一个可见项留在原位
                } else if (prevVisible != null) {
                    // 保留引擎原本的块间距（用 layout 坐标算，与 translation 无关）
                    final int gap = c.getTop() - prevVisible.getBottom();
                    cursor += (gap > 0 ? gap : 0);
                }
                c.setTranslationY(cursor - c.getTop());
                cursor += h;
                prevVisible = c;
                moved++;
            }
            if (Cfg.debug() && moved > 1) {
                H.log(Log.INFO, MainHook.TAG, "restackColumn " + g.getClass().getSimpleName()
                        + " visibleItems=" + moved);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 手工横向均分：把一行里剩余可见项按「等分槽位 + 槽内居中」重新摆位。
     * 同样只改 `translationX`，尺寸与命中区域不变。
     */
    private static void spreadRowEvenly(ViewGroup row) {
        if (row == null) return;
        try {
            int visible = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                if (row.getChildAt(i).getVisibility() != View.GONE) visible++;
            }
            final int w = row.getWidth();
            if (visible == 0 || w <= 0) return;
            int idx = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                final int slot = w / visible;
                final int target = idx * slot + Math.max(0, (slot - c.getWidth()) / 2);
                c.setTranslationX(target - c.getLeft());
                idx++;
            }
            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "spreadRowEvenly " + row.getClass().getSimpleName()
                        + " visible=" + visible + " w=" + w);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 隐藏「区块级」容器前的**最后一道保险**。
     *
     * v0.1.7 事故：`sectionRoot` 的阈值单位写错（按 px 推导却用 dp 换算比较），
     * 结果把整页 TalosRootView（h=2240）判成"小于上限"，直接摘掉 → 百度地图整页白屏。
     * 从此所有区块级隐藏都必须过这一关：**高度超过屏幕 45% 的容器一律不碰**。
     */
    private static boolean tooBigToHide(View v) {
        try {
            final int h = v.getBottom() - v.getTop();
            final int screen = v.getResources().getDisplayMetrics().heightPixels;
            if (h <= 0) return true;                 // 尺寸拿不到就不动手
            if (h >= (int) (screen * 0.45)) {
                H.log(Log.WARN, MainHook.TAG, "refuse to hide oversized block h=" + h
                        + " screen=" + screen + " cls=" + v.getClass().getName());
                return true;
            }
            return false;
        } catch (Throwable t) {
            return true;                             // 失效安全：宁可留着
        }
    }

    /** 摘除区块（含最后保险） */
    private static boolean hideBlock(View v) {
        if (v == null || tooBigToHide(v)) return false;
        return hideCollapse(v);
    }

    /**
     * 标题文案 → 往上找「区块根」：高度在 (120dp, 600dp]、宽度接近满宽的**最外层**祖先。
     *
     * ⚠ 阈值必须当 dp 用（过 dpPx 换算）。600dp ≈ 1800px 是实测值：
     *   区块实测 965px（热门语音）、500px（宫格）、400px（出行保/借钱行）都落在区间内；
     *   整页根 2240px（=747dp）> 600dp，被排除 —— 这一步就是白屏事故的修复点。
     */
    private static View sectionRoot(View child, int maxDp) {
        View best = null;
        View p = child;
        int guard = 0;
        while (p != null && guard++ < 30) {
            ViewParent par = p.getParent();
            if (!(par instanceof View)) break;
            View parent = (View) par;
            final int w = p.getRight() - p.getLeft();
            final int h = p.getBottom() - p.getTop();
            final int pw = parent.getWidth();
            if (pw > 0 && w >= (int) (pw * 0.85)
                    && h > dpPx(p, 120) && h <= dpPx(p, maxDp) && !tooBigToHide(p)) {
                best = p;      // 继续往上，取最外层满足条件的
            }
            p = parent;
        }
        return best;
    }

    /** 深度优先找「子树同时包含全部 $words（精确文本）+ 自身高度 <= maxDp」的最深容器 */
    private static View findContainerWithAll(View v, String[] words, int depth, int maxDp) {
        if (v == null || depth > 30) return null;
        try {
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findContainerWithAll(g.getChildAt(i), words, depth + 1, maxDp);
                    if (r != null) return r;
                }
            }
            if (v.getVisibility() == View.GONE) return null;
            final int h = v.getBottom() - v.getTop();
            if (h <= 0 || h > dpPx(v, maxDp)) return null;
            for (int i = 0; i < words.length; i++) {
                if (!subtreeHasExactText(v, words[i])) return null;
            }
            return v;
        } catch (Throwable ignored) {}
        return null;
    }

    // ══════════════════════════════════════════════════ 均分重排

    /**
     * 隐藏格子/入口后把同行剩余项**均分铺满**（避免留白）。
     *
     * ⚠ 只对 LinearLayout（含子类）动手，并且靠 weight + **weightSum** 两个一起改才生效：
     *   Baidu 底栏 new_btns 带固定 weightSum=5，只给 weight=1 的话分母还是 5，
     *   两个 tab 各占 1/5 宽 → "挤在最左边"（用户实测，已修）。
     *
     * ⚠⚠ 对**自定义 ViewGroup**（如 ShortCutRow）**不做重排**。v0.1.7 事故：
     *   之前对 ShortCutRow 按「容器宽/可见数」改写子项 width，实测后果是
     *   ①被隐藏格子的相邻格触发区被撑大（点实时公交会连带触发周围区域）；
     *   ②行与行之间串位（左列勾开的功能跑到第三列才显示）。
     *   也就是说这类容器的命中测试/行排布并不吃子项 width，改它只会把布局逻辑弄坏。
     *   宫格的留白只能靠宿主自己的重排，本模块不碰 —— 宁可留白，不可改坏。
     */
    private static void reflow(ViewGroup group) {
        if (group == null) return;
        if (!(group instanceof android.widget.LinearLayout)) {
            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "reflow skipped (custom ViewGroup): "
                        + group.getClass().getName());
            }
            return;
        }
        try {
            int visible = 0, hidden = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i).getVisibility() == View.GONE) hidden++;
                else visible++;
            }
            if (hidden == 0 || visible == 0) return;

            final android.widget.LinearLayout ll = (android.widget.LinearLayout) group;
            for (int i = 0; i < ll.getChildCount(); i++) {
                View c = ll.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (!(lp instanceof android.widget.LinearLayout.LayoutParams)) continue;
                android.widget.LinearLayout.LayoutParams p =
                        (android.widget.LinearLayout.LayoutParams) lp;
                if (p.width != 0 || p.weight != 1f) {
                    p.width = 0;
                    p.weight = 1f;
                    c.setLayoutParams(p);
                }
            }
            if (ll.getWeightSum() != (float) visible) ll.setWeightSum((float) visible);
            ll.requestLayout();
            if (Cfg.debug()) {
                H.log(Log.INFO, MainHook.TAG, "reflow LinearLayout visible=" + visible
                        + " hidden=" + hidden + " w=" + ll.getWidth()
                        + " weightSum=" + ll.getWeightSum());
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "reflow failed: " + t);
        }
    }

    private static int dpPx(View v, int dp) {
        try {
            return (int) (dp * v.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return dp * 3;
        }
    }

    /** 从命中文案往上找瀑布流 item 根（Talos 的 item 根类名带 WaterFallChildView）。 */
    private static View feedItemRoot(View v) {
        View p = v;
        int guard = 0;
        while (p != null && guard++ < 40) {
            String cn = p.getClass().getName();
            if (cn.contains("WaterFallChildView")) return p;
            ViewParent par = p.getParent();
            p = (par instanceof View) ? (View) par : null;
        }
        return null;
    }

    // ══════════════════════════════════════════════════ 工具方法

    /** 摘除（GONE）。返回 true 表示本次真的改了状态（幂等性判据）。 */
    private static boolean hide(View v) {
        if (v == null) return false;
        try {
            if (v.getVisibility() == View.GONE) return false;
            v.setVisibility(View.GONE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 摘除并压塌高度。
     * Talos 的瀑布流/绝对定位容器不会因为子节点 GONE 就塌陷，
     * 不压高度会留一个空洞。
     */
    private static boolean hideCollapse(View v) {
        if (v == null) return false;
        try {
            boolean changed = false;
            if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                changed = true;
            }
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
                v.setLayoutParams(lp);
                changed = true;
            }
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按精确文案摘除子树里所有命中的 TextView（用于「去设置」这类无独立 id 的重复项） */
    private static int hideByExactText(View root, String want) {
        int n = 0;
        try {
            if (root instanceof TextView) {
                String t = text((TextView) root);
                if (want.equals(t) && hide(root)) n++;
                return n;
            }
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) n += hideByExactText(g.getChildAt(i), want);
            }
        } catch (Throwable ignored) {}
        return n;
    }

    /** 取节点子树里第一个非空 TextView 文案（宫格格子 = FrameLayout(image + text)） */
    private static String label(View v) {
        try {
            if (v instanceof TextView) return text((TextView) v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    String s = label(g.getChildAt(i));
                    if (s != null && s.length() > 0) return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String text(TextView t) {
        try {
            CharSequence c = t.getText();
            return c == null ? null : c.toString().trim();
        } catch (Throwable e) {
            return null;
        }
    }
}
