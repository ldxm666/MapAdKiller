package io.github.ldxm666.mapadkiller

import android.content.Context
import android.util.Log

import java.io.File

import io.github.libxposed.api.XposedInterface

/**
 * 高德开屏广告的**深度清除**（17.02.0.2010 CN / Play 17.02.2.2014）。
 *
 * <h3>与 [AmapAlimama] 的分工</h3>
 * `AmapAlimama` 管两条已验证通道：阿里妈妈请求 `sendSplashScreen` 与就绪位 `y96.g`。
 * 本文件补的是**同一份 DEX 里另外四条**能把广告送上屏的路径。两者共用
 * `alimama_splash_off`（默认开），显式关闭仍然整体不装。
 *
 * <h3>DEX 证据（classes5 / classes6，17.02.0.2010，逐条 smali 核对）</h3>
 *
 * 1. **素材请求源头** `Lah5;->attemptRequestData(I)V` 与 `(IZ)V`。
 *    字符串自证：`SplashRequester`、`ADSwitchValue`、`OAIDSwitchValue`、`last_shown_ad`、
 *    `last_shown_ts`、`min_req_interval_second`、`不发送请求：请求次数已达上限`。
 *    调用者：`SplashChildProcessVApp.a(String)`、`com.autonavi.minimap.g.c(...)`、
 *    `rv5.run()`、`mc1.run()`。⇒ 拦住这两个方法，**任何调用者都发不出开屏素材请求**，
 *    包括子进程 VApp 与云控触发的补量；素材不落地，后面的缓存也就无米下锅。
 *
 * 2. **实时开屏** `LSplashScreenServiceImpl;->a()V`（public static）→ `Lyc5;->run()V`。
 *    `yc5` 自己 `new URL(...).openConnection()` 后 POST
 *    `https://amap-aos-info-nogw.amap.com/ws/aos/alimama/splash_screen_rt?ent=2`
 *    （另有 pre / test 两个 host 分支），**不经 AOS，也不经 `AlimamaRequestHolder`**
 *    ⇒ 旧的 `sendSplashScreen` hook 在结构上就看不到它。
 *    `fetchRealTime()` 只是 `a()` 的实例包装，两者一起拦。
 *
 * 3. **渲染器** `LImageSplashView;->getSplashView(Landroid/content/Context;Lk66;LIEventListener;)Landroid/view/View;`
 *    与 `LVideoSplashView;->getSplashView(...)`。返回 null 时 `Lv76;->run()V` 走
 *    `g.l(k66)` + `e(SplashFinishReason.ERROR)` 直接收尾，不会停在开屏页。
 *    ⇒ 最后一道网：前面全被绕过，广告也长不出视图。品牌页是主题 `windowBackground`
 *    与 `BaseSplashActivity`，不经过这两个方法，因此不受影响。
 *
 * 4. **已落地素材** `files/splash/i_*`（图片）与 `v_*`（视频）。
 *    真机实测：模块拦了请求之后，目录里仍有 5 张图 + 5 段视频（64 KB–5.5 MB，多来自
 *    更早版本的云控静默下载）。开屏是否上屏取决于就绪位，但「完全抹除」要求素材本身
 *    不留在盘上 —— 每进程一次，后台线程删，只删 `i_*` / `v_*`，不动配置与目录结构。
 *
 * <h3>刻意不碰</h3>
 * 品牌开屏页、首页与 POI、埋点、`SplashFrequencyController`（频控拦了会连品牌页一起没）、
 * `canShowSplash`（2.3.0 真机实测：钉死它 = 首帧无承载 = 白屏 30 秒）。
 */
object AmapSplashKill {

    /** 开屏素材请求器（`SplashRequester`）。 */
    private val REQUESTERS = arrayOf("ah5", "ys5")

    /** 开屏服务实现（持有实时请求与 `tryShowSplashView`）。 */
    private const val CLS_SERVICE = "com.autonavi.minimap.impl.SplashScreenServiceImpl"

    /** 实时开屏的裸 HttpURLConnection Runnable。 */
    private val REALTIME_TASKS = arrayOf("yc5", "wo5")

