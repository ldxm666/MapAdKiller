package io.github.ldxm666.mapadkiller

import android.util.Log

import io.github.libxposed.api.XposedInterface

/**
 * 高德原生库预加载（修「安装包异常」弹窗）。
 *
 * 现象（真机 2026-10-27 13:24:16 抓到的完整栈）：
 *   voicesqure 初始化线程 → AudioPlayerManagerWrapper.switchAudioServicesPtr
 *   → AudioPlayerManager.<init> → AudioManagerAdapter.setDelegate
 *   → nativeSetDelegate 抛
 *   java.lang.UnsatisfiedLinkError: dlopen failed: library "libxquic.so" not found:
 *   needed by /data/app/.../lib/arm64/libamaptts.so in namespace clns-10
 * 高德崩溃兜底 kf3.uncaughtException → t30.showErrorActivity(Throwable) 只在
 * cause 链里出现 ClassNotFoundException / NoClassDefFoundError /
 * Resources.NotFoundException / UnsatisfiedLinkError 时才判 InstallError，
 * 于是起 com.autonavi.map.activity.ErrorActivity，标题 O450_8「安装包异常」。
 *
 * 结论：这不是签名校验，是原生库加载失败。修法是**提前、单线程**地把
 * libamaptts.so 用高德自己的 SoLoader 加载一次（它内部 ConcurrentHashMap
 * 记录已加载的库，后续调用直接 return），把这次加载从启动 3 秒后的多线程
 * 竞争里挪到 NewMapApplication.attachBaseContext 刚装好 SoLoader 的时刻。
 *
 * 任何失败只写日志，绝不抛出、绝不影响宿主。
 */
object AmapSoPreload {

    @Volatile
    private var sStarted = false

    @JvmStatic
    fun install(cl: ClassLoader) {
        try {
            val appInterfaces = H.cls(cl, "com.amap.AppInterfaces")
            val soLoader = H.cls(cl, "com.amap.main.api.ISoLoader")
            if (appInterfaces == null || soLoader == null) {
                H.log("event=so_preload_skip reason=class_missing")
                return
            }
            H.hookSig(appInterfaces, "setupSoLoader", "amap_so_setup",
                    object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            spawn(cl)
                            return result
                        }
                    }, soLoader)
            H.log("event=so_preload_hooked")
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=so_preload_hook_failed", error)
        }
    }

    private fun spawn(cl: ClassLoader) {
        if (sStarted) return
        sStarted = true
        try {
            val thread = Thread(object : Runnable {
                override fun run() {
                    load(cl, "amaptts")
                    load(cl, "xquic")
                }
            }, "mapclean-so-preload")
            thread.isDaemon = true
            thread.start()
        } catch (error: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=so_preload_thread_failed", error)
        }
    }

    private fun load(cl: ClassLoader, name: String) {
        val begin = System.currentTimeMillis()
        try {
            val appInterfaces = H.cls(cl, "com.amap.AppInterfaces")
            val soLoader = H.cls(cl, "com.amap.main.api.ISoLoader")
            if (appInterfaces == null || soLoader == null) return
            val loader = appInterfaces.getMethod("getSoLoader").invoke(null)
            if (loader == null) {
                H.log("event=so_preload name=" + name + " skipped=no_loader")
                return
            }
            soLoader.getMethod("loadLibrary", String::class.java).invoke(loader, name)
            H.log("event=so_preload name=" + name + " ok loader=" + loader.javaClass.name
                    + " ms=" + (System.currentTimeMillis() - begin))
        } catch (error: Throwable) {
            val cause = if (error.cause != null) error.cause!! else error
            H.log(Log.WARN, MainHook.TAG, "event=so_preload name=" + name + " failed err="
                    + cause.javaClass.simpleName + ":" + cause.message
                    + " ms=" + (System.currentTimeMillis() - begin))
        }
    }
}
