package io.github.ldxm666.mapadkiller

import android.content.Context
import android.content.ContextWrapper
import android.util.Log

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * SdkAutoBlock — 广告 SDK 自动检索 + 拦截（通用层）。
 *
 * 思路来自 AdClose 的 AutoHookAds / SDKAdsKit（见 AdCloseHook.java），但**不依赖 DexKit**：
 * AdClose 用 native libdexkit.so 扫 dex 找"广告 SDK 包名前缀下的初始化入口"，
 * 这里改成直接解析目标 App **自己 APK 里的 dex 字符串表**（纯 Java，无 native 依赖）——
 * 只读 string_ids 指向的短字符串，不扫全文件，所以很快，而且可以在后台线程做。
 *
 * 三层落地，逐层兜底：
 *  1) [blockKnownSdks]  : 已知 SDK 的加载/展示入口直接空调用（loadAd/show/init…）
 *  2) [discover]       : 扫 dex 字符串表，找出所有命中广告 SDK 包名前缀的类
 *  3) [hookClass]      : 对发现的类挂两类 hook
 *       · 方法名 ∈ [AD_METHODS]                → 直接返回 null（广告加载/展示空转）
 *       · 方法名像 init* / start* / getContext* 且返回非 void → 入参里的真 Context 换成 FakeContext
 *
 * 安全边界：
 *  · 只 hook 命中前缀的 SDK 类，不碰 App 自身类；包名白名单外一律跳过；
 *  · 排除表里的类（如百度 BDAdConfig$Builder）不动，避免误伤 SDK 正常配置；
 *  · 全程 try/catch，任何一步失败只记日志，绝不影响 App 启动。
 */
object SdkAutoBlock {

    /** 广告 / 归因 SDK 包名前缀（取自 AdClose 的自动检测库，国内为主 + 常见海外） */
    private val SDK_PREFIXES = arrayOf(
            // ---- 国内 ----
            "com.qq.e",                        // 广点通 GDT
            "com.bytedance.sdk.openadsdk",     // 穿山甲 CSJ
            "com.bytedance.pangle",            // 穿山甲融合 Pangle
            "com.bytedance.android.openliveplugin",
            "com.ss.android.ad",               // 字节广告
            "com.ss.android.downloadlib",      // 字节广告下载通道
            "com.kwad",                        // 快手
            "com.baidu.mobads",                // 百度百青藤
            "com.sigmob",                      // Sigmob
            "com.czhj",                        // 多盟/汇量
            "com.inmobi",                      // InMobi
            "com.tradplus.ads",                // TradPlus
            "com.jd.ad.sdk",                   // 京东广告
            "com.beizi.fusion",                // 倍孜
            "com.meishu.sdk",                  // 美数
            "com.link.sdk",
            "com.xwuad.sdk",
            "com.qumeng",                      // 趣盟
            "com.huawei.hms.ads",              // 华为广告
            "com.huawei.openalliance.ad",
            "com.mbridge.msdk",                // Mintegral / MB
            "com.windmill.sdk",                // Windmill 聚合
            "com.alimm.tanx",                  // 阿里 Tanx
            "com.anythink",                    // TopOn
            "com.miui.zeus.mimo.sdk",          // 小米 MiMo
            "com.tencent.klevin.ads",          // 腾讯 Klevin
            "com.tencent.qqmini.ad",
            "com.baichuan",                    // 360 百川
            "com.sjm.sjmsdk", "com.ap.android", "com.youxiao.ssp", "com.cat.sdk", "com.superad.ad",
            "com.octopus",                     // 章鱼
            "com.gromore", "com.bytedance.msdk",   // Gromore 聚合
            "com.pangle",
            "cn.admobiletop",
            // ---- 海外 ----
            "com.applovin",
            "com.facebook.ads",
            "com.fyber.inneractive.sdk",
            "com.google.android.gms.ads",
            "com.google.android.gms.admob",
            "com.google.ads",
            "com.google.unity.ads",
            "com.google.android.ads",
            "com.unity3d.ads",
            "com.unity3d.services",
            "com.vungle.warren",
            "com.miniclip.ads",
            "com.smaato.sdk",
            "com.tp.adx"
    )

    /**
     * 广告特征词：**这才是能发现"新 SDK"的关键**。
     *
     * 只按 [SDK_PREFIXES] 扫，永远只能找到已经写进表里的厂商 ——
     * 表外的广告 SDK 一个都发现不了。所以这里改为：凡是类名里带这些广告语义词的，
     * 都当作可疑广告类，再从类名反推厂商根包名，收进"已学到的 SDK"。
     */
    private val AD_TOKENS = arrayOf(
            "adsdk", "adloader", "iadloader", "admanager", "adrequest", "adnet", "adkit",
            "adcore", "advlib", "advert", "adsplash", "splashad", "bannerad", "nativead",
            "feedad", "rewardad", "interstitialad", "fullscreenad", "adview", "adcontainer",
            "ads.", ".ads", "unionad", "adslot", "adlistener", "adcallback"
    )

