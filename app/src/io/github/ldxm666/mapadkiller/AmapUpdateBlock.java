package io.github.ldxm666.mapadkiller;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 强制去除版本更新提示（默认生效，不提供开关）。
 *
 * <h3>只掐"提示升级"这一条链路，网络层一条不碰</h3>
 * 高德的更新检查是 {@code ws/app/conf/app_update} 这条自有协议，请求由
 * {@code AppUpdateRequest}（classes6）发出，回调是
 * {@code AppUpgradeController$AppUpdateCallBack.b(Object)}（classes4）。
 * 回调里做的事很多 —— 写 {@code gray_res}、记 {@code app_uct} 次数、手动检查时弹
 * "已是最新版本" Toast —— 这些都不该动。真正决定"弹不弹升级框"的只有两个方法：
 *
 * <pre>
 *   AppUpgradeController.w(Lcom/amap/bundle/appupgrade/e;ZZ)V   唯一调用方 = AppUpdateCallBack.b
 *       └─ 不满足展示条件就 return；否则 new AppUpgradeController$a(...) + UiExecutor.post
 *          → $a.run 里 AlertView$a = UpgradeHelper.c(...) → IPageContext.showViewLayer + 动画
 *
 *   AppUpgradeController.v(Z)V                                  另一个 AlertView 构造点
 * </pre>
 *
 * 所以把这两个方法变成空实现，就等于"请求照发、回调照跑、书签照写，只是永远不弹框"。
 * 这是能想到的最保守的落点：不动 AOS 网络层（不会误伤任何正常请求），不动回调里的状态机
 * （不会把"手动检查"的 ProgressDlg 卡住），也不碰 {@code ws/app/conf/app_update} 本身。
 *
 * <h3>为什么不用 hookAll 之外的写法</h3>
 * 精确签名要用 {@code Lcom/amap/bundle/appupgrade/e;} 这个混淆类做参数类型。类名跨版本
 * 会重排，一旦解析失败，精确签名这条就整条落空。所以先用精确签名，失败再退回按名 + 运行时
 * 形状校验（返回 void、形参个数对得上）—— 校验放在拦截器里，就算将来多了同名的别的重载，
 * 也只会放行而不是把它 return 掉。
 *
 * <h3>找不到就安静跳过</h3>
 * 类不在、方法不在、框架没给 module：全部只留一行 WARN，不抛、不重试、不猜替代类。
 */
final class AmapUpdateBlock {

    private static final String CLS_CONTROLLER = "com.amap.bundle.appupgrade.AppUpgradeController";

    private AmapUpdateBlock() {}

    static void install(ClassLoader cl) {
        Class<?> controller = H.cls(cl, CLS_CONTROLLER);
        if (controller == null) {
            H.log(Log.WARN, MainHook.TAG,
                    "event=anchor_miss feature=update_block name=" + CLS_CONTROLLER);
            return;
        }
        // Loaded only to spell the exact parameter type. null is fine: the by-name fallback
        // below does not need it.
        Class<?> info = H.cls(cl, "com.amap.bundle.appupgrade.e");

        if (info != null) {
            H.hookSig(controller, "w", "amap_update_block_w",
                    suppress(3), info, boolean.class, boolean.class);
            H.hookSig(controller, "v", "amap_update_block_v",
                    suppress(1), boolean.class);
            probeCallback(cl, info);
            return;
        }

        // e changed name. Hook by name and let the shape check decide.
        H.log(Log.INFO, MainHook.TAG,
                "event=update_block_fallback reason=info_class_missing");
        H.hookAll(controller, "w", "amap_update_block_w", suppress(3));
        H.hookAll(controller, "v", "amap_update_block_v", suppress(1));
        probeCallback(cl, null);
    }

    /**
     * 只观察、不改行为：证明「更新检查这条链路真的跑到了」。
     *
     * <h3>为什么必须有这个探针</h3>
     * 只把 {@code w}/{@code v} 变成空实现，能证明的是「没弹框」，<b>不能</b>证明「检查真的执行过」——
     * 用户点了「检查更新」、请求失败、或者 tap 根本没命中，日志是一模一样的空白。
     * 这种情况写「已拦截」就是假通过。
     *
     * <p>{@code AppUpgradeController$AppUpdateCallBack.b(Object)} 是 {@code ws/app/conf/app_update}
     * 的响应回调，也是 {@code w} 的唯一调用方。它一旦命中，就说明：
     * 请求发了 → 响应回来了 → 回调跑了 → 而且框没弹出来。三段合一才算证据。
     *
     * <p>只在首次命中打一行，之后零成本；只 {@code proceed}，不碰参数也不碰返回值。
     */
    private static void probeCallback(ClassLoader cl, Class<?> info) {
        Class<?> callback = H.cls(cl, "com.amap.bundle.appupgrade.AppUpgradeController$AppUpdateCallBack");
        if (callback == null) {
            H.log(Log.WARN, MainHook.TAG, "event=anchor_miss feature=update_probe"
                    + " name=AppUpgradeController$AppUpdateCallBack");
            return;
        }
        XposedInterface.Hooker observer = new XposedInterface.Hooker() {
            private int hits;

            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                // 计数而不是「只打一次」：冷启动时高德自己会走一遍更新检查，那一次会把一次性
                // 标记吃掉，于是**手动点「检查更新」那一次在日志里完全看不见** —— 而那正是
                // 要证的那一次。前 5 次都打，之后闭嘴。
                if (hits < 5) {
                    hits++;
                    H.log(Log.INFO, MainHook.TAG, "event=update_check_reached"
                            + " method=AppUpdateCallBack.b n=" + hits);
                }
                return chain.proceed();
            }
        };
        // 形状不写死：b(Object) 的形参类型是混淆实体，跨版本会变。这里按名字挂，
        // 反正只 proceed，挂到哪个重载都不会改变行为。
        H.hookAll(callback, "b", "amap_update_probe_b", observer);
    }

    /**
     * Swallow the call when it has the expected shape, pass it through otherwise.
     *
     * <p>The shape check is what makes the by-name fallback safe: {@code hookAll} hooks every
     * overload with that name, and returning {@code null} from a method that is supposed to
     * return a value would corrupt the caller. Only a {@code void} method with the expected
     * parameter count is suppressed.
     */
    private static XposedInterface.Hooker suppress(final int parameterCount) {
        return new XposedInterface.Hooker() {
            private boolean reported;

            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                // getReturnType() lives on Method, not on Executable: an Executable may be a
                // Constructor, which has no return type at all.
                java.lang.reflect.Executable executable = chain.getExecutable();
                if (!(executable instanceof Method)) return chain.proceed();
                Method method = (Method) executable;
                if (method.getParameterCount() != parameterCount
                        || method.getReturnType() != void.class) {
                    return chain.proceed();
                }
                if (!reported) {
                    reported = true;
                    H.log(Log.INFO, MainHook.TAG, "event=update_blocked method="
                            + method.getName() + " params=" + parameterCount);
                }
                return null;
            }
        };
    }
}