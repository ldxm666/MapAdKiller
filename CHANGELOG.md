## v2.3.2（versionCode 2302）— 高德两个实验功能 / 三端开屏与更新 / 百度重要修复

发布日期：2026-11-06。版本码：2302。基线 v2.3.1（版本码 2301）。

### 新增（高德）

- **去除 3D / 扁平建筑物**（实验，默认关）：native 层补丁，改写 `libamapr.so` 中 `BuildingStyleLayer` 的虚表项，
  使该图层不产出几何。补丁前先读槽值与六个指令字做双重自检，不匹配则**不写内存、只记一行日志**，
  楼块保留但高德零崩溃。已针对 17.02.0.2010 重新定位全部偏移。
- **帧率上限 60 / 90 / 120**（实验，默认关）：hook `GLMapEngine#OnEGLRenderFrame` 做固定节拍调度。
  实测关闭 117~120、120→119、90→80~82、60→60（90 档受屏幕刷新率整数分频限制）。
- 两项均可在**模块 App → 高德 · 实验** 或 **内置版悬浮面板** 中操作，默认关闭，界面注明「需强停重开」。

### 新增（三端通用）

- **强制去除版本更新检查与更新弹窗**，默认生效、不提供开关：
  - 高德：`AppUpgradeController#w` / `#v`（唯一弹框入口）
  - 百度：`VersionUpdateController#N` / `#x0` / `#j0` / `#l0`（控制器侧，网络层一条未碰）
  - 腾讯：`AppUpgradeInfo#parseJson` 压平版本号 + `AppUpgradeDialog#show` no-op + 运行时类名兜底
- **开屏广告拦截默认开启**（高德此前因门控语义导致默认不拦）。

### 修复

- **百度 POI 逐类开关全部失效**：分类决策发生在宿主配置就绪之前 3.1 秒，读到的是空的只读表，
  13 个逐类开关全部落到缺省「隐藏」。改为先把宿主 Application 交给 `HostPrefs` 再计算分类。
- **百度地点详情页白屏**：补丁副本缓存无生命周期管理，旧缓存被误用导致页面空白。
  改为内容指纹做缓存键、临时目录原子发布、命中先校验、失败降级到原模板（**宁可广告，不可白屏**）。
- **独立 App 开关写不到内置版宿主**：新增内置版检测（只读宿主 APK 的 zip 中央目录），
  检测到即整页置灰并提示「请在地图里轻点悬浮球」，避免「界面成功、实际无效」的误导。
- **腾讯升级后唤醒链路失效**：`UpgradeInitTask` 在 6013 已无调用点，改为在弹窗与解析层拦截。
- `poi_ad_removed` 日志更名 `poi_ad_rule`（它是规则回显，不代表删除生效）。

### 已知未解决

- 百度 22.1.20 地点详情页「你可能想搜 / FAQ / 团购横幅」三块无法去除：
  七张卡片名逐个屏蔽、五次截图逐像素无变化；日志证明加载的确实是 `detailtmpl` 且已打补丁，
  说明这三块不走我们 hook 的三个数据通路。需另做 DOM 锚点探针后再评估。
- 高德开屏页 layer-list 的两个 logo 图层未上屏（白屏期非白像素仅 0.60%）。
  已排除「系统 SplashScreen 覆盖」，未改视觉层。
- 建筑功能的 5m/10m/50m 分档读数未取（界面无第三方缩放读数，不伪造）。

## v2.3.1（versionCode 2301）— 高德冷启动 / 百度配置 / 公告通道

发布日期：2026-11-03。版本码：2301。基线 v2.2.8（版本码 2280）。

### 修复

- **高德冷启动 6.5 秒 → 1 秒以内**：根因是本模块自身的 hook 无限递归。`AmapNativeHome` 拦截 `k84.f()`，其内部经 `AmapData.toolboxRaw → toolbox → applyColorIcons → colorIconUrl → defaultSlot` 反射回调 `k84.f()`，再次进入同一个 hook，递归约 2000 层；每层都 `new JSONObject(5.5KB)` 再拼字符串，涨到 `StackOverflowError` 被 `catch (Throwable)` 吞掉才停。实测 `onCreate` 6538ms → 127~659ms，冷启动 `TotalTime` 7174ms → 1170ms
- **高德开屏页恢复**：之前误当广告拦截。`SplashActivity` 是 `activity-alias` 指向 `NewMapActivity`，白底来自主题 `windowBackgroundFallback`，属高德自身界面
- **百度配置无法保存**（三次反馈）：读路径在 hook 安装期把 LSPatch 只读 remote 永久定案，44 个真实键全部读不到；`DefaultConfig` 因 `mapadkiller.App` 空桩 `service==null` 早退，140 条默认值灌进 remote 且 `_defaults_applied` 从不落盘；`invalidate()` 不重置后端定案。三处全修，`write()` 增加回读自校验
- **腾讯悬浮球消失**：浮层挂在首启 `WelcomeActivity`，该页 `finish()` 后浮层随之销毁。改为跟随宿主所有 Activity 的 `onResume`，并新增 `OnGlobalLayoutListener` 哨兵（腾讯首启是同 Activity 换内容，不发生命周期回调）
- **腾讯面板「只读」**：`BaiduMineEntry.install()` 对任何被注入的包无条件 `Vendor.host(pkg)`，把宿主身份改写成百度/WebView，导致厂商判别失效、后端判定 NONE。`Vendor.host` 加白名单且定案不改写
- **腾讯配置写进两个存储**：四条 Context 反射在宿主 Application 创建前同时为空，同一份默认值既写 `shared_prefs` 又写 `lspatch-xposed-remote.db`。改为「直给 Context 优先」，反射仅兜底
- **全新安装默认不干净**：JSON 中 `tab_*` 键不存在、部分键为 `null`（跳过不写），读取侧兜底硬编码 `true`，导致干净安装全部可见。JSON 补齐、`Spec.defaultVisible` 对齐、`Cfg.visible/tabVisible` 走 Spec

### 新增

- **服务器公告通道**：远程推送、首次必弹可关闭弹窗、自绘浮层（规避宿主 `windowBackgroundFallback` 纯黑 9-patch 黑边）、入场 150ms / 退场 130ms 动画、HMAC-SHA256 签名校验、分档节流
- **「检查更新」改从自有服务器读取**，不再依赖 GitHub API
- `HookGuard`：单 hook 连续失败 3 次自动停用，`LinkageError` 立即熔断
- `Perf`：全 hook 自动计时，超阈值输出排行榜

### 变更

- 百度地点详情页 17 类推广位清理（数据层摘卡，不留白），各块独立开关
- 百度面板重排为 6 个 chip；「赞赏支持」点击区放大到 48dp
- 抓取：成功 Toast/通知 + 1.2 秒自动退出；失败不退出可重试
- 腾讯性能：首页视图树转储收进 debug（日志 443~565 行 → 9~14 行，-80%）；悬浮钩子只装主进程（3 → 1）；布局哨兵 250ms 节流
- `_defaults_rev` 版本迁移：只填缺失键，不覆盖用户显式值，不每次启动重放

### 已知未包含

- 首页工具面板紧凑排列（原方案会压塌布局，已回退）
- 腾讯 40fps 上限未动：实测官方原版 39.4fps / 内置版 40.7fps，上限来自 SDK 自身 `limitFrame`

