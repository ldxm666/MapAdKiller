# MapAdKiller 现有 UI 功能项盘点

> 目的：为一次彻底的 UI 重构提供**可核对的完整清单**。本文只清点，不改任何代码。

---

## 0. 读数基线（先看这一节，否则后面所有数字都会误读）

| 项 | 值 |
|---|---|
| 仓库 | `D:\pojie应用\jiesuo\gh\MapAdKiller` |
| 盘点对象 | 基线 **`8b483a0`**；盘点结束时 HEAD 已前移到 **`7de8711`**（见 §0.1） |
| 盘点时间 | 2026-11-07 18:0x |
| 代码规模 | `app/src` 下 **152** 个 `.java`，**0** 个 XML layout |
| 统计方法 | 正则计数（本机无 `ripgrep`，已用等价的 .NET 正则 + `Select-String` 实现，逐条可复算） |

### 0.1 ⚠ 盘点期间 HEAD 前移了两个提交

开始盘点时 HEAD 是 `8b483a0`，工作区是脏的；盘点进行中（18:04–18:10）这些改动被**提交**了：

```
7de8711 (HEAD -> 2.3.0, origin/master) chore: 移除误提交的规范备份文件
075b549 ui: 全面重构为 HyperOS 视觉语言（基于 Miuix 0.9.4 设计规范）
8b483a0 docs: 补 BUILD.md 构建说明，源码包收录 libamapbldpatch.so   ← 本文基线
```

`8b483a0..7de8711` 的改动面：

```
 AmapPanel.java   |  53 +-      BaiduPanel.java  |   6 +-      FloatingPanel.java |  66 +-
 GlassSwitch.java | 106 +-     PanelTypes.java  |  12 +-      Skin.java          | 129 +-
 MainActivity.java|  12 +-     HyperDesign.java | 277 +++     HyperMotion.java   | 298 +++
 HyperRipple.java | 120 +++    docs/miuix-design-spec.md | 707 +++++
```

**已落地的重排（相对基线）**

1. `POWER_CHIP` 由 `"省电"` 改成 `"净化"`（`AmapPanel` + `BaiduPanel`）—— **只改名，语义没动**。
2. 高德面板「巡航」页**并入「地图」页**（2 开关 + 2 滑块跟着走）。
3. 高德面板「实验」页**并入「其他」页**；为此 `PanelTypes.Group` 新增
   `Group(label, items, metrics, note, about)` 重载。
4. `GlassSwitch` 重写；`Skin` 的颜色字面量 **60 处 / 45 值 → 23 处 / 14 值**。
5. **新增 `HyperDesign.java`（56 个不同颜色值）** —— 见 §6.1 的结论：这不是收敛，是搬家。
6. `MainActivity` 只改了 `divider()` 的左缩进，**功能项目录一字未动**。

⇒ 本文的**功能项清单（§3：键名 / 标题 / 默认值 / 依赖）对两个提交都成立**；
变的是**分组（§2）与配色（§6）**。§2 与 §6 给出基线数字，并注明 `7de8711` 的差异。
### 0.2 两套 UI 并存，且同一份目录有两份手写副本

| 形态 | 载体 | 说明 |
|---|---|---|
| **模块版**（root / LSPosed 独立 App） | `MainActivity.java`（4766 行） | 4 个底部 tab；**没有悬浮窗**（`FloatingHooks` 一条 hook 都不装） |
| **内置版**（免 root / LSPatch 注入地图进程） | `MainActivity.java` **+** `FloatingPanel.java`（2844 行） | 两套界面**同时存在**：设置页 + 悬浮面板 |

高德的开关目录因此有**两份手写副本**：

- `MainActivity.buildDrawers()`（第 1060–1182 行，16 个 drawer）
- `AmapPanel.groups()`（8 个 chip 页）

两份内容高度重叠但**不是同一份数据**，已经漂移（见 §4 问题 P2 / P3 / P4 / P6）。

### 0.3 一整页 UI 是死代码

`MainActivity.buildOtherPage()`（第 1390–1450 行）**从未被任何代码调用**，唯一出现是第 1154 行的一句注释。
该页承载的「接收服务器公告 / 隐藏桌面图标 / 抓取前 10 分钟 / 抓取后 10 分钟 / 去广告说明」在模块版**不可达**。

`MainActivity` 里 177 个方法，**13 个**只有定义没有调用（详见 §4 P1）。

---

## 1. 界面清单

### 1.1 用户可达的「屏」

| # | 界面 | 载体 | 形态 | 页/子页数 | 可达 |
|---|---|---|---|---|---|
| 1 | 首页 | `MainActivity.buildHomePage()` | 模块版 + 内置版 | 1 | ✅ |
| 2 | 配置 · 百度地图 | `buildBaiduPage()` | 两版都有 | 12 个 drawer | ✅ |
| 3 | 配置 · 高德地图 | `buildAmapPage()` → `buildDrawers()` | 两版都有 | 16 个 drawer | ✅ |
| 4 | 配置 · 腾讯地图 | `buildTmapPage()` | 仅模块版 | 7 个 drawer | ✅ |
| 5 | 备份 | `buildBackupPage()` | 两版都有 | 1 | ✅ |
| 6 | 关于 | `buildAboutPage()` | 两版都有 | 1 | ✅ |
| 7 | 悬浮面板 | `FloatingPanel` | **仅内置版** | 高德 8 / 百度 6 / 腾讯 8 个 chip 页（+「外观」1 页） | ✅ |
| 8 | 悬浮球 | `FloatingBall` + `FloatingController` | **仅内置版** | 1 | ✅ |

> 配置页的 3 个「应用」子页在内置版只有 **1** 个（`addAppChip` 只加当前宿主那一张）。

### 1.2 弹窗 / 浮层

| # | 弹窗 | 载体 | 形态 | 可达 |
|---|---|---|---|---|
| D1 | 服务器公告 | `Announce.showDialog()` | 两版 | ✅ |
| D2 | 运行状态详情 | `glassDialog("运行状态", …)` | 两版 | ✅ |
| D3 | 已捕获广告 SDK | `glassDialog("已捕获广告 SDK…")` | 两版 | ✅ |
| D4 | 版本更新 | `showUpdateDialog()` | 两版 | ✅ |
| D5 | 运行日志 | `showLogSheet()` | 两版 | ✅ |
| D6 | 赞赏 | `DonateUi.show()` | 两版 | ✅ |
| D7 | 抓取官方数据 / 重新抓取 / 启用模块 | `CaptureDialog.show()` | 仅内置版 | ✅ |
| D8 | 恢复默认 / 恢复快照 / 确认导入 / 隐藏图标确认 | `glassDialog(…)` | 两版 | ✅ |
| D9 | 配置管理 → 导入导出 → 配置文件 | `showConfigManager()` 链 | 两版 | ❌ **死代码**（只从死方法 `buildBottomBar` 可达） |
| D10 | 联系作者 | `showContact()` | 两版 | ❌ **死代码**（同上） |
| D11 | 首次进入提示 | `FirstRunNotice.maybeShow()` | — | ❌ **死代码**（241 行，调用点已被注释掉） |

### 1.3 死代码界面资产

| 文件 | 行数 | 状态 |
|---|---|---|
| `MainActivity.buildOtherPage()` | 61 | 整页不可达 |
| `MainActivity.buildHero / buildMetrics / buildForcedCard / buildBottomBar / addTab / animateIn / applyHeroInset / applyWindowBloom / adaptSummary / capsuleBg / addBmapInfoCard / addTmapInfoCard` | 约 500 | 旧「抽屉 + Pager 顶栏」设计的残留 |
| `FirstRunNotice.java` | 241 | 无调用 |
| `GlassDrawable.java` | 119 | 从未 `new`（`MainActivity:912` 注释里明确说「刻意不用」） |
| `Backdrop.java` | 132 | 只被注释提到 |

---

## 2. 现有分类结构

### 2.1 `MainActivity`（模块版 / 内置版共用骨架）

