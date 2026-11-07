package io.github.ldxm666.mapadkiller

import android.util.Log

import io.github.libxposed.api.XposedInterface

import java.io.File
import java.lang.reflect.Method

/**
 * 「安装包异常」第二修：**覆盖安装后第一次冷启动**的 linker 命名空间库目录错位。
 *
 * <h3>现象与根因（真机证据）</h3>
 * 免 root 内置版每次覆盖安装后第一次冷启动，高德必弹「安装包异常 / …去下载…」，
 * 第二次起正常。真机 13:36:39 与 13:45:57 两次复现的日志完全同构：
 * <pre>
 *   13:45:57.260 nativeloader: Configuring clns-10 for other apk
 *                  /data/user/0/com.autonavi.minimap/cache/lspatch/origin/4187910933.apk
 *                  library_path=/data/app/~~OkjFNN…/lib/arm64      ← 上一版的目录（已被删）
 *   13:45:57.402 MapAdKiller: event=libdir_probe used=/data/app/~~4tQRD8…/lib/arm64
 *   13:45:57.4xx dlopen failed: library "libamapkvd.so" not found:
 *                  needed by /data/app/~~4tQRD8…/lib/arm64/libamaptts.so in namespace clns-10
 * </pre>
 * 即：**应用侧拿到的是新目录**（libamaptts.so 确实是从新目录打开的），但 LSPatch 建的
 * 那个 linker namespace（clns-10）里登记的搜索路径还是**上一版**的目录，于是
 * libamaptts.so 的 DT_NEEDED 一个都找不到 → UnsatisfiedLinkError → 高德兜底的
 * `kf3.uncaughtException` → `t30.showErrorActivity` 判成 InstallError →
 * 起 ErrorActivity（就是那个「安装包异常」黑框）。
 *
 * <h3>为什么 AmapSoPreload 修不了这一次</h3>
 * AmapSoPreload 挂在 `AppInterfaces.setupSoLoader`，而它由
 * `NewMapApplication.attachBaseContext` 调用；这次崩溃发生在 **Application
 * 之前**（ContentProvider 初始化阶段），日志里只有 `event=so_preload_hooked`
 * 而没有任何 `event=so_preload …` 行，说明 setupSoLoader 压根没被调用。
 *
 * <h3>修法</h3>
 * 在模块加载的瞬间（比 ContentProvider 早）用**高德自己的 ISoLoader**
 * （`AppInterfaces.getSoLoader()` → `p000.t46.loadLibrary(String)`）把
 * libamaptts.so 依赖的整个闭包按依赖顺序先装一遍。t46 内部是 `System.loadLibrary`，
 * 调用方是高德自己的类 ⇒ 落进的正是 clns-10，DT_NEEDED 之后全部命中已加载的 so。
 *
 * <p>注意两个"不能"：
 * <ul>
 *   <li>不能用 `System.load(绝对路径)` —— 那会落进模块自己的 namespace，
 *       宿主导入时依然看不到（bionic 只在本 namespace 内按 soname 找已加载库）。</li>
 *   <li>不能用 `Runtime.load0(ClassLoader,String)` —— Android 16 上这个方法
 *       不存在（真机实测 NoSuchMethodException），而且它拿不到应用 namespace。</li>
 * </ul>
 *
 * <p>可重入/幂等：bionic 按 soname 去重，已加载的库再 loadLibrary 只会返回旧 handle，
 * 不会二次 JNI_OnLoad；因此健康启动时这一发只是把高德本来就要做的事提前几十毫秒，
 * 对用户零副作用。任何异常只写日志，绝不抛出。
 */
object AmapLibDirFix {

    /**
     * libamaptts.so 的 DT_NEEDED 闭包（build/…/lib/arm64-v8a 里的库，按依赖顺序，
     * 不带 lib 前缀/后缀 —— 直接喂给 System.loadLibrary）。系统库
     * （libdl/liblog/libz/libm/libc/libandroid）永远能解析，不在此列。
     */
    private val DEPS = arrayOf(
            "c++_shared", "openssl", "zstd", "amapnsq", "amapkvd",
            "xquic", "cJSON", "opus", "nui", "OSL_MNN"
    )

    /** 已**成功**完成一次闭包预加载。注意语义：不是"已经试过"—— 试失败必须允许重试。 */
    @Volatile
    private var sStarted = false

    /** `AppInterfaces.setupSoLoader` 的补装钩子只挂一次（同一个 id 不能重复 hook）。 */
    @Volatile
    private var sRetryHooked = false

