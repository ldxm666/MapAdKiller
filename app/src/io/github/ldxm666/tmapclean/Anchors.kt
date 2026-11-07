package io.github.ldxm666.tmapclean

/**
 * 腾讯地图 11.7.0 的**锚点清单** —— 所有 hook 点的唯一索引。
 *
 * <p>存在的理由与 {@code bmapclean.Anchors} 一样：跨版本维护时，先拿这份表去新 DEX 里
 * 定向检索，而不是重新反编译整个包。每一项都标注了它是<b>稳定锚点</b>（腾讯自己的命名）
 * 还是<b>混淆锚点</b>（跨版本会变，必须靠指纹重新定位）。
 *
 * <h3>证据来源</h3>
 * 真机安装版 {@code com.tencent.map} 11.7.0（versionCode 6011，base.apk 174,709,476 B，
 * SHA-256 {@code ca7c9feb85eb894dbf5cf41b2abfdce98531ee3111503c1d282fe64d124d18bc}），
 * 23 个 DEX，由 {@code tools/dex_query.py} 逐类解析。
 *
 * <h3>混淆锚点的定位指纹</h3>
 * <pre>
 *   首页工具回调   11.6.0 = navisdk.a.gak$12     11.7.0 = navisdk.a.gas$12     ← 名字会变，不挂
 *                  指纹：super = com.tencent.map.net.ResultCallback
 *                        方法签名 (Ljava/lang/Object;Lcom/tencent/map/jce/MapTools/GetHomepageGroupToolsResponse;)V
 *                  本模块改挂 JCE 层（见下），因此不需要它。
 *   底栏构造器     11.6.0 = navisdk.a.exh         11.7.0 已被复用为同名接口  ← 名字会变，不挂
 *                  指纹：调用 LiquidTabBarGroup#setTabViews(java.util.List)
 *                  本模块改挂 setTabViews（稳定），因此不需要它。
 *   工具适配器     11.7.0 = navisdk.a.gaa         指纹：extends RecyclerView$Adapter 且
 *                  onCreateViewHolder 里 new ToolsItemView(Context)   ← 名字会变，不挂
 *   设置家 hippy  11.6.0 = navisdk.a.ewp         11.7.0 = navisdk.a.ewt
 *                  指纹：J()Ljava/lang/String; 内含字面量 "设置家"
 *                  本模块改挂 HomeFavComponentImpl（稳定），因此不需要它。
 * </pre>
 *
 * <p><b>结论：本模块的界面 hook 全部落在稳定锚点上</b>，没有一个依赖混淆名。
 * 混淆锚点只在上面作为"如果将来稳定锚点也消失，该去哪找"的备份记录。
 *
 * <p><b>6013 追加（开屏广告 / 升级弹窗）</b>：这两条链的锚点同样只用腾讯自己的命名
 * （{@code ama.splash.*} / {@code ama.upgrade.*} / {@code qq.e.*} / {@code init.tasks.*}），
 * 唯一被点名过的混淆类是升级管理器 {@code navisdk.a.hgi}，它<b>刻意不挂</b> ——
 * 原因写在 [TmapUpdate] 的类注释里。6013 上这两组锚点逐类核对的结果见下方 ⑦ ⑧。
 */
object Anchors {

    // ── ① 底部入口栏 ────────────────────────────────────────────────────
    /** 稳定：classes18.dex（11.7.0） / classes17.dex（11.6.0） */
    const val TAB_BAR_LIQUID = "com.tencent.map.ama.mainpage.frame.tab.LiquidTabBarGroup"
    /** 稳定：旧版实现，11.6/11.7 均存在（11.6 已无调用点，保留挂钩以防回退） */
    const val TAB_BAR_LEGACY = "com.tencent.map.ama.mainpage.frame.tab.TabPageGroup"
    const val M_SET_TAB_VIEWS = "setTabViews"
    const val M_ON_CLICK = "onClick"
    /** 稳定：标签视图的页名读取口（TabItemView / LiquidTabItemView 同名方法） */
    const val M_GET_PAGE = "getPage"

    // ── ② 首页工具宫格 ──────────────────────────────────────────────────
    /** 稳定：JCE 结构体，未混淆。classes20.dex（11.7.0） */
    const val TOOL_RESP = "com.tencent.map.jce.MapTools.GetHomepageGroupToolsResponse"
    const val TOOL_GROUP = "com.tencent.map.jce.MapTools.Group"
    const val TOOL_ITEM = "com.tencent.map.jce.MapTools.Tool"
    const val M_READ_FROM = "readFrom"
    const val F_GROUPS = "groups"
    const val F_PREFER = "prefer"
    const val F_SELECT = "select"
    const val F_OPERATION = "operation"
    /** Group 的工具列表字段（实证自 Group.<init> 的 iput） */
    const val F_TOOLS = "tools"
    const val F_TOOLS_CACHE = "cache_tools"
    /** Tool 的标识与文案字段 */
    const val F_NAME = "name"
    const val F_TITLE = "title"