```
MainActivity  (FrameLayout contentHost + 底部导航 navBar)
├─ Pager（4 页，水平切换，Pager.java 自绘 ViewGroup）
│   │
│   ├─ [0] 首页  buildHomePage()                       ← 无开关，全是展示 + 入口
│   │   ├─ 顶部栏：MapAdKiller + 版本胶囊
│   │   ├─ 公告卡（未读=着色+红点）
│   │   ├─ 状态卡
│   │   │   ├─ 状态药丸（连接中/已连接/只读）→ 点开 D2
│   │   │   ├─ 说明行 statusView
│   │   │   └─ 指标行 ×3：版本 / 作用域 / 广告 SDK  → 点开 D2
│   │   ├─ 版本更新卡：当前版本 + [检查] → D4
│   │   ├─ 分区「数据与日志」
│   │   │   ├─ 抓取云数据 → 抓取流程
│   │   │   └─ 运行日志 → D5
│   │   ├─ 分区「应用」：百度 / 高德 / 腾讯 三行（未装沉底）
│   │   ├─ 分区「作者」：GitHub 主页 / Telegram 群组
│   │   ├─ 分区「赞赏」：赞赏支持 → D6
│   │   └─ 页脚：免费开源 · 禁止倒卖
│   │
│   ├─ [1] 配置  buildConfigPage()
│   │   ├─ 头部「配置 / 按应用调整模块开关」
│   │   ├─ 应用分段器（模块版 3 个 chip；内置版 1 个）
│   │   ├─ 统计行「<应用> · N 项开关」
│   │   ├─ 内置版只读说明条（embeddedNotice）
│   │   ├─ 搜索框（SwitchFinder.bar()）+ 结果胶囊
│   │   └─ configHost（FrameLayout，同时只显示 1 个 ScrollView）
│   │       ├─ 百度页 ─ 12 个 drawer（全部默认收起）
│   │       │   ├─ 底部入口栏 / 首页工具宫格 / 回家·去公司 / 天气·行程栏
│   │       │   ├─ 推荐信息流 / 「我的」页 / 地图标注（15 行）/ 搜索区
│   │       │   ├─ 路线页 / 地点详情页 / 诊断
│   │       │   └─ 百度 · 省电 / 后台（7 行）        ← 只在这里，BaiduPanel 里是另一份
│   │       ├─ 高德页 ─ 16 个 drawer
│   │       │   ├─ 高德 · 主页标签栏（4）
│   │       │   ├─ 高德 · 地图标注（1）
│   │       │   ├─ 高德 · 首页工具宫格（6 开关 + 2 滑块）  ← 巡航藏在这里
│   │       │   ├─ 高德 · 工具：出行 / 高德精选 / 服务 / 小程序 / 小工具 / 其他（共 114）
│   │       │   ├─ 高德 · 搜索页（1）
│   │       │   ├─ 高德 · 实验（2 开关 + 1 下拉）
│   │       │   ├─ 高德 · 首页推荐内容（7）
│   │       │   ├─ 高德 · 地点详情（1）
│   │       │   ├─ 高德 · 「我的」页（6）
│   │       │   └─ 高德 · 省电 / 后台（10）
│   │       └─ 腾讯页 ─ 7 个 drawer
│   │           ├─ 底部入口栏（6）/ 首页工具宫格（1+N）/ 首页通勤地址与信息流（2）
│   │           ├─ 内容模块（1）/ 「我的」页（6）/ 主页地图图标（2）/ 诊断（1）
│   │
│   ├─ [2] 备份  buildBackupPage()
│   │   ├─ 导出配置文件 → [导出 JSON 文件]
│   │   ├─ 导入配置文件 → [选择 JSON 文件]
│   │   ├─ 本机快照 → [保存快照] [恢复快照]
│   │   ├─ 分区「高级」：复制配置文本 / 从剪贴板导入
│   │   └─ 危险卡：恢复默认设置
│   │
│   └─ [3] 关于  buildAboutPage()
│       ├─ 应用身份卡（logo + 版本 + 构建号 + 模块版/整合版 chip）
│       ├─ 分区「支持版本」：每张地图一行「推荐版本 / 本机 x.y.z」
│       ├─ 分区「链接」：GitHub 仓库 / 版本发布 / Telegram 群组 / 问题反馈
│       ├─ 分区「通用」：隐藏桌面图标 + 调试日志
│       └─ 分区「说明」+ 页脚
│
├─ 底部导航 navBar（玻璃胶囊 + 4 个 navItem：首页 / 配置 / 备份 / 关于）
│
└─ ❌ 死分支：PAGE_OTHER / buildOtherPage() / tabBar / tabChips / bottomBar / hero
```

### 2.2 `FloatingPanel`（内置版悬浮面板，HEAD 结构）

```
FloatingPanel (FrameLayout：scrim + card，card 贴底)
├─ 头部 buildHeader()
│   ├─ AvatarBadge（作者头像，38dp）
│   ├─ 行1：MapAdKiller + 版本胶囊 + 「新版本」徽章
│   ├─ 行2：状态药丸（长按看后端）+ 作者链接（GitHubMark）
│   └─ 关闭按钮 ✕
├─ 搜索框 buildSearch()（输入 + 清空 + 计数）
├─ 分类轨 rail（HorizontalScrollView + 若干 chip）
├─ body（ScrollView，固定高度 min(396dp, 46% 屏高)）
│   ├─ [chip 0 顶部] 公告横幅（未读高亮 + 红点）
│   ├─ [「首页」页顶部] 抓取官方数据横幅
│   ├─ [可选] 跨页跳转横幅（Group.link）
│   ├─ [可选] 一键动作行（Group.action，如「一键省电」）
│   ├─ [可选] 组说明（Group.note）
│   ├─ 开关行 / 分段标题 + 开关行 / 滑块行
│   └─ [about=true 的页] 公告两行 + 抓取日志行 + 版本/更新两行
├─ 页脚脚注「修改即时写入；地图内页面可能需要重新进入才会刷新。」
├─ 写失败常驻行（⚠ 上次写入失败：原因（点此重试））
├─ 赞赏支持行
└─ 恢复默认配置行
```

**chip 页数（HEAD）**

| 厂商 | 页 | 页数 |
|---|---|---|
| 高德 | 首页 / 省电 / 界面 / 地图 / 巡航 / 首页工具 / 实验 / 其他 | **8** |
| 百度 | 界面 / 内容 / 地图 / 路线 / 省电 / 其他 | **6** |
| 腾讯 | 悬浮窗 / 底部入口栏 / 首页工具宫格 / 首页通勤地址与信息流 / 内容模块 / 「我的」页 / 主页地图图标 / 诊断 | **8** |
| 全部 | + 「外观」（`FloatingConfig.isEmbedded()` 才追加） | **+1** |

> 腾讯的「其他」页由 `withAnnouncePage()` 补出来（`Spec.cats()` 里没有 about 页）。

**⚠ `7de8711` 已把上表改小**：高德 8 → **6**（「巡航」并入「地图」，「实验」并入「其他」），
百度页名 `省电` → `净化`（行集合不变）。腾讯 8 页未动。
下表是**基线 `8b483a0`** 的页数；做分组设计时请按 6 页起步。

### 2.3 三种分组的「形状」互不相同

| 界面 | 分组手法 | 层级深度 |
|---|---|---|
| `MainActivity` 配置页 | 应用 chip → 抽屉（可折叠）→ 行 | **3 层** |
| `FloatingPanel` | chip 轨 → 页内分段标题 → 行 | **3 层**（分段是纯标题，不可折叠） |
| `bmapclean.Spec` / `tmapclean.Spec` | Cat → Row（一维） | **2 层**（由上面两个渲染器再包一层） |
---

## 3. 功能项全表

### 3.0 列约定

| 列 | 取值 |
|---|---|
| **界面** | `模` = 模块版配置页（`MainActivity`）；`内` = 内置版悬浮面板（`FloatingPanel`）；`两` = 两者都有 |
| **默认** | 取 `Config.defaultVisible()` / `Spec.defaultVisible()` / `tmapclean.Spec.defaultVisible()` / `FloatingConfig` 常量；`strict` 项取字面值 |
| **需重启** | `强停` = 必须强停并重开宿主（安装期门控）；`重开` = 重新打开宿主 App；`页面` = 重进对应页面；`即时` = 松手即生效 |

> **「开 = 已拦截」族**：`push_*` / `subproc_*` / `track_off` / `alimama_*` / `hotword_off` / `labels_off`
> 的 `true` 表示**该行为已被关掉**，与其它页语义相反。表里默认值写的是**存储值**。

---

### 3.1 高德 · 模块版配置页（`MainActivity.buildDrawers()`，16 个 drawer，155 行）

#### 3.1.1 抽屉「高德 · 主页标签栏」

| 键名 | 显示标题 | 副标题/说明 | 类型 | 默认 | 界面 | 分类 | 依赖 | 需重启 |
|---|---|---|---|---|---|---|---|---|
| `tab_探索` | 显示标签「探索」 | — | 开关 | 开 | 两 | 高德·主页标签栏 | — | 页面 |
| `tab_长按说话` | 显示标签「长按说话」 | — | 开关 | 开 | 两 | 高德·主页标签栏 | — | 页面 |
| `tab_打车` | 显示标签「打车」 | — | 开关 | 开 | 两 | 高德·主页标签栏 | — | 页面 |
| `tab_我的` | 显示标签「我的」 | — | 开关 | 开 | 两 | 高德·主页标签栏 | — | 页面 |

#### 3.1.2 抽屉「高德 · 地图标注」

| 键名 | 显示标题 | 副标题/说明 | 类型 | 默认 | 界面 | 分类 | 依赖 | 需重启 |
|---|---|---|---|---|---|---|---|---|
| `map_poi` | 显示地图标注 | 统一控制兴趣点图标、店名、道路名与城市名；路网/水域/地形照常 | 开关 | **关** | 两 | 高德·地图标注 | — | **强停** |

#### 3.1.3 抽屉「高德 · 首页工具宫格」（6 开关 + 2 滑块）

| 键名 | 显示标题 | 副标题/说明 | 类型 | 默认 | 界面 | 分类 | 依赖 | 需重启 |
|---|---|---|---|---|---|---|---|---|
| `ui_tools_visible` | 显示首页工具宫格 | 关闭后首页不再显示驾车/公交等工具入口 | 开关 | 开 | 两 | 首页工具宫格 | `tool_*` 的总闸 | 重开 |
| `cruise_auto` | 自动进入巡航（模块接管） | 车速持续达标 + 净位移判据才进巡航 | 开关 | **关** | 两 | 模：首页工具宫格 / 内：**巡航**页 | 依赖下面两个滑块 | 即时 |
| `cruise_min_kmh` | 巡航触发速度下限 | 2–30 km/h，步进 1 | **滑块** | **12** | 两 | 同上 | 依赖 `cruise_auto` | 即时 |
| `cruise_drive_sec` | 巡航触发持续时间 | 2–30 秒，步进 1 | **滑块** | **8** | 两 | 同上 | 依赖 `cruise_auto` | 即时 |
| `ui_tools_allowlist` | 反转开关：仅显示已打开的工具 | 只保留明确打开的工具；未设置/新增/未知工具全隐藏 | 开关 | **关** | 两 | 首页工具宫格 | 依赖 `ui_tools_visible` | 重开 |
| `tool_color_icon` | 工具图标统一彩绘（实验） | 用「更多工具」目录里的彩色圆底图标 | 开关 | **关** | 两 | 首页工具宫格 | 依赖 `ui_tools_visible` | 重开 |
| `tool_更多工具` | 显示「更多工具」 | — | 开关 | 开 | **仅模** | 首页工具宫格 | ⚠ **死键**（真键是 `tool_id_86`，见 P2） | 重开 |
| `tool_高德出行节` | 显示「高德出行节」 | — | 开关 | **关** | **仅模** | 首页工具宫格 | 目录 id 461 不在 `ENTRIES`，故回落到 legacy 键 | 重开 |

#### 3.1.4 抽屉「高德 · 工具：出行 / 高德精选 / 服务 / 小程序 / 小工具 / 其他」

→ **114 行，见 §3.9 完整表**。全部 `tool_id_<id>`，默认 `false`。

#### 3.1.5 抽屉「高德 · 搜索页」

