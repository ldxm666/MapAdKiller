# MapAdKiller

> 高德 / 百度 / 腾讯地图 去广告 Xposed (LSPosed) 模块
> Ad-blocking LSPosed module for Amap / Baidu Maps / Tencent Maps

**中文** | [English](#english)

---

## 中文

一个纯本地运行的 LSPosed 模块（libxposed **API 102**：`XposedModule` 入口 + `META-INF/xposed` 元数据，无 legacy de.robv 依赖），针对国内三大地图 App 的**开屏广告、首页运营横幅、信息流广告卡、第三方广告 SDK** 做确定性拦截。所有 hook 点均来自对目标 APK 的静态逆向分析（jadx / apktool / androguard），证据表见 [docs/ANALYSIS.md](https://github.com/ldxm666/MapAdKiller/blob/master/docs/ANALYSIS.md)。

### 功能一览

| 目标 App | 包名 | 拦截内容 |
|---|---|---|
| 高德地图 | `com.autonavi.minimap` | 开屏广告（强制走自家 NO_SPLASH 收尾路径，不卡启动）、实时广告拉取、首页轮播 Banner、后台推送运营弹窗、搜索页模板开屏、AJX 首页联动广告数据 |
| 百度地图 | `com.baidu.BaiduMap` | 开屏广告（Native/OpenAPI/Push 三渠道全灭）、聚合层 6 家 ADN 加载器（聚量/GroMore/美数/章鱼/趣盟/推荐位）、悬浮运营黄条（"做任务领现金"类）、首页中部横幅、BMAd 开放封装 （v1.0.7 起开屏已可正常收尾，不再需要手动按返回键）|
| 腾讯地图 | `com.tencent.map` | 广点通 GDT SDK 初始化（`initWith=false`，连带 tangramsplash / 融合 SDK 全部失效）、开屏流水线任务、首页 Banner 数据绑定、POI 列表广告卡（视图层） |

另含**通用视图清扫兜底**（ViewKiller）：每次 Activity resume 后遍历视图树，命中已知广告类名/资源名即 GONE + 移除子树。


### 高德首页 / 「我的」页 UI 自定义（v1.0.5 起）

模块 App 内提供设置页，可逐项开关高德地图的首页与「我的」页元素：

| 分类 | 可配置项 |
|---|---|
| 主页标签栏 | 首页 / 探索 / 长按说话 / 打车 / 我的 —— 逐 tab 隐藏，剩余自动均分并**撑满整条悬浮栏** |
| 首页工具宫格 | 10 个工具逐格隐藏，剩余格子行内补空；扩展工具页可单独开关；**「收藏夹」有独立开关** |
| 首页推荐内容 | 天气卡、周边景区、榜单帖、距离卡（公里/米）、精选榜单、攻略内容流、问问 AI、推荐频道栏、设置家等 |
| 首页运营卡 | 「去XX / 帮我预约车辆 / AI叫车 / 顺风车…」那一排轮播推广卡，**按版式识别**，一个开关全灭 |
| 搜索页 | 顶部「美食 / 酒店 / 加油站 / 休闲玩乐 / 扫街榜」分页金刚区 |
| 「我的」页 | 订单栏、车辆服务栏、达人任务、运营卡栏、猜你喜欢、资质信息/协议中心 |
| 其他 | 隐藏桌面图标（隐藏后从常驻通知回到设置页）、调试日志、一键恢复默认 |

改动写入 LSPosed RemotePreferences，强停高德后重新打开即生效（普通 App 作用域无需重启系统）。

实现要点：AJX 信息流卡片挂在列表适配器的 `onBindViewHolder` 上，在**绑定完成的同一帧内**判定并隐藏，
卡片一次都不会被绘制出来 —— 不是"渲染后再删"。

所有隐藏规则都带**页面归属判断**：只有认得出高德首页（底部悬浮标签栏在显示）才会动手，
路线页 / 搜索页 / 导航页 / 工具管理页一律不碰 —— 这是"某个规则跑到别的页面乱删东西"这一类事故的根治。

### 安装

1. 需要 root + LSPosed（libxposed API 102；实测 KernelSU + ZygiskNext + LSPosed v2.1.1 / Android 16）
2. 安装 `MapAdKiller.apk`
3. 在 LSPosed Manager 中启用本模块（`staticScope=true` 固定作用域：高德/百度/腾讯，见 `app/META-INF/xposed/scope.list`）
4. 重启目标 App 生效（无需重启手机）

### 构建（无需 Android Studio / Gradle）

纯命令行工具链：JDK + Android SDK build-tools（aapt2/d8/apksigner）。

```powershell
# Windows PowerShell
.\app\build.ps1
# 产物: dist/MapAdKiller.apk
```

依赖路径在 `build.ps1` 顶部可改（`$Sdk` / `$Bt` / `$Aj` / `$Jdk`）。Xposed API 使用仓库内 `app/stub-src/` 的 **compile-only 桩**（签名与 API 82 一致，运行时由框架提供，不打进 APK）。

### 已知边界

- 广告为服务端概率下发，"某次没广告"不能作为验证依据；请以 logcat `MapAdKiller:` 日志为准（`adb logcat -s LSPosedFramework | grep MapAdKiller`）
- 百度地图首页"爱去榜/旅游攻略"为百度自家内容推荐（非广告 SDK 下发），不在本模块范围
- 高德"探索"tab 内嵌 AJX 画布渲染的内容卡片无法在原生视图层定位，暂未处理
- 打车 tab "单单省"角标等平台促销徽标未处理
- 首页工具宫格第 3 行是高德**服务端下发的推荐位**，轮播哪几个工具由高德决定；「收藏夹」不在这一轮下发里时，
  开关打开也不会凭空多出一个格子（模块只做隐藏，不做插入）
- 目标 App 大版本更新可能导致混淆方法名漂移，hook 未命中时日志会输出 `miss` 行，欢迎提 issue 附日志

### 隐私

模块不联网、不采集任何数据；所有逻辑仅修改目标 App 进程内的广告组件行为。

### License

GPL-3.0-or-later

---

## English

A local-only LSPosed module that deterministically blocks splash ads, home banners, feed ad cards and third-party ad SDKs in the three major Chinese map apps. Every hook point was derived from static reverse-engineering (jadx / apktool / androguard) of the target APKs — see [docs/ANALYSIS.md](https://github.com/ldxm666/MapAdKiller/blob/master/docs/ANALYSIS.md).

- **Amap** (`com.autonavi.minimap`): splash (forced into the app's own `NO_SPLASH` path — no launch hang), real-time fetch, home carousel banner, background push ops-popups, search-page template splash
- **Baidu Maps** (`com.baidu.BaiduMap`): splash across all three channels (native/openapi/push), the mediation layer feeding 6 ad networks (Business/GroMore/Meishu/Octopus/Qumeng/Recommend), floating promo yellow bar, mid-banner
- **Tencent Maps** (`com.tencent.map`): GDT SDK init kill (`initWith=false` neutralizes tangram splash + fusion SDK), splash pipeline tasks, home banner binding, POI list ad cards (view layer)

Plus a generic `ViewKiller` sweep on every `Activity.onResume` matching known ad view classes and resource ids.

### UI customization (Amap home / My page / search page)

The module app ships a settings screen that toggles Amap home-page elements one by one:

| Group | Items |
|---|---|
| Bottom tab bar | 首页 / 探索 / 长按说话 / 打车 / 我的 — per-tab hide; the remaining tabs are re-spread to fill the whole floating bar |
| Home tool grid | all 10 tools individually, the extra-tools page, and a dedicated 收藏夹 (favorites) switch |
| Home feed | weather card, nearby spots, ranking posts, distance cards, curated lists, guides/content flow, Ask-AI, channel bar, home/unit chips |
| Home promo slot | the rotating "去XX / AI叫车 / 顺风车…" operator card — detected **by layout**, one switch kills them all |
| Search page | the paged 美食/酒店/加油站/休闲玩乐/扫街榜 category strip |
| My page | order row, vehicle-services row, creator tasks, promo row, guess-you-like, licence/agreements |
| Misc | hide the launcher icon (an ongoing notification brings you back), debug log, restore-all |

Every hiding rule is **page-scoped**: it only acts when the Amap home tab bar is present, so route / search /
navigation / tool-manager pages are never touched.

Requires root + LSPosed (tested on KernelSU + ZygiskNext + LSPosed v2.1.1, Android 16). Build without Android Studio: `.\app\build.ps1` (JDK + SDK build-tools only).

Baidu Maps splash no longer needs a manual back press as of v1.0.7. Ads are probabilistically server-delivered — verify via `logcat -s LSPosedFramework | grep MapAdKiller` (`HOOKED` / `KILLED` / `forced NO_SPLASH` lines), not by one ad-free launch.

Licensed under GPL-3.0-or-later.
