# 高德 17.02.0.2010 开屏广告：定性、证据与深度清除

记录日期：2026-11-13。对象：`com.autonavi.minimap` 17.02.0.2010（versionCode 170200，CN）。
DEX 来源：`assets/lspatch/origin.apk`（177,749,534 B，从已安装内置包抽出，
`sha256=768D62EE138771C0F1D1A08C68151A0E41F66BDA2ADA1C559E07EBB9EB65857C`），
8 个 dex 共 91.6 MB、73,638 类。smali 与 SQL 均为该原包，不是重打包产物。

---

## 1. 先回答两个问题

**有没有第三方广告 SDK？没有。**
`kwad` / `ksad` / `pangle` / `bytedance` / `gromore` / `sigmob` / `mintegral` /
`mobads` / `qq.e` / `tanx` / `tradplus` / `topon` / `anythink` / `windmill` /
`unityads` / `applovin` / `ironsource` / `vungle` / `huawei.hms.ads` /
`google.android.gms.ads` 在 8 个 dex 里**全部 0 命中**。
`bytedance` 只命中一条邮箱字面量 `salt_dyposdk@bytedance.com`；`gdt` 全部是
`/autonavi/gdtaojin/`（高德淘金）。这两个是诱饵，挂了只会误伤。
存在的只有 `com.alibaba.*` / `com.alipay.*` / `com.taobao.*` 的**容器**（Rax / Ariver /
NebulaX，H5 运行时）与 `com.amap.bundle.adiu`（设备广告 ID 上报）。

**是不是云下发？是，但"云"只下发**素材与开关**，渲染是本地原生。**
开屏内容由云控配置（`basemap.splashscreen` / `splashscreen` 两个 namespace）决定要不要开、
开多久、什么频次；素材由阿里妈妈接口下发后落地成文件；**上屏用高德自己的
`ImageSplashView` / `VideoSplashView`，不是 WebView，也不是 H5。**

---

## 2. 完整链路（每一步都有 DEX 证据）

```
冷启动
  └─ SplashScreenServiceImpl.tryShowSplashView(int scene, String src)     ← 外层入口
       └─ com.autonavi.minimap.g.o(int, String)                           ← 广告控制器
            ├─ y96.b().g(scene, src) → y96$a{a:int,b:String,c:String}     ← 就绪位（a==1 ⇒ NO_SPLASH）
            ├─ SplashFrequencyController.a/b(scene, true)                 ← 云控频次
            ├─ BootBizDataPreloader.canShowSplash()                       ← 品牌页总闸（默认不碰）
            └─ [时限 > 0] → g$e(RealTimeFetchCallback) → xc5.run()
                 └─ ISplashScreenService.fetchRealTime()
                      └─ SplashScreenServiceImpl.a()  (public static)
                           └─ yc5.run()   ← 裸 HttpURLConnection POST
                                https://amap-aos-info-nogw.amap.com/ws/aos/alimama/splash_screen_rt?ent=2
            └─ g.i(scene, elapsed) → yg2.i(scene) 取 k66 → v76.run()
                 ├─ g.d()   建 SplashContainerView
                 ├─ g.m(ctx)
                 ├─ k66.A = "static_image" → ImageSplashView   /  "video" → VideoSplashView
                 ├─ ISplashUI.getSplashView(ctx, k66, listener) → container.addView(...)
                 └─ g.startAd() → ISplashUI.play()
```

素材来源有**两条**，都能把广告送上屏：

| 通道 | 入口 | 端点 | 2.3.5 是否拦 |
| --- | --- | --- | --- |
| 预下载（缓存） | `AlimamaRequestHolder.sendSplashScreen`（3 参 + 2 参） | `ws/valueadded/alimama/splash_screen` | 拦了（请求） |
| 缓存就绪 | `y96.g` 在 `g.o` 作用域内 | — | 拦了（就绪位翻 NO_SPLASH） |
| **素材请求源头** | `ah5.attemptRequestData(int)` / `(int,boolean)` | 由它内部决定 | **未拦** |
| **实时开屏** | `SplashScreenServiceImpl.a()` → `yc5.run()` | `ws/aos/alimama/splash_screen_rt?ent=2`（裸 HttpURLConnection，**不经 AOS**） | **未拦** |

