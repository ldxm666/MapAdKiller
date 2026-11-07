package io.github.ldxm666.mapadkiller

import android.app.Application
import android.content.Context
import android.util.Log

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 去除 3D / 扁平建筑物（实验），默认关。
 *
 * <h3>两条腿，一条真、一条兜底</h3>
 * <ol>
 *   <li><b>native</b>（真正生效）：`libamapbldpatch.so` 把 `libamapr.so` 里
 *       `BuildingStyleLayer` 虚表的 [4] 槽换成 `mov x0,xzr; ret`。楼块图层照常
 *       构造、照常返回，只是不再产出几何。定位过程、四条错路、为什么 [4] 而不是 [8]，都写在
 *       `app/native/amap_buildings.c` 的文件头里。</li>
 *   <li><b>Java setter</b>（兜底，本版本实测不触发）：11 个建筑可见性 setter 全部压 false。
 *       AmapTune 在 17.00.0.2005 上用间谍扫过 437 个 setter，导航态真正被调用的只有
 *       `setBuildTextureVisible`，而它管的是贴图不是几何。留着它们的理由只有一个：
 *       native 补丁若因版本漂移被自检拒绝，这一层至少在"贴图"维度上仍然正确 —— 但**不要**
 *       把它当成能去掉楼块的实现。</li>
 * </ol>
 *
 * <h3>为什么不能让工厂返回 null</h3>
 * 试过。调用方对"已知图层类型"不预期 null，进程直接死（`fg TOP`，无 ART crash、
 * 无 tombstone）。所以补丁只动绘制槽，不动对象的存在性。
 *
 * <h3>开关语义</h3>
 * 安装期门控：开关关着就一条 hook 不装、.so 也不加载。因此**切换开关必须强停并重开高德**；
 * native 补丁本身还是进程级的，即使之后关掉开关，也要重启宿主才恢复楼块。
 */
object AmapBuildingPatch {

    private const val LIBRARY = "amapbldpatch"
    private const val FILE = "lib" + LIBRARY + ".so"

    @Volatile
    private var loadStarted = false

    @JvmStatic
    fun install(cl: ClassLoader) {
        javaHooks(cl)
        startNative()
    }

    // ───────────────────────────────────────────── native library

    /**
     * Load `libamapbldpatch.so` on a daemon thread.
     *
     * <p>A thread because `onPackageReady` fires before the host `Application`
     * exists, and the only reliable extraction target (the host's `codeCacheDir`) needs
     * a Context. The library's own constructor starts the patcher, so nothing here waits.
     */
    private fun startNative() {
        if (loadStarted) return
        synchronized(this) {
            if (loadStarted) return
            loadStarted = true
        }
        val thread = Thread(object : Runnable {
            override fun run() {
                load()
            }
        }, "amap-building-patch")
        thread.isDaemon = true
        thread.start()
    }