    /** 反推厂商根包名时，不是厂商自己的二级/三级名（避免把内部包当成厂商） */
    private val GENERIC_SEG: Set<String> = HashSet(listOf(
            "sdk", "ads", "ad", "core", "api", "common", "utils", "util", "internal", "impl",
            "loader", "view", "views", "open", "base", "lib", "libs", "plugin", "widget",
            "net", "http", "platform", "service", "manager", "adx", "ssp", "dsp", "bid",
            "bidding", "mediation", "adapter", "adapters", "report", "stat", "stats"))

    /** 学到的 SDK 前缀存在 LSPosed RemotePreferences 里（模块 App 与 Hook 进程共享） */
    private const val K_LEARNED = "sdk_learned"
    private const val MAX_LEARNED = 300

    /** 已学到的厂商根包名（跨进程持久化，下次启动直接拦，无需重新学） */
    private val learned: MutableSet<String> =
            Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile
    private var learnedLoaded = false

    /**
     * 是否运行在 **hook 侧**。
     *
     * 这一条是保命的：io.github.libxposed.api.* 是只编译、不打包的 stub，
     * **模块 App 进程里根本没有这个包**。而 H 持有 XposedInterface.Hooker 字段、
     * Config.prefs() 又引用 H —— 在 App 侧一旦碰它们就是
     * NoClassDefFoundError: io.github.libxposed.api.XposedInterface 直接崩
     * （实测把设置页启动即崩）。所以凡是可能在 App 侧被调到的分支，
     * 一律先用这个标记挡住，绝不触碰 H。
     */
    @Volatile
    private var hookSide = false

    /**
     * 可以安全"空转"的广告入口方法名。
     *
     * ⚠ 这里**绝不能**放 load / show / fetch / start 这类通用名。
     * 真机事故链（v1.1.0 → v1.1.0）：
     *   · 把 ADN 的 load/loadAd 吞掉 → 广告流程已启动但加载回调永不到达
     *     → 宿主一直等 onAdFinish/onSkip → **卡开屏，按返回键才进得去**；
     *   · 把 show 吞掉 → 对话框/弹窗类广告永久占位；
     *   · 把 initialize/init 吞掉 → SDK 半初始化，宿主拿到 null 直接 NPE。
     * 所以只保留"纯初始化入口"：不返回给宿主任何对象，也不驱动后续回调。
     * 真正让广告不展示靠两条更安全的线：
     *   ① 各 App 专属的**语义闸门**（见 BmapHooks/AmapHooks/TmapHooks）；
     *   ② [GATES] 里的 isInitialized/canLoad 这类**只读查询**。
     */
    private val AD_METHODS: Set<String> = HashSet(listOf(
            "initSDK", "initializeSdk", "startWithAppId", "startWithAppID"))

    /**
     * 只读"能力查询"闸门 —— 返回 false 不会破坏任何回调链，纯粹让宿主认为
     * "这个 SDK 不可用"。这是整套自动拦截里**最安全、性价比最高**的一类 hook。
     */
    private val GATES: Set<String> = HashSet(listOf(
            "isInitialized", "isInit", "isSdkReady", "isAdReady", "canLoadAd", "canShowAd",
            "isReady", "isLoadSuccess", "hasInit"))

    /** 不做处理的类（避免误伤 SDK 正常配置类） */
    private val EXCLUDED: Set<String> = HashSet(Collections.singletonList(
            "com.baidu.mobads.sdk.api.BDAdConfig\$Builder"))

    /** 判定范围：只在这些包名下自动检索，避免无谓开销 */
    private val TARGET_PKGS: Set<String> = HashSet(listOf(
            MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP))

