package io.github.ldxm666.mapclean;

/** Ordinary and foreign activities must never be replaced by the embedded settings bridge. */
public final class EmbeddedSettingsPolicyTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static boolean request(String host, String intentPackage, String componentPackage,
            String component, String activity, String permitted, String action,
            boolean extra, boolean loader) {
        return EmbeddedSettingsPolicy.accepts(host, intentPackage, componentPackage, component,
                activity, permitted, action, extra, loader);
    }
    public static void main(String[] args) {
        String host = EmbeddedSettingsPolicy.AMAP;
        String component = "com.autonavi.map.activity.NewMapActivity";
        String action = EmbeddedSettingsPolicy.ACTION;
        check(EmbeddedSettingsPolicy.mayInstall("LSPatch", host, host), "LSPatch main map process is supported");
        check(EmbeddedSettingsPolicy.mayInstall("LSPatch", EmbeddedSettingsPolicy.BAIDU, EmbeddedSettingsPolicy.BAIDU), "Baidu host is supported independently");
        check(!EmbeddedSettingsPolicy.mayInstall("Vector", host, host), "Vector native module does not install embedded bridge");
        check(!EmbeddedSettingsPolicy.mayInstall("LSPosed", host, host), "root module does not install embedded bridge");
        check(!EmbeddedSettingsPolicy.mayInstall("LSPatch", host, host + ":locationservice"), "host child process is excluded");
        check(!EmbeddedSettingsPolicy.mayInstall("LSPatch", "other.app", "other.app"), "other packages are excluded");
        check(!EmbeddedSettingsPolicy.mayInstall(null, host, host), "missing framework information does not enable bridge");
        check(!EmbeddedSettingsPolicy.mayInstall("LSPatch", host, null), "unknown process does not enable bridge");
        check(EmbeddedSettingsPolicy.canOpen(host, host, true, true), "integrated host with writable service exposes settings");
        check(!EmbeddedSettingsPolicy.canOpen(host, host, true, false), "manager mode without writable service exposes no broken entry");
        check(!EmbeddedSettingsPolicy.canOpen(host, host, false, true), "service alone does not redirect an uninstalled host");
        check(!EmbeddedSettingsPolicy.canOpen(host, EmbeddedSettingsPolicy.BAIDU, true, true), "another map cannot use this host settings entry");
        check(!EmbeddedSettingsPolicy.canOpen(null, host, true, true), "no configured host remains disabled");
        check(!EmbeddedSettingsPolicy.canOpen("other.app", "other.app", true, true), "writable service never enables a foreign package");
        check(request(host, host, host, component, component, component, action, true, true), "exact host-local settings request is accepted");
        check(!request(host, host, host, component, component, component, action, false, true), "normal activity without settings extra is unchanged");
        check(!request(host, host, host, component, component, component, "android.intent.action.MAIN", true, true), "launcher and normal map actions are unchanged");
        check(!request(host, null, host, component, component, component, action, true, true), "missing explicit intent package is excluded");
        check(!request(host, "other.app", host, component, component, component, action, true, true), "foreign intent package is excluded");
        check(!request(host, host, "other.app", component, component, component, action, true, true), "foreign component package is excluded");
        check(!request(host, host, host, "other.Activity", component, component, action, true, true), "unapproved component is excluded");
        check(!request(host, host, host, component, "other.Activity", component, action, true, true), "component aliases or mismatched factory activity are excluded");
        check(!request(host, host, host, component, component, null, action, true, true), "unsaved placeholder is excluded");
        check(!request(host, host, host, component, component, component, action, true, false), "different activity class loader is excluded");
        check(!request(null, host, host, component, component, component, action, true, true), "unconfigured host is excluded");
        check(!request("other.app", "other.app", "other.app", component, component, component, action, true, true), "settings extra alone cannot redirect a foreign app");
        System.out.println("PASS " + assertions + " embedded settings policy assertions");
    }
}
