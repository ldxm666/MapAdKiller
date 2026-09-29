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

## 实测日志（v1.1.0 / v6 构建）

```
MapAdKiller: AMAP hooks done ok=14 miss=0
MapAdKiller: AMAP splash forced NO_SPLASH
MapAdKiller: BMAP hooks done ok=34 miss=0
MapAdKiller: TMAP hooks done ok=8 miss=0
```

## 方法名漂移风险

百度/高德的单字母混淆方法（`za6.g`、`SplashAdManager.G` 等）在 App 大版本更新后可能重排。
本模块所有 hook 均带 miss 计数与日志，未命中时不影响 App 功能（fail-open）。

---

# 补充证据表 v2（2026-09-29，对应模块 v1.1.0 修订）

本次全部结论来自**自建 Python DEX 解析器**（jadx CLI 在 18 dex / 167K 类上单类反编译就超过 10 分钟，不可用）+ LSPosed 真机日志 + adb 逐帧截图。

目标版本：高德 **17.00.0.2005** · 百度 **22.0.0 (vc1650)** · 腾讯 **11.6.0**。

## com.baidu.BaiduMap 22.0.0

| Hook 点 | 证据 |
|---|---|
| SplashAdManager.F()Z -> **false（断源）** | 实现 com.baidu.baidumaps.splash.c.q()；调用者仅 WelcomeScreen.t() / HomeSplashPresenter.n()。t() 反编译 offset 37-43 为 if (!F()) return-void —— 应用自家无广告分支，广告请求不发出 |
| SplashAdManager.z()Z -> false | 读静态布尔 SplashAdManager.c；调用者同上 |
| SplashAdManager.w()Z -> false | 实现 splash.c.p()；调用者 WelcomeScreen.t() / operation.j（埋点） |
| SplashAdManager.y()Z -> false | AtomicBoolean.get()；调用者同上 |
| WelcomeScreen.t() / HomeSplashPresenter.n() | 开屏窗口期标记（只置位，不改行为） |
| splash.view.SplashViewContainer.addView(View,I,LayoutParams) | 容器只声明这一个 addView 重载；不覆写 onAttachedToWindow |
| commonadprovider.api.IAdLoader.I(loader,I,I,Object) | **ADN 回调完成方法**（onAdClose / onTimeOver / onAdDismissed / onAdSkip）——只观察，吞掉必卡开屏 |
| BMAd{Splash,Native,Reward}Provider.c/d + IBMapAdLoader.c/d/x | 开放封装；x 在 22.0.0 已删除（日志 miss） |
| HomeSplashPresenter.n(String,Z,String) | 内部 new SplashViewContainer(ctx) 存字段 E，随后 SplashAdManager.G/n |

**闪退红线（本次实测）**：安装期（onPackageReady）反射调用 SplashAdManager.w()
-> splash.c.<clinit> -> SplashPreference -> Preferences.build()
-> JNIInitializer.getCachedContext() 为 null -> NPE -> ExceptionInInitializerError
-> BaiduMapApplication.onCreate -> SplashAdManager.B() 抛 NoClassDefFoundError -> 闪退。

## com.autonavi.minimap 17.00.0.2005

| Hook 点 | 证据 |
|---|---|
| impl.SplashScreenServiceImpl.tryShowSplashView(I,String) | 只是转发壳：-> com.autonavi.minimap.g.o(int,String) |
| bundle.amaphome.impl.BootBizDataPreloaderImpl.canShowSplash() -> **false** | g.o() offset 274-299 是开屏决策总入口；返回 false 走 g.e(SplashFinishReason.NO_SPLASH)。全量调用者仅 3 处：g.o() / lite.a.loadPage() / eo6.doBizLogic()。**接口 aca.BootBizDataPreloader 上同名方法是 abstract，挂不上** |
| minimap.g.e(SplashFinishReason) | 开屏收尾方法（内部 g.f 幂等闸门），采样观察 |
| SplashState 枚举 | UNKNOWN / INITING / SHOWING / LANDING / FINISHED（bundle.splashscreen.api） |
| impl.SplashScreenServiceImpl.{fetchRealTime,isSplashShowing,isContinueLaunchMaskViewShowing,showSplashMaskView} | 17.00 仍在，签名与 16.23 一致 |
| component.SplashContainerView | 只声明 dispatchDraw / doExistAnim / onInterceptTouchEvent / recordUserTrack / reportUserTrackIfNeeded —— 不覆写 addView，容器方法 hook 会 hooks=0，必须用 decor 扫描 |
| u96.g / za6.g | u96 仍可挂（日志 HIT）；za6 签名已变（日志 miss za6.g (no such sig)） |

## com.tencent.map 11.6.0

| Hook 点 | 证据 |
|---|---|
| GDTADManager.initWith(Context,String)Z -> false / isInitialized() -> false / initPlugin() / preRequestDNS() | 优量汇初始化链路，11.6.0 方法表与 11.4 一致 |
| com.qq.e.tg.splash.TGSplashAD | 11.6.0 真实 API：fetchAdOnly() / preLoad() / hasPlayedToday() / fetchAndShowIn(ViewGroup) |
| init.tasks.optional.SplashRequestTask.run() / init.tasks.DecodeSplashTask.run() | 纯预取/解码，可拦 |
| launch.v2.task.t2.SplashManagerInitTask.run() -> **放行** | 父类 launch.starter.AnchorTask 有 ensureCountDownLatch/await/countdown/onFinish —— 启动任务图锚点，吞掉 = 启动图悬挂 = 卡开屏 |

## 通用：SdkAutoBlock 的误伤边界

| 类别 | 允许 | 禁止 |
|---|---|---|
| 只读能力查询 | isInitialized / isInit / isSdkReady / canLoadAd / canShowAd / isReady / isLoadSuccess / hasInit -> false | - |
| 纯初始化入口 | initSDK / initializeSdk / startWithAppId / startWithAppID -> 空转 | - |
| 加载/展示 | - | load / loadAd / loadAds / show / fetchAd —— 吞掉必卡开屏（回调不来） |
| 半初始化 | - | init / initialize —— 吞掉会让宿主拿到 null/NPE |
| Context 替换 | 仅 init* / setup* / *WithAppId **且签名含 Context 入参** | contains("start") 这类宽匹配（命中 startActivity/startService -> 闪退/ANR） |