    /** 同步执行：必须赶在 ContentProvider 之前，所以**不开线程**。总耗时约 30-60ms。 */
    @JvmStatic
    fun install(cl: ClassLoader) {
        if (sStarted) return
        try {
            probe(cl)
            val loader = soLoader(cl)
            // ⚠ 2.3.0 修白屏：原来 `sStarted = true` 抢在 `soLoader()` 之前，
            // 于是只要第一次拿不到高德自己的 ISoLoader（Application 还没建 / 类还没加载完），
            // 就打一条 `event=libdir_skip reason=no_loader` 然后**永久放弃** ——
            // 后续每一次调用都被首行的 `if (sStarted) return;` 挡掉。
            //
            // 后果：LSPatch 建的 linker namespace（clns-10）里登记的搜索路径仍指向**上一版**
            // 的 lib/arm64，`libamaptts.so` 的 DT_NEEDED 一个都找不到 →
            //   dlopen failed: library "libamapkvd.so" not found
            // → UnsatisfiedLinkError → 高德 kf3.uncaughtException → t30.showErrorActivity
            //   判成 InstallError → 起 ErrorActivity（「安装包异常」黑框），或被兜底成白屏。
            //
            // 这正是「覆盖安装后首次冷启动」的窗口，也正是本修复唯一要覆盖的那一次。
            // ⇒ 上锁必须**晚于**拿到 loader：拿不到就只记日志、保持未上锁。
            //
            // ⚠ 光"不上锁"不够。全树对 install() 只有两个调用点，两个都在 onPackageReady：
            //   AmapHooks 的 "libdir_fix" feature，和 mapadkiller.MainHook 的 Baseline 透传分支。
            //   onPackageReady 时宿主 Application 还没建 ⇒ soLoader() 必然为 null
            //   ⇒ 所谓"下一次调用"根本不存在，白屏照旧。所以第二次机会得由本类自己安排：
            //   挂 setupSoLoader(ISoLoader) + Application.attachBaseContext(Context)，两枚都是
            //   proceed() 之后再补装，谁先到谁做（见 armRetry）。
            if (loader == null) {
                if (sRetryHooked) {
                    H.log("event=libdir_skip reason=no_loader retry=waiting")
                } else {
                    H.log(Log.WARN, MainHook.TAG, "event=libdir_skip reason=no_loader retry=armed")
                    armRetry(cl)
                }
                return
            }
            // 拿到 loader 才算真的开始做。此后不重入 —— bionic 按 soname 去重，重入本身无害，
            // 但没必要重复跑 10 次 invoke。
            preload(loader)
            // 只有真的跑完闭包才置位：失败保持未上锁，窗口内再被调到时还能再试。
            sStarted = true
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_failed", error)
        }
    }

    /** 把 DEPS 闭包按依赖顺序灌进高德自己的 SoLoader（内部按 soname 去重，可重入）。 */
    private fun preload(loader: Any) {
        val loadLibrary: Method = loader.javaClass.getMethod("loadLibrary", String::class.java)
        loadLibrary.isAccessible = true
        var ok = 0
        val begin = System.currentTimeMillis()
        for (name in DEPS) {
            val one = System.currentTimeMillis()
            try {
                loadLibrary.invoke(loader, name)
                ok++
                H.log("event=libdir_dep name=" + name + " ok ms=" + (System.currentTimeMillis() - one))
            } catch (error: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=libdir_dep name=" + name + " failed", unwrap(error))
            }
        }
        H.log("event=libdir_preload_done ok=" + ok + "/" + DEPS.size
                + " ms=" + (System.currentTimeMillis() - begin))
    }

