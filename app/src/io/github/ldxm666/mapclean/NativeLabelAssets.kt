package io.github.ldxm666.mapclean

import android.os.Build
import android.util.Log

import java.io.File
import java.util.Arrays
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Drives {@code libmapclean_labels.so}, which is what actually removes 兴趣点 from the map.
 *
 * <h3>What the native side does</h3>
 * Its 高德 host hooks {@code AAssetManager_open} and intercepts exactly one asset family --
 * {@code map_assets/style_*X_Main} -- decompresses the style protobuf, rewrites the POI
 * features to invisible with {@code style::amap()}, and serves the filtered bytes onwards.
 * Roads, water, terrain and place names are untouched, which is why this is the correct
 * mechanism for 「去地图标注」 and a global label switch is not.
 *
 * <h3>Two independent things must both happen</h3>
 * <ol>
 *   <li>The library must be loaded <b>through this class loader</b>, because JNI resolves
 *       {@code Java_…_configure} against the loader that declared the native method.</li>
 *   <li>The framework must call the library's {@code native_init(entries)} so the inline-hook
 *       primitive gets bound. {@code configure()} alone cannot install anything without it --
 *       the native side reports {@code accepted=0} in that case (this used to be masked by a
 *       {@code return true} on the unbound path, which is why 地图标注 looked "installed"
 *       while doing nothing).</li>
 * </ol>
 *
 * <h3>Where the library actually lives (measured on device, 2026-10-12)</h3>
 * In an integrated (rootless) LSPatch build the module is a nested APK at
 * {@code assets/lspatch/modules/…} which the patcher unpacks to the host's cache. Two facts
 * were established by comparing that extracted copy with the embedded one byte for byte:
 * <ul>
 *   <li>the patcher <b>drops the {@code lib/} level</b>: our
 *       {@code lib/arm64-v8a/libmapclean_labels.so} becomes
 *       {@code arm64-v8a/libmapclean_labels.so};</li>
 *   <li>the module class loader only ever searches {@code <module.apk>!/lib/<abi>} plus
 *       {@code java.library.path}, so {@code System.loadLibrary} can never succeed there.</li>
 * </ul>
 * The previous revision therefore always ended at
 * {@code no packaged abi matched [arm64-v8a, armeabi-v7a, armeabi]} and the switch silently
 * did nothing. Both layouts are now accepted, and the host's own extracted native library
 * directory is preferred when it is known (see [start]).
 */
class NativeLabelAssets private constructor() {

