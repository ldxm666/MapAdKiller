package io.github.ldxm666.mapclean;

/** Narrow launch policy for the settings activity inside an LSPatch map process. */
public final class EmbeddedSettingsPolicy {
    public static final String EXTRA = "mapclean.embedded.settings";
    public static final String ACTION = "io.github.ldxm666.mapclean.EMBEDDED_SETTINGS";
    public static final String UI_PREFS = "mapclean_embedded_ui";
    public static final String BRIDGE_GROUP = "mapclean_embedded_bridge";
    public static final String PLACEHOLDER = "placeholder_activity";
    public static final String AMAP = "com.autonavi.minimap";
    public static final String BAIDU = "com.baidu.BaiduMap";

    private EmbeddedSettingsPolicy() {}

    public static boolean isHostPackage(String pkg) {
        return AMAP.equals(pkg) || BAIDU.equals(pkg);
    }

    public static boolean mayInstall(String frameworkName, String pkg, String process) {
        return "LSPatch".equalsIgnoreCase(frameworkName) && isHostPackage(pkg)
                && pkg.equals(process);
    }

    /** Manager mode has no writable host service; integrated mode becomes ready after delivery. */
    public static boolean canOpen(String host, String pkg, boolean loaderReady, boolean serviceReady) {
        return isHostPackage(host) && host.equals(pkg) && loaderReady && serviceReady;
    }

    public static boolean accepts(String host, String intentPackage, String componentPackage,
            String componentClass, String activityClass, String permittedClass, String action,
            boolean extra, boolean hostLoader) {
        return isHostPackage(host) && host.equals(intentPackage) && host.equals(componentPackage)
                && permittedClass != null && permittedClass.equals(componentClass)
                && permittedClass.equals(activityClass) && ACTION.equals(action)
                && extra && hostLoader;
    }
}
