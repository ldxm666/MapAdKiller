package io.github.ldxm666.mapclean;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 合并版（v2.0.0）里**唯一真正的 Application**。
 *
 * 原来 BMapClean 与 MapAdKiller 各自有一个 `App extends Application`，一个 APK 只能有一个，
 * 所以这里做转发：本类负责向 LSPosed 注册监听，拿到服务后**同时**喂给两边的静态持有者，
 * 两边的设置页代码因此一行都不用改（各自仍然读自己的 RemotePreferences group）：
 *   - io.github.ldxm666.bmapclean.App  → group `bmapclean_config`（百度界面精简）
 *   - io.github.ldxm666.mapadkiller.App → group `amap_enhancer_config`（高德/百度/腾讯去广告）
 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "MapCleanApp";

    private static volatile Application self;

    private static final FrameworkStatus.Monitor status = new FrameworkStatus.Monitor(
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread worker = new Thread(task, "MapClean-framework-status");
                    worker.setDaemon(true);
                    return worker;
                }
            }), new FrameworkStatus.Clock() {
                @Override public long elapsedMillis() { return android.os.SystemClock.elapsedRealtime(); }
            }, 5000);
    private static volatile XposedService boundService;
    private static FrameworkStatus.Source boundStatusSource;
    private static boolean listenerRegistered;
    private static volatile boolean embedded;

    private static final XposedServiceHelper.OnServiceListener embeddedListener =
            new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService service) { bindService(service); }
                @Override public void onServiceDied(XposedService service) { diedService(service); }
            };

    public static FrameworkStatus.Snapshot frameworkStatus() { return status.snapshot(); }
    public static void refreshFrameworkStatus() { status.refresh(); }
    public static boolean embedded() { return embedded; }
    static boolean hasEmbeddedService() { return boundService != null; }

    /** LSPatch delivers its writable Binder after modules load, before the host Application. */
    static void listenEmbedded() { registerListener(embeddedListener); }

    /** Reuse the attached host Application; never construct an Application with an unset base. */
    public static synchronized void attachEmbedded(Application host) {
        if (host == null || !EmbeddedSettings.enabledFor(host.getPackageName())) {
            throw new IllegalStateException("Embedded settings require the LSPatch host");
        }
        if (self == host && embedded && listenerRegistered) return;
        self = host;
        embedded = true;
        io.github.ldxm666.bmapclean.App.attach(host);
        io.github.ldxm666.mapadkiller.App.attach(host);
        XposedService service = boundService;
        if (service != null) {
            try { io.github.ldxm666.bmapclean.App.onBind(service); } catch (Throwable ignored) {}
            try { io.github.ldxm666.mapadkiller.App.onBind(service); } catch (Throwable ignored) {}
        }
        registerListener(embeddedListener);
    }

    private static synchronized void registerListener(XposedServiceHelper.OnServiceListener listener) {
        if (listenerRegistered) return;
        try {
            XposedServiceHelper.registerListener(listener);
            listenerRegistered = true;
            Log.i(TAG, "registerListener ok");
        } catch (Throwable t) {
            Log.e(TAG, "registerListener failed", t);
        }
    }

    private static final class ServiceStatusSource implements FrameworkStatus.Source {
        private final XposedService service;
        ServiceStatusSource(XposedService service) { this.service = service; }
        @Override public int getApiVersion() { return service.getApiVersion(); }
        @Override public String getFrameworkName() { return service.getFrameworkName(); }
        @Override public String getFrameworkVersion() { return service.getFrameworkVersion(); }
        @Override public List<String> getScope() { return service.getScope(); }
    }

    public static Application app() { return self; }

    public static Context ctx() { return self; }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        io.github.ldxm666.bmapclean.App.attach(this);
        io.github.ldxm666.mapadkiller.App.attach(this);
        registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService s) { bindService(s); }

    private static synchronized void bindService(XposedService s) {
        Log.i(TAG, "onServiceBind ok");
        boundService = s;
        boundStatusSource = new ServiceStatusSource(s);
        status.bind(boundStatusSource);
        try { io.github.ldxm666.bmapclean.App.onBind(s); } catch (Throwable t) { Log.w(TAG, "bmap bind", t); }
        try { io.github.ldxm666.mapadkiller.App.onBind(s); } catch (Throwable t) { Log.w(TAG, "amap bind", t); }
    }

    @Override
    public void onServiceDied(XposedService s) { diedService(s); }

    private static synchronized void diedService(XposedService s) {
        Log.w(TAG, "onServiceDied");
        if (boundService == s) {
            status.disconnected(boundStatusSource);
            boundStatusSource = null;
            boundService = null;
        }
        try { io.github.ldxm666.bmapclean.App.onDied(s); } catch (Throwable ignored) {}
        try { io.github.ldxm666.mapadkiller.App.onDied(s); } catch (Throwable ignored) {}
    }
}
