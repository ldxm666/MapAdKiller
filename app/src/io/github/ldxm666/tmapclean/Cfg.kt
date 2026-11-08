package io.github.ldxm666.tmapclean

import android.content.SharedPreferences
import android.util.Log

/**
 * 腾讯地图 Hook 侧配置读取。
 *
 * <p>存储 = LSPosed RemotePreferences（group [GROUP]，与模块设置页共用同一张表）。
 *
 * <p><b>失效安全</b>：任何异常 / 缺省一律返回 <b>true（可见）</b>。
 * 也就是说模块挂不上、配置读不到、腾讯改版导致规则全 miss 时，
 * 腾讯地图的界面保持原样，不会出现"被清空到没法用"。
 */
class Cfg private constructor() {

    companion object {

        /** RemotePreferences group 名（App 侧设置页用同一个） */
        const val GROUP = "tmapclean_config"

        /** 诊断日志开关（唯一默认 false 的键） */
        const val K_DEBUG = "debug_log"

        /** 真机取证期临时强制开日志。交付版必须为 false。 */
        const val FORCE_DEBUG = false

        /** {@code synchronized (Cfg.class)} 的锁对象（所有同步块共用一个监视器）。 */
        private val LOCK = Any()

        /**
         * 宿主本地后端（{@code /data/data/com.tencent.map/shared_prefs/tmapclean_config.xml}）。
         *
         * <p>2.3.0 新增，与百度侧同因同治：2.2.8 及以前只有
         * {@code H.module.getRemotePreferences()} 一条路 —— hook 侧是只读代理
         * （{@code commit()} 抛 {@code UnsupportedOperationException}），内置版（LSPatch）的 remote
         * 库又会 {@code SQLiteCantOpenDatabaseException}（{@code commit()} 返回 true 但读不回来）。
         * 结果就是面板恒显「只读 · 服务未连接」、每个开关都写不进去。
         */
        @Volatile
        private var localCache: SharedPreferences? = null

        @Volatile
        private var remoteCache: SharedPreferences? = null

        @Volatile
        private var resolved: SharedPreferences? = null

        @Volatile
        private var resolveAt: Long = 0L

        @Volatile
        private var backend: Int = -1

        /**
         * 本进程是否处于**免 root 内置版**。
         *
         * <p>★ 2.3.0 修复「内置版配置不保存」。判据用
         * <b>框架名</b> {@code XposedModule#getFrameworkName()} —— LSPatch 会报 {@code LSPatch}，
         * LSPosed 会报 {@code LSPosed}/类似名。这是两种形态**唯一可靠的区分点**：
         * <ul>
         *   <li>{@code HostInfo.isEmbedded()} 在百度变体里是**桩**，早期返回 false；</li>
         *   <li>{@code mapclean.App.embedded()} 要等 {@code attachEmbedded} 之后才为 true，
         *       而面板第一次写配置就发生在那之前；</li>
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
                val module = H.module
                if (module != null) {
                    val name: String? = module.getFrameworkName()
                    val fwText = name ?: "null"
                    fw = fwText
                    v = fwText.lowercase(java.util.Locale.US).contains("lspatch")
                }
            } catch (ignored: Throwable) {
            }
            if (!v) {
                try {
                    v = io.github.ldxm666.mapclean.HostPrefs.embedded()
                } catch (ignored: Throwable) {
                }
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
        private var embState: Int = -1

        /** 无可用后端（宿主 Context 还没就绪，稍后重试）。 */
        const val BACKEND_NONE = 0

        /** 宿主本地 SharedPreferences（内置版的正常状态）。 */
        const val BACKEND_LOCAL = 1

        /** 框架远程 prefs（LSPosed 模块版）。 */
        const val BACKEND_REMOTE = 2

        /** 定案失败后的重试间隔（{@code visible()} 是热路径，不能每帧跑一遍反射）。 */
        private const val RESOLVE_RETRY_MS = 500L

        @Volatile
        private var snapshot: Map<String, *> = java.util.Collections.emptyMap<String, Any>()

        @Volatile
        private var expires: Long = 0L

        @Volatile
        private var dbgAt: Long = 0L

        @Volatile
        private var dbgVal: Boolean = false

        private val listener: SharedPreferences.OnSharedPreferenceChangeListener =
            object : SharedPreferences.OnSharedPreferenceChangeListener {
                override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
                    invalidate()
                    MainHook.onConfigChanged(key)
                }
            }

