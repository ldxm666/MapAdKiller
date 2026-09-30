## v2.0.1（versionCode 201）—— 开关失效修复 + 首页广告位清除

### 修复（都是用户真机报的问题）

- **「我的车 / 导航车标 / 热门语音 / 热门活动」的开关失效**（v2.0.0 引入）：
  根因是 `phoneInfo.gk` 闸门 —— `gk !== '0'` 同时写在 **8 张卡的 s-if** 里，
  只要注入 `gk=0`，这些卡**自己的开关就被压住**（表现为"打开了还是不显示"）。
  现在只在「这几张卡本来就都该隐藏」时才注入 gk；各卡改由 per-card 过滤控制，
  广告/运营卡也一并从共享闸门改成**逐卡名**摘除。真机日志：`mine js` 补丁里
  卡片名逐个列出（`C.car/voice/carLogo/sport/contribution/common/...`）。
- **「全民共建 / 百度运动」关不掉**：JS 层注入被 App 新版 bundle 加载机制挡住
  （`biz_pkg_path` 只覆盖外壳与资源包；内容入口 `loadScriptFromString` 一次都没触发；
  连"写新文件 + 同步 pkginfo.json 的 md5"也不被认）。
  改为**视图层兜底**：按标题锚点（`百度运动` / `反馈中心`）整块摘卡，
  复用首页同款 `hideSection` 的三重保险（满宽 / 高度上限 / 禁词消歧）。
  真机验证：两张卡消失，页面其余区块与页头不受影响。

### 新增（用户要求）

- **首页左上角运营浮层清除**：「一键穿越 / 古今地图 / 新鲜事」那类贴图广告，
  判据是"可点击 + contentDescription 命中白名单 + 位于屏幕左上"，实测命中并 GONE；
  浮层是绝对定位的地图叠加层，GONE 不影响右侧「图层 / 反馈」按钮。
- **搜索框热词轮播钉死**：首页搜索框里的运营热词（"青城山门票 / 成都海昌极地海洋公园…"）
  不再轮播 —— 把 `tv_searchbar_title`（及其所属切换器子树）的文案钉成默认提示词
  「查找地点、公交、地铁」。不摘控件、不动搜索框。真机验证：60 秒后仍是默认词。
- 两项都进了设置页新的**「首页广告位」**分组，默认关（= 已清除），可随时打开恢复。

### 说明

- 开关语义不变：**true = 显示**。改完「我的」页开关重开该页即生效；首页开关强停 App 后生效。
- 高德 / 腾讯侧能力与 v2.0.0 一致，未改动。

## v2.0.0（versionCode 200）—— BMapClean 合并版

**这一版把另一个独立模块 BMapClean（百度地图界面精简）整体并入 MapAdKiller**，
模块 id / 应用名 / 签名全部保持不变（`io.github.ldxm666.mapadkiller`），所以是「就地升级」：
LSPosed 里的启用状态、作用域勾选、已有配置全部沿用，不用重装重勾。

### 新增：设置页左右滑动分页（用户要求）

原来的单页长列表按 App 拆成 **三页**，左右滑动切换，顶部标签可直接点：

| 页 | 内容 |
|---|---|
| **百度地图** | 首页底部入口栏 / 工具宫格 / 回家去公司 / 天气 / 推荐信息流 / 「我的」页（界面精简全部开关）+ 模块状态卡 |
| **高德地图** | 首页标签栏 / 工具宫格 / 推荐内容 / 「我的」页 / 搜索框内页（原有全部开关）+ 去广告卡片 |
| **其他** | 隐藏桌面图标 / 工具宫格排序 / 调试日志 / 去广告说明 |

### 新增：版本检查与更新提示（用户要求）

- 每次打开设置页自动查最新版本（**多源回退，国内无代理可用**）：
  `gh-proxy.com` 反代 GitHub API → jsDelivr CDN 取仓库 `module.prop` → jsDelivr fastly 节点 → GitHub 官方 API → gh-proxy 反代 raw。
  结果缓存 6 小时；点版本行可强制刷新；全部失败只显示「未获取」，**绝不弹错误框**。
- 界面顶部显示**当前版本 + 线上最新版本**：已是最新=**绿色**，有新版本=**黄色**（点击可重查）。
- 比本机新时弹更新提示，四个出口：**镜像下载**（gh-proxy，无代理可用）/ **GitHub 下载** / **Telegram 群** / **稍后更新**。
- 同时显示**最佳适配应用版本**并与本机实际安装版本对照：
  百度地图 22.0.0 / 高德地图 17.00.0.2005 / 腾讯地图 11.6.0（一致 ✔，不一致 ✘）。

### 并入的百度侧能力（原 BMapClean v0.4.3）

