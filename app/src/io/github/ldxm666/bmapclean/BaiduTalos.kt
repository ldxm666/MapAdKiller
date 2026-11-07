package io.github.ldxm666.bmapclean

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

/** Only verified page modules are adapted; built-ins and other Talos pages are untouched. */
class BaiduTalos private constructor() {

    companion object {

        private val paths: MutableMap<String, String> = HashMap()

        @JvmStatic
        fun install(cl: ClassLoader) {
            BaiduHooks.hook(H.cls(cl, "com.baidu.talos.core.runtime.TalosBaseRuntime"), "getModulePath",
                    XposedInterface.Hooker { chain ->
                        val path = chain.proceed() as String?
                        adapt(chain.getArg(0) as String?, path)
                    }, String::class.java)
            val pm = H.cls(cl, "com.baidu.talos.core.data.ParamMap")
            val callback = H.cls(cl, "com.baidu.talos.core.bridge.a")
            val bridge = H.cls(cl, "com.baidu.talos.core.bridge.TalosBridge")
            if (pm == null || callback == null) return
            for (name in arrayOf("loadJSFile", "preLoadJSFile")) {
                BaiduHooks.hook(bridge, name, XposedInterface.Hooker { chain ->
                    val map = chain.getArg(0)
                    // ParamMap 为 null 时 Method.invoke(null, ...) 会抛 NullPointerException；
                    // 这一条会被 mapclean.HookGuard 判死，于是"百度页面脚本适配"整个进程失效
                    // （用户侧看不到任何提示，只是推荐流/我的页突然恢复原样）。先原样放行。
                    if (map == null) return@Hooker chain.proceed()
                    val get = paramGet(pm)
                    val put = paramPut(pm)
                    if (get == null || put == null) return@Hooker chain.proceed()
                    val biz = get.invoke(map, "biz_pkg_name") as String?
                    val path = get.invoke(map, "biz_pkg_path") as String?
                    val replacement = adapt(biz, path)
                    if (replacement != null && replacement != path) put.invoke(map, "biz_pkg_path", replacement)
                    chain.proceed()
                }, pm, callback)
            }
        }

        /**
         * ParamMap.getString / putString 的 Method 缓存。
         *
         * <p>原来每次 {@code loadJSFile}/{@code preLoadJSFile} 都各查一次 {@code getMethod}
         * —— 那要遍历整张方法表并对每个参数类型做比较，而页面加载正是启动期热路径。
         *
         * <p>benign race：多个线程同时查就是查两次、结果一样，不加锁也不阻塞。缓存校验
         * {@code getDeclaringClass()==pm}，万一哪天解析到另一个同类名的 ParamMap 也不会串。
         */
        @Volatile
        private var GET_STRING: Method? = null

        @Volatile
        private var PUT_STRING: Method? = null

        private fun paramGet(pm: Class<*>): Method? {
            val m = GET_STRING
            if (m != null && m.declaringClass === pm) return m
            // 缓存不命中（或命中别的类）就重查一次。查不到必须返回 **null** 而不是把旧值
            // 带回去 —— 拿一个属于别的类的 Method 去 invoke 会抛 IllegalArgumentException，
            // 那又会把整条 hook 拖下水。
            try {
                val fresh = pm.getMethod("getString", String::class.java)
                GET_STRING = fresh
                return fresh
            } catch (ignored: Throwable) {
                return null
            }
        }

        private fun paramPut(pm: Class<*>): Method? {
            val m = PUT_STRING
            if (m != null && m.declaringClass === pm) return m
            try {
                val fresh = pm.getMethod("putString", String::class.java, String::class.java)
                PUT_STRING = fresh
                return fresh
            } catch (ignored: Throwable) {
                return null
            }
        }

        @Synchronized
        private fun adapt(module: String?, path: String?): String? {
            if (path == null || module == null) return path
            if ("userCore" != module && "userCenter" != module && "HomeFeed" != module) return path
            try {
                val requested = File(path)
                val source = File(requested.parentFile, "index.android.bundle")
                val canonical = source.canonicalPath
                if (!canonical.startsWith("/data/user/0/com.baidu.BaiduMap/files/talos/dpmbundles/")
                        && !canonical.startsWith("/data/data/com.baidu.BaiduMap/files/talos/dpmbundles/")) return path
                if (!source.isFile || source.length() > 4 * 1024 * 1024) return path
                val key = module + ":" + canonical + ":" + source.lastModified() + ":" + BaiduPageScripts.signature()
                val cached = paths[key]
                if (cached != null) return cached
                val original = read(source)
                val result = BaiduPageScripts.adapt(module, original)
                if (result == original) {
                    paths[key] = canonical
                    return canonical
                }
                val out = File(source.parentFile, "index.android.mapclean-210.bundle")
                val current = if (out.isFile) read(out) else null
                if (result != current) {
                    FileOutputStream(out).use { f -> f.write(result.toByteArray(charset("UTF-8"))) }
                }
                paths[key] = out.absolutePath
                H.log("talos adapted module=" + module + " bytes=" + result.toByteArray(charset("UTF-8")).size)
                return out.absolutePath
            } catch (t: Throwable) {
                H.log("talos adapter skipped " + module + ": " + t.javaClass.simpleName)
                return path
            }
        }

        private fun read(file: File): String {
            FileInputStream(file).use { input ->
                ByteArrayOutputStream().use { out ->
                    val b = ByteArray(65536)
                    var n: Int
                    while (true) {
                        n = input.read(b)
                        if (n == -1) break
                        out.write(b, 0, n)
                    }
                    return out.toString("UTF-8")
                }
            }
        }
    }
}
