package io.github.ldxm666.bmapclean

import android.content.SharedPreferences
import android.util.Log

/**
 * Hook 侧配置读取。
 *
 * 存储 = LSPosed RemotePreferences（同名 group 与模块 App 侧 XposedService 共享）。
 *
 * 失效安全：任何异常/缺省一律返回 **true（可见）**。
 * 也就是说模块挂不上、配置读不到、目标改版导致规则全 miss 时，
 * 百度地图的界面保持原样，不会出现"被清空到没法用"。
 */
class Cfg private constructor() {

    /** 一个可被 prefs 变更刷新的布尔开关。 */
    abstract class Flag {
        @Volatile
        private var value = false
        @Volatile
        private var resolved = false
        init { FLAGS.add(this) }
        protected abstract fun compute(): Boolean
        fun on(): Boolean {
            if (!resolved) resolve()
            return value
        }
        fun resolve() {
            val v = try { compute() } catch (t: Throwable) { true }
            value = v
            resolved = true
        }
    }

    companion object {

        /** RemotePreferences group 名（App 侧 CategoryActivity 用同一个） */
        const val GROUP = "bmapclean_config"

        /** 诊断日志开关（唯一默认 false 的键） */
        const val K_DEBUG = "debug_log"
        /** hook 侧写回的最近一次诊断摘要 */
        const val K_DIAG = "diag_last"

        /**
         * 真机取证期临时强制开日志（视图树 dump 走这里）。
         * 交付版必须为 false；probe 构建才临时打开。
         */
        const val FORCE_DEBUG = false

        /**
         * 宿主本地后端（{@code /data/data/com.baidu.BaiduMap/shared_prefs/bmapclean_config.xml}）。
         *
         * <p><b>2.3.0 新增，这把「只读」真正修掉了。</b> 2.2.8 及以前 {@link #prefs()} 只有
         * {@code H.module.getRemotePreferences()} 一条路：
         * <ul>
         *   <li>hook 侧的 RemotePreferences 是**只读代理**（{@code commit()} 抛
         *       {@code UnsupportedOperationException}）；</li>
         *   <li>内置版（LSPatch）的 remote 库会 {@code SQLiteCantOpenDatabaseException}，
         *       {@code commit()} 返回 true 但读不回来。</li>
         * </ul>
         * 两条都让「开关拨了不生效」，面板头部于是恒显「只读 · 无法写入配置」。
         * 宿主自己的 SharedPreferences 永远可写，且 hook 侧与面板侧在**同一个进程**里读同一个文件，
         * 所以内置版以它为准；模块版（LSPosed）仍以 remote 为准，不受影响。
         */
        @Volatile
        private var localCache: SharedPreferences? = null
        @Volatile
        private var remoteCache: SharedPreferences? = null
        @Volatile
        private var snapshot: Map<String, *> = java.util.Collections.emptyMap<String, Any?>()
        @Volatile
        private var expires = 0L
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _: SharedPreferences?, key: String? ->
            expires = 0
            refreshFlags()
            refreshViews(key)
        }
        @Volatile
        private var dbgAt = 0L
        @Volatile
        private var dbgVal = false

        /** 后端标记：-1 未定案 / 0 无 / 1 本地 / 2 远程。 */
        const val BACKEND_NONE = 0
        /** 宿主本地 SharedPreferences（内置版的正常状态）。 */
        const val BACKEND_LOCAL = 1
        /** 框架远程 prefs（LSPosed 模块版）。 */
        const val BACKEND_REMOTE = 2
        @Volatile
        private var backendState = -1

