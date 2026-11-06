package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * 判断某个宿主包是不是「免 root 内置版」（LSPatch 打过包的）。
 *
 * <h3>为什么需要它</h3>
 * 独立设置 App 里拨开关，写的是 <b>App 自己的沙箱</b>
 * （{@code /data/data/io.github.ldxm666.mapadkiller/shared_prefs/...}），
 * 而地图进程读的是 <b>宿主自己的沙箱</b>（{@code /data/data/<host>/shared_prefs/<vendor>clean_config.xml}）。
 * 两个包、两个 UID，永远读不到 —— 但 UI 是「先写先翻」，用户看到开关拨过去了，
 * 以为成功了。这就是「内置版开关无效 / 开关不匹配」的真正机制。
 *
 * <p>模块版（LSPosed）不存在这个问题：那时 App 写的是 RemotePreferences，
 * 宿主通过框架读同一张表。所以只有<b>内置版</b>要把页面锁成只读。
 *
 * <h3>怎么判</h3>
 * LSPatch 产物在 {@code assets/lspatch/} 下有自己的加载器、origin.apk 与 config.json
 * （本仓库 {@code tools/build_variant.py} 的三个常量 {@code assets/lspatch/config.json} /
 * {@code assets/lspatch/modules/} / {@code assets/lspatch/origin.apk} 就是同一份约定）。
 * 只要 {@code base.apk} 的 zip 目录里出现 {@link #MARKER}，这个 App 就是内置版。
 *
 * <p>用的是 {@link ZipFile} —— 只读中央目录，<b>不解压</b> 180 MB 的包体，
 * 几十毫秒量级；而且整件事跑在后台线程，绝不占用主线程。
 *
 * <h3>失败必须 fail-open</h3>
 * 读不到（包没装、无可见性、IO 异常）一律返回 {@code false} = 按模块版处理 = <b>允许操作</b>。
 * 宁可让极少数内置版用户看到旧行为，也不能因为一次 IO 失败把正常用户的界面锁死。
 */
public final class EmbeddedBuild {

    private static final String TAG = "MapCleanEmbeddedBuild";

    /** LSPatch 内置版的标志文件。存在即内置版。 */
    public static final String MARKER = "assets/lspatch/config.json";

    /** 结果回调；{@code embedded=false} 也包含「没测出来」的情况（fail-open）。 */
    public interface Callback {
        void onResult(boolean embedded);
    }

    /** pkg → 结果。只缓存"测完了"的值，失败也是 false，不区分 —— 失败重测没有意义且会抖。 */
    private static final ConcurrentHashMap<String, Boolean> CACHE =
            new ConcurrentHashMap<String, Boolean>();

    /** pkg → 正在等结果的回调；同一个包并发探测时只起一个线程。 */
    private static final ConcurrentHashMap<String, List<Callback>> PENDING =
            new ConcurrentHashMap<String, List<Callback>>();

    private EmbeddedBuild() {}

    /** 已经测过的结果；{@code null} = 还没测。调用方据此决定要不要先按"未锁定"渲染。 */
    public static Boolean cached(String pkg) {
        return pkg == null ? null : CACHE.get(pkg);
    }

    /**
     * 后台探测；结果可能在任意线程回调，调用方自己 post 回主线程。
     *
     * @param ctx 任意 Context（内部会取 application context）
     * @param pkg 宿主包名，例如 {@code com.tencent.map}
     */
    public static void detect(final Context ctx, final String pkg, final Callback cb) {
        if (ctx == null || pkg == null || pkg.length() == 0) {
            if (cb != null) cb.onResult(false);
            return;
        }
        Boolean hit = CACHE.get(pkg);
        if (hit != null) {
            if (cb != null) cb.onResult(hit.booleanValue());
            return;
        }
        List<Callback> mine = new ArrayList<Callback>(2);
        if (cb != null) mine.add(cb);
        List<Callback> running = PENDING.putIfAbsent(pkg, mine);
        if (running != null) {
            synchronized (running) { if (cb != null) running.add(cb); }
            return;
        }
        Context app = ctx;
        try {
            Context c = ctx.getApplicationContext();
            if (c != null) app = c;
        } catch (Throwable ignored) { }
        final Context probeCtx = app;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                boolean value = false;
                try {
                    value = probe(probeCtx, pkg);
                } catch (Throwable t) {
                    // fail-open：读不到就当模块版，界面保持可操作
                    Log.w(TAG, "probe failed pkg=" + pkg + " err=" + t);
                    value = false;
                }
                CACHE.put(pkg, Boolean.valueOf(value));
                List<Callback> waiters = PENDING.remove(pkg);
                if (waiters == null) return;
                synchronized (waiters) {
                    for (int i = 0; i < waiters.size(); i++) {
                        try {
                            waiters.get(i).onResult(value);
                        } catch (Throwable ignored) { }
                    }
                }
            }
        }, "MapCleanEmbeddedBuild");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 同步探测 —— <b>只能在后台线程调用</b>。
     *
     * <p>先看 base.apk；base 里没有再看 split（少数渠道包会把资源拆出去）。
     * LSPatch 自己写的是 base，split 这一层只是别漏判。
     */
    static boolean probe(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return false;
        PackageManager pm = ctx.getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (Throwable t) {
            // 没装 / 无包可见性声明 —— 都不是内置版
            return false;
        }
        if (ai == null) return false;
        if (hasMarker(ai.sourceDir)) return true;
        String[] splits = ai.splitSourceDirs;
        if (splits != null) {
            for (int i = 0; i < splits.length; i++) {
                if (hasMarker(splits[i])) return true;
            }
        }
        return false;
    }

    /** 只读 zip 中央目录，不解压。 */
    private static boolean hasMarker(String path) {
        if (path == null || path.length() == 0) return false;
        ZipFile zip = null;
        try {
            zip = new ZipFile(path);
            return zip.getEntry(MARKER) != null;
        } catch (Throwable t) {
            Log.w(TAG, "zip read failed " + path + " err=" + t);
            return false;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