- 首页：底部入口栏逐项开关 + 剩余项均分、工具宫格 15 格逐项、回家/去公司、天气（地图侧 + 底部行程卡）、推荐信息流（频道栏 / 优质内容精选）
- 「我的」页 —— **数据层 + JS 层**双管齐下（视图层方案三次回归后彻底废弃）：
  - 数据层：改写 Talos JS 网络响应（qt=ads 活动/资源位按 `ads.content[].type` 摘条目）
    与手机信息下发（补 `phoneInfo.gk=0`，一次关掉天天领钱 / 出行保+借钱 / 我的店铺 / 导航车标）
  - JS 层：改写 `TalosBridge.loadJSFile` 的 `biz_pkg_path` 指向打过补丁的副本，
    在页面 JS 里把 `sport` / `contribution` / `common`(宫格) 从 `cardList` 摘掉
  - 真机结果：我的页只剩 头像 / 足迹卡 / 客服中心

### 修复

- 百度「我的」页一处必崩：拦截器读取响应体后必须**无条件换新 body**，
  否则 `NetworkingModule$c.onResponse` 二次 `string()` 抛 `IllegalStateException: closed`，
  OkHttp Dispatcher 线程 FATAL（v0.4.0 真机栈为证）。
- 设置页分页：一次滑动只翻一页（松手时按拖动起始页判断，不再叠加）。

# Changelog

> **版本口径统一**：本模块对外版本恒为 **v1.1.0 / versionCode 110**
> （`app/META-INF/xposed/module.prop`、`app/AndroidManifest.xml`、设置页页脚、
> Release tag `110-1.1.0` 全部一致）。开发过程中的历史迭代记录已合并到本版本下，
> 不再单独占用版本号。

## v1.1.0

### 新增开关（默认开 = 已处理，可随时关）

- **关闭首页搜索框热词轮播（文字流动 / 运营广告词）** —— 键 `hotword_off`
- **关闭首页「去XXX 打车」打车浮窗（打车运营卡）** —— 键 `ride_card_off`

### 本次修复

- **首页搜索框里的字会自己流动，有时还是广告**：反编译
  `com.autonavi.bundle.amaphome.components.searchbar.BaseSearchBar` 确认，
  写 `mHotWordTxtView`(id `txt_hotword`) 的出口只有
  `setHotWordTxt(String,String,c95,boolean[,Listener])` 与
  `setPreWordTextView(String,boolean,boolean[,Listener])` 两个私有方法
  （前者被 `setPreSetWord` 的 6 个分支全部调用，后者还被 `refreshHotWordTxt()` 调用）。
  新增 `HotWordGuard` 挂这 4 条重载，把第一个 String 参数换成 App 自己的
  `com.autonavi.minimap:string/title_search_hint`（运行时 getIdentifier 解析，失败回退字面量），
  并把 `mHotWordIconImg` / `mRightHotWordIconImg` GONE。
  真机日志：`HOTWORD-FREEZE '灌县古城' -> '查找地点、公交、地铁'`。
- **首页总会冒出「去幸福路步行街 / 有座不拥挤 · 行程有保障 / 打车」打车浮窗**：
  真机树实证它是 AJX 卡片（`Html > Container(613x123) > Container(1006x210)×4
  > Container(1006x242) > AjxAbsoluteLayout(1006x242)` = 列表 item 根），
  卡内文案由服务端下发（8 个 dex 字符串表 / resources.arsc / APK assets 全量扫描均无此串）。
  因此按「卡内文案锚点（服务保障标签，或 `去XXXX` 标题 + 同卡打车 CTA）+ AJX 列表 item 结构
  （父级是 AjxList/RecyclerView，宽 ≥70% 屏宽、高 ≤40% 屏高）」双判据定位，
  命中即 `hideItem`（首帧之前 GONE + 高度归零 + 每轮 reassert），另有 `rideCardSweep` 锚点兜底复扫。
  真机日志：`RIDE-CARD-SWEEP '有座不拥挤' [37,391 1006x242]`。
- **构建脚本**：`build.ps1` 临时目录改走 `$env:TEMP\makbuild`（原仓库外路径在受限环境下不可写）。

### 修复（历史迭代，已合并到本版本）

- **百度地图开屏广告复发（22.0.0 实测）**：早期版本把 `SplashAdManager.F()/z()/w()/y()`
  从「强制 false」降级成「只观察」，真机日志明确记录 `OBSERVE SplashAdManager.F() -> true`
  —— 闸门重新为 true，开屏广告 100% 复发。现已按反编译证据恢复**断源式**修复：
  `F()/z()/w()/y()` 一律返回 false，`WelcomeScreen.t()` 在第 43 字节直接 return-void，
  走的是应用自己"没有广告"的原生分支，广告请求都不会发出。
  同时确认「卡开屏」的真凶**不是**闸门，而是当时一并把 ADN 的加载入口
  `IAdLoader.I(...)` 吞掉了 —— 那是 ADN 的**回调完成方法**（onAdClose/onTimeOver），
  吞它等于回调永不到达。该处现在只观察、绝不吞。
