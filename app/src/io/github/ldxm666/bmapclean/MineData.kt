package io.github.ldxm666.bmapclean

import android.os.Bundle
import android.util.Log

import org.json.JSONArray
import org.json.JSONObject

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.Locale

import io.github.libxposed.api.XposedInterface

/**
 * 「我的」页 —— **Talos 数据层**过滤（v0.4.0 起替代视图层方案）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须走数据层（血泪结论，别回退到视图层）
 * ══════════════════════════════════════════════════════════════════════════
 * 「我的」页**不是原生布局**，而是百度 Talos 引擎跑的一个 JS 小程序：
 *
 *   磁盘证据（root 可直接读）：
 *     /data/data/com.baidu.BaiduMap/files/talos/dpmbundles/bdmap.mapclient.userCenter/
 *         userCore/1.0.92.1/index.android.bundle        ← 869KB，San 框架（s-if/s-for）页面
 *
 *   JS 侧数据链（bundle 实证）：
 *     getServerData()  → nativeBridge.fetch("…/usercenter/mine/page?…", {method:"post"}, 1)
 *     TalosEmitter.on(TalosEvent.bizDataPrefetchResult)  ← 原生预取同结构 JSON（另一条入口）
 *        → processServerData(json.data) → this.data.set("serverData", json.data)
 *        → 各卡片组件用 s-if 判断 serverData.<key> 决定渲染
 *
 *   卡片可见性**只由 serverData 的字段决定**（键名取自 bundle 中 serverData.<key> 的真实引用）：
 *     gold_coin_card → 天天领钱        finance       → 出行保 + 借钱融合卡
 *     voice_card/voice → 热门语音      car/truck/recommend_car → 我的车
 *     caricon → 导航车标               sport_card    → 百度运动
 *     shop → 我的店铺                  parking       → 停车卡
 *
 * 于是：**在 JSON 进入 JS 之前把键摘掉**，卡片的 s-if 恒假 → 组件根本不创建 →
 * 布局由 Yoga 从零算 → 既没有留白，也不会像 v0.1.7/0.1.8/0.3.0 那样误伤整页。
 *
 * 三个入口都挂（互为兜底，任何一个生效即可）：
 *   ① TalosEventEmitter#emit        —— 覆盖原生预取事件 bizdataprefetchresult
 *   ② NetworkingModule#w(OkHttpClient.Builder,int) —— 往 Talos 的 JS 网络栈里塞一个应用层
 *                                     Interceptor（只改 body，不碰请求）
 *   ③ dk2.g#a(emitter,int,String)   —— 非流式响应交付的最后一道，String 直改
 *
 * 失效安全：任何异常/配置读不到 = 原样放行；filterJson 只在 JSON 里出现已知键时才动手。
 */
class MineData private constructor() {

