package io.github.ldxm666.tmapclean;

/**
 * 配置规格 —— <b>设置页与 Hook 侧共用同一份键名</b>，杜绝两侧漂移。
 *
 * <p>键值语义统一为「可见」布尔：{@code true} = 显示（缺省），{@code false} = 隐藏。
 * 总开关与逐项开关同语义，只是作用域不同（整块 / 单个入口）。
 *
 * <p>与高德侧的区别：腾讯的首页工具栏与底部栏都是<b>服务端下发</b>的，
 * 所以工具目录不是写死的常量表，而是由 Hook 侧在真实进程里"学"出来，
 * 经 {@link Report} 广播回设置页（见 {@link #learnTools}）。这样用户看到的
 * 每一项都是自己手机上真实存在的入口，不会出现"勾了却没有这一项"。
 */
public final class Spec {

    // ── 键名 ───────────────────────────────────────────────────────────
    /** 底部入口栏 整栏总开关 */
    public static final String K_BAR = "ui_bottom_bar";
    /** 单 tab：K_TAB + 页名（homePage / explorePage / planPage / mePage） */
    public static final String K_TAB = "tab_";
    /** 首页工具宫格 整块总开关 */
    public static final String K_TOOLS = "ui_tool_grid";
    /** 反转开关：只显示明确打开的工具 */
    public static final String K_TOOLS_ALLOWLIST = "ui_tools_allowlist";
    /** 单格：K_TOOL + 工具 id（服务端下发的 name） */
    public static final String K_TOOL = "tool_";
    /**
     * 宫格「常用预设」：开启后首页只保留头部几个入口，其余按腾讯自己的偏好顺序截断。
     *
     * <p>关闭（缺省）时完全遵循腾讯：它首次会把 13~14 个偏好工具写进本地缓存，
     * 首页 10 格被填满。开启后由模块替用户把列表裁剪到 {@link #TOOL_PRESET_COUNT} 个，
     * 剩下的入口仍可在「更多工具」页里找到，不会丢功能。
     */
    public static final String K_TOOL_PRESET = "ui_tool_preset";
    /** 首页「设置家 / 设置公司」一行 */
    public static final String K_HOME_FAV = "ui_home_fav";
    /** 首页下方推荐信息流卡片列表 */
    public static final String K_HOME_FEED = "ui_home_feed";
    /** 地点详情「大家都在看」 */
    public static final String K_LOOK_AND_LOOK = "ui_look_and_look";
    /** 主页地图图标 */
    public static final String K_MAP_ICONS = "ui_map_icons";
    /** 搜索框上方横向快捷入口 */
    public static final String K_SEEK_CHIPS = "ui_seek_chips";

    // ── 「我的」页（全部由 Kuikly 组件按数据是否为空决定是否渲染） ──────────
    /** 积分中心 */
    public static final String K_MY_POINTS = "my_points";
    /** 我的页快捷工具栏里的收藏入口 */
    public static final String K_MY_FAVORITES = "my_favorites";
    /** 个性化设置 */
    public static final String K_MY_PERSONALIZATION = "my_personalization";
    /** 我的小程序 */
    public static final String K_MY_MINIPROGRAM = "my_miniprogram";
    /** 我的车辆 */
    public static final String K_MY_VEHICLE = "my_vehicle";
    /** 活动专区 */
    public static final String K_MY_ACTIVITY_ZONE = "my_activity_zone";

    /**
     * 底部标签：页名 → 显示文案。
     *
     * <p>页名是 {@code TabFrameInfo.Page.pageName} 的取值，实证自
     * {@code com.tencent.map.navisdk.a.exh#a(List)} 的分支常量（11.6.0）与真机底栏（11.7.0）。
     * 文案由服务端 {@code Tab.tabText} 下发，这里只是设置页的说明文字。
     */
    public static final String[][] TABS = {
            {"homePage", "首页"},
            {"explorePage", "探索"},
            {"planPage", "行程"},
            {"mePage", "我的"},
            {"nearbyPage", "附近"},
    };

