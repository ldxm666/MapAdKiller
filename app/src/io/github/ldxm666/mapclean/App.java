package io.github.ldxm666.mapclean;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

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

    public static Application app() { return self; }

    public static Context ctx() { return self; }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        io.github.ldxm666.bmapclean.App.attach(this);
        io.github.ldxm666.mapadkiller.App.attach(this);
        try {
            XposedServiceHelper.registerListener(this);
            Log.i(TAG, "registerListener ok");
        } catch (Throwable t) {
            Log.e(TAG, "registerListener failed", t);
        }
    }

    @Override
    public void onServiceBind(XposedService s) {
        Log.i(TAG, "onServiceBind ok");
        try { io.github.ldxm666.bmapclean.App.onBind(s); } catch (Throwable t) { Log.w(TAG, "bmap bind", t); }
        try { io.github.ldxm666.mapadkiller.App.onBind(s); } catch (Throwable t) { Log.w(TAG, "amap bind", t); }
    }

    @Override
    public void onServiceDied(XposedService s) {
        Log.w(TAG, "onServiceDied");
        try { io.github.ldxm666.bmapclean.App.onDied(s); } catch (Throwable ignored) {}
        try { io.github.ldxm666.mapadkiller.App.onDied(s); } catch (Throwable ignored) {}
    }
}
