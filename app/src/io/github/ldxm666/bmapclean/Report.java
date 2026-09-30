package io.github.ldxm666.bmapclean;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Hook 侧 → 设置页的回报通道。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么不用 RemotePreferences 回写
 * ══════════════════════════════════════════════════════════════════════════
 * 实测证据（真机 logcat，v0.1.0）：
 *     diag write failed: java.lang.UnsupportedOperationException: Read only implementation
 * libxposed API 102 在 hook 侧拿到的 RemotePreferences 是**只读实现**，
 * `edit().commit()` 必然抛异常。所以「把命中结果写进 RemotePreferences 给设置页看」
 * 这条路走不通。
 *
 * 方向反过来就对：**App 侧可写、hook 侧可读**。于是：
 *   · App 侧写一个随机 token 进 RemotePreferences；
 *   · hook 侧读出来，带着它给模块 App 发一条显式广播；
 *   · 设置页据此显示「百度地图进程已回报：钩子已注入 / 本次隐藏 N 项」。
 *
 * 这条通道的价值：设置页的状态不再依赖「LSPosed 服务有没有绑上」，
 * 而是来自**目标进程本身的证据** —— 用户不会再看到"我明明勾选了却说未激活"。
 */
public final class Report {

    public static final String ACTION = "io.github.ldxm666.bmapclean.REPORT";

    /** 距上次发送不足这个间隔就丢弃（避免高频扫描刷屏/反复拉起设置页进程） */
    private static final long MIN_GAP_MS = 1500;

    private static volatile long lastAt;
    private static volatile Context appCtx;

    private Report() {}

    public static void attach(Context ctx) {
        appCtx = ctx;
    }

    /**
     * @param force true = 忽略节流（用于「钩子装好了」这种必须送达的一次性事件）
     */
    public static void send(Context ctx, String stage, String detail, boolean force) {
        Context c = ctx != null ? ctx : appCtx;
        if (c == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastAt < MIN_GAP_MS) return;
        lastAt = now;
        try {
            Intent i = new Intent(ACTION);
            i.setPackage(MainHook.PKG_SELF);
            i.putExtra("token", Cfg.token());
            i.putExtra("stage", stage);
            i.putExtra("detail", detail);
            i.putExtra("ts", now);
            c.sendBroadcast(i);
            if (Cfg.debug()) H.log(Log.INFO, MainHook.TAG, "report sent stage=" + stage + " " + detail);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "report send failed: " + t);
        }
    }
}
