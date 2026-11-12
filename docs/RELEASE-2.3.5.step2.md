## v2.3.5.step2（versionCode 23052，2026-11-13）— 高德开屏广告四层清除

- 高德开屏广告：在 2.3.5 的「阿里妈妈请求 + `y96` 缓存就绪位」两点之外，补齐同一份 DEX 里另外四条上屏路径。素材请求总闸 `Ah5.attemptRequestData`（`SplashRequester`，所有调用方共用，含子进程 VApp 与云控补量）；实时开屏 `SplashScreenServiceImpl.a()` → `Yc5.run()`（裸 `HttpURLConnection` POST `ws/aos/alimama/splash_screen_rt`，不经 AOS，旧 hook 结构上看不到）；渲染器 `ImageSplashView` / `VideoSplashView.getSplashView` 返回 null（`v76.run()` 走宿主自己的 `e(ERROR)` 收尾，不会停在开屏页）；`files/splash/` 中已落地的 `i_*` / `v_*` 素材每进程后台清一次，只删素材、不动 `bg_silent/cloud_config` 与目录结构。
- 补齐 `AlimamaRequestHolder.sendSplashScreen` 的 2 参重载（2.3.5 只挂了 3 参）。
- 开关语义不变：全部受 `alimama_splash_off` 控制（默认开），显式关闭即整体放行；品牌开屏页、`canShowSplash`、`SplashFrequencyController`、首页与 POI 一律不碰。
- 根因与证据：高德 17.02.0.2010 的 8 个 DEX（73,638 类）中**不存在任何第三方广告 SDK**（pangle / kwad / gdt / mobads / sigmob / mintegral 等全部 0 命中），广告是高德自有阿里妈妈通道 + 云控下发素材与频次，本地原生 View 渲染。真机复现：只关请求拦截时，高德仍会现下一张 506,821 B 的开屏图落地并进入就绪流程 ⇒ 仅拦请求不足以“抹除”。
- 实测（2210132C / Android 16 / 内置包）：冷启动 2 轮四层 hook 全部命中（`splash_kill_blocked target=SplashRequester.attemptRequestData(I)`、`...SplashScreenServiceImpl.fetchRealTime()`），`splash_cache_purged files=0`，`files/splash` 素材计数归零；`TotalTime` 1005 / 1046 ms（2.3.5 基线 1533–1643 ms），无白屏、无 ANR，首页与底栏正常。
- 说明：本次只对高德国内 17.02.0.2010 做了锚点校验与真机验证；Play 17.02.2.2014 的同名混淆类（`wm6` 对应 `y96`）尚未核对 `ah5` / `yc5`，使用前需单独跑一次 `tools/check_anchors.py`。百度、腾讯变体仅同步版本号，功能未改动。
- 详细分析：[SPLASH-AD-17.02-ANALYSIS.md](SPLASH-AD-17.02-ANALYSIS.md)。
