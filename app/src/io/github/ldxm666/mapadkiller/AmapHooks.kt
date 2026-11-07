package io.github.ldxm666.mapadkiller

/** Exact application entry points. No view traversal or SDK discovery in AMap. */
object AmapHooks {

    @JvmStatic
    fun install(cl: ClassLoader) {
        // 「安装包异常」修复 #2：覆盖安装后首次冷启动的 linker namespace 库目录错位。
        //
        // ⚠ 2.3.0：这里曾经也挂了一份 `feature("libdir_fix", ...)`，已删除。理由两层：
        //   1) **它要修的那一次根本走不到这儿** —— 覆盖安装后第一次冷启动会命中
        //      `Baseline.maybeCapture()` 的透传分支，`mapadkiller/MainHook` 在那条分支里
        //      **直接 return**，整个 `AmapHooks.install` 一次都不执行。也就是说挂在这里
        //      对要修的场景是死代码，却要在其余每次启动上照价付钱。
        //      真正有效的那一次在 `mapadkiller/MainHook` 的透传分支（onPackageReady 最早处），
        //      那里也留了注释说明为什么必须在那儿。
        //   2) **代价太大** —— 它是 10 次 `loadLibrary` **串行** invoke，压在宿主首帧之前的主线程上，
        //      冷启动多 100–400ms，那段时间窗口只有主题白底（用户看到的就是白屏）。
        //      `AmapSoPreload` 已经把同类工作放到 daemon 线程，作者显然也知道它不该在主线程。
        // ⇒ 只保留 MainHook 那唯一一次调用。
        // 跳过开屏页：官方 `BootBizDataPreloaderImpl.canShowSplash()` 返回 `Boolean.FALSE`
        // 即走 `SplashFinishReason.NO_SPLASH`，直接进地图。
        // ★ 2.3.0 真机结论：去掉它之后高德反而**卡在 SplashActivity 30 秒不动**
        //   （只剩一行版权声明 = 用户说的白屏）；保留它的版本能正常到 NewMapActivity。
        //   所以恢复，并受 `Config.K_SPLASH_SKIP` 控制（默认开）。
        try {
            if (Config.splashSkip()) {
                val preloader = H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl")
                H.hookSig(preloader, "canShowSplash", "amap_splash_gate", H.FALSE)
            } else {
                H.log("event=splash_gate_off reason=user")
            }
        } catch (t: Throwable) {
            H.log(android.util.Log.WARN, MainHook.TAG, "event=splash_gate_fail", t)
        }
        AmapProtocolHooks.compatible(cl)
        // 高德官方「自动进入巡航」恢复：把那个没人调的布防入口强制成 true。
        // 布防成功后进入判定（20km/h×10s）、退出、前台判定全交给官方自己。
        feature("cruise_official") { AmapCruiseOfficial.install(cl) }
        // 启动任务计时探针：只计时不接管行为，用来把「哪个启动任务慢」
        // 变成可测量的数字。开关：adb shell setprop debug.mapclean.launch_probe 0
        feature("launch_probe") { AmapLaunchFast.install(cl) }
        // 「安装包异常」修复：原生库 libamaptts/libxquic 预加载（见 AmapSoPreload 注释）。
        feature("so_preload") { AmapSoPreload.install(cl) }
        // ★ 2.3.0 删掉 canShowSplash 的钉死 —— 「进入就白屏」的主因，不是省事。
        // 实查 17.02.0.1541（origin.apk/classes5.dex）：
        //   Lcom/autonavi/bundle/amaphome/impl/BootBizDataPreloaderImpl;
        //     public canShowSplash()Ljava/lang/Boolean;      ← 装箱返回，不是 ()Z
        // 而 H.FALSE 这个 Hooker 返回的就是 Boolean.FALSE —— 返回类型本来就是匹配的。
        // 所以「返回类型不对、靠 HookGuard 抛异常把 hook 熔断掉」不成立：这条 hook 在真机上是
        // **生效**的，宿主拿到 false 走 SplashFinishReason.NO_SPLASH，开屏页被整个摘掉，
        // 冷启首帧没有任何东西盖住还没 ready 的首页（BootBizDataPreloader.prepare 才刚开始）
        // ⇒ 用户看到的就是白屏。
        // 现在改成「只拦开屏广告请求（AmapAlimama.sendSplashScreen），不拦开屏页本身」：
        // 广告内容照样一条都取不到，回来的只是官方品牌开屏页，它正好盖住首帧。
        // ⇒ 除非同时解决首帧承载，不要把这两行加回来；它和 AmapAlimama 的「拦截必放锁」是一对：
        //   开屏页回来了，就真的有一个容器在等那条被拦掉的广告请求的回调。
        feature("search") { HotWordGuard.install(cl) }
        defer("search_grid") { AmapSearchGrid.install(cl) }
        feature("home") { AmapNativeHome.install(cl) }
        feature("tabs") { AmapTabLayout.install(cl) }
        defer("widgets") { AmapMapWidget.install(cl) }
        feature("labels") { AmapPoiLayer.install(cl) }
        feature("data") { AmapProtocolHooks.install(cl) }
        // 15.21.0.2038 专用：首页信息流 + 工具宫格（版本前缀匹配，17.02 不进入）。
        defer("feed1521") { AmapFeed1521.install(cl) }
        feature("tools1521") { AmapToolbox1521.install(cl) }
        feature("mine1521") { AmapMine1521.install(cl) }
        // 长按「我的」入口是**免 root 内置版专属**（root/LSPosed 用户用模块 App 的设置页）。
        // 在安装层就跳过，避免"装了 hook 再让面板打不开"的隐性开销。
        feature("mine_entry") {
            if (!HostInfo.isEmbedded()) {
                H.log("event=mine_entry_skipped reason=module_mode_at_install")
                return@feature
            }
            AmapMineEntry.install(cl)
        }
        feature("cruise") {
            AmapCruise.install(cl)
            AmapCruise.installProbe(cl)
        }
        feature("place_recommend") {
            try {
                AmapPlaceAds.install(cl)
            } catch (error: Exception) {
                throw IllegalStateException(error)
            }
        }
        H.done(MainHook.PKG_AMAP)
    }

