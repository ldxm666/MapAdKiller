package org.navkit.bgeo;

import android.app.Application;

import io.github.libxposed.service.XposedService;

/**
 * Stub for the slim (高德-only) variant.
 *
 * The real class binds the module process to the LSPosed service so the settings page can read
 * and write the {@code nk_bgeo_prefs} group. In this variant there is no 百度 page to serve,
 * but {@code mapclean.App} -- the only real Application -- still drives the 百度 half, so the
 * three entry points have to exist. They do nothing and own no service, which is correct here.
 */
public final class App {

    private App() {}

    public static void attach(Application application) { }

    public static void onBind(XposedService service) { }

    public static void onDied(XposedService service) { }
}
