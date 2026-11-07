package io.github.ldxm666.bmapclean

import android.view.View

import io.github.libxposed.api.XposedInterface

/**
 * 路线页（地铁 / 公交方案详情）上的浮层挂件与推荐位。
 *
 * <h3>锚点怎么来的</h3>
 * 全部来自 22.1.0 的**资源实证**，不是猜名字：
 * <ul>
 *   <li>地图左下角那两个挂件在同一个布局 {@code res/y3G.xml} 里互为兄弟，
 *       aapt2 解析出的资源名是 {@code layout/bus_default_map_layout} (0x7f0e02d4)：
 *       <pre>
 *       @ll_map_buttons_view_group
 *         ├─ com.baidu.walknavi.widget.CarbonBallView          @carbon_ball_view  ← 低碳能量
 *         └─ …route.footbike.widget.PubtravBannerFloatingEntryView @carbon_entry ← 一键穿越
 *       </pre>
 *       宿主类是 {@code com.baidu.baidumaps.route.bus.widget.BusDefaultMapLayout}
 *       （superclass {@code SimpleMapLayout}，内含 {@code CarbonBallView$OnClickListener}）。</li>
 *   <li>路线页底部那张「广告 / 点击查看详情→」白卡，宿主是
 *       {@code com.baidu.baidumaps.talos.view.advert.RNAdvertContainer}（运行时视图树实测
 *       1080x433 @ y1899，正好盖住 x 广告角标 y2161 与 点击查看详情 y2246）。
 *       反面教材：{@code com.baidu.baidumaps.integratedads.*} 在这个 APK 里**根本不存在** ——
 *       DEX 里只有 {@code integratedads} 这个字符串，没有任何该包下的类型描述符。
 *       照名字挂 updateViewData/bindAd 全是静默空钩子，还会打出假的 banner=4。</li>
 *   <li>「探索××周边」三图卡是 {@code solutiondetail.BSDLAiTalosCard}（Talos 自绘，没有资源 id），
 *       「特惠打车」领券条是 {@code solutiondetail/taxiremind} 下的 {@code TaxiCouponViewBase} 家族。</li>
 * </ul>
 *
 * <h3>为什么用构造器挂，而不是挂某个方法名</h3>
 * R8 会把方法名混淆成 {@code a/b/c}，每次发版都变；但 **{@code <init>} 永远叫 {@code <init>}**。
 * 在这几个类的构造器上挂「创建即 GONE」，既不用猜方法名，也不会误伤别的视图。
 * 构造完宿主还可能把可见性改回来，所以再挂一个 attach 监听补一刀。
 *
 * <h3>顶部横幅轮播为什么在数据层断</h3>
 * 照高德那套（{@code AmapMapWidget} 在 presenter 的 showActivity 上断源）：
 * {@code BusResultTalosCard.sendBusUpdateRouteData2Talos(Map)} 是「服务端数据 → Talos 渲染」的
 * 唯一入口，进去把 {@code bus} 那个 247KB JSON 字符串里的 {@code notices} 切掉，
 * JS 排版时就没有这个节点 —— 不占位、兄弟自然上移，不用藏、不用平移、不留白。
 */
class BaiduRoute private constructor() {