    companion object {
        private const val TAG = "MapCleanLabels"
        private const val HOST = "com.autonavi.minimap"
        private const val LIBRARY = "mapclean_labels"
        private const val FILE = "lib$LIBRARY.so"

        /** {@code hidden} argument handed to the native side: 1 hides POI features, 0 leaves them. */
        private const val HIDE = 1
        private const val SHOW = 0

        // @JvmStatic + external puts these where JNI expects them: a private *static* native
        // method on this class (`Java_…_NativeLabelAssets_configure`). A companion-only native
        // would be mangled through `$Companion` and the .so would never bind.
        @JvmStatic
        private external fun configure(map: Int, hiddenMask: Int)

        @JvmStatic
        private external fun configureBaidu(originalPaths: Array<String>, filteredPaths: Array<String>): Boolean

        /** Kept for the full build, where the Baidu host is compiled in. */
        @JvmStatic
        fun prepareBaidu(originalPaths: Array<String>, filteredPaths: Array<String>): Boolean {
            if (!load()) return false
            try {
                return configureBaidu(originalPaths, filteredPaths)
            } catch (error: Throwable) {
                Log.w(TAG, "native exact-file interface unavailable", error)
                return false
            }
        }

        /**
         * Arm the label filter for one process, without a host {@code ApplicationInfo} yet.
         *
         * <p>Kept so the module entry point can call this by reflection at module-load time, when
         * the host Application does not exist. [start] re-runs it once the
         * host's native library directory is known.
         */
        @JvmStatic
        fun start(process: String) {
            start(process, null)
        }

        /**
         * Arm the label filter for one process.
         *
         * @param libraryDir the host's {@code ApplicationInfo.nativeLibraryDir}, or null when it is
         *                   not known yet. The host APK ships an extra copy of the library inside
         *                   {@code lib/<abi>/} so the platform extracts it there and the framework's
         *                   own {@code native_init} lookup can find it by name; that directory is
         *                   also the cheapest place for this class to load it from.
         */
        @JvmStatic
        fun start(process: String, libraryDir: String?) {
            if (HOST != process) return
            if (libraryDir != null && libraryDir.length > 0) nativeDirectory = libraryDir
            arm(true)
        }

        /**
         * Re-read the switch and hand the new state to the native side.
         *
         * <p>The panel and the hooks share one process in the integrated build, so a toggle can be
         * pushed straight into the running engine instead of waiting for a restart. The style
         * protobuf is only opened while the map is being set up, so an already-rendered map keeps
         * its current appearance until it is re-entered -- the panel says as much in its footer.
         */
        @JvmStatic
        fun refresh() {
            arm(false)
        }

        /** True when the library is in the process and the framework primitive has been bound. */
        @JvmStatic
        fun active(): Boolean {
            return loaded && appliedMask >= 0
        }

        private fun arm(force: Boolean) {
            // The POI switch lives in a different group per vendor (amap_enhancer_config vs
            // bmapclean_config), so the read goes through whichever panel this build ships.
            val mask = if (Vendor.baidu()) {
                if (BaiduPanel.mapPoiVisible()) SHOW else HIDE
            } else AmapPanel.mapPoiHiddenMask()
            // Loading is best-effort and separate on purpose: configure() must still run when the
            // library is already mapped, and a failed load must not be allowed to skip arming.
            load()
            if (!loaded) {
                Log.w(TAG, "label filter inactive: library not loaded (poi hidden=" + (mask != SHOW) + ")")
                return
            }
            askFramework()
            if (!force && mask == appliedMask) return
            try {
                configure(1, mask)
                appliedMask = mask
                Log.i(TAG, "label filter armed hide_poi=" + (mask != SHOW) + " from=" + loadedFrom)
            } catch (error: Throwable) {
                Log.w(TAG, "native style interface unavailable", error)
            }
        }

        /**
         * Ask the framework to dlopen the library and call its {@code native_init}.
         *
         * <p>{@code configure()} on its own cannot install anything: the asset interception is
         * built on the framework's inline-hook primitive, and {@code native_init(entries)} is the
         * only place that primitive is handed over. A normal LSPosed install finds the module
         * library by name, but an integrated LSPatch build cannot -- the patcher repacks the
         * nested module APK, moving {@code lib/<abi>/x.so} to {@code <abi>/x.so}, and no
         * name-based search path covers that layout. Measured on device, the library was never
         * loaded anywhere and the switch did nothing, silently.
         *
         * <p>{@code org.matrix.vector.nativebridge.NativeAPI.recordNativeEntrypoint} is the
         * framework's own entry point -- it is what the module loader calls for every line of
         * {@code META-INF/xposed/native_init.list} -- so the same library can be handed over here
         * by absolute path, which needs no APK surgery. A repeated call for a library the
         * framework already handled is harmless; the native side keeps the first hook it is given.
         */
        private fun askFramework() {
            if (frameworkAsked) return
            frameworkAsked = true
            val path = loadedPath ?: return     // found by name: the framework already did this
            try {
                val api = Class.forName("org.matrix.vector.nativebridge.NativeAPI")
                api.getMethod("recordNativeEntrypoint", String::class.java).invoke(null, path)
                Log.i(TAG, "framework asked to call native_init for $path")
            } catch (error: Throwable) {
                Log.i(TAG, "framework native entry point unavailable: $error")
            }
        }

        /** Load the library once; true when it ended up in the process. */
        private fun load(): Boolean {
            if (loaded) return true
            synchronized(NativeLabelAssets::class.java) {
                if (loaded) return true

                // 1) The linker's own search path. Works for a plain LSPosed install, where the
                //    library sits next to the module APK.
                try {
                    System.loadLibrary(LIBRARY)
                    loaded = true
                    loadedFrom = "linker"
                    Log.i(TAG, "library loaded from the host native path")
                    return true
                } catch (ignored: Throwable) {
                    // Expected in an integrated build; fall through to the explicit paths.
                }

                // 2) The host's extracted native library directory. The build injects a copy of the
                //    library into the patched host APK's lib/<abi>/ for exactly this reason: the
                //    platform extracts it, the app's namespace can dlopen it by name (which is what
                //    makes the framework call our native_init), and this class can load the very
                //    same file without unpacking anything.
                val directory = nativeDirectory
                if (directory != null && directory.length > 0) {
                    val candidate = File(directory, FILE)
                    if (candidate.isFile) {
                        try {
                            System.load(candidate.absolutePath)
                            loaded = true
                            loadedFrom = candidate.absolutePath
                            loadedPath = candidate.absolutePath
                            Log.i(TAG, "library loaded from the host native dir $candidate")
                            return true
                        } catch (error: Throwable) {
                            Log.w(TAG, "host native dir load failed", error)
                        }
                    } else {
                        Log.w(TAG, "host native dir has no " + FILE + ": " + candidate)
                    }
                }

                // 3) The module APK itself, for hosts whose ApplicationInfo is unavailable.
                try {
                    if (loadFromModuleApk()) {
                        loaded = true
                        return true
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "module library extraction failed", error)
                }
                return false
            }
        }

        /** Pull the library out of the module APK and load it by absolute path. */
        private fun loadFromModuleApk(): Boolean {
            val apkPath = moduleApkPath()
            if (apkPath == null) {
                Log.w(TAG, "module apk path not found")
                return false
            }
            val moduleApk = File(apkPath)

            // Extract into a sub-directory of the module's own folder rather than into the app's
            // cache: this can run from onModuleLoaded, before the host Application exists, so no
            // Context is available. The module APK's directory is already writable by this process.
            val directory = File(moduleApk.parentFile, "native")
            if (!directory.isDirectory && !directory.mkdirs()) {
                Log.w(TAG, "module native directory unavailable: $directory")
                return false
            }

            ZipFile(moduleApk).use { zip ->
                for (abi in supportedAbis()) {
                    val entryName = findEntry(zip, abi) ?: continue
                    val source: ZipEntry = zip.getEntry(entryName) ?: continue

                    val target = NativeLibraryCache.extract(zip, source.name, directory, abi)
                    System.load(target.absolutePath)
                    loadedFrom = entryName
                    loadedPath = target.absolutePath
                    Log.i(TAG, "library loaded from module apk entry=$entryName")
                    return true
                }
            }
            Log.w(TAG, "no packaged abi matched " + Arrays.toString(supportedAbis()) + " in " + apkPath)
            return false
        }

        /**
         * Locate the library inside the module APK, tolerating the two layouts that occur.
         *
         * <p>{@code lib/<abi>/} is the layout this project builds. LSPatch rewrites it to
         * {@code <abi>/} while handing the module to the host, and a future patcher version could
         * use something else again, so the last step simply accepts any path that names both the
         * ABI and the file.
         */
        private fun findEntry(zip: ZipFile, abi: String): String? {
            val packaged = "lib/$abi/$FILE"
            if (zip.getEntry(packaged) != null) return packaged
            val repacked = "$abi/$FILE"
            if (zip.getEntry(repacked) != null) return repacked
            // Java 的 ZipFile.entries() 在 Kotlin 里没被合成为属性（entries 与 stdlib 的
            // Map.entries 同名，合成会被遮蔽），必须显式调用。
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val name = en.nextElement().name
                if (name.endsWith("/$FILE") && name.contains(abi)) return name
            }
            return null
        }

