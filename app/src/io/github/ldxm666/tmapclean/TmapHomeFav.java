package io.github.ldxm666.tmapclean;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 首页「设置家 / 设置公司」一行、以及下方推荐信息流卡片。
 *
 * <h3>dex 实证锚点（11.7.0，tools/dex_query.py 逐条反编译）</h3>
 * <pre>
 *  navisdk.a.ewt                     = 首页默认卡片的适配器
 *    d(ViewGroup)V  code_off=0x267f60 = initSimpleHomeAndCompanyView
 *        inflate → findViewById → aB:LinearLayout / aJ:TextView（公司行）
 *                              → aH:LinearLayout / aI:TextView（家行）
 *        aB → aq() → ap() → 日志 "getDefaultCompanyClickListener"
 *        aH → ao() → ar() → 日志 "getDefaultHomeClickListener"
 *    d(String)V     code_off=0x2683f4 = refreshDefaultAddressUI（模式/文案刷新）
 *        由 c(String)V → lambda$34YLaN8FqCTzAQl1zOqMT-uoMIg 调用；
 *        c(String) 走 kje.e 主线程调度，带 "mini"/"expanding"/"default" 三种模式串。
 *    createView(ViewGroup)V          = 卡片入口；if(bd) c(...) else b(...)，两者最终都到 d(...)
 *
 *  navisdk.a.gcd
 *    a(ArrayList)List code_off=0x3b86f4 = 服务端 Card → 首页 iri 的唯一转换点
 *        iri.a=Card.cardType  iri.b=Card.template  iri.c=Card.priority  iri.j=Card.cardName
 *
 *  HomeCardPresenter.e(List)V code_off=0x2c1d7c → HomePageCardAdapter.a(List)V
 *        = "addOrUpdateHeaders: N"，更靠近 UI 的注入口
 *
 *  kuiklyPoi...render.RenderPeopleStillWatchingKt.a(...)  = 「大家都在看」渲染入口
 *        ⚠ 类名必须是 components/LookAndLook/RecommendationViewAttr（目录分隔符 /）
 * </pre>
 *
 * <h3>踩过的坑（都在真机上验证过，改之前先读完）</h3>
 * <ol>
 *   <li>只 GONE {@code ewt.aH/aB} → <b>无效</b>。真机 PATH 取证显示那只是最内层节点，
 *       外层行容器（{@code #expanding_default}）仍 VISIBLE，整行照显示。</li>
 *   <li>按文案猜层级、整链 GONE → 会把标题「收藏与关注」一起弄没（误伤）。</li>
 *   <li>改 {@code ewt} 的数据方法（updateAddressList 等）→ 整张首页卡片被清空。
 *       因为 {@code ewt} 是整张卡片的适配器，不是设置家那一行。</li>
 * </ol>
 *
 * <h3>现行策略（v1.2.6：按区块分别收口，两个开关互不牵连）</h3>
 * <ul>
 *   <li><b>收藏与关注</b>（{@code ui_home_fav}）：VL 卡片根 ComponentHost 的「收藏与关注」区块
 *       —— 标题 + 设置家/设置公司一行。</li>
 *   <li><b>推荐信息流</b>（{@code ui_home_feed}）：VL 卡片里的「大家都在看」区块
 *       —— 标题 + 频道栏 + 下方推荐卡片。</li>
 *   <li>两个区块都靠<b>内容特征</b>判定（{@code SectionsRecyclerView} /
 *       {@code LithoHorizontalScrollView} = 信息流；{@code VLImageView} = 收藏与关注），
 *       不依赖下标，也不依赖任何混淆名。见 {@link #applySections(View)}。</li>
 *   <li>旧版原生行（{@code aH/aB} + LCA 文案法）保留作兜底；认不出 Litho 结构时
 *       退回「两个开关都关才整块收」的保守做法（失效安全，绝不误伤）。</li>
 *   <li>已废弃：{@code installHippyGate()}（拦 {@code loadHippyView("poi")}）。
 *       真机 xref 证明首页根本不走 Hippy，那条闸门只误伤地点详情页。</li>
 *   <li>推荐卡片的数据层过滤（{@code gcd.a(ArrayList)} / {@code HomeCardPresenter.e(List)}）
 *       保留作第二道防线；真机实测这两条路径在 11.7.0 上不触发（首页走 VL），
 *       认不出卡片名时一律保留（失效安全）。</li>
 * </ul>
 */
final class TmapHomeFav {

    private static final String HOME_CARD = "com.tencent.map.navisdk.a.ewt";
    /** 建卡入口：d(ViewGroup)V = initSimpleHomeAndCompanyView（唯一签名，勿挂成 b） */
    private static final String BUILD = "d";
    /** 模式/文案刷新入口：d(String)V = refreshDefaultAddressUI */
    private static final String REFRESH = "d";

    /** ewt 持有根视图的字段（实证自 createView 的 iput） */
    private static final String[] ROOT_FIELDS = {"at", "ar", "aw"};

    /** 服务端卡片 → iri 的唯一转换点 */
    private static final String CARD_TRANSFORM = "com.tencent.map.navisdk.a.gcd";
    /** 更靠近 UI 的卡片注入口 */
    private static final String CARD_PRESENTER =
            "com.tencent.map.ama.newhome.presenter.HomeCardPresenter";

    /** 文案锚点（dex 实证：ewt.J()="设置家"、ewt.I()="设置公司/学校"） */
    private static final String[] FAV_TEXTS = {"设置家", "设置公司", "学校"};
    private static final String CARD_ROOT_MARK = "tv_home_back_card";

    /** 服务端下发的推荐/运营类卡片名（本地无静态值，只能按名匹配） */
    private static final String[] FEED_CARD_NAMES = {
            "recommend", "feed", "operation", "welfare", "coupon",
            "newuser", "new_user", "activity", "banner", "promotion",
            "addshortcut", "add_shortcut", "desktop",
    };

    private TmapHomeFav() {}

    // ══════════════════════════════════════════════════════════════════
    // 安装
    // ══════════════════════════════════════════════════════════════════

    static void install(ClassLoader cl) {
        Class<?> card = H.cls(cl, HOME_CARD);
        if (card == null) {
            Diag.note("homecard", "null");
            return;
        }
        Diag.note("homecard", HOME_CARD);

        // ① 建卡入口：d(ViewGroup)V —— 精确签名（ewt 里 d 的重载很多）
        H.hookSig(card, BUILD, "tmap_homefav_build", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                Diag.hit("homefav_build");
                applyHomeFav(chain.getThisObject());
                scheduleFavHide(chain.getThisObject());
                // 取证：这棵树上到底有几条"设置家"、各自的 id 链是什么。
                View root = viewOf(chain.getThisObject());
                if (root != null) {
                    root.postDelayed(new Runnable() {
                        @Override public void run() {
                            StringBuilder sb = new StringBuilder(1500);
                            int[] n = new int[1];
                            countFav(root, 0, sb, n);
                            H.log(Log.INFO, MainHook.TAG, "FAVCOUNT n=" + n[0] + " | " + sb);
                            // 追加：卡片根的直接子节点全列出来，看那一行到底是哪个
                            View cardRoot = findCardRoot(root, 0);
                            if (cardRoot instanceof ViewGroup) {
                                StringBuilder cs = new StringBuilder(600);
                                ViewGroup cg = (ViewGroup) cardRoot;
                                cs.append("cardRoot=").append(idOf(cardRoot))
                                  .append(" children=").append(cg.getChildCount()).append(':');
                                for (int i = 0; i < cg.getChildCount(); i++) {
                                    View c = cg.getChildAt(i);
                                    if (c == null) continue;
                                    cs.append('[').append(i).append(']')
                                      .append(c.getClass().getSimpleName())
                                      .append('(').append(c.getVisibility()).append(')')
                                      .append("id=").append(idOf(c)).append(' ');
                                }
                                H.log(Log.INFO, MainHook.TAG, "CARDROOT " + cs);
                            }
                        }
                    }, 900L);
                }
                return result;
            }
        }, android.view.ViewGroup.class);

        // ② 模式切换 / 文案刷新入口：d(String)V = refreshDefaultAddressUI
        //    建卡只在冷启动走一次；下拉展开 / 收起 / mini 切换走的是这条。
        //    不挂这里，收起态那一行永远活着 —— 上一版就是这么失效的。
        H.hookSig(card, REFRESH, "tmap_homefav_refresh", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (!Cfg.visible(Spec.K_HOME_FAV, true)) {
                    applyHomeFav(chain.getThisObject());
                    scheduleFavHide(chain.getThisObject());
                }
                return result;
            }
        }, java.lang.String.class);

        installFeedFilter(cl);
        installHomeCardGate(cl);
    }

    // ══════════════════════════════════════════════════════════════════
    // 首页 VL 卡片：按区块分别收口（收藏与关注 / 推荐信息流）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 首页那一整块 VL（VectorLayout）卡片的载体字段。
     *
     * <p>dex 实证（11.7.0 classes14.dex，{@code tools/dex_query.py --Dump createView}）：
     * <pre>
     *   Lcom/tencent/map/navisdk/a/ewt;->createView(Landroid/view/ViewGroup;)Landroid/view/View;
     *     [ 258] invoke-virtual ViewGroup->getContext()Landroid/content/Context;
     *     [ 262] invoke-direct  ewt->b(Landroid/content/Context;)Landroid/view/ViewGroup;
     *     [ 266] iput-object    ewt->ak:Landroid/view/ViewGroup;   ← 就是它
     *     [ 287] invoke-virtual HomeRecycleView->addHeaderView(Landroid/view/View;)
     *
     *   Lcom/tencent/map/navisdk/a/ewt;->b(Landroid/content/Context;)Landroid/view/ViewGroup;
     *     [ 28] const 0x7f0b0b10
     *     [ 31] invoke-virtual ConstraintLayout->setId(I)V
     * </pre>
     * {@code 0x7f0b0b10} = {@code app:id/home_hippy_main}（真机 {@code dumpsys activity top} 实证）。
     */
    private static final String CARD_HEADER_FIELD = "ak";

    /** VL 卡片根（VectorLayout 渲染器）；区块都在它里面。 */
    private static final String VL_CARD = "com.tencent.map.extraordinarymap.res.MapVLCardView";
    /** VL 卡片里的 Litho 根视图 */
    private static final String LITHO_VIEW = "com.facebook.litho.LithoView";
    /** Litho 的组件宿主（每渲染一个组件就有一个，是真实 ViewGroup） */
    private static final String COMPONENT_HOST = "com.facebook.litho.ComponentHost";
    /** 只有「推荐信息流」区块里才有这两个：卡片列表 / 频道栏 */
    private static final String[] FEED_MARKS = {
            "com.facebook.litho.widget.SectionsRecyclerView",
            "com.facebook.litho.widget.LithoHorizontalScrollView",
    };
    /** 「收藏与关注」区块里必有 VL 图片（收藏 / 设置家的图标） */
    private static final String VL_IMAGE = "com.tencent.vectorlayout.vlcomponent.image.VLImageView";
    /**
     * 只要子树里有这些，就<b>不</b>当作「收藏与关注」区块处理。
     *
     * <p>「收藏与关注」是一块紧凑的两行（标题 + 设置家/设置公司），里面不会出现列表或横向滚动；
     * 反过来，通勤卡 / 实时公交这类卡片一定带列表。把它们挡在这条判定之外，
     * 就不会出现"关掉设置家把通勤卡也关了"的误伤。
     */
    private static final String[] NOT_FAV_MARKS = {
            "com.facebook.litho.widget.SectionsRecyclerView",
            "com.facebook.litho.widget.LithoHorizontalScrollView",
            "com.facebook.litho.widget.LithoRecylerView",
            "androidx.recyclerview.widget.RecyclerView",
    };

    /** 区块归属：收藏与关注（标题 + 设置家/设置公司一行） */
    private static final String SEC_FAV = "fav";
    /** 区块归属：推荐信息流（大家都在看标题 + 频道栏 + 推荐卡片） */
    private static final String SEC_FEED = "feed";

    /**
     * 被本模块收掉的区块：{@code view → 归属}。开关重开时只恢复<b>我们收过的</b>，
     * 绝不把应用自己藏起来的节点翻出来。
     */
    private static final java.util.Map<View, String> HIDDEN =
            java.util.Collections.synchronizedMap(new WeakHashMap<View, String>());

    /** 区块视图 → 归属（只在结构变化时重建，避免每次回调都遍历树）。 */
    private static final java.util.Map<View, String> SECTIONS =
            java.util.Collections.synchronizedMap(new WeakHashMap<View, String>());

    /** 缓存的 Litho 根与卡片载体（结构变化时重建）。 */
    private static volatile java.lang.ref.WeakReference<ViewGroup> cachedRoot =
            new java.lang.ref.WeakReference<ViewGroup>(null);
    private static volatile java.lang.ref.WeakReference<View> cachedHeader =
            new java.lang.ref.WeakReference<View>(null);

    /** 上一次算出来的区块状态（只在变化时打日志，避免 onSizeChanged 刷屏）。 */
    private static volatile String lastSectionState = "";

    /** 最近一次建出来的 VL 卡片载体（开关变化时用它就地收口 / 恢复）。 */
    private static volatile java.lang.ref.WeakReference<View> lastHeader =
            new java.lang.ref.WeakReference<View>(null);

    /**
     * 首页卡片的两个开关分别落在 VL 卡片的两个区块上。
     *
     * <h3>为什么不再是「整块 GONE」</h3>
     * 2.1.x 的 {@code installHippyGate()} 拦 {@code loadHippyView("poi")} —— 真机 xref 证伪：
     * 全 APK 只有 {@code evo.a(PoiParam,String)}（地点详情页）与 {@code hcz.b(String)}
     * （启动期预加载）调用它，首页根本不走这条路。v1.2.6 第一版改成整块 GONE
     * {@code home_hippy_main}，效果对了但粒度不对：LO 实测「单独打开设置家，
     * 下方的大家都在看也一并出来了」。
     *
     * <h3>真机结构（dumpsys 子树实证，11.7.0）</h3>
     * {@code home_hippy_main} → {@code MapVLCardView} → {@code TDFCardView} → {@code LithoView}
     * → 根 {@code ComponentHost}，它<b>恰好有两个直接子节点</b>：
     * <pre>
     *   [0] ComponentHost  0,0-1080,189    「收藏与关注」标题 + 「设置家 / 设置公司」一行
     *   [1] ComponentHost  0,189-1080,N    「大家都在看」标题 + 频道栏 + 推荐卡片信息流
     * </pre>
     * 归属判定<b>不看下标</b>，看内容：含 {@code SectionsRecyclerView}（卡片列表）或
     * {@code LithoHorizontalScrollView}（频道栏）的是信息流区块，含 {@code VLImageView}
     * 的是收藏与关注区块。两个区块都是真实 ViewGroup，GONE 之后 Litho 不再为它测量，
     * 卡片自己会缩短（不留白）。
     */
    private static void installHomeCardGate(ClassLoader cl) {
        Class<?> card = H.cls(cl, HOME_CARD);
        if (card == null) {
            Diag.note("homecard_gate", "miss_class");
            return;
        }
        // ① 卡片载体建出来的那一刻：记下它，并按区块收口
        boolean ok = H.hookSig(card, "b", "tmap_homecard_header",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        Object result = chain.proceed();
                        if (result instanceof View) {
                            lastHeader = new java.lang.ref.WeakReference<View>((View) result);
                            applySections((View) result);
                        }
                        return result;
                    }
                }, android.content.Context.class);
        Diag.note("homecard_gate", ok ? "ewt.b(Context)" : "miss_sig");

        // ② 卡片数据到达 / 尺寸变化时再收一次：Litho 会重挂载区块，ComponentHost 会换新的
        Class<?> vl = H.cls(cl, VL_CARD);
        if (vl == null) {
            Diag.note("homecard_vl", "miss_class");
            return;
        }
        XposedInterface.Hooker reapply = new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof View) applySections((View) self);
                return result;
            }
        };
        // 低频入口：updateData（数据到达）/ init（卡片初始化）
        H.hookDeclared(vl, "updateData", "tmap_homecard_data", reapply);
        H.hookDeclared(vl, "init", "tmap_homecard_init", reapply);

        installDragLimit(cl);

        // 兜底入口：卡片尺寸变化（Litho 重挂载区块时一定会走到）。
        // ⚠ 这里<b>只允许</b>走廉价守卫 {@link #onCardLayout} —— 开关全开直接返回，
        //   区块表建好之后也只做几个字段比较，绝不在布局回调里遍历视图树 /
        //   无脑 setLayoutParams（那会变成"设高度 → 布局 → 再设高度"的死循环，
        //   LO 实测整机卡顿）。
        H.hookSig(vl, "onSizeChanged", "tmap_homecard_size",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof View) onCardLayout((View) self);
                        return result;
                    }
                }, int.class, int.class, int.class, int.class);

    }

    /**
     * 布局回调里的<b>廉价守卫</b>（每次布局都会走到，必须极轻）。
     *
     * <p>只做三件事：开关全开 → 立刻返回；区块表还没建 → 走一次完整流程；
     * 区块表已建 → 只比较几个 {@code getVisibility()}，有偏差才走完整流程。
     * 不遍历树、不碰 LayoutParams，所以不会造成卡顿。
     */
    private static void onCardLayout(View card) {
        if (card == null) return;
        if (Cfg.visible(Spec.K_HOME_FAV, true) && Cfg.visible(Spec.K_HOME_FEED)) {
            return;                                    // 常见情况：零开销
        }
        if (SECTIONS.isEmpty()) {
            // 结构还没认出来：只允许极少数几次重试（Litho 树很大，每次布局都遍历会卡死）。
            // 真正的兜底在低频入口：init / updateData / 延迟补刀 / 开关变化。
            if (resolveBudget <= 0) return;
            applySections(card, true);
            return;
        }
        if (sectionsNeedFix()) applySections(card, true);
    }

    /** 已缓存的区块里有没有"该藏却没藏 / 该显却没显"的（O(区块数)）。 */
    private static boolean sectionsNeedFix() {
        java.util.List<View> keys;
        synchronized (SECTIONS) { keys = new ArrayList<View>(SECTIONS.keySet()); }
        for (int i = 0; i < keys.size(); i++) {
            View v = keys.get(i);
            String kind = SECTIONS.get(v);
            if (v == null || kind == null) continue;
            boolean want = SEC_FAV.equals(kind)
                    ? Cfg.visible(Spec.K_HOME_FAV, true)
                    : Cfg.visible(Spec.K_HOME_FEED);
            int vis = v.getVisibility();
            if (want && vis != View.VISIBLE) return true;
            if (!want && vis != View.GONE) return true;
        }
        return false;
    }

    /**
     * 按 {@code ui_home_fav} / {@code ui_home_feed} 两个开关收口 VL 卡片里的两个区块，
     * 并把卡片载体的高度收到"可见内容"那么高。
     *
     * <h3>性能约定（v2.1.6 实测卡顿后重写，务必遵守）</h3>
     * <ul>
     *   <li><b>开关全开 = 零开销</b>：绝大多数用户什么都不关，这种情况直接 return，
     *       不遍历视图树、不碰 LayoutParams。</li>
     *   <li><b>绝不挂 onSizeChanged</b>：那是每帧都回调的，在里面做树遍历 + setLayoutParams
     *       会直接引发"设高度 → 重新布局 → 再设高度"的循环，整机卡顿。区块可见性只在
     *       低频事件上收口：卡片建出来 / {@code init} / {@code updateData}（数据到达）/
     *       开关变化 / 少量延迟补刀。</li>
     *   <li><b>高度目标只算一次</b>：区块的"自然高度"在载体还没被钉高度时量到一次并记住，
     *       之后只用它算目标值 —— 不读当前（已被约束的）尺寸，所以不会来回抖。</li>
     * </ul>
     */
    private static void applySections(View anyInside) {
        applySections(anyInside, false);
    }

    /**
     * @param fromLayout 是否来自布局回调。布局回调里<b>最多</b>允许 {@link #RESOLVE_BUDGET}
     *                   次"完整解析"—— Litho 的组件树有几千个节点，认不出结构时每次都去
     *                   遍历一遍会直接把地图拖卡（LO 实测"非常非常卡"就是这个）。
     *                   低频入口（卡片建出来 / init / updateData / 开关变化 / 延迟补刀）
     *                   会把这个预算重新充满。
     */
    private static void applySections(View anyInside, boolean fromLayout) {
        if (anyInside == null) return;
        if (fromLayout) {
            if (resolveBudget <= 0) return;
            resolveBudget--;
        } else {
            resolveBudget = RESOLVE_BUDGET;
        }
        boolean favOn = Cfg.visible(Spec.K_HOME_FAV, true);
        boolean feedOn = Cfg.visible(Spec.K_HOME_FEED);

        // ① 没什么要藏的：只做"恢复"，而且只在真的藏过东西时才做。
        if (favOn && feedOn) {
            if (!HIDDEN.isEmpty()) restoreSections();
            dragTopLimit = Integer.MIN_VALUE;      // 全部显示：不限制拖动
            return;
        }

        // ② 拿缓存；缓存失效才做一次树遍历
        View header = cachedHeader.get();
        ViewGroup root = cachedRoot.get();
        View vl = null, litho = null;
        if (header == null || root == null || root.getParent() == null
                || header.getParent() == null) {
            vl = findByClass(anyInside, VL_CARD, 0);
            if (vl == null) vl = anyInside;
            header = (vl.getParent() instanceof View) ? (View) vl.getParent() : null;
            litho = findByClass(vl, LITHO_VIEW, 0);
            root = litho == null ? null : firstHost(litho);
            cachedHeader = new java.lang.ref.WeakReference<View>(header);
            cachedRoot = new java.lang.ref.WeakReference<ViewGroup>(root);
            SECTIONS.clear();
        }
        if (root == null) {
            // Litho 结构还没建起来（卡片刚建出来时必然如此）。两个开关都关 → 整块先收掉，
            // 并同样量一次拖动上界 —— 否则这一档没有收口点，
            // 卡片又能滑到隐藏区的空白里（LO 实测："把设置家关闭后又可以滑动了"）。
            if (!favOn && !feedOn) {
                hideOnce(anyInside, "fallback_whole");
                captureDragLimit(anyInside);
            }
            probeNoRoot(anyInside, vl, header, litho, root);
            noteState("no_litho_root favOn=" + favOn + " feedOn=" + feedOn);
            return;
        }

        // ②b 上一轮可能是"整块收"（两个开关都关、Litho 还没挂载时走的退路）。
        //     现在要按区块收，必须先把整块放回去，否则区块永远收不出来、开关也放不回来。
        if (header != null && "whole".equals(HIDDEN.get(header))) {
            try { header.setVisibility(View.VISIBLE); } catch (Throwable ignored) {}
            HIDDEN.remove(header);
            dragTopLimit = Integer.MIN_VALUE;
            final View again = header;
            header.postDelayed(new Runnable() {
                @Override public void run() { applySections(again); }
            }, 150L);
            noteState("unhide_whole");
            return;
        }

        // ③ 区块表：只在结构变了（子节点换了 / 数量变了）才重建
        if (sectionsStale(root)) rebuildSections(root);

        // ④ 可见性：O(区块数)，不遍历树
        int changed = 0;
        java.util.List<View> keys;
        synchronized (SECTIONS) { keys = new ArrayList<View>(SECTIONS.keySet()); }
        for (int i = 0; i < keys.size(); i++) {
            View v = keys.get(i);
            String kind = SECTIONS.get(v);
            if (v == null || kind == null) continue;
            boolean want = SEC_FAV.equals(kind) ? favOn : feedOn;
            if (setSection(v, want, kind)) changed++;
        }

        // ⑤ 隐藏区不许滑到：区块可见性变了就重新量一次上界。
        //    ⚠ 必须重置：开关从"设置家开/信息流关"切到"两个都关"时内容会变矮，
        //    不重置的话上界还停在旧的大值，卡片又能滑到空白区（LO 实测）。
        if (changed > 0) dragTopLimit = Integer.MIN_VALUE;
        captureDragLimit(header);

        noteState("changed=" + changed + " fav=" + countKind(SEC_FAV)
                + " feed=" + countKind(SEC_FEED)
                + " favOn=" + favOn + " feedOn=" + feedOn);
    }

    private static int countKind(String kind) {
        int n = 0;
        synchronized (SECTIONS) {
            for (String v : SECTIONS.values()) if (kind.equals(v)) n++;
        }
        return n;
    }

    /** 区块表是否需要重建：根的子节点换了、或数量变了。 */
    private static boolean sectionsStale(ViewGroup root) {
        synchronized (SECTIONS) {
            if (SECTIONS.size() != root.getChildCount()) return true;
            for (View v : SECTIONS.keySet()) {
                if (v == null || v.getParent() != root) return true;
            }
        }
        return false;
    }

    /** 重建区块表（一次树遍历，只在结构变化时发生）。 */
    private static void rebuildSections(ViewGroup root) {
        java.util.Map<View, String> fresh = new java.util.HashMap<View, String>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == null) continue;
            String kind = sectionOf(child);
            if (kind != null) fresh.put(child, kind);
        }
        synchronized (SECTIONS) {
            SECTIONS.clear();
            SECTIONS.putAll(fresh);
        }
    }

    /**
     * 一次性取证：认不出 Litho 结构时，把每一步的结果打出来。
     *
     * <p>为什么需要：{@code MapVLCardView.onSizeChanged} 只在卡片第一次量尺寸时回调一次，
     * 那时 Litho 还没挂载，所以这一次必然是 {@code no_litho_root}。真正要判断的是
     * "后面几次回调为什么也没找到" —— 只靠 ok/miss 统计看不出来，必须把中间结果打出来。
     */
    private static void probeNoRoot(View anyInside, View vl, View header, View litho, ViewGroup root) {
        if (probeBudget <= 0) return;
        probeBudget--;
        int vlKids = -1, lithoKids = -1, rootKids = -1, hdrKids = -1;
        try { if (vl instanceof ViewGroup) vlKids = ((ViewGroup) vl).getChildCount(); } catch (Throwable ignored) {}
        try { if (litho instanceof ViewGroup) lithoKids = ((ViewGroup) litho).getChildCount(); } catch (Throwable ignored) {}
        try { if (root != null) rootKids = root.getChildCount(); } catch (Throwable ignored) {}
        try { if (header instanceof ViewGroup) hdrKids = ((ViewGroup) header).getChildCount(); } catch (Throwable ignored) {}
        H.log(Log.INFO, MainHook.TAG, "homecard probe in="
                + anyInside.getClass().getSimpleName()
                + " vl=" + (vl == null ? "null" : vl.getClass().getSimpleName()) + "/" + vlKids
                + " hdr=" + (header == null ? "null" : header.getClass().getSimpleName()) + "/" + hdrKids
                + " litho=" + (litho == null ? "null" : litho.getClass().getName()) + "/" + lithoKids
                + " root=" + (root == null ? "null" : root.getClass().getSimpleName()) + "/" + rootKids);
    }

    /** 状态只在变化时打一条 logcat。 */
    private static void noteState(String state) {
        Diag.note("homecard_sections", state);
        if (!state.equals(lastSectionState)) {
            lastSectionState = state;
            H.log(Log.INFO, MainHook.TAG, "homecard sections " + state);
        }
    }

    /**
     * 布局回调里允许的"完整解析"次数（每次低频入口重新充满）。
     *
     * <p>Litho 的组件树有几千个节点，认不出结构时如果每次布局都去遍历一遍，
     * 会把地图直接拖卡（LO 实测"非常非常卡"）。低频入口（卡片建出来 / init /
     * updateData / 开关变化 / 延迟补刀）会把这个预算重新充满。
     */
    private static final int RESOLVE_BUDGET = 3;
    private static volatile int resolveBudget = RESOLVE_BUDGET;

    /** 取证预算：认不出 Litho 结构时最多打几条 probe 日志。 */
    private static volatile int probeBudget = 10;

    /** 可上拉的底部卡片（首页卡片就是它）。 */
    private static final String SLIDE_CARD =
            "com.tencent.map.hippy.extend.view.slideview.SlideCardView";

    /** 拖动上界：卡片"只展示已显示内容"时静止的顶边（y）。{@code MIN_VALUE} = 还没测到。 */
    private static volatile int dragTopLimit = Integer.MIN_VALUE;
    private static volatile int dragTopLogged = Integer.MIN_VALUE;
    private static volatile int collapsedDragTop = Integer.MIN_VALUE;

    /**
     * 拖动/回弹窗口的截止时刻（{@code uptimeMillis}）。
     *
     * <p>这是高德配方里 {@code if ("DRAGGING".equals(state)) return;} 的等价物：
     * 腾讯的 {@code SlideCardView} 没有公开的 state 串，但它的拖动与回弹必然经过
     * {@code getValidTopHeight(int)}（每帧钳位）与 {@code DragCallBack.getTop(View,float)}
     * （松手定档）—— 在这两处盖章，就等于拿到了"正在过渡"的真信号。
     * 盖章窗口内一律不做几何干预，避免把中间态冻死。
     */
    private static volatile long draggingUntil = 0L;

    /** 首页工具宫格：量"剩余内容底部"用。 */
    private static final String GRID = "com.tencent.map.ama.newhome.widget.HomeFeatureGroupView";

    /** 稳定判定最多重试的帧数（连续两帧量到同一组几何值才算稳定）。60 帧 ≈ 1s，覆盖腾讯的收放动画。 */
    private static final int SETTLE_TRIES = 60;

    /**
     * 「隐藏的部分不要让人滑到」——把卡片可上拉的上界压到"已显示内容"的高度。
     *
     * <h3>为什么挂 {@code getValidTopHeight(int)}</h3>
     * <pre>
     *   SlideCardView$DragCallBack.clampViewPositionVertical(View,int,int)I
     *     [0] this$0
     *     [2] invoke-virtual SlideCardView->getValidTopHeight(I)I   ← 唯一的钳位函数
     *     [5] return
     * </pre>
     * 它用 {@code getHeight()} 与 adapter 的 levels 算出"合法顶边"。levels 是应用按
     * <b>原始内容</b>算的，区块被我们收掉之后内容变短、levels 却没变，于是上拉会拉出
     * 一整屏空白。这里在它返回之后再夹一道上界：顶边不许高于"内容正好铺满"时的顶边。
     *
     * <h3>为什么不去改 levels（踩过的坑，别再试）</h3>
     * 试过裁 {@code SlideCardView$Adapter.access$100} 返回的档位表：
     * <ul>
     *   <li>换一张短表 → 应用按原 size 算下标越界 → <b>整机卡死</b>；</li>
     *   <li>就地压小 → 不可逆，上界改回去时压小的档位回不来；</li>
     *   <li>真机结果：<b>地图直接消失 + 非常卡顿</b>（LO 实测）。</li>
     * </ul>
     * 结论：levels 是应用自己的核心状态，<b>只读不改</b>。
     */
    private static void installDragLimit(ClassLoader cl) {
        Class<?> sc = H.cls(cl, SLIDE_CARD);
        if (sc == null) {
            Diag.note("drag_limit", "miss_class");
            return;
        }
        boolean ok = H.hookSig(sc, "getValidTopHeight", "tmap_card_top",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        // 这个函数每拖动帧都会被调用 = 应用自己在告诉我们"正在过渡"。
                        draggingUntil = SystemClock.uptimeMillis() + 250L;
                        Object result = chain.proceed();
                        if (!(result instanceof Integer)) return result;
                        if (Cfg.visible(Spec.K_HOME_FAV, true)
                                && Cfg.visible(Spec.K_HOME_FEED)) {
                            return result;                       // 两个都显示：原样
                        }
                        int limit = dragTopLimit;
                        if (limit == Integer.MIN_VALUE) return result;
                        int orig = ((Integer) result).intValue();
                        int clamped = Math.max(orig, limit);
                        if (clamped != orig) Diag.hit("drag_limit");
                        return Integer.valueOf(clamped);
                    }
                }, int.class);
        Diag.note("drag_limit", ok ? "getValidTopHeight" : "miss_sig");
        // onViewReleased calls getTop(View,float) to choose the settling target.
        // Clamp that target too, or release can snap to a hidden adapter level.
        Class<?> callback = H.cls(cl, SLIDE_CARD + "$DragCallBack");
        H.hookSig(callback, "getTop", "tmap_card_release_top",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        // 松手 → 应用马上要跑回弹动画。整个回弹窗口内不干预几何。
                        draggingUntil = SystemClock.uptimeMillis() + 450L;
                        Object result = chain.proceed();
                        if (!(result instanceof Integer) ||
                                (Cfg.visible(Spec.K_HOME_FAV, true) &&
                                 Cfg.visible(Spec.K_HOME_FEED))) return result;
                        int limit = dragTopLimit;
                        if (limit == Integer.MIN_VALUE) return result;
                        Object moving = chain.getArg(0);
                        Object velocity = chain.getArg(1);
                        int collapsed = collapsedDragTop;
                        if (moving instanceof View && velocity instanceof Float &&
                                collapsed > limit &&
                                (((Float) velocity).floatValue() < -50f ||
                                 (((Float) velocity).floatValue() <= 50f &&
                                  ((View) moving).getTop() < (collapsed + limit) / 2))) {
                            return Integer.valueOf(limit);
                        }
                        return Integer.valueOf(Math.max(((Integer) result).intValue(), limit));
                    }
                }, View.class, float.class);
    }

    /** 向上找到承载首页卡片的那张 SlideCardView（真机实例是子类 HomePageCardView）。 */
    private static View slideCardOf(View header) {
        View cur = header;
        for (int i = 0; i < 16 && cur != null; i++) {
            if (classIs(cur, SLIDE_CARD)) return cur;
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    /**
     * 量出"剩余内容正好铺满"时卡片静止的顶边，作为拖动上界。
     *
     * <h3>为什么要重写（旧版就是"锁到中间态"的元凶）</h3>
     * 旧版用 {@code postDelayed(400/900ms)} 盲等两个时间点去采样 {@code cv.getTop()} 与
     * 宫格底边。可是开关变化之后腾讯自己会跑一段卡片收放 / 宫格重排的过渡动画 ——
     * 400ms 那个采样点经常正好落在过渡中，于是：
     * <ul>
     *   <li>{@code cv.getTop()} 取到的是<b>动画中间位置</b>，不是收起位；</li>
     *   <li>宫格此时正处在"1 排 → 3 排"的重排中间，宫格底边也是中间值；</li>
     *   <li>旧版 {@code if (limit > dragTopLimit)} 只增不减 —— 一旦采到过渡态的大值
     *       就<b>永久锁死</b>，而且再也不会被后续正确采样纠正。</li>
     * </ul>
     * 结果：卡片被夹在过渡中间态，内容区被压住而宫格没有重排 → <b>空白 + 重影</b>。
     *
     * <h3>现在的做法（对齐高德已跑通的配方）</h3>
     * <ol>
     *   <li><b>等布局稳定再量</b>：逐帧采样，连续两帧量到同一组几何值才算稳定，
     *       稳定之前绝不写 {@code dragTopLimit}（= 高德的"先量内容高度，再设锚点"）。</li>
     *   <li><b>过渡中不打断</b>：{@link #draggingUntil} 盖章窗口内直接跳过
     *       （= 高德的 {@code if ("DRAGGING".equals(state)) return;}）。</li>
     *   <li><b>稳定后覆盖而不是棘轮</b>：内容变矮时上界要跟着收紧，
     *       否则开关切到"两个都关"之后又能滑到空白里。</li>
     *   <li><b>落锁后强制宫格重新 measure+layout</b>，让它按最终行数重排一次
     *       —— 这一步专门消掉"锁到中间态"留下的重影。</li>
     * </ol>
     */
    private static void captureDragLimit(final View header) {
        if (header == null) return;
        settleProbe(header, 0, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /**
     * 逐帧采样直到布局稳定，然后才落锁。
     *
     * @param prevTop    上一帧量到的 {@code cv.getTop()}（{@code MIN_VALUE} = 还没有上一帧）
     * @param prevBottom 上一帧量到的宫格底边
     */
    private static void settleProbe(final View header, final int attempt,
                                    final int prevTop, final int prevBottom) {
        if (attempt > SETTLE_TRIES || header == null) return;
        header.postOnAnimation(new Runnable() {
            @Override public void run() {
                if (!header.isAttachedToWindow()) return;
                View card = slideCardOf(header);
                if (card == null) return;
                Object c = H.call(card, "getContentContainer");
                if (!(c instanceof View)) return;
                View cv = (View) c;
                // ① 正在拖动 / 回弹：不采样、不落锁，等它自己停下来（高德的 DRAGGING 判断）。
                if (SystemClock.uptimeMillis() < draggingUntil) {
                    settleProbe(header, attempt + 1, Integer.MIN_VALUE, Integer.MIN_VALUE);
                    return;
                }
                int top = cv.getTop();
                int bottom = gridBottomIn(cv);
                if (top <= 0 || bottom <= 0) {           // 还没量出来：下一帧再看
                    settleProbe(header, attempt + 1, Integer.MIN_VALUE, Integer.MIN_VALUE);
                    return;
                }
                // ② 与上一帧一致 = 布局稳定了，此刻的几何值才可信。
                if (prevTop != Integer.MIN_VALUE && top == prevTop && bottom == prevBottom) {
                    applyDragLimit(cv, top, bottom);
                    return;
                }
                settleProbe(header, attempt + 1, top, bottom);
            }
        });
    }

    /** 宫格底边在 {@code cv} 坐标系里的 y（真机：宫格是 cv 的深层后代）。 */
    private static int gridBottomIn(View cv) {
        View tools = findByClass(cv, GRID, 0);
        if (tools == null || tools.getHeight() <= 0) return 0;
        int bottom = tools.getBottom();
        View node = tools;
        int guard = 0;
        while (node.getParent() instanceof View && node.getParent() != cv && guard++ < 16) {
            node = (View) node.getParent();
            bottom += node.getTop();
        }
        return bottom;
    }

    /** 布局稳定之后落锁，并强制宫格按最终高度重排一次。 */
    private static void applyDragLimit(View cv, int stableTop, int contentBottom) {
        float density = cv.getResources().getDisplayMetrics().density;
        int padding = Math.round(12 * density);
        // 内容正好铺满时的顶边：再往上就会露出空白。
        // 这一项只由「内容自身几何」决定，与"采样时卡片停在哪一档"无关 ——
        // 旧版把当帧的 cv.getTop() 混进来取 min，才会锁到过渡中间态。
        int contentLimit = cv.getHeight() - contentBottom - padding;
        // 收起位 = 见过的最大 top（y 越大越收起）。用 running max 而不是当帧值，
        // 免得恰好在展开档位采样时把上界算歪；它只让 travel 项更宽松，
        // 最终由 contentLimit 说话。
        if (stableTop > collapsedDragTop) collapsedDragTop = stableTop;
        int collapsed = collapsedDragTop;
        // 收起位之上至少留 96dp 行程，避免"锁死"—— 要能上滑，但止步于剩余内容底部。
        int travel = Math.round(96 * density);
        int capped = Math.min(collapsed - travel, contentLimit);
        if (capped <= 0 || capped > collapsed) return;
        boolean moved = capped != dragTopLimit;
        dragTopLimit = capped;                          // 稳定量到就覆盖，不再只增不减地棘轮
        if (moved || dragTopLogged != capped) {
            dragTopLogged = capped;
            H.log(Log.INFO, MainHook.TAG, "homecard dragLimit=" + capped
                    + " contentLimit=" + contentLimit + " toolsBottom=" + contentBottom
                    + " collapsedTop=" + collapsed + " sampledTop=" + stableTop
                    + " cvH=" + cv.getHeight() + " density=" + density);
        }
        if (!moved) return;
        // 关键一步：锁完之后让宫格重新 measure + layout。
        // 否则宫格还停在"锁到中间态"那一版的行坐标上，几排格子互相重叠 → 重影。
        try {
            View tools = findByClass(cv, GRID, 0);
            if (tools != null) tools.requestLayout();
            cv.requestLayout();
        } catch (Throwable ignored) {}
    }

    /** 判定一个根 ComponentHost 的直接子节点属于哪个区块（认不出返回 null = 不碰）。 */
    private static String sectionOf(View v) {
        if (hasAny(v, FEED_MARKS)) return SEC_FEED;
        if (hasAny(v, NOT_FAV_MARKS)) return null;   // 带列表/横向滚动 → 不是「收藏与关注」
        if (findByClass(v, VL_IMAGE, 0) != null) return SEC_FAV;
        return null;
    }

    private static boolean hasAny(View v, String[] classes) {
        for (int i = 0; i < classes.length; i++) {
            if (findByClass(v, classes[i], 0) != null) return true;
        }
        return false;
    }

    /** 收 / 放一个区块；只记录和恢复<b>我们改过</b>的可见性。 */
    private static boolean setSection(View v, boolean visible, String kind) {
        if (v == null) return false;
        try {
            if (visible) {
                if (kind.equals(HIDDEN.get(v))) {
                    v.setVisibility(View.VISIBLE);
                    HIDDEN.remove(v);
                    H.log(Log.INFO, MainHook.TAG, "homecard SHOW section=" + kind);
                    return true;
                }
                return false;
            }
            if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                HIDDEN.put(v, kind);
                H.log(Log.INFO, MainHook.TAG, "homecard HIDE section=" + kind
                        + " cls=" + v.getClass().getSimpleName());
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 开关重开：把本模块收过的区块放回去。 */
    private static void restoreSections() {
        java.util.List<View> keys;
        synchronized (HIDDEN) { keys = new ArrayList<View>(HIDDEN.keySet()); }
        for (int i = 0; i < keys.size(); i++) {
            View v = keys.get(i);
            if (v == null) { HIDDEN.remove(null); continue; }
            try {
                if (v.getVisibility() == View.GONE) v.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {}
            HIDDEN.remove(v);
        }
    }

    /** 收一次可见性（认不出区块结构时的兜底）。 */
    private static void hideOnce(View v, String why) {
        if (v == null) return;
        try {
            if (v.getVisibility() == View.GONE) return;
            v.setVisibility(View.GONE);
            HIDDEN.put(v, "whole");
            H.log(Log.INFO, MainHook.TAG, "homecard HIT why=" + why
                    + " id=" + idOf(v) + " cls=" + v.getClass().getSimpleName());
        } catch (Throwable ignored) {}
    }

    /** 从 {@code ewt} 实例取卡片载体（字段 {@code ak}，退路是它持有的根视图）。 */
    private static View headerOf(Object self) {
        if (self instanceof View) return (View) self;
        Object v = H.field(self, CARD_HEADER_FIELD);
        if (v instanceof View) return (View) v;
        return viewOf(self);
    }

    /** 这个 View 是不是某个类（含父类，按继承链判断）。 */
    private static boolean classIs(View v, String name) {
        if (v == null) return false;
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            if (name.equals(k.getName())) return true;
        }
        return false;
    }

    /** 深度优先按类名找节点（含自身）。 */
    private static View findByClass(View v, String cls, int depth) {
        if (v == null || depth > 14) return null;
        if (cls.equals(v.getClass().getName())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByClass(g.getChildAt(i), cls, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** LithoView 的第一个 ComponentHost 子节点 = 组件树的根。 */
    private static ViewGroup firstHost(View litho) {
        if (!(litho instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) litho;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup && COMPONENT_HOST.equals(c.getClass().getName())) {
                return (ViewGroup) c;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // 设置家 / 设置公司 —— 收口
    // ══════════════════════════════════════════════════════════════════

    /**
     * 字段法：GONE {@code aH}（家行）与 {@code aB}（公司行）。
     *
     * <p>注意这只是第一层 —— 真机 PATH 证明外层还有行容器（展开态 {@code #expanding_default}），
     * 所以必须配合 {@link #scheduleFavHide} 的 LCA 法才真正生效。
     */
    private static void applyHomeFav(Object self) {
        if (self == null || Cfg.visible(Spec.K_HOME_FAV, true)) return;
        // ① 主收口：VL 卡片里的「收藏与关注」区块（标题 + 设置家/设置公司一行）
        applySections(headerOf(self));
        int hid = 0;
        for (int i = 0; i < FAV_ROW_FIELDS.length; i++) {
            Object row = H.field(self, FAV_ROW_FIELDS[i]);
            if (!(row instanceof View)) continue;
            try {
                View v = (View) row;
                if (v.getVisibility() != View.GONE) {
                    v.setVisibility(View.GONE);
                    synchronized (HIDDEN) { HIDDEN.put(v, "legacy"); }
                }
                hid++;
            } catch (Throwable ignored) {}
        }
        H.log(Log.INFO, MainHook.TAG, "homefav fields=" + hid
                + " switch=" + (Cfg.visible(Spec.K_HOME_FAV, true) ? "on" : "off"));
    }

    /** dex 实证：aH=家行 LinearLayout，aB=公司行 LinearLayout（ewt 字段表） */
    private static final String[] FAV_ROW_FIELDS = {"aH", "aB"};

    /**
     * 延迟收口：等腾讯把这一块摆回来之后再收一次。
     *
     * <p>为什么要多轮：这一块的数据是异步到的（{@code cardList:newMessage} 事件到达后
     * 才渲染「收藏与关注 / 大家都在看 / 推荐卡片」），而 RecyclerView 的 header 也可能
     * 在重绑时被重新摆出来。多轮收口覆盖「数据到达」「重绑」「下拉展开/收起」三种时机。
     *
     * <p>每轮都先按字段/ID 收 VL 卡片，再跑旧版原生行的 LCA 兜底。
     */
    private static void scheduleFavHide(final Object self) {
        if (self == null) return;
        final View root = viewOf(self);
        if (root == null) return;
        for (final long delay : new long[]{80L, 600L, 2000L}) {
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    applySections(headerOf(self));
                    if (Cfg.visible(Spec.K_HOME_FAV, true)
                            && Cfg.visible(Spec.K_HOME_FEED)) return;
                    int[] hits = new int[1];
                    hideByLca(root, hits);
                    if (hits[0] > 0) {
                        H.log(Log.INFO, MainHook.TAG,
                                "homefav_lca@" + delay + " hid=" + hits[0]);
                    }
                }
            }, delay);
        }
    }

    /**
     * LCA 法（2026-10-14 按真机 FAVCOUNT 证据修正）。
     *
     * <p>真机链路（logcat FAVCOUNT 实证）：
     * <pre>
     *   tv_home_back_card_expanding(0)              ← 整块卡片容器（VISIBLE）
     *     └ expanding_default(8)                   ← 已 GONE，但不是那一行
     *         ├ tv_home_card_add_home_expanding(8) → TextView[设置家]
     *         └ tv_home_card_add_company_expanding(8) → TextView[设置公司/学校]
     * </pre>
     * 上一版把 {@code expanding_default} 藏了，行还在 —— 因为真正占位的是
     * {@code tv_home_back_card_expanding} 自己。但它里面还含标题「收藏与关注」，
     * 整块藏会把标题一起弄没（真机误伤过）。
     *
     * <p>正解：爬到<b>含两个锚点的最深公共祖先</b>就停 —— 即 {@code expanding_default}
     * 这层本身没错，但它已被证明不是占位节点。所以规则改为：
     * 找到 {@code tv_home_back_card_*} 容器后，把它<b>除标题兄弟外的那个子节点</b>藏掉；
     * 若只有一个子节点且含全部锚点，就藏这个子节点。
     */
    private static void hideByLca(View root, int[] hits) {
        if (!(root instanceof ViewGroup)) return;
        List<View> anchors = new ArrayList<View>(4);
        collectFavText((ViewGroup) root, 0, anchors);
        if (anchors.isEmpty()) return;
        View first = anchors.get(0);
        // 从锚点往上爬到卡片根（tv_home_back_card_*）
        View cardRoot = null;
        View cur = first;
        for (int i = 0; i < 14 && cur != null; i++) {
            String id = idOf(cur);
            if (id != null && id.contains(CARD_ROOT_MARK)) { cardRoot = cur; break; }
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        if (cardRoot == null || !(cardRoot instanceof ViewGroup)) return;
        // 在卡片根下找"含锚点且不是标题"的子节点
        ViewGroup g = (ViewGroup) cardRoot;
        View target = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c == first) continue;
            List<View> sub = new ArrayList<View>(4);
            if (c instanceof TextView) {
                if (isFavText(((TextView) c).getText())) sub.add(c);
            } else if (c instanceof ViewGroup) {
                collectFavText((ViewGroup) c, 0, sub);
            }
            if (sub.isEmpty()) continue;
            // 该子节点含锚点 → 它就是要藏的"那一行"（标题兄弟不含锚点，自动跳过）
            target = c;
            break;
        }
        if (target == null || target == cardRoot || target == root) return;
        try {
            if (target.getVisibility() != View.GONE) {
                target.setVisibility(View.GONE);
                synchronized (HIDDEN) { HIDDEN.put(target, "legacy"); }
                hits[0]++;
                H.log(Log.INFO, MainHook.TAG, "homefav_row HIT id=" + idOf(target)
                        + " cls=" + target.getClass().getSimpleName()
                        + " childCount=" + (target instanceof ViewGroup
                                ? ((ViewGroup) target).getChildCount() : -1));
            }
        } catch (Throwable ignored) {}

        // ── 兜底：Kuikly 自绘节点 ──────────────────────────────────────
        // 真机 CARDROOT 取证（logcat）显示卡片根 tv_home_back_card_expanding 的三个子节点：
        //   [0] View(0) id=null                     ← Kuikly 渲染容器，屏幕上那一行就是它画的
        //   [1] LinearLayout(8) id=tv_home_card_ziyuanTitle   ← 已 GONE（废弃）
        //   [2] LinearLayout(8) id=expanding_default          ← 已 GONE（废弃）
        // 只 GONE 那两个有 id 的废弃节点对屏幕毫无影响 —— 这就是"开关看起来无效"的最终原因。
        // 所以必须把 children[0] 这个无 id 的 Kuikly View 也收掉。
        if (hits[0] == 0) {
            ViewGroup cg2 = (ViewGroup) cardRoot;
            for (int i = 0; i < cg2.getChildCount(); i++) {
                View c = cg2.getChildAt(i);
                if (c == null) continue;
                String cid = idOf(c);
                if (cid == null && c.getVisibility() != View.GONE) {
                    // 无 id 的 Kuikly 渲染容器
                    try {
                        c.setVisibility(View.GONE);
                        synchronized (HIDDEN) { HIDDEN.put(c, "legacy"); }
                        hits[0]++;
                        H.log(Log.INFO, MainHook.TAG, "homefav_kuikly HIT cls="
                                + c.getClass().getName() + " childCount="
                                + (c instanceof ViewGroup ? ((ViewGroup) c).getChildCount() : -1));
                    } catch (Throwable ignored) {}
                    break;
                }
            }
        }
    }

    /** 收集文案命中的 TextView（深度有界，避免全树扫描）。 */
    private static void collectFavText(ViewGroup g, int depth, List<View> out) {
        if (depth > 22 || out.size() > 6) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null) continue;
            if (c instanceof TextView && isFavText(((TextView) c).getText())) out.add(c);
            else if (c instanceof ViewGroup) collectFavText((ViewGroup) c, depth + 1, out);
        }
    }

    /** 子树里是否含"另一个"锚点（LCA 判定用）。 */
    private static boolean containsOtherFav(View v, View first) {
        if (!(v instanceof ViewGroup)) return false;
        List<View> all = new ArrayList<View>(6);
        collectFavText((ViewGroup) v, 0, all);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) != first) return true;
        }
        return false;
    }

    /** 卡片根下含锚点的直接子节点（退路用）。 */
    private static View childContainingFav(ViewGroup g, View first) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c == first) continue;
            List<View> all = new ArrayList<View>(4);
            if (c instanceof TextView) {
                if (isFavText(((TextView) c).getText())) return c;
            } else if (c instanceof ViewGroup) {
                collectFavText((ViewGroup) c, 0, all);
                if (!all.isEmpty()) return c;
            }
        }
        return null;
    }

    private static boolean isFavText(CharSequence t) {
        if (t == null) return false;
        String s = t.toString().trim();
        if (s.length() == 0 || s.length() > 12) return false;
        for (int i = 0; i < FAV_TEXTS.length; i++) {
            if (s.contains(FAV_TEXTS[i])) return true;
        }
        return false;
    }

    private static String idOf(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID || id == 0) return null;
            String nm = v.getResources().getResourceName(id);
            if (nm == null) return null;
            int s = nm.indexOf('/');
            return s > 0 ? nm.substring(s + 1) : nm;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取证：统计树上"设置家"锚点数量，并打出每条的 id 链。 */
    private static void countFav(View v, int depth, StringBuilder sb, int[] n) {
        if (v == null || depth > 22 || sb.length() > 1400) return;
        if (v instanceof TextView && isFavText(((TextView) v).getText())) {
            n[0]++;
            if (sb.length() > 0) sb.append(" || ");
            sb.append(idChain(v));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) countFav(g.getChildAt(i), depth + 1, sb, n);
        }
    }

    /** 找 tv_home_back_card_* 卡片根容器。 */
    private static View findCardRoot(View v, int depth) {
        if (v == null || depth > 22) return null;
        String id = idOf(v);
        if (id != null && id.contains(CARD_ROOT_MARK)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findCardRoot(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 从该节点往上打到根的 id 链。 */
    private static String idChain(View v) {
        StringBuilder sb = new StringBuilder(200);
        View cur = v;
        for (int i = 0; i < 12 && cur != null; i++) {
            String id = idOf(cur);
            if (sb.length() > 0) sb.append('<');
            sb.append(id == null ? cur.getClass().getSimpleName() : id)
              .append('(').append(cur.getVisibility()).append(')');
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return sb.toString();
    }

    /** 从 ewt 实例取它当前持有的根视图（三个候选字段，实证自 createView 的 iput）。 */
    private static View viewOf(Object ewt) {
        for (int i = 0; i < ROOT_FIELDS.length; i++) {
            Object v = H.field(ewt, ROOT_FIELDS[i]);
            if (v instanceof View) return (View) v;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // 首页下方推荐信息流卡片（K_HOME_FEED）
    // ══════════════════════════════════════════════════════════════════

    private static void installFeedFilter(ClassLoader cl) {
        filterTransform(cl);
        filterPresenter(cl);
    }

    /** ① 数据层：gcd.a(ArrayList)List —— 服务端 Card → iri 的唯一转换点。 */
    private static void filterTransform(ClassLoader cl) {
        Class<?> g = H.cls(cl, CARD_TRANSFORM);
        if (g == null) {
            Diag.note("homefeed_hook", "miss_gcd");
            return;
        }
        H.hookSig(g, "a", "tmap_homefeed", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (Cfg.visible(Spec.K_HOME_FEED)) return result;
                if (!(result instanceof List)) return result;
                int dropped = dropFeedCards((List<?>) result);
                if (dropped > 0) {
                    H.log(Log.INFO, MainHook.TAG, "homefeed gcd dropped=" + dropped);
                }
                return result;
            }
        }, java.util.ArrayList.class);
        Diag.note("homefeed_hook", "gcd.a(ArrayList)");
    }

    /** ② UI 层：HomeCardPresenter.e(List) → adapter.addOrUpdateHeaders（双保险）。 */
    private static void filterPresenter(ClassLoader cl) {
        Class<?> p = H.cls(cl, CARD_PRESENTER);
        H.hookSig(p, "e", "tmap_homefeed_ui", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object arg = chain.getArg(0);
                if (!Cfg.visible(Spec.K_HOME_FEED) && arg instanceof List) {
                    int dropped = dropFeedCards((List<?>) arg);
                    if (dropped > 0) {
                        H.log(Log.INFO, MainHook.TAG, "homefeed_ui dropped=" + dropped);
                    }
                }
                return chain.proceed();
            }
        }, java.util.List.class);
    }

    private static int dropFeedCards(List<?> cards) {
        int dropped = 0;
        java.util.Iterator<?> it = cards.iterator();
        while (it.hasNext()) {
            Object c = it.next();
            if (isFeedCard(c)) {
                try { it.remove(); dropped++; } catch (Throwable ignored) {}
            }
        }
        return dropped;
    }

    /**
     * 是否「下方推荐信息流」卡片。
     *
     * <p>判据 {@code iri.j}（= {@code Card.cardName}，服务端下发）。
     * 认不出就<b>保留</b> —— 宁可这个开关没效果，也不能把通勤卡/实时公交误删。
     */
    private static boolean isFeedCard(Object iri) {
        if (iri == null) return false;
        Object name = H.field(iri, "j");
        if (!(name instanceof String)) return false;
        String n = ((String) name).toLowerCase();
        if (n.length() == 0) return false;
        for (int i = 0; i < FEED_CARD_NAMES.length; i++) {
            if (n.contains(FEED_CARD_NAMES[i])) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════
    // 开关变化后即时恢复（不必重开地图）
    // ══════════════════════════════════════════════════════════════════

    static void refresh() {
        boolean favOn = Cfg.visible(Spec.K_HOME_FAV, true);
        boolean feedOn = Cfg.visible(Spec.K_HOME_FEED);
        // 两个都开 → 先恢复，再按区块收口；只要有一个关着就走 applySections 的收口分支。
        if (favOn && feedOn) restoreSections();
        View header = lastHeader.get();
        if (header != null) applySections(header);
        Diag.note("homefav_switch", favOn ? "on" : "off");
        Diag.note("homefeed_switch", feedOn ? "on" : "off");
    }

    // ══════════════════════════════════════════════════════════════════
    // 只读诊断（排障用）
    // ══════════════════════════════════════════════════════════════════

    private static Object[] argsOf(XposedInterface.Chain chain) {
        try {
            java.util.List<Object> list = chain.getArgs();
            if (list == null) return new Object[0];
            return list.toArray(new Object[0]);
        } catch (Throwable ignored) {}
        return new Object[0];
    }

    private static String labels(ViewGroup root, int depth) {
        if (root == null || depth > 10) return "";
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < root.getChildCount() && sb.length() < 200; i++) {
                View child = root.getChildAt(i);
                if (child == null) continue;
                if (child instanceof TextView) {
                    CharSequence t = ((TextView) child).getText();
                    if (t != null && t.length() > 0) {
                        if (sb.length() > 0) sb.append('|');
                        sb.append(t);
                    }
                } else if (child instanceof ViewGroup) {
                    String sub = labels((ViewGroup) child, depth + 1);
                    if (sub.length() > 0) {
                        if (sb.length() > 0) sb.append('|');
                        sb.append(sub);
                    }
                }
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "tmap_homefav: labels failed " + t);
        }
        return sb.toString();
    }
}