| 键名 | 显示标题 | 副标题/说明 | 类型 | 默认 | 界面 | 分类 | 依赖 | 需重启 |
|---|---|---|---|---|---|---|---|---|
| `search_grid` | 显示搜索页快捷金刚位（实验，关闭会留白） | 关掉后整排消失，但进入搜索页会先闪现 ~0.3s、原位留白 | 开关 | 开 | 两 | 高德·搜索页 | — | 页面 |

#### 3.1.6 抽屉「高德 · 实验」

| 键名 | 显示标题 | 副标题/说明 | 类型 | 默认 | 界面 | 分类 | 依赖 | 需重启 |
|---|---|---|---|---|---|---|---|---|
| `amap_fps_on` | 帧率上限（实验） | 限速不是提速；60/120 整除屏刷，90 需 90Hz 屏 | 开关 | **关** | 两 | 高德·实验 | `amap_fps_target` 的前置 | **强停** |
| `amap_fps_target` | 目标帧率（实验） | 只在上一项打开时起作用 | **下拉** | **60** | 两 | 高德·实验 | 依赖 `amap_fps_on` | **强停** |
| `amap_building_on` | 去除建筑物模型（实验） | 导航/巡航态不画 3D 与扁平楼块；原生补丁，进程级全局 | 开关 | **关** | 两 | 高德·实验 | — | **强停** |

#### 3.1.7 抽屉「高德 · 首页推荐内容」（7）

| 键名 | 显示标题 | 副标题/说明 | 类型 | 默认 | 界面 | 分类 | 依赖 | 需重启 |
|---|---|---|---|---|---|---|---|---|
| `feed_board` | 扫街榜 / 周边精选榜单（首页及分页） | — | 开关 | **关** | 两 | 高德·首页推荐内容 | — | 页面 |
| `feed_festival` | 首页出行节 / 促销活动接口 | — | 开关 | **关** | 两 | 高德·首页推荐内容 | — | 页面 |
| `map_promo` | 地图右侧运营挂件（扫街榜 / 活动入口） | — | 开关 | **关** | 两 | 高德·首页推荐内容 | — | 页面 |
| `feed_weather` | 天气卡片 | — | 开关 | **关** | 两 | 高德·首页推荐内容 | — | 页面 |
| `feed_content` | 攻略 / 内容流卡片 | — | 开关 | **关** | 两 | 高德·首页推荐内容 | — | 页面 |
| `home_chips` | 设置家 / 设置单位 / 常去地点 | 控制首页那排回家与公司快捷入口 | 开关 | 开 | 两 | 高德·首页推荐内容 | — | 即时 |
| `hotword_off` | 关闭首页搜索框热词轮播 | `true` = 热词已被关掉，文字钉死为默认提示词 | 开关 | 开（=已关） | 两 | 高德·首页推荐内容 | — | 页面 |

#### 3.1.8 抽屉「高德 · 地点详情」

| 键名 | 显示标题 | 副标题/说明 | 类型 | 默认 | 界面 | 分类 | 依赖 | 需重启 |
|---|---|---|---|---|---|---|---|---|
| `place_recommend` | 显示「发现好去处」照片推荐 | 移除长按选点详情里的推荐标题与照片流 | 开关 | **关** | 两 | 高德·地点详情 | — | 重开 |

#### 3.1.9 抽屉「高德 · 「我的」页」（6）

| 键名 | 显示标题 | 类型 | 默认 | 界面 | 分类 | 需重启 |
|---|---|---|---|---|---|---|
| `my_order_row` | 订单 / 收藏 / 待评价 一栏 | 开关 | 开 | 两 | 高德·「我的」页 | 页面 |
| `my_service_row` | 车辆服务 / 高德运动 一栏 | 开关 | 开 | 两 | 高德·「我的」页 | 页面 |
| `my_task` | 达人任务卡片 | 开关 | 开 | 两 | 高德·「我的」页 | 页面 |
| `my_promo_row` | 热门活动卡 | 开关 | 开 | 两 | 高德·「我的」页 | 页面 |
| `my_guess` | 猜你喜欢 | 开关 | 开 | 两 | 高德·「我的」页 | 页面 |
| `my_friends` | 好友动态 | 开关 | **关** | 两 | 高德·「我的」页 | 页面 |

#### 3.1.10 抽屉「高德 · 省电 / 后台」（10，全部「开 = 已拦截」）

| 键名 | 显示标题 | 类型 | 默认 | 界面 | 分类 | 需重启 |
|---|---|---|---|---|---|---|
| `push_hard_off` | 关闭推送长连接（ACCS，省电最多） | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `push_off` | 关闭推送消息处理 | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `push_kill_off` | 关闭推送服务壳（激进） | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `subproc_job_off` | 关闭周期任务（WorkManager / JobScheduler） | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `subproc_widget_off` | 关闭桌面小组件刷新 | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `subproc_lotuspool_off` | 关闭云控调度池（LotusPool） | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `track_off` | 关闭阿里埋点 Analytics | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `alimama_splash_off` | 关闭开屏广告请求（**模**：默认开 / **内**：默认关，`strict`） | 开关 | **两处不一致** | 两 | 高德·省电/后台 | **强停** |
| `alimama_log_off` | 关闭广告曝光上报 | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |
| `subproc_off` | 关闭子进程拦截（总闸，排查用） | 开关 | 开 | 两 | 高德·省电/后台 | **强停** |

> `alimama_splash_off` 是**唯一一处「同一键、两个界面显示不同默认值」**：
> 模块版走 `Config.defaultVisible()` → `true`（显示开）；面板 `Item.strict=true` → 读字面值 → `false`（显示关）。

---

### 3.2 高德 · 内置版悬浮面板（`AmapPanel.groups()`，HEAD 8 页，156 开关 + 3 滑块）

| 页 | 分段 | 行数 | 与模块版的差异 |
|---|---|---|---|
| 首页 | — | 8 | 含 `tool_id_86`（模块版是死键 `tool_更多工具`）、`splash_skip`、`search_grid` |
| 省电 | 推送 / 后台任务 / 埋点与广告 / 地图 | 11 | 多 `labels_on`；无「一键省电」之外的动作 |
| 界面 | 底栏 / 我的页 / 内容卡片 | 14 | 与模块版同 14 键，但**分组方式不同** |
| 地图 | — | 4 | `map_poi` / `map_promo` / `place_recommend` / `labels_on` |
| 巡航 | — | 2 开关 + 2 滑块 | 模块版没有独立页，藏在「首页工具宫格」 |
| 首页工具 | 6 段（按目录分组） | **113** | 模块版 116 行（含 2 个手写死键），面板 113（跳过 `更多工具`） |
| 实验 | — | 2 开关 + 1 滑块 | 同模块版 |
| 其他 | — | 2 | `float_enabled`（仅内置）+ `debug_log`；底部另挂版本/更新行 |

**只在内置版出现的 3 个高德开关**：

| 键名 | 显示标题 | 类型 | 默认 | 说明 | 需重启 |
|---|---|---|---|---|---|
| `splash_skip` | 跳过高德开屏页（不推荐） | 开关（`strict`） | **关**（只认显式 true） | 真机实测拦掉之后不会变快 | 重开 |
| `cruise_official` | 使用高德原生自动进入巡航（推荐） | 开关 | **开** | 走官方状态机（20 km/h × 10 s） | 即时 |
| `labels_on` | 启用第三方标注过滤 | 开关（`strict`） | **关** | 开启后可能影响帧率 | 重开 |

> `labels_on` 在面板里**出现两次**：一次在「省电 → 地图」分段，一次在「地图」页。

---

### 3.3 百度 · 模块版配置页（12 个 drawer，85 行）

百度侧的键在 `io.github.ldxm666.bmapclean.Spec` / `Cfg` / `BaiduLabelSettings`，与高德**不同 prefs 组**（`bmapclean_config`），但**部分键名字面值与高德完全相同**（`push_off` / `push_hard_off` / `subproc_off` / `track_off`…）。

| drawer | 行数 | 键 |
|---|---|---|
| 底部入口栏 | 6 | `ui_bottom_bar`（总闸）+ `tab_首页/周边/长按说话/打车/我的` |
| 首页工具宫格 | 23 | `ui_tool_grid`（总闸）+ `home_tools_compact` + 21 个 `tool_<中文名>` |
| 回家 / 去公司 | 4 | `ui_home_company` + `hc_home` / `hc_company` / `hc_setting` |
| 天气 / 行程栏 | 4 | `ui_weather` + `wx_map` / `wx_card` / `ai_now_card` |
| 推荐信息流 | 3 | `ui_feed` + `feed_chips` / `feed_quality` |
| 「我的」页 | 9 | `mine_apply`（总闸）+ `mine_ops` / `mine_ad` / `mine_voice` / `mine_car` / `mine_carnav` / `mine_build` / `mine_sport` / `mine_grid` |
| 地图标注 | **15** | `map_poi`（总闸）+ 13 个分类 + `poi_other` |
| 搜索区 | 1 | `search_chips` |
| 路线页 | 5 | `route_widget` / `route_ad` / `route_explore` / `route_taxi` / `route_gujin` |
| 地点详情页 | 7 | `poi_guess_search` / `poi_nearby_rec` / `poi_nearby_cards` / `poi_house_listing` / `poi_nearby_estate` / `poi_community_life` / `poi_misc_promo` |
| 诊断 | 1 | `debug_log`（bmapclean 组） |
| **百度 · 省电 / 后台** | **7** | `push_hard_off` / `push_off` / `push_kill_off` / `subproc_off`(后台常驻) / `subproc_job_off`(周期任务) / `subproc_widget_off` / `track_off` |

**13 个 POI 分类**（`poi_metro` 地铁站、`poi_transit` 公交与场站、`poi_sign` 道路标识、`poi_food` 餐饮、`poi_hotel` 酒店住宿、`poi_shop` 购物与金融、`poi_scenic` 景点与休闲、`poi_medical` 医疗、`poi_edu` 教育科研、`poi_life` 生活服务、`poi_auto` 汽车与加油、`poi_office` 写字楼与机构、`poi_fun` 娱乐）**默认全部 `false`（隐藏）**。

