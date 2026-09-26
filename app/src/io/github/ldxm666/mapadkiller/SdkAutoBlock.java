package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * SdkAutoBlock — 广告 SDK 自动检索 + 拦截（通用层）。
 *
 * 思路来自 AdClose 的 AutoHookAds / SDKAdsKit（见 AdCloseHook.java），但**不依赖 DexKit**：
 * AdClose 用 native libdexkit.so 扫 dex 找"广告 SDK 包名前缀下的初始化入口"，
 * 这里改成直接解析目标 App **自己 APK 里的 dex 字符串表**（纯 Java，无 native 依赖）——
 * 只读 string_ids 指向的短字符串，不扫全文件，所以很快，而且可以在后台线程做。
 *
 * 三层落地，逐层兜底：
 *  1) {@link #blockKnownSdks}  : 已知 SDK 的加载/展示入口直接空调用（loadAd/show/init…）
 *  2) {@link #discover}       : 扫 dex 字符串表，找出所有命中广告 SDK 包名前缀的类
 *  3) {@link #hookClass}      : 对发现的类挂两类 hook
 *       · 方法名 ∈ {@link #AD_METHODS}                → 直接返回 null（广告加载/展示空转）
 *       · 方法名像 init* / start* / getContext* 且返回非 void → 入参里的真 Context 换成 FakeContext
 *
 * 安全边界：
 *  · 只 hook 命中前缀的 SDK 类，不碰 App 自身类；包名白名单外一律跳过；
 *  · 排除表里的类（如百度 BDAdConfig$Builder）不动，避免误伤 SDK 正常配置；
 *  · 全程 try/catch，任何一步失败只记日志，绝不影响 App 启动。
 */
public final class SdkAutoBlock {

    /** 广告 / 归因 SDK 包名前缀（取自 AdClose 的自动检测库，国内为主 + 常见海外） */
    private static final String[] SDK_PREFIXES = {
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
            "com.tp.adx",
    };

    /**
     * 广告特征词：**这才是能发现"新 SDK"的关键**。
     *
     * 只按 {@link #SDK_PREFIXES} 扫，永远只能找到已经写进表里的厂商 ——
     * 表外的广告 SDK 一个都发现不了。所以这里改为：凡是类名里带这些广告语义词的，
     * 都当作可疑广告类，再从类名反推厂商根包名，收进"已学到的 SDK"。
     */
    private static final String[] AD_TOKENS = {
            "adsdk", "adloader", "iadloader", "admanager", "adrequest", "adnet", "adkit",
            "adcore", "advlib", "advert", "adsplash", "splashad", "bannerad", "nativead",
            "feedad", "rewardad", "interstitialad", "fullscreenad", "adview", "adcontainer",
            "ads.", ".ads", "unionad", "adslot", "adlistener", "adcallback",
    };

    /** 反推厂商根包名时，不是厂商自己的二级/三级名（避免把内部包当成厂商） */
    private static final Set<String> GENERIC_SEG = new HashSet<>(Arrays.asList(
            "sdk", "ads", "ad", "core", "api", "common", "utils", "util", "internal", "impl",
            "loader", "view", "views", "open", "base", "lib", "libs", "plugin", "widget",
            "net", "http", "platform", "service", "manager", "adx", "ssp", "dsp", "bid",
            "bidding", "mediation", "adapter", "adapters", "report", "stat", "stats"));

    /** 学到的 SDK 前缀存在 LSPosed RemotePreferences 里（模块 App 与 Hook 进程共享） */
    private static final String K_LEARNED = "sdk_learned";
    private static final int MAX_LEARNED = 300;

    /** 已学到的厂商根包名（跨进程持久化，下次启动直接拦，无需重新学） */
    private static final Set<String> learned = Collections.synchronizedSet(new LinkedHashSet<String>());
    private static volatile boolean learnedLoaded;

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
    private static volatile boolean hookSide;

    /** 通用广告"加载 / 展示 / 初始化"方法名（AdClose SDKAdsKit 的 validAdMethods） */
    private static final Set<String> AD_METHODS = new HashSet<>(Arrays.asList(
            "loadAd", "loadAds", "load", "show", "fetchAd",
            "initSDK", "initialize", "initializeSdk", "init"));

    /** 不做处理的类（避免误伤 SDK 正常配置类） */
    private static final Set<String> EXCLUDED = new HashSet<>(Collections.singletonList(
            "com.baidu.mobads.sdk.api.BDAdConfig$Builder"));

    /** 判定范围：只在这些包名下自动检索，避免无谓开销 */
    private static final Set<String> TARGET_PKGS = new HashSet<>(Arrays.asList(
            MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP));

    private static final Set<String> hookedMethods = Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean started;
    private static final Set<String> discovered = Collections.synchronizedSet(new LinkedHashSet<String>());

    private SdkAutoBlock() {}

    public static void install(final ClassLoader cl, final String pkg) {
        if (!TARGET_PKGS.contains(pkg)) return;
        hookSide = true;
        // 0) 先把"上一轮学到的 SDK"读回来 —— 这次无需重新学习就能直接拦
        loadLearned();
        // 1) 已知 SDK 静态入口：立刻生效，不等扫描
        try { blockKnownSdks(cl); } catch (Throwable t) { H.log(Log.WARN, MainHook.TAG, "SDK static " + t); }

        // 2) 自动检索：后台线程，绝不阻塞启动
        if (started) return;
        started = true;
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                try { discover(cl, pkg); }
                catch (Throwable t) { H.log(Log.WARN, MainHook.TAG, "SDK discover " + t); }
            }
        }, "MapAdKiller-sdk");
        th.setDaemon(true);
        th.start();
    }

    // ══════════════════════════════════════════════ 1) 已知 SDK 静态入口

    /** 几个几乎必现的 SDK 单例入口；名字不对就跳过（getDeclaredMethod 抛异常被吞） */
    private static void blockKnownSdks(ClassLoader cl) {
        String[][] known = {
                {"com.qq.e.comm.managers.GDTADManager", "initWith", "initPlugin", "preRequestDNS"},
                {"com.bytedance.sdk.openadsdk.TTAdSdk", "init", "start"},
                {"com.bytedance.sdk.openadsdk.TTAdNative", "loadFeedAd", "loadBannerAd", "loadSplashAd", "loadRewardVideoAd"},
                {"com.kwad.sdk.KsAdSDKImpl", "init", "start"},
                {"com.baidu.mobads.sdk.api.AdSettings", "setChannelId"},
                {"com.sigmob.windad.WindAds", "start"},
                {"com.mbridge.msdk.out.MBridgeSDK", "init", "initSDK"},
                {"com.mbridge.msdk.out.MBBidManager", "bidLoad", "load"},
                {"com.anythink.core.api.ATSDK", "init", "initSDK"},
                {"com.tradplus.ads.open.TradPlusSdk", "init", "initSdk"},
        };
        for (String[] row : known) {
            Class<?> c = H.cls(cl, row[0]);
            if (c == null) continue;
            for (int i = 1; i < row.length; i++) hookAdMethod(c, row[i]);
        }
    }

    // ══════════════════════════════════════════════ 2) 自动检索

    private static void discover(ClassLoader cl, String pkg) {
        List<String> apks = apkPaths(pkg);
        if (apks.isEmpty()) {
            H.log(Log.WARN, MainHook.TAG, "SDK discover: no apk path for " + pkg);
            return;
        }
        loadLearned();

        long t0 = System.currentTimeMillis();
        Set<String> names = new LinkedHashSet<>();   // 命中内置前缀的类
        Set<String> suspected = new LinkedHashSet<>(); // 只命中"广告特征词"的可疑类
        long budgetBytes = 96L * 1024 * 1024;   // 最多解析这么多 dex 字节
        long used = 0;
        for (String apk : apks) {
            used += scanApk(apk, names, suspected, budgetBytes - used);
            if (used >= budgetBytes) break;
        }
        long ms = System.currentTimeMillis() - t0;

        // ---- 学习：从"可疑类"反推厂商根包名，把没见过的收进来 ----
        // 学习只能发生在 hook 侧：只有目标 App 自己的进程读得到它自己的 APK。
        // 但 hook 侧的 RemotePreferences 是只读实现，所以学到的**通过广播上报给设置 App**，
        // 由 App 侧（有写权限）落盘（见 LearnedReceiver）。
        int fresh = 0;
        Set<String> newRoots = new LinkedHashSet<>();
        for (String cls : suspected) {
            String root = vendorRoot(cls);
            if (root == null) continue;
            if (isBuiltin(root) || learned.contains(root)) continue;
            if (isHostOrFramework(root, pkg)) continue;
            synchronized (learned) {
                if (learned.size() >= MAX_LEARNED) break;
                learned.add(root);
            }
            newRoots.add(root);
            fresh++;
        }
        if (fresh > 0) {
            for (String r : newRoots) H.log(Log.INFO, MainHook.TAG, "SDK LEARNED new vendor: " + r);
            reportLearned(newRoots);
        }

        // ---- 用 内置前缀 ∪ 已学到前缀 重新筛一遍要挂的类 ----
        for (String cls : suspected) {
            if (matchesSdk(cls)) names.add(cls);
        }

        int ok = 0;
        for (String n : names) {
            Class<?> c = H.cls(cl, n);
            if (c == null) continue;
            if (hookClass(c)) ok++;
        }
        H.log(Log.INFO, MainHook.TAG, "SDK discover pkg=" + pkg + " classes=" + names.size()
                + " hooked=" + ok + " newVendor=" + fresh + " learnedTotal=" + learned.size()
                + " dex=" + (used / 1048576) + "MB in " + ms + "ms");
        if (Config.debugLog()) {
            int i = 0;
            for (String n : names) {
                if (i++ >= 60) break;
                H.log(Log.INFO, MainHook.TAG, "SDK class " + n);
            }
        }
    }

    /** 目标 App 的 APK / split APK 路径 */
    private static List<String> apkPaths(String pkg) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = null;
            for (int i = 0; i < 20 && app == null; i++) {
                try { app = at.getMethod("currentApplication").invoke(null); } catch (Throwable ignored) {}
                if (app == null) Thread.sleep(250);
            }
            android.content.Context ctx = (android.content.Context) app;
            if (ctx == null) return out;
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.sourceDir != null) out.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) Collections.addAll(out, ai.splitSourceDirs);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "SDK apkPaths " + t);
        }
        return out;
    }

    /** 扫一个 APK 里的 classes*.dex，收集命中的类名；返回消耗的字节数 */
    private static long scanApk(String path, Set<String> names, Set<String> suspected, long budget) {
        long used = 0;
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(new File(path)));
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (!n.endsWith(".dex") || !n.startsWith("classes")) continue;
                if (budget - used <= 0) break;
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
                int r;
                long got = 0;
                while ((r = zis.read(buf)) > 0) {
                    bos.write(buf, 0, r);
                    got += r;
                    if (got > 96L * 1024 * 1024) break;   // 单个 dex 上限
                }
                byte[] dex = bos.toByteArray();
                used += dex.length;
                parseDexStrings(dex, names, suspected);
            }
        } catch (Throwable t) {
            // 某些 split 读不到就直接跳过
        } finally {
            try { if (zis != null) zis.close(); } catch (Throwable ignored) {}
        }
        return used;
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
    private static void parseDexStrings(byte[] d, Set<String> out, Set<String> suspected) {
        if (d == null || d.length < 0x70) return;
        if (d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return;
        int n = le32(d, 0x38);
        int off = le32(d, 0x3C);
        if (n <= 0 || off <= 0 || off + 4L * n > d.length) return;

        StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < n; i++) {
            int so = le32(d, off + 4 * i);
            if (so <= 0 || so >= d.length) continue;
            int p = so;
            // ULEB128 长度
            int shift = 0;
            while (p < d.length && (d[p] & 0x80) != 0 && shift < 28) { p++; shift += 7; }
            if (p < d.length) p++;
            if (p >= d.length) continue;
            sb.setLength(0);
            int q = p;
            while (q < d.length && d[q] != 0 && sb.length() < 200) {
                char ch = (char) (d[q] & 0xFF);
                sb.append(ch);
                q++;
            }
            if (sb.length() < 10 || sb.length() >= 200) continue;
            if (sb.charAt(0) != 'L' || sb.charAt(sb.length() - 1) != ';') continue;
            String cls = sb.substring(1, sb.length() - 1).replace('/', '.');
            if (cls.indexOf('$') >= 0) cls = cls.substring(0, cls.indexOf('$'));
            if (EXCLUDED.contains(cls)) continue;

            if (matchesSdk(cls)) {
                if (out.add(cls)) discovered.add(cls);
                continue;
            }
            if (looksLikeAd(cls)) suspected.add(cls);
        }
    }

    /** 是否命中内置前缀或"已学到"的前缀 */
    private static boolean matchesSdk(String cls) {
        if (isBuiltin(cls)) return true;
        return matchesLearned(cls);
    }

    private static boolean isBuiltin(String cls) {
        for (String p : SDK_PREFIXES) {
            if (cls.equals(p) || cls.startsWith(p + ".")) return true;
        }
        return false;
    }

    private static boolean matchesLearned(String cls) {
        if (learned.isEmpty()) return false;
        for (String p : learned) {
            if (cls.equals(p) || cls.startsWith(p + ".")) return true;
        }
        return false;
    }

    /** 类名里带广告特征词 → 可疑广告类 */
    private static boolean looksLikeAd(String cls) {
        String low = cls.toLowerCase();
        if (low.startsWith("android.") || low.startsWith("java.") || low.startsWith("androidx.")
                || low.startsWith("kotlin") || low.startsWith("com.android.")) {
            return false;
        }
        // 关键约束：广告特征词必须出现在**类名的前 4 段命名空间**里。
        // 只按整串做子串匹配会把一堆无关类吸进来 —— 实测高德把
        // com.alipay.android.phone.mrpc.core.AdRequest 里的 "ad" 当成了广告信号，
        // 于是学出 com.alipay / com.google / com.huawei / com.taobao 这些**基础设施**包，
        // 真拦下去会连登录支付一起打死。
        String ns = namespaceOf(low, 4);
        for (String t : AD_TOKENS) {
            if (ns.contains(t)) return true;
        }
        return false;
    }

    /** 取类名前 n 段（com.a.b.C → "com.a.b."） */
    private static String namespaceOf(String low, int n) {
        int idx = -1;
        for (int i = 0; i < n; i++) {
            idx = low.indexOf('.', idx + 1);
            if (idx < 0) return low;
        }
        return low.substring(0, idx + 1);
    }

    /**
     * 从类名反推厂商根包名，例如
     *   com.qumeng.advlib.core.AdSdk      → com.qumeng.advlib
     *   com.foo.adsdk.internal.AdLoader   → com.foo.adsdk
     *   cn.abc.adx.view.BannerAdView      → cn.abc.adx
     * 规则：跳过 TLD 后，取到第一个"通用段"（sdk/core/api/ads…）为止，最多 3 段。
     */
    private static String vendorRoot(String cls) {
        String[] seg = cls.split("\\.");
        if (seg.length < 2) return null;
        int take;
        if (seg[0].length() <= 3) {          // com / org / net / cn / io / me …
            take = Math.min(seg.length, 3);
            if (seg.length >= 3 && GENERIC_SEG.contains(seg[2].toLowerCase())) take = 3;
            else if (seg.length >= 2) take = 2;
        } else {
            take = 2;
        }
        if (take < 2) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < take && i < seg.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(seg[i]);
        }
        String root = sb.toString();
        if (root.length() < 6 || root.length() > 60) return null;
        if (GENERIC_SEG.contains(seg[seg.length - 1].toLowerCase()) && take == seg.length) return null;
        return root;
    }

    /** 别把宿主 App 自己的包、或明显不是广告厂商的根当成 SDK 学下来 */
    private static boolean isHostOrFramework(String root, String pkg) {
        if (pkg != null && (root.startsWith(pkg) || pkg.startsWith(root))) return true;
        String r = root.toLowerCase();
        if (r.startsWith("android.") || r.startsWith("androidx.") || r.startsWith("java.")
                || r.startsWith("kotlin") || r.startsWith("com.android.")
                || r.startsWith("com.google.android.gms") || r.startsWith("com.google.firebase")) {
            return true;
        }
        // 厂商自有基础库 / 太泛的社交云服务根：学了会误伤（实测噪声）
        for (String bad : BAD_ROOTS) {
            if (r.equals(bad) || r.startsWith(bad + ".")) return true;
        }
        // 宿主厂商自己的根（com.baidu.* / com.tencent.* / com.autonavi.*）——
        // 除非这个根本身带广告特征词（com.tencent.ad 这种就保留）
        if (!looksLikeAd(root)) {
            for (String hv : HOST_VENDOR_ROOTS) {
                if (r.startsWith(hv + ".")) return true;
            }
        }
        return false;
    }

    /** 明确不是"广告 SDK"的根（实测被误学进来的：厂商基础设施 / 云 / 支付 / 社交） */
    private static final String[] BAD_ROOTS = {
            "com.baidu.platform", "com.baidu.sdk", "com.baidubce", "com.baidu.lbsyun",
            "com.tencent.net", "com.tencent.mm", "com.facebook",
            "com.google.android", "com.google.firebase", "org.apache", "okhttp3",
            "com.squareup", "com.alibaba.fastjson", "com.bytedance.sdk",
            // 高德 16.25.1 实测被误学进来的一批（都不是广告 SDK）
            "com.alipay", "com.alibaba", "com.taobao", "com.ali", "com.antdigital",
            "com.uc", "com.google", "com.huawei", "com.mobile", "com.amap",
            "com.nirvana", "com.robertjx", "com.dtf", "anetwork", "org.altbeacon",
            "com.autonavi", "com.iflytek", "com.tencent.mapsdk",
    };

    /** 宿主厂商根：非广告语义的一律不学 */
    private static final String[] HOST_VENDOR_ROOTS = {
            "com.baidu", "com.tencent", "com.autonavi", "com.amap", "com.google",
            "com.alibaba", "com.taobao", "com.alipay", "com.huawei", "com.miui",
            "com.xiaomi", "com.oppo", "com.vivo", "com.samsung",
    };

    // ══════════════════════════════════════════════ 学习结果的持久化

    /** 从 RemotePreferences 载入"已学到的 SDK"（模块 App 与 Hook 进程共享同一份） */
    private static void loadLearned() {
        if (!hookSide) return;          // App 侧绝不能走这里（会碰到 H）
        if (learnedLoaded) return;
        learnedLoaded = true;
        try {
            Set<String> s = Config.prefs().getStringSet(K_LEARNED, null);
            if (s != null) {
                synchronized (learned) { learned.addAll(s); }
                H.log(Log.INFO, MainHook.TAG, "SDK learned loaded: " + learned.size());
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "SDK learned load fail " + t);
        }
    }

    /** 存回去；写失败只影响"下次免学习"，不影响本次拦截 */
    private static void saveLearned() {
        if (!hookSide) return;          // App 侧不能写 remote prefs
        try {
            Set<String> copy;
            synchronized (learned) { copy = new LinkedHashSet<>(learned); }
            Config.prefs().edit().putStringSet(K_LEARNED, copy).commit();
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "SDK learned save fail " + t);
        }
    }

    /** 供设置页展示：已捕获的 SDK 清单 */
    public static List<String> learnedList() {
        loadLearned();
        synchronized (learned) {
            List<String> l = new ArrayList<>(learned);
            Collections.sort(l);
            return l;
        }
    }

    public static int learnedCount() { return learnedList().size(); }

    /**
     * 设置页专用读取：App 进程里 `H.module` 是 null，走 hook 侧的 Config.prefs() 读不到，
     * 必须经 XposedService 读同一份 RemotePreferences。
     */
    public static List<String> learnedListForApp() {
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s != null) {
                Set<String> set = s.getRemotePreferences(Config.PREF_GROUP)
                        .getStringSet(K_LEARNED, null);
                if (set != null) {
                    List<String> l = new ArrayList<>(set);
                    Collections.sort(l);
                    return l;
                }
            }
        } catch (Throwable ignored) {}
        // 注意：这里**不能**回退到 learnedList() —— 那会走 loadLearned() → Config.prefs() → H，
        // App 进程没有 libxposed api 包，会 NoClassDefFoundError 崩溃。
        List<String> l = new ArrayList<>();
        synchronized (learned) { l.addAll(learned); }
        Collections.sort(l);
        return l;
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
    public static List<String> learnFromApks(Context ctx) {
        Set<String> found = new LinkedHashSet<>();
        if (ctx == null) return new ArrayList<>();
        for (String pkg : TARGET_PKGS) {
            List<String> apks = new ArrayList<>();
            try {
                android.content.pm.ApplicationInfo ai =
                        ctx.getPackageManager().getApplicationInfo(pkg, 0);
                if (ai.sourceDir != null) apks.add(ai.sourceDir);
                if (ai.splitSourceDirs != null) Collections.addAll(apks, ai.splitSourceDirs);
            } catch (Throwable ignored) {
                continue;   // 没装这个地图就跳过
            }
            Set<String> hits = new LinkedHashSet<>();
            Set<String> suspected = new LinkedHashSet<>();
            long budget = 96L * 1024 * 1024;
            long used = 0;
            for (String a : apks) {
                used += scanApk(a, hits, suspected, budget - used);
                if (used >= budget) break;
            }
            for (String cls : suspected) {
                String root = vendorRoot(cls);
                if (root == null) continue;
                if (isBuiltin(root)) continue;
                if (isHostOrFramework(root, pkg)) continue;
                found.add(root);
            }
        }
        synchronized (learned) {
            learned.addAll(found);
            if (learned.size() > MAX_LEARNED) {
                List<String> all = new ArrayList<>(learned);
                learned.clear();
                learned.addAll(all.subList(0, MAX_LEARNED));
            }
            found.addAll(learned);
        }
        learnedLoaded = true;
        return new ArrayList<>(found);
    }

    /** App 侧：把学习结果交给 RemotePreferences（由 MainActivity 经 XposedService 写入） */
    public static Set<String> learnedSet() {
        synchronized (learned) { return new LinkedHashSet<>(learned); }
    }

    /** hook 侧：从已经由 App 侧写好的 remote prefs 里读（只读，够用） */
    public static void reloadLearned() {
        learnedLoaded = false;
        learned.clear();
        loadLearned();
    }

    /** 上报通道：hook 进程 → 设置 App（App 侧有 prefs 写权限，负责落盘） */
    public static final String ACTION_LEARNED = "io.github.ldxm666.mapadkiller.LEARNED";
    public static final String EXTRA_ROOTS = "roots";

    /**
     * 优先走 ContentProvider（按需唤起 App 进程，MIUI 拦不住）；
     * 广播只作为兜底保留。
     */
    private static void reportLearned(Set<String> roots) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            android.content.Context ctx =
                    (android.content.Context) at.getMethod("currentApplication").invoke(null);
            if (ctx == null) return;

            boolean ok = false;
            try {
                android.os.Bundle b = new android.os.Bundle();
                b.putStringArrayList(LearnedProvider.KEY_ROOTS, new ArrayList<>(roots));
                android.os.Bundle r = ctx.getContentResolver()
                        .call(LearnedProvider.URI, LearnedProvider.METHOD_PUT, null, b);
                ok = r != null && r.getInt(LearnedProvider.KEY_TOTAL, 0) > 0;
            } catch (Throwable t) {
                H.log(Log.WARN, MainHook.TAG, "SDK report via provider fail " + t);
            }
            if (!ok) {
                android.content.Intent i = new android.content.Intent(ACTION_LEARNED);
                i.setPackage(MainHook.PKG_SELF);
                i.putStringArrayListExtra(EXTRA_ROOTS, new ArrayList<>(roots));
                ctx.sendBroadcast(i);
            }
            H.log(Log.INFO, MainHook.TAG, "SDK report sent: " + roots.size()
                    + (ok ? " (provider)" : " (broadcast fallback)"));
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "SDK report fail " + t);
        }
    }

    /** 清空学习结果（设置页"重新学习"用） */
    public static void clearLearned() {
        synchronized (learned) { learned.clear(); }
        learnedLoaded = hookSide;      // App 侧不回读
        saveLearned();                 // hook 侧才真的写 remote
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
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
     * 这与 v1.0.2 白屏事故同源，是本模块第一红线。
     *
     * 所以：只处理 **声明在该 SDK 类自己身上** 的方法，并在下单前再做一次类名校验。
     */
    private static boolean hookClass(Class<?> c) {
        if (c == null || !isSdkClass(c.getName())) return false;
        boolean any = false;
        Method[] ms;
        try { ms = c.getDeclaredMethods(); } catch (Throwable t) { return false; }
        for (Method m : ms) {
            int mod = m.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
            String nm = m.getName();
            if ("<init>".equals(nm) || "<clinit>".equals(nm)) continue;
            if (!isSdkClass(m.getDeclaringClass().getName())) continue;   // 双保险

            if (AD_METHODS.contains(nm)) { if (hookAdMethod(m)) any = true; continue; }

            if (isLifecycleEntry(nm) && m.getReturnType() != void.class) {
                if (hookContextSwap(m)) any = true;
            }
        }
        return any;
    }

    /** 是不是"广告 SDK 自己的类"——框架/系统类一律 false，这是防全局误伤的闸门 */
    private static boolean isSdkClass(String name) {
        if (name == null || name.length() == 0) return false;
        if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("dalvik.") || name.startsWith("com.android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin")
                || name.startsWith("org.jetbrains") || name.startsWith("sun.")) {
            return false;
        }
        return matchesSdk(name);
    }

    /** loadAd/show/init… → 直接返回默认值，原方法不执行（广告加载/展示空转） */
    private static boolean hookAdMethod(Class<?> c, String name) {
        boolean any = false;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && hookAdMethod(m)) any = true;
        }
        return any;
    }

    private static boolean hookAdMethod(final Method m) {
        final String id = m.getDeclaringClass().getName() + "#" + m.getName();
        if (!hookedMethods.add(id)) return false;
        try {
            m.setAccessible(true);
            H.module.hook(m).setId("sdk_ad_" + Integer.toHexString(id.hashCode()))
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) {
                            // 不 proceed = 原方法不执行；返回类型默认值
                            return def(chain.getExecutable());
                        }
                    });
            if (Config.debugLog()) H.log(Log.INFO, MainHook.TAG, "SDK BLOCK " + id);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** init* / start* / getContext* 且返回非 void → 把入参里的真 Context 换成 FakeContext */
    private static boolean hookContextSwap(final Method m) {
        final String id = m.getDeclaringClass().getName() + "#" + m.getName();
        if (!hookedMethods.add(id)) return false;
        try {
            m.setAccessible(true);
            H.module.hook(m).setId("sdk_ctx_" + Integer.toHexString(id.hashCode()))
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object[] args = chain.getArgs().toArray();
                            boolean changed = false;
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof Context) {
                                    Context real = (Context) args[i];
                                    if (!(real instanceof FakeContext)) {
                                        args[i] = new FakeContext(real);
                                        changed = true;
                                    }
                                }
                            }
                            if (changed) return chain.proceed(args);
                            // 没有 Context 入参就什么都别做 —— 凭空替换返回值风险太大
                            return chain.proceed();
                        }
                    });
            if (Config.debugLog()) H.log(Log.INFO, MainHook.TAG, "SDK CTX  " + id);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** AdClose 的 isLifecycleEntry：方法名像 SDK 初始化入口 */
    private static boolean isLifecycleEntry(String name) {
        String n = name.toLowerCase();
        return n.startsWith("init") || n.contains("start") || name.contains("getContext");
    }

    /** 非 void 方法的默认返回；void 方法返回 null 即可 */
    private static Object def(java.lang.reflect.Executable ex) {
        if (!(ex instanceof Method)) return null;
        Class<?> r = ((Method) ex).getReturnType();
        if (!r.isPrimitive()) return null;
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        return null;
    }

    /**
     * 假 Context：SDK 拿到它之后既拿不到真实环境、也读不到文件/缓存目录，
     * 初始化会自然"空转"而不会崩（AdClose FakeContext 的等价简化版）。
     */
    private static final class FakeContext extends ContextWrapper {
        FakeContext(Context base) { super(base); }

        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "com.android.settings"; }
        @Override public File getFilesDir() { return new File("/dev/null"); }
        @Override public File getCacheDir() { return new File("/dev/null"); }
        @Override public File getExternalCacheDir() { return null; }
        @Override public File getExternalFilesDir(String type) { return null; }
        @Override public File getDir(String name, int mode) { return new File("/dev/null"); }
        @Override public Object getSystemService(String name) {
            try { return super.getSystemService(name); } catch (Throwable t) { return null; }
        }
        @Override public android.content.SharedPreferences getSharedPreferences(String n, int m) {
            return super.getSharedPreferences("mak_null_" + n, Context.MODE_PRIVATE);
        }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
            ai.packageName = "com.android.settings";
            ai.sourceDir = "/dev/null";
            ai.dataDir = "/dev/null";
            ai.flags = 0;
            return ai;
        }
    }
}
