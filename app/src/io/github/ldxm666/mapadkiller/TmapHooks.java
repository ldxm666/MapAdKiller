package io.github.ldxm666.mapadkiller;

import android.content.Context;

/**
 * 腾讯地图 com.tencent.map（11.4 与 11.5.0 双版本验证）
 *  - GDT 广点通总闸: com.qq.e.comm.managers.GDTADManager
 *      initWith(Context,String)Z→false / isInitialized→false / initPlugin、preRequestDNS 吞掉
 *      （GDT 插件、tangramsplash、融合 SDK ams.dsdk 全部依赖 initWith 成功）
 *  - 开屏流水线: init.tasks.optional.SplashRequestTask.run /
 *      launch.v2.task.t2.SplashManagerInitTask.run / init.tasks.DecodeSplashTask.run
 *  - 首页 Banner 绑定: ama.newhome.widget.HomeBannerItem.setItemData
 *  - 视图兜底: POI 列表广告卡(AdCardView)、NoticeBanner、EtcpBannerCard、
 *      路线运营横幅(OperationBannerView/BusOperationBannerView)、
 *      地图浮层推广气泡(ExBannerWidget/ExMutableBannerView)、首页运营卡
 */
public final class TmapHooks {

    private TmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- GDT 总闸 ----
        Class<?> gdt = H.cls(cl, "com.qq.e.comm.managers.GDTADManager");
        H.hookSig(gdt, "initWith", "tmap_gdt_init", H.FALSE, Context.class, String.class);
        H.hookAll(gdt, "isInitialized", "tmap_gdt_isinit", H.FALSE);
        H.hookAll(gdt, "initPlugin", "tmap_gdt_plugin", H.VOID);
        H.hookAll(gdt, "preRequestDNS", "tmap_gdt_dns", H.VOID);

        // ---- 开屏流水线任务 ----
        String[] tasks = {
            "com.tencent.map.init.tasks.optional.SplashRequestTask",
            "com.tencent.map.launch.v2.task.t2.SplashManagerInitTask",
            "com.tencent.map.init.tasks.DecodeSplashTask",
        };
        for (String t : tasks) {
            Class<?> c = H.cls(cl, t);
            if (c != null) H.hookAll(c, "run", "tmap_task_" + t.substring(t.lastIndexOf('.') + 1), H.VOID);
        }

        // ---- 首页 banner 数据绑定 ----
        Class<?> bannerItem = H.cls(cl, "com.tencent.map.ama.newhome.widget.HomeBannerItem");
        H.hookAll(bannerItem, "setItemData", "tmap_hbi", H.VOID);

        // ---- 视图兜底（GONE + 移除子树 + 广告角标识别）----
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

        H.done(MainHook.PKG_TMAP);
    }
}