**百度默认值一览**：`mine_apply` / `mine_grid` / `mine_voice` / `mine_car` / `mine_carnav` / `mine_build` / `mine_sport` = 开；`mine_ops` / `mine_ad` / `ai_now_card` / 全部 `route_*` / 全部 `poi_*` / 全部 `map_poi` 族 / `home_tools_compact` = **关**；其余 = 开。

**需重启**：`map_poi` 族 + 全部 `route_*` + 全部 `poi_*` + 省电 7 行 = **强停**；其余 = 重开百度。

---

### 3.4 百度 · 内置版悬浮面板（`BaiduPanel.groups()`，6 页，79 行）

| 页 | 分段 | 行数 | 与模块版的差异 |
|---|---|---|---|
| 界面 | 底部入口栏 / 首页工具宫格 / 回家·去公司 / 「我的」页 | 42 | 同键，合并到一页 |
| 内容 | 天气·行程栏 / 推荐信息流 / 搜索区 | 8 | 同键 |
| 地图 | 地图标注 | 15 | 同键 |
| 路线 | 路线页 | 5 | 同键 |
| 省电 | **悬浮窗** / 推送 / 后台与埋点 | 8 | 多 `float_enabled`；少 `subproc_off`（总闸） |
| 其他 | — | 1 | `debug_log` + 版本/更新行 |

> 模块版百度省电组 **7** 行、面板 **8** 行，键集合**不完全相同**：
> 面板有 `float_enabled`，模块版有 `subproc_off`（「关闭后台常驻服务」）。见 P5。

---

### 3.5 腾讯 · 模块版配置页（7 个 drawer，19 + N 行）

| drawer | 行数 | 键 / 默认 |
|---|---|---|
| 底部入口栏 | 6 | `ui_bottom_bar`(开) + `tab_homePage`(开) / `tab_explorePage`(关) / `tab_planPage`(关) / `tab_mePage`(开) / `tab_nearbyPage`(关) |
| 首页工具宫格 | **1 + N** | `ui_tool_grid`(开) + N 个 `tool_<id>`（`defaultToolVisible`）；⚠ `ui_tool_preset` **被覆盖不可见**（见 P7） |
| 首页通勤地址与信息流 | 2 | `ui_home_fav`(开) / `ui_home_feed`(**关**) |
| 内容模块 | 1 | `ui_look_and_look`(**关**) |
| 「我的」页 | 6 | `my_points`(关) / `my_favorites`(开) / `my_personalization`(开) / `my_miniprogram`(关) / `my_vehicle`(开) / `my_activity_zone`(关) |
| 主页地图图标 | 2 | `ui_map_icons`(开) / `ui_seek_chips`(**关**) |
| 诊断 | 1 | `debug_log`(关) |

> `N` = 从真实腾讯进程学回来的工具目录条数；全新安装时为 **0**，此时该 drawer 只有 1 行 + 一句「尚未学到工具目录」。

---

### 3.6 腾讯 · 内置版悬浮面板（`TmapPanel.groups()`，8 页，20 + N 行）

= 「悬浮窗」1 行（`float_enabled`）+ §3.5 的 7 个 drawer 各自成为一页。

> 腾讯内置版**没有**找回入口：`float_enabled` 关掉后必须重装整合版（面板里已如实写明）。

---

### 3.7 「外观」页（仅内置版，`FloatingPanel.APPEARANCE`，4 个滑块）

| 键名 | 显示标题 | 类型 | 默认 | 范围 | 需重启 |
|---|---|---|---|---|---|
| `float_ball_size` | 悬浮球大小 | **滑块** | **46** dp | 36–72，步进 2 | 即时 |
| `float_ball_alpha` | 悬浮球不透明度 | **滑块** | **100** % | 30–100，步进 5 | 即时 |
| `float_panel_width` | 面板宽度 | **滑块** | **0 = 自动** | 自动 + 280–420，步进 10 | 即时 |
| `float_panel_alpha` | 面板不透明度 | **滑块** | **100** % | 60–100，步进 5 | 即时 |

页尾另有两行：`恢复默认外观`（重置上面 4 个键）、`关闭悬浮窗`（写 `float_enabled=false`，**红色警告样式**）。

---

### 3.8 全局项（与厂商无关）

| 键名 | 显示标题 | 类型 | 默认 | 界面 | 分类 | 需重启 |
|---|---|---|---|---|---|---|
| `announce_on` | 接收服务器公告 | 开关 | 开 | 内：其他页 / 模：**仅死页** | 其他 | 即时 |
| `hide_icon_v2` | 隐藏桌面图标 | 开关（本地 prefs，带二次确认） | 关 | 仅模（关于页） | 通用 | 即时 |
| `debug_log`（高德组） | 调试日志 | 开关 | **关** | 两 | 其他 / 通用 | 即时 |
| `debug_log`（百度组） | 调试日志 | 开关 | **关** | 两 | 诊断 | 即时 |
| `debug_log`（腾讯组） | 调试日志 | 开关 | **关** | 两 | 诊断 | 即时 |
| `float_enabled` | 显示悬浮球 / 悬浮窗入口（悬浮球） | 开关 | 开 | 仅内 | 其他 / 省电 / 悬浮窗 | 即时 |

> 「最新公告」入口在面板里有两处（首个 chip 顶部横幅 + 其他页一行），模块版只有首页公告卡。

---

### 3.9 高德工具目录（114 项，全部 `tool_id_<id>`，默认 `false`）

`AmapToolCatalog.ENTRIES` 共 **114** 条，按 `GROUPS` 分 6 组：
**出行 25 / 高德精选 6 / 服务 29 / 小程序 6 / 小工具 8 / 其他 40**。

显示标题一律是 `显示「<工具名>」`，副标题一律是 `工具 ID <id>`。

| 组 | id | 工具名 | 键 |
|---|---|---|---|
| 出行 | 102 | 驾车 | `tool_id_102` |
| 出行 | 360 | 新能源导航 | `tool_id_360` |
| 出行 | 417 | 驾车巡航 | `tool_id_417` |
| 出行 | 271 | 货车 | `tool_id_271` |
| 出行 | 254 | 摩托车 | `tool_id_254` |
| 出行 | 103 | 公交地铁 | `tool_id_103` |
| 出行 | 151 | 实时公交 | `tool_id_151` |
| 出行 | 104 | 步行 | `tool_id_104` |
| 出行 | 105 | 骑行 | `tool_id_105` |
| 出行 | 395 | 高德运动 | `tool_id_395` |
| 出行 | 472 | 无网导航 | `tool_id_472` |
| 出行 | 453 | 卫星求救 | `tool_id_453` |
| 出行 | 106 | 打车 | `tool_id_106` |
| 出行 | 370 | 助老打车 | `tool_id_370` |
| 出行 | 354 | 企业用车 | `tool_id_354` |
| 出行 | 327 | 火车票机票 | `tool_id_327` |
| 出行 | 125 | 跑步运动 | `tool_id_125` |
| 出行 | 126 | 骑行运动 | `tool_id_126` |
| 出行 | 110 | 查公交 | `tool_id_110` |
| 出行 | 111 | 地铁图 | `tool_id_111` |
| 出行 | 541 | 包车 | `tool_id_541` |
| 出行 | 145 | 乘车码 | `tool_id_145` |
| 出行 | 386 | 拼车 | `tool_id_386` |
| 出行 | 469 | 顺风车 | `tool_id_469` |
| 出行 | 502 | 乘车码 | `tool_id_502` |
| 高德精选 | 113 | 景点游玩 | `tool_id_113` |
| 高德精选 | 302 | 充电站 | `tool_id_302` |
| 高德精选 | 168 | 租车 | `tool_id_168` |
| 高德精选 | 114 | 语音包设置 | `tool_id_114` |
| 高德精选 | 544 | 超划算 | `tool_id_544` |
| 高德精选 | 484 | 特价酒店 | `tool_id_484` |
| 服务 | 348 | 我的店铺 | `tool_id_348` |
| 服务 | 362 | 代驾 | `tool_id_362` |
| 服务 | 380 | 旅游度假 | `tool_id_380` |
| 服务 | 323 | 美食 | `tool_id_323` |
| 服务 | 116 | 车主服务 | `tool_id_116` |
| 服务 | 410 | 号卡充值 | `tool_id_410` |
| 服务 | 470 | 沿街取 | `tool_id_470` |
| 服务 | 359 | 新车底价 | `tool_id_359` |
| 服务 | 418 | 秒送 | `tool_id_418` |
| 服务 | 374 | 高德车市 | `tool_id_374` |
| 服务 | 356 | 买房租房 | `tool_id_356` |
| 服务 | 335 | 洗车养车 | `tool_id_335` |
| 服务 | 449 | 洗牙 | `tool_id_449` |
| 服务 | 383 | 休闲玩乐 | `tool_id_383` |
| 服务 | 317 | 订酒店 | `tool_id_317` |
| 服务 | 313 | 优惠加油 | `tool_id_313` |
| 服务 | 537 | 高德快报 | `tool_id_537` |
| 服务 | 363 | 电影 | `tool_id_363` |
| 服务 | 382 | 丽人 | `tool_id_382` |
| 服务 | 458 | 家电维修 | `tool_id_458` |
| 服务 | 471 | 医美 | `tool_id_471` |
| 服务 | 485 | 二手车 | `tool_id_485` |
| 服务 | 490 | 加油充电 | `tool_id_490` |
| 服务 | 501 | 超市 | `tool_id_501` |
| 服务 | 522 | 附近工作 | `tool_id_522` |
| 服务 | 529 | ETC办理 | `tool_id_529` |
| 服务 | 543 | 高德问店 | `tool_id_543` |
| 服务 | 547 | 借钱 | `tool_id_547` |
| 服务 | 568 | 回收 | `tool_id_568` |
| 小程序 | 399 | 地图小程序 | `tool_id_399` |
| 小程序 | 430 | 做旅游攻略 | `tool_id_430` |
| 小程序 | 456 | 共享位置 | `tool_id_456` |
| 小程序 | 432 | 地图管门店 | `tool_id_432` |
| 小程序 | 476 | 共享轨迹 | `tool_id_476` |
| 小程序 | 477 | 地图找客户 | `tool_id_477` |
| 小工具 | 127 | 收藏夹 | `tool_id_127` |
| 小工具 | 333 | 足迹 | `tool_id_333` |
| 小工具 | 319 | 手车互联 | `tool_id_319` |
| 小工具 | 118 | 限行查询 | `tool_id_118` |
| 小工具 | 384 | 地图共建 | `tool_id_384` |
| 小工具 | 130 | 扫一扫 | `tool_id_130` |
| 小工具 | 367 | 写地点评价 | `tool_id_367` |
| 小工具 | 128 | 测距 | `tool_id_128` |
| 其他 | 391 | 油耗记录 | `tool_id_391` |
| 其他 | 283 | 通行费助手 | `tool_id_283` |
| 其他 | 112 | 群组 | `tool_id_112` |
| 其他 | 310 | 家人地图 | `tool_id_310` |
| 其他 | 115 | 离线地图 | `tool_id_115` |
| 其他 | 121 | 驾驶成就 | `tool_id_121` |
| 其他 | 337 | 语音助手 | `tool_id_337` |
| 其他 | 361 | 记录足迹 | `tool_id_361` |
| 其他 | 483 | 高德扫街榜 | `tool_id_483` |
| 其他 | 377 | 停车推荐 | `tool_id_377` |
| 其他 | 375 | 停车记录 | `tool_id_375` |
| 其他 | 349 | 领水果 | `tool_id_349` |
| 其他 | 108 | 电子狗 | `tool_id_108` |
| 其他 | 129 | 商户标注 | `tool_id_129` |
| 其他 | 131 | 反馈上报 | `tool_id_131` |
| 其他 | 133 | 高德淘金 | `tool_id_133` |
| 其他 | 136 | 交通号 | `tool_id_136` |
| 其他 | 303 | 一路护航 | `tool_id_303` |
| 其他 | 352 | 送药上门 | `tool_id_352` |
| 其他 | 365 | 钱包卡券 | `tool_id_365` |
| 其他 | 366 | 我的反馈 | `tool_id_366` |
| 其他 | 378 | 我的问答 | `tool_id_378` |
| 其他 | 388 | 我的相册 | `tool_id_388` |
| 其他 | 390 | 达人中心 | `tool_id_390` |
| 其他 | 392 | 车险报价 | `tool_id_392` |
| 其他 | 393 | 拍图赚赏金 | `tool_id_393` |
| 其他 | 396 | 道路救援 | `tool_id_396` |
| 其他 | 398 | 献血 | `tool_id_398` |
| 其他 | 405 | 发热门诊 | `tool_id_405` |
| 其他 | 406 | 合成烟花 | `tool_id_406` |
| 其他 | 414 | 养车必囤 | `tool_id_414` |
| 其他 | 416 | 电动车投屏 | `tool_id_416` |
| 其他 | 447 | 3D车标 | `tool_id_447` |
| 其他 | 451 | 游戏中心 | `tool_id_451` |
| 其他 | 468 | 高德公益 | `tool_id_468` |
| 其他 | 479 | 天天领福利 | `tool_id_479` |
| 其他 | 546 | 长征星火 | `tool_id_546` |
| 其他 | 558 | 懂鸟 | `tool_id_558` |
| 其他 | 567 | 真探中心 | `tool_id_567` |
| 其他 | 86 | 更多工具 | `tool_id_86` |