        /**
         * 本进程是否处于**免 root 内置版**。
         *
         * <p>★ 2.3.0 修复「内置版配置不保存」。判据用
         * <b>框架名</b> {@code XposedModule#getFrameworkName()} —— LSPatch 会报 {@code LSPatch}，
         * LSPosed 会报 {@code LSPosed}/类似名。这是两种形态**唯一可靠的区分点**：
         * <ul>
         *   <li>{@code HostInfo.isEmbedded()} 在百度变体里是**桩**，早期返回 false；</li>
         *   <li>{@code mapclean.App.embedded()} 要等 {@code attachEmbedded} 之后才为 true，
         *        而面板第一次写配置就发生在那之前；</li>
         *   <li>看包名不行：模块版的 hook 侧也跑在百度进程里。</li>
         * </ul>
         *
         * <p>判错的代价就是用户反馈的那个：走服务通道写 LSPatch 的坏库，
         * {@code commit()} 返回 true 但读不回来 ⇒ 关掉切个页面又自己开了。
         */
        @JvmStatic
        fun embedded(): Boolean {
            // ★★ 2.3.0 修正：**只缓存「true」，不缓存 false**。
            //
            // 上一版把第一次算出的结果（包括 false）永久写进 {@code embState}。
            // 而第一次调用很可能发生在 {@code H.module} 赋值之前（静态初始化、
            // 面板提前渲染）⇒ 结果被钉死为 false ⇒ 后面每一次写配置都走服务通道
            // 写 LSPatch 的坏库 ⇒ commit 返回 true 但读不回来 ⇒ 「关掉切页面又自己开了」。
            //
            // 现在：true 才缓存；false 每次重算（一次字符串比对，热路径也无所谓），
            // 直到 {@code H.module} 就位后给出确定答案。
            if (embState == 1) return true
            var v = false
            var fw: String? = null
            try {
                val m = H.module
                if (m != null) {
                    // Java 原文是 String.valueOf(...)：null 会变成字符串 "null"，不能改成 NPE。
                    val name = "" + m.frameworkName
                    fw = name
                    v = name.lowercase(java.util.Locale.US).contains("lspatch")
                }
            } catch (ignored: Throwable) { }
            if (!v) {
                try { v = io.github.ldxm666.mapclean.HostPrefs.embedded() } catch (ignored: Throwable) { }
            }
            if (v) {
                embState = 1
                H.log("event=cfg_embedded v=true framework=" + fw)
            } else if (fw != null && !fwLogged) {
                fwLogged = true
                H.log("event=cfg_embedded v=false framework=" + fw)
            }
            return v
        }

        /** 负面结论只打一次日志，不刷屏。 */
        @Volatile
        private var fwLogged = false

        /** -1 未定案 / 0 模块版 / 1 内置版。 */
        @Volatile
        private var embState = -1


        // ══════════════════════════════════════════════════════════════════════════
        // 省电开关（命名与高德 mapadkiller/Config 对齐）
        // ══════════════════════════════════════════════════════════════════════════
        //
        // 语义统一为「true = 已拦截」。默认 true（读不到配置也按拦截处理）。
        // 全部走**安装期门控**（见 BaiduPower.install）：关掉 ⇒ 连 hook 都不装 ⇒ 零性能开销。
        // 代价：改这些开关要**强制停止并重开百度地图**才生效。

        /** 推送长连接安全档：不建连接 + 不处理推送消息。 */
        const val K_PUSH_OFF = "push_off"
        /** 推送长连接硬档：掐断连接/线程的真实创建链。 */
        const val K_PUSH_HARD_OFF = "push_hard_off"
        /** 推送服务激进档：通道服务起来即退出。 */
        const val K_PUSH_KILL_OFF = "push_kill_off"
        /** 后台常驻服务（定位/云控/统计等自启动 Service）。 */
        const val K_BG_SERVICE_OFF = "subproc_off"
        /** 定时任务（AlarmManager / JobScheduler / WorkManager）。 */
        const val K_TIMER_OFF = "subproc_job_off"
        /** 桌面小组件刷新。 */
        const val K_WIDGET_OFF = "subproc_widget_off"
        /** 第三方埋点/统计 SDK。 */
        const val K_TRACK_OFF = "track_off"

        /** 省电键全集（「一键省电」用）。 */
        @JvmField
        val POWER_KEYS = arrayOf(
                K_PUSH_HARD_OFF, K_PUSH_OFF, K_PUSH_KILL_OFF,
                K_BG_SERVICE_OFF, K_TIMER_OFF, K_WIDGET_OFF, K_TRACK_OFF
        )

