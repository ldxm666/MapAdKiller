package io.github.ldxm666.mapadkiller
import android.util.Log
import java.lang.reflect.Method

object SplashContainerHooks {
    @JvmStatic
    fun installContainerProbe(cl: ClassLoader, clsName: String, tag: String) {
        val c = H.cls(cl, clsName)
        if (c == null) {
            H.log(Log.INFO, MainHook.TAG, tag + " splash container NOT FOUND: " + clsName)
            return
        }
        var n = 0
        for (m in c.declaredMethods) {
            val nm = m.name
            val want = nm == "addView" ||
                    nm == "onAttachedToWindow" ||
                    nm == "setContentView" ||
                    nm == "show"
            if (!want) continue
            val ps = m.parameterTypes
            if (nm == "addView" && (ps.size == 0 || !android.view.View::class.java.isAssignableFrom(ps[0]))) continue
            if (nm == "setContentView" && (ps.size == 0 || !android.view.View::class.java.isAssignableFrom(ps[0]))) continue
            val mod = H.module ?: continue
            mod.hook(m).setId(tag + "_probe_" + nm)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            val r = chain.proceed()
                            try {
                                val self = chain.getThisObject()
                                if (self is android.view.View) {
                                    // 立即探一次 + 接下来 90 帧持续探（广告内容常是异步塞进来的）
                                    probeLoop(self, tag)
                                }
                            } catch (ignored: Throwable) {}
                            return r
                        }
                    })
            n++
        }
        H.log(Log.INFO, MainHook.TAG, tag + " splash container probe on " + clsName + " hooks=" + n)
    }

    /** 帧驱动探测：命中即摘广告子树，最多盯 90 帧 */
    private fun probeLoop(root: android.view.View?, tag: String) {
        if (root == null) return
        if (root !is android.view.ViewGroup) return
        try {
            root.viewTreeObserver.addOnPreDrawListener(
                    object : android.view.ViewTreeObserver.OnPreDrawListener {
                        private var n = 0
                        override fun onPreDraw(): Boolean {
                            try {
                                if (root is android.view.ViewGroup) {
                                    val res = SplashProbe.scan(root)
                                    if (res.isAd()) {
                                        SplashProbe.stripAdChildren(root)
                                        if (SplashProbe.first(tag + ":" + res.hit)) {
                                            H.log(Log.INFO, MainHook.TAG,
                                                    tag + " splash ad subtree stripped hit=" + res.hit)
                                        }
                                    }
                                }
                                if (++n > 90) {
                                    try {
                                        root.viewTreeObserver.removeOnPreDrawListener(this)
                                    } catch (ignored: Throwable) {}
                                }
                            } catch (ignored: Throwable) {}
                            return true
                        }
                    })
        } catch (ignored: Throwable) {}
    }
}
