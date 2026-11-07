package io.github.ldxm666.tmapclean

import android.util.Log

import io.github.libxposed.api.XposedInterface

/**
 * 腾讯地图 <b>开屏广告</b>拦截 —— 11.7.0 / versionCode 6013 实证。
 *
 * <h3>为什么这个类在 tmapclean 而不是 mapadkiller.TmapHooks</h3>
 * 非 root 内置版（{@code --Target tmap}）的瘦身规则里，{@code io.github.ldxm666.mapadkiller}
 * <b>整包被裁掉</b>（{@code tools/build_variant.py} 的 {@code "dropPackages"}），
 * 只留一个转发用的 {@code MainHook} 空实现。于是 2.3.1 的腾讯产物里
 * {@code TmapHooks / SplashProbe / SplashContainerHooks / Sweeper / ViewKiller} <b>一个都没有</b>
 * —— 实测 {@code output/nonroot-tmap-231/腾讯地图-2.3.1-免root内置版.apk} 内嵌模块的
 * classes.dex 里这些名字出现次数均为 0。这就是"新版本开屏广告回来了"的真正原因：
 * 不是规则失效，是规则<b>根本没被打进产物</b>。
 *
 * <p>所以开屏规则必须落在 {@code tmapclean}（tmap 变体保留真件的唯一 vendor 包）。
 *
 * <h3>6013 锚点核对（全部逐类解析 {@code base.apk} 的 22 个 DEX）</h3>
 * <pre>
 *   存在  com.qq.e.comm.managers.GDTADManager
 *           initWith(Context,String)Z / isInitialized()Z / initPlugin()V / preRequestDNS()V
 *   存在  com.qq.e.tg.splash.TGSplashAD
 *           fetchAdOnly()V / preLoad()V / hasPlayedToday()Z / fetchAndShowIn(ViewGroup)V
 *   存在  com.tencent.map.init.tasks.optional.SplashRequestTask#run()V
 *   存在  com.tencent.map.init.tasks.DecodeSplashTask#run()V
 *   存在  com.tencent.map.init.tasks.optional.CheckAndDeleteSplashTask#run()V
 *   存在  com.tencent.map.launch.v2.task.t2.SplashManagerInitTask#run()V  (super=AnchorTask)
 *   存在  com.tencent.map.ama.splash.SplashBroadcastReceiver#onReceive
 *   存在  com.tencent.map.ama.splash.SplashTimerBroadcastReceiver#onReceive
 *   存在  com.tencent.map.ama.splash.SplashEntity#a()Z      （endT >= now，即"未过期"）
 *   共有  com.tencent.ams.fusion.*   6011 = 800 类 / 6013 = 800 类（<b>数量一致，"整块消失"是误判</b>）
 *   缺失  com.tencent.ams.fusion.widget.splash.SplashView
 * </pre>
 *
 * <p>最后一条要说清楚，否则很容易误判成"6013 把它删了"：<b>6011 里也没有这个类</b>。
 * 融合 SDK 的开屏容器实际叫 {@code com.tencent.ams.fusion.dynamic.SplashAdDynamicView}；
 * 旧代码里 {@code SplashContainerHooks.installContainerProbe(cl, "...fusion.widget.splash.SplashView")}
 * 从一开始就指向一个不存在的类 —— 它在本轮之前就是死代码，不是被 6013 改没的，这条不再补。
 * 真正起作用的是优量汇插件（{@code GDTADManager} / {@code TGSplashAD}）与宿主自己的
 * {@code ama.splash} 图片开屏；这两组锚点 6011→6013 <b>逐方法签名完全一致</b>。
 *
 * <h3>拦截层次（保守：只动开屏这一条链）</h3>
 * <ol>
 *   <li><b>优量汇总闸</b>：{@code GDTADManager.initWith/isInitialized=false}，插件与 DNS 预取吞掉。
 *       宿主自身埋点读 {@code getPM()/getSM()} 的地方一律不碰（返回 null 会 NPE）。</li>
 *   <li><b>优量汇开屏插件</b>：{@code TGSplashAD} 的预取与"拉取并显示"全吞。
 *       {@code hasPlayedToday()} 恒 true，让频控认为今天已经播过。</li>
 *   <li><b>宿主开屏任务</b>：{@code SplashRequestTask} / {@code DecodeSplashTask} 的 {@code run()}
 *       直接返回 —— 这两个是纯预取/解码任务，吞掉不产生任何回调依赖。</li>
 *   <li><b>开屏广播</b>：{@code com.tencent.map.splash.request} /
 *       {@code com.tencent.map.splash.update} 两个 receiver 吞掉。</li>
 *   <li><b>缓存实体</b>：{@code SplashEntity.a()Z} 恒 false。它是"这条开屏还没过期"的判据
 *       （{@code endT >= now}，实证自 {@code a()Z} 的字节码），恒 false 等于把库里已经下好的
 *       开屏全部标成过期 —— 第 3 条只挡新的，这一条管旧的。</li>
 * </ol>
 *
 * <p><b>{@code SplashManagerInitTask#run()} 只观察不吞</b>：它是 {@code AnchorTask} 子类，
 * 是启动任务图的锚点（别的任务在等它完成）。11.6.0 上把它整个吞掉导致"卡开屏、
 * 按返回才进得去"，这条教训在 6013 上依然适用，注释保留。
 */
object TmapSplash {