    /**
     * 第一次失败后的第二次机会：挂两枚"晚一点"的钩子，谁先到谁补装。
     *
     * <h3>为什么需要两枚</h3>
     * <ol>
     *   <li><b>`com.amap.AppInterfaces.setupSoLoader(ISoLoader)`</b> —— 语义最准：
     *       它由 `NewMapApplication.attachBaseContext` 调用，触发时 SoLoader 一定装好了。
     *       但本类注释里记录过的那次崩溃，日志里**没有** `event=so_preload` 行，
     *       说明真机上它未必赶在第一次 dlopen 之前（高德自己的 provider 期初始化就可能已经
     *       用过 libamaptts）。只押它一枚不够。</li>
     *   <li><b>`android.app.Application.attachBaseContext(Context)`</b> —— 兜底：
     *       框架保证每个进程都会调用一次，且我们跑在 `chain.proceed()` **之后**，
     *       宿主自己的 attachBaseContext 体已经执行完 —— 若它把 super 放在末尾，SoLoader
     *       此刻就绪。两枚都成功后 [sStarted] 置位，第二枚触发时只是一次布尔读。</li>
     * </ol>
     *
     * <h3>为什么同步、为什么只挂一次</h3>
     * 同步：晚了就没意义 —— 高德第一次 dlopen（voicesqure 线程 → libamaptts.so）发生在
     * attachBaseContext 之后约 3 秒，补装必须抢在它前面。只挂一次：同一个 hook id 不能重复
     * 注册，[sRetryHooked] 兜住。
     *
     * <p>任何失败只写日志，绝不抛出 —— 这条路径不能把宿主拖下水。
     */
    private fun armRetry(cl: ClassLoader) {
        if (sRetryHooked) return
        sRetryHooked = true
        var armed = 0
        try {
            val api = H.cls(cl, "com.amap.AppInterfaces")
            val iso = H.cls(cl, "com.amap.main.api.ISoLoader")
            if (api != null && iso != null) {
                H.hookSig(api, "setupSoLoader", "libdir.retry.so", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try {
                            install(cl)
                        } catch (ignored: Throwable) {
                        }
                        return result
                    }
                }, iso)
                armed++
            } else {
                H.log(Log.WARN, MainHook.TAG, "event=libdir_retry_skip reason=amap_class_missing")
            }
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_retry_failed what=setupSoLoader", error)
        }
        try {
            H.hookSig(android.app.Application::class.java, "attachBaseContext", "libdir.retry.app",
                    object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            try {
                                install(cl)
                            } catch (ignored: Throwable) {
                            }
                            return result
                        }
                    }, android.content.Context::class.java)
            armed++
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_retry_failed what=attachBaseContext", error)
        }
        H.log("event=libdir_retry_armed hooks=" + armed)
    }

    /** 只读诊断：把三个来源的库目录各打一行，方便对比"应用侧新目录 vs 出问题的 namespace 旧目录"。 */
    private fun probe(cl: ClassLoader) {
        try {
            val at = H.cls(cl, "android.app.ActivityThread")
            val thread = if (at == null) null else at.getMethod("currentActivityThread").invoke(null)
            val bind = if (thread == null) null else H.getObjectField(thread, "mBoundApplication")
            val appInfo = if (bind == null) null else H.getObjectField(bind, "appInfo")
            val loadedApk = if (bind == null) null else H.getObjectField(bind, "info")
            val used = if (appInfo == null) null else H.getObjectField(appInfo, "nativeLibraryDir") as String?
            var appCl: ClassLoader? = null
            if (loadedApk != null) {
                try {
                    appCl = loadedApk.javaClass.getMethod("getClassLoader").invoke(loadedApk) as ClassLoader?
                } catch (ignored: Throwable) {
                }
            }
            H.log("event=libdir_probe used=" + used + " pms=" + pmsDir(appInfo) + " java=" + javaLibDirs(appCl))
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_probe_failed", error)
        }
    }

    /** 高德自己的 ISoLoader（`com.amap.AppInterfaces.getSoLoader()`）。 */
    private fun soLoader(cl: ClassLoader): Any? {
        try {
            val api = H.cls(cl, "com.amap.AppInterfaces") ?: return null
            return api.getMethod("getSoLoader").invoke(null)
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=libdir_loader_failed", error)
            return null
        }
    }

    /** PackageManager 的权威 nativeLibraryDir（直接问 PMS，不受绑定时的快照影响）。 */
    private fun pmsDir(appInfo: Any?): String? {
        val pkg = if (appInfo == null) null else H.getObjectField(appInfo, "packageName") as String?
        if (pkg == null) return null
        try {
            val globals = Class.forName("android.app.AppGlobals")
            val ipm = globals.getMethod("getPackageManager").invoke(null) ?: return null
            val userId = android.os.Process.myUid() / 100000
            for (method in ipm.javaClass.methods) {
                if ("getApplicationInfo" != method.name) continue
                val types = method.parameterTypes
                if (types.size != 3 || types[0] != String::class.java) continue
                try {
                    val arg: Any = if (types[1] == Long::class.javaPrimitiveType) java.lang.Long.valueOf(0L) else java.lang.Integer.valueOf(0)
                    val info = method.invoke(ipm, pkg, arg, java.lang.Integer.valueOf(userId))
                    val dir = if (info == null) null else H.getObjectField(info, "nativeLibraryDir") as String?
                    if (dir != null) return dir
                } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}
        return null
    }

    /** `BaseDexClassLoader.pathList.nativeLibraryDirectories`。 */
    private fun javaLibDirs(loader: ClassLoader?): MutableList<String> {
        val out = ArrayList<String>()
        if (loader == null) return out
        try {
            val pathList = H.getObjectField(loader, "pathList")
            val dirs = H.getObjectField(pathList, "nativeLibraryDirectories")
            if (dirs is Array<*>) {
                for (dir in dirs) {
                    val text = dir.toString()
                    if (text.isNotEmpty() && !out.contains(text)) out.add(text)
                }
            }
        } catch (ignored: Throwable) {}
        return out
    }

    private fun unwrap(error: Throwable?): Throwable {
        if (error is java.lang.reflect.InvocationTargetException && error.cause != null) {
            return error.cause!!
        }
        return error ?: IllegalStateException("null")
    }

    /** 保留：某些 ROM 上 File 判存在比 loadLibrary 更快失败，便于日志自证。 */
    private fun exists(dir: String?, name: String): Boolean {
        return dir != null && File(dir, "lib" + name + ".so").isFile
    }
}