    /**
     * 把一个安装器推到**首帧之后**才装。
     *
     * <p>为什么需要（真机实测）：高德冷启动 `Displayed SplashActivity +8s614ms`，
     * 而模块自己的安装在 `+72ms` 就完了（`install_done ok=76`）。
     * 也就是说：<b>这 8 秒不是我们花的，但我们挂在布局/网络热路径上的
     * hook 会被这 8 秒里的每一次布局与每一个响应反复调用</b>，
     * 把它们提早装上去只会拖慢首帧。</p>
     *
     * <p>所以：「首帧就要用」的（首页容器、底栏、启动闸门、推送/后台拦截）保持同步；
     * 「页面进去之后才用得上」的（搜索金刚位、信息流、地点推荐、操作位）推迟。</p>
     *
     * <p>兜底：拿不到主线程 Looper 就**同步跑** —— 宁可慢一点，不能丢功能。</p>
     */
    private fun defer(name: String, r: () -> Unit) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
                override fun run() {
                    try {
                        r()
                    } catch (t: Throwable) {
                        H.log(android.util.Log.WARN, MainHook.TAG,
                                "event=deferred_fail name=" + name, t)
                    }
                }
            }, DEFER_MS)
            H.log("event=deferred name=" + name + " delay_ms=" + DEFER_MS)
        } catch (t: Throwable) {
            try { r() } catch (ignored: Throwable) { }
        }
    }

    /** 延迟多久：要超过高德自己的首帧窗口，又不能让用户真的感到「搜索没生效」。 */
    private const val DEFER_MS = 3000L

    private fun feature(name: String, installer: () -> Unit) {
        val before = H.ok.get()
        try {
            installer()
        } catch (error: Throwable) {
            H.log("event=feature_skipped name=" + name + " reason=" + error.javaClass.simpleName)
        }
        H.logv("event=feature_probe name=" + name + " hooks=" + (H.ok.get() - before))
    }
}