        @JvmStatic
        fun invalidate() {
            expires = 0
            dbgAt = 0
            // ★★ 2.3.0：也要丢掉「已定案的读后端」。
            // 否则会出现：写进了宿主本地（{@code cfg_write_recover via=local}），
            // 但面板重渲染时 [prefs] 还返回当初定案的那个 remote，
            // 于是读出来还是旧值 ⇒ 用户看到「关掉又自己开了」。
            resetBackend()
        }

        /** 当前配置后端：本地 / 远程 / 无。 */
        @JvmStatic
        fun backend(): Int {
            prefs()
            val b = backend
            return if (b < 0) BACKEND_NONE else b
        }

        /** 宿主本地 prefs（永久缓存）。 */
        private fun local(): SharedPreferences? {
            val cached = localCache
            if (cached != null) return cached
            synchronized(LOCK) {
                val raced = localCache
                if (raced != null) return raced
                val created = io.github.ldxm666.mapclean.HostPrefs.local(GROUP)
                if (created != null) {
                    localCache = created
                    try {
                        created.registerOnSharedPreferenceChangeListener(listener)
                    } catch (ignored: Throwable) {
                    }
                }
                return created
            }
        }

        /**
         * 框架远程 prefs。
         *
         * <p>⚠ <b>不缓存 null 的失败态</b>：2.2.8 以前把 {@code null} 永久写进 {@code cached}，
         * 只要首次调用赶在框架初始化之前，整个进程就再也拿不到配置。
         */
        private fun remote(): SharedPreferences? {
            val cached = remoteCache
            if (cached != null) return cached
            synchronized(LOCK) {
                val raced = remoteCache
                if (raced != null) return raced
                var created: SharedPreferences? = null
                try {
                    val module = H.module
                    created = if (module == null) null else module.getRemotePreferences(GROUP)
                } catch (t: Throwable) {
                    created = null
                }
                val p = created
                if (p != null) {
                    remoteCache = p
                    try {
                        p.registerOnSharedPreferenceChangeListener(listener)
                    } catch (ignored: Throwable) {
                    }
                }
                return p
            }
        }

        /**
         * 读路径使用的 prefs，同时定案 [backend]。
         *
         * <p>内置版本地优先，模块版远程优先；两个都拿不到不永久定案，500ms 后重试。
         */
        /**
         * 读路径使用的 prefs，同时定案 [backend]。
         *
         * <p>★★ 2.3.0：**只有首选后端才允许写进 [resolved]**，
         * 回落后端只借用一次。
         *
         * <p>这是百度侧同一个 bug 的逐字同源修复（已在百度内置版上真机验证）：
         * hook 是在 {@code onPackageLoaded} 装上的，那时宿主 {@code Application} 还没创建
         * ⇒ {@code HostPrefs.hostContext()} 四条路全空 ⇒ [local] 为 null。
         * 旧代码于是拿 LSPatch 的只读 remote 顶上并**把它定案**；
         * 那份 remote 只有几个键，用户的真值全在
         * {@code /data/data/com.tencent.map/shared_prefs/tmapclean_config.xml} ⇒
         * 从那一刻起整个进程每个键 {@code present=false} ⇒ 全部落缺省 true
         * ⇒「关掉重启又自己开了」。
         *
         * <p>腾讯内置版必然复现，所以这里提前修掉。
         */
        @JvmStatic
        fun prefs(): SharedPreferences? {
            val p = resolved
            if (p != null) return p
            val now = android.os.SystemClock.uptimeMillis()
            if (resolveAt != 0L && now - resolveAt < RESOLVE_RETRY_MS) return fallback
            synchronized(LOCK) {
                val raced = resolved
                if (raced != null) return raced
                if (resolveAt != 0L && now - resolveAt < RESOLVE_RETRY_MS) return fallback
                resolveAt = now

                val embedded = embedded()
                val l = local()
                val r = remote()
                val preferred = if (embedded) l else r
                val spare = if (embedded) r else l
                val preferredBackend = if (embedded) BACKEND_LOCAL else BACKEND_REMOTE
                val spareBackend = if (embedded) BACKEND_REMOTE else BACKEND_LOCAL

                if (preferred != null) {
                    val wasFallback = fallback != null
                    resolved = preferred
                    backend = preferredBackend
                    fallback = null
                    // 从「回落态」升级到首选后端：快照是照回落态算的，必须一起作废。
                    if (wasFallback) {
                        expires = 0
                        H.log(
                            "event=cfg_backend_upgraded pid=" + android.os.Process.myPid()
                                    + " backend=" + backend
                        )
                    }
                    return preferred
                }

                // 首选后端还没就绪（典型：内置版启动瞬间宿主 Context 未创建）。
                // 借用回落后端读这一次，但**绝不写进 resolved**。
                fallback = spare
                backend = if (spare != null) spareBackend else -1
                return spare
            }
        }

