package io.github.ldxm666.bmapclean;

/**
 * 配置规格 —— **设置页与 Hook 侧共用同一份键名**，杜绝两侧漂移。
 *
 * 键值语义统一为「可见」布尔：
 *   true  = 显示（缺省）
 *   false = 隐藏
 * 总开关与逐项开关同语义，只是作用域不同（整块 / 单个入口）。
 *
 * 与 MapAdKiller（高德）的区别刻意做在这里：
 *   高德是一页长列表（十几个分组堆在 MainActivity 里），
 *   本模块是**两级**：MainActivity 只列分类，点进去 CategoryActivity 才看到该分类的项。
 */
public final class Spec {

    // ── 键名 ───────────────────────────────────────────────────────────
    /** 底部五入口栏 整栏总开关 */
    public static final String K_BAR = "ui_bottom_bar";
    /** 单 tab：K_TAB + 标签文本 */
    public static final String K_TAB = "tab_";
    /** 工具宫格 整块总开关 */
    public static final String K_TOOLS = "ui_tool_grid";
    /** 首页工具面板排成一行、压掉第二行空白。true = 已优化。 */
    public static final String K_HOME_TOOLS_COMPACT = "home_tools_compact";
    /** 单格：K_TOOL + 文案 */
    public static final String K_TOOL = "tool_";
    /** 回家/去公司 整行总开关 */
    public static final String K_HC = "ui_home_company";
    public static final String K_HC_HOME = "hc_home";
    public static final String K_HC_COMPANY = "hc_company";
    public static final String K_HC_SETTING = "hc_setting";
    /** 天气 总开关 */
    public static final String K_WEATHER = "ui_weather";
    /** 地图右侧原生天气条「21°C 27 限行」 */
    public static final String K_WX_MAP = "wx_map";
    /** 底部行程/天气卡（Talos 渲染） */
    public static final String K_WX_CARD = "wx_card";
    public static final String K_AI_NOW = "ai_now_card";
    public static final String K_MAP_POI = "map_poi";
    /** 地图浮层挂件：左下角「低碳能量」气泡 + 「一键穿越」浮标。 */
    public static final String K_ROUTE_WIDGET = "route_widget";
    /** 路线详情中间插入的横幅广告。 */
    public static final String K_ROUTE_AD = "route_ad";
    /** 路线页底部「探索××周边」三图推荐卡。 */
    public static final String K_ROUTE_EXPLORE = "route_explore";
    /** 路线页底部「特惠打车」领券条。 */
    public static final String K_ROUTE_TAXI = "route_taxi";
    /** 首页出行方式快捷格里的「古今地图」入口。 */
    public static final String K_ROUTE_GUJIN = "route_gujin";
    /** 搜索框下面那排快捷入口（美食 / 加油站 / 景点 / 厕所 / 酒店…）。默认显示。 */
    public static final String K_SEARCH_CHIPS = "search_chips";

    // ── 推荐信息流（展开态：频道栏 + 优质内容精选 + 推荐卡片，全部由 Talos 渲染） ──
    /** 整块推荐信息流 = component_container6 */
    public static final String K_FEED = "ui_feed";
    /** 频道栏「推荐 / 看世界 / 成都市 …」 */
    public static final String K_FEED_CHIPS = "feed_chips";
    /** 「优质内容精选」标题及其下方的推荐卡片 */
    public static final String K_FEED_QUALITY = "feed_quality";

    // ── 「我的」页（user_center_talos_container，同样全是 Talos 渲染） ──────
    /** 顶部图标宫格：收藏/历史行程/语音包/导航车标/臻享会员 + 订单/街景打卡/度小满/我的店铺/离线地图 */
    public static final String K_MINE_GRID = "mine_grid";
    public static final String K_MINE_VOICE = "mine_voice";
    public static final String K_MINE_CAR = "mine_car";
    /** 「导航车标」区块（与宫格里的同名入口是两回事） */
    public static final String K_MINE_CARNAV = "mine_carnav";
    public static final String K_MINE_SPORT = "mine_sport";
    public static final String K_MINE_BUILD = "mine_build";
    /**
     * 下面这些键直接对应**服务端 JSON 字段 / 原生下发字段**（键名全部实证自 bundle 里的真实引用），
     * 由 {@link MineData} 在数据层摘除，而不是在视图层隐藏：
     *   car           → 我的车（s-if 闸门就是 serverData.car）
     *   voice_card    → 热门语音（s-if 闸门是 serverData.voice_card）
     *   phoneInfo.gk  → 天天领钱 / 出行保+借钱 / 我的店铺 / 导航车标 **四张卡共用这一个闸门**
     *   qt=ads 的 campaign / banner 数组 → 热门活动 / 资源位
     */
    public static final String K_MINE_OPS = "mine_ops";
    public static final String K_MINE_AD = "mine_ad";

