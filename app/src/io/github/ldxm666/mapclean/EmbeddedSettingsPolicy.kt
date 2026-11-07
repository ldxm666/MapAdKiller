package io.github.ldxm666.mapclean

/** Narrow launch policy for the settings activity inside an LSPatch map process. */
class EmbeddedSettingsPolicy private constructor() {
    companion object {
        const val EXTRA = "mapclean.embedded.settings"
        const val ACTION = "io.github.ldxm666.mapclean.EMBEDDED_SETTINGS"
        const val UI_PREFS = "mapclean_embedded_ui"
        const val BRIDGE_GROUP = "mapclean_embedded_bridge"
        const val PLACEHOLDER = "placeholder_activity"
        const val AMAP = "com.autonavi.minimap"
        const val BAIDU = "com.baidu.BaiduMap"
        const val TENCENT = "com.tencent.map"

        /**
         * True when the module is running from inside a patched host (rootless / integrated
         * build) rather than as a standalone LSPosed module.
         *
         * <p>Two probes, because neither alone is sufficient:
         * <ol>
         *   <li>The framework name reported by the module. LSPatch identifies itself as such,
         *       and this is the only signal available at {@code onPackageReady} time.</li>
         *   <li>The host's asset table. LSPatch always writes {@code lspatch/config.json} next
         *       to the embedded module, so its presence is conclusive. This needs the host
         *       Application, which does not exist yet during {@code onPackageReady} -- so it is
         *       only consulted once an Application is available.</li>
         * </ol>
         * An earlier revision used the asset probe exclusively and therefore always answered
         * false at hook-install time, which silently disabled the whole floating layer.
         */
        @JvmStatic
        fun integrated(frameworkName: String?, host: android.content.Context?): Boolean {
            if (frameworkName != null && "LSPatch".equals(frameworkName, ignoreCase = true)) return true
            if (host != null) {
                try {
                    host.assets.open("lspatch/config.json").close()
                    return true
                } catch (ignored: Throwable) {
                    // Not an LSPatch host.
                }
            }
            return false
        }

        /**
         * Every map this module can run inside.
         *
         * <p><b>This list is load-bearing for service delivery, not just for the settings entry.</b>
         * In a slim (免 root 内置) build {@code mapclean.EmbeddedSettings} is the
         * {@code tools/variant-stubs} no-op, whose {@code enabledFor()} is exactly this method --
         * and {@code App.attachEmbedded()} opens with that gate. Omitting a vendor here makes
         * {@code attachEmbedded} throw <i>before</i> it registers the framework service listener,
         * and {@code FloatingConfig.ensureService()}'s catch turns that into a silent no-op: the
         * process then has no delivery path at all, {@code App.svc()} stays null forever, and
         * every switch in that vendor's orb reads 只读 · 服务未连接.
         *
         * <p>腾讯 was missing from this list, which is precisely why 百度 worked and 腾讯 did not
         * in the same build. Real-device evidence (build 2.1.6, both processes in one logcat):
         * <pre>
         *   I/MapCleanApp ( 8013) [com.baidu.BaiduMap]  : onServiceBind ok
         *   I/MapCleanApp ( 8013) [com.baidu.BaiduMap]  : registerListener ok
         *   W/MapCleanFloat(20273) [com.tencent.map]    : embedded service gate closed pkg=com.tencent.map
         *   W/MapCleanFloat(20273) [com.tencent.map]    : java.lang.IllegalStateException: Embedded settings require the LSPatch host
         *   W/MapCleanFloat(20273) [com.tencent.map]    :   at io.github.ldxm666.mapclean.App.attachEmbedded(App.java:62)
         *   W/MapCleanFloat(20273) [com.tencent.map]    :   at io.github.ldxm666.mapclean.FloatingConfig.ensureService(FloatingConfig.java:84)
         *   W/MapCleanFloat(20273) [com.tencent.map]    :   at io.github.ldxm666.mapclean.FloatingHooks$3.onActivityCreated(FloatingHooks.java:86)
         * </pre>
         */
        @JvmStatic
        fun isHostPackage(pkg: String?): Boolean {
            return AMAP == pkg || BAIDU == pkg || TENCENT == pkg
        }

        @JvmStatic
        fun mayInstall(frameworkName: String?, pkg: String?, process: String?): Boolean {
            return "LSPatch".equals(frameworkName, ignoreCase = true) && isHostPackage(pkg) &&
                pkg == process
        }

        /** Manager mode has no writable host service; integrated mode becomes ready after delivery. */
        @JvmStatic
        fun canOpen(host: String?, pkg: String?, loaderReady: Boolean, serviceReady: Boolean): Boolean {
            return isHostPackage(host) && host == pkg && loaderReady && serviceReady
        }

        @JvmStatic
        fun accepts(
            host: String?, intentPackage: String?, componentPackage: String?,
            componentClass: String?, activityClass: String?, permittedClass: String?, action: String?,
            extra: Boolean, hostLoader: Boolean
        ): Boolean {
            return isHostPackage(host) && host == intentPackage && host == componentPackage &&
                permittedClass != null && permittedClass == componentClass &&
                permittedClass == activityClass && ACTION == action &&
                extra && hostLoader
        }
    }
}