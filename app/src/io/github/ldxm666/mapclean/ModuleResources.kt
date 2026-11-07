package io.github.ldxm666.mapclean

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log

import java.lang.reflect.Method

/**
 * Access to the module's own {@code res/} from inside a patched host.
 *
 * <h3>The problem</h3>
 * In an integrated (rootless) LSPatch build the module is an APK embedded at
 * {@code assets/lspatch/modules/} and loaded by its own class loader. Its resources are
 * <b>not</b> merged into the host, so every one of these returns nothing:
 *
 * <pre>
 *   host.getResources().getIdentifier("avatar_author", "drawable", hostPackage)   // 0
 *   host.getResources().getIdentifier("avatar_author", "drawable", modulePackage) // 0
 *   host.getApplicationInfo().loadIcon(...)   // the *host's* icon, not the module's
 * </pre>
 *
 * That last line is the trap: it silently succeeds and yields 高德's own launcher icon, so
 * the UI looks fine while showing the wrong picture entirely.
 *
 * <h3>The fix</h3>
 * Build a [Resources] over an [AssetManager] that has the module APK added as an
 * asset path, then resolve names against that. The module APK's location comes from this
 * class's own [java.security.CodeSource], which is where its dex was actually loaded
 * from -- no guessing at cache directories.
 *
 * <p>Everything here is best-effort and cached: if the module resources cannot be reached
 * the caller falls back to a drawn monogram, which is preferable to showing the host's icon.
 */
class ModuleResources private constructor() {

    companion object {
        private const val TAG = "MapCleanFloat"

        /** The package the module APK's resources.arsc was built with. */
        private const val MODULE_PACKAGE = "io.github.ldxm666.mapadkiller"

        @Volatile
        private var attempted: Boolean = false

        @Volatile
        private var resources: Resources? = null

        @Volatile
        private var sourcePath: String? = null

        @Volatile
        private var failure: String? = null

        /** The module's resources, or null when they could not be reached. */
        @JvmStatic
        fun get(host: Context): Resources? {
            if (!attempted) {
                synchronized(ModuleResources::class.java) {
                    if (!attempted) {
                        load(host)
                        attempted = true
                    }
                }
            }
            return resources
        }

        /**
         * The path of the module APK this class was loaded from, or null when it cannot be located.
         *
         * <p>Exposed for callers that need the module's own files without going through the resource
         * table -- [DefaultConfig] reads {@code res/raw/default_config.json} straight out of the
         * zip when the resource lookup does not resolve. Uses the same cached lookup as
         * [get], and falls back to a context-free discovery so a caller that has no
         * {@code Context} at hand (the framework service callback) still gets an answer.
         */
        @JvmStatic
        fun moduleApkPath(): String? {
            if (sourcePath == null) {
                val found = locateModuleApk()
                if (found != null) sourcePath = found
            }
            return sourcePath
        }

        private fun load(host: Context) {
            sourcePath = locateModuleApk()
            if (sourcePath == null) {
                failure = "module apk path not found; candidates=" + candidates()
                Log.w(TAG, "avatar: $failure")
                return
            }
            try {
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPath: Method = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                val cookie = addAssetPath.invoke(assets, sourcePath)
                if (cookie is Int && (cookie as Int) == 0) {
                    failure = "addAssetPath rejected $sourcePath"
                    Log.w(TAG, "avatar: $failure")
                    return
                }
                val hostRes = host.resources
                resources = Resources(assets, hostRes.displayMetrics, hostRes.configuration)
                Log.i(TAG, "avatar: module resources ready from $sourcePath")
            } catch (t: Throwable) {
                failure = t.javaClass.simpleName + ": " + t.message
                Log.w(TAG, "avatar: module resources unavailable ($failure)")
            }
        }

        /**
         * Where the module's dex was loaded from.
         *
         * <p>Tries, in order:
         * <ol>
         *   <li>this class's [java.security.CodeSource];</li>
         *   <li>the class loader description (LSPatch renders the dex path there);</li>
         *   <li>LSPatch's extraction directory under the host's cache, which is where the
         *       embedded module APK is unpacked before being loaded.</li>
         * </ol>
         */
        private fun locateModuleApk(): String? {
            // 1) Code source.
            try {
                val cs = ModuleResources::class.java.protectionDomain.codeSource
                if (cs != null && cs.location != null) {
                    var path: String? = cs.location.path
                    if (path != null) {
                        path = java.net.URLDecoder.decode(path, "UTF-8")
                        if (path.endsWith(".apk") && java.io.File(path).exists()) return path
                    }
                }
            } catch (ignored: Throwable) {
            }

            // 2) Class loader description.
            try {
                val description = "" + ModuleResources::class.java.classLoader
                val m = java.util.regex.Pattern
                    .compile("([^\\s\\[\\]\":]+\\.apk)").matcher(description)
                while (m.find()) {
                    val path = m.group(1)
                    if (java.io.File(path).exists()) return path
                }
            } catch (ignored: Throwable) {
            }

            // 3) LSPatch's extraction directory. java.io.tmpdir is not the app dir, so the folder
            //    is built from the process's own data path instead.
            for (dir in arrayOf("cache/lspatch/modules", "cache/lspatch", "files/lspatch")) {
                try {
                    val dataDir = dataDir() ?: break
                    val folder = java.io.File(dataDir, dir)
                    val files = folder.listFiles() ?: continue
                    for (f in files) {
                        if (f.name.endsWith(".apk") && f.name.contains("mapadkiller")) {
                            return f.absolutePath
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        /** The app's data directory, read from the process's own environment. */
        private fun dataDir(): String? {
            // /proc/self/cmdline gives the package; the data dir is /data/user/0/<pkg>.
            try {
                val reader = java.io.BufferedReader(java.io.FileReader("/proc/self/cmdline"))
                val cmdline = reader.readLine()
                reader.close()
                if (cmdline != null && cmdline.length > 0) {
                    val nul = cmdline.indexOf('\u0000')
                    val pkg = if (nul > 0) cmdline.substring(0, nul) else cmdline
                    return "/data/user/0/$pkg"
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        /** Candidate list, for the diagnostic line only. */
        private fun candidates(): String {
            val sb = StringBuilder()
            try {
                sb.append("loader=").append(ModuleResources::class.java.classLoader)
            } catch (ignored: Throwable) {
            }
            try {
                val cs = ModuleResources::class.java.protectionDomain.codeSource
                sb.append(" codeSource=").append(if (cs == null) "null" else cs.location)
            } catch (ignored: Throwable) {
            }
            sb.append(" dataDir=").append(dataDir())
            return sb.toString()
        }

        /** Load a drawable by name from the module's resources, or null. */
        @JvmStatic
        fun drawable(host: Context, name: String): Drawable? {
            val res = get(host) ?: return null
            try {
                var id = res.getIdentifier(name, "drawable", MODULE_PACKAGE)
                if (id == 0) id = res.getIdentifier(name, "drawable", null)
                if (id == 0) return null
                return res.getDrawable(id, host.theme)
            } catch (t: Throwable) {
                return null
            }
        }

        /** Diagnostic string for the one-shot log line. */
        @JvmStatic
        fun describe(): String {
            if (resources != null) return "ok path=" + sourcePath
            return "failed (" + failure + ") path=" + sourcePath
        }
    }
}