    companion object {

        /** install() 传进来的 ClassLoader，供需要延迟解析类的地方复用。 */
        private val CLASSLOADER_HOLDER = ClassLoaderHolder()

        private class ClassLoaderHolder {
            @Volatile
            var cl: ClassLoader? = null
        }

        /** 创建即隐藏的类 → 对应的开关。 */
        private val HIDE: Array<Array<String>> = arrayOf(
                arrayOf("com.baidu.walknavi.widget.CarbonBallView", Spec.K_ROUTE_WIDGET),
                arrayOf("com.baidu.baidumaps.route.footbike.widget.PubtravBannerFloatingEntryView", Spec.K_ROUTE_WIDGET),
                // 路线页底部那张「点击查看详情→ / 广告」白卡。运行时视图树实证：
                // com.baidu.baidumaps.talos.view.advert.RNAdvertContainer 1080x433 @ y1899，
                // 正好覆盖 x 广告角标(y2161) 与 点击查看详情(y2246)。Talos RN 自绘，无资源 id。
                arrayOf("com.baidu.baidumaps.talos.view.advert.RNAdvertContainer", Spec.K_ROUTE_AD),
                // 「探索××周边」：布局实证是 route.busnearby 这一族（4 个类都挂，覆盖
                // 列表态/详情态/子视图三种形态），外加 Talos 卡片形态。
                arrayOf("com.baidu.baidumaps.route.busnearby.widget.BusNearbyWidget", Spec.K_ROUTE_EXPLORE),
                arrayOf("com.baidu.baidumaps.route.busnearby.widget.BusNearbyListView", Spec.K_ROUTE_EXPLORE),
                arrayOf("com.baidu.baidumaps.route.busnearby.widget.BusNearbyDetailWidget", Spec.K_ROUTE_EXPLORE),
                arrayOf("com.baidu.baidumaps.route.busnearby.widget.BusNearbySubWidget", Spec.K_ROUTE_EXPLORE),
                arrayOf("com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLAiTalosCard", Spec.K_ROUTE_EXPLORE),
                arrayOf("com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLTalosCard", Spec.K_ROUTE_EXPLORE),
                arrayOf("com.baidu.baidumaps.route.bus.widget.taxiremind.TaxiCouponViewBase", Spec.K_ROUTE_TAXI),
                arrayOf("com.baidu.baidumaps.route.bus.widget.taxiremind.HorizontalTaxiCouponView", Spec.K_ROUTE_TAXI),
                arrayOf("com.baidu.baidumaps.route.bus.widget.taxiremind.VerticalTaxiCouponView", Spec.K_ROUTE_TAXI),
                arrayOf("com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLTaxiItemCouponView", Spec.K_ROUTE_TAXI),
                arrayOf("com.baidu.baidumaps.route.bus.widget.solutiondetail.BSDLTaxiItem", Spec.K_ROUTE_TAXI),
                // 底部操作栏的「第 4 个按钮」= 打车（带「特惠」角标）。
                // 实证：BSDPageBottomBar 底部一排是 收藏/截图/分享/[FourthView]/关注路线/开始导航，
                // 这个槽位由 BSDPageBottomFourthView + BSDPFourthButtonUtils +
                // BusDetailBottomFourthButtonConfigModel 共同驱动，类名直说它就是第四格。
                arrayOf("com.baidu.baidumaps.route.bus.widget.BSDPageBottomFourthView", Spec.K_ROUTE_TAXI),
                // 底部打车条（布局实证：route.footbike.widget.banner.WalkBikeBottomBarTaxiView）
                arrayOf("com.baidu.baidumaps.route.footbike.widget.banner.WalkBikeBottomBarTaxiView", Spec.K_ROUTE_TAXI)
        )

        /** 首页出行方式快捷格：{@code layout/aihome_route_choose_tag_uicomponent} = 0x7f0e00d1。 */
        private const val LAYOUT_ROUTE_CHOOSE_TAG = 0x7f0e00d1

        /** 要摘掉的格子文案。这一格没有 id，只有服务端下发的标题，只能按文案认。 */
        private val BLOCKED_LABELS: Array<String> = arrayOf("古今地图")

        /**
         * 摘掉首页出行方式快捷格里的「古今地图」。
         *
         * <p>取证链：整棵首页视图树里 {@code ShortCutRow #7f02405a row3_container} 的五个槽位
         * 依次是「步行 / 骑行 / 古今地图 / 空 / 空」，每格结构固定为
         * {@code FrameLayout > (BMLottieAnimationView image, TextView #7f0248be text)}。
         * 这个 {@code ShortCutRow} 是 {@code com.baidu.baidumaps.duhelper.view.ShortCutRow}，
         * 所在布局 {@code res/YlX.xml} = {@code aihome_route_choose_tag_uicomponent}。
         */
        @JvmStatic
        private fun installShortcutFilter() {
            // 不再挂 LayoutInflater：实测 aihome_route_choose_tag_uicomponent 并不是通过
            // inflate(int, ViewGroup, boolean) 出来的（同一个钩子对搜索框布局有效，对它无效）。
            // 改挂 ShortCutRow 自己的构造器 —— <init> 不会被 R8 改名，且与布局加载路径无关。
            val row = H.cls(CLASSLOADER_HOLDER.cl, "com.baidu.baidumaps.duhelper.view.ShortCutRow")
            if (row == null) return
            for (ctor in row.declaredConstructors) {
                H.hook(ctor, "baidu_shortcut_row", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        try {
                            val v = chain.thisObject as View
                            val h = android.os.Handler(android.os.Looper.getMainLooper())
                            // 格子的文案是配置下发之后才填的，构造完立刻扫是空的。
                            val delays = intArrayOf(400, 1200, 2600, 5000)
                            for (i in 0 until delays.size) {
                                h.postDelayed(object : Runnable {
                                    override fun run() {
                                        if (Spec.effectiveVisible(Spec.K_ROUTE_GUJIN, Spec.HOOK_READ)) return
                                        if (hideBlockedLabels(v, 0) > 0) report("ShortCutRow", "gujin_hidden")
                                    }
                                }, delays[i].toLong())
                            }
                        } catch (ignored: Throwable) {}
                        return r
                    }
                })
            }
        }

        /** 递归找文案命中的 TextView，把它所在格子藏掉。返回处理个数。 */
        @JvmStatic
        private fun hideBlockedLabels(v: View?, depth: Int): Int {
            if (v == null || depth > 8) return 0
            var n = 0
            if (v is android.widget.TextView) {
                val t = v.text
                if (t != null) {
                    val text = t.toString().trim()
                    for (i in 0 until BLOCKED_LABELS.size) {
                        if (text == BLOCKED_LABELS[i]) {
                            val parent = v.parent
                            removeAndCollapse(if (parent is View) parent else v)
                            return 1
                        }
                    }
                }
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) n += hideBlockedLabels(v.getChildAt(i), depth + 1)
            }
            return n
        }

        /**
         * 数据层摘除：在 {@code BusResultTalosCard.sendBusUpdateRouteData2Talos(Map)} 上动手。
         *
         * <p>这是「把数据喂给 Talos 渲染」的唯一入口（同族还有 {@code jsonToMap(JSONObject)}、
         * {@code setData(Bus,Z,DmBusResultModel)}、 {@code setDataInternal(...)}）。
         * 在这一步把横幅节点从 Map 里摘掉，Talos 排版时它根本不存在 ——
         * 上方不留白、下方也不留白，一次到位。
         *
         * <p>为什么不在视图层打补丁：Talos/RN 用绝对坐标布局，子节点 GONE 之后兄弟不上移、
         * 父容器高度也不会变，只能手工上移 + 反算高度，而 RN 每趟布局都会覆盖回去。
         * 实测那套只能消掉上方空白，下方会留下等高的空档。
         */
        private val STRIP_LOG = java.util.concurrent.atomic.AtomicBoolean(false)

        @JvmStatic
        private fun installTalosDataFilter(cl: ClassLoader) {
            val card = H.cls(cl, "com.baidu.baidumaps.route.bus.card.BusResultTalosCard")
            if (card == null) {
                H.log(android.util.Log.INFO, MainHook.TAG, "event=baidu_strip cardnull=1")
                return
            }
            var n = 0
            for (m in card.declaredMethods) {
                if (m.name != "sendBusUpdateRouteData2Talos") continue
                H.hook(m, "route.data.strip", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        var r = -9
                        try {
                            if (!Spec.effectiveVisible(Spec.K_ROUTE_AD, Spec.HOOK_READ)) {
                                val a0 = chain.getArg(0)
                                r = stripNotices(a0)
                            }
                        } catch (t: Throwable) {
                            r = -2
                        }
                        if (STRIP_LOG.compareAndSet(false, true)) {
                            H.log(android.util.Log.INFO, MainHook.TAG, "event=baidu_strip r=" + r)
                        }
                        return chain.proceed()
                    }
                })
                n++
            }
            H.log(android.util.Log.INFO, MainHook.TAG, "event=baidu_strip hooks=" + n)
        }

        /**
         * 从数据源头摘掉路线页的横幅轮播 —— 只摘 {@code notices}，别的一律不碰。
         *
         * <p>证据链：横幅文案「真的没想到，原来这里有这么多攻略」出现在
         * {@code bus} 的第 1046 字节，路径 {@code bus.notices[].prompts[].text}，
         * 同级还有 {@code icon_url} / {@code jump_url}，正好对上视图树里那张
         * ReactImageView + ReactTextView + 箭头的 289px 卡片。
         *
         * <p>在数据交给 Talos 之前摘掉，JS 排版时就没有这个节点 ——
         * 不占位、兄弟自然上移，不用藏、不用平移、不留白。
         *
         * <p>上一版按「键名含 tips/guide/recommend」批量删，把公共交通页正文一起删了，
         * 整页变空；这一版只认 notices 一个键。
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        private fun stripNotices(arg: Any?): Int {
            if (arg !is Map<*, *>) return -3
            val m = arg as MutableMap<Any?, Any?>
            val bus = m["bus"]
            if (bus !is String) return -4
            val input = bus
            val out = stripNoticesJson(input)
            if (out.length == input.length) return 0
            try {
                m["bus"] = out
            } catch (t: Throwable) {
                return -8
            }
            return 1
        }

        private const val NOTICES_KEY = "\"notices\""

        /**
         * 从 bus 的 JSON 文本里切掉顶层的 {@code notices} 键。
         *
         * <p>{@code bus} 是 {@code java.lang.String}（实测 247,015 字符的 JSON），
         * 所以这里是字符串手术，不是反射，也不是重新序列化 ——
         * 只切一段区间，其余字节原样保留，渲染层看到的东西和服务器下发的一模一样。
         *
         * <p>重新 parse + toString 会改转义（\u003d 之类）和键序，RN 侧可能因此重新布局，
         * 所以宁可自己扫括号。
         */
        @JvmStatic
        private fun stripNoticesJson(s: String): String {
            val n = s.length
            var idx = s.indexOf(NOTICES_KEY)
            while (idx >= 0) {
                var p = idx + NOTICES_KEY.length
                while (p < n && (s[p] == ' ' || s[p] == '\t')) p++
                if (p < n && s[p] == ':' && jsonDepthAt(s, idx) == 1) {
                    var v = p + 1
                    while (v < n && (s[v] == ' ' || s[v] == '\t')) v++
                    val e = jsonValueEnd(s, v)
                    if (e > v) return jsonCut(s, idx, e)
                }
                idx = s.indexOf(NOTICES_KEY, idx + NOTICES_KEY.length)
            }
            return s
        }

        /** 从 0 到 pos 的括号净深度，字符串内部的括号不算。顶层对象的键 = 1。 */
        @JvmStatic
        private fun jsonDepthAt(s: String, pos: Int): Int {
            var d = 0
            var inStr = false
            var i = 0
            while (i < pos) {
                val c = s[i]
                if (inStr) {
                    if (c == '\\') i++
                    else if (c == '"') inStr = false
                } else if (c == '"') {
                    inStr = true
                } else if (c == '{' || c == '[') {
                    d++
                } else if (c == '}' || c == ']') {
                    d--
                }
                i++
            }
            return d
        }

        /** 返回值的结束下标（不含）；返回 -1 表示不是复合值。 */
        @JvmStatic
        private fun jsonValueEnd(s: String, v: Int): Int {
            val n = s.length
            if (v >= n) return -1
            val c = s[v]
            if (c != '[' && c != '{') return -1
            var d = 0
            var inStr = false
            var i = v
            while (i < n) {
                val x = s[i]
                if (inStr) {
                    if (x == '\\') i++
                    else if (x == '"') inStr = false
                    i++
                    continue
                }
                if (x == '"') {
                    inStr = true
                    i++
                    continue
                }
                if (x == '[' || x == '{') d++
                else if (x == ']' || x == '}') {
                    d--
                    if (d == 0) return i + 1
                }
                i++
            }
            return -1
        }

        /** 切掉 [start,end)，顺手吞掉相邻的一个逗号，保证 JSON 仍然合法。 */
        @JvmStatic
        private fun jsonCut(s: String, start: Int, end: Int): String {
            val n = s.length
            var a = start
            var b = end
            var j = b
            while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
            if (j < n && s[j] == ',') {
                b = j + 1
            } else {
                var k = a - 1
                while (k >= 0 && (s[k] == ' ' || s[k] == '\t')) k--
                if (k >= 0 && s[k] == ',') a = k
            }
            return s.substring(0, a) + s.substring(b)
        }

        /**
         * 原生广告壳（BannerAdView / RotateBannerAdView）的断源。
         *
         * <p>同一个广告位有两套渲染，实测两套都在这个 APK 里：
         * <ul>
         *   <li>Talos RN 模板：{@code talos.view.advert.RNAdvertContainer}
         *       （classes.dex 与 classes13.dex 都有，已由 HIDE 表在构造时收掉）</li>
         *   <li>原生壳：{@code PubTravelBaseHomeCard.showAd(BannerAdView)} →
         *       {@code RotateBannerAdView.attachAdView()}，壳是
         *       {@code integratedads.view.BannerAdView}（classes12.dex，父类 BaseAdView）</li>
         * </ul>
         * RN 那套是「视图建出来再收」，这一套更进一步：在**取数**那一步就断，
         * 连网络请求都不发，也不会触发广告曝光回调。
         *
         * <p>只挂 {@code fetchAd()} 与 {@code showAd(1参数)} 这两个具名方法 ——
         * 这两个名字在 classes13.dex 里是明文，没被 R8 混淆；
         * 参数类型（integratedads.ad.b）解析不到也不影响，按名字+参数个数匹配。
         */
        @JvmStatic
        private fun blockNativeAd(cl: ClassLoader): Int {
            val card = H.cls(cl, "com.baidu.baidumaps.route.pubtravel.card.PubTravelBaseHomeCard")
            if (card == null) return 0
            var n = 0
            for (m in card.declaredMethods) {
                val name = m.name
                val pc = m.parameterTypes.size
                val fetch = name == "fetchAd" && pc == 0
                val show = name == "showAd" && pc == 1
                if (!fetch && !show) continue
                val hk = object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (Spec.effectiveVisible(Spec.K_ROUTE_AD, Spec.HOOK_READ)) return chain.proceed()
                        return null
                    }
                }
                if (H.hook(m, "baidu_ad_" + name, hk)) n++
            }
            return n
        }

        @JvmStatic
        fun install(cl: ClassLoader) {
            CLASSLOADER_HOLDER.cl = cl
            val landed = StringBuilder()
            var hit = 0
            for (i in 0 until HIDE.size) {
                if (hideOnCreate(cl, HIDE[i][0], HIDE[i][1])) {
                    hit++
                    landed.append(if (landed.length == 0) "" else ",").append(simple(HIDE[i][0]))
                }
            }
            val adhook = blockNativeAd(cl)
            installShortcutFilter()
            // 全局 setText 探针已拆除：它会挂在整个 App 的每一个 TextView 上，实测明显卡顿。
            installTalosDataFilter(cl)  // 数据层摘横幅：只删 bus.notices，别的不动
            // 原因：在 sendBusUpdateRouteData2Talos(Map) 上按「键名含 tips/guide/recommend…」
            // 递归删除，把公共交通页的正文数据一起删了，整页变空。
            // 数据层这条路需要精确知道横幅字段名，靠键名猜必然误伤 —— 保留方法体备查，
            // 但不再挂载。横幅仍由视图层兜住。
            // 一行交代清楚：哪些锚点在这一版还在、哪些没了。缺了就是这版改了类名，
            // 拿这行去 DEX 里重新定位，不用靠猜。
            H.log(android.util.Log.INFO, MainHook.TAG, "baidu_route widgets=" + hit + "/" + HIDE.size
                    + " ad=" + adhook + " landed=" + landed)
        }

        @JvmStatic
        private fun simple(className: String): String {
            val i = className.lastIndexOf('.')
            return if (i < 0) className else className.substring(i + 1)
        }

        /**
         * 在该类的构造器上挂「创建即 GONE」。
         *
         * <p>{@code H.method(c, "<init>", -1)} 的 {@code -1} 表示不限参数个数 ——
         * 这几个类都有多个重载（带 attrs / 不带 attrs），必须全挂上。
         */
        @JvmStatic
        private fun hideOnCreate(cl: ClassLoader, className: String, key: String): Boolean {
            val c = H.cls(cl, className)
            if (c == null || !View::class.java.isAssignableFrom(c)) return false
            return hook(c, key) > 0
        }

        /** 每个类只报一次，避免刷屏。 */
        private val REPORTED: MutableSet<String> = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

        @JvmStatic
        private fun report(cls: String, what: String) {
            if (REPORTED.add(cls + "/" + what)) {
                H.log(android.util.Log.INFO, MainHook.TAG, "baidu_route_" + what + " cls=" + cls)
            }
        }

        @JvmStatic
        private fun hook(c: Class<*>, key: String): Int {
            val simple = c.simpleName
            // 挂满**全部** <init> 重载：这几个类都有 (Context) 和 (Context,AttributeSet) 两个，
            // H.method 只返回第一个匹配，漏掉的那个重载会让控件照常出现。
            var n = 0
            for (m in c.declaredConstructors) {
                H.hook(m, "baidu_route_" + c.simpleName + "_" + n, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        if (Spec.effectiveVisible(key, Spec.HOOK_READ)) return result
                        val view = chain.thisObject as View
                        BaiduHooks.hide(view)
                        // 构造完立刻复查一次：宿主常在 init/onFinishInflate 里把可见性改回来，
                        // 只藏一次等于没藏。这里在下一帧再压一次，并把「有没有被改回来」记进日志。
                        try {
                            view.post(object : Runnable {
                                override fun run() {
                                    if (view.visibility != View.GONE) report(simple, "relapse")
                                    else report(simple, "hidden")
                                    removeAndCollapse(view)
                                }
                            })
                        } catch (ignored: Throwable) {}
                        try {
                            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: View) {
                                    removeAndCollapse(v)
                                }

                                override fun onViewDetachedFromWindow(v: View) {}
                            })
                        } catch (ignored: Throwable) {}
                        // 宿主会在构造之后、动画里、onResume 里反复把可见性改回 VISIBLE
                        // （实测 PubtravBannerFloatingEntryView 就是 relapse）。
                        // 只靠构造时藏一次必然被改回来，所以在它自己的布局回调里再压一次 ——
                        // 这个监听只挂在这几个控件上，不是全局的 setVisibility，代价可以忽略。
                        try {
                            view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                                            ol: Int, ot: Int, or: Int, ob: Int) {
                                    if (v.visibility != View.GONE || wrapperAlive(v)) removeAndCollapse(v)
                                }
                            })
                        } catch (ignored: Throwable) {}
                        return result
                    }
                })
                n++
            }
            return n
        }

        /**
         * 把这个视图、以及一路向上「只包着它一个孩子」的包装层全部 GONE，再让根重排。
         *
         * <p>为什么不能只 GONE 自己：广告卡片外面通常还套了一层带背景的容器，
         * 只藏内层会剩下一块空白卡片 —— 就是用户看到的那个白框。
         * 向上最多收 3 层，且只在父容器**只有一个孩子**时继续，
         * 避免把宿主真正的列表项/页面容器误伤。
         */
        /**
         * 内层已经 GONE，但外面那层带圆角/白底的壳还占着高度 —— 就是用户看到的空白卡片。
         * 判断标准：父容器只有一个孩子（说明它纯粹是个包装层）。
         */
        @JvmStatic
        private fun wrapperAlive(v: View): Boolean {
            val parent = v.parent
            return parent is android.view.ViewGroup &&
                    parent.visibility != View.GONE &&
                    onlyOwnsMe(parent, v)
        }

        /**
         * 这个容器除了 me 之外，还有没有「看得见的」孩子。
         *
         * <p>原来只判断 {@code getChildCount()==1}，实测不够：广告卡的父容器里常常还挂着一个
         * 宽度 0 的占位 View 或者一个已经 GONE 的兄弟，孩子数是 2，于是壳被留下 ——
         * 用户看到的就是中间空出一大块白。改成按「有没有别的可见孩子」判断。
         *
         * <p>构造期还没布局，孩子高度全是 0，此时不能拿高度当证据，
         * 所以只有容器本身已经量过（height > 0）时才用高度过滤，否则只看可见性。
         */
        @JvmStatic
        private fun onlyOwnsMe(g: android.view.ViewGroup, me: View): Boolean {
            val laidOut = g.height > 0
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c === me) continue
                if (c.visibility == View.VISIBLE) {
                    if (!laidOut || c.height > 0 || c.width > 0) return false
                }
            }
            return true
        }

        @JvmStatic
        private fun removeAndCollapse(v: View?) {
            if (v == null) return
            var root: View? = null
            try {
                root = v.rootView
            } catch (ignored: Throwable) {}
            BaiduHooks.hide(v)
            var cur: View = v
            for (depth in 0 until 4) {
                val parent = cur.parent
                if (parent !is android.view.ViewGroup) break
                // 底栏（以及它的各层壳）绝不收。刷新重建时兄弟还没量过，宽高都是 0，
                // onlyOwnsMe 会误判成「只剩我一个」，于是整条底栏连同「开始导航」被一起 GONE 掉，
                // 而且这个 View 实例会被宿主复用，之后再也回不来 —— 用户反馈的「刷新几次后
                // 开始导航消失且无法恢复」就是这条。
                if (isBottomBar(parent)) break
                if (!onlyOwnsMe(parent, cur)) break
                BaiduHooks.hide(parent)
                cur = parent
            }
            if (root != null) root.requestLayout()
        }

        /** 底栏一族（含「开始导航」）：类名里带 BottomBar / PageBottom 的容器不参与收壳。 */
        @JvmStatic
        private fun isBottomBar(g: android.view.ViewGroup): Boolean {
            var c: Class<*>? = g.javaClass
            while (c != null && c != Any::class.java) {
                val n = c.name
                if (n.contains("BottomBar") || n.contains("PageBottom")) return true
                c = c.superclass
            }
            return false
        }
    }
}