package io.github.ldxm666.bmapclean

import android.app.Application
import android.os.Bundle

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.TreeMap

/** Prepare individual STY tables and replace only their exact, read-only native
 * file opens. SDK configuration/resource roots and all original files stay intact.
 */
class BaiduPoiLayer private constructor() {

    companion object {

        private const val SCHEMA = "poi-file-assets-v9"
        private val STYLES = arrayOf("map.sty", "traffic.sty", "weakmap.sty", "reduct.sty", "weaktraffic.sty", "weakreduct.sty")
        private var cachedKey: String? = null
        private var cachedDirectory: File? = null
        private var cachedRules = 0
        private var cachedDrawings = 0
        private var failedLogged = false
        private var nativePrepared = false

        @JvmStatic
        fun install(cl: ClassLoader) {
            BaiduHooks.hook(H.cls(cl, "com.baidu.platform.comjni.map.basemap.AppBaseMap"), "initWithOptions", { chain ->
                val options = chain.getArg(0) as Bundle?
                // ★ 2.3.2：**先把宿主 Application 交给 HostPrefs，再算分类**。
                //
                // 真机实证（22.1.20 / pid 8347）：initWithOptions 在 01:51:56.498 就跑完了
                // 分类决策，而 Cfg 拿到宿主本地 prefs 是 01:51:59.598（cfg_snap n=43 backend=1）
                // —— 中间差 3.1 秒。这 3 秒里 Cfg.prefs() 只有 LSPatch 那个空表的只读 remote
                // 可读，于是每个键都 present=false、落回缺省 false = 隐藏。
                // 用户拨的逐类开关全部被忽略，界面表现就是「地图 POI 独立开关无效」。
                // 把 Application 提前交出去，local() 当场就能成功，读到的就是用户真正设的值。
                val app = currentApplication()
                if (app != null) {
                    io.github.ldxm666.mapclean.HostPrefs.remember(app)
                    // 丢掉「刚才本地拿不到」的探测结论 —— 否则 500ms 节流会把那次失败
                    // 继续当成 fallback 用，等于没修。
                    Cfg.resetBackend()
                }
                val hidden = hiddenCategories()
                val hide = hidden.isNotEmpty()
                var prepared = false
                if (hide && options != null && app != null) {
                    try {
                        prepared = prepare(options, app, hidden)
                    } catch (error: Throwable) {
                        disable()
                        if (!failedLogged) {
                            failedLogged = true
                            H.log("poi_style original_preserved reason=" + error.javaClass.simpleName)
                        }
                    }
                }
                if (!hide || !prepared) disable()
                H.log("poi_style policy=" + (if (hide) "hidden" else "visible") + " categories=" + hidden.size
                        + "/" + (BaiduLabelSettings.CATS.size + 1) + " original_bundle=true file_mapping=" + prepared
                        // backend / n 是这次修复的可观测出口：backend=1 且 n>0 才说明
                        // 「按用户配置做的决策」；backend=2 或 n=0 就是又读早了。
                        + " backend=" + Cfg.backend() + " host=" + (app != null))
                // The original Bundle, including every SDK root, is always passed once.
                val result = chain.proceed()
                H.log("poi_style initialized=" + java.lang.Boolean.TRUE.equals(result) + " redirected=false file_mapping=" + prepared)
                result
            }, Bundle::class.java, java.lang.Boolean.TYPE)
        }

        /**
         * 宿主 Application（可能为 null：initWithOptions 也可能在网络子进程里跑）。
         *
         * <p>走 {@code ActivityThread.currentApplication()} 而不是缓存 —— 这个方法在
         * 主进程第一次调用的时刻就一定有值，把它交给 {@link io.github.ldxm666.mapclean.HostPrefs}
         * 之后，{@code Cfg} 才能当场解析出宿主本地 prefs。
         */
        private fun currentApplication(): Application? {
            try {
                val app = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null)
                return if (app is Application) app else null
            } catch (ignored: Throwable) {
                return null
            }
        }