## v2.2.8（versionCode 2280）— 巡航可调 + 百度底栏/只读/日志三修

发布日期：2026-10-28。版本码：2280。基线 v2.2.7（版本码 2270）。

### 修复

- **百度路线页「开始导航」消失**：`removeAndCollapse` 在刷新重建窗口里把整条底栏当成空壳收掉（兄弟未测量 → 误判「只剩我一个」），
  宿主复用 View 实例后无法恢复。现在类名含 `BottomBar` / `PageBottom` 的容器一律不参与收壳。
- **百度面板「只读 · 无法写入配置」误报**：`writePath()` 对百度/腾讯只认 `App.service()`，服务没绑上恒判 NONE。
  改为服务缺失时做一次真实写入探测（`probeVendorPrefs()`），与高德 `effectiveBackend()` 一致。
- **百度内置版日志抓取不可用**：变体把 `mapadkiller` 整包裁掉时 `CaptureLog` 被换成空实现，面板按钮点了没反应。
  改为 `keepFiles` 保留真实 `H` / `CaptureLog`（并补 `AmapCruise` / `Config` 空实现的缺失成员）。
- **高德抓取完成提示每次都弹**：`tick()` 只提示不清 `restart.pending`，重启后标记还在。现在提示一次即清。
- **巡航参数存不住**：`Config.put(String,int)` 内置版直接写 remote（只读代理），commit 成功但读回旧值。
  改为与布尔键同一条 `CfgStore` 路径（local 为准 + remote 镜像 + 回读自校验）。

### 变更

- **自动巡航触发值可调**：新增 `K_CRUISE_KMH` / `K_CRUISE_SEC`，地图内面板与模块 App 各有一组滑块（2–30 km/h、2–30 秒），默认 18 / 15 保持旧行为。
- **低速档门槛自适应**：单步位移门槛 `min(8, max(1.5, minKmh×0.8))`、窗口总位移 `min(60, max(15, minKmh×3))`、
  净位移 0.75×、精度上限低速档放宽到 120 米、dup 下限 1.5 → 1.0 米。走路可触发。
- 模块 App 应用列表：已安装排前；未安装行去掉固定「推荐 x」；百度/高德/腾讯内嵌原生图标（`BaiduIconAsset` / `AmapIconAsset`）。
- 滑块写入失败改为退回原值 + Toast「未连接地图：先打开高德，再回来调」。

### 构建

- `module.prop` 的 `version` / `versionCode` 改为构建时从 `Version.java` 生成（此前手写，2.2.5 起停在旧值）。

### 产物

- `Baidu-2.2.8-nonroot-embedded.apk`（228,784,616 B）
- `Amap-2.2.8-nonroot-embedded.apk`（183,500,370 B）
- `MapClean-lsp-v2.2.8.apk`（4,646,588 B）

---

## v2.2.7（versionCode 2270）— 百度 22.1.0 适配、搜索 chip、路线页横幅

发布日期：2026-10-27。版本码：2270。基线 v2.2.6（版本码 2260）。

### 修复

- **百度首页整页白屏**：`applySearchChips` 反射回调组件自己的 `getView`，在钩子里重入；`field()` 在第一个名字找不到时直接抛，
  后面那句兜底是永远执行不到的死代码。现在钩子把 `chain.proceed()` 的返回值往下传，反射改走 `fieldOrNull`。
- **百度搜索框下方快捷入口开关失效**（酒店 / 景点 / 美食 / 厕所 / 加油站）：同一排 chip 有两个宿主，都要挂 ——
  首页收起态挂 `aihome.panel.presenter.QueryViewHolder` 构造器（那条 RecyclerView 没有资源 id，隐藏后还要向上收壳，否则留白）；
  搜索页挂 `PoiListHeaderMenu` 的三个构造器，按 id 抓 `search_box_line2` / `search_box_line2_content`。
  根因是 `aihome_poi_first_title` inflate 出来的那一刻那排还是空的，chip 是 `k()` 里再 `addView` 进去的，挂 inflate 永远抓空壳。
- **百度路线页横幅广告**：RN 模板 `talos.view.advert.RNAdvertContainer` 与原生壳 `PubTravelBaseHomeCard.fetchAd/showAd` 双路断源。
  只 GONE 广告容器会留约 1000 px 白块（壳里有宽度 0 的占位兄弟，`getChildCount()==1` 判据失效），判据改为「父容器没有别的可见孩子」并向上收 4 层。
- **百度进地铁规划闪退**：穿山甲断点从 `TTAdSdk.init(Context, TTAdConfig)`（返回 false）改成 `TTAdSdk.start(TTAdSdk$Callback)`（空实现）。
- **百度路线页浮层**：低碳、古今地图、周边、特惠打车等 15 个「创建即隐藏」锚点；最下方「探索周边」「特惠打车」拆成独立开关。

### 变更

- 百度地图锚点全表重测为 22.1.0（1660）的值：30 个 `NUM_*` 更新，`NUM_USER_CENTER` 从 `0` 补成 `0x7f0254eb`，新增 6 个搜索区锚点。
- 百度地图 POI 支持按 13 个类型分别关闭（+ 长尾桶共 15 行开关），按 STY 里图标资源名过滤，缓存 schema `v8` → `v9`。
- 拆掉全局 `TextView.setText` 钩子（每次设文本 4 次 `contains` + `toString()`，整机明显卡）。真机基线 416 帧 **0.00% janky**，50th 7ms / 99th 15ms。

### 高德

- 修复隐藏工具栏时连带隐藏「设置家 / 设置单位」。
- 自动巡航灵敏度调整，跟随更及时。
- 新增「自动抓取钩子」按钮：面板里一键跑锚点抓取，不用连电脑。
- 新增日志抓取：面板里直接抓一段运行日志，方便反馈问题。

### 产物

- `Baidu-2.2.7-nonroot-embedded.apk`（228,755,944 B）
- `Amap-2.2.7-nonroot-embedded.apk`（183,479,890 B）
- `MapClean-lsp-v2.2.7.apk`（4,630,204 B）

---

## v2.2.6（versionCode 2260）— 工具宫格回到原生、两行 10 个、版本号修正

发布日期：2026-10-27。版本码：2260。基线 v2.2.5-step2（版本码 2252）。

### 修复

- **首页工具宫格第二行图标被裁切**：宿主把每个格子塞进「行」容器，行的实测高度比格子内容矮 17 px，而 `clipChildren` 默认为 true → 圆形图标上沿被切平。现在宫格根与每个「行」都关闭裁剪，第 2 行起补 10dp 上边距（@560dpi = 35 px），行距 15 px → 33 px。
- **宫格槽位上限纠正为 10（2 行 × 5 列）**：此前按 11 个写，第 11 个会被宿主挤成第三行，压到下方「回家 / 去单位」那一行上。
- **首次抓取弹窗重复**：`FloatingPanel` 里还留着一份旧的「高德下发的官方数据已经抓到 / 立即重启」弹窗，
  和 `CaptureGate` 的新弹窗抢着弹（真机上看到的是旧文案）。旧实现已删除，弹窗统一由 `CaptureGate.tick()` 负责。
- **兜底抓到空基线就再也不重抓**：首次启动闪退会留下只有 3 个文件的空壳基线并标记「已抓完」。现在兜底抓到的文件
  少于 20 个直接丢弃、不写标记，下次启动重抓（`event=baseline_capture_retry reason=fallback_thin`）。