        // ── 零成本开关：常驻 hook 的拦截器里只读一个 volatile ──────────────────────
        @JvmField
        val FLAGS: MutableList<Flag> =
                java.util.concurrent.CopyOnWriteArrayList<Flag>()

        @JvmStatic
        fun flag(key: String): Flag {
            return object : Flag() {
                override fun compute(): Boolean { return Cfg.visible(key) }
            }
        }

        private fun refreshFlags() {
            for (f in FLAGS) f.resolve()
        }

        /**
         * 安装期门控：开关关掉时**根本不装**对应 hook（"关闭 = 不加载、不消耗性能"）。
         * 读不到配置按 true（已拦截）处理。
         */
        @JvmStatic
        fun installGate(key: String): Boolean {
            return try { visible(key) } catch (t: Throwable) { true }
        }

        /**
         * 「一键省电」：把 {@link #POWER_KEYS} 全部写成 true。
         *
         * @return 实际写入的键数；0 = 当前没有可写后端（面板会提示"只读"）
         */
        @JvmStatic
        fun enableAllPower(): Int {
            val p = prefs() ?: return 0
            try {
                val e = p.edit()
                for (k in POWER_KEYS) e.putBoolean(k, true)
                if (!e.commit()) return 0
                expires = 0
                refreshFlags()
                return POWER_KEYS.size
            } catch (t: Throwable) {
                return 0
            }
        }

        /**
         * 当前配置后端 —— {@link #BACKEND_LOCAL}（内置版）/ {@link #BACKEND_REMOTE}（模块版）
         * / {@link #BACKEND_NONE}（宿主 Context 还没就绪）。
         */
        @JvmStatic
        fun backend(): Int {
            prefs()
            val b = backendState
            return if (b < 0) BACKEND_NONE else b
        }

        /** 配置变了：丢快照 + 重算所有 {@link Flag}。面板写完必须调一次。 */
        @JvmStatic
        @JvmOverloads
        fun invalidate(key: String? = null) {
            expires = 0
            dbgAt = 0
            // ★★ 2.3.0：也要丢掉「已定案的读后端」。
            // 否则会出现：写进了宿主本地（{@code cfg_write_recover via=local}），
            // 但面板重渲染时 {@link #prefs()} 还返回当初定案的那个 remote，
            // 于是读出来还是旧值 ⇒ 用户看到「关掉又自己开了」。
            resetBackend()
            refreshFlags()
            refreshViews(key)
        }

        /** Runs in the host for both local edits and framework remote notifications. */
        private fun refreshViews(key: String?) {
            BaiduMineCards.refresh(key)
            if (key == null || key == Spec.K_TOOLS || key.startsWith(Spec.K_TOOL)) BaiduToolGrid.refresh()
            if (key == null || key == Spec.K_HC || Spec.isCommuteChild(key)) BaiduCommute.refresh()
        }

        /** 宿主本地 prefs（永久缓存，文件句柄本身就是稳定的）。 */
        private fun local(): SharedPreferences? {
            val cached = localCache
            if (cached != null) return cached
            synchronized(Cfg::class.java) {
                val again = localCache
                if (again != null) return again
                val p = io.github.ldxm666.mapclean.HostPrefs.local(GROUP)
                if (p != null) {
                    localCache = p
                    try { p.registerOnSharedPreferenceChangeListener(listener) } catch (ignored: Throwable) {}
                }
                return p
            }
        }