    /**
     * 各键的默认「可见」值 —— **设置页、hook 侧、内置 JSON 三处必须同源**。
     *
     * <p>规则按优先级：
     * <ol>
     *   <li>{@link Cfg#K_DEBUG} —— 默认关；</li>
     *   <li>{@link #K_TOOL_PRESET} —— 默认关（钩子侧 {@code TmapTools#presetOn()} 一直按
     *       {@code false} 执行，面板曾经显示「开」，两侧漂移）；</li>
     *   <li>底栏 {@code tab_*} —— <b>首页 / 我的默认显示，探索 / 行程 / 附近默认隐藏</b>。
     *       后三个是腾讯的运营/推广页，不是地图功能；</li>
     *   <li>{@link #DEFAULT_OFF} 里的键 —— 推广 / 运营模块，默认隐藏
     *       （首页推荐流、地点详情「大家都在看」、搜索框上方 chip 行、「我的」页的积分 /
     *       小程序 / 活动专区）；</li>
     *   <li>{@code tool_<id>} —— 交给 {@link #defaultToolVisible}，只有
     *       {@link #DEFAULT_ON_TOOLS} 那批内置工具默认显示；</li>
     *   <li>其余（首页 / 我的、搜索、导航、地图图标、家/公司、收藏、车辆…）默认显示 ——
     *       这些是功能位，模块挂不上时界面保持原样。</li>
     * </ol>
     *
     * <p>★ 2.3.1 修复「全新安装底栏仍是四个 tab、chip 行还在」：以前本方法只对
     * {@code tool_*} / debug 生效，其余一律 true；而内置 JSON 里 {@code tab_*} 根本不存在、
     * {@code ui_seek_chips} 等又是 {@code null}（跳过不写）。两侧叠加的结果就是干净安装
     * 等于「什么都没精简」。现在本表与 {@code res/raw/default_config.json} 的
     * {@code tmapclean_config} 组逐键对齐，hook 侧也统一走 {@link Cfg#visible(String)}。
     */
    public static boolean defaultVisible(String key) {
        if (key == null) return true;
        if (Cfg.K_DEBUG.equals(key)) return false;
        if (K_TOOL_PRESET.equals(key)) return false;
        if (DEFAULT_OFF.contains(key)) return false;
        if (key.startsWith(K_TAB)) {
            // 功能位：首页（返回地图的锚点）与「我的」。
            return (K_TAB + "homePage").equals(key) || (K_TAB + "mePage").equals(key);
        }
        if (key.startsWith(K_TOOL)) return defaultToolVisible(key.substring(K_TOOL.length()));
        return true;
    }

