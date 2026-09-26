# 逆向证据表 / Hook Point Evidence

目标 APK 均来自本机（授权设备），版本：

| App | 包名 | 版本 | 主 dex 规模 |
|---|---|---|---|
| 高德地图 | com.autonavi.minimap | 16.23.0.2008 | 8 dex / 73,965 类 |
| 百度地图 | com.baidu.BaiduMap | 21.18.0 | 18 dex / 138,079 类 |
| 腾讯地图 | com.tencent.map | 11.4.0 | 27 dex / 184,699 类 |

分析工具链：androguard 4.1.4（类普查）→ apktool 3（smali 全量）→ jadx 1.5.5（关键类反编译）→ Frida 17.15（辅助，百度因 baiduprotect 反注入放弃动态）。

## com.autonavi.minimap（无第三方广告 SDK，全自研）

| Hook 点 | 证据 |
|---|---|
| `za6.g(int,String):za6$a` → 强制 `za6$a.a=1` | `SplashScreenServiceImpl.tryShowSplashView` 转发至 `com.autonavi.minimap.g.o()`；`o()` 内 `if (za6.b().g(i,str).a == 1) { e(NO_SPLASH); return; }` 为应用自家"无广告"收尾分支（jadx g.java L645-651） |
| `SplashScreenServiceImpl.fetchRealTime()` | 开屏实时广告拉取入口（smali 方法表） |
| `BannerManager.b(String,ZZ,OnLoadBannerListener)` / `.a(I,String)` | 首页轮播 banner 加载/取数（classes5.dex） |
| `BannerParser.a(JSONObject)` → null | banner 网络数据解析（`net.BannerResult`） |
| `BackgroundMsgManager.a(listener)` | 后台推送运营弹窗（`bundle.msgbox.push`，`IBackgroundMsgFetchListener`） |
| `NativesModuleSplashScreen.getLinkageMsg/getCurrentLinkageMsg` → null | 开屏→AJX 首页联动广告数据桥（jadx 方法表） |
| `SplashModel.getData/getTemplate/getXmlUrl/getCssUrl` → null | 搜索页模板开屏（`search.inter.splash`） |
| ViewKiller: `DBanner` + `:id/.*banner.*` | `com.autonavi.bundle.banner.view.DBanner`（37 类 banner 包） |

## com.baidu.BaiduMap（聚合 6+ 广告网络）

dex 内确认存在的第三方 ADN：`com.kwad`(快手)、`com.bytedance.sdk.openadsdk`(穿山甲)、`com.qq.e`(广点通)、`com.ubix.ssp`、`com.meishu.sdk`、`com.sigmob`、`com.beizi`、`com.qumeng.advlib`、`com.anythink`(TopOn 聚合)、`com.octopus.ad`。

| Hook 点 | 证据 |
|---|---|
| `SplashAdManager.F()/z()` → false | **关键**：`WelcomeScreen.t()` 与 `HomeSplashPresenter` 均以 `F() && z()` 为开屏等待闸门（jadx WelcomeScreen L609 + HomeSplashPresenter.smali L2112-2127）；false 走自家无广告路径，秒进主页 |
| `SplashAdManager.G/H/n/m(...)` swallow | `G→SplashAdProvider.k(ctx,c4.a)` 为唯一装载点（jadx L411-428）；`n`=OPENAPI 渠道展示（参数含 openApiUrl）；`m`=预加载监听 |
| `SplashAdProvider.k/m` swallow | 同上，双保险 |
| `IAdLoader.I(Context,c4.a)` + 6 子类 | 各 ADN Loader 统一入口（`commonadprovider.{business,gromore,ms,octopus,qumeng,recommend}`） |
| `BMAd{Splash,Native,Reward}Provider.c/d/x` + `IBMapAdLoader.c/d/x` | `com.baidu.baidumaps.ad` 开放封装（199 类） |
| `HomeMidBannerPresenter.show(s)/onCreateView` | 首页中部横幅（`aihome.panel.presenter`，1530+ 类） |
| `MidBannerRepo.c()/onEvent(b)` | 横幅数据仓库（`base.yellowbanner`，rx.Observable） |
| `YellowBannerPresenter.tryShowYellowBanner/showYellowBanner/showSwitcherBanner` | 悬浮运营黄条（"做任务领现金"类，实测 t8 截图消失） |
| ViewKiller: `integratedads.view.BannerAdView` / `gromore.view.BannerUIView` | 自绘广告视图类（classes 普查） |

## com.tencent.map（广点通生态）

| Hook 点 | 证据 |
|---|---|
| `GDTADManager.initWith(Context,String)Z` → false；`initPlugin/preRequestDNS` swallow；`isInitialized` → false | 广点通唯一初始化入口（smali 方法表 classes12.dex）；GDT 插件（tangramsplash/TGSplashAD/融合 SDK ams.dsdk）全部依赖 initWith 成功 |
| `SplashRequestTask.run` / `SplashManagerInitTask.run` / `DecodeSplashTask.run` | 开屏流水线（`init.tasks.optional` / `launch.v2.task.t2` / `init.tasks`，smali 方法表） |
| `HomeBannerItem.setItemData(Banner,fxv)` swallow | 首页 banner 数据绑定（`ama.newhome.widget`） |
| ViewKiller: `kuiklyPoi/kuiklyexplore …special.AdCardView`、`OperationCardView`、`OperationBannerView`、`ActivityBannerView`、`:id/view_stub_home_banner_view`、`:id/banner_layout` | POI 列表广告卡（Compose 视图）、路线运营横幅；两个资源 id 来自 uiautomator 实测 dump |

## 实测日志（v1.0.0 / v6 构建）

```
MapAdKiller: AMAP hooks done ok=14 miss=0
MapAdKiller: AMAP splash forced NO_SPLASH
MapAdKiller: BMAP hooks done ok=34 miss=0
MapAdKiller: TMAP hooks done ok=8 miss=0
```

## 方法名漂移风险

百度/高德的单字母混淆方法（`za6.g`、`SplashAdManager.G` 等）在 App 大版本更新后可能重排。
本模块所有 hook 均带 miss 计数与日志，未命中时不影响 App 功能（fail-open）。