> 合计：**114** 个工具开关。模块版配置页把它们摊成 6 个 drawer（另有 2 个手写死键）；
> 内置版面板把它们放在 1 页 6 段里，并跳过 `tool_id_86`（因为它在「首页」页已经有一行）。

---

### 3.10 非开关的动作 / 入口（按钮类）

| # | 显示文字 | 位置 | 动作 | 形态 |
|---|---|---|---|---|
| A1 | 公告卡（整卡可点） | 首页 | `Announce.showLatest()` → D1 | 卡片 |
| A2 | 状态卡（整卡可点） | 首页 | `showStatusDetail()` → D2 | 卡片 |
| A3 | 版本 / 作用域 / 广告 SDK ×3 | 首页状态卡内 | 同上 | 指标格 |
| A4 | 检查 | 首页版本更新卡 | `UpdateChecker.check()` → D4 | 文字按钮 |
| A5 | 抓取云数据 | 首页「数据与日志」 | `startCloudCapture()` | 列表行 |
| A6 | 运行日志 | 首页「数据与日志」 | `showLogDialog()` → D5 | 列表行 |
| A7 | 百度地图 / 高德地图 / 腾讯地图 ×3 | 首页「应用」 | `launchHost()` | 列表行 |
| A8 | GitHub 主页 / Telegram 群组 | 首页「作者」 | `openUrl()` | 列表行 |
| A9 | 赞赏支持 | 首页「赞赏」 | `DonateUi.show()` → D6 | 列表行 |
| A10 | 百度 / 高德 / 腾讯（分段器）×3 | 配置页 | `selectApp(i)` | 分段 chip |
| A11 | 搜索框 + 清空 + 结果胶囊 | 配置页 | `SwitchFinder` | 输入 + chip |
| A12 | 抽屉头（`<标题> · <N> 项`） | 配置页每 drawer | `toggle()` 展开/收起 | 折叠头 |
| A13 | 全部恢复显示（清空百度侧配置） | 百度页尾 | `bmapclean.App.resetAll()` | 危险文字链 |
| A14 | 导出 JSON 文件 | 备份页 | `ConfigBackup.startExport()` | 主按钮 |
| A15 | 选择 JSON 文件 | 备份页 | `ConfigBackup.startImport()` | 次按钮 |
| A16 | 保存快照 / 恢复快照 | 备份页 | `saveSnapshot()` / `restoreSnapshot()` | 双次按钮 |
| A17 | 复制配置文本 / 从剪贴板导入 | 备份页「高级」 | `exportConfig()` / `importConfig()` | 列表行 |
| A18 | 恢复默认设置 | 备份页危险卡 | `resetConfig()`（带确认） | 列表行 |
| A19 | GitHub 仓库 / 版本发布 / Telegram 群组 / 问题反馈 ×4 | 关于页 | `openUrl()` | 列表行 |
| A20 | 一键省电（全部开启拦截） | 面板省电页顶部 | 写 9 个键，**无二次确认** | 动作行 |
| A21 | 抓取官方数据 / 重新抓取 | 面板「首页」页顶部 | `CaptureDialog.show()` → D7 | 横幅 |
| A22 | 公告横幅 / 最新公告 | 面板首个 chip 顶部 + 其他页 | `Announce.showLatest()` | 横幅 + 行 |
| A23 | 抓取日志（导出/开始录制） | 面板其他页 | `CaptureLog.dumpRecent / toggleLive` | 行 + 按钮 |
| A24 | 当前版本 / 检查更新 | 面板其他页底部 | `UpdateChecker.check()` | 两行 |
| A25 | 恢复默认配置 | 面板页脚 | `DefaultConfig.apply()`（带确认） | 文字链 |
| A26 | 赞赏支持 | 面板页脚 | `DonateUi.show()` | 文字链 |
| A27 | 关闭悬浮窗 | 面板外观页 | `float_enabled=false`（红色警告） | 危险行 |
| A28 | ✕ 关闭 / 作者链接 / 状态药丸长按 | 面板头部 | `closeFrom()` / `openUrl()` / toast | 图标 + 药丸 |

### 3.11 只读展示项（不可操作）

| 项 | 位置 | 数据源 |
|---|---|---|
| 版本胶囊 `v<Version.LABEL>` | 首页顶部 / 面板头部 | `Version` |
| 首页三格指标：版本 / 作用域 / 广告 SDK | 首页状态卡 | `FrameworkStatus` / `sdkCount()` |
| 状态药丸（连接中 / 已连接 / 只读 / 未连接） | 首页状态卡 / 面板头部 | `FloatingConfig.backend()` |
| 配置统计「<应用> · N 项开关」 | 配置页 | `switches.size()` 等 |
| 备份状态行 | 备份页 | `ConfigBackup` 回调 |
| 支持版本行（推荐版本 / 本机 x.y.z） | 关于页 | `Version.TARGETS` + `installedVersion()` |
| 公告标题 / 公告正文 | 首页公告卡 / 面板 | `Announce` |
| 抓取云数据 / 运行日志的副标题 | 首页 | 心跳刷新 |
| 「新版本」徽章 | 面板头部 | `UpdateChecker` |
| 组说明 `Group.note` | 面板每页顶部 | 常量文案 |
| 页脚文案（免费开源 · 禁止倒卖） | 首页 / 关于 / 面板 | 常量 |

### 3.12 有 hook 读取、但**没有任何 UI** 的键（16 个）

这些键 `Config` 里定义了、hook 侧也在读，但 `MainActivity` / `AmapPanel` / `FloatingPanel` 里一次都没引用
（正则 `Config\.<K_>` 命中数 = 0）。用户永远无法通过界面改变它们。

| 键名 | 字面值 | 说明 |
|---|---|---|
| `K_FEED_SCENIC` | `feed_scenic` | 旧版景区帖开关 |
| `K_FEED_RANK` | `feed_rank` | 旧版榜单开关 |
| `K_FEED_POSTS` | `feed_posts` | 旧版内容帖开关 |
| `K_FEED_DISTANCE` | `feed_distance` | 旧版距离帖开关 |
| `K_FEED_AI` | `feed_ai` | AI 内容流 |
| `K_FEED_FILTER` | `feed_filter` | 内容过滤 |
| `K_HOME_QUICK_ROW` | `home_quick_row` | 首页快捷行（`defaultVisible` 里专门为它写了 `false`，却没有开关） |
| `K_TOOL_SORT` | `tool_sort` | 旧版工具重排（代码注释说已移除） |
| `K_SEARCH_AD` | `search_ad` | 搜索页广告 |
| `K_RIDE_CARD_OFF` | `ride_card_off` | 顺风车/代驾卡 |
| `K_TAXI_CARD` | `taxi_card` | 打车卡（`defaultVisible` 里同样专门写了 `false`） |
| `K_MY_QUALITY` | `my_quality` | 「我的」页资质卡 |
| `K_NAV_URI_FIX` | `nav_uri_fix` | 外部导航 URI 归一化 |
| `K_LABELS_OFF` | `labels_off` | 不装标注 hook（排查掉帧用） |
| `K_CRUISE_FORCE_ONCE` | `cruise_force_once` | 「立即测试」隐藏键（设计上就不进 UI） |
| `K_CFG_FORCE_LOCAL` | `cfg_force_local` | 强制本地后端（设计上就不进 UI） |

