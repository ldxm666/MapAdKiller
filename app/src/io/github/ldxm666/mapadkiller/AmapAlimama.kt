package io.github.ldxm666.mapadkiller

import android.util.Log

import io.github.libxposed.api.XposedInterface

/**
 * 阿里妈妈（Alimama）广告请求层 —— 高德主 APK 里**唯一**的真·广告 SDK 接入点。
 *
 * <h3>为什么单独立一个类</h3>
 * 17.02 的 8 个 DEX 里**没有任何第三方广告 SDK**（穿山甲 / 优量汇 / 快手 / 百度 / MBridge /
 * Sigmob / TopOn / TradPlus / 北子 / 美数 / 章鱼 / 风铃 / 趣盟 全部 0 命中，见
 * `adsdk-impl.md` 的厂商前缀扫描表）。高德的广告走**自有运营协议**（首页卡片、
 * 我的页卡List、POI 详情 WaterFeed、地图标注 —— 已由 `AmapData`/`AmapPlaceData`/
 * `AmapPoiLayer` 在数据层剥离），唯一"SDK 形态"的广告通道就是这里的阿里妈妈。
 *
 * <h3>DEX 证据（17.02.0.1541，classes6.dex）</h3>
 * <pre>
 * Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;        单例（getInstance），7 个方法
 *   public sendSplashScreen(SplashScreenRequest, falcon.base.a, AosResponseCallback)V   ← 开屏广告请求（实现）
 *   public sendSplashScreen(SplashScreenRequest, AosResponseCallback)V                  ← 2 参转发到上面
 *   public sendH5Log(H5LogRequest, falcon.base.a, AosResponseCallback)V                 ← 广告曝光/点击埋点（实现）
 *   public sendH5Log(H5LogRequest, AosResponseCallback)V                                ← 2 参转发到上面
 *   public cancel(AosRequest)V
 * Lcom/autonavi/minimap/alimama/param/SplashScreenRequest;   super=AosPostRequest
 *   &lt;clinit&gt;: const-string "aos_url" + "ws/valueadded/alimama/splash_screen" → 静态字段 E
 * Lcom/autonavi/minimap/alimama/param/H5LogRequest;          super=AosPostRequest
 *   &lt;clinit&gt;: const-string "h5_log_url" + "ws/h5_log?" → 静态字段 L
 * </pre>
 * 调用方（`--InvokeXref` 全库只有这两处）：
 * <ul>
 *   <li>`Lzg5;->run()V`（classes6，code_off=0x408894）→ `sendSplashScreen(3 参)`。
 *       该 run 的形态是「拿锁 → 发请求 → 埋点 → finally 解锁」，
 *       **解锁在调用之后**，所以这里 return 掉不会把锁卡死；</li>
 *   <li>`Lcom/autonavi/minimap/track/b;->e(...)V`（classes6）→ `sendH5Log(2 参)`，
 *       静态 void 打点函数，调用方不等返回值。</li>
 * </ul>
 *
 * <h3>为什么挂 3 参重载而不是 2 参</h3>
 * 2 参是纯转发（体内唯一一条调用就是 3 参版）。挂实现版本一次覆盖两个入口，
 * 也不会因为"挂错了重载"而静默失效（`notifyJs` 那次事故就是这么来的）。
 *
 * <h3>风险与回滚</h3>
 * 两个开关都在设置面板里，默认 true（=已拦截）：
 * <ul>
 *   <li>`alimama_splash_off` —— 关掉后开屏广告请求照发（与旧版一致）；</li>
 *   <li>`alimama_log_off` —— 关掉后广告埋点照传。</li>
 * </ul>
 * <h3>与开屏页的关系（2.3.0 改了，白屏修复）</h3>
 * 2.2.x 另外用 `BootBizDataPreloaderImpl.canShowSplash()=false` 把**开屏页整个摘掉**，
 * 于是冷启首帧没有任何东西盖住还没 ready 的首页 —— 这就是「进入就白屏」的直接来源。
 * DEX 实查 17.02.0.1541（origin.apk/classes5.dex）该方法返回 `Ljava/lang/Boolean;`（装箱），
 * 而 `H.FALSE` 返回的就是 `Boolean.FALSE`，**返回类型本来就是匹配的** ——
 * 所以不存在「类型不对被 HookGuard 抛异常熔断」这回事，那条 hook 在真机上是生效的、正在白屏。
 *
 * <p>2.3.0 起不再改 `canShowSplash`：开屏页保留，广告请求在本类短路，跳过的只是广告内容
 * （见 `AmapHooks` 的对应注释）。⇒ 结论反过来：现在**一定**有一个开屏容器在等这条请求的
 * 回调 —— 放锁回调从「保险」变成「硬要求」，所以 [block] 里放锁失败会直接放行请求。
 *
 * <p>两个开关都在设置面板里，默认 true（=已拦截）：
 * <ul>
 *   <li>`alimama_splash_off` —— 关掉后开屏广告请求照发（与旧版一致）；</li>
 *   <li>`alimama_log_off` —— 关掉后广告埋点照传。</li>
 * </ul>
 */
object AmapAlimama {