    private fun load() {
        // 1) The linker's search path. In a plain LSPosed install the module's own native
        //    directory is on it, and this is the cheapest success case.
        try {
            System.loadLibrary(LIBRARY)
            H.log(Log.INFO, MainHook.TAG, "event=building_native from=linker")
            return
        } catch (ignored: Throwable) {
            // Expected when the module is embedded in the host (免 root 内置版): the module
            // APK's lib/<abi>/ is repacked by the patcher and no name-based path covers it.
        }

        // 2) An absolute path — the host's extracted native dir, or pulled out of the module
        //    APK into the host's own cache. Both need the host Application, which does not
        //    exist yet at install time, so retry for ~10 s.
        for (i in 0 until 100) {
            val context = currentApplication()
            if (context != null) {
                if (loadFrom(context)) return
                H.log(Log.WARN, MainHook.TAG, "event=building_native from=none"
                        + " reason=not_found_in_host")
                return
            }
            try {
                Thread.sleep(100L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        H.log(Log.WARN, MainHook.TAG, "event=building_native reason=no_context")
    }

    private fun loadFrom(context: Context): Boolean {
        // 2a) The platform's extracted copy. The 免 root 内置版's host APK carries
        //     lib/<abi>/libamapbldpatch.so, which the platform extracts here.
        try {
            val directory = context.applicationInfo.nativeLibraryDir
            if (directory != null) {
                val candidate = File(directory, FILE)
                if (candidate.isFile) {
                    System.load(candidate.absolutePath)
                    H.log(Log.INFO, MainHook.TAG, "event=building_native from=host_native_dir"
                            + " path=" + candidate)
                    return true
                }
            }
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=building_native step=host_dir err="
                    + error.javaClass.simpleName)
        }

        // 2b) Extract from the module APK into the host's code cache and load that.
        try {
            val apk = moduleApkPath() ?: return false
            val target = File(context.codeCacheDir, FILE)
            extract(apk, target)
            System.load(target.absolutePath)
            H.log(Log.INFO, MainHook.TAG, "event=building_native from=module_apk path="
                    + target + " size=" + target.length())
            return true
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=building_native step=module_apk err="
                    + error.javaClass.simpleName
                    + (if (error.message == null) "" else (": " + error.message)))
            return false
        }
    }

    /**
     * Copy the library out of the module APK, always overwriting.
     *
     * <p>Overwrite matters: a stale copy left in code_cache from a previous build keeps running
     * the old patch, and "I changed it and nothing happened" is indistinguishable from a broken
     * hook. AmapTune shipped that bug once.
     */
    private fun extract(apk: String, target: File) {
        var zip: ZipFile? = null
        var input: InputStream? = null
        var out: FileOutputStream? = null
        try {
            zip = ZipFile(apk)
            val entry = findEntry(zip) ?: throw IllegalStateException("entry missing: " + FILE)
            input = zip.getInputStream(zip.getEntry(entry))
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw IllegalStateException("cannot create " + parent)
            }
            out = FileOutputStream(target)
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read > 0) {
                out.write(buffer, 0, read)
                read = input.read(buffer)
            }
            out.flush()
        } finally {
            try { out?.close() } catch (ignored: Throwable) {}
            try { input?.close() } catch (ignored: Throwable) {}
            try { zip?.close() } catch (ignored: Throwable) {}
        }
    }

    /**
     * Accept both layouts the library can appear in.
     *
     * <p>`lib/<abi>/` is what this project builds. LSPatch rewrites it to `<abi>/`
     * while handing the module to the host, so the second form has to be accepted too — that
     * exact mismatch is what made the label library silently never load in an embedded build.
     */
    private fun findEntry(zip: ZipFile): String? {
        for (abi in supportedAbis()) {
            val packaged = "lib/" + abi + "/" + FILE
            if (zip.getEntry(packaged) != null) return packaged
            val repacked = abi + "/" + FILE
            if (zip.getEntry(repacked) != null) return repacked
        }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (name.endsWith("/" + FILE)) return name
        }
        return null
    }

    private fun supportedAbis(): Array<String> {
        val abis = android.os.Build.SUPPORTED_ABIS
        return if (abis == null || abis.isEmpty()) arrayOf("arm64-v8a") else abis
    }

    /** Where this module's dex was loaded from; see [NativeLabelAssets] for the shapes. */
    private fun moduleApkPath(): String? {
        try {
            val description = AmapBuildingPatch::class.java.classLoader.toString()
            val matcher = java.util.regex.Pattern
                    .compile("module=([^,\\]]+\\.apk)").matcher(description)
            if (matcher.find()) {
                val file = File(matcher.group(1))
                if (file.isFile) return file.absolutePath
            }
        } catch (ignored: Throwable) {
        }
        try {
            val source = AmapBuildingPatch::class.java.protectionDomain.codeSource
            if (source != null && source.location != null) {
                val path = java.net.URLDecoder.decode(source.location.path, "UTF-8")
                if (path.endsWith(".apk") && File(path).isFile) return path
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun currentApplication(): Context? {
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val application = activityThread.getMethod("currentApplication").invoke(null)
            return if (application is Application) application else null
        } catch (error: Throwable) {
            return null
        }
    }

    // ───────────────────────────────────────────── Java fallback hooks

    /**
     * Force every building-visibility setter to `false`.
     *
     * <p>Payload index differs per family and writing the wrong slot is worse than not hooking
     * at all: on `(int, boolean)` the first argument is the engine/page id, on
     * `(String, boolean)` it is the scene bundle name, and on `(boolean)` there is
     * only the flag.
     */
    private fun javaHooks(cl: ClassLoader) {
        val controller = H.cls(cl, "com.autonavi.ae.gmap.AMapController")
        if (controller == null) {
            H.log(Log.WARN, MainHook.TAG,
                    "event=anchor_miss feature=building name=com.autonavi.ae.gmap.AMapController")
        } else {
            H.hookSig(controller, "setNormalBuildVisibility", "amap_bld_setNormalBuildVisibility",
                    falseAt(1), Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            H.hookSig(controller, "setBuildTextureVisibility",
                    "amap_bld_setBuildTextureVisibility", falseAt(1), Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            H.hookSig(controller, "setBldAndModelVisibility", "amap_bld_setBldAndModelVisibility",
                    falseAt(1), Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }

        val scene = H.cls(cl, "com.autonavi.jni.vmap.dsl.VMapSceneWrapper")
        if (scene == null) {
            H.log(Log.WARN, MainHook.TAG, "event=anchor_miss feature=building"
                    + " name=com.autonavi.jni.vmap.dsl.VMapSceneWrapper")
            return
        }
        H.hookSig(scene, "setProcessBuilding", "amap_bld_setProcessBuilding",
                falseAt(1), String::class.java, Boolean::class.javaPrimitiveType)
        H.hookSig(scene, "setProcessBuildingNormal", "amap_bld_setProcessBuildingNormal",
                falseAt(1), String::class.java, Boolean::class.javaPrimitiveType)
        H.hookSig(scene, "setIsBuildNormalOn", "amap_bld_setIsBuildNormalOn",
                falseAt(1), String::class.java, Boolean::class.javaPrimitiveType)
        H.hookSig(scene, "setBuildTextureVisible", "amap_bld_setBuildTextureVisible",
                falseAt(1), String::class.java, Boolean::class.javaPrimitiveType)
        H.hookSig(scene, "setGlobalProcessBuilding", "amap_bld_setGlobalProcessBuilding",
                falseAt(0), Boolean::class.javaPrimitiveType)
        H.hookSig(scene, "setGlobalProcessBuildingNormal",
                "amap_bld_setGlobalProcessBuildingNormal", falseAt(0), Boolean::class.javaPrimitiveType)
        H.hookSig(scene, "setGlobalIsBuildNormalOn", "amap_bld_setGlobalIsBuildNormalOn",
                falseAt(0), Boolean::class.javaPrimitiveType)
        H.hookSig(scene, "setGlobalBuildTextureVisible", "amap_bld_setGlobalBuildTextureVisible",
                falseAt(0), Boolean::class.javaPrimitiveType)
    }

    private val FALSE: AmapHookArgs.Value = AmapHookArgs.Value {
        java.lang.Boolean.FALSE
    }

    private fun falseAt(index: Int): io.github.libxposed.api.XposedInterface.Hooker {
        return AmapHookArgs.arg(index, FALSE)
    }
}