    private const val CLS_IMAGE = "com.autonavi.minimap.view.ImageSplashView"
    private const val CLS_VIDEO = "com.autonavi.minimap.view.VideoSplashView"

    /** 广告素材模型与点击监听：只用来定位 `getSplashView` 的精确签名。 */
    private val ITEM_NAMES = setOf("k66", "fj6")
    private const val CLS_LISTENER = "com.autonavi.minimap.listener.IEventListener"

    /** 每进程一次：同一 id 只打一条日志，开屏热路径上不重复拼串。 */
    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    @JvmStatic
    fun install(cl: ClassLoader) {
        installRequester(cl)
        installRealtime(cl)
        installRenderer(cl)
        schedulePurge()
        H.log(Log.INFO, MainHook.TAG,
                "event=splash_kill_installed requester+realtime+renderer+purge")
    }

    // ─────────────────────────────────────────────── 1. 素材请求源头

    private fun installRequester(cl: ClassLoader) {
        val c = REQUESTERS.mapNotNull { H.cls(cl, it) }.firstOrNull { requesterShape(it) }
        if (c == null) {
            H.log(Log.WARN, MainHook.TAG, "event=splash_kill_miss anchor=SplashRequester")
            return
        }
        H.log("event=splash_requester_anchor class=" + c.name)
        // 两个重载都要拦：(I)V 是直接请求，(IZ)V 是带 force 的云控补量入口。
        H.hookSig(c, "attemptRequestData", "amap_splash_requester_int",
                block("SplashRequester.attemptRequestData(I)"), Int::class.javaPrimitiveType)
        H.hookSig(c, "attemptRequestData", "amap_splash_requester_int_bool",
                block("SplashRequester.attemptRequestData(I,Z)"),
                Int::class.javaPrimitiveType, java.lang.Boolean.TYPE)
    }

    // ─────────────────────────────────────────────── 2. 实时开屏请求

    private fun installRealtime(cl: ClassLoader) {
        val service = H.cls(cl, CLS_SERVICE)
        if (service != null) {
            // public static a()V：拼参数 + amapEncodeV2 + 丢进 AsyncTask 线程池。
            H.hookSig(service, "a", "amap_splash_realtime_static",
                    block("SplashScreenServiceImpl.a()"))
            // 实例包装，供 ISplashScreenService 调用者使用。
            H.hookSig(service, "fetchRealTime", "amap_splash_realtime",
                    block("SplashScreenServiceImpl.fetchRealTime()"))
        } else {
            H.log(Log.WARN, MainHook.TAG, "event=splash_kill_miss anchor=SplashScreenServiceImpl")
        }
        // 兜底：即使有第三条路把 Runnable 直接丢进线程池，URL 也发不出去。
        val runnable = REALTIME_TASKS.mapNotNull { H.cls(cl, it) }.firstOrNull { realtimeShape(it) }
        if (runnable != null) {
            H.log("event=splash_realtime_anchor class=" + runnable.name)
            H.hookSig(runnable, "run", "amap_splash_realtime_run", block(runnable.name + ".run()"))
        } else {
            H.log("event=splash_kill_miss anchor=realtime_task native_kept=true")
        }
    }

    // ─────────────────────────────────────────────── 3. 渲染器

    private fun installRenderer(cl: ClassLoader) {
        val listener = H.cls(cl, CLS_LISTENER)
        if (listener == null) {
            H.log(Log.WARN, MainHook.TAG, "event=splash_kill_miss anchor=renderer_signature"
                    + " listener=false")
            return
        }
        for ((cls, id) in arrayOf(CLS_IMAGE to "amap_splash_view_image",
                CLS_VIDEO to "amap_splash_view_video")) {
            val c = H.cls(cl, cls) ?: continue
            val method = c.declaredMethods.firstOrNull {
                val p = it.parameterTypes
                it.name == "getSplashView" && p.size == 3 && p[0] == Context::class.java &&
                        p[1].name in ITEM_NAMES && p[2] == listener &&
                        android.view.View::class.java.isAssignableFrom(it.returnType)
            }
            if (method == null) {
                H.log("event=splash_kill_miss anchor=" + cls + " native_kept=true")
                continue
            }
            val item = method.parameterTypes[1]
            H.log("event=splash_renderer_anchor class=" + cls + " item=" + item.name)
            // 返回 null ⇒ v76.run() 走 g.l(k66) + e(ERROR) 收尾，直接进地图。
            H.hookSig(c, "getSplashView", id, nullView("getSplashView:" + cls.substringAfterLast('.')),
                    Context::class.java, item, listener)
        }
    }