- **高德地图开屏拦不住（17.00.0.2005）**：旧版挂的是 16.23 的混淆名 `u96/za6.g(int,String)`，
  17.00 里签名已变，真机日志 `miss za6.g (no such sig)` —— 整条闸门失效。
  现改为挂**语义方法**：`com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl.canShowSplash()`
  返回 false（该方法只有三个调用点，其中 `g.o()` 是开屏决策总入口；
  返回 false 会走应用自家的 `g.e(SplashFinishReason.NO_SPLASH)` 收尾分支）。
  接口 `BootBizDataPreloader` 上的同名方法是 abstract，挂不上（会 MISS），
  必须挂实现类 —— 这是真机日志暴露出来的坑。
- **腾讯地图启动任务被断链**：`SplashManagerInitTask.run()` 从 VOID 改回放行。
  它的父类 `AnchorTask` 是启动任务图的锚点（带 CountDownLatch + await()），
  把 run() 吞掉 = 锚点任务永不完成 = 启动图悬挂，是"卡开屏"的成因之一。
  纯预取任务 `SplashRequestTask.run()` / `DecodeSplashTask.run()` 保持拦截。
- **自动补货 SDK（SdkAutoBlock）几乎没用 + 有副作用**：
  旧表把 load / loadAd / show / fetchAd / init / initialize 全部当广告入口吞掉，
  这正是「卡开屏、腾讯 ANR、百度闪退」的根因；
  Context 替换的判定条件是 `name.contains("start")`，命中面过宽
  （startActivity / startService / getContextWrapper 全在里面）。
  现在只保留两类安全动作：① 只读能力闸门（isInitialized / isInit / isSdkReady /
  canLoadAd / canShowAd / isReady …）返回 false；② 纯初始化入口
  （initSDK / initializeSdk / startWithAppId）空转；Context 替换收窄到
  init* / setup* / *WithAppId 且签名里确实有 Context 入参。
  效果：百度 22.0 上 hooked 从 73 降到 15，扫 dex 从 5.6s 降到 2.2s，误伤面大幅收窄。
- **百度地图一启动就闪退（NoClassDefFoundError: com.baidu.baidumaps.splash.c）**：
  根因是在**安装阶段**（`onPackageReady`，也就是 `Application.onCreate` 之前）
  用反射去回读开屏闸门做自检。`SplashAdManager.w()` 内部会触发
  `splash.c.<clinit>` → `SplashPreference` → `Preferences.build()` →
  `JNIInitializer.getCachedContext()`，而那个时刻缓存 Context 还是 null，
  直接 NPE → `ExceptionInInitializerError` → 类初始化被标记为永久失败 →
  `BaiduMapApplication.onCreate → SplashAdManager.B()` 抛 `NoClassDefFoundError` → 闪退。
  真机堆栈：`BmapHooks.verifyGate(BmapHooks.java:211) ← BmapHooks.install(BmapHooks.java:80)`。
  修复：删掉安装期反射自检，改成**闸门运行时首火校验**。
- **「我的」页红包答题卡删不掉**：它整张是图、没有任何文本控件，文案匹配永远打不到。
  改用结构判据（子树里没有 AJX 文本控件 + 占满整宽 + 有图）识别，只删它、不碰其它卡片。
- **搜索栏下快捷入口删不干净 / 右滑还有**：那一排是横向分页器，只摘「行」第二页照样滑出来。
  现在从格子往上找到 pager 整块摘除，两页与页码指示点一起消失；默认关闭（设置页可开）。
- **卡顿**：去掉每帧全树扫描（改为只重申已隐藏节点）、绑定路径不再跑整树 BFS、
  正则预编译、资源名按 id 缓存；实测首页来回猛滑 10 次 2778 帧仅 7 帧掉帧（0.25%）。
- **闪烁**：AJX 的文字是绑定返回之后才写进去的，旧实现只能下一帧判 → 广告先亮一下。
  现在绑定前先挂 alpha 0，在首帧绘制之前判完（广告 GONE / 正常卡片同帧恢复）。
- **路线规划页被误删**：所有首页规则现在只在「已认领」的首页 /「我的」页列表内生效，
  路线规划页整页不再受影响。
- **宫格重排与 AJX 布局拉锯导致持续乱闪**：撤掉 tick 级复排，重排退回事件驱动
  （onTextSet → applyPass）；新增「工具宫格自动排序」开关（默认关，出问题可开）。