        /**
         * 框架远程 prefs。
         *
         * <p>⚠ <b>不缓存 null 的失败态</b>：2.2.8 以前把 {@code null} 永久写进 {@code cached}，
         * 只要第一次调用恰好赶在框架初始化之前（或 remote 库恰好抛异常），整个进程就再也拿不到配置。
         * 现在失败只是「这次没有」，下次 {@link io.github.ldxm666.mapclean.HostPrefs#mayProbe()}
         * 放行后重试。
         */
        private fun remote(): SharedPreferences? {
            val cached = remoteCache
            if (cached != null) return cached
            synchronized(Cfg::class.java) {
                val again = remoteCache
                if (again != null) return again
                var p: SharedPreferences? = null
                try {
                    val mod = H.module
                    p = if (mod == null) null else mod.getRemotePreferences(GROUP)
                } catch (t: Throwable) {
                    p = null
                }
                if (p != null) {
                    remoteCache = p
                    try { p.registerOnSharedPreferenceChangeListener(listener) } catch (ignored: Throwable) {}
                }
                return p
            }
        }

        /**
         * 读路径使用的 prefs，同时**定案** {@link #backend}。
         *
         * <p>内置版：本地优先（remote 在 LSPatch 上不可靠）。
         * 模块版：远程优先（LSPosed 用户的配置都在 remote 里），拿不到才退本地。
         *
         * <p>★ 两个热路径约束：
         * <ul>
         *   <li>定案后走 {@link #resolved} 的 volatile 读，**不再做任何反射**；</li>
         *   <li>拿不到时**不永久定案**，但 500ms 内不再重试 —— {@link #visible(String, boolean)}
         *       是每帧都调的热路径，不能每次都跑一遍宿主 Context 四级探测。</li>
         * </ul>
         */
        /**
         * 读路径使用的 prefs，同时**定案** {@link #backend}。
         *
         * <p>内置版：本地优先（remote 在 LSPatch 上不可靠）。
         * 模块版：远程优先（LSPosed 用户的配置都在 remote 里），拿不到才退本地。
         *
         * <p>★ 两个热路径约束：
         * <ul>
         *   <li>定案后走 {@link #resolved} 的 volatile 读，**不再做任何反射**；</li>
         *   <li>拿不到时**不永久定案**，但 500ms 内不再重试 —— {@link #visible(String, boolean)}
         *       是每帧都调的热路径，不能每次都跑一遍宿主 Context 四级探测。</li>
         * </ul>
         *
         * <p>★★ 2.3.0 真正修掉「重启后配置恢复原样」（第三次反馈）：
         * <b>只有首选后端才允许写进 {@link #resolved}</b>，回落后端只借用一次。
         *
         * <p>为什么：hook 是在 {@code onPackageLoaded} 装上的，那时宿主 {@code Application}
         * 还没创建 —— {@code HostPrefs.hostContext()} 四条路全空 ⇒ {@link #local()} 为 null。
         * 老代码 `pick = l != null ? l : r` 于是拿 LSPatch 的只读 remote 顶上，并且**把它定案**。
         * 那个 remote 里只有 3 个键（{@code map_poi} / {@code _map_label_schema} / token），
         * 用户真正的 44 个值全在 {@code /data/data/com.baidu.BaiduMap/shared_prefs/bmapclean_config.xml}。
         * 从那一刻起整个进程都读 remote ⇒ 每个键 {@code present=false} ⇒ 全部落
         * {@link Spec#defaultVisible} = true ⇒ 「关掉重启又自己开了」。
         *
         * <p>真机取证（修复前，pid=9994 主进程）：
         * <pre>
         * event=cfg_resolve embedded=true local=null remote=VectorRemotePreferences backend=2
         * event=cfg_snap    n=3 backend=2
         * event=cfg_r key=tab_打车 v=true present=false n=3 backend=2
         * </pre>
         */
        @JvmStatic
        fun prefs(): SharedPreferences? {
            val p = resolved
            if (p != null) return p
            val now = android.os.SystemClock.uptimeMillis()
            if (resolveAt != 0L && now - resolveAt < RESOLVE_RETRY_MS) return fallback
            synchronized(Cfg::class.java) {
                if (resolved != null) return resolved
                if (resolveAt != 0L && now - resolveAt < RESOLVE_RETRY_MS) return fallback
                resolveAt = now

                val embedded = embedded()
                val l = local()
                val r = remote()
                // 首选 / 回落：内置版本地优先，模块版远程优先。
                val preferred = if (embedded) l else r
                val spare = if (embedded) r else l
                val preferredBackend = if (embedded) BACKEND_LOCAL else BACKEND_REMOTE
                val spareBackend = if (embedded) BACKEND_REMOTE else BACKEND_LOCAL

                if (preferred != null) {
                    val wasFallback = fallback != null
                    resolved = preferred
                    backendState = preferredBackend
                    fallback = null
                    // 从「回落态」升级到首选后端：快照和 Flag 全是照回落态算的，必须一起作废，
                    // 否则安装期门控会把「读不到」的缺省 true 一直留到进程结束。
                    if (wasFallback) {
                        expires = 0
                        refreshFlags()
                        H.log("event=cfg_backend_upgraded pid=" + android.os.Process.myPid()
                                + " backend=" + backendState)
                    }
                    if (RES_LOGGED.compareAndSet(false, true)) {
                        H.log("event=cfg_resolve pid=" + android.os.Process.myPid()
                                + " embedded=" + embedded
                                + " local=" + (if (l == null) "null" else l.javaClass.name)
                                + " remote=" + (if (r == null) "null" else r.javaClass.name)
                                + " pick=" + preferred.javaClass.name
                                + " backend=" + backendState)
                    }
                    return preferred
                }

                // 首选后端还没就绪（典型：内置版启动瞬间宿主 Context 未创建）。
                // 借用回落后端读这一次，但**绝不写进 resolved** —— 下次调用必须重新探测。
                fallback = spare
                backendState = if (spare != null) spareBackend else -1
                if (RES_LOGGED.compareAndSet(false, true)) {
                    H.log("event=cfg_resolve pid=" + android.os.Process.myPid()
                            + " embedded=" + embedded
                            + " local=" + (if (l == null) "null" else l.javaClass.name)
                            + " remote=" + (if (r == null) "null" else r.javaClass.name)
                            + " pick=" + (if (spare == null) "null" else spare.javaClass.name)
                            + " backend=" + backendState)
                }
                return spare
            }
        }