    @JvmStatic
    fun install(cl: ClassLoader) {
        gdt(cl)
        tangram(cl)
        hostTasks(cl)
        broadcasts(cl)
        cachedEntity(cl)
    }

    /** ① 优量汇（GDT）总闸。 */
    private fun gdt(cl: ClassLoader) {
        val gdt = H.cls(cl, Anchors.GDT_MANAGER)
        if (gdt == null) {
            quiet("gdt", Anchors.GDT_MANAGER)
            return
        }
        H.hookSig(
            gdt, "initWith", "tmap_splash_gdt_init", H.FALSE,
            android.content.Context::class.java, String::class.java
        )
        H.hookAll(gdt, "isInitialized", "tmap_splash_gdt_isinit", H.FALSE)
        H.hookAll(gdt, "initPlugin", "tmap_splash_gdt_plugin", H.VOID)
        H.hookAll(gdt, "preRequestDNS", "tmap_splash_gdt_dns", H.VOID)
        // getPM()/getSM() 是广告插件管理器，宿主自己也读来做埋点，返回 null 会 NPE —— 只看不动。
        H.hookAll(gdt, "getPM", "tmap_splash_gdt_pm", H.observe("GDTADManager.getPM()"))
    }

    /** ② 优量汇开屏插件（Tangram Splash）。方法名与 11.6.0 完全一致，6013 未变。 */
    private fun tangram(cl: ClassLoader) {
        val tg = H.cls(cl, Anchors.TG_SPLASH)
        if (tg == null) {
            quiet("tangram", Anchors.TG_SPLASH)
            return
        }
        H.hookAll(tg, "fetchAdOnly", "tmap_splash_tg_fetchonly", H.VOID)
        H.hookAll(tg, "preLoad", "tmap_splash_tg_preload", H.VOID)
        H.hookAll(tg, "hasPlayedToday", "tmap_splash_tg_played", H.TRUE)
        // fetchAndShowIn 是"拉取并显示"。吞掉后宿主的 onAdFinish/onSkip 不会来，
        // 所以真正让广告不出现的是 ①（initWith=false）；这里只是不让它再发起一次拉取。
        H.hookAll(tg, "fetchAndShowIn", "tmap_splash_tg_fetchshow", H.VOID)
    }

    /** ③ 宿主自己的开屏任务。 */
    private fun hostTasks(cl: ClassLoader) {
        val req = H.cls(cl, Anchors.SPLASH_REQUEST_TASK)
        if (req == null) quiet("splash_request_task", Anchors.SPLASH_REQUEST_TASK)
        else H.hookDeclared(req, "run", "tmap_splash_req_run", H.VOID)

        val dec = H.cls(cl, Anchors.SPLASH_DECODE_TASK)
        if (dec == null) quiet("decode_splash_task", Anchors.SPLASH_DECODE_TASK)
        else H.hookDeclared(dec, "run", "tmap_splash_decode_run", H.VOID)

        // CheckAndDeleteSplashTask 是"检查并删除过期开屏"——它是清理方，必须放行，
        // 否则过期素材永远删不掉。只观察一次，确认它确实跑过。
        val check = H.cls(cl, Anchors.SPLASH_CHECK_TASK)
        if (check != null) {
            H.hookDeclared(
                check, "run", "tmap_splash_checkdel",
                H.observe("CheckAndDeleteSplashTask.run()")
            )
        }

        // 锚点任务：只观察，绝不吞（见类注释）。
        val mgr = H.cls(cl, Anchors.SPLASH_MGR_TASK)
        if (mgr == null) quiet("splash_mgr_task", Anchors.SPLASH_MGR_TASK)
        else H.hookDeclared(mgr, "run", "tmap_splash_mgr_init", H.observe("SplashManagerInitTask.run()"))
    }

    /** ④ 开屏请求/刷新广播。 */
    private fun broadcasts(cl: ClassLoader) {
        val req = H.cls(cl, Anchors.SPLASH_BCAST_REQUEST)
        if (req == null) quiet("splash_bcast_request", Anchors.SPLASH_BCAST_REQUEST)
        else {
            H.hookDeclared(req, "onReceive", "tmap_splash_bcast_req",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        Diag.hit("tmap_splash_bcast_req")
                        return null
                    }
                })
        }
        val timer = H.cls(cl, Anchors.SPLASH_BCAST_TIMER)
        if (timer == null) quiet("splash_bcast_timer", Anchors.SPLASH_BCAST_TIMER)
        else {
            H.hookDeclared(timer, "onReceive", "tmap_splash_bcast_timer",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        Diag.hit("tmap_splash_bcast_timer")
                        return null
                    }
                })
        }
    }

    /** ⑤ 已缓存的开屏实体：{@code a()Z} = 「endT >= now」，恒 false 即全部判为过期。 */
    private fun cachedEntity(cl: ClassLoader) {
        val entity = H.cls(cl, Anchors.SPLASH_ENTITY)
        if (entity == null) {
            quiet("splash_entity", Anchors.SPLASH_ENTITY)
            return
        }
        H.hookBool0(entity, "a", "tmap_splash_entity_valid", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                Diag.hit("tmap_splash_entity_expired")
                return java.lang.Boolean.FALSE
            }
        })
    }

    /** 目标类不存在时不刷 WARN —— 跨版本少一个类不该看起来像故障。 */
    private fun quiet(what: String, cls: String) {
        H.log(Log.DEBUG, MainHook.TAG, "skip " + what + " (" + cls + " not in this build)")
    }
}
