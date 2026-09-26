package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * HomeTweaks — 首页 / 「我的」页 UI 自定义引擎。
 *
 * ══ 实测结构（高德 16.25.1.2029 / NewMapActivity / 1080x2400，真机 TreeDump 实证）══
 *
 * 首页主体整棵是 AJX3 虚拟 DOM：
 *   AmapAjxView > FullView > BodyView > PullToRefreshList > AjxList2
 *   AjxList2 的直接子节点 = AjxAbsoluteLayout = 「列表 item」，一个区块一个 item。
 *
 * 工具宫格（**整个宫格只是同一个 item 内部的一块**，不是每格一个 item）：
 *   Label(184x47) > Container(158xH)[格子] > Container(158xH) > Container(1006xH)[行]
 *                > 宫格 Container > 区块 Container > AjxAbsoluteLayout[item]
 *   3 行 × 5 列共 15 个槽位：第 1~2 行是 10 个工具，第 3 行是「更多工具」那页的轮播格
 *   （景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 更多工具 轮着显示）。
 *   列距 212px，格子宽 158px，行高 164/147/147px。
 *
 * 「我的」页同为 AJX 列表；订单 / 车辆服务是 HorizontalScroller 行：
 *   Label(209x42) > Container(209x205)[格子] > AjxAbsoluteLayout(1043x205) > HorizontalScroller
 *
 * 底部标签栏是原生 View：LinearLayout 行 > TabItemLayoutV2 > TextView(tab_name_v2)。
 *
 * ══ 旧版为什么全线崩坏（本次修复的根因，全部真机实证）══════════════════════
 *
 * 1) **整个工具宫格消失** —— 锚点表用子串匹配，宫格第 3 行轮播出的 `景点游玩`
 *    命中 ANCHOR_SCENIC 的「景点」，被当成 feed_scenic；然后沿父链爬到 AjxList2
 *    的 item —— 那个 item 就是**整个工具宫格** —— GONE + height=0。
 *    一条推荐区规则就这样把整个宫格抹掉。这就是"工具栏整个框被隐藏"。
 *
 * 2) **永远匹配不上的工具** —— 配置里写 `火车票` / `高德扫街`，界面上实际是
 *    `火车票机票` / `高德扫街榜`，精确匹配永远失败。
 *
 * 3) **永远匹配不上的我的页条目** —— 实际文案是 `-资质信息 ` 与 `协议中心-`，
 *    带装饰字符，`equals("资质信息")` 永远为假。
 *
 * 4) **"我的页被全部关闭"** —— 旧的 ascendWideRow 取的是**最外层**匹配祖先
 *    （best 一路被覆盖），一旦中间某层恰好也满足 宽≥760/高<300，就会一路爬到
 *    页面级容器并 GONE 掉；再加上 collapseItem 盲目爬到 AJX 列表 item，
 *    整页自然就没了。
 *
 * 5) **很卡、低效率** —— 每个锚点各自 postDelayed(500ms) + 150ms×20 重试，
 *    外加 600ms 全树遍历轮询，而且每次 visible(key) 都要走一次
 *    RemotePreferences IPC。
 *
 * 6) **先显示后消失的闪烁** —— 收缩发生在渲染之后 350~500ms。
 *
 * ══ 本实现的铁律 ═══════════════════════════════════════════════════
 *  A. 先分类，后动手。文本先判定归属面（工具格 / 标签栏 / 我的行 / 推荐卡），
 *     再做该面的动作，绝不跨面误伤。
 *  B. 工具格受保护。判定过的格子/行/宫格登记为 protected；任何"收缩 item"
 *     的爬升路径一旦穿过受保护节点立即中止 —— 推荐区规则再也不可能抹掉宫格。
 *  C. 一格一图。格子用 WeakHashMap 记住自己的工具键，轮播格的其它文案
 *     （景点游玩 / 旅游度假 …）复用同一个键，永不外溢成推荐区锚点。
 *  D. 一帧一次。Label.setText 只登记并立刻 GONE 掉该显示的标题（消闪烁），
 *     隐藏与重排合并到下一帧执行一次。
 *  E. 自愈。登记的锚点常驻索引，每一轮把"当时还没挂载"的锚点重试到挂载为止
 *     —— 这就是 车辆服务 那次 HorizontalScroller 尚未 attach 时丢事件的解药。
 *  F. 收行不收区。一行全空才收行，宫格全空才收宫格；绝不越级抹掉整个 item。
 */
public final class HomeTweaks {

    // ══════════════════════════════════════════════════════════ 静态表

    private static final Set<String> TAB_LABELS = new HashSet<>(Arrays.asList(Config.TABS));

    /** 工具格文案 → Config.TOOLS 内的键。轮播格的所有文案必须归一到同一个键。 */
    private static final Map<String, String> TOOL_ALIAS = new HashMap<>();
    static {
        for (String t : Config.TOOLS) TOOL_ALIAS.put(t, t);
        TOOL_ALIAS.put("火车票机票", "火车票");
        TOOL_ALIAS.put("高德扫街榜", "高德扫街");
        TOOL_ALIAS.put("旅游度假", "更多工具");
        TOOL_ALIAS.put("景点游玩", "更多工具");
        TOOL_ALIAS.put("离线地图", "更多工具");
        TOOL_ALIAS.put("通行费助手", "更多工具");
        TOOL_ALIAS.put("收藏夹", "更多工具");
        // 高德 17.00 工具栏运营位。它有时以 Label 文本出现、有时只挂在容器的
        // contentDescription 上（真机实测后者），两条路都要认。
        TOOL_ALIAS.put("高德出行节", "高德出行节");
        TOOL_ALIAS.put("出行节", "高德出行节");
        TOOL_ALIAS.put("秒送", "秒送"); TOOL_ALIAS.put("送", "秒送"); // 17.x 新工具「秒送」
    }

    /**
     * 搜索栏下方那一排圆形快捷入口的文案（真机截图：美食 / 酒店 / 景点门票 /
     * 加油充电 / 出行节 / 扫街榜）。
     *
     * 整排由 Config.K_HOME_QUICK_ROW 总开关控制；判定用「同一行命中 ≥2 个」——
     * 单命中不算，这样「推荐频道栏」里那颗孤零零的「美食」chip 不会被误伤。
     */
    private static final Set<String> QUICK_ROW_LABELS = new HashSet<>(Arrays.asList(
            // 第一页（真机截图）
            "美食", "酒店", "景点门票", "加油充电", "出行节", "扫街榜",
            // 第二页（右滑出来那一页）
            "充电站", "厕所", "商场", "银行", "医院", "休闲玩乐", "停车场",
            "药店", "火车站", "网吧", "洗车", "快捷酒店", "理发店", "便利店",
            "充电桩", "景点", "门票"));

    /** 工具键里**无条件隐藏**的（用户点名要删，没有开关）。 */
    private static final Set<String> TOOL_KEY_FORCE_HIDE = new HashSet<>(Arrays.asList(
            "高德出行节"));

    /**
     * 工具宫格"扩展页"的文案：首页宫格往下还藏着一排推荐工具
     * （实测 景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 旅游度假）。
     * 它们不是用户勾选的那 10 个工具，单独由 Config.K_TOOL_EXTRA 控制，
     * **默认隐藏** —— 否则首页往下拉就会冒出一整排没被关掉的格子。
     */
    private static final Set<String> TOOL_EXTRA_LABELS = new HashSet<>(Arrays.asList(
            "景点游玩", "离线地图", "通行费助手", "收藏夹", "旅游度假"));

    /**
     * 工具宫格里**无条件清除**的格子（用户点名要删，没有开关）。
     *
     * 高德 17.00 起在工具栏里塞了「高德出行节」运营入口（带「扫街券」角标）。
     * 真机 TreeDump 实证它**没有 Label 文本**，名字只挂在容器的 contentDescription 上：
     *   AJX!Container [0,0 158x147 scr36,1893] vis=V lp=158x147 CD='高德出行节'
     * 所以文本锚点永远看不见它 —— 必须按 contentDescription 认格子。
     *
     * 注意：这里只能走 hideCell（GONE 单格 + 行内重排），
     * **绝不能走 collapseHost** —— 那会爬到列表 item，而工具宫格整个就是**一个** item，
     * 一条规则就能把整排工具抹掉（v1.0.5 修过的老坑）。
     */
    private static final Set<String> TOOL_FORCE_HIDE = new HashSet<>(Arrays.asList(
            "高德出行节", "出行节", "扫街券"));

    /** 推荐频道栏 —— 只在"宽格子"里算数，避免误伤达人卡里的粉丝/关注计数（85px 窄格）。 */
    private static final Set<String> FEED_FILTER_LABELS =
            new HashSet<>(Arrays.asList("关注", "附近", "美食", "周末出游", "休闲玩乐"));

    private static final Set<String> HOME_CHIPS_LABELS =
            new HashSet<>(Arrays.asList("设置家", "设置单位", "常去地点"));

    // ----「我的」页：精确匹配（先做装饰字符归一）----
    private static final Set<String> MY_ORDER = new HashSet<>(Arrays.asList(
            "订单", "收藏", "待评价", "钱包卡券", "借钱"));
    private static final Set<String> MY_SERVICE = new HashSet<>(Arrays.asList(
            "车辆服务", "高德运动", "家人地图", "店铺入驻", "地图共建",
            "地图小程序", "高德代驾", "高德油耗", "工具箱"));
    private static final Set<String> MY_TASK = new HashSet<>(Arrays.asList("达人任务", "达人权益"));
    private static final Set<String> MY_PROMO = new HashSet<>(Arrays.asList(
            "扫街新发现", "长征星火", "小德果园", "重走长征路", "免费领水果", "赢奖牌勋章",
            "地图大富翁", "攒金条兑好礼", "达人卡中心", "写真评兑好礼"));
    private static final Set<String> MY_QUALITY_WORDS = new HashSet<>(Arrays.asList(
            "资质信息", "协议中心", "证照与协议", "猜你喜欢"));

    // ---- 推荐区锚点（按实测文案校准）----
    private static final String[] ANCHOR_WEATHER = {"天气", "降雨", "气温实况", "雷阵雨"};
    private static final String[] ANCHOR_SCENIC = {"周边游玩", "周边景区", "景点推荐", "附近游玩", "景区", "景点"};
    private static final String[] ANCHOR_RANK = {"热门榜", "精选榜", "榜单", "排行榜", "热榜", "上榜餐厅", "上榜景区"};
    private static final String[] ANCHOR_POSTS = {"个地点", "打卡地", "夜市", "周边热玩", "上榜"};
    private static final String[] ANCHOR_CONTENT = {
            "优质内容", "内容精选", "攻略", "Citywalk", "citywalk", "玩法", "秘境", "笔记",
            "避雷指南", "去扫描", "全新上线", "提前推演", "高德快报", "身边的新鲜事",
            "收藏起来", "值得一去", "遛娃", "城市漫游", "不可以", "不知道"};
    private static final String[] ANCHOR_AI = {"问问AI", "问问 AI", "小德助手"};

    /** 榜单/特色推荐卡标题（统一开关 K_FEED_BOARD，跨页面生效）：
     *  「尝尝这里的特色招牌菜吧」「特色场所轻松选，休闲更尽兴」等板块 */
    private static final String[] ANCHOR_BOARD = {
            "特色招牌菜", "特色场所", "休闲更尽兴", "轻松选", "招牌菜吧",
            "发现好去处", "好去处"};

    // ---- 无开关的强制清除项（用户点名要删，不给配置）----
    /** 「我的」页 好友动态 那一行（含其右侧"关注朋友种草新地点 / 邀请好友发现新宝藏"）。 */
    private static final String[] ANCHOR_JUNK_FRIENDS = {
            "好友动态", "关注朋友种草新地点", "邀请好友发现新宝藏", "朋友种草", "好友去哪了"};
    /** 「我的」页 答题瓜分百万大奖 红包运营卡（"一路封神"系列）。 */
    private static final String[] ANCHOR_JUNK_QUIZ = {
            "答题瓜分百万大奖", "答题赢大奖", "答题瓜分", "多答多得", "一路封神",
            "答对一题也有现金", "答题赢现金", "答题分现金"};

    /** 这些规则永远为「关」：不走配置、不进设置页、没有开关。 */
    private static final Set<String> ALWAYS_OFF = new HashSet<>(Arrays.asList(
            "junk_friends", "junk_quiz", "junk_promo_card",
            "junk_top_banner", "junk_float_ball"));

    /** 首页顶部运营横幅 / 运营卡（无开关强制清除）：十一出行补贴横幅、AI叫车卡。 */
    private static final String[] ANCHOR_JUNK_TOP = {
            "十一出行补贴", "限时开领", "赢好礼", "AI叫车", "一句话定制"};
    /** 悬浮推广球文案（窗口级 overlay，由 floatBallSweep 结构定位整球摘除） */
    private static final String[] FLOAT_BALL_WORDS = {"扫街榜", "赢好礼"};

    // ══════════════════════════════════════════════════════════ 运行态

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static volatile Activity activity;
    private static boolean scheduled;

    /**
     * 常驻锚点索引：AJX 文本 view → 文案。
     * 不随 applyPass 清空 —— 这样"setText 时还没挂载"的锚点可以在后续每一轮重试到挂载为止。
     */
    private static final WeakHashMap<View, String> anchors = new WeakHashMap<>();
    /** 工具格 → 工具键 */
    private static final WeakHashMap<View, String> cellKey = new WeakHashMap<>();
    /** 工具格 → 该格当前显示的 Label（用来判断"这格到底有没有画出来"） */
    private static final WeakHashMap<View, java.lang.ref.WeakReference<View>> cellLabel = new WeakHashMap<>();
    /** 受保护节点：工具格 / 工具行 / 宫格 */
    private static final WeakHashMap<View, Boolean> protectedNodes = new WeakHashMap<>();

    /**
     * 首页 / 「我的」页的 AJX 列表根（AjxList2 实例）。
     *
     * ══ 为什么必须有这张表（v1.0.7 的核心修复）══
     * 旧版所有规则都是**全页面生效**的：任何 Activity、任何页面上只要出现
     * 命中锚点表的文本就会被处理。于是「部分隐藏」一开，路线规划页跟着遭殃：
     *   · 方案卡上的 2379公里 / 2390公里 命中距离锚点 → feed_distance
     *     → collapseHost 沿父链爬到宿主 item → **整块路线信息模块 GONE**
     *   · 顶部 驾车 / 打车 / 顺风车 命中工具别名 → 被当成首页工具格隐藏
     *   · 结构识别「推荐频道栏」也会在别的横滚条上误命中
     *
     * 现在改成**先认领、再动手**：只有被证明是首页 /「我的」页的 AJX 列表根，
     * 其内部的锚点才归本模块管辖。认领证据必须是**这两个页面独有**的：
     *   · 工具宫格：一个 ≥2 行、每行 ≥3 格的格子阵列（首页独有；路线页只有 1 行）
     *   · 我的页条目：≥3 个不同的 订单/车辆服务/达人任务/资质信息 类文案
     *   · 首页 chips：≥2 个不同的 设置家/设置单位/常去地点
     * 路线规划页一条都不满足 → 整页不受影响。
     */
    private static final WeakHashMap<View, Boolean> homeLists = new WeakHashMap<>();
    /** 推荐频道栏是否已经处理过（结构识别，每轮 resume 重置） */
    private static volatile boolean channelDone;
    private static volatile long channelSince;

