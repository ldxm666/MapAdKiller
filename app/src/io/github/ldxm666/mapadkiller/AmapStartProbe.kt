package io.github.ldxm666.mapadkiller

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer

import java.lang.reflect.Method
import java.util.Locale

import io.github.libxposed.api.XposedInterface

/**
 * 高德冷启动「四段」探针 —— **只量，不改**。
 *
 * <h3>为什么要有它</h3>
 * 上一个 agent 已经把「启动任务链」这条路证伪了：`startLaunch()` 里 31 个任务全量
 * 计时合计 ≈101 ms、整条链跨度上限 ≈270 ms，而 `Displayed` 是秒级 ⇒ 差两个数量级。
 * 所以真正的时间只能在 `startLaunch()` **之后**、首帧之前，也就是下面这四段里。
 *
 * <h3>四段（签名全部从 17.02.0.1541 的 dex 里查出来，不是猜的）</h3>
 * <pre>
 *   seg1_native_init     Lcom/amap/jni/app/InterfaceAppImpl;->init(Landroid/content/Context;)V
 *                        建整棵 native 树：network / Accs / assetsReader / device /
 *                        imageDecoder / resourceLoader / FeatureCenter / DesignToken
 *   seg2a_ondemand       Lcom/amap/bundle/launch/common/OnDemandReceiver;->onApplicationCreated(Landroid/content/Context;)V
 *   seg2b_component_mgr  Lcom/amap/bundle/pluginframework/components/PluginComponentManager;->init(Landroid/app/Application;)V
 *                        （IComponentManager 是接口，没有方法体，只能挂唯一实现）
 *   seg3_activity_o      Lcom/autonavi/map/activity/NewMapActivity;->o(Landroid/os/Bundle;)V   ← onCreate 实体
 *   seg3b_activity_G     Lcom/autonavi/map/activity/NewMapActivity;->G(Landroid/content/Intent;)V
 *   seg4a_load_main_map  Lcom/autonavi/map/activity/NewMapActivity;->loadMainMap(Z)V
 *   seg4b_F              Lcom/autonavi/map/activity/NewMapActivity;->F()V   ← ViewStub inflate
 *   first_frame          由 seg4b 结束后 post 一帧 Choreographer 回调，量到"真正画出来"
 * </pre>
 *
 * <h3>为什么这样量出来的数能对上 `Displayed`</h3>
 * 每条都带 `at_ms`，基准是 [Perf.START]（= 模块拿到 `module_loaded`
 * 的那一刻）。把所有 `at_ms + ms` 排一遍，就能看出：
 * <pre>
 *   module_loaded(0) → seg1 → seg2a → seg2b → seg3 → seg3b → seg4a → seg4b → first_frame
 * </pre>
 * 哪一段是空的（没打出来）就说明那个方法在这个版本/这条路径上根本没走，
 * 这本身就是结论 —— 不要假设它一定执行。
 *
 * <h3>开销</h3>
 * 每段**每个进程只打一条**日志（FirstOnce 语义），hook 体里除了两次
 * `System.nanoTime()` 什么都不做，`chain.proceed()` 原样放行。
 * 关掉：`adb shell setprop debug.mapclean.start_probe 0`（改完强停重开生效）。
 *
 * <h3>段内细分</h3>
 * 某段 &gt;2 s 需要继续切时，不用改代码：
 * <pre>
 *   adb shell setprop debug.mapclean.start_probe.extra \
 *     "com.amap.jni.app.InterfaceAppImpl#createNetwork,com.amap.jni.app.InterfaceAppImpl#createAccs"
 * </pre>
 * 逗号分隔的 `类名#方法名`，会按"第一个匹配到的同名重载"各挂一条计时。
 */
object AmapStartProbe {

    private val TAG = MainHook.TAG

    /** 进程内只装一次。 */
    @Volatile
    private var installed = false

