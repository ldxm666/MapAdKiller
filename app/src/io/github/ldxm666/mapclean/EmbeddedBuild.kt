package io.github.ldxm666.mapclean

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * 判断某个宿主包是不是「免 root 内置版」（LSPatch 打过包的）。
 *
 * <h3>为什么需要它</h3>
 * 独立设置 App 里拨开关，写的是 <b>App 自己的沙箱</b>
 * （{@code /data/data/io.github.ldxm666.mapadkiller/shared_prefs/...}），
 * 而地图进程读的是 <b>宿主自己的沙箱</b>（{@code /data/data/<host>/shared_prefs/<vendor>clean_config.xml}）。
 * 两个包、两个 UID，永远读不到 —— 但 UI 是「先写先翻」，用户看到开关拨过去了，
 * 以为成功了。这就是「内置版开关无效 / 开关不匹配」的真正机制。
 *
 * <p>模块版（LSPosed）不存在这个问题：那时 App 写的是 RemotePreferences，
 * 宿主通过框架读同一张表。所以只有<b>内置版</b>要把页面锁成只读。
 *
 * <h3>怎么判</h3>
 * LSPatch 产物在 {@code assets/lspatch/} 下有自己的加载器、origin.apk 与 config.json
 * （本仓库 {@code tools/build_variant.py} 的三个常量 {@code assets/lspatch/config.json} /
 * {@code assets/lspatch/modules/} / {@code assets/lspatch/origin.apk} 就是同一份约定）。
 * 只要 {@code base.apk} 的 zip 目录里出现 [MARKER]，这个 App 就是内置版。
 *
 * <p>用的是 [ZipFile] —— 只读中央目录，<b>不解压</b> 180 MB 的包体，
 * 几十毫秒量级；而且整件事跑在后台线程，绝不占用主线程。
 *
 * <h3>失败必须 fail-open</h3>
 * 读不到（包没装、无可见性、IO 异常）一律返回 {@code false} = 按模块版处理 = <b>允许操作</b>。
 * 宁可让极少数内置版用户看到旧行为，也不能因为一次 IO 失败把正常用户的界面锁死。
 */
class EmbeddedBuild private constructor() {

    /** 结果回调；{@code embedded=false} 也包含「没测出来」的情况（fail-open）。 */
    fun interface Callback {
        fun onResult(embedded: Boolean)
    }

    companion object {
        private const val TAG = "MapCleanEmbeddedBuild"

        /** LSPatch 内置版的标志文件。存在即内置版。 */
        const val MARKER = "assets/lspatch/config.json"

        /** pkg → 结果。只缓存"测完了"的值，失败也是 false，不区分 —— 失败重测没有意义且会抖。 */
        private val CACHE = ConcurrentHashMap<String, Boolean>()

        /** pkg → 正在等结果的回调；同一个包并发探测时只起一个线程。 */
        private val PENDING = ConcurrentHashMap<String, MutableList<Callback>>()

        /** 已经测过的结果；{@code null} = 还没测。调用方据此决定要不要先按"未锁定"渲染。 */
        @JvmStatic
        fun cached(pkg: String?): Boolean? {
            return if (pkg == null) null else CACHE.get(pkg)
        }

        /**
         * 后台探测；结果可能在任意线程回调，调用方自己 post 回主线程。
         *
         * @param ctx 任意 Context（内部会取 application context）
         * @param pkg 宿主包名，例如 {@code com.tencent.map}
         */
        @JvmStatic
        fun detect(ctx: Context?, pkg: String?, cb: Callback?) {
            if (ctx == null || pkg == null || pkg.isEmpty()) {
                cb?.onResult(false)
                return
            }
            val hit = CACHE.get(pkg)
            if (hit != null) {
                cb?.onResult(hit)
                return
            }
            val mine = ArrayList<Callback>(2)
            if (cb != null) mine.add(cb)
            val running = PENDING.putIfAbsent(pkg, mine)
            if (running != null) {
                synchronized(running) { if (cb != null) running.add(cb) }
                return
            }
            var app: Context = ctx
            try {
                val c = ctx.applicationContext
                if (c != null) app = c
            } catch (ignored: Throwable) {
            }
            val probeCtx = app
            val worker = Thread(object : Runnable {
                override fun run() {
                    var value = false
                    try {
                        value = probe(probeCtx, pkg)
                    } catch (t: Throwable) {
                        // fail-open：读不到就当模块版，界面保持可操作
                        Log.w(TAG, "probe failed pkg=$pkg err=$t")
                        value = false
                    }
                    CACHE.put(pkg, value)
                    val waiters = PENDING.remove(pkg) ?: return
                    synchronized(waiters) {
                        for (i in waiters.indices) {
                            try {
                                waiters[i].onResult(value)
                            } catch (ignored: Throwable) {
                            }
                        }
                    }
                }
            }, "MapCleanEmbeddedBuild")
            worker.isDaemon = true
            worker.start()
        }

        /**
         * 同步探测 —— <b>只能在后台线程调用</b>。
         *
         * <p>先看 base.apk；base 里没有再看 split（少数渠道包会把资源拆出去）。
         * LSPatch 自己写的是 base，split 这一层只是别漏判。
         */
        @JvmStatic
        fun probe(ctx: Context?, pkg: String?): Boolean {
            if (ctx == null || pkg == null) return false
            val pm: PackageManager = ctx.packageManager
            val ai: ApplicationInfo
            try {
                ai = pm.getApplicationInfo(pkg, 0)
            } catch (t: Throwable) {
                // 没装 / 无包可见性声明 —— 都不是内置版
                return false
            }
            if (hasMarker(ai.sourceDir)) return true
            val splits = ai.splitSourceDirs
            if (splits != null) {
                for (i in splits.indices) {
                    if (hasMarker(splits[i])) return true
                }
            }
            return false
        }

        /** 只读 zip 中央目录，不解压。 */
        private fun hasMarker(path: String?): Boolean {
            if (path == null || path.isEmpty()) return false
            var zip: ZipFile? = null
            try {
                zip = ZipFile(path)
                return zip.getEntry(MARKER) != null
            } catch (t: Throwable) {
                Log.w(TAG, "zip read failed $path err=$t")
                return false
            } finally {
                if (zip != null) {
                    try {
                        zip.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
    }
}