    private val hookedMethods: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())
    @Volatile
    private var started = false
    private val discovered: MutableSet<String> =
            Collections.synchronizedSet(LinkedHashSet<String>())

    @JvmStatic
    fun install(cl: ClassLoader, pkg: String?) {
        if (!TARGET_PKGS.contains(pkg)) return
        hookSide = true
        // 0) 先把"上一轮学到的 SDK"读回来 —— 这次无需重新学习就能直接拦
        loadLearned()
        // 1) 已知 SDK 静态入口：立刻生效，不等扫描
        try { blockKnownSdks(cl) } catch (t: Throwable) { H.log(Log.WARN, MainHook.TAG, "SDK static " + t) }

        // 2) 自动检索：后台线程，绝不阻塞启动
        if (started) return
        started = true
        val th = Thread(object : Runnable {
            override fun run() {
                try { discover(cl, pkg) }
                catch (t: Throwable) { H.log(Log.WARN, MainHook.TAG, "SDK discover " + t) }
            }
        }, "MapAdKiller-sdk")
        th.isDaemon = true
        th.start()
    }

    // ══════════════════════════════════════════════ 1) 已知 SDK 静态入口

    /** 几个几乎必现的 SDK 单例入口；名字不对就跳过（getDeclaredMethod 抛异常被吞） */
    private fun blockKnownSdks(cl: ClassLoader) {
        val known: Array<Array<String>> = arrayOf(
                arrayOf("com.qq.e.comm.managers.GDTADManager", "initWith", "initPlugin", "preRequestDNS"),
                arrayOf("com.bytedance.sdk.openadsdk.TTAdSdk", "init", "start"),
                arrayOf("com.bytedance.sdk.openadsdk.TTAdNative", "loadFeedAd", "loadBannerAd", "loadSplashAd", "loadRewardVideoAd"),
                arrayOf("com.kwad.sdk.KsAdSDKImpl", "init", "start"),
                arrayOf("com.baidu.mobads.sdk.api.AdSettings", "setChannelId"),
                arrayOf("com.sigmob.windad.WindAds", "start"),
                arrayOf("com.mbridge.msdk.out.MBridgeSDK", "init", "initSDK"),
                arrayOf("com.mbridge.msdk.out.MBBidManager", "bidLoad", "load"),
                arrayOf("com.anythink.core.api.ATSDK", "init", "initSDK"),
                arrayOf("com.tradplus.ads.open.TradPlusSdk", "init", "initSdk"),
                // 腾讯 11.6 的优量汇开屏插件（TG = Tangram）
                arrayOf("com.qq.e.tg.splash.TGSplashAD", "loadFullScreenAD", "showFullScreenAD", "fetchFullScreenAD"),
                // 百度 22.0 的聚合开屏入口（只挂初始化，不碰加载回调）
                arrayOf("com.baidu.baidumaps.commonadprovider.SplashAdProvider", "R", "t"),
                arrayOf("com.qumeng.advlib.api.QMAdManager", "init", "initSdk"),
                arrayOf("com.octopus.ad.OctopusAdSdk", "init", "initSdk"),
                arrayOf("com.meishu.sdk.MSAdSdk", "init", "initSdk"),
                arrayOf("com.beizi.fusion.BeiZiSDK", "init", "initSdk"),
                arrayOf("com.windmill.sdk.WindMillAd", "init", "initSdk"),
                arrayOf("com.anythink.core.api.ATSDK", "initSDK", "init"),
                arrayOf("com.smartdigimkt.sdk.api.SDMAdSdk", "init", "initSdk")
        )
        for (row in known) {
            val c = H.cls(cl, row[0]) ?: continue
            for (i in 1 until row.size) hookAdMethod(c, row[i])
        }
    }

    // ══════════════════════════════════════════════ 2) 自动检索

    private fun discover(cl: ClassLoader, pkg: String?) {
        val apks = apkPaths(pkg)
        if (apks.isEmpty()) {
            H.log(Log.WARN, MainHook.TAG, "SDK discover: no apk path for " + pkg)
            return
        }
        loadLearned()

        val t0 = System.currentTimeMillis()
        val names = LinkedHashSet<String>()   // 命中内置前缀的类
        val suspected = LinkedHashSet<String>() // 只命中"广告特征词"的可疑类
        val budgetBytes = 96L * 1024 * 1024   // 最多解析这么多 dex 字节
        var used = 0L
        for (apk in apks) {
            used += scanApk(apk, names, suspected, budgetBytes - used)
            if (used >= budgetBytes) break
        }
        val ms = System.currentTimeMillis() - t0

        // ---- 学习：从"可疑类"反推厂商根包名，把没见过的收进来 ----
        // 学习只能发生在 hook 侧：只有目标 App 自己的进程读得到它自己的 APK。
        // 但 hook 侧的 RemotePreferences 是只读实现，所以学到的**通过广播上报给设置 App**，
        // 由 App 侧（有写权限）落盘（见 LearnedReceiver）。
        var fresh = 0
        val newRoots = LinkedHashSet<String>()
        for (cls in suspected) {
            val root = vendorRoot(cls) ?: continue
            if (isBuiltin(root) || learned.contains(root)) continue
            if (isHostOrFramework(root, pkg)) continue
            var full = false
            synchronized(learned) {
                if (learned.size >= MAX_LEARNED) full = true else learned.add(root)
            }
            if (full) break
            newRoots.add(root)
            fresh++
        }
        if (fresh > 0) {
            for (r in newRoots) H.log(Log.INFO, MainHook.TAG, "SDK LEARNED new vendor: " + r)
            reportLearned(newRoots)
        }

        // ---- 用 内置前缀 ∪ 已学到前缀 重新筛一遍要挂的类 ----
        for (cls in suspected) {
            if (matchesSdk(cls)) names.add(cls)
        }

        var ok = 0
        for (n in names) {
            val c = H.cls(cl, n) ?: continue
            if (hookClass(c)) ok++
        }
        H.log(Log.INFO, MainHook.TAG, "SDK discover pkg=" + pkg + " classes=" + names.size
                + " hooked=" + ok + " newVendor=" + fresh + " learnedTotal=" + learned.size
                + " dex=" + (used / 1048576) + "MB in " + ms + "ms")
        if (Config.debugLog()) {
            var i = 0
            for (n in names) {
                if (i++ >= 60) break
                H.log(Log.INFO, MainHook.TAG, "SDK class " + n)
            }
        }
    }

    /** 目标 App 的 APK / split APK 路径 */
    private fun apkPaths(pkg: String?): List<String> {
        val out = ArrayList<String>()
        try {
            val at = Class.forName("android.app.ActivityThread")
            var app: Any? = null
            for (i in 0 until 20) {
                if (app != null) break
                try { app = at.getMethod("currentApplication").invoke(null) } catch (ignored: Throwable) {}
                if (app == null) Thread.sleep(250)
            }
            val ctx = app as? Context ?: return out
            val ai = ctx.packageManager.getApplicationInfo(pkg!!, 0)
            if (ai.sourceDir != null) out.add(ai.sourceDir)
            val splits = ai.splitSourceDirs
            if (splits != null) out.addAll(splits)
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "SDK apkPaths " + t)
        }
        return out
    }

    /** 扫一个 APK 里的 classes*.dex，收集命中的类名；返回消耗的字节数 */
    private fun scanApk(path: String, names: MutableSet<String>, suspected: MutableSet<String>, budget: Long): Long {
        var used = 0L
        var zis: ZipInputStream? = null
        try {
            val zin = ZipInputStream(FileInputStream(File(path)))
            zis = zin
            var e: ZipEntry? = zin.nextEntry
            val buf = ByteArray(64 * 1024)
            while (e != null) {
                val n = e.name
                if (n == null || !n.endsWith(".dex") || !n.startsWith("classes")) { e = zin.nextEntry; continue }
                if (budget - used <= 0) break
                val bos = ByteArrayOutputStream(1 shl 20)
                var r = zin.read(buf)
                var got = 0L
                while (r > 0) {
                    bos.write(buf, 0, r)
                    got += r
                    if (got > 96L * 1024 * 1024) break   // 单个 dex 上限
                    r = zin.read(buf)
                }
                val dex = bos.toByteArray()
                used += dex.size
                parseDexStrings(dex, names, suspected)
                e = zin.nextEntry
            }
        } catch (t: Throwable) {
            // 某些 split 读不到就直接跳过
        } finally {
            try { zis?.close() } catch (ignored: Throwable) {}
        }
        return used
    }

    /**
     * 解析 dex 字符串表：一个 dex 只走一遍 string_ids。
     *
     * 同时收集两拨：
     *  · out        —— 命中内置/已学到前缀的类（直接挂）
     *  · suspected  —— 只命中"广告特征词"的可疑类（用来反推新厂商，见 AD_TOKENS）
     *
     * dex header: string_ids_size @0x38, string_ids_off @0x3C（小端 u4）
     * string_data_item: ULEB128(utf16_size) + MUTF-8 字节 + 0x00
     */
    private fun parseDexStrings(d: ByteArray?, out: MutableSet<String>, suspected: MutableSet<String>) {
        if (d == null || d.size < 0x70) return
        if (d[0] != 'd'.code.toByte() || d[1] != 'e'.code.toByte() || d[2] != 'x'.code.toByte()) return
        val n = le32(d, 0x38)
        val off = le32(d, 0x3C)
        if (n <= 0 || off <= 0 || off + 4L * n > d.size) return

        val sb = StringBuilder(128)
        for (i in 0 until n) {
            val so = le32(d, off + 4 * i)
            if (so <= 0 || so >= d.size) continue
            var p = so
            // ULEB128 长度
            var shift = 0
            while (p < d.size && (d[p].toInt() and 0x80) != 0 && shift < 28) { p++; shift += 7 }
            if (p < d.size) p++
            if (p >= d.size) continue
            sb.setLength(0)
            var q = p
            while (q < d.size && d[q].toInt() != 0 && sb.length < 200) {
                val ch = (d[q].toInt() and 0xFF).toChar()
                sb.append(ch)
                q++
            }
            if (sb.length < 10 || sb.length >= 200) continue
            if (sb[0] != 'L' || sb[sb.length - 1] != ';') continue
            var cls = sb.substring(1, sb.length - 1).replace('/', '.')
            if (cls.indexOf('\$') >= 0) cls = cls.substring(0, cls.indexOf('\$'))
            if (isExcluded(cls)) continue

            if (matchesSdk(cls)) {
                if (out.add(cls)) discovered.add(cls)
                continue
            }
            if (looksLikeAd(cls)) suspected.add(cls)
        }
    }

    /**
     * 排除表匹配。
     *
     * <p>⚠ 这里必须带 `entry.startsWith(cls + "$")` 这一半：dex 字符串里的嵌套类名
     * 在此之前已经被截到外层类（`BDAdConfig$Builder` → `BDAdConfig`），所以单靠
     * `EXCLUDED.contains(cls)` 永远匹配不到带 `$` 的条目 —— 排除表会整条失效。
     */
    private fun isExcluded(cls: String): Boolean {
        if (EXCLUDED.contains(cls)) return true
        for (e in EXCLUDED) {
            if (e.startsWith(cls + "\$")) return true
        }
        return false
    }

    /** 是否命中内置前缀或"已学到"的前缀 */
    private fun matchesSdk(cls: String): Boolean {
        if (isBuiltin(cls)) return true
        return matchesLearned(cls)
    }

    private fun isBuiltin(cls: String): Boolean {
        for (p in SDK_PREFIXES) {
            if (cls == p || cls.startsWith(p + ".")) return true
        }
        return false
    }

    private fun matchesLearned(cls: String): Boolean {
        // learned 是 synchronizedSet：迭代必须自己拿锁，否则 clearLearned()（UI 线程）
        // 撞上后台扫描线程会抛 ConcurrentModificationException，整轮 discover 作废。
        synchronized(learned) {
            if (learned.isEmpty()) return false
            for (p in learned) {
                if (cls == p || cls.startsWith(p + ".")) return true
            }
        }
        return false
    }

    /** 类名里带广告特征词 → 可疑广告类 */
    private fun looksLikeAd(cls: String): Boolean {
        val low = cls.lowercase()
        if (low.startsWith("android.") || low.startsWith("java.") || low.startsWith("androidx.")
                || low.startsWith("kotlin") || low.startsWith("com.android.")) {
            return false
        }
        // 关键约束：广告特征词必须出现在**类名的前 4 段命名空间**里。
        // 只按整串做子串匹配会把一堆无关类吸进来 —— 实测高德把
        // com.alipay.android.phone.mrpc.core.AdRequest 里的 "ad" 当成了广告信号，
        // 于是学出 com.alipay / com.google / com.huawei / com.taobao 这些**基础设施**包，
        // 真拦下去会连登录支付一起打死。
        val ns = namespaceOf(low, 4)
        for (t in AD_TOKENS) {
            if (ns.contains(t)) return true
        }
        return false
    }

    /** 取类名前 n 段（com.a.b.C → "com.a.b."） */
    private fun namespaceOf(low: String, n: Int): String {
        var idx = -1
        for (i in 0 until n) {
            idx = low.indexOf('.', idx + 1)
            if (idx < 0) return low
        }
        return low.substring(0, idx + 1)
    }

    /**
     * 从类名反推厂商根包名，例如
     *   com.qumeng.advlib.core.AdSdk      → com.qumeng.advlib
     *   com.foo.adsdk.internal.AdLoader   → com.foo.adsdk
     *   cn.abc.adx.view.BannerAdView      → cn.abc.adx
     * 规则：跳过 TLD 后，取到第一个"通用段"（sdk/core/api/ads…）为止，最多 3 段。
     */
    private fun vendorRoot(cls: String): String? {
        val seg = cls.split(".")
        if (seg.size < 2) return null
        val take: Int
        if (seg[0].length <= 3) {          // com / org / net / cn / io / me …
            take = Math.min(seg.size, 3)
            // （原逻辑：命中"通用段"时保持 3，否则退到 2）
            if (!(seg.size >= 3 && GENERIC_SEG.contains(seg[2].lowercase())) && seg.size >= 2) {
                return buildRoot(seg, 2)
            }
            return buildRoot(seg, 3)
        } else {
            return buildRoot(seg, 2)
        }
    }

    private fun buildRoot(seg: List<String>, takeIn: Int): String? {
        val take = if (takeIn > seg.size) seg.size else takeIn
        if (take < 2) return null
        val sb = StringBuilder()
        var i = 0
        while (i < take && i < seg.size) {
            if (i > 0) sb.append('.')
            sb.append(seg[i])
            i++
        }
        val root = sb.toString()
        if (root.length < 6 || root.length > 60) return null
        if (GENERIC_SEG.contains(seg[seg.size - 1].lowercase()) && take == seg.size) return null
        return root
    }

    /** 别把宿主 App 自己的包、或明显不是广告厂商的根当成 SDK 学下来 */
    private fun isHostOrFramework(root: String, pkg: String?): Boolean {
        if (pkg != null && (root.startsWith(pkg) || pkg.startsWith(root))) return true
        val r = root.lowercase()
        if (r.startsWith("android.") || r.startsWith("androidx.") || r.startsWith("java.")
                || r.startsWith("kotlin") || r.startsWith("com.android.")
                || r.startsWith("com.google.android.gms") || r.startsWith("com.google.firebase")) {
            return true
        }
        // 厂商自有基础库 / 太泛的社交云服务根：学了会误伤（实测噪声）
        for (bad in BAD_ROOTS) {
            if (r == bad || r.startsWith(bad + ".")) return true
        }
        // 宿主厂商自己的根（com.baidu.* / com.tencent.* / com.autonavi.*）——
        // 除非这个根本身带广告特征词（com.tencent.ad 这种就保留）
        if (!looksLikeAd(root)) {
            for (hv in HOST_VENDOR_ROOTS) {
                if (r.startsWith(hv + ".")) return true
            }
        }
        return false
    }

    /** 明确不是"广告 SDK"的根（实测被误学进来的：厂商基础设施 / 云 / 支付 / 社交） */
    private val BAD_ROOTS = arrayOf(
            "com.baidu.platform", "com.baidu.sdk", "com.baidubce", "com.baidu.lbsyun",
            "com.tencent.net", "com.tencent.mm", "com.facebook",
            "com.google.android", "com.google.firebase", "org.apache", "okhttp3",
            "com.squareup", "com.alibaba.fastjson", "com.bytedance.sdk",
            // 高德 16.25.1 实测被误学进来的一批（都不是广告 SDK）
            "com.alipay", "com.alibaba", "com.taobao", "com.ali", "com.antdigital",
            "com.uc", "com.google", "com.huawei", "com.mobile", "com.amap",
            "com.nirvana", "com.robertjx", "com.dtf", "anetwork", "org.altbeacon",
            "com.autonavi", "com.iflytek", "com.tencent.mapsdk"
    )

    /** 宿主厂商根：非广告语义的一律不学 */
    private val HOST_VENDOR_ROOTS = arrayOf(
            "com.baidu", "com.tencent", "com.autonavi", "com.amap", "com.google",
            "com.alibaba", "com.taobao", "com.alipay", "com.huawei", "com.miui",
            "com.xiaomi", "com.oppo", "com.vivo", "com.samsung"
    )

    // ══════════════════════════════════════════════ 学习结果的持久化

    /** 从 RemotePreferences 载入"已学到的 SDK"（模块 App 与 Hook 进程共享同一份） */
    private fun loadLearned() {
        if (!hookSide) return          // App 侧绝不能走这里（会碰到 H）
        if (learnedLoaded) return
        learnedLoaded = true
        try {
            val s = Config.prefs()!!.getStringSet(K_LEARNED, null)
            if (s != null) {
                synchronized(learned) { learned.addAll(s) }
                H.log(Log.INFO, MainHook.TAG, "SDK learned loaded: " + learned.size)
            }
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "SDK learned load fail " + t)
        }
    }

    /** 存回去；写失败只影响"下次免学习"，不影响本次拦截 */
    private fun saveLearned() {
        if (!hookSide) return          // App 侧不能写 remote prefs
        try {
            val copy: Set<String>
            synchronized(learned) { copy = LinkedHashSet(learned) }
            Config.prefs()!!.edit().putStringSet(K_LEARNED, copy).commit()
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "SDK learned save fail " + t)
        }
    }

    /** 供设置页展示：已捕获的 SDK 清单 */
    @JvmStatic
    fun learnedList(): List<String> {
        loadLearned()
        synchronized(learned) {
            val l = ArrayList(learned)
            Collections.sort(l)
            return l
        }
    }

    @JvmStatic
    fun learnedCount(): Int = learnedList().size

    /**
     * 设置页专用读取：App 进程里 `H.module` 是 null，走 hook 侧的 Config.prefs() 读不到，
     * 必须经 XposedService 读同一份 RemotePreferences。
     */
    @JvmStatic
    fun learnedListForApp(): List<String> {
        try {
            val s = App.svc()
            if (s != null) {
                val set = s.getRemotePreferences(Config.PREF_GROUP)
                        .getStringSet(K_LEARNED, null)
                if (set != null) {
                    val l = ArrayList(set)
                    Collections.sort(l)
                    return l
                }
            }
        } catch (ignored: Throwable) {}
        // 注意：这里**不能**回退到 learnedList() —— 那会走 loadLearned() → Config.prefs() → H，
        // App 进程没有 libxposed api 包，会 NoClassDefFoundError 崩溃。
        val l = ArrayList<String>()
        synchronized(learned) { l.addAll(learned) }
        Collections.sort(l)
        return l
    }

    // ══════════════════════════════════════════════ 模块 App 侧：扫描 + 学习

    /**
     * 在**模块 App 进程**里扫描三家地图的 APK，找出广告 SDK 厂商根包名。
     *
     * 为什么放在 App 侧：
     *  1) 目标 APK 在 /data/app 下是全局可读的，App 侧用 PackageManager 拿 sourceDir 就能读，
     *     不需要 root，也不需要注入目标进程；
     *  2) hook 侧拿到的 RemotePreferences 是**只读实现**（实测 UnsupportedOperationException），
     *     学到的结果只能由 App 侧（经 XposedService）写进去；
     *  3) 这样就不用把 10 秒的 dex 扫描压在目标 App 的启动上。
     *
     * @return 新学到的厂商根包名（已合并进返回值全集）
     */
    @JvmStatic
    fun learnFromApks(ctx: Context?): List<String> {
        val found = LinkedHashSet<String>()
        if (ctx == null) return ArrayList()
        for (pkg in TARGET_PKGS) {
            val apks = ArrayList<String>()
            try {
                val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
                if (ai.sourceDir != null) apks.add(ai.sourceDir)
                val splits = ai.splitSourceDirs
                if (splits != null) apks.addAll(splits)
            } catch (ignored: Throwable) {
                continue   // 没装这个地图就跳过
            }
            val hits = LinkedHashSet<String>()
            val suspected = LinkedHashSet<String>()
            val budget = 96L * 1024 * 1024
            var used = 0L
            for (a in apks) {
                used += scanApk(a, hits, suspected, budget - used)
                if (used >= budget) break
            }
            for (cls in suspected) {
                val root = vendorRoot(cls) ?: continue
                if (isBuiltin(root)) continue
                if (isHostOrFramework(root, pkg)) continue
                found.add(root)
            }
        }
        synchronized(learned) {
            learned.addAll(found)
            if (learned.size > MAX_LEARNED) {
                val all = ArrayList(learned)
                learned.clear()
                learned.addAll(all.subList(0, MAX_LEARNED))
            }
            found.addAll(learned)
        }
        learnedLoaded = true
        return ArrayList(found)
    }

    /** App 侧：把学习结果交给 RemotePreferences（由 MainActivity 经 XposedService 写入） */
    @JvmStatic
    fun learnedSet(): Set<String> {
        synchronized(learned) { return LinkedHashSet(learned) }
    }

    /** hook 侧：从已经由 App 侧写好的 remote prefs 里读（只读，够用） */
    @JvmStatic
    fun reloadLearned() {
        learnedLoaded = false
        learned.clear()
        loadLearned()
    }

    /** 上报通道：hook 进程 → 设置 App（App 侧有 prefs 写权限，负责落盘） */
    const val ACTION_LEARNED = "io.github.ldxm666.mapadkiller.LEARNED"
    const val EXTRA_ROOTS = "roots"

    /**
     * 优先走 ContentProvider（按需唤起 App 进程，MIUI 拦不住）；
     * 广播只作为兜底保留。
     */
    private fun reportLearned(roots: Set<String>) {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val ctx = at.getMethod("currentApplication").invoke(null) as? Context
            if (ctx == null) return

            var ok = false
            try {
                val b = android.os.Bundle()
                b.putStringArrayList(LearnedProvider.KEY_ROOTS, ArrayList(roots))
                val r = ctx.contentResolver
                        .call(LearnedProvider.URI, LearnedProvider.METHOD_PUT, null, b)
                ok = r != null && r.getInt(LearnedProvider.KEY_TOTAL, 0) > 0
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "SDK report via provider fail " + t)
            }
            if (!ok) {
                val i = android.content.Intent(ACTION_LEARNED)
                i.setPackage(MainHook.PKG_SELF)
                i.putStringArrayListExtra(EXTRA_ROOTS, ArrayList(roots))
                ctx.sendBroadcast(i)
            }
            H.log(Log.INFO, MainHook.TAG, "SDK report sent: " + roots.size
                    + (if (ok) " (provider)" else " (broadcast fallback)"))
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "SDK report fail " + t)
        }
    }

    /** 清空学习结果（设置页"重新学习"用） */
    @JvmStatic
    fun clearLearned() {
        synchronized(learned) { learned.clear() }
        learnedLoaded = hookSide      // App 侧不回读
        saveLearned()                 // hook 侧才真的写 remote
    }

    private fun le32(b: ByteArray, o: Int): Int {
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }

    // ══════════════════════════════════════════════ 3) 挂 hook

    /**
     * 对一个 SDK 类挂两类 hook；返回是否至少挂上一个。
     *
     * ⚠⚠ 绝对不能沿父类链往上走！
     * SDK 类的父类会一路升到 android.view.View / android.app.Dialog /
     * android.content.ContextWrapper 这类框架类，按名字命中就是**全局**钩子。
     * 真机实测的后果：`android.app.Dialog#show` 被拦、`Activity#startActivityIfNeeded` /
     * `ContextWrapper#startService` 被换 Context → **百度地图直接闪退、腾讯地图 ANR**。
     * 这与 v1.1.0 白屏事故同源，是本模块第一红线。
     *
     * 所以：只处理 **声明在该 SDK 类自己身上** 的方法，并在下单前再做一次类名校验。
     */
    private fun hookClass(c: Class<*>?): Boolean {
        if (c == null || !isSdkClass(c.name)) return false
        var any = false
        val ms: Array<Method>
        try { ms = c.declaredMethods } catch (t: Throwable) { return false }
        for (m in ms) {
            val mod = m.modifiers
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue
            val nm = m.name
            if (nm == "<init>" || nm == "<clinit>") continue
            if (!isSdkClass(m.declaringClass.name)) continue   // 双保险

            if (AD_METHODS.contains(nm)) { if (hookAdMethod(m)) any = true; continue }

            // 只读查询闸门：isInitialized() 之类一律 false（不碰回调链）
            if (GATES.contains(nm) && m.parameterTypes.isEmpty()) {
                if (hookAdMethod(m)) any = true
                continue
            }

            // Context 替换只对"明确的 SDK 初始化入口"下手，且必须有 Context 入参。
            // 通用名字（start*/getContext*）一律不做 —— 实测这就是腾讯地图 ANR 的来源。
            if (isLifecycleEntry(nm) && m.returnType !== java.lang.Void.TYPE
                    && hasContextParam(m)) {
                if (hookContextSwap(m)) any = true
            }
        }
        return any
    }

    /** 是不是"广告 SDK 自己的类"——框架/系统类一律 false，这是防全局误伤的闸门 */
    private fun isSdkClass(name: String?): Boolean {
        if (name == null || name.isEmpty()) return false
        if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("dalvik.") || name.startsWith("com.android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin")
                || name.startsWith("org.jetbrains") || name.startsWith("sun.")) {
            return false
        }
        return matchesSdk(name)
    }

    /** loadAd/show/init… → 直接返回默认值，原方法不执行（广告加载/展示空转） */
    private fun hookAdMethod(c: Class<*>, name: String): Boolean {
        var any = false
        for (m in c.declaredMethods) {
            if (m.name == name && hookAdMethod(m)) any = true
        }
        return any
    }

    private fun hookAdMethod(m: Method): Boolean {
        val id = m.declaringClass.name + "#" + m.name
        if (!hookedMethods.add(id)) return false
        try {
            m.isAccessible = true
            H.module!!.hook(m).setId("sdk_ad_" + Integer.toHexString(id.hashCode()))
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            // 不 proceed = 原方法不执行；返回类型默认值
                            return def(chain.executable)
                        }
                    })
            if (Config.debugLog()) H.log(Log.INFO, MainHook.TAG, "SDK BLOCK " + id)
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    /** init* / start* / getContext* 且返回非 void → 把入参里的真 Context 换成 FakeContext */
    private fun hookContextSwap(m: Method): Boolean {
        val id = m.declaringClass.name + "#" + m.name
        if (!hookedMethods.add(id)) return false
        try {
            m.isAccessible = true
            H.module!!.hook(m).setId("sdk_ctx_" + Integer.toHexString(id.hashCode()))
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            val list = chain.args
                            val args = arrayOfNulls<Any?>(list.size)
                            for (k in args.indices) args[k] = list[k]
                            var changed = false
                            for (i in args.indices) {
                                if (args[i] is Context) {
                                    val real = args[i] as Context
                                    if (real !is FakeContext) {
                                        args[i] = FakeContext(real)
                                        changed = true
                                    }
                                }
                            }
                            if (changed) return chain.proceed(args)
                            // 没有 Context 入参就什么都别做 —— 凭空替换返回值风险太大
                            return chain.proceed()
                        }
                    })
            if (Config.debugLog()) H.log(Log.INFO, MainHook.TAG, "SDK CTX  " + id)
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * 只认**明确的**初始化入口名。
     *
     * 旧实现用 name.contains("start") + getContext*，命中面太宽：
     * "startActivity"、"startService"、"getContextWrapper" 全在里面，
     * 真机表现就是百度闪退 / 腾讯 ANR。现在收窄到 init* / setup* / *WithAppId。
     */
    private fun isLifecycleEntry(name: String): Boolean {
        val n = name.lowercase()
        if (n == "init" || n == "initcontext" || n == "setup") return true
        if (n.startsWith("init") && n.length <= 24) return true
        if (n.startsWith("setup") && n.length <= 24) return true
        if (n.endsWith("withappid") || n.endsWith("withappkey")) return true
        return false
    }

    /** 方法签名里是否真的有 Context 入参（没有就根本不需要换 Context） */
    private fun hasContextParam(m: java.lang.reflect.Method): Boolean {
        for (p in m.parameterTypes) {
            if (Context::class.java.isAssignableFrom(p)) return true
        }
        return false
    }

    /** 非 void 方法的默认返回；void 方法返回 null 即可 */
    private fun def(ex: java.lang.reflect.Executable): Any? {
        if (ex !is Method) return null
        val r = ex.returnType
        if (!r.isPrimitive) return null
        if (r === java.lang.Boolean.TYPE) return java.lang.Boolean.FALSE
        if (r === java.lang.Integer.TYPE) return 0
        if (r === java.lang.Long.TYPE) return 0L
        if (r === java.lang.Short.TYPE) return 0.toShort()
        if (r === java.lang.Byte.TYPE) return 0.toByte()
        if (r === java.lang.Character.TYPE) return '\u0000'
        if (r === java.lang.Float.TYPE) return 0f
        if (r === java.lang.Double.TYPE) return 0.0
        return null
    }

    /**
     * 假 Context：SDK 拿到它之后既拿不到真实环境、也读不到文件/缓存目录，
     * 初始化会自然"空转"而不会崩（AdClose FakeContext 的等价简化版）。
     */
    private class FakeContext(base: Context) : ContextWrapper(base) {

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.android.settings"
        override fun getFilesDir(): File = File("/dev/null")
        override fun getCacheDir(): File = File("/dev/null")
        override fun getExternalCacheDir(): File? = null
        override fun getExternalFilesDir(type: String?): File? = null
        override fun getDir(name: String?, mode: Int): File = File("/dev/null")
        override fun getSystemService(name: String): Any? {
            return try { super.getSystemService(name) } catch (t: Throwable) { null }
        }
        override fun getSharedPreferences(n: String?, m: Int): android.content.SharedPreferences {
            return super.getSharedPreferences("mak_null_" + n, Context.MODE_PRIVATE)
        }
        override fun getApplicationInfo(): android.content.pm.ApplicationInfo {
            val ai = android.content.pm.ApplicationInfo()
            ai.packageName = "com.android.settings"
            ai.sourceDir = "/dev/null"
            ai.dataDir = "/dev/null"
            ai.flags = 0
            return ai
        }
    }
}