    /** Both overloads and void return are required: these names exist as unrelated Play classes. */
    @JvmStatic fun requesterShape(owner: Class<*>?): Boolean = try {
        owner != null && owner.getDeclaredMethod("attemptRequestData", Integer.TYPE).returnType == Void.TYPE &&
                owner.getDeclaredMethod("attemptRequestData", Integer.TYPE, java.lang.Boolean.TYPE).returnType == Void.TYPE
    } catch (_: Throwable) { false }

    @JvmStatic fun realtimeShape(owner: Class<*>?): Boolean = try {
        owner != null && Runnable::class.java.isAssignableFrom(owner) &&
                owner.getDeclaredConstructor(String::class.java, String::class.java) != null &&
                owner.getDeclaredMethod("run").returnType == Void.TYPE
    } catch (_: Throwable) { false }

    // ─────────────────────────────────────────────── 4. 已落地素材清除

    /**
     * 素材目录每进程清一次。
     *
     * <p>安装期 `Config.hostCtx()` 还是 null（真机日志：`hostctx ... resolved=null via=probe`），
     * 所以不能就地删，必须延后重试到宿主 Context 可用为止；最多 20 次 × 500 ms。
     *
     * <p>只删 `i_*` / `v_*` 两种**广告素材**文件，其余（`bg_silent/cloud_config`、
     * `time_mills_filter`、目录本身）原样保留 —— 宿主对目录结构的假设不受影响。
     */
    private fun schedulePurge() {
        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var tries = 0
            handler.post(object : Runnable {
                override fun run() {
                    val ctx = Config.hostCtx()
                    if (ctx != null) {
                        if (Config.visible(Config.K_ALIMAMA_SPLASH_OFF)) purge(ctx)
                        return
                    }
                    if (++tries > 20) {
                        H.log(Log.WARN, MainHook.TAG, "event=splash_cache_purge_skip reason=no_context")
                        return
                    }
                    handler.postDelayed(this, 500)
                }
            })
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=splash_cache_purge_fail reason="
                    + t.javaClass.simpleName)
        }
    }

    private fun purge(context: Context) {
        Thread({
            var files = 0
            var bytes = 0L
            try {
                if (!Config.visible(Config.K_ALIMAMA_SPLASH_OFF)) return@Thread
                val dir = File(context.filesDir, "splash")
                if (!dir.isDirectory) return@Thread
                val list = dir.listFiles() ?: return@Thread
                for (f in list) {
                    if (!f.isFile) continue
                    val name = f.name
                    // 只认广告素材命名：i_<hash> / v_<hash>。
                    if (!name.startsWith("i_") && !name.startsWith("v_")) continue
                    val len = f.length()
                    if (f.delete()) {
                        files++
                        bytes += len
                    }
                }
                H.log(Log.INFO, MainHook.TAG,
                        "event=splash_cache_purged files=" + files + " bytes=" + bytes)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=splash_cache_purge_fail reason="
                        + t.javaClass.simpleName + " files=" + files)
            }
        }, "mapclean-splash-purge").start()
    }

    // ─────────────────────────────────────────────── Hooker

    /** `void` 方法：不 proceed 即短路。 */
    private fun block(label: String): XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Config.visible(Config.K_ALIMAMA_SPLASH_OFF)) return chain.proceed()
            if (once(label)) {
                H.log(Log.INFO, MainHook.TAG, "event=splash_kill_blocked target=" + label)
            }
            return null
        }
    }

    /** 返回 `View` 的方法：给 null 让宿主走它自己的渲染失败收尾。 */
    private fun nullView(label: String): XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Config.visible(Config.K_ALIMAMA_SPLASH_OFF)) return chain.proceed()
            if (once(label)) {
                H.log(Log.INFO, MainHook.TAG, "event=splash_kill_noview target=" + label)
            }
            return null
        }
    }

    private fun once(key: String): Boolean = reported.add(key)
}