- **版本号显示**：设置页顶部徽标固定显示「定制版」→ 改为真实版本号 `v2.2.6`（有新版时显示 `v2.2.6 → vX.Y.Z`）；内置版页脚同步为 `MapAdKiller v2.2.6 · 免 root 整合版`。

### 变更

- 首页工具宫格、设置家 / 单位 / 常去地点 chip 全部改回**高德原生渲染**（不再自绘），图标、字体与点击行为与官方一致。
- 宫格 payload 只写官方 15 个字段（白名单），空槽模板直接复制宿主自己的空槽，不再夹带私有键。
- 删除「打车运营卡」「去哪里打车」两个无效开关（后端 key 保留，界面无入口，等同永久关闭）。

### 首次启动（免 root 内置版 / 模块版通用）

- **第一次打开就是纯原生地图**：`Baseline` 在首次启动时整体放行（除悬浮窗外一个 hook 都不装），所以工具栏图标、首页卡片、底栏全是官方原样，不会再出现「图标不显示、功能半残」。
- **采集模式也保留悬浮窗**：面板「首页」页顶部那条红 → 黄 → 绿提示条就挂在悬浮窗上，第一次进来的用户看得到「正在抓取 / 已抓完」，也找得到「点这里重启启用」的入口。
- **抓完自动弹窗 + 自动重启**：后台把官方下发数据抓全后写 `restart.pending`，下一次 `onActivityResumed` 弹「✓ 已获取官方数据」，5 秒后自动关掉地图并拉起新进程；新进程检测到基线已就绪，直接按用户设置启用模块。
- **抓取提示条**：面板「首页」页顶部新增三态提示条（红：还没抓 · 黄：正在抓 · 绿：已抓完，点一下重启启用），显式触发路径也走同一套弹窗与重启。
- 个别系统（Android 14+ 后台启动限制）会拦截自动拉起，此时点一下桌面图标即可，基线已就绪、启动即生效。
- **90 秒兜底**：首页 90 秒内没出现（首次安装的隐私弹窗、网速慢）就退出采集模式、模块继续不启用，下次打开继续抓。
- **内置版装好后第一次点开可能闪退一次**（LSPatch 首次解包 177 MB 宿主，`ClassNotFoundException: …NewMapApplication`），
  再点一次桌面图标即正常，此后不再出现。

### 注意

- **首次使用必须先抓一次官方下发数据**：2.2.6 起全自动 —— 第一次打开就是纯原生高德，正常用一下首页 / 搜索 / 我的，
  抓完弹窗提示并自动重启，之后开关才按设置生效。宫格、首页卡片、金刚位都是服务端下发的，不抓就没有。
- **工具宫格最多两行（10 个）**：超过会溢出，第三行会压到下方内容，请用「更多工具」而不是硬塞。
- 内置版与本版同源（模块代码 4,470,136 B），功能完全一致。

## v2.2.3（versionCode 223）— 悬浮窗按版本分流、装错包诊断

发布日期：2026-10-20。版本码：223。基线 v2.2.2（版本码 222）。

### 新增

- **悬浮窗按安装形态分流**：免 root 内置版默认显示悬浮球；**LSPosed 模块版默认不显示**（悬浮窗主要面向免 root 用户），可在面板「其他 → 悬浮窗入口」手动开启。判定依据是宿主 APK 内是否存在 `assets/lspatch/config.json`，不依赖 root 状态、也不依赖 PackageManager。
- **`host_mode` 诊断日志**：`event=host_mode pkg=<宿主> embedded=<true|false> kind=<lspatch-embedded|official-or-lsposed>`，用于一眼判断用户装的是官方版还是内置版，方便排查「模块好像把高德签名改了」这类误报。

### 修复

- `host_signer` 在 Android 13+ 读不到证书（`GET_SIGNATURES` 已废弃返回 null）→ 改用 `GET_SIGNING_CERTIFICATES`。
- 诊断日志误报包名（会打出 `com.miui.contentcatcher` / `com.google.android.webview`）→ 改为从宿主 Context 取包名，并按包名去重。
- **不再输出证书 DN / 指纹**：LSPatch 会把宿主的 PackageManager 包装成「原版应用」，读到的始终是官方签名 `CN=minimap`，而非磁盘上重签名包的 `CN=xiawanli` —— 该字段具有误导性，已删除。需要确认签名请对 APK 文件本身做校验（`apksigner verify --print-certs`）。

### 构建

- **模块版在构建期彻底移除免 root 专属实现**：`tools/module_prune.py` 会在编译前复制一份源码并删除 9 个文件（悬浮面板 / 悬浮球 / 悬浮窗挂载 / 悬浮窗 hook / 自绘滑块 / 头像徽章 / 长按入口），并校验 9 个核心类仍在。模块 APK 的 dex 里这些类与相关字符串**全部 0 命中**，体积从 4,380,276 B 降到 4,331,124 B。
- **签名密钥固定并加构建守卫**：`app/build.ps1` 现在会校验 `app/debug.keystore` 的证书，**缺失或不匹配直接中止构建**，避免再次出现「换密钥导致老用户无法升级」。

### 说明

- 2.2.3 同时包含 2.2.2 之后的所有修复：未登录用户打不开面板、面板关闭后无法再次打开、版本胶囊卡在「检查中」、部分设备显示「只读」与开关不生效（配置后端本地兜底）。
- **更新模块 APK 后若发现模块没生效**：LSPosed 会缓存模块的 `apk_path`，重装后该路径可能指向已删除的旧目录，导致模块不注入（重启也不会自动刷新）。**重启一次手机**，或在 LSPosed 里取消再重新勾选一次作用域即可。
---
## v2.2.2（versionCode 222）— 长按「我的」开面板 + 「省电 / 后台」整组

发布日期：2026-10-20。版本码：222。基线 v2.2.1（版本码 221）。

这一版把面板从「打开模块设置页」改成了「长按地图底部『我的』标签直接唤出」，并把此前分散在各处的后台省电项收成一组，普通用户不需要懂任何技术名词就能用。

### 新增

- **适配高德地图 17.02.0.1541**。首页、工具宫格、地图标注、搜索栏等各分组开关随新版本重新校验过。
- **新增「省电 / 后台」整组（11 项 + 一键省电）**，模块版设置页同步出现同一组：
  - 推送：关闭推送长连接（ACCS，省电最多）／关闭推送消息处理／关闭推送服务壳（激进）。
  - 后台任务：关闭周期任务（WorkManager / JobScheduler）／关闭子进程拦截（总闸，排查用）／关闭桌面小组件刷新／关闭云控调度池（LotusPool）。
  - 埋点与广告：关闭阿里埋点 Analytics／关闭开屏广告请求／关闭广告曝光上报。
  - 地图：启用第三方标注过滤（默认关闭，开启后可能影响帧率）。
  - 顶部「一键省电（全部开启拦截）」一次写入 9 项，不含「子进程拦截」总闸与「第三方标注过滤」。
  - 语义统一为「**开 = 该行为已被拦截**」，默认全开；关掉即恢复原应用行为。本页改动需强制停止并重开目标 App 才生效。