        /** 每个进程只打一条「后端定案」。 */
        private val RES_LOGGED = java.util.concurrent.atomic.AtomicBoolean()
        /** 快照重建的日志预算（每进程 12 条，够看启动期与一次写后的重读）。 */
        private val SNAP_LOG = java.util.concurrent.atomic.AtomicInteger()
        /** 每键首次读取的日志预算（每进程 48 条）。 */
        private val READ_LOG = java.util.concurrent.atomic.AtomicInteger()
        /** 已打过「首次读取」的键。 */
        private val READ_SEEN: MutableSet<String> =
                java.util.Collections.synchronizedSet(java.util.HashSet<String>())

        /** 已定案的读后端（定案后零反射）。 */
        @Volatile
        private var resolved: SharedPreferences? = null

        /**
         * 首选后端未就绪时的**临时**后端（内置版=remote，模块版=local）。
         *
         * <p>只做「这一次读」的替身，不进 {@link #resolved}：首选后端一出现就立刻接管，
         * 并在接管时作废快照 + 重算 Flag。
         */
        @Volatile
        private var fallback: SharedPreferences? = null
        /** 上次尝试定案的时间（0 = 还没试过）。 */
        @Volatile
        private var resolveAt = 0L

        /** 定案失败后的重试间隔：500ms 内不再跑宿主 Context 四级探测。 */
        private const val RESOLVE_RETRY_MS = 500L

        /** 配置来源变了（换宿主 / 刚绑定服务）：丢掉定案，下次重新选后端。 */
        @JvmStatic
        fun resetBackend() {
            synchronized(Cfg::class.java) {
                resolved = null
                fallback = null
                backendState = -1
                resolveAt = 0
                expires = 0
            }
        }

        /**
         * 写一个布尔键。<b>内置版以宿主本地为准</b>，remote 只做 best-effort 镜像；
         * 模块版反过来。返回「至少一个后端 commit 成功」。
         *
         * <p>这就是「开关拨了不生效」的修法：从前 hook 侧一律不写（只读代理），
         * 面板侧写 remote 又被 LSPatch 的坏库吞掉。现在写的是宿主自己的 SharedPreferences，
         * 与读路径是同一个文件。
         */
        /** 写入诊断计数（每进程前 60 条），超了就不再打。 */
        private val WLOG = java.util.concurrent.atomic.AtomicInteger()