    private const val CLS_HOLDER = "com.autonavi.minimap.alimama.AlimamaRequestHolder"
    private const val CLS_SPLASH_REQ = "com.autonavi.minimap.alimama.param.SplashScreenRequest"
    private const val CLS_H5LOG_REQ = "com.autonavi.minimap.alimama.param.H5LogRequest"
    private const val CLS_FALCON = "com.autonavi.minimap.falcon.base.a"
    private const val CLS_CALLBACK = "com.amap.bundle.aosservice.response.AosResponseCallback"

    @JvmStatic
    fun install(cl: ClassLoader) {
        // ★ 安装期门控（两条各自独立）：关掉的那条**根本不装**，零成本。
        // ★ 2.3.0 一度把开屏广告拦截改成"显式开关、默认不拦"（`Config.explicit()`：
        //   没设置过 = 不拦），理由是"开屏页是高德自己的界面，拦掉广告请求会让页面拿不到
        //   内容又不结束"。真机上这个理由只成立一半：开屏**页**与白屏无关 —— `canShowSplash`
        //   那条 gate 由 `K_SPLASH_SKIP` 控制且默认不装，页面本来就在；而不拦广告的净结果
        //   就是用户重新看到开屏广告。
        // ⇒ 2.4.0 恢复**默认拦截**，读法改回 `visible()`（该键在 `defaultVisible` 里落 true）。
        //   页面照旧显示，只是页面上那条第三方物料不再下发。开关保留，排查时可临时关掉。
        val splashOn = Config.visible(Config.K_ALIMAMA_SPLASH_OFF)
        val logOn = Config.installGate(Config.K_ALIMAMA_LOG_OFF)
        if (!splashOn && !logOn) {
            H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_splash_off+alimama_log_off"
                    + " reason=switch_off")
            return
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
                        // releaseLock=true：zg5.run() 拿 a76 锁后才发这条请求，锁只在
                        // Callback#onFailure/onSuccess 里放 ⇒ 吞掉请求前必须补一次失败回调。
                        block("sendSplashScreen", "ws/valueadded/alimama/splash_screen", true),
                        splash, falcon, callback)
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_splash_off reason=switch_off")
            }
            if (logOn) {
                H.hookSig(holder, "sendH5Log", "amap_alimama_h5log",
                        // releaseLock=false：埋点是静态 void 打点函数，调用方不等返回值，也没有锁；
                        // 对埋点请求补 onFailure 只会让宿主多记一条失败、甚至进重试队列。
                        block("sendH5Log", "ws/h5_log", false),
                        h5log, falcon, callback)
            } else {
                H.log(Log.INFO, MainHook.TAG, "event=hook_skipped key=alimama_log_off reason=switch_off")
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=alimama_failed reason=" + t.javaClass.simpleName)
        }
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
    private fun block(label: String, endpoint: String, releaseLock: Boolean): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            private var reported = false
            override fun intercept(chain: XposedInterface.Chain): Any? {
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
                // 装上就无条件短路（安装期门控已决定装不装），拦截器里零 Config 读。
                return null
            }
        }
    }

    /**
     * 补一次 `onFailure(...)`，让宿主自己把开屏锁放掉。
     *
     * <p>签名实查（17.02.0.1541，origin.apk）：`classes6.dex` 里
     * <pre>
     *   Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;
     *     sendSplashScreen(SplashScreenRequest, falcon.base.a, AosResponseCallback)V  ← 实现
     *     sendSplashScreen(SplashScreenRequest, AosResponseCallback)V                 ← 2 参转发
     *   Lcom/amap/bundle/aosservice/response/AosResponseCallback;   （接口）
     *     public abstract onFailure(AosRequest, AosResponseException)V               ← 2 参、void
     *   Lcom/autonavi/minimap/net/Callback;   （zg5.run() 真正传进来的那个）
     *     public onFailure(AosRequest, AosResponseException)V                        ← 2 参、void
     * </pre>
     * 这两个 onFailure 都是 public、2 参、void —— 但**不能只认 2 参**：宿主每次大版本都可能换混淆
     * 形态（1 参的 `onFailure(Throwable)` 同样常见），所以按「名字 onFailure + 返回 void +
     * 形参 1~2 个 + 无基本类型」筛，逐个 setAccessible 试，成功一次就停。
     *
     * <p>不写死参数下标：3 参实现是 `(request, falcon.base.a, callback)`，回调永远在最后一个
     * （2 参转发时同理），所以从尾往头找。旧写法按正序扫**全部**参数，会先撞上 `falcon.base.a`
     * 那个中间对象，给无关对象也发一次 onFailure。
     *
     * <p>永远不抛：放锁失败只影响一条日志，绝不能把宿主搞挂。
     *
     * @return true = 失败回调已经发出去（锁会被释放）
     */
    private fun releaseCallback(args: Array<Any?>?): Boolean {
        if (args == null || args.isEmpty()) return false
        val request = args[0]
        val tried = StringBuilder()
        for (i in args.size - 1 downTo 0) {
            val cb = args[i]
            if (cb == null || cb === request) continue
            if (fireFailure(cb, request, tried)) return true
        }
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