- **面板顶部新增版本胶囊**：绿色表示已是最新；红色并挂「新版本」小标表示有新版本可用，点它可直达下载。
- **新增每日自动检查更新**：24 小时节流，每个新版本只提示一次；并接入国内镜像源（gh-proxy.com / gh.llkk.cc / ghfast.top / ghproxy.net / jsDelivr），无 VPN 也能查到新版本。查到新版本后出现「镜像下载」按钮。
- **新增「外观」页 4 个滑块**：悬浮球大小 36~72dp（默认 46）、悬浮球不透明度 30~100%（默认 100）、面板宽度（自动 / 280~420dp）、面板不透明度 60~100%。拖动实时预览，松手保存，另有「恢复默认外观」。
- **新增「关闭悬浮窗」**：关掉后悬浮球与面板都不再出现；需要时**长按底部「我的」标签约 1 秒**即可重新唤出面板。
- **新增「长按『我的』打开面板」**：长按约 1 秒弹出，再长按收起。面板右上角 ✕ 只关面板，不关悬浮球。
- 首页顶部新增常驻横幅「省电拦截已开启 · 查看/调整 →」，点击直达省电页；若有关掉的项会以红字提示。
- 面板底部新增搜索框，可按开关名搜索。

### 性能

- 修掉高德 ACCS 长连接空转导致的周期性掉帧：首页地图拖动帧间隔 **p99 82.77ms → 17.3ms**，掉帧率 **6.1% → 0.0%**，稳定 60fps。
- 冷启动中位耗时 **~790ms**。
- 模块自身**不创建任何线程**；静置 60 秒，模块日志 0~3 条。

### 修复

- 工具宫格隐藏不生效：兜底路径绕过了过滤逻辑，已修正。
- 「关闭 = 不加载」由运行时拦截改为**安装期门控**，关掉的项彻底不加载对应实现，零性能开销。
- 版本号固定为 2.2.2 / 222（`Version.java`、`module.prop`、`AndroidManifest.xml` 三处同步）。
- **修复未登录用户完全打不开面板**：「我的」标签在未登录时文案是「未登录」，入口判定只认「我的」⇒ 长按无效。改为文案集合 + tab 身份 + 末位兜底三层判定。
- **修复面板关闭后无法再次打开**：长按后的「吞点击」钩子未调用 `chain.proceed()`，把整条钩子链短路，导致宿主 tab 状态机缺少收尾、后续触摸被吞。改为永远 proceed。
- **修复版本胶囊卡在「检查中」**：8 个镜像串行、单源 14 秒超时（最坏 112 秒），且并发检查时回调不来。改为单源 4s/5s + 整轮 12 秒上限 + 13 秒看门狗，任何情况必有终态。
- **修复部分设备面板显示「只读」、开关不生效**：配置后端不再强依赖框架远程通道，新增进程内本地配置兜底（远程可用时优先远程并备份一份到本地），拿不到宿主 Context 时不缓存失败结果、稍后自动重试。
- **修复部分设备上面板显示「只读」、开关拨了不生效**：配置读写不再强依赖框架的远程配置通道，新增宿主进程内的本地配置后端作为兜底（远程可用时优先用远程，并自动把配置备份到本地一份），免 root 内置版在拿不到远程通道的设备上也能正常保存设置。

### 已知限制

- 面板分类栏从屏幕最右约 160px 起手滑动不了（系统手势区冲突），直接点 chip 可正常跳转。
- 「省电 / 后台」页改动需强制停止并重开目标 App 才生效。
- 关掉推送长连接后，行程提醒、活动推送、消息中心、家人地图消息会收不到；拨回「开」并强停重开即可恢复。
- 灵动岛 / 手表联动：官方原包上同样不可用，属设备侧配置，与本模块无关。
- **降级到本地配置的设备**（框架远程通道不可用）：面板读写与保存均正常，但少数在启动极早期安装的拦截项可能仍按默认值运行，**强制停止并重开一次目标 App 即可**。
- **免 root 内置版可能被高德自动更新覆盖**（部分机型商店会直接装回官方版），请在应用商店关闭高德的自动更新。

---
## v2.2.1（versionCode 221）— 补齐自带默认配置（含 2.2.1 修订）

发布日期：2026-10-15。版本码：221。基线 v2.2.0（版本码 220）。

2.2.0 的 APK 在「自带默认配置」完成前就已构建，包内不含该功能。本次为补齐发布。

### 新增

- 自带默认配置：模块内置一份 JSON（`res/raw/default_config.json`，5486 B），全新安装后自动应用一次。
- 格式与「更多 → 导出 JSON 配置」完全一致，可直接替换。
- `null` 键表示「未设置、保持缺省」，应用时跳过，不会写成 `false`。
- 只应用一次：标记键 `_defaults_applied` 存在即跳过，不覆盖用户改动。
- 设置页「恢复默认配置」可手动再应用一次，忽略标记，有二次确认。
- 免 root 内置版按宿主包名只应用本机那一家的配置组，其它组静默跳过。
- 内置 JSON 覆盖高德（139 键）、百度（51 键）、腾讯（92 键）与模块界面项（1 键）。
- 腾讯组 92 键：15 个默认显示的工具为 `true`，其余 61 个已知工具为 `false`，16 个总开关为 `null`（保持缺省）。

### 修复

- `Version.TARGETS` 把百度适配版本写作 22.0.0，设置页因此对 22.0.50 显示 ✘。改为 22.0.50。
- `app/build.ps1` 输出名硬编码为 `MapClean-lsp-v2.1.6.apk`，不随版本号变化。改为从 `Version.NAME` 动态生成。
- `app/AndroidManifest.xml` 版本停在 216 / 2.1.6。同步为 221 / 2.2.1。
- 仓库 `app/META-INF/xposed/module.prop` 同步为 2.2.1 / 221，模块内置更新检查不再报旧版。
- 修正 v2.2.0 产物表：资产名为 ASCII，与 Release 实际一致。

### 产物

| 资产文件名 | 对应产品 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| `amap-2.2.1-nonroot.apk` | 高德地图免 root 内置版 | 200,803,570 B | `6de374138b47cdb0077c4d6471c20ca9ff9266c5248ae0a2c90bb4f5db0965a0` |
| `baidu-2.2.1-nonroot.apk` | 百度地图免 root 内置版 | 228,588,008 B | `43d5be616d5c602051b24ac8456338e53180609f176dfd731910cdf81caedb08` |
| `tencent-2.2.1-nonroot.apk` | 腾讯地图免 root 内置版 | 180,844,452 B | `e88feac70dd61adae770b680bc85d07d28236c6e4a91bb443bd9604557d259ee` |
| `MapClean-lsp-v2.2.1.apk` | MapClean 模块本体 | 4,343,326 B | `fce3283eaa6741196567e57fe028bcd25953b1da5eb313f3563541c8592e3aa2` |

### 已知限制

- 百度面板仍保留约 80px 空白带（原生 `mHideHeight` 几何下限）。
- 腾讯工具宫格第 3 档（完全展开）未取得运行时截图验证，自动化上滑只到中档。
- 腾讯 `extendHeight` 在官方包内无调用点，模块写入该字段生效，但与官方后续版本兼容性未知。
- 免 root 内置版的「自带默认配置」仅做静态验证。变体包名与官方一致，安装会覆盖已装的内置版，故未做真机验证。
- 内置默认配置的腾讯组仅做静态校验，未做真机首启验证。

---

### 2.2.1 内修订（替换资产，不升版本号）

发布后补两处改动，仍为 2.2.1 / 221，四个资产原地替换。