    companion object {

        @Volatile
        private var installed = false
        @Volatile
        private var sFiltered = 0
        @Volatile
        private var sGk = 0
        @Volatile
        private var sGkDumped = false
        @Volatile
        private var sShapeDumped = false
        @Volatile
        private var sPropsProbe = 0
        @Volatile
        private var sDumped = false
        private const val LOG_CHUNK = 2600

        /**
         * 真机把包里 JS 的 `s-if` 全部抄出来核对过（bundle @727000-734000 的卡片分发段），
         * **只有下面这几个字段是闸门**，摘掉它卡片就整张不渲染：
         *
         *   car        → serverData.car       && phoneInfo.gk !== '0' && cardLoadingStatus === 0
         *   voice_card → serverData.voice_card && …
         *   voice      → showOldVoiceCard && serverData.voice && …
         *
         * ⚠ 反面教材（**别加进来**，会拆掉卡片但闸门不假 → 组件仍渲染 → 模板取 undefined 崩）：
         *   gold_coin_card / finance / shop / caricon / sport_card —— 这些只是"内容"，不是 s-if 闸门。
         *   天天领钱 · 出行保+借钱 · 我的店铺 · 导航车标 四张卡的真闸门是 **phoneInfo.gk**（见 hookPhoneInfo）。
         */
        private val DATA_RULES: Array<Array<String>> = arrayOf(
                arrayOf("car",        Spec.K_MINE_CAR),
                arrayOf("voice_card", Spec.K_MINE_VOICE),
                arrayOf("voice",      Spec.K_MINE_VOICE)
        )

        /** JSON 里出现这些子串才认为"这份数据是我的页的" */
        private val SIGNATURES: Array<String> = arrayOf(
                "voice_card", "gold_coin_card", "\"new_user_entrance\"",
                "\"recommend_car\"", "\"caricon\"", "mine/page"
        )

        /** qt=ads 接口里属于「热门活动 / 资源位」的数组键（值清空 → JS 侧 campaignData 为空 → 卡不渲染） */
        private val AD_KEYS: Array<String> = arrayOf(
                "user_home_activity_banner_new", "user_home_ctb_card_banner", "campaignData", "campaignList"
        )

        @JvmStatic
        fun installed(): Boolean { return installed }

        @JvmStatic
        fun filteredCount(): Int { return sFiltered }

        // ══════════════════════════════════════════════════ 安装

        @JvmStatic
        fun install(cl: ClassLoader) {
            if (installed) return
            installed = true
            hookNetBuilder(cl);     // ① 唯一动数据的地方：Talos JS 网络栈的应用层拦截器（已真机验证）
            hookPhoneInfo(cl);      // ② 手机信息下发：往里补一个 gk=0（见下）
            // ③ hookInitialProps（initialProps.isCar=1）**已停用** —— v0.4.3 起「全民共建」改由
            //    MineJs 在 JS 层把 'contribution' 从 cardList 里摘掉：效果一样，但**不会**把页头
            //    那行文案变成车机版「一键连接手机」。方法保留只是留档，不再调用。
            // ⚠ 不再挂 dk2.g#a 那种"替换参数再 proceed"的钩子：
            //   ① 与本拦截器功能重叠；② 真机出现过 OkHttp Dispatcher 线程 FATAL EXCEPTION，
            //   ③ 参数替换依赖 API 102 的 Chain#getArgs/proceed(Object[])，没在真机上单独验证过。
            //   同一个 hook 点两次失败即熔断（本处已触发），只保留最小面。
        }

        /**
         * ③ 页面初始化参数（initialProps）—— **v0.4.3 起不再调用**（保留留档）。
         *
         * 这条路是通的（真机验证过 `mine props: packageName=bdmap.mapclient.userCenter initialProps.isCar=1`），
         * 但 `isCarPlay` 同时决定页头那行 `ifCarPlay ? '一键连接手机' : loginBtnText` ——
         * 未登录时会把页头变成车机版文案，代价太大。现在改用 {@link MineJs} 在 JS 层摘 cardList。
         *
         *   Bundle bundle = new Bundle();
         *   bundle.putInt("newHome", 1);
         *   bundle.putInt("isCar", CstmConfigFunc.isMapAutoChannel(ctx) ? 1 : 0);   // ← 手机上恒为 0
         *   bundle.putInt("isLogin", …); bundle.putInt("voice_engine_version", …);
         *   TalosManager.getTalosContainerViewManager().createTalosContainer(activity, params, bundle);
         *
         * JS 侧（bundle 实证）：`this.data.set("isCarPlay", Number(this.$app.initialProps.isCar))`
         * 「全民共建/反馈中心」卡的 s-if 是
         *   `cardName === 'contribution' && cardLoadingStatus === 0 && isCarPlay !== 1`
         * —— 也就是说 **isCar=1 是这张卡唯一的闸门**（它没有 serverData 字段可控）。
         *
         * Bundle 是**引用传递**，改造里就地 putInt 即可，不需要替换参数。
         * 只对 packageName 含 userCenter 的容器动手 → 其它 Talos 小程序（首页信息流等）完全不受影响。
         */
        private fun hookInitialProps(cl: ClassLoader) {
            val impls = arrayOf("vh2.d", "mm2.e", "ni2.h")
            for (i in 0 until impls.size) installPropsHook(H.cls(cl, impls[i]))
            installPropsHook(H.cls(cl, "com.baidu.talos.ITalosContainerViewManager\$a"))
        }

        private fun installPropsHook(c: Class<*>?) {
            if (c == null) return
            val m = H.method(c, "createTalosContainer", 3)
            if (m == null) return
            H.hook(m, "mine_props_" + c.simpleName, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val args = chain.getArgs()
                        if (args != null && args.size == 3 && args[1] != null
                                && args[2] is Bundle) {
                            val pkg = readPackageName(args[1]!!)
                            val b = args[2] as Bundle
                            if (Cfg.debug() && sPropsProbe < 8) {
                                sPropsProbe++
                                Cfg.log("mine props probe: impl=" + c.simpleName + " pkg=" + pkg
                                        + " isCar=" + b.getInt("isCar", -1)
                                        + " newHome=" + b.getInt("newHome", -1)
                                        + " buildVisible=" + Cfg.visible(Spec.K_MINE_BUILD, true))
                            }
                            if (isUserCenterPage(pkg, b)
                                    && !Cfg.visible(Spec.K_MINE_BUILD, Spec.defaultVisible(Spec.K_MINE_BUILD))) {
                                b.putInt("isCar", 1)
                                Cfg.log("mine props: packageName=" + pkg
                                        + " initialProps.isCar=1 → 全民共建卡 s-if 恒假")
                            }
                        }
                    } catch (t: Throwable) {
                        logSoft("props", t)
                    }
                    return chain.proceed()
                }
            })
        }        /** 读容器参数里的 packageName（Kotlin getter 优先，字段兜底；读不到就不动手 = 失效安全） */
        private fun readPackageName(params: Any): String? {
            try {
                val c = params.javaClass
                var k: Class<*>? = c
                while (k != null) {
                    val ms = k.declaredMethods
                    for (i in 0 until ms.size) {
                        val m = ms[i]
                        if (m.parameterTypes.size != 0 || m.returnType !== String::class.java) continue
                        if (m.name.lowercase(Locale.ROOT).indexOf("packagename") < 0) continue
                        try {
                            m.isAccessible = true
                            val v = m.invoke(params)
                            if (v is String) return v
                        } catch (ignored: Throwable) {}
                    }
                    val fs = k.declaredFields
                    for (i in 0 until fs.size) {
                        val f = fs[i]
                        if (f.type !== String::class.java) continue
                        if (f.name.lowercase(Locale.ROOT).indexOf("packagename") < 0) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(params)
                            if (v is String) return v
                        } catch (ignored: Throwable) {}
                    }
                    k = k.superclass
                }
            } catch (ignored: Throwable) {}
            return null
        }

        /**
         * 判断这个容器是不是「我的」页。
         * 首选参数里的 packageName（`bdmap.mapclient.userCenter`）；
         * 读不到时用**这一页独有的 bundle 标记**兜底 —— `initTalosView()` 里
         * `bundle.putInt("newHome", 1)` 和 `voice_engine_version` 只有它设置（反编译实证）。
         */
        private fun isUserCenterPage(pkg: String?, b: Bundle): Boolean {
            if (pkg != null && pkg.length > 0) {
                return pkg.lowercase(Locale.ROOT).indexOf("usercenter") >= 0
            }
            return b.getInt("newHome", -1) == 1 && b.containsKey("voice_engine_version")
        }

        /**
         * ④ 手机信息下发（`BMTLSGetNativeInfo.getPhoneInfo`）。
         *
         * 这是**四张运营卡共用的唯一闸门**：JS 里
         *   goldCoin(天天领钱) / dxmFinance(出行保+借钱) / merchant(我的店铺) / carLogo(导航车标)
         * 的 s-if 都是 `cardName === 'X' && phoneInfo.gk !== '0'` —— 把 gk 置 "0" 这四张一起消失。
         *
         * 做法：用动态代理包住 native 侧拿到的 Promise，只在 resolve() 的那一刻把载荷里的 gk 改掉，
         * 其它方法原样转发。参数替换用 API 102 的 `chain.getArgs()` / `proceed(Object[])`，
         * 并且挂在 PROTECTIVE 异常模式上 —— 万一运行时没有这两个方法，框架兜住异常走原逻辑，
         * 不会把目标 App 干掉（最多是本条规则不生效）。
         */
        private fun hookPhoneInfo(cl: ClassLoader) {
            try {
                val mod = H.cls(cl, "com.baidu.baidumaps.talos.modules.BMNativeInfoModule")
                val m = H.method(mod, "getPhoneInfo", 1)
                H.hook(m, "mine_gk", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        var na: Array<Any?>? = null
                        try {
                            if (Spec.mineEnabled() && Spec.opsHidden()) {
                                val args = chain.getArgs()
                                val p: Any? = if (args != null && args.size == 1) args[0] else null
                                val proxy = wrapPromise(p)
                                if (proxy != null && args != null) {
                                    val a: Array<Any?> = args.toTypedArray()
                                    a[0] = proxy
                                    na = a
                                }
                            }
                        } catch (t: Throwable) {
                            logSoft("phoneInfo", t)
                            na = null
                        }
                        if (na != null) {
                            try {
                                return chain.proceed(na)
                            } catch (e: NoSuchMethodError) {
                                // 运行时不支持参数替换（compile-only stub 与真接口不符时的兜底）
                                Cfg.log("mine gk: chain.proceed(args) unsupported -> fallback")
                            }
                        }
                        return chain.proceed()
                    }
                })
            } catch (t: Throwable) {
                logSoft("hookPhoneInfo", t)
            }
        }

        /** 把 Promise 包一层：只在 resolve/回填 时改 gk，其余方法直通 */
        private fun wrapPromise(promise: Any?): Any? {
            if (promise == null) return null
            var itf: Class<*>? = null
            val ifs = promise.javaClass.interfaces
            for (i in 0 until ifs.size) {
                if (!ifs[i].isInterface) continue
                if ("com.baidu.talos.core.callback.Promise" == ifs[i].name) { itf = ifs[i]; break }
                if (itf == null && declaresResolve(ifs[i])) itf = ifs[i]
            }
            if (itf == null) {
                // 只诊断一次：把真实类型打出来，下一次改这里就有依据（别删）
                if (!sShapeDumped) {
                    sShapeDumped = true
                    val sb = StringBuilder()
                    for (i in 0 until ifs.size) sb.append(ifs[i].name).append(' ')
                    Cfg.log("mine gk: promise shape unknown -> " + promise.javaClass.name
                            + " ifaces=[" + sb + "]")
                }
                return null
            }
            val api = itf
            return Proxy.newProxyInstance(api.classLoader, arrayOf<Class<*>>(api),
                    object : InvocationHandler {
                        override fun invoke(proxy: Any?, m2: Method, a2: Array<out Any?>?): Any? {
                            try {
                                if (a2 != null && a2.size == 1 && a2[0] != null
                                        && ("resolve" == m2.name
                                            || a2[0] is Map<*, *>)) {
                                    forceGk(a2 as Array<Any?>)
                                }
                            } catch (t: Throwable) {
                                logSoft("resolve", t)
                            }
                            try {
                                m2.isAccessible = true
                                return m2.invoke(promise, a2)
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.cause!!
                            }
                        }
                    })
        }

        private fun declaresResolve(itf: Class<*>): Boolean {
            try {
                val ms = itf.methods
                for (i in 0 until ms.size) {
                    if ("resolve" == ms[i].name && ms[i].parameterTypes.size == 1) return true
                }
            } catch (ignored: Throwable) {}
            return false
        }

        /** 就地把载荷里的 gk 改成 "0"（JSON 字符串 / Map / ParamMap 三种形态都试，真机实测是字符串） */
        private fun forceGk(holder: Array<Any?>) {
            val payload = holder[0]
            if (payload == null) return
            if (payload is String) {
                val s = payload
                if (s.length < 8 || s[0] != '{') return
                // 只认手机信息那一份（它有 sinan_ext / support_abi / sv 这些字段），别的字符串一律不碰
                if (s.indexOf("\"support_abi\"") < 0 && s.indexOf("\"sinan_ext\"") < 0
                        && s.indexOf("\"sv\"") < 0) {
                    if (!sShapeDumped) {
                        sShapeDumped = true
                        Cfg.log("mine gk: skip non-phone json len=" + s.length
                                + " head=" + s.substring(0, Math.min(120, s.length)))
                    }
                    return
                }
                val out: String
                if (s.indexOf("\"gk\"") < 0) {
                    // JS 里 phoneInfo.gk 默认是 ""（`gk:""`），比较式是 !== '0' → 恒真。
                    // 也就是说这个闸门在宿主 App 里**是死的**：native 从来没下发过 gk。
                    // 所以这里直接把这个字段**补进去**，四张卡（天天领钱/出行保+借钱/我的店铺/导航车标）一起消失。
                    out = "{\"gk\":\"0\"," + s.substring(1)
                } else {
                    out = replaceGk(s)
                }
                if (out != s) {
                    holder[0] = out
                    sGk++
                    Cfg.log("mine gk=0 injected into phoneInfo total=" + sGk)
                }
                return
            }
            if (payload is MutableMap<*, *>) {
                val map = payload as MutableMap<Any?, Any?>
                if (Cfg.debug() && !sGkDumped) {
                    sGkDumped = true
                    Cfg.log("phoneInfo keys=" + map.keys + " gk=" + map["gk"])
                }
                map.put("gk", "0")
                sGk++
                Cfg.log("mine gk=0 applied (ops cards off) total=" + sGk)
                return
            }
            try {
                call(payload, "putString", "gk", "0")
                sGk++
                Cfg.log("mine gk=0 applied (ParamMap) total=" + sGk)
            } catch (t: Throwable) {
                if (!sShapeDumped) {
                    sShapeDumped = true
                    Cfg.log("mine gk: payload type " + payload.javaClass.name + " unsupported: " + t)
                }
            }
        }

        /**
         * 只改 `"gk":<值>` 这一处（字符串或数字都归一成 "0"），**不整体重新序列化** ——
         * 原文里可能带百度自己的字段顺序/转义习惯，整体 JSON 化会引入无谓差异。
         * JS 侧判断是 `phoneInfo.gk !== '0'`，所以必须落成**字符串** "0"。
         */
        private fun replaceGk(s: String): String {
            var i = s.indexOf("\"gk\"")
            while (i >= 0) {
                val j = s.indexOf(':', i)
                if (j < 0) return s
                var k = j + 1
                while (k < s.length && Character.isWhitespace(s[k])) k++
                if (k < s.length && s[k] == '"') {
                    val end = s.indexOf('"', k + 1)
                    if (end > 0) return s.substring(0, k) + "\"0\"" + s.substring(end + 1)
                } else {
                    var end = k
                    while (end < s.length) {
                        val c = s[end]
                        if (Character.isDigit(c) || c == '-' || c == '+' || c == '.') end++
                        else break
                    }
                    if (end > k) return s.substring(0, k) + "\"0\"" + s.substring(end)
                }
                i = s.indexOf("\"gk\"", i + 4)
            }
            return s
        }        /** ① 原生 → JS 事件（覆盖 bizdataprefetchresult 这条预取入口） */
        private fun hookEmitter(cl: ClassLoader) {
            try {
                val em = H.cls(cl, "com.baidu.talos.core.render.events.TalosEventEmitter")
                val m = H.method(em, "emit", 2)
                H.hook(m, "mine_emit", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val a = args(chain)
                            if (a != null && a.size == 2 && a[1] != null) {
                                val name = if (a[0] is String) a[0] as String else ""
                                val low = name.lowercase(Locale.ROOT)
                                if (low.indexOf("prefetch") >= 0 || low.indexOf("usercenter") >= 0
                                        || low.indexOf("usercore") >= 0) {
                                    if (Cfg.debug()) Cfg.log("emit " + name + " payload="
                                            + a[1]!!.javaClass.name)
                                    val n = rewritePayload(a[1])
                                    if (n > 0) Cfg.log("mine emit filter: " + name + " n=" + n)
                                }
                            }
                        } catch (t: Throwable) {
                            logSoft("emit hook", t)
                        }
                        return chain.proceed()
                    }
                })
            } catch (t: Throwable) {
                logSoft("hookEmitter", t)
            }
        }

        /** ② Talos JS 网络栈：每次 sendRequest 都会调 w(builder, id)，顺手塞一个应用层拦截器 */
        private fun hookNetBuilder(cl: ClassLoader) {
            try {
                val nm = H.cls(cl, "com.baidu.talos.core.modules.network.NetworkingModule")
                val builderCls = H.cls(cl, "okhttp3.OkHttpClient\$Builder")
                val m = H.method(nm, "w", 2)
                H.hook(m, "mine_net", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val a = args(chain)
                            if (a != null && a.size == 2 && a[0] != null
                                    && (builderCls == null || builderCls.isInstance(a[0]))) {
                                addInterceptor(a[0]!!)
                            }
                        } catch (t: Throwable) {
                            logSoft("net builder", t)
                        }
                        return chain.proceed()
                    }
                })
            } catch (t: Throwable) {
                logSoft("hookNetBuilder", t)
            }
        }

        /** ③ 非流式响应交付：dk2.g#a(TalosEventEmitter,int,String) */
        private fun hookBodyOneShot(cl: ClassLoader) {
            try {
                val g = H.cls(cl, "dk2.g")
                val m = H.method(g, "a", 3)
                H.hook(m, "mine_body", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val list = chain.getArgs()
                            if (list != null && list.size == 3 && list[2] is String) {
                                val body = list[2] as String
                                val out = filterJson(body, "body")
                                if (out !== body) {
                                    val na: Array<Any?> = list.toTypedArray()
                                    na[2] = out
                                    return chain.proceed(na)
                                }
                            }
                        } catch (t: Throwable) {
                            logSoft("oneshot", t)
                        }
                        return chain.proceed()
                    }
                })
            } catch (t: Throwable) {
                logSoft("hookBodyOneShot", t)
            }
        }

        // ══════════════════════════════════════════════════ 网络拦截器（反射代理，无 okhttp 编译期依赖）

        private fun addInterceptor(builder: Any) {
            try {
                val cl: ClassLoader? = builder.javaClass.classLoader
                val itf = H.cls(cl, "okhttp3.Interceptor")
                if (itf == null) return
                val proxy = Proxy.newProxyInstance(cl, arrayOf<Class<*>>(itf), NetHandler(cl))
                for (mm in builder.javaClass.methods) {
                    val p = mm.parameterTypes
                    if (p.size == 1 && p[0].isAssignableFrom(itf) && "addInterceptor" == mm.name) {
                        mm.invoke(builder, proxy)
                        return
                    }
                }
            } catch (t: Throwable) {
                logSoft("addInterceptor", t)
            }
        }

        private class NetHandler(private val cl: ClassLoader?) : InvocationHandler {

            override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
                val n = method.name
                if ("intercept" == n && args != null && args.size == 1 && args[0] != null) {
                    val chain = args[0]!!
                    val request = call(chain, "request")
                    var response = call(chain, "proceed", request)
                    try {
                        val url = call(call(request, "url"), "toString").toString()
                        val low = url.lowercase(Locale.ROOT)
                        val mineUrl = low.indexOf("usercenter") >= 0 || low.indexOf("usercore") >= 0
                                || low.indexOf("mine/page") >= 0 || low.indexOf("qt=ads") >= 0
                                || low.indexOf("phpui2") >= 0
                        if (response != null && mineUrl) {
                            val body = call(response, "body")
                            if (body != null) {
                                val s = call(body, "string") as String?
                                if (Cfg.debug()) {
                                    Cfg.log("mine net url=" + url.substring(0, Math.min(110, url.length))
                                            + " len=" + (if (s == null) -1 else s.length))
                                }
                                if (s != null) {
                                    val out = filterJson(s, url)
                                    // ⚠ 必须**无条件**换新 body：body.string() 已经把原 source 读干/关闭，
                                    // 再把原 response 交回去 → 下游 NetworkingModule$c.onResponse 二次
                                    // string() 抛 IllegalStateException: closed → OkHttp Dispatcher 线程
                                    // FATAL EXCEPTION → 整个 App 闪退（v0.4.0 真机实测，堆栈见交付卡）。
                                    response = rebuild(response, body, out!!)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        logSoft("intercept", t)
                    }
                    return response
                }
                if ("equals" == n) return proxy === args!![0]
                if ("hashCode" == n) return System.identityHashCode(proxy)
                if ("toString" == n) return "BMapCleanInterceptor"
                return null
            }

            private fun rebuild(response: Any, oldBody: Any?, text: String): Any? {
                val cl2 = response.javaClass.classLoader
                val rb = H.cls(cl2, "okhttp3.ResponseBody")
                val mt = H.cls(cl2, "okhttp3.MediaType")
                if (rb == null) return response
                var contentType: Any? = null
                try { contentType = call(oldBody, "contentType") } catch (ignored: Throwable) {}
                var create: Method? = null
                var mediaFirst = true
                for (mm in rb.methods) {
                    if ("create" != mm.name || !Modifier.isStatic(mm.modifiers)) continue
                    val p = mm.parameterTypes
                    if (p.size != 2) continue
                    val a = (p[0] === Any::class.java || p[0] === mt || p[0].name == "okhttp3.MediaType")
                            && p[1] === String::class.java
                    val b = p[0] === String::class.java
                            && (p[1] === Any::class.java || p[1] === mt || p[1].name == "okhttp3.MediaType")
                    if (a) { create = mm; mediaFirst = true; break }
                    if (b) { create = mm; mediaFirst = false; break }
                }
                if (create == null) return response
                val newBody = if (mediaFirst) create.invoke(null, contentType, text)
                               else create.invoke(null, text, contentType)
                if (newBody == null) return response
                val b = call(response, "newBuilder")
                call(b, "body", newBody)
                // 头也要跟着改：长度变了（而且 gzip 已被 okhttp 透明解开），留着旧值会让下游误判
                removeHeader(b, "Content-Length")
                removeHeader(b, "Content-Encoding")
                return call(b, "build")
            }
        }        // ══════════════════════════════════════════════════ 载荷改写

        /** 就地改写事件载荷里的 JSON 字符串（Map / ParamMap 两种形态都试） */
        private fun rewritePayload(payload: Any?): Int {
            var n = 0
            if (payload is MutableMap<*, *>) {
                val map = payload as MutableMap<Any?, Any?>
                val keys = ArrayList<Any?>(map.keys)
                for (i in 0 until keys.size) {
                    val k = keys[i]
                    val v = map[k]
                    if (v is String) {
                        val out = filterJson(v, "event")
                        if (out !== v) { map.put(k, out); n++ }
                    }
                }
                return n
            }
            // com.baidu.talos.core.data.ParamMap：JS 侧读的就是 e.data
            try {
                val v = call(payload, "getString", "data")
                if (v is String) {
                    val out = filterJson(v, "event")
                    if (out !== v) { call(payload, "putString", "data", out); n++ }
                }
            } catch (ignored: Throwable) {}
            return n
        }

        /**
         * 核心过滤：把「我的」页不需要的卡片数据摘掉。
         * 非我的页数据 → 原样返回（引用比较即可判断有没有动过）。
         */
        @JvmStatic
        fun filterJson(json: String?, src: String?): String? {
            if (json == null || json.length < 32) return json
            if (!Spec.mineEnabled()) return json
            if (src != null && (src.indexOf("qt=ads") >= 0 || src.indexOf("phpui2") >= 0)) {
                return filterAds(json, src)
            }
            var sig = false
            for (i in 0 until SIGNATURES.size) {
                if (json.indexOf(SIGNATURES[i]) >= 0) { sig = true; break }
            }
            if (!sig) return json
            dumpOnce(src, json)
            try {
                val root = JSONObject(json)
                val data = root.optJSONObject("data")
                if (data == null) return json
                var dropped = 0
                val sb = StringBuilder()
                for (i in 0 until DATA_RULES.size) {
                    val key = DATA_RULES[i][0]
                    if (!Cfg.visible(DATA_RULES[i][1], Spec.defaultVisible(DATA_RULES[i][1]))) {
                        if (data.has(key)) {
                            data.remove(key)
                            dropped++
                            if (sb.length > 0) sb.append(',')
                            sb.append(key)
                        }
                    }
                }
                // 兜底：万一服务端用 cardList 控制卡片，按 name 一起摘
                val cards = data.optJSONArray("cardList")
                if (cards != null) {
                    for (i in cards.length() - 1 downTo 0) {
                        val c = cards.optJSONObject(i)
                        if (c == null) continue
                        val name = c.optString("name", "")
                        for (j in 0 until DATA_RULES.size) {
                            if (DATA_RULES[j][0].equals(name, ignoreCase = true)
                                    && !Cfg.visible(DATA_RULES[j][1], Spec.defaultVisible(DATA_RULES[j][1]))) {
                                cards.remove(i)
                                dropped++
                                break
                            }
                        }
                    }
                }
                if (dropped == 0) return json
                sFiltered++
                val out = root.toString()
                Cfg.log("mine data filter[" + src + "] dropped=" + dropped + " keys=" + sb
                        + " total=" + sFiltered + " len " + json.length + "->" + out.length)
                return out
            } catch (t: Throwable) {
                logSoft("filter", t)
                return json
            }
        }

        /**
         * qt=ads（`…/client/phpui2/?qt=ads&type=user_home_ctb_card_banner,user_home_activity_banner_new,user_home_icon&`）
         * —— 我的页的活动/资源位就在这一份里，**按 `ads.content[].type` 摘条目**（bundle @854911 实证）：
         *
         *   c.forEach(function(e){
         *     "user_home_ctb_card_banner"   === e.type && l.push(JSON.parse(e.data));
         *     "user_home_activity_banner_new" === e.type && d.push(e);   // → resourceBannerData → banner 卡
         *     "user_home_icon"              === e.type && u.push(e);     // → campaignData     → 热门活动卡
         *   }); e.data.assign({resourceBannerData: d, campaignData: u})
         *
         * 所以：数组少一个条目 → 对应卡片 s-if 的 length 为 0 → 整卡不渲染。
         */
        private fun filterAds(json: String, src: String?): String? {
            if (Cfg.visible(Spec.K_MINE_AD, Spec.defaultVisible(Spec.K_MINE_AD))) return json
            try {
                val root = JSONObject(json)
                val ads = root.optJSONObject("ads")
                if (ads == null) return json
                val content = ads.optJSONArray("content")
                if (content == null) return json
                var dropped = 0
                val sb = StringBuilder()
                for (i in content.length() - 1 downTo 0) {
                    val item = content.optJSONObject(i)
                    if (item == null) continue
                    val type = item.optString("type", "")
                    if ("user_home_icon" == type
                            || "user_home_activity_banner_new" == type
                            || "user_home_ctb_card_banner" == type) {
                        content.remove(i)
                        dropped++
                        sb.append(type).append(' ')
                    }
                }
                if (dropped == 0) return json
                sFiltered++
                val out = root.toString()
                Cfg.log("mine ads filter[" + src + "] dropped=" + dropped + " types=" + sb
                        + " total=" + sFiltered + " len " + json.length + "->" + out.length)
                return out
            } catch (t: Throwable) {
                logSoft("filterAds", t)
                return json
            }
        }

        /** 备用（当前不启用）：qt=ads 里按 key 清空数组的兜底实现 */
        private fun clearAds(node: Any?): Int {
            var n = 0
            if (node is JSONObject) {
                val o = node
                val keys = ArrayList<String>()
                val it = o.keys()
                while (it.hasNext()) keys.add(it.next())
                for (i in 0 until keys.size) {
                    val k = keys[i]
                    val v = o.opt(k)
                    if (v is JSONArray) {
                        var hit = false
                        for (j in 0 until AD_KEYS.size) {
                            if (AD_KEYS[j].equals(k, ignoreCase = true)) { hit = true; break }
                        }
                        if (!hit && k.lowercase(Locale.ROOT).indexOf("campaign") >= 0) hit = true
                        if (hit && v.length() > 0) {
                            try { o.put(k, JSONArray()); n++ } catch (ignored: Throwable) {}
                            continue
                        }
                    }
                    n += clearAds(v)
                }
            } else if (node is JSONArray) {
                val a = node
                for (i in 0 until a.length()) {
                    val v = a.opt(i)
                    if (v is JSONObject || v is JSONArray) n += clearAds(v)
                }
            }
            return n
        }

        /** 首次命中时把原始载荷打一条到 logcat（分片），用于核对真实字段；只打一次。 */
        private fun dumpOnce(src: String?, json: String) {
            if (sDumped) return
            if (!Cfg.debug()) return
            sDumped = true
            try {
                val total = json.length
                val chunks = (total + LOG_CHUNK - 1) / LOG_CHUNK
                Cfg.log("mine payload[" + src + "] len=" + total + " chunks=" + chunks)
                for (i in 0 until Math.min(chunks, 4)) {
                    val s = i * LOG_CHUNK
                    val e = Math.min(total, s + LOG_CHUNK)
                    Cfg.log("PAYLOAD#" + (i + 1) + "/" + chunks + " " + json.substring(s, e))
                }
            } catch (ignored: Throwable) {}
        }

        // ══════════════════════════════════════════════════ 反射小工具

        private fun args(chain: XposedInterface.Chain): Array<Any?>? {
            try {
                val l = chain.getArgs()
                return if (l == null) null else l.toTypedArray()
            } catch (t: Throwable) {
                return null
            }
        }

        private fun call(target: Any?, name: String): Any? {
            val m = find(target!!.javaClass, name, 0)
            m.isAccessible = true
            return m.invoke(target)
        }

        private fun call(target: Any?, name: String, a1: Any?): Any? {
            val m = find(target!!.javaClass, name, 1)
            m.isAccessible = true
            return m.invoke(target, a1)
        }

        private fun call(target: Any?, name: String, a1: Any?, a2: Any?): Any? {
            val m = find(target!!.javaClass, name, 2)
            m.isAccessible = true
            return m.invoke(target, a1, a2)
        }

        /** okhttp 的 Response.Builder#removeHeader(String) 是"删完返回自己"，拿返回值接着链式调用 */
        private fun removeHeader(builder: Any?, name: String) {
            try {
                val r = call(builder, "removeHeader", name)
                if (r != null) return
            } catch (ignored: Throwable) {}
            try {
                call(builder, "removeHeader", name)   // okhttp4 有返回 Unit 的重载
            } catch (ignored: Throwable) {}
        }

        private fun find(c: Class<*>, name: String, argc: Int): Method {
            var k: Class<*>? = c
            while (k != null) {
                for (m in k.declaredMethods) {
                    if (m.name == name && m.parameterTypes.size == argc) return m
                }
                k = k.superclass
            }
            throw NoSuchMethodException(c.name + "#" + name + "/" + argc)
        }

        private fun logSoft(where: String, t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "mine data " + where + " failed: " + t)
        }
    }
}