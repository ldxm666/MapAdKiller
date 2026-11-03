package io.github.ldxm666.tmapclean;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 地点详情「大家都在看」模块开关。
 *
 * <h3>锚点（实证：真机 11.7.0 base.apk）</h3>
 * <pre>
 *   classes23.dex  com.tencent.map.kuiklyPoi.pages.poidetail.components.LookAndLook.RecommendationViewAttr
 *       public getFormattedTitle()Ljava/lang/String;   ← 字面量 "大家都在看" / "看过「…」的人还看过"
 *       public updateFromStillWatchingData(Lcom/tencent/map/kuiklyPoi/pages/poidetail/data/StillWatchingData;)V
 *
 *   classes23.dex  com.tencent.map.kuiklyPoi.pages.poidetail.components.render.RenderPeopleStillWatchingKt
 *       public static a(Lcom/tencent/kuikly/core/base/ViewContainer;
 *                       Lcom/tencent/map/kuiklypoi/pages/poidetail/PoiDetailScene;F)V   ← 唯一渲染入口
 * </pre>
 *
 * <p>渲染入口只有一个调用点：{@code RenderPeopleStillWatchingKt.a} 是地点详情页把该模块挂进
 * 视图树的唯一通道。吞掉它，整个模块（标题 + 宫格 + 查看更多）都不会进入组件树，
 * 不像 GONE 那样留下一条空白。
 *
 * <p>同时挂 {@code RecommendationViewAttr.updateFromStillWatchingData}：即使别的版本换了渲染入口，
 * 数据层被掐断后 {@code getItems()} 为空，宫格也渲染不出内容（双保险）。
 *
 * <p>两个类名都是腾讯自己的命名（非混淆），跨小版本稳定；方法用按名挂钩而不是精确签名，
 * 因为参数里的 {@code PoiDetailScene} 属于会被重排的业务类型。
 */
final class TmapLookAndLook {

    /**
     * ⚠ 2026-10-14 修正：原来写成 {@code ...components.LookAndLook.RecommendationViewAttr}
     * （用点号），真机 dex 里 {@code LookAndLook} 是<b>真实目录</b>，描述符是
     * {@code components/LookAndLook/RecommendationViewAttr}。
     * 类名写错 → {@code H.cls()} 返回 null → {@code hookAll} 静默返回 0，
     * 连 miss 都不加 —— 所以这个开关从装上那天起就根本没有 hook 点。
     */
    private static final String RENDER =
            "com.tencent.map.kuiklyPoi.pages.poidetail.components.render.RenderPeopleStillWatchingKt";
    /**
     * ⚠ 2.3.0 修正：这里曾经写成
     * {@code ...components.LookAndLook/RecommendationViewAttr}（用 {@code /} 当分隔符）。
     * {@code ClassLoader.loadClass()} 只认**点分**二进制名，{@code /} 是描述符里的分隔符 ——
     * 于是 {@link H#cls} 恒返回 null，{@code hookAll} 静默返回 0，
     * 腾讯「大家都在看」的数据层整条死掉（开关拨了毫无反应，日志里连 miss 都没有）。
     *
     * <p>实测 11.7.0 {@code classes23.dex}：
     * {@code Lcom/tencent/map/kuiklyPoi/pages/poidetail/components/LookAndLook/RecommendationViewAttr;}
     * —— {@code LookAndLook} 是<b>包名</b>，{@code RecommendationViewAttr} 是类名。
     * 点分形式才是正确的：{@code ...components.LookAndLook.RecommendationViewAttr}。
     * 被 hook 的方法同 dex 实查存在：
     * {@code updateFromStillWatchingData(Lcom/tencent/map/kuiklyPoi/pages/poidetail/data/StillWatchingData;)V}。
     */
    private static final String ATTR =
            "com.tencent.map.kuiklyPoi.pages.poidetail.components.LookAndLook.RecommendationViewAttr";

    /**
     * 开关变化后的即时生效处理。
     *
     * <p>本模块的抑制发生在<b>渲染/数据入口被调用的那一刻</b>（{@code RenderPeopleStillWatchingKt.a}
     * 与 {@code updateFromStillWatchingData}），没有可持有的视图对象，所以"刷新"能做的
     * 只是让下一次进入地点详情页时读到新配置：清掉 {@link Cfg} 的 2 秒 TTL 快照，
     * 并把本次生效状态记进诊断。
     *
     * <p>已经打开的详情页不会就地消失 —— 腾讯的 Kuikly 组件只在数据到达时重建，
     * 强行去翻视图树正是这个模块卡顿的来源。重新进入详情页即生效。
     */
    static void refresh() {
        Cfg.invalidate();
        Diag.note("look_switch", Cfg.visible(Spec.K_LOOK_AND_LOOK) ? "on" : "off");
    }

    private TmapLookAndLook() {}

    static void install(ClassLoader cl) {
        Class<?> render = H.cls(cl, RENDER);
        H.hookAll(render, "a", "tmap_look_render", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (Cfg.visible(Spec.K_LOOK_AND_LOOK)) {
                    Diag.hit("look_show");
                    return chain.proceed();
                }
                Diag.count("look_render", 1, 1);
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "tmap_look: render suppressed");
                return null;
            }
        });

        Class<?> attr = H.cls(cl, ATTR);
        H.hookAll(attr, "updateFromStillWatchingData", "tmap_look_data",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        if (Cfg.visible(Spec.K_LOOK_AND_LOOK)) return chain.proceed();
                        Diag.hit("look_data");
                        return null;
                    }
                });
    }
}