        /** ABI candidates, most preferred first. */
        private fun supportedAbis(): Array<String> {
            val abis: Array<String>? = Build.SUPPORTED_ABIS
            return if (abis == null || abis.isEmpty()) arrayOf("arm64-v8a", "armeabi-v7a") else abis
        }

        /**
         * Where the module's dex was loaded from.
         *
         * <p>The class loader description carries it: Vector's module loader renders as
         * {@code VectorModuleClassLoader[module=/data/…/io.github.ldxm666.mapadkiller/<hash>.apk, …]}.
         */
        private fun moduleApkPath(): String? {
            try {
                val description = "" + NativeLabelAssets::class.java.classLoader
                val m = java.util.regex.Pattern
                    .compile("module=([^,\\]]+\\.apk)").matcher(description)
                if (m.find()) {
                    val file = File(m.group(1))
                    if (file.isFile) return file.absolutePath
                }
            } catch (ignored: Throwable) {
            }
            try {
                val source = NativeLabelAssets::class.java.protectionDomain.codeSource
                if (source != null && source.location != null) {
                    val path = java.net.URLDecoder.decode(source.location.path, "UTF-8")
                    if (path.endsWith(".apk") && File(path).isFile) return path
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        @Volatile
        private var nativeDirectory: String? = null

        @Volatile
        private var loaded = false

        @Volatile
        private var loadedFrom = ""

        /** Absolute path this class loaded the library from, or null when the linker found it. */
        @Volatile
        private var loadedPath: String? = null

        @Volatile
        private var frameworkAsked = false

        /** Mask handed to the native side, or -1 when it has never been armed. */
        @Volatile
        private var appliedMask = -1
    }
}
