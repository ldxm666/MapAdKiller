package io.github.ldxm666.mapadkiller

import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * LSPatch 覆盖安装首启：linker namespace 仍登记上一安装目录，导致原生库依赖
 * 查找失败并 System.exit(0)。仅在安装目录改变时按当前 PMS 路径预载依赖闭包。
 * Runtime.load0 的签名是 (Class, String)，传宿主 Class 才进入宿主 namespace；
 * 模块直接 System.load 会进入模块 namespace，无法修复宿主依赖。
 * 不清除缓存/账号/配置，不跳过宿主初始化，不拦截退出或伪造加载成功。
 */
object AmapLibDirFix {
    // 17.02.0.2010 原版 arm64 ELF 的 DT_NEEDED 拓扑序。
    // xquic 不属于 amaptts 闭包，且缺少 tb_ssl/tb_crypto，不能作为成功条件。
    private val DEPS = arrayOf(
        "c++_shared", "openssl", "zstd", "amapnsq", "amapkvd",
        "cJSON", "opus", "nui", "OSL_MNN"
    )
    @Volatile private var completed = false
    private var running = false

    @JvmStatic
    @Synchronized
    fun install(cl: ClassLoader) {
        if (completed || running || !HostInfo.isEmbedded()) return
        running = true
        try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: return
            val bound = H.getObjectField(thread, "mBoundApplication") ?: return
            val info = H.getObjectField(bound, "appInfo") as? android.content.pm.ApplicationInfo ?: return
            val actual = currentInfo(info) ?: return
            val dir = File(actual.nativeLibraryDir ?: return)
            if (!dir.isDirectory || DEPS.any { !File(dir, "lib$it.so").isFile }) return
            val marker = File(actual.dataDir, "cache/mapclean-native-dir-v2")
            if (marker.isFile && marker.readText() == dir.absolutePath) {
                completed = true
                return
            }
            val caller = cl.loadClass("com.amap.AppInterfaces")
            val load = Runtime::class.java.getDeclaredMethod("load0", Class::class.java, String::class.java)
            load.isAccessible = true
            val start = android.os.SystemClock.elapsedRealtime()
            for (name in DEPS) {
                load.invoke(Runtime.getRuntime(), caller, File(dir, "lib$name.so").absolutePath)
            }
            // 任一依赖失败会跳到 catch：不写标记，也不锁死下一次重试。
            marker.parentFile?.mkdirs()
            marker.writeText(dir.absolutePath)
            completed = true
            H.log("event=libdir_repaired count=${DEPS.size} ms=" +
                (android.os.SystemClock.elapsedRealtime() - start))
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.cause ?: error else error
            H.log(Log.WARN, MainHook.TAG, "event=libdir_repair_failed", cause)
        } finally {
            running = false
        }
    }

    /** 询问 PMS，避免 mBoundApplication 中覆盖安装首启的旧路径快照。 */
    private fun currentInfo(info: android.content.pm.ApplicationInfo): android.content.pm.ApplicationInfo? {
        val globals = Class.forName("android.app.AppGlobals")
        val pm = globals.getMethod("getPackageManager").invoke(null) ?: return null
        val user = android.os.Process.myUid() / 100000
        for (m in pm.javaClass.methods) {
            val types = m.parameterTypes
            if (m.name != "getApplicationInfo" || types.size != 3 || types[0] != String::class.java) continue
            try {
                val flags: Any = if (types[1] == Long::class.javaPrimitiveType) 0L else 0
                return m.invoke(pm, info.packageName, flags, user) as? android.content.pm.ApplicationInfo
            } catch (_: Throwable) { }
        }
        return null
    }
}
