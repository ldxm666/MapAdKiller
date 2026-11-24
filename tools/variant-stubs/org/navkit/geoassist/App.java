package org.navkit.geoassist;

import android.app.Application;

import io.github.libxposed.service.XposedService;

/**
 * Stub for the 百度-only variant.
 *
 * <p>The 高德 half is dropped whole, but the shared {@code mapclean.App} still drives every
 * vendor holder by name -- one APK declares exactly one Application, so the others have to be
 * reached explicitly. This one owns no service and says so, rather than pretending to be
 * connected: a silent "true" here would make the orb report a writable config it cannot write.
 */
public final class App {

    /** Same value as the real class, so shared callers still link. */
    public static final String UI_PREFS = "nk_ui_prefs";

    private App() {}

    public static XposedService svc() { return null; }

    public static void attach(Application application) { }

    public static void onBind(XposedService service) { }

    public static void onDied(XposedService service) { }
}
