package io.github.ldxm666.mapclean

/**
 * Which map app the orb is currently running inside.
 *
 * <p>The floating orb lives in the <b>host</b> process, so it always knows which map it is
 * drawn over. That one fact decides everything vendor-specific: which switch catalogue to
 * show, and which configuration group to read and write.
 *
 * <p>Two ways to learn it, because the two matter at different times:
 * <ul>
 *   <li>[host] — the framework hands us the package name at install time,
 *       before any Activity exists;</li>
 *   <li>[from] — the panel's own context, which is available
 *       even if the install callback somehow did not run.</li>
 * </ul>
 * Unknown host falls back to 高德, which is the variant that has always existed.
 */
class Vendor private constructor() {

    companion object {
        const val AMAP = "com.autonavi.minimap"
        const val BAIDU = "com.baidu.BaiduMap"
        const val TMAP = "com.tencent.map"

        @Volatile
        private var HOST: String? = null

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
         * 第三次覆盖之后 {@code Vendor.tmap()} 恒为 false ⇒ [FloatingConfig.backend] 走
         * 高德分支（腾讯构建里那个类是 no-op 桩）⇒ 返回 {@code BACKEND_NONE} ⇒ 面板顶部
         * 「○ 只读」，厂商目录也整页消失（只剩公告/外观）。**与配置文件能不能写毫无关系**，
         * 排查时看到的就是「写得进但显示只读」。
         *
         * <p>规则：只接受 [AMAP] / [BAIDU] / [TMAP]；一旦定案不再改写
         * —— 一个进程只可能跑在一张地图里。
         */
        @JvmStatic
        fun host(pkg: String?) {
            if (!known(pkg)) return
            if (HOST != null) return      // 已定案：地图身份不会被另一个包名顶掉
            HOST = pkg
        }

        /** 三张地图之一？ */
        private fun known(pkg: String?): Boolean {
            return AMAP == pkg || BAIDU == pkg || TMAP == pkg
        }

        @JvmStatic
        fun host(): String? = HOST

        /** True when the orb is running inside 百度地图. */
        @JvmStatic
        fun baidu(): Boolean = BAIDU == HOST

        /** True when the orb is running inside 腾讯地图. */
        @JvmStatic
        fun tmap(): Boolean = TMAP == HOST

        /**
         * Same question, answered from a live context; also records the answer.
         *
         * <p>只认地图包名（见 [host]）：WebView / MIUI 组件把自己的 Context
         * 传进来时一律忽略，返回值仍以已定案的宿主为准。
         */
        @JvmStatic
        fun from(context: android.content.Context?): Boolean {
            if (context != null) {
                try {
                    host(context.packageName)
                } catch (ignored: Throwable) {
                    // 拿不到包名就当没问过。
                }
            }
            return baidu()
        }
    }
}