    /**
     * 「我的」页规则**总闸** —— **默认关闭（熔断后回退）**。
     *
     * 事故记录（同一处的三次回归，已触发两次熔断规则）：
     *   ① v0.1.7 `sectionRoot` 阈值单位错误 → 整页根被当区块摘除 = 白屏；
     *   ② v0.1.8 宫格被"壳"容器连坐误伤；
     *   ③ v0.3.0 加了 `restackColumn`（translationY 手工竖向重排）后**整页被清空**。
     * 结论：该页由 Talos/Yoga 自绘，**视图层**（GONE / 移除 / translation 重排）无法可靠地
     * 做到「隐藏且不留白」；正确做法是改 Talos 的**数据/模型层**，让被清的区块根本不进布局。
     * 在那条链做出来并验证之前，本模块不碰这一页。
     */
    public static final String K_MINE_APPLY = "mine_apply";

    /**
     * 地点详情页（长按地图 / POI 详情）三块推广位。默认**已移除**。
     *
     * <p>这一页是本地 H5 模板（{@code cards/*.umd.min.js} 一张卡一块），
     * 摘除点在 {@code js/app.*.js} 的 Vuex {@code setPageData} 入参上，
     * 见 {@link BaiduPoiAds}。等价于原生列表的 {@code getItemCount}：
     * 卡片不进 groupDataList ⇒ {@code v-for} 连占位壳都不生成，天然不留白。
     */
    public static final String K_POI_GUESS = "poi_guess_search";
    public static final String K_POI_REC = "poi_nearby_rec";
    public static final String K_POI_CARDS = "poi_nearby_cards";
    /** 第二排：在线房源 / 周边小区·周边新楼盘 / 社区生活 */
    public static final String K_POI_HOUSE = "poi_house_listing";
    public static final String K_POI_ESTATE = "poi_nearby_estate";
    public static final String K_POI_LIFE = "poi_community_life";
    /** 第三排：详情页里零散的运营/推广位（带「广告」角标的那类）。 */
    public static final String K_POI_MISC = "poi_misc_promo";

    /**
     * 各键的默认「可见」值。
     *
     * v0.4.0 起默认值跟着**能力**走：
     *   - 「我的」页总闸默认**开**（数据层方案失效安全，最坏情况是不生效，不会白屏）；
     *   - 广告/运营卡（{@link #K_MINE_OPS}）与热门活动（{@link #K_MINE_AD}）默认**关（隐藏）**
     *     —— 用户要的就是它们消失，其余区块默认保持原样。
     */
    public static boolean defaultVisible(String key) {
        // 首页工具面板紧凑排列：默认**关**。上一版把父容器高度压塌过（图标被裁、压到下一行），
        // 真机确认干净之前不上线。
        if (K_HOME_TOOLS_COMPACT.equals(key)) return false;
        if (K_MINE_OPS.equals(key) || K_MINE_AD.equals(key) || K_AI_NOW.equals(key)) return false;
        // 路线页的浮层/推荐位：用户要的就是它们消失，默认关（隐藏）。
        if (K_ROUTE_WIDGET.equals(key) || K_ROUTE_AD.equals(key)
                || K_ROUTE_EXPLORE.equals(key) || K_ROUTE_TAXI.equals(key)
                || K_ROUTE_GUJIN.equals(key)) return false;
        // 地点详情页的推广位：用户要的就是它们消失，默认关（隐藏）。
        if (K_POI_GUESS.equals(key) || K_POI_REC.equals(key) || K_POI_CARDS.equals(key)
                || K_POI_HOUSE.equals(key) || K_POI_ESTATE.equals(key)
                || K_POI_LIFE.equals(key) || K_POI_MISC.equals(key)) return false;
        // 地图标注：总开关 + 13 个分类 + 长尾桶，默认全部「关（隐藏）」。
        // 这里必须和 BaiduLabelSettings.rows() 的默认值、BaiduPoiLayer.hiddenCategories()
        // 的缺省值保持一致 —— 三处任何一处不同，设置页显示「开」而实际在隐藏。
        if (isPoiKey(key)) return false;
        return true;
    }

