package io.github.ldxm666.mapadkiller;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 去除 3D / 扁平建筑物（实验），默认关。
 *
 * <h3>两条腿，一条真、一条兜底</h3>
 * <ol>
 *   <li><b>native</b>（真正生效）：{@code libamapbldpatch.so} 把 {@code libamapr.so} 里
 *       {@code BuildingStyleLayer} 虚表的 [4] 槽换成 {@code mov x0,xzr; ret}。楼块图层照常
 *       构造、照常返回，只是不再产出几何。定位过程、四条错路、为什么 [4] 而不是 [8]，都写在
 *       {@code app/native/amap_buildings.c} 的文件头里。</li>
 *   <li><b>Java setter</b>（兜底，本版本实测不触发）：11 个建筑可见性 setter 全部压 false。
 *       AmapTune 在 17.00.0.2005 上用间谍扫过 437 个 setter，导航态真正被调用的只有
 *       {@code setBuildTextureVisible}，而它管的是贴图不是几何。留着它们的理由只有一个：
 *       native 补丁若因版本漂移被自检拒绝，这一层至少在"贴图"维度上仍然正确 —— 但**不要**
 *       把它当成能去掉楼块的实现。</li>
 * </ol>
 *
 * <h3>为什么不能让工厂返回 null</h3>
 * 试过。调用方对"已知图层类型"不预期 null，进程直接死（{@code fg TOP}，无 ART crash、
 * 无 tombstone）。所以补丁只动绘制槽，不动对象的存在性。
 *
 * <h3>开关语义</h3>
 * 安装期门控：开关关着就一条 hook 不装、.so 也不加载。因此**切换开关必须强停并重开高德**；
 * native 补丁本身还是进程级的，即使之后关掉开关，也要重启宿主才恢复楼块。
 */
final class AmapBuildingPatch {

    private static final String LIBRARY = "amapbldpatch";
    private static final String FILE = "lib" + LIBRARY + ".so";

    private static volatile boolean loadStarted;

    private AmapBuildingPatch() {}

    static void install(ClassLoader cl) {
        javaHooks(cl);
        startNative();
    }

    // ───────────────────────────────────────────── native library

