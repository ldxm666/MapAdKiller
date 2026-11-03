package io.github.ldxm666.tmapclean;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 「我的」页逐卡开关（积分中心 / 个性化设置 / 我的小程序 / 我的车辆 / 活动专区）。
 *
 * <h3>为什么在数据层而不是视图层做</h3>
 * 「我的」页是 Kuikly（腾讯自研 Compose 系）自绘的。每个卡片组件都由一对指令包住，实证（11.7.0，classes5.dex）：
 * <pre>
 *   PersonalCenter$body$1$3$2$3$4$3$3$3$2$1$9.invoke()Ljava/lang/Object;
 *       [2] invoke-static PersonalCenter;->access$getPointsCenterData(...)Ljava/util/Map;
 *       [6] return-object                       ← 这就是 vif 的条件值本身
 *   PersonalCenter$body$1$3$2$3$4$3$3$3$2$1$10.invoke(BindDirectivesView)
 *       [ 7] access$getPointsCenterData()
 *       [11] if-*  →  [13] return-void          ← 数据为 null 直接返回，组件根本不创建
 *       [75] PointsCenterComponentKt;->b(...)   ← 只有非 null 才创建组件
 * </pre>
 * 把数据置空，组件就不会被创建，既不需要 GONE 也不会留白。
 *
 * <h3>卡片数据不是一条链路灌进来的（真机 Diag 修正过一次）</h3>
 * <pre>
 *   PersonalCenter$created$3.invoke(useCardManager$CardDataState)V
 *       → access$setPointsCenterData / setPersonalizationData / setMiniProgramData / setActivityZoneData
 *   PersonalCenter$created$10.invoke(java.util.List)V        ← 车辆是**另一条异步链路**
 *       → access$setVehicleList
 * </pre>
 * 第一版只挂了 5 个 setter，真机 Diag 读到 {@code my_vehicle 2/1}（确实抑制了 1 次）
 * 但用户反馈车辆卡片还在 —— 说明"我的车辆"那块的可见性不只由 {@code vehicleList} 决定：
 * {@code CardDataState} 里还有一个 {@code sellCarBannerData}（卖车 Banner），
 * 它的 setter 是 {@code setSellCarBannerData}。所以这里把两者一起按 {@code my_vehicle} 控制。
 *
 * <h3>卡片模板号（服务端下发，实证自 useCardManager.parseCardListResponse 的字面量）</h3>
 * <pre>
 *   template=12 工具栏      template=41 活动专区   template=76 我的小程序
 *   template=80 订单        template=81 积分中心   template=82 个性化设置
 *   template=83 我的店铺    template=84 我的车辆
 * </pre>
 */
final class TmapMine {

    private static final String PC = "com.tencent.map.personalCenter.PersonalCenter";
    /** Kuikly created 回调：把 CardDataState 灌进页面的入口 */
    private static final String CREATED3 = "com.tencent.map.personalCenter.PersonalCenter$created$3";
    /** 车辆数据的独立异步入口 */
    private static final String CREATED10 = "com.tencent.map.personalCenter.PersonalCenter$created$10";

    private TmapMine() {}