    /** 是不是「地图标注」那一族键（总开关 / 13 个分类 / 长尾桶）。 */
    public static boolean isPoiKey(String key) {
        if (K_MAP_POI.equals(key) || BaiduLabelSettings.KEY_OTHER.equals(key)) return true;
        for (BaiduLabelSettings.Cat c : BaiduLabelSettings.CATS) if (c.key.equals(key)) return true;
        return false;
    }

    /** 运营卡闸门：两者任一被关就摘 phoneInfo.gk（JS 里这四张卡共用它） */
    public static boolean opsHidden() {
        return !Cfg.visible(K_MINE_OPS, defaultVisible(K_MINE_OPS))
                || !Cfg.visible(K_MINE_CARNAV, defaultVisible(K_MINE_CARNAV));
    }

    /** 「我的」页规则是否启用（默认开；关掉则整页完全不受影响） */
    public static boolean mineEnabled() {
        return Cfg.visible(K_MINE_APPLY, defaultVisible(K_MINE_APPLY));
    }

    /**
     * 「我的」页**强制清除**（不给开关，属于广告/运营卡）：
     * 西湖问答、天天领钱、出行保、借钱、热门活动。
     * 每项是一组文案锚点：同一容器内命中全部锚点才算该卡（出行保/借钱左右并排，所以同组出现）。
     */
    public static final String[][] MINE_FORCE = {
            {"西湖属于哪个省？", "答对领钱"},
            {"天天领钱"},
            {"出行保", "借钱"},
            {"热门活动"},
    };

    /**
     * 「我的」页顶部图标宫格的文案（真机 TREE 实测）。
     * 宫格整块 = 同时含 ≥6 个这些文案、且**不含任何 MINE_SECTION_ANCHORS** 的容器。
     */
    public static final String[] MINE_GRID_TEXTS = {
            "收藏", "历史行程", "语音包", "臻享会员", "订单",
            "街景打卡", "度小满", "我的店铺", "离线地图",
    };

    /**
     * 我的页各区块的标题锚点 —— 用来做**消歧**：
     * 候选容器如果同时含别的区块锚点，说明它是包了好几块的壳，一律拒绝
     * （v0.1.7 白屏/误伤就是被这种"壳"命中的）。
     *
     * 实测修正：「全民共建」那一块的标题是**图片徽章**，TextView 里只有「反馈中心」，
     * 所以它的锚点必须用「反馈中心」——用「全民共建」永远匹配不到（用户实测关不掉）。
     */
    public static final String[] MINE_SECTION_ANCHORS = {
            "天天领钱", "出行保", "借钱", "热门活动",
            "热门语音", "我的车", "百度运动", "反馈中心",
    };

    // ── 分类 id ────────────────────────────────────────────────────────
    public static final int CAT_BAR = 0;
    public static final int CAT_TOOLS = 1;
    public static final int CAT_HC = 2;
    public static final int CAT_WX = 3;
    public static final int CAT_DIAG = 4;
    public static final int CAT_FEED = 5;
    public static final int CAT_MINE = 6;
    public static final int CAT_MAP = 7;
    public static final int CAT_SEARCH = 8;
    public static final int CAT_ROUTE = 9;
    public static final int CAT_POI = 10;

    /** 频道栏文案锚点（需同一容器内同时命中 ≥2 个才算频道栏，避免误伤卡片文案） */
    public static final String[] CHIP_ANCHORS = {"推荐", "看世界", "成都市"};
    /** 优质内容精选标题锚点 */
    public static final String QUALITY_ANCHOR = "优质内容精选";

    /** 底部栏 5 个入口（截图 + 真机树实证的顺序） */
    public static final String[] TABS = {"首页", "周边", "长按说话", "打车", "我的"};