        /**
         * 当前要隐藏哪几类。
         *
         * <p>开关语义与其它页一致：prefs 里存的是「显示」，所以 {@code visible==false} = 隐藏。
         * 总开关 {@link Spec#K_MAP_POI} 关着 ⇒ 返回全集（等价 2.2.6 的一刀切）。
         * 总开关开着 ⇒ 逐类读；一类都没关 ⇒ 返回空集，调用方据此完全不装文件映射（零开销）。
         */
        private fun hiddenCategories(): Set<String> {
            val all = java.util.HashSet<String>()
            for (c in BaiduLabelSettings.CATS) all.add(c.key)
            all.add(BaiduLabelSettings.KEY_OTHER)
            if (!Cfg.visible(Spec.K_MAP_POI, Spec.defaultVisible(Spec.K_MAP_POI))) return all
            val out = java.util.HashSet<String>()
            for (c in BaiduLabelSettings.CATS) {
                if (!Cfg.visible(c.key, false)) out.add(c.key)
            }
            if (!Cfg.visible(BaiduLabelSettings.KEY_OTHER, false)) out.add(BaiduLabelSettings.KEY_OTHER)
            return out
        }

        private fun disable() {
            if (nativePrepared) io.github.ldxm666.mapclean.NativeLabelAssets.prepareBaidu(emptyArray<String>(), emptyArray<String>())
            nativePrepared = false
        }

        @Synchronized
        private fun prepare(options: Bundle, app: Application, hidden: Set<String>): Boolean {
            val cfg: String? = options.getString("cfgdataroot")
            if (cfg == null) {
                disable()
                return false
            }
            val source = File(cfg).canonicalFile
            if (!source.isDirectory) {
                disable()
                return false
            }
            val styles = ArrayList<File>()
            var main = false
            for (child in list(source)) {
                if (!mode(child)) continue
                for (name in STYLES) {
                    val style = File(child, name)
                    if (!style.isFile) continue
                    if (style.length() < 1 || style.length() > 16 * 1024 * 1024) throw java.io.IOException("Invalid STY size")
                    styles.add(style)
                    if ("map.sty" == name) main = true
                }
            }
            if (!main || styles.size > 128) throw java.io.IOException("Unsupported cfg style set")
            val key = sourceKey(styles, hidden)
            val base = File(app.codeCacheDir, "mcpoi").canonicalFile
            mkdir(base)
            val output = File(base, key.substring(0, 32))
            if (key != cachedKey || cachedDirectory == null || !complete(output, key, styles)) {
                if (!complete(output, key, styles)) {
                    // Source reads also use libc. Remove our old mapping before
                    // building a new generation so its input is always original STY.
                    disable()
                    val data = ArrayList<ByteArray>()
                    var rules = 0
                    var drawings = 0
                    for (style in styles) {
                        val result = BaiduLabelStyle.filterByCategories(read(style), hidden)
                        data.add(result.bytes)
                        rules += result.features
                        drawings += result.drawings
                    }
                    if (drawings == 0) throw java.io.IOException("No native point drawings")
                    val temporary = File(base, key.substring(0, 32) + "-" + android.os.Process.myPid() + "-" + System.nanoTime())
                    mkdir(temporary)
                    for (i in 0 until styles.size) {
                        val destination = File(temporary, relative(styles[i]))
                        mkdir(destination.parentFile)
                        write(destination, data[i])
                    }
                    if (key != sourceKey(styles, hidden)) throw java.io.IOException("Source STY changed during preparation")
                    write(File(temporary, ".complete"), (SCHEMA + "\n" + key + "\n" + styles.size + "\n" + rules + "\n" + drawings).toByteArray(StandardCharsets.UTF_8))
                    if (!temporary.renameTo(output) && !complete(output, key, styles)) throw java.io.IOException("Cannot publish STY cache")
                }
                if (!complete(output, key, styles)) throw java.io.IOException("Incomplete STY cache")
                val manifest = manifest(output)
                cachedRules = manifest[3].toInt()
                cachedDrawings = manifest[4].toInt()
                cachedKey = key
                cachedDirectory = output
                H.log("poi_style prepared themes=" + styles.size + " features=" + cachedRules + " drawings=" + cachedDrawings)
            }
            if (key != sourceKey(styles, hidden)) throw java.io.IOException("Source STY changed before initialization")
            val paths = TreeMap<String, String>()
            val resources = options.getString("stylerespath")
            for (style in styles) {
                val destination = File(cachedDirectory!!, relative(style)).absolutePath
                add(paths, style, destination, style)
                add(paths, File(cfg, relative(style)), destination, style)
                if (resources != null) add(paths, File(resources, relative(style)), destination, style)
                val canonical = style.canonicalPath
                val data = "/data/data/" + MainHook.PKG_BMAP + "/"
                val user = "/data/user/0/" + MainHook.PKG_BMAP + "/"
                if (canonical.startsWith(data)) add(paths, File(user + canonical.substring(data.length)), destination, style)
                else if (canonical.startsWith(user)) add(paths, File(data + canonical.substring(user.length)), destination, style)
            }
            val accepted = io.github.ldxm666.mapclean.NativeLabelAssets.prepareBaidu(
                    paths.keys.toTypedArray(), paths.values.toTypedArray())
            nativePrepared = accepted
            if (!accepted) throw java.io.IOException("Native exact-file interface unavailable")
            return true
        }

