package io.github.ldxm666.mapadkiller;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Shared setting keys. Hook reads use an immutable snapshot refreshed at most every two seconds. */
public final class Config {
    public static final String PKG = "io.github.ldxm666.mapadkiller";
    public static final String PREF_GROUP = "amap_enhancer_config";

    /* ------------------------------------------------------------------ 自动巡航触发值
     * 面板「自动巡航」页的两个滑块写的就是这两个 key，AmapCruise 每次判定时读一次。
     *
     * <p>2.3.0 默认值从 18 km/h / 15 秒放宽到 <b>12 km/h / 8 秒</b>。为什么：
     * 老默认下一次判定要求「连续 15 个 1Hz 采样全部过 6 道串联 gate」，
     * 城市里单帧通过率大约 0.8（精度超 45m、红绿灯掉速、GNSS 速度抖动各占一部分），
     * 0.8^15 ≈ 3.5% ——「开了等于没开」的真正原因是这个乘出来的乘积，不是车主不开车。
     * 12 km/h × 8 秒，配合 AmapCruise 里新加的「容忍 3 帧抖动」，实际通过率才落到可用量级；
     * 同时仍然挡得住步行（5 km/h）和原地抖（净位移那一关）。
     *
     * <p>高德自己的云控参数是 minSpeed=15 / driveTime=5。取 12/8 是在它上下浮动，
     * 属于同一档严格度，不会比官方更敏感。
     */
    public static final String K_CRUISE_KMH = "cruise_min_kmh";
    public static final String K_CRUISE_SEC = "cruise_drive_sec";
    public static final int CRUISE_KMH_MIN = 2, CRUISE_KMH_MAX = 30, CRUISE_KMH_DEFAULT = 12;
    public static final int CRUISE_SEC_MIN = 2, CRUISE_SEC_MAX = 30, CRUISE_SEC_DEFAULT = 8;

    /**
     * 「立即测试」隐藏键（不进任何可见列表）：写入 true ⇒ AmapCruise 下一次 1s tick 里
     * **跳过全部速度/位移门槛，直接尝试进入一次巡航，并立刻把本键清回 false**。
     *
     * <p>为什么需要：「链接不通（scheme 被吞 / SchemeHandleActivity 类名漂移）」和
     * 「判定过不去（门槛太严 / 拿不到速度）」在日志里长得一模一样。
     * 有这个键，用户坐在家里点一下按钮就能把两条路分开，不用真开一趟车。
     *
     * <p>读取必须走 {@link #explicit}（只有显式写过才非 null）——
     * 走 {@link #visible} 会落到 {@link #defaultVisible} 的兜底 true，等于每次启动都强进一次。
     */
    public static final String K_CRUISE_FORCE_ONCE = "cruise_force_once";

    /** 是否按下了「立即测试」：只有显式写入 true 才算。 */
    public static boolean cruiseForceOnce() {
        try { return Boolean.TRUE.equals(explicit(K_CRUISE_FORCE_ONCE)); }
        catch (Throwable t) { return false; }
    }

    /** 消费掉「立即测试」：写回 false。拿不到后端时返回 false，绝不抛。 */
    public static boolean clearCruiseForceOnce() {
        try { return put(K_CRUISE_FORCE_ONCE, false); }
        catch (Throwable t) { return false; }
    }

    /** 触发速度下限（km/h），越界钳到 [CRUISE_KMH_MIN, CRUISE_KMH_MAX]。 */
    public static int cruiseMinKmh() {
        return clampInt(getInt(K_CRUISE_KMH, CRUISE_KMH_DEFAULT), CRUISE_KMH_MIN, CRUISE_KMH_MAX);
    }