    // ── ③ 设置家 / 设置公司 ─────────────────────────────────────────────
    /** 稳定：classes19.dex（11.7.0） */
    const val HOME_FAV_COMP = "com.tencent.map.ama.newhome.component.HomeFavComponentImpl"
    const val HOME_FAV_VIEW = "com.tencent.map.ama.newhome.widget.HomePlaceView"
    const val M_SET_CARD_DATA = "setCardData"
    const val M_UPDATE_ADDRESS = "updateAddressList"
    const val M_ON_CREATE_VIEW = "onCreateView"

    // ── ④ 大家都在看 ────────────────────────────────────────────────────
    /** 稳定：classes23.dex（11.7.0） / classes21.dex（11.6.0） */
    const val LOOK_RENDER =
        "com.tencent.map.kuiklyPoi.pages.poidetail.components.render.RenderPeopleStillWatchingKt"
    const val LOOK_ATTR =
        "com.tencent.map.kuiklyPoi.pages.poidetail.components.LookAndLook.RecommendationViewAttr"
    const val M_UPDATE_STILL_WATCHING = "updateFromStillWatchingData"

    // ── ⑤ 「我的」页卡片 ────────────────────────────────────────────────
    /** 稳定：classes5.dex（11.7.0） / classes4.dex（11.6.0） */
    const val MINE_PAGE = "com.tencent.map.personalCenter.PersonalCenter"
    /** 卡片数据管道（本模块不用它挂钩，仅用于确认模板号；11.6/11.7 同名同结构） */
    const val MINE_CARD_MANAGER = "com.tencent.map.personalCenter.hooks.useCardManager"
    const val M_PARSE_CARDS = "parseCardListResponse"

    /** 服务端卡片模板号 → 本模块的开关键（实证自 parseCardListResponse 的字面量） */
    @JvmField
    val CARD_TEMPLATES = intArrayOf(12, 41, 76, 80, 81, 82, 83, 84)

    @JvmField
    val CARD_LABELS = arrayOf(
        "工具栏", "活动专区", "我的小程序", "订单", "积分中心", "个性化设置", "我的店铺", "我的车辆",
    )

    // ── ⑥ 主页地图图标 ──────────────────────────────────────────────────
    /** 稳定：classes19.dex（11.7.0） */
    const val MAP_BASE_VIEW = "com.tencent.map.api.view.MapBaseView"
    const val MAP_TOOLBAR = "com.tencent.map.api.view.ToolBar"
    const val M_SET_VISIBLE = "setVisible"
    const val M_IS_HOME_PAGE = "isHomePage"

    /** 地图图标视图的取用方法（全部实证自 MapBaseView 方法表） */
    @JvmField
    val ICON_GETTERS = arrayOf(
        "getToolBar", "getSceneSwitch", "getCompass", "getZoomView",
        "getLocateBtn", "getScreenshotView", "getShare", "getShareView",
        "getDayNightView", "getCopyrightView", "getScale", "getScaleView",
    )

    /** 图标重排后会被调用的方法 —— 挂这些点，否则下一次重排会把图标摆回来 */
    @JvmField
    val ICON_RESHUFFLE = arrayOf(
        "showViews", "updateZoomStatus", "setToolBarElementVisible",
        "showZoomContral", "showZoomButton", "setCompassVisibility",
    )

    // ── 不属于本模块、但同属"主页地图"的说明 ─────────────────────────────
    /**
     * 地图引擎画的兴趣点图标与名称：由原生引擎按样式资源绘制，Java 层无开关。
     * 证据：11.7.0 里只有 {@code map_icon_4.1} / {@code map_icon_version}
     * （classes9.dex，{@code com.tencent.mapsdk2.internal.config.data.k.<init>}）
     * 与 {@code map_icon_poi_8_7_}（classes20.dex，
     * {@code com.tencent.map.init.tasks.optional.ConfigUpdateTask#run}）三个字面量。
     * 要做只能像高德侧那样走原生样式改写。
     */
    @JvmField
    val MAP_ENGINE_STYLE_KEYS = arrayOf(
        "map_icon_4.1", "map_icon_version", "map_icon_poi_8_7_",
    )