- **更多工具点不动**：AJX 自定义容器的触摸分发不跟随 translation 平移，
  重排改回 `layout()` 直改几何（触摸即绘制位置）。
- **「高德出行节」轮播第二格漏网可点**：packGrid 收集时做子树 contentDescription
  深查（CD 常挂在深层子节点，单点检查会漏）。
- **跨行重排坐标算错**：`getTop()` 相对各自父行，跨行搬运必须补「目标行 − 源行」的 top 差。
- **工具格隐藏改「幽灵格」三件套**：GONE 在 AJX 上有两个死症（自定义命中检测
  不查可见性 → 隐藏格触摸区还在；模型重建拉锯 → 乱闪）。
  改为保持 VISIBLE + alpha 0 + 子树 GONE + 触摸吞掉，三害齐除。
- 底部标签栏删掉 tab 后悬浮胶囊没被占满 → 胶囊随槽位自适应
- `Hooker.intercept` 方法名错误导致全部 Hook 失效（AbstractMethodError）
- 修复一条推荐区规则把整个工具宫格抹掉；修复百度开屏白屏；
  补齐 manifest 里声明却缺失的 `LearnedReceiver`

### 新增（历史迭代，已合并到本版本）

- **搜索页去广告**（`search_ad`）：搜索输入页 / 联想页 / 搜索结果页 / 搜索运营落地页的
  券包 / 红包 / 满减 / 会场 / 运营卡 —— 结构指纹判页 + AJX 文案锚点判广告 +
  视图层清扫 + 帧驱动钉住
- **液态玻璃设置页**（设计参考 Kyant0/AndroidLiquidGlass）：光斑背景、玻璃卡片、
  可折叠抽屉、底部悬浮操作栏、深色模式自适应
- **配置管理**：保存 / 恢复 / 导出 / 导入
- **联系作者**：Telegram 群组 + GitHub Issues
- **隐藏桌面图标后 LSPosed 管理器入口常驻**（CATEGORY_INFO 通道，与桌面完全正交）
- **高德首页 /「我的」页 UI 自定义**：标签栏逐 tab、工具宫格 15 格逐格、
  推荐内容（天气 / 景区 / 榜单帖 / 距离卡 / 精选榜 / 攻略流 / 问问 AI / 推荐频道栏）、
  「我的」页各栏逐项开关；搜索栏下方快捷入口整排总开关（默认关）
- **榜单板块结构识别（boardSweep）**：目的地 / 路线详情页的「发现好去处」等推荐板块，
  标题为 GL 级渲染（不走任何文本钩子），改用纯结构判据 —— 满屏容器 + 子树 ≥2 张
  460~560 宽双列卡 → 整块隐藏
- **主动挂列表 bind hook（sweepHookLists）**：AJX 列表不再依赖 collapseHost 先命中
- **区块兜底（collapseBlock）**：非列表页面的跨页规则从板块标题上溯到
  「宽≥60%屏、高≤80%屏」的最近祖先整块收掉
- **开屏容器统一探针 SplashProbe**：三家共用一张长词根表（qumeng / advlib / gromore /
  octopus / meishu / taku / wangmai / ruishi / smartdigimkt / vlion / ubix / tangramsplash 等），
  配色"跳过 / 广告"角标识别；命中后只摘广告子树，容器与品牌层原样保留
- **开屏窗口自动清扫**：Activity.onResume 后 600ms / 1600ms / 3000ms 三轮扫描 decor，
  加帧驱动预绘制探测（30~90 帧）
- **广告 SDK 自动检索 + 学习 + 持久化**（扫目标 App 自身 dex 字符串，ContentProvider 通道落盘）
- **秒送工具识别**；顶部运营横幅强制清除（十一出行补贴 / 限时开领 / 赢好礼 / AI叫车 /
  一句话定制）；悬浮推广球窗口级扫描（floatBallSweep）；「好友动态」「答题瓜分百万大奖」
  「高德出行节」强制清除（无开关）

### 验证

- 平台：KernelSU + LSPosed 2.1.1 / Android 16 / 1080x2400；
  目标：高德 17.00.0.2005、百度 22.0.0、腾讯 11.6.0
- 搜索框恒显示「查找地点、公交、地铁」不再流动；信息流里打车浮窗整卡消失不留空洞；
  两个开关各自关掉并强停高德重开后，热词轮播 / 打车浮窗立即恢复（双向验证通过）
- 完整证据与 hook 设计：`docs/FIX-v1.1.0-hotword-and-ridecard.md`、
  `docs/FIX-REPORT-v1.1.0.md`、`docs/ANALYSIS.md`