    /** 已收缩的宿主 item */
    private static final List<java.lang.ref.WeakReference<View>> hiddenItems = new ArrayList<>();
    /** 已 GONE 的格子 */
    private static final List<java.lang.ref.WeakReference<View>> hiddenCells = new ArrayList<>();
    /** 已收空的工具行（需要连同高度一起压成 0，否则宫格仍留白） */
    private static final List<java.lang.ref.WeakReference<View>> squashedRows = new ArrayList<>();
    /** 已收缩的宿主 → 规则（去重 + 幂等） */
    private static final WeakHashMap<View, String> hiddenWhy = new WeakHashMap<>();
    /** 已挂过 onBindViewHolder 的列表适配器类（按类去重，一个类只挂一次） */
    private static final Set<String> bindHooked = new HashSet<>();
    /** 已整行摘掉的快捷入口行（去重，避免 squashedRows 无限膨胀） */
    private static final WeakHashMap<View, Boolean> hiddenRows = new WeakHashMap<>();
    /** 已登记进 squashedRows 的节点（去重）—— 不去重的话每次复扫都会 add 一条，
     *  reassert 每 100ms 遍历它，用久了必然越翻越卡。 */
    private static final WeakHashMap<View, Boolean> squashedSeen = new WeakHashMap<>();
    /** 可见性代次：每次隐藏/恢复格子就 +1，宫格排版据此只做一次 */
    private static volatile int hideGen = 1;
    private static final WeakHashMap<View, Integer> packedGen = new WeakHashMap<>();
    private static final Set<String> loggedOnce = new HashSet<>();

    private static final Set<String> dumped = new HashSet<>();
    private static int dumpBudget = 120;

    private static volatile Map<String, Boolean> cfg = Collections.emptyMap();
    private static volatile long cfgAt;

    private static ViewGroup tabRow;

    private HomeTweaks() {}

    // ══════════════════════════════════════════════════════════ 安装