- 内置默认配置补上 `tmapclean_config` 组，腾讯侧不再需要手动逐个打开开关。
- `res/values/strings.xml` 的 `<description>` 去掉写死的 `v2.0.0`。该字符串是系统「应用信息」展示的文案，属于用户可见残留。
- `MainActivity.buildFooter()` 内 `MapAdKiller v2.0.0` 的版本行删除。该方法从无调用点，是死代码。
- 版本号未改动：`Version.java`、`module.prop`、`AndroidManifest.xml` 仍为 2.2.1 / 221。

产物（替换后）：

| 资产文件名 | 大小 | SHA-256 |
| --- | --- | --- |
| `amap-2.2.1-nonroot.apk` | 200,807,666 B | `06b02ce42b688592c55ccb84e0ea96c80f59016e8548e73a0283faa7d62df41b` |
| `baidu-2.2.1-nonroot.apk` | 228,592,104 B | `2c275eee02feeddf86035ffca9d7ff8e4cb4fb40c76990dc7d3526d427bed1d4` |
| `tencent-2.2.1-nonroot.apk` | 180,848,548 B | `0cf56869a4e06936c450c801411dcfc9ec0ca7bd8db3bc33f7d25a07573959f7` |
| `MapClean-lsp-v2.2.1.apk` | 4,347,508 B | `5ed6105166909e1e8e6a2951e3aa613936a86e25688dee3bee8a05fcb979469f` |

## v2.2.0（versionCode 220）— 腾讯地图支持与百度 22.0.50 适配

发布日期：2026-10-15。版本码：220。对比基线 v2.1.5（版本码 215）。

### 新增

- 腾讯地图支持（`com.tencent.map` 11.7.0）：底部入口栏、工具宫格、我的页、地图图标等开关。
- 百度地图工具宫格逐格开关，覆盖 22.0.50 新增的智行、新能源、停车助手、新手导航、现金签到、测距等文案。

### 修复

- 百度 22.0.50：`HomeMapOperatePresenter.onEventMainThread` 的事件参数类混淆名从 `xe.c` 迁移到 `fe.c`，旧名仍存在但无该重载，导致一个钩子静默 `miss`。已修正。
- 百度面板上滑留大片空白：把面板展开上限与收起位一起夹到剩余内容高度（`DrawerBehavior.extendHeight` / `mMinOffset` / `mPeekHeight` / `mMaxOffset`），空白从 686px 降到 80px。
- 百度 `EXPANDED` 状态整屏闪白：钉死态把 `EXPANDED` 归一为 `COLLAPSED`（几何不变），27 帧里白屏帧从 4 降到 0。
- 百度冷启动夹不上（写字段需要第 3 次布局，冷启动只有 2 次）：稳定判定后补一次有界 `requestLayout()`。
- 腾讯「服务未连接」：`EmbeddedSettingsPolicy.isHostPackage()` 的宿主白名单漏了 `com.tencent.map`。
- 腾讯底部入口栏隐藏失效：钩子比 UI 装配晚 0.85 秒，`Instrumentation#callApplicationOnCreate` 改为模块加载即挂；`hidden()` 里的总开关判断不再吞掉启动时的初始页选择。
- 腾讯工具宫格默认只显示一排：钉住初始展开值使三排常驻。
- 腾讯拖动时 ANR：模块在高频路径里做反射、触发宿主日志、拼 220 字符字符串，改为热路径零反射／零分配／零日志。
- `Spec.mergeDiscovered()` 此前无调用者，设置页从未真正合并 hook 上报的工具文案。已接通上报与落盘链路。
- `build_variant.py`：新增构建期不变量，校验变体宿主包被 DEX 内的宿主白名单接受。

### 产物

发布资产名为 ASCII（GitHub 会剥离非 ASCII 字符，中文名清洗后会撞成同名并静默丢文件）。

| 资产文件名 | 对应产品 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| `amap-2.2.0-nonroot.apk` | 高德地图免 root 内置版 | 200,795,378 B | `00229cc75e8776eddc07b0bc8d995a4f706cb7509c28a83c411be361c9a99275` |
| `baidu-2.2.0-nonroot.apk` | 百度地图免 root 内置版 | 228,579,816 B | `f98cefaff32b366093e46b11543a637266fb7acacf4213d135346c0fc2efcecc` |
| `tencent-2.2.0-nonroot.apk` | 腾讯地图免 root 内置版 | 180,840,356 B | `45d30a2734cbb133e0c055caeed0c9e98d7cbbd518ef4fbaff495fd5c56e1e79` |
| `MapClean-lsp-v2.2.0.apk` | MapClean 模块本体 | 4,326,869 B | `290a03b09f2bfa14fb8225ed61316b34f7d339d68235dba7463b03a1a5671e4d` |

### 已知限制

- 百度面板仍保留约 80px 空白带（原生 `mHideHeight` 几何下限）。
- 腾讯工具宫格第 3 档（完全展开）未取得运行时截图验证，自动化上滑只到中档。
- 腾讯 `extendHeight` 在官方包内无调用点，模块写入该字段生效，但与官方后续版本兼容性未知。

---

## v2.1.5（versionCode 215）— 修复百度地图完全失效

发布日期：2026-10-12。版本码：215。对比基线 v2.1.4（版本码 214）。

### 症状

2.1.4 模块对百度地图**完全没有反应**：开关照写、界面照旧，logcat 里连一条
`BMapClean` 都没有。腾讯与高德不受影响。

### 根因：一次"为了另一个构建"的删改，泄漏到了共享源码

一个 APK 只能声明一个 Xposed 入口（`META-INF/xposed/java_init.list`），
所以百度、腾讯两半都要靠共享源码里的**显式转发**到达：

```
mapclean.MainHook.onModuleLoaded   → bmapclean.MainHook.onModuleLoaded   （设置 H.module）
mapadkiller.MainHook.onPackageReady → bmapclean.MainHook.onPackageReady  （装百度钩子）
```

为了让「只并入高德」的免 root 精简构建能编译（那个构建把整个 `bmapclean` 包删掉），
**这两处转发被直接从共享源码里删掉了**。精简构建确实过了，但完整模块的百度也一起没了：

- 没有 `onModuleLoaded` 转发 → `bmapclean.H.module` 恒为 null →
  每一次 `hook()` 都以 `miss <id> (module null)` 失败，装了等于没装；
- 没有 `onPackageReady` 转发 → 百度分支只剩一句注释，一个钩子都不装。

### 修复

| 位置 | 改动 |
| --- | --- |
| `mapclean/MainHook.java` | 恢复向 `bmapclean.MainHook.onModuleLoaded` 的转发（`H.module` 必须先赋值） |
| `mapadkiller/MainHook.java` | `case PKG_BMAP` 恢复调用 `bmapclean.MainHook.onPackageReady` |
| `tools/build_variant.py` | 新增 `bmapclean/MainHook` 的 no-op 桩类 —— 精简构建靠**桩**编译，而不是靠删调用 |

### 顺手加的构建期护栏（这次就是缺这个）

- `app/build.ps1`：d8 之后直接在 `classes.dex` 里查类型描述符，
  `Lio/github/ldxm666/bmapclean/MainHook;` 或 `Lio/github/ldxm666/mapadkiller/TmapHooks;`
  缺任一个就 **fail the build** —— 谁再删转发都过不了构建。
