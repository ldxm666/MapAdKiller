package io.github.ldxm666.mapadkiller

import android.util.Log
import android.content.Context

import io.github.libxposed.api.XposedInterface

/**
 * 高德阿里妈妈开屏通道。17.02.0.2010 / Play 17.02.2.2014 的请求实现均为
 * AlimamaRequestHolder.sendSplashScreen(request, falcon, callback)；两参入口转发到三参。
 * 请求被拦时调用宿主 onFailure 放锁，回调不兼容时放行请求，避免启动一直等待。
 * AmapSplashRules 另拦已缓存的广告，保留宿主 NO_SPLASH 完成流程。
 * 两种广告拦截共用 alimama_splash_off（默认开），显式关闭仍有效，重启后生效。
 * 不改整个品牌启动页、支付 SDK、隐私授权或地图初始化。
 */
object AmapAlimama {

    private const val CLS_HOLDER = "com.autonavi.minimap.alimama.AlimamaRequestHolder"
    private const val CLS_SPLASH_REQ = "com.autonavi.minimap.alimama.param.SplashScreenRequest"
    private const val CLS_H5LOG_REQ = "com.autonavi.minimap.alimama.param.H5LogRequest"
    private const val CLS_FALCON = "com.autonavi.minimap.falcon.base.a"
    private const val CLS_CALLBACK = "com.amap.bundle.aosservice.response.AosResponseCallback"