    // ── ⑦ 开屏广告（[TmapSplash]） ─────────────────────────────────
    /**
     * 全部逐类解析 versionCode <b>6013</b> 的 22 个 DEX，并与 6011 逐方法签名比对实证。
     *
     * <p>结论：<b>这两组锚点在 6011→6013 上逐方法签名完全一致</b>（{@code GDTADManager} 35 个方法、
     * {@code TGSplashAD} 50 个、{@code SplashRequestTask} 11 个、{@code DecodeSplashTask} 2 个、
     * {@code CheckAndDeleteSplashTask} 2 个、{@code SplashManagerInitTask} 5 个、
     * 两个 Receiver 各 2 个、{@code SplashEntity} 4 个，全部 identical）。
     *
     * <p>容易误判的一条：{@code com.tencent.ams.fusion.widget.splash.SplashView}（旧
     * {@code SplashContainerHooks} 的探测点）<b>6011 里就不存在</b>，融合 SDK 在两边都是 800 个类。
     * 那行探测从一开始就是死代码，与本轮无关。
     */
    const val GDT_MANAGER = "com.qq.e.comm.managers.GDTADManager"
    const val TG_SPLASH = "com.qq.e.tg.splash.TGSplashAD"
    const val SPLASH_REQUEST_TASK =
        "com.tencent.map.init.tasks.optional.SplashRequestTask"
    const val SPLASH_DECODE_TASK = "com.tencent.map.init.tasks.DecodeSplashTask"
    const val SPLASH_CHECK_TASK =
        "com.tencent.map.init.tasks.optional.CheckAndDeleteSplashTask"
    const val SPLASH_MGR_TASK =
        "com.tencent.map.launch.v2.task.t2.SplashManagerInitTask"
    const val SPLASH_BCAST_REQUEST =
        "com.tencent.map.ama.splash.SplashBroadcastReceiver"
    const val SPLASH_BCAST_TIMER =
        "com.tencent.map.ama.splash.SplashTimerBroadcastReceiver"
    /** 开屏实体；{@code a()Z} = 「endT >= now」（实证自字节码），恒 false 即全部判过期。 */
    const val SPLASH_ENTITY = "com.tencent.map.ama.splash.SplashEntity"

    // ── ⑧ 升级检查 / 升级弹窗（[TmapUpdate]） ───────────────────────
    /**
     * 启动任务。6013 上<b>已无调用点</b>（全包 {@code --InvokeXref} = 0 条）：检查改走
     * {@code navisdk.a.evp → navisdk.a.hgi.a(Context)}，两个都是混淆名。钩子保留，
     * 但它不会 HIT —— 真正兜底的是 [UPGRADE_INFO] 与 [UPGRADE_DIALOG]。
     */
    const val UPGRADE_TASK = "com.tencent.map.init.tasks.UpgradeInitTask"
    /** 升级信息数据体；{@code parseJson} 的 {@code version} 字段决定"要不要提示升级"。 */
    const val UPGRADE_INFO = "com.tencent.map.ama.upgrade.AppUpgradeInfo"
    /** 标准升级弹窗，覆写了 {@code Dialog#show()}。 */
    const val UPGRADE_DIALOG = "com.tencent.map.ama.upgrade.view.AppUpgradeDialog"
    /** 图文轮播升级弹窗，<b>没有</b>覆写 show()，靠 {@code Dialog#show()} 的类名闸门拦。 */
    const val UPGRADE_SLIDE_DIALOG =
        "com.tencent.map.ama.upgrade.slidedialog.SlideDialog"

    /*
     * ── ⑨ 6011 → 6013 逐类签名比对结果（不是猜的，是工具跑的） ─────────────
     *
     * 用 .agent_work/scripts/tmap_diff_6011_6013.py 把两个 base.apk 的全部 DEX 解出来逐类比对：
     *   6011 = 23 dex / 220,019 类    6013 = 22 dex / 214,763 类
     *   （删 10,849 类、增 5,593 类 —— 变的是 Kuikly / Hippy / 业务包，我们挂的地方没动）
     *
     * 开屏 + 去更新两组：逐方法签名 identical，零漂移。
     *
     * 三处真实的签名变化（都在回归组里；本模块因为一律"按名挂 + 不依赖参数类型"才没被打破，
     * 记在这里是给下一轮任何一个想改用精确签名的人看的）：
     *
     *   com.tencent.map.ama.newhome.component.HomeFavComponentImpl
     *       setCardData(Lcom/tencent/map/navisdk/a/iri;)V   →  (.../irf;)V
     *       另有 6 个方法的第一参数 hyo → hyl（内部类跟着换名）
     *   com.tencent.map.ama.newhome.widget.HomePlaceView
     *       setCardData(.../iri;)V → (.../irf;)V
     *       getPresenter()Lcom/tencent/map/navisdk/a/koz$a; → (.../kow$a;)
     *   com.tencent.map.api.view.MapBaseView
     *       addMapElement / getMapElement 的参数/返回 .../mvl; → .../mvi;
     *
     * 对照参考：navisdk.a.hgi（升级管理器）在 6013 上 80 个方法与 6011 完全一致 —— 混淆名这一次
     * 没漂。即便如此仍然不挂它：混淆名不漂是运气，不是契约。
     *
     * 另一条容易记错的：com.tencent.map.ama.upgrade 包两边都是 13 个类、com.qq.e.tg.splash 都是
     * 31 个类、com.tencent.ams.fusion 都是 800 个类。6013 不是"砍了东西"，是换了内部实现
     * （UpgradeInitTask 变成零调用点就是证据）。
     */
}
