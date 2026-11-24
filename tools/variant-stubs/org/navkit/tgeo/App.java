package org.navkit.tgeo;

import android.app.Application;

import io.github.libxposed.service.XposedService;

/** Stub for the 高德-only and 百度-only variants: no Tencent half is compiled in. */
public final class App {

    private App() {}

    public static Application app() { return null; }

    public static XposedService svc() { return null; }

    public static void attach(Application application) { }

    public static void onBind(XposedService service) { }

    public static void onDied(XposedService service) { }
}