`ah5` 的字符串自证它是什么：`SplashRequester`、`ADSwitchValue`、`OAIDSwitchValue`、
`last_shown_ad`、`last_shown_ts`、`min_req_interval_second`、`max_req_count`、
`不发送请求：请求次数已达上限`。调用者四个：
`SplashChildProcessVApp.a(String)`、`com.autonavi.minimap.g.c(...)`、`rv5.run()`、`mc1.run()`。
⇒ **这是一个比 `sendSplashScreen` 更靠上的总入口**：拦它，任何调用方都发不出开屏素材请求。

---

## 3. 为什么 2.3.5 发布后"又出现了"

2.3.5 的拦截是正确的，但它是**两个点**，不是一条链：

1. `sendSplashScreen` 只覆盖"预下载"这一条。实时开屏走 `yc5.run()` 的裸连接，
   结构上就不经过 AOS，也不经过 `AlimamaRequestHolder`。
2. `y96` 就绪位只在 `g.o` 作用域内生效，且只改**当次**结果对象；
   预加载（`BootBizDataPreloaderImpl.prepare`）、schema 路由（`SplashScreenServiceImpl.interceptSchema`）、
   页面创建（`xv3.onPageCreated`）同样调 `y96.g`，那些调用被刻意放行。
3. `files/splash/` 里的素材从来没有被清过。真机实测目录里长期躺着 5 张图 + 5 段视频
   （64 KB–5.5 MB）。**只要云端把某次开屏的就绪判定翻成"允许"，盘上的旧素材立刻能上屏。**

真机复现（2026-11-13 00:46，本机 2210132C / Android 16 / 内置 2.3.5）：
把 `alimama_splash_off` 置 false 后冷启动，日志里 `alimama_blocked
method=sendSplashScreen` 消失，高德**确实新下了一张 506,821 B 的开屏图**
（`files/splash/i_1e925026203c62ded055b2ad6f9dbf48`，此前不存在），
但 `event=alimama_cached_blocked anchor=y96 finish=NO_SPLASH` 在 0.5 s 就把流程收尾，
所以视图没挂上。**这条证据同时说明：请求通道是活的，素材是会落地的，
拦住它的只有就绪位一个点。** 那个点一旦被云控/另一条路径绕过，广告就回来。

---

## 4. 本次改动：`AmapSplashKill`

新文件 `app/src/io/github/ldxm666/mapadkiller/AmapSplashKill.kt`，
由 `AmapAlimama.install` 在同一开关（`alimama_splash_off`，默认开）下安装。
四个层面，全部只做"不发请求 / 不给视图"，不改宿主状态机：

| # | 目标 | 处理 | 为什么安全 |
| --- | --- | --- | --- |
| 1 | `ah5.attemptRequestData(I)V`、`(IZ)V` | 不 proceed | 方法体只负责发素材请求，无返回值、无锁；不请求 ⇒ 素材不落地 |
| 2 | `SplashScreenServiceImpl.a()V`（static）、`fetchRealTime()V` | 不 proceed | 实时开屏的唯一构造点；宿主对"取不到"本来就有失败分支 |
| 3 | `yc5.run()V` | 不 proceed | 兜底：即使有人直接投递该 Runnable，URL 也发不出去 |
| 4 | `ImageSplashView.getSplashView(...)`、`VideoSplashView.getSplashView(...)` | 返回 null | `v76.run()` 见 null 走 `g.l(k66)` + `e(SplashFinishReason.ERROR)` 正常收尾，**不会停在开屏页** |
| 5 | `files/splash/i_*`、`v_*` | 每进程一次，后台线程删除 | 只删广告素材；`bg_silent/cloud_config`、`time_mills_filter` 与目录结构原样保留 |