        /** 回落后端的临时引用：只借读，不定案。 */
        @Volatile
        private var fallback: SharedPreferences? = null

        /** 配置来源变了：丢掉定案，下次重新选后端。 */
        @JvmStatic
        fun resetBackend() {
            synchronized(LOCK) {
                resolved = null
                fallback = null
                backend = -1
                resolveAt = 0
                expires = 0
            }
        }

        /**
         * 写一个布尔键。内置版以宿主本地为准，remote 只做 best-effort 镜像；模块版反过来。
         *
         * @return 「至少一个后端 commit 成功」
         */
        @JvmStatic
        fun write(key: String, value: Boolean): Boolean {
            val embedded = embedded()
            val okLocal = commitBool(local(), key, value)
            val okRemote = commitBool(remote(), key, value)
            var ok = if (embedded) okLocal else (okRemote || okLocal)
            invalidate()

            // ★★ 回读自校验（最后一道保险）。
            // 用户反馈的「关掉切个页面又自己开了」本质是：
            // **commit 返回了 true，但下一次读不回来**。
            // 所以不能信返回值，必须用与读取完全相同的路径再读一次。
            // 不一致 ⇒ 强制改写宿主本地（永远可写）并标记成功。
            try {
                val rp = prefs()
                if (rp != null) {
                    val back = rp.all[key]
                    val got = if (back is Boolean) back else !value
                    if (got != value) {
                        if (commitBool(local(), key, value)) {
                            invalidate()
                            ok = true
                            H.log("event=cfg_write_recover key=" + key + " via=local")
                        } else {
                            H.log(
                                Log.WARN, MainHook.TAG,
                                "event=cfg_write_lost key=" + key + " embedded=" + embedded
                                        + " local=" + okLocal + " remote=" + okRemote
                            )
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
            if (ok) MainHook.onConfigChanged(key)
            return ok
        }

        private fun commitBool(p: SharedPreferences?, key: String, value: Boolean): Boolean {
            if (p == null) return false
            try {
                return p.edit().putBoolean(key, value).commit()
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * 缺省值走 [Spec.defaultVisible] —— 与设置页、内置 JSON 同源。
         *
         * <p>★ 2.3.1：以前这里写死 {@code true}。腾讯那份 JSON 里 {@code tab_*} 根本不存在、
         * {@code ui_seek_chips} 等又是 {@code null}（不写），于是干净安装的所有键都 absent
         * ⇒ 全部回落到 {@code true} ⇒ 底栏四个 tab、首页 chip 行原样保留。
         * 现在「没设置过」不再等于「显示」：推广/运营位按 Spec 的默认表隐藏，功能位照旧显示。
         *
         * <p>循环依赖检查：[Spec.defaultVisible] 只引用 {@code Cfg.K_DEBUG} 这类
         * <b>编译期字符串常量</b>，不回调 {@code visible()}，因此不存在递归。
         */
        @JvmStatic
        fun visible(key: String?): Boolean {
            return visible(key, Spec.defaultVisible(key))
        }

        @JvmStatic
        fun visible(key: String?, def: Boolean): Boolean {
            if (key == null) return def
            try {
                if (prefs() == null) return def
                val value = values()[key]
                return if (value is Boolean) value else def
            } catch (t: Throwable) {
                return def
            }
        }

        /**
         * 是否显示某个工具（工具 id = 服务端下发的 {@code Tool.name}）。
         *
         * <p>用户显式设置过的按用户的来；<b>没设置过的走 [Spec.defaultToolVisible]</b>
         * —— 首页宫格那 15 格默认显示，其余默认隐藏。
         *
         * <p>⚠ 这里以前写死 {@code return true}（"未设置一律显示"），与面板侧的缺省脱节：
         * 面板要表达"只有首页那几格是开的"就无从表达。两处缺省现在同源。
         *
         * <p>失效安全：{@code filterData()} 只对**认得出 id** 的格子问这个问题，
         * 取不到名字的格子直接保留；另外它还有"清空即原样放行"的兜底。
         */
        @JvmStatic
        fun toolVisible(toolId: String?): Boolean {
            if (toolId == null || toolId.length == 0) return true   // 认不出 → 保留
            val value = values()[Spec.K_TOOL + toolId]
            if (value is Boolean) return value
            return Spec.defaultToolVisible(toolId)
        }

        /**
         * 该键是否被用户<b>显式</b>设置过（区别于"按缺省值"）。
         *
         * <p>给"默认隐藏会不会把宫格清空"这道兜底用：只有用户真的逐格关过，
         * 才允许宫格被清空。
         */
        @JvmStatic
        fun explicit(key: String?): Boolean {
            if (key == null) return false
            try {
                return values().containsKey(key)
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * 是否显示某个底部标签（页名，例如 homePage）；总开关由调用方另行判断。
         *
         * <p>★ 2.3.1：缺省值改走 [Spec.defaultVisible]。以前写死 {@code true}，
         * 与面板的默认值脱节 —— 面板显示「探索/行程」是关的，钩子却按开处理（或反过来）。
         */
        @JvmStatic
        fun tabVisible(pageName: String): Boolean {
            // A new host page has no supported switch yet. Preserve it rather than
            // applying the default-OFF policy intended for our known optional tabs.
            if (Spec.TABS.none { it[0] == pageName }) return true
            return visible(Spec.K_TAB + pageName)
        }

        /** 热点路径 2 秒 TTL 缓存，避免每帧都走一次 IPC */
        @JvmStatic
        fun debug(): Boolean {
            if (FORCE_DEBUG) return true
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - dbgAt > 2000) {
                var v = false
                try {
                    v = java.lang.Boolean.TRUE == values()[K_DEBUG]
                } catch (ignored: Throwable) {
                }
                dbgVal = v
                dbgAt = now
            }
            return dbgVal
        }

        /**
         * 设置页写入的防伪 token（App 侧可写、hook 侧只读 —— 见 [Report]）。
         * hook 侧回报时带上它，设置页据此过滤伪造广播。
         */
        @JvmStatic
        fun token(): String {
            try {
                val value = values()["app_token"]
                return if (value is String) value else ""
            } catch (t: Throwable) {
                return ""
            }
        }

        /**
         * 说明：libxposed API 102 在 hook 侧返回的 RemotePreferences 是<b>只读</b>实现，
         * 真机实测 {@code edit().commit()} 抛 UnsupportedOperationException: Read only implementation。
         * 因此 hook 侧一律不回写配置；诊断与「学到的工具清单」走 [Report] 的广播通道。
         */
        @JvmStatic
        fun log(s: String) {
            H.log(Log.INFO, MainHook.TAG, s)
        }

        private fun values(): Map<String, *> {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= expires) {
                synchronized(LOCK) {
                    if (now >= expires) {
                        val p = prefs()
                        if (p != null) {
                            try {
                                snapshot = java.util.Collections.unmodifiableMap(
                                    java.util.HashMap<String, Any?>(p.all)
                                )
                            } catch (ignored: Throwable) {
                            }
                        }
                        expires = now + 2000
                    }
                }
            }
            return snapshot
        }
    }
}