### 3.13 隐藏键 / 内部键（不在任何可见列表）

`cruise_force_once`、`cfg_force_local`、`_probe`、`_local_created`、`_local_migrated`、
`_defaults_applied`、`cfg_snapshot`、`ack_at`、`announce_seen_id`、`announce_last_*`。

---

## 4. 分类合理性问题（18 条）

> 按「对重构决策的影响」排序，不按严重程度。

### P1 · 一整页 UI 是死的，但清单上看起来是有的
`MainActivity.buildOtherPage()`（1390–1450）无任何调用点。它承载「接收服务器公告」「隐藏桌面图标」
「抓取前 10 分钟」「抓取后 10 分钟」「去广告说明」。**模块版用户根本进不去这一页。**
连带 13 个死方法：`buildHero` / `buildMetrics` / `buildForcedCard` / `buildBottomBar` / `addTab` /
`animateIn` / `applyHeroInset` / `applyWindowBloom` / `adaptSummary` / `capsuleBg` /
`addBmapInfoCard` / `addTmapInfoCard`，以及整条 `showConfigManager → showConfigMore → showConfigFile` 弹窗链和 `showContact()`。
另有 3 个死文件：`FirstRunNotice.java`(241)、`GlassDrawable.java`(119)、`Backdrop.java`(132)。

**后果**：任何「按现状分类」的重构方案都会把不存在的入口算进去。必须先删，再谈分组。

### P2 · 同一个开关两套键，其中一个永远无效
- 模块版：`tool_更多工具`（`MainActivity:1087`）
- 面板：`tool_id_86`（`AmapPanel.moreToolsKey()`）

`AmapToolCatalog.key("86")` 返回 `tool_id_86`，所以模块版那一行写进 `tool_更多工具` 后**没有任何读取方**。
用户在高德首页看不到「更多工具」时会去关它 —— 关的是个空键。
同类：`tool_高德出行节`（id 461 不在 `ENTRIES`，回落到 legacy 键，这个反而是对的）。

### P3 · 同一个键在同一页出现两次
`labels_on` 在面板里出现两处：「省电 → 地图」分段（`powerSections()`）和「地图」页（`groups()` 第 4 项）。
两处 `Item` 标题/说明一字不差，但一个在省电页、一个在地图页。改一处，另一处不会跟着变（各建各的 `GlassSwitch`）。

### P4 · 3 个高德开关只有内置版有
`splash_skip`、`cruise_official`、`labels_on` 只在 `AmapPanel` 注册。模块版用户（root/LSPosed 主力用户）
**永远看不到这三项**，而它们控制的是高德开屏页、官方巡航接管、第三方标注过滤 —— 都不是内置版专属功能。

### P5 · 百度「省电」两处行集合不同
模块版 7 行（含 `subproc_off`「关闭后台常驻服务」），面板 8 行（含 `float_enabled`，无 `subproc_off`）。
同一个语义页，两个界面能关的东西不一样，且**排序也不同**。

### P6 · 同一份 114 项工具目录，两个界面结构不同
模块版：6 个独立 drawer（要先滚到「工具：其他」再在 40 行里找）。
面板：1 页 6 段。
同一个数据源（`AmapToolCatalog.ENTRIES`），两套渲染，两套跳过规则（模块版不跳 `更多工具`，面板跳）。

### P7 · 腾讯「只保留常用入口」开关被静默覆盖
`tmapclean/Spec.cats()`：
```java
Row[] tools = new Row[1 + ids.length + (ids.length == 0 ? 1 : 0)];
tools[0] = master;
tools[0 + 1] = new Row(K_TOOL_PRESET, "只保留常用入口…");   // 写在 [1]
for (int i = 0; i < ids.length; i++) tools[i + 1] = …;      // 又从 [1] 开始写 ⇒ 覆盖
```
只要学到 ≥1 个工具，这一行就**永远不显示**。`K_TOOL_PRESET` 默认 `false`，也没有其它入口。

### P8 · 语义重复 / 名称相近的开关相邻摆放
「地图」页同时有 `map_poi`（显示兴趣点图标和名称）和 `labels_on`（启用第三方标注过滤）。
两者名字都在说「标注」，实现一个是原生样式改写、一个是 Java 层 hook 补充过滤，效果互相叠加。
另有 `labels_off`（不装 hook）完全无 UI —— 三个名字相近的键，只有一个有清晰入口。

### P9 · 危险操作与普通操作混放，且无二次确认
「一键省电（全部开启拦截）」一次写 **9 个键**（`POWER_ALL_KEYS`），就在省电页普通开关的正上方，
点击即写、无确认、无回滚入口。对比：备份页的「恢复默认」有独立危险卡 + 确认弹窗，
「隐藏桌面图标」有确认弹窗，「关闭悬浮窗」有红色警告样式。**危险度的视觉编码不一致**。

### P10 · 层级过深：16 个 drawer 里 6 个是工具分类
模块版高德页需要：应用 chip → 抽屉（默认收起）→ 行。找「优惠加油」要走
配置 tab → 高德 chip → 滚到「高德 · 工具：服务」→ 展开 → 在 29 行里找。

### P11 · 层级过浅 / 过深并存
百度模块页 **12 个 drawer 全部默认收起**（`// 全部默认收起：与高德页统一`），用户看到的是 12 行折叠头；
百度面板是 **6 页 + 页内分段标题**（无折叠）。同一个 App 两套信息密度。

### P12 · 分类名与内容不符（一）
抽屉「高德 · 首页工具宫格」里塞了 `cruise_auto` + 两个巡航滑块。巡航是驾驶行为判定，
和「工具宫格显示哪些格子」无关。面板侧已把它单列成「巡航」页 —— 两处分类互相矛盾。

### P13 · 分类名与内容不符（二）
抽屉「高德 · 首页推荐内容」里有 `map_promo`（**地图右侧**运营挂件）和 `home_chips`（首页通勤快捷入口）。
一个在地图上、一个在首页顶部，都不属于「首页推荐内容」。

### P14 · 只读展示与可操作项同层
首页状态卡的三格指标（版本 / 作用域 / 广告 SDK）是纯展示，却和下方「应用」「作者」列表行
同一视觉层级、同样可点（都打开同一个 D2 弹窗）。用户分不清哪格是开关、哪格是信息。

### P15 · 16 个键有 hook 读取但无 UI
见 §3.12。其中 `home_quick_row` 和 `taxi_card` 在 `Config.defaultVisible()` 里被**专门**写成 `false`，
说明作者认为它们是用户会想调的 —— 但没有任何界面能调。

### P16 · 「省电」页语义与其它所有页相反
`push_*` / `subproc_*` / `track_off` / `alimama_*`：`开 = 该行为已被拦截`。
其它所有页：`开 = 该功能被显示`。这一页靠 `POWER_NOTE` 一段文字兜底，是全项目最容易读错的地方。
（工作区已在把标题改成「净化」，但**语义没有变**，只是换了个名字。）

### P17 · 备份页与死掉的「配置管理」弹窗链功能重复
`buildBackupPage()` 提供导出/导入/快照/恢复默认；死掉的 `showConfigManager()` 链提供同一组操作。
两套实现、两套文案，只有一套可达。

### P18 · 公告入口三处、模块版实际零处
- 面板：首个 chip 顶部横幅（主入口）
- 面板：「其他」页「接收服务器公告」+「最新公告」两行（兜底）
- 模块版：首页公告卡（只能读，**不能关** —— 开关在死页里）
---

## 5. 界面骨架清单（自绘组件）

全部界面**没有一个 XML layout**，`app/src` 下 `layout/` 目录不存在。
骨架由 4 个自绘 `View` 子类 + 2 个样式层 + 若干弹窗工具构成。

### 5.1 自绘 View

| 组件 | 文件 | 行数 | 谁在用 | 用在哪 | 备注 |
|---|---|---|---|---|---|
| `GlassSwitch` | `GlassSwitch.java` | 265 | `MainActivity`（经 `makeSwitch()`，**5 处**工厂调用）、`FloatingPanel.buildRow()`、`SwitchFinder` | 全部开关行 | 自定义 `onDraw` 胶囊 + 滑块，`setLabel()` 供无障碍 |
| `GlassSlider` | `GlassSlider.java` | 313 | `MainActivity.addCruiseSlider()`、`FloatingPanel.buildMetricRow()` | 巡航 2 个滑块 + 外观 4 个滑块 | 停靠点由 `Metric.values` 决定 |
| `Pager` | `Pager.java` | 295 | **仅 `MainActivity`** | 4 个主 tab | `ViewGroup` 自绘水平位移 + `applyTransforms()` |
| `Icon` | `Icon.java` | 310 | `MainActivity`（17 处 `new Icon`）、`SwitchFinder`（1 处） | 导航图标、列表行图标、按钮图标 | 18 种 `kind`，**实际只用了 14 种**；`SHIELD` / `CHECK` / `SAVE` / `BOLT` 零引用 |
| `AvatarBadge` | `AvatarBadge.java` | 150 | **仅 `FloatingPanel`** | 面板头部作者头像（38dp） | 头像缺失时退化为字母 |
| `GithubMark` | `GithubMark.java` | 196 | `MainActivity`（2 处）、`FloatingPanel`（1 处） | 作者卡 / 关于页链接 / 面板作者链接 | 手绘 GitHub 标记路径 |
| `BloomDrawable` | `MainActivity` 内部类 | 65 | `MainActivity` | 窗口底层的径向光斑 | `applyWindowBloom()` 调用，但 `applyWindowBloom` 本身是死方法 |
| `FloatingBall` | `FloatingBall.java` | 494 | `FloatingController`（仅内置版） | 悬浮球 | 拖动 / 贴边 / 展开把手 |