    /** 触发持续时间（秒）。 */
    public static int cruiseDriveSec() {
        return clampInt(getInt(K_CRUISE_SEC, CRUISE_SEC_DEFAULT), CRUISE_SEC_MIN, CRUISE_SEC_MAX);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    public static final String K_TAB_PREFIX = "tab_", K_TOOL_PREFIX = "tool_";
    public static final String K_TOOLS_VISIBLE = "ui_tools_visible";
    public static final String K_TOOLS_ALLOWLIST = "ui_tools_allowlist";
    public static final String K_FEED_WEATHER = "feed_weather", K_FEED_SCENIC = "feed_scenic";
    public static final String K_FEED_RANK = "feed_rank", K_FEED_POSTS = "feed_posts", K_FEED_DISTANCE = "feed_distance";
    public static final String K_FEED_CONTENT = "feed_content", K_FEED_AI = "feed_ai", K_FEED_FILTER = "feed_filter";
    public static final String K_FEED_BOARD = "feed_board", K_FEED_FESTIVAL = "feed_festival";
    public static final String K_HOME_CHIPS = "home_chips", K_HOME_QUICK_ROW = "home_quick_row";
    /**
     * 搜索页「搜索框下方那排彩色金刚位」（美食 / 酒店 / 景点门票 / 加油站 / 休闲玩乐 / 超市）。
     *
     * <p>语义：{@code true = 显示}（与 {@link #K_TOOLS_VISIBLE} 同族，不是 {@code *_off} 族）。
     * 为什么不用 {@code search_grid_off}：面板开关的「开」= 存储值为 true
     * （{@code PanelTypes.Item} 没有取反能力，参见 {@code K_HOTWORD_OFF} 那一行），
     * 而这一行的标题是名词「搜索页快捷金刚位」、用户要的是「关掉 = 整排消失」。
     * 正向键 + {@link #defaultVisible} 兜底为 true ⇒ 老用户升级后这排照旧显示，
     * 只有主动关掉才消失。
     */
    public static final String K_SEARCH_GRID = "search_grid";
    public static final String K_DEBUG_LOG = "debug_log", K_TOOL_SORT = "tool_sort";
    /** 「工具图标统一彩绘（实验）」：true = 首页工具栏全部用高德自己的彩绘圆底图（默认 false=原生设计）。 */
    public static final String K_TOOL_COLOR_ICON = "tool_color_icon";
    public static final String K_SEARCH_AD = "search_ad", K_HOTWORD_OFF = "hotword_off", K_RIDE_CARD_OFF = "ride_card_off";
    /**
     * 「去哪里打车」卡片（首页工具行下方、天气卡上方那张：蓝色打车图标 + POI 名 +
     * 「打车享优惠 / 行程有保障」+ 右侧「打车」按钮）。
     *
     * <p>语义：{@code true = 显示}（与 {@link #K_TOOLS_VISIBLE} 同族，不是 {@code *_off} 族）。
     * 默认 **false** ⇒ 不显示，保持老用户升级前的观感（该卡以前被 {@code ride_card_off}
     * 顺带删掉）。
     *
     * <p>⚠ 为什么必须单独成键：这张卡是 {@code main-page-aiqs} 响应里
     * {@code data.cardList[]} 的 {@code ShareTripTaxiCard} 一项，和顺风车/代驾/抢神券
     * 同数组；以前 {@code AmapFeed1521} 在 {@code K_FEED_BOARD=false} 时把整个
     * {@code cardList} 清空 ⇒ 想单独显示打车卡只能把 {@code ride_card_off} 关掉，
     * 而 {@code AmapNativeHome.COMPACT} 又把它当总闸 ⇒ 整个推荐画布重建，天气/榜单/
     * 内容流/出行节一起"炸开"。新键只控制这一种 card type。
     */
    public static final String K_TAXI_CARD = "taxi_card";
    public static final String K_MY_ORDER_ROW = "my_order_row", K_MY_SERVICE_ROW = "my_service_row", K_MY_TASK = "my_task";
    public static final String K_MY_PROMO_ROW = "my_promo_row", K_MY_GUESS = "my_guess", K_MY_QUALITY = "my_quality";
    public static final String K_MY_FRIENDS = "my_friends";
    public static final String K_MAP_PROMO = "map_promo";
    public static final String K_MAP_POI = "map_poi";
    /**
     * 外部导航跳转 URI 归一化（只补缺失参数，绝不覆盖已有值）。默认 true。
     * 语义：第三方 App 发来的 androidamap:// / amapuri:// 缺 dev/dname/t 时高德会停在首页，
     * 补参数能把它救回路线页（实现见 AmapProtocolHooks.installNavUriFix）。
     */
    public static final String K_NAV_URI_FIX = "nav_uri_fix";
    /**
     * 性能开关：完全**不装** {@code VMapSceneWrapper} 那三个 native 标注 hook。
     *
     * <p>为什么需要它：{@code nativeSetThirdLabelInfo} / {@code nativeSetUpdate3rdLabelsWithPoiData}
     * / {@code nativeSetStylePointInfo} 是「每次刷新第三方标注 / POI / 样式点」都会走的方法。
     * 如果高德在**渲染帧**里调用它们，我们的 hook 就变成**每帧一次 Java→hook→native 往返**，
     * 表现为「整体掉帧」，而 {@code dumpsys gfxinfo} **测不到**（地图是 SurfaceView/GL
     * 独立 surface，HWUI 计数器不含地图层）。用户反馈过「负优化掉帧」，所以留一个能把这
     * 三个 hook **彻底不装**的开关，用于真机 A/B 定位。
     *
     * <p>语义：{@code selected(K_LABELS_OFF)==true} ⇒ **不装**这三个 hook（= 放弃第三方标注
     * 过滤，回到高德原生行为）。默认 false（保持原行为）。
     * 与 {@link #K_MAP_POI} 不是一回事：{@code map_poi} 控制「要不要过滤」，
     * {@code labels_off} 控制「要不要挂 hook」——排查掉帧时用后者。
     */
    public static final String K_LABELS_OFF = "labels_off";
    /**
     * 性能开关（**默认 false = 不装**）：是否启用 {@code VMapSceneWrapper} 的三个 native 标注 hook。
     *
     * <p>默认关掉的原因见 {@code AmapPoiLayer.install()} 顶部注释：这三个方法在「每次刷新标注」
     * 的热路径上，而地图是 SurfaceView/GL 独立 surface 渲染，{@code gfxinfo} 与
     * {@code Choreographer#doFrame} 都测不到地图层 ⇒ 这类开销在现有测量手段下不可见，
     * 只能默认关掉以消除「掉帧」风险。**功能不损失**：地图标注真正的实现是
     * {@code libmapclean_labels.so} 在原生层改样式 protobuf，本类只是"补充过滤"。
     *
     * <p>需要时在面板里打开（写 prefs {@code labels_on=true}）。
     */
    public static final String K_LABELS_ON = "labels_on";
    public static final String K_PLACE_RECOMMEND = "place_recommend";
    /** 「自动进入巡航」模块接管（默认 false：老用户行为不变，需在模块面板主动打开）。 */
    public static final String K_CRUISE_AUTO = "cruise_auto";

    /**
     * 「恢复高德官方自动进入巡航」。默认 <b>true</b>。
     *
     * <p>为什么默认开：这不是新功能，而是把高德自己就有、只是没被布防的
     * 状态机接回去。行为与官方一致：20 km/h × 10 秒。不喜欢可以关。
     *
     * <p>与 {@link #K_CRUISE_AUTO}（模块接管）互斥地并存：这个是「让官方跑」，
     * 那个是「官方跑不起来时自己上」。
     */
    public static final String K_CRUISE_OFFICIAL = "cruise_official";

    /**
     * 跳过高德开屏页（默认 <b>true</b>）。
     *
     * <p><b>2.3.0 真机实测结论，与推理相反</b>：
     * 把这条 hook 去掉、让官方开屏页自己跑之后，<em>高德停在
     * SplashActivity 不动（实测 30 秒仍在开屏页，只剩一行版权声明）</em>；
     * 而保留它的那一版，进程能正常走到 {@code NewMapActivity}、悬浮层挂上、
     * {@code baseline_home_ready} 也打了出来。
     *
     * <p>所以“开屏页被摘掉 ⇒ 首帧没东西盖 ⇒ 白屏”这个推理在 17.02 + LSPatch
     * 上不成立。真实情况是：官方开屏页在内置版里**自己就卡住了**。
     *
     * <p>开关化：不喜欢直接进地图的可以关掉，代价是开屏页可能长时间停留。
     */
    public static final String K_SPLASH_SKIP = "splash_skip";

    /**
     * 跳过高德开屏页。**缺省 false**——高德自带的开屏页不是广告，
     * 那是它自己的品牌页，截掉就是把正常界面挡了。
     *
     * <p>★ 为什么改成「必须显式开启」：上一版用 {@code visible()}（未知 key → true），
     * 于是它**默认就开着**，用户看到的就是「开屏页被拦了」。现在只认用户
     * 显式写过的 true（{@code explicit} 读的是字面值，不走可见性表）。
     *
     * <p>面板侧对应的一行必须带 {@code strict} 标记，否则开关会显示成「开」
     * 而实际上开屏页是正常显示的。
     */
    public static boolean splashSkip() {
        try { return Boolean.TRUE.equals(explicit(K_SPLASH_SKIP)); } catch (Throwable t) { return false; }
    }

    /** 官方巡航恢复开关（缺省 true）。 */
    public static boolean cruiseOfficial() {
        // Config 只有 visible(String) 一个重载；它对未知 key 默认返回 true，
        // 正好是这个开关要的缺省值。
        try { return visible(K_CRUISE_OFFICIAL); } catch (Throwable t) { return true; }
    }
    // ── 子进程省电（默认 true = 已拦截；面板里可关掉，用于排查「是不是我拦的」）──────
    // 语义与 K_HOTWORD_OFF 一致：**true 表示"这个行为被关掉"**，不是"显示"。
    // 三个键都落在 defaultVisible 的兜底 `return true` 上，所以默认值天然就是"已拦截"；
    // 不要在 defaultVisible 里为它们加分支 —— 那个函数是"可见性"语义，混进来最容易写反。
    public static final String K_SUBPROC_OFF = "subproc_off";
    public static final String K_SUBPROC_WIDGET_OFF = "subproc_widget_off";
    public static final String K_SUBPROC_LOTUSPOOL_OFF = "subproc_lotuspool_off";
    // ── 阿里妈妈广告请求层（高德主 APK 里唯一的真·广告 SDK 通道，见 AmapAlimama.java）──
    // 同样是 "true = 已拦截" 语义。开屏广告请求与广告埋点各一个开关，可分别回滚。
    public static final String K_ALIMAMA_SPLASH_OFF = "alimama_splash_off";
    public static final String K_ALIMAMA_LOG_OFF = "alimama_log_off";
    // ── 阿里 UT 埋点（com.alibaba.analytics）初始化闸门，见 AmapTrack.java ──────────
    // ⚠ 这是本轮**唯一默认开启的「谨慎类」拦截**。真机出现任何无法归因的异常时，第一个关它。
    public static final String K_TRACK_OFF = "track_off";
    // ── 周期任务 / 推送长连接（见 AmapJob.java；这两类才是真正的后台耗电源）──────────
    // subproc_job_off：WorkManager 的两条执行通道（RescheduleReceiver + SystemJobService）
    // push_off       ：ACCS/AGOO 安全档（不建长连接 + 不处理推送消息，**不杀服务** ⇒ 不会进退避重连）
    // push_kill_off  ：ACCS/AGOO 激进档（ChannelService.onCreate 直接 stopSelf）
    //                  ⚠ 单独成键的理由：A/B 采样若发现 CPU **反而上升**，可以只退这一档而不重新出包。
    public static final String K_SUBPROC_JOB_OFF = "subproc_job_off";
    public static final String K_PUSH_OFF = "push_off";
    public static final String K_PUSH_KILL_OFF = "push_kill_off";
    // push_hard_off：**真正掐断 ACCS0 线程**的硬档。前三档（push_off/push_kill_off/AccsJobService）
    //   在真机上都装上了却拦不住 ACCS0（31% 单核），因为它们都不是长连接的真实起点。
    //   这一档挂的是真实创建链：ConnectionServiceImpl.start / ConnectionServiceImpl.startChannelService /
    //   BaseConnection.startChannelService / SpdyConnection.start / InAppConnection.start /
    //   SpdyConnection$NetworkThread.run。代价：高德推送收不到。默认 true，面板可关。
    public static final String K_PUSH_HARD_OFF = "push_hard_off";
    // The map's default page is kept; removing it invalidates navigation return targets.
    public static final String[] TABS = {"探索", "长按说话", "打车", "我的"};
    public static final String[] TOOLS = toolLabels();
    /**
     * 默认关闭的工具（只对 {@code AmapData.TOOLS} 里那 19 条 legacy 生效 —— 它们的 key 是
     * {@code tool_<中文名>}；其余 ~95 条走 {@code tool_id_<id>}，本来就默认 false）。
     *
     * <p>⚠ **改这里是唯一对老设备立即生效的办法**：模块的默认值只在第一次安装时由
     * `res/raw/default_config.json` 播种一次（设备侧 `_defaults_applied=true`），
     * 之后改 JSON 不会重播；而删标记会重置用户全部开关。所以"改默认"必须走源码常量。
     *
     * <p>本轮新增 `打车` / `实时公交`：两者都是运营入口而非导航方式。
     * `公交地铁` **保留可见** —— 它是导航方式（`amapuri://routePlan/home?t=1`），不是运营位。
     */
    public static final java.util.Set<String> TOOLS_DEFAULT_OFF = new java.util.HashSet<String>(java.util.Arrays.asList(
            "旅游度假", "车主服务", "景点游玩", "离线地图", "高德出行节",
            "打车", "实时公交"));
    public static final boolean FORCE_DEBUG = false;
    private static volatile SharedPreferences cached;
    private static volatile Map<String, ?> snapshot = Collections.emptyMap();
    private static volatile long expiresAt;
    private static final SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            expiresAt = 0;
            refreshFlags();
        }
    };
    private Config() {}

    // ══════════════════════════════════════════════════════════════════════════
    // 「零成本开关」：常驻 hook 的拦截器里**只读一个 volatile 字段**
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 为什么需要：`Config.visible(key)` 每次都要 SystemClock.uptimeMillis() + map 查找
    // + defaultVisible() 里若干 String.equals（2 秒缓存只省掉了复制整张表的开销，没省这些）。
    // 常驻在热路径上的 hook（payload 过滤、tab 分发）每次调用都付这个代价。
    //
    // 做法：Flag 自己缓存一个 volatile boolean，值由 prefs 变更监听器刷新。
    // 拦截器里就是 `if (!FLAG.on()) return chain.proceed();` —— 一次 volatile 读，
    // 不查 map、不比时间。
    //
    // ⚠ 构造 Flag 时**不读 prefs**（模块 App 进程里 H.module 为 null，读会 NPE），
    //    第一次 on() 才解析；解析失败按"开"处理（宁可拦截，不静默放行）。

    /** 一个可被 prefs 变更刷新的布尔开关。子类实现 {@link #compute()}。 */
    public abstract static class Flag {
        private volatile boolean value;
        private volatile boolean resolved;
        protected Flag() { FLAGS.add(this); }
        protected abstract boolean compute();
        /** 零成本读：命中后只有一次 volatile 读。 */
        public final boolean on() {
            if (!resolved) resolve();
            return value;
        }
        public final void resolve() {
            boolean v;
            try { v = compute(); } catch (Throwable t) { v = true; }
            value = v;
            resolved = true;
        }
    }

    private static final java.util.List<Flag> FLAGS =
            new java.util.concurrent.CopyOnWriteArrayList<Flag>();

    /** 直接绑一个键的 Flag（compute 就是 Config.visible(key)）。 */
    public static Flag flag(final String key) {
        return new Flag() {
            @Override protected boolean compute() { return Config.visible(key); }
        };
    }

    private static void refreshFlags() {
        for (Flag f : FLAGS) f.resolve();
    }

    // ── 热键表：这些键的读取走 Flag（一次 volatile 读），不走 map 查 ──────────────
    // 只放**每次调用都会被读到**的键。新增键要加进下面的 static 块。
    private static final java.util.Map<String, Flag> HOT =
            new java.util.concurrent.ConcurrentHashMap<String, Flag>();

    static {
        // 工具面板（每个格子都要判一次）
        hot(K_TOOLS_VISIBLE);
        hot(K_TOOL_COLOR_ICON);
        hot(K_TOOLS_ALLOWLIST);
        // 首页推荐画布（compact() 每次面板重建都判 5 个）
        hot(K_FEED_WEATHER);
        hot(K_FEED_BOARD);
        hot(K_FEED_CONTENT);
        hot(K_FEED_FESTIVAL);
        hot(K_RIDE_CARD_OFF);
        hot(K_TAXI_CARD);
        // 地图挂件 / 我的页卡片
        hot(K_MAP_PROMO);
        hot(K_HOME_CHIPS);
        // 热词（搜索框每次刷新都判）
        hot(K_HOTWORD_OFF);
    }

    private static Flag hot(final String key) {
        Flag existing = HOT.get(key);
        if (existing != null) return existing;
        Flag created = flag(key);
        Flag raced = ((java.util.concurrent.ConcurrentHashMap<String, Flag>) HOT).putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    /**
     * 热键 → Flag；不在热键表里返回 null（调用方回退到 {@link #visible}）。
     *
     * <p>供 {@link AmapData.Settings#enabled} 使用：让 payload 过滤这类常驻热路径
     * **只读一个 volatile**，不查 map、不比时间。
     */
    public static Flag hotFlag(String key) {
        return key == null ? null : HOT.get(key);
    }

    /**
     * 安装期门控：开关关掉时**根本不装**对应 hook。
     *
     * <p>语义与 {@link #visible} 一致（读不到就按默认值），但只在 install 时调用一次。
     * 副作用：**改这类开关必须强停并重开高德才生效** —— 这是"关闭 = 零成本"的必然代价，
     * 面板的组说明里要如实写。
     */
    public static boolean installGate(String key) {
        try { return visible(key); } catch (Throwable t) { return true; }
    }

    /**
     * Drop the cached snapshot so the next read reflects a write made in this process.
     * The embedded panel writes through the same preferences it reads, and without this
     * the UI would keep showing the previous value for up to two seconds.
     */
    public static void invalidate() {
        expiresAt = 0;
        // 写入后立刻刷新热键 Flag，避免"改了开关但拦截器还读旧值"。
        refreshFlags();
    }
    private static String[] toolLabels() {
        String[] out = new String[AmapData.TOOLS.length];
        for (int i = 0; i < out.length; i++) out[i] = AmapData.TOOLS[i][1];
        return out;
    }
    public static boolean defaultVisible(String key) {
        if (K_CRUISE_AUTO.equals(key)) return false;
        if (K_DEBUG_LOG.equals(key) || K_TOOLS_ALLOWLIST.equals(key) || K_MAP_POI.equals(key)
                || K_TOOL_COLOR_ICON.equals(key) || K_PLACE_RECOMMEND.equals(key)) return false;
        if (key != null && key.startsWith("tool_id_")) return false;
        if (key != null && key.startsWith(K_TOOL_PREFIX))
            return !TOOLS_DEFAULT_OFF.contains(key.substring(K_TOOL_PREFIX.length()));
        // 「设置家 / 设置单位 / 常去地点」 is on by default.
        //
        // It used to default to off as part of the compact-homepage work, and that is what the
        // user saw as "设置家无效": the row is produced by the app's own HomeCompanyBuilderV3,
        // the module only decides whether to let it through, so an off default means the row is
        // simply never there -- with a switch that has to be found first and a home page that
        // has to be rebuilt before it takes effect. The switch still hides the row for anyone
        // who wants the shorter panel.
        return !K_HOME_QUICK_ROW.equals(key) && !K_FEED_BOARD.equals(key) && !K_FEED_FESTIVAL.equals(key)
            && !K_FEED_WEATHER.equals(key) && !K_FEED_CONTENT.equals(key)
            && !K_MY_FRIENDS.equals(key) && !K_MAP_PROMO.equals(key) && !K_TAXI_CARD.equals(key);
    }
    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p != null) return p;
        synchronized (Config.class) {
            p = cached;
            if (p != null) return p;
            p = resolveBackend();
            cached = p;
            if (p != null) {
                try { p.registerOnSharedPreferenceChangeListener(listener); } catch (Throwable ignored) { }
            }
            if (backend >= 0) {
                // 定案：只打一次（remote / local / none），强制本地时追加 forced=true。
                if (!backendLogged) {
                    backendLogged = true;
                    H.log("event=cfg_backend " + backendName()
                            + (backendForced ? " forced=true" : ""));
                }
            } else if (!pendingLogged) {
                // 未定案（拿不到宿主 Context）：**不缓存失败**，下次调用再试。
                pendingLogged = true;
                H.log("event=cfg_backend pending reason=no_context");
            }
            return p;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 配置后端：远程（框架）→ 本地（宿主 SharedPreferences）→ 无
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 为什么需要：`H.module.getRemotePreferences(PREF_GROUP)` 依赖框架提供远程配置通道。
    // 在**免 root 内置（LSPatch）**形态下，部分设备/版本上 `H.module` 为 null，或该方法
    // 返回 null / 抛异常 ⇒ 读写全废；而所有读都有 defaultVisible 兜底 ⇒ 界面表现为
    // 「○ 只读 · 需要目标 App 运行中」+ 全部显示默认值（"开关能显示"是假象）。
    //
    // 优先级：远程有就用远程（LSPosed 用户的配置不丢）；否则用**同进程内的普通
    // SharedPreferences**（永远可写，hook 侧与面板侧读的是同一个文件）；都没有才只读。

    public static final int BACKEND_NONE = 0;
    public static final int BACKEND_LOCAL = 1;
    public static final int BACKEND_REMOTE = 2;
    /** 本地兜底后端文件名。**不要改**：面板与 hook 必须读同一个文件。 */
    public static final String LOCAL_PREFS = "mapclean_cfg";
    private static final String K_LOCAL_MIGRATED = "_local_migrated";
    /**
     * **隐藏键**（不进任何可见列表）：强制走本地后端。
     *
     * <p>为什么需要：LSPatch 的远程配置通道对文件篡改有韧性（改名 / `chmod 000` 都会被自动重建
     * 或仍可读），所以"本地降级"这条路在真机上**无法自然触发**，也就永远没人验过。
     * 这个键让验证可以显式切到本地后端。
     *
     * <p>读取方式：**远程和本地都看，任一为 true 即生效** —— 它必须在"远程不可用"时也能被读到，
     * 只查远程就没有意义。
     */
    public static final String K_CFG_FORCE_LOCAL = "cfg_force_local";

    private static volatile int backend = -1;        // -1 = 未解析（允许重试）
    private static volatile boolean backendLogged;
    private static volatile boolean pendingLogged;
    private static volatile boolean backendForced;   // 本次解析是否是"强制本地"
    private static volatile long lastResolveAt;      // 上次尝试解析的时间（用于重试节流）

    /** 当前后端：{@link #BACKEND_REMOTE} / {@link #BACKEND_LOCAL} / {@link #BACKEND_NONE}。 */
    public static int backend() {
        prefs();
        int b = backend;
        return b < 0 ? BACKEND_NONE : b;
    }

    /** 本次解析是否走了"强制本地"（面板可据此显示"（强制本地）"）。 */
    public static boolean backendForced() {
        prefs();
        return backendForced;
    }

    /**
     * 解析后端。**失败不缓存**：拿不到宿主 Context 时把 {@code backend} 留在 -1，
     * 下次 {@link #prefs()} / {@link #backend()} 再试（面板打开时 Application 一定已就绪）。
     *
     * <p>重试节流 500ms：{@code Config.visible()} 是热路径，不能在"没有 Context"的进程里
     * 每次调用都做一遍反射。
     */
    private static SharedPreferences resolveBackend() {
        long now = android.os.SystemClock.uptimeMillis();
        if (backend < 0 && lastResolveAt != 0 && now - lastResolveAt < 500) return null;
        lastResolveAt = now;

        SharedPreferences remote = remotePrefs();
        SharedPreferences local = localPrefs();
        boolean forced = forceLocal(remote, local);

        // ★ 隐藏键：强制本地。必须在"选远程"之前判，且**不播种**（否则等于没测到降级）。
        if (forced) {
            if (local != null) {
                backend = BACKEND_LOCAL;
                backendForced = true;
                // 强制本地时**必须真的把库建出来**，否则 verify-runner 无法用
                // `ls shared_prefs/` 证明它生效（一个键都没写时文件可能不落盘）。
                ensureLocalCreated(local);
                return local;
            }
            // 拿不到宿主 Context ⇒ **不定案**，下次重试（不能永久退化成远程）。
            backend = -1;
            return null;
        }

        backendForced = false;
        // 内置版（LSPatch）：remote 走 lspatch-xposed-remote.db，真机实测会
        // SQLiteCantOpenDatabaseException（旧 uid / 文件锁）⇒ 写入只落到内存，重进就回滚。
        // 因此内置版一律以宿主自己的 mapclean_cfg 为准；remote 只做镜像（见 CfgStore）。
        if (CfgStore.embedded()) {
            if (local != null) {
                backend = BACKEND_LOCAL;
                H.log("event=cfg_backend kind=local embedded=true");
                return local;
            }
            H.log("event=cfg_backend kind=none embedded=true reason=no_local_ctx");
        }
        if (remote != null) {
            backend = BACKEND_REMOTE;
            // 顺手把远程值播种到本地一次：将来远程不可用时配置不会"消失"。
            seedLocal(remote, local);
            return remote;
        }
        if (local != null) {
            backend = BACKEND_LOCAL;
            return local;
        }
        // 两个都拿不到 —— 大概率是"Context 还没就绪"，**不定案**，留给下次重试。
        backend = -1;
        return null;
    }

    /**
     * 强制本地时确保 `mapclean_cfg` 真的落盘。
     *
     * <p>SharedPreferences 的文件是**首次 commit 时**才写出的；一个键都没写就不会有
     * `shared_prefs/mapclean_cfg.xml`。所以这里写一个 {@code _local_created=<时间戳>} 标记
     * 并 commit，让验证脚本能直接用 `ls shared_prefs/` 判定"本地后端真的生效了"。
     */
    private static void ensureLocalCreated(SharedPreferences local) {
        try {
            if (!local.contains("_local_created")) {
                local.edit().putLong("_local_created",
                        android.os.SystemClock.uptimeMillis()).commit();
                H.log("event=cfg_local_ready path=" + LOCAL_PREFS);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 读隐藏键 {@link #K_CFG_FORCE_LOCAL}：远程、本地**任一为 true 即生效**。
     *
     * <p>不写进任何可见列表（面板不注册它），只给验证/排障用。
     */
    private static boolean forceLocal(SharedPreferences remote, SharedPreferences local) {
        return readBool(remote, K_CFG_FORCE_LOCAL) || readBool(local, K_CFG_FORCE_LOCAL);
    }

    private static boolean readBool(SharedPreferences p, String key) {
        if (p == null) return false;
        try { return p.getBoolean(key, false); } catch (Throwable t) { return false; }
    }

    private static SharedPreferences remotePrefs() {
        try {
            if (H.module == null) return null;          // 免 root 形态下这里就是 null
            return H.module.getRemotePreferences(PREF_GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences localPrefs() {
        try {
            android.content.Context ctx = hostContext();
            if (ctx == null) return null;
            return ctx.getSharedPreferences(LOCAL_PREFS, android.content.Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 宿主进程的 Context —— **不依赖框架**，四级顺序，任一成功即返回。
     *
     * <ol>
     *   <li>{@code ActivityThread.currentApplication()} —— 最常见，但**安装早期返回 null**
     *       （这正是 r23 "强制本地没生效"的根因）；</li>
     *   <li>{@code android.app.AppGlobals.getInitialApplication()} —— 隐藏静态方法，
     *       在 Application 绑定后即可用，比 currentApplication 更早成功；</li>
     *   <li>{@code ActivityThread.currentActivityThread()} 的 {@code mInitialApplication} 字段
     *       —— 前两条都失败时的兜底（字段在 ActivityThread 实例上）；</li>
     *   <li>{@code io.github.ldxm666.mapclean.App.ctx()} —— 内置版里由面板侧持有的实例
     *       （反射调用 ⇒ 不引入对 mapclean 的编译期依赖，精简构建不会缺符号）。</li>
     * </ol>
     * 全部 try/catch，任一抛异常继续试下一个；全失败返回 null（调用方按"未定案"处理）。
     *
     * <p>注：libxposed API 102 的 {@code XposedModule} **没有** context getter
     * （`javap` 核实：该类只有构造器），所以没有"从 module 拿 Context"这条路。
     */
    /** 包内可复用的宿主 Context（供 {@link HostInfo} 做签名/模式诊断）；拿不到返回 null。 */
    static android.content.Context hostCtx() { return hostContext(); }

    /**
     * 取**宿主进程自己的** Context。
     *
     * <p>★ 必须校验包名：模块可能被注入到别的 App（实测日志里出现过
     * {@code com.miui.contentcatcher} / {@code com.google.android.webview}），
     * 若把那些 Context 当宿主用，`getSharedPreferences()` 会写到**别人的 data 目录**
     * ⇒ 权限不足 ⇒ {@code commit_false} ⇒ 用户看到"开关拨了不生效"。
     * 判据以 {@code ActivityThread.currentPackageName()}（= 当前进程所属包）为准。
     */
    private static android.content.Context hostContext() {
        String procPkg = currentProcessPackage();
        // 1) ActivityThread.currentApplication()
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (isHostCtx(app, procPkg)) return (android.content.Context) app;
        } catch (Throwable ignored) { }
        // 2) AppGlobals.getInitialApplication()
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Object app = ag.getMethod("getInitialApplication").invoke(null);
            if (isHostCtx(app, procPkg)) return (android.content.Context) app;
        } catch (Throwable ignored) { }
        // 3) ActivityThread.currentActivityThread().mInitialApplication
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                java.lang.reflect.Field f = at.getDeclaredField("mInitialApplication");
                f.setAccessible(true);
                Object app = f.get(thread);
                if (isHostCtx(app, procPkg)) return (android.content.Context) app;
            }
        } catch (Throwable ignored) { }
        // 4) mapclean.App.ctx()（内置版面板侧持有）
        try {
            Class<?> app = Class.forName("io.github.ldxm666.mapclean.App");
            Object ctx = app.getMethod("ctx").invoke(null);
            if (isHostCtx(ctx, procPkg)) return (android.content.Context) ctx;
        } catch (Throwable ignored) { }
        return null;
    }

    /** 当前进程所属包名（反射取，取不到返回 null）。 */
    private static String currentProcessPackage() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object v = at.getMethod("currentPackageName").invoke(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * 这个 Context 是不是**当前进程**的宿主 Context。
     *
     * <p>拿不到进程包名时退化为"包名属于已知地图包"；再不行就放行（宁可退回旧行为，
     * 也不要因为校验过严让配置完全不可用）。
     */
    private static boolean isHostCtx(Object ctx, String procPkg) {
        if (!(ctx instanceof android.content.Context)) return false;
        String pkg;
        try { pkg = ((android.content.Context) ctx).getPackageName(); } catch (Throwable t) { return false; }
        if (pkg == null || pkg.length() == 0) return false;
        if (procPkg != null && procPkg.length() > 0) return procPkg.equals(pkg);
        return pkg.equals("com.autonavi.minimap")
                || pkg.equals("com.baidu.BaiduMap")
                || pkg.equals("com.tencent.map");
    }
    /**
     * 把远程配置播种到本地（一次性，标记 {@link #K_LOCAL_MIGRATED}）。
     *
     * <p>为什么在"远程可用"时做：远程不可用时已经读不到任何值，那时无从迁移。
     * 真正会丢配置的场景是「现在远程好用 → 换设备/换框架后远程不可用」，
     * 所以必须在远程**还能读**的时候先落一份到本地。
     */
    private static void seedLocal(SharedPreferences remote, SharedPreferences local) {
        if (remote == null || local == null) return;
        try {
            if (local.getBoolean(K_LOCAL_MIGRATED, false)) return;
            Map<String, ?> all = remote.getAll();
            if (all == null || all.isEmpty()) return;
            SharedPreferences.Editor e = local.edit();
            int n = 0;
            for (Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                Object v = en.getValue();
                if (k == null || v == null) continue;
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof String) e.putString(k, (String) v);
                else if (v instanceof Integer) e.putInt(k, (Integer) v);
                else if (v instanceof Long) e.putLong(k, (Long) v);
                else if (v instanceof Float) e.putFloat(k, (Float) v);
                else continue;
                n++;
            }
            e.putBoolean(K_LOCAL_MIGRATED, true);
            e.commit();
            if (n > 0) H.log("event=cfg_migrated n=" + n);
        } catch (Throwable ignored) { }
    }

    private static String backendName() {
        int b = backend;
        return b == BACKEND_REMOTE ? "remote" : b == BACKEND_LOCAL ? "local" : "none";
    }

    /**
     * **统一写入口**：面板与 hook 都必须走这里（写必须落在当前后端上）。
     *
     * @return null 表示当前没有可写后端（{@link #BACKEND_NONE}）
     */
    public static SharedPreferences.Editor edit() {
        try {
            SharedPreferences p = prefs();
            return p == null ? null : p.edit();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写一个布尔键并立即 commit；成功返回 true。 */
    public static boolean put(String key, boolean value) {
        // 内置版：local 为准 + remote 镜像 + write→read 自校验（见 CfgStore 类注释）
        if (CfgStore.embedded()) {
            boolean ok = CfgStore.write(key, value);
            if (ok) invalidate();
            return ok;
        }
        SharedPreferences.Editor e = edit();
        if (e == null) return false;
        boolean ok;
        try { ok = e.putBoolean(key, value).commit(); } catch (Throwable t) { ok = false; }
        if (ok) invalidate();
        return ok;
    }

    /** 写一个字符串键并立即 commit；成功返回 true。 */
    public static boolean put(String key, String value) {
        SharedPreferences.Editor e = edit();
        if (e == null) return false;
        boolean ok;
        try { ok = e.putString(key, value).commit(); } catch (Throwable t) { ok = false; }
        if (ok) invalidate();
        return ok;
    }

    /** 读 int（面板的滑块类配置用）；后端不可用时返回 fallback，绝不抛。 */
    public static int getInt(String key, int fallback) {
        try {
            // 内置版必须走合并读（local + remote），否则写到 local 的值读不回来。
            if (CfgStore.embedded()) return CfgStore.readInt(key, fallback);
            SharedPreferences p = prefs();
            return p == null ? fallback : p.getInt(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 写 int 并立即 commit；成功返回 true。 */
    public static boolean put(String key, int value) {
        // 内置版：和布尔键一样走 CfgStore（local 为准 + remote 镜像 + 回读自校验）。
        // 以前这里直接写 remote，内置版 remote 是只读代理 → commit 说成功、下次读回默认值，
        // 用户看到的就是「调好的触发值关掉悬浮窗又变回去」。
        if (CfgStore.embedded()) {
            boolean ok = CfgStore.writeInt(key, value);
            if (ok) invalidate();
            return ok;
        }
        SharedPreferences.Editor e = edit();
        if (e == null) return false;
        boolean ok;
        try { ok = e.putInt(key, value).commit(); } catch (Throwable t) { ok = false; }
        if (ok) invalidate();
        return ok;
    }
    private static Map<String, ?> currentValues() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now >= expiresAt) synchronized (Config.class) {
            if (now >= expiresAt) {
                try {
                    Map<String, Object> merged = CfgStore.embedded()
                            ? CfgStore.readAll()
                            : new HashMap<String, Object>(prefs().getAll());
                    snapshot = Collections.unmodifiableMap(merged);
                } catch (Throwable ignored) {}
                expiresAt = now + 2000;
            }
        }
        return snapshot;
    }
    /**
     * 当前生效的全部配置（远端 → 本地 → 无 的合并结果）的只读副本。
     * 给「主动抓包」写 txt 用：只读，不会触发后端重连。
     */
    public static Map<String, ?> snapshot() {
        try {
            Map<String, ?> v = currentValues();
            return v == null ? new java.util.LinkedHashMap<String, Object>() : new java.util.LinkedHashMap<String, Object>(v);
        } catch (Throwable t) {
            return new java.util.LinkedHashMap<String, Object>();
        }
    }

    public static boolean visible(String key) { return value(currentValues(), key); }
    public static boolean selected(String key) { return Boolean.TRUE.equals(currentValues().get(key)); }

    /**
     * 显式设置过则返回其值，否则返回 {@code null}。
     *
     * <p>用于「**透传优先**」的判定（工具宫格方案 C）：宿主 payload 里给的工具有什么就显示什么，
     * 只有用户在模块面板里**显式关掉**的才剔除。没设置过 ⇒ null ⇒ 放行。
     */
    public static Boolean explicit(String key) {
        try {
            Object v = currentValues().get(key);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
    /** UI and hooks use the same default when the tool allowlist is active. */
    public static boolean readVisible(SharedPreferences prefs, String key) {
        // prefs 可能是 null（BACKEND_NONE）—— 面板侧绝不能因此崩，退回默认值。
        if (prefs == null) return defaultVisible(key);
        try { return value(prefs.getAll(), key); }
        catch (Throwable t) { return defaultVisible(key); }
    }
    /** Same rule, but against an already-fetched map -- the settings page reads every
     *  key at once instead of copying the whole table once per switch. */
    public static boolean readVisible(java.util.Map<String, ?> values, String key) { return value(values, key); }
    private static boolean value(Map<String, ?> values, String key) {
        Object stored = values.get(key);
        if (stored instanceof Boolean) return (Boolean) stored;
        if (key != null && key.startsWith(K_TOOL_PREFIX) && Boolean.TRUE.equals(values.get(K_TOOLS_ALLOWLIST))) return false;
        return defaultVisible(key);
    }
    public static boolean toolVisible(String label) { return visible(K_TOOL_PREFIX + label); }
    public static boolean tabVisible(String label) { return visible(K_TAB_PREFIX + label); }
    /**
     * 搜索页那排金刚位要不要显示。{@code false} = 用户关掉了它，整排要真删掉并让下方内容上移。
     *
     * <p>读取失败一律按“显示”处理：这个开关一旦误判就是“用户的入口凭空消失”，
     * 比多显示一排糟得多。
     */
    public static boolean searchGrid() {
        try { return visible(K_SEARCH_GRID); } catch (Throwable t) { return true; }
    }
    public static boolean cruiseAuto() { try { return visible(K_CRUISE_AUTO); } catch (Throwable t) { return false; } }
    /** 工具图标统一彩绘（实验）——默认 false，保持高德原生「第一行彩绘 + 第二行线条」。 */
    public static boolean toolColorIcon() {
        try { return visible(K_TOOL_COLOR_ICON); } catch (Throwable t) { return false; }
    }

    public static boolean debugLog() { return FORCE_DEBUG || visible(K_DEBUG_LOG); }
}