        private fun add(paths: MutableMap<String, String>, alias: File, destination: String, original: File) {
            if (alias.isFile && alias.canonicalFile == original.canonicalFile)
                paths.put(alias.absolutePath, destination)
        }

        private fun relative(style: File): String {
            return style.parentFile.name + "/" + style.name
        }

        private fun complete(directory: File, key: String, styles: List<File>): Boolean {
            try {
                val manifest = manifest(directory)
                if (manifest.size != 5 || SCHEMA != manifest[0] || key != manifest[1]
                        || manifest[2].toInt() != styles.size || manifest[3].toInt() < 1 || manifest[4].toInt() < 1) return false
                for (style in styles) {
                    val file = File(directory, relative(style))
                    if (!file.isFile || file.length() < 1 || file.length() > 16 * 1024 * 1024
                            || file.canonicalFile != file.absoluteFile) return false
                }
                return true
            } catch (error: Exception) {
                return false
            }
        }

        private fun manifest(directory: File): Array<String> {
            val marker = File(directory, ".complete")
            if (!marker.isFile || marker.length() > 256) throw java.io.IOException("Invalid STY manifest")
            return String(read(marker), StandardCharsets.UTF_8).split("\n").toTypedArray()
        }

        /**
         * 缓存键 = SCHEMA + 每个源 STY 的 路径/大小/mtime + **当前隐藏的分类集合**。
         *
         * <p>最后一项是 2.2.7 补的。以前键里只有源文件，于是「改了开关」和「没改」算出来的键
         * 一模一样，直接复用旧缓存 —— 表现就是「开关拨了没反应」。分类按名字排序后参与摘要，
         * 集合不变则键不变，变了必然重建。
         */
        private fun sourceKey(styles: List<File>, hidden: Set<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fingerprint(digest, SCHEMA)
            val cats = ArrayList<String>(hidden)
            java.util.Collections.sort(cats)
            for (cat in cats) fingerprint(digest, "cat=" + cat)
            for (style in styles) fingerprint(digest, style.canonicalPath + ":" + style.length() + ":" + style.lastModified())
            val out = StringBuilder()
            val hex = "0123456789abcdef".toCharArray()
            for (b in digest.digest()) {
                out.append(hex[(b.toInt() and 255) ushr 4])
                out.append(hex[b.toInt() and 15])
            }
            return out.toString()
        }

        private fun fingerprint(digest: MessageDigest, value: String) {
            digest.update(value.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }

        private fun list(directory: File): Array<File> {
            val out = directory.listFiles()
            if (out == null || out.size > 1024) throw java.io.IOException("Unsupported cfg directory")
            Arrays.sort(out, Comparator<File> { a, b -> a.name.compareTo(b.name) })
            return out
        }

        private fun mode(file: File): Boolean {
            val name = file.name
            if (!file.isDirectory || !name.startsWith("mode_") || name.length == 5) return false
            for (i in 5 until name.length) if (name[i] < '0' || name[i] > '9') return false
            return true
        }

        private fun read(file: File): ByteArray {
            FileInputStream(file).use { input ->
                val out = ByteArrayOutputStream(file.length().toInt())
                val buffer = ByteArray(8192)
                var count: Int
                while (true) {
                    count = input.read(buffer)
                    if (count == -1) break
                    if (out.size() + count > 16 * 1024 * 1024) throw java.io.IOException("STY size changed")
                    out.write(buffer, 0, count)
                }
                return out.toByteArray()
            }
        }

        private fun write(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.getFD().sync()
            }
        }

        private fun mkdir(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Cannot create STY cache")
        }
    }
}
