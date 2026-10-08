package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.util.Log;

/**
 * 腾讯地图 com.tencent.map（11.4 / 11.5 / 11.6.0 验证；11.6.0 真机实证）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 11.6.0 包内实证（classes*.dex 类普查）
 * ══════════════════════════════════════════════════════════════════════════
 *   优量汇（GDT）仍在：com.qq.e.comm.managers.GDTADManager
 *                        initWith(Context,String)Z / isInitialized()Z
 *                        initPlugin()V / preRequestDNS()V
 *   开屏插件：com.qq.e.tg.splash.TGSplashAD（Tangram Splash）
 *   融合 SDK：com.tencent.ams.fusion.*（DownloadCardView / fusion splash）
 *   宿主开屏：com.tencent.map.ama.splash.SplashService / SplashEntity
 *             com.tencent.map.init.tasks.optional.SplashRequestTask.run()
 *             com.tencent.map.init.tasks.DecodeSplashTask.run()
 *             com.tencent.map.launch.v2.task.t2.SplashManagerInitTask.run()
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 本次修复（v1.1.0）
 * ══════════════════════════════════════════════════════════════════════════
 *  ① SplashManagerInitTask.run() 从 VOID 改回**放行**。
 *     反编译实证：该 run() 只是 `navisdk.eta.b(Runnable)` 往主线程丢一个 lambda，
 *     而 SplashManagerInitTask 的父类 AnchorTask 是**启动任务图的锚点**
 *     （有 CountDownLatch + await()，别的任务在等它 onFinish）。
 *     把 run() 整个吞掉 = 锚点任务永远不完成 = 启动任务图悬挂 →
 *     这正是"卡开屏，按返回键才进得去"的成因之一。
 *  ② SplashRequestTask.run() / DecodeSplashTask.run() 保持拦截：
 *     这两个是纯**预取/解码**任务（拉开屏素材、解码缓存），吞掉不会影响任何回调。
 *  ③ 补齐 GDT 侧：isInitialized/initPlugin/preRequestDNS 已经在挂，
 *     再加 TGSplashAD 的初始化与"能力查询"闸门。
 *  ④ 新增开屏容器视图探测（兜底）。
 */
public final class TmapHooks {

    private TmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- ① 优量汇总闸 ----
        Class<?> gdt = H.cls(cl, "com.qq.e.comm.managers.GDTADManager");
        H.hookSig(gdt, "initWith", "tmap_gdt_init", H.FALSE, Context.class, String.class);
        H.hookAll(gdt, "isInitialized", "tmap_gdt_isinit", H.FALSE);
        H.hookAll(gdt, "initPlugin", "tmap_gdt_plugin", H.VOID);
        H.hookAll(gdt, "preRequestDNS", "tmap_gdt_dns", H.VOID);
        // getPM()/getSM() 是插件管理器与设置管理器，广告插件全靠它们；
        // 但宿主自身也读它们做埋点，返回 null 会 NPE —— 只观察，不动。
        H.hookAll(gdt, "getPM", "tmap_gdt_pm", H.observe("GDTADManager.getPM()"));

        // ---- ② 优量汇开屏插件（Tangram Splash）----
        // 11.6.0 里 TGSplashAD 的公开 API 名（classes12.dex 方法表实证）：
        //   fetchAdOnly()V / preLoad()V / fetchAndShowIn(ViewGroup)V / hasPlayedToday()Z
        // 这三个前两个是**纯预取**：吞掉只影响"素材有没有提前拉到"，
        // 不产生任何宿主可见的回调依赖；fetchAndShowIn 是"拉取并显示"，
        // 吞掉后宿主的 onAdFinish/onSkip 不会来 —— 所以它只观察不吞，
        // 真正让广告不显示的是 GDTADManager.initWith/isInitialized=false。
        Class<?> tg = H.cls(cl, "com.qq.e.tg.splash.TGSplashAD");
        H.hookAll(tg, "fetchAdOnly", "tmap_tg_fetchonly", H.VOID);
        H.hookAll(tg, "preLoad", "tmap_tg_preload", H.VOID);
        H.hookAll(tg, "hasPlayedToday", "tmap_tg_played", H.TRUE);
        H.hookAll(tg, "fetchAndShowIn", "tmap_tg_fetchshow", H.observe("TGSplashAD.fetchAndShowIn()"));

        // ---- ③ 宿主开屏任务 ----
        Class<?> req = H.cls(cl, "com.tencent.map.init.tasks.optional.SplashRequestTask");
        H.hookAll(req, "run", "tmap_req_run", H.VOID);
        Class<?> dec = H.cls(cl, "com.tencent.map.init.tasks.DecodeSplashTask");
        H.hookAll(dec, "run", "tmap_decode_run", H.VOID);
        // SplashManagerInitTask 是启动锚点任务，只观察（见类注释 ①）
        Class<?> mgrInit = H.cls(cl, "com.tencent.map.launch.v2.task.t2.SplashManagerInitTask");
        H.hookAll(mgrInit, "run", "tmap_splash_mgr", H.observe("SplashManagerInitTask.run()"));

        // ---- ④ 首页 banner 数据绑定 ----
        Class<?> bannerItem = H.cls(cl, "com.tencent.map.ama.newhome.widget.HomeBannerItem");
        H.hookAll(bannerItem, "setItemData", "tmap_hbi", H.VOID);

        // ---- ⑤ 开屏容器视图探测（兜底）----
        SplashContainerHooks.installContainerProbe(cl, "com.tencent.ams.fusion.widget.splash.SplashView", "tmap");

        // ---- ⑥ 视图兜底（GONE + 移除子树 + 广告角标识别）----
        Sweeper.install(new ViewKiller("TMAP-KILL",
                "^(com\\.tencent\\.map\\.kuiklyPoi\\.pages\\.list\\.item\\.special\\.AdCardView|" +
                "com\\.tencent\\.map\\.kuiklyexplore\\.components\\.poiCard\\.special\\.AdCardView|" +
                "com\\.tencent\\.map\\.ama\\.newhome\\.widget\\.HomeBannerItem|" +
                "com\\.tencent\\.map\\.ama\\.mainpage\\.business\\.pages\\.home\\.view\\.OperationCardView|" +
                "com\\.tencent\\.map\\.route\\.components\\.operation\\.OperationBannerView|" +
                "com\\.tencent\\.map\\.ama\\.route\\.bus\\.operation\\.BusBillboardView|" +
                "com\\.tencent\\.map\\.route\\.components\\.common\\.HistoryRoutes\\.bus\\.BannerView\\.BusOperationBannerView|" +
                "com\\.tencent\\.map\\.kuiklyPoi\\.pages\\.list\\.item\\.common\\.NoticeBanner|" +
                "com\\.tencent\\.map\\.kuiklyPoi\\.pages\\.poidetail\\.components\\.EtcpBannerCardView|" +
                "com\\.tencent\\.map\\.ama\\.route\\.car\\.view\\.CarRouteSubPoiBannerView|" +
                "com\\.tencent\\.map\\.extraordinarymap\\.overlay\\.widget\\.ExBannerWidget|" +
                "com\\.tencent\\.map\\.extraordinarymap\\.overlay\\.widget\\.view\\.mutable\\.ExMutableBannerView)$",
                ":id/(view_stub_home_banner_view|banner_layout|home_ad_banner|poi_list_ad_card)$"));

        SplashProbe.installActivitySweep(new SplashProbe.SplashPhase() {
            @Override public boolean active() { return true; }
        }, "tmap");

        H.done(MainHook.PKG_TMAP);
    }
}