### 5.2 样式与动效层

| 层 | 文件 | 行数 | 调用点数 | 职责 |
|---|---|---|---|---|
| `Skin` | `Skin.java` | 443 | **468** | 颜色 token（`bg` / `surface` / `surface2` / `text` / `text2` / `text3` / `stroke` / `accent` / `accentSoft` / `success` / `warn` / `danger` / `chipBg` / `glassTint`）、间距 token `S1..S7`、圆角 token `R_CARD/R_INNER/R_CONTROL/R_PILL`、字阶 `T_DISPLAY/T_TITLE/T_ROW/T_BODY/T_CAPTION/T_LABEL`、卡片/行/按钮工厂 |
| `UiKit` | `UiKit.java` | 284 | **429** | `glass()` / `plate()` / `gradient()` / `outlined()` / `ripple()` / `rippleBare()` / `clipRounded()` / `pressable()` / `enter()` / `reveal()` / `enterStaggered()` / `animateInt()` / `animateFloat()` / `blend()` / `dp()` / `dpf()` + 4 个 `PathInterpolator` + 3 个时长常量 |

**两套样式层并存且互不覆盖**：
`Skin` 只有 `MainActivity` 用（+ `Announce` / `DonateUi` / `FirstRunNotice`），
`FloatingPanel` / `GlassSwitch` / `GlassSlider` / `AvatarBadge` / `FloatingBall` / `FloatingController` / `SwitchFinder` **全部绕过 Skin 直接用 `UiKit` + 自己的私有 `cXxx()` 配色函数**。

| 文件 | 私有配色函数 |
|---|---|
| `MainActivity` | `cBg/cGlass/cRimHi/cRimLo/cTxP/cTxS/cAccent/cRipple/cTrackOff/cDotOk/cDotBad/cAccentTop/cAccentBottom/pillBg/metricBg/tabOnBg/tintBg` = **17 个** |
| `FloatingPanel` | `cBg/cGlass/cRimHi/cRimLo/cTxP/cTxS/cAccent/cRipple/cDotOk/cDotBad/cWarn/cWarnFill/cAccentTop/cAccentBottom` = **14 个** |
| `GlassSwitch` / `GlassSlider` / `SwitchFinder` / `DonateUi` / `CaptureDialog` | 各自内联 4–13 个颜色字面量 |

### 5.3 辅助组件

| 组件 | 文件 | 行数 | 用在哪 |
|---|---|---|---|
| `SwitchFinder` | 315 | 配置页搜索：索引（`index(key,title,note,page)`）、结果胶囊、跳页 |
| `DonateUi` | 211 | 赞赏弹窗（首页 + 面板页脚） |
| `CaptureDialog` | 168 | 抓取流程弹窗（**仅内置版**） |
| `AvatarAsset` / `DonateAsset` / `ModuleResources` | 174 / 511 / 211 | 从模块 APK 里取 drawable（不依赖宿主资源表） |
| `AppIcon` | 72 | 首页「应用」三行的图标 |
| `FirstRunNotice` | 241 | **死代码** |
| `GlassDrawable` | 119 | **死代码** |
| `Backdrop` | 132 | **死代码** |

---

## 6. 硬编码样式普查

> 口径：只统计**代码行**（以 `*` / `//` / `/*` 开头的行已排除）。
> 6 位十六进制（`0x4099d4` 等 23 处）经逐条核对全部是 smali/dex 偏移注释，**不是颜色**，已排除。

### 6.1 颜色

| 指标 | 值 |
|---|---|
| `0xAARRGGBB` 字面量（代码） | **354** 处 |
| **不同颜色值** | **197** 个 |
| `Color.parseColor` | 1 处 |
| `Color.rgb/argb` | 1 处 |
| `setBackgroundColor(...)` | 11 处 |
| `setTextColor(...)` | 83 处 |
| 分布文件数 | **22** 个 |

**按文件分布（出现数 / 不同值数）**

| 文件 | 出现 | 不同值 | 唯一度 |
|---|---|---|---|
| `MainActivity.java` | 81 | 46 | 57% |
| `FloatingPanel.java` | 73 | 54 | 74% |
| `Skin.java` | 60 | 45 | 75% |
| `Anchors.java` | 36 | **36** | **100%** |
| `GlassDrawable.java` | 16 | 14 | 88% |
| `CaptureDialog.java` | 14 | 13 | 93% |
| `SwitchFinder.java` | 12 | 12 | 100% |
| `GlassSwitch.java` | 9 | 7 | 78% |
| `DonateUi.java` | 9 | 7 | 78% |
| `AmapNativeHome.java` | 9 | 7 | 78% |
| `AppIcon.java` | 7 | 7 | 100% |
| `GlassSlider.java` | 6 | 6 | 100% |
| `FloatingController.java` | 6 | 5 | 83% |
| `AvatarBadge.java` | 5 | 5 | 100% |
| `FloatingBall.java` | 4 | 3 | 75% |
| 其余 7 个文件 | 各 1 | 各 1 | 100% |

**出现次数最多的 10 个值**

| 值 | 次数 | 语义 |
|---|---|---|
| `0xFFFFFFFF` | 34 | 纯白 / 玻璃高光 |
| `0x00000000` | 11 | 全透明（当占位背景用） |
| `0x33FFFFFF` | 9 | 20% 白（rim / 分隔） |
| `0xFF0A6CF5` | 8 | 主强调蓝（浅色） |
| `0x00FFFFFF` | 8 | 全透明白 |
| `0x14000000` | 8 | 8% 黑 |
| `0x2E7AB1FF` | 8 | 18% 蓝（选中底） |
| `0x0F000000` | 7 | 6% 黑 |
| `0x2E8A94A4` | 6 | 18% 灰（关闭态轨道） |
| `0x22FFFFFF` | 5 | 13% 白 |

**关键发现：`Skin.java` 自己就带 45 个不同颜色值。**
「有 token 层」是名义上的 —— token 文件本身也是另一份散落，且 `MainActivity` 与 `FloatingPanel`
各自还定义了 17 / 14 个私有配色函数，等于**三套并行的调色板**。

#### 6.1.1 对照：`7de8711` 的重构之后

| 指标 | `8b483a0`（基线） | `7de8711`（重构后） | 变化 |
|---|---|---|---|
| 颜色字面量 | 354 | **358** | +4 |
| **不同颜色值** | **197** | **201** | **+4（变多）** |
| 涉及文件 | 22 | **23** | +1 |
| `Skin.java` | 60 / 45 | **23 / 14** | −37 / −31 |
| `FloatingPanel.java` | 73 / 54 | 41 / 26 | −32 / −28 |
| `GlassSwitch.java` | 9 / 7 | 5 / 4 | −4 / −3 |
| `HyperDesign.java` | — | **77 / 56** | 新增 |

**结论：这一轮重构没有减少颜色总数，只是把散落从 `Skin` 搬到了新的 `HyperDesign`。**
「不同颜色值」从 197 涨到 **201**，文件数从 22 涨到 23。
⇒ 如果目标是「一处定义、处处引用」，现在的方向是**增加**了一层而不是收敛到一层。
重构前必须先决定 `Skin` 与 `HyperDesign` 谁去谁留，否则样式分散度只会继续上升。

### 6.2 dp / 间距

| 调用形态 | 次数 |
|---|---|
| 裸 `dp(N)`（`MainActivity` 私有 helper） | 234 |
| `skin.dp(N)` | 77 |
| `skin.dp(Skin.SN)`（token） | **75** |
| `UiKit.dp(ctx, N)` | 208 |
| `UiKit.dpf(ctx, Nf)` | 39 |
| **合计（dp 家族）** | **761** |

**数字型 dp 值：46 个不同值**（带数字的调用 570 处）。最常用 12 个：

| 值 | 次数 | | 值 | 次数 |
|---|---|---|---|---|
| 10dp | **72** | | 12dp | 33 |
| 14dp | **58** | | 20dp | 31 |
| 8dp | 47 | | 11dp | 26 |
| 2dp | 44 | | 16dp | 26 |
| 6dp | 43 | | 18dp | 19 |
| 4dp | 41 | | 3dp | 16 |

**`Skin.S1..S7` 只有 7 个 token（= 4 / 8 / 12 / 16 / 20 / 24 / 32），
覆盖率 75 / 570 ≈ 13%。** 也就是说 87% 的间距是裸数字，
而裸数字里 `10` / `14` / `11` / `18` 这些**不在 token 表里**的值占了绝大多数。

`setPadding(...)` 共 **124** 次，18 个不同数字。Top 10：

| 数字 | 次数 | | 数字 | 次数 |
|---|---|---|---|---|
| 0 | 29 | | 6 | 8 |
| 14 | 14 | | 2 | 8 |
| 20 | 11 | | 18 | 7 |
| 4 | 11 | | 10 | 6 |
| 8 | 9 | | 11 | 9 |

### 6.3 字号

`setTextSize(<字面量>)` **55** 次，**13 个不同值**：

| sp | 次数 | | sp | 次数 |
|---|---|---|---|---|
| 13 | 11 | | 14.5 | 3 |
| 11.5 | 10 | | 17 | 3 |
| 12 | 7 | | 16 | 1 |
| 13.5 | 6 | | 12.5 | 1 |
| 10.5 | 5 | | 14 | 1 |
| 11 | 5 | | 9.5 | 1 |
| | | | 20 | 1 |

`Skin.T_*` 只有 6 个 token（30 / 18 / 15 / 13.5 / 12 / 11）。
裸值里的 **11.5 / 10.5 / 13 / 14.5 / 12.5 / 9.5** 全部不在 token 表内 —— 半像素字号是随手写的。

### 6.4 圆角

