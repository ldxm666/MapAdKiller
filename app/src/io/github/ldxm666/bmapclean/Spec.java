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
    public static final String K_AI_NOW = "ai_now_card";
    public static final String K_MAP_POI = "map_poi";

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
        if (K_MINE_OPS.equals(key) || K_MINE_AD.equals(key) || K_AI_NOW.equals(key) || K_MAP_POI.equals(key)) return false;
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
    public static final int CAT_MAP = 7;

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
        if(CATS!=null)return CATS;
        Row[] bar=new Row[1+TABS.length];
        bar[0]=new Row(K_BAR,"显示底部入口栏","开关勾选表示显示；关闭表示隐藏。",true);
        for(int i=0;i<TABS.length;i++)bar[i+1]=new Row(K_TAB+TABS[i],TABS[i],i==0?"整栏显示时保留首页，确保可以返回地图。":null,false);
        Row[] tools=new Row[1+TOOLS.length];
        tools[0]=new Row(K_TOOLS,"显示首页工具宫格","关闭整块时，所有工具入口一起隐藏。",true);
        for(int i=0;i<TOOLS.length;i++)tools[i+1]=new Row(K_TOOL+TOOLS[i],TOOLS[i],null,false);
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
            new Cat(CAT_MAP,"地图标注","统一关闭兴趣点图标和店名，保留地名、街道和城市名称。修改后彻底关闭并重新打开百度生效。",BaiduLabelSettings.rows()),
            new Cat(CAT_DIAG,"诊断","百度 22.0.0 版本适配；改动后重启百度生效。",new Row[]{new Row(Cfg.K_DEBUG,"调试日志",null,false)})
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