        @JvmStatic
        fun write(key: String, value: Boolean): Boolean {
            val embedded = embedded()
            val okLocal = commitBool(local(), key, value)
            val okRemote = commitBool(remote(), key, value)
            var ok = if (embedded) okLocal else (okRemote || okLocal)
            invalidate(key)

            // ★★ 回读自校验（最后一道保险）。
            // 用户反馈的「关掉切个页面又自己开了」本质是：
            // **commit 返回了 true，但下一次读不回来**。
            // 所以不能信返回值，必须用与读取完全相同的路径再读一次。
            // 不一致 ⇒ 强制改写宿主本地（永远可写）并标记成功。
            try {
                val rp = prefs()
                if (rp != null) {
                    val back: Any? = rp.all[key]
                    val got = if (back is Boolean) back else !value
                    if (got != value) {
                        if (commitBool(local(), key, value)) {
                            invalidate(key)
                            ok = true
                            H.log("event=cfg_write_recover key=" + key + " via=local")
                        } else {
                            H.log(android.util.Log.WARN, MainHook.TAG,
                                    "event=cfg_write_lost key=" + key + " embedded=" + embedded
                                            + " local=" + okLocal + " remote=" + okRemote)
                        }
                    }
                }
            } catch (ignored: Throwable) { }

            // ★ 应急诊断（用户反馈「重启后没保存」但文件里值是对的 ⇒ 必须看到
            // **写的时候到底 ok 不 ok、走的哪个后端**。这条日志不受 debug_log 控制，
            // 否则用户拿不到证据。限 60 条防刷屏。
            try {
                if (WLOG.incrementAndGet() <= 60) {
                    H.log("event=cfg_w key=" + key + " v=" + value + " ok=" + ok
                            + " backend=" + backendState + " local=" + okLocal + " remote=" + okRemote)
                }
            } catch (ignored: Throwable) { }
            return ok
        }

        /** 写一个 int 键（语义同 {@link #write(String, boolean)}）。 */
        @JvmStatic
        fun writeInt(key: String, value: Int): Boolean {
            val embedded = embedded()
            val okLocal = commitInt(local(), key, value)
            val okRemote = commitInt(remote(), key, value)
            val ok = if (embedded) okLocal else (okRemote || okLocal)
            invalidate(key)
            return ok
        }

        private fun commitBool(p: SharedPreferences?, key: String, value: Boolean): Boolean {
            if (p == null) return false
            return try {
                val editor = p.edit().putBoolean(key, value)
                // An enabled child must not remain silently gated by a disabled parent.
                if (value && Spec.isCommuteChild(key)) editor.putBoolean(Spec.K_HC, true)
                if (value && Spec.isMineChild(key)) editor.putBoolean(Spec.K_MINE_APPLY, true)
                editor.commit()
            } catch (t: Throwable) {
                false
            }
        }

        private fun commitInt(p: SharedPreferences?, key: String, value: Int): Boolean {
            if (p == null) return false
            return try {
                p.edit().putInt(key, value).commit()
            } catch (t: Throwable) {
                false
            }
        }

        /** 写一个字符串键（目前只有防伪 token 用）。语义同 {@link #write(String, boolean)}。 */
        @JvmStatic
        fun writeString(key: String, value: String): Boolean {
            val embedded = embedded()
            val okLocal = commitString(local(), key, value)
            val okRemote = commitString(remote(), key, value)
            val ok = if (embedded) okLocal else (okRemote || okLocal)
            invalidate(key)
            return ok
        }

        private fun commitString(p: SharedPreferences?, key: String, value: String): Boolean {
            if (p == null) return false
            return try {
                p.edit().putString(key, value).commit()
            } catch (t: Throwable) {
                false
            }
        }