    /**
     * Load {@code libamapbldpatch.so} on a daemon thread.
     *
     * <p>A thread because {@code onPackageReady} fires before the host {@code Application}
     * exists, and the only reliable extraction target (the host's {@code codeCacheDir}) needs
     * a Context. The library's own constructor starts the patcher, so nothing here waits.
     */
    private static void startNative() {
        if (loadStarted) return;
        synchronized (AmapBuildingPatch.class) {
            if (loadStarted) return;
            loadStarted = true;
        }
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                load();
            }
        }, "amap-building-patch");
        thread.setDaemon(true);
        thread.start();
    }

    private static void load() {
        // 1) The linker's search path. In a plain LSPosed install the module's own native
        //    directory is on it, and this is the cheapest success case.
        try {
            System.loadLibrary(LIBRARY);
            H.log(Log.INFO, MainHook.TAG, "event=building_native from=linker");
            return;
        } catch (Throwable ignored) {
            // Expected when the module is embedded in the host (免 root 内置版): the module
            // APK's lib/<abi>/ is repacked by the patcher and no name-based path covers it.
        }

        // 2) An absolute path — the host's extracted native dir, or pulled out of the module
        //    APK into the host's own cache. Both need the host Application, which does not
        //    exist yet at install time, so retry for ~10 s.
        for (int i = 0; i < 100; i++) {
            Context context = currentApplication();
            if (context != null) {
                if (loadFrom(context)) return;
                H.log(Log.WARN, MainHook.TAG, "event=building_native from=none"
                        + " reason=not_found_in_host");
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        H.log(Log.WARN, MainHook.TAG, "event=building_native reason=no_context");
    }

    private static boolean loadFrom(Context context) {
        // 2a) The platform's extracted copy. The 免 root 内置版's host APK carries
        //     lib/<abi>/libamapbldpatch.so, which the platform extracts here.
        try {
            String directory = context.getApplicationInfo().nativeLibraryDir;
            if (directory != null) {
                File candidate = new File(directory, FILE);
                if (candidate.isFile()) {
                    System.load(candidate.getAbsolutePath());
                    H.log(Log.INFO, MainHook.TAG, "event=building_native from=host_native_dir"
                            + " path=" + candidate);
                    return true;
                }
            }
        } catch (Throwable error) {
            H.log(Log.WARN, MainHook.TAG, "event=building_native step=host_dir err="
                    + error.getClass().getSimpleName());
        }

        // 2b) Extract from the module APK into the host's code cache and load that.
        try {
            String apk = moduleApkPath();
            if (apk == null) return false;
            File target = new File(context.getCodeCacheDir(), FILE);
            extract(apk, target);
            System.load(target.getAbsolutePath());
            H.log(Log.INFO, MainHook.TAG, "event=building_native from=module_apk path="
                    + target + " size=" + target.length());
            return true;
        } catch (Throwable error) {
            H.log(Log.WARN, MainHook.TAG, "event=building_native step=module_apk err="
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : (": " + error.getMessage())));
            return false;
        }
    }

    /**
     * Copy the library out of the module APK, always overwriting.
     *
     * <p>Overwrite matters: a stale copy left in code_cache from a previous build keeps running
     * the old patch, and "I changed it and nothing happened" is indistinguishable from a broken
     * hook. AmapTune shipped that bug once.
     */
    private static void extract(String apk, File target) throws Exception {
        ZipFile zip = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            zip = new ZipFile(apk);
            String entry = findEntry(zip);
            if (entry == null) throw new IllegalStateException("entry missing: " + FILE);
            in = zip.getInputStream(zip.getEntry(entry));
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("cannot create " + parent);
            }
            out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (zip != null) zip.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Accept both layouts the library can appear in.
     *
     * <p>{@code lib/<abi>/} is what this project builds. LSPatch rewrites it to {@code <abi>/}
     * while handing the module to the host, so the second form has to be accepted too — that
     * exact mismatch is what made the label library silently never load in an embedded build.
     */
    private static String findEntry(ZipFile zip) {
        for (String abi : supportedAbis()) {
            String packaged = "lib/" + abi + "/" + FILE;
            if (zip.getEntry(packaged) != null) return packaged;
            String repacked = abi + "/" + FILE;
            if (zip.getEntry(repacked) != null) return repacked;
        }
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.endsWith("/" + FILE)) return name;
        }
        return null;
    }

    private static String[] supportedAbis() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        return abis == null || abis.length == 0 ? new String[]{"arm64-v8a"} : abis;
    }

    /** Where this module's dex was loaded from; see {@link NativeLabelAssets} for the shapes. */
    private static String moduleApkPath() {
        try {
            String description = String.valueOf(AmapBuildingPatch.class.getClassLoader());
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("module=([^,\\]]+\\.apk)").matcher(description);
            if (matcher.find()) {
                File file = new File(matcher.group(1));
                if (file.isFile()) return file.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        try {
            java.security.CodeSource source =
                    AmapBuildingPatch.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                String path = java.net.URLDecoder.decode(source.getLocation().getPath(), "UTF-8");
                if (path.endsWith(".apk") && new File(path).isFile()) return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            return application instanceof Application ? (Application) application : null;
        } catch (Throwable error) {
            return null;
        }
    }

    // ───────────────────────────────────────────── Java fallback hooks

    /**
     * Force every building-visibility setter to {@code false}.
     *
     * <p>Payload index differs per family and writing the wrong slot is worse than not hooking
     * at all: on {@code (int, boolean)} the first argument is the engine/page id, on
     * {@code (String, boolean)} it is the scene bundle name, and on {@code (boolean)} there is
     * only the flag.
     */
    private static void javaHooks(ClassLoader cl) {
        Class<?> controller = H.cls(cl, "com.autonavi.ae.gmap.AMapController");
        if (controller == null) {
            H.log(Log.WARN, MainHook.TAG,
                    "event=anchor_miss feature=building name=com.autonavi.ae.gmap.AMapController");
        } else {
            H.hookSig(controller, "setNormalBuildVisibility", "amap_bld_setNormalBuildVisibility",
                    falseAt(1), int.class, boolean.class);
            H.hookSig(controller, "setBuildTextureVisibility",
                    "amap_bld_setBuildTextureVisibility", falseAt(1), int.class, boolean.class);
            H.hookSig(controller, "setBldAndModelVisibility", "amap_bld_setBldAndModelVisibility",
                    falseAt(1), int.class, boolean.class);
        }

        Class<?> scene = H.cls(cl, "com.autonavi.jni.vmap.dsl.VMapSceneWrapper");
        if (scene == null) {
            H.log(Log.WARN, MainHook.TAG, "event=anchor_miss feature=building"
                    + " name=com.autonavi.jni.vmap.dsl.VMapSceneWrapper");
            return;
        }
        H.hookSig(scene, "setProcessBuilding", "amap_bld_setProcessBuilding",
                falseAt(1), String.class, boolean.class);
        H.hookSig(scene, "setProcessBuildingNormal", "amap_bld_setProcessBuildingNormal",
                falseAt(1), String.class, boolean.class);
        H.hookSig(scene, "setIsBuildNormalOn", "amap_bld_setIsBuildNormalOn",
                falseAt(1), String.class, boolean.class);
        H.hookSig(scene, "setBuildTextureVisible", "amap_bld_setBuildTextureVisible",
                falseAt(1), String.class, boolean.class);
        H.hookSig(scene, "setGlobalProcessBuilding", "amap_bld_setGlobalProcessBuilding",
                falseAt(0), boolean.class);
        H.hookSig(scene, "setGlobalProcessBuildingNormal",
                "amap_bld_setGlobalProcessBuildingNormal", falseAt(0), boolean.class);
        H.hookSig(scene, "setGlobalIsBuildNormalOn", "amap_bld_setGlobalIsBuildNormalOn",
                falseAt(0), boolean.class);
        H.hookSig(scene, "setGlobalBuildTextureVisible", "amap_bld_setGlobalBuildTextureVisible",
                falseAt(0), boolean.class);
    }

    private static AmapHookArgs.Value FALSE = new AmapHookArgs.Value() {
        @Override public Object get() {
            return Boolean.FALSE;
        }
    };

    private static io.github.libxposed.api.XposedInterface.Hooker falseAt(int index) {
        return AmapHookArgs.arg(index, FALSE);
    }
}
