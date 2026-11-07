package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * Stub for the slim (免 root 内置) variant.
 *
 * The old entry point -- long-pressing 高德's "More Tools" cell -- is replaced by the
 * always-present floating orb, so the Activity-bridge machinery is not compiled in.
 * {@code FloatingHooks} owns the entry point instead.
 *
 * <b>2.3.0：{@link #enabledFor} 从「恒 true」改回委托策略类。</b>它正是
 * {@code mapclean.App.attachEmbedded} 第一句的闸门，也是
 * {@code tools/build_variant.py} 的 {@code check_host_whitelist()} 反读 DEX 逐个
 * const-string 校验的那份名单。以前这里硬编码 {@code true}，等于把
 * {@code EmbeddedSettingsPolicy.isHostPackage()} 从执行路径里摘掉 —— 那条构建期硬检查
 * 于是变成空转：名单写错也查不出来。名单现在三家齐全（高德 / 百度 / 腾讯，
 * 见 {@code EmbeddedSettingsPolicy.java:69-71}），委托回去不会重演 2.1.6「腾讯不在名单里
 * ⇒ attachEmbedded 在 registerListener 之前抛 ⇒ 腾讯进程没有投递路径 ⇒ 面板恒只读」的旧事故。
 */
public final class EmbeddedSettings {
    private static final String TAG = "MapCleanEmbedded";

    private EmbeddedSettings() {}

    /**
     * 谁能挂悬浮层 / 谁能拿到服务投递：与真件同一个判据。
     *
     * <p>名单漏一个厂商，这里就会在 {@code App.attachEmbedded} 的最前面抛
     * {@code IllegalStateException}，而 {@code FloatingConfig.ensureService()} 把它吞成一条
     * {@code embedded service gate closed} 日志 —— 那个厂商从此一条投递路径都没有。
     * 所以这份委托必须有，且名单必须完整：{@code check_host_whitelist()} 在构建期校验的就是它。
     */
    public static boolean enabledFor(String pkg) {
        return EmbeddedSettingsPolicy.isHostPackage(pkg);
    }

    /** No standalone settings Activity in this variant; the orb is the UI. */
    public static boolean open(Activity activity) { return false; }

    /** The tab bar keeps its native long-press behaviour. */
    public static void bindEntry(View view, String pkg) { }

    /** Never consumes: the floating orb, not this bridge, owns the entry point. */
    public static boolean consumeEntryTouch(View view, MotionEvent event) { return false; }

    public static void install(Object module, Object param, String process) {
        // ⚠ 2.3.0：这里只剩一行日志 ⇒ 真件 EmbeddedSettings.install() 里那句
        // `App.listenEmbedded()` 被吞掉了：框架服务的 registerListener 遂只剩
        // FloatingHooks 在第一个 Activity 的 onActivityCreated 里那一次——
        // Binder 是异步的，首启面板注定看到 App.svc()==null，
        // 药丸恒显「○ 只读·服务未连接」，开关写不进去（=用户反馈的“无法使用”）。
        // 并且 App.svc() 越早就绪越好：CfgStore.embedded() 的第二条判据也要靠它。
        try {
            // Kotlin 会给 *internal* 成员的 JVM 名加上 `$模块名` 后缀（防止跨模块误用），
            // App.listenEmbedded 声明为 `@JvmStatic internal fun`，所以它在这里的可调用名
            // 是 listenEmbedded$main —— 模块名用的是编译器默认值，app/build.ps1 与本工具
            // 都没有传 -module-name，两边一致。
            //
            // 故意写成直接调用而不是反射：名字再变一次就编译不过，而不是悄悄变成一次空调用，
            // 让每个内置版装机后面板恒只读、日志里又什么都看不到。
            io.github.ldxm666.mapclean.App.listenEmbedded$main();
            Log.i(TAG, "embedded settings replaced by the floating entry (service listener registered)");
        } catch (Throwable t) {
            Log.w(TAG, "listenEmbedded failed: " + t);
        }
    }
}