    @JvmStatic
    fun install(cl: ClassLoader) {
        // Attach may not have happened yet. Install the small hooks now; each
        // interceptor checks the actual setting at invocation time.
        val splashOn = true
        val logOn = Config.installGate(Config.K_ALIMAMA_LOG_OFF)
        if (!splashOn && !logOn) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_splash_off+alimama_log_off"
                    + " reason=switch_off")
            return
        }
        if (splashOn) installCacheGate(cl)
        if (splashOn) {
            // 同一开关下的深度清除（见 AmapSplashKill）：素材请求源头 ah5、实时开屏
            // SplashScreenServiceImpl.a()/yc5、渲染器 Image/VideoSplashView.getSplashView，
            // 以及已经落在 files/splash/ 的 i_* / v_* 素材。任一环节装不上只记日志。
            try {
                AmapSplashKill.install(cl)
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "event=splash_kill_failed reason="
                        + t.javaClass.simpleName)
            }
        }
        try {
            val holder = H.cls(cl, CLS_HOLDER)
            val splash = H.cls(cl, CLS_SPLASH_REQ)
            val h5log = H.cls(cl, CLS_H5LOG_REQ)
            val falcon = H.cls(cl, CLS_FALCON)
            val callback = H.cls(cl, CLS_CALLBACK)
            if (holder == null || falcon == null || callback == null) {
                // 不猜替代品：类名对不上就只留痕，绝不去 hook 名字相近的类。
                H.log(Log.WARN, MainHook.TAG, "event=anchor_miss anchor=AlimamaRequestHolder"
                        + " holder=" + (holder != null) + " falcon=" + (falcon != null)
                        + " callback=" + (callback != null))
                return
            }
            if (splashOn) {
                H.hookSig(holder, "sendSplashScreen", "amap_alimama_splash",
                        // 当前 CN 的 zg5.run / Callback.onFailure 已从 DEX 核对：
                        // 请求被拦前必须释放回调持有的 a76 锁。
                        block("sendSplashScreen", "ws/valueadded/alimama/splash_screen", true, Config.K_ALIMAMA_SPLASH_OFF),
                        splash, falcon, callback)
                // 2 参重载（SplashScreenRequest, AosResponseCallback）：DEX 里确实存在，
                // 注释说它转发到 3 参，但"转发"是宿主实现细节，不能当作唯一入口。
                // 签名对不上时 hookSig 只记 MISS，不会误挂。
                H.hookSig(holder, "sendSplashScreen", "amap_alimama_splash_2",
                        block("sendSplashScreen/2", "ws/valueadded/alimama/splash_screen", true, Config.K_ALIMAMA_SPLASH_OFF),
                        splash, callback)
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_splash_off reason=switch_off")
            }
            if (logOn) {
                H.hookSig(holder, "sendH5Log", "amap_alimama_h5log",
                        // releaseLock=false：埋点是静态 void 打点函数，调用方不等返回值，也没有锁；
                        // 对埋点请求补 onFailure 只会让宿主多记一条失败、甚至进重试队列。
                        block("sendH5Log", "ws/h5_log", false, Config.K_ALIMAMA_LOG_OFF),
                        h5log, falcon, callback)
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_log_off reason=switch_off")
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=alimama_failed reason=" + t.javaClass.simpleName)
        }
    }

    private fun installCacheGate(cl: ClassLoader) {
        val presenter = H.cls(cl, "com.autonavi.minimap.g") ?: return
        val show = H.method(presenter, "o", Int::class.javaPrimitiveType!!, String::class.java) ?: return
        if (show.returnType != Void.TYPE || java.lang.reflect.Modifier.isStatic(show.modifiers)) return
        // g.o itself obtains this context immediately after readiness. It can exist
        // before ActivityThread publishes currentApplication during cold startup.
        val appContext = H.method(H.cls(cl,
                "com.autonavi.map.fragmentcontainer.page.utils.AMapPageUtil"), "getAppContext")
        for (name in AmapSplashRules.CACHE_GATES) {
            val owner = H.cls(cl, name) ?: continue
            val method = AmapSplashRules.resolveGate(owner) ?: continue
            H.hookSig(presenter, show.name, "amap_alimama_splash_scope",
                    object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (!splashEnabled(appContext)) return chain.proceed()
                            val previous = AmapSplashRules.beginPresentation()
                            try { return chain.proceed() }
                            finally { AmapSplashRules.endPresentation(previous) }
                        }
                    }, Int::class.javaPrimitiveType, String::class.java)
            var reported = false
            H.hookSig(owner, method.name, "amap_alimama_cached_splash",
                    object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val native = chain.proceed()
                            if (AmapSplashRules.denyAd(method, native) && !reported) {
                                reported = true
                                H.log("event=alimama_cached_blocked anchor=" + name
                                        + " finish=NO_SPLASH")
                            }
                            return native
                        }
                    }, Int::class.javaPrimitiveType, String::class.java)
            return
        }
        H.log(Log.WARN, MainHook.TAG, "event=anchor_miss anchor=alimama_cached_splash native_kept=true")
    }

    private fun splashEnabled(appContext: java.lang.reflect.Method?): Boolean {
        var local: Boolean? = null
        val embedded = HostInfo.isEmbedded()
        if (embedded) try {
            val context = (Config.hostCtx() ?: appContext?.invoke(null)) as? Context
            if (context?.packageName == MainHook.PKG_AMAP) {
                val prefs = context.getSharedPreferences(CfgStore.LOCAL_PREFS, Context.MODE_PRIVATE)
                if (prefs.contains(Config.K_ALIMAMA_SPLASH_OFF))
                    local = prefs.getBoolean(Config.K_ALIMAMA_SPLASH_OFF, true)
            }
        } catch (_: Throwable) { }
        return AmapSplashRules.enabled(embedded, local,
                Config.readVisible(CfgStore.readAll(), Config.K_ALIMAMA_SPLASH_OFF))
    }

    /**
     * 短路一个 `void` 请求方法。
     *
     * <p>两个目标方法都是 `void` ⇒ 拦截只有一种正确写法：`return null`（不 proceed）。
     * 不能"proceed 之后再返回"：请求已经在 `proceed` 里发出去了，拦了等于没拦。
     *
     * <p><b>拦截与放锁必须成对</b>：`zg5.run()` 拿到 `a76` 锁后才发这条请求，而锁只在
     * `Callback#onFailure`/`onSuccess` 里释放（`a76.m77b()`）。把请求吞掉却没有
     * 回调 ⇒ 锁永不释放 ⇒ 宿主一直停在 SplashActivity（2026-10-27 真机实测：点图标 4 分钟不进
     * 首页，全程白屏）。所以 `releaseLock=true` 的钩子在 return 之前必须先补一次失败回调。
     *
     * <p><b>放锁失败就放行</b>：回调对象认不出来时（宿主换混淆形态），宁可放过这一条广告请求，
     * 也不能把宿主钉在白屏上 —— 请求照发，锁自然会在宿主的真实回调里释放。
     */
    private fun block(label: String, endpoint: String, releaseLock: Boolean, key: String): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var reported = false
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // Installation can precede Application attachment. Recheck the actual
                // backend at request time instead of freezing an early missing default.
                if (!Config.readVisible(CfgStore.readAll(), key)) return chain.proceed()
                try {
                    if (!reported) {
                        reported = true
                        H.log(Log.INFO, MainHook.TAG, "event=alimama_blocked method=" + label
                                + " endpoint=" + endpoint + " release_lock=" + releaseLock)
                    }
                } catch (ignored: Throwable) {
                }
                if (releaseLock) {
                    var released = false
                    try {
                        released = releaseCallback(chain.getArgs().toTypedArray())
                    } catch (ignored: Throwable) {
                        // releaseCallback 自己已经全包了；这里再兜一层，绝不让放锁逻辑抛出去。
                    }
                    if (!released) {
                        try {
                            H.log(Log.WARN, MainHook.TAG, "event=alimama_passthrough method=" + label
                                    + " reason=callback_unreleased")
                        } catch (ignored: Throwable) {
                        }
                        return chain.proceed()
                    }
                }
                return null
            }
        }
    }

    /** The last declared argument is the verified callback, never the falcon/request object. */
    private fun releaseCallback(args: Array<Any?>?): Boolean {
        if (args == null || args.isEmpty()) return false
        val request = args[0]
        val tried = StringBuilder()
        val cb = args.lastOrNull()
        if (cb != null && cb !== request && fireFailure(cb, request, tried)) return true
        try {
            H.log(Log.WARN, MainHook.TAG, "event=alimama_callback_lost args=" + describe(args)
                    + " tried=[" + tried.toString().trim() + "]")
        } catch (ignored: Throwable) {
        }
        return false
    }

    /** 在一个对象上找 onFailure 发一次。找不到/全失败返回 false，绝不抛。 */
    private fun fireFailure(cb: Any, request: Any?, tried: StringBuilder): Boolean {
        var k: Class<*>? = cb.javaClass
        while (k != null && k != Any::class.java) {
            val cur = k
            // ★ getDeclaredMethods：混淆后的包私有实现类 / 匿名内部类也拿得到，
            //   getMethods 只给 public，那正是旧写法在换版后静默失效的地方。
            val methods = try {
                cur.declaredMethods
            } catch (t: Throwable) {
                null
            }
            if (methods == null) {
                k = cur.superclass
                continue
            }
            for (m in methods) {
                if ("onFailure" != m.name) continue
                if (m.returnType != Void.TYPE) continue
                val ps = m.parameterTypes
                if (ps == null || ps.size < 1 || ps.size > 2) continue
                if (hasPrimitive(ps)) continue          // 形参里有 int/boolean ⇒ null 填不进去
                try {
                    m.isAccessible = true
                    if (ps.size == 1) {
                        m.invoke(cb, argFor(ps[0], request))
                    } else {
                        m.invoke(cb, argFor(ps[0], request), argFor(ps[1], request))
                    }
                    H.log(Log.INFO, MainHook.TAG, "event=alimama_callback_released cb="
                            + cb.javaClass.name + " via="
                            + cur.simpleName + "#" + ps.size + "arg")
                    return true
                } catch (t: Throwable) {
                    val cause = if (t.cause == null) t else t.cause!!
                    tried.append(m.name).append('/').append(ps.size).append(':')
                            .append(cause.javaClass.simpleName).append(' ')
                    return false
                }
            }
            k = cur.superclass
        }
        return false
    }

    /** 参数位置填什么：优先宿主自己那个 request；能收 Throwable 的给一个真异常；其余给 null。 */
    private fun argFor(type: Class<*>, request: Any?): Any? {
        if (type.isInstance(request)) return request
        if (type.isAssignableFrom(RuntimeException::class.java)) {
            return RuntimeException("mapadkiller: alimama request blocked")
        }
        // 只接受具体异常子类时给 null：宿主的 onFailure 只做埋点 + 放锁，不碰异常对象。
        return null
    }

    private fun hasPrimitive(ps: Array<Class<*>>): Boolean {
        for (p in ps) {
            if (p.isPrimitive) return true
        }
        return false
    }

    /** 只把参数的类型名打进日志：宿主对象没有可读的 toString，别在日志里踩它的实现。 */
    private fun describe(args: Array<Any?>): String {
        val sb = StringBuilder()
        for (a in args) {
            sb.append(if (a == null) "null" else a.javaClass.name).append(',')
        }
        return sb.toString()
    }
}