    /**
     * 工具宫格文案。
     *
     * 真机取证（v0.1.5 probe，把每个格子的文案打进 logcat）实测三行是：
     *   row1 = 驾车 / 公共交通 / 打车 / 步行 / 骑行
     *   row2 = 实时公交 / 订酒店 / 问一问 / 优惠加油 / 顺风车 / 更多
     *   row3 = 爱去榜 / 租车 / 叫代驾 / 录语音包 / 更多
     *
     * <p>22.0.50 起宫格换了文案（真机截图实测）：
     *   row1 = 驾车 / 公共交通 / 打车 / 步行
     *   row2 = 智行 / 新能源 / 停车助手
     *   row3 = 新手导航 / 现金签到 / 测距
     * 新文案同样加进清单 —— 否则设置页不会为它们生成开关，格子就只能整块开关、不能单点控制。
     *
     * 也就是说宫格内容是**服务端下发**的，行数/文案会变（这正是"第三行没有开关"的根因）。
     * 因此除了这份静态清单，设置页还会合并 hook 侧回报的**实际发现文案**（见 Spec.mergeDiscovered），
     * 未知文案在没被回报前一律保持可见（失效安全）。
     */
    public static final String[] TOOLS = {
            "驾车", "公共交通", "打车", "步行", "骑行",
            "实时公交", "订酒店", "问一问", "优惠加油", "更多",
            "顺风车", "爱去榜", "租车", "叫代驾", "录语音包",
            // 22.0.50 新增
            "智行", "新能源", "停车助手", "新手导航", "现金签到", "测距",
    };

    /** hook 侧发现、经广播回报上来的额外格子文案（设置页合并展示） */
    private static final java.util.LinkedHashSet<String> DISCOVERED = new java.util.LinkedHashSet<>();

    /** 追加发现到的宫格文案；返回是否有变化（有变化需要让 CATS 缓存失效） */
    public static synchronized boolean mergeDiscovered(java.util.Collection<String> labels) {
        if (labels == null || labels.isEmpty()) return false;
        boolean changed = false;
        for (String s : labels) {
            if (s == null) continue;
            String t = s.trim();
            if (t.length() == 0 || t.length() > 12) continue;
            if (DISCOVERED.add(t)) changed = true;
        }
        if (changed) CATS = null;   // 让设置页下次重建分类时带上新文案
        return changed;
    }