        /** 缺省 true = 显示 */
        @JvmStatic
        fun visible(key: String): Boolean {
            return visible(key, true)
        }

        /** 缺省值由调用方指定（用于默认「关」的键，例如 mine_apply） */
        @JvmStatic
        fun visible(key: String, def: Boolean): Boolean {
            try {
                val p = prefs()
                if (p == null) {
                    traceRead(key, def, false, -1)
                    return def
                }
                val all = values()
                val value = all[key]
                val v = if (value is Boolean) value else def
                traceRead(key, v, value is Boolean, all.size)
                return v
            } catch (t: Throwable) {
                return def
            }
        }

        /**
         * 每键「首次读取」留一条日志（每进程 ≤48 条）。
         *
         * <p>为什么要它：{@code cfg_w} 已经证明「写进去了」，但「读回来的是不是那个值」从来没有证据。
         * 这条日志把三个数放在一起 —— 读到的值、键在快照里存不存在、快照有几个键 ——
         * 一眼就能分开三种完全不同的故障：
         * <ul>
         *   <li>{@code present=false} ⇒ 读的后端是空的（remote 坏库 / 后端定错了）；</li>
         *   <li>{@code present=true v=true} 但文件里是 false ⇒ 快照太旧或写错了文件；</li>
         *   <li>{@code n=-1} ⇒ {@code prefs()} 直接拿不到后端，读的是失效安全缺省。</li>
         * </ul>
         */
        private fun traceRead(key: String, v: Boolean, present: Boolean, n: Int) {
            if (READ_LOG.get() >= 48) return
            if (!READ_SEEN.add(key)) return
            if (READ_LOG.incrementAndGet() > 48) return
            H.log("event=cfg_r pid=" + android.os.Process.myPid()
                    + " key=" + key + " v=" + v + " present=" + present
                    + " n=" + n + " backend=" + backendState)
        }

        /** 热点路径 2 秒 TTL 缓存，避免每帧都走一次 IPC */
        @JvmStatic
        fun debug(): Boolean {
            if (FORCE_DEBUG) return true
            val now = System.currentTimeMillis()
            if (now - dbgAt > 2000) {
                var v = false
                try {
                    val p = prefs()
                    v = p != null && p.getBoolean(K_DEBUG, false)
                } catch (ignored: Throwable) {}
                dbgVal = v
                dbgAt = now
            }
            return dbgVal
        }

        /**
         * 设置页写的防伪 token（App 侧可写、hook 侧只读 —— 见 {@link Report} 的说明）。
         * hook 侧回报时带上它，设置页据此过滤伪造广播。
         */
        @JvmStatic
        fun token(): String {
            try {
                val p = prefs()
                if (p == null) return ""
                val value = values()["app_token"]
                val s: String? = if (value is String) value else ""
                return s ?: ""
            } catch (t: Throwable) {
                return ""
            }
        }

        /**
         * 说明：libxposed API 102 在 hook 侧返回的 RemotePreferences 是**只读**实现，
         * 真机实测 `edit().commit()` 抛 UnsupportedOperationException: Read only implementation。
         * 因此 hook 侧一律不回写配置；诊断结果走 {@link Report} 的广播通道。
         */
        @JvmStatic
        fun log(s: String) {
            H.log(Log.INFO, MainHook.TAG, s)
        }

        private fun values(): Map<String, *> {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= expires) {
                synchronized(Cfg::class.java) {
                    if (now >= expires) {
                        val p = prefs()
                        if (p != null) try {
                            val copy = java.util.HashMap<String, Any?>(p.all)
                            snapshot = java.util.Collections.unmodifiableMap(copy)
                            if (SNAP_LOG.get() < 12) {
                                SNAP_LOG.incrementAndGet()
                                H.log("event=cfg_snap pid=" + android.os.Process.myPid()
                                        + " n=" + snapshot.size + " backend=" + backendState
                                        + " src=" + p.javaClass.name)
                            }
                        } catch (ignored: Throwable) {}
                        expires = now + 2000
                    }
                }
            }
            return snapshot
        }
    }
}