- `tools/build_variant.py`：把 `bmapclean/MainHook` 加进精简构建的 **required** 列表
  （即"转发必须还在"），并把原来"整包禁用 `Lio/github/ldxm666/bmapclean/`"改成**逐类禁用**
  —— 允许那个空桩，但不允许任何真正能挂钩百度的类（`BaiduHooks`、`HomeClean`、
  `MineData`、`PoiLayer`、`Spec`、`Talos`…）。

### 同一批的第二个回归：设置页「百度」那一页读写全挂

用户反馈：模块设置页里百度那一页怎么点都提示 **「LSPosed 服务未连接」**。

同一个成因的第二个漏接。`bmapclean.App` 的注释写着"v2.0.0 合并版起由
`io.github.ldxm666.mapclean.App`（真正的 Application）驱动"，但那个驱动**从来没接上**：

```java
io.github.ldxm666.mapadkiller.App.attach(this);   // ← 有
io.github.ldxm666.bmapclean.App.attach(this);     // ← 没有（本次补上）
```

于是 `bmapclean.App.service` 恒为 null：百度组（`bmapclean_config`）读开关永远返回缺省值、
写开关永远返回 false，UI 就把它显示成"服务未连接"；同时 `bmapclean.App.app()`
为 null，诊断卡永远显示"尚未收到回报"。**跟百度 hook 失效是同一类错误** ——
合并时只把一半接上了。

修复：`mapclean.App` 的 `onCreate` / `bindService` / `diedService` 三处补上
`bmapclean.App.attach / onBind / onDied`；精简构建同样用 no-op 桩类补位；
`app/build.ps1` 的护栏也把 `Lio/github/ldxm666/bmapclean/App;` 纳入必查。

实机：`I/MapCleanApp: onServiceBind ok` 之后紧跟着
`I/BMapCleanApp: onServiceBind ok (driven by mapclean.App)`（修复前这一行不存在）。

### 验证

| 层级 | 结果 |
| --- | --- |
| 模块编译 | BUILD OK，签名 `cbecdaeb…54ed`（不变，可就地覆盖） |
| 百度实机 | `baidu_adapter version=22.0.0 mode=capabilities` → **`event=install_done pkg=com.baidu.BaiduMap ok=60 miss=0`** |
| 精简构建 | BUILD OK（桩类生效，护栏通过） |
| 高德回归 | 四件修复全部照旧：`amap_tool_boot slots=103,86,104,105,-`、`amap_home_chips built=… items=3`、`style_filtered rules=2950` |

---

## v2.1.4（versionCode 214）— 四个「开关无效」修复

发布日期：2026-10-12。版本码：214。对比基线 v2.1.3（版本码 213）。

四个问题全部在真机（小米 · Android 16 · 高德 17.00.0.2005）上用日志 + 截图逐条验证过。
完整的技术叙述见 `docs/PROGRESS-免root内置版.md`。

### 1. 「设置家」现在立即生效，且默认开启

**根因不是开关没写进去，而是面板根本没重建。**
高德的首页工具面板实例被 `BootUIPreloader.a.f9663a` 缓存复用，返回首页时拿到的还是旧 View；
那条行本身由高德自己的 `g13.a`（HomeCompanyBuilderV3）构建，模块只负责放行。
用户"把「内容」开关全打开后才显示"看到的是**重建时机** —— 那些开关让首页画布整体重建了一次。

- `AmapNativeHome` 新增 `refresh()`：捕获 `b.a` 造出的容器与参数、面板实例，
  以及 `g13.a` / `b.a` / `buildSkeletonView` 的 `Method` 句柄；开关一写就按 `signature()`
  比对并在原地重建面板内容。失败只记一行日志，下次重建时照样生效。
- `FloatingConfig.write()` 成功后直接回调 `AmapNativeHome.onConfigChanged(key)` ——
  内置版里模块与宿主同进程，不需要 IPC。
- 默认值由关改开（它是地图核心功能，不属于推荐内容）；迁移 `_amap_protocol_schema 210 → 211`，
  只对"迁移前本来是开"的安装恢复，不覆盖用户的显式选择。

日志：`amap_native_home refresh ok signature_changed=true`；关掉开关后那一行**立即消失**，未重启。

### 2. 工具开关真的能把工具放进首页工具栏

**根因在数据层。** 设备上 `local_tool_list_for_xml_v2` 里只有两个槽
（`id0=103 公交地铁`、`id1=86 更多工具`），而旧的 `AmapData.toolbox()` **只会删、不会补** ——
步行(104)/骑行(105) 从来没进过这个列表，开关打开也不可能有 UI。
另外实测确认首页工具栏**只渲染 `id0..id4` 共 5 格**。

- `AmapData.toolbox(source, settings, fill)`：保留应用自己的顺序，已开启的槽原样搬；
  `fill=true` 时用本版本自带的 `AmapToolCatalog` 目录把空位补满（最多 5 格）。
- **只在 boot 路径 fill**（`amap_tool_boot`）。`local_tool_list_for_xml_v2` 是高德自己的存储，
  会被「更多工具」页读回，不往里写臆造的槽。
- 新槽的点击载荷是 `Uri.encode({"schema": …})`，与 `com.autonavi…toolbox.v3.f.a` 的解析契约一致
  （它 `Uri.decode` 后只取 `schema`；`ut` 可选，缺失有兜底）。
- 被丢弃的槽写 `sizeN=0px`，渲染器据尺寸跳过，不再留空格子。

日志：`amap_tool_boot slots=103,86,104,105,-`；工具栏显示 公交地铁 / 更多工具 / 步行 / 骑行。

### 3. 「更多工具」有了独立开关

它原本埋在「工具 → 其他」一百多个工具的最后。现在移到**「首页」分组**，
标签「更多工具入口」，并从工具分区里排除，避免同一个键出现两个开关。
工具分类顶部加了一行说明（工具栏 5 格、超出部分去「更多工具」页）。

### 4. 地图兴趣点开关真的在改写样式

真正的实现在原生层：`libmapclean_labels.so` 挂 `AAssetManager_open`，
只截 `map_assets/style_*X_Main` 这张样式 protobuf，把兴趣点要素改写成不可见，道路 / 水域 / 地名保留。

两个独立条件必须同时满足：库要能被**本模块的类加载器**装进来（JNI 认声明 native 方法的那个 loader），
且框架必须调用 `native_init(entries)` 交出 inline-hook 原语。v2.1.3 两个都没满足：

- LSPatch 把内嵌模块 APK 的 `lib/<abi>/x.so` 重写成 `<abi>/x.so`，旧代码只认前者 → 库从未加载；
- `native_hook_dispatch.h` 的 `install()` 在框架从未绑定时**返回 true**，把"没装"伪装成"装上了"。

- `NativeLabelAssets` 三种布局都认，并优先用宿主 `ApplicationInfo.nativeLibraryDir` 里的副本；
- `MainHook.onPackageReady` 用 `param.getApplicationInfo().nativeLibraryDir` 再试一次；
- `install()` 在 `hook_ == nullptr` 时改回返回 `false`；`configure` 日志补 `framework_bound=`。

日志：`native_init framework_ready=true api=2` / `native_install host=amap asset_only=true failures=0` /
`configure … accepted=1 framework_bound=1` /
`style_filtered mask=1 rules=2950 bytes=173610 asset=map_assets/style_X_MainStd_Std_D_s_…`。

