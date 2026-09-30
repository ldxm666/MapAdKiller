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
     * 各键的默认「可见」值。
     *
     * v0.4.0 起默认值跟着**能力**走：
     *   - 「我的」页总闸默认**开**（数据层方案失效安全，最坏情况是不生效，不会白屏）；
     *   - 广告/运营卡（{@link #K_MINE_OPS}）与热门活动（{@link #K_MINE_AD}）默认**关（隐藏）**
     *     —— 用户要的就是它们消失，其余区块默认保持原样。
     */
    public static boolean defaultVisible(String key) {
        if (K_MINE_OPS.equals(key) || K_MINE_AD.equals(key)) return false;
        return true;
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
     * 也就是说宫格内容是**服务端下发**的，行数/文案会变（这正是"第三行没有开关"的根因）。
     * 因此除了这份静态清单，设置页还会合并 hook 侧回报的**实际发现文案**（见 Spec.mergeDiscovered），
     * 未知文案在没被回报前一律保持可见（失效安全）。
     */
    public static final String[] TOOLS = {
            "驾车", "公共交通", "打车", "步行", "骑行",
            "实时公交", "订酒店", "问一问", "优惠加油", "更多",
            "顺风车", "爱去榜", "租车", "叫代驾", "录语音包",
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
        if (CATS != null) return CATS;
        Cat[] a = new Cat[7];

        Row[] bar = new Row[1 + TABS.length];
        bar[0] = new Row(K_BAR, "隐藏整个底部入口栏", "把 ufo_root 里整条入口栏摘掉（含中央长按说话覆盖层）", true);
        String[] tabNote = {
                "首页 tab（ufo_root/new_route）",
                "周边 tab（ufo_root/new_nearby）",
                "长按说话（new_third + home_ai_container 覆盖层，两者同进同退）",
                "打车 tab（ufo_root/new_fourth）",
                "我的 tab（ufo_root/new_user）",
        };
        for (int i = 0; i < TABS.length; i++) {
            bar[i + 1] = new Row(K_TAB + TABS[i], TABS[i], tabNote[i], false);
        }
        a[CAT_BAR] = new Cat(CAT_BAR, "底部入口栏", "首页最下方 5 个入口；隐藏后剩余自动均分重排", bar);

        Row[] tools = new Row[1 + allToolLabels().length];
        tools[0] = new Row(K_TOOLS, "隐藏整块工具宫格", "component_container3（含 row1/2/3 三排）", true);
        String[] labels = allToolLabels();
        for (int i = 0; i < labels.length; i++) {
            tools[i + 1] = new Row(K_TOOL + labels[i], labels[i], null, false);
        }
        a[CAT_TOOLS] = new Cat(CAT_TOOLS, "首页工具宫格",
                "逐格开关（宫格文案由服务端下发，实测三行共 15 格）", tools);

        Row[] hc = {
                new Row(K_HC, "隐藏整行", "component_container5（回家 + 去公司 + 去设置）", true),
                new Row(K_HC_HOME, "回家", "home_info 整条（图标 + 文案 + 路况）", false),
                new Row(K_HC_COMPANY, "去公司", "company_info 整条（图标 + 文案 + 路况）", false),
                new Row(K_HC_SETTING, "去设置", "未设置地址时右侧的「去设置」入口", false),
        };
        a[CAT_HC] = new Cat(CAT_HC, "回家 / 去公司", "首页中部那一行通勤入口", hc);

        Row[] wx = {
                new Row(K_WEATHER, "隐藏整块天气", "总开关（同时关掉下面两项）", true),
                new Row(K_WX_MAP, "地图右侧天气条", "原生 weather_limited（如「21°C 27 限行」）", false),
                new Row(K_WX_CARD, "底部行程/天气卡", "信息流首项；由百度 Talos 引擎渲染，按卡内文案 + 瀑布流结构定位整卡摘除", false),
        };
        a[CAT_WX] = new Cat(CAT_WX, "天气 / 行程栏", "两处天气展示：地图浮层 + 底部卡片", wx);

        Row[] diag = {
                new Row(Cfg.K_DEBUG, "调试日志（debug_log）", "打开后 logcat 里会打出每一条规则的命中/未命中，以及宫格实际文案", false),
        };
        a[CAT_DIAG] = new Cat(CAT_DIAG, "诊断", "钩子状态 / 锚点命中统计 / 排错指引", diag);

        // 展开态首页（面板上拉）才显示；都是 Talos 渲染，没有资源 id，按结构 + 文案锚点定位
        Row[] feed = {
                new Row(K_FEED, "隐藏整块推荐信息流", "component_container6：频道栏 + 优质内容精选 + 全部推荐卡片一次清空", true),
                new Row(K_FEED_CHIPS, "频道栏", "「推荐 / 看世界 / 成都市 …」那一行（同容器命中 ≥2 个锚点才动手）", false),
                new Row(K_FEED_QUALITY, "优质内容精选 + 推荐卡片", "从「优质内容精选」标题起，把它和它下面的卡片一并摘除", false),
        };
        a[CAT_FEED] = new Cat(CAT_FEED, "推荐信息流", "首页面板往下拉后出现的内容区（频道栏 + 推荐卡片）", feed);

        Row[] mine = {
                new Row(K_MINE_APPLY, "启用「我的」页精简",
                        "总闸（默认开）。v0.4.0 起走**数据层**：在 Talos 小程序收到的 JSON 里摘字段，"
                                + "卡片的 s-if 恒假 → 组件根本不创建 → 布局由 Yoga 重算，无留白、不误伤。", true),
                new Row(K_MINE_OPS, "广告/运营卡",
                        "天天领钱 · 出行保+借钱 · 我的店铺 · 导航车标 —— 这四张在页面 JS 里共用同一个闸门 phoneInfo.gk（默认关）", false),
                new Row(K_MINE_AD, "热门活动 / 资源位",
                        "清空 qt=ads 接口下发的 campaign / banner 数组（默认关）", false),
                new Row(K_MINE_VOICE, "热门语音",
                        "数据层摘 serverData.voice_card / voice（s-if 闸门就是它，摘了卡片不渲染）", false),
                new Row(K_MINE_CAR, "我的车",
                        "数据层摘 serverData.car（s-if 闸门就是它）", false),
                new Row(K_MINE_CARNAV, "导航车标（区块）",
                        "与「广告/运营卡」同一闸门；关任一个即生效", false),
                new Row(K_MINE_BUILD, "全民共建 / 反馈中心",
                        "JS 层：把 'contribution' 从页面 cardList 摘掉（该卡唯一闸门是 isCarPlay，"
                                + "旧方案会把页头变成车机文案，v0.4.3 已改走 JS，页头不动）", false),
                new Row(K_MINE_SPORT, "百度运动",
                        "JS 层：把 'sport' 从 page 的 cardList 摘掉（数据层无闸门：s-if 只认 showSportCard←版本号）", false),
                new Row(K_MINE_GRID, "顶部图标宫格",
                        "JS 层：宫格图标对象不入列表（整块关）。宫格是包内 JS 静态表 + 版本号过滤，数据层够不到", false),
        };
        a[CAT_MINE] = new Cat(CAT_MINE, "「我的」页",
                "v0.4.2：数据层过滤。可关：广告/运营卡（天天领钱·出行保+借钱·我的店铺·导航车标）、"
                        + "热门活动/资源位、热门语音、我的车、全民共建。"
                        + "暂不可关：顶部图标宫格（包内 JS 静态表 + 版本号过滤）、百度运动"
                        + "（s-if 里是 phoneInfo.sv 版本号判断，改它会把整页请求参数也改掉）。", mine);

        CATS = a;
        return CATS;
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
        if (K_WX_MAP.equals(key) || K_WX_CARD.equals(key)) {
            if (!r.visible(K_WEATHER)) return false;
        }
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
