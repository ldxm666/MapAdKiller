package io.github.ldxm666.bmapclean

import android.content.Context
import android.util.Log
import android.view.ViewGroup

import java.lang.reflect.Method
import java.lang.reflect.Modifier

import io.github.libxposed.api.XposedInterface

/**
 * 百度广告 SDK 的**按前缀自动发现**拦截，各版本通用。
 *
 * <h3>为什么不能写死类名</h3>
 * {@code commonadprovider} 在 22.0.0 → 22.0.50 之间从「Gromore 专用包装」扩成了
 * 多 ADN 聚合层，loader 子包从 7 个涨到 12 个以上。22.0.50 实测（18 个 dex）：
 *
 * <pre>
 *   business  gromore  ms  octopus  qumeng  qumengCache
 *   recommend recommendThird  ruishi  taku  ubix  ubixCache  wangmai  ylh
 * </pre>
 *
 * 写死名单的后果：百度加一个新 ADN，模块少拦一家，那一家的广告就会漏出来。
 * 22.0.50 新增的 {@code ruishi / taku / wangmai / ylh} 与两个 Cache 变体，
 * 就是老名单（仅 7 个）完全漏掉的一批。
 *
 * <h3>发现策略：三层漏斗</h3>
 * <ol>
 *   <li><b>已知完整类名</b>（{@link #KNOWN}）—— 三代 APK 交叉核对过的最小集合，
 *       永远是第一拨，保证枚举渠道失效时仍然拦得住主流 ADN。</li>
 *   <li><b>按名字枚举</b>—— 遍历 ClassLoader 已加载类里 {@code *AdLoader} 结尾的
 *       顶层类。靠的是百度自己的命名习惯，不依赖任何混淆名。</li>
 *   <li><b>按接口枚举</b>—— 凡实现 {@code commonadprovider.api.IAdLoader} 的类
 *       一并纳入，即使名字不像 loader 也不漏。</li>
 * </ol>
 *
 * <p>三层只做加法、去重；某个类不存在就静默跳过，不影响其余。
 *
 * <h3>挂什么、不挂什么</h3>
 * 只挂 <b>ADN 回调完成方法 {@code I()}</b>，且一律 {@link #observe(String)}
 * （原样放行 + 首条日志）—— <b>绝不 VOID</b>。
 *
 * <p>反编译实证：{@code IAdLoader.I(...)} 是 onAdClose / onTimeOver 之类的
 * 回调出口，吞掉它 = 回调永不到达 = 卡开屏（v1.1.0 踩过并修好）。
 * 真正的断源在上游：{@code SplashAdManager} 的布尔闸门（见
 * {@link BaiduHooks#install}）与 {@code BMAdSplashProvider} 的加载入口。
 *
 * <h3>BMAd 开放封装层</h3>
 * {@code com.baidu.baidumaps.ad} 下 246 个类。这里只挑 {@code *Provider} 结尾的，
 * 并只挂<b>参数可识别的加载 / 绑定入口</b>：
 * <ul>
 *   <li>{@code c(Context, SplashAdConfig, *)} —— 开屏加载入口。22.0.0 与
 *       22.0.50 逐参数一致，是最可靠的断源点。</li>
 *   <li>{@code d(_, ViewGroup, _)} —— 广告视图绑定入口，第二参是容器。</li>
 * </ul>
 * 之所以不按 {@code c/d/x} 裸名字挂：22.0.0 有三个，22.0.50 只剩两个
 * （{@code x()} 被百度删了），按名挂只会在新版上静默 miss。
 * 这两个是 void 方法且在 provider 最外层，VOID 后宿主走自己的「没拿到广告」分支，
 * 不会卡住任何回调。
 */
class BaiduSdkBlock private constructor() {