    /**
     * 默认隐藏的推广 / 运营位。
     *
     * <p>与 {@code res/raw/default_config.json} 的 {@code tmapclean_config} 组保持一致：
     * 资源里这些键写的是 {@code false}，这里返回 {@code false}。改任何一边都要同步另一边，
     * 否则会出现「面板显示关、实际没关」（或反过来）。
     */
    private static final java.util.Set<String> DEFAULT_OFF =
            java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(
                    java.util.Arrays.asList(
                            K_SEEK_CHIPS,          // 搜索框上方 chip 行（问一问 / 厕所 / 超市 / 美食）
                            K_HOME_FEED,           // 首页下方推荐信息流卡片
                            K_LOOK_AND_LOOK,       // 地点详情「大家都在看」
                            K_MY_POINTS,           // 我的 · 积分中心
                            K_MY_MINIPROGRAM,      // 我的 · 小程序
                            K_MY_ACTIVITY_ZONE     // 我的 · 活动专区
                    )));

    /**
     * 常用预设保留几个入口。
     *
     * <p>取 5 而不是 4：宫格是 5 列起步（{@code gcp.a()} 在单行模式下返回 5），
     * 留一个余量给服务端偶发多下发的一项，避免「就差一个凑成一行」。
     * 真机默认偏好集是 14 项，裁到 5 之后第 6 个起进「更多工具」。
     */
    public static final int TOOL_PRESET_COUNT = 5;

    /**
     * 默认打开的工具 —— 腾讯 11.7.0 首页宫格实测的那 15 格。
     *
     * <h3>证据</h3>
     * 用户真机截图（11.7.0）首页宫格逐格文案：
     * <pre>
     * 驾车 / 公交地铁 / 问一问 / 打车 / 骑行
     * 摩托车 / 离线地图 / 通勤设置 / 实时公交 / 位置分享
     * 积分兑礼 / 步行 / 电子狗 / 自制车标 / 更多
     * </pre>
     * 对应 {@code Tool.name} 依次为（前 14 个都能在 {@link #BUILTIN} 里查到，
     * 第 15 个「更多」是合成 Cell，本次才补进 BUILTIN）：
     * <pre>
     * car bus aidingdang taxi cycle
     * motor offline commuting_set etabus locationShare
     * signin walk edog zizhichebiao more
     * </pre>
     * 交叉验证：腾讯自己的缺省偏好队列（{@code com.tencent.map.navisdk.a.gas#m()}，
     * classes14.dex，{@code map_app:preferred_tool_names} 为空时返回的硬编码 13 项）是
     * <pre>
     * car bus buscode freshevents taxi aidingdang walk cycle offline commuting_set
     * jiayou signin edog
     * </pre>
     * —— 与上面同属一套 latin id 命名空间，其中 12 项直接重合，
     * 说明宫格确实按这套 id 过滤，而不是按中文文案。
     *
     * <h3>为什么改成"白名单默认开"而不是以前的"一律默认开"</h3>
     * 用户的要求是：<b>首页那 15 格默认打开，其它默认关闭</b>，且每格都要有独立开关。
     * 以前 {@code defaultToolVisible()} 恒返回 true，于是 78 项工具在面板里全显示为"开"，
     * 用户没法表达"我只要首页那几格"。
     *
     * <h3>⚠ 这里踩过一个大坑，务必看完再改</h3>
     * 曾经把这里改成白名单（{@code car/bus/aidingdang/taxi/more}）并让未设置项按"关"处理，
     * 结果真机上<b>整个工具栏宫格直接消失</b>。原因是过滤发生在
     * {@code TmapTools#filterData()}：宫格里每一格都被问了一次
     * {@code Cfg.toolVisible(id)}，凡是不在白名单里的格子一律被摘掉；
     * 一旦实际格子用的 id 与白名单不是同一套（例如取到了 {@code title} 而不是 {@code name}），
     * 就是"全摘" —— 宫格空掉。
     *
     * <p>所以本表的正确性必须用<b>真机 id</b>核对，不能靠猜。核对手段已经内置：
     * 打开面板里的「调试日志」后，{@code TmapTools#filterData()} 会把宫格实际用到的
     * id 打到 logcat（{@code grid_cells=…}），并与本表逐一比对。
     * 另外 {@code filterData()} 有一道兜底：若按缺省过滤会把宫格清空、而用户并没有
     * 显式关掉其中任何一格，则原样放行（见 {@code tools_filter=defaults_would_empty_grid_kept_original}）。
     */
    private static final String[] DEFAULT_ON_TOOLS = {
            // 第一行
            "car", "bus", "aidingdang", "taxi", "cycle",
            // 第二行
            "motor", "offline", "commuting_set", "etabus", "locationShare",
            // 第三行
            "signin", "walk", "edog", "zizhichebiao", "more",
    };

    private static final java.util.Set<String> DEFAULT_ON_IDS =
            java.util.Collections.unmodifiableSet(
                    new java.util.LinkedHashSet<String>(
                            java.util.Arrays.asList(DEFAULT_ON_TOOLS)));

    /**
     * 某个工具缺省是否显示。
     *
     * <p>{@link #DEFAULT_ON_TOOLS} 里的内置工具 → {@code true}（显示）；
     * 其余（服务端新下发、本模块还不认识）→ {@code false}（隐藏）。
     *
     * <p>认不出 id 时返回 true：{@code TmapTools#cellId()} 取不到名字的格子不能被误摘。
     *
     * <p>⚠ 钩子侧 {@code Cfg.toolVisible()} 与面板侧 {@code TmapPanel.read()} 都读这里，
     * 只有一处缺省，界面与实际行为不可能再漂移。
     */
    public static boolean defaultToolVisible(String toolId) {
        if (toolId == null || toolId.length() == 0) return true;   // 认不出 → 保留
        return DEFAULT_ON_IDS.contains(toolId);
    }

    /** 缺省打开的那批工具 id（只读，供核对表/诊断使用）。 */
    public static String[] defaultOnTools() { return DEFAULT_ON_TOOLS.clone(); }

    /**
     * 设置页 / 悬浮窗显示某个开关时该用的缺省值。
     *
     * <p>与 {@link #defaultVisible} 的区别只有一处，但那一处会直接误导用户：
     * <b>反转开关打开时，没设置过的 {@code tool_} 键必须显示为「关」</b>。
     * 否则界面说这个工具是开着的、Hook 侧却按"未设置 = 关闭"把它摘掉 ——
     * 用户看到的就是"开关是开的，工具却不见了"。
     */
    public static boolean defaultChecked(String key, Read r) {
        if (key == null) return true;
        return defaultVisible(key);
    }

    // ── 工具目录 ────────────────────────────────────────────────────────
    /**
     * 腾讯首页工具宫的**已知目录**（{@code Tool.name} + 显示文案）。
     *
     * <p>为什么要有这份内置表：工具列表是服务端下发的，安装包里没有静态目录
     * （{@code res/**}{@code /tools_ic_*} 只剩 4 个兜底图标）。第一版只靠 hook 侧"学"回来，
     * 结果用户第一次打开设置页时目录还没学到 → 逐项开关一个都不显示 →
     * 用户看到的结论就是"工具宫格自定义没做"。
     *
     * <p>本表实证自真机 11.7.0 进程回报（{@code tools_catalog}，78 项），
     * 学习结果会与之合并：学到的覆盖内置的，新增的自动追加。
     */
    private static final String BUILTIN =
            "car=驾车,taxi=打车,bus=公交地铁,etabus=实时公交,cycle=骑行,walk=步行,"
            + "motor=摩托车,running=运动,commuting_set=通勤设置,offline=离线地图,"
            + "buscode=乘车码,huochepiao=火车票,qichepiao=汽车票,jipiao=飞机票,"
            + "danche=共享单车,zuche=租车,subway=地铁图,locationShare=位置分享,"
            + "new_energy=新能源,shunfengche=顺风车,truck=货车,cruise=驾车巡航,"
            + "qishoudaohang=骑手导航,jingwaitaxi=境外用车,edog=电子狗,tingchejilu=停车记录,"
            + "jiayou=优惠加油,chongdianzhuang=充电桩,daijia=叫代驾,yangche=汽车保养,"
            + "chexian=车险,xianxingchaxun=限行查询,newETC=办理ETC,ETCP=ETCP,"
            + "nuochema=挪车码,xindiantu=新电途,fabipei=你用我赔,woyaomaiche=我要卖车,"
            + "cheliangguzhi=车辆估值,cityevents=去哪儿玩,freshevents=新鲜事,"
            + "xiaochengxu_fuwu=小程序,paotuiqusong=跑腿取送,maiyao=买药,guahao=挂号,"
            + "movie=电影票,banjia=拉货搬家,newweather=天气,XIEcheng=校园特权卡,"
            + "meishi=美食,jiudian=酒店,jingdian=景点,repair=小修小补,liren=丽人,"
            + "yinhang_shouye=银行服务,naicha_diandian=奶茶点单,crowding=人流量预测,"
            + "reserve=景区预约,ebtyj=鹅家守护,itinerary=指南,footprint=足迹,"
            + "favorites=收藏,theme=特色主题,gift=活动专区,voice=导航语音,signin=积分兑礼,"
            + "didianyanzheng=地点验证,business=商户中心,xqchebiao=心情车标,"
            + "syfukemix=DIY语音包,zizhichebiao=自制车标,chatmap=地图生成器,"
            + "aidingdang=问一问,paizhaozhuanqian=拍照赚钱,smart_parse=提取攻略,"
            // 「更多」不是服务端下发的工具，是 gaa 在宫格排满时插入的**合成** Cell，
            // 它的 Tool.name 就是字面量 "more"（见 TmapTools#installMoreFilter：
            // "more".equals(cellId(cell)) 与 Cfg.toolVisible("more")）。
            // 之前漏在表外，所以宫格第 15 格「更多」在面板里没有任何开关。
            + "more=更多";

    private static volatile String[] TOOL_IDS;
    private static volatile String[] TOOL_TITLES;

    /** 内置目录 + 学到的目录合并（学到的优先，新增的追加）。 */
    private static synchronized void ensureTools() {
        if (TOOL_IDS != null) return;
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<String, String>();
        String[] parts = BUILTIN.split(",");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            map.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        String[] learnedIds = LEARNED_IDS;
        String[] learnedTitles = LEARNED_TITLES;
        if (learnedIds != null) {
            for (int i = 0; i < learnedIds.length; i++) {
                String title = learnedTitles != null && i < learnedTitles.length
                        ? learnedTitles[i] : learnedIds[i];
                map.put(learnedIds[i], title);
            }
        }
        TOOL_IDS = map.keySet().toArray(new String[0]);
        TOOL_TITLES = map.values().toArray(new String[0]);
    }

    private static volatile String[] LEARNED_IDS;
    private static volatile String[] LEARNED_TITLES;

    /** 设置页收到 hook 回报后调用：把真实工具目录交给本表（与内置表合并）。 */
    public static synchronized void learnTools(String[] ids, String[] titles) {
        if (ids == null || ids.length == 0) return;
        LEARNED_IDS = ids;
        LEARNED_TITLES = titles != null && titles.length == ids.length ? titles : ids;
        TOOL_IDS = null;                 // 触发重新合并
        // ⚠ CATS 也必须一起失效：它是整棵设置树的缓存，只在第一次 cats() 时构建一次。
        // 面板是先建树、后收到回报的（回报要等真实进程把工具目录发回来），
        // 只清 TOOL_IDS 的话，学到的工具要等进程重启才会出现在逐项开关里 ——
        // 用户看到的仍然是「有些格子没有开关」。
        CATS = null;
        ensureTools();
    }

    public static synchronized String[] toolIds() { ensureTools(); return TOOL_IDS; }
    public static synchronized String[] toolTitles() { ensureTools(); return TOOL_TITLES; }

    // ── 设置页目录 ──────────────────────────────────────────────────────
    public static final class Row {
        /** 配置键；null 表示纯说明行（不可点） */
        public final String key;
        public final String title;
        public final String note;
        /** true = 整块总开关 */
        public final boolean master;

        Row(String key, String title, String note, boolean master) {
            this.key = key;
            this.title = title;
            this.note = note;
            this.master = master;
        }
    }

    public static final class Cat {
        public final int id;
        public final String title;
        public final String subtitle;
        public final Row[] rows;

        Cat(int id, String title, String subtitle, Row[] rows) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.rows = rows;
        }
    }

    public static final int CAT_BAR = 1, CAT_TOOLS = 2, CAT_HOME = 3, CAT_CONTENT = 4,
            CAT_MINE = 5, CAT_MAP = 6, CAT_DIAG = 7;

    private static Cat[] CATS;

    public static synchronized Cat[] cats() {
        if (CATS != null) return CATS;

        Row[] bar = new Row[1 + TABS.length];
        bar[0] = new Row(K_BAR, "显示底部入口栏", "勾选表示显示；隐藏后剩余入口自动均分。", true);
        for (int i = 0; i < TABS.length; i++) {
            bar[i + 1] = new Row(K_TAB + TABS[i][0], "显示「" + TABS[i][1] + "」",
                    "homePage".equals(TABS[i][0])
                            ? "首页是地图的返回锚点：隐藏后仍可用返回键回到地图，但底栏不再有主页入口。"
                            : null, false);
        }

        String[] ids = toolIds();
        String[] titles = toolTitles();
        // 布局：[0]=总开关 [1]=只保留常用入口 [2..]=逐项工具（未学到时 [2] 是占位提示）
        //
        // 这里曾经少算一格：数组开 1+ids.length，preset 写进 [1]，随后又被
        // 「尚未学到工具目录」和 for 循环的 tools[i+1]（i=0 → [1]）覆盖 —— 两条分支
        // 都会盖掉它。结果是「只保留常用入口」这个开关在**任何情况下都不显示**，
        // 而它的键 K_TOOL_PRESET 有 hook 在消费，属于用户永远调不到的功能。
        Row[] tools = new Row[2 + ids.length + (ids.length == 0 ? 1 : 0)];
        tools[0] = new Row(K_TOOLS, "显示首页工具宫格",
                "关闭后首页不再显示驾车 / 公交地铁 / 打车等工具入口。", true);
        tools[1] = new Row(K_TOOL_PRESET, "只保留常用入口（前 " + TOOL_PRESET_COUNT + " 个）",
                "腾讯首次会把十几个偏好工具写进首页，把宫格填满。开启后按它自己的顺序只留前 "
                        + TOOL_PRESET_COUNT + " 个，其余收入「更多工具」。", true);
        if (ids.length == 0) {
            tools[2] = new Row(null, "尚未学到工具目录",
                    "打开腾讯地图首页（工具宫格出现一次）后回到本页即可看到逐项开关。", false);
        } else {
            for (int i = 0; i < ids.length; i++) {
                String label = titles[i] == null || titles[i].length() == 0 ? ids[i] : titles[i];
                tools[i + 2] = new Row(K_TOOL + ids[i], "显示「" + label + "」",
                        label.equals(ids[i]) ? null : "工具 ID：" + ids[i], false);
            }
        }

        CATS = new Cat[]{
                new Cat(CAT_BAR, "底部入口栏", "首页 / 探索 / 行程 / 我的。勾选为显示；隐藏后剩余入口均分。", bar),
                new Cat(CAT_TOOLS, "首页工具宫格",
                        "按工具 ID 逐项控制。关闭整块时所有工具入口一起隐藏。", tools),
                new Cat(CAT_HOME, "首页通勤地址与信息流",
                        "「设置家 / 设置公司」那一行，以及首页下方那一串推荐卡片（打车券 / 添加桌面 / 新人福利…）。",
                        new Row[]{
                        new Row(K_HOME_FAV, "显示「设置家 / 设置公司」", null, true),
                        new Row(K_HOME_FEED, "显示下方推荐信息流卡片",
                                "关掉后首页下方那一串运营卡片不再出现。", true)}),
                new Cat(CAT_CONTENT, "内容模块", "按数据层拦截，关闭后模块不再渲染，不留白。", new Row[]{
                        new Row(K_LOOK_AND_LOOK, "地点详情「大家都在看」",
                                "关掉后地点详情页不再出现「看过「X」的人还看过」推荐模块。", true)}),
                new Cat(CAT_MINE, "「我的」页",
                        "按卡片数据层拦截（模板 81/82/76/84/41），关闭后组件不会创建，不留空卡片。", new Row[]{
                        new Row(K_MY_POINTS, "积分中心", null, true),
                        new Row(K_MY_FAVORITES, "收藏所在快捷入口整排", "关闭后整排与占位一起消失。", true),
                        new Row(K_MY_PERSONALIZATION, "个性化设置", null, true),
                        new Row(K_MY_MINIPROGRAM, "我的小程序", null, true),
                        new Row(K_MY_VEHICLE, "我的车辆", null, true),
                        new Row(K_MY_ACTIVITY_ZONE, "活动专区", null, true)}),
                new Cat(CAT_MAP, "主页地图图标",
                        "关闭的是应用层叠在地图上的那一条图标控件：图层 / 消息 / 上报 / 路况 / 2D-3D / "
                                + "指南针 / 加减号 / 定位 / 截图 / 分享 / 昼夜 / 版权 / 比例尺。"
                                + "（地图引擎画出来的兴趣点图标与店名由样式资源决定，Java 层没有开关，不在本项范围内。）",
                        new Row[]{
                        new Row(K_MAP_ICONS, "显示主页地图图标控件", null, true),
                        new Row(K_SEEK_CHIPS, "显示搜索框上方快捷入口", "问一问、厕所、超市、美食等横向入口。", true)}),
                new Cat(CAT_DIAG, "诊断", "腾讯地图 11.7.0 适配；部分开关改动后重开腾讯地图生效。", new Row[]{
                        new Row(Cfg.K_DEBUG, "调试日志", "把挂钩命中与工具目录输出到 logcat。", false)}),
        };
        return CATS;
    }

    public static Cat cat(int id) {
        Cat[] all = cats();
        for (int i = 0; i < all.length; i++) if (all[i].id == id) return all[i];
        return all[0];
    }

    /** 键值读取器：Hook 侧走 Cfg，App 侧走 App.read。 */
    public interface Read {
        boolean visible(String key);
    }

    public static final Read HOOK_READ = new Read() {
        @Override public boolean visible(String key) { return Cfg.visible(key, defaultVisible(key)); }
    };

    public static final Read APP_READ = new Read() {
        @Override public boolean visible(String key) { return App.read(key, defaultVisible(key)); }
    };

    /** 「实际上会不会被隐藏」——总开关关掉时子项一并视为隐藏，设置页计数才与真实行为一致。 */
    public static boolean effectiveVisible(String key, Read r) {
        if (key == null) return true;
        if (key.startsWith(K_TAB) && !r.visible(K_BAR)) return false;
        if (key.startsWith(K_TOOL) && !r.visible(K_TOOLS)) return false;
        return r.visible(key);
    }

    public static int hiddenCount(Cat c, Read r) {
        int n = 0;
        for (int i = 0; i < c.rows.length; i++) {
            Row row = c.rows[i];
            if (row.master || row.key == null) continue;
            if (!effectiveVisible(row.key, r)) n++;
        }
        return n;
    }

    private Spec() {}
}
