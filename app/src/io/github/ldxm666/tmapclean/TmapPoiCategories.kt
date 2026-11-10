package io.github.ldxm666.tmapclean

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** A private derivative of Tencent's CURRENT style. Never edits the host's files.
 * The native stylization loader already supports per-point-style `base.disable`.
 * Both LSPatch and LSPosed use this same SDK path; no ABI offsets or fake overlays.
 */
object TmapPoiCategories {
    private class State {
        var context = WeakReference<Context>(null)
        var home = false
        var scene: Int? = null
        var original: String? = null
        var applied: String? = null
        var enabled: Float? = null
        var generation = 0
    }
    private val states = WeakHashMap<Any, State>()
    private val applying = ThreadLocal<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MapClean-Tencent-POI").apply { isDaemon = true }
    }
    private var catalogueKey = ""
    private var catalogue: Map<Int, Int> = emptyMap()
    private var catalogueHash = ""

    private fun state(controller: Any): State = synchronized(states) {
        states.getOrPut(controller) { State() }
    }

    @JvmStatic fun install(cl: ClassLoader) {
        val controller = H.cls(cl, "com.tencent.mapsdk2.internal.controllers.l")
        H.hookSig(controller, "setStylizationAssetPath", "tmap_poi_category_style",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (applying.get() == true) return chain.proceed()
                    val target = chain.getThisObject() ?: return chain.proceed()
                    val result = chain.proceed()
                    val s = state(target)
                    synchronized(s) {
                        s.original = chain.getArg(0) as? String
                        s.applied = s.original
                    }
                    Diag.note("poi_categories", "native_style_observed")
                    schedule(target, s)
                    return result
                }
            }, String::class.java)
        H.hookSig(controller, "setStylizationParam", "tmap_poi_category_enable",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (applying.get() != true && chain.getArg(0) == "enableStylizationConfig") {
                        val target = chain.getThisObject() ?: return result
                        val s = state(target)
                        synchronized(s) { s.enabled = (chain.getArg(1) as? Number)?.toFloat() }
                        schedule(target, s)
                    }
                    return result
                }
            }, String::class.java, java.lang.Float.TYPE)
        // Scene intent arrives before the SDK setter. In particular a navigation
        // style must not inherit the previous home scene during a view transition.
        val sdk = H.cls(cl, "com.tencent.mapsdk2.api.TencentMap") ?: return
        val manager = H.cls(cl, "com.tencent.tencentmap.mapstyle.MapStyleManager")
        H.hookSig(manager, "executeStylization", "tmap_poi_category_scene",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val map = chain.getArg(0)
                    val target = H.call(map, "getStylizationController")
                    if (target != null) {
                        val mapStates = H.field(chain.getThisObject(), "styleStateMap") as? Map<*, *>
                        val scene = H.call(mapStates?.get(map), "getMapMode") as? Int
                        val s = state(target)
                        synchronized(s) {
                            s.scene = scene
                            // The style manager owns this SDK directly. MapBaseView
                            // can attach before its scene-switch map is populated.
                            // Bind here too, using the exact scene intent from DEX.
                            if (scene != null) s.home = scene == 0 || scene == 36
                            io.github.ldxm666.mapclean.HostPrefs.hostContext()?.let {
                                s.context = WeakReference(it.applicationContext ?: it)
                            }
                        }
                    }
                    val result = chain.proceed()
                    if (target != null) schedule(target, state(target))
                    return result
                }
            }, sdk, String::class.java, Integer.TYPE)
    }

    @JvmStatic fun bind(sdk: Any, home: Boolean, context: Context) {
        val target = H.call(sdk, "getStylizationController") ?: return
        val s = state(target)
        val changed = synchronized(s) {
            val changed = s.home != home || s.context.get() == null
            s.home = home
            s.context = WeakReference(context.applicationContext ?: context)
            changed
        }
        if (changed) schedule(target, s)
    }

    @JvmStatic fun hiddenMask(): Int {
        var mask = 0
        for (i in TmapPoiStyles.KEYS.indices)
            if (!Cfg.visible(TmapPoiStyles.KEYS[i], true)) mask = mask or (1 shl i)
        return mask
    }

    private fun schedule(target: Any, s: State) {
        val original: String
        val context: Context
        val mask: Int
        val generation: Int
        synchronized(s) {
            generation = ++s.generation
            original = s.original?.takeIf { it.isNotEmpty() } ?: run {
                Diag.note("poi_categories", "waiting_native_style"); return
            }
            context = s.context.get() ?: run {
                Diag.note("poi_categories", "waiting_host_context"); return
            }
            val requested = hiddenMask()
            mask = if (s.home && (s.scene == null || s.scene == 0 || s.scene == 36)) requested else 0
            if (mask == 0 && s.applied == original) {
                Diag.note("poi_categories", "native_unchanged requested=$requested home=${s.home} scene=${s.scene}")
                return
            }
        }
        val weak = WeakReference(target)
        worker.execute {
            val desired = try {
                if (mask == 0) original else prepare(context, original, mask) ?: run {
                    Diag.note("poi_categories", "unsupported_style_native_kept mask=$mask")
                    original
                }
            } catch (error: Exception) {
                Diag.note("poi_categories", "native_style_kept=${error.javaClass.simpleName}")
                original
            }
            main.post {
                val controller = weak.get() ?: return@post
                synchronized(s) {
                    if (s.generation != generation || s.original != original || s.applied == desired) return@post
                    val setter = H.method(controller.javaClass, "setStylizationAssetPath", String::class.java) ?: return@post
                    val parameter = H.method(controller.javaClass, "setStylizationParam", String::class.java, java.lang.Float.TYPE)
                    val previous = applying.get()
                    applying.set(true)
                    try {
                        setter.invoke(controller, desired)
                        // Do not enable a style that Tencent disabled. Re-send only
                        // an observed native intent so the SDK reloads its new path.
                        s.enabled?.let { parameter?.invoke(controller, "enableStylizationConfig", it) }
                        s.applied = desired
                        Diag.note("poi_categories", if (desired == original) "native_restored" else "native_applied mask=$mask")
                        if (Cfg.debug()) Cfg.log("TMPoiCategories mask=$mask applied=${desired != original}")
                    } catch (error: Exception) {
                        Diag.note("poi_categories", "apply_failed=${error.javaClass.simpleName}")
                    } finally {
                        if (previous == null) applying.remove() else applying.set(previous)
                    }
                }
            }
        }
    }

    /** Runs on one worker. Source files and their resources are copied with bounds;
     * all style properties (including day/night, lighting and native disables) survive.
     */
    private fun prepare(context: Context, original: String, mask: Int): String? {
        val config = File(context.filesDir, "config/mapconfig_crc.bin")
        if (!config.isFile || config.length() !in 32..(8L * 1024 * 1024)) return null
        val bytes = config.readBytes()
        val digest = hash(bytes)
        val key = config.canonicalPath + ":" + digest
        if (key != catalogueKey) {
            catalogue = TmapPoiStyles.catalogue(bytes)
            catalogueHash = digest
            catalogueKey = key
            if (Cfg.debug()) Cfg.log("TMPoiCategories catalog_points=${catalogue.size}")
        }
        if (catalogue.isEmpty()) {
            Diag.note("poi_categories", "unsupported_catalog_native_kept")
            return null
        }
        val input = File(original).canonicalFile
        val source = if (input.isDirectory) input else input.parentFile ?: return null
        if (!source.isDirectory) return null
        val rootConfig = File(source, "stylization_config.json")
        if (!rootConfig.isFile || rootConfig.length() !in 2..(1024L * 1024)) return null
        val rootJson = TmapPoiStyles.parseStyle(rootConfig.readText())
        val layer = rootJson.optJSONObject("layers")?.optJSONObject("stylizd_mpcfg") ?: return null
        if (!layer.optBoolean("enable", false)) return null
        val relative = layer.optString("path")
        if (relative.isEmpty()) return null
        val style = File(source, relative).canonicalFile
        if (!style.path.startsWith(source.path + File.separator) || !style.isFile || style.length() !in 2..(2L * 1024 * 1024)) return null
        val filtered = TmapPoiStyles.filter(style.readText(), catalogue, mask) ?: return null
        val files = ArrayList<Pair<File, String>>()
        var total = 0L
        fun visit(dir: File, depth: Int) {
            require(depth <= 8)
            for (file in dir.listFiles() ?: throw IllegalArgumentException("unreadable_style")) {
                require(file.canonicalFile.path.startsWith(source.path + File.separator))
                if (file.isDirectory) visit(file, depth + 1)
                else {
                    require(file.isFile && files.size < 128)
                    total += file.length()
                    require(total <= 8L * 1024 * 1024)
                    files.add(file to file.relativeTo(source).path)
                }
            }
        }
        visit(source, 0)
        val stamp = source.path + "|" + mask + "|" + catalogueHash + "|" + filtered + "|" +
            files.sortedBy { it.second }.joinToString("|") { "${it.second}:${it.first.length()}:${it.first.lastModified()}" }
        val destination = File(context.cacheDir, "mapclean-poi-styles/" + hash(stamp.toByteArray(Charsets.UTF_8)))
        val marker = File(destination, ".ready")
        if (!marker.isFile) {
            require(destination.mkdirs() || destination.isDirectory)
            for ((file, relativeFile) in files) {
                val copy = File(destination, relativeFile)
                require(copy.parentFile?.mkdirs() == true || copy.parentFile?.isDirectory == true)
                if (file.canonicalFile == style) copy.writeText(filtered)
                else file.copyTo(copy, overwrite = true)
            }
            marker.writeText("1")
        }
        return if (input.isDirectory) destination.path + File.separator else File(destination, input.name).path
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @JvmStatic fun refresh() {
        val snapshot = synchronized(states) { states.entries.map { it.key to it.value } }
        if (snapshot.isEmpty()) Diag.note("poi_categories", "waiting_native_controller")
        for ((target, s) in snapshot) schedule(target, s)
    }
}
