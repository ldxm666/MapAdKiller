package io.github.ldxm666.mapclean

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read/write access to the hook-side configuration from inside the host (map) process.
 *
 * <h3>Why two paths</h3>
 * In a normal LSPosed install the module's own App process holds the writable
 * `RemotePreferences`, and the hook process only gets a read-only view. In an LSPatch
 * <i>integrated</i> build there is no separate module process at all: the patcher hands the
 * host a writable Binder, which arrives as an [XposedService] via
 * `XposedServiceHelper`.
 *
 * <p>So writes try, in order:
 * <ol>
 *   <li>the embedded service (present in the integrated build),</li>
 *   <li>the hook-side preferences (writable in frameworks that expose it).</li>
 * </ol>
 * Reads always use the same snapshot the hooks read, so the panel can never disagree with
 * what the hooks actually enforce.
 *
 * <h3>Which vendor</h3>
 * Decided by [Vendor]: the orb is drawn inside the host, so it can only ever be
 * configuring the map it is running in. The per-vendor details (group name, default for a
 * given key, whether a write needs pushing into a live view tree) live in [AmapPanel],
 * [BaiduPanel] and [TmapPanel].
 */
class FloatingConfig private constructor() {

    companion object {

        /**
         * The 悬浮窗 master switch: true = the orb and its sheet are attached, false = no overlay.
         *
         * <p>Deliberately <b>not</b> in `mapadkiller.Config` / `bmapclean.Spec`: this
         * switch belongs to [FloatingController], which every variant keeps, and putting it
         * here means all three vendors read the same key through the same [read] /
         * [write] pair without touching a vendor catalogue.
         *
         * <p>Default is <b>on</b>: an unset key must keep the orb, because in the 免 root build the
         * orb is the only way into the settings sheet. All three vendor default tables fall through
         * to `true` for an unknown key, so no per-vendor edit is needed -- see the note on
         * [floatEnabled].
         */
        const val K_FLOAT_ENABLED = "float_enabled"

        /* ---------------------------------------------------------------- 悬浮窗外观 */

        /**
         * Appearance of the orb and the sheet, adjustable from the sheet's 外观 page.
         *
         * <h3>Why these are ints, and why they need their own channel</h3>
         * [read] / [write] are the <b>switch</b> channel:
         * every vendor panel resolves a missing boolean through its own `defaultVisible`
         * table, which is exactly right for on/off rows and useless for a size. These four keys
         * are therefore stored as ints and read through [readInt] / [writeInt],
         * which never touch that table.
         *
         * <h3>Defaults are the pre-2.2.2 behaviour</h3>
         * An unset key must reproduce what shipped before this page existed: a
         * [BALL_SIZE_DEFAULT] orb, fully opaque, and a sheet that spans the screen. A user
         * who never opens the page must not see the orb change after upgrading.
         */
        const val K_FLOAT_BALL_SIZE = "float_ball_size"
        const val K_FLOAT_BALL_ALPHA = "float_ball_alpha"
        const val K_FLOAT_PANEL_WIDTH = "float_panel_width"
        const val K_FLOAT_PANEL_ALPHA = "float_panel_alpha"

        /** Orb diameter in dp. 36dp still leaves a 52dp tap target with the 8dp pad. */
        const val BALL_SIZE_MIN = 36
        const val BALL_SIZE_MAX = 72
        const val BALL_SIZE_DEFAULT = 46

        /** Orb opacity in percent. Below ~30% the parked grab tab stops reading as a control. */
        const val BALL_ALPHA_MIN = 30
        const val BALL_ALPHA_MAX = 100
        const val BALL_ALPHA_DEFAULT = 100

        /** Sheet width in dp; `0` is the "自动" stop = full width, the pre-2.2.2 look. */
        const val PANEL_WIDTH_AUTO = 0
        const val PANEL_WIDTH_MIN = 280
        const val PANEL_WIDTH_MAX = 420
        const val PANEL_WIDTH_DEFAULT = 0

        /** Sheet plate opacity in percent; the scrim behind it is deliberately not affected. */
        const val PANEL_ALPHA_MIN = 60
        const val PANEL_ALPHA_MAX = 100
        const val PANEL_ALPHA_DEFAULT = 100

        /**
         * Preference group for the appearance keys on the vendors whose panel has no int channel
         * (百度 / 腾讯). 高德 keeps them in its own `amap_enhancer_config` group through
         * [AmapPanel], i.e. the same file its switch rows already write.
         *
         * <p><b>未确认</b>: this path is untested on a 百度 / 腾讯 device -- this build only ships 高德.
         */
        private const val FLOAT_GROUP = "mapclean_float"

        /** Orb diameter in dp, clamped to the range the sheet can show. */
        @JvmStatic
        fun ballSizeDp(): Int {
            return clamp(readInt(K_FLOAT_BALL_SIZE, BALL_SIZE_DEFAULT), BALL_SIZE_MIN, BALL_SIZE_MAX)
        }

        /** Orb opacity in percent, clamped. */
        @JvmStatic
        fun ballAlphaPercent(): Int {
            return clamp(
                readInt(K_FLOAT_BALL_ALPHA, BALL_ALPHA_DEFAULT),
                BALL_ALPHA_MIN, BALL_ALPHA_MAX
            )
        }

        /** Sheet width in dp; [PANEL_WIDTH_AUTO] means "follow the screen". */
        @JvmStatic
        fun panelWidthDp(): Int {
            val stored = readInt(K_FLOAT_PANEL_WIDTH, PANEL_WIDTH_DEFAULT)
            if (stored <= PANEL_WIDTH_AUTO) return PANEL_WIDTH_AUTO
            return clamp(stored, PANEL_WIDTH_MIN, PANEL_WIDTH_MAX)
        }

        /** Sheet plate opacity in percent, clamped. */
        @JvmStatic
        fun panelAlphaPercent(): Int {
            return clamp(
                readInt(K_FLOAT_PANEL_ALPHA, PANEL_ALPHA_DEFAULT),
                PANEL_ALPHA_MIN, PANEL_ALPHA_MAX
            )
        }

        private fun clamp(value: Int, min: Int, max: Int): Int {
            return if (value < min) min else (if (value > max) max else value)
        }

        /**
         * Read one int-valued key.
         *
         * <p>Deliberately <b>not</b> routed through [read]: that one
         * answers "is this switch on" and would turn a stored `46` into `true`.
         * Every failure path returns `fallback`, because a preferences read that throws
         * (service not bound yet, group missing) must degrade to the default orb rather than
         * crash `FloatingBall`'s constructor.
         */
        @JvmStatic
        fun readInt(key: String, fallback: Int): Int {
            try {
                if (Vendor.tmap() || Vendor.baidu()) return readIntViaService(key, fallback)
                return AmapPanel.readInt(key, fallback)
            } catch (t: Throwable) {
                return fallback
            }
        }

        /**
         * Persist one int-valued key. `commit()`, not `apply()`: the user can leave
         * the map the instant the finger lifts off the slider.
         *
         * <p>Unlike the switch channel this does <b>not</b> run a vendor "apply" step: no hook
         * reads these keys, so pushing them into the live view tree would only cost a native
         * refresh on every slider release.
         */
        @JvmStatic
        fun writeInt(key: String, value: Int): Boolean {
            try {
                if (Vendor.tmap() || Vendor.baidu()) return writeIntViaService(key, value)
                return AmapPanel.writeInt(key, value)
            } catch (t: Throwable) {
                return false
            }
        }

        private fun readIntViaService(key: String, fallback: Int): Int {
            val service = App.service() ?: return fallback
            return service.getRemotePreferences(FLOAT_GROUP).getInt(key, fallback)
        }

        private fun writeIntViaService(key: String, value: Int): Boolean {
            val service = App.service() ?: return false
            return service.getRemotePreferences(FLOAT_GROUP).edit().putInt(key, value).commit()
        }

        /**
         * The 悬浮窗 switch, read the way the controller reads it.
         *
         * <p>Verified per vendor: 高德 `Config.defaultVisible`, 百度
         * `Spec.defaultVisible` and 腾讯 `Spec.defaultVisible` all return `true`
         * for a key they do not know, so `float_enabled` defaults to on everywhere. The
         * `true` fallback below covers the "preferences not reachable yet" case, which must
         * also mean "keep the orb" rather than "silently lose the only entry point".
         */
        /**
         * 悬浮窗是否属于「免 root 内置版」。
         *
         * <p>产品要求：**悬浮窗是免 root 用户专属**。模块版（LSPosed / root 用户）应该用模块 App
         * 的设置页，默认不往高德里挂球 —— 但功能不删，用户在面板里显式打开即可。
         *
         * <p>判据只看 `App.isEmbedded()`（宿主 APK 内是否有 `assets/lspatch/config.json`），
         * **不看**"有没有 root" —— 那既判断不了也不可靠。该方法由 `amap-core2` 提供，
         * 这里用反射调用：两种构建都能编译，拿不到就按模块版处理。
         */
        @JvmStatic
        fun isEmbedded(): Boolean {
            val known = embeddedState
            if (known >= 0) return known == 1
            val value: Boolean = try {
                // 首选**直连**：HostInfo 是真实类（两种构建都有，不是 stub），
                // 判据是宿主 APK 内是否有 assets/lspatch/config.json。
                io.github.ldxm666.mapadkiller.HostInfo.isEmbedded()
            } catch (t: Throwable) {
                // 兜底：万一 HostInfo 被挪走或改签名，再走一次反射。
                // ⚠ 2.3.0 修正：原来写的是 mapclean.App 的 "isEmbedded" —— 那个方法名不存在
                // （真名是 embedded()，见 App.java），所以这条兜底从写上那天起就恒返回 false。
                // 一旦 HostInfo 真的缺席，内置版会被误判成模块版 ⇒ 悬浮球默认关 ⇒ 用户失去唯一入口。
                reflectBool("io.github.ldxm666.mapadkiller.HostInfo", "isEmbedded")
                        || reflectBool("io.github.ldxm666.mapclean.App", "embedded")
            }
            embeddedState = if (value) 1 else 0
            return value
        }

        /** 反射调用无参静态布尔方法；类或方法不存在一律返回 false，不抛。 */
        private fun reflectBool(className: String, method: String): Boolean {
            try {
                val r = Class.forName(className).getMethod(method).invoke(null)
                return r is Boolean && r
            } catch (ignored: Throwable) {
                return false
            }
        }

        /** -1 = 未判定, 0 = 模块版, 1 = 内置版。判定只做一次。 */
        @Volatile
        private var embeddedState = -1

        /** 悬浮球的**默认**开关：内置版 = 开；模块版 = 关。显式写过的值永远优先。 */
        @JvmStatic
        fun floatDefault(): Boolean {
            return isEmbedded()
        }

        /**
         * 悬浮窗总开关。默认值按 [floatDefault] 分流，判定只在这一层做。
         *
         * <p>读配置优先：老用户若已显式写过 `float_enabled=true`，模块版也照样挂球。
         */
        @JvmStatic
        fun floatEnabled(): Boolean {
            // The overlay is host-local UI, not a vendor hook flag. Prefer the last
            // committed user choice even while a vendor backend is still warming up.
            mirrorGet()?.let { return it }
            // ① 远程/面板通道（正常情况）
            try {
                if (hasStored(K_FLOAT_ENABLED)) {
                    // 只有存储里**真的存在**这个键才采信并回写镜像。
                    // 以前这里无条件 mirrorPut，一旦 read 因后端未就绪而回落默认值，
                    // 镜像就被写成 true —— 用户的选择被自己的兜底机制覆盖掉。
                    val v = when {
                        Vendor.tmap() -> TmapPanel.read(K_FLOAT_ENABLED)
                        Vendor.baidu() -> BaiduPanel.read(K_FLOAT_ENABLED)
                        else -> AmapPanel.read(K_FLOAT_ENABLED)
                    }
                    mirrorPut(v)
                    return v
                }
            } catch (ignored: Throwable) {
            }
            // ② 启动早期框架服务还没绑定 ⇒ hasStored 读不到，会误判成"从未写过"从而回落到默认值
            //    （默认 true）⇒ 用户明明关掉了，重开 App 球又出现。用本地镜像兜底。
            val m = mirrorGet()
            if (m != null) return m
            // ③ 真正从未写过
            return floatDefault()
        }

        // ── 本地镜像：把「用户最后一次显式选择」记在宿主进程自己的 SharedPreferences 里 ──
        // 远程通道（LSPatch/LSPosed 的 getRemotePreferences）在进程刚起来时可能还不可用，
        // 那一刻读不到值就会回落到默认值，导致「关掉的悬浮窗重开 App 又出现」。
        private const val MIRROR_PREFS = "mapclean_float_mirror"
        private const val MIRROR_KEY = "float_enabled"

        private fun mirror(): SharedPreferences? {
            try {
                val c = HostPrefs.hostContext() ?: App.ctx() ?: return null
                if (!EmbeddedSettingsPolicy.isHostPackage(c.packageName) &&
                    c.packageName != "io.github.ldxm666.mapadkiller") return null
                return c.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
            } catch (t: Throwable) {
                return null
            }
        }

        private fun mirrorGet(): Boolean? {
            try {
                val p = mirror()
                if (p == null || !p.contains(MIRROR_KEY)) return null
                return p.getBoolean(MIRROR_KEY, true)
            } catch (t: Throwable) {
                return null
            }
        }

        private fun mirrorPut(value: Boolean): Boolean {
            return try {
                val p = mirror()
                p != null && p.edit().putBoolean(MIRROR_KEY, value).commit()
            } catch (ignored: Throwable) {
                false
            }
        }

        /**
         * True when the key has an <b>explicit</b> stored value.
         *
         * <p>`Config.visible()` hides that distinction behind `defaultVisible`, and the
         * mode-aware default for `float_enabled` only applies to "never written". 百度/腾讯
         * 变体返回 true（按已设置处理），不改变它们原有行为 —— 只有高德变体会被构建。
         */
        private fun hasStored(key: String): Boolean {
            // 这里曾经是 `if (Vendor.tmap() || Vendor.baidu()) return true` ——
            // 一句"不改变原有行为"的保守写法，实际造出了一个用户能天天碰到的 bug：
            //
            //   floatEnabled() 的第一分支是 `if (hasStored(...)) { read(); return }`。
            //   hasStored 恒 true ⇒ 永远走这条 ⇒ read() 读不到就回落 floatDefault()
            //   ⇒ isEmbedded() = true ⇒ **用户关掉的悬浮球，重启后必然回来**。
            //   而且那一分支还会 mirrorPut(true)，把用户的选择从本地镜像里也抹掉，
            //   连兜底路径一起污染。
            //
            // 现在三个厂商都真问一次存储。异常时返回 false 而不是 true：读不到就当没写过，
            // 让调用方退到本地镜像（那才是用户最后一次显式选择），而不是退到默认值。
            return try {
                when {
                    Vendor.tmap() -> TmapPanel.hasStored(key)
                    Vendor.baidu() -> BaiduPanel.hasStored(key)
                    else -> AmapPanel.hasStored(key)
                }
            } catch (t: Throwable) {
                false
            }
        }

        /** No writable path at all (neither the service nor the module's own preferences). */
        const val WRITE_NONE = 0

        /** No framework service, but `Config` has a local backend -- writable in-process. */
        const val WRITE_PREFS = 1

        /** The framework service is bound: writable in every build. */
        const val WRITE_SVC = 2

        /**
         * Which write path is usable right now, for the panel's status pill.
         *
         * <p>Three states instead of a boolean: 「只读 · 服务未连接」 used to be shown even when the
         * in-process preferences were perfectly writable, which told the user their tap could not
         * work when it could (and the other way round).
         */
        @JvmStatic
        fun writePath(): Int {
            try {
                if (Vendor.tmap() || Vendor.baidu()) {
                    // ★ 2.3.0：**先实测再下结论**。内置版（LSPatch）里服务通道常常是通的，
                    //   但那条通道读的是只读代理；真正能落盘的是宿主本地 prefs。
                    //   所以顺序改成：本地实测写得进 ⇒ LOCAL；否则服务在 ⇒ REMOTE；都没有 ⇒ NONE。
                    //   反过来（先看 service）就会把一份完全能写的配置标成「○ 只读」——
                    //   真机日志：`event=svc_null where=status path=0` 出现在 Cfg 明明能写的时候。
                    if (probeVendorPrefs()) return WRITE_PREFS
                    if (App.service() != null) return WRITE_SVC
                    return WRITE_NONE
                }
                return AmapPanel.writePath()
            } catch (t: Throwable) {
                return WRITE_NONE
            }
        }

        /** -1 未探测 / 0 写不进 / 1 可写。**只缓存成功**（见 [probeVendorPrefs]）。 */
        @Volatile
        private var vendorPrefsProbe = -1

        /**
         * 用一次真实写入判定厂商的偏好存储能不能写。
         *
         * <p>走反射是因为 `bmapclean.Cfg` / `tmapclean.Cfg` 在别的变体里整个包都被裁掉，
         * 编译期不能直接引用。写一个临时键、立刻删掉，不污染配置。
         *
         * <p>★ 2.3.0 的两处修正：
         * <ol>
         *   <li><b>只缓存成功</b>。原来失败也写进 [vendorPrefsProbe] 并永久生效 ——
         *       只要第一次探测恰好赶在「框架服务还没绑上 / 宿主 Context 还没就绪」的那一刻，
         *       整个进程就恒判 [WRITE_NONE]，面板从此死死显示「只读」。
         *       这正是用户说的「第二次打开一直只读」。</li>
         *   <li><b>探测对象换成 `Cfg.prefs()` 的解析结果</b>。它现在在内置版里返回宿主的
         *       本地 SharedPreferences（永远可写），不再是那个 `commit()` 必抛
         *       `UnsupportedOperationException` 的只读代理。</li>
         * </ol>
         */
        private fun probeVendorPrefs(): Boolean {
            if (vendorPrefsProbe == 1) return true
            var ok = false
            var sp: Any? = null
            var cls: String? = null
            try {
                cls = if (Vendor.baidu()) "io.github.ldxm666.bmapclean.Cfg"
                else if (Vendor.tmap()) "io.github.ldxm666.tmapclean.Cfg" else null
                if (cls != null) {
                    sp = Class.forName(cls).getMethod("prefs").invoke(null)
                    if (sp is SharedPreferences) {
                        ok = HostPrefs.writable(sp)
                    }
                }
            } catch (t: Throwable) {
                Log.w(
                    TAG, "event=cfg_probe threw cls=" + cls + ": " + t.javaClass.simpleName
                            + ": " + t.message
                )
            }
            // 只在成功时定案；失败留给下次（面板每次 refreshStatus 都会再试一次）。
            if (ok) {
                vendorPrefsProbe = 1
            } else {
                // 失败必须留下证据：哪条后端、拿到的是什么对象、四条 Context 路各自给了什么。
                // 没有这三行，「写得进但显示只读」就永远只能靠猜。
                Log.w(
                    TAG, "event=cfg_probe fail cls=" + cls
                            + " tmap=" + Vendor.tmap() + " baidu=" + Vendor.baidu()
                            + " prefs=" + HostPrefs.describe(sp)
                            + " remembered=" + HostPrefs.hasRemembered()
                )
                val c = sp?.javaClass
                Log.w(TAG, "event=cfg_probe impl=" + (if (c == null) "null" else c.name))
                HostPrefs.diagnose("probe")
            }
            return ok
        }

        /** True when a writable path is currently available. */
        @JvmStatic
        fun writable(): Boolean {
            return writePath() > 0
        }

        /** No backend at all (mirrors `Config.BACKEND_NONE`). */
        const val BACKEND_NONE = io.github.ldxm666.mapadkiller.Config.BACKEND_NONE

        /** Local in-process backend: writable without any framework channel. */
        const val BACKEND_LOCAL = io.github.ldxm666.mapadkiller.Config.BACKEND_LOCAL

        /** Framework remote-preferences backend (service bound). */
        const val BACKEND_REMOTE = io.github.ldxm666.mapadkiller.Config.BACKEND_REMOTE

        /**
         * The configuration backend actually in use, after verification.
         *
         * <p>`NONE` is never reported on a static verdict alone -- the vendor panel probes it
         * with one real write first, because a stale NONE is exactly what told users 「只读」 while
         * the sheet was running inside the target app.
         */
        @JvmStatic
        fun backend(): Int {
            try {
                if (Vendor.tmap() || Vendor.baidu()) {
                    // 与 writePath() 同源、同顺序：实测能写 ⇒ 本地；服务在 ⇒ 远程；都没有 ⇒ NONE。
                    // 只认 service() 会把一份完全能写的配置显示成「○ 只读」
                    // （FloatingPanel 把这个分支直接印成「只读」）。
                    if (probeVendorPrefs()) return BACKEND_LOCAL
                    if (App.service() != null) return BACKEND_REMOTE
                    return BACKEND_NONE
                }
                return AmapPanel.effectiveBackend()
            } catch (t: Throwable) {
                return BACKEND_NONE
            }
        }

        /** Nothing written yet in this process. */
        const val W_UNTRIED = 0

        /** The last write landed. */
        const val W_OK = 1

        /** The last write was refused (permissions / IO / read-only proxy). */
        const val W_REFUSED = 2

        /**
         * Result of the last write attempt, so the header pill can stop claiming 「可写入」 while
         * every tap is being refused. Never polled -- it is set by the write itself.
         *
         * <p>★ 2.3.0：原来百度/腾讯分支是「`App.service() == null` 即 [W_REFUSED]」。
         * 内置版的服务通道本来就起不来，于是**连接完全正常也被判成「● 本地 · 写入被拒」**，
         * 而用户实际能写（本地后端）。现在只认 [vLastWrite] —— 由 [write]
         * 的真实 commit 结果更新，没写过就是 [W_UNTRIED]。
         */
        @JvmStatic
        fun lastWriteState(): Int {
            try {
                if (Vendor.tmap() || Vendor.baidu()) return vLastWrite
                return AmapPanel.lastWriteState()
            } catch (t: Throwable) {
                return W_UNTRIED
            }
        }

        /** 百度/腾讯分支最近一次真实写入的结果。[W_UNTRIED] = 本次进程还没写过。 */
        @Volatile
        private var vLastWrite = W_UNTRIED

        /** `svc_null` / `prefs_null` / `commit_false` / `io`. */
        @JvmStatic
        fun lastWriteReason(): String? {
            try {
                return if (Vendor.tmap() || Vendor.baidu()) null else AmapPanel.lastWriteReason()
            } catch (t: Throwable) {
                return null
            }
        }

        /**
         * Read one switch. `float_enabled` is special: 悬浮窗是免 root 内置版专属，
         * 所以"从未写过"时用 [floatDefault] 而不是可见性表的 true。
         */
        @JvmStatic
        fun read(key: String): Boolean {
            if (K_FLOAT_ENABLED == key) return floatEnabled()
            if (Vendor.tmap()) return TmapPanel.read(key)
            return if (Vendor.baidu()) BaiduPanel.read(key) else AmapPanel.read(key)
        }

        @JvmStatic
        fun read(key: String, fallback: Boolean): Boolean {
            if (K_FLOAT_ENABLED == key) return floatEnabled()
            try {
                if (Vendor.tmap()) return TmapPanel.read(key)
                return if (Vendor.baidu()) BaiduPanel.read(key) else AmapPanel.read(key)
            } catch (t: Throwable) {
                return fallback
            }
        }

        /**
         * Read a switch whose "on" is the <b>literal</b> stored value rather than the visibility
         * table -- today only `labels_on`, whose consumer reads `Config.selected()`.
         *
         * <p>Without this the row would hydrate from [read], which resolves
         * a missing key through `defaultVisible` and answers `true`: the switch would
         * show ON while the native label filter is actually off (its default).
         */
        @JvmStatic
        fun readStrict(key: String): Boolean {
            try {
                if (Vendor.tmap() || Vendor.baidu()) return read(key, false)
                return AmapPanel.readStrict(key)
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * Persist one switch. The vendor panel uses `commit()` rather than `apply()`:
         * the user may leave the map immediately after flipping a switch, and an async write
         * would be lost.
         */
        @JvmStatic
        fun write(key: String, value: Boolean): Boolean {
            if (key == K_FLOAT_ENABLED && isEmbedded()) {
                // A successful write must survive a cold start. Vendor read-only
                // Binder snapshots must never overwrite this host-local switch.
                val saved = mirrorPut(value)
                if (!saved) return false
                try {
                    when {
                        Vendor.tmap() -> TmapPanel.write(key, value)
                        Vendor.baidu() -> BaiduPanel.write(key, value)
                        else -> AmapPanel.write(key, value)
                    }
                } catch (ignored: Throwable) { }
                invalidate()
                vLastWrite = W_OK
                FloatingController.setEnabled(value)
                Log.i(TAG, "event=float_saved value=$value source=host_local")
                return true
            }
            val ok: Boolean
            if (Vendor.tmap()) ok = TmapPanel.write(key, value)
            else ok = if (Vendor.baidu()) BaiduPanel.write(key, value) else AmapPanel.write(key, value)
            // 记下真实结果：药丸（FloatingPanel）据此显示「● 本地 · 写入被拒」，而不是像以前那样
            // 拿「服务有没有绑上」当写入成败的替身 —— 内置版服务恒 null，会一直谎报被拒。
            if (Vendor.tmap() || Vendor.baidu()) vLastWrite = if (ok) W_OK else W_REFUSED
            // The 悬浮窗 key is the one switch whose "apply" step is not a vendor action but the
            // controller itself: without this, flipping it in the sheet would only take effect on
            // the next resume, which reads as "the switch does nothing".
            //
            // Applied only when the write landed: the vendor panels' own readers cache the same
            // snapshot, so acting on a failed write would make the UI and the hook disagree.
            if (ok && K_FLOAT_ENABLED == key) {
                // 记住用户的显式选择：下次进程启动时远程通道可能还没就绪，靠它兜底，
                // 否则「关掉的悬浮窗重开 App 又出现」（float_enabled 读不到就回落到默认 true）。
                mirrorPut(value)
                try {
                    FloatingController.setEnabled(value)
                } catch (t: Throwable) {
                    Log.w(TAG, "float toggle failed: " + t.javaClass.simpleName)
                }
            }
            return ok
        }

        /** Drop the cached snapshot after a write so the panel and the hooks agree at once. */
        @JvmStatic
        fun invalidate() {
            // 后端可能刚变过（服务迟绑 / 宿主 Context 就绪）⇒ 允许重新探测，别让旧的失败结论卡住。
            vendorPrefsProbe = -1
            if (Vendor.tmap()) TmapPanel.invalidate()
            else if (Vendor.baidu()) BaiduPanel.invalidate()
            else AmapPanel.invalidate()
        }

        private const val TAG = "MapCleanFloat"

        /**
         * One line per process: this catch used to be silent, which hid a dead service for a
         * release.
         */
        private val gateReported = AtomicBoolean()

        /** One line per process the first time the panel finds no service at all. */
        private val svcNullReported = AtomicBoolean()

        /**
         * Best-effort recovery before a write is reported as failed.
         *
         * <p>The service binds asynchronously, and the sheet can be built before it lands. Running
         * the attach path here registers the delivery listener (it is idempotent), so the next tap
         * succeeds instead of the user concluding the module is read-only.
         */
        @JvmStatic
        fun ensureServiceFrom(context: Context?) {
            if (context == null) return
            try {
                val app = context.applicationContext
                if (app is android.app.Application) ensureService(app)
            } catch (ignored: Throwable) {
                // Nothing to recover with; the caller falls back to the failure toast.
            }
        }

        /** One-shot diagnostic: the panel found neither the service nor in-process preferences. */
        @JvmStatic
        fun reportNoWritePath(where: String) {
            if (svcNullReported.compareAndSet(false, true)) {
                Log.w(TAG, "event=svc_null where=" + where + " path=0")
            }
        }

        /**
         * The embedded service binds asynchronously, and it can be ready long before the user
         * ever opens the panel. Make sure the host Application is attached to the module's
         * static holders so `App.svc()` has somewhere to land.
         */
        @JvmStatic
        fun ensureService(application: android.app.Application?) {
            if (application == null) return
            try {
                App.attachEmbedded(application)
            } catch (t: Throwable) {
                // Not an LSPatch host (plain LSPosed) -- the hook-side path still works.
                //
                // Never swallow this silently: `attachEmbedded` registers the framework
                // service listener on its last line, so an exception here means this process has
                // *no* delivery path at all -- `App.svc()` stays null and every vendor
                // holder's panel reads 只读. That is exactly how the 腾讯 build shipped with a
                // dead service while 百度 worked: the gate was a vendor allow-list that did not
                // name com.tencent.map. Logged once per process, with the stack, so the next
                // occurrence is a one-line diagnosis instead of a log hunt.
                if (gateReported.compareAndSet(false, true)) {
                    Log.w(
                        TAG, "embedded service gate closed pkg="
                                + application.packageName, t
                    )
                }
            }
        }
    }
}