    /** 静态清单 ∪ 已发现文案 */
    private static synchronized String[] allToolLabels() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (int i = 0; i < TOOLS.length; i++) all.add(TOOLS[i]);
        all.addAll(DISCOVERED);
        return all.toArray(new String[0]);
    }

    // ── 已发现文案的**持久化** ───────────────────────────────────────────
    // 广播接收（ReportReceiver）与面板渲染都在宿主进程里，但进程会被回收；
    // 只靠内存里的 DISCOVERED，重开百度后新格子的开关又会消失。所以落盘，
    // 进程启动时（BaiduHooks.install）读回。

    /** 与 ReportReceiver 同一个 SharedPreferences 文件名 */
    public static final String P_DISCOVERED = ReportReceiver.P;
    private static final String KEY_DISCOVERED = "discovered_tools";
    private static final String SEP = "\n";

    private static volatile android.content.Context sCtx;
    private static volatile boolean sLoaded;

    /** 宿主进程 Application（由 BaiduHooks.install 注入）；为 null 时退化为纯内存模式。 */
    public static void attach(android.content.Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
    }

    /** 把已发现文案落盘（逗号/换行分隔；文案里不会含换行） */
    public static synchronized void persistDiscovered() {
        android.content.Context c = sCtx;
        if (c == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : DISCOVERED) { if (sb.length() > 0) sb.append(SEP); sb.append(s); }
            c.getSharedPreferences(P_DISCOVERED, android.content.Context.MODE_PRIVATE)
                    .edit().putString(KEY_DISCOVERED, sb.toString()).commit();
        } catch (Throwable t) {
            H.log(android.util.Log.WARN, MainHook.TAG, "discovered persist failed: " + t);
        }
    }

    /** 进程启动时读回已发现文案；只做一次。 */
    public static void loadDiscovered() {
        if (sLoaded) return;
        sLoaded = true;
        android.content.Context c = sCtx;
        if (c == null) return;
        try {
            String raw = c.getSharedPreferences(P_DISCOVERED, android.content.Context.MODE_PRIVATE)
                    .getString(KEY_DISCOVERED, "");
            if (raw == null || raw.length() == 0) return;
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            for (String s : raw.split(SEP)) if (s != null && s.length() > 0) list.add(s);
            if (mergeDiscovered(list)) {
                H.log(android.util.Log.INFO, MainHook.TAG,
                        "discovered tool labels restored: " + list.size());
            }
        } catch (Throwable t) {
            H.log(android.util.Log.WARN, MainHook.TAG, "discovered load failed: " + t);
        }
    }

    /**
     * 供 **hook 侧** 使用的同一份清单（静态清单 ∪ 已发现文案）。
     *
     * <p>必须与设置页用同一份，否则会出现「设置页有开关、勾了却不生效」：
     * 设置页按 {@link #allToolLabels()} 生成开关，hook 侧若只遍历静态 {@link #TOOLS}，
     * 那么新发现文案的键在 hook 侧根本不被查询，开关就是个摆设。
     */
    public static synchronized String[] toolLabels() {
        return allToolLabels();
    }

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

    private static Cat[] CATS;

    public static synchronized Cat[] cats() {
        if(CATS!=null)return CATS;
        Row[] bar=new Row[1+TABS.length];
        bar[0]=new Row(K_BAR,"显示底部入口栏","开关勾选表示显示；关闭表示隐藏。",true);
        for(int i=0;i<TABS.length;i++)bar[i+1]=new Row(K_TAB+TABS[i],TABS[i],i==0?"整栏显示时保留首页，确保可以返回地图。":null,false);
        String[] toolList = allToolLabels();
        Row[] tools=new Row[2+toolList.length];
        tools[0]=new Row(K_TOOLS,"显示首页工具宫格","关闭整块时，所有工具入口一起隐藏。",true);
        tools[1]=new Row(K_HOME_TOOLS_COMPACT,"紧凑排列（一行放下）","把「驾车 / 公共交通 / 更多…」收进同一行并压掉第二行空白；入口个数变化时自动适配。",false);
        for(int i=0;i<toolList.length;i++)tools[i+2]=new Row(K_TOOL+toolList[i],toolList[i],null,false);
        CATS=new Cat[]{
            new Cat(CAT_BAR,"底部入口栏","勾选为显示；隐藏后剩余入口均分。",bar),
            new Cat(CAT_TOOLS,"首页工具宫格","单独控制工具；保留原有点击功能。",tools),
            new Cat(CAT_HC,"回家 / 去公司","单独控制通勤地址入口。",new Row[]{
                new Row(K_HC,"显示通勤地址栏",null,true),new Row(K_HC_HOME,"回家",null,false),new Row(K_HC_COMPANY,"去公司",null,false),new Row(K_HC_SETTING,"去设置",null,false)}),
            new Cat(CAT_WX,"天气 / 行程栏","地图天气和首页卡片分别控制。",new Row[]{
                new Row(K_WEATHER,"显示天气 / 行程栏",null,true),new Row(K_WX_MAP,"地图天气条",null,false),new Row(K_WX_CARD,"底部行程 / 天气卡",null,false),
                new Row(K_AI_NOW,"AI此刻 / 首页主提示卡","包含 AI 天气和行程主提示；默认关闭，重开百度生效。",false)}),
            new Cat(CAT_FEED,"推荐信息流","独立控制频道入口和推荐内容。",new Row[]{
                new Row(K_FEED,"显示推荐信息流",null,true),new Row(K_FEED_CHIPS,"频道栏",null,false),new Row(K_FEED_QUALITY,"推荐内容",null,false)}),
            new Cat(CAT_MINE,"「我的」页","逐卡过滤；运营卡与导航车标各自控制。",new Row[]{
                new Row(K_MINE_APPLY,"启用「我的」页精简","关闭后还原该页原始内容。",true),
                new Row(K_MINE_OPS,"广告 / 运营卡","天天领钱、出行保、借钱、我的店铺。",false),
                new Row(K_MINE_AD,"热门活动 / 资源位",null,false),new Row(K_MINE_VOICE,"热门语音",null,false),
                new Row(K_MINE_CAR,"我的车",null,false),new Row(K_MINE_CARNAV,"导航车标（区块）",null,false),
                new Row(K_MINE_BUILD,"全民共建 / 反馈中心",null,false),new Row(K_MINE_SPORT,"百度运动",null,false),new Row(K_MINE_GRID,"顶部图标宫格",null,false)}),
            new Cat(CAT_MAP,"地图标注","按类型分别控制地图上的兴趣点图标与店名；地名、街道、城市名和路网始终保留。改动后需彻底关闭再打开百度。",BaiduLabelSettings.rows()),
            new Cat(CAT_SEARCH,"搜索区","搜索框下方那排快捷入口。",new Row[]{
                new Row(K_SEARCH_CHIPS,"显示搜索框快捷入口","关掉后搜索框下面那排（美食 / 加油站 / 景点 / 厕所 / 酒店…）整体隐藏。",true)}),
            new Cat(CAT_ROUTE,"路线页","地铁 / 公交方案详情页上的浮层与推荐位。改动后需彻底关闭再打开百度。",new Row[]{
                new Row(K_ROUTE_WIDGET,"地图浮层挂件","地图左下角的「低碳能量」气泡和「一键穿越」浮标。",false),
                new Row(K_ROUTE_AD,"横幅广告","路线详情中间插入的广告横幅（右下角带「广告」角标那条）。",false),
                new Row(K_ROUTE_EXPLORE,"探索周边","路线页底部「探索××周边」的三图推荐卡片。",false),
                new Row(K_ROUTE_TAXI,"特惠打车","路线页底部的「特惠打车」领券横幅。",false),
                new Row(K_ROUTE_GUJIN,"古今地图入口","首页出行方式快捷格里的「古今地图」那一格（与步行/骑行同一排）。",false)}),
            new Cat(CAT_POI,"地点详情页","长按地图 / 点开地点后的详情页里的推广位。摘除发生在页面数据层（卡片清单），不留空白。改动后需彻底关闭再打开百度。",new Row[]{
                new Row(K_POI_GUESS,"猜你想搜","搜索框下面那一整块：圆角词条 + 「问一问」。",false),
                new Row(K_POI_REC,"周边推荐","带分类 tab（推荐 / 美食 / 休闲娱乐…）的双列大图卡片，含「查看更多」。",false),
                new Row(K_POI_CARDS,"底部推荐附近","页面最下方那条推荐信息流连它的分类 tab 一起摘掉。",false),
                new Row(K_POI_HOUSE,"在线房源","带「咨询 / 电话」的房源卡片流和底部「查看全部 ›」。",false),
                new Row(K_POI_ESTATE,"周边小区 / 周边新楼盘","两个 tab 连切换条整块摘掉（不留一条空 tab）。",false),
                new Row(K_POI_LIFE,"社区生活","保洁清洗 / 家电维修 这一排两列入口卡。",false),
                new Row(K_POI_MISC,"其它运营 / 推广位","带「广告」角标的热门服务推荐、商户招募、流量包、好车推荐、同类好店。",false)}),
            new Cat(CAT_DIAG,"诊断","百度 22.1.0 (1660) 适配；改动后重启百度生效。",new Row[]{new Row(Cfg.K_DEBUG,"调试日志",null,false)})
        };return CATS;
    }

    public static Cat cat(int id) {
        Cat[] all = cats();
        for (int i = 0; i < all.length; i++) if (all[i].id == id) return all[i];
        return all[0];
    }

    /** 键值读取器：Hook 侧走 Cfg（XposedModule#getRemotePreferences），App 侧走 App.read（XposedService）。 */
    public interface Read {
        boolean visible(String key);
    }

    /** Hook 侧读取器 */
    public static final Read HOOK_READ = new Read() {
        @Override public boolean visible(String key) { return Cfg.visible(key, defaultVisible(key)); }
    };

    /** App 侧读取器 */
    public static final Read APP_READ = new Read() {
        @Override public boolean visible(String key) { return App.read(key, defaultVisible(key)); }
    };

    /**
     * 「实际上会不会被隐藏」——天气总开关关掉时，两个子项一并视为隐藏，
     * 这样设置页显示的计数与 Hook 侧真实行为一致。
     */
    public static boolean effectiveVisible(String key, Read r) {
        if(key==null)return true;
        if(key.startsWith(K_TAB)&&!r.visible(K_BAR))return false;
        if(key.startsWith(K_TOOL)&&!r.visible(K_TOOLS))return false;
        if((K_HC_HOME.equals(key)||K_HC_COMPANY.equals(key)||K_HC_SETTING.equals(key))&&!r.visible(K_HC))return false;
        if((K_WX_MAP.equals(key)||K_WX_CARD.equals(key)||K_AI_NOW.equals(key))&&!r.visible(K_WEATHER))return false;
        if((K_FEED_CHIPS.equals(key)||K_FEED_QUALITY.equals(key))&&!r.visible(K_FEED))return false;
        if(key.startsWith("mine_")&&!K_MINE_APPLY.equals(key)&&!r.visible(K_MINE_APPLY))return true;
        if((K_TAB+TABS[0]).equals(key))return true;
        return r.visible(key);
    }

    /** 该分类里被隐藏的项数（不含总开关自身），用于一级页摘要 */
    public static int hiddenCount(Cat c, Read r) {
        int n = 0;
        for (int i = 0; i < c.rows.length; i++) {
            Row row = c.rows[i];
            if (row.master) continue;
            if (!effectiveVisible(row.key, r)) n++;
        }
        return n;
    }

    private Spec() {}
}