    static void install(ClassLoader cl) {
        Class<?> pc = H.cls(cl, PC);
        if (pc == null) return;

        // ToolbarUtilsKt.a(boolean) creates the data list consumed directly by
        // ToolbarComponentAttr. The first entry is name=favorites in 11.7.0.
        Class<?> toolbar = H.cls(cl,
                "com.tencent.map.personalCenter.components.ToolbarComponent.utils.ToolbarUtilsKt");
        H.hookSig(toolbar, "a", "tmap_my_favorites", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return filterFavorites(chain.proceed());
            }
        }, boolean.class);
        Class<?> attr = H.cls(cl,
                "com.tencent.map.personalCenter.components.ToolbarComponent.ToolbarComponentAttr");
        H.hookSig(attr, "setToolItems", "tmap_my_favorites_attr",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object original = chain.getArg(0);
                        Object filtered = filterFavorites(original);
                        return filtered == original ? chain.proceed()
                                : chain.proceed(new Object[]{filtered});
                    }
                }, java.util.List.class);
        // Both PersonalCenter branches create the entire toolbar through this factory.
        Class<?> toolbarComponent = H.cls(cl,
                "com.tencent.map.personalCenter.components.ToolbarComponent.ToolbarComponentKt");
        H.hookAll(toolbarComponent, "a", "tmap_my_toolbar_row",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (Cfg.visible(Spec.K_MY_FAVORITES, true)) return chain.proceed();
                        Diag.hit("my_toolbar_row_hidden");
                        return null;
                    }
                });

        // 车辆：**真正的入口**在这里 —— 把 VehicleComponent 的创建直接挡掉。
        //
        // 实证（11.7.0 classes5.dex）：
        //   PersonalCenter$body$1$3$2$3$4$3$3$3$2$1.invoke(DivView)V   code_off=0x218db4
        //       → static VehicleComponentKt.a(ViewContainer, Function1)V
        //   VehicleComponentKt 全类**只有这一个方法**，且这个方法全 APK 只有上面那一个调用者。
        //
        // ⚠ 为什么不能只把 vehicleList 置空：VehicleComponent 是 Kuikly 组件，
        // 数据为空时它渲染的是**空状态**（"我的车辆"标题 + "添加爱车"按钮 + 服务图标行），
        // 卡片照样在 —— 真机截图证实了这一点。必须挡住组件创建。
        Class<?> vck = H.cls(cl, "com.tencent.map.personalCenter.components.VehicleComponentKt");
        if (vck != null) {
            H.hookAll(vck, "a", "tmap_my_vehicle_comp", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Diag.hit("my_vehicle_comp");
                    if (Cfg.visible(Spec.K_MY_VEHICLE, true)) return chain.proceed();
                    Diag.count("my_vehicle_comp", 1, 1);
                    if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "tmap_mine: VehicleComponent suppressed");
                    return null;
                }
            });
        }

        // 页面 created 漏斗：只做诊断（能区分"页面没打开"和"锚点不对"）
        watch(cl, CREATED3, "my_created");
        // 车辆：直接在**异步入口**吞掉，比挂在 setter 上更靠前
        // （实证：access$setVehicleList 的唯一调用者就是 PersonalCenter$created$10.invoke(List)）
        H.hookAll(H.cls(cl, CREATED10), "invoke", "tmap_my_created_vehicle",
                new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        Diag.hit("my_created_vehicle");
                        if (Cfg.visible(Spec.K_MY_VEHICLE, true)) return chain.proceed();
                        Diag.count("my_vehicle_async", 1, 1);
                        return null;
                    }
                });

        nullOut(pc, "setPointsCenterData", "access$setPointsCenterData",
                Spec.K_MY_POINTS, "my_points");
        nullOut(pc, "setPersonalizationData", "access$setPersonalizationData",
                Spec.K_MY_PERSONALIZATION, "my_personalization");
        nullOut(pc, "setMiniProgramData", "access$setMiniProgramData",
                Spec.K_MY_MINIPROGRAM, "my_miniprogram");
        nullOut(pc, "setActivityZoneData", "access$setActivityZoneData",
                Spec.K_MY_ACTIVITY_ZONE, "my_activity_zone");
        // 车辆：列表 + 卖车 Banner 一起，两块都属于「我的车辆」
        nullOut(pc, "setVehicleList", "access$setVehicleList",
                Spec.K_MY_VEHICLE, "my_vehicle");
        nullOut(pc, "setSellCarBannerData", "access$setSellCarBannerData",
                Spec.K_MY_VEHICLE, "my_vehicle_banner");
    }

    private static Object filterFavorites(Object result) {
        if (Cfg.visible(Spec.K_MY_FAVORITES, true) || !(result instanceof java.util.List))
            return result;
        java.util.List<?> items = (java.util.List<?>) result;
        java.util.ArrayList<Object> filtered = new java.util.ArrayList<Object>(items.size());
        for (Object item : items) {
            Object name = H.call(item, "getName");
            if (!"favorites".equals(name) && !"收藏".equals(H.call(item, "getTitle")))
                filtered.add(item);
        }
        Diag.count("my_favorites", items.size(), items.size() - filtered.size());
        return filtered.size() == items.size() ? result : filtered;
    }

    private static void watch(ClassLoader cl, String cls, final String tag) {
        H.hookAll(H.cls(cl, cls), "invoke", "tmap_" + tag, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Diag.hit(tag);
                return chain.proceed();
            }
        });
    }

    /**
     * 实例 setter 与 public static 合成方法一起挂。
     *
     * <p>两层的参数个数不同（合成方法多一个 {@code PersonalCenter} 前导参数），
     * 所以统一按"<b>最后一个参数是数据本体</b>"处理 —— 参数类型不用写死，
     * 腾讯随版本重排参数类型也不会失效。
     */
    private static void nullOut(Class<?> owner, String instanceName, String staticName,
                                final String key, final String tag) {
        XposedInterface.Hooker hk = new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object[] args = argsOf(chain);
                int dataAt = args.length - 1;
                if (Cfg.visible(key)) return chain.proceed();
                if (dataAt < 0 || args[dataAt] == null) return chain.proceed();
                Diag.count(tag, args.length, 1);
                if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "tmap_mine: suppress " + key);
                Object[] replaced = args.clone();
                replaced[dataAt] = null;
                return chain.proceed(replaced);
            }
        };
        H.hookAll(owner, instanceName, "tmap_" + tag, hk);
        H.hookAll(owner, staticName, "tmap_" + tag + "_acc", hk);
    }

    /** 取当前调用的参数数组（API 102 的 {@code Chain#getArgs()} 返回 List）。 */
    private static Object[] argsOf(XposedInterface.Chain chain) {
        try {
            java.util.List<Object> list = chain.getArgs();
            if (list == null) return new Object[0];
            return list.toArray(new Object[0]);
        } catch (Throwable ignored) {}
        return new Object[0];
    }
}