| 指标 | 值 |
|---|---|
| `setCornerRadius(...)` 直接调用 | **18** 次 / 7 种实参形态 |
| 半径位置的调用点合计（含 `UiKit.plate/gradient/outlined/ripple/rippleBare/glass/clipRounded`、`skin.solid/border/glass/glassFor/tintedGlass`） | **117** 处 |
| 命名半径常量 | **6** 个：`R_CARD=28`、`R_INNER=14`、`R_CONTROL=12`、`R_PILL=999`、`GLASS_R=22`、`HEAD_R=18` |
| `R_CONTROL` 引用数 | **0**（声明了没用） |
| `R_PILL` 引用数 | **0**（声明了没用） |

**圆角没有独立词汇表**：除 6 个命名常量外，其余半径全部以裸 `dp` 数字传进
`UiKit.plate(color, dp(24))` 这类调用，与间距共用同一批 46 个数字。
静态上无法区分「这个 14 是圆角还是 padding」。

### 6.5 动画时长

| 指标 | 值 |
|---|---|
| `setDuration(...)` 调用 | **27** 次 |
| 命名常量 | 3 个：`UiKit.DUR_QUICK=140`、`DUR_BASE=240`、`DUR_SLOW=380`（`DUR_BASE` 被用 **11** 次） |
| 裸数字 | **10 个不同值**：1 / 90 / 130 / 150 / 180 / 190 / 200 / 220 / 240 / 260（ms） |
| 三元表达式里的裸值 | `snap ? 260L : 200L`、`target > progress ? 240L : 180L`、`target < from ? DUR_QUICK : DUR_BASE` |

### 6.6 结构规模（重构工作量的直接指标）

| 指标 | 次数 |
|---|---|
| `addView(...)` | **426** |
| `new LinearLayout(...)` | 114 |
| `new TextView(...)` | 64 |
| `new FrameLayout(...)` | 12 |
| `new GradientDrawable(...)` | 21 |
| `setOnClickListener(...)` | 77 |
| `setVisibility(...)` | 105 |
| `setLayoutParams(...)` | 31 |
| `skin.*` 调用 | 468 |
| `UiKit.*` 调用 | 429 |

---

## 7. 工作量评估与优先项

### 7.1 需要迁移的「字面量」总量

| 类别 | 不同值 | 出现处 |
|---|---|---|
| 颜色 | **197** | 354 |
| dp 数字 | **46** | 570 |
| sp 字面量 | **13** | 55 |
| 圆角（命名 6 + 裸数字不可分离） | ≥6 | 117 |
| 动画时长 | 10 裸 + 3 命名 | 27 |
| **合计需决策的语义命名** | **≈272 个** | **≈1123 处** |

### 7.2 三个数量级

1. **纯样式 token 化**：≈1123 处替换 + 272 个命名决策。机械，可脚本辅助。
2. **组件收敛**：把 `MainActivity` 的 17 个私有配色函数和 `FloatingPanel` 的 14 个合并进 `Skin`
   （或反过来废掉 `Skin`，全部走 `UiKit`）。**必须先选一个方向**，现在两套并存是最大的隐性成本。
3. **结构重排**：删死代码 + 合并两套目录源 + 处理 18 条分类问题。**这是真正的成本，不是样式。**

### 7.3 最该优先重排的 3 个问题

#### ① 先删死代码，否则任何分组方案都算错（P1 / P17）

`buildOtherPage()` 整页 + 13 个死方法 + 3 个死文件（`FirstRunNotice` 241 行 / `GlassDrawable` 119 行 /
`Backdrop` 132 行）+ 2 条死弹窗链。
不清掉，「哪些功能有入口」这件事本身就没有唯一答案 —— 本文 §3 里已经要专门为它开一节
（§1.3 / §3.8 / P18），后续任何「合并/拆分分类」的讨论都会被这批幽灵入口污染。
**动作**：`rg` 出 0 引用的方法与类，整块删除，`docs/current-ui-inventory.md` 里对应的行随之作废。

#### ② 收敛到单一目录源：删掉 `MainActivity.buildDrawers()` 的手写副本（P2 / P3 / P4 / P6）

现状是**同一份高德开关有两套分组、两套键、三处不一致**：
- `tool_更多工具`（模）vs `tool_id_86`（内）—— 前者永远无效；
- `labels_on` 在面板里出现两次；
- `splash_skip` / `cruise_official` / `labels_on` 只在面板存在；
- 114 项工具在两个界面被摊成 6 个 drawer vs 1 页 6 段。

**动作**：让配置页直接渲染 `AmapPanel.groups()` / `BaiduPanel.groups()` / `TmapPanel.groups()`
返回的 `PanelTypes.Group[]`（`MainActivity` 已经在 import `PanelTypes`，类型是现成的），
`buildDrawers()` 整体删除。这一步同时解决 P2/P3/P4/P6，并把「一份 UI 两处维护」降为一份。
> 注意：`Group` 的渲染需要抽屉/分段两种形态，`MainActivity` 现有 `newDrawer()` 可以保留为
> 「把 Group 渲染成 drawer」的适配器，而不是另写一份内容。

#### ③ 「省电」页语义重排 + 危险动作分级（P9 / P16）

`push_*` / `subproc_*` / `track_off` / `alimama_*` 这一族是 `开 = 已拦截`，与其余全部页相反；
而「一键省电」一次写 9 个键、无确认、无回滚，就摆在普通开关上方。
工作区已经在把标题从「省电」改成「净化」（只改名，语义没动），这反而让「净化」这个词同时盖住了
推送长连接（真省电）、后台任务（省电）、埋点上报（去广告）、开屏请求（去广告）四类不同后果。

**动作**：
- UI 层统一反转（开关显示「关闭 XXX」而不是「XXX 已拦截」），或在渲染器里给这一族加
  `inverted` 标志（`PanelTypes.Item` 现在只有 `strict`，加一个布尔即可）；
- 「一键省电」拆成「只省电（推送+后台）」和「只去广告（埋点+开屏）」两个动作，各带确认弹窗；
- 把 `alimama_splash_off` 在两个界面的默认值对齐（现在一个显示开、一个显示关）。
---

## 8. 已在进行的重构：现状与本文的适用边界

盘点期间这批改动从「工作区脏」变成了两个提交（`075b549` + `7de8711`）。逐项判断它们对本文的影响：

| 改动 | 对本文的影响 | 是否需要重做盘点 |
|---|---|---|
| `POWER_CHIP` `省电` → `净化`（高德 + 百度） | §2.2 / §3.2 / §3.4 的**页名** | 否（行集合不变） |
| 高德「巡航」页并入「地图」页 | §2.2 页数 8 → 6；§3.2 分页表 | 否（键与行不变，只是搬家） |
| 高德「实验」页并入「其他」页 | 同上 | 否 |
| `PanelTypes.Group(items, metrics, note, about)` 新重载 | 无（是能力增强） | 否 |
| `GlassSwitch` 重写 | §5.1 行为细节 | 否 |
| `Skin` 颜色 60→23 | §6.1 | **部分**（已在 §6.1.1 给出对照） |
| **新增 `HyperDesign` 56 个颜色值** | §6 的核心结论 | **部分**（已计入 §6.1.1） |
| `MainActivity.divider()` 左缩进 | 无 | 否 |

**一句话**：§3 的功能项清单（键名 / 标题 / 默认 / 依赖 / 重启要求）**对 `8b483a0` 和 `7de8711` 都成立**，
可以直接拿去当重构的输入；§2 的分类树与 §6 的样式计数**以基线为准**，并已在正文标注差异。

**尚未被这轮重构触及的部分**（本文的全部结论仍然完全有效）：

- P1 死代码（`buildOtherPage` / 13 个死方法 / 3 个死文件 / 2 条死弹窗链）—— **一行没删**
- P2 双键 `tool_更多工具` vs `tool_id_86` —— **仍然存在**
- P3 `labels_on` 在面板里出现两次 —— **仍然存在**
- P4 `splash_skip` / `cruise_official` / `labels_on` 模块版仍无入口 —— **仍然存在**
- P5 百度省电两处行集合不同 —— **仍然存在**
- P7 腾讯 `ui_tool_preset` 被覆盖 —— **仍然存在**
- P9 「一键省电」无确认 —— **仍然存在**
- P15 16 个键无 UI —— **仍然存在**
## 9. 数字汇总（一页速查）

| 指标 | 值 |
|---|---|
| **界面数** | 8 个用户可达「屏」（4 个主 tab + 3 个应用配置子页 + 悬浮面板 + 悬浮球）+ 8 类弹窗 |
| 其中内置版专属 | 2（悬浮面板、悬浮球） |
| 其中模块版专属 | 1（腾讯地图配置页） |
| 死界面 | 1 整页 + 2 条弹窗链 + 3 个死组件文件 |
| **功能项总行数** | **≈525 行**（模块版 259 + 内置版高德 167 + 百度 79 + 腾讯 20） |
| 去重后的键 | **271 个** + N 个腾讯工具键（N = 运行时学到） |
| 高德工具目录 | 114 项（6 组：出行 25 / 高德精选 6 / 服务 29 / 小程序 6 / 小工具 8 / 其他 40） |
| 有 hook 读取但无 UI 的键 | **16** |
| 隐藏/内部键 | 10 |
| 安装期门控项（改了必须强停） | 高德 12（`map_poi` + 实验 3 + 省电 8）／百度 27（`map_poi` 族 15 + 路线 5 + 地点详情 7）／腾讯 0 |
| **分类问题条数** | **18 条**（P1–P18） |
| **样式分散度** | 基线 **197 个不同颜色值** / 354 处 / 22 个文件；重构后 **201 个 / 358 处 / 23 个文件**（没收敛，见 §6.1.1）。dp 46 个值 / 570 处；sp 13 个值 / 55 处；圆角命名 6 个（2 个零引用）；动画时长 10 裸值 + 3 命名 |
| 自绘组件 | 8 个（`GlassSwitch` / `GlassSlider` / `Pager` / `Icon` / `AvatarBadge` / `GithubMark` / `BloomDrawable` / `FloatingBall`），其中 `Icon` 18 种图标只用 14 种 |
| 样式层 | 2 套并存（`Skin` 468 次调用 / `UiKit` 429 次），外加 31 个私有配色函数 |
| XML layout | **0** |

---

*本文由只读盘点生成，未修改任何源码，未执行构建，未接触设备。*