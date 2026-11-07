package io.github.ldxm666.mapadkiller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject
import io.github.libxposed.api.XposedInterface

/** Compact homepage keeps the native toolbar and never creates the recommendation canvas. */
class AmapNativeHome private constructor() {

    companion object {

        private val attached: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val sizes: MutableMap<View, IntArray> = WeakHashMap()
        private val returning: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

        @Volatile
        private var compactSupported = false

        /* ---- live refresh ----------------------------------------------------------------
         * A Chain can only be proceeded once, so anything that has to run again later needs a real
         * Method handle. These are captured at install time and used by refresh() to rebuild the
         * panel content in place when a switch changes -- without them a toggle only took effect
         * after the home page happened to be rebuilt, which read as "开关无效".
         */
        @Volatile
        private var factoryMethod: Method? = null

        @Volatile
        private var chipsMethod: Method? = null

        @Volatile
        private var skeletonMethod: Method? = null

        /** 17.02 活面板 ToolboxPanelV2 的实例方法 buildHomeAndCompanyView(Context,String,int,JSONObject)。 */
        @Volatile
        private var v2ChipsMethod: Method? = null

        /** How the live container was built, so it can be built again the same way. */
        @Volatile
        private var built: Built? = null

        /** The live ToolboxPanel; its content is swapped instead of waiting for a rebuild. */
        @Volatile
        private var panelRef: java.lang.ref.WeakReference<View>? = null

        /** The 「设置家 / 设置单位 / 常去地点」 payload, cached even while the row is suppressed. */
        @Volatile
        private var chipsPayload: JSONObject? = null

        /** chips 行里的文字 View -> 格序号（0 家 / 1 单位 / 2 常去地点），用于状态刷新。 */
        private val chipLabels: MutableMap<View, Int> = WeakHashMap()

        /** 我们亲手产出的 chips 行（原生构建成功或自绘兜底），供 V2 原地增删时识别。 */
        private val chipsRows: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

        /** Switch state the live container was built with; see {@link #signature()}. */
        @Volatile
        private var builtSignature = ""

        /** 最近一次读 uid 的来源：account / empty，用于日志自证。 */
        @Volatile
        private var lastUidSource = "empty"

        /**
         * 「首页精简」是否生效。
         *
         * <p>★ 这是**热路径**：每次面板重建、每个 compact_* hook 都会调一次。
         * 原实现每次读 5 个 {@code Config.visible()}（5 次 map 查 + 5 次 defaultVisible 的
         * 若干 String.equals）；现在整个合取缓存成一个 {@link Config.Flag}，
         * 调用方**只读一个 volatile**。
         *
         * <p>刷新时机：prefs 变更监听器 → {@code Config.refreshFlags()}；
         * 另外 {@code install()} 末尾会显式 {@code resolve()} 一次 —— 因为
         * {@code compactSupported} 是在 install 里才赋值的，早于它读会缓存成 false。
         */
        private val COMPACT: Config.Flag = object : Config.Flag() {
            override fun compute(): Boolean {
                // ★ 打车卡**不参与**本判定（铁律，勿加回）。
                // 历史的坑：把 visible(K_TAXI_CARD) 写进合取式，用户只要打车卡 ⇒ COMPACT=false
                // ⇒ loadAjxQs 不返回 null、preloadAIData 放行 ⇒ 整块推荐画布重建 ⇒
                // 天气 / 扫街榜 / 内容流 / 出行节跟着一起"炸开"。
                // 现在「是否建画布」只由四个**内容**开关决定；打车卡的收放完全交给
                // AmapFeed1521 的 dataType 白名单在数据层解决（cardList 里只留打车家族）。
                return compactSupported && !Config.visible(Config.K_FEED_WEATHER) &&
                        !Config.visible(Config.K_FEED_BOARD) && !Config.visible(Config.K_FEED_CONTENT) &&
                        !Config.visible(Config.K_FEED_FESTIVAL)
            }
        }

        @JvmStatic
        fun compact(): Boolean {
            return COMPACT.on()
        }

        @JvmStatic
        fun install(cl: ClassLoader) {
            val qs = H.cls(cl, "com.autonavi.bundle.amaphome.lite.NewQuickServiceView")
            val page = H.cls(cl, "com.autonavi.common.IPageContext")
            val state = H.cls(cl, "sv3")
            val panelDelegate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.IToolboxPanel\$QsPanelStateDelegate")
            // ── 17.02 适配：`toolbox.b` → `toolbox.a`（单字母类名整体重排）──────────────
            // DEX 证据（17.02.0.1541）：
            //   · `Lcom/autonavi/bundle/amaphome/lite/quickservice/toolbox/a;`（classes5.dex）只有 1 个方法：
            //       public static a(Landroid/content/Context;Ljava/lang/String;ILorg/json/JSONObject;
            //                       Lcom/autonavi/bundle/amaphome/lite/quickservice/toolbox/IToolboxPanel$QsPanelStateDelegate;)
            //                      Lcom/amap/bundle/commonui/designtoken/view/viewgroup/DtLinearLayout;
            //     —— 与 17.01 的 `toolbox.b.a` **签名逐字相同**（返回类型同为 DtLinearLayout）；
            //   · `...quickservice.toolbox.b` 在 17.02 **不存在**（8 DEX 全量 --Find 无该类），
            //     而 17.01 里它存在 ⇒ 这是真实迁移，不是匹配条件写错。
            // 为什么必须走候选表而不是写死：类名一错，`compactSupported` 恒为 false ⇒
            // `compact()` 恒 false ⇒ 本类所有 compact_* hook 全部退化成直通，界面上
            // 完全看不出异常（只有 amap_compact_capability 那行日志能暴露）。
            // 抗混淆锚点：不认类名。认「包 ...amaphome.lite.quickservice.toolbox 下、静态方法 a、
            //   5 参 (Context,String,int,JSONObject,QsPanelStateDelegate)、返回 DtLinearLayout」。
            var factory: Class<*>? = null
            if (panelDelegate != null) for (holder in arrayOf("a", "b")) {
                val candidate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox." + holder) ?: continue
                if (io.github.ldxm666.mapclean.Compatibility.method(candidate, "a", null,
                                Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java, panelDelegate) == null) continue
                factory = candidate
                break
            }
            // ── 工具面板过滤（挂在**最上游**的两个入口，与 compact() 无关）───────────────
            installToolFilter(cl, factory, panelDelegate)
            val loads = java.util.ArrayList<Method>()
            if (qs != null) for (candidate in qs.declaredMethods) {
                val args = candidate.parameterTypes
                if ("loadAjxQs" == candidate.name && View::class.java.isAssignableFrom(candidate.returnType) &&
                        (args.size == 2 || args.size == 3) && args[0] === page && !args[1].isPrimitive &&
                        (args.size == 2 || args[2] === java.lang.Boolean.TYPE)) {
                    loads.add(candidate)
                }
            }
            compactSupported = loads.isNotEmpty() && panelDelegate != null &&
                    io.github.ldxm666.mapclean.Compatibility.method(factory, "a", null,
                            Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java, panelDelegate) != null
            H.log("amap_compact_capability supported=" + compactSupported +
                    " loadAjxQs=" + loads.size + " factory=" + (factory != null))
            // compactSupported 到这里才确定，显式刷一次缓存（否则首次读会把 false 缓存住）。
            COMPACT.resolve()
            if (loads.isEmpty() && qs != null) {
                // 诊断：把真实签名打出来，省得下次版本更新又只能靠猜"为什么整层精简静默失效"。
                // compactSupported=false 会让 compact() 恒 false ⇒ 本类所有 compact_* hook 全部变成
                // 直通，界面上看不出任何异常，只有这一行日志能暴露它。
                //
                // 17.01.0.2012 实测（classes4.dex NewQuickServiceView）：
                //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lqu3;)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
                //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lqu3;Z)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
                // 17.02.0.1541 实测（classes5.dex NewQuickServiceView，类从 classes4 挪到 classes5）：
                //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lbw3;)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
                //   loadAjxQs(Lcom/autonavi/common/IPageContext;Lbw3;Z)Lcom/autonavi/minimap/ajx3/views/AmapAjxView;
                //   —— 只有第 2 参的混淆类名 qu3→bw3 变了，形状匹配器不看具体类型，所以**不受影响**。
                //   这条也是「别按固定签名挂 loadAjxQs」的证据：混淆类名每版必变。
                // 返回类型链 AmapAjxView → AjxView → AjxScalpelLayout → android.widget.FrameLayout，
                // 是 View；参数 0 是 IPageContext、参数 1 非基本类型、参数 2 是 boolean ⇒
                // 与下面的形状匹配器一致，正常版本上这条日志不应出现。
                val why = StringBuilder()
                for (m in qs.declaredMethods) {
                    if ("loadAjxQs" != m.name) continue
                    why.append(' ').append(java.util.Arrays.toString(m.parameterTypes))
                            .append("->").append(m.returnType.simpleName)
                }
                H.log("amap_compact_capability reason=loadAjxQs_no_match candidates=" + why)
            }
            for (load in loads) H.hookSig(qs, "loadAjxQs", "amap_compact_canvas_" + load.parameterTypes.size, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return if (compact()) null else chain.proceed()
                }
            }, *load.parameterTypes)
            // ── AI 预载（永不加载：compact 时连 LLM 会话都不建）───────────────────────────
            // 17.00.0.2005 挂的是 `xc5.preloadAIData()`；17.01.0.2012 该方法**从 xc5 移走**，
            // 落到 IQSNetworkDataProvider 的唯一实现类 `l95` 上。
            // DEX 证据（17.01.0.2012, classes4.dex）：
            //   · method_ids 里声明 `preloadAIData()V` 的只有 `l95`（接口 IQSNetworkDataProvider
            //     自身另有一条抽象声明），xc5 的 11 个方法里已经没有它；
            //   · `l95.preloadAIData()` 体内 3 条调用：AMapServiceManager.getService(Class) →
            //     ILLMService.createSession() → ILLMRequestParam.setTimeoutSeconds(J)；
            //   · 调用方 2 处：`zq1$a.run()`、`tp3.onAjxQsBizLogicStart()`，都是接口调用
            //     （IQSNetworkDataProvider->preloadAIData()V），所以只能挂实现类、挂不了接口。
            // 也就是说：只挂 xc5 在 17.01 上是**死锚点** —— AI 会话照建、流照开，卡片只是被
            // 后面的 amap_stream 过滤掉，正是"藏了但还在跑"。现在两代锚点都挂，存在哪个挂哪个。
            // 抗混淆锚点：`l95` 是单字母类名，跨版本必变，重定位请认
            //   `IQSNetworkDataProvider.preloadAIData` + `createSession`/`setTimeoutSeconds` 组合。
            //
            // ── 17.02 适配：实现类又从 `l95` 搬到了 `kb5`（这次连方法都只剩一个）────────
            // DEX 证据（17.02.0.1541）：
            //   · 全 8 DEX 的 method_ids 里声明 `preloadAIData()V` 的类只有两个：
            //       `Lcom/autonavi/bundle/amaphome/api/IQSNetworkDataProvider;`（classes5，接口抽象声明）
            //       `Lkb5;`（classes5，唯一实现）
            //   · `kb5.preloadAIData()V` 体内 3 条调用与 17.01 的 `l95` **逐条相同**：
            //       AMapServiceManager.getService(Class) → ILLMService.createSession() →
            //       ILLMRequestParam.setTimeoutSeconds(J)
            //   · `kb5` 同时实现接口的其余方法（cancelAIPreloadData / fetchAIPreloadData /
            //     fillReqParams / getAJXLocation / getPreloadLocation / getPreloadLocationTS /
            //     setAJXLocation）⇒ 身份确认无疑；
            //   · `l95` 在 17.02 仍存在，但已被无关类占用（只剩 `public static a(String)I`），
            //     所以它作为候选只会打一条 miss，无副作用。
            // 结论：`kb5` 是第三代锚点。三代全挂，存在哪个挂哪个（hookSig 找不到就空转）。
            // 第四代（Play 17.02.2.2014）：`Len5;` 是 `preloadAIData()V` 除接口外的**唯一**实现，
            // 且同时实现 IQSNetworkDataProvider 的其余 6 个方法（cancelAIPreloadData /
            // fetchAIPreloadData / fillReqParams / getAJXLocation / getPreloadLocation /
            // getPreloadLocationTS）⇒ 身份确认无疑。`kb5` 在 Play 上仍然存在但已被换体
            // （没有 preloadAIData），所以先筛「类上真有这个方法」再挂，候选落空不刷 miss 行。
            for (holder in arrayOf("kb5", "en5", "l95", "xc5")) {
                val preloader = H.cls(cl, holder)
                if (H.method(preloader, "preloadAIData") == null) continue
                H.hookSig(preloader, "preloadAIData", "amap_compact_preload_" + holder, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return if (compact()) null else chain.proceed()
                    }
                })
            }
            // 17.01.0.2012 迁移：`g13` 这个编号被另一个无关类占了（见下方说明），真正的
            // 「设置家/单位/常去地点」行构建器搬到了 `yz2`。
            // 证据（17.01.0.2012, classes4.dex）：
            //   · `Lyz2;` 只有两个方法，第一个是
            //       a(Landroid/content/Context;Ljava/lang/String;ILorg/json/JSONObject;)Landroid/view/ViewGroup;
            //     —— 与 17.00 的 `g13.a` **签名逐字相同**；
            //   · 唯一调用方是 `com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3.init(Context,String,int)`，
            //     与已 MATCH 的 `ToolboxPanelV2.buildHomeAndCompanyView` **同包**（toolbox），
            //     即它就是工具面板里那一行的构建器。
            // 抗混淆锚点：`yz2` 是单字母类名，下次必变。重定位认
            //   「(Context,String,int,JSONObject)→ViewGroup 的静态方法」+ 调用方在 amaphome.lite.quickservice.toolbox 包内。
            //
            // ── 17.02 适配：`yz2` 也被无关类占了，构建器搬到了 `s03` ──────────────────────
            // DEX 证据（17.02.0.1541）：
            //   · 全 8 DEX 里唯一具有
            //       (Landroid/content/Context;Ljava/lang/String;ILorg/json/JSONObject;)Landroid/view/ViewGroup;
            //     这个**精确签名**的方法只有 `Ls03;->a`（classes5.dex）；
            //   · `Ls03;` 只有 2 个方法：上面那个 a(...)，以及 b(II,String,String)GradientDrawable
            //     （画圆角底色的辅助）—— 与 17.01 `yz2` 的形态完全一致；
            //   · `s03.a` 的**唯一调用方** = `com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3.init(Context,String,int)`
            //     （classes5.dex, code_off=0x4209d4）—— 与 17.01 `yz2`、17.00 `g13` 的调用方**同一处**，
            //     这就是它的身份指纹。
            //   · 17.02 的 `yz2` 现在是 `a(String,ve2)Loe2`（无关类）；`g13` 变成可穿戴设备类
            //     （connect/disconnect/send/...）。两个旧候选都只会 miss。
            var homeCompany: Class<*>? = null
            for (holder in arrayOf("s03", "yz2", "g13")) {
                val candidate = H.cls(cl, holder) ?: continue
                if (H.method(candidate, "a", Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java) == null) continue
                homeCompany = candidate
                break
            }
            chipsMethod = H.method(homeCompany, "a", Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java)
            // 17.01.0.2012：`g13` 这个编号被**另一个无关类**占了 —— 它现在是 okhttp3 的
            // 一个内部类（super=Lqd2，只有 <init>(okhttp3/internal/http2/b,[Object,int,List,Z)
            // 和 a()V 两个方法），不再是 HomeCompanyBuilderV3。
            // 按规矩不猜替代品：保留原锚点（hookSig 找不到方法时是空转，无副作用），
            // 只把这件事显式打成一条 anchor_miss，让它在真机日志里显形。
            // 「设置家/单位/常去地点」这一行在 17.01 由 ToolboxPanelV2.buildHomeAndCompanyView
            // 产出（已 MATCH），所以功能不依赖 g13；但 refresh() 里的原地重建走的是
            // chipsMethod（静态），它为 null ⇒ 切换开关后重建的面板会缺这一行，直到首页重建。
            // 待真机确认项，见 never-load-impl.md「未确认」。
            if (chipsMethod == null) {
                H.log("event=anchor_miss anchor=g13.a(Context,String,int,JSONObject)" +
                        " class=" + (if (homeCompany == null) "null" else homeCompany.name) +
                        " note=17.01 该类已被无关类占用，HomeCompanyBuilderV3 迁移；V2 路径仍生效")
            }
            H.hookSig(homeCompany, "a", "amap_native_home_chips", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return chipsHook(chain, cl, "s03_build")
                }
            }, Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java)
            // 17.00.0.2005 的首页实际走 ToolboxPanelV2 自己构建「设置家 / 设置单位 / 常去地点」，
            // 不经过 g13。视图树里能看到 ToolboxPanelV2 -> DtLinearLayout -> 工具行，
            // 而 buildHomeAndCompanyView 正是那个位置的下一个 addView。
            // 只挂 g13 的话，这个开关在这个版本永远是死的 —— 切换了也不会 HIT。
            // 签名与 g13.a 完全一致 (Context, String, int, JSONObject)，所以共用一段逻辑。
            val v2 = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.ToolboxPanelV2")
            v2ChipsMethod = H.method(v2, "buildHomeAndCompanyView",
                    Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java)
            H.hookSig(v2, "buildHomeAndCompanyView", "amap_v2_home_chips", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return chipsHook(chain, cl, "v2_build")
                }
            }, Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java)
            // ── 17.02 真机根因（B）：服务端不下发 home_and_company_data ────────────────────
            // `ToolboxPanelV2.init` 的字节码是：读 main_page/home_and_company_data →
            // TextUtils.isEmpty → 为空则整段跳过 → 才调 buildHomeAndCompanyView。
            // 真机日志 `search_grid_helper op=get key=home_and_company_data len=0` 证明该值恒空，
            // 所以上面两个 chips 钩子在这台机器上**永远不会 HIT**（不是钩错方法，是宿主不调）。
            // 修法：**纯内存注入** —— hook wm3.c 的返回值，只在 init 线程上把空值换成合成 payload。
            // 不写 main_page.xml / 不写任何宿主 prefs；渲染仍由宿主 buildHomeAndCompanyView 完成。
            H.hookSig(v2, "init", "amap_v2_home_chips_init", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    var result: Any? = null
                    IN_TOOLBOX_INIT.set(java.lang.Boolean.TRUE)
                    try {
                        result = chain.proceed()
                    } finally {
                        IN_TOOLBOX_INIT.remove()
                    }
                    ensureV2ChipsRow(chain.getThisObject() as View, cl,
                            chain.getArg(1).toString(), chain.getArg(2) as Int, "v2_init")
                    return result
                }
            }, Context::class.java, String::class.java, Integer.TYPE, java.lang.Boolean.TYPE)
            // c(String,String)String 是 V2/V3.init 读 home_and_company_data 的唯一入口。
            // 混淆名会重排：国内 = wm3.c，Play 17.02.2.2014 = lv3.c（候选表 AmapData.PREFS_HELPER）。
            // 只在 IN_TOOLBOX_INIT 线程上替换，别的读取方（搜索页等）完全不受影响。
            val helperAt = arrayOfNulls<String>(2)
            val helper = H.anchor(cl, AmapData.PREFS_HELPER as Array<Array<String>?>, helperAt)
            if (helper == null) {
                H.log("event=home_chips_miss reason=no_prefs_helper")
            } else {
                H.hookSig(helper, helperAt[1]!!, "amap_native_home_chips_payload", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val original = chain.proceed()
                        val key = chain.getArg(0)
                        if (key !is String || KEY_HOME_COMPANY != key) return original
                        val injected = injectChipsPayload(cl, original)
                        val base = if (injected != null) injected
                                else (if (original == null) null else original.toString())
                        if (base == null || base.isEmpty()) return original
                        if (!Config.visible(Config.K_HOME_CHIPS)) return original
                        // 服务端快照不会在用户设置家/单位后刷新 ⇒ 只改内存里的 name 文案，
                        // 其它字段（icon/scheme/样式）原样保留，渲染仍是高德原生行。
                        val relabeled = relabelChips(base, cl)
                        if (relabeled != base) {
                            H.log("event=home_chips_relabel home=" + homeSet(cl) +
                                    " company=" + companySet(cl) + " len=" + base.length)
                            return relabeled
                        }
                        if (injected != null) {
                            H.log("event=home_chips_show via=native_payload hook=wm3_c items=3")
                            return injected
                        }
                        return original
                    }
                }, String::class.java, String::class.java)
            }
            val v3 = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3")
            skeletonMethod = H.method(v3, "buildSkeletonView", Context::class.java, String::class.java, Integer.TYPE)
            H.hookSig(v3, "init", "amap_v3_home_chips_init", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    var result: Any? = null
                    IN_TOOLBOX_INIT.set(java.lang.Boolean.TRUE)
                    try {
                        result = chain.proceed()
                    } finally {
                        IN_TOOLBOX_INIT.remove()
                    }
                    return result
                }
            }, Context::class.java, String::class.java, Integer.TYPE)
            H.hookSig(v3, "buildSkeletonView", "amap_native_skeleton", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!compact()) return chain.proceed()
                    val empty = View(chain.getArg(0) as Context)
                    empty.layoutParams = LinearLayout.LayoutParams(0, 0)
                    empty.visibility = View.GONE
                    return empty
                }
            }, Context::class.java, String::class.java, Integer.TYPE)
            // 17.00.0.2005 首页实际用的是 ToolboxPanelV2，不是 V3（见 amap_tree 视图树：
            // ToolboxPanelV2 -> DtLinearLayout -> 工具行 + DtFrameLayout 964x189 的空灰块）。
            // 那块灰就是 V2 的 AI 骨架（buildAICotView / buildAILoadingView），
            // 内容拉回来之前先占位，拉不回来就一直留在那儿 —— 也就是用户看到的
            // 「工具栏下方一直有灰色隐形窗口」。V3 的 buildSkeletonView 在这个版本根本不会走，
            // 所以只挂 V3 等于没挂。
            for (method in arrayOf("buildAICotView", "buildAILoadingView")) {
                H.hookSig(v2, method, "amap_v2_skeleton_" + method, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 只有在「首页精简」生效时才收掉骨架；否则原样返回，
                        // 让 AI 入口在用户没关推荐的情况下正常工作。
                        if (!compact()) return chain.proceed()
                        val empty = View(chain.getArg(0) as Context)
                        empty.layoutParams = LinearLayout.LayoutParams(0, 0)
                        empty.visibility = View.GONE
                        return empty
                    }
                }, Context::class.java, String::class.java, Integer.TYPE)
            }
            // 真正把工具过滤掉的不是这个 View 层，而是数据层：amap_tool_boot 在
            // AmapData.toolbox() 里就把未开启的工具从 JSON 里剔除，并把「已开启但原生列表里
            // 没有的」工具补进空位（步行 / 骑行 之所以永远不出现，就是因为旧实现只会删不会补）。
            // 所以这里保持原样，只把「造好的容器」和「再造一次所需的参数」记下来，
            // 供开关变化时原地重建 —— 否则改了开关要等首页重建才生效，看起来就是「开关无效」。
            val delegate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.IToolboxPanel\$QsPanelStateDelegate")
            val factoryCls = factory
            if (delegate != null && factoryCls != null) {
                factoryMethod = H.method(factoryCls, "a", Context::class.java, String::class.java, Integer.TYPE,
                        JSONObject::class.java, delegate)
                H.hookSig(factoryCls,
                        "a", "amap_native_tools", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 显式传当前 chain 的参数（外层 amap_tool_factory 已替换第 4 个参数）；
                        // 无参 proceed() 在部分框架实现里会回退原始参数，过滤后的 payload 会被丢掉。
                        val result = chain.proceed(chain.getArgs().toTypedArray())
                        if (result is ViewGroup) remember(chain, result)
                        return result
                    }
                }, Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java, delegate)
            }
            H.hookSig(qs, "initToolBoxPanel", "amap_compact_height", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // Captured no matter what compact() says: the panel that was just attached is
                    // the one a later switch change has to rebuild, and it is reused from
                    // BootUIPreloader's cache rather than recreated when the home page comes back.
                    val splashPanel = H.getObjectField(chain.getThisObject(), "mSplashToolboxPanel")
                    if (splashPanel is View) {
                        panelRef = java.lang.ref.WeakReference(splashPanel)
                        refresh()
                    }
                    val self = chain.getThisObject()
                    if (compact() && self is View) {
                        val view: View = self
                        // Dump the enclosing screen once so the empty grey card under the tool bar
                        // can be named instead of guessed at.
                        view.post(object : Runnable {
                            override fun run() {
                                try {
                                    val root = view.rootView
                                    val board = H.getObjectField(view, "mSplashToolboxPanel")
                                    dumpTree("qs", if (board is View) board else view)
                                    if (root != null) dumpTree("root", root)
                                } catch (ignored: Throwable) {
                                }
                            }
                        })
                        val panel = H.getObjectField(view, "mSplashToolboxPanel")
                        if (panel is View) {
                            val toolbar = panel
                            // getLayoutParams() 在视图还没被 addView 时返回 **null**，
                            // 而 initToolBoxPanel 恰好可能先建面板、稍后才挂进容器。
                            // 这一句以前直接 NPE → 被 HookGuard 判死 → amap_compact_height
                            // 整个进程不再工作（"紧凑首页"点了没反应，重启才好）。
                            var lp: ViewGroup.LayoutParams? = toolbar.layoutParams
                            if (lp == null) {
                                lp = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT)
                            }
                            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            toolbar.layoutParams = lp
                        }
                        synchronized(attached) {
                            if (attached.add(view)) view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                                            ol: Int, ot: Int, orr: Int, ob: Int) {
                                    if (compact()) resize(v)
                                }
                            })
                        }
                        resize(view)
                    }
                    return result
                }
            }, String::class.java, Integer.TYPE)
            H.hookSig(qs, "setTransparentHeight", "amap_compact_limit", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val self = chain.getThisObject()
                    if (!compact() || self !is View) return chain.proceed()
                    val size = sizes[self]
                    if (size == null || self.height <= 0) return chain.proceed()
                    return chain.proceed(arrayOf<Any?>(Math.max(0, self.height - size[3] - statusBar(self))))
                }
            }, Integer.TYPE)
            val home = H.cls(cl, "com.autonavi.bundle.amaphome.page.MapHomePage")
            if (home != null) H.hookSig(qs, "resetQSHeights", "amap_compact_reset", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val self = chain.getThisObject()
                    if (compact() && self is View) {
                        sizes.remove(self)
                        resize(self)
                    }
                    return result
                }
            }, home)
            val panelState = H.cls(cl, "com.autonavi.bundle.uitemplate.container.internal.SlidableLayout\$PanelState")
            if (panelState != null) H.hookSig(qs, "onPanelStateChanged", "amap_compact_rebound", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val self = chain.getThisObject()
                    if (compact() && self is View) {
                        val panel = H.getObjectField(self, "mSplashToolboxPanel")
                        val next = chain.getArg(2).toString()
                        if (panel is View) panel.visibility =
                                if ("COLLAPSED" == next || "HIDDEN" == next) View.INVISIBLE else View.VISIBLE
                    }
                    if (compact() && "EXPANDED" == chain.getArg(2).toString() && self is View)
                        rebound(self)
                    return result
                }
            }, View::class.java, panelState, panelState)
        }

        /**
         * Shared body of the 「设置家 / 设置单位 / 常去地点」 hooks.
         *
         * <p>Two different builders can produce this row depending on the app build:
         * {@code g13.a} (HomeCompanyBuilderV3) and, on 17.00.0.2005,
         * {@code ToolboxPanelV2.buildHomeAndCompanyView}. They take the same arguments
         * {@code (Context, String, int, JSONObject)}, so both hooks delegate here and the
         * behaviour stays identical whichever one the app happens to call.
         *
         * <p>History worth keeping in mind before changing this:
         * <ol>
         *   <li>An older build folded {@code K_HOME_CHIPS} into {@code compact()}, so enabling
         *       this switch made {@code compact()} false, which released the four recommendation
         *       canvas hooks and brought the weather / ranking / feed / festival cards back.</li>
         *   <li>Making it unconditional in the other direction was worse: a user who had already
         *       hidden the recommendations had {@code compact() == true} permanently, so the
         *       switch became dead.</li>
         * </ol>
         * It therefore honours only its own key. The canvases are suppressed by
         * {@link #compact()} independently; the two never influence each other.
         */

        /**
         * 工具面板过滤：挂在 payload 变成格子之前的**两个最上游入口**。
         *
         * <h3>为什么原来的三道 hook 拦不住（17.02 实测证据）</h3>
         * 原来只在三处过滤：{@code BootBizDataPreloaderImpl.getToolboxData()()},
         * {@code AjxModuleLocalStorage.getItemSync/getItemASYNC}。但 17.02 的面板构建链是：
         * <pre>
         *  ToolboxPanelV2.init(Context,String,int,Z)   ┐
         *  ToolboxPanelV3.init(Context,String,int)     ┘→ getToolboxData() ──┐
         *                                                                     ├→ toolbox.a.a(ctx,name,i,JSONObject,delegate)
         *  两者都有兜底：if (data == null || data.length()==0)               │      └ 内部还有兜底：
         *      data = new JSONObject(k84.f())            ────────────────────┘         if (!data.has("id0"))
         *                                                                                 data = new JSONObject(k84.f())
         * </pre>
         * {@code k84.f()Ljava/lang/String;} 被 **5 处**调用（工厂兜底 / V2 兜底 / V3 兜底 /
         * {@code getToolboxData} 自己建缓存 / {@code prepare}）。只要走到任一兜底，
         * payload 就**完全绕过**上面三道 hook ⇒ 这正是"过滤逻辑正确、格子却照样显示"的根因。
         *
         * <h3>所以挂两处</h3>
         * <ol>
         *   <li>{@code toolbox.a.a(...,JSONObject,...)} 的**第 4 个参数**（0-based 3）：
         *       这是"原料 → 10 个格子"的唯一出口，V2/V3 都走它。改这个参数即可；
         *       它的 schema 就是 {@code id0..idN + size<i> + itemDataForClick<i>}，
         *       与 {@link AmapData#toolbox} 完全一致（DEX 逐条核对过它读的 key）。</li>
         *   <li>{@code k84.f()Ljava/lang/String;} 的**返回值**：所有兜底路径的共同上游，
         *       在最上游拦一次，任何兜底都拿不到未过滤的清单。</li>
         * </ol>
         * 两处都是**幂等**的：{@code AmapData.toolbox} 在"没有需要过滤的格子"时直接返回原对象，
         * 所以重复过滤、schema 不匹配、解析失败都不会有副作用（解析失败原样放行）。
         */

        /**
         * Replace the host's five-cell row with our own grid, then append the
         * 「设置家 / 设置单位 / 常去地点」 row to the same container.
         *
         * <p>把 chips 行放在这里（而不是 ToolboxPanelV2 的根节点）有两个原因：
         * 1) 位置天然正确 —— 工具行下方、AI 卡上方，与高德原生顺序一致；
         * 2) 面板每次重建都会经过 toolbox.a.a，所以开关切换后重新进首页必定重新走这里。
         */

        /**
         * ★ 自绘「设置家 / 设置单位 / 常去地点」——**默认关闭**。
         *
         * <p>官方高德自带这一行（`ToolboxPanelV2.buildHomeAndCompanyView` 从服务端
         * `home_and_company_data` 渲染）。模块模式下两行会同时出现（自绘 + 原生），
         * 用户明确要求「只保留最下方原生行，自绘的不要」。
         *
         * <p>自绘只在宿主完全没有这一行时才有意义（如某些内置版机型拿不到 payload）。需要时置 true。
         */
        private const val SELF_DRAW_CHIPS = false

        /**
         * ★ 自绘工具宫格——**默认关闭**：UI 完全交给高德原生渲染，模块只做数据过滤。
         *
         * <p>{@code decorateHomeRow(row)} 的 {@code row} 就是
         * {@code toolbox.a.a(ctx,name,int,JSONObject,delegate)} 的返回值，即高德**已经建好**的原生
         * 宫格容器。模块在该调用的第 4 个参数上做 payload 过滤（见 {@code installToolFilter}），
         * 所以只要不 {@code removeAllViews()}、不 {@code addView(homeGrid)}，原生宫格拿到的
         * 本来就是过滤后的格子 —— 数据过滤与 UI 渲染互不影响。
         *
         * <p>{@link #homeGrid} / {@link #toolCell} / {@link AmapToolIcons} / {@link AmapToolCatalog}
         * 保留不删；只有宿主完全拿不到原生宫格时才把本开关置 true。
         */
        private const val SELF_DRAW_TOOL_GRID = false

        /** 原生宫格日志只报一次（面板每次重建都会走 decorateHomeRow，不节流会刷屏）。 */
        @Volatile
        private var nativeToolGridReported = false

        /**
         * 放行原生宫格的行间距 —— 不改任何子 View 的结构，只做两件事：
         *
         * <p>1) {@code setClipChildren(false)} / {@code setClipToPadding(false)}：宿主把每个工具格子
         * 塞进「行」DtLinearLayout 里，行的实测高度比格子内容矮 17px（真机 1440x3200 / 560dpi
         * 实测：行 2 图标宽 142、高只有 125，圆形顶边被切平）。关掉裁剪后图标能画到行外。
         *
         * <p>2) 给第 2 行及以后的行补一点 topMargin，把行距真正拉开（用户要求「拓宽一点」）。
         *
         * <p>每个 View 实例只处理一次（面板每次重建都会走这里，用 tag 去重），
         * 不修改宿主任何绘制逻辑、不自绘图标。
         */
        private fun relaxToolRows(grid: ViewGroup?, ctx: Context?) {
            try {
                if (grid == null || ctx == null) return
                if (RELAXED_GRIDS.put(grid, true) != null) return

                grid.clipChildren = false
                grid.clipToPadding = false

                val density = ctx.resources.displayMetrics.density
                val extra = (10f * density + 0.5f).toInt()   // 10dp，约 26px @560dpi

                for (i in 0 until grid.childCount) {
                    val row = grid.getChildAt(i) ?: continue
                    if (row is ViewGroup) {
                        row.clipChildren = false
                        row.clipToPadding = false
                    }
                    if (i == 0) continue
                    val lp: ViewGroup.LayoutParams? = row.layoutParams
                    if (lp is ViewGroup.MarginLayoutParams) {
                        lp.topMargin = lp.topMargin + extra
                        row.layoutParams = lp
                    } else if (lp != null) {
                        lp.height = if (lp.height > 0) lp.height + extra else ViewGroup.LayoutParams.WRAP_CONTENT
                        row.layoutParams = lp
                    }
                }
                H.log("event=amap_tool_rows_relax extra_px=" + extra + " rows=" + grid.childCount)
            } catch (t: Throwable) {
                H.log("event=amap_tool_rows_relax error=" + t)
            }
        }

        /** 已处理过的宫格（面板重建会反复走 decorateHomeRow，用它去重，避免行距越加越大）。 */
        private val RELAXED_GRIDS: java.util.WeakHashMap<View, Boolean> = java.util.WeakHashMap()

        /** 我们为「首页工具宫格」开关亲手隐藏过的子 View（只恢复自己改过的，不碰宿主状态）。 */
        private val GRID_HIDDEN: java.util.WeakHashMap<View, Boolean> = java.util.WeakHashMap()

        /** chips 行的文字特征：宿主原生那排「设置家 / 设置单位 / 常去地点」。 */
        private val CHIP_TEXTS = arrayOf(
                "设置家", "回家", "设置单位", "去单位", "常去地点", "添加常去地点", "公司")

        private fun hasChipsText(view: View?, depth: Int): Boolean {
            if (view == null || depth > 4) return false
            if (view is TextView) {
                val cs = view.text
                val text = if (cs == null) "" else cs.toString().trim()
                for (chip in CHIP_TEXTS) {
                    if (text == chip || text.startsWith(chip)) return true
                }
                return false
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (hasChipsText(view.getChildAt(i), depth + 1)) return true
                }
            }
            return false
        }

        private fun isChipsRow(view: View?): Boolean {
            try {
                if (view == null) return false
                if (chipsRows.contains(view)) return true
                return view.javaClass.name.contains("HorizontalScrollView") && hasChipsText(view, 0)
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * 「首页工具宫格」开关：只隐藏宫格自己，**绝不隐藏承载 chips 的容器**。
         *
         * <p>真机复现（17.02 / 2260）：关掉 {@code ui_tools_visible} 之后
         * {@code home_chips_show items=4} 明明建出来了，首页却看不到「回家 / 去单位」——
         * 因为那排 chips 是宿主在 {@code toolbox.a.a} 返回的同一个容器里追加的，
         * 旧的 {@code vg.setVisibility(GONE)} 把它一起藏了。这就是用户报的
         * 「关掉首页工具宫格，设置家被连带关闭」。
         */
        private fun applyToolGridVisibility(vg: ViewGroup?, show: Boolean) {
            if (vg == null) return
            try {
                if (vg.visibility != View.VISIBLE) vg.visibility = View.VISIBLE
                val kids = vg.childCount
                for (i in 0 until kids) {
                    val child = vg.getChildAt(i) ?: continue
                    if (isChipsRow(child)) {
                        child.visibility = View.VISIBLE
                        continue
                    }
                    if (show) {
                        if (GRID_HIDDEN.remove(child) != null) child.visibility = View.VISIBLE
                    } else {
                        GRID_HIDDEN.put(child, true)
                        child.visibility = View.GONE
                    }
                }
                H.log("event=amap_tool_grid_visibility show=" + show + " kids=" + kids)
            } catch (t: Throwable) {
                H.log("event=amap_tool_grid_visibility error=" + t.javaClass.simpleName)
            }
        }

        private fun decorateHomeRow(row: Any?, cl: ClassLoader, ctx: Context?): View? {
            try {
                if (row !is ViewGroup || ctx == null) return row as View?
                val vg = row
                if (SELF_DRAW_TOOL_GRID) {
                    vg.removeAllViews()
                    if (vg is LinearLayout) vg.orientation = LinearLayout.VERTICAL
                    if (Config.visible(Config.K_TOOLS_VISIBLE)) {
                        val grid = homeGrid(cl, ctx, night(ctx))
                        if (grid != null) {
                            vg.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT))
                            H.log("event=amap_tool_grid cells=" + (grid as ViewGroup).childCount)
                        }
                    }
                } else {
                    // 原生宫格原样保留：不删子 View、不换容器、不自绘。
                    // 「整块工具栏」开关只隐藏**宫格自己的子 View**：宿主把「设置家」那排
                    // chips 追加在同一个容器里，直接 GONE 容器会把设置家一起藏掉（用户 bug）。
                    val gridHolder = vg
                    applyToolGridVisibility(gridHolder, Config.visible(Config.K_TOOLS_VISIBLE))
                    // 宿主有时在 decorate 之后才把格子 / chips 塞进来，layout 完再按同一规则刷一次。
                    try {
                        gridHolder.post(object : Runnable {
                            override fun run() {
                                applyToolGridVisibility(gridHolder, Config.visible(Config.K_TOOLS_VISIBLE))
                            }
                        })
                    } catch (ignored: Throwable) {
                    }
                    relaxToolRows(vg, ctx)
                    if (!nativeToolGridReported) {
                        nativeToolGridReported = true
                        H.log("event=amap_tool_grid via=native skip_self_draw cells=" + vg.childCount)
                        val lines = java.util.ArrayList<String>()
                        describe(lines, vg, 0)
                        for (i in 0 until Math.min(lines.size, 60)) H.log("amap_native_tool_tree " + lines[i])
                    }
                }
                if (SELF_DRAW_CHIPS && Config.visible(Config.K_HOME_CHIPS) && findChipsRow(vg) == null) {
                    val chipsRow = chips(cl, ctx, localChipsPayload(cl))
                    if (chipsRow != null) {
                        chipsRows.add(chipsRow)
                        vg.addView(chipsRow)
                        applyChipState(cl)
                        H.log("event=home_chips_show via=self_draw items=3")
                    } else {
                        H.log("event=home_chips_miss reason=chips_null host=decorateHomeRow")
                    }
                } else if (!Config.visible(Config.K_HOME_CHIPS)) {
                    H.log("event=home_chips_hide via=decorateHomeRow")
                }
                return vg
            } catch (t: Throwable) {
                H.log("event=home_chips_miss reason=decorate_failed err=" + t.javaClass.simpleName)
                return if (row is View) row else null
            }
        }

        private fun night(ctx: Context): Boolean {
            return (ctx.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        private fun homeGrid(cl: ClassLoader, ctx: Context, night: Boolean): View? {
            try {
                val items = java.util.ArrayList<JSONObject>()
                val raw = ctx.getSharedPreferences("toolbox", Context.MODE_PRIVATE)
                        .getString("tool_list_data", null)
                // A fresh install has no cached catalogue yet (tool_list_data is fetched later);
                // the static catalogue still describes every id/schema/icon, so use it as the base.
                if (raw != null) {
                    val cats = JSONObject(raw).optJSONArray("category")
                    if (cats != null) for (i in 0 until cats.length()) {
                        val cat = cats.optJSONObject(i)
                        val list = cat?.optJSONArray("tools")
                        if (list == null) continue
                        for (j in 0 until list.length()) {
                            val it = list.optJSONObject(j)
                            if (it != null && AmapData.toolVisible(it.optString("id"), AmapData.CURRENT)) items.add(it)
                        }
                    }
                }
                // Tools that exist in the static catalogue but not in the host cache still get a
                // cell: this grid is drawn by us, so the host's own filter can no longer drop them.
                val present = java.util.HashSet<String>()
                for (it in items) present.add(it.optString("id"))
                for (entry in AmapToolCatalog.ENTRIES) {
                    if (present.contains(entry[0]) || !AmapData.toolVisible(entry[0], AmapData.CURRENT)) continue
                    items.add(AmapToolCatalog.item(entry))
                    present.add(entry[0])
                }
                if (items.isEmpty()) return null
                val ids = StringBuilder()
                for (it in items) {
                    if (ids.length > 0) ids.append(',')
                    ids.append(it.optString("id"))
                }
                H.log("event=amap_tool_grid_items n=" + items.size + " ids=" + ids)
                val col = LinearLayout(ctx)
                col.orientation = LinearLayout.VERTICAL
                var row: LinearLayout? = null
                for (i in items.indices) {
                    if (i % 5 == 0) {
                        row = LinearLayout(ctx)
                        row.orientation = LinearLayout.HORIZONTAL
                        col.addView(row, LinearLayout.LayoutParams(-1, -2))
                    }
                    row!!.addView(toolCell(cl, ctx, items[i], night), LinearLayout.LayoutParams(0, -2, 1f))
                }
                return col
            } catch (t: Throwable) {
                return null
            }
        }

        private fun toolCell(cl: ClassLoader, ctx: Context, item: JSONObject, night: Boolean): View {
            val cell = LinearLayout(ctx)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = android.view.Gravity.CENTER
            cell.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 4))
            try {
                val icon = cl.loadClass("com.amap.bundle.commonui.designtoken.view.DtImageView")
                        .getConstructor(Context::class.java).newInstance(ctx) as View
                // 图标解析集中到 AmapToolIcons：一条条目有五六种图标写法
                // （imageToken / imageLegacy / icon / iconV2 / imageV2 …），格子这里自己拼 key
                // 就必然漏——这正是「有的格子有图、有的格子空白」的由来。
                AmapToolIcons.load(cl, icon as android.widget.ImageView,
                        AmapToolIcons.imageOf(item), null, item.optString("id"))
                val iconLp = LinearLayout.LayoutParams(
                        dp(ctx, AmapToolCatalog.ICON_DP), dp(ctx, AmapToolCatalog.ICON_DP))
                iconLp.bottomMargin = dp(ctx, AmapToolCatalog.ICON_TEXT_GAP_DP)
                cell.addView(icon, iconLp)
            } catch (ignored: Throwable) {
            }
            val label = TextView(ctx)
            label.text = item.optString("name")
            // 工具面板是设计令牌卡片，**恒为浅色**。按系统 uiMode 选白字，会在深色主题下把
            // 文字打成浅色卡上的白字——看起来就是「字太透明、发糊、看不清」。
            // 规格（13sp / sans-serif-medium / #FF191919 / alpha=1）统一在 AmapToolCatalog。
            AmapToolCatalog.styleLabel(label)
            label.maxLines = 2
            cell.addView(label, LinearLayout.LayoutParams(-1, -2))
            val schema = item.optString("schema")
            cell.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    if (schema.isEmpty()) return
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(schema))
                                .setPackage(MainHook.PKG_AMAP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (ignored: Throwable) {
                    }
                }
            })
            return cell
        }


        private fun installToolFilter(cl: ClassLoader, factory: Class<*>?, panelDelegate: Class<*>?) {
            if (factory != null && panelDelegate != null) {
                H.hookSig(factory, "a", "amap_tool_factory", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val data = chain.getArg(3)
                            if (data is JSONObject) {
                                val source = data
                                // fill=true: this factory is the single exit that turns the payload into visible
                                // cells. Filter-only mode could never ADD a tool that the host's own
                                // cached list did not already contain, which is why every switch
                                // outside the five defaults appeared dead. Filling here injects the
                                // enabled catalogue tools into the render payload without touching
                                // the host's stored list.
                                val out = AmapData.toolbox(source, AmapData.CURRENT,
                                        chain.getArg(0) as? Context)
                                if (!toolGridReported) {
                                    toolGridReported = true
                                    H.log("event=amap_tool_grid_items before=" + AmapData.slotIds(source) +
                                            " after=" + AmapData.slotIds(out))
                                    H.log("event=amap_tool_factory_args theme=" + chain.getArg(1) +
                                            " mode=" + chain.getArg(2))
                                    H.log("event=amap_tool_slot_icons " + slotIconDump(source))
                                    for (i in 0 until 10) {
                                        if (out.optString("id" + i).isEmpty()) continue
                                        H.log("amap_tool_slot_dump i=" + i + " " + slotDump(out, i))
                                    }
                                }
                                if (out !== source) {
                                    val args = chain.getArgs().toTypedArray()
                                    args[3] = out
                                    if (!toolFactoryReported) {
                                        toolFactoryReported = true
                                        H.log("event=amap_tool_factory_filter kept=" + AmapData.slotIds(out))
                                    }
                                    val row = chain.proceed(args)
                                    return decorateHomeRow(row, cl, chain.getArg(0) as? Context)
                                }
                            }
                        } catch (ignored: Throwable) {
                            // 过滤失败就原样放行：宁可漏一次，也不能让工具面板空白。
                        }
                        val row = chain.proceed()
                        return decorateHomeRow(row, cl, chain.getArg(0) as? Context)
                    }
                }, Context::class.java, String::class.java, Integer.TYPE, JSONObject::class.java, panelDelegate)
            }
            // 默认工具清单的上游（混淆名，跨构建会整体重排 —— 只认形状，不认名字）。
            // 形状：被 quickservice.toolbox.a.a 与 ToolboxPanelV2/V3.init 调用、静态、无参、
            //   返回 String、内容是带 id0 的 JSON。候选表在 AmapData.TOOL_DEFAULT_RAW，
            //   两版实测：国内 17.02.0.1541 = k84.f()，Play 17.02.2.2014 = s27.d()。
            val rawAt = arrayOfNulls<String>(2)
            val raw = H.anchor(cl, AmapData.TOOL_DEFAULT_RAW as Array<Array<String>?>, rawAt)
            if (raw != null) {
                H.hookSig(raw, rawAt[1]!!, "amap_tool_default_raw", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        if (result !is String) return result
                        // **先存后过滤**：缓存的是原厂串。AmapData.defaultSlot() 用它当"默认槽位
                        // 模板"，如果存的是过滤后的串，模板里会少掉被裁掉的槽位。
                        // 这一行同时是递归的解药：缓存一旦有了，defaultSlot 就再也不反射调 k84.f()。
                        AmapData.rememberDefaultRaw(result)
                        val filtered = AmapData.toolboxRaw(result, AmapData.CURRENT)
                        if (filtered != null && filtered != result && !toolDefaultReported) {
                            toolDefaultReported = true
                            H.log("event=amap_tool_default_filter raw=" + result.length +
                                    " kept=" + filtered.length)
                        }
                        return filtered ?: result
                    }
                })
            } else {
                H.log("event=anchor_miss anchor=tool_default_raw note=默认工具清单上游缺失，" +
                        "工具过滤只剩工厂 + 存储两路")
            }
        }

        /** 单槽位全字段诊断（确认 native 渲染器为什么跳过某格）。 */
        private fun slotDump(p: JSONObject, i: Int): String {
            val sb = StringBuilder()
            for (f in arrayOf("id", "name", "schema", "size", "display", "containerClass",
                    "isLottie", "displayLottie", "displayImage", "imageUrlRender", "imageToken",
                    "imageClass", "imageLegacy", "icon", "moreItem", "weight")) {
                var v = p.optString(f + i)
                if (v.length > 60) v = v.substring(0, 60) + "…"
                sb.append(f).append('=').append(v).append(' ')
            }
            return sb.toString()
        }

        /** 102 vs 104 等原生槽位的 icon 字段对比（一次性诊断，只读不改）。 */
        private fun slotIconDump(p: JSONObject): String {
            val sb = StringBuilder()
            try {
                for (i in 0 until 10) {
                    val id = p.optString("id" + i)
                    if (id.isEmpty()) continue
                    sb.append('#').append(i).append('=').append(id)
                    for (f in arrayOf("icon", "imageUrlRender", "imageToken", "imageLegacy",
                            "imageClass", "displayImage", "displayLottie", "size")) {
                        val v = p.optString(f + i)
                        if (v.isNotEmpty()) sb.append(',').append(f).append('=')
                                .append(if (v.length > 48) v.substring(0, 48) + "…" else v)
                    }
                    sb.append(' ')
                }
            } catch (ignored: Throwable) {
            }
            return sb.toString()
        }

        /** 过滤前/后槽位对比日志只报一次。 */
        @Volatile
        private var toolGridReported = false

        /** 工厂过滤只报一次（首页每次重建都会走，不节流会刷屏）。 */
        @Volatile
        private var toolFactoryReported = false

        /** 默认清单上游过滤只报一次。 */
        @Volatile
        private var toolDefaultReported = false

        private fun chipsHook(chain: XposedInterface.Chain, cl: ClassLoader, via: String): Any? {
            // 模块自己反射调 buildHomeAndCompanyView 时原样放行：payload 已由 nativeChipsPayload
            // 备好（uid 真值 + 完整字段），这里再改一遍只会重复日志 / 重复 relabel。
            if (java.lang.Boolean.TRUE == IN_NATIVE_BUILD.get()) return chain.proceed()
            val show = Config.visible(Config.K_HOME_CHIPS)
            val data = chain.getArg(3)
            var items = -1
            var enable = -1
            var uid = "?"

            // The payload is parsed -- and cached -- before the switch is consulted. Two things
            // need it later: the fallback row, and the in-place rebuild that makes the switch take
            // effect without waiting for the home page to be recreated.
            var payload: JSONObject? = null
            if (data is JSONObject) {
                payload = data
            } else {
                try {
                    payload = JSONObject(data.toString())
                } catch (ignored: Throwable) {
                }
            }
            if (payload != null) chipsPayload = payload

            try {
                val probe = payload ?: JSONObject()
                val list = probe.optJSONArray("items")
                items = if (list == null) 0 else list.length()
                enable = probe.optInt("enable", -1)
                uid = probe.optString("uid")
            } catch (ignored: Throwable) {
            }

            if (!show) {
                H.log("event=home_chips_hide via=" + via + " reason=switch_off")
                return null
            }
            if (payload == null || items <= 0) {
                H.log("event=home_chips_miss via=" + via + " reason=payload_null items=" + items)
                return null
            }
            val pl = payload

            // The native builder requires payload.uid to equal the signed-in uid and returns
            // null otherwise. The cached home_and_company_data can carry an empty uid (stale
            // cache after an account switch), which is why the whole row stayed invisible.
            // The payload is otherwise complete, so align the uid and let the app's own builder
            // render it -- that keeps the row pixel-identical to the native one.
            var alignedUid: String? = null
            if (items > 0) {
                val current = currentUid(cl)
                if (current != null && current.isNotEmpty() && current != uid) {
                    try {
                        pl.put("uid", current)
                        alignedUid = current
                    } catch (t: Throwable) {
                        H.log("amap_home_chips uid_align_failed=" + t.javaClass.simpleName)
                    }
                }
            }

            // A Chain may only be proceeded once, so the arguments are prepared up front and
            // exactly one call is made.
            var built: Any? = null
            try {
                built = if (alignedUid == null) chain.proceed()
                        else chain.proceed(arrayOf(chain.getArg(0), chain.getArg(1),
                                chain.getArg(2), pl))
            } catch (error: Throwable) {
                chipsFuseBlown = true
                H.log("event=home_chips_fuse_blown reason=builder_throw err=" + error.javaClass.simpleName)
                return null
            }
            if (built != null) {
                if (built is View) chipsRows.add(built)
                H.log("event=home_chips_show via=native_payload hook=" + via +
                        " items=" + items + " uid_aligned=" + (alignedUid != null) +
                        " view=" + built.javaClass.simpleName)
                return built
            }

            // built == null means the native builder itself refused (enable==0, uid mismatch,
            // or no items). One line here is enough to tell those apart later.
            var raw = data.toString()
            if (raw.length > 300) raw = raw.substring(0, 300) + "..."
            H.log("amap_home_chips refused via=" + via + " enable=" + enable + " items=" + items +
                    " uid=[" + uid + "] aligned=[" + alignedUid + "] payload=" + raw)

            // Last resort: draw the row from the same payload. Unit conversion follows the
            // app's own helper exactly, dx1.l(v) = round(v / 2 * density).
            if (items > 0) {
                val fallback = chips(cl, chain.getArg(0) as? Context, pl)
                if (fallback != null) {
                    chipsRows.add(fallback)
                    H.log("event=home_chips_show via=fallback_view hook=" + via + " items=" + items)
                    return fallback
                }
            }
            H.log("event=home_chips_miss via=" + via + " reason=no_view items=" + items)
            return null
        }

        /**
         * Read the currently signed-in account uid exactly the way the native builder does
         * (BundleServiceManager -> IAccountService.getUID). Returns "" when unavailable, in
         * which case the caller must not touch the payload.
         */
        private fun currentUid(cl: ClassLoader): String {
            try {
                val manager = cl.loadClass("com.autonavi.wing.BundleServiceManager")
                val instance = manager.getMethod("getInstance").invoke(null)
                val service = cl.loadClass("com.autonavi.bundle.account.api.IAccountService")
                val account = manager.getMethod("getBundleService", Class::class.java).invoke(instance, service)
                if (account == null) {
                    lastUidSource = "empty"
                    return ""
                }
                val uid = service.getMethod("getUID").invoke(account)
                if (uid == null || uid.toString().isEmpty()) {
                    lastUidSource = "empty"
                    return ""
                }
                lastUidSource = "account"
                return uid.toString()
            } catch (t: Throwable) {
                lastUidSource = "empty"
                return ""
            }
        }

        /* ---- 17.02 home-chips payload injection (in-memory only) --------------------------
         * 真机证据（17.02.0.1541 / classes5.dex）：
         *   ToolboxPanelV2.init code_off=0x41fb68:
         *     [38] const-string "home_and_company_data"
         *     [46] invoke-virtual Lwm3;->c(String,String)String     ← 读 main_page 偏好
         *     [50] TextUtils.isEmpty(...)
         *     [54] if-*                                            ← 为空则整段跳过
         *     [61] buildHomeAndCompanyView(...)
         *   运行期：search_grid_helper op=get key=home_and_company_data len=0
         *   ⇒ 服务端不下发时宿主根本不建这一行，两个 chips 钩子永远不 HIT。
         *
         * 修法（内存注入，绝不落盘）：hook `wm3.c(String,String)String`，只在
         * 「key == home_and_company_data && 调用线程正处在 ToolboxPanelV2.init 内 &&
         *   原值为空 && 开关 ON」时返回合成 payload。宿主 init 拿到非空 payload 后会自己调用
         * `buildHomeAndCompanyView` 原生渲染 ⇒ 样式与点击 100% 原生，且 main_page.xml 一个字节都不写。
         * 开关 OFF / 服务端真下发了值 ⇒ 原样返回，行自然消失/使用真数据。
         *
         * payload 字段全部来自 buildHomeAndCompanyView/buildHomeAndCompanyItem 的 const-string；
         * 标题与 scheme 抄自高德自己的桌面 widget 默认数据 Lxl5;->g(I)Lt42; (0x4099d4)。
         */
        private const val KEY_HOME_COMPANY = "home_and_company_data"

        /**
         * ★ 总开关（默认 false）：合成 payload 注入**已停用**。
         *
         * <p>原因：注入能让高德的 {@code ToolboxPanelV2.buildHomeAndCompanyView} 真的跑起来，
         * 但**我们拼的 payload schema 是反推猜的**（`icon`/`background`/`radius`/`textSize` 的
         * 真实 key 名与类型未确认，`isSameUser` 的 uid 也对不上 ⇒ 日志 `uid_aligned=false`）。
         * 结果是一行**降级渲染**：无图标、无白卡背景、位置游离在工具行下方 —— 比不显示更难看。
         *
         * <p>要重新启用，必须先做到：① 从 `buildHomeAndCompanyItem` 的字节码里逐字段确认
         * schema；② 拿到高德内部真实 uid。两者都确认后再置 true。
         */
        private const val PAYLOAD_INJECT = false

        /** 合成 payload 的 JSON 缓存（纯内存，进程重启即消失）。 */
        @Volatile
        private var localChipsJson: String? = null

        /** 熔断：一旦注入导致构建异常或面板高度异常，本进程内不再注入。 */
        @Volatile
        private var chipsFuseBlown = false

        /** 只在 ToolboxPanelV2/V3.init 的调用线程上允许返回合成 payload。 */
        private val IN_TOOLBOX_INIT = ThreadLocal<Boolean>()

        /** 我们自己反射调用 buildHomeAndCompanyView 时置位：让 chipsHook 原样放行，不重复改写/重复日志。 */
        private val IN_NATIVE_BUILD = ThreadLocal<Boolean>()

        /** 高德自己的 drawable 设计 token（ResourcesUtil 白名单里实测存在，比外网 URL 稳）。 */
        private const val ICON_HOME = "@desktop_widget_commute_home"
        private const val ICON_COMPANY = "@desktop_widget_commute_company"
        private const val ICON_ADD = "@add"

        /**
         * 原生行用的设计 token 图标：直接抄自高德真机下发的 {@code main_page.xml / home_and_company_data}
         * 里 items[].iconToken 的值 —— 与 {@code buildHomeAndCompanyItem} 的
         * {@code DtImageView.proxy().s(icon)}（dt_src 设计 token 解析）完全同源。
         * 比之前的 tokenImg 外网 URL 更稳（离线可用、跟随主题）。
         */
        private const val TOKEN_HOME = "@Img_QS_home_company_Home"
        private const val TOKEN_COMPANY = "@Img_QS_home_company_Company"
        private const val TOKEN_ADD = "@Img_QS_home_company_Add"

        /** 未设置家/单位时的服务端原值：source=0 落到设置页（已设置才换成 source=1 的回家/去单位）。 */
        private const val SCHEME_HOME_SET =
                "amapuri://routecommute?source=0&type=home&subs=0&fromPage=basemap"
        private const val SCHEME_COMPANY_SET =
                "amapuri://routecommute?source=0&type=company&subs=0&fromPage=basemap"

        /** 高德自己的通勤设置路由（classes4.dex Lzg1;->i(Lt42;)V @0x636780 [174]/[225]）。 */
        private const val SCHEME_HOME =
                "amapuri://routecommute?source=1&type=home&clearStack=1" +
                        "&sourceApplication=widget_card&widgetType=searchtool_card&clickID=home"
        private const val SCHEME_COMPANY =
                "amapuri://routecommute?source=1&type=company&clearStack=1" +
                        "&sourceApplication=widget_card&widgetType=searchtool_card&clickID=company"

        /** 常去地点管理页（classes5.dex Lxl5;->g(I)Lt42; @0x4099d4 [24] const-string）。 */
        private const val SCHEME_FREQ =
                "amapuri://ajx?path=path://amap_bundle_fre_loc/src/pages/" +
                        "FrequentLocationManage.page.js&transition_mode=fullpage&wvc=1" +
                        "&sourceApplication=widget_card&widgetType=frequent_places&clickID=blank_space"

        private fun localChipsPayload(cl: ClassLoader): JSONObject? {
            try {
                val payload = JSONObject()
                payload.put("enable", 1)
                val uid = currentUid(cl)
                payload.put("uid", if (uid == null) "" else uid)
                // 数值全部按 buildHomeAndCompanyView/Item 的字节码（tg3.m = ×2）：
                // height 42 -> 84px；margin left/right 16 -> 32px；spacing 8 -> 16px
                payload.put("height", 42)
                val margin = JSONArray()
                margin.put(0); margin.put(0); margin.put(0); margin.put(16)
                payload.put("margin", margin)
                payload.put("split", 1)
                payload.put("spacing", 8)
                val items = JSONArray()
                items.put(localChipItem("设置家", ICON_HOME, SCHEME_HOME))
                items.put(localChipItem("设置单位", ICON_COMPANY, SCHEME_COMPANY))
                items.put(localChipItem("添加常去地点", ICON_ADD, SCHEME_FREQ))
                payload.put("items", items)
                H.log("event=home_chips_payload_build items=3 uid_source=" + lastUidSource)
                return payload
            } catch (t: Throwable) {
                H.log("event=home_chips_miss reason=payload_build_failed err=" + t.javaClass.simpleName)
                return null
            }
        }

        private fun localChipItem(name: String, icon: String, scheme: String): JSONObject {
            val item = JSONObject()
            item.put("name", name)
            item.put("icon", icon)
            item.put("iconToken", icon)
            // textSize 17 -> m()=34px = 13sp @ density 2.625；padding 16 -> 32px；radius 16 -> 32px
            item.put("textSize", 15)
            item.put("textColor", "#e6000000")
            item.put("paddingLeft", 16)
            item.put("paddingRight", 16)
            item.put("radius", 16)
            item.put("background", "#FFFFFFFF")
            item.put("scheme", scheme)
            return item
        }

        /**
         * 给高德原生 builder 用的 payload —— 字段与类型逐个抄自
         * {@code buildHomeAndCompanyView} / {@code buildHomeAndCompanyItem} 的 const-string：
         * enable / uid / height / margin[4] / split / spacing / items[]
         * (name / icon / textSize / textColor / paddingLeft / paddingRight / radius /
         *  background / scheme)。
         *
         * <p>{@code uid} 必须是 {@code IAccountService.getUID()} 的真值，否则
         * {@code isSameUser} 返回 false、builder 直接 return null —— 这就是之前这一行永远不显示的根因。
         * {@code m(v) = 2v}（tg3.m），所以这里的数值都是「设计值」，builder 内部会 ×2 转 px。
         */
        private fun nativeChipsPayload(cl: ClassLoader): JSONObject? {
            try {
                val payload = JSONObject()
                payload.put("enable", 1)
                var uid = currentUid(cl)
                if (uid == null) uid = ""
                payload.put("uid", uid)
                H.log("event=home_chips_uid source=" + lastUidSource + " value=" + uid)
                payload.put("height", 64)                 // 高德真实下发值（m()=128px）
                val margin = JSONArray()        // 顺序 [top, right, bottom, left]
                margin.put(30); margin.put(24); margin.put(0); margin.put(24)   // 高德真实下发值
                payload.put("margin", margin)
                // ★ 唯一偏离真值的地方：真值是 0，但那是 V3 横滑容器（每格 WRAP_CONTENT 左对齐）；
                //   V2 是 3 项横排铺满整行，split=1（width=0, weight=1）才能三等分，否则会挤在左边。
                payload.put("split", 1)
                payload.put("spacing", 12)                // 高德真实下发值（m()=24px）
                val items = JSONArray()
                items.put(nativeChipItem("设置家", TOKEN_HOME, SCHEME_HOME_SET))
                items.put(nativeChipItem("设置单位", TOKEN_COMPANY, SCHEME_COMPANY_SET))
                items.put(nativeChipItem("常去地点", TOKEN_ADD, SCHEME_FREQ))
                payload.put("items", items)
                // 已设置家/单位 ⇒ name + scheme 成对替换（家→回家 + routecommute type=home，单位同理）。
                val out = JSONObject(relabelChips(payload.toString(), cl))
                H.log("event=home_chips_payload items=3 uid_source=" + lastUidSource +
                        " home=" + homeSet(cl) + " company=" + companySet(cl))
                return out
            } catch (t: Throwable) {
                H.log("event=home_chips_miss reason=payload_build_failed err=" + t.javaClass.simpleName)
                return null
            }
        }

        private fun nativeChipItem(name: String, icon: String, scheme: String): JSONObject {
            val item = JSONObject()
            item.put("name", name)
            item.put("icon", icon)
            item.put("textSize", 24)                 // 高德真实下发值（m()=48px）
            item.put("textColor", "@Color_Text_L1")  // 设计 token，深色模式自动适配
            item.put("paddingLeft", 24)              // 高德真实下发值
            item.put("paddingRight", 24)             // 高德真实下发值
            item.put("radius", 40)                   // 高德真实下发值（m()=80px）
            item.put("background", "@Color_BG_L2")   // 高德真实下发值（ra0.a → pv1.g 支持 @token）
            item.put("scheme", scheme)
            return item
        }

        /**
         * 按「家/单位是否已设置」把 payload 里的 name 文案改掉：
         * 设置家 -> 回家、设置单位 -> 去单位。只替换 name 字段，不动 icon/scheme。
         * 兼容 "name":"x" 与 "name": "x" 两种写法（服务端 JSON 可能带空格）。
         */
        private fun relabelChips(json: String?, cl: ClassLoader): String? {
            if (json == null || json.isEmpty()) return json
            var out: String = json
            try {
                if (homeSet(cl)) {
                    out = out.replace("\"name\":\"设置家\"", "\"name\":\"回家\"")
                            .replace("\"name\": \"设置家\"", "\"name\": \"回家\"")
                    out = replaceScheme(out, "home", SCHEME_HOME)
                }
                if (companySet(cl)) {
                    out = out.replace("\"name\":\"设置单位\"", "\"name\":\"去单位\"")
                            .replace("\"name\": \"设置单位\"", "\"name\": \"去单位\"")
                    out = replaceScheme(out, "company", SCHEME_COMPANY)
                }
                // 第 3 项：常去地点 -> 添加常去地点 + 常去地点管理页（服务端给的是搜索页）
                out = out.replace("\"name\":\"常去地点\"", "\"name\":\"添加常去地点\"")
                        .replace("\"name\": \"常去地点\"", "\"name\": \"添加常去地点\"")
                try {
                    val repl = java.util.regex.Matcher.quoteReplacement(SCHEME_FREQ)
                    out = java.util.regex.Pattern.compile("\"scheme\"\\s*:\\s*\"[^\"]*SinglePoiSearchPage[^\"]*\"")
                            .matcher(out).replaceAll("\"scheme\":\"" + repl + "\"")
                    out = java.util.regex.Pattern.compile("\"schema\"\\s*:\\s*\"[^\"]*SinglePoiSearchPage[^\"]*\"")
                            .matcher(out).replaceAll("\"schema\":\"" + repl + "\"")
                } catch (ignored: Throwable) {
                }
            } catch (ignored: Throwable) {
            }
            if (!schemeProbed) {
                schemeProbed = true
                try {
                    val i = out.indexOf("\"scheme\"")
                    H.log("event=home_chips_scheme_probe head=" +
                            out.substring(i, Math.min(out.length, i + 260)))
                } catch (ignored: Throwable) {
                }
            }
            return out
        }

        @Volatile
        private var schemeProbed = false

        /**
         * 把 payload 里 type=home/company 的 scheme（以及同值的 schema）换成可导航的
         * routecommute 路由。服务端原值是 `amapuri://routecommute?source=0&type=home&subs=0
         * &fromPage=basemap` —— source=0 会落到设置页；source=1 才是回家/去单位导航。
         */
        private fun replaceScheme(json: String, type: String, scheme: String): String {
            var out = json
            try {
                val repl = java.util.regex.Matcher.quoteReplacement(scheme)
                out = java.util.regex.Pattern.compile("\"scheme\"\\s*:\\s*\"[^\"]*type=" + type + "[^\"]*\"")
                        .matcher(out).replaceAll("\"scheme\":\"" + repl + "\"")
                out = java.util.regex.Pattern.compile("\"schema\"\\s*:\\s*\"[^\"]*type=" + type + "[^\"]*\"")
                        .matcher(out).replaceAll("\"schema\":\"" + repl + "\"")
            } catch (ignored: Throwable) {
            }
            return out
        }

        /** wm3.c 的返回值替换：只认 ToolboxPanelV2/V3.init 线程上的 home_and_company_data。 */
        private fun injectChipsPayload(cl: ClassLoader, original: Any?): String? {
            if (!PAYLOAD_INJECT) return null   // 已停用：见 PAYLOAD_INJECT 注释
            if (chipsFuseBlown || !Config.visible(Config.K_HOME_CHIPS)) return null
            if (java.lang.Boolean.TRUE != IN_TOOLBOX_INIT.get()) return null
            val value = if (original == null) "" else original.toString()
            if (value.isNotEmpty()) return null              // 服务端/宿主已有真数据，绝不覆盖
            var cached = localChipsJson
            if (cached == null) {
                val payload = localChipsPayload(cl) ?: return null
                cached = payload.toString()
                localChipsJson = cached
            }
            return cached
        }


        /**
         * V2.init 之后补行：**直接反射调用高德自己的**
         * {@code ToolboxPanelV2.buildHomeAndCompanyView(Context,String,int,JSONObject)}，
         * 把返回的 DtLinearLayout 原样挂到面板根上 —— 与宿主 {@code init} 里的
         * {@code this.addView(buildHomeAndCompanyView(...))} 是同一个位置、同一个 View 类。
         *
         * <p>不自绘（SELF_DRAW_CHIPS 保持 false）、不写宿主 prefs；builder 返回 null 或抛异常
         * 一律记 fuse_blown 并在本进程内不再重试，绝不影响首页其它部分。
         */
        private fun ensureV2ChipsRow(panel: View?, cl: ClassLoader, theme: String?, mode: Int, via: String) {
            if (panel !is ViewGroup) return
            val parent = panel
            val show = Config.visible(Config.K_HOME_CHIPS)
            val existing = findNativeChipsRow(parent)
            if (!show) {
                if (existing != null) {
                    val vp = existing.parent
                    if (vp is ViewGroup) vp.removeView(existing) else parent.removeView(existing)
                    chipsRows.remove(existing)
                    H.log("event=home_chips_hide via=" + via)
                }
                return
            }
            if (existing != null) {
                H.log("event=home_chips_skip via=" + via + " reason=exists")
                return
            }
            if (chipsFuseBlown) {
                H.log("event=home_chips_miss via=" + via + " reason=fuse_blown")
                return
            }
            val builder = v2ChipsMethod
            if (builder == null) {
                H.log("event=home_chips_miss via=" + via + " reason=no_method")
                return
            }
            val payload = nativeChipsPayload(cl)
            if (payload == null) {
                H.log("event=home_chips_miss via=" + via + " reason=payload_null")
                return
            }
            var created: Any? = null
            try {
                IN_NATIVE_BUILD.set(java.lang.Boolean.TRUE)
                try {
                    created = builder.invoke(panel, parent.context, theme, mode, payload)
                } finally {
                    IN_NATIVE_BUILD.remove()
                }
            } catch (t: Throwable) {
                chipsFuseBlown = true
                H.log("event=home_chips_fuse_blown via=" + via +
                        " reason=builder_throw err=" + t.javaClass.simpleName)
                return
            }
            if (created !is View) {
                chipsFuseBlown = true
                H.log("event=home_chips_fuse_blown via=" + via +
                        " reason=null_view uid_source=" + lastUidSource)
                return
            }
            val row: View = created
            chipsRows.add(row)
            // 宿主 init 的字节码是 `v0.addView(chips)`，v0 = a.a(...) 返回的**工具宫格容器**
            // （[67] invoke-virtual ViewGroup->addView regs=v0,v2），不是面板根 ——
            // 所以必须挂进同一个容器，位置才是「工具宫格下面、AI 卡上面」。
            val host = resolveChipsHost(parent)
            if (host == null) {
                // 拿不到工具宫格容器（V3 或面板结构不同）⇒ 不插。官方自己有这一行，别叠。
                H.log("event=home_chips_miss via=" + via + " reason=no_tool_container")
                return
            }
            val insertAt = Math.min(1, host.childCount)
            host.addView(row, insertAt)
            dumpPanelChildren(parent)
            H.log("event=home_chips_insert idx=" + insertAt + " of=" + host.childCount +
                    " after=" + (if (insertAt > 0) host.getChildAt(insertAt - 1).javaClass.name else "none") +
                    " host=" + host.javaClass.name)
            H.log("event=home_chips_show via=native_builder view=" + row.javaClass.simpleName +
                    " items=" + countItems(payload) + " uid_source=" + lastUidSource +
                    " host=" + via)
            // 熔断哨兵：3 秒后面板仍挂在窗口上却高度为 0 ⇒ 撤掉这一行并停用，宁可没有也不能毁首页。
            parent.postDelayed(object : Runnable {
                override fun run() {
                    if (chipsFuseBlown) return
                    if (!parent.isAttachedToWindow) return
                    if (parent.height > 0) return
                    chipsFuseBlown = true
                    val found = findNativeChipsRow(parent)
                    if (found != null) parent.removeView(found)
                    H.log("event=home_chips_fuse_blown reason=panel_height_zero")
                }
            }, 3000)
        }

        /**
         * 面板根上是否已经挂着「设置家 / 设置单位 / 常去地点」这一行（防重复 add）。
         * 只认 DtLinearLayout + 文本命中，避免把 AI 卡 / 工具宫格误判成 chips 行。
         */
        private fun findNativeChipsRow(panel: ViewGroup): View? {
            for (i in 0 until panel.childCount) {
                val child = panel.getChildAt(i)
                if (chipsRows.contains(child)) return child
                if (child !is ViewGroup) continue
                if (child.javaClass.name.endsWith("DtLinearLayout") &&
                        containsChipText(child)) return child
                val nested = findNativeChipsRow(child)
                if (nested != null) return nested
            }
            return null
        }

        /**
         * chips 行该挂的容器：宿主原生路径就是 a.a(...) 返回的工具宫格容器。
         *
         * <p>★ 只在「拿得到工具宫格容器、且它确实挂在这个面板上」时才返回；否则返回 null。
         * 绝不再退回面板根 —— 面板根是 FrameLayout，挂上去会叠在工具宫格上面（上一版的 bug），
         * 而且 V3 页面官方自己已经画了这一行，再插就是重复。
         */
        private fun resolveChipsHost(panel: ViewGroup): ViewGroup? {
            val container = built?.container?.get()
            if (container != null && container.parent === panel) return container
            return null
        }

        private fun dumpPanelChildren(panel: ViewGroup) {
            for (i in 0 until panel.childCount) {
                val child = panel.getChildAt(i)
                H.log("event=panel_child idx=" + i + " cls=" + child.javaClass.name +
                        " h=" + child.height + " w=" + child.width)
            }
        }

        private fun containsChipText(group: ViewGroup): Boolean {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is TextView) {
                    val text = child.text
                    if (text == null) continue
                    val s = text.toString()
                    if (s.contains("设置家") || s.contains("回家") ||
                            s.contains("设置单位") || s.contains("去单位") || s.contains("去公司") ||
                            s.contains("常去地点")) return true
                } else if (child is ViewGroup && containsChipText(child)) {
                    return true
                }
            }
            return false
        }

        private fun findChipsRow(panel: ViewGroup): View? {
            for (i in 0 until panel.childCount) {
                val child = panel.getChildAt(i)
                if (chipsRows.contains(child)) return child
                if (child is ViewGroup) {
                    val nested = findChipsRow(child)
                    if (nested != null) return nested
                }
            }
            return null
        }

        private fun chipsInsertIndex(panel: ViewGroup): Int {
            val container = built?.container?.get()
            if (container != null && container.parent === panel) {
                return panel.indexOfChild(container) + 1
            }
            return Math.min(1, panel.childCount)
        }

        private fun countItems(payload: JSONObject?): Int {
            val items = payload?.optJSONArray("items")
            return if (items == null) 0 else items.length()
        }

        /**
         * Rebuild the 「设置家 / 设置单位 / 常去地点」 row when the native builder bails out.
         *
         * g13.a (HomeCompanyBuilderV3) returns null unless the payload's uid equals the
         * logged-in account uid. On this device the cached payload carries real items but an
         * empty uid, so the row is never created and the 设置家 switch looks dead. The payload
         * is otherwise complete (items[].name / .iconToken / .scheme), so the same row is drawn
         * here. Every text, target and measurement comes from the app's own payload; nothing is
         * invented.
         */

        /**
         * 「设置家 / 设置单位 / 常去地点」自绘行 —— **照高德原生的线性图标 + 文字样式**。
         *
         * <h3>为什么自绘而不是注入 payload</h3>
         * 注入 payload 能让宿主的 {@code buildHomeAndCompanyView} 跑起来，但那份 schema 是我们
         * 反推猜的（`icon`/`background`/`radius` 的真实 key 与类型未确认，`isSameUser` 的 uid 也对
         * 不上 ⇒ `uid_aligned=false`），渲染结果是**降级版**：无图标、无背景、位置游离。
         * 实测比不显示更难看，所以改为自绘，规格照用户确认的原生样式：
         * <ul>
         *   <li>三格**等宽均分**（weight=1），整行白底、无卡片、无描边</li>
         *   <li>每格：**18dp 线性图标 + 6dp 间距 + 13sp 文字**，整体水平居中</li>
         *   <li>文字 {@code #FF191919} / {@code sans-serif-medium}（与工具栏统一规格）</li>
         *   <li>行高 44dp，上边距 6dp</li>
         * </ul>
         * 图标用本文件内的 {@link ChipGlyph} 绘制（house / building / plus），不依赖 drawable 资源。
         */

        /** 自绘「设置家」卡片：第 1 行两列（回家 / 去单位），第 2 行整行（添加常去地点）。 */
        private fun chips(cl: ClassLoader, context: Context?, payload: JSONObject?): ViewGroup? {
            try {
                val ctx = context!!
                val items = payload!!.optJSONArray("items")
                if (items == null || items.length() < 3) return null
                val card = LinearLayout(ctx)
                card.orientation = LinearLayout.VERTICAL
                val bg = android.graphics.drawable.GradientDrawable()
                bg.setColor(0xFFFFFFFF.toInt())
                val radius = m(12)                       // 12dp -> 24px
                val r = radius.toFloat()
                bg.setCornerRadii(floatArrayOf(r, r, r, r, r, r, r, r))
                bg.setStroke(2, 0x14000000)              // 1px 描边
                card.background = bg
                val cardLp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                cardLp.leftMargin = m(21)                // 16dp -> 42px
                cardLp.rightMargin = m(21)
                card.layoutParams = cardLp
                card.setPadding(m(21), m(16), m(21), m(16))   // 左右 16dp / 上下 12dp

                // 第 1 行：两列等宽
                val row1 = LinearLayout(ctx)
                row1.orientation = LinearLayout.HORIZONTAL
                card.addView(row1, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                for (i in 0 until 2) {
                    val item = items.optJSONObject(i) ?: continue
                    row1.addView(chipCell(ctx, item, i, false), LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }

                // 第 2 行：整行「添加常去地点」
                val add = items.optJSONObject(2)
                if (add != null) {
                    val row2 = LinearLayout(ctx)
                    row2.orientation = LinearLayout.HORIZONTAL
                    val row2Lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    row2Lp.topMargin = m(13)             // 10dp -> 26px
                    card.addView(row2, row2Lp)
                    row2.addView(chipCell(ctx, add, 2, true), LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                return card
            } catch (t: Throwable) {
                H.log("event=home_chips_miss reason=chips_draw_failed err=" + t.javaClass.simpleName)
                return null
            }
        }

        private fun chipCell(context: Context, item: JSONObject, idx: Int, addRow: Boolean): View {
            val cell = LinearLayout(context)
            cell.orientation = LinearLayout.HORIZONTAL
            cell.gravity = android.view.Gravity.CENTER_VERTICAL
            cell.setPadding(m(4), m(4), m(4), m(4))
            val icon = ChipGlyph(context, idx)
            val ilp = LinearLayout.LayoutParams(m(20), m(20))
            cell.addView(icon, ilp)
            val label = TextView(context)
            label.text = item.optString("name")
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m(20).toFloat())  // 15sp -> 40px
            label.setTextColor(if (addRow) 0xFF666666.toInt() else 0xFF333333.toInt())
            label.isSingleLine = true
            val llp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            llp.marginStart = m(4)                 // 8px
            label.layoutParams = llp
            cell.addView(label)
            chipLabels[label] = idx
            cell.isClickable = true
            cell.isFocusable = true
            cell.contentDescription = item.optString("name")
            try {
                cell.background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x1A000000), null, null)
            } catch (ignored: Throwable) {
            }
            val scheme = item.optString("scheme")
            cell.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    openChipsScheme(v.context, scheme, idx)
                }
            })
            return cell
        }

        /** 读高德自己的状态：SP_KEY_HOME_INFO / SP_KEY_COMPANY_INFO（Lml5;->b()/a()）。 */
        private fun homeSet(cl: ClassLoader): Boolean {
            try {
                val c = cl.loadClass("ml5")
                val v = c.getMethod("b").invoke(null)
                return v != null && v.toString().isNotEmpty()
            } catch (t: Throwable) {
                return false
            }
        }

        private fun companySet(cl: ClassLoader): Boolean {
            try {
                val c = cl.loadClass("ml5")
                val v = c.getMethod("a").invoke(null)
                return v != null && v.toString().isNotEmpty()
            } catch (t: Throwable) {
                return false
            }
        }

        /** 按当前状态刷新三格文案：设置家→回家、设置单位→去单位。 */
        private fun applyChipState(cl: ClassLoader) {
            val home = homeSet(cl)
            val company = companySet(cl)
            H.log("event=home_chips_state home=" + home + " company=" + company)
            for (e in chipLabels.entries) {
                val v = e.key
                if (v !is TextView) continue
                val idx = e.value
                val text = if (idx == 0) (if (home) "回家" else "设置家")
                else if (idx == 1) (if (company) "去单位" else "设置单位") else "添加常去地点"
                v.text = text
            }
        }

        private fun scheduleChipStateRefresh(cl: ClassLoader) {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            for (delay in intArrayOf(1500, 3000, 6000, 10000)) {
                h.postDelayed(object : Runnable {
                    override fun run() {
                        applyChipState(cl)
                    }
                }, delay.toLong())
            }
        }

        /** 原生 tg3.m(F)I 的等价实现：纯 ×2（classes8.dex @0x407908，无 density）。 */
        private fun m(v: Int): Int {
            return v * 2
        }

        private fun parseColor(value: String?, fallback: Int): Int {
            if (value == null || value.isEmpty()) return fallback
            try {
                if (value[0] == '#') return android.graphics.Color.parseColor(value)
            } catch (ignored: Throwable) {
            }
            return fallback
        }

        /**
         * 点开「设置家 / 设置单位 / 常去地点」对应的原生页面。
         *
         * <p>原生 {@code ToolboxPanelV2$f.onClick} 的做法是
         * {@code AMapAppGlobal.getTopActivity().solveScheme(Intent)}；这里优先复刻同一条通路，
         * 失败再退到 ACTION_VIEW + setPackage（仍由高德自己的 SchemeHandler 接收）。
         */
        private fun openChipsScheme(context: Context?, scheme: String?, idx: Int) {
            if (context == null || scheme == null || scheme.isEmpty()) {
                H.log("event=home_chips_click_fail idx=" + idx + " reason=no_scheme")
                return
            }
            val intent: Intent
            try {
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (t: Throwable) {
                H.log("event=home_chips_click_fail idx=" + idx + " reason=bad_scheme")
                return
            }
            // ① 首选：宿主自己的 solveScheme（与原生 ToolboxPanelV2$f.onClick 完全一致）
            try {
                val global = context.classLoader.loadClass("com.autonavi.amap.app.AMapAppGlobal")
                val top = global.getMethod("getTopActivity").invoke(null)
                if (top != null) {
                    var solve: Method? = null
                    try {
                        solve = top.javaClass.getMethod("solveScheme", Intent::class.java)
                    } catch (ignored: Throwable) {
                        for (itf in top.javaClass.interfaces) {
                            try {
                                solve = itf.getMethod("solveScheme", Intent::class.java)
                                break
                            } catch (ignored2: Throwable) {
                            }
                        }
                    }
                    if (solve != null) {
                        solve.invoke(top, intent)
                        H.log("event=home_chips_click idx=" + idx + " via=solveScheme scheme=" + scheme)
                        scheduleChipStateRefresh(context.classLoader)
                        return
                    }
                }
            } catch (ignored: Throwable) {
            }
            // ② 兜底：标准 ACTION_VIEW，指定高德包名
            try {
                context.startActivity(Intent(intent).setPackage(MainHook.PKG_AMAP))
                H.log("event=home_chips_click idx=" + idx + " via=startActivity scheme=" + scheme)
                scheduleChipStateRefresh(context.classLoader)
            } catch (t: Throwable) {
                H.log("event=home_chips_click_fail idx=" + idx + " reason=" + t.javaClass.simpleName)
                try {
                    android.widget.Toast.makeText(context, "打开失败", android.widget.Toast.LENGTH_SHORT).show()
                } catch (ignored: Throwable) {
                }
            }
        }

        /**
         * 三个线性图标（24×24 网格，1.8dp 描边，圆头圆角），照高德原生风格：
         * 0 = 房子（设置家）、1 = 楼宇（设置单位）、2 = 加号（常去地点）。
         */

        /**
         * 实心彩色线性图标（用户确认的图2 观感）：
         * 0 = 金色房子 #F0A020、1 = 蓝色公文包 #4A90E2、2 = 蓝色加号 #4A90E2。
         * 24 网格绘制，按 View 实际尺寸缩放；不依赖 drawable / 网络，保证一定能看见。
         */
        private class ChipGlyph(c: Context, kind: Int) : View(c) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val path = android.graphics.Path()
            private val kind: Int = kind % 3

            init {
                paint.style = android.graphics.Paint.Style.FILL
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width
                val h = height
                if (w <= 0 || h <= 0) return
                val s = Math.min(w, h) / 24f
                path.reset()
                if (kind == 0) {
                    // 金色房子：屋顶三角 + 墙体，门用白色挖空
                    paint.color = 0xFFF0A020.toInt()
                    path.moveTo(12f * s, 3.5f * s)
                    path.lineTo(21.5f * s, 12f * s)
                    path.lineTo(2.5f * s, 12f * s)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawRect(5f * s, 11.5f * s, 19f * s, 20.5f * s, paint)
                    paint.color = 0xFFFFFFFF.toInt()
                    canvas.drawRect(10f * s, 15f * s, 14f * s, 20.5f * s, paint)
                } else if (kind == 1) {
                    // 蓝色公文包：包体 + 提手
                    paint.color = 0xFF4A90E2.toInt()
                    canvas.drawRoundRect(3f * s, 8f * s, 21f * s, 20f * s, 2.2f * s, 2.2f * s, paint)
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = 1.8f * s
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                    canvas.drawRoundRect(8.5f * s, 4.5f * s, 15.5f * s, 8f * s, 1.4f * s, 1.4f * s, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                } else {
                    // 蓝色加号
                    paint.color = 0xFF4A90E2.toInt()
                    canvas.drawRoundRect(10.2f * s, 4f * s, 13.8f * s, 20f * s, 0.8f * s, 0.8f * s, paint)
                    canvas.drawRoundRect(4f * s, 10.2f * s, 20f * s, 13.8f * s, 0.8f * s, 0.8f * s, paint)
                }
            }
        }

        /**
         * The app's own unit helper, reproduced exactly: dx1.l(v) = round(v / 2 * density).
         * Payload values are "design px" at 2x, so this is the only correct conversion.
         */
        private fun px(context: Context, value: Int): Int {
            if (value <= 0) return 0
            return Math.round(value / 2f * context.resources.displayMetrics.density)
        }

        private fun rebound(self: View) {
            if (!returning.add(self)) return
            self.post(object : Runnable {
                override fun run() {
                    returning.remove(self)
                    if (!compact() || !self.isAttachedToWindow) return
                    try {
                        val state = self.javaClass.getMethod("getPanelState").invoke(self)
                        if ("EXPANDED" != state.toString()) return
                        val anchored = state.javaClass.getField("ANCHORED").get(null)
                        // Use the native animator after release; never interrupt an active drag.
                        self.javaClass.getMethod("setPanelState", state.javaClass, java.lang.Boolean.TYPE)
                                .invoke(self, anchored, true)
                        H.log("amap_compact_rebound native_anchor")
                    } catch (e: Exception) {
                        H.log("amap_compact_rebound failed=" + e.javaClass.simpleName)
                    }
                }
            })
        }

        private fun contentHeight(self: View): Int {
            try {
                // The field name differs between builds (17.02 renamed several panel members);
                // reading only mSplashToolboxPanel silently returned 0 and the panel kept its
                // default anchor, which is why the upward slide always stopped at the same place.
                var p: Any? = null
                for (field in arrayOf("mSplashToolboxPanel", "mToolboxPanel", "mQsToolboxPanel",
                        "mToolBoxPanel", "mPanel")) {
                    p = H.getObjectField(self, field)
                    if (p is View) break
                }
                if (p !is View) {
                    if (Config.debugLog()) H.log("amap_compact_height no_panel_field")
                    return 0
                }
                val panel: View = p
                val width = if (self.width > 0) self.width else self.resources.displayMetrics.widthPixels
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                // The native minimum already includes the search bar, bottom tabs and system inset.
                return minimum(self) + panel.measuredHeight + dp(self.context, 12)
            } catch (ignored: Exception) {
                return 0
            }
        }

        private fun resize(self: View) {
            if (self.width <= 0 || self.height <= 0) return
            val height = contentHeight(self)
            if (height <= 0) return
            try {
                val type = self.javaClass
                val minimum = minimum(self)
                val max = Math.min(self.height - statusBar(self), height + dp(self.context, 48))
                val last = sizes[self]
                if (last != null && last[0] == self.width && last[1] == self.height && last[2] == height && last[3] == max && last[4] == minimum) return
                val state = type.getMethod("getPanelState").invoke(self)
                if ("DRAGGING" == state.toString()) return
                sizes[self] = intArrayOf(self.width, self.height, height, max, minimum)
                type.getMethod("setMinHeight", Integer.TYPE).invoke(self, minimum)
                type.getMethod("setAnchorHeight", Integer.TYPE).invoke(self, height)
                type.getMethod("setTransparentHeight", Integer.TYPE).invoke(self, Math.max(0, self.height - max - statusBar(self)))
                type.getMethod("computeSlideRange").invoke(self)
                type.getMethod("onMeasureComplete").invoke(self)
                // Reposition once after changing the range; never reset the state during a gesture.
                type.getMethod("setPanelState", state.javaClass, java.lang.Boolean.TYPE).invoke(self, state, false)
                H.log("amap_compact_geometry min=" + minimum + " anchor=" + height + " max=" + max + " state=" + state)
            } catch (e: Exception) {
                if (Config.debugLog()) H.log("amap_compact_height error=" + e.javaClass.simpleName)
            }
        }

        private fun minimum(self: View): Int {
            // 反射句柄缓存：ClassLoader.loadClass 与 Class.getMethod 都比 Method.invoke 贵一个数量级，
            // 而 resize() 在**每次布局变化**时都会走到这里（面板拖动/展开/返回首页）。
            // 缓存后只剩一次静态 invoke + 一次实例 invoke。
            val guide = guideClass ?: self.javaClass.classLoader
                    .loadClass("com.autonavi.bundle.uitemplate.searchbar.SearchBarGuideSpHelper")
                    .also { guideClass = it }
            val mode = guideModeMethod ?: guide.getMethod("a").also { guideModeMethod = it }
            val value = mode.invoke(null)
            val type = self.javaClass
            val min = minHeightMethods[type] ?: type.getMethod("getMinHeight", java.lang.Boolean.TYPE)
                    .also { minHeightMethods[type] = it }
            return min.invoke(self, "SEARCHBAR_MODE_TOP" != value.toString()) as Int
        }

        @Volatile
        private var guideClass: Class<*>? = null

        @Volatile
        private var guideModeMethod: Method? = null

        private val minHeightMethods: MutableMap<Class<*>, Method> =
                java.util.Collections.synchronizedMap(WeakHashMap())

        /**
         * 状态栏高度，整个进程只解析一次。
         *
         * <p>原来每次调用都做 {@code getIdentifier("status_bar_height","dimen","android")} ——
         * 一次系统资源表查询。而它的调用方 {@link #resize(View)} 挂在 QS 面板的
         * onLayoutChange 上，一次 resize 里要调它两次（第 495、503 行），面板拖动时每秒几十次。
         * 同一个 dimen 在同一进程里恒定，缓存 id 与像素值即可，行为完全一致。
         */
        @Volatile
        private var statusBarResolved = false

        @Volatile
        private var statusBarPx = 0

        private fun statusBar(self: View): Int {
            if (statusBarResolved) return statusBarPx
            synchronized(this) {
                if (statusBarResolved) return statusBarPx
                var px = 0
                try {
                    val id = self.resources.getIdentifier("status_bar_height", "dimen", "android")
                    if (id != 0) px = self.resources.getDimensionPixelSize(id)
                } catch (ignored: Throwable) {
                    px = 0
                }
                statusBarPx = px
                statusBarResolved = true
                return px
            }
        }


        private fun tools(cl: ClassLoader, context: Context, boot: JSONObject, night: Boolean): LinearLayout {
            val root = cl.loadClass("com.amap.bundle.commonui.designtoken.view.viewgroup.DtLinearLayout")
                    .getConstructor(Context::class.java).newInstance(context) as LinearLayout
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            val source = JSONArray()
            val prefs = context.getSharedPreferences("toolbox", Context.MODE_PRIVATE)
            for (key in arrayOf("choiceTools", "recommendTools")) {
                var raw = prefs.getString(key, null)
                if (raw == null) raw = prefs.getString(key + "_SP_DEFAULT_ITEM", null)
                if (raw == null) continue
                val list = JSONObject(raw).optJSONArray(key)
                if (list != null) for (i in 0 until list.length()) source.put(list.opt(i))
            }
            var rawList = prefs.getString("tool_list_data", null)
            if (rawList == null) rawList = prefs.getString("tool_list_data_SP_DEFAULT_ITEM", null)
            if (rawList != null) {
                val categories = JSONObject(rawList).optJSONArray("category")
                if (categories != null) for (i in 0 until categories.length()) {
                    val category = categories.optJSONObject(i)
                    val list = category?.optJSONArray("tools")
                    if (list != null) for (j in 0 until list.length()) {
                        val item = list.optJSONObject(j)
                        if (item != null && AmapData.toolVisible(item.optString("id"), AmapData.CURRENT)) source.put(item)
                    }
                }
            }
            if (source.length() == 0) for (i in 0 until 10) {
                val id = boot.optString("id" + i)
                if (id.isNotEmpty() && "86" != id) source.put(JSONObject().put("id", id)
                        .put("name", boot.optString("name" + i)).put("schema", boot.optString("schema" + i))
                        .put("iconV2", boot.optString("imageUrlRender" + i)))
            }
            // A newly installed app may not have opened More Tools yet. Visible selections
            // still use the routes/icons extracted from that directory, never invented IDs.
            for (entry in AmapToolCatalog.ENTRIES)
                if (AmapData.toolVisible(entry[0], AmapData.CURRENT)) source.put(AmapToolCatalog.item(entry))
            val more = "amapuri://ajx?path=path://amap_bundle_toolbox/src/pages/ToolboxHomepage.page.js&style=Page&animation=1"
            source.put(JSONObject().put("id", "86").put("name", "更多工具").put("schema", more)
                    .put("iconV2", "@Img_icon_aiqs_tool_more_Png"))
            val ids = java.util.HashSet<String>()
            var row: LinearLayout? = null
            var count = 0
            for (i in 0 until source.length()) {
                val item = source.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (!ids.add(id) || !AmapData.toolVisible(id, AmapData.CURRENT)) continue
                if (count % 5 == 0) {
                    row = LinearLayout(context)
                    row.orientation = LinearLayout.HORIZONTAL
                    root.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
                val cell = LinearLayout(context)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = android.view.Gravity.CENTER
                cell.setPadding(dp(context, 2), dp(context, 4), dp(context, 2), dp(context, 4))
                cell.minimumHeight = dp(context, 76)
                val icon = cl.loadClass("com.amap.bundle.commonui.designtoken.view.DtImageView")
                        .getConstructor(Context::class.java).newInstance(context) as View
                // 与 toolCell 同一套解析：token 优先、URL 兜底、裸 token 归一。
                AmapToolIcons.load(cl, icon as android.widget.ImageView, AmapToolIcons.imageOf(item), boot, id)
                val iconLp = LinearLayout.LayoutParams(
                        dp(context, AmapToolCatalog.ICON_DP), dp(context, AmapToolCatalog.ICON_DP))
                iconLp.bottomMargin = dp(context, AmapToolCatalog.ICON_TEXT_GAP_DP)
                cell.addView(icon, iconLp)
                val label = TextView(context)
                label.text = item.optString("name")
                AmapToolCatalog.styleLabel(label)
                label.maxLines = 2
                cell.addView(label, LinearLayout.LayoutParams(-1, -2))
                val schema = item.optString("schema")
                cell.contentDescription = item.optString("name")
                cell.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        if (schema.isEmpty()) return
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(schema))
                                    .setPackage(MainHook.PKG_AMAP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            H.log("amap_tool_action failed=" + e.javaClass.simpleName)
                        }
                    }
                })
                if ("86" == id && io.github.ldxm666.mapclean.EmbeddedSettings.enabledFor(MainHook.PKG_AMAP)) {
                    cell.setOnLongClickListener(object : View.OnLongClickListener {
                        override fun onLongClick(view: View): Boolean {
                            var owner: Context = view.context
                            for (depth in 0 until 16) {
                                if (owner is android.app.Activity)
                                    return io.github.ldxm666.mapclean.EmbeddedSettings.open(owner)
                                if (owner !is android.content.ContextWrapper) break
                                val base: Context = owner.baseContext
                                if (base === owner) break
                                owner = base
                            }
                            return false
                        }
                    })
                }
                row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                count++
            }
            if (row != null) {
                var i = count % 5
                while (i > 0 && i < 5) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                    i++
                }
            }
            if (count == 0) {
                // 绝不能把整行设为 GONE：ToolboxPanelV3.init 在调用完本方法之后，
                // 还会把「设置家 / 设置单位 / 常去地点」那条 g13(HomeCompanyBuilderV3) 行
                // addView 到本返回值上。父容器一旦 GONE，后加进来的 chips 永远不可见
                // —— 这就是「设置家开关没反应」的真正原因。空 LinearLayout 本来就是 0 高，
                // 不需要也不应该靠 GONE 来收起。
                //
                // 但也不能直接还回 DtLinearLayout：它带设计令牌的卡片背景，
                // 空的时候会在工具栏下方画出一整块没有内容的灰色圆角矩形。
                val blank = LinearLayout(context)
                blank.orientation = LinearLayout.VERTICAL
                return blank
            }
            return root
        }

        /** One-shot per view: a stray container only ever needs describing once. */
        private val dumped: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

        /**
         * Dump a view subtree to logcat, once per root.
         *
         * Added to locate the empty grey card that shows up under the tool bar: the panel's
         * anchor height is computed from the panel's measured size, so a container that is still
         * reserving space for removed content inflates the panel and leaves a blank card behind.
         * Reading the real tree beats guessing which of the panel's children it is.
         */
        @JvmStatic
        fun dumpTree(label: String, root: View?) {
            // 诊断专用，默认不跑。
            //
            // 它挂在 initToolBoxPanel 的 compact 分支上，而首页面板会随开关/主题/返回首页
            // 反复重建 —— 每次都是一个**新的 root**，`dumped` 去重挡不住，于是每轮都要
            // 递归 describe() 整棵子树（深度 5、每层 12 个子节点）再往 logcat 写最多 80 行。
            // 这是纯 CPU + IO 开销，对功能没有任何作用。debug_log 打开时行为与旧版完全一致。
            if (!Config.debugLog()) return
            if (root == null || !dumped.add(root)) return
            val lines = java.util.ArrayList<String>()
            describe(lines, root, 0)
            for (i in 0 until Math.min(lines.size, 80)) H.log("amap_tree " + label + " " + lines[i])
        }

        private fun describe(out: MutableList<String>, v: View?, depth: Int) {
            if (v == null || depth > 5) return
            val sb = StringBuilder()
            for (i in 0 until depth) sb.append("  ")
            sb.append(v.javaClass.simpleName)
                    .append(" v=").append(v.visibility)
                    .append(' ').append(v.width).append('x').append(v.height)
                    .append(" bg=").append(if (v.background != null) "Y" else "n")
            if (v is TextView) {
                val t = v.text
                sb.append(" text=").append(if (t == null) "" else t.subSequence(0, Math.min(18, t.length)))
            }
            out.add(sb.toString())
            if (v is ViewGroup) {
                val n = Math.min(v.childCount, 12)
                out.add(pad(depth + 1) + "kids=" + v.childCount +
                        (if (v.childCount > n) " (first " + n + ")" else ""))
                for (i in 0 until n) describe(out, v.getChildAt(i), depth + 1)
            }
        }

        private fun pad(depth: Int): String {
            val sb = StringBuilder()
            for (i in 0 until depth) sb.append("  ")
            return sb.toString()
        }

        /* ------------------------------------------------------------- live refresh */

        /** One built panel content plus the arguments it was built from. */
        private class Built(
                container: ViewGroup,
                val context: Context,
                val theme: String,
                val mode: Int,
                val boot: JSONObject,
                val delegate: Any?
        ) {
            val container: java.lang.ref.WeakReference<ViewGroup> = java.lang.ref.WeakReference(container)
        }

        private fun remember(chain: XposedInterface.Chain, container: ViewGroup) {
            val boot = chain.getArg(3)
            built = Built(container, chain.getArg(0) as Context, chain.getArg(1).toString(),
                    chain.getArg(2) as Int,
                    if (boot is JSONObject) boot else JSONObject(),
                    chain.getArg(4))
            builtSignature = signature()
        }

        /**
         * The switches the panel content depends on, as one comparable string.
         *
         * <p>Rebuilding on every call would be wasteful and would fight the app's own animations,
         * so the rebuild is gated on this: it is computed once when the container is built and
         * again when a switch is written, and they only differ when something relevant changed.
         */
        private fun signature(): String {
            val out = StringBuilder(AmapToolCatalog.ENTRIES.size + 3)
            out.append(if (Config.visible(Config.K_HOME_CHIPS)) '1' else '0')
            out.append(if (Config.visible(Config.K_TOOLS_VISIBLE)) '1' else '0')
            out.append(if (Config.visible(Config.K_TOOLS_ALLOWLIST)) '1' else '0')
            for (entry in AmapToolCatalog.ENTRIES)
                out.append(if (Config.visible(AmapToolCatalog.key(entry[0]))) '1' else '0')
            return out.toString()
        }

        /**
         * Re-apply the switches to the home panel that is on screen right now.
         *
         * <h3>Why this exists</h3>
         * The panel is built once, by {@code ToolboxPanelV3.init}, and then cached in
         * {@code BootUIPreloader}; returning to the home page reuses that same instance. A switch
         * change therefore used to change nothing at all until the home page happened to be
         * rebuilt by something else -- which is exactly what the user hit as
         * 「设置家无效，打开内容全部开关后才显示」: turning 天气 / 扫街榜 / 攻略 / 出行节 on
         * made {@link #compact()} false, that rebuilt the whole canvas, and only then did the
         * 设置家 row appear.
         *
         * <p>The panel and the hooks share one process in the integrated build, so the panel can
         * hand the change straight back here instead. Everything is best-effort: if any handle is
         * missing the method returns without touching the view tree, and the change still applies
         * the next time the home page is built.
         */
        @JvmStatic
        fun refresh() {
            val current = built ?: return
            val panel = if (panelRef == null) null else panelRef!!.get()
            val container = current.container.get()
            if (panel !is ViewGroup || container == null) return
            val panelClass = panel.javaClass.name
            // 17.02 的活面板是 V2：不重建整块内容（会把 AI 卡也重建），只原位增删 chips 行。
            // 这样开关 ON/OFF 立刻生效，且不碰 buildAICotView / buildAILoadingView 的既有路径。
            if (panelClass.endsWith("ToolboxPanelV2")) {
                if (container.parent !== panel) return
                // 工具宫格走 decorateHomeRow 原地重建（原生容器，只切可见性）：
                // ON/OFF 都立即生效，且工具宫格用同一份 catalogue 重建，不会丢格子。
                try {
                    decorateHomeRow(container, panel.javaClass.classLoader, current.context)
                    // chips 行挂在面板根上（与宿主 init 的 this.addView 一致），单独增删。
                    ensureV2ChipsRow(panel, panel.javaClass.classLoader,
                            current.theme, current.mode, "v2_refresh")
                    H.log("event=home_chips_refresh via=v2_container show=" +
                            Config.visible(Config.K_HOME_CHIPS))
                } catch (error: Throwable) {
                    H.log("event=home_chips_refresh_failed err=" + error.javaClass.simpleName)
                }
                builtSignature = signature()
                return
            }
            // Only V3 builds its content the way build() reproduces it.
            if (!panelClass.endsWith("ToolboxPanelV3")) return
            if (container !== panel.getChildAt(0)) return
            if (!container.isAttachedToWindow) return
            if (signature() == builtSignature) return
            try {
                val fresh = build(current) ?: return
                val parent = panel
                parent.removeAllViews()
                parent.addView(fresh)
                built = Built(fresh, current.context, current.theme, current.mode,
                        current.boot, current.delegate)
                H.log("amap_native_home refresh ok signature_changed=true")
            } catch (error: Throwable) {
                H.log("amap_native_home refresh_failed=" + error.javaClass.simpleName)
            }
        }

        /**
         * Rebuild the panel content exactly the way {@code ToolboxPanelV3.init} does: the tool grid
         * from {@code b.a}, then the 「设置家」 row, then the skeleton placeholder.
         */
        private fun build(source: Built): ViewGroup? {
            val factory = factoryMethod ?: return null
            val created = factory.invoke(null, source.context, source.theme, source.mode,
                    source.boot, source.delegate)
            if (created !is ViewGroup) return null
            val container = created

            // The row is produced by the app's own builder; going through the hooked method keeps
            // the switch and the uid alignment in one place.
            val chips = chipsMethod
            if (chips != null && Config.visible(Config.K_HOME_CHIPS) && chipsPayload != null) {
                try {
                    val row = chips.invoke(null, source.context, source.theme, source.mode, chipsPayload)
                    if (row is View) container.addView(row)
                } catch (error: Throwable) {
                    H.log("amap_native_home chips_rebuild_failed=" + error.javaClass.simpleName)
                }
            }

            val skeleton = skeletonMethod
            if (skeleton != null) {
                try {
                    val view = skeleton.invoke(null, source.context, source.theme, source.mode)
                    if (view is View) container.addView(view)
                } catch (ignored: Throwable) {
                }
            }
            return container
        }

        /** Entry point for the floating panel: it writes a switch, then tells the hook side. */
        @JvmStatic
        fun onConfigChanged(key: String?) {
            if (key == null) return
            if (Config.K_HOME_CHIPS == key || Config.K_TOOLS_VISIBLE == key ||
                    Config.K_TOOLS_ALLOWLIST == key || key.startsWith(Config.K_TOOL_PREFIX)) {
                refresh()
            }
        }

        private fun dp(c: Context, value: Int): Int {
            return Math.round(c.resources.displayMetrics.density * value)
        }
    }
}