### 构建侧：内置版的签名与包结构

排查过程中一度把产物判为"包结构损坏"，**这个判断是错的**，在此更正：

LSPatch 的集成产物是**有意嵌套**的 —— `assets/lspatch/origin.apk` 就是原封不动的官方 APK，
而官方 APK 自身的条目（`lib/*.so`、`assets/*`、`resources.arsc` 等）也仍在包里，
它们的字节**物理上就位于 origin.apk 覆盖的那段区域**。因此：

- 统计各条目声明大小之和得到 366 MB，而文件只有 200 MB —— 不是数据缺失，是同一段区域被
  两个记录引用；
- `zipalign -c` 报一批 "header mismatch" 并认为 `origin.apk` 与其它条目区间重叠 —— 同一个原因；
- 任何按声明大小重建的工具（`zipalign`、`apksigner`）都会把原区域复制两遍 → 产物变 367 MB。

Android 的 libziparchive 和 Python 的 zipfile 都读**本地头**，所以完全正常：
`adb install` 与 MIUI 安装器都实测可装、应用可正常运行。

结论：内置版**只能保留 LSPatch 自己的 v2 签名**，不做二次对齐/签名（Android 7.0+ 认 v2）。
也**不要**把 `.so` 追加进宿主 APK：追加会重写中央目录，破坏原包签名，
LSPatch 会直接报 `Could not read the original apk's signature`。

---

## v2.1.3（versionCode 213）— 「设置家」行修复与推荐画布过滤加固

发布日期：2026-10-12。版本码：213。对比基线 v2.1.2（版本码 212）。

本次更新全部来自 hook 源码 + logcat + **对设备上实际安装的高德 17.00.0.2005 APK 的 DEX 级复核**，
没有做界面层猜测。

### 「设置家 / 设置单位 / 常去地点」整行不再出现（用户反馈“打开设置家无效”）

根因不在模块开关，而在宿主自己的构建器：

- `Lg13;` = `HomeCompanyBuilderV3`，全 APK 唯一调用点是 `ToolboxPanelV3.init`。
- 该构建器要求 payload 的 `uid` 等于**当前登录 uid**，否则直接返回 `null`。
- 实测缓存 `home_and_company_data` 里有完整的 3 条 `items`，但 `uid` 是空串 → 整行永远不创建。
- 结果：开关开或关都一样，表现为“开关没反应”。

修复：hook 内先反射取当前 uid，对齐后**交回高德原生构建器绘制**，样式（字号 / 圆角 / 描边 / 主题）
全部跟随 App；仅未登录、原版仍拒绝时才使用同 payload 的兜底渲染。

### 连带修复

- 工具列表为空时不再把父容器设为 `GONE`。该父容器正是「设置家」那一行的宿主，
  一旦 `GONE`，之后 `addView` 进去的 chips 永远不可见。
- 兜底渲染的尺寸换算改为精确复刻宿主 `dx1.l(v) = round(v / 2 * density)`。
  之前把 payload 的 `textSize` 当 sp 使用，字被撑大后拆行并被裁掉（标签消失）。
- `AmapToolIcons.load()` 修复 `id.equals(...)` 空指针（`boot` / `id` 允许为 null），
  并新增 `tokenOf()` 兼容 `iconToken` 键名。

### 首页推荐画布过滤加固

- 卡名解析改为按宿主真实规则取值：`cardName` / `card_name` / `bizName` / `biz_name` / `name`，
  并做大小写与下划线归一化。旧实现只读 `component.extra_info.cardName`，
  其他拼写的卡片永远匹配不上、也就永远不会被过滤。
- 流式信封同时读 `is_done` / `isDone`，同时写 `has_more` / `hasMore`，
  避免因宿主拼写差异导致编辑落空。

### DEX 复核发现的失效分支（本次记录，未删除）

以下字面量在整个 APK（含嵌套 ajx 包）中**均不存在**，对应分支为死代码：

| 位置 | 失效字面量 |
| --- | --- |
| `AmapPlaceData.FEED_MODULES` | `poiDetailWaterFeedTitle`、`poiDetailWaterFeed` |
| `AmapData.handles()` | `/ws/promote/main-page/assets`、`/ws/shield/dsp/profile/index/nodefaasv3` |
| `AmapData.cardVisible()` | `tipsRanking`、`hourlyWeather`、`weatherForecast`、`contentCenterFeed`、`taxiQS`、`taxiQSMarket` |

真实卡名由宿主 `gu2.A` 从响应树动态提取，因此这些固定字面量永远匹配不上。
解析规则已按真实取值重写；**具体卡名清单仍需抓取一次真实响应报文才能定案**。

### 验证

| 层级 | 结果 |
| --- | --- |
| 编译 | BUILD OK，签名 SHA-256 未变 |
| 安装 | Success（in-place 覆盖） |
| Hook 加载 | 44/44 HOOKED，miss=0，无 hook_error |
| Hook 命中 | `amap_native_home_chips` → `uid_aligned=true` → `built=DtLinearLayout` |
| 行为回归 | 三枚胶囊带图标、单行、间距正确；天气 / 扫街榜 / 推荐 / 出行节均未复活 |

---

## v2.1.2（versionCode 212）— 地图标识、底栏路由、工具与推荐修复

发布日期：2026-10-10。版本码：212。对比基线为上一发布版本 2.1.0 系列（Git 标签 v2.1.0-beta1，版本码 210）。

本次更新围绕实际使用中反复出现的问题展开：页面刷新后推荐内容重新出现、隐藏入口仍能被点到、选中的工具没有显示，以及隐藏地图标识误伤底图。设置、数据、布局和点击处理一起调整。

### 高德首页与工具栏

- 首页信息流在请求返回、列表刷新和页面重建时继续按配置处理，覆盖天气、扫街榜、活动与营销内容等已验证模块。
- 回家／公司、好友动态及地图活动入口保持独立设置，减少不同区域共用一个开关造成的误处理。
- 修复面板上滑后地图无法拖动、收起与回弹状态异常的问题，保留原生手势与面板状态转换。
- 工具按稳定身份处理，目录从“更多工具”核对；补齐步行、骑行，避免被远程下发的“超划算”等工具替换或混入。
- 增加仅显示已开启工具的模式：未选择和未知的新工具默认隐藏；普通模式继续使用逐项显示设置。
- 重排同步更新数据顺序与点击对象，避免显示的是一个工具，点击却打开另一个功能。

### 高德底栏与地点详情

- 隐藏底栏入口时同步处理数据项和点击路由，修复“我的”附近误点到已隐藏打车入口的问题。
- 调整剩余入口宽度与选中背景，修复按钮和背景尺寸不一致。
- 新增“显示「发现好去处」照片推荐”独立开关，默认关闭，控制长按地图选点详情中的推荐标题与照片信息流。
- 保留该详情页的地址、行政区、收藏、分享、新增和路线操作，以及“逛逛周边”功能快捷入口。
- 推荐模块从页面数据与布局引用中同时删除，后续同类响应继续应用配置，避免只隐藏首屏后又被刷新回来。

### 百度地图标识与白屏修复

- 地图标识改为一个整体显示开关，不再展示效果不稳定的细分类别选项。
- 隐藏兴趣点图标与名称，保留道路、街道、城市、河流和其他基础地理内容。
- 修复替换整个资源目录导致底图白屏的问题：保留百度原始目录，仅为经过结构验证的地图样式文件提供处理后的内容。
- 原版资源不被覆盖，配置和登录数据不需要清除。无法识别的样式、文件打开失败时使用原始文件。
- 百度与高德的原生处理按宿主隔离，避免一套资源 Hook 被装入另一地图中。