    companion object {

        /** 聚合层根包名（非混淆，跨版本稳定）。 */
        private const val ROOT = "com.baidu.baidumaps.commonadprovider"
        /** BMAd 开放封装层根包名。 */
        private const val BMAD = "com.baidu.baidumaps.ad."

        /**
         * 各版本实证过的 loader 完整类名（22.0.0 / 22.0.40 / 22.0.50 / **22.1.0** 四代交叉核对）。
         *
         * <p><b>22.1.0 变化（18 dex / 141,023 类全表核对）：</b>
         * {@code commonadprovider} 从 308 个类、20 个子包，ADN 集合与 22.0.50 **完全一致**，
         * {@code AdType} 枚举一个都没加 —— 但 {@code recommend.RecommendAdLoader2}
         * **整个类没了**（只剩 {@code RecommendAdLoader1}）。留着它只会每次多记一次
         * miss，已删除。实测 {@code loaders observed=15 of 16} 就是这么来的。
         *
         * <p>真正新增的攻击面在**另一个引擎**：{@code com.baidu.baidumaps.ad.*}（246 个类），
         * 它有自己的 {@code BMapAdEngine} / {@code IBMapAdLoader}，40+ 个具体 loader 叫
         * {@code OctopusSplashLoader} / {@code CsjInterstitialLoader} / {@code UbixRewardLoader}
         * 这种 —— **没有一个以 {@code AdLoader} 结尾**，按名字枚举完全抓不到。
         * 那一层靠 {@link #blockBmadProviders} 在 {@code BMAd*Provider} 的
         * {@code c(Context, *SplashAdConfig, *)} / {@code d(*, ViewGroup, *)} 入口上断源，
         * 22.1.0 签名逐参数不变（反编译实证，见下）。
         */
        private val KNOWN = arrayOf(
                ROOT + ".api.IAdLoader",
                ROOT + ".business.BusinessAdLoader",
                ROOT + ".gromore.GromoreAdLoader",
                ROOT + ".ms.MeishuAdLoader",
                ROOT + ".octopus.OctopusAdLoader",
                ROOT + ".qumeng.QumengAdLoader",
                ROOT + ".qumeng.QumengCacheAdLoader",
                ROOT + ".recommend.RecommendAdLoader1",
                ROOT + ".recommend.third.ApiAdLoader",
                ROOT + ".ruishi.RuishiAdLoader",
                ROOT + ".taku.TakuAdLoader",
                ROOT + ".ubix.UBixAdLoader",
                ROOT + ".ubix.UbixCacheAdLoader",
                ROOT + ".wangmai.WangmaiAdLoader",
                ROOT + ".ylh.YlhAdLoader"
        )

        /**
         * 老引擎之外、22.1.0 实证存在且**绕过 {@code commonadprovider}** 的 loader。
         *
         * <p>这些只做 {@code observe}（原样放行 + 首条日志），不 VOID ——
         * 它们的回调语义没有逐个反编译确认过，吞掉有卡开屏的风险。
         * 作用是把「这一层被调用过」记进日志，方便下次百度改版时知道哪条链还活着。
         */
        private val EXTRA = arrayOf(
                BMAD + "api.IBMapAdLoader",
                BMAD + "BMAdSplashProvider",
                BMAD + "BMAdNativeProvider",
                BMAD + "BMAdRewardProvider",
                "com.baidu.baidumaps.tasksplash.TaskSplashAdLoader",
                "com.baidu.baidumaps.tasksplash.bmap.BMapTaskSplashAdLoader",
                "com.baidu.baidumaps.tasksplash.gromore.GromoreTaskSplashAdLoader",
                "com.baidu.baidumaps.taskweb.CustomBMapAdLoader",
                "com.baidu.baidumaps.mobads.interstitial.GromoreInterstitialAdLoader",
                "com.baidu.baidumaps.operation.popad.PopAdLoader"
        )

        /** 新引擎的包前缀：这些包下的 {@code *Loader} 按名字收进来观察。 */
        private val NEW_ENGINE_PREFIXES = arrayOf(
                BMAD,
                "com.baidu.baidumaps.tasksplash.",
                "com.baidu.baidumaps.taskweb.",
                "com.baidu.baidumaps.operation.popad."
        )

        /**
         * BMAd 开放封装层的入口（install 阶段就能挂：provider 类可解析）。
         *
         * @return 被 VOID 掉入口的 provider 类数
         */
        @JvmStatic
        fun installBmad(cl: ClassLoader): Int {
            return blockBmadProviders(cl)
        }

        /**
         * ADN loader 的入口。<b>必须在 loader 类已加载之后调用</b> ——
         * 见 {@link BaiduHooks} 里 hookAdSdkOnRequest 的说明：onPackageReady 早期
         * 调它会得到 0（类还没加载）。
         *
         * @return 实际挂上 callback 观测点的 loader 数
         */
        @JvmStatic
        fun installLoaders(cl: ClassLoader): Int {
            var hooked = 0
            val names = collect(cl)
            for (i in 0 until names.size) {
                val c = H.cls(cl, names[i])
                if (c == null) continue
                if (observeCallbacks(c, names[i]) > 0) {
                    hooked++
                }
            }
            H.log("baidu_adn loaders observed=" + hooked + " of " + names.size
                    + " (known=" + KNOWN.size + " extra=" + EXTRA.size + ")")
            return hooked
        }

        /**
         * 给一个 loader 类挂上它的<b>回调出口</b>观测点。
         *
         * <p><b>为什么不按 {@code I()} 裸名字挂。</b>22.0.0 的回调出口是
         * {@code IAdLoader.I()}（无参），22.0.50 已变成 {@code I(Z)V} —— 同一个方法名
         * 换了参数表。按 {@code H.method(c, "I", 0)} 找会得到 null，
         * 于是整整一层防线静默失效（实测 loaders=0）。
         *
         * <p>改用<b>签名形状</b>：方法名 {@code I} 且参数表为空或单个 boolean。
         * 这两种形态在反编译里都是“广告流程结束/超时”的回调出口（语义未变），
         * 而且 {@code I} 这个单字母名在 22.0.0 与 22.0.50 都在同一个接口上，
         * 是比参数表更稳的锚。
         *
         * <p>只 observe 不 VOID：这些是宿主在等的回调，吞掉就卡开屏。
         *
         * @return 挂上的方法数
         */
        private fun observeCallbacks(c: Class<*>, fullName: String): Int {
            val all: Array<Method>
            try {
                all = c.methods          // getMethods：含接口继承来的默认实现
            } catch (t: Throwable) {
                return 0
            }
            var n = 0
            for (i in 0 until all.size) {
                val m = all[i]
                if ("I" != m.name) continue
                val ps = m.parameterTypes
                val voidPlain = ps.size == 0
                val voidBool = ps.size == 1 && ps[0] === java.lang.Boolean.TYPE
                if (!voidPlain && !voidBool) continue
                if (H.hook(m, "baidu_adn_" + tail(fullName) + "_I" + ps.size,
                        observe(fullName + ".I()" + (if (ps.size == 1) "(Z)" else "()")))) {
                    n++
                }
            }
            return n
        }

        private fun collect(cl: ClassLoader): List<String> {
            val out = ArrayList<String>()
            for (i in 0 until KNOWN.size) {
                if (!out.contains(KNOWN[i])) out.add(KNOWN[i])
            }
            for (i in 0 until EXTRA.size) {
                if (!out.contains(EXTRA[i])) out.add(EXTRA[i])
            }
            val found = discover(cl)       // 只枚举一次
            for (i in 0 until found.size) {
                if (!out.contains(found[i])) out.add(found[i])
            }
            return out
        }

        /** 按名字 + 按接口两条腿枚举已加载的 loader。 */
        private fun discover(cl: ClassLoader): List<String> {
            val out = ArrayList<String>()
            try {
                val api = H.cls(cl, ROOT + ".api.IAdLoader")
                val loaded = loadedClasses(cl)
                for (i in 0 until loaded.size) {
                    val k = loaded[i]
                    val name: String? = k.name
                    if (name == null || !name.startsWith(ROOT + ".")) continue
                    if (name.indexOf('$') >= 0) continue              // 跳过合成/匿名类
                    val byName = name.endsWith("AdLoader")
                    val byApi = api != null && name != ROOT + ".api.IAdLoader"
                            && api.isAssignableFrom(k)
                    if (byName || byApi) out.add(name)
                }
                // 新引擎（22.1.0 起）：类名是 *SplashLoader / *InterstitialLoader / *RewardLoader…
                // 一律以 Loader 结尾，但**不以 AdLoader 结尾**，上面那条腿抓不到。
                for (i in 0 until loaded.size) {
                    val k = loaded[i]
                    val name: String? = k.name
                    if (name == null || name.indexOf('$') >= 0) continue
                    if (!name.endsWith("Loader")) continue
                    var inNewEngine = false
                    for (p in 0 until NEW_ENGINE_PREFIXES.size) {
                        if (name.startsWith(NEW_ENGINE_PREFIXES[p])) { inNewEngine = true; break }
                    }
                    if (inNewEngine && !out.contains(name)) out.add(name)
                }
            } catch (ignored: Throwable) {
                // 发现失败不是错误：KNOWN 名单仍然生效。
            }
            return out
        }

        // ── 第二层：BMAd provider 的加载 / 绑定入口 ────────────────────────

        /** @return 被 VOID 掉入口的 provider 类数 */
        private fun blockBmadProviders(cl: ClassLoader): Int {
            val providers = ArrayList<Class<*>>()
            val loaded = loadedClasses(cl)
            for (i in 0 until loaded.size) {
                val k = loaded[i]
                val name: String? = k.name
                if (name == null || !name.startsWith(BMAD)) continue
                if (name.indexOf('$') >= 0) continue
                if (!name.endsWith("Provider")) continue
                if (!providers.contains(k)) providers.add(k)
            }
            if (providers.isEmpty()) {
                val fallback = arrayOf(
                        BMAD + "BMAdSplashProvider",
                        BMAD + "BMAdNativeProvider",
                        BMAD + "BMAdRewardProvider"
                )
                for (i in 0 until fallback.size) {
                    val c = H.cls(cl, fallback[i])
                    if (c != null && !providers.contains(c)) providers.add(c)
                }
            }

            var blocked = 0
            for (i in 0 until providers.size) {
                val c = providers[i]
                val all: Array<Method>
                try {
                    all = c.declaredMethods
                } catch (t: Throwable) {
                    continue
                }
                var n = 0
                for (j in 0 until all.size) {
                    val m = all[j]
                    if (Modifier.isAbstract(m.modifiers)) continue
                    val ps = m.parameterTypes
                    if (ps.size < 2) continue
                    val load = Context::class.java.isAssignableFrom(ps[0])
                            && ps[1].name.endsWith(".SplashAdConfig")
                    val bind = ViewGroup::class.java.isAssignableFrom(ps[1])
                    if (!load && !bind) continue
                    if (H.hook(m, "baidu_bmad_" + c.simpleName + "_" + m.name + j, VOID)) {
                        n++
                    }
                }
                if (n > 0) {
                    blocked++
                }
            }
            return blocked
        }

        // ── 共用工具 ───────────────────────────────────────────────────────

        /** void 方法统一吞掉：provider 最外层入口，宿主走自己的“没拿到广告”分支。 */
        private val VOID: XposedInterface.Hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                return null
            }
        }

        /** 原样放行 + 首条日志；用来观察而绝不打断回调链。 */
        private fun observe(what: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                private var once = false
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    if (!once) {
                        once = true
                        H.log(Log.INFO, "BMapClean", "OBSERVE " + what)
                    }
                    return r
                }
            }
        }

        /**
         * 取 ClassLoader 已加载的类集合。
         *
         * <p>{@code ClassLoader.classes} 是 {@code dalvik.system.BaseDexClassLoader}
         * 的 package-private 字段，普通 API 拿不到，只能反射读。
         * 读不到（字段改名 / 无权限）就返回空列表 —— 调用方各自带 KNOWN 名单兜底，
         * 功能不少，只是 newest ADN 会漏。
         */
        private fun loadedClasses(cl: ClassLoader?): List<Class<*>> {
            val out = ArrayList<Class<*>>()
            if (cl == null) return out
            try {
                val f = ClassLoader::class.java.getDeclaredField("classes")
                f.isAccessible = true
                val v = f.get(cl)
                if (v is List<*>) {
                    for (o in v) {
                        if (o is Class<*>) out.add(o)
                    }
                }
            } catch (ignored: Throwable) {
                // 静默降级。
            }
            return out
        }

        /** 取完整类名的最后一段。 */
        private fun tail(full: String): String {
            val dot = full.lastIndexOf('.')
            return if (dot < 0) full else full.substring(dot + 1)
        }
    }
}