    public static void install(final ClassLoader cl) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            Method onPause = Activity.class.getDeclaredMethod("onPause");
            H.module.hook(onResume).setId("amapenhancer_resume")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            activity = (Activity) chain.getThisObject();
                            tabRow = null;
                            channelDone = false;
                            channelSince = System.currentTimeMillis();
                            scheduled = false;
                            // 收敛尾巴：布局稳定前后各压几轮，然后停手（不再 600ms 常驻轮询）
                            // v1.0.19：8 轮 → 4 轮。绝大多数隐藏现在都在 onTextSet 里
                            // 当场完成，applyPass 只负责收尾与认领，不需要压这么多轮。
                            for (long d : new long[]{120, 600, 1800, 4000}) {
                                MAIN.postDelayed(APPLY, d);
                            }
                            // 兜底复扫：即便一次 onGlobalLayout 都没打过来，也要补一刀
                            for (long d : new long[]{2500}) {
                                MAIN.postDelayed(LIGHT, d);
                            }
                            return r;
                        }
                    });
            H.module.hook(onPause).setId("amapenhancer_pause")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            activity = null;
                            tabRow = null;
                            MAIN.removeCallbacks(APPLY);
                            MAIN.removeCallbacks(LIGHT);
                            watchedDecor = null;
                            return chain.proceed();
                        }
                    });
            installLabelHook(cl);
            H.log(Log.INFO, MainHook.TAG, "hometweaks installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "hometweaks fail " + t);
        }
    }

    /**
     * AJX 文本钩子。这里只做两件事：
     *  - 登记（常驻索引，供后续每一轮重试到挂载为止）；
     *  - 若该文案对应"已关闭"的配置，立刻 GONE 掉标题本身 → 消除"卡片先显示后消失"的闪烁。
     * 真正的隐藏与重排由 applyPass 在下一帧合并执行一次。
     */
    /**
     * AJX 里所有会承载文本的控件。
     *
     * 只挂 Label 是不够的 —— 真机 TreeDump 里出现过 `Html(0x0)` 节点，
     * AJX 的富文本/内联样式文本走的是 Html，不挂它这些字永远进不了锚点表，
     * 「我的」页那张红包答题卡的文案很可能就是这么漏掉的。
     */
    private static final String[] AJX_TEXT_VIEWS = {
            "com.autonavi.minimap.ajx3.widget.view.Label",
            "com.autonavi.minimap.ajx3.widget.view.Html",
            "com.autonavi.minimap.ajx3.widget.view.RichText",
            "com.autonavi.minimap.ajx3.widget.view.Text",
    };

    private static void installLabelHook(ClassLoader cl) {
        int total = 0;
        for (String cn : AJX_TEXT_VIEWS) {
            total += hookTextClass(cl, cn);
        }
        H.log(Log.INFO, MainHook.TAG, "text hook installed x" + total);
    }

    /** 挂一个 AJX 文本控件的所有 setText 与 setAttribute 重载，返回挂上的条数。 */
    private static int hookTextClass(ClassLoader cl, String className) {
        try {
            Class<?> label = cl.loadClass(className);
            if (label == null) return 0;
            H.log(Log.INFO, MainHook.TAG, "text class found " + className);
            // 不能只挂 setText(String)：实测首页"推荐频道栏"那一排
            // （关注 / 成都 / 附近 / 周末出游 / 美食 / 休闲玩乐）根本不走这个重载，
            // 所以它们既看不见也藏不掉。这里把 Label 上所有以文本为入参的
            // 设值方法全部挂上，顺便把方法表打进日志便于以后校准。
            int hooked = 0;
            for (Method m : label.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.startsWith("set")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 0) continue;
                String p0 = ps[0].getName();
                boolean textual = p0.equals("java.lang.String")
                        || p0.equals("java.lang.CharSequence")
                        || p0.equals("java.lang.Object");
                if (!textual) continue;
                if (Config.debugLog() && loggedOnce.add("labelsig_" + n + ps.length)) {
                    H.log(Log.INFO, MainHook.TAG, "LABEL-METHOD " + m);
                }
                if (n.startsWith("setText") && hookLabelText(m)) hooked++;
            }
            // 关键补充：AJX 有一部分文本根本不走 setText，而是走属性系统
            // Label.setAttribute("text", value, ...)。
            // 首页「推荐频道栏」那一排（关注/成都/附近/周末出游/美食/休闲玩乐）
            // 实测就是走这条路 —— 只挂 setText 永远看不见它们。
            if (hookAttribute(label)) hooked++;
            return hooked;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "text class miss " + className + " " + t);
            return 0;
        }
    }

    private static boolean hookLabelText(Method m) {
        try {
            H.module.hook(m).setId("amapenhancer_label")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try { onLabelText(chain); } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            return true;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "label hook fail " + m + " " + t);
            return false;
        }
    }

    /** Label.setAttribute(name, value, ...) —— AJX 的属性写入口，文本属性名为 "text" */
    private static boolean hookAttribute(Class<?> label) {
        for (Method m : label.getDeclaredMethods()) {
            if (!m.getName().equals("setAttribute")) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 2 || !ps[0].getName().equals("java.lang.String")) continue;
            try {
                H.module.hook(m).setId("amapenhancer_labelattr")
                        .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                            @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                try {
                                    Object n0 = chain.getArg(0);
                                    Object v1 = chain.getArg(1);
                                    // v1.1.9：不再只认 "text" 属性 —— 目的地页板块标题
                                    //（发现好去处）实测不走 setText 也不走 setAttribute("text")，
                                    //  任何属性名只要是 CharSequence 文本都进锚点表。
                                    if (n0 instanceof String && v1 instanceof CharSequence) {
                                        onTextSet((View) chain.getThisObject(),
                                                ((CharSequence) v1).toString());
                                    }
                                } catch (Throwable ignored) {}
                                return r;
                            }
                        });
                return true;
            } catch (Throwable t) {
                H.log(Log.WARN, MainHook.TAG, "attr hook fail " + t);
            }
        }
        return false;
    }

    private static void onLabelText(io.github.libxposed.api.XposedInterface.Chain chain) {
        Object a0 = chain.getArg(0);
        if (!(a0 instanceof CharSequence)) return;
        onTextSet((View) chain.getThisObject(), a0.toString());
    }

    /** 所有 AJX 文本的统一落点（setText 与 setAttribute("text"…) 都汇到这里） */
    private static void onTextSet(View v, String raw) {
        if (v == null || raw == null) return;
        if (raw.length() == 0 || raw.length() > 28) return;
        String t = norm(raw);
        if (t.length() == 0) return;

        String toolKey = TOOL_ALIAS.get(t);
        String rule = toolKey != null ? null : ruleFor(t, v);

        // ══ v1.0.11：**所有**短文案都登记，不再只登记命中规则的 ══
        // 结构类清除（快捷入口整排 / 工具格强制清除）必须按文案认格子，
        // 只登记命中规则的文案时，酒店 / 景点门票 / 加油充电 / 扫街榜 这些字
        // 根本进不了索引 —— 快捷入口那排因此永远只命中一个「美食」，
        // 凑不够「同一行 ≥2 个」的判据，整排自然删不掉（用户实测复现）。
        synchronized (LOCK) { anchors.put(v, t); }
        trace(v, t);
        if (toolKey == null && rule == null) return;

        // 无开关的强制清除项（好友动态 / 答题红包卡）：文案本身已经足够独特，
        // 不等归属判定，setText 当场就 GONE，卡片一次都不会画出来。
        if (rule != null && ALWAYS_OFF.contains(rule)) {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            // 当场把宿主 item 也收掉（一次父链上溯，O(层数)）——
            // 广告在**首帧绘制之前**就整块没了，不用等 applyPass。
            try { collapseHost(v, rule); } catch (Throwable ignored) {}
            schedule();
            return;
        }
        // 工具格强制清除（高德出行节）：文案一到就摘那一格，同样在首帧之前
        if (toolKey != null && TOOL_KEY_FORCE_HIDE.contains(toolKey)) {
            try {
                View fcell = toolCellOf(v);
                if (fcell != null && fcell.getVisibility() != View.GONE) {
                    Set<ViewGroup> frows = new LinkedHashSet<>();
                    hideCell(fcell, "key:" + toolKey, frows);
                    for (ViewGroup fr : frows) { packRow(fr); squashRowIfEmpty(fr); }
                }
            } catch (Throwable ignored) {}
            schedule();
            return;
        }
        // ★ 快捷入口整排：文案一到就当场把 pager 整块摘掉（首帧绘制之前）★
        //   它不依赖 homeLists 认领（那排实测在搜索页），也不等 applyPass，
        //   所以页面第一次画出来时这一排就已经不在了 —— 没有「先显示再消失」。
        if (QUICK_ROW_LABELS.contains(t) && !cfgOn(Config.K_HOME_QUICK_ROW)) {
            try {
                View qcell = toolCellOf(v);
                if (qcell != null) {
                    View blk = quickBlockOf(qcell);
                    if (blk == null && qcell.getParent() instanceof View) blk = (View) qcell.getParent();
                    if (blk != null) hideRow(blk, Config.K_HOME_QUICK_ROW);
                }
            } catch (Throwable ignored) {}
        }

        // 归属未定（还没认领到首页 /「我的」页的列表根）→ 只登记，等 applyPass 认领后再动。
        if (!inHomeScope(v)) { schedule(); return; }

        String key = toolKey;
        if (key == null) {
            View cell = ascendSmallCell(v);
            String ck = cell == null ? null : cellKey.get(cell);
            if (ck != null) key = ck;   // 轮播格的其它文案复用本格已登记的键
        }
        if (key != null && !cfgOn(Config.K_TOOL_PREFIX + key)) {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        } else if (rule != null && !ruleEnabled(rule)) {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        }
        schedule();
    }

    private static void schedule() {
        if (scheduled) return;
        scheduled = true;
        MAIN.post(APPLY);
    }

    // ══════════════════════════════════════════════════════════ 一帧一次

    private static final Runnable APPLY = new Runnable() {
        @Override public void run() {
            scheduled = false;
            try { applyPass(); }
            catch (Throwable t) { H.log(Log.WARN, MainHook.TAG, "applyPass err " + t); }
        }
    };

    private static void applyPass() {
        Activity act = activity;
        if (act == null || act.isFinishing()) return;
        refreshCfg();

        View decor;
        try { decor = act.getWindow().getDecorView(); } catch (Throwable t) { return; }
        if (decor == null) return;

        applyTabs(decor);
        installLayoutWatcher(decor);

        List<View> views = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<View, String> e : anchors.entrySet()) {
                views.add(e.getKey());
                texts.add(e.getValue());
            }
        }

        // ① 先认领：把首页 /「我的」页的 AJX 列表根登记下来。
        //    认领之前一律不动手 —— 这就是路线规划页不再被误抹的原因。
        claimHomeLists(views, texts);

        // ② 工具宫格（只在已认领的列表内生效）
        applyTools(views, texts);

        // ③ 推荐区 /「我的」页规则（同样只在已认领的列表内生效；
        //    无开关的强制清除项不受闸门限制）
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            String t = texts.get(i);
            if (TOOL_ALIAS.containsKey(t)) continue;
            String rule = ruleFor(t, v);
            if (rule == null || ruleEnabled(rule)) continue;
            boolean crossPage = Config.K_FEED_BOARD.equals(rule);   // 榜单卡跨页面统一删
            if (!ALWAYS_OFF.contains(rule) && !crossPage && !inHomeScope(v)) continue;
            collapseHost(v, rule);
        }

        // ②.5 强制清除项 + 工具格强制清除 + 快捷入口整排：
        //      **一趟 BFS 全做完**，作用域只有已认领的列表（不扫整棵 DecorView）。
        for (View r : claimedRoots()) { sweepItemAll(r); }
        // ③.5 快捷入口整排：**不受认领限制**，decor 级扫一次
        //      （那排实测在搜索页，没有首页证据，永远认领不到）
        quickRowSweep(decor);
        // ③.6 v1.2.1：主动给当前页所有 AJX 列表挂 adapter bind hook ——
        //      目的地/路线详情页的板块靠 ruleForItem 判定，但这些页的列表
        //      从没被 collapseHost 命中过，adapter 没挂 hook，BIND-HIDE 永不发生。
        sweepHookLists(decor);
        // ③.7 v1.2.2：榜单板块的结构识别 —— 标题不走文本钩子（GL 级），
        //      卡片是唯一身份：满屏容器 + 子树 ≥2 张 460~560 宽双列卡 → 整块收掉。
        boardSweep(decor);

        // ③.5 强制清除项兜底扫描（好友动态 / 答题红包卡）。
        //      这两块卡片的文案**不一定走 Label.setText** —— 实测 AJX 里还有
        //      Html 等其它文本控件（TreeDump 里出现过 Html(0x0) 节点），
        //      只挂 Label 的话它们的文案永远进不了锚点表。这里直接遍历已认领的
        //      首页 /「我的」页列表子树，用 TextView#getText() 兜底认字，
        //      认到就把该 TextView 所属的列表 item 整个收掉。

        // ④ 推荐频道栏（关注 / 成都 / 附近 / 周末出游 / 美食 / 休闲玩乐）：
        //    实测它的文案既不走 Label.setText 也不走 Label.setAttribute("text")，
        //    所以文本锚点根本看不见它。这一栏用**结构**识别并整体摘掉，
        //    且必须落在已认领的首页列表里 —— 别的页面的横滚条一概不碰。
        if (!cfgOn(Config.K_FEED_FILTER) && !channelDone) {
            // 频道栏是懒加载的，可能几秒后才出现，因此在一个时间窗内持续找；
            // 超窗后停手，避免无休止全树遍历。
            View bar = findChannelBar(decor, 0);
            if (bar != null) {
                collapseHost(bar, Config.K_FEED_FILTER);
                channelDone = true;
            } else if (System.currentTimeMillis() - channelSince < 40000) {
                MAIN.postDelayed(APPLY, 400);
            }
        }

        reassert();
    }

    /** 每轮重申（AJX 重渲染会复活可见性 / 复位高度）。 */
    private static void reassert() {
        for (Iterator<java.lang.ref.WeakReference<View>> it = hiddenItems.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            // AJX 把 item 复活成有高度时才补 0，避免无谓的 LayoutParams 抖动
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != 0 && v.getHeight() > 0) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
        }
        for (Iterator<java.lang.ref.WeakReference<View>> it = hiddenCells.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        }
        // 幽灵格重申：AJX 重建后 alpha/触摸/子树可能被复位，逐轮按回
        for (Iterator<java.lang.ref.WeakReference<View>> it = ghostCells.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            try {
                if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                if (v.getAlpha() != 0f) v.setAlpha(0f);
                v.setOnTouchListener(GHOST_TOUCH);
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c.getVisibility() != View.GONE) c.setVisibility(View.GONE);
                    }
                }
            } catch (Throwable ignored) {}
        }
        for (Iterator<java.lang.ref.WeakReference<View>> it = squashedRows.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
        }
    }

    // ══════════════════════════════════════════════════════════ 归属面认领

    /**
     * 先认领，后动手。
     *
     * 逐个锚点找出它所在的 AJX 列表根（AjxList2 实例），统计三类**页面独有**证据，
     * 证据够数才把这个列表根登记为 homeLists —— 此后只有它内部的锚点归本模块管辖。
     *
     *   [0] 工具宫格格子：必须落在一个 ≥2 行、每行 ≥3 格的阵列里（首页独有）
     *   [1] 首页 chips（设置家 / 设置单位 / 常去地点）
     *   [2] 「我的」页条目（订单 / 车辆服务 / 达人任务 / 资质信息 …）
     *
     * 路线规划页的证据：只有 驾车/打车/顺风车（不构成宫格）和 距离标签（不在这三类里）
     * → 一条都不满足 → 整页不认领 → 一个字都不动。
     */
    private static void claimHomeLists(List<View> views, List<String> texts) {
        try {
            Map<View, int[]> stat = new HashMap<>();
            for (int i = 0; i < views.size(); i++) {
                View v = views.get(i);
                String t = texts.get(i);
                // ══ 性能：先做「证据判定」，够格的锚点才去爬父链 ══
                // 原来对**每一个**锚点都调 listRootOf()（最多 40 层父链），
                // 而 v1.0.11 起所有短文案都进了锚点表 —— 那是纯浪费。
                int slot;
                if (TOOL_ALIAS.containsKey(t)) slot = 0;
                else if (HOME_CHIPS_LABELS.contains(t)) slot = 1;
                else if (isMyAnchor(t)) slot = 2;
                else continue;
                View root = listRootOf(v);
                if (root == null) continue;
                int[] s = stat.get(root);
                if (s == null) { s = new int[3]; stat.put(root, s); }
                if (slot == 0) {
                    if (isToolGridCell(v)) s[0]++;
                } else {
                    s[slot]++;
                }
            }
            for (Map.Entry<View, int[]> e : stat.entrySet()) {
                int[] s = e.getValue();
                if (s[0] >= 1 || s[1] >= 2 || s[2] >= 3) {
                    homeLists.put(e.getKey(), Boolean.TRUE);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 从任意节点上溯到它所在的 AJX 列表根（父级是列表的那个节点的父级）。 */
    private static View listRootOf(View v) {
        View cur = v;
        for (int i = 0; i < 40 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            if (isList(p)) return p;
            cur = p;
        }
        return null;
    }

    /**
     * ══ 一趟 BFS 做完三件事（v1.0.13 的性能核心）══
     *
     * v1.0.12 是 sweepJunk / sweepToolCells / sweepQuickRow 各扫一遍 ——
     * 同一棵子树被完整走三次。这里合并成**一趟**，成本直接降到 1/3。
     *
     * 调用时机只有两个：
     *   · 列表 onBindViewHolder —— 作用域是**刚绑定的那一个 item**（几百节点）
     *   · resume 后的固定几刀 —— 作用域是已认领列表
     * 绝不挂在 onGlobalLayout 上（那是 v1.0.11 卡顿的根因）。
     */
    private static void sweepItemAll(View root) {
        if (root == null || root.getParent() == null) return;
        try {
            Set<ViewGroup> toolRows = new LinkedHashSet<>();
            List<View> stack = new ArrayList<>();
            stack.add(root);
            int guard = 0;
            while (!stack.isEmpty() && guard++ < 900) {   // 单个 item 的子树，预算收紧
                View v = stack.remove(stack.size() - 1);
                String t = textAt(v);
                String cd = cdOf(v);

                // a) 强制清除项（好友动态 / 答题红包卡）
                String jr = junkRuleOf(t);
                if (jr == null) jr = junkRuleOf(cd);
                if (jr != null && !hiddenWhy.containsKey(v)) {
                    H.log(Log.INFO, MainHook.TAG, "JUNK-HIT " + jr + " '" + t + "'");
                    collapseHost(v, jr);
                }

                // b) 工具格强制清除（高德出行节）：CD 优先，退回文案
                String name = cd != null ? cd : t;
                if (name != null && TOOL_FORCE_HIDE.contains(name)) {
                    View cell = toolCellOf(v);
                    if (cell != null && cell.getVisibility() != View.GONE) {
                        hideCell(cell, "cd:" + name, toolRows);
                    }
                }


                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
                }
            }
            for (ViewGroup row : toolRows) {
                packRowOrGrid(row);
                squashRowIfEmpty(row);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "sweepItemAll err " + t);
        }
    }

    /** v1.2.1：扫当前页所有 AJX 列表，主动挂 adapter bind hook（有 bindHooked 去重） */
    private static void sweepHookLists(View root) {
        if (root == null) return;
        try {
            List<View> stack = new ArrayList<>();
            stack.add(root);
            int guard = 0;
            while (!stack.isEmpty() && guard++ < 2500) {
                View v = stack.remove(stack.size() - 1);
                if (isList(v)) {
                    hookListAdapter(v);
                    continue;   // 列表内部不用再扫
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 已认领的首页 /「我的」页列表根快照（扫描的**唯一**作用域，绝不扫整棵 DecorView）。 */
    private static List<View> claimedRoots() {
        List<View> out = new ArrayList<>();
        synchronized (LOCK) { out.addAll(homeLists.keySet()); }
        return out;
    }

    /** 这个节点是否落在已认领的首页 /「我的」页列表里。 */
    private static boolean inHomeScope(View v) {
        View cur = v;
        for (int i = 0; i < 40 && cur != null; i++) {
            if (homeLists.containsKey(cur)) return true;
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /**
     * 这个工具文案是不是真的落在**首页工具宫格**里。
     * 判据：格子 → 行（≥3 格）→ 宫格（≥2 行）。
     * 路线规划页顶部的 驾车/公共交通/骑行/步行 只有 1 行，因此永远不算宫格。
     */
    private static boolean isToolGridCell(View anchor) {
        View cell = ascendSmallCell(anchor);
        if (cell == null) return false;
        ViewParent rp = cell.getParent();
        if (!(rp instanceof ViewGroup)) return false;
        ViewGroup row = (ViewGroup) rp;
        if (row.getChildCount() < 3) return false;
        ViewParent gp = row.getParent();
        if (!(gp instanceof ViewGroup)) return false;
        ViewGroup grid = (ViewGroup) gp;
        int rows = 0;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() >= 3) rows++;
        }
        return rows >= 2;
    }

    /** 「我的」页独有文案（用于认领「我的」页的列表根）。 */
    private static boolean isMyAnchor(String t) {
        return MY_ORDER.contains(t) || MY_SERVICE.contains(t) || MY_TASK.contains(t)
                || MY_PROMO.contains(t) || MY_QUALITY_WORDS.contains(t);
    }

    /** 读一个节点的 contentDescription（归一后返回，太长的不算）。 */
    private static String cdOf(View v) {
        try {
            CharSequence cs = v.getContentDescription();
            if (cs == null) return null;
            String s = norm(cs.toString());
            return (s.length() > 0 && s.length() <= 28) ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 快捷入口整块摘除。
     *
     * ══ 为什么必须独立出来、且**不受 homeLists 认领限制**（v1.0.15 的关键修正）══
     * 真机实测：这一排出现在**搜索页**（点搜索框进去那一页），
     * 而它没有任何「首页 / 我的页独有证据」，永远认领不到 homeLists ——
     * 之前的 sweepItemAll 只扫已认领列表，于是这一排从来没被处理过。
     *
     * 安全性由「同一个 pager 块里命中 ≥2 个该排文案」保证：
     * 单个「美食」（推荐频道栏那颗 chip）凑不够，不会误伤。
     */
    private static void quickRowSweep(View root) {
        if (root == null) return;
        try {
            if (cfgOn(Config.K_HOME_QUICK_ROW)) return;   // 总开关开着 → 一个像素都不动
            Map<View, Integer> blocks = new HashMap<>();
            List<View> stack = new ArrayList<>();
            stack.add(root);
            int guard = 0;
            while (!stack.isEmpty() && guard++ < 4000) {
                View v = stack.remove(stack.size() - 1);
                String t = textAt(v);
                if (t == null) t = cdOf(v);
                if (t != null && QUICK_ROW_LABELS.contains(t)) {
                    View cell = toolCellOf(v);
                    if (cell != null) {
                        View blk = quickBlockOf(cell);
                        if (blk == null && cell.getParent() instanceof View) {
                            blk = (View) cell.getParent();
                        }
                        if (blk != null) {
                            Integer c = blocks.get(blk);
                            blocks.put(blk, c == null ? 1 : c + 1);
                        }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
                }
            }
            for (Map.Entry<View, Integer> e : blocks.entrySet()) {
                if (e.getValue() < 2) continue;
                hideRow(e.getKey(), Config.K_HOME_QUICK_ROW);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "quickRowSweep err " + t);
        }
    }

    /**
     * 从快捷入口格子往上找到 **pager 所在的整块**。
     *
     * 实测结构（高德 17.00 真机 TreeDump）：
     *   Container(格子 169x184) → Container → Container(行 1016x184)
     *     → Container(页) → AjxAbsoluteLayout(2032x184, 两页并排)
     *     → AJX!HorizontalScroller(1043x205) → Container(整块, 含页码指示点)
     *
     * 摘掉 scroller 的父级 = 两页 + 指示点一起消失，不会再「往右滑还有」。
     */
    private static View quickBlockOf(View cell) {
        View cur = cell;
        View pager = null;
        for (int i = 0; i < 8 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            if (isList(p)) break;
            String cn = p.getClass().getName();
            if (cn.endsWith("HorizontalScroller") || cn.contains("Scroller")
                    || cn.contains("Pager")) {
                pager = p;
                break;
            }
            cur = p;
        }
        if (pager == null) return null;
        ViewParent pp = pager.getParent();
        if (pp instanceof View && !isList((View) pp)) {
            View par = (View) pp;
            int h = par.getHeight();
            if (h > 0 && h <= 900) return par;
        }
        return pager;
    }

    /** 从节点上溯到「工具格子」：直接父级是宽行（≥600px）的那个节点。 */
    private static View toolCellOf(View v) {
        View cur = v;
        for (int i = 0; i < 8 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            if (p.getWidth() >= 600) return cur;
            cur = p;
        }
        return null;
    }

    /**
     * 工具格强制清除（高德出行节）。
     *
     * 高德 17.00 的工具栏新入口「高德出行节」没有 Label 文本，
     * 名字只挂在容器 contentDescription 上，走不到文本锚点那条路。
     * 这里在**已认领的首页列表**里按 contentDescription 认格子，
     * 命中就把那一格 GONE 掉并做行内重排 —— 与用户手动关掉的格子走同一条路，
     * 不碰 item、不碰宫格，绝不会误伤整排工具。
     */
    private static void sweepToolCells(View decor) {
        try {
            // 不依赖 homeLists 认领 —— 只认「文案完全等于强制清除名」这一件事。
            // 名字唯一，不可能误伤；而且运营位有可能挂在嵌套列表 / 独立区块里，
            // 认领闸门一旦没覆盖到就会漏（上一版就是这么漏掉的）。
            List<View> roots = new ArrayList<>();
            roots.add(decor);
            synchronized (LOCK) { roots.addAll(homeLists.keySet()); }
            Set<ViewGroup> dirtyRows = new LinkedHashSet<>();
            for (int i = 0; i < roots.size(); i++) {
                View root = roots.get(i);
                if (root == null || root.getParent() == null) continue;
                List<View> stack = new ArrayList<>();
                stack.add(root);
                int guard = 0;
                while (!stack.isEmpty() && guard++ < 4000) {
                    View v = stack.remove(stack.size() - 1);
                    String cd = cdOf(v);
                    if (cd == null) cd = textAt(v);      // 有的版本走 Label 文本，有的只挂 CD
                    if (cd != null && TOOL_FORCE_HIDE.contains(cd)) {
                        View cell = toolCellOf(v);
                        if (cell != null && cell.getVisibility() != View.GONE) {
                            hideCell(cell, "cd:" + cd, dirtyRows);
                        }
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) v;
                        for (int k = 0; k < g.getChildCount(); k++) stack.add(g.getChildAt(k));
                    }
                }
            }
            for (ViewGroup row : dirtyRows) {
                packRowOrGrid(row);
                squashRowIfEmpty(row);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "sweepToolCells err " + t);
        }
    }

    /**
     * 搜索栏下方那一排圆形快捷入口的**总开关**（Config.K_HOME_QUICK_ROW）。
     *
     * 真机截图实测这一排是：美食 / 酒店 / 景点门票 / 加油充电 / 出行节 / 扫街榜。
     * 它们不是工具宫格（只有一行，过不了 isToolGridCell 的「≥2 行」判据），
     * 所以单独走这条：认到 ≥2 个同排文案就把**整行**摘掉。
     *
     * 为什么是「≥2 个」而不是「≥1 个」：推荐频道栏里也有一颗叫「美食」的 chip，
     * 单命中就动手会把它连带摘掉。同一行命中两个及以上，才一定是这一排。
     */
    private static void sweepQuickRow(View decor) {
        try {
            if (cfgOn(Config.K_HOME_QUICK_ROW)) return;     // 总开关开着 → 一个像素都不动
            if (decor == null) return;
            Map<View, Integer> rows = new HashMap<>();
            Map<View, View> firstHit = quickFirstHit = new HashMap<>();
            List<View> stack = new ArrayList<>();
            stack.add(decor);
            int guard = 0;
            while (!stack.isEmpty() && guard++ < 15000) {
                View v = stack.remove(stack.size() - 1);
                String t = textAt(v);
                if (t == null) t = cdOf(v);
                if (t != null && QUICK_ROW_LABELS.contains(t)) {
                    View cell = toolCellOf(v);
                    if (cell != null && cell.getParent() instanceof View) {
                        View row = (View) cell.getParent();
                        Integer c = rows.get(row);
                        rows.put(row, c == null ? 1 : c + 1);
                        if (!firstHit.containsKey(row)) firstHit.put(row, v);
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int k = 0; k < g.getChildCount(); k++) stack.add(g.getChildAt(k));
                }
            }
            for (Map.Entry<View, Integer> e : rows.entrySet()) {
                if (e.getValue() < 2) continue;
                // ══ v1.0.10：整块摘掉，不再逐格隐藏 ══
                // 这一排是**横向滚动**的（右滑还有 充电站/厕所/商场/银行/医院… 一页），
                // 逐格隐藏有三个毛病：翻页翻出来的格子是懒绑定的、当场还没生成；
                // 而且容器与页码指示点会留下一条空白（用户实测的「留白」）。
                // 所以改成：从命中锚点上溯到**列表 item**，整块 GONE + 高度压 0 ——
                // 容器、翻页内容、指示点一起消失，不留白也不会有第二页。
                // 从行往上取「区块容器」：一路爬到「再上一级就超过 900px」或「已经到列表 item」为止。
                // 这样摘掉的是这一整块（含第二页与页码指示点），
                // 又**不会**越级到整页 —— v1.0.10 的 collapseHost 就是越级了。
                View block = e.getKey();
                for (int k = 0; k < 5 && block.getParent() instanceof View; k++) {
                    View p = (View) block.getParent();
                    if (isList(p)) break;
                    if (p.getHeight() > 900) break;
                    block = p;
                }
                hideRow(block, Config.K_HOME_QUICK_ROW);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "sweepQuickRow err " + t);
        }
    }

    /** 取该行第一个命中锚点（用来上溯到列表 item 整块摘掉）。 */
    private static View firstMatchInRow(View row) {
        if (row == null) return null;
        for (Map.Entry<View, View> e : quickFirstHit.entrySet()) {
            if (e.getKey() == row) return e.getValue();
        }
        return null;
    }

    /** 本轮扫描：行 → 该行第一个命中锚点 */
    private static Map<View, View> quickFirstHit = new HashMap<>();

    /** 整行摘掉：GONE + 高度压 0，并登记进 squashedRows 让每轮重申。 */
    private static void hideRow(View row, String why) {
        if (row == null) return;
        if (!hiddenRows.containsKey(row)) {
            hiddenRows.put(row, Boolean.TRUE);
            H.log(Log.INFO, MainHook.TAG, "ROW-HIDE " + why + " " + geom(row));
        }
        if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            row.setLayoutParams(lp);
        }
        if (!squashedSeen.containsKey(row)) {
            squashedSeen.put(row, Boolean.TRUE);
            squashedRows.add(new java.lang.ref.WeakReference<>(row));
        }
        squashEmptyAncestors(row);
    }

    /**
     * 把「因为子节点全被摘掉而变成空壳」的祖先一并压 0。
     *
     * 为什么需要它：把 pager 那一块 GONE + 高度归零之后，
     * 它的父容器仍然按自己的 LayoutParams 占着高度 —— 屏幕上就是一条**空白带**
     * （用户实测的「贴白」）。这里向上看几层，只要某层已经没有任何可见子节点，
     * 就连它一起压 0。
     */
    private static void squashEmptyAncestors(View v) {
        View cur = v;
        for (int i = 0; i < 4 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            if (isList(p)) break;
            if (p instanceof ViewGroup && hasVisibleChild((ViewGroup) p)) break;
            if (p.getVisibility() != View.GONE) p.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = p.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
                p.setLayoutParams(lp);
            }
            if (!squashedSeen.containsKey(p)) {
                squashedSeen.put(p, Boolean.TRUE);
                squashedRows.add(new java.lang.ref.WeakReference<>(p));
            }
            cur = p;
        }
    }

    private static boolean hasVisibleChild(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.GONE && c.getHeight() > 0) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════ 持续复扫（v1.0.9）

    /**
     * 为什么需要持续复扫：AJX 会**整棵重建**卡片（新的 View 实例）。
     * 一次性扫描 + WeakHashMap 记录挡不住重建 —— 卡片重新冒出来时旧实例早没了，
     * 于是「我的页红包卡又出现了」。
     *
     * 触发点用 DecorView 的 onGlobalLayout：AJX 每次重排/重建都会打到这里，
     * 事件驱动、不轮询；再 debounce 250ms 合并连续布局，开销可以忽略。
     */
    /**
     * 每帧只做「重申」，**不做任何扫描**。
     *
     * 这就是「删了又反复出现」的解药：AJX 把节点复活成可见时，下一帧就被按回去。
     * 成本只有 O(已登记节点数)，与树大小无关 —— 100ms 时间闸门再兜一层，
     * 避免连续布局把 CPU 吃满。
     *
     * 对照 v1.0.11 的写法：那个是「每帧三趟全树 BFS（各 15000 节点预算）」——
     * 同一个监听，成本差了四个数量级。
     */
    private static volatile View watchedDecor;
    private static volatile long lastReassertAt;

    private static void installLayoutWatcher(final View decor) {
        if (decor == null || decor == watchedDecor) return;
        watchedDecor = decor;
        try {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() { tick(); }
                    });
        } catch (Throwable ignored) {}
    }

    private static int tickCount;

    private static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastReassertAt < 100) return;
        lastReassertAt = now;
        reassert();
        // 每 ~1.5 秒做一次「快捷入口」低频复扫（一趟 decor BFS，只在开关关闭时）。
        // 频率远低于每帧，既保证翻页/切页后不复活，又不会吃 CPU。
        // v1.1.2：tick 级复排会与 AJX 布局逐帧拉锯（用户实测"一直乱闪"），撤掉。
        // 重排只走事件驱动：onTextSet → applyPass → applyTools → packRowOrGrid。
        if (tickCount % 15 == 0) { Activity fa = activity; if (fa != null) floatBallSweep(fa.getWindow().getDecorView()); }
        if (++tickCount % 15 == 0) {
            Activity a = activity;
            if (a != null && !a.isFinishing()) {
                try { quickRowSweep(a.getWindow().getDecorView()); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 低频兜底复扫。
     *
     * ══ v1.0.12 的性能修复 ══
     * v1.0.11 在 DecorView 上挂了 OnGlobalLayoutListener，回调里跑三趟**全树 BFS**
     * （每趟预算 15000 节点）。列表一滚动就是每帧一次 —— 这就是用户实测的「非常卡」。
     *
     * 现在：
     *   · 主路径：列表 onBindViewHolder → 只扫**刚绑定的那个 item**（几百节点，微秒级）
     *   · 兜底：resume 后固定几刀，且只扫**已认领的首页/「我的」页列表**，不是整棵 DecorView
     */
    private static final Runnable LIGHT = new Runnable() {
        @Override public void run() {
            Activity a = activity;
            if (a == null || a.isFinishing()) return;
            try {
                for (View r : claimedRoots()) { sweepItemAll(r); }
                Activity a2 = activity;
                if (a2 != null) {
                    View dv = a2.getWindow().getDecorView();
                    quickRowSweep(dv);
                    floatBallSweep(dv);
                }
            } catch (Throwable ignored) {}
        }
    };

    /**
     * 取一个节点上的文案：优先用 Label 钩子登记过的（走 setText/setAttribute 的），
     * 没有就退回 TextView#getText() —— AJX 里还有 Html 等文本控件不走 Label 那条路，
     * 只认锚点表会漏（「我的」页红包答题卡就是这么漏掉的）。
     */
    private static String textAt(View v) {
        String t;
        synchronized (LOCK) { t = anchors.get(v); }
        if (t != null) return t;
        if (v instanceof TextView) {
            try {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) {
                    String s = cs.toString();
                    if (s.length() > 0 && s.length() <= 28) return norm(s);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 强制清除项判定：返回规则名，null = 不是。 */
    private static String junkRuleOf(String t) {
        if (t == null || t.length() == 0) return null;
        if (containsAny(t, ANCHOR_JUNK_FRIENDS)) return "junk_friends";
        if (containsAny(t, ANCHOR_JUNK_QUIZ)) return "junk_quiz";
        if (containsAny(t, ANCHOR_JUNK_TOP)) return "junk_top_banner";
        return null;
    }

    /**
     * 强制清除项兜底扫描。
     *
     * 为什么需要它：Label 钩子只覆盖走 setText / setAttribute("text") 的文本。
     * AJX 里还有 Html 之类的文本控件（真机 TreeDump 里见过 Html(0x0) 节点），
     * 「我的」页那张「一路封神 答题瓜分百万大奖」红包卡如果走的是它们，
     * 锚点表里就永远没有这几个字，规则自然打不到。
     *
     * 做法：只扫**已认领的**首页 /「我的」页列表子树（范围小、且绝不会碰到别的页面），
     * 用 TextView#getText() 认字，命中就把该 TextView 所在 item 整个收掉。
     * 两个规则都找到后立即停手，不再扫。
     */
    private static void sweepJunk(View decor) {
        try {
            // ══ v1.0.9：拆掉「找到一次就收手」的闩 ══
            // 旧版一旦 junkFound 里记下两个规则就直接 return —— 可是 AJX 会把卡片
            // **整棵重建**（新的 View 实例，WeakHashMap 里的旧记录早没了），
            // 于是重新冒出来的那张红包卡再也盖不住。用户实测复现的正是这个。
            // 现在：每次都扫，且**从 DecorView 全树扫**（不再依赖 homeLists 认领 ——
            // 这两块卡片的文案足够独特，误伤风险为零）。
            List<View> roots = new ArrayList<>();
            roots.add(decor);
            synchronized (LOCK) { roots.addAll(homeLists.keySet()); }
            for (int i = 0; i < roots.size(); i++) {
                View root = roots.get(i);
                if (root == null || root.getParent() == null) continue;
                List<View> stack = new ArrayList<>();
                stack.add(root);
                int guard = 0;
                while (!stack.isEmpty() && guard++ < 15000) {
                    View v = stack.remove(stack.size() - 1);
                    String t = textAt(v);
                    if (t == null) t = cdOf(v);
                    {
                        String rule = junkRuleOf(t);
                        if (rule != null && !hiddenWhy.containsKey(v)) {
                            H.log(Log.INFO, MainHook.TAG, "JUNK-HIT " + rule + " '" + t + "'");
                            collapseHost(v, rule);
                        }
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) v;
                        for (int k = 0; k < g.getChildCount(); k++) stack.add(g.getChildAt(k));
                    }
                }
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "sweepJunk err " + t);
        }
    }

    /** 规则开关：无开关的强制清除项永远为「关」。 */
    private static boolean ruleEnabled(String rule) {
        if (rule == null) return true;
        if (ALWAYS_OFF.contains(rule)) return false;
        return cfgOn(rule);
    }

    // ══════════════════════════════════════════════════════════ 工具宫格

    private static void applyTools(List<View> views, List<String> texts) {
        Set<ViewGroup> dirtyRows = new LinkedHashSet<>();
        for (int i = 0; i < views.size(); i++) {
            String t = texts.get(i);
            String toolKey = TOOL_ALIAS.get(t);
            if (toolKey == null) continue;
            View anchor = views.get(i);
            // 只认首页工具宫格：别的页面（路线规划页顶部的 驾车/打车/顺风车）不碰
            if (!inHomeScope(anchor) || !isToolGridCell(anchor)) continue;
            View cell = ascendSmallCell(anchor);
            if (cell == null) continue;          // 还没挂载：留在常驻索引里，下一轮再说
            String key = cellKey.get(cell);
            if (key == null) { key = toolKey; cellKey.put(cell, key); }
            // 记下该格当前显示的 Label：优先保留"真的画出来了"（width>0）的那个实例
            java.lang.ref.WeakReference<View> prev = cellLabel.get(cell);
            View prevV = prev == null ? null : prev.get();
            if (prevV == null || (prevV.getWidth() <= 0 && anchor.getWidth() > 0)) {
                cellLabel.put(cell, new java.lang.ref.WeakReference<>(anchor));
            }
            protectTool(cell, key);

            // 扩展页整排单独控制（默认隐藏）：不看单个工具的开头，只看扩展页开关
            if (TOOL_EXTRA_LABELS.contains(t) && !cfgExtraPage()) {
                hideCell(cell, "extra:" + t, dirtyRows);
                continue;
            }

            if (TOOL_KEY_FORCE_HIDE.contains(key)) {
                hideCell(cell, "key:" + key, dirtyRows);
                continue;
            }
            if (cfgOn(Config.K_TOOL_PREFIX + key)) continue;

            hideCell(cell, key, dirtyRows);
        }
        // 宫格级"补空"：只在**同一行内**把可见格左对齐压实，绝不换父。
        // 换父（removeView/addView 把第 3 行的格搬进第 1 行）实测会被 AJX 的
        // 自有模型下次布局时覆盖，两套几何叠在一起 → 标签重叠错乱。
        // 该功能需要改 AJX 的数据层才能真正实现，属于后续工作，这里不做。
        Set<ViewGroup> grids = new LinkedHashSet<>();
        for (ViewGroup row : dirtyRows) {
            packRowOrGrid(row);
            ViewParent p = row.getParent();
            if (p instanceof ViewGroup) grids.add((ViewGroup) p);
        }
        for (ViewGroup grid : grids) {
            for (int i = 0; i < grid.getChildCount(); i++) {
                View c = grid.getChildAt(i);
                if (c instanceof ViewGroup) {
                    ViewGroup row = (ViewGroup) c;
                    if (row.getChildCount() >= 3) {
                        packRowOrGrid(row);
                        squashRowIfEmpty(row);
                    }
                }
            }
        }
    }

    /** 幽灵格重申表（alpha/触摸/子树），AJX 重建后逐轮按回 */
    private static final List<java.lang.ref.WeakReference<View>> ghostCells = new ArrayList<>();
    private static final WeakHashMap<View, Boolean> ghostSeen = new WeakHashMap<>();

    /** 吞触摸监听（单例）：幽灵格点上去 = 点空气 */
    private static final View.OnTouchListener GHOST_TOUCH = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, android.view.MotionEvent event) { return true; }
    };

    /**
     * ══ v1.1.4：工具格隐藏的唯一正道 —— 幽灵格 ══
     *
     * GONE 会引发两个死症（真机实证）：
     *  · AJX 自定义容器的命中检测不查可见性 → 已隐藏格的触摸区还在，
     *    点空白直接进入出行节等页面（幽灵触摸）；
     *  · 模型发现子树消失会重建/复活，与我们的隐藏互相拉锯 → 乱闪。
     *
     * 幽灵格三件套：
     *  · 保持 VISIBLE —— AJX 模型无感，不重建、不复活、不闪；
     *  · alpha 0 + 子树 GONE —— 绘制完全不可见；
     *  · OnTouchListener 返回 true —— 触摸当场吞掉，点击 = 点空气。
     */
    private static void ghostCell(View cell, String id, Set<ViewGroup> dirtyRows) {
        if (cell.getVisibility() != View.VISIBLE) cell.setVisibility(View.VISIBLE);
        if (cell.getAlpha() != 0f) cell.setAlpha(0f);
        try {
            cell.setClickable(true);
            cell.setOnTouchListener(GHOST_TOUCH);
        } catch (Throwable ignored) {}
        if (cell instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) cell;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != View.GONE) c.setVisibility(View.GONE);
            }
        }
        if (!ghostSeen.containsKey(cell)) {
            ghostSeen.put(cell, Boolean.TRUE);
            ghostCells.add(new java.lang.ref.WeakReference<>(cell));
            if (loggedOnce.add("tool_" + id)) {
                H.log(Log.INFO, MainHook.TAG, "TOOL-GHOST " + id + " " + geom(cell));
            }
        }
        ViewParent p = cell.getParent();
        if (dirtyRows != null && p instanceof ViewGroup) dirtyRows.add((ViewGroup) p);
    }

    /** 隐藏一个工具格并登记该行待重排 */
    private static void hideCell(View cell, String id, Set<ViewGroup> dirtyRows) {
        ghostCell(cell, id, dirtyRows);
    }

    /** 格子 + 其行 + 宫格登记为受保护，杜绝被推荐区锚点顺手抹掉。 */
    private static void protectTool(View cell, String key) {
        protectedNodes.put(cell, Boolean.TRUE);
        ViewParent p = cell.getParent();
        for (int i = 0; i < 2 && p instanceof View; i++) {
            View v = (View) p;
            protectedNodes.put(v, Boolean.TRUE);
            p = v.getParent();
        }
        if (loggedOnce.add("key_" + key)) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-KEY " + key + " " + geom(cell));
        }
    }

    /**
     * 行内补空：把这一行的可见格子按原始槽位几何左对齐压实。
     *
     * 只做行内平移，**绝不换父**。曾经尝试跨行把 `更多工具`（宫格第 3 行）
     * 搬进第 1 行空出来的槽位，实测会被 AJX 自有模型的下一次布局覆盖，
     * 两套几何叠在一起 → 标签重叠错乱。跨行重排必须改 AJX 数据层，
     * 不属于 View 层能稳定做到的事，因此这里不做。
     *
     * 行内平移是安全的：只改 left，槽位间距 pitch 与格子尺寸都不变。
     */
    private static void packRow(ViewGroup row) {
        if (row == null || row.getChildCount() <= 1) return;

        List<View> cells = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getWidth() > 0 && c.getHeight() > 0) cells.add(c);
        }
        if (cells.size() <= 1) return;
        Collections.sort(cells, new Comparator<View>() {
            @Override public int compare(View x, View y) { return Integer.compare(x.getLeft(), y.getLeft()); }
        });

        int originX = cells.get(0).getLeft();
        int pitch = cells.get(0).getWidth();
        int d = cells.get(1).getLeft() - originX;
        if (d > 0) pitch = d;
        if (pitch <= 0) return;

        // v1.1.2：layout() 直改几何 —— translation 平移绘制跟着走，但 AJX 容器的
        // 触摸分发手算原始坐标（不走 framework 的 transformed hit-test），
        // 实测「更多工具画在别处、点下去没反应」。回到 layout()，配合 tick 级
        // repackGrids（AJX 每次复位 ≤100ms 内被按回，肉眼无感）。
        int slot = 0, moved = 0;
        for (View c : cells) {
            if (c.getVisibility() == View.GONE) continue;
            int nl = originX + slot * pitch;
            if (c.getLeft() != nl || c.getTop() != cells.get(0).getTop()
                    || c.getWidth() != cells.get(0).getWidth()
                    || c.getHeight() != cells.get(0).getHeight()) {
                if (!ajxFighting(c, nl, cells.get(0).getTop())) {
                    c.layout(nl, cells.get(0).getTop(),
                            nl + cells.get(0).getWidth(),
                            cells.get(0).getTop() + cells.get(0).getHeight());
                    moved++;
                }
            } else {
                laidAt.remove(c);
            }
            slot++;
        }
        if (moved > 0 && loggedOnce.add("rowpack_" + System.identityHashCode(row))) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-REPACK " + geom(row)
                    + " cells=" + cells.size() + " moved=" + moved + " pitch=" + pitch);
        }
    }

    /**
     * 这个格子是否真的在显示：以该格自己 Label 的实测宽度为准。
     * 轮播格里没轮到显示的那些 Label 是 0x0 —— 这是唯一能区分
     * "View 层 VISIBLE 但其实没画出来"的信号。
     */
    private static boolean isDisplayed(View cell) {
        java.lang.ref.WeakReference<View> r = cellLabel.get(cell);
        if (r == null) return true;
        View lb = r.get();
        if (lb == null) return true;
        return lb.getWidth() > 0;
    }

    /**
     * 一行全空才收行：GONE **并且**把高度压成 0。
     * 只 GONE 不收高度的话，AJX 的宫格容器仍按模型留出那一行的空白
     * —— 这就是"剩下两个工具下面一大片空"的原因。宫格只有全空才收。
     */
    private static void squashRowIfEmpty(ViewGroup row) {
        int visible = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            // ghost 格（alpha 0）不算可见 —— 全隐藏的行仍然要收掉
            if (c.getVisibility() != View.GONE && c.getAlpha() > 0f
                    && c.getWidth() > 0 && c.getHeight() > 0) visible++;
        }
        if (visible > 0) return;

        if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            row.setLayoutParams(lp);
        }
        squashedRows.add(new java.lang.ref.WeakReference<>(row));
        if (loggedOnce.add("emptyrow_" + System.identityHashCode(row))) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-ROW-EMPTY " + geom(row));
        }

        ViewParent sp = row.getParent();
        if (!(sp instanceof ViewGroup)) return;
        ViewGroup grid = (ViewGroup) sp;
        int gv = 0;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c.getVisibility() != View.GONE && c.getWidth() > 0 && c.getHeight() > 0) gv++;
        }
        if (gv == 0 && grid.getVisibility() != View.GONE) {
            grid.setVisibility(View.GONE);
            if (loggedOnce.add("emptygrid_" + System.identityHashCode(grid))) {
                H.log(Log.INFO, MainHook.TAG, "TOOL-GRID-EMPTY " + geom(grid));
            }
        }
    }

    /** 上溯到工具格子：连续满足 宽≤300 且 高≤300 的最外层祖先，且其父为宽≥600 的容器。 */
    /** 已做过跨行重排的宫格（tick 里低频复排，防 AJX 重建后回跳） */
    private static final java.util.WeakHashMap<View, Boolean> claimedGrids =
            new java.util.WeakHashMap<>();

    /** 每宫格重排节流（400ms 内只排一次，防逐帧拉锯） */
    private static final java.util.WeakHashMap<View, Long> packedAt =
            new java.util.WeakHashMap<>();
    /** 我们摆过的目标位：cell -> {nl, nt} —— 用于检测 AJX 是否又拖回去了 */
    private static final java.util.WeakHashMap<View, long[]> laidAt =
            new java.util.WeakHashMap<>();
    /** 拉锯投降名单：摆过去又被 AJX 拖回的格子，永不再动（宁可不排，不可乱闪） */
    private static final java.util.WeakHashMap<View, Boolean> surrendered =
            new java.util.WeakHashMap<>();

    /** AJX 拉锯检测：同一目标位我们摆过、它又跑回去 → 投降，这格永不再动 */
    private static boolean ajxFighting(View c, int nl, int nt) {
        long[] prev = laidAt.get(c);
        if (prev != null && prev[0] == nl && prev[1] == nt) {
            surrendered.put(c, Boolean.TRUE);
            laidAt.remove(c);
            if (loggedOnce.add("surrender_" + System.identityHashCode(c))) {
                H.log(Log.INFO, MainHook.TAG, "GRID-SURRENDER " + geom(c));
            }
            return true;
        }
        laidAt.put(c, new long[]{nl, nt});
        return false;
    }

    /** 子树里任何节点的 contentDescription 命中 TOOL_FORCE_HIDE（出行节 CD 常挂在深层） */
    private static boolean subtreeForceHide(View root) {
        List<View> st = new ArrayList<>();
        st.add(root);
        int guard = 0;
        while (!st.isEmpty() && guard++ < 24) {
            View v = st.remove(st.size() - 1);
            String cd = cdOf(v);
            if (cd != null && TOOL_FORCE_HIDE.contains(cd)) return true;
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount() && i < 6; i++) st.add(vg.getChildAt(i));
            }
        }
        return false;
    }

    /** 宫格结构判据：≥2 行、每行 ≥3 格（首页工具宫格独有） */
    private static boolean isToolGrid(ViewGroup g) {
        int rows = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() >= 3) rows++;
        }
        return rows >= 2;
    }

    /** 行若属于工具宫格 → 跨行重排；否则行内补齐 */
    private static void packRowOrGrid(ViewGroup row) {
        if (row == null) return;
        ViewParent p = row.getParent();
        if (p instanceof ViewGroup && isToolGrid((ViewGroup) p)) {
            packGrid((ViewGroup) p);
        } else {
            packRow(row);
        }
    }

    /** tick 驱动的宫格复排：AJX 重建 / 下拉刷新 / 轮播翻页后恢复排布 */
    private static void repackGrids() {
        try {
            List<View> gs = new ArrayList<>(claimedGrids.keySet());
            for (View g : gs) {
                if (!(g instanceof ViewGroup) || g.getParent() == null) continue;
                ViewGroup grid = (ViewGroup) g;
                packGrid(grid);
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View c = grid.getChildAt(i);
                    if (c instanceof ViewGroup) squashRowIfEmpty((ViewGroup) c);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * v1.1.1 跨行 row-major 紧凑重排。可见格按行序串起来依次占槽
     * （第 1 行摆满再第 2 行），跨行格用 translation 平移；不换父、不改 left/top
     * —— AJX 模型复位槽位不影响绘制位置（根治闪动回跳）。
     */
    private static void packGrid(ViewGroup grid) {
        if (grid == null) return;
        if (!cfgOn(Config.K_TOOL_SORT)) return;   // 用户关掉自动排序 → 一个像素都不动
        long now = System.currentTimeMillis();
        Long pa = packedAt.get(grid);
        if (pa != null && now - pa < 400) return;   // 节流：400ms 一次，杜绝逐帧拉锯
        packedAt.put(grid, now);
        claimedGrids.put(grid, Boolean.TRUE);
        List<ViewGroup> rows = new ArrayList<>();
        List<List<View>> rowCells = new ArrayList<>();
        int cols = 0;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (!(c instanceof ViewGroup)) continue;
            ViewGroup row = (ViewGroup) c;
            rows.add(row);
            List<View> cells = new ArrayList<>();
            // ══ 轮播格去重：同一行内同一工具键可能出现两个实例（轮播双 Label），
            // 只保留「真的画出来了」的（isDisplayed 以该格 Label 实测宽度为准），
            // 未显示的实例 GONE 掉 —— 否则 packGrid 平移后两份都可见（实测：更多工具×2）。
            java.util.Set<String> seenKeys = new HashSet<>();
            for (int k = 0; k < row.getChildCount(); k++) {
                View g = row.getChildAt(k);
                if (g.getVisibility() == View.GONE) continue;
                if (surrendered.containsKey(g)) continue;   // 拉锯投降格：不参与排布
                if (g.getWidth() <= 0 || g.getHeight() <= 0) continue;
                String ck = cellKey.get(g);
                if (ck != null) seenKeys.add(ck);   // 仅登记；宽0实例本就不显示，不再 GONE（防误杀更多工具）
                // 出行节类强制清除格：子树 CD 兜底再认一遍，防轮播第二页漏网
                if (subtreeForceHide(g)) {
                    ghostCell(g, "cd:" + cdOf(g), null);
                    continue;
                }
                cells.add(g);
            }
            Collections.sort(cells, new Comparator<View>() {
                @Override public int compare(View x, View y) { return Integer.compare(x.getLeft(), y.getLeft()); }
            });
            rowCells.add(cells);
            cols = Math.max(cols, row.getChildCount());
        }
        if (rows.size() < 2 || cols < 3) return;
        int originX = Integer.MAX_VALUE, row0Top = Integer.MAX_VALUE;
        int pitch = 0;
        for (List<View> rc : rowCells) {
            if (rc.isEmpty()) continue;
            View fc = rc.get(0);
            if (fc.getLeft() < originX) originX = fc.getLeft();
            if (fc.getTop() < row0Top) row0Top = fc.getTop();
            if (rc.size() >= 2 && pitch == 0) {
                int d = rc.get(1).getLeft() - fc.getLeft();
                if (d > 0) pitch = d;
            }
        }
        if (originX == Integer.MAX_VALUE || pitch <= 0) return;
        int slot = 0, moved = 0;
        for (int ri = 0; ri < rows.size(); ri++) {
            List<View> rc = rowCells.get(ri);
            for (int ci = 0; ci < rc.size(); ci++) {
                View c = rc.get(ci);
                int tr = slot / cols, tcol = slot % cols;
                if (tr >= rows.size()) tr = rows.size() - 1;
                List<View> tCells = rowCells.get(tr);
                // ══ 坐标系修正（v1.1.2）：getTop() 是相对各自父行的，跨行搬运时
                // 必须补上「目标行 View 与源行 View 的 top 差」，否则 ty 算错、
                // 格子飘到错误行（实测：更多工具停在第 2 行不上去）。
                int rowTopDelta = 0;
                if (tr != ri && !tCells.isEmpty()) {
                    rowTopDelta = rows.get(tr).getTop() - rows.get(ri).getTop();
                    rowTopDelta += tCells.get(0).getTop() - rc.get(0).getTop();
                }
                // v1.1.2：layout() 直改（触摸必须跟随）；拉锯格投降不再动
                int nl = originX + tcol * pitch;
                int nt = (tCells.isEmpty() ? c.getTop() : tCells.get(0).getTop()) + rowTopDelta;
                slot++;
                if (c.getLeft() != nl || c.getTop() != nt) {
                    if (!ajxFighting(c, nl, nt)) {
                        c.layout(nl, nt, nl + c.getWidth(), nt + c.getHeight());
                        moved++;
                    }
                } else {
                    laidAt.remove(c);   // 已就位：清记忆，下次被拖走再重新判
                }
            }
        }
        for (int ri = 0; ri < rows.size(); ri++) {
            if (!rowCells.get(ri).isEmpty()) continue;
            ViewGroup row = rows.get(ri);
            if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp != null && lp.height != 0) { lp.height = 0; row.setLayoutParams(lp); }
        }
        if (moved > 0) {
            H.log(Log.INFO, MainHook.TAG, "GRID-PACK rows=" + rows.size()
                    + " cols=" + cols + " moved=" + moved);
        }
    }

    private static View ascendSmallCell(View v) {
        View cur = v, best = null;
        for (int i = 0; i < 8 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            int w = p.getWidth(), h = p.getHeight();
            if (w <= 0 || h <= 0 || w > 300 || h > 300) break;
            best = p;
            cur = p;
        }
        if (best == null) return null;
        ViewParent pp = best.getParent();
        if (!(pp instanceof View) || ((View) pp).getWidth() < 600) return null;
        return best;
    }

    // ══════════════════════════════════════════════════════════ 标签栏（原生）

    private static void applyTabs(View root) {
        try {
            ViewGroup row = tabRow;
            if (row == null || row.getParent() == null) {
                row = findTabRow(root, 0);
                tabRow = row;
            }
            if (row == null || row.getChildCount() <= 1) return;

            int visible = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                String name = tabName(c);
                if (name == null) { visible++; continue; }
                if (!cfgOn(Config.K_TAB_PREFIX + name)) {
                    if (c.getVisibility() != View.GONE) {
                        c.setVisibility(View.GONE);
                        hiddenCells.add(new java.lang.ref.WeakReference<>(c));
                        if (loggedOnce.add("tab_" + name)) {
                            H.log(Log.INFO, MainHook.TAG, "TAB-HIDE " + name);
                        }
                    }
                } else {
                    if (c.getVisibility() == View.GONE) c.setVisibility(View.VISIBLE);
                    visible++;
                }
            }
            if (visible == 0) return;   // 全关也不收条，避免只剩 0 宽的残条

            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                if (!(c.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams)) continue;
                android.widget.LinearLayout.LayoutParams lp =
                        (android.widget.LinearLayout.LayoutParams) c.getLayoutParams();
                if (lp.width != 0 || lp.weight != 1f) {
                    lp.width = 0;
                    lp.weight = 1f;
                    c.setLayoutParams(lp);
                }
                stretchTabBackground(c);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 让「悬浮胶囊」（选中态那层圆角背景）跟着槽位一起长。
     *
     * ══ 为什么这是「删了标签，悬浮栏没被占满」的正解（真机实测几何）══
     * 标签栏实测结构：
     *   LiteTabBar(1080)
     *     └ DtLinearLayout(1006, 左右各 37 内边距)
     *         ├ TabItemLayoutV2(0..503)   ← 首页
     *         │   ├ DtRelativeLayout(151,11 201x134) id=tab_bg_layer   ← 悬浮胶囊
     *         │   ├ DtFrameLayout(216,23 71x71)        id=icon_img
     *         │   └ DtTextView(225,97 52x36)           id=tab_name_v2
     *         └ TabItemLayoutV2(503..1006) ← 我的
     *
     * 原版 5 个 tab：槽位 = 1006/5 = 201.2，胶囊固定 201 —— **胶囊本来就等于槽位宽**。
     * 删掉 3 个 tab 后槽位变成 503，胶囊却还是 201（居中摆着），于是两边各空出一大块，
     * 看起来就是「悬浮栏没被占满」。
     *
     * 修法：槽位明显宽于胶囊原始宽度时，把胶囊改成 MATCH_PARENT —— 它随即等于槽位宽，
     * 与 5 tab 时的比例完全一致（201/201.2 ≈ 100%）。MATCH_PARENT 天生自适应：
     * 用户把 tab 打开回来，槽位缩回 201，胶囊跟着缩回 201，无需额外还原逻辑。
     * 原版状态下槽位 ≈ 胶囊宽，判据不成立 → 一个像素都不动，不改变原版观感。
     */
    private static void stretchTabBackground(View item) {
        if (!(item instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) item;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getId() == View.NO_ID || c.getId() == 0) continue;
            String entry = null;
            try { entry = c.getResources().getResourceEntryName(c.getId()); }
            catch (Throwable ignored) { continue; }
            if (!"tab_bg_layer".equals(entry)) continue;
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp == null || lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return;
            int slotW = item.getWidth();
            int bgW = c.getWidth();
            if (slotW <= 0 || bgW <= 0) return;          // 还没量过 → 下一轮再说
            if (slotW <= bgW + bgW / 8) return;          // 原版比例 → 不动
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            c.setLayoutParams(lp);
            if (loggedOnce.add("tabpill_" + System.identityHashCode(c))) {
                H.log(Log.INFO, MainHook.TAG, "TAB-PILL stretch " + bgW + " -> " + slotW);
            }
            return;
        }
    }

    private static ViewGroup findTabRow(View v, int depth) {
        if (v == null || depth > 26) return null;
        if (v.getClass().getName().endsWith(".TabItemLayoutV2")) {
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) return (ViewGroup) p;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup r = findTabRow(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String tabName(View node) {
        if (node instanceof TextView) {
            CharSequence cs = ((TextView) node).getText();
            if (cs != null && TAB_LABELS.contains(cs.toString())) return cs.toString();
        }
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0; i < g.getChildCount(); i++) {
                String n = tabName(g.getChildAt(i));
                if (n != null) return n;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════ 宿主 item 收缩

    /**
     * 从锚点上溯到 AJX 列表 item（父级是 AjxList2/RecyclerView 的那个节点）并收缩。
     * 三重保险：
     *  - 爬升路径穿过受保护节点（工具格/行/宫格）→ 立即放弃（这是"宫格整体消失"的解药）；
     *  - item 高 > 55% 屏高 → 放弃（页面级容器，误伤即整页消失）；
     *  - 找不到列表宿主 → 放弃，留在常驻索引里等下一轮挂载后再试。
     * 绝不盲目 ascendWideRow —— 那是"我的页整体被关闭"的元凶。
     */
    private static void collapseHost(View anchor, String rule) {
        View cur = anchor;
        View item = null;
        boolean touchedProtected = protectedNodes.containsKey(cur);
        for (int i = 0; i < 40 && cur.getParent() instanceof View; i++) {
            View parent = (View) cur.getParent();
            if (protectedNodes.containsKey(parent)) touchedProtected = true;
            if (isList(parent)) { item = cur; break; }
            cur = parent;
        }
        if (item != null) {
            View list = (View) item.getParent();
            dumpListOnce(list);
            hookListAdapter(list);
        }
        if (item == null) {                             // 还没挂载 → 下一轮重试
            if (loggedOnce.add("nolist_" + rule)) {
                H.log(Log.INFO, MainHook.TAG, "NO-LIST " + rule
                        + " attached=" + (anchor.getParent() != null)
                        + " deepest=" + shortName(cur) + " " + geom(cur));
            }
            // ══ v1.1.7：目的地/路线详情页不是 RecyclerView —— 标题所在板块
            // 挂在普通滚动容器里，永远找不到列表宿主。对跨页规则走「区块兜底」：
            // 从锚点（板块标题）上溯到宽≥60%屏、高≤80%屏的最近祖先 = 板块容器。
            if (Config.K_FEED_BOARD.equals(rule) || ALWAYS_OFF.contains(rule)) {
                collapseBlock(anchor, rule);
            }
            return;
        }
        if (touchedProtected) {
            if (loggedOnce.add("skip_" + rule)) {
                H.log(Log.INFO, MainHook.TAG, "SKIP(protected) " + rule);
            }
            return;
        }
        if (hiddenWhy.containsKey(item)) return;        // 幂等

        int screenH = screenOf(item);
        int h = item.getHeight();
        if (h > screenH * 45 / 100) {
            if (loggedOnce.add("toolarge_" + rule)) {
                H.log(Log.INFO, MainHook.TAG, "SKIP(toolarge " + h + ") " + rule);
            }
            return;
        }
        if (item.getVisibility() != View.GONE) item.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp != null && lp.height != 0 && h > 0) {
            lp.height = 0;
            item.setLayoutParams(lp);
        }
        hiddenItems.add(new java.lang.ref.WeakReference<>(item));
        hiddenWhy.put(item, rule);
        H.log(Log.INFO, MainHook.TAG, "COLLAPSE " + rule + " " + shortName(item) + " " + geom(item));
    }

    /**
     * v1.1.7 区块兜底：非列表页面（目的地 / 路线详情）的板块删除。
     * 从板块标题上溯，取最后一个「宽 ≥60% 屏 且 高 ≤80% 屏」的祖先 = 板块容器，
     * 整块 GONE + 高度归零。页面根（全屏高）会被高度上限拦住，不会误伤整页。
     */
    private static void collapseBlock(View anchor, String rule) {
        try {
            View cur = anchor;
            View best = null;
            View rt = anchor;
            ViewParent pp;
            while ((pp = rt.getParent()) instanceof View) rt = (View) pp;
            int sw = rt.getWidth() > 0 ? rt.getWidth() : 1080;
            int sh = rt.getHeight() > 0 ? rt.getHeight() : 2400;
            for (int i = 0; i < 10 && cur.getParent() instanceof View; i++) {
                View p = (View) cur.getParent();
                int w = p.getWidth(), h = p.getHeight();
                if (w <= 0 || h <= 0) break;
                if (h > sh * 80 / 100) break;                 // 页面级容器 → 停
                if (w >= sw * 60 / 100 && h >= 150) best = p; // 板块容器候选
                cur = p;
            }
            if (best == null || best.getParent() == null) return;
            if (hiddenWhy.containsKey(best)) return;
            if (best.getVisibility() != View.GONE) best.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = best.getLayoutParams();
            if (lp != null && lp.height != 0 && best.getHeight() > 0) {
                lp.height = 0;
                best.setLayoutParams(lp);
            }
            hiddenItems.add(new java.lang.ref.WeakReference<>(best));
            hiddenWhy.put(best, rule);
            H.log(Log.INFO, MainHook.TAG, "BLOCK-COLLAPSE " + rule + " "
                    + shortName(best) + " " + geom(best));
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "collapseBlock err " + t);
        }
    }

    /**
     * 取证：把一个 AJX 列表的适配器结构打出来，用来找"信息流条目"的原始数据，
     * 目标是以后能在数据层直接过滤，而不是等 AJX 把卡片渲染出来再删。
     */
    private static final Set<Integer> dumpedLists = new HashSet<>();

    private static void dumpListOnce(View list) {
        if (!Config.debugLog() || list == null) return;
        if (!dumpedLists.add(System.identityHashCode(list))) return;
        try {
            H.log(Log.INFO, MainHook.TAG, "LIST " + list.getClass().getName() + " " + geom(list));
            Method getAdapter = null;
            for (Class<?> k = list.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try { getAdapter = k.getDeclaredMethod("getAdapter"); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (getAdapter == null) { H.log(Log.INFO, MainHook.TAG, "LIST no getAdapter"); return; }
            getAdapter.setAccessible(true);
            Object ad = getAdapter.invoke(list);
            if (ad == null) { H.log(Log.INFO, MainHook.TAG, "LIST adapter=null"); return; }
            H.log(Log.INFO, MainHook.TAG, "LIST adapter=" + ad.getClass().getName());
            for (Class<?> k = ad.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || f.getType().isArray()) continue;
                    H.log(Log.INFO, MainHook.TAG, "LIST  field " + k.getSimpleName()
                            + "." + f.getName() + " : " + f.getType().getName());
                }
            }
            // 数据入口侦察：AJX 列表的数据由 JS 侧灌入，native 侧只可能是
            // BaseList2Adapter / 其子类上的某个 setData 类方法。把方法表打出来定位它。
            for (Class<?> k = ad.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                String sn = k.getSimpleName();
                if (sn.equals("Adapter") || sn.startsWith("RecyclerView")) break;
                for (Method m : k.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 0 || ps.length > 4) continue;
                    StringBuilder sb = new StringBuilder();
                    for (Class<?> x : ps) sb.append(x.getSimpleName()).append(',');
                    H.log(Log.INFO, MainHook.TAG, "LIST  method " + sn + "."
                            + m.getName() + "(" + sb + ") -> "
                            + m.getReturnType().getSimpleName());
                }
            }
            try {
                Object n = ad.getClass().getMethod("getItemCount").invoke(ad);
                H.log(Log.INFO, MainHook.TAG, "LIST itemCount=" + n);
            } catch (Throwable ignored) {}
            // 数据层侦察：把适配器实例上每个非原始字段的"值"也打出来，
            // 找出信息流条目的原始数据容器（目标是从源头过滤，而不是渲染后再删）。
            for (Class<?> k = ad.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(ad);
                        String s = describe(val);
                        if (s == null) continue;
                        H.log(Log.INFO, MainHook.TAG, "LIST  value " + k.getSimpleName()
                                + "." + f.getName() + " = " + s);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "LIST dump err " + t);
        }
    }

    /** 把字段值描述成短字符串；对集合额外报告元素个数与首元素类型/内容 */
    private static String describe(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) v;
                StringBuilder sb = new StringBuilder("Map(size=" + m.size() + ")");
                int i = 0;
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (i++ >= 3) break;
                    sb.append(" [").append(trunc(String.valueOf(e.getKey())))
                      .append(" -> ").append(describeBrief(e.getValue())).append(']');
                }
                return sb.toString();
            }
            if (v instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) v;
                StringBuilder sb = new StringBuilder("List(size=" + l.size() + ")");
                for (int i = 0; i < Math.min(3, l.size()); i++) {
                    sb.append(" <").append(describeBrief(l.get(i))).append('>');
                }
                return sb.toString();
            }
            String cn = v.getClass().getName();
            if (cn.startsWith("java.lang") || cn.startsWith("android.")) return null;
            return trunc(cn + " :: " + String.valueOf(v));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describeBrief(Object o) {
        if (o == null) return "null";
        return trunc(o.getClass().getSimpleName() + "=" + String.valueOf(o));
    }

    private static String trunc(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 90 ? s.substring(0, 90) + "~" : s;
    }

    /**
     * 结构识别「推荐频道栏」：一个 HorizontalScroller，其内容容器里全是矮而窄的
     * chip（实测 84~168 宽 × 110 高，至少 3 个）。
     * 依据来自实测：同页面"运营位横向滚动条"的内容格是 247x210，
     * 高度 210 直接落在 chip 高度带之外，所以不会误伤。
     */
    private static View findChannelBar(View v, int depth) {
        if (v == null || depth > 26) return null;
        if (v.getClass().getName().endsWith("HorizontalScroller") && v instanceof ViewGroup) {
            ViewGroup sc = (ViewGroup) v;
            if (sc.getChildCount() >= 1 && sc.getChildAt(0) instanceof ViewGroup) {
                ViewGroup content = (ViewGroup) sc.getChildAt(0);
                int chips = 0;
                boolean ok = true;
                for (int i = 0; i < content.getChildCount(); i++) {
                    View c = content.getChildAt(i);
                    int h = c.getHeight(), w = c.getWidth();
                    if (h <= 0 || w <= 0) continue;
                    if (h >= 60 && h <= 170 && w <= 250) { chips++; continue; }
                    // 下划线指示条之类的装饰（实测 53x11）直接忽略，
                    // 只有"明显不是 chip"的大块内容才否决整条
                    if (h > 200 || w > 300) { ok = false; break; }
                }
                if (ok && chips >= 3 && inHomeScope(sc)) {
                    H.log(Log.INFO, MainHook.TAG, "CHANNEL-BAR found chips=" + chips
                            + " " + geom(sc));
                    return v;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findChannelBar(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * 根治向：把 AJX 列表适配器的 onBindViewHolder 挂上，在**同一个布局帧内**判定并隐藏。
     *
     * 为什么这是能做到的最接近"从源头阻断"的做法：
     *  - 实测 AJX 列表适配器是 `...ajx3.widget.view.list.a`（混淆），字段只有
     *    IAjxContext / zr / nv0 —— **native 侧根本不存在条目数据列表**，
     *    数据在 JS 引擎里，Java 层没有可过滤的数据结构；
     *  - 但它的方法表是 section 化的（getSectionByPosition → ListSection、
     *    onBindViewHolder(v,int)），所以可以在**条目绑定完成的瞬间**
     *    （仍在本次 layout pass 内、这一帧还没绘制）就判定并 GONE。
     *  - 效果：卡片**一次都不会被画出来**，也不再依赖"下一帧再扫全树"。
     *
     * 只挂 App 自己的适配器类（不是框架超类），符合"禁止 hook 框架通用回调"的红线。
     */
    private static void hookListAdapter(View list) {
        try {
            Method getAdapter = null;
            for (Class<?> k = list.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try { getAdapter = k.getDeclaredMethod("getAdapter"); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (getAdapter == null) return;
            getAdapter.setAccessible(true);
            Object ad = getAdapter.invoke(list);
            if (ad == null) return;
            final Class<?> ac = ad.getClass();
            if (!bindHooked.add(ac.getName())) return;

            Method bind = null;
            for (Method m : ac.getDeclaredMethods()) {
                if (m.getName().equals("onBindViewHolder") && m.getParameterTypes().length == 2) {
                    bind = m;
                    break;
                }
            }
            if (bind == null) {
                H.log(Log.INFO, MainHook.TAG, "BIND hook miss " + ac.getName());
                return;
            }
            H.module.hook(bind).setId("amapenhancer_bind")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            View item = null;
                            try {
                                Object vh = chain.getArg(0);
                                if (vh != null) {
                                    item = (View) vh.getClass().getField("itemView").get(vh);
                                }
                            } catch (Throwable ignored) {}
                            // ★★ 反闪：**先挂 INVISIBLE 再让它绑** ★★
                            //
                            // AJX 的文字是 onBindViewHolder 返回**之后**才写进去的，
                            // 所以当场判不出来 —— 旧实现只能等下一帧 preDraw 再判，
                            // 那一帧广告已经被画出来了（用户看到的「先亮一下再消失」）。
                            //
                            // 现在：绑定前先 INVISIBLE，绑完在 preDraw 里判 ——
                            //   · 判为广告 → GONE（一次都没画过）
                            //   · 判为正常 → VISIBLE（同样在**首帧绘制之前**恢复）
                            // 两种情况都发生在同一帧的 draw 之前，所以既不会闪，
                            // 正常卡片也不会被延迟显示。
                            if (item != null) {
                                try { item.setAlpha(0f); } catch (Throwable ignored) {}
                            }
                            Object r = chain.proceed();
                            if (item != null) onItemBound(item);
                            return r;
                        }
                    });
            H.log(Log.INFO, MainHook.TAG, "BIND hook on " + ac.getName());
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "BIND hook fail " + t);
        }
    }

    /**
     * 条目刚绑定完：在这一个小子树里找我们登记过的锚点文本，
     * 命中「已关闭」的规则就立刻把整个 item 收掉。
     * 含工具格的 item 一律放过（那是"宫格整体消失"的老坑）。
     *
     * 两个坑都在这儿补掉：
     *  1) 向下多翻几次冒出来的新帖，`onBindViewHolder` 返回时它的文字往往**还没写进去**
     *     （AJX 先绑视图再灌属性）→ 当场判定会判成"放行"。所以额外挂一个
     *     onPreDraw：每帧绘制前再判一次，文字一到就在**同一帧**被盖掉。
     *  2) 帖子标题是用户自由文案（"受累已回，说点 xhs 上没有的实话"），
     *     任何锚点词都匹配不到 → 靠**结构**兜底：半屏宽 + 高卡的 item 就是内容流帖子卡。
     */
    private static void onItemBound(final View item) {
        if (hiddenWhy.containsKey(item)) {
            if (item.getVisibility() != View.GONE) item.setVisibility(View.GONE);
            return;
        }
        // 兜底：万一这个条目一直没等到 preDraw（离屏 / 0 尺寸），
        // 400ms 后把不透明度恢复 —— 宁可显示，也绝不留一个永远看不见的卡片。
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (hiddenWhy.containsKey(item)) return;
                    if (item.getAlpha() < 1f) item.setAlpha(1f);
                } catch (Throwable ignored) {}
            }
        }, 400);
        // 结构类清除做在**绑定时、只扫这一个 item** —— 几百个节点、微秒级。
        // v1.0.11 是挂在 DecorView 的 onGlobalLayout 上跑全树 BFS，列表一滚动就每帧三趟，
        // 那是「非常卡」的根因。这里换成事件驱动 + 局部作用域。
        // 这里**不再**跑 sweepItemAll（900 节点 BFS）——
        // 强制清除项 / 工具格 / 快捷入口全部改成 onTextSet 里按文案即时处理，
        // 既更早（首帧之前）又便宜（每条文案 O(1) 判定 + 一次父链上溯）。
        String rule = ruleForItem(item);
        if (rule != null) { hideItem(item, rule); return; }
        // 当场没判出来（文字还没到）→ 挂 preDraw 再判，最多 12 帧
        try {
            item.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                private int n;
                @Override public boolean onPreDraw() {
                    try {
                        if (hiddenWhy.containsKey(item)) {
                            item.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        String r = ruleForItem(item);
                        if (r != null) {
                            hideItem(item, r);
                            item.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        // 无罪 → 在**本帧绘制之前**恢复不透明度
                        if (item.getAlpha() < 1f) {
                            item.setAlpha(1f);
                            item.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        if (++n > 12) item.getViewTreeObserver().removeOnPreDrawListener(this);
                    } catch (Throwable ignored) {}
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 判定一个 item 该不该收：先锚点文本，再不济按结构认「内容流帖子卡」 */
    private static String ruleForItem(View item) {
        List<View> stack = new ArrayList<>();
        stack.add(item);
        String hitRule = null;
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 400) {
            View v = stack.remove(stack.size() - 1);
            String text = textAt(v);
            if (text != null) {
                if (TOOL_ALIAS.containsKey(text)) return null;   // 工具格所在 item 绝不动
                if (hitRule == null) {
                    String rule = ruleFor(text, v);
                    // 强制清除项 / 榜单卡（跨页）无条件生效；其它规则须在已认领列表内
                    boolean crossPage = Config.K_FEED_BOARD.equals(rule);
                    if (rule != null && !ruleEnabled(rule)
                            && (ALWAYS_OFF.contains(rule) || crossPage || inHomeScope(item))) {
                        hitRule = rule;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        if (hitRule != null) return hitRule;

        // ══ v1.0.12：删掉「纯图运营大卡」形状兜底 ══
        // 这条规则两版都闯祸：v1.0.10 在绑定瞬间判（文字还没灌进去）→ 足迹/语音包/车标
        // 一起被删；v1.0.11 改成延迟复核仍然不够 —— AJX 的文字并不总是走 TextView，
        // `hasAnyText()` 对不少卡片恒为 false，结果「我的」页整页被删空。
        //
        // 结论：**形状不是证据**。红包答题卡只走文案这条路（Label / Html / RichText /
        // Text 四个类都已挂钩），认不到就不删 —— 宁可留一张卡，也不能删掉一整页。

        // —— 结构兜底 1：「纯图运营大卡」（v1.0.14，判据换成了结构而不是时机）——
        //
        // 真机 TreeDump 实证：「我的」页那张「一路封神 答题瓜分百万大奖」红包卡
        // 子树里 **一个 AJX Label 都没有** —— 整张就是图/SVG，没有任何文案可匹配。
        // 而足迹卡 / 语音包卡 / 车标卡都有 Label。
        //
        // 前两版之所以误杀，是因为用 `v instanceof TextView` 判「有没有文字」，
        // 而 AJX 的文本控件是 `ajx3.widget.view.Label`，**不是 TextView** ——
        // 于是对任何 AJX 卡片都恒为 false。现在判据换成「子树里有没有 AJX 文本控件」，
        // 这个跟绑定时机无关，是结构事实。
        if (inHomeScope(item) && looksLikeBanner(item)) {
            schedulePromoCheck(item);
        }

        // —— 结构兜底 2：内容流帖子卡 ——
        // 实测：双列布局，每张卡宽 ≈487~514（约屏幕宽的 45%），高 640~990。
        // 天气卡 487x518、语音包 493x252 都够不着高度线。
        // 注意：这里必须用**屏幕宽**，不能用 screenOf()（那个给的是根高度）。
        if (!cfgOn(Config.K_FEED_CONTENT) && inHomeScope(item)) {
            int w = item.getWidth(), h = item.getHeight();
            if (w > 0 && h > 0) {
                View root = item;
                ViewParent p;
                while ((p = root.getParent()) instanceof View) root = (View) p;
                int sw = root.getWidth() > 0 ? root.getWidth() : 1080;
                int sh = root.getHeight() > 0 ? root.getHeight() : 2400;
                if (w >= sw * 40 / 100 && w <= sw * 52 / 100 && h >= sh * 25 / 100) {
                    return Config.K_FEED_CONTENT + "#shape";
                }
            }
        }
        // ══ v1.1.9：feed_board 的结构兜底 —— 目的地/路线页的双列推荐板块
        //（标题不走文本钩子，卡片是唯一身份），按结构收整块
        if (!cfgOn(Config.K_FEED_BOARD) && item.getWidth() > 0 && item.getHeight() > 0) {
            int w = item.getWidth(), h = item.getHeight();
            View root = item;
            ViewParent p;
            while ((p = root.getParent()) instanceof View) root = (View) p;
            int sw = root.getWidth() > 0 ? root.getWidth() : 1080;
            int sh = root.getHeight() > 0 ? root.getHeight() : 2400;
            if (w >= sw * 88 / 100 && h >= 500 && h <= sh * 70 / 100) {
                return Config.K_FEED_BOARD + "#destshape";
            }
        }
        return null;
    }

    /** 已排过延迟复核的条目（避免重复排） */
    private static final WeakHashMap<View, Boolean> promoChecked = new WeakHashMap<>();

    /**
     * 子树里是否存在 **AJX 的文本控件**。
     *
     * AJX 的文本控件是 `com.autonavi.minimap.ajx3.widget.view.Label` / `Html` / `Text`，
     * **都不是 `android.widget.TextView`** —— 用 `instanceof TextView` 判会恒为 false，
     * 这正是前两版把「我的」页整片卡片误判成纯图卡、整页删空的根因。
     */
    private static boolean hasLabelNode(View root) {
        List<View> stack = new ArrayList<>();
        stack.add(root);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 200) {
            View v = stack.remove(stack.size() - 1);
            String cn = v.getClass().getName();
            if (cn.endsWith(".Label") || cn.endsWith(".Html") || cn.endsWith(".RichText")
                    || cn.endsWith(".Text") || v instanceof TextView) {
                return true;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        return false;
    }

    /** 子树里是否有图片控件（AJX 的图片是 `ajx3.widget.view.Image`）。 */
    private static boolean hasImage(View root) {
        List<View> stack = new ArrayList<>();
        stack.add(root);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 200) {
            View v = stack.remove(stack.size() - 1);
            if (v instanceof android.widget.ImageView) return true;
            if (v.getClass().getName().endsWith(".Image")) return true;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        return false;
    }

    /**
     * 形状判据：占满整宽（≥90% 屏宽）+ 矮（150~400px）+ 有图 + **一个文本控件都没有**。
     * 最后一条是关键 —— 足迹 / 语音包 / 车标都有 AJX Label，天然被排除。
     */
    private static boolean looksLikeBanner(View item) {
        int w = item.getWidth(), h = item.getHeight();
        if (w <= 0 || h < 150 || h > 400) return false;
        View rt = item;
        ViewParent p;
        while ((p = rt.getParent()) instanceof View) rt = (View) p;
        int sw = rt.getWidth() > 0 ? rt.getWidth() : 1080;
        if (w < sw * 90 / 100) return false;
        if (hasLabelNode(item)) return false;
        return hasImage(item);
    }

    /**
     * 延迟 1.5 秒复核：AJX 的子树是 onBindViewHolder 返回之后才填满的，
     * 绑定时就判结构必然误判。等布局稳定后再看一眼，仍然符合才收掉。
     */
    private static void schedulePromoCheck(final View item) {
        if (promoChecked.containsKey(item)) return;
        promoChecked.put(item, Boolean.TRUE);
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (hiddenWhy.containsKey(item)) return;
                    if (item.getParent() == null) return;
                    if (!looksLikeBanner(item)) return;
                    H.log(Log.INFO, MainHook.TAG, "PROMO-CARD-HIDE " + geom(item));
                    hideItem(item, "junk_promo_card");
                } catch (Throwable ignored) {}
            }
        }, 1500);
    }

    /** 子树里是否存在任何文字（TextView 文本、contentDescription，或已登记的锚点）。 */
    private static boolean hasAnyText(View root) {
        List<View> stack = new ArrayList<>();
        stack.add(root);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 400) {
            View v = stack.remove(stack.size() - 1);
            try {
                CharSequence cd = v.getContentDescription();
                if (cd != null && cd.length() > 0) return true;
                if (v instanceof TextView) {
                    CharSequence t = ((TextView) v).getText();
                    if (t != null && t.length() > 0) return true;
                }
            } catch (Throwable ignored) {}
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        return false;
    }

    /** 真正把条目收掉 */
    private static void hideItem(View item, String rule) {
        if (hiddenWhy.containsKey(item)) return;
        // ══ 硬闸门：绝不收「页面级」容器 ══
        // 列表 item 一旦是整页容器，一条规则就能把整页删空（用户实测的「我的页全没了」）。
        // 45% 屏高以上一律放行；正常卡片不受影响（内容流帖 862px ≈ 36%）。
        int sh = screenOf(item);
        int hh = item.getHeight();
        if (hh > 0 && hh > sh * 45 / 100) {
            if (loggedOnce.add("item_toolarge_" + rule)) {
                H.log(Log.INFO, MainHook.TAG, "SKIP(item toolarge " + hh + ") " + rule);
            }
            return;
        }
        if (item.getVisibility() != View.GONE) item.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp != null && lp.height != 0 && item.getHeight() > 0) {
            lp.height = 0;
            item.setLayoutParams(lp);
        }
        hiddenItems.add(new java.lang.ref.WeakReference<>(item));
        hiddenWhy.put(item, rule);
        if (loggedOnce.add("bind_" + System.identityHashCode(item))) {
            H.log(Log.INFO, MainHook.TAG, "BIND-HIDE " + rule + " " + geom(item));
        }
    }

    private static boolean isList(View v) {
        for (Class<?> k = v.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals("androidx.recyclerview.widget.RecyclerView")
                    || n.endsWith(".RecyclerView") || n.endsWith(".RecyclerViewV2")
                    || n.contains("AjxList")) {
                return true;
            }
        }
        return false;
    }

    private static int screenOf(View v) {
        View root = v;
        ViewParent p;
        while ((p = root.getParent()) instanceof View) root = (View) p;
        return root.getHeight() > 0 ? root.getHeight() : 2400;
    }

    // ══════════════════════════════════════════════════════════ 文本归类

    /**
     * 非工具类锚点 → 配置键。null = 不受本模块管辖。
     * 工具格在调用前已经分流，所以推荐区的子串锚点永远碰不到工具格。
     */
    private static String ruleFor(String t, View v) {
        // 无开关的强制清除项，优先级最高
        if (containsAny(t, ANCHOR_JUNK_FRIENDS)) return "junk_friends";
        if (containsAny(t, ANCHOR_JUNK_QUIZ)) return "junk_quiz";
        if (containsAny(t, ANCHOR_JUNK_TOP)) return "junk_top_banner";
        if (containsAny(t, ANCHOR_BOARD)) return Config.K_FEED_BOARD;

        if (HOME_CHIPS_LABELS.contains(t)) return Config.K_HOME_CHIPS;

        if (MY_ORDER.contains(t)) return Config.K_MY_ORDER_ROW;
        if (MY_SERVICE.contains(t)) return Config.K_MY_SERVICE_ROW;
        if (MY_TASK.contains(t)) return Config.K_MY_TASK;
        if (MY_PROMO.contains(t)) return Config.K_MY_PROMO_ROW;
        if (t.equals("猜你喜欢")) return Config.K_MY_GUESS;
        if (MY_QUALITY_WORDS.contains(t)) return Config.K_MY_QUALITY;

        if (FEED_FILTER_LABELS.contains(t)) {
            // 「推荐频道栏」的 chip 实测是 84x110 / 168x110；
            // 达人卡里的粉丝/关注计数是小矮格 85x42 —— 用高度把它们区分开，
            // 否则要么误伤达人卡，要么（按宽度卡）把 84px 宽的频道 chip 漏掉。
            View cell = ascendSmallCell(v);
            return (cell != null && cell.getHeight() >= 80) ? Config.K_FEED_FILTER : null;
        }
        if (isTempLabel(t)) return Config.K_FEED_WEATHER;
        if (isDistanceLabel(t)) return Config.K_FEED_DISTANCE;
        if (containsAny(t, ANCHOR_AI)) return Config.K_FEED_AI;
        if (containsAny(t, ANCHOR_WEATHER)) return Config.K_FEED_WEATHER;
        if (containsAny(t, ANCHOR_SCENIC)) return Config.K_FEED_SCENIC;
        if (containsAny(t, ANCHOR_POSTS)) return Config.K_FEED_POSTS;
        if (containsAny(t, ANCHOR_RANK)) return Config.K_FEED_RANK;
        if (containsAny(t, ANCHOR_CONTENT)) return Config.K_FEED_CONTENT;
        return null;
    }

    /** 归一：去掉首尾空白与 `- · | ——` 等装饰字符，避免精确匹配被装饰字符废掉。 */
    private static String norm(String s) {
        int a = 0, b = s.length();
        while (a < b && isDecor(s.charAt(a))) a++;
        while (b > a && isDecor(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isDecor(char c) {
        return c == ' ' || c == '\u3000' || c == '-' || c == '\u2014' || c == '\u2013'
                || c == '|' || c == '·' || c == '\u2022' || c == ',' || c == '\uff0c';
    }

    // ══ 性能：正则**预编译** ══
    // 原来用 String.matches()，每次调用都会重新编译一次 Pattern；
    // 而这两个函数在首页每一次 setText 都会被调用 —— 这是实打实的 CPU 浪费。
    private static final java.util.regex.Pattern P_TEMP =
            java.util.regex.Pattern.compile("\\d+\\s*°.*");
    private static final java.util.regex.Pattern P_DIST =
            java.util.regex.Pattern.compile("\\d+(\\.\\d+)?\\s*(米|公里|km|KM|Km)");

    /** 天气卡的温度标签：21° / 18°C —— 结构上足够独特，可安全用于定位天气卡。 */
    private static boolean isTempLabel(String t) {
        return t.length() <= 6 && P_TEMP.matcher(t).matches();
    }

    /**
     * 内容卡的距离标签。实测有两种：
     *   1.1公里 / 63公里 / 5km      —— 旅行帖、探店帖
     *   597米                        —— 同城卡（**漏了这个会剩一张卡藏不掉**）
     */
    private static boolean isDistanceLabel(String t) {
        return t.length() <= 8 && P_DIST.matcher(t).matches();
    }

    private static boolean containsAny(String text, String[] anchors) {
        for (String a : anchors) if (text.contains(a)) return true;
        return false;
    }

    // ══════════════════════════════════════════════════════════ 配置快照

    private static void refreshCfg() {
        try {
            Map<String, ?> all = Config.prefs().getAll();
            Map<String, Boolean> m = new HashMap<>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean) m.put(e.getKey(), (Boolean) v);
            }
            cfg = m;
        } catch (Throwable t) {
            // 读不到就沿用上一次快照（默认全部显示）
        }
        cfgAt = System.currentTimeMillis();
    }

    /** 快照读：默认 true = 显示（失效安全）。避免每个锚点都走一次 RemotePreferences IPC。 */
    private static boolean cfgOn(String key) {
        if (cfg.isEmpty() || System.currentTimeMillis() - cfgAt > 1500) refreshCfg();
        Boolean b = cfg.get(key);
        return b == null || b;
    }

    /** 扩展工具页：缺省即隐藏（与其它键的"缺省即显示"相反，因为它是额外推荐位） */
    private static boolean cfgExtraPage() {
        if (cfg.isEmpty() || System.currentTimeMillis() - cfgAt > 1500) refreshCfg();
        Boolean b = cfg.get(Config.K_TOOL_EXTRA);
        return b != null && b;
    }

    // ══════════════════════════════════════════════════════════ 诊断

    private static void trace(View v, String t) {
        if (!Config.debugLog() || dumpBudget <= 0 || !dumped.add("L:" + t)) return;
        dumpBudget--;
        final View fv = v;
        final String ft = t;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (fv.getParent() != null) {
                        H.log(Log.INFO, MainHook.TAG, "LBL " + ft + " :: " + geom(fv)
                                + " | " + chainOf(fv));
                    }
                } catch (Throwable ignored) {}
            }
        }, 700);
    }

    private static String chainOf(View v) {
        StringBuilder sb = new StringBuilder();
        View cur = v;
        for (int i = 0; i < 8 && cur != null; i++) {
            sb.append(shortName(cur)).append('(').append(cur.getWidth()).append('x')
              .append(cur.getHeight()).append(')');
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            if (cur != null) sb.append(" > ");
        }
        return sb.toString();
    }

    private static String shortName(View v) {
        String n = v.getClass().getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    private static String geom(View v) {
        return "[" + v.getLeft() + "," + v.getTop() + " " + v.getWidth() + "x" + v.getHeight() + "]";
    }
    // ══════════════════════════════════════════════════ 榜单板块结构识别

    private static int screenWOf(View v) {
        View rt = v;
        ViewParent p;
        while ((p = rt.getParent()) instanceof View) rt = (View) p;
        return rt.getWidth() > 0 ? rt.getWidth() : 1080;
    }

    /** 子树里 460~560 宽、高≥250 的卡片容器个数（≥2 即停） */
    private static int countWideCards(View block) {
        List<View> st = new ArrayList<>();
        st.add(block);
        int guard = 0, n = 0;
        while (!st.isEmpty() && guard++ < 500) {
            View c = st.remove(st.size() - 1);
            int w = c.getWidth(), h = c.getHeight();
            if (w >= 440 && w <= 560 && h >= 250 && h <= 1400) {
                n++;
                if (n >= 2) return n;
            }
            if (c instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) c;
                for (int i = 0; i < g.getChildCount(); i++) st.add(g.getChildAt(i));
            }
        }
        return n;
    }

    /**
     * v1.2.2 榜单板块结构识别（boardSweep）：
     * 目的地/路线详情页的「发现好去处」等板块，标题是 GL 级渲染（不走任何
     * 文本钩子），卡片是唯一身份。识别判据：满屏容器（≥85% 屏宽、高 500~1600）
     * 且子树含 ≥2 张 460~560 宽双列卡 → 整块 GONE + 高度归零。
     */
    private static void boardSweep(View root) {
        if (root == null) return;
        if (cfgOn(Config.K_FEED_BOARD)) return;   // 开关开=显示，一个像素不动
        try {
            List<View> stack = new ArrayList<>();
            stack.add(root);
            int guard = 0;
            while (!stack.isEmpty() && guard++ < 6000) {
                View v = stack.remove(stack.size() - 1);
                int w = v.getWidth(), h = v.getHeight();
                if (w > 0 && h >= 500 && h <= 1600 && w >= screenWOf(v) * 85 / 100) {
                    int cards = countWideCards(v);
                    if (cards >= 2 && !hiddenWhy.containsKey(v)
                            && !surrendered.containsKey(v)) {
                        if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                        ViewGroup.LayoutParams lp = v.getLayoutParams();
                        if (lp != null && lp.height != 0 && v.getHeight() > 0) {
                            lp.height = 0;
                            v.setLayoutParams(lp);
                        }
                        hiddenItems.add(new java.lang.ref.WeakReference<>(v));
                        hiddenWhy.put(v, Config.K_FEED_BOARD + "#struct");
                        H.log(Log.INFO, MainHook.TAG, "BOARD-BLOCK-HIDE "
                                + shortName(v) + " " + geom(v) + " cards=" + cards);
                        continue;
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
                }
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "boardSweep err " + t);
        }
    }

    // ══════════════════════════════════════════════════ 悬浮推广球

    /** 已整球摘掉的分支（去重） */
    private static final java.util.HashSet<Integer> killedBalls = new java.util.HashSet<>();

    /**
     * 悬浮推广球（扫街榜 2026 / 赢好礼）：不在 AJX 列表里，是窗口上的独立 overlay。
     * 文本 / contentDescription 完全等于 FLOAT_BALL_WORDS 之一才认，且分支不得
     * 落在已认领列表内 —— 列表里的正常入口零影响。命中后把宿主分支 GONE。
     */
    private static void floatBallSweep(View decor) {
        if (decor == null) return;
        try {
            List<View> stack = new ArrayList<>();
            stack.add(decor);
            int guard = 0;
            while (!stack.isEmpty() && guard++ < 6000) {
                View v = stack.remove(stack.size() - 1);
                String t = textAt(v);
                if (t == null) t = cdOf(v);
                if (t != null) {
                    boolean hit = false;
                    for (String w : FLOAT_BALL_WORDS) { if (t.equals(w)) { hit = true; break; } }
                    if (hit && !inHomeScope(v) && v.getParent() != null) {
                        View cur = v;
                        for (int k = 0; k < 10 && cur.getParent() instanceof View; k++) {
                            View p = (View) cur.getParent();
                            if (isList(p)) { cur = null; break; }
                            if (p.getWidth() > decor.getWidth() * 2 / 3
                                    || p.getHeight() > decor.getHeight() / 2) break;
                            cur = p;
                        }
                        if (cur != null && cur.getParent() != null
                                && killedBalls.add(System.identityHashCode(cur))) {
                            if (cur.getVisibility() != View.GONE) cur.setVisibility(View.GONE);
                            H.log(Log.INFO, MainHook.TAG, "FLOAT-BALL-HIDE [" + t + "] " + geom(cur));
                        }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int k = 0; k < g.getChildCount(); k++) stack.add(g.getChildAt(k));
                }
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "floatBallSweep err " + t);
        }
    }

}
