package io.github.ldxm666.mapadkiller

import android.util.Log

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 高德/百度/腾讯 去广告（v1.1.0 逻辑原样保留）。
 *
 * v2.0.0 合并版起本类**不再是 Xposed 入口**，改由 `io.github.ldxm666.mapclean.MainHook`
 * 转发回调（见那边的说明），本类方法签名从 override 改为带 module 参数的 static。
 */
class MainHook private constructor() {

    companion object {

        const val TAG = "MapAdKiller"

        const val PKG_AMAP = "com.autonavi.minimap"
        const val PKG_BMAP = "com.baidu.BaiduMap"
        const val PKG_TMAP = "com.tencent.map"
        /** 模块自身包名 = 合并后的包名 */
        const val PKG_SELF = "io.github.ldxm666.mapadkiller"

        @Volatile
        private var sProcess: String? = null

        @JvmStatic
        fun onModuleLoaded(mod: XposedModule, param: XposedModuleInterface.ModuleLoadedParam) {
            sProcess = param.processName
            H.module = mod
            // Perf.START 在这里被第一次读到（类初始化）⇒ 后续所有 event=phase 的 at_ms
            // 都相对"模块拿到回调"这一刻，正好对齐 logcat 里的 event=module_loaded。
            Perf.phase("module_loaded")
            // 冷启动探针：20 s 后打一份 hook 耗时排行榜。只在本进程起一条守护线程，
            // 只用 sleep + 一次日志输出，不碰宿主任何状态。
            Perf.startDumper(20000L)
            H.log(Log.INFO, TAG, "event=module_loaded process=" + sProcess
                    + " api=" + mod.apiVersion + " framework=" + mod.frameworkName)
        }

        @JvmStatic
        @Suppress("LongMethod")
        fun onPackageReady(mod: XposedModule, param: XposedModuleInterface.PackageReadyParam) {
            val pkg = param.packageName
            val process = sProcess
            if (pkg == null) return
            Perf.phase("package_ready")

            // 一次性宿主身份诊断：签名 DN + 嵌入/模块模式。
            // 用于快速识别"用户装错了包"（官方 CN=minimap / 内置版 CN=xiawanli）。
            HostInfo.reportOnce(pkg)

            // 模块自身：状态自检（LSPosed Manager 显示"已激活"）
            if (PKG_SELF == pkg) {
                try {
                    val sc = param.classLoader.loadClass(PKG_SELF + ".StatusCheck")
                    mod.hook(sc.getDeclaredMethod("amEnabled"))
                            .setId("self_check")
                            .intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    return java.lang.Boolean.TRUE
                                }
                            })
                } catch (ignored: Throwable) {}
                return
            }

            if (PKG_AMAP != pkg && PKG_BMAP != pkg && PKG_TMAP != pkg) return

            // ── 官方基线抓取闸门（v2.2.5）────────────────────────────────────────────
            // 首次安装/升级后的第一次启动：本进程一个 hook 都不装（等价于模块没装），
            // 让高德跑纯官方；后台线程等首页稳定后把官方 shared_prefs 原样复制到
            // <宿主 filesDir>/baseline/<模块版本>/。决策是同步的（只读标记），拷贝全在
            // 后台线程，绝不阻塞宿主启动；任何失败都只写标记，不影响宿主。
            // 放在进程分支之前 ⇒ 主进程和所有子进程一起透传（AmapSubprocess 也不装）。
            if (PKG_AMAP == pkg && Baseline.maybeCapture()) {
                H.log(Log.INFO, TAG, "event=baseline_passthrough process=" + process)
                // 「安装包异常」修复 #2 在**透传模式也要跑**：它不是 hook，只是把宿主自己的
                // 原生库按依赖顺序提前装一遍（见 AmapLibDirFix 注释），不改高德任何行为。
                // 必须有这一句 —— 「覆盖安装后首次冷启动 namespace 库目录错位」正好落在
                // 版本升级后第一次启动（= 透传这一次），否则又会弹「安装包异常」。
                if (process == null || !process.contains(":")) {
                    try {
                        AmapLibDirFix.install(param.classLoader)
                    } catch (t: Throwable) {
                        H.log(Log.WARN, TAG, "event=libdir_thread_failed", t)
                    }
                }
                // 采集模式（纯官方）也要保留悬浮窗：面板「首页」页那条红/黄/绿提示条要靠它显示，
                // 否则第一次进来的用户既看不到进度、也找不到「点这里重启启用」的入口。其余 hook 一条不装。
                if (process == null || !process.contains(":")) {
                    try {
                        io.github.ldxm666.mapclean.FloatingHooks.install(mod, pkg, true)
                    } catch (t: Throwable) {
                        H.log(Log.WARN, TAG, "event=floating_install_failed process=" + process)
                    }
                }
                return
            }

            // ── 进程策略（17.02 / 2.3.0）──────────────────────────────────────────────
            // 主进程：全量（广告 UI 全在主进程）。
            // 高德的两个常驻子进程：只挂 **最小 kill 集**（AmapSubprocess），绝不挂 UI/数据层逻辑。
            // 其余子进程（:lite1…:lite5 H5 容器 / :pushservice / :QQPlayerProcess /
            //   :dumpcrashservice / :installerror …）：直接跳过 —— 那些进程里挂 hook 只有风险没收益。
            //
            // 关于 H.markInstalled：它是 **static Set**，而 Android 每个进程是独立 VM，
            // 所以它天然就是 per-process 的 —— 主进程和子进程各有自己的一份，
            // `:locationservice` 里第一次调用同样返回 true，不会被子进程"抢先"或"误判已装"。
            // 它防的是**同一进程内重复 install**（真机日志里 activity lifecycle callbacks
            // 打了 3 次就是它拦下的）。⇒ 放开子进程**不需要改 H**，也不该改。
            val secondary = process != null && process.contains(":")
            if (secondary) {
                if (PKG_AMAP == pkg && AmapSubprocess.handles(process)) {
                    if (!H.markInstalled(pkg)) {
                        H.log(Log.INFO, TAG, "event=install_skipped reason=already pkg=" + pkg
                                + " process=" + process)
                        return
                    }
                    H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg
                            + " process=" + process + " mode=subprocess")
                    try {
                        // secondary == true 已蕴含 process != null，这里只是让编译器看见。
                        AmapSubprocess.install(param.classLoader, process!!)
                    } catch (t: Throwable) {
                        H.log(Log.ERROR, TAG, "event=install_failed pkg=" + pkg
                                + " process=" + process, t)
                        // 撤销占位，让本进程后续的 onPackageReady 能重试（每个进程只重试一次）。
                        // 不撤销的话这个子进程在本进程内永远 install_skipped，等于模块没装。
                        H.unmarkInstalled(pkg)
                    }
                } else {
                    H.log(Log.INFO, TAG, "event=skip_subprocess pkg=" + pkg + " process=" + process)
                }
                return
            }
            if (!H.markInstalled(pkg)) {
                H.log(Log.INFO, TAG, "event=install_skipped reason=already pkg=" + pkg)
                return
            }

            val cl = param.classLoader
            try {
                when (pkg) {
                    PKG_AMAP -> {
                        H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg
                                + " process=" + process + " mode=main")
                        AmapHooks.install(cl)   // 去广告基线
                        // 冷启动四段探针（只量不改）。放在去广告基线之后：它一条行为都不改，
                        // 只把 InterfaceAppImpl.init / IComponentManager.init / NewMapActivity.o
                        // / F() 这四段的耗时钉到同一条时间轴上，用来回答
                        // 「startLaunch() 之后到首帧之间到底花在哪」。
                        // 关掉：adb shell setprop debug.mapclean.start_probe 0
                        try {
                            AmapStartProbe.install(cl)
                        } catch (t: Throwable) {
                            H.log(Log.WARN, TAG, "event=start_probe_install_failed", t)
                        }
                        // 阿里妈妈广告请求层（开屏广告请求 + 广告埋点上报）。
                        // 刻意放在 AmapHooks **之外**：AmapHooks 是"高德自有界面/数据层"的总入口，
                        // 而这一层是 SDK 形态的广告通道（ws/valueadded/alimama/splash_screen、
                        // ws/h5_log），单独成文件便于审计、便于单独回滚，也不受 AmapHooks 的
                        // feature() 计数口径影响。
                        try {
                            AmapAlimama.install(cl)
                        } catch (t: Throwable) {
                            H.log(Log.WARN, TAG, "event=alimama_failed", t)
                        }
                        // 阿里 UT 埋点初始化闸门（com.alibaba.analytics.AnalyticsMgr.init/checkInit）。
                        // 同样独立成文件：它拦的是**第三方埋点 SDK**，与"去广告"是两件事，
                        // 回滚粒度必须分开（见 Config.K_TRACK_OFF）。
                        try {
                            AmapTrack.install(cl)
                        } catch (t: Throwable) {
                            H.log(Log.WARN, TAG, "event=track_failed", t)
                        }
                        // 杠杆 A：WorkManager 的两条执行通道（RescheduleReceiver + SystemJobService）。
                        // 这两个组件都在**主进程**（components.csv: process 为空），所以在这里装；
                        // 推送长连接（ACCS/AGOO）在 :locationservice，由 AmapSubprocess 装。
                        try {
                            AmapJob.installMain(cl)
                        } catch (t: Throwable) {
                            H.log(Log.WARN, TAG, "event=job_failed", t)
                        }
                        // 推送长连接（ACCS/AGOO）**主进程也装一份**。
                        // 真机观测到的 ACCS0 在 :locationservice（由 AmapSubprocess 装），
                        // 但高德完全可能同时从主进程初始化 accs（SDK 常见做法）⇒ 主进程也挂一次，
                        // 让 ACCS0 无论在哪初始化都被掐断。
                        // 代价为零：类在本进程加载得到就装，装不上/不触发就空转，不会报错。
                        try {
                            AmapJob.installPush(cl)
                        } catch (t: Throwable) {
                            H.log(Log.WARN, TAG, "event=push_failed", t)
                        }
                        // ── 强制去除版本更新提示（默认生效，**不设开关**）────────────────────
                        // 用户明确要求"默认加入无需开关"。落点是 AppUpgradeController 里唯一那两处
                        // 弹升级框的方法，网络层一条不碰（ws/app/conf/app_update 照发）。
                        // 找不到类就安静跳过，见 AmapUpdateBlock 的注释。
                        try {
                            AmapUpdateBlock.install(cl)
                        } catch (t: Throwable) {
                            H.log(Log.WARN, TAG, "event=update_block_failed", t)
                        }
                        // ── 实验特性：帧率上限 / 去除建筑物（**默认关**，安装期门控）────────
                        // 两个都是"改高德自己的渲染"，判错就是地图错乱，所以缺省什么都不做；
                        // 关着的时候一条 hook 不装、.so 也不加载，代价为零。
                        // 打开后**必须强停重开高德**才生效 —— 面板文案里写明了这一点。
                        var fpsCap = false
                        var stripBuildings = false
                        try {
                            fpsCap = AmapTuneGate.on(Config.K_AMAP_FPS_ON)
                        } catch (ignored: Throwable) {
                        }
                        try {
                            stripBuildings = AmapTuneGate.on(Config.K_AMAP_BUILDING_ON)
                        } catch (ignored: Throwable) {
                        }
                        if (fpsCap) {
                            try {
                                AmapFpsCap.install(cl)
                            } catch (t: Throwable) {
                                H.log(Log.WARN, TAG, "event=fps_cap_failed", t)
                            }
                        } else {
                            H.log(Log.INFO, TAG, "event=hook_skipped key=amap_fps_on reason=switch_off")
                        }
                        if (stripBuildings) {
                            try {
                                AmapBuildingPatch.install(cl)
                            } catch (t: Throwable) {
                                H.log(Log.WARN, TAG, "event=building_patch_failed", t)
                            }
                        } else {
                            H.log(Log.INFO, TAG,
                                    "event=hook_skipped key=amap_building_on reason=switch_off")
                        }
                    }
                    PKG_BMAP -> {
                        H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg + " process=" + process)
                        // 百度界面精简在 bmapclean 里。一个 APK 只能有一个 Xposed 入口，
                        // 所以这里**必须**显式转发。
                        //
                        // 这两个转发调用曾经被删掉（当时是为了让"只并入高德"的免 root 精简构建
                        // 能编译），结果连完整模块的百度一起失效了：免 root 版不需要百度，
                        // 但模块版需要。正确做法是给精简构建生成 no-op 桩类
                        // （见 tools/build_variant.py 的 STUBS），而不是把调用从共享源码里拿掉。
                        io.github.ldxm666.bmapclean.MainHook.onPackageReady(mod, param)
                    }
                    PKG_TMAP -> {
                        H.log(Log.INFO, TAG, "event=install_begin pkg=" + pkg + " process=" + process)
                        TmapHooks.install(cl)
                        // 腾讯界面精简在 tmapclean 里。同百度：一个 APK 只能有一个 Xposed 入口，
                        // 所以这里**必须**显式转发（精简构建由 tools/build_variant.py 出 no-op 桩）。
                        io.github.ldxm666.tmapclean.MainHook.onPackageReady(mod, param)
                    }
                    else -> {
                    }
                }
                // 三家通用层：广告 SDK 自动检索 + 拦截（已知入口立刻挂，dex 扫描后台跑）
                if (PKG_TMAP == pkg) SdkAutoBlock.install(cl, pkg)
            } catch (t: Throwable) {
                H.log(Log.ERROR, TAG, "event=install_failed pkg=" + pkg, t)
                // 同上：占位必须先退回。这里 catch 的是"整个包的安装"，任何一处抛出都会让
                // 剩下的 hook 一条都装不上；不撤销的话本进程内不会再有机会补齐。
                H.unmarkInstalled(pkg)
            }
        }
    }
}