另外补了 2.3.5 的一个缺口：`sendSplashScreen` 的 **2 参重载**此前没挂，现在挂了
（签名对不上时 `hookSig` 只记 MISS，不会误挂）。

刻意**不碰**：品牌开屏页（主题 `windowBackground` + `BaseSplashActivity`）、
`canShowSplash`（2.3.0 实测钉死它 = 首屏无承载 = 白屏 30 秒）、
`SplashFrequencyController`（频控拦了会连品牌页一起没）、首页 / POI / 埋点。

---

## 5. 验证（真机实测，2026-11-13 00:54，2210132C / Android 16 / 内置包）

```
python tools/check_anchors.py --Apk .agent_work/amap1702-host/origin.apk \
    --Anchors tools/anchors-amap-splash-cn-17.02.0.2010.json
→ RESULT: ALL ANCHORS PRESENT   （新增 5 组锚点全部 0 missing；y96/ah5/yc5 均命中）
```

`app/build.ps1` 全量编译通过（178 个 Kotlin 文件，签名证书 SHA-256 `cbecdaeb…54ed`）。
`output/MapClean-lsp-v2.3.5.apk` 的 classes.dex 里 `AmapSplashKill` /
`amap_splash_requester_int` / `amap_splash_realtime` / `amap_splash_view_image` 全部存在。

内置版：`output/fix-236-cn/Amap-2.3.5-splashkill-cn-embedded.apk`
（333,238,033 B，`sha256 184eb84e1a3a666e0afa89a0f7144641a04d007397e0aef7a339fb5f4708f1fc`），
覆盖安装成功（同签名，未卸载、未清数据）。

冷启动两轮日志（`am force-stop` + `am start -W`）：

```
event=splash_kill_installed requester+realtime+renderer+purge
HIT amap_splash_realtime    → event=splash_kill_blocked target=SplashScreenServiceImpl.fetchRealTime()
HIT amap_alimama_cached_splash（y96 就绪位仍在岗）
event=splash_cache_purged files=0 bytes=0
HIT amap_alimama_splash_scope / amap_alimama_h5log
HIT amap_splash_requester_int → event=splash_kill_blocked target=SplashRequester.attemptRequestData(I)
```

`TotalTime` 1005 ms / 1046 ms（2.3.5 基线为 1533–1643 ms，**没有变慢，也没有白屏**）。
`files/splash/` 下 `i_*` / `v_*` **计数为 0**，只剩 4 个 0 字节锁文件与 `bg_silent/`。
首页、搜索框、工具行、底栏、天气卡全部正常（截图 + UI 树核对）。

设备状态已还原：`mapclean_cfg.xml` 中不存在 `alimama_splash_off` 键
（= 走默认值"拦截开"），复现实验期间写入的 false 已按字节还原。

仍需补的回归：
1. 显式把 `alimama_splash_off` 关掉，确认广告能恢复（开关双向有效）；
2. Play 17.02.2.2014 跑一次 `check_anchors` 并冷启动；
3. 连续 5 轮冷启动 + 热启动（从最近任务返回）各一次，确认无 ANR。

---

## 6. 边界

- Play 17.02.2.2014 的同名混淆类（`wm6` 对应 `y96`）**尚未核对** `ah5`/`yc5`/`SplashScreenServiceImpl`
  是否同名。当前只对 CN 17.02.0.2010 做了锚点验证；Play 需要单独跑一次 `check_anchors` 再上。
- 外部 LSPosed 注入官方签名地图这条路径仍未验收（交接 §7）。若用户看到广告的实例是
  "官方高德 + 外部模块"，先确认 `event=module_loaded ... framework=LSPosed` 真的出现，
  否则问题不在拦截逻辑，而在注入根本没发生。
- 云控可以在不改 APK 的前提下换 host（`amap-aos-info-nogw` / `pre-amap-…` / `…amap.test`）
  或换频次策略。所以钩的是**类与方法**，不是 URL 字符串。