### 百度底栏与搜索页

- 百度底栏除了可见按钮，还有面板状态切换时使用的透明点击代理。本版同步代理显隐、宽度和对应监听器，解决按钮已均分但点击范围仍停在原五列的问题。
- 在原生重新绑定事件、页面恢复和面板状态变化后维护对应关系，避免“我的”误进入打车。
- 保留第三入口在语音皮肤下的原生触摸语义，不把长按语音强行改成普通点击。
- 去除搜索页“热度飙升榜”。仅过滤已验证的排名模型，历史记录与未知数据项保留。
- 首页搜索框的推广轮播词恢复原生默认提示，同时清理其隐藏的推广查询数据。
- 搜索页推荐词标签替换为原生常用地址入口，保留回家、去公司、收藏夹及自定义常去地点。
- 保留景点、美食、酒店、厕所等搜索分类，以及用户输入、历史与正常搜索结果。
- 继续保留活动图标、任务横幅、AI 此刻、推荐信息流和“我的”页精简能力。

### 配置与兼容性

- 新备份格式按高德、百度和模块设置分组，解决同名配置覆盖，以及导回后只有高德恢复的问题。
- 保留“未设置／使用默认值”的状态，避免导出把默认值永久写成显式选择。
- 旧版高德平面配置仍可导入；旧文件没有包含的百度设置不会被错误重置。
- 界面与 Hook 共享配置语义，变更使缓存失效，减少高频读取与开关显示不一致。
- 大部分功能按实际接口能力安装，地图小版本变化不再直接导致整套增强失效；未知接口和数据结构回退原应用行为。
- 官方 API 102 仅作编译依赖，完整 service／interface 102 服务类随模块打包，补齐 Vector 2.2 所需的公开服务接口。
- 框架服务与作用域查询改为异步缓存，避免状态页等待 IPC 导致卡顿，并区分连接等待、作用域读取失败和未勾选地图。服务未连接时不直接判定模块未激活。
- 校验 APK 签名、四 ABI 原生库、真实 ZIP STORED 存储方式与 16 KB 对齐，构建失败时停止输出。

### 验证范围与使用说明

主要设备验证版本为高德 17.00.0.2005、百度 22.0.0（1650），Android 16／LSPosed 2.1.1。回归包括配置备份、接口失败回退、工具列表处理、原生样式结构、文件替换边界及百度底栏实际布局和触摸分发。

百度标识开关已完成显示／隐藏、冷启动、放大缩小和拖图对照。百度搜索页已确认默认提示、常用地址与历史保留，热榜和推荐词移除。百度首页／我的已完成 24 次不同位置的点击切换，并覆盖面板展开与收起，未再跳转到打车。

高德地点详情已实机确认推荐标题与照片卡消失，地址、周边工具和路线操作保留。数据过滤通过 Android 真实 org.json 的 136 项断言，覆盖模块与布局引用同步移除、重复刷新、损坏 JSON、未知结构及歧义卡片 ID 的保守回退。

Vector 2.2 已对照官方公开接口完成依赖和状态逻辑检查，尚未在 Vector 2.2 实机复测；腾讯地图本轮未重新验证。不将静态检查写成实机适配结论。

覆盖安装后保持原有框架作用域；调整开关后强停相应地图再打开。地图标识默认隐藏，可在标识分组中开启显示；升级时旧细分类别设置合并到整体开关。

本次发布未纳入百度地点详情“猜你想搜／周边推荐”的新增处理；免 Root 整合 APK 本轮不发布，普通模块仍需相应框架。


---

## v2.1.0-beta1（versionCode 210）— 高德重构、百度页面与开关修复

- 保留本轮确认通过的高德版本：精简首页广告信息流与活动入口，修复工具排列、首页滑动和底部选中背景，增加好友动态开关。
- 百度以 2.0.0 为基线修复，运行入口改为版本限定的原生组件、Presenter 及 Talos 页面适配。
- 百度配置缓存读取；整块和逐项开关共享规格；底部剩余入口均分，「我的」页运营卡与导航车标分别控制。
- 停用百度旧全局视图扫描及全局数组原型修改，避免页面被整体误处理。
- 隐藏百度左上角 V22 活动图标和任务轮播横幅，拦截原生创建及恢复显示。
- 最新 APK 已完成编译、签名验证与 ADB 覆盖安装；百度安装日志 44 个 Hook 成功、0 个 miss。
- 实测版本：高德 17.00.0.2005、百度 22.0.0（1650）；详细范围见 `docs/REFACTOR-2.1.0-beta1.md`。

以下为历史版本记录，其中旧实现与验证数字不代表本版。

## v2.0.2（versionCode 202）—— 左上角气泡、百度运动、更新提示可见性

### 修复（用户逐条真机反馈）

- **首页左上角运营气泡清不掉**（「新鲜事 / 一键穿越 / 古今地图」）：
  这类气泡**没有 a11y 节点**（uiautomator 导出里完全看不到），文案又是服务端下发的（dex 里搜不到），
  之前按 contentDescription / 文案 / 几何的三套判据都够不着它。
  这次用模块自己的视图树遍历把它挖了出来，真身是：
  `RelativeLayout id=event_entry_container @18,297 137x137`（`left_container` 是同类另一颗）。
  改为**按资源 id 命中**（运行时按名解析，App 重排 0x7f 数值也不怕）。
  真机日志：`homeAd hidden: RelativeLayout desc=null @18,21 (2)`。
- **「百度运动」关不掉**：「百度运动」四个字在同一页出现两次（卡片标题 + 顶部图标宫格里的图标名），
  单锚点会先命中宫格那一条，`sectionRootSafe` 判定"不是满宽区块"直接拒绝 → 卡片纹丝不动。
  改为**双锚点最近公共祖先**（`百度运动` + `开始运动` 同时出现的地方只有那张卡）。真机：卡片消失，页面其余不动。
- **发了新版但旧版收不到更新提示**：`UpdateChecker` 的 6 小时节流把**冷启动**的检查也挡掉了
  （用户实测：2.0.0 开着，2.0.1 已发布却要手动点一下才提示）。
  改成**节流只在同一次运行内生效**，冷启动一律重新查一次。

### 新增

- **醒目的更新入口**：设置页顶部整宽大按钮，三种状态一目了然 ——
  有新版 **黄色「⬆ 立即更新 vX.Y.Z（镜像 / GitHub / Telegram）」**、
  已最新 **绿色「✓ 已是最新 vX.Y.Z（点此再检查）」**、
  查不到 **蓝色「检查版本更新（点此重试）」**。点一下直接出更新弹窗（镜像下载 / GitHub / Telegram / 稍后更新）。

### 说明

- 开关语义不变：**true = 显示**。「我的」页开关改完重开该页即生效；首页开关强停 App 后生效。
- 高德 / 腾讯侧能力未改动；最佳适配版本仍为 百度地图 22.0.0 / 高德地图 17.00.0.2005 / 腾讯地图 11.6.0。

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

> **v1.1.0 当时的版本口径（历史记录）**：当时模块对外版本为 **v1.1.0 / versionCode 110**
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
