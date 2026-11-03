package io.github.ldxm666.mapclean;

/**
 * Which map app the orb is currently running inside.
 *
 * <p>The floating orb lives in the <b>host</b> process, so it always knows which map it is
 * drawn over. That one fact decides everything vendor-specific: which switch catalogue to
 * show, and which configuration group to read and write.
 *
 * <p>Two ways to learn it, because the two matter at different times:
 * <ul>
 *   <li>{@link #host(String)} — the framework hands us the package name at install time,
 *       before any Activity exists;</li>
 *   <li>{@link #from(android.content.Context)} — the panel's own context, which is available
 *       even if the install callback somehow did not run.</li>
 * </ul>
 * Unknown host falls back to 高德, which is the variant that has always existed.
 */
public final class Vendor {

    public static final String AMAP = "com.autonavi.minimap";
    public static final String BAIDU = "com.baidu.BaiduMap";
    public static final String TMAP = "com.tencent.map";

    private static volatile String host;

    private Vendor() {}

    /**
     * 记住宿主包名，**只认三张地图**。
     *
     * <p>★★ 2.3.0：这行校验是「面板显示只读」的真凶修复。原实现无条件覆盖：
     * <pre>
     *   Vendor.host(pkg);        // BaiduMineEntry.install() 里，对**任何**被注入的包都调
     * </pre>
     * 而框架会把模块装进一堆非地图进程。真机日志（同一进程 pid 24649，2.3.0 修复前）：
     * <pre>
     *   12:58:09.261 event=baidu_mine_entry_skipped reason=not_baidu host=com.tencent.map
     *   12:58:09.619 event=baidu_mine_entry_skipped reason=not_baidu host=com.miui.contentcatcher
     *   12:58:21.670 event=baidu_mine_entry_skipped reason=not_baidu host=com.google.android.webview
     * </pre>
     * 第三次覆盖之后 {@code Vendor.tmap()} 恒为 false ⇒ {@link FloatingConfig#backend()} 走
     * 高德分支（腾讯构建里那个类是 no-op 桩）⇒ 返回 {@code BACKEND_NONE} ⇒ 面板顶部
     * 「○ 只读」，厂商目录也整页消失（只剩公告/外观）。**与配置文件能不能写毫无关系**，
     * 排查时看到的就是「写得进但显示只读」。
     *
     * <p>规则：只接受 {@link #AMAP} / {@link #BAIDU} / {@link #TMAP}；一旦定案不再改写
     * —— 一个进程只可能跑在一张地图里。
     */
    public static void host(String pkg) {
        if (!known(pkg)) return;
        if (host != null) return;      // 已定案：地图身份不会被另一个包名顶掉
        host = pkg;
    }

    /** 三张地图之一？ */
    private static boolean known(String pkg) {
        return AMAP.equals(pkg) || BAIDU.equals(pkg) || TMAP.equals(pkg);
    }

    public static String host() { return host; }

    /** True when the orb is running inside 百度地图. */
    public static boolean baidu() { return BAIDU.equals(host); }

    /** True when the orb is running inside 腾讯地图. */
    public static boolean tmap() { return TMAP.equals(host); }

    /**
     * Same question, answered from a live context; also records the answer.
     *
     * <p>只认地图包名（见 {@link #host(String)}）：WebView / MIUI 组件把自己的 Context
     * 传进来时一律忽略，返回值仍以已定案的宿主为准。
     */
    public static boolean from(android.content.Context context) {
        if (context != null) {
            try {
                host(context.getPackageName());
            } catch (Throwable ignored) {
                // 拿不到包名就当没问过。
            }
        }
        return baidu();
    }
}