    /** 总开关，默认开：它只打几条日志，代价可以忽略。 */
    @Volatile
    private var on = true

    @JvmStatic
    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        try {
            on = flag("debug.mapclean.start_probe", true)
            if (!on) {
                H.log(Log.INFO, TAG, "event=start_probe skipped reason=prop_off")
                return
            }
            var n = 0
            // ── seg1：native 树 ────────────────────────────────────────────────
            n += seg(cl, "com.amap.jni.app.InterfaceAppImpl", "init", "seg1_native_init",
                    Context::class.java)
            // ── seg2：按需模块装配 ─────────────────────────────────────────────
            n += seg(cl, "com.amap.bundle.launch.common.OnDemandReceiver", "onApplicationCreated",
                    "seg2a_ondemand", Context::class.java)
            n += seg(cl, "com.amap.bundle.pluginframework.components.PluginComponentManager", "init",
                    "seg2b_component_mgr", Application::class.java)
            // ── seg3：Activity onCreate 实体 ───────────────────────────────────
            n += seg(cl, "com.autonavi.map.activity.NewMapActivity", "o", "seg3_activity_o",
                    Bundle::class.java)
            n += seg(cl, "com.autonavi.map.activity.NewMapActivity", "G", "seg3b_activity_G",
                    android.content.Intent::class.java)
            // ── seg4：地图页 + 首帧 ────────────────────────────────────────────
            n += seg(cl, "com.autonavi.map.activity.NewMapActivity", "loadMainMap",
                    "seg4a_load_main_map", Boolean::class.javaPrimitiveType)
            n += frameSeg(cl)
            // ── 可选：临时细分 ─────────────────────────────────────────────────
            n += extra(cl)
            H.log(Log.INFO, TAG, "event=start_probe installed hooks=" + n)
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=start_probe failed", t)
        }
    }

    /**
     * 挂一段计时：`chain.proceed()` 之后打一条 `event=seg`。
     *
     * <p>每个进程只打**第一次**调用。理由：`o(Bundle)` 在 Activity 重建时会再来一次，
     * 那时候的耗时和冷启动无关，混在日志里只会误导。
     */
    private fun seg(cl: ClassLoader, cls: String, method: String, name: String,
                    vararg params: Class<*>?): Int {
        try {
            val c = H.cls(cl, cls)
            if (c == null) {
                H.log(Log.INFO, TAG, "event=seg_missing name=" + name + " class=" + cls)
                return 0
            }
            val m: Method = try {
                c.getDeclaredMethod(method, *params)
            } catch (e: NoSuchMethodException) {
                H.log(Log.INFO, TAG, "event=seg_missing name=" + name + " sig=" + method)
                return 0
            }
            val mod = H.module ?: return 0
            mod.hook(m).setId("probe_" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(object : XposedInterface.Hooker {
                        private var done = false
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (done) return chain.proceed()
                            val t0 = System.nanoTime()
                            val at = SystemClock.uptimeMillis() - Perf.START
                            try {
                                return chain.proceed()
                            } finally {
                                done = true
                                val ms = (System.nanoTime() - t0) / 1000000L
                                H.log(Log.INFO, TAG, "event=seg name=" + name
                                        + " ms=" + ms + " at_ms=" + at
                                        + " thread=" + Thread.currentThread().name)
                            }
                        }
                    })
            return 1
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=seg_hook_failed name=" + name, t)
            return 0
        }
    }

    /**
     * seg4b + first_frame：`F()` 结束后 post 一帧回调。
     *
     * <p>为什么要 Choreographer 而不是直接拿 F() 的耗时：F() 只负责 inflate / attach，
     * 真正"用户看见"的那一刻是这一帧被合成。两者之间可能还隔着一次 measure/layout。
     */
    private fun frameSeg(cl: ClassLoader): Int {
        try {
            val c = H.cls(cl, "com.autonavi.map.activity.NewMapActivity")
            val mod = H.module ?: return 0
            if (c == null) return 0
            val m = c.getDeclaredMethod("F")
            mod.hook(m).setId("probe_seg4b_F")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(object : XposedInterface.Hooker {
                        private var done = false
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (done) return chain.proceed()
                            val t0 = System.nanoTime()
                            val at = SystemClock.uptimeMillis() - Perf.START
                            try {
                                return chain.proceed()
                            } finally {
                                done = true
                                val ms = (System.nanoTime() - t0) / 1000000L
                                H.log(Log.INFO, TAG, "event=seg name=seg4b_F ms=" + ms
                                        + " at_ms=" + at
                                        + " thread=" + Thread.currentThread().name)
                                postFirstFrame(at + ms)
                            }
                        }
                    })
            return 1
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=seg_hook_failed name=seg4b_F", t)
            return 0
        }
    }

    /** post 一帧 Choreographer 回调，把"真正出帧"的时刻也钉在时间轴上。 */
    private fun postFirstFrame(afterMs: Long) {
        try {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                private var done = false
                override fun doFrame(frameTimeNanos: Long) {
                    if (done) return
                    done = true
                    try {
                        val at = SystemClock.uptimeMillis() - Perf.START
                        H.log(Log.INFO, TAG, "event=seg name=first_frame ms=0 at_ms=" + at
                                + " since_F_end_ms=" + (at - afterMs))
                    } catch (ignored: Throwable) {
                    }
                }
            })
        } catch (t: Throwable) {
            H.log(Log.WARN, TAG, "event=seg_hook_failed name=first_frame", t)
        }
    }

    /** `debug.mapclean.start_probe.extra="类名#方法名,..."` 临时细分。 */
    private fun extra(cl: ClassLoader): Int {
        val list = prop("debug.mapclean.start_probe.extra")
        if (list == null || list.trim().isEmpty()) return 0
        var n = 0
        for (part in list.split(",")) {
            val spec = part.trim()
            if (spec.isEmpty()) continue
            val hash = spec.indexOf('#')
            if (hash <= 0 || hash == spec.length - 1) continue
            val cls = spec.substring(0, hash)
            val method = spec.substring(hash + 1)
            try {
                val c = H.cls(cl, cls)
                val mod = H.module
                if (c == null || mod == null) continue
                for (m in c.declaredMethods) {
                    if (m.name != method) continue
                    val name = "extra_" + c.simpleName + "_" + method
                    mod.hook(m).setId("probe_" + name)
                            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                            .intercept(object : XposedInterface.Hooker {
                                private var done = false
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    if (done) return chain.proceed()
                                    val t0 = System.nanoTime()
                                    val at = SystemClock.uptimeMillis() - Perf.START
                                    try {
                                        return chain.proceed()
                                    } finally {
                                        done = true
                                        H.log(Log.INFO, TAG, "event=seg name=" + name
                                                + " ms=" + ((System.nanoTime() - t0) / 1000000L)
                                                + " at_ms=" + at)
                                    }
                                }
                            })
                    n++
                    break   // 只挂第一个同名重载，避免一次命中多个把日志打乱
                }
            } catch (t: Throwable) {
                H.log(Log.WARN, TAG, "event=seg_hook_failed name=extra_" + spec, t)
            }
        }
        return n
    }

    private fun flag(key: String, def: Boolean): Boolean {
        var v = prop(key)
        if (v == null || v.trim().isEmpty()) return def
        v = v.trim().lowercase(Locale.US)
        return !(v == "0" || v == "false" || v == "off" || v == "no")
    }

    /** 读系统属性。`android.os.SystemProperties` 是 @hide，只能反射。 */
    private fun prop(key: String): String? {
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getDeclaredMethod("get", String::class.java, String::class.java)
            get.isAccessible = true
            val v = get.invoke(null, key, null)
            return v as? String
        } catch (t: Throwable) {
            return null
        }
    }
}
